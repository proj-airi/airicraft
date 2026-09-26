# Planner Perception, Event Bus and Wake Scheduling Design

Status: **accepted** (2026-09-26). Nothing here is implemented yet. All
decisions in section 9 were accepted on 2026-09-26. Phase 0 is planned in
[`plans/2026-09-26-planner-event-system-phase-0.md`](../plans/2026-09-26-planner-event-system-phase-0.md). When they are settled,
record them as ADR-0003 and add the new terms to `CONTEXT.md`.

Evidence base: the code at `a1c05d8`. Line numbers in this document refer to
that commit. The design also draws on Cortico (`Pal-AI-Lab/Cortico` at
`bc47c82`: `src/core/bus.ts`, `src/core/types.ts`, `src/core/loop.ts`,
`src/worlds/minecraft/world.ts`, `PHILOSOPHY.md`) and on the legacy AIRI
Mineflayer bot (`moeru-ai/airi`, `integrations/minecraft/src/cognitive/` at
`7abffae`, plus its perception history under `services/minecraft/`).

Relationship to ADR-0002: this design extends ADR-0002 and does not reverse it.
Evidence still exists independently of wakes and still reaches each role
through the runtime-issued `observe` exchange. Recording stays bounded. The
design gives wakes a typed pipeline and makes observation the only evidence
channel. The places where an ADR-0002 number or rule might change are marked
**[ADR-0002]**.

## 1. Why

The planner's "when do I think, and about what" logic grew one feature at a
time. Each feature added its own event type, wake path, suppression rule and
prompt text. The results are:

- The planner can be woken in eight different ways. Eleven places decide
  whether it wakes and what it sees.
- The same fact can reach the model through three channels, each with its own
  filter. A fact can arrive twice, or be hidden from one channel and not
  another.
- Prompt instructions are built into runtime code inside
  `EmbodiedAgentRuntime`, which is 5,926 lines long.
- There is no perception layer. The agent notices only what an executor or a
  tick-sampler happens to report. It cannot notice a diamond it walks past,
  because no component looks for salient things that nobody asked about.

Goal: one path from **sensing** to **deciding whether to wake** to **waking
the planner**. Every step should be typed, explainable, testable, and
prioritized explicitly.

Non-goals: rewriting the reflex state machine, the executors, the
orchestrator's conversation, compaction and retry mechanics, or the tool
catalog. No persistence changes, no model or effort changes, and no change to
the controller/thinker ownership rules.

## 2. Current system

### 2.1 Flow today

```
 mixins / tick samplers / executors / reflex / dialogue
        │  eventBuffer.append(tick, "type.string", Map)   (~70 call sites in 6 classes + drained queues from
        ▼                                                   reflex, action graphs, dialogue effects, logbook)
 ┌───────────────────────┐   AgentEventPipeline.drain()  (drainEventPipeline() has 21 call sites: 6 in tickClient)
 │ eventBuffer (raw,512) │──► EventRoutingProfile table (46 of ~91 types) ──► EventPolicyState (planner rules)
 └───────────────────────┘        │ semanticEligible                    │ triggerType
        │                         ▼                                     ▼
        │              plannerEventBuffer (512,          createPlannerTrigger() switch (14 types, prose built here)
        │              own seq space)                               │
        │                         │                                  ▼
        │                         │               DialogueRuntime.onPlannerTrigger()  ◄── IdleIdeaScheduler (IDLE_THINK)
        │                         │                      │                            ◄── continuePlannerGoal() (GOAL CONTINUATION)
        │                         │                      │                            ◄── delegation start prompt
        │                         │                      │   queueTaskWakeup()/queueTaskAttention() deque
        │                         │                      │   ◄── 11 call sites (work, reflex, travel, task, blocked goal…)
        │                         ▼                      ▼
        │               PlannerOrchestrator.submit() ─► PlannerContextAggregator (coalescing keys, idle-think invalidation,
        │                         │                        legacy semantic notices, overflow flush)
        ▼                         ▼
 PlannerDecisionContext (raw eventBuffer, prefix whitelist) ──► `observe` tool result in the role's history
```

### 2.2 Eight ways to wake the planner

| # | Path | Entry point |
|---|---|---|
| W1 | Routed event trigger | `EmbodiedAgentRuntime.drainEventPipeline` (4528) → `createPlannerTrigger` (4595) → `DialogueRuntime.onPlannerTrigger` (522) |
| W2 | Task wakeup deque (FIFO) | `DialogueRuntime.queueTaskWakeup` (805), 7 call sites: reflex events (EAR 1061), travel violation (2758), `work.changed` (2798), task transitions (5222, 5377), internal task update (DR 585), blocked-goal reconsider (DR 621) |
| W3 | Task attention (front of deque, ignores the accepted-work hold) | `DialogueRuntime.queueTaskAttention` (798): work stalled (901), slow mining (956), food unavailable (993), reflex food failures (1063) |
| W4 | Goal continuation, every ≥20 ticks while idle | `DialogueRuntime.continuePlannerGoal` (237), polled from `maybeFireIdleIdeaTrigger` (4549) |
| W5 | Idle think | `IdleIdeaScheduler.tick` (EAR 4566); bridge route `fireIdleIdeaTriggerManually` (1777) |
| W6 | Delegation start | `DialogueRuntime.poll` (641) |
| W7 | Evaluation seeds | `emitEvaluationTrigger` (5728), `startEvaluationGoal` |
| W8 | Semantic overflow flush | `PlannerOrchestrator.startOverflowFlushIfIdle` (1360), when pending semantic events ≥ cap |
| W9 | FIFO result review | `PlannerOrchestrator.tickToolQueue`, after FIFO exhaustion or a `report_to_me` checkpoint |

Also present: policy continuation (`DialogueRuntime.pollPolicyContinuation`) is
wired but dormant, because action policies have been disabled since
2026-09-20. `onContextTrigger` and the 9-argument `onPlayerChat` have no
production callers.

### 2.3 Eleven places that decide attention

| # | Gate | Location |
|---|---|---|
| G1 | Routing profile table: semantic eligibility, trigger type, policy bypass | `EmbodiedAgentRuntime.createEventRoutingProfiles` (5069) |
| G2 | Planner-authored rules (`update_event_policy`) plus one hard-coded default rule (mining pickups) | `EventPolicyState`, `resolveDefaultEventPolicy` (4769) |
| G3 | Early returns in the per-type trigger factories: evaluation suppression list, proactive social mode, chat distance, reset commands, collect-resource job, pending craft result, reflex owns actuation, payload validity | EAR 4595–4970 |
| G4 | Blocked goal; accepted work or queued tool work vs CRAFT/PICKUP/IDLE_THINK | `DialogueRuntime.onPlannerTrigger` (531–539) |
| G5 | External driver, in-flight, degraded, accepted `run_policy`, queued tool work, already-incorporated event, blocked-goal relevance, supersession by guidance revision or mission | `submitNextPendingInternalTaskUpdate` (809–847) |
| G6 | Twelve continuation guards | `continuePlannerGoal` (237–267) |
| G7 | Idle-think guards | `maybeFireIdleIdeaTrigger` (4549), `isIdleForIdleIdeaScheduling` |
| G8 | Enabled, compaction, awaiting reply record, side-effect tool, supersede, coalesce window | `PlannerOrchestrator.submit` (493–533) |
| G9 | Coalescing keys; idle-think invalidation when an action goal starts | `PlannerContextReducer.enqueueTrigger` (51), `invalidateIdleThinkTriggers` (74, called from EAR 1382) |
| G10 | Observation prefix whitelist | `PlannerDecisionContext.relevant()` |
| G11 | Aggregation of legacy semantic notices | `SemanticContextProjector.AggregationStrategy.forType` |

### 2.4 Three evidence channels

| Channel | Source | Filter |
|---|---|---|
| E1 Trigger prose (user turn or NOTICE) | Trigger factories, `IdleIdeaScheduler`, `DialogueRuntime` | G1–G9 |
| E2 Legacy semantic notices, folded into `observe.notices` | `pendingSemanticEvents`, filled from **whichever buffer** the submit call received | G11 |
| E3 `observe.events` | raw `eventBuffer` | G10 |

### 2.5 Problems

1. **Too many wake paths, too many gates (W1–W9, G1–G11).** No single place
   answers "why did the planner wake at tick N?" or "why didn't it?". Only W1
   decisions are recorded (`AgentDebugRecorder.recordEventRouting`).
2. **Evidence and instructions are mixed.** Trigger text contains both facts
   and imperatives, for example "…use continue to retain and resume the plan,
   or clear_queue…" and "Do not claim completion…". This text is assembled in
   the runtime, which conflicts with ADR-0002's rule that observations are
   evidence, not requests.
3. **Priority is binary.** `PlannerTriggerOrigin` is either `DIRECT_GUIDANCE`
   (may supersede a launched turn) or `AUTONOMOUS`. Everything else is
   `SYSTEM`, a catch-all used for physical episodes, reflex resolution,
   smelting, blocked tasks, graph failures, goal continuation, delegation and
   item offers. Safety events have no way to preempt a turn. They depend on
   rejecting a stale result after the turn has already been paid for.
4. **Events are untyped.** Payloads are free-form `String` type plus
   `Map<String,Object>`, with about 91 static ids and two dynamic families
   (`interaction.<action>`, `policy.continuation.<state>`). There is no
   catalog, yet the ids are model-visible (`observe.events[].type`,
   `update_event_policy`, `block_planner_goal.reconsiderEvents`) and persisted
   (`planner-goal.json` stores `reconsiderEvents`).
5. **There is no perception layer.** Observers (`PhysicalEventObserver`,
   `ItemOfferObserver`, `SlowMiningObserver`, `LocalDamageTracker`,
   `NearbyPlayerTracker`) are wired by hand into `tickClient`. Their resets are
   duplicated in five places (world leave, world load, respawn-required,
   respawned, and world-identity change). Nothing detects salience.
6. **Ordering is implicit.** `drainEventPipeline()` has 21 call sites: six at
   different points inside `tickClient`, and the rest in mixin callbacks and
   tool handlers between ticks. Whether an event wakes the planner in the same
   tick depends on which call site happens to run next.
7. **Clocks are mixed.** The coalesce window and idle ideas use wall-clock
   milliseconds. Goal continuation uses ticks. Tick-debug pause therefore
   affects the paths differently.

### 2.6 Defect characterization (Phase 0)

- **D1 One cursor, two sequence spaces.** `DialogueRuntime.submitPlannerTrigger`
  (782–792) advances the role's `lastObservedEventSeqNo` by querying the buffer
  it was handed. W1, W4 and W5 pass `plannerEventBuffer`; W2, W3 and W6 pass the
  raw `eventBuffer`. Each buffer numbers its own events, so the legacy notice
  channel (E2) may skip events or include ones the policy excluded.
- **D2 Duplicate evidence.** Pickups and crafts appear both as an E2
  aggregated notice and as E3 observation events.
- **D3 Event policy applies to only part of the pipeline.** Planner-authored
  `ignore` and `semantic_only` rules affect E1 and E2 but not E3, because the
  observation reads the raw buffer. "Ignore" hides nothing from the model's
  observation.
- **D4 Double wake paths for one fact.** Examples: `reflex.resolved` (a W1
  SYSTEM trigger plus a W2 wakeup), and task terminals (`work.changed`, the
  semantic transition, and the terminal task event, all W2, plus a possible W1
  graph terminal). These are reconciled only as a side effect of the
  incorporated-event cursor.
- **D5 Wrong wake reference.** W2 records `eventBuffer.latestSeqNo()` instead
  of the event that caused the wake (5222, 5377). The prompt lookup then falls
  back to "Work changed."
- **D6 The evidence buffer is used as a state channel.** `EATING` work
  resolves by searching the 512-entry ring for `food.eaten` (2784). If more
  than 512 events arrive first, that path never resolves.
- **D7 Pause counted as idle.** `IdleIdeaScheduler` measures idle time in wall
  milliseconds, so time spent in tick-debug pause may count as idle time and
  fire idle think right after resume.
- **D8 Inconsistent visibility.** `social.*`, `follow.*`, `planner.*`,
  `action_graph.*` and `mission.*` are absent from E3. Graph failure details
  and item offers reach the model only through prose (E1), which can be
  coalesced away. For example, a second `item_offer:<player>` replaces the
  first one.

Phase 0 probes preserve current behavior. `WakeDefectProbeTest`,
`DialogueWakeCharacterizationTest`, `IdleIdeaSchedulerTest`, and
`PlannerDecisionContextTest` establish these bounded verdicts:

| Probe | Verdict | Pinned evidence |
| --- | --- | --- |
| D1 | Confirmed | A W2 raw-buffer cursor of 10 skips a later planner-buffer pickup at sequence 1 in the legacy notice channel. |
| D2 | Confirmed | A pickup appears in both `observe.events` and `observe.notices`. |
| D3 | Confirmed | An `ignore` rule suppresses the pickup wake, but a later chat still observes its raw evidence. |
| D4 | Confirmed paths; second request suppressed | One `reflex.resolved` attempts W1 and W2; `G5.incorporated` drops W2, leaving one backend request. |
| D5 | Refuted for direct-navigation failure | Both W2 references identify the causal `task.failed` / `work.changed` event. Generic “Work changed.” prose still occurs because those events lack `task.notice` message prose. Other producers are not proven by this probe. |
| D6 | Confirmed | A synthetic 512-event flood evicts `food.eaten`: EATING stays RUNNING, versus SUCCEEDED without the flood. This establishes the dependency, not its gameplay frequency. |
| D7 | Confirmed | Five minutes of wall time with no ticks causes one idle-think wake on the first resumed tick; there is no catch-up burst. |
| D8 | Confirmed | Offers and graph failures are absent from canonical observation; a second offer with the same player key replaces the first prose trigger. |

Fatal damage currently wakes before respawn; the death golden pins this
rather than adopting the original plan's no-wake expectation. W9, discovered
during implementation, records FIFO exhaustion / `report_to_me` result
review in `PlannerOrchestrator.tickToolQueue`. It is distinct from W8 overflow.
These observations do not change production decisions.

## 3. What to adopt from the references

### 3.1 Cortico

| Idea | Where | Verdict |
|---|---|---|
| Producers choose a **trigger mode** per event: `preempt` (cancel the unexternalized in-flight model round), `flush` (deliver now with backlog), `debounce` (batch), `piggyback` (never wakes; rides along) | `core/types.ts` `TriggerMode`, `core/bus.ts` | **Adopt, adapted.** In Airicraft the `observe` cursor already delivers every event since the last decision, so "piggyback" costs nothing: it just means "don't wake". |
| Debounce formula `min(first+maxAge, max(first+minAge, last+quietGap))`, plus `maxBatchSize` | `WakeBus.arm` | **Adopt**, measured in agent ticks. |
| One bus, one consumer, FIFO batches; operator pause and a persona **delivery gate** | `WakeBus` | **Adopt.** One scheduler per runtime. The blocked-goal filter becomes a gate. |
| Preempt only cancels a model round that has **not externalized** output or tool calls | `MainLoop.abortCurrentRound` | **Adopt** for safety epochs (Phase 5). |
| **Producers own throttling**; the bus sees only generic modes. Hysteresis, per-category cooldowns and hourly budgets live in the World | `worlds/minecraft/world.ts` (`noticeDamage`, proximity enter/exit ranges, goal-staleness budget) | **Adopt**: sensors own throttling. |
| Settle throttled summaries **before** an immediately delivered event, so the timeline is not inverted | `MinecraftWorld.emit` | **Adopt** as tick-phase ordering (4.9). |
| Snapshot rendered at delivery time (`pushDeferred` + `piggyback`) | `DeferredEventSpec` | **Already have it**: `PlannerDecisionContext.current` is computed at decision time. |
| **Honest epistemology**: events state only facts the infrastructure can verify; heuristic inference is avoided or flagged; exceptions are declared to the model | `PHILOSOPHY.md` | **Adopt as a rule for event contracts** (4.1). |
| Events enter context as synthetic **tool results**, not user messages | `eventDelivery: 'tool'` | **Already have it** (`observe`, ADR-0002). |
| Persistent JSONL event store with a delivery watermark and replay on restart | `core/event-store.ts`, `requeueUndelivered` | **Reject.** It conflicts with ADR-0002 (bounded recording, no unbounded event sourcing), and world reloads already reset planner state. |

### 3.2 Legacy AIRI Mineflayer bot

| Idea | Where | Verdict |
|---|---|---|
| Four layers: Perception → Reflex → Conscious → Action | `integrations/minecraft/README.md` | Airicraft already has System 1 and System 2. Add the missing **perception** layer. |
| Declarative perception events: `definePerceptionEvent({id, modality: sighted/heard/felt/system, kind, binding, filter, extract})` in a registry | `perception/events/*` | **Adopt** as `Sensor` and ingress adapters, with a modality field on percepts. |
| Salience via **temporal detectors**: threshold within a sliding or tumbling window, grouped by entity or source, emitting `signal:*` with a description template and confidence | `perception/rules/engine.ts`, `temporal-detector.ts`, `rules/*.yaml` | **Adopt the detector, not the YAML DSL.** Rules are GraalJS modules (4.12). Windowed detectors ship as helpers in the bundled rule library, so rule authors, including the planner later, can change them. |
| Leaky bucket for attention | `services/minecraft/.../leaky-bucket.ts` (history, `a3a29ae2`) | **Adopt** as a global autonomous-wake budget. It is implemented **inside the bundled default JS rule**, not in Java. |
| Reflex inhibits the conscious layer (`shouldForwardSignalToConscious`: never forward attention signals; suppress `damage` while attacking) | `reflex/reflex-manager.ts` | **Adopt** as ownership-inhibition rules (4.6). |
| Priority tiers (urgent chat/command < perception < feedback < no-action follow-up); stable-sort, drop stale follow-ups while urgent input waits, bounded queue drops the lowest priority, and a starvation guard after 8 consecutive high-priority turns | `conscious/brain.ts` (`getEventPriority`, `coalesceQueue`, `dequeueNextQueuedEvent`) | **Adopt the ordering and the dropping of stale self-wakes.** Batches go out whole here, so the starvation guard becomes a **supersede budget**. |
| Traced events (`traceId`, `parentId`); pattern subscriptions; isolated subscriber failures | `cognitive/event-bus.ts` | **Adopt** a `cause` reference on events and subscriber isolation. |
| Chat routed through the same signal path as world events | `cognitive/index.ts` | **Adopt**: chat becomes an ingress percept. |

## 4. Target architecture

### 4.1 Principles

1. **One path.** Sensor or producer → event log → attention policy → wake
   scheduler → planner. No component except the scheduler submits planner
   turns.
2. **Wakes reference evidence; they don't carry it.** A wake names the event
   sequence numbers, or the self-generated reason, that justify a decision.
   The `observe` result carries the facts. Human chat is the only exception: it
   stays a user turn.
3. **Honest events.** Event payloads contain facts. Inference is flagged, as
   `ItemOfferObserver` already does with `inferred: true`. There are no
   imperatives. Guidance lives in the system prompt, tool descriptions, or a
   small decision-hint registry with explicit provenance.
4. **Producers own throttling and hysteresis. The policy owns attention.** The
   scheduler owns timing. The orchestrator owns conversations.
5. **Everything is explainable.** Each event receives a recorded attention
   decision `(decision, rule id, reason)`. Each batch records its members.
6. **Bounded and tick-driven.** State is bounded. Timing uses agent ticks, so
   tick-debug pause freezes perception and wakes consistently.
7. **Mechanism in Java, judgement in rules.** Java owns sensing, the event
   log, scheduling and a small fixed set of protections (the *constitution*).
   Deciding what is salient and what deserves attention is written as GraalJS
   rules (4.12) that people, coding agents and eventually the planner can
   read and edit.

### 4.2 Vocabulary (candidates for `CONTEXT.md`)

- **Sensor**: samples client or world state, or adapts a game callback, and
  publishes percepts. It never actuates.
- **Percept**: an event published by a sensor. It states an observed fact,
  possibly flagged `inferred`.
- **Agent event**: any entry in the event log. There are three families:
  *percepts*, *execution feedback* (work, task, graph, process and watchdog),
  and *agent-internal* records (planner, policy, reflex decisions, session).
- **Event type**: a catalog entry with an id, family, payload contract and
  defaults (visibility, urgency, delivery).
- **Event log**: the single bounded, sequenced store of agent events. It
  succeeds `SemanticEventBuffer`.
- **Attention policy**: decides whether an event may wake the planner, and
  how, given the current agent state.
- **Wake**: a request for a planner decision. It references events or a
  self-generated reason. It replaces `PlannerTrigger`.
- **Wake batch**: the wakes delivered to one planner turn. It replaces
  `PlannerTriggerBatch`.
- **Urgency**: an ordered class that controls gate bypass, supersede and
  preempt.
- **Delivery**: the timing mode, one of `PREEMPT`, `IMMEDIATE`, `DEBOUNCE` or
  `NONE`.
- **Candidate**: something a noticing sensor verified the player could
  perceive. A candidate is not yet an event; salience rules decide whether it
  becomes a percept.
- **Rule module**: a GraalJS `step(input, state, lib)` program for the
  `salience` or `attention` hook, with host-owned JSON state.
- **Constitution**: the fixed Java protections that no rule module can
  override.

### 4.3 Overview

```
 ┌─────────────── Perception (agent.perception) ───────────────┐   ┌─ Execution feedback ─┐  ┌─ Agent-internal ─┐
 │ Sensors (tick-sampled, budgeted):  physical, damage,        │   │ work projection,     │  │ planner, policy, │
 │   item-offer, presence, environment; noticing sensors emit  │   │ tasks/graphs/process,│  │ reflex decisions,│
 │   candidates (block, entity, dropped item) → salience rules │   │ watchdog, mining     │  │ session          │
 │ Ingress adapters (mixin callbacks): chat, craft, pickup,    │   │ opportunities        │  │                  │
 │   death/respawn, join/leave                                 │   └──────────┬───────────┘  └────────┬─────────┘
 └──────────────────────────────┬──────────────────────────────┘              │                       │
                                ▼  bus.publish(EventType, payload, cause)     ▼                       ▼
                   ┌──────────────────────────────────────────────────────────────────────────────────────┐
                   │ AgentEventBus → AgentEventLog (bounded ring, one seq space, explicit gaps)           │
                   │   synchronous subscribers: reflex inputs*, work tracker, dashboard, recorder, …      │
                   └───────────────────────┬──────────────────────────────────────────────────────────────┘
                                           ▼  every event
                   ┌──────────────────────────────────────────┐    decisions   ┌─────────────────────────┐
                   │ AttentionPolicy: Java constitution →     │───────────────►│ AttentionDecisionLog    │
                   │  GraalJS rules (+ salience) → Java clamp │                │ (bounded; dashboard/CLI)│
                   └───────────────────────┬──────────────────┘                └─────────────────────────┘
                                           ▼ wakes (urgency, delivery, eventRefs, coalescing key)
                   ┌──────────────────────────────────────────┐◄── idle hook: delegation continuation,
                   │ WakeScheduler (one per runtime)          │    safety-hold reminder, goal continuation,
                   │  pending set · debounce · gates ·        │    idle think (self-wakes)
                   │  supersession · budgets · satisfaction   │
                   └───────────────────────┬──────────────────┘
                                           ▼ ≤1 WakeBatch per tick (end of tick)
                   DialogueRuntime (owner routing, delegation, visible replies) → active PlannerOrchestrator
                                           ▼
                   observe = { wake: [...refs], current: {...}, events: [...] }   (evidence only)
```

`*` Reflex inputs move to the bus only if Phase 5 chooses to. The reflex keeps
its direct tick sampling for latency. It publishes its outputs through the
bus.

### 4.4 Event catalog, bus and log

```java
enum EventFamily { PERCEPT, EXECUTION, INTERNAL }
enum Visibility { PLANNER, DIAGNOSTIC }           // PLANNER = may appear in observe.events
enum Urgency { CRITICAL, DIRECT, HIGH, NORMAL, LOW, SELF }   // ordered
enum Delivery { PREEMPT, IMMEDIATE, DEBOUNCE, NONE }

record EventType(String id, EventFamily family, Visibility visibility,
                 Urgency urgency, Delivery delivery, boolean policyProtected,
                 Function<Map<String,Object>, String> coalescingKey,
                 Set<String> requiredFields) {}

record AgentEvent(long seqNo, long tick, long serverTick, long timestampMs,
                  String type, String source, long causeSeqNo, Map<String,Object> payload) {}
```

- `EventCatalog` declares every type, including the dynamic families as
  prefix entries. In Phase 1 its defaults reproduce today's G1 table exactly.
  A test fails if code publishes an undeclared type. Typed static factories,
  such as `Events.damageTaken(...)`, replace hand-built maps at call sites.
  Payload records are optional for existing types and preferred for new
  percepts.
- `AgentEventLog` keeps one sequence space and a bounded ring (**[ADR-0002]**:
  512 events unless Phase 0 data justifies more). It exposes the current
  `SemanticEventBuffer.query` API, so dashboard, recorder, CLI and evaluator
  consumers keep working, and it reports gaps. `plannerEventBuffer`
  disappears; planner visibility becomes a view over this log.
- `AgentEventBus.publish` runs only on the client thread. Integrated-server or
  async producers, such as the interaction logbook and future completions, go
  through a bounded `EventIngressQueue` drained at tick start. It counts drops
  and reports them as a gap event, as `pendingInteractions` does today.
  Subscribers run synchronously, and each failure is isolated and recorded.
- `cause` holds the event, or tool-call reference, that led to this event.
  Examples: `reflex.started` ← `combat.damage_taken#812`, and
  `work.changed` ← `call_abc`. This supports "why" views and lets wakes
  reference exact evidence (fixes D5).
- Components that need a fact subscribe to it and do not scan the ring
  (fixes D6).

### 4.5 Perception layer

```java
interface Sensor {
  String id();
  void sample(SensorContext ctx, PerceptSink sink);                 // once per tick, within budget
  default void onBoundary(LifecycleBoundary boundary) {}            // WORLD_CHANGED, DIMENSION_CHANGED,
}                                                                   // DIED, RESPAWNED, SHUTDOWN
```

- `SensorRegistry` owns sensor order, per-tick budgets, and a single
  lifecycle-boundary dispatch. This replaces the five duplicated reset blocks
  (problem 5).
- **Sensors produce candidates; salience rules produce percepts.** Most
  existing sensors publish percepts directly, because their logic is already
  fact-shaped (falls, damage, chat). The new noticing sensors (section 5) are
  split in two:
  - **Java part: honest candidates.** It decides what the player could
    actually perceive: line of sight, an exposed face, range, and enter/exit
    `Hysteresis` for entities. It also keeps a `NoticedMemory` (a bounded
    seen-set per world and dimension, with TTL) so each thing becomes a
    candidate once.
  - **GraalJS part: salience rules** (4.12). They decide which candidates
    matter now, for example dropping common garbage, clustering, or noticing
    a player punching the agent five times in two seconds. They then emit
    percepts. The rules see only candidate records, never the live world, so
    rules cannot "X-ray".
- `WindowedDetector` (AIRI's sliding or tumbling threshold detector) and
  `NoticeBudget` (a per-category cooldown plus an hourly cap) are helpers in
  the bundled JS rule library rather than Java classes.
- Migration map for today's producers:

| Today | Becomes |
|---|---|
| `PhysicalEventObserver` + `observePhysicalEvents` | `PhysicalSensor` (logic unchanged) |
| `ItemOfferObserver` + `observeItemOffers` | `ItemOfferSensor` |
| `LocalDamageTracker` + `onPlayerHealthUpdated` | `DamageSensor` (callback ingress plus tick pruning) |
| `NearbyPlayerTracker`, join/leave callbacks | `SocialPresenceSensor` |
| `ChatIngestService`, `onChatReceived`/`onSystemChatReceived` | `ChatIngress` (addressed, ambient, operator and system classification stays) |
| `SlowMiningObserver`, `WorkProgressWatchdog` | Stay in the work package as **monitors** (execution feedback), published through the bus |
| Smelting output poll, `MiningOpportunityJournal`, graph coordinator events | Execution feedback, published through the bus |

New sensors are described in section 5.

### 4.6 Attention policy

`AttentionPolicy.decide(AgentEvent, AttentionState) → WakeDecision(delivery,
urgency, ruleId, reason)`. `AttentionState` is an immutable snapshot taken
once per tick, plus incremental updates. It contains: decision owner,
actuator owner (idle, work, reflex, safety_hold, policy), accepted work,
queued tool work, planner in-flight/enabled/degraded/configured, external
driver, goal status and blocker `reconsiderEvents`, delegation phase, session
actuation allowed, evaluation suppression, proactive social mode, and the
guidance revision.

The policy runs in three stages. Within the rules, the layers are ordered and
the first decisive rule wins.

**Stage A: the Java constitution** (fixed, and never editable by rules):

1. **System gates.** Planner disabled, unconfigured or degraded, external
   driver, or world not loaded → `NONE`. Reset commands are handled before
   policy, as today.
2. **Protected.** `DIRECT` urgency (addressed chat, operator, evaluation chat)
   and `CRITICAL` safety handoffs → wake immediately. These decisions are
   applied synchronously on the client thread and never wait for the rule
   engine, so chat latency and safety handoffs don't depend on JS.
3. **Evaluation suppression.** The current list of autonomous types → `NONE`.

**Stage B: GraalJS attention rules** (4.12). The bundled default rule
module implements the remaining layers, then the budgets from 4.7:

4. **Ownership inhibition (AIRI's reflex inhibition, generalized).**
   - Reflex owns actuation → combat and physical percepts get `NONE`.
   - A job owns progress (collect-resource or mining) → pickups and crafts get
     `NONE`.
   - A pending craft tool result → crafts get `NONE`.
   - Accepted work or queued tool work → `LOW` and `SELF` get `NONE`.
     `HIGH` attention events, such as work stalled, still wake.
5. **Blocked goal (the gate).** Only `reconsiderEvents`, supervisory reflex
   events and `DIRECT` wake. Everything else gets `NONE`.
6. **Planner-authored rules** (`update_event_policy`, schema unchanged).
   `ignore` and `semantic_only` → `NONE`; `trigger_only` → wake. Protected types
   reject rules, as `policyBypass` does today.
7. **Social configuration.** Proactive social mode and chat distance.
8. **Type defaults** from the catalog.

**Stage C: Java clamp.** The host validates the rule output against the
constitution:
- Events whose type is `policyProtected` (today's `policyBypass` set: reflex
  handoffs, `task.blocked`, smelting output, graph outcomes, `player.*`) may be
  delayed by a rule, up to a bounded number of ticks, but never set to `NONE`.
  This stops any rule from hiding the outcomes the planner needs to avoid
  getting stuck.
- An event with a missing or invalid decision falls back to its catalog
  default.
- Each clamp is recorded in the decision log.

Phase 2 must reproduce today's effective behavior. The characterization suite
decides this, not the order above. The only exceptions are the listed
defects, and each fix is its own reviewed change. This policy replaces G1–G7
and G9; G8 stays in the orchestrator (session mechanics).

### 4.7 Wake scheduler

- **Pending set.** It holds wakes with urgency, delivery, event references, a
  coalescing key, the guidance revision and the mission id. Non-`DIRECT` wakes
  with the same key merge by keeping their event references; they don't
  replace each other (fixes D8's lost offers). `DIRECT` wakes never merge.
- **Satisfaction replaces ad-hoc dedupe.** A wake is satisfied once the
  active role's incorporated cursor has passed all its event references. This
  one rule absorbs D4's double paths and `hasIncorporatedDecisionEvent`.
- **Release.** Any `PREEMPT` or `IMMEDIATE` wake releases the batch at the next
  dispatch point if the planner can accept it. `DEBOUNCE` uses Cortico's
  formula in ticks (defaults to be tuned: quiet gap 10, minimum age 0,
  maximum age 60, maximum batch size 8). `NONE` never releases a batch; its
  events ride along through the cursor. The orchestrator's supersede-armed
  coalesce window (`computeCoalesceWindowMs`) moves here.
- **Planner availability.** If a turn is in flight and not replaceable, the
  batch waits. A `DIRECT` batch with a replaceable in-flight turn supersedes
  it, using today's orchestrator mechanism. A `PREEMPT` batch cancels an
  unexternalized turn (Phase 5). The batch also waits while the orchestrator
  is awaiting an accepted-reply record, a side-effect tool or compaction.
- **Supersession.** A `DIRECT` wake increments the guidance revision. Pending
  `HIGH`, `NORMAL` and `LOW` wakes from older revisions, or older missions,
  are dropped and recorded as `planner.wake_superseded`, which renames
  `planner.internal_task_update_superseded`. Their events stay in the log and
  are observed anyway.
- **Idle hook (Cortico `onIdle`).** It runs when no waking wake is pending,
  the planner is idle and work is idle. Ordered generators each keep their
  own cadence: delegation continuation, then the safety-hold decision
  reminder, then goal continuation, then idle think. These are `SELF` wakes,
  and any other pending wake drops them (AIRI's stale follow-up dropping). This
  retires W4, W5 and W6 as separate paths, and `invalidateIdleThinkTriggers`.
- **Budgets.**
  - A leaky bucket limits autonomous wakes. It lives in the default GraalJS
    attention rule, not in the scheduler. The rule keeps the bucket level in
    its host-owned state, leaks it by tick delta, and degrades `NORMAL` and
    `LOW` wakes to `NONE` while it is above the trigger level. Each such
    decision is recorded with the rule's reason. Rule authors, and later the
    planner, can therefore retune or replace the budget without a Java
    change.
  - A supersede budget limits chat spam that supersedes every turn. After N
    supersedes within M ticks, `DIRECT` wakes queue instead of superseding.
  - The pending set is bounded. When full, it drops the lowest urgency and
    records the drop.

### 4.8 Planner intake: wakes that reference evidence

`WakeBatch(userTurns, wakes, supersedes, maxUrgency, throughSeqNo)` is passed
to `DialogueRuntime.deliver` and then to the active role's `submit`.

- `userTurns`: chat lines, rendered as today's `CHAT` user turn.
- `wakes`: rendered into the observation as a new additive field:

```json
{"wake": [{"reason": "event", "seqNo": 9121, "type": "perception.block_noticed", "urgency": "normal"},
          {"reason": "goal_continuation"}],
 "current": {"...": "..."}, "events": [{"seqNo": 9121, "type": "perception.block_noticed", "payload": {"...": "..."}}]}
```

- Trigger prose is removed in Phase 3. Today's imperative text moves:
  - Static guidance goes to the system prompt and tool descriptions. For
    example, "when woken for `goal_continuation`…" and "a safety hold awaits
    `continue` or `clear_queue`".
  - Situation facts go into structured payload fields, for example
    `recoveryWindowTicks: 600` and `pendingDecision: {holdId, options}`.
  - The remaining situation-specific coaching goes to a `DecisionHints`
    presenter keyed by event type, rendered as `observe.hints` with provenance
    `"runtime_hint"`.
- The legacy semantic notice channel (E2) retires: `pendingSemanticEvents`,
  `SemanticContextProjector` notices, overflow flush (W8) and
  `plannerEventBuffer`. Aggregations worth keeping, such as pickup sums, move
  into the observation renderer. Visibility comes from the catalog
  (`Visibility.PLANNER`) instead of G10's prefix list. That brings `social.*`
  and `action_graph.*` failures into `observe.events` (fixes D8). Whether a
  planner-authored `ignore` rule also hides an event from `observe` is decision
  O8.

### 4.9 Tick phases and threading

```
tickClient
  0 ingress.drain()          off-thread queues → events (interaction logbook, async completions)
  1 session.poll()           lifecycle boundaries → sensors.onBoundary(...)
  2 perception.sample()      all sensors, budgeted; settle aggregations
  3 reflex.tick()            System 1; publishes reflex.*
  4 dialogue.poll()          apply completed planner results (current position kept)
  5 executors.tick()         graphs, jobs, follow, lighting, monitors
  6 rules.collect()          apply the rule engine's result for the previous tick's step
                             (percepts published, decisions clamped and queued), then
                             submit this tick's step: new events + candidates + state
  7 scheduler.dispatch()     one WakeBatch at most
```

Chat and damage callbacks still arrive between ticks. They publish
immediately, and their wake is dispatched at the end of the next tick, adding
at most 50 ms of latency. Constitution decisions (Stage A) are made
synchronously when an event is published. Rule-decided events (Stage B) are
decided one tick later, because the rule step runs on the rule engine's
worker thread (4.12). Ordering is then explicit (problem 6), and
aggregated percepts are settled before a batch leaves (Cortico's
settle-before-flush rule).

### 4.10 Observability

- Each `AgentEvent` has a `source` and a `cause`.
- `AttentionDecisionLog` is bounded. It records every event's decision (rule
  id, reason, urgency, delivery), every batch's members, and every drop,
  supersede and budget degradation. It subsumes `recordEventRouting` and
  `EventPolicyIntervention`.
- Surfaces: a new dashboard panel ("why did / didn't the planner wake"), a
  flight-recorder export section, and optionally `airicraft agent debug
  wakes`.
- Evaluation metrics:
  - wakes per minute, by reason
  - supersedes
  - **empty wakes**: turns whose observation had no new planner-visible
    event and no user turn
  - **outcome latency**: from a work-terminal event to the first request
    incorporating it (ADR-0002 already measures this)
  - chat reply latency
  - tokens per hour

### 4.11 Invariants to preserve

- [ ] Evidence exists independently of wakes. Each role incorporates it
  through a runtime-issued `observe` exchange, with per-role cursors and
  explicit `missingEventRange` gaps (ADR-0002).
- [ ] New guidance supersedes wakes but never the effects of earlier work
  (`userGuidanceRevision`).
- [ ] Safety epoch and hold rules reject stale responses. The reflex gates
  actuation, not reasoning. Bounded, event-driven inspection during a reflex
  still works.
- [ ] There is exactly one gameplay decision owner (controller or thinker).
  Wakes go to the current owner.
- [ ] A blocked goal wakes only for `reconsiderEvents` and supervisory events,
  with no idle loops.
- [ ] Goal continuation happens only when idle, after a quiet interval. A
  plaintext reply does not end a goal.
- [ ] External-driver mode produces no autonomous wakes. The degraded-mode and
  hosted auto-reset semantics are unchanged.
- [ ] Tick-debug pause freezes perception, attention and wakes, and paused
  time does not count as idle time (fixes D7).
- [ ] Tool schemas and prefixes stay frozen. `update_event_policy` and
  `block_planner_goal` keep their schemas.
- [ ] Event ids stay stable, because they are model-visible and persisted.
- [ ] Recorder and dashboard exports stay replayable, and changes to them are
  additive only.
- [ ] All new state is bounded.
- [ ] No rule, bundled or authored, can suppress a protected event or delay
  a `DIRECT` or `CRITICAL` wake (the Stage A constitution and the Stage C
  clamp).

### 4.12 Rules in GraalJS: agentic authorship

Salience and attention judgement are written as GraalJS modules. That lets
people and coding agents author them without a Java rebuild. In a later phase
the planner can inspect and edit them too. The engine reuses the sandbox
already used by `GraalPolicyInvocation` and `query_world`:
`HostAccess.NONE`, no class lookup, IO, threads, processes or native access,
and a statement limit, running on a dedicated daemon worker thread.

**Contract.** Each tick the host makes one call per module with a JSON value.
There are no host objects and no per-event host round trips.

```js
// attention/default.js (bundled)
function step(input, state, lib) {
  // input: { tick, attention: {actuatorOwner, acceptedWork, goal, delegation, …},
  //          events: [ {seqNo, type, urgency, delivery, payload, …} ],   // Stage-B events only
  //          candidates: [ {kind: "block"|"entity"|"item", …evidence} ] }   // from noticing sensors
  // state:  this module's previous JSON state (host-owned, size-capped)
  // returns { percepts: [...], decisions: [{seqNo, delivery, urgency, reason}], state }
}
```

- **Host-owned state.** A module keeps no hidden state. It receives its
  previous JSON state and returns the next one, capped at 16 KiB (to be
  tuned), for example the leaky bucket level, window counters and cooldowns.
  This makes rule evaluation **deterministic and replayable**: wake replay
  (Phase 0) can feed a recorded event log plus an initial state and get
  exactly the same decisions. It also makes state inspectable in the
  dashboard and by the planner. If a step fails, its returned state is
  discarded and the previous state is kept.
- **Determinism.** Time comes only from `input.tick`. `Date.now` and
  `Math.random` are replaced with a tick-based clock and a seeded generator
  provided through `lib`.
- **Bundled library** (`src/main/resources/airicraft/rules/lib.js`, shipped
  with the build and readable through a docs tool, like `read_policy_docs`
  today). It provides `leakyBucket`, `slidingWindow` and `tumblingWindow`
  (AIRI's temporal detector), `cooldown` and `hourlyCap` (Cortico-style
  notice budgets), and `cluster`. The library is plain JS, so authored
  rules can use it, copy it or replace it.
- **Rule sets.** There are two hook points, `salience` (candidates →
  percepts) and `attention` (events → decisions). Bundled defaults live in
  `src/main/resources/airicraft/rules/`. They can be overridden from
  `config/airicraft/rules/*.js` and reloaded with `airicraft reload`. This is
  where people and coding agents author rules today.
- **Budgets and failure handling.**
  - The statement limit is reset each step.
  - The step has a deadline measured in ticks. If a step overruns or throws,
    that tick's Stage-B events fall back to catalog defaults and
    `rules.step_failed` is published.
  - After K consecutive failures, the module reverts to its previous version
    (or to the bundled default) and `rules.reverted` is published.
  - Guest heap is not hard-limited inside the shared JVM, a limitation the
    policy docs already state. Capped state and capped input sizes are the
    mitigation.
- **Performance.** The client runs on JBR 21, so Graal JS runs
  interpreter-only. Phase 0 includes a spike that measures one step with
  the default rules and 20 events and 50 candidates. Stage A never waits on
  it.
- **Planner authorship (Phase 6).** This follows the
  `define_tool`/`inspect_tool` precedent in `SelfToolProvider`. It adds native
  tools `inspect_rules` (source, state, recent decisions and their rule
  reasons) and `update_rules` (replace one module's source, with a reason).
  An update is **dry-run by replay** before it is activated: the runtime
  replays the last N logged events through both versions and returns the
  decision diff in the tool result, so the planner sees what its edit would
  have changed.
  - Updates are versioned (a bounded history) and can be rolled back.
  - Updates are session-local at first; the persistence scope is decision
    O11.
  - Updates can never change the constitution or the clamp.
  - `update_event_policy` keeps its frozen schema. It becomes a shim that
    writes a rules table the default attention module reads, so existing
    prompts and persisted behaviour keep working.

**Phase 0 measurement (2026-09-27).** The [corrected three-run spike](../../experiments/2026-09-27-attention-rule-engine-spike.md)
used JBR 21 and the production sandbox restrictions. Median context build was
410.852 ms, first step 80.828 ms, early handoff p99 6.955 ms, and steady-state
handoff p99 1.462 ms after 500 warmup steps. Proceed for steady state, with
startup measured separately in a live client. Synthetic input exercised
20 events and 50 candidates (about 10.7 KiB), with state 6,995 bytes under the
16 KiB cap. Keep those initial batch caps; larger traces need measurement.
Both 50,000 and 200,000 statements completed the workload. Use 50,000 as the
initial statement cap and a one-tick (50 ms) delivery deadline: a late Stage-B
result falls back for that tick. The worker's hard cancellation ceiling remains
one second as in the existing sandbox; the spike proves that ceiling for
throw, statement exhaustion, and oversized-state failures. The measured cold
step exceeds one tick, so context preparation and cold fallback must be
accounted for explicitly in Phase 1/2. Stage A remains synchronous and never
waits for the worker. This is benchmark evidence, not live scheduling proof.

## 5. Salience perception: blocks, entities and dropped items

Three noticing sensors share one pattern. Java produces **honest
candidates**: things the player could actually perceive, each once, with
evidence. The GraalJS `salience` rules then decide which candidates become
percepts (4.12). Decision O6 resolved the scope: notable blocks, entities,
and dropped items other than common garbage.

| Sensor (Java) | Candidate when | Candidate record | Default salience rule |
|---|---|---|---|
| `NotableBlockSensor` | A block has at least one face exposed to air or a transparent block, **and** a budgeted raycast from the eyes reaches that face within `radius` (12 blocks proposed). Fully enclosed blocks are never candidates (no X-ray). | `blockId, position, exposedFaces, distance, direction, evidence: line_of_sight_to_exposed_face, dimension` | A notable-block list (diamond, emerald and ancient-debris ores, spawners, chests, portals); adjacent same-type blocks cluster into one vein percept with a count |
| `EntityNoticeSensor` | An entity enters perception range with line of sight (*seen*), or is audible but occluded within a shorter range (*heard*). Enter/exit hysteresis applies, as in Cortico's proximity map | `entityType, uuid, name/customName, tamed/owner, baby, distance, direction, modality, holding/equipment summary` | Other players, villagers and traders, named or tamed animals, and passive mobs that are food or breeding sources when relevant. Hostile mobs are skipped while the reflex tracks them; the reflex's threat memory stays authoritative and is not duplicated |
| `DroppedItemSensor` | An item entity is seen (line of sight) within `radius`, the first time for that entity uuid | `itemId, count, age, position, distance, attribution` (`thrown_by_player` from the existing offer inference, `own_mining_drop` when a job owns it, else `unknown`) | Every item **except common garbage**: `dirt`, `cobblestone`, `cobbled_deepslate`, `netherrack`, `gravel`, `sand`, `stone` variants (andesite, diorite, granite, tuff), `rotten_flesh`, seeds and similar. Garbage is **contextual**: an item stops being garbage when the goal, constraints or an inventory shortage needs it, for example cobblestone while the goal is a furnace. The JS rule decides this from `input.attention`. |

Shared behaviour:

- **Scanning within a budget.** Blocks are scanned incrementally from a shell
  around the player that moves with them, at most K positions and R raycasts
  per tick. Entities and items are read from the client entity list, which
  is already filtered by range, with at most R raycasts per tick. Cost is
  measured with Arthas `trace`.
- **Memory.** `NoticedMemory` is keyed by position (blocks) or uuid (entities
  and items), per world and dimension. It is a bounded LRU with a TTL, so
  something seen again long after it was forgotten can be noticed again.
- **Events.** `perception.block_noticed`, `perception.entity_noticed`,
  `perception.entity_lost` (only for tracked entities the rules care about),
  and `perception.item_noticed`. All are family `PERCEPT`, visibility
  `PLANNER`, urgency `NORMAL`, delivery `DEBOUNCE`. The rules may lower any
  of these.
- **Existing offers stay.** `social.item_offered` keeps its id and its
  inference. `DroppedItemSensor` reuses the same geometry, so one physical
  drop does not produce both an offer and a separate noticed-item wake. The
  rule suppresses `item_noticed` when the item already has an offer percept.
- **Attention.**
  - An executor that is doing that exact work owns the percept (`NONE`).
    Examples: a mining job for that block id, a collection job picking up its
    own drops, or the reflex tracking a hostile.
  - When the agent is idle, percepts wake after debounce.
  - While other work runs, they also wake, debounced and inside the leaky
    bucket (recommendation for O5), so the model can choose to divert
    (Cortico's minimal-intervention principle).

Example flow: the agent walks along a ravine, and three `diamond_ore` blocks
with an exposed face come into line of sight 7 blocks away.
1. `NotableBlockSensor` produces three candidates.
2. The salience rule clusters them into one `perception.block_noticed` with
   `count: 3`.
3. The attention rule finds the leaky bucket below its trigger level and
   chooses `DEBOUNCE`.
4. Ten quiet ticks later the scheduler releases a batch with
   `wake: [{seqNo, type: perception.block_noticed}]`.
5. The controller sees the fact in `observe.events` and decides to mine it,
   `remember_place` it, or ignore it. No prose tells it what to do.

A second example: a player drops a bread stack near the agent. The existing
offer inference publishes `social.item_offered`, and `perception.item_noticed`
is suppressed for that entity. A cobblestone drop from someone else's mining
is garbage and is recorded as a candidate only, unless the goal needs
cobblestone.

Other sensors, in order of value:

- `EnvironmentSensor`: dusk and dawn, weather, dimension or biome change, and
  darkness at the feet (Cortico's `isDark`, a connected-light mode). It emits
  on transitions only.
- `InventoryDeltaSensor`: aggregated deltas with resulting totals, such as
  `oak_log +4 (13)`. They become the canonical inventory fact. Pickup and craft
  events remain as attribution.
- Social gestures (AIRI's punch and teabag windows) as salience rules over
  entity animation candidates. These are optional, useful for companion
  behaviour rather than survival.

## 6. Migration plan

Each phase ends with a green `./gradlew build`, the characterization suite,
and an evaluation batch (`scripts/run-evaluation-scenarios`, all scenarios).
Phases 3 and 4 change what the model sees and also require live playtests.
Phases ship as several small PRs to limit conflicts in `EmbodiedAgentRuntime`.

### Phase 0: characterize and decide (no behaviour changes)

Production edits are limited to package-private test seams and additive
debug-timeline instrumentation (`planner_wake` entries). Task-level plan:
[`plans/2026-09-26-planner-event-system-phase-0.md`](../plans/2026-09-26-planner-event-system-phase-0.md).

- [x] Generate the event inventory (Appendix A) from code and assert it in a
  test that enumerates `createEventRoutingProfiles()`.
- [x] Build a wake-scenario harness on `EmbodiedAgentRuntime.createForTests`
  with a recording backend. The existing deterministic-response tests in
  `PlannerOrchestratorTest` and `DialogueRuntimeTest` are a starting point. For
  each scenario, record golden files under `src/test/resources/planner/wakes/`
  containing submitted requests, the tick of each request, rendered
  user/notice messages, and `observe.events`. Cover:
  - chat while idle, busy, and with a replaceable in-flight turn
  - ambient chat with proactive mode on and off
  - a pickup during a mining job
  - reflex start and resolve, with and without a hold
  - work stalled while accepted work runs
  - a task terminal cascade
  - goal continuation, and a blocked goal with reconsider
  - idle think, and idle think invalidated by an action goal
  - delegation start
  - degraded mode, external driver, and evaluation suppression
  - tick-debug pause
- [x] Build the **wake ledger** (the first half of wake replay). It
  reconstructs, for each planner request in a recorded run, why the request
  was made and which evidence was newly incorporated. Its input is the
  `RuntimeFlightRecorder` output written by evaluation runs and automatic
  playtests. Phase 2 adds a Java replay harness that emits the same ledger
  format from the new policy, and the two ledgers are diffed. Phases 2 and 3
  both use it.
- [x] Confirm or refute D1–D8 with focused tests and write the outcome into
  this document.
- [x] **GraalJS rule-engine spike.** Using the `GraalPolicyInvocation`
  sandbox settings on the interpreter-only JBR 21 runtime, measure the
  latency of one `step()` with a draft default attention rule, 20 events and
  50 candidates, both cold and warm, and its steady-state memory. The result
  sets the step deadline and the input caps, and confirms that one-tick-late
  Stage-B decisions are acceptable.
- [x] Record baseline metrics (4.10) from three evaluation batches and one live
  playtest: [results, spread and tolerance](../../experiments/2026-09-27-planner-wake-baseline.md).
- [x] Resolve section 9 (accepted 2026-09-26).
- [x] Write ADR-0003 and add the vocabulary to `CONTEXT.md`.

Exit: characterization suite green on `dev`; decisions recorded. Implementation
and baseline validation are complete on PR #81; merging into `dev` remains pending.

### Phase 1: event catalog and a single log (behaviour-preserving)

- [ ] Add `EventCatalog`, `AgentEvent`, `AgentEventBus`, `AgentEventLog`
  (implementing the `SemanticEventBuffer` query API) and `EventIngressQueue`.
- [ ] Route all ~70 `append` call sites and the drained producer queues
  (reflex, graph coordinator, interaction logbook) through `publish`, adding
  `source` and `cause`. Keep `plannerEventBuffer` as a derived subscriber for
  now so behaviour is identical.
- [ ] Add a single `LifecycleBoundary` dispatch that replaces the duplicated
  observer resets.
- [ ] Replace ring scans used as state channels, starting with the `food.eaten`
  lookup, with subscribers (D6).

Exit: no golden diffs; dashboard, recorder, CLI (`agent events recent`) and
evaluator (`semanticEventContains`) unchanged.

### Phase 2: attention policy and wake scheduler (behaviour-preserving)

- [ ] Add `AttentionState`, the Stage A constitution and Stage C clamp in
  Java, and `AttentionDecisionLog`.
- [ ] Add the GraalJS rule engine (4.12): a worker thread, the per-step JSON
  contract, host-owned state, the deterministic clock and seed, failure
  fallback and revert, `rules/lib.js`, and loading overrides from
  `config/airicraft/rules/` on `airicraft reload`.
- [ ] Write the bundled `attention/default.js` so that it reproduces G1–G7
  and G9. Planner-authored `update_event_policy` rules become a table this
  module reads. The golden suite runs through the real engine.
- [ ] Add `WakeScheduler` with the pending set, satisfaction, supersession and
  idle hook. Retire the W2/W3 deque, `continuePlannerGoal` scheduling,
  `IdleIdeaScheduler` timing (it becomes the idle-think generator), the
  delegation start wake, evaluation seed wakes, and FIFO/checkpoint result
  review (W9) as separate scheduling paths. Preserve their characterized
  evidence and tool-result delivery.
- [ ] Move trigger prose out of `EmbodiedAgentRuntime` into a presenter keyed
  by event type, producing the same strings in this phase. Move the orchestrator's
  coalesce window into the scheduler. If tick timing changes delivery, record
  the difference.
- [ ] Add the dashboard panel for wake decisions.

Exit: golden prompts unchanged (except documented timing); wake replay of at
least two recorded playtests shows no unexplained decision diffs; the
evaluation pass rate and planner-turn counts stay within the
[Phase 0 baseline tolerance](../../experiments/2026-09-27-planner-wake-baseline.md#phase-2-tolerance).
For each scenario, pass/fail must be no worse than its worst baseline result;
turns and available request-span rates must stay within `[0.9 × minimum,
1.1 × maximum]` across baseline runs. Compare like clock windows: evaluator
rates estimate the dispatch span, whereas the automatic Play has a full server
window. Single-call estimates cannot establish request-rate thresholds, and
incomplete token records cannot establish token-rate thresholds. Wake-path
changes require an explained, characterized defect fix;
new failure classes and recording gaps are not excused by baseline failures.
`EmbodiedAgentRuntime` loses the routing table, the trigger
factories and the suppression helpers.

### Phase 3: wakes that reference evidence (changes what the model sees)

- [ ] Add the `observe.wake` field. Remove trigger prose; move static guidance
  into the prompt and tool descriptions and situation coaching into
  `DecisionHints`.
- [ ] Take visibility from the catalog and bring `social.*` and
  `action_graph.*` into `observe`.
- [ ] Retire E2: `pendingSemanticEvents`, `SemanticContextProjector` notices,
  overflow flush (W8) and `plannerEventBuffer`.
- [ ] A/B test: a live playtest plus an evaluation batch against the Phase 2
  build.

Exit: fewer empty wakes; outcome latency and chat latency no worse; tokens per
hour no worse; no new failure classes in playtest review.

### Phase 4: perception layer and salience sensors

- [ ] Add `agent.perception` with the Sensor API, the registry,
  `Hysteresis` and `NoticedMemory`, with unit tests.
- [ ] Migrate the existing observers (4.5 table).
- [ ] Add `NotableBlockSensor`, `EntityNoticeSensor`, `DroppedItemSensor`,
  `EnvironmentSensor` and `InventoryDeltaSensor`. Put the Java-side budgets
  (radius, K, R) in `agent.yml` (`perception:`), reloadable through
  `airicraft reload`.
- [ ] Write the bundled `salience/default.js`: notable blocks, entity
  categories, the contextual garbage list, vein clustering, and offer
  deduplication.
- [ ] Add new evaluation scenarios:
  - `notice-diamond`: an exposed diamond vein beside a path, and a fully
    enclosed one that must **not** be noticed.
  - `notice-drop`: valuable and garbage drops near the path.

  Check the wakes, and review what the controller chooses to do.
- [ ] Budget check: perception tick cost measured with Arthas `trace` on a
  loaded world, within the budget set in Phase 0.

### Phase 5: priority refinements

- [ ] `PREEMPT` for safety-epoch changes: cancel an unexternalized in-flight
  turn instead of paying for it and then rejecting it as stale.
- [ ] Supersede budget (Java scheduler). Add the autonomous-wake leaky bucket
  and the per-category notice budgets to the bundled JS rules, each tuned from
  metrics. These are rule edits and need no Java changes.
- [ ] Update `AGENTS.md` (behavior notes, key files), the docs index, and
  ADR-0003 (final).

### Phase 6: planner-authored rules

- [ ] Add native tools `inspect_rules` and `update_rules`, which change
  the frozen native tool prefix in a deliberate release. They follow the
  `SelfToolProvider` precedent: bounded source size, versioning with a bounded
  history, rollback, and session-local scope unless O11 decides otherwise.
- [ ] Dry-run every update by replaying the last N logged events through the
  old and new module, and return the decision diff in the tool result.
  Reject any update that fails to evaluate.
- [ ] Automatically revert on repeated step failures, and publish
  `rules.reverted` so the planner sees it in `observe`.
- [ ] Add prompt guidance and `read_rules_docs` (the contract, `lib.js`, and
  the constitution the planner cannot change).
- [ ] Live playtest in which the planner tunes its own attention, for example
  muting noticed-item wakes while building. Review rule edits in the decision
  log.

## 7. Verification strategy

| Level | What |
|---|---|
| Unit | Constitution and clamp decision tables in Java; `WakeScheduler` timing with a fake tick source; `Hysteresis` and `NoticedMemory`; sensors with synthetic samples (the `PhysicalEventObserver` tests are the model) |
| Rules | Bundled JS modules run in the real Graal sandbox against JSON fixtures, with the state threaded through consecutive steps (bucket fill and leak, windows, clustering, contextual garbage); determinism check (same input and state give identical output); failure paths (throw, statement limit, oversized state) |
| Characterization | Golden wake scenarios (Phase 0), unchanged through Phases 1–2 |
| Replay | Recorded playtest JSONL through the old and new policy, diffing decisions |
| Integration | `EmbodiedAgentRuntimeTest`, `DialogueRuntimeTest`, `PlannerOrchestratorTest` |
| Evaluation | All `scenarios/*` through the batch harness, before and after each phase, comparing pass/fail, planner turns and time used |
| Live | Automatic and hosted playtests for Phases 3–6; export the incident before rebuilding (`docs/live-playtest-recording.md`) |

## 8. Compatibility surface

| Surface | Constraint |
|---|---|
| Event ids | Frozen. They are model-visible (`observe`, `update_event_policy`, `reconsiderEvents`) and persisted (`planner-goal.json`). New percepts use a new `perception.*` namespace. |
| `observe` JSON | Additive only (`wake`, `hints`). The cursor fields stay as they are. |
| Tool schemas | Frozen (`update_event_policy`, `block_planner_goal`, `observe`). |
| Flight recorder and dashboard JSONL | The `SemanticEvent` shape stays, extended with `source` and `cause`. The decision log is a new section. |
| CLI and bridge | `agent events recent` and `agent debug idle-trigger` keep their behaviour. The idle trigger is routed through the scheduler's idle-think generator. |
| Evaluator | `EvaluationAddonRuntime` uses `recentEvents(null)` as evidence and `semanticEventContains` for checks; both stay. Planner-call contract v1 has no trigger fields, so it is unaffected. |
| Debug overlay | `PlannerDebugOverlay` reads `triggerBatch`. It must be adapted to `WakeBatch`. |

## 9. Decisions

All decisions were accepted on 2026-09-26. O6 and O7 were changed from the
original recommendation; the rest were accepted as recommended.

| # | Decision | Outcome |
|---|---|---|
| O1 | Should the refactor preserve behaviour first (Phases 1–2), with model-visible changes isolated in Phase 3? | **Yes.** It keeps regressions attributable. |
| O2 | Priority model: an AIRI-style numeric priority queue, or Cortico-style delivery modes plus an urgency enum decided by a layered policy? | **Urgency plus delivery, decided by the policy.** Batches are delivered whole, so ordering within a queue matters less than when to wake and whether to interrupt. |
| O3 | Preemption scope | Direct guidance supersedes, as today. Add `PREEMPT` for safety-epoch changes. Never preempt after a side-effect tool has executed. |
| O4 | Clock for debounce and idle timing | **Agent ticks.** LLM retry and backoff stay on wall clock. |
| O5 | Should salient perceptions wake the planner while other work runs? | **Yes, debounced and inside the autonomous-wake budget.** When the owning executor is doing that exact work, `NONE`. |
| O6 | What can be noticed | **Decided:** notable blocks, entities, and dropped items other than common garbage (section 5). The honesty rule applies to all three: line of sight within range, and for blocks an exposed face; no X-ray. Whether an item is garbage depends on the goal and inventory, decided by the salience rule. |
| O7 | How rules are authored | **Decided: GraalJS.** Rules are written for flexible agentic authorship, and the planner will be able to edit its own rules in a later phase (Phase 6). The leaky bucket and other budgets live inside the JS rules. Java keeps only the constitution and clamp (4.6, 4.12). |
| O8 | `update_event_policy` semantics once E2 retires: should `ignore` also hide events from `observe`? | **No, keep evidence visible.** `ignore` only stops wakes. Say so in the tool description without changing the schema. |
| O9 | Event log bound **[ADR-0002]** | Keep 512 unless the Phase 0 gap measurements show loss within typical decision intervals. |
| O10 | Where the scheduler lives | A new package `agent.attention`, owned by `EmbodiedAgentRuntime`, delivering to `DialogueRuntime`. |
| O11 | How long planner-authored rules persist (Phase 6) | **Session-local first**, like `define_tool`. Consider world-persisted rules, stored next to `planner-goal.json`, only after live evidence that edits help across sessions. Operator files in `config/airicraft/rules/` always take precedence over bundled defaults. |
| O12 | Which layers are in the constitution | Stage A: system gates, protected `DIRECT`/`CRITICAL` wakes, evaluation suppression. Stage C: protected types can be delayed but never dropped. Ownership inhibition and the blocked-goal gate stay in JS. They are heuristics, but the golden suite guards them. |
| O13 | Whether rule engine failures should also stop wakes | **No.** Events fall back to catalog defaults, and repeated failures revert the module. An authored rule must never leave the agent unable to wake. |

## 10. Risks

- **Timing drift changes model behaviour.** Mitigations: behaviour-preserving
  phases, golden prompts, wake replay, and A/B testing only in Phase 3.
- **Conflicts with ongoing feature work in `EmbodiedAgentRuntime`.**
  Mitigations: small PRs, strangler adapters (`AgentEventLog` implements the
  old buffer API), and announcing a short freeze on the runtime's event
  section during Phase 2.
- **Over-waking from new sensors.** Mitigations: `NONE` by default for
  low-value percepts, the autonomous-wake budget, and tracking wakes per
  minute as a phase exit metric.
- **Perception CPU cost.** Mitigations: per-tick budgets, incremental scans,
  and measurement with Arthas `trace`.
- **GraalJS cost and robustness.** Graal runs interpreter-only on JBR 21, and
  guest heap is not hard-limited. Mitigations: the Phase 0 spike, one call
  per tick with batched JSON, capped input and state, a step deadline,
  default fallback, and automatic revert. Stage A keeps chat and safety
  handoffs independent of JS.
- **Planner-authored rules that tune attention badly** (Phase 6). For
  example, the planner mutes everything and misses important events.
  Mitigations: the constitution and clamp, dry-run replay diffs before
  activation, versioning with rollback, session-local scope, and rule edits
  shown in the decision log. The planner-authored ruleset is an explicit
  playtest review item.
- **Rendering differences between the Codex and OpenAI backends.** Both use
  the decision context today. Include one Codex-driver smoke test in the
  Phase 3 checks.

## Appendix A: Phase 0 event inventory

Generated from `src/test/resources/planner/wakes/event-inventory.json`.
S = semantic eligibility; T = trigger; B = policy bypass; O = observe visibility;
W = additional task wake path. Dynamic families use a prefix entry.

| Type | Producer | S | T | B | O | W |
|---|---|---|---|---|---|---|
| `action_graph.goal_admission` | EmbodiedAgentRuntime | – | – | – | – |  |
| `action_graph.goal_cancelled` | ActionGraphCoordinator | – | – | – | – |  |
| `action_graph.goal_resumed` | ActionGraphCoordinator | – | – | – | – |  |
| `action_graph.goal_runnable` | ActionGraphCoordinator | – | – | – | – |  |
| `action_graph.goal_started` | ActionGraphCoordinator | – | – | – | – |  |
| `action_graph.goal_suspended` | ActionGraphCoordinator, EmbodiedAgentRuntime | yes | SYSTEM | yes | – |  |
| `action_graph.goal_terminal` | ActionGraphCoordinator, EmbodiedAgentRuntime | yes | SYSTEM | yes | – |  |
| `combat.damage_taken` | EmbodiedAgentRuntime | yes | DAMAGE | – | yes |  |
| `crafting.item_crafted` | EmbodiedAgentRuntime | yes | CRAFT | – | yes |  |
| `follow.stuck` | EmbodiedAgentRuntime | yes | – | – | – |  |
| `follow.target_acquired` | EmbodiedAgentRuntime, FollowCapability | yes | – | – | – |  |
| `follow.target_lost` | EmbodiedAgentRuntime, FollowCapability | yes | – | – | – |  |
| `food.eat_failed` | EmbodiedAgentRuntime | – | – | – | yes |  |
| `food.eat_started` | EmbodiedAgentRuntime | – | – | – | yes |  |
| `food.eaten` | EmbodiedAgentRuntime | – | – | – | yes |  |
| `food.unavailable` | EmbodiedAgentRuntime | – | – | – | yes | W3 |
| `lighting.torch_placed` | EmbodiedAgentRuntime | yes | – | yes | yes |  |
| `mission.submitted` | EmbodiedAgentRuntime | – | – | – | – |  |
| `objective.changed` | EmbodiedAgentRuntime | – | – | – | yes |  |
| `pickup.item_picked_up` | EmbodiedAgentRuntime | yes | PICKUP | – | yes |  |
| `planner.degraded_blocked` | DialogueCore | – | – | – | – |  |
| `planner.degraded_cleared` | DialogueCore | yes | – | – | – |  |
| `planner.degraded_entered` | DialogueCore | yes | – | – | – |  |
| `planner.goal_cleared` | EmbodiedAgentRuntime | yes | – | – | – |  |
| `planner.goal_set` | EmbodiedAgentRuntime | yes | – | – | – |  |
| `planner.internal_task_update_superseded` | DialogueRuntime | – | – | – | – |  |
| `planner.parse_error` | DialogueCore | – | – | – | – |  |
| `planner.provider_error` | DialogueCore | – | – | – | – |  |
| `planner.reset_requested` | DialogueCore | yes | – | yes | – |  |
| `planner.response_applied` | EmbodiedAgentRuntime | – | – | – | – |  |
| `planner.stale_response_rejected` | EmbodiedAgentRuntime | yes | – | yes | – |  |
| `planner.timeout` | DialogueCore | – | – | – | – |  |
| `planner.unknown_intent` | DialogueCore | – | – | – | – |  |
| `player.action_rejected` | EmbodiedAgentRuntime | yes | – | yes | yes |  |
| `player.actions_cancelled` | EmbodiedAgentRuntime | yes | – | yes | yes |  |
| `player.death_place_save_failed` | EmbodiedAgentRuntime | – | – | – | yes |  |
| `player.death_place_saved` | EmbodiedAgentRuntime | – | – | – | yes |  |
| `player.died` | EmbodiedAgentRuntime, SessionRuntime | yes | – | yes | yes |  |
| `player.physical` | EmbodiedAgentRuntime | yes | SYSTEM | yes | yes |  |
| `player.respawn_request_failed` | EmbodiedAgentRuntime | yes | – | yes | yes |  |
| `player.respawn_requested` | EmbodiedAgentRuntime | yes | – | yes | yes |  |
| `player.respawned` | EmbodiedAgentRuntime, SessionRuntime | yes | – | yes | yes |  |
| `policy.event_intervened` | AgentEventPipeline, EmbodiedAgentRuntime | – | – | – | yes |  |
| `policy.rule_rejected` | EmbodiedAgentRuntime | – | – | – | yes |  |
| `policy.travel_changed` | EmbodiedAgentRuntime | – | – | – | yes |  |
| `reflex.action_changed` | SurvivalReflexRuntime | yes | – | yes | yes |  |
| `reflex.actuator_failed` | SurvivalReflexRuntime | yes | – | yes | yes | W2 |
| `reflex.close_quarter_attack` | SurvivalReflexRuntime | – | – | – | yes |  |
| `reflex.combat_focus` | SurvivalReflexRuntime | – | – | – | yes |  |
| `reflex.combat_progress` | SurvivalReflexRuntime | – | – | – | yes |  |
| `reflex.combat_reposition` | SurvivalReflexRuntime | – | – | – | yes |  |
| `reflex.food_eat_failed` | SurvivalReflexRuntime | – | – | – | yes |  |
| `reflex.food_eat_interrupted` | SurvivalReflexRuntime | – | – | – | yes |  |
| `reflex.food_eat_started` | SurvivalReflexRuntime | – | – | – | yes |  |
| `reflex.food_retreat_failed` | SurvivalReflexRuntime | – | – | – | yes | W3 |
| `reflex.food_unavailable` | SurvivalReflexRuntime | – | – | – | yes | W3 |
| `reflex.hold_released` | SurvivalReflexRuntime | yes | – | yes | yes |  |
| `reflex.movement_recovery` | SurvivalReflexRuntime | – | – | – | yes |  |
| `reflex.policy_changed` | SurvivalReflexRuntime | – | – | – | yes |  |
| `reflex.resolved` | SurvivalReflexRuntime | yes | SYSTEM | yes | yes | W2 |
| `reflex.shield_lowered` | SurvivalReflexRuntime | – | – | – | yes |  |
| `reflex.shield_raised` | SurvivalReflexRuntime | – | – | – | yes |  |
| `reflex.started` | SurvivalReflexRuntime | yes | – | yes | yes | W2 |
| `reflex.task_resumed` | EmbodiedAgentRuntime | – | – | – | yes |  |
| `reflex.threat_detected` | SurvivalReflexRuntime | yes | – | yes | yes | W2 |
| `session.connection_lost` | SessionRuntime | yes | – | – | yes |  |
| `session.lan_open_failed` | EmbodiedAgentRuntime | – | – | – | yes |  |
| `session.lan_opened` | SessionRuntime | yes | – | – | yes |  |
| `session.world_loaded` | SessionRuntime | yes | – | – | yes |  |
| `session.world_unloaded` | SessionRuntime | yes | – | – | yes |  |
| `smelting.output_ready` | EmbodiedAgentRuntime | yes | SYSTEM | yes | yes |  |
| `social.item_offered` | EmbodiedAgentRuntime | yes | SYSTEM | – | – |  |
| `social.local_controller_spoke` | EmbodiedAgentRuntime | – | CHAT | yes | – |  |
| `social.player_addressed_agent` | ChatIngestService, EmbodiedAgentRuntime | – | CHAT | yes | – |  |
| `social.player_joined_game` | EmbodiedAgentRuntime | yes | – | – | – |  |
| `social.player_joined_nearby` | EmbodiedAgentRuntime, NearbyPlayerTracker | yes | – | – | – |  |
| `social.player_left_game` | EmbodiedAgentRuntime | yes | – | – | – |  |
| `social.player_left_nearby` | EmbodiedAgentRuntime, NearbyPlayerTracker | yes | – | – | – |  |
| `social.player_spoke` | ChatIngestService, EmbodiedAgentRuntime | – | CHAT | – | – |  |
| `social.system_message` | ChatIngestService, EmbodiedAgentRuntime | – | SYSTEM | – | – |  |
| `task.blocked` | EmbodiedAgentRuntime | yes | SYSTEM | yes | yes | W2 |
| `task.cancelled` | EmbodiedAgentRuntime | – | – | – | yes | W2 |
| `task.completed` | EmbodiedAgentRuntime | – | – | – | yes | W2 |
| `task.failed` | EmbodiedAgentRuntime | – | – | – | yes | W2 |
| `task.mining_opportunity` | EmbodiedAgentRuntime | yes | – | yes | yes |  |
| `task.notice` | EmbodiedAgentRuntime | – | – | – | yes | W2/W3 |
| `task.paused_by_reflex` | EmbodiedAgentRuntime | – | – | – | yes | W2 |
| `task.paused_by_session_gate` | EmbodiedAgentRuntime | – | – | – | yes | W2 |
| `task.started` | EmbodiedAgentRuntime | – | – | – | yes | W2 |
| `task.submitted` | EmbodiedAgentRuntime | – | – | – | yes | W2 |
| `work.changed` | EmbodiedAgentRuntime | – | – | – | yes | W2 |
| `work.travel_restriction_violated` | EmbodiedAgentRuntime | – | – | – | yes | W2 |
| `interaction.*` | InteractionLogbookRecorder | – | – | – | yes |  |
| `policy.continuation.*` | DialogueRuntime | – | – | – | yes |  |

## Appendix B: proposed defaults (to be confirmed in Phase 0)

| Urgency | Delivery | Types |
|---|---|---|
| DIRECT | IMMEDIATE (may supersede) | addressed chat, operator chat, evaluation chat |
| CRITICAL | IMMEDIATE, later PREEMPT | `reflex.started`, `reflex.resolved` with a hold, `reflex.actuator_failed`, `player.died`, `player.respawned` |
| HIGH | IMMEDIATE (merging a burst in the same tick) | `work.changed` terminal/paused/check-output, `task.blocked`, `task.notice` (stalled, slow mining), `work.travel_restriction_violated`, `action_graph.goal_terminal` (failed), `action_graph.goal_suspended`, `smelting.output_ready`, `food.unavailable` |
| NORMAL | DEBOUNCE | `social.item_offered`, `player.physical`, `perception.block_noticed`, `perception.entity_noticed`, `perception.item_noticed`, `social.player_joined_nearby` (if proactive mode) |
| LOW | DEBOUNCE, often inhibited to NONE | ambient chat (proactive mode), system messages, pickups, crafts, `combat.damage_taken` outside a reflex |
| SELF | idle hook only | goal continuation, idle think, delegation continuation, safety-hold reminder |
| – | NONE (evidence only) | everything else visible to the planner, such as `lighting.*`, `reflex.combat_*`, `food.eat_*`, `session.*`, `interaction.*` |
