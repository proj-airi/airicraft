# Planner Event System Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every agent event one declared type, one publishing path and
one bounded log, without changing when the planner wakes or what it sees.
Every event records which component published it and, where known, what
caused it. The observer resets are consolidated into one lifecycle dispatch,
and the ring-scan state channel from D6 is replaced with a subscriber.

**Architecture:** Six pieces. Each one lands without changing the Phase 0
goldens:

1. **`EventCatalog`**: the Java source of truth for every event id and
   dynamic prefix. It holds the family, the declared producers, today's G1
   routing profile, and today's G10 observe visibility. The routing table in
   `EmbodiedAgentRuntime` (`createEventRoutingProfiles`, line 5108) is derived
   from it. The Phase 0 inventory JSON becomes its checked-in golden.
2. **Event provenance**: `SemanticEvent` gains `source` and `cause`. The JSON
   change is additive and the model-visible `observe` shape is unchanged.
3. **`AgentEventLog`** and **`AgentEventBus`**: the log has one sequence space
   and the `SemanticEventBuffer` query API. The bus validates types against
   the catalog, stamps provenance, and runs synchronous subscribers. A strict
   mode fails tests on undeclared types and off-thread publishes.
4. **`EventIngressQueue`**: the bounded off-thread queue behind
   `pendingInteractions`, generalized and still drained at tick start.
5. **`LifecycleBoundary` dispatch**: one ordered dispatch that replaces the
   seven hand-written observer reset blocks.
6. **`FoodOutcomeIndex`**: a bus subscriber that replaces the `food.eaten`
   ring scan and fixes D6.

**Tech Stack:** Java 21 on the JBR 21 toolchain, JUnit 5, Gson, and the
Phase 0 characterization suite (wake goldens, `EventInventoryTest`,
`WakeDefectProbeTest`) plus the wake ledger and evaluation harness.

**Spec:** [`specs/2026-09-26-planner-perception-and-wake-design.md`](../specs/2026-09-26-planner-perception-and-wake-design.md),
sections 4.2, 4.4, 4.5 (lifecycle only), 4.9 (ingress only), 4.11 and 6.
Terms such as W1–W9, G1–G11, E1–E3 and D1–D8 refer to that document.
**Previous phase:** [`plans/2026-09-26-planner-event-system-phase-0.md`](2026-09-26-planner-event-system-phase-0.md).

---

## Implementation status (2026-09-27)

Tasks 1–10 are implemented on stacked Phase 1 slices. Task 11's full build
passed with 1,770 root tests (3 skipped), 95 wrapper tests, 30 evaluator tests,
and 20 JourneyMap compatibility tests; all had zero failures/errors. The 27
wake goldens remain unchanged. The normal-client smoke retained sourced events
and reported zero undeclared, unknown-source, off-thread, and subscriber-failure
counters. The accepted inventory correction adds the existing `DialogueRuntime`
producer for `task.notice`; it is separate from the unchanged goldens. See the
[verification note](../../experiments/2026-09-27-planner-event-system-phase-1-check.md)
for the artifact hashes, live limits, and evaluation evidence.

The [paired same-host follow-up](../../experiments/2026-09-27-planner-event-system-paired-ab.md)
is complete: 12 terminal trials at frozen heads `44acc6bc` (#81) and `34184be7`
(#88), with wall-clock and server-tick rates reported over identical windows.
It supersedes the original cross-session comparison as current rate evidence
for `farm_easy`, `sea_grass`, and `bread-cooperative-watch`. Farming rates were
higher on #88 in both pairs, bread rates lower, and seagrass differences mixed;
no event/timeline gaps or unknown wake attribution occurred. This small sample
does not establish general regression or equivalence.

Full nine-scenario evaluation parity remains **unverified**: the other six
scenarios were not rerun in the paired session, and the original two interrupted
scenarios still lack terminal results. No threshold was relaxed. The source
review found no Critical or Important Phase 1 defect; source/build/smoke evidence
and the completed paired follow-up can be reviewed separately from the remaining
full-suite acceptance evidence.

## Scope decisions

The spec's Phase 1 bullets leave some choices open. The recommendations below
are the ones that keep this phase behaviour-preserving. Anything outside them
belongs to a later phase.

| # | Question | Recommendation | Why |
|---|---|---|---|
| P1 | Does routing move to publish time? | **No.** `AgentEventPipeline` stays a cursor consumer of the log. It is still drained at today's 21 `drainEventPipeline()` call sites, and it still feeds `plannerEventBuffer`. The spec's "derived subscriber" is a cursor subscriber in this phase. | G2/G3 read runtime state at drain time: event policy, reflex ownership, pending craft results. Routing at publish time would move those decisions and change the goldens. Phase 2 owns tick-phased routing (4.9). |
| P2 | What happens to `DialogueRuntime` writes that land in `plannerEventBuffer` today? | **Keep them planner-feed-only.** W1, W4, W5 and W7 hand `DialogueRuntime` the planner buffer. On those paths `planner.internal_task_update_superseded` and `DialogueCore` effects such as `planner.degraded_blocked` go into that buffer and never reach the raw log. Wrap the planner buffer in a named `PlannerFeedPublisher` and pin the behaviour with a test. | Moving these writes into the log would shift raw sequence numbers and the golden `events`. They are part of D1, and Phase 3 fixes them when E2 and `plannerEventBuffer` retire. |
| P3 | Is `SemanticEvent` renamed to `AgentEvent`? | **Not yet.** Extend the record, and add a 5-argument constructor so the 39 test constructions and all readers compile unchanged. `AgentEvent` stays the prose term (`CONTEXT.md`). Rename mechanically in Phase 3, when `SemanticEventBuffer` retires. | A rename now touches ~30 files for no behaviour gain and collides with feature work in `EmbodiedAgentRuntime`. |
| P4 | Which catalog fields land now? | **Id or prefix, family, producers, routing (G1), observe visibility (G10 mirror, asserted but not enforced).** Urgency, delivery, `policyProtected`, coalescing keys and `requiredFields` land in Phase 2, together with the code that reads them. | Fields nothing reads would drift from reality before Phase 2 could check them. |
| P5 | How much of `cause` is filled? | **Only where the producer already holds the reference** (Task 8 lists the cases). Every other event gets `null`. | Adding new plumbing, for example damage to reflex, would change producer code paths. The catalog test reports coverage, so later phases can extend it. |
| P6 | Which behaviour changes are allowed? | **Two.** The D6 fix (`EATING` resolves even after a flood), and the additive `source`/`cause` fields in bridge, recorder and dashboard JSON. | These are the spec's Phase 1 items. Neither changes a golden. |
| P7 | Does the blocked-goal cursor scan (`DialogueRuntime.poll`, line 649) become a subscriber? | **No.** It queues W2 wakeups, and a subscriber would change the deque order. It is a Phase 2 scheduler input. | It is a wake source, not a state channel. |
| P8 | What does strict mode do outside tests? | **Warn once per type and publish anyway.** Tests set `airicraft.events.strict=true`, which throws. | An undeclared id from a mod integration must never drop evidence in a live session. |

Please confirm or override P1–P8 before Task 1 starts. They change the task
boundaries.

## Ground rules

1. **The goldens do not change.** After every task:
   ```bash
   ./gradlew --no-daemon :test --tests 'ai.moeru.airicraft.agent.Wake*' \
     --tests 'ai.moeru.airicraft.agent.dialogue.DialogueWake*' --rerun
   git diff --exit-code src/test/resources/planner/wakes
   ```
   Never run with `AIRICRAFT_UPDATE_WAKE_GOLDENS=1` in this phase. A golden
   diff means a timing or ordering change: find and remove it.
2. **Intended changes get their own commit.** The D6 probe flip (Task 10) and
   the provenance fields (Task 2) are the only expected test-expectation
   changes. Each goes in its own commit, and the message names the change.
   Every other commit says "no behaviour change".
3. **Mechanical migrations stay mechanical.** Tasks 6–7 change the call target
   and add a source. They never reorder statements, merge appends, or change
   a payload map.
4. **Prerequisites** are as in `AGENTS.md`: a JDK 25 Gradle JVM, the JBR 21
   toolchain, submodules, and `ffmpeg`. Run root tests with `:test`. Plain
   `test` also runs `:wrapper:test`, which fails with "No tests found" when
   filtered.
5. **Base branch.** Start after PR #81 merges into `dev`. If it has not
   merged, stack a new branch on `claude/hopeful-gauss-1gjr8v` and rebase
   once it lands.
6. **PR slices.** Each slice merges independently, in order:
   - A: Tasks 1–2 (catalog, provenance fields)
   - B: Tasks 3–5 (log, bus, ingress queue; not wired yet)
   - C: Tasks 6–8 (wiring and migration)
   - D: Task 9 (lifecycle dispatch)
   - E: Task 10 (D6)
   - F: Task 11 (verification and docs)

   D can go in parallel with C. E needs C.

## File Structure

### New files

- `src/client/java/ai/moeru/airicraft/agent/events/EventFamily.java`: `PERCEPT`, `EXECUTION`, `INTERNAL`
- `src/client/java/ai/moeru/airicraft/agent/events/EventVisibility.java`: `PLANNER`, `DIAGNOSTIC`
- `src/client/java/ai/moeru/airicraft/agent/events/EventTypeSpec.java`: one catalog entry
- `src/client/java/ai/moeru/airicraft/agent/events/EventCatalog.java`: all entries, lookup, the derived routing table
- `src/client/java/ai/moeru/airicraft/agent/events/EventCause.java`: cause reference
- `src/client/java/ai/moeru/airicraft/agent/events/EventView.java`: read API (query, latest, containsType, …)
- `src/client/java/ai/moeru/airicraft/agent/events/EventPublisher.java`: write API with provenance
- `src/client/java/ai/moeru/airicraft/agent/events/EventStream.java`: `EventView & EventPublisher`, the parameter type for producers that also read
- `src/client/java/ai/moeru/airicraft/agent/events/AgentEventLog.java`: bounded ring, one sequence space
- `src/client/java/ai/moeru/airicraft/agent/events/AgentEventBus.java`: validation, provenance, subscribers, thread affinity
- `src/client/java/ai/moeru/airicraft/agent/events/EventIngressQueue.java`: bounded off-thread queue with a drop counter
- `src/client/java/ai/moeru/airicraft/agent/events/PlannerFeedPublisher.java`: named adapter for the P2 planner-buffer writes
- `src/client/java/ai/moeru/airicraft/agent/perception/LifecycleBoundary.java`: the boundary enum
- `src/client/java/ai/moeru/airicraft/agent/perception/LifecycleDispatcher.java`: ordered participants
- `src/client/java/ai/moeru/airicraft/agent/food/FoodOutcomeIndex.java`: D6 subscriber
- Tests next to each class: `EventCatalogTest`, `AgentEventLogContractTest` (runs against both `SemanticEventBuffer` and `AgentEventLog`), `AgentEventBusTest`, `EventIngressQueueTest`, `LifecycleDispatcherTest`, `FoodOutcomeIndexTest`, `EventProvenanceSerializationTest`

### Modified files

- `EmbodiedAgentRuntime.java`:
  - the `eventBuffer` field (232) becomes the bus and log;
  - the 51 appends are migrated;
  - the routing table (5108) is derived from the catalog;
  - `pendingInteractions` (338) becomes `EventIngressQueue`;
  - the reset blocks (505, 579, 586, 756, 865, 1232, 2003) become dispatches;
  - the `EATING` scan (2819) uses `FoodOutcomeIndex`.
- `AgentEventPipeline.java`: reads an `EventView`, publishes `policy.event_intervened` with a cause
- `SemanticEvent.java`: `source` and `cause` components, plus a 5-argument constructor
- `SemanticEventBuffer.java`: implements `EventStream` (planner feed, tests)
- `SessionRuntime.java`, `ChatIngestService.java`, `NearbyPlayerTracker.java`, `FollowCapability.java`: `SemanticEventBuffer` parameters become `EventPublisher`
- `DialogueRuntime.java`: `SemanticEventBuffer` parameters become `EventStream`; `applyEffects` and the continuation writes stamp a source
- `build.gradle`: `systemProperty 'airicraft.events.strict', 'true'` on the root `test` task
- `src/test/java/ai/moeru/airicraft/agent/EventInventoryTest.java`: asserts catalog ≡ inventory
- `src/test/java/ai/moeru/airicraft/agent/WakeDefectProbeTest.java`: the D6 probe flips (Task 10)
- `scripts/wake_ledger.py`: optionally carries `source`/`cause` into `newEvents`
- Spec, Phase 0 plan index, `AGENTS.md` key files (Task 11)

### Read before editing

- Spec sections 2.1, 2.5 (problems 4–6), 2.6 (D1, D6), 4.4 and 8.
- `EmbodiedAgentRuntime.java`: `tickClient` (563–700), `processSurvivalReflexEvents` (1083), `recordStalePlannerRejections` (1100), `drainActionGraphCoordinatorEvents` (2418), `drainInteractionEvidence` (2770), `refreshWorkHistory` (around 2795–2830), `drainEventPipeline` (4563), `createEventRoutingProfiles` (5108).
- `AgentEventPipeline.java` (all of it), `SemanticEventBuffer.java` (all of it).
- `DialogueRuntime.java`: `pollPolicyContinuation` (102–137), `poll` (628–660), `submitPlannerTrigger`, `supersedePendingInternalTaskUpdates` (960–990), `applyEffects` (1042).
- Consumers that must not change: `PlannerDecisionContext.observation` (builds explicit maps, so it is unaffected by new fields), `WakeTranscript.capture` (explicit maps), `RuntimeFlightRecorder.drainEvents`, `DashboardObservationCollector.captureEventHistory`, `ModBridgeServer.createRecentAgentEventsResponse`, `EvaluationAddonRuntime` (`recentEvents(null)`, `semanticEventContains`), and the wrapper's `agent events recent` view (filters `seqNo`, `tick`, `timestampMs`, `type` unless `--verbose`).

---

### Task 1: Event catalog

**Files:**
- Create: `EventFamily.java`, `EventVisibility.java`, `EventTypeSpec.java`, `EventCatalog.java`, `EventCatalogTest.java`
- Modify: `EmbodiedAgentRuntime.java` (`createEventRoutingProfiles`), `EventInventoryTest.java`

- [ ] **Step 1: Types.**
  ```java
  public enum EventFamily { PERCEPT, EXECUTION, INTERNAL }
  public enum EventVisibility { PLANNER, DIAGNOSTIC }

  /** One declared event id, or a dynamic family when {@code prefix} is true. */
  public record EventTypeSpec(String id, boolean prefix, EventFamily family, Set<String> producers,
      EventVisibility observeVisibility, EventRoutingProfile routing) { }
  ```
  `routing` is `EventRoutingProfile.rawOnly(id)` for types without a G1
  profile, which matches `AgentEventPipeline`'s `getOrDefault`.
- [ ] **Step 2: `EventCatalog.defaults()`.** Declare all 92 exact ids and the
  two prefixes (`interaction.`, `policy.continuation.`), transcribed from
  `src/test/resources/planner/wakes/event-inventory.json`. Keep one
  declaration per line, grouped by namespace, so later phases diff cleanly.
  - **Family:** assign by namespace, with explicit exceptions.
    - `PERCEPT`: `social.*`, `combat.*`, `pickup.*`, `crafting.*`,
      `player.physical`, `player.died`, `player.respawned`.
    - `EXECUTION`: `task.*`, `work.*`, `action_graph.*`, `smelting.*`,
      `food.*`, `lighting.*`, `follow.*`, `mission.*`, `interaction.*`.
    - `INTERNAL`: everything else (`planner.*`, `policy.*`, `reflex.*`,
      `session.*`, `objective.*`, and the other `player.*` records).
  - **Lookup:** `find(String type)` tries an exact match first, then the
    longest matching prefix.
  - **`routingProfiles()`** returns the exact-id profiles that are not raw
    only. That is today's 46-entry G1 map.
- [ ] **Step 3: Derive G1.** Replace the body of `createEventRoutingProfiles()`
  with `EventCatalog.defaults().routingProfiles()`. Keep
  `eventRoutingProfilesForTests()`.
- [ ] **Step 4: Tests.**
  - **`EventInventoryTest`:** add `catalogMatchesInventory`. For every
    inventory entry, the catalog has the same id or prefix, the same producer
    set, `observeVisibility == PLANNER` iff `observeVisible`, and the same
    profile. The reverse holds too: no catalog entry is missing from the
    inventory.
  - **Existing guards:** keep `routingProfilesMatchInventory` and
    `everyEmittedEventTypeIsInventoried`. The source-scan guard still catches
    ids that no test publishes.
  - **`EventCatalogTest`:**
    - every spec has a family;
    - prefixes end with `.`;
    - lookup precedence holds;
    - `observeVisibility` agrees with `PlannerDecisionContext.relevant` for
      every exact id and a sample id per prefix.
- [ ] **Step 5: Run** `EventInventoryTest`, `EventCatalogTest`, the wake
  suite, and `AgentEventPipelineTest`. Expected: all green, no golden diff.
  Commit: `feat(events): add event catalog; derive routing profiles (no behaviour change)`.

### Task 2: Provenance on events

**Files:**
- Create: `EventCause.java`, `EventProvenanceSerializationTest.java`
- Modify: `SemanticEvent.java`, `SemanticEventBuffer.java`

- [ ] **Step 1: Record components.**
  ```java
  public record EventCause(Kind kind, String ref) {
      public enum Kind { EVENT, TOOL_CALL, WORK, GENERATION }
      public static EventCause event(long seqNo) { return new EventCause(Kind.EVENT, Long.toString(seqNo)); }
      // toolCall(String id), work(String workId), generation(long generation)
  }

  public record SemanticEvent(long seqNo, long tick, long timestampMs, String type,
      Map<String, Object> payload, String source, EventCause cause) {
      public SemanticEvent(long seqNo, long tick, long timestampMs, String type, Map<String, Object> payload) {
          this(seqNo, tick, timestampMs, type, payload, null, null);
      }
  }
  ```
- [ ] **Step 2: Check the serialization surfaces.** Default Gson omits null
  fields, so events without provenance serialize exactly as before. The
  bridge, recorder and dashboard all use default Gson. `DiagnosticReport`
  uses `serializeNulls()`: check whether it embeds events. If it does, add
  the fields there on purpose; the change is additive.
  `EventProvenanceSerializationTest` pins these cases:
  - an event without provenance serializes byte-identically to today;
  - an event with provenance adds `"source"` and `"cause":{"kind":…,"ref":…}`.
- [ ] **Step 3:** Confirm the consumers:
  - `observe` and `WakeTranscript` build explicit maps, so the goldens stay
    unchanged;
  - the wrapper's non-verbose view filters fields;
  - `wake_ledger.py` ignores unknown keys (run its tests).

  Commit: `feat(events): add source and cause to agent events (additive JSON)`.

### Task 3: `AgentEventLog` and the read/write interfaces

**Files:**
- Create: `EventView.java`, `EventPublisher.java`, `EventStream.java`, `AgentEventLog.java`, `AgentEventLogContractTest.java`
- Modify: `SemanticEventBuffer.java`

- [ ] **Step 1: Interfaces.** Each method mirrors the `SemanticEventBuffer`
  method it replaces, so call sites change only their types.
  ```java
  public interface EventView {
      SemanticEventQueryResult query(Long sinceSeqNo);
      long latestSeqNo();
      boolean containsType(String type);
      long droppedCount();
  }
  public interface EventPublisher {
      SemanticEvent publish(long tick, String type, Map<String, Object> payload, String source, EventCause cause);
  }
  public interface EventStream extends EventView, EventPublisher { }
  ```
  `SemanticEventBuffer implements EventStream`: `publish` forwards to the
  existing append with provenance, and existing `append` overloads stay.
- [ ] **Step 2: `AgentEventLog implements EventView`.** It is a package-private
  appender used only by the bus. Semantics must match `SemanticEventBuffer`
  exactly:
  - sequence numbers start at 1;
  - it holds up to `capacity` events (512) and evicts the oldest first,
    counting each eviction in `droppedCount`;
  - `query` reports `truncated` under the same rule;
  - `clear()` resets the sequence numbers, while `clearPreservingSequence()`
    does not.

  Use an `ArrayDeque`, which removes the `ArrayList.remove(0)` shift. Keep
  the `containsTypeForPlayer`/`countType*` helpers as methods on the log.
  Tests use them; production does not.
- [ ] **Step 3: Contract test.** Move the `SemanticEventBufferTest` cases into
  an abstract `AgentEventLogContractTest` with two subclasses, one per
  implementation. Add cases for:
  - an eviction at exactly `capacity`;
  - `query(since)` at the eviction boundary;
  - `clearPreservingSequence` followed by an append.

  Expected: identical results.
  Commit: `feat(events): add AgentEventLog with SemanticEventBuffer semantics (not wired)`.

### Task 4: `AgentEventBus`

**Files:**
- Create: `AgentEventBus.java`, `AgentEventBusTest.java`
- Modify: `build.gradle` (the root `test` task system property)

- [ ] **Step 1: API.**
  ```java
  public final class AgentEventBus implements EventStream {
      AgentEventBus(EventCatalog catalog, AgentEventLog log, LongSupplier wallClockMs, boolean strict);
      /** Validates the type, stamps provenance and time, appends, then notifies subscribers. */
      public SemanticEvent publish(long tick, String type, Map<String, Object> payload, String source, EventCause cause);
      public EventPublisher from(String source);                  // binds the source; cause optional per call
      public Subscription subscribe(Predicate<String> types, Consumer<SemanticEvent> subscriber);
      public void bindOwnerThread(Thread thread);                  // called at the top of tickClient
      public AgentEventBusStats stats();                           // undeclared, offThread, subscriberFailures
      // EventView methods delegate to the log.
  }
  ```
  `from(source)` returns a publisher with two overloads:
  `publish(tick, type, payload)` and `publish(tick, type, payload, cause)`.
- [ ] **Step 2: Validation.**
  - **Undeclared types:** strict mode throws `IllegalArgumentException`.
    Otherwise the bus logs a warning once per type, counts it, and publishes.
  - **Unknown sources:** a source missing from the spec's `producers` is
    handled the same way. That keeps the catalog's producer list honest.
- [ ] **Step 3: Time.** Stamp `timestampMs` from the runtime's injected
  `Clock`. Today the buffer calls `System.currentTimeMillis()` directly. In
  production the two are identical. In tests timestamps become
  deterministic, and they are not part of the goldens.
- [ ] **Step 4: Subscribers.** Subscribers run synchronously after the append,
  in subscription order.
  - **Nested publishes:** a publish made inside a subscriber is queued and
    dispatched after the current event, so every subscriber sees events in
    sequence order.
  - **Failures:** a `RuntimeException` from a subscriber is caught, counted,
    and recorded as a debug-timeline entry (`event_bus/subscriber_failed`).
    It never reaches the producer.
- [ ] **Step 5: Thread affinity.** Once an owner thread is bound, a publish
  from another thread is counted. In strict mode it also throws.
  - **Tests:** unit tests that never bind an owner skip the check.
  - **Harness:** `WakeScenarioHarness` binds the test thread through
    `onClientTick`, so an accidental publish from a pool thread in a tool
    future fails loudly.
- [ ] **Step 6: Tests.**
  - undeclared type (strict and lenient);
  - prefix types;
  - unknown source;
  - subscriber order and nested publish order;
  - subscriber failure isolation;
  - off-thread detection;
  - bound source stamping.

  Commit: `feat(events): add AgentEventBus with catalog validation and subscribers (not wired)`.

### Task 5: `EventIngressQueue`

**Files:**
- Create: `EventIngressQueue.java`, `EventIngressQueueTest.java`

- [ ] **Step 1:** Generalize the `pendingInteractions` and
  `droppedInteractionBatches` pair (EAR 338–339):
  ```java
  public final class EventIngressQueue<T> {
      public EventIngressQueue(int capacity);                // 128 for interactions
      public boolean offer(T item);                          // any thread; counts drops when full
      public int drain(Consumer<T> onItem);                  // owner thread; returns drained count
      public int takeDroppedCount();                         // getAndSet(0)
  }
  ```
- [ ] **Step 2: Tests.**
  - capacity and drop counting under concurrent offers (two producer
    threads and one drainer);
  - `takeDroppedCount` resets the counter;
  - drain order is FIFO.

  Commit: `feat(events): add bounded EventIngressQueue (not wired)`.

### Task 6: Wire the runtime to the bus

**Files:**
- Modify: `EmbodiedAgentRuntime.java`, `AgentEventPipeline.java`

- [ ] **Step 1: Construct the bus.** Replace the `eventBuffer` field (232):
  ```java
  private final AgentEventBus eventBus = new AgentEventBus(EventCatalog.defaults(),
      new AgentEventLog(512), clock::millis, Boolean.getBoolean("airicraft.events.strict"));
  ```
  Keep `plannerEventBuffer` (233) as a `SemanticEventBuffer`.
  `AgentEventPipeline` takes an `EventView` for raw reads and an
  `EventPublisher` for `policy.event_intervened`. Its drain loop and
  `clear*`/`setPlannerEnabled` semantics are unchanged. The `clear*` calls go
  to the log through package-private methods.
- [ ] **Step 2: Bind the owner thread** as the first statement of
  `tickClient`.
- [ ] **Step 3: Migrate the 51 appends in `EmbodiedAgentRuntime`.** Each
  `eventBuffer.append(tickCount, T, P)` becomes `EVENTS.publish(tickCount, T, P)`,
  where `EVENTS = eventBus.from(<producer>)`.
  - **Producer:** use the class the inventory names for that type, not
    necessarily `EmbodiedAgentRuntime`. The drained queues keep their
    originating producer:
    - `processSurvivalReflexEvents` (1083) → `SurvivalReflexRuntime`
    - `drainActionGraphCoordinatorEvents` (2418) → `ActionGraphCoordinator`
    - `recordStalePlannerRejections` (1100) → `EmbodiedAgentRuntime`
  - **Mechanical only:** do not reorder statements, and keep every returned
    `SemanticEvent` used for `seqNo` (for example 922, 982, 1017, 1086, 2792,
    2831).
- [ ] **Step 4: Switch the reads.** `recentEvents`, `latestEventSeqNo`,
  `semanticEventContains`, `currentPlannerDecisionContext` (860) and
  `appendEventForTests` now go through the bus's `EventView` and publisher.
  `appendEventForTests` uses source `"test"`, which the catalog accepts as a
  test-only producer.
- [ ] **Step 5: Wire the ingress queue.** `pendingInteractions` becomes
  `EventIngressQueue<ObservedInteractions>`. `drainInteractionEvidence`
  (2770) keeps its position at tick start. It publishes
  `interaction.history_gap` when `takeDroppedCount() > 0`, then each
  `interaction.<action>` with source `InteractionLogbookRecorder`.
- [ ] **Step 6: Run the full focused set:**
  - the wake suite;
  - `EmbodiedAgentRuntimeTest`, `AgentEventPipelineTest`, `EventInventoryTest`;
  - `DialogueRuntimeTest`, `PlannerOrchestratorTest`.

  Expected: all green and `git diff --exit-code src/test/resources/planner/wakes`.
  Strict mode surfaces any undeclared id or foreign-thread publish. Fix the
  catalog or the producer. Never loosen the check.
  Commit: `refactor(events): route runtime events through AgentEventBus (no behaviour change)`.

### Task 7: Migrate the remaining producers

**Files:**
- Modify: `SessionRuntime.java`, `ChatIngestService.java`, `NearbyPlayerTracker.java`, `FollowCapability.java`, `DialogueRuntime.java`, `EmbodiedAgentRuntime.java` (callers)
- Create: `PlannerFeedPublisher.java`

- [ ] **Step 1: Write-only producers.** Change these parameter types from
  `SemanticEventBuffer` to `EventPublisher`:
  - `SessionRuntime` (9 appends)
  - `ChatIngestService` (3)
  - `NearbyPlayerTracker` (2)
  - `FollowCapability` (2)

  Each class declares `private static final String SOURCE`. Callers in EAR
  pass `eventBus`. Their unit tests keep passing a `SemanticEventBuffer`,
  which is also an `EventPublisher`.
- [ ] **Step 2: `DialogueRuntime` reads and writes.** Change its
  `SemanticEventBuffer` parameters to `EventStream`, then stamp sources:
  - `applyEffects` (1042) publishes `DialogueEffect` events with source
    `DialogueCore`;
  - `supersedePendingInternalTaskUpdates` and the `task.notice` write (610)
    use `DialogueRuntime`;
  - the policy continuation writes (111, 137) also use `DialogueRuntime`.
- [ ] **Step 3: Name the planner-feed writes (P2).** At the five EAR call
  sites that pass `plannerEventBuffer` (1828, 4579, 4596, 4610, 5780), pass
  `PlannerFeedPublisher.wrap(plannerEventBuffer)` instead.
  - **Behaviour:** it is an `EventStream` over the planner buffer, with
    identical behaviour, plus a Javadoc explaining D1 and Phase 3.
  - **Test:** add `DialogueRuntimeTest.plannerFeedWritesStayOutOfRawLog`. It
    pins that `planner.degraded_blocked`, emitted on a W1 path while
    degraded, lands in the planner feed and not in the raw log.
- [ ] **Step 4: Pipeline intervention.** `AgentEventPipeline` publishes
  `policy.event_intervened` with source `AgentEventPipeline` and
  `cause = EventCause.event(sourceSeqNo)`.
- [ ] **Step 5:** Run the same set as Task 6, plus `SessionRuntimeTest`,
  `ChatIngestServiceTest`, `NearbyPlayerTrackerTest` and
  `FollowCapabilityTest`. Expected: green, no golden diff.
  Commit: `refactor(events): publish all producers through the bus with sources (no behaviour change)`.

### Task 8: Populate causes the producer already knows

**Files:**
- Modify: `EmbodiedAgentRuntime.java`, `DialogueRuntime.java`
- Test: `EmbodiedAgentRuntimeTest`, `DialogueRuntimeTest`

- [ ] **Step 1:** Add a cause only where the value is already in scope at the
  publish call:

  | Event | Cause |
  |---|---|
  | `policy.event_intervened` | `event(sourceEventSeqNo)` (Task 7) |
  | `planner.internal_task_update_superseded` | `event(wake.eventSequence())` |
  | `planner.stale_response_rejected` | `generation(rejection.generation())` |
  | `planner.response_applied`, `planner.goal_set`, `planner.goal_cleared` | `generation(...)` when the applied response carries one |
  | `work.changed` | `work(work.handle().id())` |
  | `work.travel_restriction_violated` | `work(...)` for the violating work |
  | `task.notice` from the watchdog or slow mining | `work(...)` when the notice names a work id |
  | `player.actions_cancelled`, `player.death_place_saved` | `event(<player.died seqNo>)`, stored when the death event is published |

  Leave every other cause `null`. Do not add parameters to reflex, graph or
  session producers in this phase (P5).
- [ ] **Step 2:** Add `EventCatalogTest.causeCoverageReport`. It publishes a
  representative scenario through the harness and prints the fraction of
  events carrying a cause, by family. It is informational, and it gives
  Phase 2 a starting number.
- [ ] **Step 3:** Run the wake suite and the runtime and dialogue tests.
  Expected: green, no golden diff. `observe` does not render causes in this
  phase.
  Commit: `feat(events): record known causes on agent events (additive)`.

### Task 9: One lifecycle boundary dispatch

**Files:**
- Create: `LifecycleBoundary.java`, `LifecycleDispatcher.java`, `LifecycleDispatcherTest.java`
- Modify: `EmbodiedAgentRuntime.java`

- [ ] **Step 1: The boundaries and what each participant does today.**
  Blank cells mean no action. The dispatcher calls participants in
  registration order, and this table is that order:

  | Participant | `WORLD_LEFT` | `WORLD_LOADED` | `AWAITING_RESPAWN` (each tick) | `RESPAWNED` | `WORLD_CHANGED` | `PLAYER_UNAVAILABLE` | `SHUTDOWN` |
  |---|---|---|---|---|---|---|---|
  | `LocalDamageTracker` | `clear()` | `onLifecycleReset(tick)` | | `onLifecycleReset(tick)` | | | `clear()` |
  | `PhysicalEventObserver` (+ `physicalObservationWorld`) | reset, world = null | reset | reset | reset | reset, world = new | reset, world = null | reset, world = null |
  | `ItemOfferObserver` (+ `itemOfferWorld`) | reset | reset | reset | reset | reset, world = new | reset, world = null | reset |
  | `SlowMiningObserver` | reset | reset | reset | reset | reset | reset | reset |
  | `NearbyPlayerTracker` | `clear(tick, events)` | | | | | | `clear(tick, events)` |

  Sources for the table:
  - `onWorldLeave` (505–516); the world-load edge (579–584); the
    respawn-required tick (586–589); `onPlayerRespawned` (2003–2007);
    `shutdown` (1239–1244).
  - `WORLD_CHANGED` and `PLAYER_UNAVAILABLE` come from the guards in
    `observeItemOffers` (756–761) and `observePhysicalEvents` (865–872).
    Today those guards check `client.world` identity separately, one per
    observer.
  - Before implementing, verify two things against the code: that the
    item-offer guard's "unavailable" case really leaves `SlowMiningObserver`
    alone, and whether `itemOfferWorld` is cleared at world leave. Where the
    two guards differ today, keep them separate:
    - dispatch `WORLD_CHANGED`/`PLAYER_UNAVAILABLE` with the observer id as a
      target (`dispatch(boundary, Set<participant>)`);
    - never widen a reset.
- [ ] **Step 2: Implement** `LifecycleDispatcher.register(String id,
  EnumSet<LifecycleBoundary>, BoundaryHandler)` and
  `dispatch(LifecycleBoundary, long tick)`. Replace each reset block with one
  dispatch at the same statement position.
  - **Resets outside this table:** the non-observer resets inside those
    blocks stay where they are. Examples are `eventPolicyState.clear()` and
    `eventPipeline.clearPlannerFeed()` in `onWorldLeave`. They are session
    and planner resets, not perception.
- [ ] **Step 3: Event order.** `NearbyPlayerTracker.clear` publishes
  `social.player_left_nearby` events. Registering it last keeps the world
  leave event order after `SessionRuntime.onWorldLeave`'s events.
  `LifecycleDispatcherTest` pins:
  - participant order;
  - that each boundary calls exactly the table's handlers;
  - that an unregistered boundary is a no-op.
- [ ] **Step 4:** Run the wake suite (the `death_then_respawn` golden covers
  respawn), `EmbodiedAgentRuntimeTest`, `PhysicalEventObserverTest`,
  `ItemOfferObserverTest` and `SlowMiningObserverTest`. Expected: green, no
  golden diff.
  Commit: `refactor(perception): dispatch lifecycle boundaries from one place (no behaviour change)`.

### Task 10: Fix D6 with a subscriber

**Files:**
- Create: `FoodOutcomeIndex.java`, `FoodOutcomeIndexTest.java`
- Modify: `EmbodiedAgentRuntime.java` (`refreshWorkHistory`, around 2815–2822), `WakeDefectProbeTest.java`

- [ ] **Step 1: The index.**
  ```java
  /** Food outcomes by sequence, independent of event-log eviction. */
  public final class FoodOutcomeIndex implements Consumer<SemanticEvent> {
      FoodOutcomeIndex(int capacity);                        // 32 outcomes
      public void accept(SemanticEvent event);               // subscribed to food.eaten, food.eat_failed
      public Optional<SemanticEvent> firstAfter(long seqNo); // same selection as today's stream().findFirst()
      public void clear();                                   // on WORLD_LEFT and SHUTDOWN, like workHistory
  }
  ```
  Subscribe it in the EAR constructor. In `refreshWorkHistory`, replace
  `eventBuffer.query(since).events().stream().filter(...).findFirst()` with
  `foodOutcomes.firstAfter(since)`. The rest of the branch is unchanged.
  Hook `clear()` into the dispatcher's `WORLD_LEFT` and `SHUTDOWN`.
- [ ] **Step 2: Tests.**
  - `FoodOutcomeIndexTest`:
    - first-after selection;
    - eviction beyond capacity only drops the oldest outcomes;
    - both types are indexed;
    - other types are ignored.
  - Rename `d6_CONFIRMED_evictionLosesEatingCompletion` to
    `d6_FIXED_eatingCompletesAfterLogEviction` and flip the expectation. The
    512-event flood now ends with `EATING` → `SUCCEEDED`. This is the only
    changed expectation in the phase. Keep the no-flood control case.
- [ ] **Step 3:** Run the wake suite, `WakeDefectProbeTest` and
  `EmbodiedAgentRuntimeTest`. Expected: green, no golden diff.
  Commit: `fix(work): resolve EATING from a food-outcome subscriber, not the event ring (fixes D6)`.

### Task 11: Verify and document

**Files:**
- Modify: the spec (Phase 1 checklist, the D6 row in 2.6, a Phase 1 status note), this plan (implementation status), `AGENTS.md` (key files: `agent/events/AgentEventBus.java`, `EventCatalog.java`), and optionally `scripts/wake_ledger.py` (carry `source`/`cause` into `newEvents`)
- Create: `docs/experiments/<date>-planner-event-system-phase-1-check.md`

- [x] **Step 1: Full build:** `./gradlew build`. Record the root, wrapper and
  compatibility test counts.
- [x] **Step 2: Golden check** (ground rule 1). Also run
  `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`.
- [x] **Step 3: Live smoke check.**
  1. Start `./gradlew runClient`.
  2. Join a world, then chat, take damage and pick something up.
  3. Run `airicraft agent events recent --verbose`. Every event has a
     `source`, and the listed causes appear.
  4. Check the dashboard: the event history panel is unchanged, and the
     export JSONL has the new fields.
  5. Check that `airicraft status` and the debug timeline show no
     `event_bus/subscriber_failed` entries.
  6. Check the bus stats: zero undeclared types and zero off-thread
     publishes.
- [ ] **Step 4: Evaluation batch.**
  - **Run:** the nine baseline scenarios with the baseline command and
    configuration
    ([baseline](../../experiments/2026-09-27-planner-wake-baseline.md), "Provenance and method").
  - **Compare:** use `python3 scripts/wake_ledger.py summarize` and `diff`
    against the retained baseline ledgers.
  - **Pass criteria:** the Phase 2 tolerance table applies unchanged. Each
    scenario's pass/fail must be no worse than its worst baseline result,
    turns and rates must stay within the tolerance interval, and there must
    be no new event or timeline gaps.
  - **Record** the result in the Phase 1 check note.
- [x] **Step 5: Docs.**
  - tick the spec's Phase 1 checklist;
  - mark D6 fixed in 2.6;
  - add the implementation status to this plan;
  - update `AGENTS.md`.

  Commit: `docs: record Phase 1 verification`.

## Exit criteria

- [x] `./gradlew build` is green; the characterization suite passes with no
  golden diff.
- [x] Every published event type is declared in `EventCatalog`, with strict
  mode on in tests. The G1 routing table is derived from the catalog.
- [x] Every event carries a `source`; Task 8 events carry a `cause` where the
  producer already holds its reference, per P5.
- [x] Off-thread producers go through `EventIngressQueue`; the bus reports
  zero off-thread publishes in the smoke run.
- [x] Observer resets happen only through `LifecycleDispatcher`.
- [x] D6 is fixed, and no production code scans the event ring for state.
  The remaining scans are reads of evidence (`observe`, the wake prose
  lookup) and the P7 blocked-goal cursor, which Phase 2 moves.
- [x] The dashboard, recorder, CLI (`agent events recent`) and evaluator
  (`recentEvents`, `semanticEventContains`) behave as before. JSON changes
  are additive only.
- [ ] The evaluation batch is within the baseline tolerance.

## What Phase 1 deliberately leaves alone

These stay for Phase 2 (attention policy, scheduler) or Phase 3 (evidence
references):
- `plannerEventBuffer` and its second sequence space (D1, D2, D3);
- the planner-feed writes (P2);
- publish-time routing (P1);
- the 21 `drainEventPipeline()` call sites;
- trigger factories and prose;
- the G10 visibility list (the catalog only mirrors it);
- urgency and delivery;
- the blocked-goal cursor scan (P7).

## Risks

- **Hidden ordering in the migration.** Some appends return the event whose
  `seqNo` feeds a wakeup: `task.notice`, `food.unavailable`, reflex events,
  `work.changed`, `work.travel_restriction_violated`. Changing the order of a
  publish and its `queueTaskWakeup` call would change W2 and W3 references.
  Mitigation: mechanical edits only, plus the goldens and
  `d4`/`d5` probes.
- **Strict mode finds real off-thread publishes.** For example, a tool future
  that calls `executePlannerAction` → `refreshWorkHistory` → `recordWork` on
  a pool thread. That would be a latent data race today. Mitigation: route
  the publish through `EventIngressQueue`, or move it onto the client thread
  (`client.execute`). If the fix changes timing, it gets its own commit and
  its own golden review. Do not suppress the check.
- **Undeclared ids from integrations or future features.** Mitigation: the
  lenient mode in production (P8), and the source-scan guard kept from
  Phase 0.
- **Conflicts in `EmbodiedAgentRuntime`.** Mitigation: slices A and B touch
  it only at the routing table. Slice C is one mechanical commit, rebased
  just before merge.
