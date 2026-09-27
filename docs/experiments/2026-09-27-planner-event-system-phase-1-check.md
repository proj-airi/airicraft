# Planner event system Phase 1 verification

**Status:** Phase 1 source and smoke verification passed; evaluation parity did not. Seven scenarios reached terminal reports and two were manually interrupted after sustained, captured stalls. Two of the seven request rates exceed the unchanged baseline bounds. The full nine-scenario tolerance gate remains unmet.

## Build and characterization

Source is `9a4e317f` before this documentation commit. The build JVM was OpenJDK 26.0.2; compilation and tests used the configured JBR 21 toolchain. One combined `./gradlew --no-daemon -Pairicraft.includeEvaluator=true build :addons:evaluator:remapJar :compat:journeymap:remapJar :compat:rei:remapJar :prepareHotswapProductionConfigs` invocation passed in 48 seconds, preparing the production and hot-swap artifacts before either client run. Root tests: 1,770 run, 3 skipped, 0 failures/errors. Wrapper: 95; evaluator: 30; JourneyMap compatibility: 20; REI compatibility: no tests. All reported failures/errors were zero. The earlier Python ledger suite passed 95 tests; no file under `scripts/` changed after that run. Full logs and XML-derived counts are retained locally under `eval-output/phase1-event-system-check/build/`.

| Artifact | SHA-256 |
| --- | --- |
| root production JAR | `4e44c9d72679e78f00c87323bf2a61a10ba625541dab1a5849aa921384fb2754` |
| evaluator JAR | `14952b8c417206691b4b54947075953dd4ce61cc72ecc8b5cc3b39d3cbe2e06e` |
| JourneyMap compatibility JAR | `d59f856f6f9680b587d58cd9031ac4a111ffbaaebe6a3fad1eff1e19241b1e92` |
| REI compatibility JAR | `2bdeb0b02f46f7a6d200ced2275d44db47520d1904934d60ad4ce4d69b77731f` |

All 27 `*.golden.json` wake transcripts are byte-unchanged from `44acc6bc`. The sole wake-resource difference is an accepted inventory metadata correction: `task.notice` now declares its existing `DialogueRuntime` producer. The root build includes the strict catalog/inventory, provenance, lifecycle, and D6 flood tests. The D6 probe now completes EATING work after 512 unrelated events evict the raw log entry; its no-flood control also passes. This proves the bounded subscriber path in tests, not the frequency of that flood in gameplay.

## Normal-client smoke

A full-compatibility `runClient` joined a private copy of `farm_easy`. The read-only CLI returned 109 raw events, each with `source`, and 30 with `cause`; the captured dashboard export retained 105 semantic events, all sourced and 30 caused. The displayed event history included a `task.notice` with an `EmbodiedAgentRuntime` source and `WORK` cause; the browser reported no warning or error. The event-bus counters for undeclared type, unknown source, off-thread publication, and subscriber failure were all zero. No `subscriber_failed` or `off_thread` entry appeared in the retained timeline, verbose event output, or client log. Local-controller chat, server-command damage, and a summoned stick pickup appeared at sequences 98, 100, and 105; health fell to 19 after damage.

The smoke export hit its 64 MiB recording retention cap and dropped four early semantic events and other observations. The separate raw event snapshot retains all 109 events. The chat check proves local controller injection, not an external player's chat path. Complete smoke files are retained locally under `eval-output/phase1-event-system-check/live-smoke/`; they may contain private runtime data and are not committed.

## Evaluation method and current result

The same nine scenario worlds, manifests, private baseline-matched configs, model, and budgets as the [three-batch baseline](2026-09-27-planner-wake-baseline.md) were used. The launcher uses `--no-recorder --jobs 3 --stop-client-after-scenario`, one `--scenario` flag per baseline scenario, and the baseline `scripts/eval run --no-daemon` command with JDK 26 and `-x` exclusions for root/evaluator/compatibility packaging plus hot-swap preparation. This prevents client workers from overwriting the prebuilt shared artifacts. The output root is the ignored local `eval-output/phase1-event-system-check/evaluation/` directory. The original baseline ledgers and their three outcome/rate distributions remain under `/Volumes/wd_black_1tb/airicraft/eval-output/phase0-wake-baseline-frozen/`.

The table applies the baseline's unchanged Phase 2 intervals. Rates are initial planner requests per minute over their measured server-tick dispatch span; one-call rates are unavailable. `E/T` means event/timeline gap. `L` means a planner journal/LLM record gap and is separate from event/timeline continuity.

| Scenario | Result | Turns (allowed) | Req/min (allowed) | E/T | L |
| --- | --- | ---: | ---: | --- | --- |
| bread-cooperative-watch | FAILED, baseline class | 30 (18–33) | 36.92 (26.80–41.86) | no/no | no |
| farm_easy | PASSED | 8 (5.4–15.4) | **73.28 (29.06–40.88)** | no/no | no |
| farm_from_scratch | MANUALLY INTERRUPTED at 56 turns / 25,710 ticks | no terminal comparison | unavailable | partial capture only | partial capture only |
| farm_harder | FAILED, baseline class | 30 (27–33) | 28.50 (13.98–33.96) | no/no | no |
| get_water | PASSED | 8 (6.3–11) | 11.35 (8.67–13.12) | no/no | no |
| iron-pickaxe | MANUALLY INTERRUPTED at 62 turns / 24,794 ticks | no terminal comparison | unavailable | partial capture only | partial capture only |
| pickup | PASSED | 1 (0.9–3.3) | unavailable; one call | no/no | no |
| sea_grass | FAILED, baseline illumination/no-route class | 5 (3.6–5.5) | **43.80 (24.22–40.00)** | no/no | yes, also in baseline batches 1–2 |
| underground | PASSED | 1 (0.9–1.1) | unavailable; one call | no/no | no |

`farm_easy` made eight planner submissions over 130 server ticks and 14.24 wall seconds (9.13 ticks per wall second). The comparable seven-turn baseline run spanned 226 ticks and 13.74 seconds (16.44 ticks per wall second). Model-call median latency was 1.61 seconds, within the baseline medians of 1.45–2.05 seconds. A 3.198-second server overload warning occurred inside the new request window; baseline runs also had approximately two-second warnings in their windows. The new run first failed a farmland interaction with `target_material_mismatch`, then a seed interaction with `interaction_failed` and a raycast mismatch, before a third task led to success. It recorded 23 events versus 11 in each baseline farm-easy run, without truncation or event/timeline gaps. These measurements explain the high per-server-tick rate numerically but do not establish its cause or satisfy the fixed rate tolerance.

`sea_grass` made five turns over 137 server ticks and reached the same `environment_changed/insufficient_illumination` then `no_route` action-graph failure as baseline batches 1–2. Its 43.80 request rate exceeds the fixed 40.00 upper bound. Its incomplete LLM evidence likewise occurred in those two baseline runs, so no token-rate conclusion is drawn. Both rate breaches remain failures of the Phase 1 parity gate; no threshold was changed and no scenario was rerun to select a favorable result.

The `farm_from_scratch` worker paused a bucket-use job for a survival reflex after a `reflex.resolved` event. A read-only bridge probe twice observed planner/tool idle, the job blocked by reflex, and `PAUSED_BY_REFLEX` task execution. The reflex hold was `AWAITING_PLANNER`; the recorded W1 wake reached the planner, whose last tool was `say`. The explicit continue/resume contract and `resumeAfterReflex` call sites are unchanged from `44acc6bc`, so the evidence does not identify a Phase 1 source regression. At 56 turns and 25,710 elapsed ticks, the controller stopped only that worker's identified client PID after preserving its read-only state. This was a manual interruption, not the scenario's 80-turn or 120,000-tick budget result.

The iron worker advanced from navigation to `BREAK_BLOCKS`, then remained in `BlockBreak`'s `waiting_for_aim` state against a loaded coal-ore block at (-7,64,13). Two read-only bridge snapshots 47 seconds and 942 elapsed ticks apart showed the same player position, target block, task state, and 62-turn count. `BlockBreakTaskExecutor` and its `cameraController.blockHit` empty-result guard are byte-unchanged from `44acc6bc`; this observation does not establish a Phase 1 cause. The controller stopped only iron's identified client PID at 62 turns and 24,794 elapsed ticks. Its 80-turn/72,000-tick budget was not reached. Both scenarios set `maxElapsedMillis: 0`, and the launcher has no independent wall-time or no-progress cutoff. The runner reported `CLIENT_EXITED RUNNING` for both, with no `results-final.json`; it exited nonzero because the two workers were interrupted. Both owned client PIDs were verified absent. Incident captures are retained locally under `eval-output/phase1-event-system-check/incidents/`.

`python3 scripts/wake_ledger.py summarize` completed for the seven terminal runs; `diff` produced 21 comparisons against each scenario's three retained baseline ledgers. Every terminal run has no event or timeline gap and no unknown wake attribution. `sea_grass` has an LLM record gap like baseline batches 1–2; this is not an event or timeline loss. The seven runs include four passes and three failures. The two interrupted runs have only partial ledgers: `planner-calls.jsonl` is emitted on normal finalization, so their snapshots cannot establish request rates, wake-path proportions, or completed outcomes. No zero-request or zero-rate inference is made from those files.

Request-level diffs show changed evidence and wake paths in several trajectories. A notable distribution change is `bread-cooperative-watch`: W4/W9/W1 attribution counts are 14/16/3, versus 4/24/3 in baseline batch 2 at the same 30 turns. The new run's later W4 turns repeatedly chose `continue`, while baseline batch 2 repeatedly chose `observe` on W9 result reviews. This is an observed planner/tool trajectory difference, not a proven routing regression. The retained baseline artifacts were recorded before the inherited `5defe768` audit stamping correction; changes in recording-tick metadata must not be attributed to the Phase 1 event migration. The current request-level diffs do not override the concrete rate breaches or the missing terminal outcomes.
