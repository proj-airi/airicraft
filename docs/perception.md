# Perception

The companion notices what a player standing in its place could notice: an exposed diamond vein beside the path, a
player or a named animal coming into view, bread someone dropped, dusk falling. The planner learns it as an ordinary
event (`perception.*`) that it can act on or ignore. Nothing tells it what to do about it.

## Honesty

Noticing is split in two:

- **Java sensors decide what could be perceived.** A block is a candidate only when a raycast from the eyes reaches a
  face exposed to air or another non-opaque block. A block sealed in stone is never a candidate, so there is no X-ray.
  An item or entity must be in line of sight within range.
- **The salience rules decide what matters.** A sandboxed GraalJS module (`salience/default.js`, overridable) turns
  candidates into percepts and drops the rest with a reason. It sees plain records only, never the live world. See
  [attention-rules.md](attention-rules.md#salience-rules).

Each thing is a candidate once: blocks are remembered for 10 minutes and entities for 5, per world and dimension.
Items are remembered for their lifetime.

## Sensors

All sensors live in `agent.perception` and run once per client tick. Each has a pure core (unit-tested with synthetic
samples) and a thin client adapter.

| Sensor | Produces |
|---|---|
| `physical`, `item`, `damage`, `nearby` | The existing observers, unchanged: `player.physical`, `social.item_offered`, damage attribution, `social.player_*_nearby`. |
| `notable_blocks` | Block candidates: an incremental, nearest-first scan of the radius for the salience module's `interests`. |
| `dropped_items` | Item candidates: once per item entity, after the offer inference had its ticks; offered and own-mining drops are marked. |
| `entities` | Entity candidates on entering range with line of sight (enter/exit hysteresis); `entity_lost` when a noticed entity leaves. Reflex-tracked hostiles are flagged. |
| `environment` | Transitions only: dusk, dawn, rain and thunder, the biome at the feet, and darkness at the feet. |

## Budgets

`config/airicraft/agent.yml`, reloadable:

```yaml
perception:
  enabled: true
  radius: 12              # blocks scanned and items noticed within this distance
  positionsPerTick: 256   # block positions scanned per tick
  raycastsPerTick: 16     # raycasts per sensor per tick
  candidatesPerStep: 50   # candidates handed to one salience step
  entityEnterRange: 16    # an entity enters perception within this range, with line of sight
  entityExitRange: 20     # and leaves beyond this range
```

`airicraft agent debug state --verbose` reports each sensor's mean, p99 and maximum cost per tick, and the salience
step timings.

## State as changes

Observations report state as changes (`observe`, both planner backends). An observation carries the full `current`
state when:

- it is the first one, or the world changed, or events were lost, or history was compacted;
- the planner asked for it by calling `observe` itself;
- 20 observations or 6,000 ticks have passed since the last full state;
- the changes would be larger than the state.

Otherwise it carries only what changed:

- inventory deltas with resulting totals (`oak_log +4 (13)`);
- vitals before and after (`health 20.0 -> 14.0`);
- moves to another block;
- a JSON Patch for the rest.

Velocity and sub-block movement are left out. `inspect_inventory` still gives the full inventory on request.

## Verifying

Without a model, the evaluator's `notice_walk` course checks honesty end to end. Start `scripts/codex-driver-evaluator`,
join a disposable singleplayer world, then run:

```sh
scripts/perception-baseline --runs 3
```

It builds a dark corridor at Y=200 and walks it with `navigate_to`. The corridor holds an exposed diamond vein, an
emerald ore sealed in the wall, bread and cobblestone. The run passes when the vein and the bread are noticed, the
sealed ore never is, and the cobblestone is dropped as garbage. The report also records each sensor's cost and the
percept wake decisions per minute. Each run builds the course in a fresh place, since the agent remembers what it
noticed.

`scenarios/notice-walk` is the same course for a model run. Its checks cover perception; what the planner chooses
to do about what it noticed is for the reviewer.
