# Planner Event System Phase 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Before the refactor changes anything, pin down how the planner wakes
today with executable characterization tests, measure a baseline, find out
whether the GraalJS rule engine is fast enough, and record the accepted
decisions. Agent behaviour does not change in this phase.

**Architecture:** Five pieces, each usable on its own:

1. An **event inventory guard**: a test that fails when an event type
   exists in code but not in the checked-in inventory.
2. Additive **wake audit** entries (`planner_wake`) in the debug timeline,
   recording every planner submission and every dropped wake with its path
   and gate.
3. A **wake scenario harness**: the real `EmbodiedAgentRuntime` and
   `DialogueRuntime` driven tick by tick against a recording planner backend.
   It compares normalized transcripts with golden files.
4. A Python **wake ledger** tool that computes baseline metrics from
   recorded runs.
5. An opt-in **GraalJS spike** that measures the proposed rule-engine step
   contract in the real sandbox.

**Tech Stack:** Java 21 on the JBR 21 toolchain, JUnit 5, GraalJS 25.0.4
(interpreter-only on JBR, as in production), Gson, Python 3 with `unittest`,
and the existing evaluation harness and automatic playtest scripts.

**Spec:** [`specs/2026-09-26-planner-perception-and-wake-design.md`](../specs/2026-09-26-planner-perception-and-wake-design.md).
Terms such as W1–W9, G1–G11, E1–E3, D1–D8, Stage A/B/C and O1–O13 refer to
that document.

---

## Implementation status (2026-09-27)

Tasks 1–10 are implemented on PR #81. The full build passes (1,718 root
tests, 95 wrapper tests; 3 opt-in root tests skipped), and the Python ledger
suite passes 12 tests. Live baseline runs use the committed Phase 0 branch;
Task 3 retention and Tasks 11–12 remain open until those records are checked.
The branch has not been merged into `dev`.

Implementation adjustments: FIFO/checkpoint reviews are W9; retained W2 gate
audits are emitted once per gate transition; fatal damage wakes before
respawn; the rule spike lives in the `policy` test package to reuse production
sandbox options. The spec records bounded D1–D8 verdicts and measured spike
limits. These changes characterize current scheduling without changing it.

## Ground rules

1. **No behaviour changes.** Two kinds of production edits are allowed:
   - package-private test seams (accessors, an injectable `Clock`, an
     injectable planner backend, event and reflex injection hooks);
   - the additive `planner_wake` debug-timeline entries (Task 3).

   Each goes in its own commit whose message says "no behaviour change".
2. **Characterization tests pin current behaviour, including suspected
   defects.** A test that documents a defect is named after it, for example
   `d1_...`. When a later phase fixes the defect, that fix changes the
   pinned expectation in its own PR, and the diff is the review evidence.
3. **Prerequisites** are the ones in `AGENTS.md`: a JDK 25 Gradle JVM, the JBR 21
   toolchain, `git submodule update --init --recursive`, and `ffmpeg` on
   `PATH`. The commands below were initially unrun in the planning session; the
   implementation status and checked steps now record execution evidence.
4. **PR slices.** Each slice merges independently, and Task 11 needs A–E:
   - A: Tasks 1–2
   - B: Tasks 3–4
   - C: Tasks 5–8
   - D: Task 9
   - E: Task 10
   - F: Tasks 11–12
   
   Slice E can go in parallel with anything.

## File Structure

### New files

- `docs/adr/0003-planner-perception-attention-and-wakes.md`: the accepted decisions
- `src/test/resources/planner/wakes/event-inventory.json`: the checked-in event inventory
- `src/test/java/ai/moeru/airicraft/agent/EventInventoryTest.java`: inventory guard
- `src/test/java/ai/moeru/airicraft/agent/wakes/RecordingPlannerBackend.java`: scripted, recording `LlmBackend`
- `src/test/java/ai/moeru/airicraft/agent/wakes/WakeTranscript.java`: transcript model, normalization, golden compare
- `src/test/java/ai/moeru/airicraft/agent/WakeScenarioHarness.java`: runtime-level driver (same package as the runtime's package-private hooks)
- `src/test/java/ai/moeru/airicraft/agent/WakeCharacterizationTest.java`: runtime-level scenarios
- `src/test/java/ai/moeru/airicraft/agent/dialogue/DialogueWakeCharacterizationTest.java`: dialogue-level scenarios
- `src/test/java/ai/moeru/airicraft/agent/WakeDefectProbeTest.java`: D1–D8 probes that need the runtime
- `src/test/resources/planner/wakes/*.golden.json`: one golden transcript per scenario
- `scripts/wake_ledger.py`, `scripts/tests/test_wake_ledger.py`, `scripts/tests/fixtures/wake-ledger/`: ledger tool, tests and fixture run
- `src/test/java/ai/moeru/airicraft/agent/attention/RuleEngineSpikeTest.java`: opt-in GraalJS measurement
- `src/test/resources/rules-spike/lib.js`, `src/test/resources/rules-spike/attention.js`, `src/test/resources/rules-spike/kernel.js`: draft rule modules for the spike
- `docs/experiments/<date>-attention-rule-engine-spike.md`, `docs/experiments/<date>-planner-wake-baseline.md`: results

### Modified files (test seams and instrumentation only)

- `src/client/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntime.java`: routing-profile accessor, `Clock` and backend-factory seams, event/reflex injection hooks, wiring for the wake audit sink
- `src/client/java/ai/moeru/airicraft/agent/shell/PlannerShellFactory.java`: accepts a backend factory, with the existing `switch` as the default
- `src/client/java/ai/moeru/airicraft/agent/dialogue/DialogueRuntime.java`: `WakeAuditSink`, called at submissions and early returns
- `src/client/java/ai/moeru/airicraft/agent/llm/PlannerOrchestrator.java`: audit entry for the overflow flush (W8)
- `src/client/java/ai/moeru/airicraft/agent/debug/AgentDebugRecorder.java`: public `recordPlannerWake(...)`
- `src/client/java/ai/moeru/airicraft/agent/reflex/SurvivalReflexRuntime.java`: package-private event injection hook
- `CONTEXT.md`: planner attention vocabulary
- `docs/superpowers/specs/2026-09-26-planner-perception-and-wake-design.md`: Phase 0 outcomes

### Read before editing

- The spec, sections 2, 4.6–4.12 and 6
- `docs/adr/0002-system2-evidence-work-and-decisions.md`
- `EmbodiedAgentRuntime.java`: `tickClient` (536), `drainEventPipeline` (4528), `maybeFireIdleIdeaTrigger` (4549), trigger factories (4595–4970), `createEventRoutingProfiles` (5069)
- `DialogueRuntime.java` (all of it), `PlannerOrchestrator.submit` (493), `PlannerContextAggregator.freezePlannerSnapshot` (164)
- The test precedents:
  - `DialogueRuntimeTest.newDialogueRuntime` and `BlockingLlmBackend`
  - `EmbodiedAgentRuntimeTest.loadedRemoteSession` and `FakeWorldTaskExecutor`
  - the `@EnabledIfEnvironmentVariable` opt-in tests in `agent/llm/codex/`

---

### Task 1: Record the decisions (ADR-0003, `CONTEXT.md`)

**Files:**
- Create: `docs/adr/0003-planner-perception-attention-and-wakes.md`
- Modify: `CONTEXT.md`

- [x] **Step 1: Write ADR-0003** in the shape of ADR-0002: status, context,
  decision, ownership and limits, consequences. Record O1–O13 as accepted on
  2026-09-26. Say explicitly that it extends ADR-0002:
  - the event log stays bounded at 512 events (O9);
  - there is still no event sourcing;
  - `observe` stays the evidence channel.
  
  Link the spec instead of copying its tables.
- [x] **Step 2: Add a "Planner attention" section to `CONTEXT.md`** in the
  existing `**Term**: … _Avoid_: …` format. Terms and the synonyms to avoid:
  - Sensor
  - Candidate
  - Percept (avoid: signal)
  - Agent event
  - Event log (avoid: semantic buffer)
  - Attention policy (avoid: routing profile, event policy)
  - Constitution
  - Rule module
  - Wake (avoid: trigger, wakeup)
  - Wake batch (avoid: trigger batch)
  - Urgency (avoid: priority)
  - Delivery
- [x] **Step 3: Commit** (`docs(adr): accept planner perception, attention and wake design`).

### Task 2: Event inventory guard

The guard catches any event type added on `dev` during the refactor, and
becomes the seed of Phase 1's `EventCatalog`. While this plan was written,
the same scan found `planner.timeout`, `planner.parse_error` and
`planner.provider_error` (emitted through `DialogueCore.failureEventType`),
which the hand-built spec appendix had missed.

**Files:**
- Modify: `EmbodiedAgentRuntime.java`
- Create: `src/test/resources/planner/wakes/event-inventory.json`
- Create: `src/test/java/ai/moeru/airicraft/agent/EventInventoryTest.java`

- [x] **Step 1: Expose the routing table to tests (no behaviour change)**

```java
// EmbodiedAgentRuntime, next to createEventRoutingProfiles()
static Map<String, EventRoutingProfile> eventRoutingProfilesForTests() {
	return createEventRoutingProfiles();
}
```

- [x] **Step 2: Write the inventory.** Write one entry per type, and one
  prefix entry per dynamic family. Seed it from spec Appendix A plus the
  three failure types. For example:

```json
{
  "schema": "airicraft.event-inventory.v1",
  "types": [
    {"type": "pickup.item_picked_up", "producers": ["EmbodiedAgentRuntime"],
     "profile": {"semantic": true, "trigger": "PICKUP", "bypass": false},
     "observeVisible": true, "extraWake": null},
    {"type": "reflex.resolved", "producers": ["SurvivalReflexRuntime"],
     "profile": {"semantic": true, "trigger": "SYSTEM", "bypass": true},
     "observeVisible": true, "extraWake": "W2"},
    {"prefix": "interaction.", "producers": ["InteractionLogbookRecorder"],
     "profile": null, "observeVisible": true, "extraWake": null}
  ],
  "notEventTypes": ["inventory.item", "inventory.resource", "inventory.tool",
    "planner.micro_compaction", "session.lock", "smelting.process"]
}
```

  `notEventTypes` lists string literals that look like event ids but are
  action-fact kinds, request kinds or file names.

- [x] **Step 3: Write the tests**
  - `routingProfilesMatchInventory`: compare
    `eventRoutingProfilesForTests()` with every `profile` in the inventory,
    in both directions.
  - `observeVisibilityMatchesInventory`: for each type, build a
    `PlannerDecisionContext` holding one `SemanticEvent` of that type, and
    check that `observation(0).get("events")` is non-empty exactly when
    `observeVisible` is true. `work.changed` needs a minimal
    `WorkSnapshot.payload()`, because the observation summarizes it.
  - `everyEmittedEventTypeIsInventoried`: scan
    `src/client/java/ai/moeru/airicraft/agent/**/*.java` for literals
    matching
    `"(social|pickup|crafting|smelting|combat|player|reflex|lighting|planner|session|follow|task|work|food|action_graph|mission|objective|policy|interaction|inventory|container|survival)\.[a-z_]+"`.
    Resolve paths from `System.getProperty("user.dir")`, which is the project
    directory under Gradle. Fail with the sorted list of ids that are neither
    an inventory type, covered by a prefix entry, nor in `notEventTypes`.
    The scan found 99 such literals when this plan was written.
- [x] **Step 4: Run** `./gradlew test --tests 'ai.moeru.airicraft.agent.EventInventoryTest'`.
  Expected: PASS once the inventory is complete. Temporarily delete one
  entry and check that the failure message names it.
- [x] **Step 5: Regenerate spec Appendix A** from the inventory if they
  disagree, then commit.

### Task 3: Wake audit instrumentation (additive)

Today only W1 routing is recorded (`recordEventRouting`). This task records
every submission and every drop, so both the harness and the baseline can
say *why* each planner request happened.

**Files:**
- Modify: `AgentDebugRecorder.java`, `DialogueRuntime.java`,
  `PlannerOrchestrator.java`, `EmbodiedAgentRuntime.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/dialogue/DialogueRuntimeTest.java`

- [x] **Step 1: Add the recorder entry**

```java
// AgentDebugRecorder
public synchronized void recordPlannerWake(long tick, long timestampMs, String kind, Map<String, Object> fields) {
	appendTimeline(tick, timestampMs, "planner_wake", kind,
		"Planner wake " + kind + " " + fields.getOrDefault("path", "-"), Map.of(), fields);
}
```

  Check the existing `appendTimeline` parameter order before copying this.
  `kind` is `submitted` or `dropped`.

- [x] **Step 2: Add a sink to `DialogueRuntime`**

```java
public interface WakeAuditSink {
	WakeAuditSink NO_OP = (tick, kind, fields) -> { };
	void record(long tick, String kind, Map<String, Object> fields);
}
private WakeAuditSink wakeAudit = WakeAuditSink.NO_OP;
public void configureWakeAudit(WakeAuditSink sink) { wakeAudit = Objects.requireNonNull(sink); }
```

  Fields:
  - `path`: W1–W8
  - `gate`: G-id plus a short reason, only for drops
  - `triggerTypes`, `origins`, `coalescingKeys`, `speakers`
  - `eventSequence`: the referenced event, when there is one
  - `owner`: controller or thinking
  - `serverTick`

- [x] **Step 3: Call the sink.** Don't reorder any logic.

  | Site | Record |
  |---|---|
  | `onPlannerTrigger`, each early return (531 blocked goal, 537–539 accepted work / queued tool work) | `dropped`, gates `G4.blocked_goal` / `G4.accepted_work` |
  | `onPlannerTrigger`, before `submitPlannerTrigger` | `submitted`. The path is inferred: `IDLE_THINK`→W5, key `planner_goal`→W4, key `delegation`→W6, speaker `evaluation`→W7, else W1 |
  | `submitNextPendingInternalTaskUpdate`, each `continue`/`return false` inside the loop | `dropped`, gates `G5.run_policy`, `G5.queued_tool_work`, `G5.incorporated`, `G5.blocked_irrelevant`, `G5.superseded` |
  | `submitNextPendingInternalTaskUpdate`, before submit | `submitted`, W3 if `wake.attention()`, else W2, with `eventSequence` |
  | `submitPlannerTrigger`, degraded branch | `dropped`, gate `G8.degraded` |
  | `PlannerOrchestrator.startOverflowFlushIfIdle`, on submit | `submitted` W8, recorded through its existing `debugRecorder` |

- [x] **Step 4: Wire it.** In the `EmbodiedAgentRuntime` constructor, call
  `dialogueRuntime.configureWakeAudit((tick, kind, fields) -> debugRecorder.recordPlannerWake(tick, System.currentTimeMillis(), kind, withServerTick(fields)))`.
  Use the injected clock once Task 4 lands. The entries reach the dashboard
  export and `RuntimeFlightRecorder`'s `debug-timeline.jsonl` with no further
  work.
- [x] **Step 5: Test.** Add a `DialogueRuntimeTest` case using a recording
  sink for:
  - one W1 submission;
  - a W2 wake dropped as `G5.incorporated` after its event was observed;
  - a W3 attention submission while accepted work runs.
- [ ] **Step 6: Check timeline retention.** The debug timeline holds 256
  entries. Confirm in the Task 11 baseline that `debug-timeline.jsonl` has
  no gap markers caused by the extra entries. If it does, raise the
  recorder's poll frequency; don't raise the capacity.
- [x] **Step 7: Run and commit.** Run
  `./gradlew test --tests 'ai.moeru.airicraft.agent.dialogue.*'`. Expected:
  PASS, with no changes to existing assertions.

### Task 4: Test seams for a deterministic runtime

**Files:**
- Modify: `EmbodiedAgentRuntime.java`, `PlannerShellFactory.java`, `SurvivalReflexRuntime.java`

- [x] **Step 1: Planner backend factory.**
  - Add a
    `PlannerShellFactory.create(..., Function<AgentConfig.LlmConfig, LlmBackend> backendFactory)`
    overload. The existing overloads delegate with the current `switch`,
    used in `createOrchestrator`.
  - Compaction and micro-compaction keep their own HTTP clients. The harness
    configures a compaction threshold high enough that they never run.
- [x] **Step 2: Inject the clock.** Add a package-private
  `EmbodiedAgentRuntime` constructor taking the backend factory and a
  `java.time.Clock`. The public constructors pass the current behaviour:
  the provider `switch` and `Clock.systemDefaultZone()`. Route every
  wake-relevant wall-clock read through that clock, with no logic changes:
  - the `Clock` passed to `PlannerShellFactory` (dialogue, orchestrator
    coalescing, aggregator time beacons);
  - `System.currentTimeMillis()` in `maybeFireIdleIdeaTrigger` and
    `emitEvaluationTrigger`.
  
  Keep the event buffers' timestamps on the system clock. The transcripts
  strip them.
- [x] **Step 3: Injection hooks** (package-private, named `...ForTests`,
  like the existing hooks):
  - `appendEventForTests(String type, Map<String,Object> payload)`: appends
    to the raw buffer at the current tick, for producers that need a live
    client (item offers, physical episodes, smelting output, graph
    outcomes).
  - `SurvivalReflexRuntime.enqueueEventForTests(SurvivalReflexEvent event)`:
    the next `tickSurvivalReflex` drains it through
    `processSurvivalReflexEvents`, so the reflex path's
    `queueTaskWakeup`/`queueTaskAttention` side effects are exercised.
    `tickSurvivalReflex` with a null client may return early, so check the
    drain still runs. If it doesn't, add
    `processSurvivalReflexEventsForTests()` to EAR.
  - `dialogueRuntimeForTests()`: for scenarios that need to read planner
    goal or delegation state.
- [x] **Step 4: Run and commit.** Run
  `./gradlew test --tests 'ai.moeru.airicraft.agent.*'`. Expected: PASS with
  no assertion changes.

### Task 5: Wake scenario harness

**Files:**
- Create: `RecordingPlannerBackend.java`, `WakeTranscript.java`, `WakeScenarioHarness.java`

- [x] **Step 1: Recording backend.** Generalize the `BlockingLlmBackend`
  pattern from `DialogueRuntimeTest`:

```java
public final class RecordingPlannerBackend implements LlmBackend {
	public record Request(int index, LlmConversation conversation, long observedAtTick) { }
	private final List<Request> requests = new CopyOnWriteArrayList<>();
	private final LinkedBlockingQueue<CompletableFuture<PlannerResponse>> scripted = new LinkedBlockingQueue<>();
	private volatile boolean autoYield = true;   // default reply: one `continue` call
	// generate(): record, then take the next scripted response, or build a silent yield
	//   new PlannerResponse("", new PlannerToolCall("yield-" + index, "continue", new JsonObject(), null), null)
	// holdNext(): the next request blocks until release(response) -- used to keep a turn in flight
	// awaitRequests(n, timeout), assertNoNewRequests(duration) as in BlockingLlmBackend
	@Override public boolean isConfigured() { return true; }
}
```

  A reply with neither text nor tool calls is a parse failure (ADR-0002),
  so the default reply is a single `continue` call. First confirm, in one
  harness self-test, that `continue` ends the turn without a follow-up
  request.

- [x] **Step 2: Runtime harness.** `WakeScenarioHarness` builds the runtime
  with:
  - `AgentConfigLoader.fromMapStrict(Map.of("providerBaseUrl", "http://127.0.0.1:9", "apiKey", "test", "model", "test"), AgentConfig.defaults())`,
    so that `config.llm().isConfigured()` is true (check the key names
    against `AgentConfigLoader`);
  - the recording backend factory;
  - a `MutableClock`;
  - `FakeWorldTaskExecutor`, moved from `EmbodiedAgentRuntimeTest` to a
    shared test fixture;
  - `overrideSessionSnapshotForTests(...)`, LAN-host or remote as the
    scenario needs.

  API:

```java
harness.tick(n);                 // n × runtime.onClientTick(null); clock += 50 ms per tick; then settle
harness.advanceWallClock(Duration d); // simulate tick-debug pause: time passes, no ticks
harness.chat(sender, message);   // runtime.onChatReceived; nearby players via injectNearbyPlayerForTests
harness.event(type, payload);    // appendEventForTests + drain on the next tick
harness.reflex(SurvivalReflexEvent e);
harness.transcript();            // WakeTranscript built from backend requests + debug timeline + events
```

  `settle()` waits until each wake submitted in the tick has either reached
  the backend or been recorded as dropped. Use `awaitRequests` with a
  1-second timeout, the same pattern as `BlockingLlmBackend`. Scenarios never
  sleep for time to pass; they move the clock.

- [x] **Step 3: Transcript content** (`WakeTranscript`, serialized with Gson in pretty-print):

```json
{
  "scenario": "addressed_chat_while_turn_in_flight",
  "requests": [
    {"index": 1, "tick": 3, "generation": 1, "phase": "INITIAL", "owner": "controller",
     "observe": {"after": 0, "through": 4, "baseline": true,
                 "events": [{"seqNo": 4, "type": "social.player_addressed_agent"}], "notices": []},
     "newMessages": [{"role": "user", "kind": "USER_TURN", "text": "Alex: @agent hello"}],
     "prefix": {"systemSha": "…", "toolsSha": "…"}}
  ],
  "wakeAudit": [{"tick": 3, "kind": "submitted", "path": "W1", "triggerTypes": ["CHAT"], "origins": ["DIRECT_GUIDANCE"]}],
  "events": [{"seqNo": 1, "tick": 1, "type": "session.world_loaded"}]
}
```

  - `newMessages` holds the canonical messages after the previous request's
    last assistant message, which is the model-visible delta. `observe`
    holds the parsed fields of the runtime-issued `observe` tool result.
    `current` is left out except `decisionOwner` and `actuatorOwner`.
  - The system prompt and tool schemas are stored only as SHA-256, so an
    unrelated prompt change shows up as a one-line diff.
  - Normalization:
    - `call_observe_\d+` → `call_observe_N`
    - UUIDs → `<uuid>`
    - `@r\d+` → `@rN`
    - numbers that are wall-clock times → `<ms>`
    - time-beacon text → `<time>`

- [x] **Step 4: Golden compare.** Compare with
  `src/test/resources/planner/wakes/<scenario>.golden.json`. When
  `AIRICRAFT_UPDATE_WAKE_GOLDENS=1` is set, write the file instead and fail
  with "golden updated", so updates are never silent. On a mismatch, report
  a unified diff. Update command:
  `AIRICRAFT_UPDATE_WAKE_GOLDENS=1 ./gradlew test --tests '*WakeCharacterization*' --rerun`.
- [x] **Step 5: Harness self-tests.**
  - Two identical scenario runs in one JVM produce identical transcripts.
    This catches static counters such as `PlannerObservation.NEXT_CALL`
    leaking through normalization.
  - A deliberately changed prompt changes only `systemSha`.
- [x] **Step 6: Commit.**

### Task 6: Runtime-level characterization scenarios

**Files:**
- Create: `WakeCharacterizationTest.java`, plus one golden file per scenario

Each scenario is one `@Test` using the harness, and ends with
`transcript().assertMatchesGolden("<name>")`. Where the scenario gives an
expected outcome, assert it explicitly as well, so a wrong golden cannot
pass review unnoticed.

- [x] `addressed_chat_idle`: `@agent hello` from a nearby player. Expect one W1 submission with `DIRECT_GUIDANCE`.
- [x] `addressed_chat_while_turn_in_flight`: hold the first request, then chat. Expect supersede: the second request, and the first generation marked superseded.
- [x] `ambient_chat_proactive_on` and `ambient_chat_proactive_off`: a player without `@agent`. Expect a coalesced `ambient_player_chat` wake only when proactive mode is on.
- [x] `pickup_and_craft_idle`: `onPlayerPickedUpItem` and `onPlayerCraftedItem`. Expect generic-wake prompts.
- [x] `pickup_during_mining_job`: start a mining job through `executePlannerAction` with `FakeWorldTaskExecutor`, then pick up. Expect `default-mining-pickup-semantic-only` and no wake.
- [x] `damage_outside_reflex`: `onPlayerHealthUpdated(true, 20, 16)`. Expect a DAMAGE wake.
- [x] `death_then_respawn`: a fatal health update, then `onPlayerRespawned`. Expect no autonomous wake until respawn, and the conversation preserved.
- [x] `navigation_failure_cascade`: `navigate_to`, then a FAILED terminal from `FakeWorldTaskExecutor`, following the precedent in `plannerReceiptAndWorkInspectionShareDirectNavigationIdentityThroughFailure`. This pins every W2 submission and drop.
- [x] `reflex_started_then_resolved_with_hold`: inject `reflex.started`, then `reflex.resolved` carrying a `holdId`. Pins the W1+W2 double path (D4).
- [x] `item_offer_and_physical_episode`: `harness.event("social.item_offered", …)` and `player.physical`, both with and without reflex actuation.
- [x] `smelting_output_and_graph_failure`: `smelting.output_ready`, then `action_graph.goal_terminal` with state `FAILED`.
- [x] `idle_think_after_delay`: LAN-host session, idle, clock advanced past `initialDelaySeconds` (30 s by default). Expect W5.
- [x] `idle_think_invalidated_by_action_goal`: queue idle think while a turn is held, start an action goal, release. Expect the IDLE_THINK trigger to be gone.
- [x] `tick_debug_pause_then_resume`: idle, `advanceWallClock(5 min)` with no ticks, then one tick. Pins D7.
- [x] `degraded_mode`: inject timeouts until degraded, then chat. Expect no request, and the fixed character line queued.
- [x] `external_driver`: set `airicraft.codexDriver=true` before construction and restore it in `finally`. Expect no autonomous requests.
- [x] `evaluation_chat_then_suppression`: `emitEvaluationChat`, then `finishEvaluation()`, then autonomous events. Expect the listed types suppressed. If `prepareForEvaluation` needs a live client, cover only the reachable part and note the gap in the test comment.

If a scenario turns out not to be reachable with a null client, move it to
Task 7 at the dialogue level. Record the move in the test class Javadoc;
don't drop it silently.

- [x] Run `./gradlew test --tests 'ai.moeru.airicraft.agent.WakeCharacterizationTest'`. Expected: PASS against the reviewed goldens. Review every golden file in the PR.

### Task 7: Dialogue-level characterization scenarios

These cover the queue and gate logic that is easier to drive directly.

**Files:**
- Create: `DialogueWakeCharacterizationTest.java`, plus golden files

Use `newDialogueRuntime(backend, …, goalStore)` from `DialogueRuntimeTest`,
moved into a shared fixture, and the same `WakeTranscript`, built from the
backend and a recording `WakeAuditSink`.

- [x] `work_stalled_attention_during_accepted_work`: `observeAcceptedWork(RUNNING)`, then `queueTaskAttention`. Expect W3 to submit while an ordinary W2 wake is held.
- [x] `task_wakeups_superseded_by_guidance`: queue W2 wakes, then an addressed chat. Expect `planner.internal_task_update_superseded` events.
- [x] `task_wakeup_mission_changed`: the mission id changes between queueing and submitting.
- [x] `goal_continuation_idle_and_busy`: an active goal with `workIdle` true and then false. Expect the 20-tick cadence and the guards.
- [x] `blocked_goal_reconsider`: `block_planner_goal` with `reconsiderEvents: ["work.changed"]`. A `work.changed` event wakes; an unrelated event is dropped (`G4.blocked_goal`, `G5.blocked_irrelevant`).
- [x] `delegation_start_and_return`: `configureDelegation`, start, then return. Expect the W6 prompt and ownership.
- [x] `safety_hold_awaiting_decision`: `updateSafetyContext(epoch, "hold-1", false)` plus idle. Expect the safety-hold continuation text.
- [x] `stale_safety_response_rejected`: bump the safety epoch while a turn is held. Expect a stale rejection and no replay.
- [x] Run `./gradlew test --tests 'ai.moeru.airicraft.agent.dialogue.DialogueWakeCharacterizationTest'`. Expected: PASS.

### Task 8: Defect probes D1–D8

Each probe pins current behaviour and states its verdict in the test name
and Javadoc: CONFIRMED, REFUTED or NOT REPRODUCIBLE. Record the verdicts in
the spec in Task 12.

**Files:**
- Create: `WakeDefectProbeTest.java`
- Modify: `DialogueRuntimeTest.java`, `IdleIdeaSchedulerTest.java`,
  `PlannerDecisionContextTest.java`, `PlannerContextAggregatorTest.java`
  (a probe per class, where it belongs)

- [x] **D1, two sequence spaces with one cursor** (dialogue level). Put 10 events in a raw buffer R and submit a W2 wake with `poll(t, R)`, so the cursor advances to 10. Then append a pickup to a separate planner buffer P (seq 1) and call `onPlannerTrigger(PICKUP, …, P)`. Check whether the pickup's legacy notice is present in the second request.
- [x] **D2, duplicate evidence** (runtime). An idle pickup followed by an addressed chat. Check whether the request carries the pickup both as an `observe.notices` entry and in `observe.events`.
- [x] **D3, policy applies to only part of the pipeline** (runtime). Run `runtime.execute(update_event_policy {upserts:[{match:{eventType:"pickup.item_picked_up"}, effect:"ignore"}]})`, then a pickup and an addressed chat. Check whether `observe.events` still contains the pickup.
- [x] **D4, double wake paths** (runtime). Covered by `navigation_failure_cascade` and `reflex_started_then_resolved_with_hold`. Assert the number of `submitted` audit entries per fact against the number of model requests.
- [x] **D5, wrong wake reference** (runtime). In the cascade, compare each W2 audit `eventSequence` with the sequence of the task or work event that caused it. Check whether the rendered message fell back to "Work changed.".
- [x] **D6, the ring used as a state channel.** Try to reach `refreshWorkHistory`'s `EATING` lookup through `WorkHistory` with a synthetic EATING work entry. If that is not reachable without a client, record NOT REPRODUCIBLE with the reasoning: the scan runs every tick, so eviction needs more than 512 events in a single tick. This is a design smell, fixed by the Phase 1 subscriber.
- [x] **D7, pause counted as idle.** A unit test on `IdleIdeaScheduler`: call `tick(true, 0, t0)`, then `tick(true, 1, t0 + 300_000)`, and check whether it fires immediately. The runtime scenario `tick_debug_pause_then_resume` confirms the same end to end.
- [x] **D8, visibility and coalescing.**
  - `PlannerDecisionContext` observations exclude `social.item_offered` and `action_graph.goal_terminal`.
  - `PlannerContextReducer.enqueueTrigger` with two `item_offer:<same uuid>` triggers keeps only the second text.
- [x] Run `./gradlew test --tests '*WakeDefectProbe*' --tests '*IdleIdeaSchedulerTest' --tests '*PlannerDecisionContextTest' --tests '*PlannerContextAggregatorTest' --tests '*DialogueRuntimeTest'`. Expected: PASS, with each probe pinning what it found.

### Task 9: Wake ledger tool

**Files:**
- Create: `scripts/wake_ledger.py`, `scripts/tests/test_wake_ledger.py`, `scripts/tests/fixtures/wake-ledger/run-1/`

The input is a `RuntimeFlightRecorder` output directory. Evaluation writes
one per scenario (see `recording-start.json` → `outputDir`), and automatic
playtests write one under `automatic_playtest/`. The files used:

| File | Used for |
|---|---|
| `planner-calls.jsonl` (planner-call v1) | The list of requests: `turnId`, `plannerAttempt.phase`, `timeline.submitted`/`applied` (server ticks), and canonical `request.messages`, from which the `observe` tool result gives `tick`, `serverTick`, `afterEventSequence`, `throughEventSequence`, `events` and `decisionOwner` |
| `events.jsonl` | Event identity and agent tick for each `seqNo` |
| `debug-timeline.jsonl` | `planner_wake` audit entries (Task 3) and `event_pipeline/route` entries |
| `llm-calls.jsonl` | Token usage per planner request |

- [x] **Step 1: Output `airicraft.wake-ledger.v1`.** The output is one JSON
  document per run. It contains:
  - `requests[]`: seq, dispatch agent tick and server tick, owner, phase, after and through sequence, the new planner-visible events, whether a user turn is present, and the attributed wakes. Wakes are the `submitted` audit entries since the previous INITIAL request; if none exist, the wake is `unknown` and the request text's trigger prefix is kept as a hint.
  - `drops[]`
  - `metrics`

  Align the agent and server clocks with the `(tick, serverTick)` pair
  carried by every `observe` payload.
- [x] **Step 2: Metrics.** These are the spec's 4.10 definitions made exact:
  - `requestsPerMinute`: INITIAL-phase gameplay requests per 1,200 server ticks, split by owner and by wake path.
  - `followUpsPerTurn`: TOOL_FOLLOW_UP calls per INITIAL call.
  - `emptyWakes`: INITIAL requests with no new planner-visible events, no user turn and no baseline refresh, split by path.
  - `outcomeLatencyTicks`: for each terminal `work.changed` event, the `observe.tick` of the first request whose `throughEventSequence` is at least the event's sequence, minus the event tick. Report p50, p90 and max.
  - `chatReplyLatencyTicks`: from `social.player_addressed_agent` to the first request incorporating it, and to `timeline.applied`.
  - `droppedWakes`: counts per gate.
  - `tokensPerHour`: from `llm-calls.jsonl` planner records, per 72,000 server ticks.
  - `timelineGaps` and `eventGaps`: present or absent, so metrics from incomplete recordings are flagged.
- [x] **Step 3: CLI.** Three commands:
  - `python3 scripts/wake_ledger.py ledger <run-dir> -o ledger.json`
  - `python3 scripts/wake_ledger.py summarize <run-dir>...`: a Markdown table across runs, including the run-to-run spread
  - `python3 scripts/wake_ledger.py diff a.json b.json`: per-request wake-path and evidence differences, for Phase 2 replay
- [x] **Step 4: Tests.** Build a small hand-written fixture run with 3
  requests, one W2 drop, one terminal work event and one chat. Assert each
  metric, the clock alignment, the `unknown` fallback, and that `diff`
  reports a changed path. Run
  `python3 -m unittest scripts.tests.test_wake_ledger`, the same style as the
  existing script tests. Expected: PASS.
- [x] **Step 5: Commit.**

### Task 10: GraalJS rule-engine spike (opt-in)

**Files:**
- Create: `RuleEngineSpikeTest.java`, `src/test/resources/rules-spike/{kernel,lib,attention}.js`,
  `docs/experiments/<date>-attention-rule-engine-spike.md`

- [x] **Step 1: Draft the modules.**
  - `lib.js`: `leakyBucket(state, {capacity, leakPerTick, cost}, tick)`,
    `slidingWindow`, `cooldown`, `hourlyCap`, `cluster`, and
    `seededRandom(seed)`.
  - `attention.js`: a representative `step(input, state, lib)`. It covers
    ownership checks on about six types, the blocked-goal gate, matching
    against a planner-rules table, type defaults, and the leaky bucket
    applied to NORMAL and LOW. It also has a small `salience` pass over
    candidates: garbage-list filtering and block clustering. It should be a
    realistic workload, not the Phase 2 rules.
- [x] **Step 2: Kernel with determinism overrides**

```js
// kernel.js -- evaluated once per context
(() => {
  let rule, lib;
  return {
    load(libFactory, ruleFactory) { lib = libFactory(); rule = ruleFactory(lib); },
    run(inputJson, stateJson) {
      const input = JSON.parse(inputJson);
      const fixedNow = input.tick * 50;
      Date.now = () => fixedNow;                       // also shadow `new Date()` -- verify in Step 4
      Math.random = lib.seededRandom(input.seed);
      const out = rule.step(input, stateJson ? JSON.parse(stateJson) : {}, lib);
      return JSON.stringify(out);
    }
  };
})()
```

- [x] **Step 3: Harness.** Build the `Context` with **exactly** the
  `GraalPolicyInvocation` builder options: `HostAccess.NONE`, no IO,
  threads, processes, native, environment or polyglot access, null streams,
  `engine.WarnInterpreterOnly=false`, and a `statementLimit`. Run it on a
  single-thread daemon executor, like the production worker. Call
  `context.resetLimits()` before each step. Put the test behind
  `@EnabledIfEnvironmentVariable(named = "AIRICRAFT_RULE_SPIKE", matches = "1")`.
- [x] **Step 4: Measure.** Print a JSON summary and write it to
  `build/rule-spike/result.json`.
  - Context build plus module evaluation time.
  - First-step latency (cold), then p50, p95, p99 and max over 2,000
    steps (warm). The input is 20 events and 50 candidates, sampled from the
    Task 11 `events.jsonl` if available, otherwise synthetic with realistic
    payload sizes.
  - The same measurement including the handoff to the worker thread and
    back (`supplyAsync(...).get()`), because that is the path production
    will take.
  - Whether 2,000 steps complete under statement limits of 50,000 and
    200,000.
  - Input, output and state JSON sizes. Also check that state stays under
    16 KiB after 2,000 steps with the bucket and windows active.
  - Heap: `MemoryMXBean` used heap after GC, before the context, after
    building it, and after 10,000 steps.
  - Determinism: running the same input and state twice gives identical
    output, and `Date.now()`, `new Date()` and `Math.random()` inside the rule
    are tick-derived or seeded.
  - Failure paths: a throwing rule, an infinite loop (statement limit), and
    oversized state. Each must fail within the deadline without affecting
    later steps.
- [x] **Step 5: Run** with
  `AIRICRAFT_RULE_SPIKE=1 ./gradlew test --tests '*RuleEngineSpikeTest' --rerun`
  on the JBR 21 toolchain (the default), on a developer machine
  representative of playtests. Run it three times, and report the median
  run.
- [x] **Step 6: Decide.** These thresholds are proposed; record the chosen
  outcome.
  - **Proceed as designed** if warm p99 including handoff is at most 2 ms,
    the cold first step is at most 100 ms, and building the context is at
    most 1 s at runtime start.
  - **Proceed with tighter caps** if warm p99 is between 2 and 10 ms: cut
    input to 16 events and 32 candidates, re-measure, and write the caps
    into the spec's 4.12.
  - **Stop and revisit 4.12** if it is above 10 ms. Options are evaluating
    every N ticks, or a Graal JIT toolchain. Raise this with the team before
    Phase 1.
- [x] **Step 7: Write the experiment doc** with the machine, JVM, numbers,
  determinism results and the decision, then commit. The draft JS stays in
  test resources; Phase 2 promotes it to
  `src/main/resources/airicraft/rules/`.

### Task 11: Baseline metrics

This task needs slices A–D merged. It uses real model calls, so budget for
the cost of about three evaluation batches.

- [x] **Step 1: Build** from the committed Phase 0 PR branch (merge pending): `./gradlew build`. Expected: SUCCESS.
- [ ] **Step 2: Run the evaluation batch twice** to measure run-to-run
  spread:

```sh
scripts/run-evaluation-scenarios --no-recorder --jobs 3 \
  --scenario bread-cooperative-watch --scenario farm_easy --scenario farm_from_scratch \
  --scenario farm_harder --scenario get_water --scenario iron-pickaxe \
  --scenario pickup --scenario sea_grass --scenario underground
```

  Run it a third time if the two runs disagree on any pass/fail.

- [x] **Step 3: Run one automatic playtest** with the world, objective and
  recording profile of the most recent documented automatic playtest, so the
  results are comparable (`docs/automatic-playtest.md`).
- [ ] **Step 4: Compute the ledgers.**
  `python3 scripts/wake_ledger.py summarize <each scenario outputDir> <playtest dir> > /tmp/wake-baseline.md`.
  Check that `timelineGaps` and `eventGaps` are absent. If any are present,
  fix the recorder poll cadence (Task 3 Step 6) and re-run the affected
  scenario.
- [ ] **Step 5: Write `docs/experiments/<date>-planner-wake-baseline.md`.**
  Include the commit, provider and model, per-scenario pass/fail and
  metrics, and the spread. Also propose the **Phase 2 tolerance**:
  - pass/fail must not be worse than the worst baseline run;
  - planner turns and requests per minute must stay within the observed
    spread plus 10%;
  - wake-path proportions may change only where a characterized defect fix
    explains it.
  
  Commit only the document; the run bundles stay in the ignored `eval-output/`.

### Task 12: Close out Phase 0

- [ ] **Step 1: Update the spec.**
  - D1–D8 verdicts in 2.6.
  - Appendix A regenerated from the inventory.
  - The spike numbers and caps, and the step deadline, in 4.12.
  - The tolerance and baseline link in the Phase 2 exit criteria.
  - Tick the Phase 0 checklist.
- [ ] **Step 2: Run the full build** with `./gradlew build`. Expected:
  SUCCESS. The characterization, defect-probe and inventory tests run in
  the normal build; the spike does not.
- [x] **Step 3: Update `AGENTS.md`.** Add a Behavior Notes line: wake
  behaviour is pinned by golden transcripts in
  `src/test/resources/planner/wakes/`, updated with
  `AIRICRAFT_UPDATE_WAKE_GOLDENS=1`, and every golden diff must be reviewed.
- [ ] **Step 4: Commit and open the PR(s)** for the remaining slices.

## Exit criteria

- On `dev`, the inventory guard, the characterization suite (Tasks 6–7) and
  the defect probes are green, and the goldens have been reviewed.
- `planner_wake` audit entries appear in `debug-timeline.jsonl` for every
  planner request in the baseline runs, and no request is attributed as
  `unknown`.
- The baseline and Phase 2 tolerance are recorded; the spike decision is
  recorded, and so are the D1–D8 verdicts.
- ADR-0003 and the `CONTEXT.md` vocabulary are merged.

## Risks in this phase

- **Scenarios that need a live client.** Several producers (item offers,
  physical episodes, the watchdog, slow mining) sample a real
  `MinecraftClient`. The injection hooks cover their events, but not their
  sensing logic. Sensing stays covered by the existing observer unit tests,
  and Phase 4 adds sensor tests.
- **Flaky asynchronous waits.** The backend responds on planner executor
  threads. The harness only waits for "request arrived" or "recorded as
  dropped" with bounded timeouts, and never waits for time to pass.
- **Golden churn from unrelated work.** Prompts and tools are hashed, not
  embedded. If churn becomes a problem during the refactor, narrow
  `newMessages` to trigger text, notices and observe fields.
- **Baseline noise** from model nondeterminism. That is why the batch runs
  twice and the tolerance is set from the observed spread instead of a fixed
  number.
