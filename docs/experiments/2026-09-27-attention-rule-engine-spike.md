# Attention rule engine spike — 2026-09-27

## Decision

**Proceed as designed for Phase 1.** On this developer machine, the median of three runs has a warm p99 of **0.866 ms including worker handoff**, an **85.2 ms** first step, and **406 ms** for context build and module evaluation. These meet the proposed 2 ms, 100 ms, and 1 s gates in section 4.12. This is a workload spike, not a live Minecraft frame-time measurement.

## Setup and workload

- Apple M4, 10 CPU cores, 16 GiB RAM, macOS arm64.
- Gradle build JVM: Homebrew OpenJDK 26. Test JVM: JetBrains JBR 21.0.11 (`OpenJDK 64-Bit Server VM`), the repository's default test toolchain.
- `AIRICRAFT_RULE_SPIKE=1 JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home ./gradlew :test --tests '*RuleEngineSpikeTest' --rerun -Dorg.gradle.java.home=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`
- Task 11 `events.jsonl` was unavailable. The fixture therefore generates 20 varied events and 50 visible candidates per step, with 10,922 input bytes. It exercises ownership, blocked goal, planner table matches, defaults, a leaky bucket, window and notice budgets, garbage filtering, and block clustering. The output is about 4,747 bytes; state after 2,000 steps is 2,234 bytes, below the 16 KiB cap.
- The sandbox copies `GraalPolicyInvocation`'s builder restrictions and runs on a single daemon worker. Each call resets the statement limit. There are no host objects or event-by-event host calls.

## Three runs

All times are milliseconds. Each run uses one context, 2,000 direct guest steps, 2,000 steps measured from caller through the worker and back, then 6,000 more steps for the heap reading. The direct series runs first, so the later handoff series benefits from further interpreter warmup; the two columns should not be compared as an estimate of handoff overhead.

| Run | Build | First step | Direct p50 / p95 / p99 / max | Handoff p50 / p95 / p99 / max |
| --- | ---: | ---: | --- | --- |
| 1 | 406.226 | 86.511 | 0.619 / 1.770 / 2.849 / 8.731 | 0.460 / 0.567 / 0.866 / 1.069 |
| 2 | 380.693 | 85.208 | 0.625 / 1.819 / 2.874 / 9.340 | 0.463 / 0.562 / 0.707 / 1.144 |
| 3 | 428.234 | 84.681 | 0.613 / 1.886 / 3.015 / 7.556 | 0.456 / 0.561 / 0.868 / 1.123 |
| Median | 406.226 | 85.208 | 0.619 / 1.819 / 2.874 / 8.731 | 0.460 / 0.562 / 0.866 / 1.123 |

Both 50,000 and 200,000 statement limits completed 2,000 steps in each run. The rule's `Date.now()` and `new Date().getTime()` both returned `tick × 50` (10,000 at tick 200); seeded `Math.random()` and the full JSON output replayed identically from the same input and state. An intentional throw left the next step usable. An infinite loop was stopped by the statement limit; Graal closes that exhausted context, and a fresh context then replayed the next step identically. Oversized state was rejected before guest evaluation and the next step still ran.

| Heap after GC | Run 1 | Run 2 | Run 3 | Median |
| --- | ---: | ---: | ---: | ---: |
| Before context | 21,740,344 | 21,744,944 | 21,791,080 | 21,744,944 |
| After build | 26,968,224 | 26,867,872 | 26,964,352 | 26,964,352 |
| After 10,000 steps | 28,098,160 | 28,008,472 | 28,106,112 | 28,098,160 |

The rule sources remain under `src/test/resources/rules-spike/`. Phase 2 can promote a reviewed version. Live client scheduling, GC interactions, and fixture fidelity still need validation before treating this as a gameplay latency guarantee.
