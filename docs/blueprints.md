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

`commit` requires an exact revision and origin. It currently spawns the draft's cells directly and is limited to creative worlds named `Blueprint-*`. `verify` checks those committed cells against the world. Unspecified cells remain untouched: moving/removing geometry in a later draft does not automatically undo an earlier commit. Use explicit Clearance or separate test sites for those changes. The separate `construct_blueprint` tool below performs normal movement and placement; supplied-material Survival qualifications are listed below.

`sample` captures terrain for foundation authoring; `save` saves the scratch world. `prepare` and `view` are Codex-driver-only diagnostics (test setup and camera positioning). `prepare` accepts `gameMode: "creative"` (default) or `"survival"`, preserves inventory (optionally topped up with `give: {"minecraft:oak_planks": 128}`), and sets peaceful daytime in a `Blueprint-*` scratch world. Survival construction still consumes supplied materials; direct-block `commit` remains Creative-only. The normal planner delegates drafting and linting to its designer and can inspect, commit and verify without external-driver mode. The dashboard exposes none of these mutation operations.

## Validation

`DebugDashboardServerTest` verifies that the blueprint API requires the viewer token, serves updated detached snapshots and rejects POST. The historical lightweight rule probes and model experiments remain under `prototypes/blueprint/` for reproducibility; they are not a runtime dependency. The former Python HTTP server and interactive playground are retired in favor of the in-mod dashboard.

The live creative check on 2026-10-01 used two workshop sites in `Blueprint-Superflat`: `[24,-60,0]` with a low beam and `[36,-60,0]` with the raised beam. The low beam produced both route and two-block-clearance warnings with `workshop.beam` as the obstruction owner. The repaired draft passed those checks, with all eight advisory rules completing. The commits verified 62/62 and 66/66 exact block states respectively. The authenticated dashboard snapshot matched draft, lint and committed revision 2. Raw local evidence is in ignored `run/blueprint-evidence/`.

One rule hit an isolated error during the first cold run; other rules continued and the next complete run succeeded. Rule failures remain visible as failures, not a clean bill of health. Null exception messages now fall back to the error class name.

A clean-client repeat completed all eight rules, rejected an intentionally throwing draft without changing the revision, verified 66/66 committed cells, and retained revision 1 through `airicraft reload`. The dashboard was browser-verified against this live session, including its empty-draft state and rendered component/rule lists.

## Dedicated designer

The normal planner calls `design_blueprint({brief,site:[x,y,z],revise?})`. A separate model conversation receives the brief, local terrain height field, semantic authoring library and its own draft/compiler/lint results. It does not inherit the main planner's chat, character, inventory, movement history or gameplay tools. The configured model/backend is reused. One design job runs at a time; its tool result returns when the bounded loop completes, fails or is cancelled. The main planner reviews the returned ID, revision, dimensions and findings before explicitly committing and verifying.

The specialist can only draft, inspect coordinates/components and run the bundled advisory rules. Each successful draft updates the Blueprint preview. Full cell maps are omitted from its repeated conversational feedback. It has a 16-turn and 240,000-character conversation budget; budget exhaustion is reported rather than treated as completion. A final host-run lint is required before a completed result. Findings remain advisory, and rule failures are returned for review.

`revise` refers to the ID returned by this planner session and must retain the same site. A repeated request at the same site is also treated as a revision, so the completed building is not resampled as terrain. Terrain sampling preserves the last accepted draft, source and advice until another draft succeeds. It seeds a fresh specialist conversation with the existing component tree rather than unrelated earlier chat. There is one active design and a world-local catalog of up to 32 independent designs. `blueprint list` discovers saved IDs and `blueprint load` selects one for inspection or revision. Planner reset cancels an active design; world leave invalidates its lease and clears the in-memory preview; the saved catalog reloads on joining the world. Late model responses cannot write into a new session. Direct authoring through the raw `blueprint` tool remains available only in Codex driver mode; ordinary planners use the specialist.

The **Blueprint designer** dashboard tab is separate from the main LLM transcript. Dedicated `blueprint_designer` observations record the system/authoring prompt, brief, supplied context, progress, assistant replies (including text alongside tool calls), tool calls/results, errors and terminal status, tagged by run ID and blueprint ID. The tab displays recorded runs through the selected timeline moment, including offline JSONL replay. Raw developer exports include these records; normal diagnostic reports include metadata only. The live snapshot remains bounded independently, while recorded entries are capped at 1,000,000 characters each with explicit truncation. Recording follows the dashboard's existing rolling time/byte retention and a 4,096-entry type cap; export a session to retain its transcript beyond that window or a client restart. The worker is not fed the viewer transcript, and its internal conversation is not added to the main planner's transcript. Runs from before recording was enabled cannot be reconstructed.

The first live specialist check on 2026-10-01 used DeepSeek via the normal planner: the parent invoked `design_blueprint`, the worker started with two isolated messages and blueprint-only tools, and the dashboard displayed its separate brief, context, draft call, compiler rejection and next iteration. This validates delegation and visibility, not successful completion of that house. Screenshot: ignored `run/blueprint-evidence/designer-dashboard-live.jpg`. The focused suite passed 9 designer/provider/lease tests and 9 dashboard tests.

Follow-up diagnosis found that the parent had committed 912 cells at `[64,-60,32]`, then started another design at that same site after four fence connection-state mismatches. Resampling cleared the preview and interpreted the roof as terrain. Sampling now preserves the accepted design and same-site designer requests revise the existing design. The live regression check preserved revision 4, all draft/source data and lint exactly across sampling (`run/blueprint-evidence/retention-check.json`). The separate test designer reached its iteration limit; its accepted 599-cell draft is visible but uncommitted. The earlier house was visually confirmed in-world. Focused blueprint tests pass (11 tests).


## Persistence and integrity

Each successful draft and commit is durably written through an atomic replacement on a dedicated storage thread. Persisted source is retained for inspection but is never evaluated on load; the saved semantic tree is compiled with the block registry. Unsupported/corrupt files are reported and block operations rather than being silently replaced. The catalog separates furniture/additions from other blueprint IDs, even when origins match. Save files are bounded to 64 MiB.

A periodic server-thread check reads at most 256 committed cells per scheduled pass. It does not load chunks. Completed checks report matching, mismatch, or unknown with a timestamp, changed-cell count and up to 30 coordinate/expected/actual/owner details. Unknown/unloaded cells are never declared intact. The dashboard lists saved drafts and committed designs separately, with integrity status; the existing source and component inspector work for either selection. This is detection only: no automatic repairs, protection, or planner wake is triggered. Neighbor-derived fence/pane/wall connection properties are ignored; other block/state changes are reported. Container inventory contents are not part of blueprint comparison.

Live persistence/integrity check (2026-10-01): the designer furnished the existing house with 17 blocks, all verified. Two independent designs were saved (a 599-cell uncommitted test house and the committed furnishings). Removing the barrel at `[70,-59,39]` automatically reported one mismatch owned by `furnishings.workstation_corner.barrel_rear_a`; restoring it returned the scan to 17 matching cells. A full client restart reloaded identical catalog, source and semantic tree without model generation. Evidence: ignored `run/blueprint-evidence/persistence-damage.json`, `persistence-reload.json`, and `persisted-catalog.jpg`. All 23 focused blueprint/dashboard tests passed. The old house shell predates persistence and its already-lost original semantic tree could not be recovered; this check tracks its saved furnishings, not that shell. Intentional edits are also reported as deviations; mismatch detection does not infer whether a creeper, player or other cause made the change.

Designer recording verification (2026-10-01): a live DeepSeek inspection-only run generated 14 separate worker observations, including supplied context, tools, final reply and completed status. The raw JSONL export was reopened in the dashboard with Minecraft stopped; the Blueprint designer tab replayed all retained entries independently of the empty main-planner transcript. The backend's raw assistant payload is recorded as `assistant_raw` too, because tool-call responses can omit their text from normalized `replyText`. All 69 focused blueprint/dashboard tests passed, covering export inclusion, historical seek exclusion of future replies, prompt/tool-message recording and cancellation. Local evidence: `run/blueprint-evidence/designer-recording.jsonl` and `designer-recorded-replay.jpg`.


## Deterministic construction (experimental)

Current qualification (details and operation IDs are recorded below):

| Fixture | Mode | Uninterrupted result | Remaining boundary |
| --- | --- | --- | --- |
| `examples/construction-raised-bridge.js` | Creative | 146/146 cells, 83 placements, zero failures/scaffolds, ground exit verified | One flat-ground run |
| Same raised bridge | Peaceful Survival, supplied inventory | 146/146 cells; exact 83-item consumption; zero failures/scaffolds | No Survival breaking or scaffold recovery |
| `examples/construction-two-story-house.js` | Creative | Two fresh autonomous completions across successive revisions: 599/599, zero debt | Route-aware run: 12,101 ticks, 711.03 travel, 53 failures, 24 scaffolds added/removed; more repeats and retry reduction remain open |
| `examples/construction-terrain-platform.js` | Creative, natural sand slope | 60/60, 910 ticks, zero failures/scaffolds | One gentle site; no excavation, water or steep-terrain qualification |
| Same two-story house | Peaceful Survival, supplied materials and tools | Latest-code fresh 599/599, 7,878 ticks, 4 failures, 17 scaffolds added/removed, zero debt; upstairs/outside navigation and zero-edit recheck passed | 10 dirt items not collected; no general completion guarantee |
| `examples/construction-tall-canopy.js` | Creative, superflat | Two latest fresh completions: 16/16, 921/941 ticks, 7 failures, 12 scaffolds added/removed | One simple shape; limited repetition |
| Same tall canopy | Peaceful Survival, natural terrain | Fresh 16/16, zero debt; patch-ranking trial used 12 scaffolds and 8 failures | Different sites confound performance comparison; material pickup incomplete |
| Same terrain platform | Peaceful Survival, natural terrain | Fresh uninterrupted 87/87, 1,275 ticks, zero failures/scaffolds, exact material consumption; entry navigation completed | One new gentle site; no excavation, water or steep-terrain qualification |
| `examples/construction-furnished-pavilion.js` | Creative, superflat | Fresh 88/88, 2,457 ticks, 10 failures, 7 scaffolds added/removed, zero debt | Fences, bottom slabs, opposing stair benches and lantern; Repetition pending |
| Same furnished pavilion | Peaceful Survival, supplied inventory | Two fresh 88/88 completions: 2,458/2,457 ticks, 10 failures each, 7 scaffolds added/removed each, zero debt | Six dirt drops uncollected; one profiled before/after pair |

Example paths above are relative to `prototypes/blueprint/`. The tracked house
source is byte-identical to the house used in the recorded trials. No universal
completion or broad terrain reliability is claimed.

**Historical validation correction:** the earliest Creative experiments were confounded by flight (`abilities.flying=1` in saved player NBT). Their movement/climbing observations are not grounded-construction evidence. Construction now suppresses flight, waits for grounded starts, records flight/grounded state, and fails if flight returns. The fresh grounded and Survival qualifications above were performed after that correction; older partial experiments below retain their original limitations.

`construct_blueprint({revision,x,y,z})` pins the current compiled cells and builds through the ordinary `inspect_world` and `place_block` executors. It returns an operation work ID; use `inspect_work` until that operation reaches a terminal state. No model calls or direct world writes are used by this construction loop. Cancellation leaves already placed blocks in the world. Starting again skips cells whose actual state already matches.

The scheduler favors lower cells, nearby targets, stairs, and cells supporting other pending cells. It rereads geometry after every action and verifies exact block state rather than trusting an accepted receipt. Failed targets stay deferred while untried eligible work remains; retries require intervening verified placement progress. Placement searches a bounded volume of standing positions and predicts block orientation with Minecraft's placement context. Each native placement includes construction bounds. Immediately before interacting, a bounded collision graph checks for a walking exit after an expected-state collision overlay (including paired door/bed parts). Unknown/fluid cells, large drops, and exhausted searches fail closed. The guard uses integer-height stances with projection from supported partial-height surfaces and can conservatively reject some routes. Bounded cut/patch and scaffold plans provide recovery, but do not prove preserved access to every unfinished target. Navigation temporarily disables the in-house navigator's independent breaking and placing through PathfindSettings, restoring the previous settings when construction ends.

Progress includes verified placements, remaining mismatched cells, failed attempts, current semantic component, elapsed client ticks, and distance traveled. Tick duration is not wall-clock duration under lag. Construction currently limits drafts to 8,192 cells, loaded in-bounds positions within 128 blocks, with bounded tick/effect budgets. Existing obstructions, unsupported block states, unreachable work, and owned-air mismatches can prevent completion. General obstruction removal, guaranteed access-preserving ordering, and successful-construction registration with the integrity baseline remain unfinished. Temporary scaffolding and cleanup are implemented and qualified in the bounded cases above; automatic collection of every dropped scaffold item is not implemented. Do not interpret an accepted operation as proof of a buildable design.

Live development evidence (2026-10-01): the 9×7 foundation and four-step, two-wide staircase were placed through normal actions, with all eight stair states checked. Navigation climbed the constructed staircase with Baritone route editing disabled. An initial automatic house run produced 114 successful placement actions and 37 failed actions before cancellation. Its upper floor obstructed stair headroom; the original design is retained, and `construction-house-clearance` widens the stair opening by two cells for the next run. These are partial-build results, not full-house or Survival qualification. Raw evidence is in ignored `run/blueprint-evidence/construction/`.


Access-repair work in progress: construction now has a bounded best-first edit search and an explicit restoration ledger. The first live model searches up to three nearby, blueprint-owned full-block removals from the current reachable interaction pose (12-entry frontier, 32 expansions). It checks hypothetical raycasts after earlier removals and requires replacement material/harvest capability outside Creative. Normal breaking tools execute the result; changed preconditions abort the plan. Repair debt is included in work progress and terminal details and is cleared only by observed matching state. This is not yet a general scaffold solver. The restoration ledger is now journaled durably as described below. A strict placement exit check remains until recovery plans can certify placement-plus-cleanup sequences.


Live access-repair check (2026-10-01): the corner trap was reproduced through ordinary glass placement. The first bounded search failed because the collision model treated the closed, hand-openable oak entrance door as a permanent barrier. It now checks both door poses, consistent with `NavigationDoorInteraction`. After that fix, the search selected one oak plank at `[103,-53,38]`, broke it normally, retained its semantic restoration debt, and the player moved from the corner `[104,-54,38]` to the staircase near `[97,-55.5,36]` while construction resumed. This proves opening-and-egress progress, not completed restoration or a finished house. Evidence: ignored `run/blueprint-evidence/construction/door-recovery-*.txt`.

The test design required a second stairwell correction: with route edits disabled, Baritone failed to descend until the floor opening extended one additional row over the first stair. The successful descent is `JOB:job-48b20d3d-1ce3-4c0b-b8fa-ef244a971c10`. Earlier saved designs remain intact. The latest test copy also fixes two hanging lantern states and puts the upper lantern on its floor instead of unsupported air.


Grounded scaffold probe (2026-10-01): operation `85bfb1cb-8c4e-4147-aacb-afa20a7cad10` verified ten more blueprint placements and two oak-plank platforms with `initiallyFlying=false` and `flying=false`. It stopped after 3,337 client ticks, 15 failed attempts and 414 blocks of travel, with 108 mismatched cells remaining. This is useful progress but poor efficiency, not a completed house. The platform search is limited to two nearby edits and default-state full-block targets. Automatic cleanup is covered by the state-machine test but was not reached live. Both platforms (`[97,-55,36]`, `[104,-60,30]`) were removed through normal tools before restarting; the upper cell was reread as air and the exterior removal completed successfully.

The stop exposed another conservative navigation mismatch: the construction graph allowed only one-block descents, while normal navigation reached the upper floor from the wall without route edits. The graph now allows at most three-block descents and checks the entire landing column, retaining a one-block ascent limit. This does not make reachability symmetric: a safe drop may not have a climbable return route. Tests cover that distinction and reject larger drops. The next grounded run (`235391a1-aec5-45e4-ab98-c3be1d01e2d7`) continued without the prior upper-wall failure, placing 20 blueprint blocks and two platforms before deliberate cancellation to improve scheduling. It still spent 352 blocks of travel and 12 failed attempts; 88 cells remained mismatched. Both platforms were removed and reread as air before restart (evidence: `descent-terminal.txt`, `descent-cleanup.txt`). This does not establish completion or automatic cleanup.


Scheduling follow-up: plain default-state cubes that are visible and within immediate interaction range now rank ahead of work requiring travel. Successful access edits no longer erase every failed-target deferral: only targets made immediately placeable are re-enabled. Exact placement-state and escape validation remain in the ordinary interaction executor. This ranking is a bounded hint, not a global route optimizer; rotated/non-cube blocks still use the general executor.


The subsequent ranking run (`21a583ef-76b9-483d-90f0-957f8c625409`) placed 23 blueprint blocks and four temporary platforms with flight disabled. It was deliberately stopped after 25 failures and 374 blocks of travel; 68 cells remained mismatched including the platforms. All four platforms were manually removed through normal tools and reread as air before restart (`ranking-terminal.txt`, `ranking-cleanup.txt`). This is not an automatic-cleanup success. Scaffold plans now retain their verified working stance; after the final platform edit is observed, the program explicitly navigates there and waits for success before selecting more work. A focused state-machine test checks that handoff and that cleanup debt survives it. On the new navigator, four platform handoff navigation jobs succeeded in the subsequent partial run; this still does not establish completed work or cleanup.


Upstream integration (2026-10-01): rebased onto `upstream/dev` at `ee977409`, retaining the dashboard's Attention and Blueprint views. Blueprint geometry uses Mojang mappings. Construction scopes route-edit settings through the in-house navigation settings, and placement uses the shared actuator and movement leases. The supplied conditional-sneak patch is integrated with exact-state construction checks. Earlier Baritone live results are historical evidence only; they do not qualify the new backend. Repair journaling is written asynchronously before repair effects and restored for the same compiled build identity; focused tests cover persistence and dispatch ordering, while restart behavior still needs live validation.


Post-rebase live probe (2026-10-01): operation `710961df-619e-4944-861d-475c3d5449de` restored all four saved scaffold debts after a client restart, including semantic owners and required air states. It was deliberately cancelled after 3,588 ticks: three verified blueprint placements, three new platforms, 24 failed attempts, 515.54 blocks traveled, and 59 mismatched cells. Flight remained disabled. Seven platform removal obligations remain journaled; no automatic cleanup success is claimed. Evidence: ignored `run/blueprint-evidence/construction/connected-terminal.txt` and `connected-*.txt`. Ranking connected stances first and disabling direct forward approach did not resolve the efficiency problem.

The probe exposed inconsistent reach geometry: scaffolding evaluated eye-to-face distance, while actual placement used feet-to-face distance and hypothetical stances used voxel-center distance. Placement now uses actual eye position, and candidate stances use the current pose's eye height, with the same conservative 4.5-block bound. A regression covers reachable overhead faces, unreachable low faces, and standing versus sneaking eye heights. The focused regression failed before the change and all 116 selected tests passed afterward. Live operation `1292aebf-add1-443b-a786-34473dcbcf00` restored seven scaffold obligations and, by 3,230 ticks, verified two further blueprint placements with flight disabled. It still had 17 failed attempts, 465.76 blocks of travel, and 58 mismatches; this correction does not establish efficient or complete construction. A 300-tick player/action trace is saved at ignored `run/blueprint-evidence/construction/eye-motion.jsonl` for further stance/navigation diagnosis.


Tick-trace diagnosis (2026-10-01): the eye-reach run was cancelled at 8,294 ticks with ten verified placements, 54 failures, 1,152.58 blocks traveled, and 52 mismatches. The user's observed roof collisions are captured in `roof-jump-motion.jsonl` (600 ticks) and `roof-jump-geometry-motion.jsonl` (300 ticks with geometry). At tick 6665 the player had landed at `[103.506,-53,34.959]`, supported by the edge of the destination platform at z=35. ASCEND still considered the destination cell unreached and requested another jump. Tick 6666 rose only to y=-52.8 and collided with the roof underside at y=-51. The same unnecessary second jump occurs at tick 6655 one level lower. These impacts coexist with successful ascents; they do not alone explain the repeated traversal of unreachable partial routes.

ASCEND now compares the remaining rise against the actual landing surface (including partial-height blocks), and keeps horizontal steering without another jump when already at landing height. A regression reproduces the recorded edge landing and retains the initial necessary jump. All 78 navigation-core tests pass. Live validation of the change is pending. The compact trajectory is exported as `roof-jump-trajectory.csv` beside the raw evidence.


Landing-height follow-up: live operation `a506c6d7-8019-472b-bd93-789005413cfb` was cancelled after 2,573 ticks with no new blueprint placement, 14 failures, 350.27 blocks traveled and 52 mismatches. It is not an efficiency success. In `landing-motion.jsonl`, ticks 1526–1529 show the player landing at y=-53 and continuing horizontally without the old second jump. Tick 1517 exposes a different launch problem: the centre has entered z=34, but the body still overlaps the lower roof in z=33. A sideways collision triggers a premature jump, clipped after 0.2 blocks. At tick 1520, after moving forward, the next jump succeeds. The second regression includes the recorded horizontal-collision flag and fails before the fix. ASCEND now checks the first jump impulse's head clearance over the actual 0.6-wide footprint before issuing jump, continuing horizontal movement while clearing an overhang. All navigation-core tests pass; live verification of this second correction is pending.


Launch-clearance live check (2026-10-02): `launchclear-motion.jsonl` completed a 900-tick trace under operation `2aa78f8c-bb52-4499-9e38-d536cb7a8d87`. It records 22 jump-input ticks and zero jumps clipped by a vertical collision on their launch tick, including repeated ascents through x=103, z=33–35. The original 600-tick trace had six such immediate impacts among 21 jump-input ticks; the landing-only 900-tick trace had two among 15. These windows cover different movement and are not a throughput benchmark. Later apex contact can still occur during successful ascents. The first 747 ticks of the latest operation placed no new blueprint blocks and had seven failed attempts, so unreachable placement routes remain unresolved. All 79 navigation-core tests pass. Construction remains active with its repair journal intact.


Placement route selection now asks the navigator for a complete route before it starts moving. Other navigation retains partial/segmented paths. A construction stance that yields an incomplete search is rejected with `incomplete_stance_route` and its search reason; this is not proof of global unreachability. This prevents repeated physical approaches to nearby roof stances whose searches only reach an interior platform. The no-edit construction policy and exact block-state checks remain in effect. Live performance validation is pending.


Complete-route live check: operation `89c43f83-8204-45e7-9288-53aecc27d8c1` failed explicitly after 1,604 ticks, 21 rejected placement attempts, zero travel, zero placements, and 52 mismatches. It avoids physical partial-route retries but does not solve access. Terminal evidence: `complete-route-terminal.txt`.

Towering implementation: the bounded access planner can now propose up to three underfoot scaffold placements from a reachable origin when they expose a pending cube and retain an exit. Materials remain inventory-backed (dirt, cobblestone, then oak planks). Each pinned block is journaled before its `place_block(tower:true)` effect. The action centres on a full support, checks jump headroom, jumps normally, waits until feet clear the target, uses the shared block interaction actuator, and requires exact state plus grounded landing. No structure spawning or flight is used. Cleanup considers owned scaffolds highest first and may remove its current support only for a one-block descent onto verified full support with an exit. The state machine already waits for grounding before the next edit. Focused tests cover jump timing and tower-journal dispatch; live towering and cleanup qualification remain pending.


The first tower-enabled house run (`eaf5ad90-09a2-4f07-b6df-ae86342fd69f`) failed after 965 ticks with 21 rejected attempts, zero travel, zero tower blocks, and 52 mismatches. No useful tower was selected, so it did not exercise the new action. The planner now removes origins without full support, intended air, or first-jump headroom before applying the 24-origin limit. Empty plans report reachable/viable/sampled origin counts, visibility matches and exit rejections. Focused tests pass after this change; the follow-up live probe is pending.

The first live tower run (`tower-origin-terminal.txt`, operation
`affd901b-56ed-41ee-a013-2cc63b196f0d`) placed three oak-plank access blocks
under the jumping player at `(104,-60..-58,31)` and then one house block.
Flight remained disabled. The 338-tick run travelled 27.18 blocks, recorded
six failed attempts, and left 51 blueprint mismatches. All three tower blocks
remain recorded as required-air cleanup debts; this is placement evidence,
not completed-construction or teardown evidence. Dirt was not supplied in this
fixture, so the inventory fallback selected oak planks.

The subsequent tower plan exposed a job-identity bug: a newly queued
`navigate_to` consumed the previous `place_block` failure before navigation
started. Goal-job result consumption now checks the primitive task ID, matching
the protection already used by other primitive jobs. A regression test reproduced
the failure before the change; all 48 focused job-runtime, construction-program,
and tower-policy tests passed afterward. Live restart verification follows.

After restarting with the job-identity fix, `tower-resume-terminal.txt`
(operation `b69af595-f57a-4166-a348-acc1d5653bdc`) restored the previous debts,
extended the tower three more blocks, and placed 39 house blocks in 1,546
ticks (134.43 blocks travelled, 18 failed attempts). Seven new access blocks
were placed in that run. The 900-tick `tower-resume-motion.jsonl` contains
zero flying frames and grounded tower landings at ticks 1226, 1250, and 1274,
with feet at -56, -55, and -54 respectively. Thirteen blueprint mismatches
and 20 cleanup debts remained; the operation failed while unable to reach a
remaining glass target. No scaffold removals occurred in that run.

Construction can now request journal-owned scaffold cleanup as a fallback
when no access repair is available, even before all final blocks are present.
This permits descending from temporary work positions to regain access to
unfinished lower work. Cleanup still requires verified ownership, a reachable
interaction pose, and the post-removal escape check. A regression test verifies
cleanup followed by resumed placement, and that cleanup remains necessary for
completion. This does not yet prove arbitrary escape-and-repair recoverability.

The first cleanup run (`tower-cleanup-terminal.txt`, operation
`9c6087de-9154-4974-aba0-e41a07c570ac`) removed the owned scaffold at
`(104,-52,31)` normally, then exposed stale grounding state. The tick trace
shows `onGround=true` at tick 1303 with feet -51 after removal, airborne descent
at 1304–1307, and landing at -52 at 1308. Replanning consumed the stale flag at
1303 and failed before the descent. Construction readiness now requires actual
collision support immediately below the player's feet in addition to onGround,
so underfoot removal waits for the next supported stance.

With the support check, `tower-support-terminal.txt` (operation
`1f147036-f8bf-439e-be6e-03819ce639b5`) removed 18 scaffolds, placed two new
underfoot access blocks at the other corner, and placed the remaining window.
It stopped after 888 ticks with four blueprint mismatches and three unresolved
cleanup debts. Two cleanup defects were identified: break reach used feet while
the access planner used eyes, and scheduling restoration replaced the ledger's
owned intermediate material with air even when the break failed. Break reach
now uses eyes; restoration preserves the recorded intermediate material until
world observation confirms completion. The ledger regression failed before
that change. The three affected live records were recovered from their original
material entries in `tower-resume-terminal.txt`; the pre-recovery journal is
saved as `pre-ledger-recovery.json`. No world blocks were edited for recovery.

Final scaffold retry: `tower-ledger-terminal.txt` (operation
`af98b24c-1c4e-4672-990e-f2a146638fa9`) removed the last three recorded blocks in 259 ticks,
travelled 53.83 blocks, and had zero failed attempts. Runtime cleanup debt was
empty. The operation still reports failure because both entrance-door halves
are open while the saved schematic expects closed; this is not a completed
exact-state house run. The live client was stopped afterward to release resources.
A subsequent durability fix persists reconciled cleanup even when planning the
next target fails, rather than leaving the last already-removed block in the
on-disk journal until the next run reconciles it.

Door finalization now runs after placement and scaffold cleanup. It accepts only
hand-openable, unpowered doors whose observed state becomes exactly the requested
state by toggling OPEN. One ordinary `use_block` on the lower half is followed by
verification of the whole blueprint; incompatible occupied states are not silently
accepted. A regression test failed before finalization was implemented and passed
afterward, together with construction and repair-ledger/store tests.

Live `door-finish-terminal.txt`, operation
`0b00f584-a97c-4134-ad67-3a197251f4ec`, completed successfully in 18 ticks with
one adjustment, no retries, no travel, and all 599 cells verified. The player was
grounded outside the entrance at `(100.572,-60,30.928)`. Runtime repair debt and
the persisted repair journal are both empty. This qualifies completion of the
incrementally repaired house fixture, not an uninterrupted from-empty construction
run, Survival qualification, or broad architectural coverage.

A from-empty attempt at origin `(128,-59,32)` (`fresh-house-terminal.txt`,
operation `198c180b-0b96-4873-a30a-df3d34d02669`) stopped after 135 placements,
3,637 ticks, six failed attempts, 291.12 blocks of travel, and no scaffolds.
It is a failed fresh-build trial. The trace `fresh-house-upper-motion.jsonl`
shows a grounded edge pose `(137.020493,-58,32.362084)` with the body extending
left to X=136.720493. Starting the escape graph at the center's cell X=137
lost the support under X=136 and returned an empty reachable region.

Live escape queries now project the actual body to a standing grid cell overlapped
by its feet, only if the entire centering sweep is collision-clear. Explicit
hypothetical starts retain their existing semantics. Tests cover the recorded
pose, a wall obstructing centering, and a distant unsupported candidate; focused
escape and construction tests pass. This remains conservative for fractional
standing heights and is not a full continuous-pose reachability model.

The live resumed edge trial (`fresh-edge`, operation
`da81a0cb-57e8-4a9d-83ec-cdf8957ff2d8`) passed the formerly failing starting
pose and placed 17 additional blocks within its first 508 ticks, with two failed
attempts and no scaffolds. It was still running at this checkpoint; do not treat
this as a completed build. Its running driver handle is 24075 and probe handle
80221; inspect the same operation before restarting or issuing another build.

The resumed edge trial ultimately failed after 8,216 ticks: 223 additional
placements, 76 failures, 609.10 blocks travelled, 18 scaffolds placed (eight by
towering), no cleanup yet, and 42 mismatches. The terminal history shows six
`missing_suitable_tool` failures for white concrete despite Creative mode.
The break-only access search then exhausted its bound on the rooftop and threw
before other recovery strategies could run.

Creative `break_blocks` now skips Survival harvesting-tool preparation and still
uses the normal break actuator. An exhausted break search tries scaffold access
and then returns no plan so owned teardown remains available. Cleanup is no
longer charged against the 32 speculative-access-plan limit; verified owned
blocks are finite. Failed cleanup positions are deferred until verified geometry
progress, preventing an unlimited retry loop. A 40-scaffold regression failed
under the old shared budget. These recovery changes require live qualification;
the interrupted build remains an unsuccessful from-empty trial.

Live recovery `fresh-recovery-terminal.txt`, operation
`5f029d5d-8bb5-4f69-99e9-21477c5eba79`, placed 22 blocks and removed 16
scaffolds in 2,232 ticks, but still failed with 17 mismatches and five debts.
It travelled 120.45 blocks and recorded 70 failed attempts. Two white-concrete
breaks succeeded through the normal Creative break path; no missing-tool
failure remained. Cleanup continued at the speculative plan limit of 32.

That run exposed deferred work that stayed suppressed after teardown because
its retry epoch counted only final placements. Verified removals now advance
the retry epoch too, allowing reopened lower work (including non-cube targets)
to be reconsidered. Added scaffolds retain the narrower immediately-placeable
reset; the existing unrelated-scaffold regression caught and rejected a broader
reset. A new test covers failed construction, successful scaffold removal, and
resumed construction without an intervening final placement. Live qualification
of this retry change is still pending.

`fresh-cleanup-retry` ended with 14 mismatches, five debts, three placements,
seven scaffolds placed/removed, and 97 failed attempts. Deferred retries now
require a currently reachable placement pose with face visibility/reach and
post-placement escape. Reachability is shared per environment tick; the executor
still owns exact interaction and route validation. Focused program, repair, and
escape tests pass. The subsequent unchanged-site `fresh-retry-ready` run failed
with 11 attempts and no geometry progress; this is not a controlled speedup
comparison. Its diagnostic showed that the three-block tower horizon could not
reach any of the remaining high roof targets from the ground.

Tower search now considers the target work height, bounded by inventory and 32
blocks per macro. `fresh-tall-tower-terminal.txt`, operation
`a43b83b7-a23e-407a-a7b1-96f023b58fd7`, climbed seven blocks, placed four final
blocks, placed eight access blocks total, and removed eight access blocks in
648 ticks (48.88 blocks travelled, 11 failed attempts). It still failed with ten
mismatches and five older repair obligations. This validates taller access in
this case, not a complete fresh house. Remaining stairs include occluded targets
behind completed walls. Connected players currently try scaffolds but do not
search temporary break-and-patch access to such targets; that recovery mode is
still required. The client was stopped after this terminal result.

The next change adds bounded temporary break-and-patch access when an exterior
escape already exists but remaining work is occluded. It requires a newly usable
placement pose and post-placement escape, records the opened work target, and
prioritizes that target before restoring the obstructing block. The regression
`openedAccessIsUsedBeforeRestoringTheObstructingWall` caught premature patching;
focused construction, access-search, and repair tests now pass.

Live qualification of this change has not run. Two client launches crashed on
world join in JourneyMap `JmUI.getQuarterMaxScale` because the window monitor was
null. Neither started construction. The crashed clients stalled during shutdown
and were stopped to release resources. The prepared `fresh-target-access` probe
must be run only after a successful world join; no result is available for it.

With a display-awake assertion held through launch, world join succeeded. This
supports a display-sleep explanation of the JourneyMap crash but does not prove
it. `fresh-target-access` (`ef9acbea-8442-4a69-814e-4772544f64b0`) subsequently
failed after 2,912 ticks: 19 placements including patches, 26 failures, 353.94
blocks travelled, 24 scaffolds placed (23 towered), 23 removed, two remaining
structure mismatches and five debts. Temporary openings reached previously
occluded work; overall completion and cleanup remain unproven.

The tick traces and full rolling recording are preserved under
`run/blueprint-evidence/construction/fresh-target-access-*`. Correlated action
history shows tick 1297 placing a window from a tower, then attempts at a distant
roof and ground-floor stair before extending the same tower for the adjacent
window. Later openings were patched after unsuccessful stair attempts, followed
by another tower. These are observations of this partial-site trial, not a
controlled performance comparison.

The selector now retains an anchor at the last verified final placement or
access destination. It considers usable current-stance work first, including
geometry-gated retries; then reachable candidates within two Manhattan blocks
of the anchor and six blocks of the player; then global untried/retry work.
Untouched work wins within each tier. Failed local work yields, and executor
reach, exact state, and escape checks remain authoritative. Progress reports
`selectionReason`, `workAnchor`, `localSelections`, and `workAreaSwitches`.
Two locality regressions failed before this change and now pass, alongside the
unreachable-frontier fallback and existing construction/repair tests. Live
qualification on a fresh site is pending.

Fresh locality trial `7b24698b-c912-4fbe-a2d3-f7c6a58b51a1` started at
`[160,-59,32]` after an air-only query matched all 819 cells in the bounding box.
It was cancelled after a confirmed stationary break-aim stall: 17,574 ticks,
394 placements including patches, 97 failures, 649.40 blocks travelled, 31
scaffolds placed (23 towered), none removed, 17 mismatches and 32 journal debts.
This is an unsuccessful uninterrupted fresh trial, not completion evidence.
The early recording shows 57 of 114 placement-target transitions exceeding two
Manhattan blocks. Current-stance selection now also uses anchor distance before
the old global score; a regression failed before that refinement and passes now.
The refinement was not loaded during this trial.

The final 200-tick trace held exactly the same position and angles while
`BlockBreak` reported `waiting_for_aim` for `[165,-59,32]`. A geometric regression
using the recorded eye position confirms that the upper wall block occludes
the lower block's centre but leaves a front-face aim point visible. Block camera
aim now selects a visible point inside the outline, including face centres.
`break_blocks` also bounds missing target-ray hits to 40 ticks before returning
`break_aim_timeout`; previously its timeout started only after mining began.
Focused camera, break, construction and repair tests pass. Live qualification
of this aim correction is pending. The through-roof and stall recording exports
are marked truncated by retention; the early export and explicit tick traces
are separate evidence and must not be described as one complete recording.

Live close-wall qualification after restart retained the exact saved position
`(165.3667829332416,-59,33.58445256007759)`. Both the target at `[165,-59,32]`
and its upper neighbour were verified as oak planks. Normal `break_blocks` job
`job-4939bd27-d2a1-4d96-91d5-8ae0382d0307` queued at tick 2109, ran at 2110,
and succeeded at 2111. The post-read found air only at the target; the upper
neighbour remained oak. The 200-frame trace has one unchanged player position.
This validates the visible-face aim correction for the recorded incident, not
house completion. The existing durable journal retains that block's required
oak state for later patching. Evidence uses the `close-wall-*` prefix. All 50
focused camera, break, construction and repair tests passed, and diff whitespace
validation passed. The within-stance adjacency refinement still needs a fresh
live order comparison.

Resuming the x160 house exposed repeated opening/patching with no mismatch
improvement (`locality-resume`, cancelled at 4,210 ticks, 119 failures). The
escape check in the interaction executor still represented a proposed door as
two stone cubes, while access planning used its actual state. A shared placement
overlay now preserves expected collision states and paired door/plant/bed parts;
full blocks remain full obstacles. Two overlay regressions failed before the
change; 83 focused escape, construction and placement tests then passed.

`state-overlay-resume` (`062fef59-d6b9-4c7e-8672-c9384ec6e2a7`) placed the entrance
with both correct closed-door halves, verified through `inspect_world`. It
eventually failed at 4,294 ticks with four mismatches, six scaffold debts,
85 placements including repeated patches, 138 failures, 132.76 travel, one
scaffold added and 26 removed. Repeated stair approach failures dominated:
access search accepted side-on visibility without checking the desired facing,
and limited cuts to the current eye position instead of reachable stances.

Access search now enumerates reachable break stances for its bounded candidate
set and records navigation stances with each cut. Connected players retain a
post-cut escape check; cutting one's own support is excluded here (owned teardown
has its separate controlled-drop handling). Stair approach filtering follows
vanilla facing and half selection; neighbour-dependent shape/waterlogging remain
the executor's exact-state responsibility. The two approach regressions failed
before the filter; focused tests pass after it. The `reachable-access-resume` recovery ended after 172 ticks with three
placements, one failed attempt, 22.30 blocks travelled, two remaining cells and
six scaffold debts. A subsequent live read confirmed both bottom stairs exactly
matched the required south-facing, bottom-half, straight, dry state. This remains
incremental recovery evidence, not a fresh-house success.

Cleanup now samples visible block faces and, when direct removal is unavailable,
can build a bounded exterior access tower to an owned stranded scaffold. The
queued sequence removes that original debt before dismantling the entire new
tower top-down with one-block supported drops. Every edit uses normal navigation,
placement/break tools and the existing durable ownership journal. Search checks
at most 64 nearby exterior origins, samples 24 viable origins and caps tower
height at 32 blocks and available stock. This currently handles straight vertical
access only; arbitrary cleanup routes and a ground-safe exterior anchor remain
unproven. The sequence/ledger regression and 36 other focused tests pass; live
validation is recorded under `cleanup-access-resume`.


The first cleanup-access live trial (`d51043d3-1d2d-4a58-96a8-de72346c01ce`)
was cancelled after repeated ineffective short towers. It removed three original
debts but incurred 62 identical `target_out_of_range` failures, 74 temporary
placements and 77 removals. The 5,215-observation recording is not truncated.
The planner accepted a reachable face while the break executor gated on centre
distance. Cleanup/access visibility now also checks that centre-distance gate,
and each original debt gets at most one new access-tower plan per operation;
removing the new tower no longer permits regenerating the same failed macro.
The recorded geometry is pinned by a reach regression. Further live evidence
uses the `cleanup-range-resume` prefix.

`cleanup-range-resume` (`c1c1468a-d7f0-4af9-b5a1-85ad5e7cfb30`) succeeded
in 599 ticks with zero failed attempts, 57.44 blocks travelled, nine tower blocks
placed, thirteen scaffolds removed and one door adjustment. All 599 blueprint
cells matched and the on-disk repair journal contains zero debts. Its exported
recording contains 814 observations without truncation. This qualifies the
specific cleanup recovery, not uninterrupted fresh construction, broad terrain
coverage or Survival. The latest focused suite passes 38 tests.

Fresh x192 trial `fresh-locality-cleanup`
(`c64fd56c-a8ab-4060-b174-ade968038cba`) failed after 6,781 ticks: 219 placements,
174 mismatched cells, 45 failed attempts, 344.34 blocks travelled and no scaffold
edits/debt. The early recording shows 102/123 consecutive target transitions
within Manhattan distance two. This is locality evidence only; the house did not
complete. Early/upper recordings are complete; the terminal recording is truncated.
Eleven early escape rejections correlated with a stationary half-height stair
pose at `(193.4184,-56.5,34.7440)`; final repair search also started at Y=-56.5 and
reported zero reachable stances. Integer-only initial projection was rejecting
that supported pose before any route search. The adapter now connects supported
fractional-height poses to overlapping integer stances by collision-checked
vertical, horizontal and landing sweeps, allowing at most a 0.6-block step.
Actual support is required; unsupported airborne poses and obstructing ceilings
remain rejected. The recorded pose is covered with vanilla stair collision boxes.
Live qualification of this change uses `halfstep-resume`.

`halfstep-resume` (`bddf8f12-06bb-457c-9c36-f829ca3ee1eb`) failed after 248 ticks
with no travel/final placements, three failed attempts and one owned platform
block. Escape search could now discover a platform, but navigation still rejected
the starting stair cell: the live navigation diagnostic reports start
`193,-57,35`, `NO_ROUTE`, one expanded node. Its 582-observation recording is
complete. The native navigator now attempts a bounded ordinary centring step
before searching when the current stair cell is invalid but its top is a valid
stance within 0.6 blocks. It requires grounded support, a clear lift/horizontal
sweep, issues no jump or block action, and times out after 40 controlled ticks.
This is a local start recovery, not a claim that the integer graph models every
partial-block route. Live validation follows under `nav-start-resume`.

The start-recovery change passes 45 focused tests. `nav-start-resume`
(`87b2220c-8e49-456d-9258-44e9291a90e3`) resumed at the exact saved
`(193.5630,-56.5,35.0824)` pose and moved through normal controls, then made
19 verified placements by tick 1,353. The first 1,200 traced frames contain no
Creative flight. It was still running with ten failures, three new tower blocks
and four repair debts at that observation; full completion/efficiency are not
established by this recovery.

The same `nav-start-resume` operation subsequently succeeded in 8,183 ticks:
180 verified placements (including repairs), one door adjustment, 54 failures,
525.62 blocks travelled, 24 scaffolds added and 25 removed. All 599 cells matched
and the x192 construction journal (`f7c4dbd7...c5424c.json`) is empty. This remains
incremental recovery from an unfinished house, and its travel/retry overhead is
substantial. The next unchanged-code, empty-site house trial is `fresh-halfstep`
at `[224,-59,32]`; all 819 cells in its volume were verified air beforehand.

The next construction fixture is
`prototypes/blueprint/examples/construction-raised-bridge.js`: two abutments,
a raised span with an open underpass, guardrails protecting a named central
walkway, and east/west stair approaches with solid supports. It has 146 cells
(83 solid, 63 air): 30 stone bricks, 27 oak planks, 18 dark oak planks and eight
oak stairs. Its offline compile and applicable advisory rules pass without
findings; the offline adapter is synthetic and this is not construction proof.
It uses the same supplied Creative inventory as the house and will be tested
through `construct_blueprint`, never the direct-block commit operation.

The unchanged-code `fresh-halfstep` house trial
(`7aa20fb3-97ea-4d63-a653-76aea8ac3405`) failed at 13,915 ticks: 375 placements,
28 mismatched cells, 118 failures, 663.76 blocks travelled, 28 scaffolds added,
none removed and 29 journal debts. No uninterrupted house success is claimed.
The final reachable region was five cells on the front roof ledge at Y=-52.
The bounded cut candidates were ranked against unfinished roof cells even though
the player was trapped; removing all selected candidates still did not connect
an exit. Trapped-state cut selection now ranks by player proximity, reserving
unfinished-target proximity for already connected players. The recorded roof
pose/candidate ordering has a focused regression. The terminal recording is
truncated; earlier trace/export chunks remain alongside it. The native search
still has depth/beam/expansion limits, and the operation-wide repair budget is an
additional unresolved scaling limit. Live recovery evidence uses `roof-escape-resume`.

At the recorded ledge, `roof-escape-resume`
(`bc3066ed-e0f6-46f1-80dd-5224ad60f82e`) found a one-block cut at
`[225,-51,32]` after five expansions. The normal break/navigation sequence let the
player leave the strip and continue on the roof. The first recording contains
1,045 observations without truncation. At tick 1,362 it was still running with
17 placements, 14 failures, 16 mismatched cells and 36 repair debts; this confirms
the specific escape recovery, not completed construction. The focused cut-search,
escape and construction tests pass after the ranking change.

That recovery ultimately failed at 6,567 ticks: 42 placements, nine mismatched
cells, five repair debts, 54 failed attempts and 474.42 blocks travelled. It
removed 42 scaffolds after adding 19, but did not finish cleanup. The terminal
recording contains 6,782 observations without truncation. Repeated door-state
approach failures also caused cut/patch churn. Inspection found that candidate
stances could validate visibility at an edge while predicting state at the face
centre, and live hit selection accepted the first visible point before checking
its expected state. Both now sample one point that satisfies visibility, reach
and predicted state together; the actual post-aim state check remains in place.
Use-block interactions retain their existing behavior. The 72 focused interaction
and construction tests pass. This is not yet live qualification of the change;
that retry uses `state-hit-resume`.

`state-hit-resume` (`e06a7c7a-7506-4d70-9a73-e5b16e68d28e`) failed after 112
ticks without movement or placements: nine mismatches and five debts remain.
The three final-target attempts were two stairs and a lantern, all rejected for
lack of an accessible stance. No door attempt occurred, so this run does not
qualify the door click-point fix. Its terminal export has 208 observations and
is not truncated. The player remained grounded outside at approximately
`[227.454,-60,39.518]`; reachable-stance/navigation diagnosis remains open.

A controlled navigation probe walked from the back exterior to the front and
then through the doorway, without editing blocks. Retrying from inside
(`inside-access-resume`, `36650a79-25c8-4b6a-972a-8c30ae47c8d5`) succeeded in
682 ticks: all 599 cells matched, zero repair debts, five placements including
wall repairs, one door adjustment, three failures, 81.27 blocks travelled,
and all five remaining scaffolds removed. The planner itself cut and restored
the wall to place the two south-facing stair bases. This is assisted recovery:
the useful entrance approach was supplied externally and has not been discovered
autonomously. The terminal export has 1,220 observations without truncation.

Inspection also found that work-platform planning excluded all non-full-cube or
non-default-state final blocks. Access targets now include directional stairs
and lamps; upper paired halves remain excluded because lower placement produces
them. Both short-platform and tower goals pass the required state through the
existing approach checks. Seven scaffold and 25 construction tests pass. Exact
placement remains authoritative in the executor; broader access eligibility does
not prove that every block-state variant has a feasible approach.

The raised bridge then passed its first uninterrupted Creative trial at
`[260,-60,64]`: `raised-bridge`, operation
`0cfbd616-ab57-4ca7-9e24-2ec892787fcb`. Before construction all 255 cells in the
fixture bounding volume were verified as loaded air. It placed all 83 solid
blocks and finished with all 146 blueprint cells matching, zero failed attempts,
zero scaffolds, zero repair edits/debt, and 132.90 blocks travelled in 1,803 ticks.
The terminal recording contains 1,523 observations without truncation; all 83
successful placements are present. Of 82 consecutive placement pairs, 70 were
within Manhattan distance two (largest gap eight). The player was grounded and
not flying at completion. A subsequent normal navigate_to to ground position
`[274,-60,65]` succeeded, confirming an exit for this fixture. This qualifies one
flat-ground Creative bridge, not Survival, terrain variation, or uninterrupted
house construction. The trial did not need scaffolds and therefore does not
live-qualify the broadened scaffold-target eligibility.

The same bridge passed uninterrupted Peaceful Survival at `[300,-60,64]`,
operation `8ea82ff5-ebc5-4202-9a6f-c428d9132ee3` (`raised-bridge-survival`).
The driver-only prepare operation confirmed `creative:false`; inventory was
preserved when switching modes. All 255 bounding cells were verified loaded air
before construction. In 1,816 ticks it placed 83 blocks with zero failures,
scaffolds or repair debt and 141.01 blocks travelled. All 146 blueprint cells
matched. Inventory fell from 64 each to 34 stone bricks, 37 oak planks,
46 dark oak planks and 56 oak stairs: exactly the 30+27+18+8 required blocks.
Unrelated materials were unchanged. Final inventory inspection placed the player
on ground at `[302,-60,67]` with 20/20 health. The terminal recording retains
1,415 observations without truncation. This validates supplied-material placement
for this fixture; Survival breaking, scaffold recovery, hazardous terrain and
the harder house remain unqualified. The mode-option build and 29 focused service
and construction tests passed before this run.

A fresh Creative house trial with the click-point and expanded access-target
changes is now `fresh-state-access`, operation
`472a32ca-fefc-4675-812e-508b85de3034`, origin `[336,-59,32]`. The complete 819-cell
bounding volume matched loaded air in a find_blocks query before starting.
At 607 ticks it had placed 41 blocks without failures; it is ongoing, not a
completed result. No manual approach or recovery has been supplied to this run.

`fresh-state-access` ended FAILED at 9,398 ticks with `EOFException`, 242
placements, 153 mismatches, 53 failed attempts and 22 temporary-block debts.
The operator ran Gradle tests concurrently; their remapJar dependency rewrote
the jar used by the production-mapped live client. A subsequent visual probe
also failed loading ViewCaptureResult from ZipFile with EOFException. This is a
trial invalidated by live-artifact replacement, not clean construction evidence.
Do not run jar/remapJar-producing builds against an active client; finish/export
the live trial and stop it before building. Early recording: 4,913 observations,
not truncated. Middle export: 7,055 observations, truncated.

The pending repair-budget change replaces the 32-plan lifetime cap with a cap
of 32 plans without new final-block progress. Initially correct positions and
previously completed positions are remembered; patching them cannot renew the
budget. Temporary blocks, cleanup and door adjustments do not renew it either.
A simulated 40-block build requiring one access repair per block reproduces the
old cutoff with nine blocks remaining and passes with the change. Repeated wall
cut/patch churn still terminates. All 27 construction and three ledger tests pass.
This budget change is not yet live-qualified.

The invalidated trial's terminal export contains 8,650 observations and is
truncated. After stopping and restarting the client with the tested budget
change, recovery `progress-budget-resume`
(`4dfb1508-7f95-45f9-98f3-e01d749798e4`) began at the same x336 structure. At
599 ticks it had placed 26 blocks, with three failures, 128 mismatches and
23 debts. This recovery is ongoing and is not an uninterrupted fresh-build result.

`progress-budget-resume` succeeded at 7,705 ticks: all 599 cells matched,
zero repair debt, 164 final/patch placements, one door adjustment, 31 failures,
619.82 blocks travelled, 32 scaffolds added and 54 removed (including the 22
inherited debts). It used 57 total repair plans; the no-new-progress counter
ended at 32, with cleanup still allowed to finish. The player was grounded,
not flying, and final inventory inspection located it outside at `[344,-60,31]`
with full health. Terminal recording: 7,184 observations, not truncated. This is
recovery evidence, not fresh-house success, and its retry/scaffold costs remain high.

During cleanup, the only final mismatches were door-open states, but each
teardown step still requested a construction-access search. A focused regression
reproduced the unnecessary call. Pending air plus toggleable door adjustments
now bypass construction-access search while owned teardown proceeds; door
adjustment still happens only after cleanup. The client was stopped before
building/testing this change to avoid replacing its live jar.

`construction-terrain-platform.js` is a new terrain-input fixture: a platform
with foundations above sampled ground and a front stair approach selected from
the height field. Pure JavaScript design generation was exercised on flat and
sloped synthetic heights. Native Foundation compilation and live terrain
construction have not yet been tested; the offline adapter deliberately does not
support Foundation compilation.

The cleanup-search fix passes all 28 construction and three ledger tests. The
next uninterrupted Creative house trial is `fresh-budget-house`, operation
`e6acba2d-30cc-4603-8e0d-ae9d3b814642`, origin `[368,-59,32]`. All 819 bounding
cells were verified loaded air beforehand. At 570 ticks it had placed 37 blocks
without failures, scaffolds or debt. It is ongoing; no completion is claimed.
Keep jar-producing builds stopped until this live trial has ended and its
recording is exported.

Trace-capture correction: the CLI accepts at most 1,200 window ticks. Recent
requests using 2,400 were rejected, so those requested trace paths are not
per-tick evidence. Dashboard recording exports were independently saved.
For `fresh-budget-house`, `fresh-budget-house-upper-motion.jsonl` is confirmed:
1,200 records, 174 distinct player positions, 62 airborne ticks, no flight.
No upward-to-nonpositive-velocity airborne vertical collision was found in that
window. That narrow detector does not exclude all possible head contacts.
The next valid 1,200-tick window is `fresh-budget-house-mid-motion.jsonl`.
Early dashboard export: 2,410 observations; middle: 5,201; neither truncated.
At 7,090 operation ticks the house had 280 placements, 31 failures and 18 debts.
Probe samples associated with failed targets selected `global_untried` seven
times and `local_frontier` once; these are sparse samples, not all decisions.
A reachability-priority regression is being prepared, but no build or runtime
change will occur until the live trial finishes.

The uninterrupted `fresh-budget-house` trial failed at 13,383 ticks: 389
placements, 12 mismatches, 92 failures, 717.01 blocks travelled, 45 scaffolds
added, 42 removed and four debts remaining. Terminal export: 9,070 observations,
truncated; the earlier 8,076-observation roof export was not truncated.
Of the recorded failures, 31 were the same break target `[368,-52,32]` immediately
after navigation accepted stance `[368,-51,36]`. The valid finish trace records
arrival at `[368.555,-51,36.864]`: being in the goal cell did not mean being at its
centre, and the actual standing eye was beyond the breaker centre-distance reach.

Repair/cleanup stance selection now reserves a half-cell offset along each
horizontal axis. Hypothetical scaffold and repair work poses use standing eye
height instead of inheriting a possibly crouched current pose. The actual break
executor's reach limit is unchanged. A regression covers the recorded arrival,
rejects the nominal edge-of-reach stance and accepts a closer one.

Global untried selection now looks for a reachable stance among the first 24
ordered candidates before dispatching an unverified target. Local/current-stance
priority is unchanged, and the native fallback remains available when the coarse
graph finds none. Tests reproduce the old selection of an unreachable close
block over a reachable candidate and preserve the fallback case. Both changes
await live qualification after focused tests.

All eight scaffold, 30 construction and three ledger tests pass after the
arrival-margin and global reachability-priority changes. Recovery
`arrival-reach-resume` (`95c0d000-dc6e-4848-987a-894837c0ba26`) is running against
the failed x368 house. At 756 ticks it had placed four blocks, with eight
mismatches, seven failures and ten repair debts. Its 1,200-tick motion trace was
confirmed active (`05dba85e-760c-4160-bc83-731ae3e23a45`). Completion and removal
of the repeated out-of-range failure remain to be verified.

The `arrival-reach-resume` recovery terminated FAILED at 2,319 ticks, with four
placements, eight mismatches, 35 failures, 65.87 blocks travelled and three debts.
All 13 newly added scaffolds were removed. The terminal export contains 2,052
observations without truncation. Repeated wall-cut aiming timeouts replaced the
previous roof-range loop: repair search had no feedback about failed stances.

Repair failures now report their exact edit and stance to the environment. The
access-cut search excludes that approach until a verified construction geometry
change, allowing a different stance for the same block; changing travel cost
alone cannot make a failed approach eligible again. This is not yet a complete
stance-batching scheduler or a fix for all navigation/cleanup failures.
Forty focused tests pass, including a construction-loop regression that fails a
break, replans to a second stance and verifies geometry-change feedback. Live
qualification is pending in the restarted client.

First feedback recovery (`1869f26b-b738-4e3e-b3fa-72406c555b98`) stopped after
37 ticks with `invalid_repair_edit`, zero placements and zero travel. The native
filter constructed a zero-cost placeholder, violating Edit validation; it now
looks up the approach directly without constructing an edit. The trace started
after termination and is not evidence of construction behavior. Live validation
of the corrected filter remains pending.

Corrected-filter recovery `feedback-v2` (`dbd1535d-abc1-42ca-882b-fd3f49cfa1f2`)
succeeded in 776 ticks: 599 exact cells, five placements, one door adjustment,
three failures, 99.72 blocks travelled, five scaffold placements and eight removals
(including three inherited debts). Final debt is zero and player is grounded,
with no flight reported. Terminal recording: 835 observations, not truncated.
This is incremental recovery, not an uninterrupted house success or isolated
proof that stance rejection caused the improvement.

A fresh site at `[400,-59,32]` was checked before construction: all 819 cells in
`400,-59,32..408,-47,38` were loaded air. `fresh-feedback-house` starts here using
the same house source and ordinary construction actions, with Creative supplies.

Fresh operation `258b6b34-7181-44a3-95f6-6eca00f1a9ff` reached 216 placements at
4,335 ticks with one failure, 340.45 blocks travelled and no scaffolds or debt.
Early recording (2,368 observations, not truncated) contains 113 fresh-site
completed placement jobs: 93/112 consecutive target transitions have Manhattan
distance <=2, but the largest is 10. This is target locality, not stance locality.
The later 3,416-observation stairs export records adjacent glass targets
`408,-57,37` and `408,-57,36` taking 139 and 192 job ticks respectively. A 1,200-tick
wall motion trace contains no flying samples. Completion remains pending;
substantial travel despite few failed jobs motivates ranking work by usable
standing positions, not only target adjacency.

**Fresh house completion:** `fresh-feedback-house`, operation
`258b6b34-7181-44a3-95f6-6eca00f1a9ff`, SUCCEEDED at 12,573 ticks. All 599 cells
match; 405 placements include restoration work, one door adjustment, 37 failed
attempts, 1,166.40 blocks travelled, 32 scaffolds added and 32 removed (29 tower
placements), 22 repair plans, zero repair debt. Player ended grounded, not flying.
No operator repositioning or world edits occurred during construction. The site
was verified empty before the trial. This establishes one autonomous fresh-house
completion in Creative, not repeatability, efficiency, terrain coverage or
Survival house construction. Final first-person screenshot shows the closed
entry door; it is not a full exterior visual audit. Terminal recording has 7,695
observations and is truncated; early and upper exports preserve earlier portions.

Upper/roof exports also reveal late `placement_would_block_escape` failures:
post-placement escape is checked at dispatch, but candidate stance selection does
not yet incorporate that check. Candidate ranking currently prefers connected
cells using height/straight-line distance, not walking distance around walls.
Route-aware stance ranking regressions are being evaluated next.

Construction stance ranking now uses walking-step distances from the same bounded
geometry flood fill instead of only connected/not-connected plus straight-line
proximity. Unknown routes retain stable fallback order for richer native
navigation. Distances count graph transitions, not exact time or a native path
cost. A U-shaped detour regression checks eight walking steps for a position two
blocks away versus three for a directly reachable position. This is a foundation
for stance locality, not yet multi-target coverage planning. Live comparison
against the completed house baseline is pending.


**Route-aware live comparison:** fresh site `[432,-59,32]`, operation
`273225df-3668-4311-81e1-01beeab8b5c3`, SUCCEEDED in 12,101 ticks, 599/599 exact,
404 placement/restoration actions, one door adjustment, 53 failures, 711.03 blocks
travelled, 24 scaffolds added and removed (12 tower), 46 repair plans, zero debt;
ended grounded, not flying. All 819 above-ground site cells were verified loaded
air before starting. No operator intervention occurred during construction.
Compared with the preceding same-design run: travel -39.0%, ticks -3.75%,
scaffolds -25%, failed attempts +43.2%. This is one paired live comparison across
two sites/revisions, not a statistical performance guarantee. Route ranking is
retained provisionally; visibility/reach retries and safe-stance selection remain
unfinished. Raw evidence uses prefix `fresh-route-house` in the ignored evidence
directory; early/mid/roof/late exports preserve the run at several points.
Terminal export: 8,774 observations, truncated. Earlier exports were not truncated
(early 1,683; walls 2,646; mid 4,383; roof 6,418; late 8,311 observations).


**Natural terrain platform:** sampled origin `[64,64,48]` in `Blueprint-Terrain`.
Native sample contains sand surface heights 1..4 relative to origin; footprint
heights are 2..3. Native compilation generated 60 cells: six stone-brick
foundations, 35 oak deck blocks, 17 dark-oak rail blocks, two stairs. All design
cells lie strictly above sampled ground. Native stair-access and walkable-area
advice passed (18 selected surface patches). Saved blueprint ID:
`5e7223d1-afcc-48af-90a4-0cfedbd97a1f`.

The scratch world's empty inventory was supplied offline with 64 of each required
material and 64 dirt before construction; both original player records were
backed up under ignored `terrain-inventory-setup`. Only inventory was changed;
world geometry and player position were preserved. Live inventory inspection
confirmed supplies. Ordinary navigation approached `[66,67,50]` before measuring.
Operation `7f9db15c-7db3-482f-98ea-7699003af4b9` then SUCCEEDED: 60/60, 910 ticks,
53.88 blocks travelled, zero failures, zero scaffolds/debt. Terminal recording:
1,069 observations, not truncated. Normal navigation to exterior `[70,67,49]`
succeeded afterward. `/tmp/terrain-platform-complete.png` visually confirms the
stair entrance, rails and foundation against the sand slope. This is one gentle
natural site, not steep terrain, excavation, water or Survival qualification.
Post-build navigation from exterior to deck `[70,69,55]` also succeeded
(`job-7efa4245-a4a9-4296-ab18-0e9004873daf`); this verifies reachable access,
not a claim that every movement tick avoided jumping.

**First Survival terrain trial:** new sampled origin `[80,64,48]`, footprint
surface heights 3..5. Native design has 72 cells (18 stone bricks, 35 oak planks,
17 dark-oak planks, two stairs), with passing stair/clearance advice. Pre-build
inspection found 62 target cells air and ten dry, replaceable leaf-litter cells;
no solid obstacles were removed. Supplied inventory began with 64 of each
material and dirt. Operation `bc8f826d-2392-4237-8281-4ebd065273c5` FAILED at 1,243
ticks: 69 cells placed, three mismatches, five failures, 81.41 blocks travelled,
no scaffolds/debt. Recording: 2,689 observations, truncated. This is not a
Survival completion. Raw sample, draft, layer scans and effects are retained
under prefix `terrain-survival`.

Failures included two foundation cells under a completed deck and a deck cell
under a rail. Construction now gives an untried lower solid its first attempt before the
solid directly above it; already-failed supports and intentional repair openings
do not block other work. Intended air beneath an overhang is not a dependency. Access planning now recognizes dry replaceable
vegetation consistently with ordinary placement. Temporary scaffold proposals
remain air-only, preserving their journal preconditions and avoiding untracked
vegetation replacement. Focused regressions cover support ordering and the
replaceable/solid/fluid distinction; live qualification of these changes is next.
The initial hard support dependency failed five existing retry/access regressions.
It was changed to a first-attempt preference, preserving deferred work and
intentional repair openings. All 45 focused construction, scaffold and ledger
tests now pass. The saved Survival failure remains available for live recovery;
no post-change live result is claimed yet.


**Supplied Survival recovery:** operation
`c0bca3c1-aa18-4ac6-876e-0a814f59b30e` SUCCEEDED at 448 ticks on the saved failed
terrain platform, without repositioning or resupply. It broke three owned blocks
through ordinary `break_blocks` (rail `[88,71,57]`, deck `[86,70,54]` and
`[86,70,52]`), placed three missing cells and restored all three openings.
Final: 72/72 correct, six placement/restoration actions, three failures,
15.18 blocks travelled, zero temporary scaffolds and zero debt; grounded and not
flying. Terminal recording has 503 observations, not truncated. Trace started at
client tick 1,683 and overlaps the recovery's last 235 ticks (terminal 1,918),
not the entire operation.

Final live inventory is 46 stone bricks, 29 oak planks, 47 dark-oak planks,
62 stairs and 64 dirt: exactly the initial stacks minus the blueprint's
18/35/17/2 material counts, with no net loss from the three cut-and-patch repairs.
Health remains full. This validates ordinary Survival breaking/restoration and
replacement of leaf litter for this recovery, not an uninterrupted Survival
terrain build or temporary-scaffold cleanup in Survival.

**Tall canopy fixture:** `examples/construction-tall-canopy.js` creates a seven-block
stone-brick column and 3x3 oak roof from sampled ground, 16 final blocks total.
Creative site `[64,64,64]` in `Blueprint-Terrain`: all 72 cells in its build volume
were verified air. Operation `91ea57be-263e-4d81-8bc2-814516f70133` FAILED at
1,279 ticks on `policy_effect_limit`: 13 placements, three mismatches, 16 failures,
73.92 blocks travelled, 20 temporary dirt placements (18 tower) and 20 debts.
Terminal export contains 2,242 observations, not truncated. This is not a completed
canopy or a scaffold-cleanup success.

The old effect limit was max(128, cells*8), giving this small tall fixture only
128 effects including inspection, navigation, construction and cleanup. The
construction-specific limit now adds 192 effects for a 32-block temporary tower
with navigate/inspect/edit both up and down. Time and repair-search limits remain
unchanged. A PolicyRuntime integration fixture reproduces exhaustion at 128 with
20 temporary placements and verifies completion plus all 20 removals under the
new bounded allowance. Live recovery remains pending.


**Canopy budget recovery:** `62dc5c6b-d2f2-4615-a5f2-1220d5ccf4a6` SUCCEEDED
in 674 ticks: three placements, three failures, 50.99 blocks travelled, three
new scaffolds plus all 20 inherited scaffold debts removed (23 removals), 16/16
correct and zero debt. Terminal recording: 1,083 observations, not truncated.
This verifies recovery and persisted teardown debt, not fresh Creative completion.

**Fresh Survival canopy:** sampled origin `[64,64,70]`, 72/72 volume cells verified
air before the trial. Operation `ad23e2ed-35bc-4d09-8107-08d2fcf968b9` SUCCEEDED
in 2,299 ticks: 16/16 correct, 17 failures, 121.71 blocks travelled, 23 dirt
scaffolds placed and removed (21 tower placements), zero debt, grounded and not
flying. Terminal recording: 3,654 observations, not truncated. No intervention
occurred during construction. This is a fresh supplied Survival demonstration
of reaching above ground interaction range and removing temporary scaffolds.

Final structure consumption is exact: stone bricks 46->39 and oak planks 29->20.
Dirt is 64->57: seven items were not automatically recovered. A post-build entity
query found four nearby Dirt item entities (within 1.9..3.6 blocks); it does not
report stack counts. Thus all scaffold *blocks* were removed, but complete
material recovery is not proved and is an open cleanup deficiency. The high
access overhead (23 temporary blocks for 16 final blocks) also remains an
efficiency target. Failed candidate sites overlapped water/tree canopy and were
not built on; this trial does not establish tree removal or over-water building.

### Work-patch tower ranking (2026-10-02)

The previous Survival canopy's repair history showed repeated first-valid towers:
for example a four-block tower at (69,68,74), a later extension there, then a
six-block tower at (70,68,74). Tower access formerly returned the first pose
exposing any target. Construction now ranks the bounded set of tower poses by
an estimated adjacent work patch, divided by tower placement/removal cost plus
an approach-distance penalty. A greedy hypothetical support closure can count
neighbours unlocked by earlier full-cube placements. Only ordinary full cubes
are introduced as hypothetical supports, and the standing body is excluded.
This is a ranking heuristic, not an executable plan or a safety proof: actual
placement checks, escape checks, and debt journaling remain authoritative.
Cleanup's single-debt tower search retains its first-valid behavior.

Focused validation: 45 tests passed (34 construction program, 11 scaffold
planner), including support closure and work-versus-access-cost ranking.
Log: `/tmp/construction-patch-green.log`.

A fresh Peaceful Survival canopy at origin `[67,64,66]`, blueprint
`construction-tall-canopy-patch`, operation
`93138913-5b81-4bb1-a551-cb8cc2b5bb44`, completed autonomously:
16/16 final cells, 1,883 ticks, 17 placements including one restoration,
8 failed attempts, 58.567 blocks travelled, 12 scaffolds placed and removed,
12 tower placements, 8 repair plans, and zero repair debt. The first recorded
ranked tower predicted a six-block work patch at height five. All seven column
and nine roof target positions were inspected as air before construction.
Nearby trees and older canopies remained; this is not a controlled A/B timing
comparison. Relative to the prior fresh Survival run (2,299 ticks, 23 scaffolds,
17 failures, 121.709 travel), results are encouraging but do not isolate the
ranking change from site geometry.

Evidence is under ignored `run/blueprint-evidence/construction/tall-canopy-patch*`:
1,200-tick trajectory trace, numbered work snapshots, and terminal recording
with 2,267 observations, not truncated. Inventory consumption was exactly seven
stone bricks and nine oak planks; dirt fell from 57 to 49. Removed scaffold
blocks still do not imply all dropped materials were recovered. A final
first-person image (`/tmp/tall-canopy-patch-complete.png`) shows the canopy
underside amid neighboring builds; exact completion comes from the verified
construction snapshot, not an unobstructed exterior image. Within-patch ordering,
remaining retries, drop collection, and repeated controlled trials remain open.

### Preserve visibility within a work patch (2026-10-02)

In the terrain patch-ranking trial, ticks 5033..5327 show the near roof corner
at (71,75,70) placed, cut back out, and restored while accessing (71,75,71).
Current-stance selection now uses one-ply visibility lookahead before its
existing locality tie breakers: score at most 24 currently placeable candidates
against at most 24 nearby pending cells using a hypothetical ordinary-cube
placement and real block raycasts from the current eye position. This only
ranks candidates; actual placement/escape validation is unchanged. It is not a
complete visibility-order search and does not predict every neighbor update.

The regression fixture completes a two-cell opening by placing the far work
before the near closure. Disabling continuation scoring makes the corrected
fixture fail with `near closure must be last`; restoring it passes. All 46
focused construction/scaffold tests pass (`/tmp/construction-occlusion-final-tests.log`).
An earlier test harness incorrectly treated the presence of `done:false` as
completion; that was corrected before the counterfactual check.

Fresh Creative superflat canopy, blueprint `construction-flat-canopy-occlusion`,
origin `[480,-59,32]`, operation `401ae0ac-d367-417d-a46e-f50df49d7b93`:
**SUCCEEDED**, 16/16 cells, 1,242 ticks, exactly 16 final placements, zero
restorations, 9 failed attempts, 71.714 blocks travel, 17 scaffolds placed and
removed, 16 tower placements, 13 repair plans, zero debt. Setup moved the player
to the site before construction; the complete 72-cell surrounding volume was
verified air. Execution used ordinary movement/place/break with construction
flight disabled. Scaffolds were oak planks from the available Creative inventory.
No intervention occurred after construction began. No previous same-site
baseline exists, so this is completion/ordering evidence, not a controlled
performance improvement claim.

Evidence: ignored `run/blueprint-evidence/construction/flat-canopy-occlusion*`,
including the 1,200-tick trajectory trace, work snapshots and terminal recording
(1,582 observations, not truncated). Remaining failures: seven
`target_not_visible` / no-safe-stance failures and two
`placement_would_block_escape` failures at (485,-57,37). The escape failures
suggest the next investigation: candidate stance/access planning still promises
placements that the authoritative post-placement escape check rejects.

### Escape-preserving placement stances (2026-10-02)

The prior flat canopy rejected placement at (485,-57,37) twice. Tick trace
1683..1690 shows the player grounded, crouching at approximately
(485.597,-55,36.545); this was not a transient mid-jump support failure.
Current-stance ranking now includes the authoritative expected-state escape
check. The placement executor filters alternative stances using the same
post-placement geometry (including paired states) before navigating. A final
arrival-time escape rejection excludes that stance and requests another
validated approach, instead of immediately failing the target. Direct movement
is disabled for this approach reason. The final escape gate remains in place.

94 focused tests passed: 48 interaction executor, 11 Minecraft escape geometry,
35 construction program (`/tmp/construction-escape-green.log`).

Two fresh identical Creative superflat canopy trials, shifted 16 blocks apart,
used the same starting offset and inventory as the preceding lookahead run.
Each surrounding 72-cell volume was verified air before construction. Both
finished with 16 exact final placements, no restorations, 12 scaffolds placed
and removed, 11 tower placements, 10 repair plans, 7 failures and zero debt:

| Trial | Origin | Operation | Ticks | Travel |
| --- | --- | --- | ---: | ---: |
| safe | `[496,-59,32]` | `e6d0df9c-2933-491a-81f0-fc94c7ed520b` | 921 | 45.253 |
| safe-repeat | `[512,-59,32]` | `482901e9-9699-4a10-a5fa-7cc82518c768` | 941 | 45.969 |

The previous flat run took 1,242 ticks, travelled 71.714 blocks, used 17
scaffolds and had 9 failures. The first updated run's seven failed jobs were
all `target_not_visible` with no viable stance, with no
`placement_would_block_escape` failures. This is repeatable evidence on one
simple geometry, not proof across arbitrary structures or Survival efficiency.
Pre-run positioning used the existing driver setup helper; there was no
intervention during construction.

Evidence under ignored `run/blueprint-evidence/construction/flat-canopy-safe*`
includes traces, exact work handles, snapshots and terminal recordings.
The first terminal export contains 1,169 observations, not truncated. The repeat
trace starts late in construction; its terminal recording preserves earlier
semantic events. Avoid interpreting these limited traces as full trajectory
coverage. Remaining no-stance visibility attempts motivate access planning
before spending a native placement attempt; that remains future work.

### First supplied Survival house and lantern dependency fix (2026-10-02)

Fresh house at `[544,-59,32]`, blueprint `construction-house-buildable`, operation
`8f9c0e28-892e-4bee-9e13-e4b119add304`, ran in Peaceful Survival with supplied
materials and diamond tools. Player inventory records were backed up before
inventory-only offline setup under ignored
`run/blueprint-evidence/construction/survival-house-inventory-setup`; no world
geometry was edited. The 819-cell above-ground volume was verified air. No
operator intervention occurred during construction.

It **FAILED** after 15,363 ticks: 490 placements, 7 remaining mismatches,
37 failed attempts, 866.964 blocks travel, 54 scaffolds placed / 52 removed,
39 tower placements, 101 repair plans, and 2 remaining scaffold debts. The first
229 placements had no failures or scaffolds. Later repair repeatedly cut and
restored upper-floor blocks. Exact paused world comparison identified three
missing lanterns, their two missing floor/ceiling support blocks, and the two
open door halves. Remaining dirt debt was at (546,-60,30) and (548,-60,39).

The first-attempt lower-support ordering incorrectly treated hanging lanterns
as structural supports. A ceiling waited for the lantern below it, while the
lantern required that ceiling. `Environment.structuralSupport` now distinguishes
full collision cubes in the native environment. Non-support fixtures no longer
block the ceiling's first placement. A regression reproduces the prior
`construction_no_feasible_target` and verifies ceiling-then-lantern completion.
47 focused program/scaffold tests pass (`/tmp/construction-lantern-green.log`).

Recovery with the fix, without resupply or repositioning, operation
`127ed9c5-cf9c-4c7a-b22a-c00d61191fdb`, placed both floor blocks and all three
lanterns in 199 ticks, travelled 22.848 blocks, and had zero failed attempts.
It nevertheless **FAILED** with the two door-state mismatches and both scaffold
debts remaining: cleanup found no feasible plan. The terminal trace shows the
player grounded on a half stair at approximately (546.668,-57.5,34.080). This
is evidence for the next cleanup/reachability diagnosis, not proof that the
half-step is the cause. Do not count this as an uninterrupted successful
Survival house or as complete recovery.

Evidence prefixes: `survival-house-current*` and
`survival-house-lantern-recovery*`. Early/mid/access/roof/late exports preserve
bounded live history; the late original export had 8,233 observations and was
not truncated. The delayed original terminal export was truncated and cannot
establish the full final sequence. The paused world scan has all 882 requested
cells and is authoritative for the listed mismatches. The recovery trace starts
after its short operation completed, so it proves terminal stance, not its full
trajectory. These observation limits do not change the recorded terminal work
results or the regression's dependency diagnosis.

### Cleanup ownership survives grass spreading (2026-10-02)

The cleanup replay disproved the stair-reachability hypothesis. Two complete
2,295-cell scans at snapshot `1ae24cee-0052-42e8-9578-52c0f2155ec7:tick:1198`
showed that the remaining owned dirt at (546,-60,30) and (548,-60,39) had both
become grass blocks. Exact intermediate-state matching silently skipped them.
The captured collision world and exact player body are retained in
`src/test/resources/blueprint/cleanup-stair-world.json`; an offline test confirms
that this stance can reach the open front doorway. The native escape query now
has a BlockGetter/loaded-region/body overload so captured geometry is replayable
without a client.

Cleanup accepts grass only when a persisted air-restoration debt records dirt
as the intermediate block. Exact matching remains the default for other states;
unrelated changes are not removed, and final-state restoration debt is not
reclassified as temporary air. Tests cover unchanged dirt, grass (including
snowy state), air, unrelated replacements, and non-air restoration obligations.
63 focused tests pass (`/tmp/construction-grass-green.log`).

Saved-house recovery with this change, no resupply or repositioning, operation
`9595018a-27ac-4017-9064-aab63c74a7f8`, **SUCCEEDED** in 306 ticks: all 599 cells
verified, 2 old scaffolds removed, 1 door adjustment, 0 placements, 0 failed
attempts, 58.301 blocks travel, 0 debt. Terminal recording has 290 observations,
not truncated. Evidence prefix is `survival-house-grass-recovery` under ignored
`run/blueprint-evidence/construction/`. This proves complete recovery of the
supplied Survival house, not an uninterrupted fresh Survival house. That fresh
qualification remains outstanding, as do efficiency and broader-shape trials.

### Fresh uninterrupted supplied Survival house (2026-10-02)

With the structural-support and grass-ownership fixes present from the start,
operation `d83ac054-9ff4-4b1e-b470-30ee9fd3ea3b` at `[576,-59,32]` **SUCCEEDED**
without intervention. The saved source was checked byte-for-byte against
`construction-two-story-house.js`. Setup used the same inventory as the prior
house, backed up both player records, and verified the 819-cell above-ground
site volume as air. The driver moved the player before starting and switched
to Peaceful Survival; no resupply, teleport, world edit, or restart occurred
during the operation.

Result: 599/599 exact cells, 8,633 ticks, 397 placements including restoration,
13 failed attempts, 556.738 blocks travelled, 17 scaffolds placed and removed,
15 tower placements, 18 repair plans, zero debt. Initial/final flying=false,
final grounded=true and health 20/20. Inventory deltas match the final structure
exactly: 63 dark oak planks, 60 glass, 3 lanterns, 1 door item (two blocks),
161 oak planks, 8 stairs, 63 stone bricks and 32 white concrete. Dirt fell from
128 to 120: all temporary blocks were removed, but 8 dropped items were not
collected. The diamond axe used six durability and shovel seventeen; pickaxe
was unused.

The test harness now exports recording history every approximately 1,000 elapsed
ticks and at terminal state. Nine exports plus three bounded tick traces and
numbered work snapshots are saved under ignored
`run/blueprint-evidence/construction/survival-house-fixed*`. The terminal export
`survival-house-fixed-recording-8633.jsonl` reports `truncated: false`. Supplies
and backups are in `survival-house-fixed-inventory-setup`. This is the first
fresh uninterrupted full-house Survival qualification. It is one run, not a
universal guarantee; repeated larger/varied builds and the fixed terrain-platform
Survival rerun remain outstanding.

### Route travel cost in target selection

Construction target selection now adds estimated movement ticks to the existing
height/support score. It uses the shortest known route to a visible, placeable,
escape-preserving stance, rather than only the target's straight-line distance.
The estimate is five ticks per transition in the bounded walking graph, not an
exact prediction of jump, acceleration or executor latency. Reachability and
travel estimates share a per-tick cache, invalidated on verified geometry edits.

Current-stance placements still win. Adjacent work gets a 12-tick switching
allowance, but yields when another work stance is substantially cheaper to reach.
Global candidates combine up to 24 structurally preferred and 24 geometrically
nearby targets, so lower remote layers do not entirely hide local work. The
coarse graph's native-executor fallback and retry gating remain intact.
`estimatedTravelTicks` in construction progress exposes the selected estimate;
unknown fallback/repair estimates are omitted.

Focused verification: 115 construction, escape, scaffold and interaction tests
passed. New scheduling regressions failed before the change and pass afterward:
a geometrically near target requiring a long detour loses to a cheaper route,
and two work sites are finished one at a time instead of alternating by height.
These are deterministic fixture checks, not a measured live speedup.

Live integration check `travel-two-sites`: Creative Superflat origin
`[608,-60,32]`, two 3x3x3 solids separated by nine empty columns. The 135-cell
bounding volume was inspected as loaded air before construction. Operation
`OPERATION:b9a1e2b8-0bcd-45e7-b17d-36091d49d710` succeeded with all 54 cells
exact in 1,654 ticks, 56 final placements including restorations, 12 failed
attempts, 70.016 blocks travelled, two scaffolds placed and removed, zero debt.
The setup camera had flight enabled; construction disabled flight and finished
grounded. No operator intervention occurred during the operation. This run
still revisited sites during failed-placement fallback and access repair; it
proves integration and completion, not a speedup or perfect locality. Evidence:
`run/blueprint-evidence/construction/travel-two-sites-*`, including terminal
recording, tool effects and a late-window tick trace. The trace does not cover
the beginning of the operation. Fresh Survival terrain qualification remains
pending; its supplied inventory setup was preserved while this targeted test ran.


### Fresh Survival terrain qualification after support and travel-cost changes

`terrain-fixed`, operation `OPERATION:1fd7763d-e4d9-4f0f-b6cf-200d7c3d96a2`,
used the tracked terrain-platform source at origin `[83,64,36]`. Two rejected
sample sites contained trees; the selected footprint spans two blocks of ground
height variation. No terrain was cleared to make the test pass. The sampled
blueprint contains 87 blocks: 33 stone bricks, 35 oak planks, 17 dark oak planks,
and two oak stairs.

The fresh supplied-inventory Peaceful Survival run succeeded uninterrupted in
1,275 ticks: 87/87 exact, 87 placements, zero failures, 43.982 blocks travelled,
zero scaffold/repair edits or debt. Initial/final flight was false and the final
player was grounded. Inventory consumption exactly matches all 87 target blocks;
dirt stayed at 128, tools were undamaged, and health remained 20/20. No operator
movement, resupply, restart or world edits occurred during construction. A
subsequent ordinary navigation job `JOB:job-9070cfe2-7975-4d62-9dbc-a6a54f1f6bd9`
completed from the front approach to deck position `[89,71,42]`.

Evidence prefix: `run/blueprint-evidence/construction/terrain-fixed-*`, including
sample, compiled draft, initial/final inventory, numbered observations, 1,200-tick
trace and terminal recording at tick 1,275 (not truncated). The trace began before
construction and therefore does not cover its final ticks; terminal tool effects
and runtime observations cover completion. This closes the earlier fresh terrain
rerun gap, but is not a controlled speed comparison with the older 72-cell site.

### Probe access before exhausting inaccessible targets

The two-site fixture exposed a fallback cost: when no shortlisted target had a
known reachable stance, the scheduler still issued ordinary placement attempts
across upper cells before requesting scaffolds. It now tries one bounded access
plan per geometry revision before `global_untried` fallback. Available plans use
the existing journaled placement/break/cleanup path and existing repair budget.
Empty searches still allow native placement, because the coarse graph omits some
legal movements. Failed native attempts alone cannot repeatedly trigger this
new speculative probe; the existing failure-recovery policy remains available.

Two new regressions failed before the change and pass afterward: a known scaffold
is built before an unreachable final target, and an empty access search preserves
native fallback without repeating the speculative search on unchanged geometry.
All 117 focused construction, escape, scaffold and interaction tests passed.

Live access-first checks used the identical two-solid source and starting offset
on fresh inspected-air Superflat sites. `access-first-two-sites` at `[640,-60,32]`,
operation `OPERATION:f4402ad7-1d15-4047-99f1-aeed05a41acf`, completed 54/54 in
1,331 ticks with one failed placement, 106.389 blocks travel, five scaffolds
placed/removed and zero debt. The fresh repeat at `[672,-60,32]`, operation
`OPERATION:c09c6c9b-0242-4c16-9d6f-a60c14814e8c`, completed in 1,315 ticks with
one failure, 103.504 travel, five scaffolds placed/removed and zero debt.
Both performed 56 final placements including restorations, without operator
intervention during construction. Prefixes under the construction evidence
directory include source, preflight, observations and terminal recordings;
the first also has a bounded early tick trace.

Compared with the preceding travel-cost run (1,654 ticks, 12 failures, 70.016
travel, two scaffolds), these two runs reduce elapsed ticks by about 20% and
failures to one, but increase travel by 48–52% and scaffold work. This is a small
sequential comparison, not a broad performance guarantee. Keep the tradeoff
visible: earlier access avoids futile attempts, while scaffold selection and
teardown routing still need better locality. Survival house/terrain outcomes
above predate this access-first change and are not repeat qualification for it.

### Local scaffold cleanup

The access-first repeat's recorded cleanup alternated east/west/east/west:
scaffolds were ordered by descending height across the whole build. Direct
cleanup now enumerates reachable removal stances by graph route length and
chooses the first safe removal, instead of globally sweeping heights. Each
scaffold column remains top-down, including when its top is currently unreachable.
Ownership, visibility, break reach, underfoot landing and post-removal escape
checks remain mandatory. A fresh geometry query follows each edit; cleanup
access planning remains the fallback when no direct removal is safe.

The new ordering regression failed with the former height-first traversal and
passes with stance-first traversal. Another regression prevents undercutting an
unreachable top while allowing work on a different column. All 119 focused
construction, scaffold, escape and block-interaction tests passed.

Live cleanup qualification, same two-site fixture and start offset on fresh
inspected-air Superflat sites:

| Evidence prefix | Origin | Ticks | Travel | Failures | Scaffolds added/removed |
| --- | --- | --- | --- | --- | --- |
| `local-cleanup-two-sites` | `[704,-60,32]` | 1,282 | 74.914 | 1 | 5/5 |
| `local-cleanup-two-sites-repeat` | `[736,-60,32]` | 1,200 | 73.323 | 1 | 5/5 |

Operations `OPERATION:62f980b6-9897-41a2-bf3d-21ec2b82e296` and
`OPERATION:2b7708f9-9246-4e5c-99d7-12707e8e7d57` both verified 54/54 target cells,
56 placements including restorations, and zero debt. No interventions occurred
during either build. Recorded break effects show both west scaffolds removed
before the three east scaffolds, replacing the previous alternating-site cleanup.
Compared with the preceding access-first pair, travel fell about 28–31%, with no
increase in failures or scaffolds. These remain small sequential Creative trials;
current cleanup/access changes still need Survival requalification on larger work.
Source, preflight, full effects and terminal recordings are stored under each
prefix in `run/blueprint-evidence/construction/`; the first also has an early
bounded tick trace. No broad optimality claim is made.


### Full Survival house with travel cost, early access and local cleanup

`survival-house-latest`, operation
`OPERATION:5a68b2a9-bf5a-4c15-b2c6-69f39b713c1c`, built a fresh house at
`[768,-59,32]` from the tracked source (byte equality checked), with supplied
materials/tools and no intervention during construction. Preflight checked the
819-cell above-ground volume as loaded air. The saved design ID had been reused
by earlier draft experiments, so this trial deliberately compiled the tracked
source and asserted 599 cells instead of trusting that mutable saved ID.

Result: 599/599 exact, 8,555 ticks, 394 placements including restorations, one
door adjustment, five failed attempts, 440.941 blocks travelled, 29 scaffolds
added and removed (22 tower placements), 34 access plans, zero debt. Initial and
final flight were false; final player grounded, health 20/20. Permanent material
consumption exactly matches the house: 63 stone bricks, 161 oak planks, 63 dark
oak planks, 60 glass, 32 white concrete, eight stairs, three lanterns and one
door item. Dirt fell from 128 to 118 because dropped items were not all collected;
the shovel used 29 durability, axe three, pickaxe zero.

Against the earlier 8,633-tick Survival house, this is similar elapsed time,
21% less travel and five rather than thirteen failures, but 29 rather than 17
scaffolds. Do not treat lower retries as a universal efficiency improvement.
Current larger Survival construction and cleanup are qualified; scaffold economy,
material pickup and broader terrain/state coverage remain open.

Evidence prefix `run/blueprint-evidence/construction/survival-house-latest-*`:
source, compiled draft, numbered work observations, inventory backups and totals,
three bounded traces and nine periodic recordings. The final recording hit the
retention cap and is truncated; the preceding eight were not. Their union covers
all observation sequences 6 through 7,625 without gaps. Duplicate copies differ
only in coalesced `throughServerTickId` fields for three constant context records,
not payloads. See `survival-house-latest-recording-coverage.json`; the final export
alone must not be described as a complete recording.

### Broad regression verification

After the latest Survival house trial, ran `:test :navigation-core:test
:wrapper:test --continue` with the client stopped and JDK 25. Final result:
2,066 root tests (four skipped), 79 navigation-core tests, and 100 wrapper tests;
zero failures/errors. The four skipped cases are installed-Codex integration
checks (three) and the opt-in rule-engine measurement (one), not construction.

The initial broad run exposed 34 stale wake goldens. The repository's explicit
update mode was used, every changed JSON tree was reviewed, then normal mode was
rerun successfully. Differences are limited to 59 system-prompt hashes, 66 tool
schema hashes and two `updatedTick` values (2 to 1) in the navigation-failure
cascade, corresponding to the existing primitive-task identity guard. No wake
counts, routing decisions, event content or scenario assertions were relaxed.
Logs: `/tmp/construction-broad-tests.log`, `/tmp/construction-wake-update.log`,
`/tmp/construction-broad-tests-final.log`. This broad regression pass complements,
but does not expand, the live geometry/terrain qualification scope above.

### Mixed-shape pavilion probe and missing-material failure

Added `examples/construction-furnished-pavilion.js`: 88 cells comprising a raised
stone deck with entry stair, isolated fence columns, bottom-slab roof and table,
opposing stair benches, and a hanging lantern. The first Creative probe at
`[800,-60,32]`, operation `OPERATION:910ce50e-4b7e-4bee-9217-117db154d905`, is
**not a qualification**: the test setup omitted fences, slabs and stone stairs
from inventory. Creative mode does not bypass the ordinary executor's inventory
requirement. Recorded failures are `required_item_missing`, not proof that those
shapes cannot be constructed.

This setup mistake exposed a real scheduler defect: material failures were being
treated as geometry failures, causing repeated access plans and scaffolds. The
run was paused and the client stopped, preserving the partial structure and debt.
Last observation: 37 placements, 51 mismatches, 271 failed attempts, 42 scaffolds
added / 11 removed, 31 debt entries. The persisted ledger is copied to
`run/blueprint-evidence/construction/furnished-pavilion-paused-debt.json`;
recording and pause evidence share the `furnished-pavilion-*` prefix. This was
operator-interrupted, not a terminal autonomous failure or a completed build.

Construction now checks inventory for each distinct pending material before
access search, and checks again after child effects. Existing door toggles and
air cells do not require items. Missing stock raises `construction_material_missing`
with item and target; the durable error path preserves any existing repair debt.
This checks availability, not an exact bill-of-materials reservation. Regressions
cover missing initial stock and depletion after a verified placement. Properly
supplied recovery, scaffold cleanup and a fresh pavilion trial remain pending.

All 121 focused construction, escape, scaffold and interaction tests passed after
the material guard, including both new regressions that failed before the fix.
The guard has not yet been exercised through a restarted live client.

### Supplied pavilion recovery exposes state-prediction mismatch

The correctly supplied recovery `OPERATION:35f3a94e-7e05-402e-b1b9-d298cc7b9218`
resumed the same origin and player position, restoring 31 prior debt entries.
It made progress but produced excessive scaffolds and was operator-paused, then
the client stopped. Last observation: 7,171 ticks, 44 verified placements
including restorations, 11 mismatches, 54 failures, 703.962 travel, 95 new
scaffolds and 126 total debt entries. This is not completion. Paused world scan,
recording, traces, inventory setup and debt snapshot are preserved under
`run/blueprint-evidence/construction/pavilion-recovery-*`.

With materials now present, recorded failures include
`placement_state_requires_different_approach`: the access planner checked stair
orientation but not fence neighbor connections or slab half. It could therefore
promise access that the ordinary executor correctly rejected. The planner now
checks FenceBlock's native connection predicate against the hypothetical geometry
and rejects slab approaches producing the wrong half. Exact executor and final
blueprint-state verification remain unchanged; this does not permit temporarily
incorrect target states. Both new regressions failed before the change, and all
123 focused construction, escape, scaffold and interaction tests pass afterward.

Live recovery with these checks is still pending. The access model's full-cube
support assumption also remains conservative for partial blocks, so this change
must not be presented as complete support for arbitrary fences/slabs or a
successful furnished pavilion. Persisted debt must be recovered, not discarded.

### Pavilion cleanup and partial-support click points

`pavilion-state-recovery`, operation
`OPERATION:383f65b8-cd74-45c1-91a9-074e9c4ab045`, resumed without resupply or
repositioning. It removed all 126 inherited scaffold debts, added no scaffolds,
and placed three final cells. It then failed at 1,639 ticks with five missing
fence cells, seven failed attempts, 34.726 travel and zero debt. This validates
cleanup recovery, not complete pavilion construction. Terminal recording, trace
and paused state are retained under that evidence prefix.

The remaining conservative gap was partial supports: planning required a full
collision cube and both planning/execution sampled full-cube face positions.
Added shared `PlacementSupportPoints`, sampling each outline-shape box's face
centre and inset points. Access planning now permits non-replaceable partial
supports and execution uses the same candidate points. Existing range, raycast,
expected-state and escape checks remain authoritative. Tests cover a half-height
slab top, slab-side ray intersection, narrow post side, empty shape and the
unchanged nine-point full-cube pattern. The old full-cube behavior failed the
slab/post regressions; all 126 focused tests pass with actual shape sampling.
Live partial-support recovery is being verified separately.


Partial-support live recovery `pavilion-shape-recovery`, operation
`OPERATION:f78e0c2d-2437-44a8-b13e-b844ca887f9e`, completed the remaining five
fence cells in 111 ticks, 5.093 travel, zero failed attempts and no scaffolds.
All 88 cells verified exact, zero debt, initially/finally flight false and final
player grounded. No resupply or repositioning preceded this recovery.

Fresh Creative qualification `pavilion-fresh`, operation
`OPERATION:98a06e4a-8027-4fc0-885f-4fcb377ece01`, used origin `[832,-60,32]`
and the unchanged 88-cell source. All 210 cells of the enclosing volume were
inspected as loaded air first. It completed uninterrupted in 2,457 ticks with
88 placements, 10 failed attempts, 75.732 travel, seven scaffolds placed/removed
(four tower placements), zero debt. Setup view had flight enabled; construction
disabled it and finished grounded. No operator actions occurred during building.
Recorded artifacts use those prefixes under the construction evidence directory.
This qualifies one fresh mixed-shape Creative build, not yet Survival or broad
partial-block coverage. Neither prior interrupted pavilion run counts as success.

### Memory-pressure interruption (2026-10-02; investigated 2026-10-03)

The attempted fresh Survival pavilion run did not save even its first successful
setup response or a construction work ID. It is not a completed or failed build
qualification. The client (PID 88425) subsequently crashed in CoreAudio; see
`run/hs_err_pid88425.log` and the local macOS `java-2026-10-02-205658.ips` report.
The fatal-error report records only 36 MiB physical memory free and all 8.75 GiB
swap occupied. Java's post-GC live heap stayed roughly 347–361 MiB in the retained
history, with a 2 GiB maximum; the final committed heap was 1.60 GiB. This establishes
host memory exhaustion, not the process responsible or a construction heap leak.
Server stalls reached 38 seconds. Minecraft was kept stopped during investigation.

A separate offline allocation regression reproduces eager face-sample allocation
through `BlockInteractionTaskExecutor.selectPlacementHitPoint`: 10,000 successful
first-candidate queries allocated 18,000,000 bytes before optimization. Placement
now generates candidates until the first valid click, preserving sample order,
shape-aware coordinates, raycasts, and state prediction. Scaffold planning uses
the same lazy search. No shape cache or long-lived retained geometry was added.
This is a targeted allocation improvement, not a demonstrated fix for the crash.
The next live qualification still needs process-memory and allocation profiling;
these offline checks do not establish native/GPU memory use or end-to-end speed.

Validation: the allocation regression first failed at 18,000,000 bytes; after the
change it measured 4,720,248 bytes for the same 10,000 first-hit executor queries
(about 74% less). The concurrently measured eager reference allocated 16,560,088
bytes. These are allocation measurements, not wall-clock or live throughput claims.
All 128 focused placement, scaffold, construction, and escape tests passed using
one Gradle worker and a 768 MiB build heap; `git diff --check` passed.

### Search-local collision memoization (2026-10-03)

An offline probe of the real `ConstructionEscape.component` on a flat 19x19
reachable area made 4,465 standability calls for 1,444 unique candidate positions;
rejected air/obstacle positions were queried up to four times. Each search now
memoizes standability, capped at 8,192 entries. Beyond the cap it evaluates normally;
no entry survives into another search. Directed traversal checks remain independent.
The same probe now makes 1,444 calls (68% fewer) and reaches the same 361 stances.
A regression changes geometry between searches and verifies the changed block is
excluded on the next query. All 76 focused escape, native collision, scaffold and
construction tests pass. This measures collision-query reduction only; native
allocation, tick latency, and live build performance still require profiling.

### Profiled fresh Survival pavilion (2026-10-03)

Operation `OPERATION:a3fe883d-aecf-42ac-acb6-2da040f784f3` at `[864,-60,32]`
completed uninterrupted: 88 exact cells, 88 placements, 2,458 ticks, 10 failures,
74.261 blocks of travel, 13 repair plans, seven scaffolds placed/removed (three
tower blocks), zero repair debt. Final health was 20; permanent consumption was
exactly 35 stone bricks, 13 fences, 36 slabs, two oak stairs, one stone stair,
and one lantern. Dirt decreased 128→122 and the shovel used seven durability;
cleanup removes scaffolds but does not yet recover all dropped materials.
The 1,200-tick trace records Survival, flight disabled, and a grounded final sample.
All three recording exports report no truncation. Evidence prefix is
`run/blueprint-evidence/construction/pavilion-profile`.

The client ran with a verified 1.5 GiB heap cap, native-memory tracking, and a
32 MiB bounded JFR profile. A watchdog sampled system memory every five seconds
and would stop the specific client below 18% free memory or above 6,500 MiB swap
used. Neither threshold was reached: minimum free percentage was 27%, maximum
swap used 4,874 MiB. The client was stopped after evidence capture and its process
absence verified. The first launch was stopped before construction because the
JavaExec init settings did not apply to Loom's ClientProductionRunTask; the second
launch explicitly configured that task and its actual VM flags were verified.

JFR analysis restricted to the operation's recorded RUNNING→SUCCEEDED timestamps
(126.415 seconds) estimated 15.88 GB allocated. This is sampled cumulative
allocation, not retained heap. Post-GC heap samples ranged 476–875 MB. Final NMT
reported 2.07 GB committed across tracked JVM categories (not complete process/GPU
memory). The largest Airicraft allocation stacks were recipe normalization
(`CraftingOpportunity.recipeIdSegment`, 2.58 GB), recipe resolution (several more
GB), blueprint publication (`BlueprintService.publish`, 1.40 GB), and frame encoding
(1.08 GB). Blueprint publication also accounted for 404 execution samples. These
are optimization targets, not proof that any one caused the prior host exhaustion.
The matching fixture on an earlier Creative revision took 2,457 ticks; mode,
profiling, and launch differences prevent a controlled performance comparison.

### Follow-up on measured publication and recipe allocation hotspots

`BlueprintService` now retains the serialized design portion across maintenance
checks. Integrity and storage-error fields are serialized separately; an unchanged
maintenance result reuses the published string. Every non-maintenance operation
refreshes the design portion, including same-revision commits, and world reset
clears the cache. This preserves the dashboard contract and integrity scan cadence;
it does not suppress reports or retain versions of old designs.

`CraftingOpportunity.recipeIdSegment` now scans normalized characters instead of
compiling two regex patterns per recipe candidate. A 128 MiB offline JVM probe of
100,000 calls allocated 204,901,488 bytes before and 14,179,144 bytes after (93% less).
This targets a stack responsible for an estimated 2.58 GB in the captured build;
it is not an end-to-end allocation or latency measurement of the patched client.

All 27 focused recipe resolver/normalization, publication, designer lease,
designer, and storage tests passed. Normalization includes 10,000 deterministic
randomized UTF-16 comparisons against the original regex implementation.
Publication regressions verify unchanged reuse, fresh integrity/error data,
control-operation invalidation at the same revision, world reset, and rejection
of stale-generation publication. `git diff --check` passed. Live re-profiling of
these two changes remains pending.

### Live re-profile after publication/normalization changes (2026-10-03)

Fresh Survival operation `OPERATION:8972c319-31c4-47a8-a957-cd75fa268b18` at
`[896,-60,32]` succeeded with 88/88 exact cells, 88 placements, 2,457 ticks,
10 failures, 73.577 travel, 13 repair plans, seven scaffolds placed/removed
(three tower blocks), and zero debt. Permanent inventory consumption and tool
wear matched the preceding run exactly; six dirt drops remained uncollected.
The 1,200-frame tick trace stayed in Survival with flight disabled and ended
grounded. Three recording exports report no truncation. Evidence prefix:
`run/blueprint-evidence/construction/pavilion-optimized`.

The same 1.5 GiB heap cap, profiler settings, watchdog, supplied inventory,
fixture, and relative starting pose were used, at a fresh translated flat site.
Minimum monitored free memory was 28%; peak swap use was 4,691 MiB. After capturing
evidence, the client was stopped and its process absence checked before tests.

| Metric, construction window only | Before | After |
| --- | ---: | ---: |
| Recorded wall time | 126.415 s | 122.698 s |
| Game ticks | 2,458 | 2,457 |
| Sampled allocated bytes | 15.876 GB | 12.461 GB |
| Sampled allocation rate | 125.58 MB/s | 101.56 MB/s |
| Allocation attributed to BlueprintService.publish | 1.398 GB | 0.169 GB |
| Post-GC heap sample range | 476–875 MB | 425–761 MB |

This pair shows about 22% less sampled cumulative allocation (19% less per second)
and 88% less blueprint-publication allocation with equivalent construction
outcome. It is one matched pair, not statistical evidence of general speedup or
proof that the original host-memory crash is fixed. Final JVM-tracked committed
memory was 1,828,418 KiB; NMT does not cover all native/graphics allocations.
Recipe resolution and frame capture remain the largest measured Airicraft
allocation sources. Avoid expanding the scope on that basis alone: the current
build already sustains approximately normal tick rate.

Post-profile broad verification passed with `:test :navigation-core:test
:wrapper:test --continue`, one worker and a 768 MiB build heap. Reports contain
2,079 root tests (four expected skips), 79 navigation tests, and 100 wrapper tests,
with zero failures/errors. The root suite ran fresh; unchanged navigation and
wrapper test tasks were up to date. `git diff --check` passed.

### Latest-code house qualification and completion audit (2026-10-03)

Fresh supplied-material Survival operation
`OPERATION:da3b3040-ca65-4923-93f6-58f4042b75a7`, origin `[928,-59,32]`, revision 83,
completed the tracked two-story house with 599/599 exact cells. It used 393
placement actions, one state adjustment, 7,878 ticks (393.959 recorded wall
seconds), four failed attempts, 426.333 travel, 15 repair plans, and 17 scaffolds
placed/removed (13 tower blocks), leaving zero debt. Initial/final flight was
false and final grounding true. Permanent inventory consumption matched the
fixture; ten dirt items were left as drops. Evidence prefix is
`run/blueprint-evidence/construction/house-final`.

With navigation breaking and placement explicitly disabled, these ordinary
navigation jobs completed after construction:

- Exit to `[926,-60,34]`: `JOB:job-39574158-f6f3-4654-96be-9ce5b69fbb2c`.
- Walk from outside to upper floor `[933,-55,36]`:
  `JOB:job-fdf6d044-9412-4d77-8d63-7f7c691ac397`.
- Return outside: `JOB:job-98a7b11c-634d-4c20-931b-49736524b621`.

A final deterministic construction recheck,
`OPERATION:f1334dc4-9aa4-4cb0-ba34-131951cff811`, succeeded in one tick with all
599 cells correct, zero placements, and zero retries. The navigation settings
were restored afterward. This verifies a usable stair route and an exterior
exit without damaging the completed structure, not merely a successful receipt.

Eight periodic construction exports report no truncation; their union covers
sequences 6–6691 without gaps, including RUNNING and SUCCEEDED. They contain zero
LLM-call records. Recorded construction effects consist only of inspect_world,
place_block, navigate_to, break_blocks, and use_block. The three 1,200-tick
foundation/upper/cleanup traces and 600-tick usability trace all show Survival
with flight disabled. The later usability export is truncated; individual job
receipts, terminal states, traces and final exact recheck provide its evidence.
The client remained above 28% monitored free memory, with peak swap use 4,881 MiB,
and was stopped after capture; process absence was verified.

The deterministic-construction goal is qualified over the demonstrated suite,
with these requirement-to-evidence links:

| Requirement | Implementation and authoritative evidence |
| --- | --- |
| Existing pathfinder/motor, legitimate actions, no construction LLM | EmbodiedAgentRuntime starts BlueprintConstructionProgram through ToolPolicyHost; house effect inventory and gap-free recording above; Codex driver verified active |
| Foundation and jump/roof failure diagnosis | Recorded roof-jump trajectories and landing/headroom fixes above; current Moves/MoveExecutors regression tests; latest house foundation and upper traces, full completion and usable stairs |
| Ordering, locality, travel cost, bounded lookahead | BlueprintConstructionProgram structural-support ordering, current-stance 24-candidate lookahead, route-cost shortlist and local allowance; focused tests; two separated-site repetitions and current house/pavilion metrics |
| Exact states and dynamic replanning | Native placement-state prediction, fresh state reads after effects, geometry invalidation; exact 599-cell completion and zero-edit recheck; partial-shape pavilion completion |
| Climbing, scaffolding, teardown, recovery | Structure/stair navigation, bounded access repair and persistent debt ledger; canopy repetitions, house towers/cleanup, pavilion partial supports; zero terminal debt and real exterior navigation |
| Varied structures and terrain | Bridge, tall canopy, two-story house, furnished pavilion, separated work areas, and sampled gentle-terrain platform; Creative and supplied Survival rows in the qualification table |
| Measured efficiency and resource use | Tick, travel, failure and scaffold counts in raw work reports; locality comparison; profiled pavilion allocation comparison; full regression suite with zero failures |

Supported claims are limited to the measured structures (16–599 declared cells),
supplied materials/tools, loaded sites, ordinary block geometry, and the recorded
flat/gentle terrain. The 8,192-cell input cap is an implementation limit, not a
validated scale claim. No universal completion or optimal-order guarantee is
made. Steep terrain, excavation, liquids, falling-block/redstone mechanisms,
arbitrary block states, adversarial world changes, and resource gathering remain
outside this qualification. General obstruction removal and registration of
legitimately constructed structures as integrity baselines remain separate
integration work; saved authoring data already persists. Cleanup removes temporary
blocks but does not promise pickup of every dropped item. The prior host-memory
exhaustion's root cause remains unproven despite the measured allocation fixes.


### Additional structure fixtures: live launch blocked

Added `construction-watchtower.js` (107 cells), `construction-courtyard.js`
(207), and `construction-cantilever.js` (165) under
`prototypes/blueprint/examples/`. They stress a tall stair approach, a roofed
perimeter corridor, and a wide roof with supports only at its rear. Each
retains component ownership and declares intended walkable support blocks.

Offline compilation found no component overlap; the bundled component rule
and two-block-clearance rule reported no findings. The clearance assessments
cover 17 support blocks on the watchtower (25 exposed patches), 25 in the
courtyard, and 75 under the cantilever. These checks use synthetic geometry,
not the Minecraft registry, and do not prove construction or navigation.
Results are saved as `diverse-*-offline.json` under the ignored construction
evidence directory.

Both live driver launches failed before construction began with JourneyMap
`JmUI.getQuarterMaxScale` throwing a NullPointerException because the window's
monitor lookup returned null. The second launch reproduced it immediately
after joining. Both also hung during shutdown and required terminating the
specific client process after normal shutdown failed. No construction work
handle was created and none of these three fixtures has a live pass or fail.
Crash logs are `diverse-launch-display-crash.log` and
`diverse-retry-display-crash.log`; the first shutdown stack is
`diverse-launch-crash-threads.txt`. Inventory-only setup backups are in
`diverse-inventory-setup/`. The client used a 1.5 GiB heap cap; no watchdog
threshold fired. An unavailable display is a suspected environmental cause,
not a confirmed diagnosis. Retry after restoring the display.

### Additional structures: live Survival retry

After the display was available, the normal compatibility client launched in
Codex driver mode with all integrations. The following fresh-site trials used
supplied inventory, normal movement/placement, and no structure spawning. Setup
teleports and game-mode changes occurred before each construction operation.

| Fixture | Origin | Result | Ticks | Placement failures | Travel | Scaffold placed/removed |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| Watchtower | 960,-60,32 | Failed, 55/107 placed | 1,473 | 15 | 57.92 | 11/11 |
| Courtyard | 992,-60,32 | 207/207 exact | 4,467 | 0 | 161.12 | 0/0 |
| Cantilever | 1024,-60,32 | 165/165 exact | 8,757 | 114 | 438.10 | 49/49 |

Operation handles, respectively:
`344f52e1-c401-4cdd-9ae8-bf2e72a4b49a`,
`8bad6e60-3b1b-4c02-a214-c1643a7f9d20`, and
`a2c35364-eba5-4424-bd03-285c96dd31ab` (all `OPERATION:`).
Evidence is under `run/blueprint-evidence/construction/diverse-*`, including
terminal receipts, periodic recording exports, source copies, screenshots,
`diverse-results.json`, and five bounded tick traces. The traces contain 5,400
samples in Survival and zero flying samples; they sample the trials rather than
cover every tick. Every trial ended with zero scaffold repair debt, including
the failed watchtower.

The watchtower stopped with `construction_no_feasible_target`, not a material
shortage. Thirteen upper rail/post attempts reported zero safe stand candidates;
the earlier deck attempts also encountered visibility/reach failures. The
1,200-tick sampled trajectory reached feet Y -53.748 at most, below the lookout
standing surface at -52. It built the supports and most of the deck but not a
usable route onto it. This is an access-planning regression fixture, not proof
of a uniquely identified root cause.

The courtyard needed no temporary access and passed five navigation checks:
exterior, entrance corridor, rear walkway, central court, and exterior again.
The cantilever passed four: exterior, center paving, rear paving, and exterior.
Both checks explicitly disabled navigation block breaking and placement, then
restored the prior true/true settings. Per-job receipts are retained. Visual
inspection agreed with the completed shapes.

The cantilever succeeded but was inefficient. Failed effects comprise 91
`target_not_visible`, 22 `target_out_of_range`, and one other placement failure.
The 46 access-repair plans and 49 temporary blocks for a 165-cell structure make
this a useful placement-stance/visibility benchmark. Completion alone does not
qualify its efficiency. No construction algorithm change was made during this
batch, and no new full-suite pass is claimed.

Memory monitoring observed at least 28% free and at most 4,959.31 MiB used swap,
with no watchdog stop or crash during these trials. The client retained the
1.5 GiB heap cap. This is a successful monitored run, not proof that the earlier
host-memory exhaustion is fixed.

### Watchtower access fix and roof-placement improvement

The additional trials now have a successful live watchtower reproduction and
an improved (still retry-heavy) cantilever reproduction. Changes:

- Reserve up to 12 of the existing 24 tower-origin candidates for exterior
  positions. Nearby positions underneath a deck no longer consume the entire
  search budget. Exact geometry, reach and escape checks still apply.
- Consider state-compatible shaped blocks, including slabs, for placement from
  the current stance. Full state prediction remains the executor's responsibility.
- When a tower alone offers no work, consider one temporary support extension
  next to a target. Candidate supports are deduplicated, within four blocks of
  the stance, and capped at 16 per height. Stock, body collision, legal click,
  final-work utility and escape checks gate the plan. The extension uses the
  existing repair journal and normal placement/cleanup effects. This addresses
  diagonally adjacent stairs that do not supply each other a clickable face.

The exterior-origin regression failed before the selection change and passed
with it. A focused extension-candidate test checks the missing stair faces,
uniqueness, distance and count bounds; it does not substitute for live placement.
Focused planner/executor/escape tests passed before launch.

Intermediate evidence is retained rather than counted as success: the first
patch reached 103/107 watchtower cells and then failed on four unsupported
stairs (`OPERATION:3411a097-ffd9-446d-9066-23214d9359ed`). It removed 43/43
scaffolds. The first improved cantilever succeeded at 6,306 ticks, 64 retries,
225.49 travel and 31/31 scaffold cleanup
(`OPERATION:75a07511-9b2b-4a92-b688-1aa2e18cee6d`).

Final code, fresh translated sites, supplied-material Survival:

| Fixture | Origin | Exact result | Ticks | Failed attempts | Travel | Scaffold placed/removed |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| Watchtower | 1152,-60,32 | 107/107 | 4,562 | 3 | 249.46 | 51/51 |
| Cantilever | 1184,-60,32 | 165/165 | 6,306 | 64 | 226.61 | 31/31 |

Handles: `OPERATION:d9f0b484-0379-48e3-989a-3eac2a67ee7a` and
`OPERATION:c6c6c628-1603-43d0-8ed8-c2ccb8b282f8`. Evidence prefixes are
`extension-watchtower` and `extension-cantilever` in the construction evidence
directory. Both ended with zero repair debt. Watchtower ground → lookout at
1162,-52,34 → ground navigation succeeded with block breaking/placing disabled;
all four analogous shelter navigation checks also passed. Settings were
restored afterward. The watchtower screenshot shows the completed approach.

Against the original cantilever, the final run uses 28.0% fewer ticks, 43.9%
fewer failed attempts, 48.3% less travel and 36.7% fewer temporary blocks. This is
one original trial and two translated improved trials, not a statistical
performance guarantee. Sixty-four retries and 51 watchtower scaffolds remain
optimization opportunities; these changes fix completion of this watchtower,
not arbitrary construction completeness or all visibility disagreements.

Both clients were stopped after their trials. Memory-watch thresholds did not
fire: the final session retained at least 28% free memory, with peak used swap
6,103.69 MiB. The 1.5 GiB client heap cap remained enabled. No host-OOM fix claim
is implied.

Final verification: `:test :navigation-core:test :wrapper:test --continue`
completed successfully (`/tmp/structure-final-tests.log`): root 2,081 tests,
4 expected skips, zero failures/errors; unchanged navigation-core 79 and wrapper
100 reports were up-to-date. `git diff --check` passed. Actual client-process
absence was verified before this build.


### Live LLM designer on natural terrain (2026-10-03)

First end-to-end trial of `design_blueprint` (glm-5-3-flash, reasoning effort `low`, OpenAI-compatible provider) followed by `construct_blueprint` in Survival on the natural-terrain `Blueprint-Terrain` world (seed 42, features on), headless under Xvfb. Materials and tools were supplied with the driver-only `prepare` `give` option. One designer run per site; not a statistical result.

Problems found and fixed on this branch:

- **Provider stream cap.** The runinfra stream repeats a status envelope on every token chunk, so a long reasoning turn exceeded the old 4 MiB raw-line cap after about 9k tokens. The cap now bounds accumulated payload text; raw size keeps a coarse 64 MiB guard.
- **Trees counted as ground.** `sample` used the no-leaves heightmap, so logs and giant mushrooms became "surface" and Foundation built on top of trunks (spikes of +4..+6 in a sloped forest). `sample` now looks through logs, leaves, mushroom blocks, vines, cactus and bamboo (and the air under floating canopies) to the soil, and adds `terrain.obstacles` (per column, run-length `block@y0..y1` in local y).
- **Error text did not name the fix.** `terrain_intersects_floor` now gives the column, floor and highest ground; `component_conflict` spells out the exact full dotted `replaces` path; a component with no `id` reports `missing_component_id` instead of a NullPointerException. With the old text the model spent all 16 iterations looping on these.
- **Site clearing conflicted with everything.** `SiteClearance` (a Clearance with `yields: true`) never displaces another component's cell and is displaced by any later one, so it is order-independent and needs no `replaces`. It is an experiment; the designer is told to size it to the building volume.

Live construction results (Survival, flight disabled, no construction LLM calls):

| Site | Ground | Result |
| --- | --- | --- |
| Dark-oak/giant-mushroom forest, origin `[0,71,32]`, oak cabin rev 6 (864 cells, 12-block-tall clearance) | -4..+2, 178 obstacle columns | **Failed**, `construction_no_feasible_target`: 165 placed, 262 cells left, 352 failed attempts, 817 blocks travelled, 42 scaffolds placed and removed. Placement failures were `safe_stand_position_not_found` on upper walls and roof; giant mushrooms and a trunk stood right beside the footprint, outside the clearance. The model's clearance reached 12 blocks up through the canopy, which forced scaffold towers just to break leaves. |
| Obstacle-free sandy slope, origin `[60,65,20]`, oak cabin rev 11 (438 cells, cobblestone foundation following a 3-block drop) | -3..+2, no obstacles | **Failed late**: 282 placed, 15 cells left (the last target was a glass pane at `[74,70,21]`, `safe_stand_position_not_found attemptedStandPositions=0`), 409 failed attempts, 13,546 ticks, scaffolds fully removed. Visually a complete cabin on its foundation; the slope itself was handled. |

Open questions: why stand-position search yields zero candidates for window panes on the high side of a slope (suspect the escape guard); whether clearance should be derived from `terrain.obstacles` by the program instead of authored; and designer drift from the brief (the first draft used white concrete despite a material list).


### Window panes: placement prediction ignored derived connections (2026-10-09)

Root cause of the window-pane failures above: a designed `glass_pane` carries `east=false,west=false,...`, but the game connects a pane to the walls beside it. The executor's placement-state prediction and post-placement confirmation compared exact states, so no stance ever predicted the designed state and every candidate failed line of sight (`safe_stand_position_not_found`, 0 candidates). Placement prediction, confirmation and the construction state read now treat fence, pane, iron-bar and wall connection properties as derived (`BlueprintIntegrity.statesMatch`). Placement failures also report how many stand candidates survive each filter (`supports`, `standableChecks`, `lineOfSight`, `viableStands`).

`scripts/terrain-trial` replays one trial (navigate, supply, optionally design, construct, poll) and writes `metrics.json`/`work.json`. Same sandy-slope cabin (rev 11, origin `[60,65,20]`, Survival, supplied materials):

| Run | Result | Ticks | Failed attempts | Travel | Scaffolds |
| --- | --- | ---: | ---: | ---: | ---: |
| Before (fresh world) | 282 placed, 15 left | 13,546 | 409 | n/a | 48 |
| After, replay on the half-built world | 9 placed, 7 left | 715 | 9 | n/a | 0 |
| After, fresh world | 293 placed, 3 left | 4,206 | 1 | 289 | 5 |
| After, second fresh world | 293 placed, 3 left | 4,358 | 1 | 296 | 8 |

The last 3 cells are foundation cells in a pit (for example `[72,66,21]`: sand and placed cobblestone on four sides, the one open side above). Its attempt fails with `target_not_visible`, `supports=5`, `lineOfSight=0`: the faces it could click are only visible from stances that are themselves foundation cells. A most-constrained-first ordering tweak (fill cells with the most closed sides first) did not change the outcome (same 3 cells, 4,358 ticks) and was reverted. Open: place pit cells before their rims, or add a bounded cut-and-restore access repair for enclosed foundation cells.
