# Mod-owned blueprints

Blueprint state is owned by `BlueprintService` inside the mod. The normal `blueprint` planner tool and the authenticated wrapper control path use the same serialized session. The existing debug dashboard has a **Blueprint** tab showing the published draft, source, component hierarchy, exact block ownership and advisory findings. No Python process or separate playground is required.

## Ownership and lifecycle

- The service compiles declarative JavaScript component trees with bounded GraalJS, then validates/materializes cells with the Java compiler and Minecraft's block registry.
- A successful draft creates a new revision. Failed drafts preserve the previous revision. Drafting and linting never place blocks.
- Lint captures detached real-world geometry merged with planned cells on the integrated server thread, then executes independent bounded JavaScript rules. Findings include applicability, coordinates and ownership; advice does not block commits.
- The dashboard reads an immutable, pre-serialized snapshot through authenticated `GET /api/blueprint`. The HTTP handler never evaluates source, accesses world state or invokes a control tool. POST is rejected. The existing viewer token remains separate from the bridge credential.
- Tool operations serialize across provider instances and asynchronous evaluations. A world-session generation rejects stale queued/in-flight operations. Leaving the world clears the published view and releases retained state after outstanding work finishes. Reloading the agent does not independently create a second blueprint service.
- Drafts and semantic component trees are saved atomically under the world folder at `airicraft/blueprints.json`, together with source, terrain context, dimension, origin, revision and committed design. Blueprint viewing is a live session view, not part of dashboard historical playback. Singleplayer/integrated-server operation is currently required.

## Authoring and inspection

The normal planner delegates authoring to `design_blueprint` (see below). In Codex driver mode, use `blueprint` with `op:"rule_docs"` to retrieve the component library, rule API and defaults, then submit JavaScript defining `function design(input)` with `op:"draft", source:...`. `get` returns compact draft metadata to the ordinary planner and the full draft in driver mode; `explain` traces a local position to its owner and overridden contributors. The authoring library and advisory sources live under `src/main/resources/blueprint/`.

Components include `Assembly`, `Room`, `Floor`, `Door`, `Window`, `Staircase`, `GableRoof`, `Foundation`, `Solid` and `Clearance`. Explicit `replaces` relationships resolve intentional overlaps. `Clearance` emits owned air; unspecified cells do not change the world.

`Entrance`, `WalkRoute`, `Guardrail` and `WalkableArea` express intent:

- `Entrance` is an open entrance with local walk-access guidance.
- `WalkRoute` declares integer feet endpoints and bounded search region, with optional body width. It checks a conservative half-block movement graph, not the motion controller.
- `Guardrail` relates an edge barrier to an exact surface component path.
- `WalkableArea({id,surface,blocks})` selects support-block coordinates in the referenced component's local frame. Omit `blocks` to select its declared volume top layer. The annotation emits no geometry. It checks preserved support and two blocks of clearance above actual collision tops, including stair treads and slabs.

Annotate circulation surfaces rather than furniture footprints. The compiler retains declared volume dimensions separately from final ownership so a deleted floor cannot silently delete its walkability obligation. Unknown or unsupported geometry remains unverified. All rules are replaceable JavaScript and support component-scoped suppressions.

## Creative realization and diagnostics

`commit` requires an exact revision and origin. It currently spawns the draft's cells directly and is limited to creative worlds named `Blueprint-*`. `verify` checks those committed cells against the world. Unspecified cells remain untouched: moving/removing geometry in a later draft does not automatically undo an earlier commit. Use explicit Clearance or separate test sites for those changes. Survival placement/pathfinding execution is not implemented.

`sample` captures terrain for foundation authoring; `save` saves the scratch world. `prepare` and `view` are Codex-driver-only diagnostics (creative test setup and camera positioning). The normal planner delegates drafting and linting to its designer and can inspect, commit and verify without external-driver mode. The dashboard exposes none of these mutation operations.

## Validation

`DebugDashboardServerTest` verifies that the blueprint API requires the viewer token, serves updated detached snapshots and rejects POST. The historical lightweight rule probes and model experiments remain under `prototypes/blueprint/` for reproducibility; they are not a runtime dependency. The former Python HTTP server and interactive playground are retired in favor of the in-mod dashboard.

The live creative check on 2026-10-01 used two workshop sites in `Blueprint-Superflat`: `[24,-60,0]` with a low beam and `[36,-60,0]` with the raised beam. The low beam produced both route and two-block-clearance warnings with `workshop.beam` as the obstruction owner. The repaired draft passed those checks, with all eight advisory rules completing. The commits verified 62/62 and 66/66 exact block states respectively. The authenticated dashboard snapshot matched draft, lint and committed revision 2. Raw local evidence is in ignored `run/blueprint-evidence/`.

One rule hit an isolated error during the first cold run; other rules continued and the next complete run succeeded. Rule failures remain visible as failures, not a clean bill of health. Null exception messages now fall back to the error class name.

A clean-client repeat completed all eight rules, rejected an intentionally throwing draft without changing the revision, verified 66/66 committed cells, and retained revision 1 through `airicraft reload`. The dashboard was browser-verified against this live session, including its empty-draft state and rendered component/rule lists.

## Dedicated designer

The normal planner calls `design_blueprint({brief,site:[x,y,z],revise?})`. A separate model conversation receives the brief, local terrain height field, semantic authoring library and its own draft/compiler/lint results. It does not inherit the main planner's chat, character, inventory, movement history or gameplay tools. The configured model/backend is reused. One design job runs at a time; its tool result returns when the bounded loop completes, fails or is cancelled. The main planner reviews the returned ID, revision, dimensions and findings before explicitly committing and verifying.

The specialist can only draft, inspect coordinates/components and run the bundled advisory rules. Each successful draft updates the Blueprint preview. Full cell maps are omitted from its repeated conversational feedback. It has a 16-turn and 240,000-character conversation budget; budget exhaustion is reported rather than treated as completion. A final host-run lint is required before a completed result. Findings remain advisory, and rule failures are returned for review.

`revise` refers to the ID returned by this planner session and must retain the same site. A repeated request at the same site is also treated as a revision, so the completed building is not resampled as terrain. Terrain sampling preserves the last accepted draft, source and advice until another draft succeeds. It seeds a fresh specialist conversation with the existing component tree rather than unrelated earlier chat. There is one active design and a world-local catalog of up to 32 independent designs. `blueprint list` discovers saved IDs and `blueprint load` selects one for inspection or revision. Planner reset cancels an active design; world leave invalidates its lease and clears the in-memory preview; the saved catalog reloads on joining the world. Late model responses cannot write into a new session. Direct authoring through the raw `blueprint` tool remains available only in Codex driver mode; ordinary planners use the specialist.

The **Blueprint designer** dashboard tab is separate from the main LLM transcript. It shows the brief, supplied site/design context, model progress, assistant replies, tool calls, compiler/lint results and errors. It is a read-only live-session transcript, capped at 200,000 characters (40,000 per entry); display truncation is explicit. The worker is not fed the viewer transcript, and its internal conversation is not added to the main planner's transcript. Historical playback/export of this separate transcript is not yet implemented.

The first live specialist check on 2026-10-01 used DeepSeek via the normal planner: the parent invoked `design_blueprint`, the worker started with two isolated messages and blueprint-only tools, and the dashboard displayed its separate brief, context, draft call, compiler rejection and next iteration. This validates delegation and visibility, not successful completion of that house. Screenshot: ignored `run/blueprint-evidence/designer-dashboard-live.jpg`. The focused suite passed 9 designer/provider/lease tests and 9 dashboard tests.

Follow-up diagnosis found that the parent had committed 912 cells at `[64,-60,32]`, then started another design at that same site after four fence connection-state mismatches. Resampling cleared the preview and interpreted the roof as terrain. Sampling now preserves the accepted design and same-site designer requests revise the existing design. The live regression check preserved revision 4, all draft/source data and lint exactly across sampling (`run/blueprint-evidence/retention-check.json`). The separate test designer reached its iteration limit; its accepted 599-cell draft is visible but uncommitted. The earlier house was visually confirmed in-world. Focused blueprint tests pass (11 tests).


## Persistence and integrity

Each successful draft and commit is durably written through an atomic replacement on a dedicated storage thread. Persisted source is retained for inspection but is never evaluated on load; the saved semantic tree is compiled with the block registry. Unsupported/corrupt files are reported and block operations rather than being silently replaced. The catalog separates furniture/additions from other blueprint IDs, even when origins match. Save files are bounded to 64 MiB.

A periodic server-thread check reads at most 256 committed cells per scheduled pass. It does not load chunks. Completed checks report matching, mismatch, or unknown with a timestamp, changed-cell count and up to 30 coordinate/expected/actual/owner details. Unknown/unloaded cells are never declared intact. The dashboard lists saved drafts and committed designs separately, with integrity status; the existing source and component inspector work for either selection. This is detection only: no automatic repairs, protection, or planner wake is triggered. Neighbor-derived fence/pane/wall connection properties are ignored; other block/state changes are reported. Container inventory contents are not part of blueprint comparison.

Live persistence/integrity check (2026-10-01): the designer furnished the existing house with 17 blocks, all verified. Two independent designs were saved (a 599-cell uncommitted test house and the committed furnishings). Removing the barrel at `[70,-59,39]` automatically reported one mismatch owned by `furnishings.workstation_corner.barrel_rear_a`; restoring it returned the scan to 17 matching cells. A full client restart reloaded identical catalog, source and semantic tree without model generation. Evidence: ignored `run/blueprint-evidence/persistence-damage.json`, `persistence-reload.json`, and `persisted-catalog.jpg`. All 23 focused blueprint/dashboard tests passed. The old house shell predates persistence and its already-lost original semantic tree could not be recovered; this check tracks its saved furnishings, not that shell. Intentional edits are also reported as deviations; mismatch detection does not infer whether a creeper, player or other cause made the change.
