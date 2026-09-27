# Scoped target acquisition

System 2 chooses how specific to be; System 1 handles the physical acquisition loop.

| Intent | Tool | Completion |
| --- | --- | --- |
| Get more logs from this area | `collect_resource` with `resourceKind: WOOD_LOGS` | Requested inventory gain |
| Mine particular block types | `mine_blocks` with `blockIds` | Requested matching block breaks, with bounded drop pursuit |
| Stock up to a total | `ensure_blocks_in_inventory` | Absolute matching inventory count |
| Edit a specific block | Existing `break_blocks` with observed coordinates | Confirmed block change |

The first three accept the same optional `constraints` object. For example:

```json
{
  "resourceKind": "WOOD_LOGS",
  "quantity": 8,
  "constraints": {"surfaceOnly": true, "radius": 24}
}
```

Omitted constraints select a loaded area within 16 horizontal and 16 vertical blocks of the position at submission. `radius` and `verticalRadius` each accept 1–32. An optional `center: {"x": 284, "y": 64, "z": -141}` scopes a particular area. The center stays fixed during movement, retries and a resumed planner-tool job. The player must be inside the scope to begin; navigate to a distant area before collecting there.

`surfaceOnly` admits the top ground layer (or water surface) and positions above it; it does not admit submerged work. The ground classification ignores logs, leaves and non-colliding vegetation so a tree canopy does not count as a cave roof. It is a geometric constraint, not a named biome or a claim that an open ravine floor is a desired destination. Sources, work positions and drops must qualify. Acquisition stops if the player leaves the area or moves below its surface; the survival reflex retains priority. This checks observed travel each client tick, rather than constraining Baritone's internal A* search space in advance.

The acquisition executor selects loaded targets, finds an interaction position, navigates, breaks the exact source and pursues matching drops. It can clear leaves obstructing a source. For fully buried ore, System 1 also considers excavatable two-block-high work spaces on each horizontal side of the known source, at source height or one block below. Feet and head space must meet the scope constraint and contain no fluid, unbreakable block, block entity or excluded contact hazard; the floor must provide safe solid support. Baritone's A* navigation can excavate a route to that exact work space. The source remains the exact harvesting target, with inventory pickup still required for collection. Failed approaches reject that work position rather than every side of the source. It uses eye-based interaction reach. Production mining dispatch no longer invokes Baritone's general `mineByName` process. The existing explicit underwater-harvest executor retains its separate breathing and movement behavior.

A source with no open or excavatable work position is skipped. Failed or stalled approaches are excluded for that attempt; movement has an 80-tick no-progress limit, but productive excavation may continue within the overall attempt budget. Breaking has a 240-tick limit, pickup settling is bounded, and the whole attempt has a 2,400-active-tick budget. A partial result does not satisfy an inventory goal. Missing targets, failed pickup and scope exits return terminal evidence to the planner; constraints are never silently widened. Excavation reaches known ore in the loaded search volume; it does not explore unloaded terrain or dig prospecting tunnels without a known source.

Current phase, selected target and work position, inventory count and rejected-target reason appear in task execution snapshots and the flight recorder. These details are available for diagnosis without requiring System 2 to choose every block or movement.

The existing illumination admission rule still applies to `mine_blocks` and `ensure_blocks_in_inventory`; a surface constraint is not permission to override a no-torch rejection at night.

## Nearby ore opportunities

While an ore acquisition task is active, System 1 may stop for an exposed ore block within interaction reach, collect its drop, then resume the original target. Gathering logs or other non-ore resources does not trigger this policy. Once a `mine_blocks` break count is reached, it may also break nearby exposed blocks of the requested ore before reporting completion. These extra breaks do not satisfy a different requested block type. The executor uses the same scope, tool, session, and reflex ownership checks as the foreground task; it does not excavate a detour to reach an opportunity. A failed optional break or pickup does not fail the requested task. The terminal task message lists extra breaks by block id.

Each optional stop also records semantic `task.mining_opportunity` observations for the next planner decision: requested block ids, optional block and mapped drops, block position, side-ore versus post-quota cleanup, and whether the break and matching inventory gain were observed or the attempt ended unconfirmed. These observations do not independently wake the planner mid-task. The planner's task follow-up includes them even when an inventory-based parent task completes before the mining executor emits its own terminal result. A break observation is not a pickup claim; current inventory remains authoritative.

This policy starts enabled with at most six extra blocks and 200 active ticks spent on them per mining task. `configure_opportunistic_mining` changes `enabled`, `maxExtraBlocks` (0–32), and `maxExtraTicks` (0–1200) for the current agent session; the policy resets on reload or world leave. Disable it when an exact edit or quota matters. `collect_resource` and `ensure_blocks_in_inventory` can pick up ore opportunities while working, but their inventory completion boundary still ends the task immediately; post-quota vein cleanup currently applies to `mine_blocks`.
