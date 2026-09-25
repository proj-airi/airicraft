# Navigation baseline

Phase 0 of the [in-house navigation plan](superpowers/specs/2026-09-25-in-house-navigation-and-control-plane.md)
needs a repeatable measurement of navigation that does not depend on a model. The
evaluator builds deterministic courses, `scripts/navigation-baseline` drives
`navigate_to` directly, and every navigation task reports its metrics on the debug
timeline. The same run compares Baritone now with the in-house backend later.

## Courses

Fixture courses are built at Y=200 above the position the player held before the first
course. They sit inside a barrier shell with a barrier floor. `far_xz` uses natural
terrain instead.

| Course | Measures | Expectation | Loadout |
| --- | --- | --- | --- |
| `flat_walk` | 22-block straight walk | arrive | none |
| `staircase_up` | six one-block ascents | arrive | none |
| `drop_3` | a three-block drop | arrive | none |
| `drop_5_stairs` | a five-block drop next to a staircase lane; health lost shows which route was taken | arrive | none |
| `river_crossing` | swimming a six-wide, two-deep channel and climbing out | arrive | none |
| `dirt_wall` | breaking through a two-thick, three-high wall | arrive | none |
| `gap_bridge` | a five-wide gap; bridging with carried blocks | arrive | 64 cobblestone |
| `pillar_pit` | leaving a six-deep pit | arrive | 64 cobblestone |
| `door_house` | leaving a closed room through an oak door | arrive | none |
| `cave_route` | a dark, winding one-wide tunnel with a step up | arrive | none |
| `far_xz` | 320 blocks east over natural terrain, crossing unloaded chunks | arrive | none |
| `travel_bounds_refusal` | strategy travel bounds that end before the goal | refuse | none |

Course geometry lives in `addons/evaluator/src/client/java/ai/moeru/airicraft/evaluator/NavigationCourses.java`.
`NavigationCoursesTest` checks that start and goal cells are standable, the shell is
closed, door halves are paired, and step heights are what each course claims.

Not covered yet: follow (needs a second player), preserved-area refusal (needs a
remembered place), and counts of blocks broken or placed.

## Running

1. Start an isolated driver client with the evaluator:

   ```sh
   export AIRICRAFT_BRIDGE_STATE_FILE="$PWD/run/navigation-bridge-state.json"
   scripts/codex-driver-evaluator
   ```

2. Join a disposable singleplayer world while standing on open ground. The fixture
   clears the player's inventory, sets difficulty to peaceful and the time to morning,
   and does not restore them. Do not use a save you care about.
3. Run the courses:

   ```sh
   scripts/navigation-baseline --label baritone --runs 5
   ```

   Use `--course <id>` (repeatable) to run a subset. The report is written to
   `run/navigation-baseline/<label>-<utc>.json` and a summary table is printed. The exit
   code is 0 only when every run passed.

### Choosing the backend

The client's `navigation.backend` setting (`config/airicraft/airicraft.yml`) picks
`baritone` (the default) or `airicraft`, the in-house planner and motor. It is reloadable
with `airicraft reload`. The Gradle property `-Pairicraft.navigationBackend=<backend>`
overrides it for a launched client, and `scripts/run-navigation-baseline --backend
<backend>` passes that property. `airicraft agent debug navigation state` shows the active
backend.

On the Baritone backend, every navigation is also planned in-house without moving (shadow
mode). The shadow outcome is in the terminal diagnostics under `planner.shadow`, and the
summary counts `shadowFound`. `airicraft agent debug navigation plan --x --y --z` dry-runs
one plan from the player and highlights it.

### Unattended and in CI

`scripts/run-navigation-baseline` does all of the above without a person. It launches
the evaluator in Codex-driver mode in `run/navigation-baseline/game`, loads
`scenarios/farm_easy/world.zip` as a plain save, joins it, runs the courses and stops
the client. It needs a display, so on Linux use `xvfb-run -a python3
scripts/run-navigation-baseline`. Course failures are recorded data; pass
`--require-pass` to make them fail the command.

The `navigation baseline` GitHub workflow runs it under Xvfb with Mesa software
rendering. It runs on manual dispatch (with runs, courses and backend inputs) and on
pushes that change the benchmark or the in-house backend; pushes measure the in-house
backend. It prints the summary and one line per run in the
job log and uploads the report with the client logs.

The fixture route can also be called directly:

```json
POST /v1/evaluation/navigation-course
{"action": "list"}
{"action": "build", "course": "door_house"}
{"action": "status"}
{"action": "cleanup"}
```

`cleanup` clears the current course and returns the player to the recorded origin.

## Baritone baseline (2026-09-25)

Recorded by the `navigation baseline` workflow at commit `07c518c`, run
[36107233049](https://github.com/proj-airi/airicraft/actions/runs/36107233049): five runs
per course, Baritone 1.15.0, `farm_easy` world, headless client under Xvfb. Ticks and path
length are medians.

| Course | Passed | Ticks | Path (blocks) | End distance | Health lost |
| --- | --- | --- | --- | --- | --- |
| `flat_walk` | 5/5 | 85 | 21.7 | 0.30 | 0 |
| `staircase_up` | 5/5 | 83 | 18.67 | 0.09 | 0 |
| `drop_3` | 5/5 | 51 | 13.44 | 0.32 | 0 |
| `drop_5_stairs` | 5/5 | 111 | 23.9 | 0.30 | 0 |
| `river_crossing` | 5/5 | 115 | 18.95 | 0.20 | 0 |
| `dirt_wall` | 5/5 | 125 | 16.92 | 0.23 | 0 |
| `gap_bridge` | 5/5 | 99 | 16.28 | 0.36 | 0 |
| `pillar_pit` | 5/5 | 113 | 17.39 | 0.53 | 0 |
| `door_house` | 5/5 | 43 | 8.79 | 0.21 | 0 |
| `cave_route` | 5/5 | 164 | 39.14 | 0.07 | 0 |
| `far_xz` | 5/5 | 1796 | 354.61 | 0.35 | 0 |
| `travel_bounds_refusal` | 5/5 | 92 | 10.94 | 11.07 | 0 |

No run stalled or replanned. On `drop_5_stairs` Baritone took the staircase lane (path
23.9 against a 17-block straight line, no health lost). On `travel_bounds_refusal` it
walked about 11 blocks to the edge of the bounds, then failed without leaving them.
Runs were nearly deterministic: most courses repeated the same tick count exactly.

## In-house backend (2026-09-25)

Recorded at commit `329b764` by the `navigation baseline` workflow on the `airicraft`
backend, run [36117463790](https://github.com/proj-airi/airicraft/actions/runs/36117463790),
beside a Baritone run with shadow planning on the same commit,
[36117528209](https://github.com/proj-airi/airicraft/actions/runs/36117528209). Five runs per
course; ticks and path length are medians, with the Baritone figure from the same commit in
parentheses.

| Course | Passed | Ticks | Path (blocks) | Health lost | Slowest plan (ms) |
| --- | --- | --- | --- | --- | --- |
| `flat_walk` | 5/5 | 83 (85) | 21.97 (21.7) | 0 | 8.8 |
| `staircase_up` | 5/5 | 85 (83) | 19.01 (18.67) | 0 | 19.1 |
| `drop_3` | 5/5 | 56 (51) | 14.52 (13.44) | 0 | 0.6 |
| `drop_5_stairs` | 5/5 | 97 (111) | 24.21 (23.9) | 0 | 0.7 |
| `river_crossing` | 5/5 | 97 (116) | 18.85 (18.95) | 0 | 1.4 |
| `dirt_wall` | 5/5 | 135 (121) | 18.0 (16.92) | 0 | 2.5 |
| `gap_bridge` | 5/5 | 68 (99) | 14.91 (16.28) | 0 | 1.5 |
| `pillar_pit` | 5/5 | 77 (113) | 13.06 (17.39) | 0 | 1.3 |
| `door_house` | 5/5 | 40 (43) | 8.79 (8.79) | 0 | 1.1 |
| `cave_route` | 5/5 | 181 (164) | 45.48 (39.14) | 0 | 1.2 |
| `far_xz` | 5/5 | 1723 (1796) | 402.32 (354.59) | 0 | 49.6 |
| `travel_bounds_refusal` | 5/5 | 43 (92) | 11.01 (10.94) | 0 | 1.3 |

No run stalled or replanned. Differences from Baritone:

- `gap_bridge`: the in-house planner pillars twice onto the course's barrier shell and walks
  along it instead of bridging five blocks. Both routes are legal in the game.
- `dirt_wall`: it climbs over the wall, breaking three blocks, instead of tunnelling through
  four.
- `far_xz` walks further (402 against 355 blocks) in six segments planned toward waypoints
  64 blocks apart.
- `travel_bounds_refusal`: like Baritone, it walks to the edge of the bounds on the best
  partial path, then fails. It finishes in 43 ticks against Baritone's 92.

Shadow plans on the Baritone run found a path for all 50 loaded-terrain requests Baritone
completed (`far_xz` is planned in segments and `travel_bounds_refusal` has no route). Shadow
search took 3.6 ms at p95 (5.6 ms including the terrain copy). Across all 86 plans on the
in-house run, p95 was 25.6 ms and the slowest 49.6 ms, a `far_xz` segment.

The first in-house run, at `8a4c309` before the heuristic weight, waypoints and edge guard,
passed 58 of 60: two `far_xz` runs walked off a ravine edge at the same place.

## Metrics

Each follow or navigate task ends with a `task`/`terminal_diagnostics` timeline entry.
Its correlation carries `taskId`, `goalType` and `terminalState`, and its payload
`navigation` object has:

| Field | Meaning |
| --- | --- |
| `elapsedTicks` | Client ticks from start to the terminal event, including pauses |
| `activeTicks` | Ticks the executor actively drove the task |
| `pathLength` | Sum of per-tick feet displacement, in blocks |
| `startDistance`, `endDistance` | Feet distance to the goal cell's floor center; horizontal only when `exactY` is false |
| `replans` | Water-stall replans plus follow reacquisitions |
| `stalled` | Whether the stall watchdog ended the task |

The payload's `planner` object comes from the backend. On the Baritone backend it holds the
shadow plan: `outcome`, `reason`, `steps`, `cost`, `length`, `expanded` and `searchMillis`.
On the in-house backend it holds `plans`, `replans`, `lastReplanReason`, `failure`,
`medianSearchMillis`, `maxSearchMillis` and one entry per plan.

The script adds `outcome` (`completed`, `failed`, `cancelled`, `rejected` or `timeout`),
`passed`, `healthLost` and the final feet position. Planner-visible events do not
include these metrics.
