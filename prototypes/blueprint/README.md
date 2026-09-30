# Semantic blueprint prototype

Throwaway experiment on `codex/blueprint-prototype`. The question is whether composing named semantic objects, retaining block provenance, and separating draft from realization makes building easier.

## Run

With JDK 25 on `JAVA_HOME`:

```sh
export AIRICRAFT_BRIDGE_STATE_FILE=/tmp/airicraft-blueprint-bridge.json
scripts/codex-driver
```

In a second terminal, with the same environment:

```sh
python3 prototypes/blueprint/serve.py
```

Open http://127.0.0.1:8788. Join a scratch world named `Blueprint-*` using the normal world selector or `airicraft worlds join`. The prototype tool is available only in Codex driver mode. `op: prepare` enables creative in that scratch world. The UI is a small adapter over the public wrapper CLI, not a second control bridge.

The one-off `create-worlds.py` uses `nbtlib` and this checkout's `run/saves/a/level.dat` as a metadata template to seed **fresh, chunk-free** superflat and normal terrain scratch worlds. It neither copies chunks nor modifies existing worlds. Existing scratch worlds are retained.

## Authoring

Define `function design(input)` returning a component tree. The built-in constructors are in `src/main/resources/blueprint-prototype/components.js`; `house.js` is the two-story example. Ordinary JavaScript functions can define reusable components, and arrays/loops compose repeated instances.

- Coordinates are integer local block coordinates. Front is negative Z. Sizes are counts, not inclusive endpoints.
- `Room.interior` is usable width, height, depth; walls add one block on each side. Its floor is local y=0.
- Every node has a sibling-unique `id`. Its full path identifies the instance. Parent translations and quarter-turn rotations transform descendants and block states together.
- A component's nested children can replace its volume. Door/window children therefore carve their host wall with explicit provenance. Other overlaps fail unless the new component names an allowed `replaces` path (or list). Explicit sibling overrides currently require their targets to occur earlier in the tree; a dependency resolver is future work.
- `Clearance` emits owned air. Missing cells leave the world untouched.
- `Room` exports named entrance/above anchors in inspection output. General anchor attachment and a layout constraint solver are not implemented.
- `get` exposes the entire component tree and final cell map. `explain` returns owner, ancestry, contributor history, and block state. World-coordinate explanation refers to the last committed revision and compares actual state.
- Initialization from terrain samples a 16×16 height field plus the top three blocks of each column. These cells are observed context, not design output. The example raises its datum above the sample and emits foundation blocks down to the sampled ground. This is not a complete terrain voxel import or cave/vegetation solver.

## Creative realization

Commit pins a draft revision and world origin, validates all cells are nearby and loaded, then sets server blocks directly in a creative `Blueprint-*` integrated world. It reports how many exact states match. `verify` checks them again later. This deliberately bypasses movement, materials and survival construction.

Direct tool use:

```sh
wrapper/build/install/airicraft/bin/airicraft agent tools call \
  --name blueprint_prototype --arguments '{"op":"get"}' --verbose
```

`draft` takes `source`; `commit` takes `revision` and `origin:[x,y,z]`; `explain` takes `position` and optionally `world:true`; `sample` takes a world `origin`. `view` takes `position`, optional `yaw` and `pitch`, and positions the creative player for screenshots. Use `camera screenshot --output <path>` for actual Minecraft evidence.

## Deliberate prototype limits

One in-memory draft and last commit per provider; restart/reload loses provenance. World blocks remain saved. No undo, save/import format, commit diff, removal of old unspecified cells, multi-client editing, general layout solver, or survival executor. Recompiling a smaller design does not erase remnants of an earlier commit. Terrain snapshots are not automatically refreshed; resample before adapting to a changed site. Sampling invalidates the old draft, requiring recompilation. `op: save` flushes the scratch world to disk. Limit 8,192 cells, 512 components and 24 nested levels. Commit uses direct state writes with neighbor notifications suppressed; this is suitable for static geometry experiments, not proof of redstone/physics correctness.

## Live experiment — 2026-09-30

Driven through `scripts/codex-driver`, with `codexDriverActive: true`, normal optional integrations enabled, and the embedded planner suppressed.

| Check | Observed result |
| --- | --- |
| Superflat house, origin `[0,-60,0]` | 66 components; 1,007 specified cells; 0 mismatches after commit |
| Visual checks | Exterior, ground-floor staircase/headroom opening, and upper floor inspected from real client screenshots |
| Door at world `[5,-58,0]` | Owner `village.house.ground.front.entrance.upper`; wall retained in contribution history; actual state matched |
| Stairwell at local `[7,4,3]` | Owned air from `village.house.upper.floor.stairwell`; floor retained as contributor |
| Repeated component rotations | Independent `east`/`west` instances produced correctly rotated door states and separate paths |
| Unrelated overlap / stale revision | Both rejected; failed compilation preserved previous draft |
| Draft isolation | A different repeated-door draft left the committed house at 0 mismatches |
| Terrain resampling | Invalidated the draft; attempting to commit its former revision returned `no_draft` |
| Terrain house, origin `[40,70,54]` | Same source; floor at world y=69, sampled footprint surface y=65–68; 92 foundation blocks; 67 components and 1,099 specified cells; 0 mismatches |
| Foundation world query | World `[50,68,62]` resolved to `village.foundation`, matching cobblestone |
| Persistence | Terrain scratch world explicitly saved with server acknowledgement |

Runtime evidence (ignored local files) is under `run/blueprint-evidence/`: draft JSON, exact state verification, provenance responses, probe results, before/after and interior screenshots. The first normal-world sample was water; its screenshot and material observations informed choosing a dry sand footprint. This experiment establishes height-based foundations on that site, not general terrain suitability or access planning.

The editor runs at `http://127.0.0.1:8788`; the active world is `Blueprint-Terrain`. `Blueprint-Superflat` retains the earlier house. The live processes are intentionally left running for inspection. Restart commands are above.

**Verdict:** semantic composition and block provenance work together in live Minecraft. Explicit owned air is useful for both openings and inspection. Terrain should stay distinguishable from authored blocks. Next design work should address attachment/constraint resolution, terrain suitability (including fluids/vegetation), access to elevated entrances, and durable identity/provenance before treating this as production construction tooling.
