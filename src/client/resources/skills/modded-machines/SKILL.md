---
name: modded-machines
description: Using and building modded machines (Create): hand cranks, held clicks, facings, pressing.
---

Modded blocks are ordinary blocks to use_block and place_block; read their state with inspect_world and recipes with search_recipes before building.

Blocks that act only while clicked (a Create hand crank) need use_block with holdTicks: it repeats the click every 4 ticks like a held right mouse button, 20 ticks per second, maximum 1200. One click turns a crank for about half a second. Stay within reach; the task fails with out_of_range_during_hold if you leave. Turning a crank costs hunger, so eat first for long holds.

A hand crank must be attached to a kinetic block: its back faces the first shaft, cogwheel or gearbox of the line, so a crank at the north end of a shaft line along z is facing=north. A crank on stone or any other non-kinetic block spins nothing.

Item processing machines act on what lies below them: a mechanical press needs one empty block between its head and the depot or belt under it. Put items on a depot with use_block and itemId; the press works the stack while the line turns, so crank for longer to process more.

When building a line with construct_blueprint, give exact block states, such as create:shaft[axis=z]. Create picks some facings from the neighbouring kinetic blocks, so if construction reports placement_state_requires_different_approach, flip that block's facing and redraft.
