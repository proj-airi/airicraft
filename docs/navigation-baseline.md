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

The fixture route can also be called directly:

```json
POST /v1/evaluation/navigation-course
{"action": "list"}
{"action": "build", "course": "door_house"}
{"action": "status"}
{"action": "cleanup"}
```

`cleanup` clears the current course and returns the player to the recorded origin.

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

The script adds `outcome` (`completed`, `failed`, `cancelled`, `rejected` or `timeout`),
`passed`, `healthLost` and the final feet position. Planner-visible events do not
include these metrics.
