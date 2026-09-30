# Semantic blueprint prototype

Throwaway experiment on `codex/blueprint-prototype`. The question is whether composing named semantic objects, retaining block provenance, and separating draft from realization makes building easier.

## Run without Minecraft (current experiment)

```sh
BLUEPRINT_OFFLINE=1 python3 prototypes/blueprint/serve.py
```

Open http://127.0.0.1:8788. Requires Python 3 and Node; no Gradle, game process, rendering or world mutation. Compile, component inspection, provenance and JavaScript linting work offline. World controls are disabled. Each evaluation runs in a short-lived Node worker with a 96 MiB heap cap, one-second JS timeout and eight-second process timeout. The prototype VM is for locally authored/generated experiment code, not a hardened multi-tenant sandbox.

The offline compiler is a temporary adapter reproducing the Java component traversal. It does **not** validate against Minecraft's block registry/default states. Geometry uses a small explicit full-cube/straight-stair/open-wooden-door fixture; other shapes are unknown. Ground is solid below local y=0. Terrain sampling/foundations are currently live-only. The bundled component library and rule sources are shared across both backends. A saved live house matched all 1,007 cell positions, material IDs and ownership histories offline; that does not establish full engine equivalence.

## Run with Minecraft

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

The terrain and superflat worlds retain these earlier builds. Minecraft was subsequently stopped at the user's request to reduce MacBook load; the editor now runs offline. Restart commands are above.

**Verdict:** semantic composition and block provenance work together in live Minecraft. Explicit owned air is useful for both openings and inspection. Terrain should stay distinguishable from authored blocks. Next design work should address attachment/constraint resolution, terrain suitability (including fluids/vegetation), access to elevated entrances, and durable identity/provenance before treating this as production construction tooling.


## Extensible advisory rules

Rules are ordinary JavaScript defining `function check(ctx)`. `lint` uses bundled `entrance-access` and `room-lighting` rules unless given `rules:[{id,source}]`; the UI has a custom-rule editor. Rules receive a detached draft/site snapshot, run independently, and cannot block commits. Results pin the draft revision and contain per-rule status, component paths, coordinates and evidence. Click a finding to select its component and highlight its coordinates.

```js
function check(ctx) {
  for (const room of ctx.components({type:'Room'})) {
    const windows = ctx.components({type:'Window'})
      .filter(w => w.path.startsWith(room.path + '.'));
    if (!windows.length) ctx.warn({component:room.path,
      positions:[room.origin], message:'No windows here; is that intentional?'});
  }
}
```

Available queries: `components({type})`, `cells(component)`, `block([x,y,z])`, `access.entrance(component)`, `lighting.darkWalkingSurfaces(component, threshold)`. Emit `warn`, `info`, or `unverified(component, reason)`. No package imports or host/network calls. Rules have stable IDs, at most 12 per request and 48 findings per rule; one rule failure does not cancel the others. Live execution uses the existing bounded Graal query runtime; offline uses bounded workers.

Component `guidance` merges over parent guidance. `Room` explicitly defaults its lighting guidance to `expected`; use `guidance:{lighting:'dark'}` on a mob chamber, while maintenance rooms keep `expected`. `minLight` changes the advisory threshold (default 8). Tag walk-in exterior doors with `guidance:{access:'walk'}`. Suppress chosen rules on a component/subtree with `disabledRules:['rule-id']` and `suppressionReason:'intentional design'`; suppressed findings remain inspectable in results. A semantic parent component can carry custom guidance for custom rules.

Entrance advice checks a short straight approach with a 0.6×1.8 body and <=0.6 step, assuming wooden doors can open. It does not prove circulation between rooms or floors. Lighting is an approximate artificial-light flood fill, omitting skylight, external boundary light and partial-block occlusion; it is not a mob-spawning guarantee. Unsupported/incomplete geometry is reported as unverified where queried. These are style helpers, not validity constraints or automatic fixes.

Run the lightweight probes with `python3 prototypes/blueprint/check-offline.py`. They cover absent/fixed steps, dark/illuminated rooms, intentional darkness, scoped suppression, timeout/host-access failure isolation, unchanged drafts and component provenance. Results are saved under ignored `run/blueprint-evidence/rule-probes/`. Java integration compiled before switching offline; the new lint operations have not been exercised inside Minecraft.

## Model experiment

`model-tasks.json` fixes three exploratory tasks. `model-trial.py` calls the configured provider/model remotely and uses local offline `design`, `lint`, and `inspect` tools. It does not invoke the embedded planner prompt/scheduler or place blocks. Credentials are read from the existing config and never logged. Full model responses, compiler diagnostics and generated sources stay under ignored `run/blueprint-evidence/`; existing trial directories are never overwritten. No manual source repair is performed.

```sh
BLUEPRINT_TRIAL_ID=my-trial BLUEPRINT_MODEL_TOKENS=16384 \
  python3 prototypes/blueprint/model-trial.py
```

Sampling uses provider defaults, with 8 turns and 3 design submissions per task. The HTTP timeout defaults to 240 seconds; `BLUEPRINT_REQUEST_TIMEOUT` can set 1–600 seconds, independently of the configured runtime. This is an interface usability probe, not a benchmark or proof of normal planner performance. See `model-report.md` for observed results and limitations. Set `BLUEPRINT_REASONING=none` to reproduce the separate non-reasoning arm; leave unset for provider defaults. The `examples/` sources are unchanged trial outputs, exposed by named UI buttons; the report distinguishes earlier failures from later reasoning-enabled repairs. `rules/empty-geometry.js` is a post-hoc example extension, not part of the original bundled model feedback.

## Semantic common-sense rules

The default JS bundle now also includes:

- `room-coverage`: every column in a Room's declared interior should have a full-width covering block above it. Upper floors, flat ceilings, glass skylights and sloped roofs can provide coverage. It checks final geometry, so an incomplete roof cannot pass on its bounding box alone. `guidance:{coverage:'open'}` marks an intentional open space. Unsupported cover shapes produce an unverified result.
- `stair-access`: a straight bottom-half stair flight should have support, sufficient headroom and a landing at its top height. The inferred center lane uses a 0.6×1.8 body. Turning/custom flights are unverified, and this does not prove a full room-to-room route. Inherited `guidance:{access:'decorative'}` on an enclosing Assembly exempts a decorative staircase; scoped `disabledRules` also works.
- `component-semantics`: Door/Window/Room/Staircase/GableRoof labels should actually emit blocks, and ignored Room constructor fields should be made visible. For example, `Room.children` is unsupported; use documented slots or sibling components with explicit replacement intent. These findings stay advisory, including when malformed authoring is suspected.

Rule inputs now expose a Room's declared `interior` dimensions, constructor `ignoredFields`, and `ctx.position(component, localPosition)` for transformed coordinates. These facts are separate from final voxel ownership, so a fully furnished interior or an overridden component cannot erase the original semantic expectation.

`python3 prototypes/blueprint/check-semantics.py` checks complete/holey/rotated roofs, intentional open spaces, uncertain block shapes, scoped suppression, ignored fields, empty components, and blocked/repaired stair headroom. It also replays the saved Qwen failure cases. Coverage catches 18 uncovered upper-room columns in the earlier two-story trial; widening its roof clears that advice. The rule found an insufficient headroom opening in our hand-authored house too; the example's stairwell now starts one block earlier. This correction was tested offline only.

## Variety trials and independent review

See [variety-report.md](variety-report.md) for bridge, warehouse, courtyard and watchtower judgments. The editor exposes all four as `Qwen trial · variety-*` buttons. These are unchanged model outputs with known defects; the watchtower run was interrupted by a provider rate limit. No Minecraft process is needed.

A fresh exploratory batch can use:

```sh
BLUEPRINT_TRIAL_ID=my-variety-trial \
BLUEPRINT_TASKS_FILE=prototypes/blueprint/variety-tasks.json \
BLUEPRINT_REASONING=low BLUEPRINT_MODEL_TOKENS=32768 \
BLUEPRINT_REQUEST_TIMEOUT=480 BLUEPRINT_MAX_DESIGNS=5 BLUEPRINT_MAX_TURNS=12 \
  python3 prototypes/blueprint/model-trial.py
python3 prototypes/blueprint/review-variety.py run/blueprint-evidence/my-variety-trial
```

The review script is specific to these fixed briefs and applies separate post-hoc geometry probes. It is not a general structure linter or Minecraft movement simulation. The report documents its assumptions, rule blind spots, original failed attempts and retry policy.
