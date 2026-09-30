# Cave route mapping

`map_cave` is a read-only System 1 geometry query. System 2 chooses a branch or remembered destination; the mapper supplies a route through the connected cave floor. Navigation executes the short movement segments.

```json
{"radius":24,"verticalRadius":16,"opennessWeight":1}
```

Add `"target":{"x":175,"y":22,"z":398}` to route to an exact feet coordinate. Without a target, the tool ranks four separated chamber/frontier destinations and returns a route to the first. These are geometric suggestions, not unvisited locations: compare them with place memory and recent travel before choosing. A map boundary is an exploration frontier, not necessarily a cave entrance or exit.

The snapshot covers loaded blocks within horizontal radius 8–32 and vertical radius 4–24. It does not load chunks. Connected-air flooding stops at 32,768 cells and reports truncation. Supported floor connectivity permits one-block steps and one-cell gap jumps with clearance, excluding known hazards and unknown cells. Deeper drops are excluded because ordinary passage travel should be retraceable without building footholds. Swimming, climbing, excavation and construction need separate actions.

Routing adds a bounded penalty for narrow floor space, using reachable local floor area within three movement edges. `opennessWeight:0` minimizes route distance; values up to 2 favor wider alternatives. This measures usable floor area, not the volume of a tall shaft. Routes are capped at 256 cells. Waypoints preserve turns, every descending landing and gap crossings while merging straight flat/uphill runs of up to four movement edges. Downhill compression was reverted after a live cancellation and overshot landing.

The result includes the full route, waypoints, alternative destinations, reachable/air/floor counts, truncation and capture/total timings. Geometry includes space around corners, so this is **not line-of-sight perception**. It exposes no ore identities and does not register hidden blocks as observed acquisition sources. Continue using `survey_cave` for visible ore. Unknown, unreachable or truncated results do not establish that a branch is a dead end.

## Live driver

The checked-in playtest follower consumes one saved map through the public wrapper CLI:

```sh
source .envrc
export AIRICRAFT_BRIDGE_STATE_FILE=/absolute/path/to/bridge-state.json
wrapper/build/install/airicraft/bin/airicraft agent tools call \
  --name map_cave --arguments '{}' > /tmp/cave-map.txt
scripts/playtest-follow-cave-route /tmp/cave-map.txt \
  --incident-output /tmp/cave-route-pause.txt --pause-on-complete
```

Start the follower with simulation running and the player still at the map origin. It disables navigation digging, placement and inventory tool swaps. It tracks exact jobs and polls position against the current segment's two-block corridor. A detour, failed job, reflex interruption or observation timeout pauses the game and writes the pause state; it does not cancel or restart the job. Export the rolling recorder before diagnosing. A 30-second observation timeout is not proof of a failed path.

This is a local playtest follower, not a native multi-waypoint movement executor. Polling is not a hard per-tick movement constraint. Terrain changes require remapping; mobs, food, torches and visit history remain contextual decisions. Configure automatic lighting separately. Prefer HotSwap for implementation changes; a normal runtime reload resets agent state and can release tick-debug pause, so read fresh tick state afterward.

## Verification

Eleven focused tests cover ravines, unknown boundaries, step/headroom limits, hazardous gaps, broad-route preference, waypoint compression, bounded flooding and exclusion of one-way drops. D087 in `autonomous-playtest-log.md` records build results, live routes, recorder exports, the reproduced drop limitation and its correction; automated geometry checks alone are not movement proof.
