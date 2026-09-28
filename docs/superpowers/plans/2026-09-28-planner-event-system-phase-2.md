# Planner Event System Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One component decides *whether and how* each event may wake the
planner (the attention policy). One component decides *when* the planner wakes
(the wake scheduler). The judgement part of the policy runs as a sandboxed
GraalJS rule module behind a fixed Java constitution. Agent behaviour stays
the same, except for a short list of timing changes. Each timing change is
its own commit with a reviewed golden diff.

**Architecture:** Four slices, plus one small piece of prerequisite work.
- **Slice 0: evaluation stall cutoff.** A scenario that stops making progress
  ends as `STALLED` instead of hanging the batch.
- **2a: one decision point (synchronous Java).** The attention decisions now
  spread across routing profiles, planner rules, trigger factories and
  default rules (G1–G3) move into a `ReferenceAttentionPolicy`, called
  where they run today. Every decision is recorded in an
  `AttentionDecisionLog`. The goldens stay identical.
- **2b: one scheduler.** `WakeScheduler` takes over the wake paths one at a
  time:
  - the task-wakeup deque (W2/W3);
  - the idle hook: goal continuation (W4), idle think (W5) and delegation
    start (W6);
  - routed event triggers (W1) and evaluation seeds (W7);
  - the tool-queue review (W9);
  - finally, one dispatch point per tick.

  Each path is its own commit.
- **2c: rules in GraalJS.** A `RuleEngine` runs the bundled `attention/default.js`
  on a worker thread. Its decisions apply on the next tick. The Java reference
  policy stays as both the differential oracle and the fallback.
- **2d: presentation and tooling.** Trigger prose moves into a
  `WakePresenter`, and a dashboard panel and a wake replay harness are added.

**Tech Stack:** Java 21 on the JBR 21 toolchain, GraalJS 25.0.4
(interpreter-only), JUnit 5, Gson, and the Phase 0/1 characterization suite
(wake goldens, defect probes, event catalog), the wake ledger and the
evaluation harness.

**Spec:** [`specs/2026-09-26-planner-perception-and-wake-design.md`](../specs/2026-09-26-planner-perception-and-wake-design.md),
sections 4.6, 4.7, 4.9, 4.10, 4.11, 4.12 and 6. Terms such as W1–W9,
G1–G11, D1–D8, Stage A/B/C and O1–O13 refer to that document. **Previous
phase:** [`plans/2026-09-27-planner-event-system-phase-1.md`](2026-09-27-planner-event-system-phase-1.md).

## Status (2026-09-28)

Implemented on `claude/hopeful-gauss-1gjr8v`: slice 0 and Tasks 2–9 and 12–18,
with the revisions recorded below. `./gradlew build` passes (1,821 tests,
0 failures). The evaluator, Python and dashboard suites pass. A 200× repeat of
the 20 golden scenarios (4,000 runs) is stable.

- **Golden diffs.** Only the D7 commit changed goldens. The idle-think wake in
  `idle_think_after_delay` and `tick_debug_pause_then_resume` moved from tick 2
  to tick 601. Stage B through the rule engine changed none, because the rules
  run synchronously once warm and decide exactly like the reference
  (Q2 revision).
- **Not done here.**
  - Task 6 Step 2 (D4) moves to Phase 3.
  - Task 9 Step 2 (W9) and Task 11 (coalescing) move to Phase 5.
  - Task 10 is folded into Task 14 (see the revisions).
  - Task 9 Step 3 is not done: `DialogueRuntime.onPlannerTrigger` remains as
    the entry point, but it only calls `WakeScheduler.offerTrigger`.
- **Live smoke and replay (2026-09-28).** Both were done without a planner
  model: a dev client ran under Xvfb and was driven through `agent tools call`.
  See the [Phase 2 check](../../experiments/2026-09-28-planner-event-system-phase-2-check.md).
  Replay of two recorded runs (11 and 48 replayed decisions) found 0
  differences.
- **Waiting on the user.** The paired A/B (Q3) needs a planner model.

---

## Decisions

These were settled on 2026-09-28, before implementation.

| # | Question | Decision | Why |
|---|---|---|---|
| Q1 | One Phase 2 PR, or slices? | **Slices 0, 2a, 2b, 2c, 2d**, each mergeable on its own. Inside 2b, each wake path is its own commit. | Phase 2 is the first phase where timing changes. Slicing ties every golden diff to a single named cause. |
| Q2 | Do Stage-B rules run synchronously on the client thread, or on a worker thread with next-tick delivery? | **A worker thread with next-tick delivery**, as in the spec (4.9, 4.12). `ReferenceAttentionPolicy` decides whenever the engine can't: while the context is cold, when a step fails or misses its deadline, and for events beyond the input cap. | The spike measured a cold step of about 80 ms and a context build of about 410 ms. Phase 6 lets the planner author rules. Neither may run on the game thread. Using the Java reference as the fallback also covers cold start, and it keeps the cost of the Java reference justified after the port. |
| Q3 | What is the evaluation gate? | **A paired A/B in one session.** Run all nine scenarios, three runs per arm, with control = `dev` before the slice and treatment = the slice head. The frozen Phase 0 intervals are only a reference. | In Phase 1 the control arm itself fell outside the frozen intervals, so those bounds are tighter than run-to-run noise. |
| Q4 | Which defects are fixed in Phase 2? | **D7** (idle time counted in wall-clock milliseconds; it moves to ticks with the idle hook) and **D4**. For D4, satisfaction replaces `hasIncorporatedDecisionEvent`, which is equivalent for single-reference wakes. **Not** D1/D2/D3/D8. | D8's merge-not-replace and D1–D3 change what the model sees, which is Phase 3. The prose triggers keep today's replace-on-same-key coalescing in Phase 2. |
| Q5 | What happens to the wake-audit vocabulary? | **The `planner_wake` path labels W1–W9 and gate ids stay stable.** When a path moves into the scheduler, it keeps its W label. Attention decisions go to a new, separate log. | The goldens' `wakeAudit` and the wake ledger key on these labels. Renaming them would bury real changes under label churn. |
| Q6 | What is in the Stage A constitution in Phase 2? | **Protected decisions** (`DIRECT`: addressed, local-controller and evaluation chat; `CRITICAL`: `reflex.resolved`) **and evaluation suppression.** The system gates (disabled, degraded, external driver) stay enforced in `DialogueRuntime.submitPlannerTrigger` (G8). | The degraded gate has side effects today: the `onPlannerDegradedBlocked` transition and its visible reply. It stays with the dialogue. Stage A records the gate outcome for the log without enforcing it. |
| Q7 | Where does G4 (blocked goal; accepted or queued work versus craft, pickup and idle think) live? | **In `DialogueRuntime` during 2a; in the scheduler's admission from 2b Task 9 on,** once every path reaches it. | Today G4 also filters W4, W5 and W7. Moving it before all paths use the scheduler would duplicate it. |
| Q8 | Where do the engine and bundled rules live? | **The engine in `src/main` (`ai.moeru.airicraft.rules`, no Minecraft dependencies), bundled modules in `src/main/resources/airicraft/rules/`, and an optional override at `config/airicraft/rules/attention.js`.** An invalid override **fails `airicraft reload`** with `invalid_config` and keeps the running runtime. At startup it falls back to the bundled module with a warning. | This matches how config files behave today. A typo in a rule must never leave the agent unable to wake (O13). |
| Q9 | What happens to `update_event_policy`? | **The schema is unchanged.** `EventPolicyState` stays the store. From 2c it is passed to the rule as the `plannerRules` table. The host still records rule matches (`matchCount`, `lastMatchedAt`) from the `ruleId` in the returned decision. | Frozen tool schema (4.11). Existing debug views of rule matches keep working. |

## Revisions during implementation (2026-09-28)

- **Task 10 is folded into Task 14.** Moving `IMMEDIATE` delivery to one
  end-of-tick dispatch would add up to 50 ms to chat and safety wakes and
  reorder deliveries without fixing a characterized defect. After Task 14,
  autonomous Stage-B wakes are delivered at the rules phase, a fixed point in
  the tick. `DIRECT` and `CRITICAL` wakes stay immediate, as Stage A requires.
  This leaves one reviewed timing change instead of two.
- **Task 11 (coalescing) and the W9 move go to Phase 5.** The coalesce window
  belongs to the orchestrator's supersede mechanism. The spec places the
  supersede budget in the scheduler in Phase 5, so the two move together
  there. W9 is the tool queue's own continuation (G8 session mechanics) and
  already has its audit label.
- **W6 (delegation start) stays in `DialogueRuntime.poll`.** It fires as soon
  as a delegation starts, not when the agent is idle, so it is admitted
  through `WakeScheduler.offerTrigger` with the other trigger wakes rather
  than run from the idle hook.
- **D4 is not flipped in Phase 2.** `hasIncorporatedDecisionEvent` is already
  a cursor comparison, so satisfaction is identical for single-reference
  wakes. Collapsing the W1+W2 double path needs W1 wakes to carry event
  references into the scheduler, which is Phase 3 work.
- **The Stage C clamp compares against the reference.** Today's bundled
  behaviour already sets some protected types to `NONE`: `player.physical`
  while the reflex owns actuation, and non-failed graph terminals. So the
  clamp rule is: *a protected type may not be set to `NONE` by rules when the
  Java reference would wake it*. Delays remain allowed.
- **Q2 is revised: the rules run on the tick thread once warm, not on a
  worker thread.** The engine is built off-thread. Once it is warm, steps run
  synchronously on the routing thread, and each step is bounded by the
  statement limit. The engine is interpreter-only, so "warm" must include
  running steps, not just loading. The live smoke measured a 671 ms first
  step, and a microbenchmark measured a 167 ms first step and 2.5 ms early
  steps. `RuleEngine` therefore runs 600 synthetic steps off-thread before it
  reports ready. After that, benchmark steps have a median of 0.27–0.46 ms, a
  p99 of about 4 ms and a first step of 6 ms. The live client, on software
  rendering, peaked at 17–19 ms. While the engine
  is cold, or when a step fails, the Java reference decides. That fallback is
  audited as stage `FALLBACK`. The bundled module decides identically to the
  reference: `RuleDifferentialTest` checks 5,000 generated cases. So routing
  through the rules changes no wake, and Task 14 needs neither the one-tick
  delay nor a golden diff. A worker thread would have added a tick of
  latency to every autonomous wake without gaining any isolation that the
  sandbox and the statement limit do not already provide.
- **Task 15: startup does not validate an override.** Validating would block
  client start for the engine warm-up. Instead, a broken override fails on
  the first routed event, and the policy reverts to the bundled module with
  `rules.reverted` (reason `load_failed`) and a log warning. Reload validates
  strictly.

## Ground rules

1. **The goldens change only in named commits.** After every task, run:
   ```bash
   ./gradlew --no-daemon :test --tests 'ai.moeru.airicraft.agent.Wake*' \
     --tests 'ai.moeru.airicraft.agent.dialogue.DialogueWake*' --rerun
   git diff --exit-code src/test/resources/planner/wakes
   ```
   Only the commits marked **[golden diff]** below regenerate them with
   `AIRICRAFT_UPDATE_WAKE_GOLDENS=1`. Each such commit message lists every
   changed scenario and explains why it changed.
2. **The harness stays deterministic.** Every asynchronous boundary the
   runtime polls on a tick is released only by `WakeScenarioHarness.settle()`:
   - the planner backend gate (Phase 0);
   - action-graph resolution (Phase 1);
   - from 2c, the rule engine.

   A new asynchronous path without this treatment is a flake waiting to
   happen.
3. **Defect probes change only on purpose.** `d7_…` and `d4_…` flip to
   `…_FIXED_…` in their own commits. The other probes must stay as they are.
4. **Prerequisites** are as in `AGENTS.md`. Run root tests with `:test`.
5. **Branch.** Work on `claude/hopeful-gauss-1gjr8v`, restarted from `dev`,
   with one commit series per slice. After each slice, run the full root
   suite and push.

## File Structure

### New files (main)

- `src/client/java/ai/moeru/airicraft/agent/attention/`:
  - `Urgency.java`, `Delivery.java`, `AttentionStage.java`
  - `WakeDecision.java`: delivery, urgency, stage, rule id, reason, emitSemantic
  - `AttentionState.java`: an immutable snapshot for one decision
  - `AttentionEvidence.java`: per-event facts the rules can't compute, such as chat distance and reset commands
  - `AttentionDecision.java`, `AttentionDecisionLog.java`: bounded (512) and exported to the dashboard and bridge
  - `ReferenceAttentionPolicy.java`: Stage A, the Java reference for Stage B, and the Stage C clamp
  - `Wake.java`, `WakeScheduler.java`, `IdleHook.java`: the scheduler (2b)
  - `WakePresenter.java`: trigger prose keyed by event type (2d)
- `src/main/java/ai/moeru/airicraft/rules/RuleEngine.java`, `RuleModule.java`, `RuleStepResult.java` (2c)
- `src/main/resources/airicraft/rules/kernel.js`, `lib.js`, `attention/default.js` (2c)
- `docs/attention-rules.md`: the rule contract, library and constitution (2c)

### New files (test)

- `src/test/java/ai/moeru/airicraft/agent/attention/*Test.java`: policy tables, log, scheduler timing, idle hook
- `src/test/java/ai/moeru/airicraft/rules/RuleEngineTest.java`: determinism, failure paths, caps, revert
- `src/test/java/ai/moeru/airicraft/agent/attention/RuleDifferentialTest.java`: JS default decides exactly like the Java reference
- `src/test/java/ai/moeru/airicraft/agent/attention/AttentionReplayTest.java` and fixture (2d)

### Modified files

- `EmbodiedAgentRuntime.java`:
  - trigger factories keep only payload checks and prose;
  - their attention early-returns move to the policy;
  - the idle trigger, evaluation-seed and drain paths feed the scheduler;
  - the tick gains a dispatch phase.
- `AgentEventPipeline.java`: asks the policy, then records the decision
- `DialogueRuntime.java`: loses `pendingTaskWakeups`, the G5 loop, `continuePlannerGoal` scheduling and the delegation start wake; gains `deliver(WakeBatch)`
- `PlannerOrchestrator.java`: W9 asks the scheduler; the supersede-armed coalesce window moves to the scheduler (2b Task 11)
- `IdleIdeaScheduler.java`: becomes the idle-think generator, counting ticks (D7)
- `addons/evaluator/**`: the `maxStallTicks` budget and `STALLED` status (Slice 0)
- the dashboard (`app.js`, `index.html`), the bridge debug state, `EventCatalog` (the new `rules.*` events)

### Read before editing

- Spec 4.6–4.12, and D4/D7 in 2.6.
- `EmbodiedAgentRuntime.java`:
  - `tickClient` (588);
  - `drainEventPipeline` (4591);
  - `maybeFireIdleIdeaTrigger` (4612);
  - `createPlannerTrigger` and the factories (4658–5040);
  - `resolveDefaultEventPolicy` (4832);
  - `applyPlannerEventPolicyUpsert` (5073);
  - `emitEvaluationTrigger` (5748);
  - `fireIdleIdeaTriggerManually` (1826).
- `DialogueRuntime.java`:
  - `continuePlannerGoal` (255);
  - `onPlannerTrigger` (545);
  - `poll` (633);
  - `submitPlannerTrigger` (793/802);
  - `queueTaskAttention`/`queueTaskWakeup` (853/860);
  - `submitNextPendingInternalTaskUpdate` (864).
- `PlannerOrchestrator.java`: `hasIncorporatedDecisionEvent` (369), `submit` (498), `tickToolQueue` (1012), `armCoalesceWindow` (1950). `PlannerContextReducer.enqueueTrigger` and `invalidateIdleThinkTriggers`.
- `GraalPolicyInvocation.java`, `RuleEngineSpikeTest` and `src/test/resources/rules-spike/`.

---

## Slice 0: evaluation stall cutoff

### Task 1: `maxStallTicks` and `STALLED`

**Files:** `EvaluationBudget.java`, `EvaluationStatus.java`, `ScenarioEvaluationRunner.java`, the scenario loader, `scripts/run-evaluation-scenarios`, and the evaluator tests.

- [x] **Step 1: The budget field.** Add `maxStallTicks` to `EvaluationBudget`.
  The default is 6,000 ticks (five minutes of game time); `0` disables it.
  The loader reads the optional field from `scenario.yml`.
- [x] **Step 2: The stall rule.** A scenario stalls when no planner turn has
  completed, no planner call is in flight, and no new agent event has been
  published for `maxStallTicks`. It then finishes as the new terminal status
  `STALLED`, with the stalled tick span in its message.
- [x] **Step 3:** The launcher and `wake_ledger.py` treat `STALLED` as a
  terminal non-pass. The batch summary counts it separately from `FAILED`.
- [x] **Step 4: Tests.**
  - the loader parses and defaults the new field;
  - the runner stalls with a fake context at exactly `maxStallTicks`;
  - an in-flight planner call or a new event resets the stall clock.

  Commit: `feat(eval): end scenarios that stop making progress as STALLED`.

## Slice 2a: one decision point

### Task 2: Attention types and the decision log

- [x] **Step 1: The types.**
  - `Urgency {CRITICAL, DIRECT, HIGH, NORMAL, LOW, SELF}` (ordered)
  - `Delivery {PREEMPT, IMMEDIATE, DEBOUNCE, NONE}`
  - `AttentionStage {CONSTITUTION, RULES, CLAMP, FALLBACK}`
  - `WakeDecision(Delivery delivery, Urgency urgency, AttentionStage stage, String ruleId, String reason, boolean emitSemantic)`
- [x] **Step 2: The log.** `AttentionDecision(long seqNo, long tick, String type, WakeDecision decision, String triggerType)`.
  - `AttentionDecisionLog` is a ring of 512 decisions with a `query(sinceSeqNo)`
    that has the same truncation semantics as the event log. It counts
    decisions by `ruleId`.
  - Expose it as `debugAttentionState()` (counts, the last 32 decisions) in
    the bridge `agent debug state` response and in the dashboard
    `decision_state`.
  - This is additive JSON only.
- [x] **Step 3: Tests.** Ring eviction, the truncation flag, and counts.
  Commit: `feat(attention): add wake decision types and a bounded decision log`.

### Task 3: `AttentionState` and `AttentionEvidence`

- [x] **Step 1: The state record.** `AttentionState` holds everything G1–G4
  read today, taken once per decision:
  - actuator owner: `reflex`, `safety_hold`, `policy`, `work` or `idle`
  - `reflexOwnsActuation`
  - active job type and whether it is terminal
  - `pendingCraftToolResult`
  - `proactiveSocialMode`
  - `evaluationSuppressed`
  - `plannerRules`: an immutable copy of `EventPolicyState`'s rules
- [x] **Step 2: The per-event evidence.** `AttentionEvidence` carries facts
  that depend on live world or chat parsing:
  - `senderWithinChatDistance` (today's `playerChatWithinConfiguredDistance`)
  - `resetCommand` (`DialogueRuntime.isResetCommand`)
  - `addressedToAgent` (`ChatIngestService.isAddressedToAgent`)

  The runtime computes it with the state. The rules (2c) receive it as data
  and never touch the world.
- [x] **Step 3: Tests.** A builder test with a fake runtime seam for each
  field. Commit: `feat(attention): snapshot attention state and per-event evidence`.

### Task 4: `ReferenceAttentionPolicy`

- [x] **Step 1: The decision function.**
  `decide(SemanticEvent, EventRoutingProfile, AttentionState, AttentionEvidence) → WakeDecision`.
  It reproduces today's decisions exactly:
  - **Stage A** (constitution):
    - `DIRECT` for addressed chat and local-controller chat, unless it is a
      reset command or (addressed only) out of chat distance;
    - `CRITICAL` for `reflex.resolved`;
    - `NONE` for the evaluation-suppressed types (today's
      `suppressAutonomousPlannerTriggerAfterEvaluation`).
  - **Stage B** (the reference for `default.js`):
    - the G1 routing profile;
    - G2 planner rules (last match wins), with `policyBypass` skipping them,
      then the default rule for pickups during a mining job;
    - the G3 attention checks: proactive social mode and chat distance for
      ambient chat and system messages; the collect-resource job (pickup,
      craft); a pending craft result (craft); reflex owns actuation
      (damage, physical).
  - **Stage C** (clamp): a policy-protected type can't get `NONE` from Stage B.
    Today no bundled rule does this. The clamp exists for authored rules
    (2c), and its unit test proves it.
- [x] **Step 2: Wire it in without changing timing.**
  - `AgentEventPipeline.route` asks the policy once per event, in place of
    today's G1/G2 blocks. The policy's `emitSemantic` and delivery decide
    what the pipeline does: whether to feed the planner buffer, whether to
    call the trigger factory, and the `policy.event_intervened` record
    (unchanged).
  - The trigger factories lose their attention early-returns: suppression,
    proactive social, distance, reset, collect-resource, pending craft and
    reflex ownership. They keep payload validation and prose.
  - Each decision goes to the `AttentionDecisionLog`. `recordEventRouting`
    timeline entries stay, as additive diagnostics.
- [x] **Step 3: A table-driven `ReferenceAttentionPolicyTest`.** One row per
  gate branch removed from the factories. Each row is the event, state and
  evidence, with the expected delivery and rule id. Each row names the old
  factory branch it replaces. `AgentEventPipelineTest` and `EmbodiedAgentRuntimeTest`
  must pass unchanged, and the goldens must be identical.
  Commit: `refactor(attention): decide routed events in one reference policy (no behaviour change)`.

## Slice 2b: one scheduler

Every task in this slice keeps today's dispatch points until Task 10, so
Tasks 5–9 are "no behaviour change" commits.

### Task 5: `Wake` and the scheduler skeleton

- [x] **Step 1: `Wake`.** It carries:
  - the path label (W1–W9);
  - urgency and delivery;
  - event references (sequence numbers in the raw log);
  - the coalescing key, guidance revision and mission id;
  - the `PlannerTrigger` it carries until Phase 3 removes prose;
  - an `attention` flag (W3 is placed ahead of the deque and bypasses the
    accepted-work hold).
- [x] **Step 2: `WakeScheduler`.** It is owned by `EmbodiedAgentRuntime` and
  delivers to `DialogueRuntime.deliver(WakeBatch)`. `deliver` replaces the
  public `onPlannerTrigger` path, which stays as a thin adapter until
  Task 9. Its pending set is bounded (64); on overflow it drops the lowest
  urgency and records the drop.
- [x] **Step 3: Tests.** Offer and drain order, the bound, and the drop
  record. Commit: `feat(attention): add the wake scheduler skeleton`.

### Task 6: Move W2/W3 into the scheduler

- [x] **Step 1: The move.**
  - `queueTaskWakeup`/`queueTaskAttention` become `scheduler.offer(Wake)`.
  - The G5 loop moves from `submitNextPendingInternalTaskUpdate` into
    `WakeScheduler.releaseTaskWakes`, called at the same two points in
    `DialogueRuntime.poll` and from `onInternalTaskUpdate`. It covers the
    external driver, in-flight, degraded/unconfigured clear, the `run_policy`
    hold, queued tool work, blocked-goal relevance and supersession.
  - The audit calls keep path W2/W3 and the same gate ids.
- [ ] **Step 2: Satisfaction (D4).** The `G5.incorporated` check becomes "the
  active role's incorporated cursor has passed every event reference of this
  wake". This is equivalent for today's single-reference wakes. Flip
  `d4_…` to document the absorbed double path, keeping the single request.
- [x] **Step 3:** Run the goldens (identical) and the defect probes.
  Commits: `refactor(attention): schedule task wakeups (no behaviour change)`
  and `test(wakes): d4 satisfied by the incorporated cursor`.

### Task 7: The idle hook (W4, W5, W6)

- [x] **Step 1: The generators.** `IdleHook` runs ordered generators when
  nothing wakeable is pending:
  1. delegation continuation or start;
  2. the safety-hold reminder (inside goal continuation today);
  3. goal continuation, with G6's twelve guards moved verbatim;
  4. idle think (`IdleIdeaScheduler`, G7's guards).

  It is invoked at today's two positions: `maybeFireIdleIdeaTrigger` in the
  tick, and the delegation start in `DialogueRuntime.poll`.
  `invalidateIdleThinkTriggers` becomes "any non-`SELF` offer drops pending
  `SELF` wakes"; the reducer call stays until Task 11. The goldens stay
  identical.
- [x] **Step 2 [golden diff]: D7.** Idle think counts idle **ticks** (an
  initial delay and cooldown of seconds × 20). Paused ticks don't count.
  - **Goldens:** only `idle_think_after_delay` and
    `tick_debug_pause_then_resume` may change. Paused wall-clock time no
    longer counts as idle.
  - **Probe:** flip `d7_CONFIRMED_…` to `d7_FIXED_…`.
- [x] Commits: `refactor(attention): run goal continuation, idle think and delegation start from the idle hook (no behaviour change)`
  and `fix(attention): measure idle time in agent ticks (fixes D7)`.

### Task 8: W1 and W7 through the scheduler

- [x] **Step 1:** `drainEventPipeline` offers routed triggers as W1 wakes,
  and `emitEvaluationTrigger` offers W7. With `IMMEDIATE` delivery, the
  scheduler dispatches inside `offer`, so the dispatch points don't change
  yet and the goldens stay identical.
  Commit: `refactor(attention): offer routed and evaluation wakes to the scheduler (no behaviour change)`.

### Task 9: G4 in scheduler admission, and W9

- [x] **Step 1: G4.** With every path now going through the scheduler, G4
  (the blocked goal, accepted work and queued tool work versus craft, pickup
  and idle think) moves from `DialogueRuntime.onPlannerTrigger` into the
  scheduler's admission step. The audit gate ids stay the same.
- [ ] **Step 2: W9.** `PlannerOrchestrator.tickToolQueue` asks the scheduler
  for its review wake instead of calling `submit` itself. The scheduler
  releases it immediately, in the same call. The audit keeps W9.
- [ ] **Step 3: Remove the adapter.** Drop the `onPlannerTrigger` adapter;
  `deliver` is the only way in. The goldens stay identical.
  Commit: `refactor(attention): admit every wake path through the scheduler (no behaviour change)`.

### Task 10 [golden diff]: One dispatch point per tick

- [ ] **Step 1: The dispatch phase.** `IMMEDIATE`/`PREEMPT` wakes no longer
  dispatch inside `offer`. They queue until a single `scheduler.dispatch()`
  at the end of `tickClient`.
  - That end-of-tick call is spec 4.9 step 7. It is the fix for problem 6.
  - Callbacks between ticks (chat, damage) publish immediately. Their wakes
    leave at the end of the next tick, with at most 50 ms of added latency.
  - A `DIRECT` wake still supersedes a replaceable in-flight turn through
    the orchestrator's existing mechanism.
- [ ] **Step 2: Review the goldens.** Regenerate them and review each diff.
  - **Expected:** request ticks move to the end of their tick, and
    same-tick wakes go out as one batch.
  - **Not expected:** a wake appearing, disappearing or changing text.
  - **The commit message** lists every scenario with its change.
- [ ] **Step 3: Paired A/B (Q3).** The user runs control = the Task 9 head
  and treatment = the Task 10 head, then records the result in the phase
  check note.
  Commit: `feat(attention): dispatch wakes once per tick`.

### Task 11: Coalescing in the scheduler

- [ ] **Step 1: The move.**
  - The orchestrator's supersede-armed coalesce window
    (`armCoalesceWindow`, `computeCoalesceWindowMs`) moves into the
    scheduler, counted in ticks.
  - The quiet gap, minimum age and maximum age come from today's
    `plannerSessionCoalesce*Millis` ÷ 50.
  - Same-key replacement stays (Q4). `PlannerContextReducer.invalidateIdleThinkTriggers`
    is removed; the idle hook's `SELF` drop covers it.
  - The goldens should be identical. If any tick rounding changes one, it
    becomes a **[golden diff]** commit with the rounding explained.

  Commit: `refactor(attention): coalesce wakes in the scheduler`.

## Slice 2c: rules in GraalJS

### Task 12: `RuleEngine`

- [x] **Step 1: The engine.** Package `ai.moeru.airicraft.rules` in `src/main`,
  reusing `GraalPolicyInvocation`'s sandbox options.
  - **Kernel:** one context per module, built on a daemon worker thread.
    The kernel is taken from the spike, with `Date` and `Math.random` fixed
    to the input tick and seed.
  - **Step contract:** `step(inputJson, stateJson) → {decisions, state}`.
- [x] **Step 2: Limits and failures.**
  - Each step gets 50,000 statements.
  - Input carries at most 20 Stage-B events per step. Events beyond that
    are decided by the fallback, marked `stage=FALLBACK, reason=input_cap`.
  - State is capped at 16 KiB. On overflow, the step fails and the previous
    state is kept.
  - A step that throws, exhausts its statements, returns an oversized state
    or returns malformed output publishes `rules.step_failed`. After three
    consecutive failures, the engine reverts to the bundled module and
    publishes `rules.reverted`. Both are new `INTERNAL`, `DIAGNOSTIC`
    catalog entries.
  - The hard cancellation ceiling is one second, as in the existing sandbox.
- [x] **Step 3: The async API.**
  - `submit(tick, input, state)` returns a handle.
  - `collect(handle)` returns the result if it is done; otherwise it returns
    `LATE`.
  - Pre-warm: `warm()` builds the context off-thread when the runtime is
    constructed. Until it is ready, `collect` reports `COLD`.
- [x] **Step 4: `RuleEngineTest`.**
  - determinism (same input and state give identical output);
  - the frozen clock and seed;
  - each failure path;
  - the input cap, state cap and revert after three failures;
  - cold and late reporting.

  Commit: `feat(rules): add a sandboxed attention rule engine`.

### Task 13: `lib.js` and `attention/default.js`

- [x] **Step 1: The modules.**
  - `lib.js`: `leakyBucket`, `slidingWindow`, `tumblingWindow`, `cooldown`,
    `hourlyCap`, `cluster`, `seededRandom`, taken from the spike.
  - `attention/default.js`: a port of `ReferenceAttentionPolicy`'s Stage B,
    reading `input.attention`, each event's evidence and `input.plannerRules`.
    No leaky bucket yet (that is Phase 5). Output decisions carry `ruleId`
    and `reason` values equal to the Java reference's.
- [x] **Step 2: `RuleDifferentialTest`.**
  - **Inputs:** the (event, state, evidence) inputs recorded by a policy
    decorator while every wake golden scenario runs, plus 5,000
    seeded-random states over every catalog type.
  - **Check:** the JS decisions (delivery, urgency, ruleId, emitSemantic)
    equal the Java reference's on every input.
  - **Replay:** replaying recorded input and state twice gives identical
    output.
- [x] Commit: `feat(rules): bundle the default attention rules`.

### Task 14 [golden diff]: Stage B from the engine, applied a tick later

- [x] **Step 1: The runtime wiring.**
  - **Stage A** stays synchronous in the pipeline.
  - **Stage-B events** each tick are collected into one step input. The
    input is submitted at the rules phase (spec 4.9 step 6), and the result
    is collected at the next tick's rules phase.
  - **Collected decisions** are clamped (Stage C), logged, and then routed:
    the semantic feed, the trigger factory and the scheduler offer.
  - **Late, cold or failed steps** are decided by `ReferenceAttentionPolicy`,
    marked `stage=FALLBACK`.
  - **Rule matches:** the host records `EventPolicyState` rule matches from
    the returned `ruleId` values (Q9).
- [x] **Step 2: Harness.** `WakeScenarioHarness` warms the engine in its
  constructor and releases submitted steps in `settle()` (ground rule 2), so
  every decision lands exactly one tick later.
- [x] **Step 3: Review the goldens.**
  - **Expected:** Stage-B W1 triggers move one tick later: pickup, craft,
    damage, item offer, physical, smelting, `task.blocked` and graph
    outcomes.
  - **Unchanged:** chat, `reflex.resolved` (Stage A) and the W2/W3/W9 paths.
- [ ] **Step 4:** The user runs the paired A/B.
  Commit: `feat(attention): decide stage-B events with the rule engine`.

### Task 15: Overrides and reload

- [x] **Step 1: The override file.** `config/airicraft/rules/attention.js`, if
  present, replaces the bundled module.
  - **Reload:** `airicraft reload` compiles it and runs one step against an
    empty input before accepting it. On failure it throws `invalid_config`
    naming the file and the guest error, and the old runtime keeps running.
  - **Startup:** a failure logs a warning and uses the bundled module.
- [x] **Step 2: Docs.** `docs/attention-rules.md` covers the step contract,
  input fields, `lib`, the constitution and clamp (what rules can't do), and
  the failure and revert behaviour. Link it from `AGENTS.md`.
- [x] **Step 3: Tests.**
  - a valid override takes effect;
  - an invalid one fails a strict load but falls back at startup;
  - a protected type set to `NONE` by an override is clamped and the clamp
    is logged.

  Commit: `feat(rules): load attention rule overrides on reload`.

## Slice 2d: presentation and tooling

### Task 16: `WakePresenter`

- [x] **Step 1:** The trigger prose moves out of `EmbodiedAgentRuntime` into
  `WakePresenter`, keyed by event type. This covers the pickup, craft,
  damage, item offer, physical, reflex resolved, smelting, task blocked,
  graph suspended/terminal, and ambient and system chat texts. The strings
  are byte-identical, and the goldens are identical.
  Commit: `refactor(attention): present wake prose from one place (no behaviour change)`.

### Task 17: The dashboard attention panel

- [x] **Step 1: The panel.** "Why did / didn't the planner wake" shows:
  - the latest decisions, filterable by event type and rule id;
  - the scheduler's pending wakes;
  - the rule engine's status: module source (bundled or override), state
    size, last failure, and cold/late/fallback counters.

  It uses only the existing read-only observation stream (4.10). There are
  no new mutation routes.
- [x] Commit: `feat(dashboard): show attention decisions and wake scheduling`.

### Task 18: The wake replay harness

- [x] **Step 1: `AttentionReplay`.** It reads a recorder run directory: the
  events, the debug timeline, and the attention state recorded with each
  decision (additive in Task 2). It re-decides every event with both the
  Java reference and the JS engine, and writes
  `attention-replay.json`: per event, the recorded, reference and rule
  decisions and whether they differ.
- [x] **Step 2: The CLI.** `python3 scripts/wake_ledger.py replay-summary <run>` summarizes the diffs.
- [x] **Step 3:** A test on a synthetic fixture run.
  Commit: `feat(attention): replay recorded runs through the attention policy`.

### Task 19: Verification and docs

- [x] **Step 1: Build and characterization.** `./gradlew build`, the wake
  goldens (changed only in the named commits), the defect probes, the Python
  suite, and a 200× repeat of the golden scenarios (ground rule 2).
- [x] **Step 2: Live smoke.** Run `runClient`, then check:
  - the dashboard panel shows decisions;
  - an override rule loaded through `airicraft reload` takes effect;
  - a deliberately broken override is rejected with `invalid_config`;
  - the rule engine's counters show no fallback after warm-up.
- [ ] **Step 3: Paired A/B and replay.** The user runs the final paired A/B
  (Q3). The replay harness runs on at least two recorded playtests, with
  zero unexplained reference-versus-rule differences.
- [x] **Step 4: Docs.**
  - the spec: the Phase 2 checklist and the D4/D7 rows;
  - this plan's status;
  - `AGENTS.md` (key files, behaviour notes on rules and reload);
  - `CONTEXT.md` (wake batch and scheduler terms, if they need refining).

  Commit: `docs: record Phase 2 verification`.

## Exit criteria

- [ ] `./gradlew build` is green. The wake goldens changed only in the
  **[golden diff]** commits (Tasks 7, 10 and 14, and 11 if needed), each
  with a reviewed explanation. A 200× repeat of the golden scenarios is
  stable.
- [ ] `EmbodiedAgentRuntime` no longer holds attention checks in trigger
  factories, suppression helpers or idle wake scheduling. `DialogueRuntime`
  no longer owns `pendingTaskWakeups`, the G5 loop, goal continuation
  scheduling or the delegation start wake. The orchestrator no longer owns
  the coalesce window.
- [ ] The JS default rules decide exactly like `ReferenceAttentionPolicy` on
  all recorded golden inputs and 5,000 random states. Replay is
  deterministic.
- [ ] D4 and D7 probes are flipped to `FIXED`. D1, D2, D3, D5, D6 and D8
  probes are unchanged.
- [ ] Every Stage-B decision is in the attention log with a rule id, and the
  dashboard shows it.
- [ ] **Paired A/B** (nine scenarios, three runs per arm), for every scenario:
  - pass/fail is no worse than the control arm's worst run;
  - the median planner-request rate (wall-clock and per 1,200 ticks) is
    within ×0.75–×1.33 of the control median;
  - there are no new failure classes and no new `STALLED` outcomes beyond
    the control's.
- [ ] Replay of at least two recorded playtests shows no unexplained
  decision differences.

## Risks

- **Timing changes in Tasks 10 and 14 change model behaviour.** Mitigations:
  - each is isolated in one commit with a golden review and its own A/B;
  - chat and reflex resolution stay synchronous (Stage A), so user-facing
    latency grows by at most one tick.
- **New asynchronous paths make the harness flaky** (as happened in Phase 0
  and Phase 1). Mitigation: ground rule 2, and the 200× repeat in the exit
  criteria.
- **The Graal context never warms in some environments** (interpreter-only
  JBR, slow disks). Mitigations:
  - the Java reference decides while the engine is cold;
  - the dashboard and bridge show cold, late and fallback counters;
  - Stage A never waits.
- **Merge conflicts in `EmbodiedAgentRuntime` and `DialogueRuntime`.**
  Mitigation: small commits, and pushing each slice as soon as it is green.
- **Scope.** Phase 2 is the largest phase. If a slice's A/B fails, the
  earlier slices still stand on their own. 2a and the no-behaviour-change
  part of 2b (Tasks 5–9) are worth merging even if Tasks 10–14 need
  rework.
