# Planner event system: paired same-host A/B

**Result:** Twelve terminal trials completed in one session, with no censored runs. Wall-clock and server-tick rates agree closely under these matched conditions. B has a higher request rate in both farming pairs, a lower rate in both bread pairs, and mixed seagrass contrasts. This does not establish a general Phase 1 rate regression or full evaluation parity.

## Fixed inputs and execution

- A: [PR #81](https://github.com/proj-airi/airicraft/pull/81), `44acc6bc107254dfbed2de6c880083c6dd6d31a6`.
- B: [PR #88](https://github.com/proj-airi/airicraft/pull/88), `34184be759cda766c597fd4a27d9ea9863437644`, its head when this experiment started. This includes the follow-up source fixes after the earlier Phase 1 check.
- One local host: macOS 15.4.1, arm64, `Mac16,10`, 10 logical CPUs, 16 GiB RAM. Session: 2026-09-27 21:40–22:10 UTC+08.
- Same Gradle JVM: OpenJDK 26.0.2. Same evaluator JVM: Zulu 21.0.5+11-LTS (`Zulu21.38+21-CA`), selected explicitly with `JvmVendorSpec.AZUL` and Java language version 21. Both heads retained the same Java 21 compilation toolchain configuration.
- Same isolated worktree, with a clean production rebuild before every trial, then a fixed 15-second interval before launch. Fresh isolated client/world copy per trial; one client at a time (`--jobs 1`), no recorder, fixed player name `PairedEval`.
- Scenario order: `farm_easy`, `sea_grass`, `bread-cooperative-watch`. Each used A–B–B–A: one A→B pair followed by one B→A pair. No favorable-result reruns.
- Original scenario manifests, world archives, private config, model settings and game options were unchanged. Forty-two input-file fingerprints were checked before each trial; planner journals identify `deepseek-v4.1-flash`.
- The same 600-second wall observation cap was declared before any run, measured after the evaluator start response. Reaching it would produce a censored observation, not a scenario failure. **No trial reached it.** Original scenario turn/tick budgets were unchanged.

All six trial builds of each head produced the same main-mod JAR hash. Only the main-mod JAR differs between arms; the wrapper, evaluator and both compatibility JARs are byte-identical across arms.

| Arm | Main-mod JAR SHA-256 |
| --- | --- |
| A | `37fc7190542853b18e254934d2932cda56721c7377142ac39a0520d404ed274c` |
| B | `1f70d69d2561c9c7ce5bc1ad6c2c699e4a8ee3dd3735317767152b2fb09e0dd8` |

The runtime executable was pinned to `/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home/bin/java`. Direct process-path snapshots confirm it for trials 2–12. Trial 1 used the same explicit launcher configuration but completed before that process monitor was enabled; no direct process-path snapshot was retained for it. All 12 harness shutdown records report that their Minecraft process exited, and the final process check found no remaining test client.

## Rate definition

Both rates below use exactly the same first-to-last **initial planner request** dispatch window from `planner-calls.jsonl`. Transport retries, tool-follow-up phases, compilation, client startup and shutdown are outside this rate definition. N is the number of initial requests, including both window endpoints:

```text
wall requests/min = N × 60 / wall_seconds_between_first_and_last_request
tick requests/min = N × 1200 / server_ticks_between_first_and_last_request
```

Wall time comes from `timing.requestedAtUnixMs`; server ticks come from `timeline.submitted.serverTick`. The tick rate is normalized to 20 server ticks/second: one nominal minute is 1,200 ticks. These are request-window rates, not rates over the entire scenario lifetime. One-request or zero-width windows would be unavailable; every trial here has at least four requests. The legacy wake-ledger rate is retained separately in the CSV because it can expand the tick window using other LLM dispatch records; it is not paired with a different wall-time denominator here.

## Results

Arithmetic means of the two trials per arm; rates are requests/minute.

| Scenario | A wall | A tick | B wall | B tick | A requests | B requests | Outcomes A / B |
| --- | ---: | ---: | ---: | ---: | --- | --- | --- |
| farm_easy | 26.75 | 26.69 | 33.69 | 33.69 | 4, 5 | 7, 7 | 2 passed / 2 passed |
| sea_grass | 37.37 | 37.29 | 35.24 | 35.34 | 7, 5 | 8, 4 | 2 failed / 2 failed |
| bread-cooperative-watch | 32.18 | 32.19 | 28.07 | 28.07 | 30, 30 | 30, 30 | 2 failed / 2 failed |

Paired changes are B relative to its adjacent A trial; positive means B submitted requests more frequently.

| Scenario | Pair (A trial, B trial) | Wall change | Tick change |
| --- | --- | ---: | ---: |
| farm_easy | 1, 2 | +15.3% | +15.9% |
| farm_easy | 4, 3 | +40.2% | +40.0% |
| sea_grass | 5, 6 | -18.1% | -18.0% |
| sea_grass | 8, 7 | +5.2% | +6.0% |
| bread-cooperative-watch | 9, 10 | -18.2% | -18.2% |
| bread-cooperative-watch | 12, 11 | -7.1% | -7.1% |

Every individual trial, in execution order:

| Trial | Scenario | Arm | Result | N | Wall window (s) | Server ticks | Wall req/min | Tick req/min | Journal latency median (ms) |
| ---: | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | farm_easy | A | PASSED | 4 | 7.815 | 157 | 30.71 | 30.57 | 2034.5 |
| 2 | farm_easy | B | PASSED | 7 | 11.862 | 237 | 35.41 | 35.44 | 1581.0 |
| 3 | farm_easy | B | PASSED | 7 | 13.136 | 263 | 31.97 | 31.94 | 1692.0 |
| 4 | farm_easy | A | PASSED | 5 | 13.158 | 263 | 22.80 | 22.81 | 1803.0 |
| 5 | sea_grass | A | FAILED | 7 | 12.038 | 241 | 34.89 | 34.85 | 1640.0 |
| 6 | sea_grass | B | FAILED | 8 | 16.806 | 336 | 28.56 | 28.57 | 1555.5 |
| 7 | sea_grass | B | FAILED | 4 | 5.724 | 114 | 41.93 | 42.11 | 1524.0 |
| 8 | sea_grass | A | FAILED | 5 | 7.527 | 151 | 39.86 | 39.74 | 1503.0 |
| 9 | bread-cooperative-watch | A | FAILED | 30 | 54.681 | 1093 | 32.92 | 32.94 | 1295.0 |
| 10 | bread-cooperative-watch | B | FAILED | 30 | 66.858 | 1337 | 26.92 | 26.93 | 1499.5 |
| 11 | bread-cooperative-watch | B | FAILED | 30 | 61.620 | 1232 | 29.21 | 29.22 | 1199.0 |
| 12 | bread-cooperative-watch | A | FAILED | 30 | 57.234 | 1145 | 31.45 | 31.44 | 1349.0 |

## Interpretation and limits

- Observed request-window tick throughput ranged from **19.916 to 20.090 server ticks per wall second**. The wall/tick denominator discrepancy in the earlier cross-session comparison is absent here. This is the appropriate matched comparison for these heads and scenarios; it does not retrospectively identify why the earlier host/run slowed down.
- Farming B was higher in both pairs (+15.3% and +40.2% wall rate), and made 7 requests in each run versus A’s 4 and 5. B also used different tool sequences and had shorter journal model-call latency medians (1,581/1,692 ms versus A’s 2,034.5/1,803 ms). These are observed trajectory/provider-timing differences; they do not isolate a code cause.
- Seagrass contrasts changed sign (−18.1%, +5.2%). All four runs ended through the illumination/environment-change → `no_route` failure path. Bread B was lower in both pairs (−18.2%, −7.1%); all four bread runs exhausted the same 30-request budget. A lower request rate is not by itself better gameplay performance.
- All 12 journals matched their reported planner-turn counts. There were **no event gaps, timeline gaps or unknown wake attributions**. All four seagrass runs had the existing planner-journal/LLM-record mismatch, on both heads; journal dispatch timestamps still support the rates above, but token-rate inference is excluded.
- Two pairs per scenario are a small sample with a nondeterministic hosted model and different tool trajectories. There is no statistically supported general regression or equivalence claim. The other six baseline scenarios were not rerun, so this does **not** satisfy the complete nine-scenario Phase 1 gate.

## Evidence and reproduction

The [machine-readable measurements](2026-09-27-planner-event-system-paired-ab.csv) contain the same 12 rows, full source hashes, both matched rates and the separate legacy rate. Private local artifacts are retained under `eval-output/phase1-paired-ab/`: `protocol.json`, `provenance.json`, `results.json`, `analysis.json`, `run.py`, `client`, `pin-runtime.gradle`, per-trial build logs/JAR hashes, runtime process snapshots, complete evaluator bundles and wake ledgers. Raw transcripts/config copies are ignored and are not published.

For each pinned head, the build was:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
./gradlew --no-daemon -Dorg.gradle.java.home="$JAVA_HOME" \
  -Pairicraft.includeEvaluator=true clean :remapJar :wrapper:jar \
  :addons:evaluator:remapJar :compat:journeymap:remapJar \
  :compat:rei:remapJar :prepareHotswapProductionConfigs
```

The local client wrapper fixes the player name and applies a Gradle init script selecting Azul Java 21 for `:addons:evaluator:runClientEvaluator`. It runs `scripts/eval run` with packaging and hot-swap preparation excluded after the clean build. Each serial trial uses:

```sh
scripts/run-evaluation-scenarios --no-recorder --jobs 1 --stop-client-after-scenario \
  --client-command /absolute/path/to/paired-ab/client \
  --output-root /absolute/path/to/trial/evaluation --scenario <scenario-id>
```

Twelve clean packaging builds and twelve terminal evaluations completed as execution steps; this experiment did not rerun the unit-test suites. The prior [Phase 1 verification note](2026-09-27-planner-event-system-phase-1-check.md) remains the historical record of its original build, smoke and incomplete nine-scenario batch.
