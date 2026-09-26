# Planner wake baseline — 2026-09-27

Phase 0 measures the existing planner before the attention refactor. The
characterization suite and additive audit do not change gameplay decisions.
Scenario failures below are baseline observations, not fixes made by this PR.

## Provenance and method

The production artifacts were built from `bc9ea23c0ba14fc51bfd9bac7649e0364aa78522`
on PR #81, before merge into `dev`. Later commits correct the offline Python
ledger and documentation; the running client JARs stayed frozen. Their SHA-256:

| Artifact | SHA-256 |
| --- | --- |
| `build/libs/airicraft-1.0.0.jar` | `573520c3583e5cd04dc6ecb4fe857188c631f3cab5ccef9a41f8d558584d19d5` |
| `addons/evaluator/build/libs/airicraft-evaluator-1.0.0.jar` | `14952b8c417206691b4b54947075953dd4ce61cc72ecc8b5cc3b39d3cbe2e06e` |

The planner used `deepseek-v4.1-flash` through `https://ollama.com/v1`.
Gradle used the installed JDK 26; tests and the GraalJS spike used the configured
JBR 21 toolchain. The production evaluation launcher selected installed Zulu 21.
The nine scenario worlds, manifests and budgets were unchanged between batches.
Three isolated workers ran per batch, with recording video disabled but the
runtime flight recorder enabled.

An initial parallel launch was excluded: two workers failed startup while a
third rebuilt the shared root JAR, producing missing-mod errors. All clients
from that attempt were stopped. The valid batches prebuilt the evaluator and
compatibility artifacts and excluded packaging and hot-swap preparation during
launch. No launcher or gameplay fix is included in Phase 0.

The repeated batches used this launch command (with a separate output root
per batch):

```sh
client_command="scripts/eval run --no-daemon \
-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
-x :jar -x :remapJar -x :addons:evaluator:jar -x :addons:evaluator:remapJar \
-x :compat:journeymap:jar -x :compat:journeymap:remapJar \
-x :compat:rei:jar -x :compat:rei:remapJar \
-x :syncHotswapMainClasses -x :prepareHotswapProductionConfigs"
scripts/run-evaluation-scenarios --no-recorder --jobs 3 \
  --stop-client-after-scenario --client-command "$client_command" \
  --scenario bread-cooperative-watch --scenario farm_easy \
  --scenario farm_from_scratch --scenario farm_harder --scenario get_water \
  --scenario iron-pickaxe --scenario pickup --scenario sea_grass \
  --scenario underground
```

## Metric interpretation

- Calls include retries; requests/minute counts initial turns and excludes
  retries and tool follow-ups. A retry inherits its initial turn's wake.
- A `submitted` audit records an attempt. Multiple paths may be attributed to
  one request, so path counts need not sum to the number of calls.
- An empty wake has no new observed event, user turn or baseline refresh. A W9
  FIFO review can still carry useful tool results; this is not a waste estimate.
- Outcome latency is in agent ticks. Tables show p50/p90/max and sample count.
  Missing samples are unavailable, not zero latency.
- Evaluator rates marked `*` use the first-to-last recorded dispatch window,
  normalized to 1,200 server ticks per minute or 72,000 per hour. Raw evaluator
  output lacks full server-clock endpoints. Quiet time after the final call is
  excluded, and a single-call run has no rate without an explicit full recording window. These are request-span
  estimates, not whole-run usage or wall-clock billing rates.
- The automatic Play artifact supplies explicit start/end server ticks, so its
  rates use the full captured window.
- Missing LLM records or usage suppress token rates. A journaled request cancelled
  before dispatch can legitimately cause that conservative completeness flag.
  Timeline/event continuity is reported separately.

## Evaluation results

Batches 1 and 2 are complete. Iron pickaxe passed in batch 1 but failed in
batch 2, so the plan's required third batch is running. Final numeric tolerance
intervals await that result.

| Batch | Scenario | Result | Turns | Calls/retries | Req/min* | Empty | Outcome p50/p90/max | Tokens/hour* | Timeline/event gaps | Unknown |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- | ---: |
| 1 | bread-cooperative-watch | FAILED | 27 | 29/2 | 29.78 | 14 | 1/1/1 (10) | 65,914,147 | False/False | 0 |
| 1 | farm_easy | PASSED | 14 | 16/2 | 35.07 | 12 | —/—/— (0) | 63,363,006 | False/False | 0 |
| 1 | farm_from_scratch | FAILED | 80 | 81/1 | 16.30 | 17 | 1/1/110 (58) | 40,489,019 | False/False | 0 |
| 1 | farm_harder | FAILED | 30 | 32/2 | 30.87 | 18 | 1/1/1 (8) | 71,652,844 | False/False | 0 |
| 1 | get_water | PASSED | 7 | 7/0 | 9.63 | 3 | 1/1/1 (3) | 14,044,294 | False/False | 0 |
| 1 | iron-pickaxe | PASSED | 35 | 36/1 | 9.71 | 18 | 1/2/19 (15) | 22,263,149 | False/False | 0 |
| 1 | pickup | PASSED | 3 | 3/0 | 14.06 | 1 | 1/1/1 (1) | 18,453,375 | False/False | 0 |
| 1 | sea_grass | FAILED | 4 | 4/0 | 36.36 | 1 | 0/0/0 (1) | — | False/False | 0 |
| 1 | underground | PASSED | 1 | 1/0 | — | 0 | —/—/— (0) | — | False/False | 0 |
| 2 | bread-cooperative-watch | FAILED | 30 | 31/1 | 38.05 | 26 | 0/1/1 (3) | 67,997,759 | False/False | 0 |
| 2 | farm_easy | PASSED | 7 | 7/0 | 37.17 | 5 | —/—/— (0) | 56,039,257 | False/False | 0 |
| 2 | farm_from_scratch | FAILED | 80 | 87/7 | 17.50 | 33 | 1/1/140 (40) | 47,159,698 | False/False | 0 |
| 2 | farm_harder | FAILED | 30 | 32/2 | 15.53 | 19 | 1/1/1 (6) | 35,335,848 | False/False | 0 |
| 2 | get_water | PASSED | 10 | 11/1 | 11.93 | 6 | 1/1/1 (3) | 19,270,378 | False/False | 0 |
| 2 | iron-pickaxe | FAILED | 80 | 80/0 | 11.44 | 39 | 1/1/22 (40) | 25,720,730 | False/False | 0 |
| 2 | pickup | PASSED | 1 | 1/0 | — | 0 | —/—/— (0) | — | False/False | 0 |
| 2 | sea_grass | FAILED | 5 | 6/1 | 27.65 | 2 | 0/0/0 (1) | — | False/False | 0 |
| 2 | underground | PASSED | 1 | 1/0 | — | 0 | —/—/— (0) | — | False/False | 0 |

Batch 1 finished with five passes and four failures. Both harder farming
scenarios exhausted their planner-turn budgets. Bread exhausted its 48,000-tick
budget at 27 turns. Both bread graph attempts had already failed `no_route`;
the planner then marked its objective `BLOCKED`, and final task execution was
`IDLE`. The last recorded crop inspection (agent tick 1,270) saw three wheat
blocks at age 0. This was not a native graph suspended pending growth. Seagrass
gave up after its graph reported an illumination
failure and then no route. These are current-behavior observations; Phase 0
makes no gameplay fixes. Batch 2 matched eight outcomes; iron pickaxe instead
exhausted its 80-turn budget, producing four passes and five failures.

All 18 completed evaluation recordings have no event or debug-timeline gaps and zero
unknown wake attributions. Seagrass has four/three planner journal/LLM entries in batch 1 and six/five
in batch 2 after terminal cancellation, so its token rates are unavailable.
Single-call underground and pickup runs have no normalized rate. Journal/LLM
timestamp skew within one call cannot establish a useful rate window.
No addressed-chat latency samples were observed; fixture tests validate the
calculation, but these runs provide no live chat-latency evidence.

## Automatic playtest

The latest documented prior run supplied the unchanged source world
`run/playtest-inputs/peaceful-nether-provider-reset-20260921`, the recorder profile
`/Volumes/wd_black_1tb/mc-play-recorder/mods/recorder-mod/build/recording-profile/recorder-profile.jar`,
and its 1,800-second limit. The objective was:

> Continue survival progression on Peaceful difficulty until you enter
> minecraft:the_nether through a working Nether portal. Success requires actually
> entering the Nether. Gather and craft the needed materials, build and light a
> portal, and enter it. Preserve the existing camp. Report suspected interface
> bugs using something_wrong with relevant work IDs and expected versus observed
> behavior.

The run ended at the time limit with `harnessStatus: OK`, `recordingComplete:
true`, complete screen video and a saved world. No `something_wrong` report was
raised. The final dimension was `minecraft:overworld`; the Nether objective was
not achieved. The final block-breaking job remained `RUNNING`, with
`lastPathEvent: waiting_for_aim targetPos=3,133,-4`. A complete recording is not
proof of successful gameplay or recovery.

| Metric | Result |
| --- | ---: |
| Full server window | 7–35,996 (35,989 ticks) |
| Initial turns / calls / retries | 28 / 29 / 1 |
| Initial requests/minute | 0.933619 |
| Tool follow-ups/turn | 0 |
| Empty wakes | 20 (W4: 2, W9: 18) |
| Dropped attempts | 2, G5.queued_tool_work |
| Outcome latency p50/p90/max | 1 / 1 / 1 agent ticks (3 samples) |
| Addressed-chat samples | 0 |
| Recorded planner tokens | 935,320 |
| Planner tokens/server-hour | 1,871,211.759149 |
| Event / timeline / LLM gaps | absent / absent / absent |
| Unknown wake attribution | 0 |

All initial turns were owned by `controller`. Attributed path rates were W1:
0.033344, W3: 0.033344, W4: 0.100031 and W9: 0.766901 requests/minute. Tokens were
complete and all planner usage fell inside the explicit Play window.

## Phase 2 tolerance

Final numeric intervals await the repeated batches. Apply the same scenario
worlds, model, configs, budgets and metric definitions:

- Pass/fail must be no worse than each scenario's worst baseline result. A
  consistently passing scenario must still pass; baseline failures do not excuse
  new failure classes. Report both per-scenario outcomes and aggregate pass count.
- Planner turns and request-span rates must fall within `[0.9 × minimum,
  1.1 × maximum]` over completed baseline batches. This implements observed spread
  plus 10%; it is an empirical guard, not a statistical confidence interval.
- An unavailable baseline rate cannot establish a rate regression threshold.
  Whole-run evaluator rates need additional endpoint instrumentation before they
  can be claimed. Compare the automatic playtest with its full-window definition.
- Wake-path proportions may change only with an explicitly characterized defect
  fix and its reviewed golden diff. W9 result reviews must remain accounted for.
- Reject unexplained wake attribution or event/timeline loss before interpreting
  latency and rate changes. Missing token evidence cannot support a token claim.

## Retained evidence

Run bundles and derived ledgers remain ignored; this document is the tracked
baseline. Paths are relative to the repository unless absolute:

- Valid batch 2: `eval-output/phase0-wake-baseline-frozen/batch-2/20260927-035522-277283-65787/`.
- Valid batch 1: `eval-output/phase0-wake-baseline-frozen/batch-1/20260927-031059-745572-54856/`.
- Excluded startup attempt: `eval-output/phase0-wake-baseline/batch-1/20260927-030358-669411-52095/`.
- Automatic index: `automatic_playtest/20260927-032451-928170-59722-6ff44014-7920-4964-9df1-72f4368918a6/summary.json`.
- Portable Play: `automatic_playtest/v1/airicraft-evaluation--b84c5d14-38b8-4019-9477-3586a928cf5d/players/AiricraftTest--d0a06f8c-4222-3e72-988a-8e0924bde20d/plays/20260926T192513.485Z--e7831546-4800-4942-9fee-c1248e0fdfea/`.
- Ledger analysis: `.superpowers/sdd/2026-09-26-planner-event-system-phase-0/`.

The Play extension `extensions/airicraft.playtest/` contains compressed flight
streams and `playtest.json`; decompress the four ledger streams into a separate
analysis directory and copy `playtest.json` alongside them. Do not alter the
published artifact. Run `python3 scripts/wake_ledger.py ledger <run-dir>` or
`summarize <run-dir>...` to reproduce metrics.

## Validation

The final implementation build passed at `677cd8cc` in an isolated checkout:
1,719 root tests, 95 wrapper tests and 20 JourneyMap compatibility tests, with
three opt-in root tests skipped. The offline ledger suite passes 13 tests.
The characterization harness settles event-callback submissions before advancing
its fake clock; this removes a worker-scheduling race without changing runtime
behavior. The focused verification passed 153 tests including 100 repetitions of
the death/respawn case. Its reviewed golden changes affect only four request-tick
fields, with no wake, evidence or outcome changes.
