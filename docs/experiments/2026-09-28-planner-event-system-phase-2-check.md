# Planner event system Phase 2 live check

**Status (2026-09-28):** the live smoke checks and replay of two recorded runs passed without a planner model. The smoke found that "warm" was too weak for the interpreter-only engine, which is now fixed by an off-thread warm-up. The paired A/B (plan Q3) is **not run**; it needs a planner model.

## Setup

- Dev client: `./gradlew runClient -Pairicraft.includeCompat=false`, under Xvfb with Mesa llvmpipe (software rendering, 4 cores).
- Driving: `-Pairicraft.codexDriver=true`. The agent was driven through `airicraft agent tools call`, with no LLM configured.
- Recording: `-Pairicraft.automaticPlaytest=true` wrote `attention-decisions.jsonl` next to `events.jsonl`.
- State reads: bridge JSON from `/v1/agent/debug/state`, and the dashboard in headless Chromium.
- Worlds: `run/saves/smoke` from `scenarios/pickup` and `run/saves/smoke2` from `scenarios/iron-pickaxe`.

## Smoke checks

| Check | Result |
| --- | --- |
| Decisions recorded and shown | Each routed event had one decision with stage, rule id and reason. They appeared in the bridge debug state and in the dashboard Attention view (live screenshot checked), with engine counters, the last failure and pending wakes. |
| Override loaded by `airicraft reload` | A module that never wakes and counts events in its state took effect. `social.system_message` was decided `RULES / NONE / override.smoke`, `stateBytes` was 10 and the module read `config:rules/attention.js`. Direct chat still went to the constitution (`constitution.direct_chat`). |
| Broken override rejected | A syntax error and a module that throws on every step were both rejected with `invalid_config` (exit 4), naming the file, the code (`load_failed`, `guest_error`) and the guest message. The previous override kept running. |
| Broken override at startup | The throwing module was left in config and the client restarted. Three events were decided by the reference (`FALLBACK`). Then `rules.reverted` was published, a warning was logged and the bundled module took over. |
| Clamp | With the never-wake override loaded, a failed `diamond_pickaxe` action goal produced `action_graph.goal_terminal` → `CLAMP / IMMEDIATE / HIGH`, with reason "protected type: a rule may not silence this wake" and `clamps: 1`. |
| No fallback after warm-up | Run 2 with the bundled module: 48 steps, 0 fallbacks, 0 failures. |

The driven activity:

- Walking.
- Collecting logs, dirt and cobblestone.
- Crafting planks, sticks, a crafting table and a wooden pickaxe.
- Two action goals: a stone pickaxe, which succeeded, and an iron ingot, which failed.
- A planner rule `quiet-dirt` (`ignore` dirt pickups).

The live decisions were as the reference predicts:

- Pickups during a collect job were owned by it.
- Crafts were held while a craft tool call waited for its result.
- Mining pickups used the bundled default rule.
- Dirt pickups matched `quiet-dirt`.
- The failed goal terminal was a `HIGH` wake; the succeeded one was `graph.terminal_not_failed`.

## Replay

`./gradlew attentionReplay -Pairicraft.replayRun=<run>` then `python3 scripts/wake_ledger.py replay-summary <run>`:

| Run | Decisions | Replayed | Raw-only (no inputs) | Missing events | Recorded ≠ reference | Reference ≠ rules |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| smoke-1 (pickup world) | 49 | 11 | 38 | 0 | 0 | 0 |
| smoke-2 (iron-pickaxe world) | 162 | 48 | 114 | 0 | 0 | 0 |

Replayed types:

- `pickup.item_picked_up`: 33
- `crafting.item_crafted`: 15
- `action_graph.goal_terminal`: 2
- `social.system_message`: 4
- `session.*`: 4
- `planner.goal_set`: 1

Run 1 includes the three `FALLBACK` decisions from the startup revert. The replay compares outcomes, not stages, so they match.

## Step latency (found by this check)

The first smoke client reported `maxStepMicros` 671,216: one synchronous step took 0.67 s on the client thread. GraalJS runs interpreter-only on the JBR, and "ready" meant only "loaded". A microbenchmark (2,000 steps of the bundled module, test JVM) showed step times in µs:

| Steps | Before warm-up: median | Before: p99 / max | With 600 warm-up steps: median | With: p99 / max |
| --- | ---: | ---: | ---: | ---: |
| First | — | 166,584 | — | 6,167 |
| 10–100 | 2,527 | 10,845 | 463 | 4,237 |
| 500–2,000 | 346 | 4,781 / 28,304 | 274 | 3,496 / 11,785 |

`RuleEngine` now runs 600 synthetic attention steps off-thread before it reports ready. The Java reference decides meanwhile, as it already did while the engine was cold. If a module exhausts the statement limit on synthetic input, the context is rebuilt once without warm-up. After the fix, the live client (software rendering) peaked at 17.9 ms over 8 steps and 19.5 ms over 48 steps. Those peaks are consistent with the benchmark's tail on a CPU-bound host. This is still a synchronous cost on the tick, and the worker-thread option from the original Q2 remains available if it matters on real hardware.

## Build after the fixes

`./gradlew build` passes: root 1,822 tests (4 skipped) and wrapper 95, with 0 failures and no golden changes. `airicraft agent debug state` now prints the engine as `[attentionRules]`, with decision and pending-wake counts; `--verbose` adds the latest decisions.

## Found, not fixed here

- **Recorder cursors after reload.** `airicraft reload` creates a new runtime whose event sequence restarts at 1. `RuntimeFlightRecorder` keeps its cursors, so nothing more is written to `events.jsonl` or `attention-decisions.jsonl` for that recording (run 2 stopped at sequence 162). This predates Phase 2 for events.
- **Syntax-error line numbers.** Load failures report positions in the wrapped module source (`Unnamed:4:0`), not the file's own lines.
- **Dashboard row mix.** The Attention view lists raw-only decisions (`catalog.raw_only`) alongside the rest, which crowds the table in busy runs.
