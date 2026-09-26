# Attention rule engine spike — 2026-09-27

## Decision

**Proceed as designed for the steady-state Phase 1 path, with startup latency visible in the runtime.** The median of three corrected runs has a warm p99 of **1.462 ms including worker handoff after 500 rule steps**, an **80.8 ms** first step, and **411 ms** for context build and module evaluation. These meet section 4.12's proposed warm 2 ms, cold 100 ms, and build 1 s gates. The first 200 handoff steps after context creation have a much higher median p99 of **6.955 ms**. The rule worker is asynchronous, but Phase 1 should instrument this startup interval in a live client before treating the steady-state number as a frame-time guarantee. Run 2 is the representative median run by the primary warm handoff p99 metric.

## Setup and workload

- Apple M4, 10 CPU cores, 16 GiB RAM, macOS arm64.
- Gradle build JVM: Homebrew OpenJDK 26. Test JVM: JetBrains JBR 21.0.11 (`OpenJDK 64-Bit Server VM`), the repository's default test toolchain.
- `AIRICRAFT_RULE_SPIKE=1 JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home ./gradlew :test --tests '*RuleEngineSpikeTest' --rerun -Dorg.gradle.java.home=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`
- Task 11 `events.jsonl` was unavailable. The synthetic fixture has 20 events of eight repeating types and 50 candidates per step, about 10,976 input bytes. Tick-varying owner, blocked-goal state, seed, evidence coordinates, sources, and candidate IDs exercise the ownership and blocked-goal gates. The representative rule applies planner-table matches, defaults, leaky bucket, sliding and tumbling windows, hourly notice cap, cooldown, garbage filtering, and block clustering. The test confirms window, cooldown, and notice-cap reasons occur in decisions. The type mix and planner override remain fixed, so this is still narrower than a playtest trace.
- The sandbox copies `GraalPolicyInvocation`'s builder restrictions and runs on a single daemon worker. Each call resets the statement limit. There are no host objects or event-by-event host calls.

## Corrected three-run measurements

All times are milliseconds. For handoff, a fresh context handles the cold first call, then 200 timed early calls, 299 further warmup calls (500 total), and 2,000 timed warm calls. A separate fresh context handles 500 warmup calls and 2,000 timed direct guest calls; each direct duration is measured inside its worker. Thus direct and handoff are observed after the same fixed number of warmup steps, without letting the direct sample prewarm the handoff context. The handoff duration includes dispatch, JSON work, and return to the caller. The heap sample follows 10,000 workload steps on the handoff context.

| Run | Build | First step | Early handoff p50 / p95 / p99 / max | Warm handoff p50 / p95 / p99 / max | Warm direct p50 / p95 / p99 / max |
| --- | ---: | ---: | --- | --- | --- |
| 1 | 410.852 | 77.340 | 2.213 / 4.401 / 7.641 / 10.910 | 0.889 / 1.267 / 1.516 / 1.893 | 0.880 / 1.118 / 1.322 / 1.513 |
| 2 | 451.984 | 82.787 | 2.340 / 4.329 / 6.955 / 11.768 | 0.928 / 1.239 / 1.462 / 2.163 | 0.888 / 1.109 / 1.315 / 1.920 |
| 3 | 405.106 | 80.828 | 2.029 / 3.958 / 6.816 / 10.125 | 0.920 / 1.174 / 1.354 / 1.729 | 0.875 / 0.989 / 1.131 / 1.625 |
| Median by metric | 410.852 | 80.828 | 2.213 / 4.329 / 6.955 / 10.910 | 0.920 / 1.239 / 1.462 / 1.893 | 0.880 / 1.109 / 1.315 / 1.625 |

Both 50,000 and 200,000 statement limits completed 2,000 steps in each run. The rule's `Date.now()` and `new Date().getTime()` both returned `tick × 50` (10,000 at tick 200); seeded `Math.random()` and full JSON output replayed identically from the same input and state. An intentional throw produced the expected Graal error and the next step succeeded. An infinite loop produced `PolyglotException.isResourceExhausted()` and stopped within one second; after replacing that context, the next step replayed identically. Oversized state produced `state_size_limit` before guest evaluation and the next step succeeded. All three failures completed in under 20 ms in these runs. The one-second deadline matches normal `GraalPolicyInvocation` calls; timeout exceptions cannot pass these cause assertions.

State after 2,000 warm steps was 6,995 bytes, below the 16 KiB cap. The measured output at the end of the heap workload was 10,706 bytes. Used heap after GC, in bytes:

| Point | Run 1 | Run 2 | Run 3 | Median by metric |
| --- | ---: | ---: | ---: | ---: |
| Before context | 21,780,256 | 21,776,272 | 21,743,928 | 21,776,272 |
| After build | 26,887,224 | 26,968,112 | 26,983,424 | 26,968,112 |
| After 10,000 steps | 28,043,288 | 28,118,712 | 28,096,816 | 28,096,816 |

The rule sources remain under `src/test/resources/rules-spike/`. Phase 2 can promote a reviewed version. Live client scheduling, GC interactions, and trace fidelity still need validation before treating this as gameplay latency proof.
