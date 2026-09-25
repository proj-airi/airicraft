# In-house Navigation and Unified Control Plane

Status: accepted (2026-09-25), recorded as
[ADR-0003](../../adr/0003-in-house-navigation-and-control-plane.md). Not yet
implemented.

## Goal

Replace Baritone with Airicraft-owned pathfinding and motor code. Route every
physical actuation through one control plane with explicit ownership: movement
input, rotation, hotbar selection, attack and use.

This removes:

- the optional-mod runtime dependency;
- ten mixins that patch Baritone internals;
- the code that compensates for Baritone's asynchronous process and cancellation
  model.

Non-goals: elytra, schematic building, Baritone's mine, explore and farm
processes, and an on-disk chunk cache. System 2 tool contracts change only in
Phase 5.

## Decisions (2026-09-25)

1. **v1 edit moves.** Tunnel, bridge and pillar are part of v1 and of the
   parity gates. Parkour comes after v1.
2. **Long-range travel.** Plan in segments through loaded terrain toward the
   goal and replan as chunks load. Airicraft does not replicate Baritone's
   cached-chunk pathing.
3. **Planner tools.** Keep the `configure_pathfind` and `inspect_pathfind` names.
   Their schema becomes a small Airicraft-owned movement policy.
4. **No fallback period.** Baritone is deleted in the same change that makes the
   in-house backend the default. The parity gates must pass before that change
   merges; rollback means reverting it.

Still open: whether `navigation-core` is a Gradle subproject or a package. The
plan assumes a subproject, because the compiler then enforces purity.

## What Baritone does for Airicraft today

52 client source files and 24 test files mention Baritone. 20 client files import
it directly.

| Use | Where | Notes |
| --- | --- | --- |
| Goal navigation (`GoalBlock`, `GoalXZ`, `GoalNear`) | `LiveBaritoneFacade`, used by 11 executors, the reflex and the dispatcher through `BaritoneFacade` | Primary use. |
| Follow a player | `BaritoneFacade.startFollow` | `BaritoneTaskExecutor.continueFollow` restarts it after every terminal path event. |
| Path execution: break, place, parkour, fall and swim | Baritone movements | `BaritoneSettingsProfile` enables break, place and parkour by default. |
| Movement model used as a library | `MinecraftCombatPositioning`, `CombatTraversal` | Uses `CalculationContext`, `Moves`, `MovementHelper` and `Movement.updateState`, but never lets Baritone execute. |
| Mob-avoidance cost | Settings plus `BaritoneMobAvoidanceMixin` | Radius 16, coefficient 4. |
| Settings as a planner tool | `configure_pathfind`, `inspect_pathfind` | Exposes every non-Java Baritone setting by its Baritone name. |
| Mining (`MineProcess`) | `startMine`, `HybridMining*` | **Unused in production.** `MINE` dispatches to `TargetAcquisitionTaskExecutor`. Only tests construct `HybridMiningTaskExecutor`. |
| Player feet position | `PlaceMemoryToolProvider`, `MinecraftAcquisitionEnvironment` | Trivial to replace. |

Build and runtime: `fabric.mod.json` requires `baritone >=1.15.0`, but the mod jar
does not bundle it. `libs/baritone-unoptimized-fabric-1.15.0.jar` and
`libs/nether-pathfinder-1.4.1.jar` are `modImplementation`. The compat and
evaluator builds list the Baritone jar for runtime launches.

## Seams

1. **Asynchronous ownership release.** A cancel may emit `CANCELED` later, or
   never. `LiveBaritoneFacade` therefore tracks operation generations, expected
   and deferred cancellations, and an acknowledgement counter.
   `BaritoneTaskExecutor` tracks `pendingInternalCancel*`.
   `BaritoneReleaseBarrier` gates the dispatcher, target acquisition and
   underwater escape. The resulting wait states include
   `waiting_for_baritone_release`, `waiting_for_previous_baritone_release`,
   `waiting_for_satisfied_mine_release`, `releasing_baritone` and
   `baritone_release_quarantined`.
2. **String outcomes that don't mean what they say.**
   - `AT_GOAL` can fire before arrival, so the executor waits 10 ticks to see
     where the player ends up (`BaritoneTaskExecutor.java:292-304`).
   - `CALC_FAILED` while already at the goal is reconciled to success (`:494-498`).
   - `CANCELED` may be our own cancel and must be suppressed.
3. **Policy set by mutating global state.**
   - Water-stall recovery rewrites the global `walkOnWaterOnePenalty` (`:331-339`).
   - Lure travel flips six process-wide settings and restores them later
     (`MinecraftLureEntitiesEnvironment.java:109`).
   - The planner's `configure_pathfind` writes the same globals.
   - The 2026-09 playthrough log records tool schemas carrying stale "current"
     values.

   Nothing arbitrates between these writers.
4. **Constraints bolted on through mixins.** Travel bounds and preserved areas
   are enforced in three places inside Baritone: `isPossiblyProtected`, A*
   `Moves.apply`, and `Movement.update`. `attackBlock` also has a veto keyed on
   Baritone's forced left click (`WorldPlacePreservation.java:72`). Door
   handling redirects `Movement.update`. Every one of these mixins is
   `remap=false` against a jar in `libs/`, so a Baritone upgrade breaks them.
5. **Two actuation systems.**
   - Baritone writes input through `InputOverrideHandler` on its own tick hook.
   - Airicraft writes `KeyBinding` state from at least 7 independent
     `MovementController` instances, plus `OwnedKeyPress`,
     `PlacementSneakController` and `PlayerItemUseController`.
   - About 16 classes call `interactionManager` directly.

   Arbitration is "last writer wins", plus a camera mixin that vetoes Baritone
   input while aiming. Symptoms already in code and docs:
   - "Navigation release can clear keys after placement first presses them"
     (`PlacementSneakController.java:26`).
   - Baritone's hotbar housekeeping swapped out food mid-eat (playthrough log,
     2026-09-13). It was patched by `BaritoneInventoryBehaviorMixin` plus a
     "keep slot 0 free" rule in the lure code.
   - Each `MovementController` forces the user's global `autoJump` option on and
     restores its own saved copy. Two interleaved instances can restore the
     wrong value.
6. **Duplicated stall detection.** Both `BaritoneTaskExecutor` and
   `DispatchingWorldTaskExecutor` own a `NavigationStallWatchdog`. Water-stall
   recovery is a third, separate mechanism.

## Target architecture

```
 System 2 tools / jobs / action graph / policies           survival reflex
             │ WorldTaskRequest                                  │
             ▼                                                   ▼
      WorldTaskExecutors ─────► NavigationService ◄────── SurvivalReflexRuntime
             │                  (plan + follow path)
             │ MotorIntent            │ MotorIntent
             ▼                        ▼
  ┌──────────────── ControlPlane: the only writer ─────────────────┐
  │ leases:   REFLEX > FOREGROUND > BACKGROUND                     │
  │ channels: locomotion, look, hotbar, primary (attack), secondary│
  │           (use); one merged ControlFrame per client tick       │
  └────────────────────────────────────────────────────────────────┘
             │ player Input object, rotation (CameraController spring),
             ▼ selected slot, interaction calls
     ClientPlayerEntity / ClientPlayerInteractionManager
```

### `navigation-core`: a pure Java library

Put the core in a Gradle `java-library` subproject with no Minecraft dependency,
nested into the mod jar. `action-plan-advisor` already works this way. The
compiler then enforces purity, and tests stay plain JUnit; the existing tests
never bootstrap Minecraft. The library follows the existing split between a pure
core and a Minecraft adapter (`CombatPositioning` and
`MinecraftCombatPositioning`, `UnderwaterEscapeSearch`).

- **`TerrainView` and `CellInfo`.** A read-only cell lookup. Each cell reports:
  - collision top height, passable and standable;
  - fluid kind, climbable, and openable door, gate or trapdoor with its facing;
  - hazard flags;
  - break ticks with the best carried tool;
  - protected (travel bounds or preserved area) and loaded.

  The Minecraft adapter builds it on the client thread, and it is immutable once
  handed to search. This replaces the `WorldPlacePreservation` arrangement of
  "immutable reads on Baritone's path thread".
- **`MovementPolicy`.** An immutable record passed with each request, with
  defaults from `AgentConfig`. Fields:
  - `allowBreak`, `allowPlace` and `maxSafeFall` (`allowParkour` arrives with
    the parkour move, after v1);
  - water and hazard costs;
  - avoidance sources, as positions and radii;
  - travel bounds and a placeable-item budget.

  Callers derive variants such as `noEdits()` and `withWaterCost(x)` instead of
  mutating globals.
- **`Move` catalog.**
  - Movement: Traverse, Diagonal, Ascend, Descend, Fall(n), Swim, Climb and Door.
  - Edits: Tunnel (break), Bridge and Pillar (place). Parkour comes after v1.

  Each move declares its cost, its required edits, and its occupied envelope.
  Travel bounds check the whole envelope, which is what the current
  `BaritoneTravelBoundsMixin` does.
- **`Goal`.** Block, XZ, Near(r), AnyOf(cells) for interaction work positions,
  and Entity for moving targets. **Arrival uses the same predicate as search**,
  which removes the observation window and the reconciliation after
  `AT_GOAL`/`CALC_FAILED`.
- **`PathSearch`.** Weighted A* with node and wall-time budgets. It returns
  `Found`, `Partial` (the best prefix toward the goal, with a reason),
  `Unreachable` (a reason plus the explored frontier) or `Cancelled`. One worker
  thread runs it with an epoch token. Results are delivered on the client thread
  and dropped if the epoch has changed.
- **Motor.**
  - A `MoveExecutor` per move type. It reads `BodyState` (position, velocity,
    ground and water contact, horizontal collision, rotation, break progress)
    and probes the live terrain. It produces a `MotorIntent` (direction, jump,
    sneak, sprint, look target, break, place or use target) and a `MoveStatus`.
  - A `PathFollower`. It runs the current move and rechecks the next N moves
    against the live terrain and policy every tick. This replaces
    `BaritoneTravelExecutionMixin`.
  - The follower also replans on deviation, owns the single stall detector, and
    closes doors behind the player, replacing `NavigationDoorInteraction`.
- **Control arbitration types.** `ControlLease`, `Priority`, `MotorIntent`,
  `ControlFrame`, and a pure `ControlArbiter` that merges lease holders' intents
  into one frame per tick. Preemption is unit-testable.

### Mod-side adapters (`agent.control`, `agent.navigation`)

- **`MinecraftTerrainSnapshotter`.** Builds `TerrainView` from loaded chunks.
  Cells are classified from collision and outline shapes, not block lists.
  Unknown cells are impassable (fail closed).
- **`ControlPlane`.** Applies the merged frame once per tick, where
  `cameraController.tick` runs in `ClientRuntimeController.onClientTick` today.
  - **Movement:** installs an Airicraft `Input` on the player, as Baritone does,
    instead of pressing `GameOptions` key bindings and flipping `autoJump`.
    During Phase 1, verify which 1.21.8 paths still read `GameOptions` directly,
    such as sprint and sneak toggles.
  - **Rotation:** `CameraController` becomes the look channel. It keeps its
    spring smoothing and `whenAligned`.
  - **Hotbar and clicks:** one hotbar writer, and one click channel for attack
    and use. The click channel enforces travel bounds and preserved areas. The
    existing `ClientPlayerInteractionManagerMixin` vetoes stay as a backstop,
    keyed on the lease owner instead of Baritone's forced click.
- **`NavigationService`.** `navigate(NavigationRequest, ControlLease)` returns a
  `NavigationHandle`.
  - The handle reports progress: current move, remaining cost, ETA and break
    target.
  - Outcomes are sealed: `Arrived`, `Unreachable(reason, partialEnd)`,
    `Stalled(evidence)`, `Preempted(by)`, `Cancelled`, `PolicyBlocked(cell)` and
    `WorldChanged`.
  - `cancel()` is synchronous: the channel is cleared in the same tick, and no
    acknowledgement event follows.
- **Observability.**
  - Render the path and current move through `HighlightManager`.
  - Add navigation evidence to dashboard observations and tick-debug captures;
    the frame owner and intents per tick make player-action captures explainable.
  - Add `airicraft agent debug navigation plan|state` for dry-run plans.

### Leases replace release barriers

- Priorities: `REFLEX` (`SurvivalReflexRuntime`) > `FOREGROUND` (the one active
  executor, policy or action graph) > `BACKGROUND` (lighting, idle eating).
- A single foreground lease makes ADR-0002's "single foreground actuator
  boundary" explicit and testable.
- A higher-priority acquire revokes the lower lease synchronously. On its next
  tick the holder sees `revoked(by)`, and its handle ends `Preempted`. Reflex
  holds (`holdId`, `safetyEpoch`) map to a revoked lease that can be resumed.
- Channels are leased separately. For example, eating can hold `secondary` and
  `hotbar` while navigation holds `locomotion` and `look`. This replaces
  `BaritoneInventoryBehaviorMixin` and the slot-0 convention.
- Releasing a lease clears its channels in the same tick. No code waits for
  release.

## Migration plan

This is a strangler migration: every phase can ship on its own. Track A (control
plane) and Track B (navigation core) run in parallel and converge in Phase 3.
Sizes are relative (S, M, L).

### Phase 0 — Baseline and dead code (S)

Status (2026-09-25): done.

- **Delete production-dead mining code.** Done.
  - `HybridMiningTaskExecutor`, `HybridMiningPolicy`,
    `LiveHybridMiningEnvironment` and their tests.
  - `BaritoneFacade.startMine` and `mineProcessActive`, and
    `BaritoneMineProcessAccessor`.
  - `BaritoneBlockBreakMixin` is reduced to clearance-only tool selection, and
    the mine-only branches of `BaritoneTaskExecutor` are gone.
- **Navigation benchmark courses.** Done, as a deterministic evaluator fixture
  rather than `scenarios/nav-*` world archives, driven without a model by
  `scripts/navigation-baseline`. See [navigation-baseline.md](../../navigation-baseline.md).
  - Built: flat walk; staircase ascent; 3-block drop; 5-block drop beside
    stairs; river crossing; dirt wall; gap bridge; pillar pit; door; cave
    tunnel; far `GoalXZ` (320 blocks over natural terrain); travel-bounds
    refusal.
  - Not yet covered: follow (needs a second player) and preserved-area refusal
    (needs a remembered place).
- **Structured navigation metrics.** Done: elapsed and active ticks, path
  length, start and end distance, replans and stalls, on a
  `task`/`terminal_diagnostics` debug-timeline entry. Block break/place counts
  are deferred: they need a new interaction-manager injection point that must be
  verified on a live client.
- **Record the Baritone baseline.** Done in CI: the `navigation baseline` workflow
  runs a headless client under Xvfb. All 60 runs (12 courses, 5 each) passed; the
  summary is in [navigation-baseline.md](../../navigation-baseline.md#baritone-baseline-2026-09-25).
- **CI.** The evaluator addon is now compiled and tested, in its own step after
  the root suite.

### Phase 1 — Control plane, with Baritone as a lease holder (M, Track A)

- **Add the control plane.** Add `ControlArbiter` (core) and `ControlPlane`
  (adapter). Move all key-binding and `autoJump` writes into the plane.
  `MovementController` becomes a thin intent builder, or is deleted.
- **Migrate every writer:**
  - movement: `SurvivalReflexRuntime`, `BehaviorTreeRuntime`, return-to-surface,
    drop-items, entity- and block-interaction executors, underwater
    harvest/escape;
  - keys and item use: `PlacementSneakController`, `OwnedKeyPress` users,
    `PlayerItemUseController`, `LightingRuntime`;
  - hotbar: `MiningToolPreparation`, lure;
  - direct attack and use calls.
- **One camera.** Remove the fallback `new CameraController()` construction from
  production paths.
- **Baritone as a lease holder.** The facade acquires a foreground
  locomotion+look lease on start and releases it on terminal.
  `BaritoneCameraInputMixin` gates Baritone input on lease ownership. Baritone
  pathing is otherwise unchanged.
- **Guard test.** Scan sources and fail on player `setPressed(`,
  `setSelectedSlot(`, `setYaw(` or `setPitch(`, or `interactionManager`
  attack/interact calls outside the control package. The allowlist shrinks with
  each PR.
- **Exit criteria:**
  - existing tests pass;
  - scenario pass rate ≥ baseline;
  - no writes to `GameOptions` key bindings or `autoJump`.

### Phase 2 — `navigation-core` in shadow mode (L, Track B)

- **Build the core.** Add the subproject: `TerrainView`, `MovementPolicy`,
  non-edit moves, budgeted A* and goals. Test with JUnit on ASCII-grid terrains:
  stairs, slabs, fences, doors, water, lava, drops and tunnels.
- **Mod integration.** Add the snapshotter and async search.
  `airicraft agent debug navigation plan --x --y --z` highlights the planned
  path.
- **Shadow mode.** For every Baritone navigation request, also plan in-house
  with the same goal and an equivalent policy. Record the outcome
  (found/partial/unreachable), cost, length, nodes and milliseconds in dashboard
  observations. Nothing actuates.
- **First real consumer: combat.** Replace `CalculationContext`, `Moves` and
  `MovementHelper` in `MinecraftCombatPositioning` (`edges`, `dryAndSafe`) with
  core moves under a no-edits policy. Combat never used Baritone's executor.

### Phase 3 — Motor and `NavigationService` behind a flag (L, converge)

- **Movement executors.** Add `MoveExecutor`s for walk, diagonal, ascend,
  descend, fall, swim, climb and door. Add the `PathFollower` with live
  revalidation, replanning, stall detection and door restore. `CombatTraversal`
  consumes these executors.
- **Edit moves.**
  - Tunnel: `MiningToolPreparation` plus the click channel.
  - Bridge and Pillar: the click channel plus sneak.
  - Parkour: not in v1. Baritone enables parkour by default today, so a route
    that needs a gap jump ends `Unreachable` or `Partial` until it lands.
- **Two backends, during development only.**
  - `AiricraftNavigationService` is the new implementation.
  - `BaritoneNavigationService` wraps the facade and translates path events to
    typed outcomes once. Its barrier logic stays private. It exists only so that
    consumers can move to `NavigationService` before the switch.
  - A config key, `navigation.backend = baritone|airicraft`, selects the backend
    and is live-reloadable through `airicraft reload`. The default stays
    `baritone` until the flip, which deletes the key.
- **Exit:** navigation scenarios on the Airicraft backend meet the parity gates
  below.

### Phase 4 — Consumer migration (M, many small PRs)

Migrate the lowest-risk consumers first. Each PR deletes that consumer's
release-barrier and pending-cancel code.

1. Lure: `policy.noEdits()` replaces the global settings flip.
2. Approach steps, using `Near` and `AnyOf` goals: entity and block
   interaction, crafting, smelting, drop items and crop tending.
3. Underwater escape and harvest: the `WaypointDriver` becomes Swim moves, and
   `waitingForBaritoneRelease` goes away.
4. Target acquisition: `AnyOf` work positions, and an Entity goal for drop
   pickup.
5. Reflex combat approach: `startNavigateNear(target, 2)` becomes an Entity goal
   under a `REFLEX` lease.
6. Return to surface. Follow becomes an Entity goal with continuous retargeting,
   instead of a restart after each terminal event.
7. Generic `navigate_to`: `BaritoneTaskExecutor` becomes `NavigationTaskExecutor`.
   - Water-stall recovery becomes a replan with `withWaterCost(...)`.
   - `observeNavigationEnd` and the `CALC_FAILED`-at-goal reconciliation are
     deleted.
   - `DispatchingWorldTaskExecutor` loses `sharedBaritone` and its duplicate
     stall watchdog.

### Phase 5 — Planner contract (M, kept separate from navigation changes)

- **Movement policy tool.** Replace Baritone setting names with the Airicraft
  movement policy, keeping both tool names. `configure_pathfind` takes a small
  typed schema: `allowBreak`, `allowPlace`, `maxFallHeight`, `waterCost`,
  `avoidMobs` and `allowInventoryToolSwap`. `allowParkour` joins it when parkour
  lands. `inspect_pathfind` reads it back.
- **Update every surface that names Baritone:** `PlannerToolCatalog`,
  `PathfindSettingsToolProvider`, `prompts/planner-system.md` (line 43),
  `policies/survey.json` and `docs/cave-exploration.md`.
- **Rename planner-visible strings.**
  - `TaskTerminationCause.BARITONE_CANCELLED` becomes `NAVIGATION_CANCELLED`. It
    reaches the planner as `terminationCause` in event payloads
    (`EmbodiedAgentRuntime.java:5207`).
  - `baritone_unavailable` and `baritone_chase` get neutral names too.
- **Evaluate separately.** Run the planner scenarios on their own so that prompt
  regressions are not mistaken for navigation regressions.

### Phase 6 — Flip and remove in one change (S)

- **Merge criterion.** The parity gates below pass on the flip branch. There is
  no soak period and no fallback afterwards.
- **Flip.** `AiricraftNavigationService` becomes the only backend. Delete the
  `navigation.backend` key and `BaritoneNavigationService`.
- **Delete, in the same change:**
  - the Baritone and nether-pathfinder jars and their `modImplementation` lines;
  - the `fabric.mod.json` dependency and the compat/evaluator runtime jar lists;
  - the ten Baritone mixins and the `agent/baritone` package;
  - `BaritoneReleaseBarrier` and `BaritoneTaskExecutor`.
- **Update docs:** the reflex README ("Baritone owns combat approach…"),
  `camera-control.md`, `combat-positioning.md` and the cave docs. Historical
  logs and experiment records stay as written. Mark ADR-0003 implemented.
- **Release note.** Airicraft no longer configures Baritone, so users should
  remove any Baritone jar from their mods folder.

## Parity gates (proposed defaults)

With no fallback period, these gates are the only protection before the switch.
They are the merge criterion for the Phase 6 change.

- **Safety (hard gates):**
  - zero planned or executed cells outside travel bounds or inside preserved
    areas, across shadow runs and scenarios;
  - zero fall deaths in navigation scenarios.
- **Shadow coverage:**
  - `Found` for at least 95% of the loaded-terrain requests Baritone solved.
    Requests whose Baritone path contains parkour movements are reported
    separately and do not count against this gate;
  - median cost within +15% of Baritone's;
  - p95 planning time at most 50 ms off-thread.
- **Scenarios:**
  - navigation pass rate ≥ the Baritone baseline over 5 runs each. The far
    `GoalXZ` scenario passes on arrival; its path cost is not compared, because
    segmented planning differs from Baritone's cached-chunk pathing by design;
  - no regression in the existing ten scenarios.
- **Live session:** one Codex-driver session on the flip branch covering travel,
  resource acquisition, combat and underwater recovery, in place of a soak.
- **Stalls:** stall rate ≤ baseline.

## Risks

- **Movement edge cases.** Baritone encodes years of them: slabs, fences,
  soul sand, ice, powder snow, bubble columns and magma. Mitigations: classify
  cells from collision shapes, fail closed on unknown cells, grow coverage from
  scenario failures, and keep parkour last.
- **Long-range travel.** Baritone paths through cached unloaded chunks. v1 plans
  to the loaded frontier toward the goal and replans as chunks load; the far
  `GoalXZ` scenario measures this.
- **Human-like input.** Emit only player inputs and smoothed rotations, never
  packet-level movement, matching current behavior.
- **Thread safety.** Search reads only immutable snapshots, and the live world
  is touched only on the client thread.
- **Two backends during Phases 3–5.** Keep `BaritoneNavigationService` a thin
  adapter and add no features to it; the flip deletes it.
- **No fallback after the switch.** A regression found after Phase 6 is fixed
  forward or by reverting the flip change. The gates and the live session carry
  the weight a soak period would have.
- **Parkour gap.** Routes that relied on Baritone's gap jumps fail until the
  parkour move lands. The shadow data shows how often that happens.

## ADR interactions

- ADR-0002 says "keep their executors and single foreground actuator boundary".
  Leases make that boundary explicit.
- ADR-0002 also says "do not add a continuous position controller". That rule
  governs System 2 decisions. The motor replaces Baritone's existing System 1
  executor; it does not give System 2 a continuous controller.
- Neither is a conflict. ADR-0003 records both points.
