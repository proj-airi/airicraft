# System 2 refactor validation — 2026-09-14

Implementation, automated checks and the requested live contract trials are complete, with operator-assisted fixtures and limitations below. Actual configured roles demonstrated gathering, delegated shelter repair, chest/furnace use, search/travel separation, and reflex supervision with exact-handle resumption. This does not establish reliable autonomous self-play: repeated planner argument/spatial errors, an incorrect completion judgment, and an exact-arrival cancellation remain documented. A natural-trial setup error caused a death; the final reflex trial used cheat summoning and temporary protection. Models and effort are unchanged; provider recovery used external retries only.

## Automated verification

The latest `./gradlew build` passed after the chest schema/error clarification (`/tmp/system2-chest-build.log`, commit `0bb9027a`). The earlier integrated build/HotSwap result is retained in `/tmp/system2-work-context-final-build.log`; the initialization failure and clean restart are documented below. Root suite:1254tests, zero failures/errors,2skipped. Wrapper:94tests, zero failures/errors/skips. Earlier integrated subsystem builds and live corrections are recorded in D130–D140 of [the playtest log](autonomous-playtest-log.md).

| Contract | Evidence |
| --- | --- |
| Fresh outcomes between consecutive tool calls | PlannerOrchestratorTest inserts an identified failure while a model call is running; the next request contains it once and preserves tool pairing. |
| Frozen retry, fresh next decision | Timeout retry preserves the request and leaves the later failure unincorporated; the next gameplay request incorporates it. |
| Compaction, overflow, world/owner changes | Successful compaction preserves the role cursor and reprojects unresolved work/hold identity. Overflow reports its missing range. Changed world or decision owner rejects late actuation. |
| Work lifecycle and identity | WorkHistory/WorkProjection/runtime tests cover bounded terminal retention, graph children, exact held identities, unknown furnace outputs, cancellation semantics and waiting. Requested parameters remain inspectable without conversation history. |
| Immediate outcomes | A live-reproduced craft/placement receipt mismatch failed the new regression before correction. Terminal executor observations now enter the existing job reducer and evidence before completing the tool future. |
| Objective ownership | v1 migration preserves terminal status; controller-only mutation, blocked persistence/reassessment, bounded decisions and replacement reasons are covered. Tool budgets preserve active goals/delegations. |
| Delegation and prefixes | Real orchestrator fixtures transfer tools twice, preserve separate histories and valid pairing, retain effects across stale ownership, and preserve all bounded objective decisions. Fixed role schemas remain unchanged by discovery. |
| Reflex supervision | Active and inactive unchanged holds do not loop after plaintext. Blocked objectives still permit identified safety supervision without resuming the objective. Competing work requires observed release and exact work/hold resumption. |
| Search, travel and geometry | Search detours, occupied movement/head/jump envelopes, user/strategy intersection, partial support, unsupported feet, head obstructions, unloaded observations and door geometry have focused tests. Travel changes enter semantic evidence; route failures identify the failed path predicate and bounds while leaving the cause unknown. |

These tests establish code behavior. The separately recorded world trials below establish the live contract at the exercised boundaries.

## Actual planner and world evidence

World: `planner playtest`, Easy. Normal configured controller/thinker client, without external-driver gameplay tool execution. The old GIVEN_UP objective6713b725 was explicitly migrated to v2 without reactivation. The controller created objectiveb8230738-0152-4146-be67-df874622b2c6, which remained ACTIVE through the provider failure. After provider recovery the controller explicitly finished it GIVEN_UP due to repeated invalid chest arguments; a new chest-verification objective succeeded without rewriting that outcome.

- Collection JOB:job-0efc7ae9-5333-4377-b8fc-fe364ee7523b produced3spruce logs. Pickup events12–14, terminal event15, fresh inventory and the paused player snapshot agree.
- The controller delegated a bounded repair. The thinker navigated, waited through work handles, inspected geometry, crafted3logs into12planks, recovered from a support_not_found placement by changing support face, and placed2planks. Inventory ended with10planks.
- Independent frozen-world queries at snapshot `ccf43b39-ca43-4968-86dd-33f904a2dd7f:tick:24515` confirm spruce_planks at(1,133,3) and(1,133,5), and the open west-facing lower spruce door at(0,134,4). The model-visible standing query at feet(1.5,134,4.5) reports supported, clear feet and clear head.
- Navigation farther east to(2,134,4) failed CALC_FAILED. The thinker retained that limitation in its claimed return rather than claiming a complete route. The observed work failure remains under its original identity.
- The controller's return request at22412 contains shared events21–77, including the failed attempt and completed work, plus the thinker's separately labelled claimed outcome and final facts. HTTP403 prevented a model response to that request.
- Local class corrections were HotSwapped while paused. Continuing the final reload exposed an uninitialized enum-switch table in WorkProjection; successful redefinition had not established live compatibility. A clean restart resolved it (crash-2026-09-14_11.29.18-client.txt). The later static catalog description change also received a clean restart at a completed-objective boundary.

The operator explicitly permitted unilluminated **surface wood only** after existing illumination-policy rejections. This did not grant underground mining permission. A concise delegation instruction reduced repeated restatement of context already supplied by the handoff.

## Measurements and limits

Agent ticks below use nominal20TPS and exclude tick-debug pause intervals. Model transport latency is separately recorded in milliseconds. These are incident measurements, not a controlled before/after benchmark.

| Measurement | Observed result |
| --- | --- |
| Successful collection executor time | ticks7286–7586:300ticks, approximately15seconds. |
| Collection terminal to next decision context | event15 at7586 → controller context8124:538ticks, approximately26.9seconds. The preceding model call was still in flight. The next request contained the outcome, without a plaintext prerequisite. |
| Placement failure to next decision context, before the immediate-outcome fix | event50 at16857 → thinker context16981:124ticks, approximately6.2seconds. Detailed failure text arrived sooner, but the common receipt incorrectly said RUNNING; fixed and regression-tested. |
| Failed farther-east navigation to next decision context | event74 at21360 → thinker context21376:16ticks, approximately0.8seconds. |
| Time from first post-restart request to successful collection admission | ticks548→7286:6738ticks, approximately337seconds, including rejected approaches and illumination investigation. |
| Short delegation to first thinker actuation | controller dispatch14833 → navigation admission15133:300ticks, approximately15seconds. |
| Retained completed read requests | Controller:2inspect_world and1image; thinker:3inspect_world. Earlier reads are missing from the bounded exports, so these are lower bounds. The daylight image and torch lookup did not unlock collection; the explicit surface-wood permission did. |
| Repeated failures | Existing illumination rejection repeated; long delegation output timed out twice in the retained dispatch records. Repair attempted one unsupported placement, then used a different support face successfully. Farther-east navigation failed once. |
| Earlier provider block | Three actual controller HTTP403 dispatches; additional recorder failure records with dispatchTick=-1 are synthetic reports and are excluded from request counts. Runtime entered degraded scheduling; gameplay objective remained ACTIVE. |

The exported union retains27actual dispatch records, with gaps at calls4,5,23. Completed call snapshots were supplemented from contemporaneous bounded CLI queries where available. This is insufficient to claim an exact full-run redundant-read rate or reliable p95 latency. Per-dispatch metadata is saved in `run/playtest/2026-09-14/system2-metrics.json`; local diagnostic exports are ignored by Git and may contain private gameplay/chat context.

Relevant preserved exports: `system2-after-restart.jsonl`, `system2-logs-collected.jsonl`, `system2-immediate-outcome.jsonl`, `system2-delegation-return.jsonl`, and `system2-provider-blocked.jsonl` under `run/playtest/2026-09-14/`. Later exports explicitly report retention truncation; no complete10minute retention claim is made under the64MiB cap. The delegation-return export was collected while running; the incident exports before code changes and final provider-blocked export were taken while paused.

## Live acceptance completion and limits

- Completed: controller created/placed a chest and verified a bone deposit/withdrawal with settled slot inspections, then closed it (objective6d870d85, chest syncId4). Furnace process smelt-process-dfaca43c-a8f8-469d-94e6-062b554935bb produced one charcoal, which was collected and subsequently crafted into four torches. Common background work visibility and waiting were used live; furnace cancellation remains automated-only.
- Completed under controlled conditions: navigation1cb357f5 paused at20710 with holdf97a442b; competing navigation was rejected; policy change20728 produced observed release20729; exact-handle resume20732 led to physical movement20734..20736. Default policy restored20740. D143–D144 distinguish the earlier setup death and temporary protection from survival performance. Subsequent natural interruptions were separate; the return-home job was cancelled for cleanup rather than falsely called completed.
- Completed with operator-assisted fixture setup: work98da37cd approached outside radius2 and collected one log. Workac7324d2 could not cross a dirt obstacle under occupied-cell bounds x=2,y=131..132; clearing those bounds let work67fc36e6 jump into formerly forbidden head cells and cross. It stopped just past the requested cell with CANCELLED, so exact arrival is not counted as success. Both temporary blocks were removed and path/travel settings restored. D142 records the repeated planner errors and exact evidence.
- Completed: torch craft2971 and fixture placements11206/11278 returned terminal SUCCEEDED receipts before their follow-up requests. No attempt was made to bypass the security checkpoint.

Further trials use explicit new objectives, preserving all historical terminal outcomes. Diagnostic exports system2-chest-opening.jsonl and system2-chest-recovered.jsonl contain the failure and successful argument-only recovery, with retention gaps explicitly marked.


Additional timings: narrow-scope acquisition3075→3242 took167simulation ticks, with pickup3240 and inventory2→3logs. Its next gameplay context3561 followed an intervening compaction request, a319tick delay; both work outcome and event range survived compaction. Controlled reflex event20710 entered request20712, and release20729 entered that same tick's next gameplay context. Manual stepping makes these latter tick deltas unsuitable for real-time performance comparisons.

Planner inefficiencies observed: eight malformed remember_place attempts in the retained fixture setup (stringified coordinate object); repeated placement against replaceable snow without solid support; redundant reads of the wrong camp height; proactive pursuit of a different non-aggro creeper after an explosion; and a success claim for a test whose required interruption never happened. The tool schema transmitted to the provider contained the correct coordinate-object union. These findings need targeted future planner-interface/prompt work; they are not silently counted as successful autonomous behavior.

## Final cleanup and saved state

The controller cancelled the same navigation work at22359 and explicitly completed controlled objective3f212a00-6b53-46b7-9b47-337e2225c70a, retaining prior GIVEN_UP outcomes. It acknowledged that return-home navigation had not completed. Cleanup used an operator teleport to camp, removed temporary Resistance, and verified no tagged test entity remained. Normal reflex policy is restored (combat/drowning enabled, threat distance16, line of sight required); path breaking/placing is enabled and strategy travel bounds are absent.

Final pause: session600a2756-73ee-4170-9e7f-d3a36479b4c3, epoch40, client tick22395; player(-2.5,134,4.5), health20, reflexIDLE, test workCANCELLED. Independent frozen-world checks confirm the chest, unlit furnace, crafting table, torch, open lower door and both repaired spruce-plank blocks remain at camp. This is cleanup verification, not successful return-home navigation. Final artifacts: `run/playtest/2026-09-14/system2-final-cleanup.jsonl` and `system2-final-player.txt`; the export explicitly reports older retention gaps.
