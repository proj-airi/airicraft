# Planner Perception, Event Bus and Wake Scheduling Design

Status: **proposed** (2026-09-26). Nothing here is implemented. Section 9 lists
the decisions that need an answer before Phase 1 starts. When they are settled,
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
 │ eventBuffer (raw,512) │──► EventRoutingProfile table (46 of ~88 types) ──► EventPolicyState (planner rules)
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

1. **Too many wake paths, too many gates (W1–W8, G1–G11).** No single place
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
   `Map<String,Object>`, with about 88 static ids and two dynamic families
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

### 2.6 Suspected defects (confirm or refute in Phase 0)

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
| Salience via **temporal detectors**: threshold within a sliding or tumbling window, grouped by entity or source, emitting `signal:*` with a description template and confidence | `perception/rules/engine.ts`, `temporal-detector.ts`, `rules/*.yaml` | **Adopt the detector, not the YAML DSL** (Java-first, config-tunable thresholds). |
| Leaky bucket for attention | `services/minecraft/.../leaky-bucket.ts` (history, `a3a29ae2`) | **Adopt** as a global autonomous-wake budget. |
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

### 4.3 Overview

```
 ┌─────────────── Perception (agent.perception) ───────────────┐   ┌─ Execution feedback ─┐  ┌─ Agent-internal ─┐
 │ Sensors (tick-sampled, budgeted):  physical, damage,        │   │ work projection,     │  │ planner, policy, │
 │   item-offer, presence, environment, notable-block,         │   │ tasks/graphs/process,│  │ reflex decisions,│
 │   entity-awareness, inventory-delta                         │   │ watchdog, mining     │  │ session          │
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
                   │ AttentionPolicy (layered rules)          │───────────────►│ AttentionDecisionLog    │
                   │  (event, AttentionState) → WakeDecision  │                │ (bounded; dashboard/CLI)│
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
- Salience utilities are small pure classes with unit tests:
  - `Hysteresis`: enter and exit thresholds, as in Cortico's proximity
    enter/exit ranges.
  - `NoticedMemory`: a bounded seen-set per world and dimension, with TTL.
  - `WindowedDetector`: AIRI's sliding or tumbling threshold detector, keyed
    by entity or source.
  - `NoticeBudget`: a per-category cooldown plus an hourly cap, as in
    Cortico's goal-staleness notices.
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

The rule layers are ordered, and the first decisive rule wins:

1. **System gates.** Planner disabled, unconfigured or degraded, external
   driver, or world not loaded → `NONE`. Reset commands are handled before
   policy, as today.
2. **Protected.** `DIRECT` urgency (addressed chat, operator, evaluation chat)
   and `CRITICAL` safety handoffs → wake. The remaining soft gates are
   skipped.
3. **Evaluation suppression.** The current list of autonomous types → `NONE`.
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
  - A leaky bucket limits autonomous wakes. `NORMAL` and `LOW` wakes degrade
    to `NONE` while the bucket is above its trigger level, and each such
    decision is recorded.
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
  6 scheduler.dispatch()     one WakeBatch at most; attention decisions were made at publish time
```

Chat and damage callbacks still arrive between ticks. They publish
immediately, and their wake is dispatched at the end of the next tick, adding
at most 50 ms of latency. Ordering is then explicit (problem 6), and
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

## 5. Salience perception: "walked past a diamond"

`NotableBlockSensor`:

- **Honest noticing (no X-ray).** A candidate block must have at least one
  face exposed to air or a transparent block. A budgeted raycast from the
  eyes must reach that face within `radius`. The percept records its evidence
  (`line_of_sight_to_exposed_face`). Fully enclosed blocks are never reported.
- **Incremental scan.** Each tick the sensor checks at most K positions from a
  shell around the player that moves with them, and runs at most R raycasts
  on candidates. It is profiled with Arthas `trace`.
- **Memory and clustering.** `NoticedMemory` is keyed by position, world and
  dimension (bounded LRU, TTL). Adjacent same-type blocks cluster into one
  percept (a vein) with a count.
- **Salient set.** Configurable in `agent.yml` under `perception.blocks`.
  Proposed defaults: diamond, emerald and ancient-debris ores, spawners, and
  (decision O6) chests and portals. A later hook could extend it from the
  character card's interests.
- **Event.** `perception.block_noticed {blockId, position, count, distance,
  direction, exposedFaces, evidence, dimension}`. Family `PERCEPT`,
  visibility `PLANNER`, urgency `NORMAL`, delivery `DEBOUNCE`.
- **Attention.** An active mining job for that block id owns it (`NONE`), and
  so does an active reflex (`NONE`). When idle, the event wakes after
  debounce. While other work runs, decision O5 applies. The recommendation is
  to wake, debounced and within the autonomous-wake budget, so the model can
  choose to divert (Cortico's minimal-intervention principle).

Example flow: the player walks along a ravine, and three `diamond_ore` blocks
with an exposed face come into line of sight 7 blocks away. The sensor
publishes one clustered percept. Policy layer 8 (type default) chooses
`DEBOUNCE`. Ten quiet ticks later the scheduler releases a batch with
`wake: [{seqNo, type: perception.block_noticed}]`. The controller sees the
fact in `observe.events` and decides to mine, `remember_place`, or ignore it.
No prose tells it what to do.

Other sensors, in order of value:

- `EnvironmentSensor`: dusk and dawn, weather, dimension or biome change, and
  darkness at the feet (Cortico's `isDark`, a connected-light mode). It emits
  on transitions only.
- `EntityAwarenessSensor`: notable non-hostile entities (players approaching,
  villagers, food animals) with enter/exit hysteresis and seen/heard
  modality. Hostiles stay in the reflex's threat memory. The sensor reads that
  memory and does not duplicate it.
- `InventoryDeltaSensor`: aggregated deltas with resulting totals, such as
  `oak_log +4 (13)`. They become the canonical inventory fact. Pickup and craft
  events remain as attribution.
- Social gestures (AIRI's punch and teabag windows) are optional. They are
  useful for companion behaviour, not survival.

## 6. Migration plan

Each phase ends with a green `./gradlew build`, the characterization suite,
and an evaluation batch (`scripts/run-evaluation-scenarios`, all scenarios).
Phases 3 and 4 change what the model sees and also require live playtests.
Phases ship as several small PRs to limit conflicts in `EmbodiedAgentRuntime`.

### Phase 0: characterize and decide (no production changes)

- [ ] Generate the event inventory (Appendix A) from code and assert it in a
  test that enumerates `createEventRoutingProfiles()`.
- [ ] Build a wake-scenario harness on `EmbodiedAgentRuntime.createForTests`
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
- [ ] Build **wake replay**: feed an exported flight-recorder JSONL (the
  dashboard export is replayable) through the old and new attention logic
  offline and diff the decisions. Use it in Phases 2 and 3.
- [ ] Confirm or refute D1–D8 with focused tests and write the outcome into
  this document.
- [ ] Record baseline metrics (4.10) from one evaluation batch and one live
  playtest.
- [ ] Resolve section 9, write ADR-0003, and add the vocabulary to `CONTEXT.md`.

Exit: characterization suite green on `dev`; decisions recorded.

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

- [ ] Add `AttentionState`, the layered `AttentionPolicy` reproducing G1–G7
  and G9, and `AttentionDecisionLog`.
- [ ] Add `WakeScheduler` with the pending set, satisfaction, supersession and
  idle hook. Retire the W2/W3 deque, `continuePlannerGoal` scheduling,
  `IdleIdeaScheduler` timing (it becomes the idle-think generator), the
  delegation start wake, and evaluation seed wakes as separate paths.
- [ ] Move trigger prose out of `EmbodiedAgentRuntime` into a presenter keyed
  by event type, producing the same strings in this phase. Move the orchestrator's
  coalesce window into the scheduler. If tick timing changes delivery, record
  the difference.
- [ ] Add the dashboard panel for wake decisions.

Exit: golden prompts unchanged (except documented timing); wake replay of at
least two recorded playtests shows no unexplained decision diffs; the
evaluation pass rate and planner-turn counts stay within tolerance (set in
Phase 0). `EmbodiedAgentRuntime` loses the routing table, the trigger
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

- [ ] Add `agent.perception` with the Sensor API, the registry and the
  salience utilities, with unit tests.
- [ ] Migrate the existing observers (4.5 table).
- [ ] Add `NotableBlockSensor`, `EnvironmentSensor`, `EntityAwarenessSensor` and
  `InventoryDeltaSensor`, with configuration in `agent.yml` (`perception:`),
  reloadable through `airicraft reload`.
- [ ] Add a new evaluation scenario, `notice-diamond`: a frozen world with an
  exposed diamond vein beside a path. Check that the planner is woken by
  `perception.block_noticed`, and review what the controller chooses to do.
- [ ] Budget check: perception tick cost measured with Arthas `trace` on a
  loaded world, within the budget set in Phase 0.

### Phase 5: priority refinements

- [ ] `PREEMPT` for safety-epoch changes: cancel an unexternalized in-flight
  turn instead of paying for it and then rejecting it as stale.
- [ ] Supersede budget, autonomous-wake leaky bucket, and per-category notice
  budgets, each tuned from metrics.
- [ ] Update `AGENTS.md` (behavior notes, key files), the docs index, and
  ADR-0003 (final).

## 7. Verification strategy

| Level | What |
|---|---|
| Unit | Decision tables for `AttentionPolicy` (parameterized, one row per rule); `WakeScheduler` timing with a fake tick source; salience utilities; sensors with synthetic samples (the `PhysicalEventObserver` tests are the model) |
| Characterization | Golden wake scenarios (Phase 0), unchanged through Phases 1–2 |
| Replay | Recorded playtest JSONL through the old and new policy, diffing decisions |
| Integration | `EmbodiedAgentRuntimeTest`, `DialogueRuntimeTest`, `PlannerOrchestratorTest` |
| Evaluation | All `scenarios/*` through the batch harness, before and after each phase, comparing pass/fail, planner turns and time used |
| Live | Automatic and hosted playtests for Phases 3–5; export the incident before rebuilding (`docs/live-playtest-recording.md`) |

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

## 9. Open decisions

| # | Decision | Recommendation |
|---|---|---|
| O1 | Should the refactor preserve behaviour first (Phases 1–2), with model-visible changes isolated in Phase 3? | **Yes.** It keeps regressions attributable. |
| O2 | Priority model: an AIRI-style numeric priority queue, or Cortico-style delivery modes plus an urgency enum decided by a layered policy? | **Urgency plus delivery, decided by the policy.** Batches are delivered whole, so ordering within a queue matters less than when to wake and whether to interrupt. |
| O3 | Preemption scope | Direct guidance supersedes, as today. Add `PREEMPT` for safety-epoch changes. Never preempt after a side-effect tool has executed. |
| O4 | Clock for debounce and idle timing | **Agent ticks.** LLM retry and backoff stay on wall clock. |
| O5 | Should salient perceptions wake the planner while other work runs? | **Yes, debounced and inside the autonomous-wake budget.** When the owning executor is doing that exact work, `NONE`. |
| O6 | Honesty rule and default salient block set | Require an exposed face plus line of sight within 12 blocks. Defaults: diamond, emerald and ancient-debris ores, and spawners. Chests and portals are the team's call. |
| O7 | How rules are authored: Java, or AIRI-style YAML | **Java rules** with thresholds set in `agent.yml`. Revisit YAML only if non-developers need to author rules. |
| O8 | `update_event_policy` semantics once E2 retires: should `ignore` also hide events from `observe`? | **No, keep evidence visible.** `ignore` only stops wakes. Say so in the tool description without changing the schema. |
| O9 | Event log bound **[ADR-0002]** | Keep 512 unless the Phase 0 gap measurements show loss within typical decision intervals. |
| O10 | Where the scheduler lives | A new package `agent.attention`, owned by `EmbodiedAgentRuntime`, delivering to `DialogueRuntime`. |

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
- **Rendering differences between the Codex and OpenAI backends.** Both use
  the decision context today. Include one Codex-driver smoke test in the
  Phase 3 checks.

## Appendix A: event inventory at `a1c05d8`

Key: S = semanticEligible, T = trigger type (from `createEventRoutingProfiles`),
B = policy bypass, O = visible in `observe` (G10 prefix list), W = extra W2/W3
wake path.

| Type(s) | Producer | S | T | B | O | W |
|---|---|---|---|---|---|---|
| `social.player_spoke` | `ChatIngestService` | – | CHAT | – | – | |
| `social.player_addressed_agent`, `social.local_controller_spoke` | `ChatIngestService`, EAR | – | CHAT | ✓ | – | |
| `social.system_message` | `ChatIngestService` | – | SYSTEM | – | – | |
| `social.item_offered` | `ItemOfferObserver` via EAR | ✓ | SYSTEM | – | – | |
| `social.player_{joined,left}_{game,nearby}` | EAR, `NearbyPlayerTracker` | ✓ | – | – | – | |
| `pickup.item_picked_up` | mixin via EAR | ✓ | PICKUP | – | ✓ | |
| `crafting.item_crafted` | mixin via EAR | ✓ | CRAFT | – | ✓ | |
| `smelting.output_ready` | smelting poll | ✓ | SYSTEM | ✓ | ✓ | |
| `combat.damage_taken` | `LocalDamageTracker` via EAR | ✓ | DAMAGE | – | ✓ | |
| `player.physical` | `PhysicalEventObserver` | ✓ | SYSTEM | ✓ | ✓ | |
| `reflex.{threat_detected,started,actuator_failed}` | `SurvivalReflexRuntime` | ✓ | – | ✓ | ✓ | W2 |
| `reflex.resolved` | `SurvivalReflexRuntime` | ✓ | SYSTEM | ✓ | ✓ | W2 |
| `reflex.{action_changed,hold_released}` | `SurvivalReflexRuntime` | ✓ | – | ✓ | ✓ | |
| `reflex.food_{retreat_failed,unavailable}` | `SurvivalReflexRuntime` | – | – | – | ✓ | W3 |
| other `reflex.*` (12 types) | `SurvivalReflexRuntime`, EAR | – | – | – | ✓ | |
| `lighting.torch_placed` | `LightingRuntime` | ✓ | – | ✓ | ✓ | |
| `player.{died,actions_cancelled,action_rejected,respawn_requested,respawn_request_failed,respawned}` | `SessionRuntime`, EAR | ✓ | – | ✓ | ✓ | |
| `player.death_place_{saved,save_failed}` | EAR | – | – | – | ✓ | |
| `session.{world_loaded,world_unloaded,connection_lost,lan_opened}` | `SessionRuntime` | ✓ | – | – | ✓ | |
| `session.lan_open_failed` | EAR | – | – | – | ✓ | |
| `follow.{target_acquired,target_lost,stuck}` | `FollowCapability`, EAR | ✓ | – | – | – | |
| `planner.{goal_set,goal_cleared,degraded_entered,degraded_cleared}` | EAR, `DialogueCore` | ✓ | – | – | – | |
| `planner.{reset_requested,stale_response_rejected}` | `DialogueCore`, EAR | ✓ | – | ✓ | – | |
| `planner.{response_applied,internal_task_update_superseded,unknown_intent,degraded_blocked}` | EAR, `DialogueRuntime`, `DialogueCore` | – | – | – | – | |
| `task.blocked` | EAR task transitions | ✓ | SYSTEM | ✓ | ✓ | W2 |
| `task.mining_opportunity` | `MiningOpportunityJournal` | ✓ | – | ✓ | ✓ | |
| `task.{submitted,started,paused_by_session_gate,paused_by_reflex,completed,failed,cancelled}` | EAR | – | – | – | ✓ | W2 |
| `task.notice` | EAR monitors, `DialogueRuntime` | – | – | – | ✓ | W2/W3 |
| `work.changed`, `work.travel_restriction_violated` | EAR | – | – | – | ✓ | W2 |
| `food.{eaten,eat_started,eat_failed}` | EAR | – | – | – | ✓ | |
| `food.unavailable` | EAR | – | – | – | ✓ | W3 |
| `action_graph.goal_{suspended,terminal}` | `ActionGraphCoordinator` | ✓ | SYSTEM | ✓ | – | |
| `action_graph.goal_{started,runnable,resumed,cancelled,admission}` | `ActionGraphCoordinator`, EAR | – | – | – | – | |
| `mission.submitted` | EAR | – | – | – | – | |
| `objective.changed`, `policy.travel_changed` | EAR | – | – | – | ✓ | |
| `policy.{event_intervened,rule_rejected}` | `AgentEventPipeline`, EAR | raw only | – | – | ✓ | |
| `policy.continuation.<state>` (dormant) | `DialogueRuntime` | – | – | – | ✓ | |
| `interaction.<action>`, `interaction.history_gap` | interaction logbook | – | – | – | ✓ | |

## Appendix B: proposed defaults (to be confirmed in Phase 0)

| Urgency | Delivery | Types |
|---|---|---|
| DIRECT | IMMEDIATE (may supersede) | addressed chat, operator chat, evaluation chat |
| CRITICAL | IMMEDIATE, later PREEMPT | `reflex.started`, `reflex.resolved` with a hold, `reflex.actuator_failed`, `player.died`, `player.respawned` |
| HIGH | IMMEDIATE (merging a burst in the same tick) | `work.changed` terminal/paused/check-output, `task.blocked`, `task.notice` (stalled, slow mining), `work.travel_restriction_violated`, `action_graph.goal_terminal` (failed), `action_graph.goal_suspended`, `smelting.output_ready`, `food.unavailable` |
| NORMAL | DEBOUNCE | `social.item_offered`, `player.physical`, `perception.block_noticed`, `perception.entity_*`, `social.player_joined_nearby` (if proactive mode) |
| LOW | DEBOUNCE, often inhibited to NONE | ambient chat (proactive mode), system messages, pickups, crafts, `combat.damage_taken` outside a reflex |
| SELF | idle hook only | goal continuation, idle think, delegation continuation, safety-hold reminder |
| – | NONE (evidence only) | everything else visible to the planner, such as `lighting.*`, `reflex.combat_*`, `food.eat_*`, `session.*`, `interaction.*` |
