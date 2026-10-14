---
name: modded-machines
description: Using and building modded machines (Create): hand cranks, held clicks, facings, pressing.
blocks: create:*
---

Modded blocks are ordinary blocks to use_block and place_block; read their state with inspect_world and recipes with search_recipes before building.

Blocks that act only while clicked (a Create hand crank) need use_block with holdTicks: it repeats the click every 4 ticks like a held right mouse button, 20 ticks per second, maximum 1200. One click turns a crank for about half a second. Stay within reach; the task fails with out_of_range_during_hold if you leave. Turning a crank costs hunger, so eat first for long holds.

A hand crank must be attached to a kinetic block: its back faces the first shaft, cogwheel or gearbox of the line, so a crank at the north end of a shaft line along z is facing=north. A crank on stone or any other non-kinetic block spins nothing.

Item processing machines act on what lies below them: a mechanical press needs one empty block between its head and the depot or belt under it. Put items on a depot with use_block and itemId; the press works the stack while the line turns, so crank for longer to process more.

When building a line with construct_blueprint, give exact block states, such as create:shaft[axis=z]. Create picks some facings from the neighbouring kinetic blocks, so if construction reports placement_state_requires_different_approach, flip that block's facing and redraft.

Placing a line block by block: the face you place against decides a shaft's axis, so a shaft placed on top of a pillar comes out axis=y and an expectedState of axis=z fails with target_not_visible. Place each shaft against the end of the line (a temporary scaffold block at the far end works; remove it with break_blocks, which needs a pickaxe, once the crank is in), or build the whole line with construct_blueprint. Scaffold blocks need their own support. A repeat call that answers target_material_mismatch with the wanted block as beforeBlockId means it is already placed. Inspect the area with inspect_world before placing or using; an unobserved target is refused with target_not_inspected.

A held click returns RUNNING while it works; read the result with inspect_work or the block's state, not the first reply. A press works about one ingot per 80 ticks of crank, so hold for 80 ticks times the stack size. A working line: shafts axis=z, crank facing=north at the north end, press facing=north at the south end, a depot two blocks below the press head.
