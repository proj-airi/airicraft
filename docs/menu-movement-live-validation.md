# Menu closing during movement: live validation

Validated `635b9b53` on 2026-09-29 in external Codex-driver mode, Minecraft
1.21.8, with JourneyMap and REI loaded. All nine checks passed without a code
change. The embedded planner remained disabled (`codexDriverActive: true`).

## Fixture

Created a new disposable save, `CodexMenuFixture-20260929`, seed `42`, in an
isolated game directory. Enabled commands in that save while its client was
fully stopped. Constructed the fixture with vanilla commands sent through
`/v1/player/command`; agent actions used the wrapper's `agent tools call`.
No existing save or normal client configuration was changed.

The fixture was a stone platform at Y=99, a crafting table at `(1,100,1)`, and
a furnace at `(1,100,-1)`. Commands supplied raw iron, coal and oak planks.
Tests ran in survival mode with peaceful difficulty. A later water pool had
water at `(-2,96,4)..(2,99,8)` with stone walls and floor.

Core construction commands:

```text
fill -16 99 -16 24 99 16 minecraft:stone
fill -16 100 -16 24 108 16 minecraft:air
setblock 1 100 1 minecraft:crafting_table
setblock 1 100 -1 minecraft:furnace[facing=west]
tp @s 0.5 100 0.5 -90 20
give @s minecraft:raw_iron 8
give @s minecraft:coal 2
give @s minecraft:oak_planks 16
```

## Results

Menu state came from the live bridge's `currentScreen`: crafting was
`class_479`, furnace was `class_3873`, and closed was `in_game`. Movement
completion required the matching task's terminal success plus observed
position; an accepted tool call alone was insufficient.

| Check | Live outcome |
| --- | --- |
| Baritone, crafting menu | Open menu closed during navigation; reached `(8,100,0)`. |
| Baritone, furnace menu | Open unused furnace closed; reached `(8,100,0)`. |
| Airicraft backend, crafting menu | Open menu closed; reached `(8,100,0)`. |
| Airicraft backend, furnace menu | Open unused furnace closed; reached `(8,100,0)`. |
| Baritone, early smelting collection | Furnace remained open in `waiting_for_output`; collection succeeded with four ingots. |
| Airicraft backend, early smelting collection | Furnace remained open in `waiting_for_output`; collection succeeded with two additional ingots. |
| Menu-only smelting process | Started from `OPEN_SCREEN`, moved to `(4,100,0)` with the same furnace menu retained, then successfully collected four ingots. |
| Normal crafting | `oak_planks_x8_to_chest` succeeded and added one chest to inventory. |
| Direct underwater movement | Crafting menu was open at `(0,96,6)`; it closed during `underwater_recovery:ascending`; `return_to_surface` succeeded at `(-3,100,6)` with reflex idle. |

Final inventory contained ten iron ingots and one chest. Both navigation
backends were confirmed with `agent debug navigation state`; the isolated
configuration was reloaded between backend tests after all work completed.

## Evidence and limits

Local artifacts are under `/tmp/airicraft-menu-live-20260929/`:

- `validation-summary.json`: nine asserted results and corresponding work IDs.
- `*.terminal.json`: retained terminal work results.
- `*-status.json`, `collect-wait-agent.txt`, and `direct-surfacing-agent.txt`:
  menu, position, and active executor evidence.
- `commands.jsonl`, `fixture-blocks.txt`, and inventory captures: fixture
  construction and resulting world/inventory state.

These are controlled live gameplay checks, not an autonomous planner test.
The nonempty-cursor guard remains unit-tested only. First-person screenshot
captures hide menus, so menu assertions use runtime state rather than pixels.
The isolated test client was stopped after validation; the disposable save and
artifacts were retained for reproduction.
