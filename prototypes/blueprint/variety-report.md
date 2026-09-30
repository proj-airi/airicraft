# Variety rollout review — 2026-09-30

The clearest finding is a mismatch between semantic names and semantic obligations: a component named `rail` can fail to guard anything, and an opening used as an entrance can escape the entrance rule. A clean rule result is not evidence that the brief was met.

## Method

Four briefs were frozen in `variety-tasks.json`: bridge, watchtower, courtyard compound and warehouse. Each includes review criteria before generation. Qwen uses the configured remote `qwen3-8-27b` with requested low reasoning effort, 32,768 output tokens/request, at most 12 turns and 5 accepted design submissions. No manual source repair. The authoring library and default rules stay fixed across the batch; resource hashes and the system prompt are in every transcript. Two lightweight request loops ran concurrently; Minecraft and Gradle remained off.

The judge reviews final source, compiled cells and component ownership, rule results and browser geometry. `review-variety.py` adds separate post-hoc checks for the briefs: bridge datum/rails, warehouse two-wide flat-route connectivity, courtyard flat connectivity and open overhead space, and tower footprint/standing surfaces. These probes were not supplied to Qwen and are not new default rules. They recognize a conservative set of full cubes, assume wooden doors can open, and do not implement a general stair/pathfinding or Minecraft physics simulation.

All first attempts and any retries are retained; failures are not discarded. These are exploratory case studies, not a success-rate benchmark or model comparison.

## Outcomes and transport accounting

| Brief | Latest compiled draft | Model run | Default advice | Independent judgment |
|---|---:|---|---|---|
| Bridge | 318 cells, 2 submissions | Finished | 0 findings | Deck too high, rails flush with deck, raised banks need jumps |
| Warehouse | 930 cells, 1 submission | Finished | 0 findings | Interior circulation works; exterior entrance needs a step |
| Courtyard | 728 cells, 3 submissions on retry | Finished | 1 lighting warning, 2 unverified lighting results | Covered wings connect to open court; raised approach still needs a jump |
| Watchtower | 435 cells, 4 submissions on retry | Interrupted by HTTP 429 | 1 stair warning, 1 unverified stair result | Platform height/footprint fit; continuous stair route is blocked |

Submissions include compiler-rejected designs, not just successful revisions. All sources in `examples/variety-*.js` are byte-for-byte copies of the last compiled model sources. In particular, `variety-watchtower.js` is an **unfinished attempt**, not an approved example.

Initial watchtower request timed out at 240 seconds before producing a response. Its one retry used a 480-second request timeout, keeping low reasoning and the same 32,768-token allowance; the first response arrived at 250.92 seconds with 27,418 completion tokens. It eventually hit HTTP 429 while investigating the remaining stair warning. No further rate-limit retries were attempted.

Initial courtyard response took 196.78 seconds and reported 22,964 completion tokens but returned neither visible content nor a tool call. The original transcript labels this `model_finished`; that is a harness classification error, not a completed design. The harness now labels such results `empty_response`. The single courtyard retry (480-second timeout) produced its first tool call after 292.21 seconds and finished after six response turns. These cases suggest allowing a longer transport window with reasoning enabled; they do not establish an optimal reasoning effort or token budget.

Initial evidence is under `run/blueprint-evidence/model-trial-variety-low-32k/`; retries are under `model-trial-variety-tower-retry/` and `model-trial-variety-courtyard-retry/` in that same evidence directory. Transcripts preserve prompts, resource hashes, raw responses, usage, tool results and failures. Screenshots are `run/blueprint-evidence/variety-{bridge,warehouse,watchtower,courtyard}.png`.

## Bridge: clean lint, defective access and guardrails

The final 318-cell design has a continuous three-block deck over the trench and three support piers. The trench remains open between piers (108 of the 126 cells below deck height across the bottom two trench layers are still air).

However:

- Deck blocks occupy y=0, so their walking surface is **y=1**, contrary to the requested y=0 surface.
- Both rail rows also occupy y=0. Their tops are flush with the deck; all 18 expected rail positions at walking height y=1 are air. They widen the standing surface rather than protect its edges.
- The model raises six-block-long banks on both ends to y=1. They match the deck but introduce a full-block jump at the transition from untouched ground, whose surface stays at y=0. There are no approach stairs.

The model inspected selected blocks and asserted that deck/ground tops were y=0. Those coordinate checks verified block identity, not the inferred walking surface. Its final answer acknowledges rail uncertainty but still calls the geometry verified.

All five bundled rules returned no findings. There are no Room, Door or Staircase instances, so the main coverage, lighting and access rules have no matching subjects. The named Solid/Assembly parts carry no machine-readable relationship saying which deck the rails protect.

Evidence: `model-trial-variety-low-32k/bridge/{final.js,draft.json,transcript.json,independent-review.json}` and `run/blueprint-evidence/variety-bridge.png`. The source is preserved unchanged in `examples/variety-bridge.js`.

## Warehouse: useful interior, missed entrance step

The 930-cell warehouse preserves interior `[11,4,9]`, has two two-block-tall shelving rows, a three-wide central clearance region, side access lanes, a rear work area and six ceiling-height glowstone blocks. The roof and approximate lighting checks pass. A separate flat-route search confirms a two-wide path from the front opening to the rear work area.

The entrance threshold is at `[5,1,0]` / `[6,1,0]`, but exterior ground is at walking y=0. There is no supporting approach at `[5,1,-1]` or a stair outside the footprint. Entering requires jumping.

Lint reports zero findings because the two-wide entrance is represented by `Clearance`, not `Door`. The entrance check only selects Door instances with walk guidance, so it never checks this opening. This is a rule-applicability defect as well as a generated-design mistake.

Evidence: `model-trial-variety-low-32k/warehouse/{final.js,draft.json,transcript.json,independent-review.json}`. Source preserved unchanged in `examples/variety-warehouse.js`.

## Courtyard: meaningful composition, locally repaired but globally inaccessible

The final compound fits a 21×9 footprint. It has a genuinely uncovered 5×5 courtyard and two distinct occupied wings with complete roofs, windows and glowstone. Paired doors through adjacent walls line up, connecting the courtyard to both wings. The independent flat-route probe confirms that connection assuming operable wooden doors and full-cube polished-andesite floors.

The added entry path occupies `[9,0,5]` through `[11,0,6]`, placing its surface at y=1. It matches the doorway but ends abruptly at ground y=0; at `[10,0,4]` the next step onto the path is one full block. The local entrance rule only samples two blocks outward and starts on the raised path, so it reports no finding. The repair moved the jump beyond the check's horizon.

The courtyard retains a lighting warning (minimum estimated artificial light 4). Each wing's lighting is **unverified** because the offline fixture lacks polished-andesite collision data. That is an evaluator limitation, not evidence that the wings are dark or impassable. The independent connectivity probe explicitly treats polished andesite as a full cube; this extra assumption was not added to model feedback. It does not certify in-game lighting.

Evidence: `model-trial-variety-courtyard-retry/courtyard/{final.js,draft.json,transcript.json,independent-review.json}`. Source preserved unchanged in `examples/variety-courtyard.js`.

## Watchtower: partial repair, still blocked; run interrupted

The last compiled tower fits 9×10 and its platform has walking surface y=7. The model added an exterior stair, platform rail geometry, floor lights and a hatch. It repaired initial overlap errors and ground-room lighting, but the route to the observation platform remains unusable:

- The mid-landing at `[6,4,5]` has standing level y=5, but platform floor at `[6,6,5]` leaves only one block of headroom. Both stair lanes have this obstruction.
- Its hatch clears `[6,6,6]` and `[7,6,6]` only. The first step of flight 2 at `[6,5,7]` remains directly underneath platform floor `[6,6,7]`.
- The upper flight points away from the platform, ending at z=8; the expected straight arrival at z=9 has no support. This needs a designed connection, not just another staircase label.

The stair rule correctly warns about flight 2, but reports the approach coordinate `[6,5,6]`, which is itself clear after the hatch repair. The model then inspects only that air cell and gets `specified:false`, leaving it without the nearby obstruction that explains the warning. Advice should provide a collision witness, the failing body volume and obstructing component ownership, not only an approximate route position.

Flight 1 becomes unverified because the checker rejects an entire sampled vertical column when any unknown geometry occurs in it (here, iron bars at the platform edge). This can hide known lower obstructions. Unknown geometry should be scoped to the relevant movement volume, and should not erase independently established failures.

The model was still investigating when the provider returned HTTP 429. These are defects in its last compiled draft, not evidence it would necessarily have left them unresolved given further turns. The renderer also displays unknown iron bars as full cubes and does not draw true stair collision shapes; source/cell probes, not those pixels alone, establish the route problems.

Evidence: `model-trial-variety-tower-retry/watchtower/{final.js,draft.json,transcript.json,independent-review.json}`. Source preserved unchanged in `examples/variety-watchtower.js`.

## Prioritized interface/rule implications

1. **Semantic obligations should attach to roles and relationships.** An entrance needs access advice whether implemented by a Door, arch or Clearance. A guardrail should identify the walk surface/edge it protects. Names alone should not count as these declarations.
2. **Expose walking surfaces explicitly.** A block's y coordinate and the top of that block are different. Inspection should return useful surface/threshold anchors so models can compare ground, deck and landing heights directly.
3. **Report applicability.** Distinguish zero findings after checking subjects from no applicable subjects. The bridge's all-green list currently suggests much more validation than happened.
4. **Check connected routes and required widths.** Local doors/stairs cannot prove outside-to-interior or floor-to-platform reachability. Preserve a witness route or identify the blocking support/headroom/landing cells.

5. **Return actionable failure witnesses.** Show the body volume, nearby colliders and their owning components; distinguish a route sample from the actual obstruction.
6. **Keep uncertainty local.** Unsupported blocks should not conceal known failures outside their collision influence. Expand fixture support separately from judging generated geometry.

These are findings and proposed follow-ups; the default rules were not changed while generating these examples.
