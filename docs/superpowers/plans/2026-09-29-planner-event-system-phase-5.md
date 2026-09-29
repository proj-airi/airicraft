# Planner Event System Phase 5 Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the wake scheduler's priority rules.

- A safety emergency stops a planner turn that has become stale right away,
  instead of paying for the turn and then throwing its answer away.
- A player who keeps chatting can no longer cancel every turn forever.
- Autonomous wakes run within a budget that rule authors can tune.
- Every way of waking the planner goes through the scheduler.

This is the last phase before planner-authored rules (Phase 6).

**Spec:** [`specs/2026-09-26-planner-perception-and-wake-design.md`](../specs/2026-09-26-planner-perception-and-wake-design.md),
sections 3.1, 3.2, 4.7 and 6 (Phase 5), and decisions O3 and O7. **Previous
phase:** [`plans/2026-09-29-planner-event-system-phase-4.md`](2026-09-29-planner-event-system-phase-4.md).
Earlier phases deferred two items to this one: Phase 2 Task 9 Steps 2 and 3
(W9 and the adapter) and Task 11 (coalescing).

## Status (2026-09-29)

Proposed; not started. The decisions below need the user's confirmation.

**Out of scope, by the user's decision (2026-09-29):** the perception
performance work. This covers the P9 budget measured on a real machine and
the choice of whether to run the salience step off the client thread. It
waits until the whole refactor (Phase 6 included) is complete.

## Where Phase 5 starts

What Phases 1–4 already provide, and what is missing (on `dev`, 8dee6095):

| Needed | State on `dev` |
|---|---|
| `PREEMPT` delivery | **Declared only.** `Delivery.PREEMPT` exists, but nothing decides it or acts on it. |
| Rejecting stale turns after a safety change | **Done, but late.** The reflex increases the safety epoch when it starts (`SurvivalReflexRuntime.begin`). `PlannerOrchestrator.isStaleSafetyRequest` compares each result's epoch and hold id with the current ones. A stale result is rejected only when it *completes*: the model call runs to the end, is paid for, and is then dropped (`planner.stale_response_rejected`, golden `stale_safety_response_rejected`). |
| Cancelling a model call | **Codex only.** `CodexAppServerLlmBackend` interrupts the turn. `OpenAiCompatibleLlmBackend` does not support cancellation: `PlannerExecutor.discardGeneration` returns early, and the HTTP call keeps streaming in the background. The worker thread waits on `future.get`, and cancelling the outer `supplyAsync` future does not stop it. So a supersede on this backend also pays for the discarded turn. |
| Superseding with direct guidance | **In the orchestrator.** `submit` supersedes a replaceable in-flight turn when the batch `maySupersedeLaunchedTurn`, then arms a coalesce window in wall-clock milliseconds (`armCoalesceWindow`: `(n−1) × step`, clamped to [min, max]; defaults 10/10/100 ms). Chat can supersede any number of times: there is no budget. |
| One way into the scheduler | **Partly.** W1 and W4–W7 go through `WakeScheduler.offerTrigger`, and W2/W3 through the task wakes. W9 (tool-queue review) still calls `submit` itself in `PlannerOrchestrator.tickToolQueue`. `DialogueRuntime.onPlannerTrigger` is still the adapter in front of `offerTrigger`. |
| Autonomous-wake budget | **Missing.** `lib.leakyBucket` exists, but `attention/default.js` keeps no state, and `RuleDifferentialTest` pins it to the stateless Java reference. |
| Per-category notice budgets | **Done in Phase 4** (P6): `cooldown` and `hourlyCap` in `salience/default.js`, with drops recorded with a reason. |
| A bounded pending set | **Missing.** The scheduler's task-wake and debounced queues have no bound. |
| Metrics to tune from | **Partial.** The Phase 0 baseline records request rates per scenario, not autonomous wake decisions. The Phase 4 notice walk measured 10.4–10.8 percept wake decisions per minute. `attentionReplay` can re-decide recorded runs through a new module in order. No model-driven recorded run is available in this container. |

## Decisions

These are proposed on 2026-09-29, for the user to confirm before
implementation. Each has a recommendation.

| # | Question | Decision | Why |
|---|---|---|---|
| F1 | One PR or several? | **One PR with five slices (5a–5e), each with its own commits.** A commit that changes an existing golden is marked **[golden diff]** and names its cause. | Same shape as Phases 3 and 4. The user asked for one PR per phase. |
| F2 | What triggers `PREEMPT`? | **An increase of the safety epoch**, which happens when a reflex starts. A hold-id change within the same epoch keeps today's rejection-on-completion. | Spec O3 says "safety-epoch changes". A same-epoch hold change can be caused by the planner's own `continue` or `clear_queue`. The orchestrator already treats that case specially (`sameEpochHoldRelease`), and preempting it would cancel the planner's reaction to its own action. |
| F3 | What does "unexternalized" mean? | **A turn is preemptible when all of the following hold:** <br>• the active generation's model call is in flight (the first call, a repair retry, or a tool follow-up); <br>• no tool call is executing; <br>• no side-effect tool has run in this generation (read tools do not count). <br>Streaming previews do not count as externalized: by design they never enter history and never authorize an action. After a side-effect tool, the turn is not preempted, and today's rejection-on-completion applies. | Spec O3: "Never preempt after a side-effect tool has executed." Cortico cancels only a round that has not externalized output or tool calls. Read tools change nothing, so their results are committed as evidence, as the `TOOL_WAIT` stale path already does. |
| F4 | Where does preemption live? | **The scheduler decides and the orchestrator acts.** The `reflex.started` task wake carries delivery `PREEMPT`. When the scheduler reaches a `PREEMPT` wake and the planner is busy, it calls `host.preemptInFlight()`. The orchestrator cancels the turn only if its request is stale against the current safety context (F2) and still unexternalized (F3), and returns what it did. The wake is then delivered in the same tick. The reflex already ticks before the dialogue poll, so the epoch is up to date when the wake is released. | Delivery modes belong to the scheduler (spec 4.7). The orchestrator stays the only owner of the stale-request rule, so a `PREEMPT` can never cancel a turn that is still valid. |
| F5 | What happens to a preempted turn? | **The same as a stale rejection, only earlier.** The generation is discarded and the turn journal marks it superseded. The obsolete trigger is not replayed; its evidence stays in the log, and the next observation carries it. A new catalog type, `planner.turn_preempted` (INTERNAL, visibility `PLANNER`, policy bypass, no trigger), records `{generation, requestSafetyEpoch, currentSafetyEpoch, phase}`. `planner.stale_response_rejected` is kept for turns that could not be preempted. | This is a timing change only, with nothing new for the model to interpret. Event ids are frozen (spec 8), so preemption gets a new type rather than overloading "response rejected" when no response exists. |
| F6 | Real cancellation for the OpenAI-compatible backend | **The backend tracks the HTTP future of each generation's call. `discardGeneration` cancels it; the non-streaming path moves to `sendAsync` too; `supportsGenerationCancellation()` returns `true`.** A cancelled call ends as a discarded attempt, not as a provider failure, and never counts toward degradation. | Without this, preempting (and superseding) on this backend still pays for the whole discarded response. On JBR 21, cancelling a `sendAsync` future aborts the exchange. Whether a provider still bills a cancelled stream is up to the provider; closing the stream is the most the client can do. |
| F7 | Supersede budget | **In `WakeScheduler` (Java), at most 3 supersedes per 600 ticks (30 s).** Only real supersedes count, meaning a replaceable in-flight turn was cancelled. Over budget, a `DIRECT` wake does not supersede: it waits behind the in-flight turn and goes out at the next decision boundary. Audit gate `supersede.budget`. The window resets at lifecycle boundaries and resets. Constants, like the debounce timings. | Spec 3.2 and 4.7: the legacy AIRI bot guards against starvation after 8 consecutive high-priority turns. Batches go out whole here, so a chatty player needs a supersede limit instead. Reset commands are handled before the planner (constitution), so a player can still always stop the agent. |
| F8 | The coalesce window (Phase 2 Task 11) | **It moves into the scheduler and counts ticks.** When a `DIRECT` wake finds a replaceable turn in flight and the budget allows, the scheduler has the host supersede the turn (`host.supersedeInFlight()`), then holds the `DIRECT` wakes for `clamp((n−1) × step, min, max)`. The existing millisecond settings are converted to ticks, rounded up: the defaults 10/10/100 ms become 1/1/2 ticks. Each new `DIRECT` wake re-arms the window. `submit` no longer supersedes. Triggers submitted while a turn cannot be replaced still wait in the orchestrator's context aggregator, which is conversation mechanics, so `invalidateIdleThinkTriggers` stays. | O4 (agent ticks), and one owner for supersession. Today's orchestrator polls once per 50 ms tick, so a 10 ms window already means "the next tick": the goldens should not change. If rounding changes one, it becomes a named **[golden diff]** commit. Holding every trigger in the scheduler instead of the aggregator would be a much larger change with no characterized defect behind it. |
| F9 | W9 and the adapter (Phase 2 Task 9 Steps 2–3) | **`tickToolQueue` hands its review wake to the scheduler, through a sink the dialogue configures on the orchestrator. The scheduler audits it as W9 with today's fields and delivers it at once.** `DialogueRuntime.onPlannerTrigger` is removed, and callers use `offerTrigger`. A new golden, `tool_queue_review`, pins W9, which has no golden today. | Every wake is then admitted and audited in one place, and W9 also respects the supersede budget and preemption. The queue's own continuation (G8 mechanics) keeps its timing. |
| F10 | Autonomous-wake leaky bucket | **In `attention/default.js`, using `lib.leakyBucket`, with state owned by the host.** <br>• It covers `NORMAL` and `LOW` wakes (`IMMEDIATE` or `DEBOUNCE`) on types that are not protected. Protected types keep their wakes through the Stage C clamp, so the bucket skips them rather than fighting the clamp. <br>• Each such decision costs 1. Over capacity, the decision becomes `NONE` with rule id `budget.autonomous_wakes` and the level in its reason; the evidence stays in `observe` (O8). <br>• Defaults: capacity 10, leaking 1 per 100 ticks, so a burst of 10 and 12 per minute sustained. <br>• There is **no Java mirror.** The reference decides only as the fallback while the engine is cold or failing, and it does not budget. `RuleDifferentialTest` keeps comparing single decisions from an empty bucket, which never engages. A separate rules test threads the bucket state through a storm. | Spec 4.7 and O7: budgets live in JS so rule authors (and the planner in Phase 6) can retune them without a Java change. An empty bucket keeps the 5,000-case parity check meaningful. The defaults are set to catch storms, not normal play: the notice walk's 10.8 per minute sits under the sustained rate. |
| F11 | How are the budgets tuned from metrics? | **By replay and one model batch.** <br>• Replay: `attentionReplay` re-decides recorded runs through the new module in order, so bucket muting appears as decision differences. Each difference is reviewed. <br>• Model batch: the user runs one evaluation batch of all `scenarios/*` on the Phase 5 head. A scenario must not do worse than its Phase 0 baseline result, and the ledger must show zero `budget.autonomous_wakes` and `supersede.budget` hits on those scenarios. <br>• A synthetic storm test (40 pickups in 40 ticks, or a chat line every 20 ticks for a minute) must show both budgets engaging. <br>The notice budgets from Phase 4 are re-checked against the same batch and changed only if a scenario shows a cause. | Spec 6 asks for tuning "from metrics". The recorded data in this container comes from stub runs without a model, so a real model batch is needed and is the user's call. Until it runs, the defaults are deliberately loose. |
| F12 | Bounded pending set | **At most 64 pending task wakes and 32 held debounced wakes.** When the bound is exceeded, the lowest urgency goes first, oldest first among equals; attention wakes, `CRITICAL` and `DIRECT` are never dropped. Audit gate `pending.bounded`. | Spec 4.7 ("the pending set is bounded… records the drop"). Nothing reaches these bounds in normal play; they only cap a runaway producer. |
| F13 | Do reflex inputs move to the bus? | **No.** The reflex keeps sampling on its own tick for latency and only publishes its outputs through the bus. | Spec 4.3 leaves this to Phase 5. No defect calls for it, and moving it would add a tick of latency to survival reactions. |
| F14 | Rename `planner.internal_task_update_superseded` to `planner.wake_superseded`? | **No. The id stays.** The spec text is updated. | The event is diagnostic only, and the wake ledger and the dashboard read it. Event ids are frozen (spec 8), so renaming costs churn and gains nothing. |

## Ground rules

1. **Existing goldens change only in named [golden diff] commits:** 5b
   (preemption) and anything F8's rounding changes. Every other slice leaves
   existing goldens and their `wakeAudit` byte-identical.
2. **Parity holds for single decisions.** The bucket is the only stateful
   part of `attention/default.js`. The differential test runs every case
   from empty rule state, so it must still pass.
3. **Never preempt after a side-effect tool** (O3), and never preempt a turn
   that is valid for the current safety context.
4. **No new model-visible prose.** Preemption and budgets change timing and
   add one `PLANNER`-visible event type; the prompts do not change.
5. **Budgets never silence protected wakes.** The clamp keeps them, and the
   bucket skips them.

---

## Slice 5a: cancelling OpenAI-compatible calls

### Task 1: Per-generation cancellation

- [ ] **Step 1: Track the HTTP future of each call.**
  - `OpenAiCompatibleChatClient` registers each call's `sendAsync` future
    under its generation. The non-streaming path moves to `sendAsync` with a
    timed `get`.
  - `OpenAiCompatibleLlmBackend.discardGeneration(generation)` cancels that
    generation's futures, and `supportsGenerationCancellation()` returns
    `true`.
  - `PlannerExecutor.discardGeneration` then cancels the attempt future and
    ends its flight span (the existing Codex path).
- [ ] **Step 2: A cancelled call is a discard, not a failure.** Cancellation
  surfaces as `CancellationException`. It is recorded as a discarded attempt
  and never reaches `finishFailedPlannerResult`, the degradation counter or
  a repair retry.
- [ ] **Step 3: Tests.**
  - A local HTTP server streams one SSE line per 100 ms. Discarding the
    generation closes the connection within 200 ms, and no result is
    applied.
  - The same for the non-streaming path.
  - The existing supersede tests pass unchanged.

Commit: `feat(llm): cancel discarded OpenAI-compatible planner calls`.

The goldens use the recording test backend and stay byte-identical.

## Slice 5b [golden diff]: `PREEMPT` for safety-epoch changes

### Task 2: What a turn has externalized

- [ ] **Step 1:** In the orchestrator, add `preemptStaleTurn()`. It returns
  one of:
  - `PREEMPTED(phase)`;
  - `NOT_STALE`;
  - `EXTERNALIZED(reason)`, where the reason is `tool_executing` or
    `side_effect_tool_ran`;
  - `NOTHING_IN_FLIGHT`.

  It reads:
  - the active session's phase and request;
  - `pendingToolExecution`;
  - a per-generation flag, set when a side-effect tool's result is
    committed (`isSideEffectTool`, which already exists).
- [ ] **Step 2:** A turn is preempted only when its request's safety epoch
  is below the current one (F2). Hold-only changes return `NOT_STALE`.
- [ ] **Step 3:** Preempting reuses the stale-rejection steps:
  - `commitRecordedToolExchanges`, which covers read tools;
  - `discardGeneration`, which now cancels on both backends;
  - `turnJournal.markSuperseded`, `finishGeneration(…, true)`, clearing
    `pendingSubmitRequest`, and `endTurnSpan`.

  It records a preemption for the runtime to publish.

### Task 3: The scheduler's `PREEMPT` delivery

- [ ] **Step 1:** `Wake` gains a `delivery` field (default `IMMEDIATE`).
  `EmbodiedAgentRuntime.processSurvivalReflexEvents` queues the
  `reflex.started` task wake with `PREEMPT`.
- [ ] **Step 2:** In `WakeScheduler.releaseTaskWake`, when the head is
  `PREEMPT` and the planner is in flight:
  - the scheduler calls `host.preemptInFlight()`;
  - on `PREEMPTED`, it records the wake-audit kind `preempted` (gate
    `preempt.safety_epoch`) and delivers the wake in the same call;
  - otherwise it records gate `preempt.<outcome>` once and waits, as
    today.
- [ ] **Step 3:** The runtime publishes `planner.turn_preempted` (a new
  catalog entry, and the event inventory updated) and `planner_wake`.
- [ ] **Step 4: Goldens.**
  - **[golden diff]** `stale_safety_response_rejected` now shows
    `planner.turn_preempted` at the epoch change, not a rejection at
    completion. Its scenario raises the epoch, so preemption applies. The
    held response is never applied.
  - New goldens:
    - `safety_epoch_preempts_turn`: the reflex starts during a first
      model call, which is preempted; the `reflex.started` wake is
      delivered in the same tick.
    - `side_effect_tool_not_preempted`: after a side-effect tool, the
      follow-up is rejected on completion, as today.
    - `hold_change_not_preempted`: same epoch, new hold.
- [ ] **Step 5:** Orchestrator unit tests cover:
  - every `preemptStaleTurn` outcome;
  - a read tool before preemption keeps its result as evidence.

Commits:
- `feat(llm): preempt a turn made stale by a safety epoch`;
- `feat(attention): PREEMPT delivery for reflex starts [golden diff]`.

## Slice 5c: supersession in the scheduler

### Task 4: Coalescing moves to the scheduler (F8)

- [ ] **Step 1:** `TriggerHost` gains `supersedeInFlight()`, which returns
  whether a replaceable turn was cancelled. The orchestrator exposes it
  (the body of today's supersede branch in `submit`), and `submit` loses
  that branch.
- [ ] **Step 2:** `WakeScheduler` gains a coalesce hold for `DIRECT`
  wakes:
  - it is armed after a supersede;
  - `readyAt = tick + clampTicks((n−1) × step)`;
  - each new `DIRECT` wake joins the hold and re-arms it;
  - it is released from `releaseDebounced` (renamed `releaseHeld`), together
    with any held debounced wakes.
  The orchestrator's coalesce fields, `armCoalesceWindow` and
  `computeCoalesceWindowMs` are removed. The debug snapshot reads the
  scheduler's hold instead.
- [ ] **Step 3:** The millisecond settings are converted to ticks once, when
  the runtime is built, rounding up. `agent.yml` keeps its keys.
- [ ] **Step 4:** Goldens are expected to stay identical. Run the golden
  suite 50 times. Any rounding diff is split out as a **[golden diff]**
  commit with its explanation.

Commit: `refactor(attention): coalesce direct guidance in the scheduler`.

### Task 5: Supersede budget (F7)

- [ ] **Step 1:** The scheduler keeps the ticks of recent supersedes, at most
  3 within 600 ticks. Over budget, it skips `supersedeInFlight()` and
  delivers the `DIRECT` batch. The orchestrator queues the batch behind the
  in-flight turn, as it does for any batch that is not replacing one.
  Audit gate `supersede.budget`.
- [ ] **Step 2:** `clearTaskWakes` and lifecycle boundaries clear the
  window.
- [ ] **Step 3:** New golden `chat_spam_supersede_budget`: four addressed
  chat lines at ticks 1, 40, 80 and 120, each arriving while a held turn is
  in flight. Three supersede; the fourth waits for the turn to finish and
  is delivered next.

Commit: `feat(attention): supersede budget for direct guidance`.

### Task 6: W9 and the adapter (F9)

- [ ] **Step 1:** Add `PlannerOrchestrator.configureReviewWakeSink(...)`.
  `tickToolQueue` builds today's request and wake fields, then hands them
  to the sink instead of calling `submit`. `DialogueRuntime` wires the sink
  to the scheduler, which audits W9 and delivers at once.
- [ ] **Step 2:** Remove `DialogueRuntime.onPlannerTrigger`. Callers,
  including tests, use `offerTrigger` through the dialogue's host.
- [ ] **Step 3:** New golden `tool_queue_review`: a FIFO of two tools with a
  `report_to_me` checkpoint. W9 is recorded with `review: checkpoint`, then
  with `review: fifo_empty`.

Commit: `refactor(attention): admit W9 through the scheduler and drop the trigger adapter`.

## Slice 5d: budgets

### Task 7: Autonomous-wake leaky bucket (F10)

- [ ] **Step 1:** In `attention/default.js`:
  - after deciding a wake, apply `lib.leakyBucket(state.autonomous, {capacity: 10, leakPerTick: 0.01, cost: 1}, input.tick)`
    to every non-protected `NORMAL` or `LOW` wake;
  - when it is not accepted, return `none('budget.autonomous_wakes', 'autonomous wake budget spent (level L of 10)')`;
  - the protected set comes from each event's `profile.bypass`, which the
    module already receives.
- [ ] **Step 2:** `RuleDifferentialTest` runs each case from `{}` state; it
  keeps passing unchanged. New `AttentionBudgetRulesTest`:
  - it threads state through 40 `pickup.item_picked_up` events at one per
    tick, while idle;
  - the first 10 wake and the rest are `NONE` with the budget rule id;
  - after 1,000 quiet ticks, the bucket accepts again;
  - protected types in the same storm are never muted.
- [ ] **Step 3:** New golden `autonomous_wake_budget`: the pickup storm
  through the real runtime. It shows the muted decisions in the decision log
  and every pickup still present in `observe.events`.
- [ ] **Step 4:** `docs/attention-rules.md` documents the bucket: where it
  lives, its defaults, how to retune or remove it in an override, and that
  the fallback does not budget.

Commit: `feat(rules): autonomous-wake leaky bucket in the bundled attention module`.

### Task 8: Bounded pending set (F12)

- [ ] **Step 1:** Bound the task wakes at 64 and the held debounced wakes at
  32. Overflow drops the lowest urgency first (oldest among equals), never
  an attention, `CRITICAL` or `DIRECT` wake. Audit gate `pending.bounded`.
- [ ] **Step 2:** `WakeSchedulerTest` covers the overflow order and the
  protected wakes surviving it.

Commit: `feat(attention): bound the pending wake set`.

### Task 9: Tuning (F11)

- [ ] **Step 1:** Replay the recorded runs available (the Phase 2 stub runs
  and any model runs the user provides) through the Phase 5 module. Review
  every `budget.autonomous_wakes` difference.
- [ ] **Step 2:** Run the synthetic storms (pickups, and a chat line every
  20 ticks) in the harness. Record when each budget engages.
- [ ] **Step 3 (needs a model; the user's call):** run one evaluation batch
  of all `scenarios/*` on the Phase 5 head.
  - Compare with the Phase 0 baseline: pass/fail no worse than the worst
    baseline result for each scenario.
  - Count `budget.autonomous_wakes`, `supersede.budget` and `preempted`
    in the wake ledger.
  - Adjust the defaults only when a scenario shows a cause, and record it.
- [ ] **Step 4:** Add `budget`, `supersede` and `preempt` counts to
  `scripts/wake_ledger.py summarize`, with a Python test.

## Slice 5e: docs, verification, PR

### Task 10: Docs

- [ ] **Step 1:** `docs/attention-rules.md`: `PREEMPT`, the supersede
  budget, the coalesce hold, the leaky bucket, and the bounded pending set.
- [ ] **Step 2:** `AGENTS.md`: behaviour notes (preemption, budgets) and
  key files.
- [ ] **Step 3:** The README's docs links.
- [ ] **Step 4:** `CONTEXT.md`:
  - **Delivery** covers preemption;
  - add **Supersede budget** and **Autonomous-wake budget**.
- [ ] **Step 5:** **ADR-0003 (final):**
  - change the status to "implemented through Phase 5";
  - record F2–F5 (preemption scope) and F7/F10 (where budgets live);
  - note that planner-authored rules (Phase 6) are the remaining step.
- [ ] **Step 6:** In the spec:
  - tick the Phase 5 checklist;
  - record F13 (reflex inputs stay off the bus) and F14 (the id stays);
  - fix the `planner.wake_superseded` text.
- [ ] **Step 7:** Update this plan's status and revisions.

### Task 11: Verification

- [ ] **Step 1:** `./gradlew build`, and the evaluator, Python and
  dashboard suites.
- [ ] **Step 2:** Golden repeat 50×.
- [ ] **Step 3:** Live smoke with the stub endpoint in an evaluator client.
  1. A slow held response is in flight, and a reflex is triggered through
     the evaluator (drowning or a spawned zombie).
  2. `planner.turn_preempted` appears in the same tick.
  3. The stub sees the connection close.
  4. The `reflex.started` wake is delivered next.
- [ ] **Step 4:** Open the PR against `dev`.

## Exit criteria

- [ ] Existing goldens change only in 5b's named **[golden diff]** commit
  (plus any F8 rounding commit). Every new golden is reviewed. A 50× repeat
  passes.
- [ ] `RuleDifferentialTest` passes 5,000 cases unchanged, and the budget
  rules test passes.
- [ ] Preemption:
  - a stale, unexternalized turn is cancelled in the same tick as the epoch
    change, on both backends;
  - a turn after a side-effect tool is never preempted;
  - a hold-only change never preempts.
- [ ] Discarding an OpenAI-compatible generation closes its HTTP stream.
- [ ] A chat line every 20 ticks can supersede at most 3 times per 30 s.
- [ ] A pickup storm is muted after 10 wakes, with every pickup still
  observed. Protected wakes are never muted.
- [ ] Every wake path (W1–W7, W9) goes through `WakeScheduler`. `submit`
  no longer supersedes on its own.
- [ ] Docs and ADR-0003 are final for Phase 5.
- [ ] (Needs a model; the user's call) One evaluation batch shows no scenario
  worse than its Phase 0 baseline, and zero budget hits on those
  scenarios.
