# Planner Event System Phase 4 Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the companion honest noticing. It sees an exposed diamond vein
beside the path, a player or a named animal coming into view, or an item worth
picking up, and the planner learns it as an ordinary event it can act on or
ignore. The Java side decides what the player could actually perceive, with no
X-ray. A GraalJS `salience` module decides what matters. The existing
observers move behind one Sensor API.

**Spec:** [`specs/2026-09-26-planner-perception-and-wake-design.md`](../specs/2026-09-26-planner-perception-and-wake-design.md),
sections 4.5, 4.12, 5 and 6 (Phase 4). **Previous phase:**
[`plans/2026-09-28-planner-event-system-phase-3.md`](2026-09-28-planner-event-system-phase-3.md).

## Where Phase 4 starts

What Phases 1–3 already provide, and what is missing:

| Needed | State on `dev` (d173a64c) |
|---|---|
| One lifecycle dispatch for resets (spec problem 5) | **Done** in Phase 2: `LifecycleDispatcher` with named participants (`damage`, `physical`, `item`, `slow`, `food`, `nearby`). The sensor registry registers into it; it does not replace it. |
| A rule engine that can host a second module | **Partly.** `RuleEngine`, `RuleModule` and `kernel.js` exist. The kernel's comment names salience, but a step returns only `decisions`. Warm-up inputs are attention-shaped, and `RuleModule` knows only `bundledAttention()`. |
| `lib.cluster`, `cooldown`, `hourlyCap`, windows | **Done** in `lib.js`. |
| `DEBOUNCE` delivery | **Declared only.** `Delivery.DEBOUNCE` exists, but nothing decides it and `WakeScheduler` does not hold it. |
| Ownership facts for "the executor is doing exactly this" | **Partly.** `AttentionState` has `activeJobType` but not the job's target ids. |
| Sensor-shaped observers | `ItemOfferObserver` and `PhysicalEventObserver` are already pure cores fed by an adapter in `EmbodiedAgentRuntime`. `LocalDamageTracker` and `NearbyPlayerTracker` are callback- and poll-driven. `ChatIngestService` is callback-only. |
| A deterministic in-world fixture | **Exists** for navigation: `NavigationCourseFixtureService` builds courses at Y=200 through the integrated server, and `scripts/navigation-baseline` drives them without a model. |
| A negative evaluator check | **Missing.** There is `event_contains` but nothing like `event_absent`. |
| A perception tick budget | **Missing.** The spec's Phase 4 item says "within the budget set in Phase 0", but Phase 0 only measured the rule engine (steady-state step p99 1.462 ms for 20 events and 50 candidates). This plan sets the budget (P9). |

## Decisions

These are proposed on 2026-09-29, for the user to confirm before
implementation. Each has a recommendation.

| # | Question | Decision | Why |
|---|---|---|---|
| P1 | One PR or several? | **One PR, six slices, each with its own commits.** 4a and 4b must leave every golden unchanged. 4c–4e may change goldens only in new scenarios. | Same shape as Phase 3 (R1). The user asked for one PR per phase. |
| P2 | Which new sensors? | **`NotableBlockSensor`, `DroppedItemSensor`, `EntityNoticeSensor` (seen only), `EnvironmentSensor`.** Defer `InventoryDeltaSensor` and the *heard* modality. | `current.inventory` already gives totals on every observe, and pickups and crafts are events, so an inventory-delta event would bring back the duplication Phase 3 removed (D2). *Heard* needs a sound model; a distance-only guess through walls would break the no-X-ray rule. The client does receive sound packets, so a later phase can add honest hearing from them. |
| P3 | How does Java avoid flooding salience with every visible block? | **The salience module declares its interests on load:** `interests: {blocks: [ids or #tags]}`. Java only raycasts blocks in that set. Entities and items are all candidates, capped per tick. | Keeps honesty in Java and taste in JS (spec 4.5) without scanning every stone face. An override that wants copper ore adds it to `interests`. |
| P4 | Salience contract | **`step(input, state, lib)` returns `{percepts: [{type, payload, candidateIds}], drops: [{candidateId, reason}], state}`.** The input carries `tick`, `seed`, `candidates` (at most 50 per step, the rest carried to the next tick), and a `context` holding the goal objective and constraints, `wanted` item ids (active job and graph targets, plus item ids named in the goal), inventory counts, and the active job type and targets. The kernel passes `percepts` and `drops` through, and `RuleStepResult` gains both, empty for attention. | One kernel, two hook points (spec 4.12). `wanted` is how contextual garbage works: cobblestone stops being garbage while a furnace job wants it. |
| P5 | Salience failure | **Candidates are not marked noticed and are offered again next tick, for at most 20 ticks, then dropped.** `rules.step_failed` is published, and after three consecutive failures an override reverts to the bundled module, as for attention. There is no Java mirror of the salience rules. | Noticing is not safety-critical, so O13's "never leave the agent unable to wake" is already met by the attention constitution. A Java mirror would double the code for no safety gain. |
| P6 | Where do notice budgets live? | **In the salience module:** a per-category `cooldown` and an `hourlyCap` from `lib.js`, so over-budget candidates are dropped with a reason. Attention stays stateless for `perception.*`. | The spec puts `NoticeBudget` in the JS library. The attention module must decide exactly like the Java `ReferenceAttentionPolicy`, and stateless percept rules keep that parity cheap. The global autonomous-wake leaky bucket stays in Phase 5. |
| P7 | Attention for percepts | **Owned → `NONE`; otherwise `DEBOUNCE` with `LOW` urgency.** "Owned" means a mining job whose targets include the block id, a collect job for that item, or reflex-tracked hostiles. Salience already skips those, and attention repeats the check. Add `activeJobTargets` to `AttentionState`, in both the JS and the Java reference. `perception.environment_changed` is `NONE` by default (evidence only), except dusk while idle. | Matches O5: salient percepts may wake while other work runs, but debounced. Parity stays exact. |
| P8 | `DEBOUNCE` semantics | **Tick-based hold in `WakeScheduler`.** A debounced event wake is held until no new `DEBOUNCE` wake has arrived for `quietTicks` (default 10), or until `maxHoldTicks` (default 100). The held wakes are then released together, so `observe.wake` lists them all. They also ride along with any earlier `IMMEDIATE` release. They are dropped if the planner's cursor passes them first (satisfied) or on a lifecycle boundary. Protected urgencies are never debounced: the clamp raises them to `IMMEDIATE`. Audit kinds `debounced` and `released`, on path W1. | O4 (agent ticks), spec section 5's example ("ten quiet ticks later"). Existing types never choose `DEBOUNCE`, so current goldens do not move. |
| P9 | Perception budget | **Measured over a 5-minute walk on a loaded world:** all Java sensor sampling p99 ≤ 0.5 ms per tick. The salience step runs only on ticks with new candidates, at p99 ≤ 2 ms. Mean total ≤ 0.3 ms per tick. Defaults in `agent.yml` `perception:` are radius 12, K = 256 positions per tick, R = 16 raycasts per tick and 50 candidates per step, reloadable. | Sets the budget the spec assumed Phase 0 set, within one tick's 50 ms and within what the rule-engine spike measured. |
| P10 | `NoticedMemory` bounds | **LRU of 4,096 keys per world and dimension.** TTLs: blocks 12,000 ticks (10 minutes), entities 6,000 ticks, items for the entity's lifetime. Entity enter/exit hysteresis is range 16 to enter and 20 to exit, with line of sight required to enter. | Something forgotten long ago can be noticed again (spec section 5), without repeat wakes from walking back and forth. |
| P11 | Offer deduplication | **`DroppedItemSensor` shares the item-entity read with `ItemOfferSensor`, and marks a candidate `offered: true` when that uuid produced `social.item_offered`.** The salience module drops it with reason `offer_percept`. | One physical drop never produces both an offer and a noticed item (spec section 5). |
| P12 | Verification without a model | **Notice courses in the evaluator plus `scripts/perception-baseline`:** a model-free `navigate_to` walk past fixtures, checking events (exposed vein noticed, enclosed vein never noticed, valuable drop noticed, garbage dropped with a reason). Add the `event_absent` check type. The model scenarios `notice-diamond` and `notice-drop` are saved from the same fixtures as world archives; running them needs a model and is the user's call. | The same approach as the navigation baseline: deterministic and runnable here. The spec's scenarios still exist for the model run. |
| P13 | Replay | **Recorded runs write `salience-steps.jsonl`** (input candidates, context and state, capped as in the step). `./gradlew attentionReplay` replays them through a module and diffs percepts and drops. | Spec 7 lists replay for rules. Salience needs its own recording, because candidates are not events. |
| P14 | What becomes planner-visible | **New catalog types** `perception.block_noticed`, `perception.entity_noticed`, `perception.entity_lost`, `perception.item_noticed` and `perception.environment_changed`: family `PERCEPT`, visibility `PLANNER`, trigger-eligible. `update_event_policy` can mute them by `eventType`, and by `itemId` for items; its schema is frozen. | Spec 4.4 and 8: new percepts get a new namespace, and event ids are frozen. |

## Ground rules

1. **4a and 4b change no behaviour:** every golden and `wakeAudit` stays
   byte-identical, and the D-probes stay as they are.
2. **The Java side never reads the world for salience.** Candidates are
   plain records; the rules never see live objects (no X-ray by
   construction).
3. **Every sensor has a pure core and a thin Minecraft adapter.** Cores are
   unit-tested with synthetic samples, as `PhysicalEventObserver` is today.
4. **Attention parity holds.** Any branch added to `attention/default.js` is
   mirrored in `ReferenceAttentionPolicy`, and the parity test covers the new
   types.
5. **Goldens for percepts come from injected candidates.** The harness has no
   client, so a test sensor injects candidate records; real sensors are
   covered by unit tests and the notice courses.

## Slice 4a: Sensor API (no behaviour change)

### Task 1: package `agent.perception`

- [ ] `Sensor` (`id()`, `sample(SensorContext, PerceptSink)`,
  `onBoundary(LifecycleBoundary, tick)`), `SensorRegistry` (order, per-sensor
  nanos counters, registration into `LifecycleDispatcher`), `SensorContext`
  (tick, client view, budgets) and `PerceptSink` (publishes through the bus,
  or hands candidates to salience).
- [ ] `Hysteresis` (enter and exit ranges, line of sight to enter) and
  `NoticedMemory` (per world and dimension, LRU, TTL), with unit tests
  covering TTL expiry, LRU eviction, dimension switch and boundary reset.
- [ ] `AgentConfig.PerceptionConfig` and the `perception:` section of
  `agent.yml` (P9 defaults), read strictly on reload. Add them to
  `agent.yml.example`.

## Slice 4b: migrate the existing observers (no behaviour change)

### Task 2: sensors around today's observers

- [ ] `PhysicalSensor`, `ItemOfferSensor`, `DamageSensor` (callback ingress
  plus tick pruning) and `SocialPresenceSensor` wrap the existing cores, with
  the same call order inside `tickClient`.
- [ ] `ChatIngress` wraps `ChatIngestService` as a callback ingress with
  boundary resets. `SlowMiningObserver` and `WorkProgressWatchdog` stay in
  the work package as monitors (spec 4.5 table).
- [ ] The lifecycle participants keep their ids, and a test pins
  `LifecycleDispatcher.table()`.
- [ ] Full build; goldens and `wakeAudit` byte-identical; a 50× repeat of
  the golden classes. Commit:
  `refactor(perception): run existing observers as sensors`.

## Slice 4c: salience hook and percept types

### Task 3: engine support for a second module

- [ ] `kernel.js` passes `percepts` and `drops` through, and `load` returns
  the module's `interests` (P3). `RuleStepResult` gains `percepts` and
  `drops`.
- [ ] `RuleModule.bundledSalience()`, a salience warm-up input, and
  `SalienceRuleSource` (override `config/airicraft/rules/salience.js`, strict
  validation on reload, startup reverts to the bundled module), following
  `AttentionRuleSource`.
- [ ] `SaliencePolicy`: host-owned state, the 50-candidate cap with
  carry-over, the P5 failure path and revert, and decision counters for
  `agent debug state` and the dashboard.

### Task 4: bundled `salience/default.js`

- [ ] Interests: diamond, emerald and ancient-debris ores (with their
  deepslate variants), spawners, chests and portals.
- [ ] Block clustering into one vein percept with a count (`lib.cluster`).
- [ ] Entity categories: players, villagers and traders, named or tamed
  animals, and food or breeding animals only when `wanted` or the goal names
  them. Skip reflex-tracked hostiles.
- [ ] The contextual garbage list (spec section 5), overridden by `wanted`.
- [ ] Offer deduplication (P11) and notice budgets (P6).
- [ ] Rule tests in the real sandbox against JSON fixtures, with state
  threaded through steps: clustering, garbage and `wanted`, budgets,
  determinism, and the failure paths.

### Task 5: catalog and attention

- [ ] Add the P14 types to `EventCatalog` and `event-inventory.json`.
- [ ] `activeJobTargets` in `AttentionState` and the attention input. P7
  branches in `attention/default.js` and `ReferenceAttentionPolicy`, with
  parity tests.

## Slice 4d: `DEBOUNCE`

### Task 6: scheduler hold

- [ ] Implement P8 in `WakeScheduler`: the hold, quiet and maximum release,
  piggy-backing on an immediate release, satisfaction, boundary clearing,
  the clamp for protected urgencies, and the audit kinds. Unit tests with a
  fake tick source.
- [ ] New golden scenarios with injected candidates:
  - `percept_block_idle`: one vein percept is released after 10 quiet
    ticks, and `observe.wake` names it;
  - `percept_owned_by_mining`: `NONE` while a mining job targets that ore;
  - `percept_batch`: three percepts in 30 ticks give one release listing
    three wakes;
  - `percept_offer_dedup`: an offer and a noticed item from one drop give a
    single offer wake;
  - `percept_protected_not_debounced`: a chat during the hold is not
    delayed.

  Existing goldens unchanged. Commit:
  `feat(attention): debounce percept wakes`.

## Slice 4e: the new sensors

### Task 7: `NotableBlockSensor`

- [ ] Incremental shell scan (at most K positions per tick) of the interest
  set, an exposed-face test, and at most R raycasts per tick from the eyes
  to the exposed face. Fully enclosed blocks never become candidates.
- [ ] A pure core over a block-lookup function, with tests: exposed vs
  enclosed, a glass window counts as transparent, budget respected, memory
  prevents repeats.

### Task 8: `DroppedItemSensor` and `EntityNoticeSensor`

- [ ] Items: first sight with line of sight within radius, keyed by uuid.
  Attribution is `thrown_by_player` from the offer inference,
  `own_mining_drop` when a job owns the position, otherwise `unknown`.
- [ ] Entities: hysteresis enter and exit, `reflexTracked` flag, equipment
  summary; exit candidates only for entities that became percepts.
- [ ] Pure-core tests with synthetic entity lists.

### Task 9: `EnvironmentSensor`

- [ ] Transitions only: dusk and dawn from time of day, rain and thunder
  start and stop, biome at the feet (100-tick hysteresis), and darkness at
  the feet (connected-light mode, with hysteresis). Dimension changes stay
  `session.*` events.

## Slice 4f: verification and docs

### Task 10

- [ ] Notice courses in the evaluator (`NoticeCourses`,
  `NoticeCourseFixtureService`), the `event_absent` check, and
  `scripts/perception-baseline` (P12). Run it here in a disposable world.
- [ ] Budget (P9): per-sensor nanos from the registry counters, and Arthas
  `trace` of `SensorRegistry.sample` on a loaded world during a 5-minute
  walk. Record the numbers in this plan.
- [ ] Salience replay (P13) on a recorded baseline run: zero diff against the
  bundled module.
- [ ] Live smoke with the stub endpoint (as in Phase 3): an exposed vein
  beside the path produces one `perception.block_noticed` wake after the
  debounce, rendered in the observation text.
- [ ] Save `notice-diamond` and `notice-drop` world archives and scenario
  files from the fixtures; the user decides whether to run them with a
  model.
- [ ] Docs: spec Phase 4 checklist, `docs/attention-rules.md` (salience
  module, `interests`, overrides), a `docs/perception.md` (sensors, budgets,
  the honesty rule, the baseline), and the `AGENTS.md` key files and
  behaviour notes.

## Exit criteria

- [ ] Build green. 4a and 4b leave every golden byte-identical, and the
  golden repeat is stable.
- [ ] The notice baseline passes: the exposed vein is noticed, the enclosed
  vein never is, valuable drops are noticed, garbage is dropped with a
  reason, and an offer is a single wake.
- [ ] Perception stays within the P9 budget on a loaded world.
- [ ] No existing wake changes: `wakeAudit` in existing goldens is
  unchanged.
- [ ] Wakes per minute during the baseline walk are recorded as the
  over-waking metric (spec risk), for Phase 5's bucket tuning.

## Risks

- **Over-waking from percepts.** Mitigations: debounce, notice budgets,
  ownership to `NONE`, and the wakes-per-minute metric. The global leaky
  bucket is Phase 5.
- **Tick cost of scanning.** Mitigations: the interest set, K and R caps,
  incremental shells, and measurement (P9).
- **Event log pressure (512 entries).** Percepts are budgeted and clustered.
  The baseline checks `missingEventRange` never appears during the walk.
- **Honesty leaks.** A candidate built from data the player could not see
  would be X-ray. Mitigations: the exposed-face and line-of-sight tests are
  in the pure cores with tests, and the enclosed-vein course is a hard
  negative check.
- **Model behaviour on new events** (diverting to every diamond). This is
  only measurable with a model; the saved scenarios exist for that run.
