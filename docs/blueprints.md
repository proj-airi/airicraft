# Mod-owned blueprints

Blueprint state is owned by `BlueprintService` inside the mod. The normal `blueprint` planner tool and the authenticated wrapper control path use the same serialized session. The existing debug dashboard has a **Blueprint** tab showing the published draft, source, component hierarchy, exact block ownership and advisory findings. No Python process or separate playground is required.

## Ownership and lifecycle

- The service compiles declarative JavaScript component trees with bounded GraalJS, then validates/materializes cells with the Java compiler and Minecraft's block registry.
- A successful draft creates a new revision. Failed drafts preserve the previous revision. Drafting and linting never place blocks.
- Lint captures detached real-world geometry merged with planned cells on the integrated server thread, then executes independent bounded JavaScript rules. Findings include applicability, coordinates and ownership; advice does not block commits.
- The dashboard reads an immutable, pre-serialized snapshot through authenticated `GET /api/blueprint`. The HTTP handler never evaluates source, accesses world state or invokes a control tool. POST is rejected. The existing viewer token remains separate from the bridge credential.
- Tool operations serialize across provider instances and asynchronous evaluations. A world-session generation rejects stale queued/in-flight operations. Leaving the world clears the published view and releases retained state after outstanding work finishes. Reloading the agent does not independently create a second blueprint service.
- Drafts are currently in memory for one integrated-server world session, not durable saved schematics. Blueprint viewing is a live session view, not part of dashboard historical playback. Singleplayer/integrated-server operation is currently required.

## Authoring and inspection

Use `blueprint` with `op:"rule_docs"` to retrieve the component library, rule API and defaults. Submit JavaScript defining `function design(input)` with `op:"draft", source:...`. `get` returns the current draft; `explain` traces a local position to its owner and overridden contributors. The authoring library and advisory sources live under `src/main/resources/blueprint/`.

Components include `Assembly`, `Room`, `Floor`, `Door`, `Window`, `Staircase`, `GableRoof`, `Foundation`, `Solid` and `Clearance`. Explicit `replaces` relationships resolve intentional overlaps. `Clearance` emits owned air; unspecified cells do not change the world.

`Entrance`, `WalkRoute`, `Guardrail` and `WalkableArea` express intent:

- `Entrance` is an open entrance with local walk-access guidance.
- `WalkRoute` declares integer feet endpoints and bounded search region, with optional body width. It checks a conservative half-block movement graph, not the motion controller.
- `Guardrail` relates an edge barrier to an exact surface component path.
- `WalkableArea({id,surface,blocks})` selects support-block coordinates in the referenced component's local frame. Omit `blocks` to select its declared volume top layer. The annotation emits no geometry. It checks preserved support and two blocks of clearance above actual collision tops, including stair treads and slabs.

Annotate circulation surfaces rather than furniture footprints. The compiler retains declared volume dimensions separately from final ownership so a deleted floor cannot silently delete its walkability obligation. Unknown or unsupported geometry remains unverified. All rules are replaceable JavaScript and support component-scoped suppressions.

## Creative realization and diagnostics

`commit` requires an exact revision and origin. It currently spawns the draft's cells directly and is limited to creative worlds named `Blueprint-*`. `verify` checks those committed cells against the world. Unspecified cells remain untouched: moving/removing geometry in a later draft does not automatically undo an earlier commit. Use explicit Clearance or separate test sites for those changes. Survival placement/pathfinding execution is not implemented.

`sample` captures terrain for foundation authoring; `save` saves the scratch world. `prepare` and `view` are Codex-driver-only diagnostics (creative test setup and camera positioning). The normal planner can draft, inspect and lint without external-driver mode. The dashboard exposes none of these mutation operations.

## Validation

`DebugDashboardServerTest` verifies that the blueprint API requires the viewer token, serves updated detached snapshots and rejects POST. The historical lightweight rule probes and model experiments remain under `prototypes/blueprint/` for reproducibility; they are not a runtime dependency. The former Python HTTP server and interactive playground are retired in favor of the in-mod dashboard.

The live creative check on 2026-10-01 used two workshop sites in `Blueprint-Superflat`: `[24,-60,0]` with a low beam and `[36,-60,0]` with the raised beam. The low beam produced both route and two-block-clearance warnings with `workshop.beam` as the obstruction owner. The repaired draft passed those checks, with all eight advisory rules completing. The commits verified 62/62 and 66/66 exact block states respectively. The authenticated dashboard snapshot matched draft, lint and committed revision 2. Raw local evidence is in ignored `run/blueprint-evidence/`.

One rule hit an isolated error during the first cold run; other rules continued and the next complete run succeeded. Rule failures remain visible as failures, not a clean bill of health. Null exception messages now fall back to the error class name.

A clean-client repeat completed all eight rules, rejected an intentionally throwing draft without changing the revision, verified 66/66 committed cells, and retained revision 1 through `airicraft reload`. The dashboard was browser-verified against this live session, including its empty-draft state and rendered component/rule lists.
