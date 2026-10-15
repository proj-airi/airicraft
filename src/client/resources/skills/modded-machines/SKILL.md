---
name: modded-machines
description: Using and building machines from any mod: held clicks, placement order, processing blocks, reading results.
---

Modded blocks are ordinary blocks to use_block and place_block. Read their state with inspect_world and their recipes with search_recipes before building. A mod's own rules (create-machines for Create) are attached to the result the first time you place or use one of its blocks.

Inspect the area with inspect_world before placing or using; an unobserved target is refused with target_not_inspected.

Blocks that act only while clicked (cranks, levers that need holding) need use_block with holdTicks: it repeats the click every 4 ticks like a held right mouse button, 20 ticks per second, maximum 1200. Stay within reach; the task fails with out_of_range_during_hold if you leave, and with target_replaced_during_hold if the block changes into another block. Holding costs hunger, so eat first for long holds. A held click returns RUNNING while it works; read the outcome with inspect_work or the block's state, not the first reply.

Machines that process items act on what lies below or beside them: leave the gap they need, and put items on the holder (depot, belt, table) with use_block and itemId. Processing takes time per item, so hold or wait for the whole stack.

Blocks that choose their orientation from what they are placed against (shafts, pipes, rails, machines) cannot always take the state you want: the face you place against decides it. Place against the right neighbour, using a temporary scaffold block when needed (it needs its own support; remove it with break_blocks, which needs a suitable tool), or build the whole structure with construct_blueprint and exact block states such as namespace:block[axis=z]. If construction reports placement_state_requires_different_approach, flip that block's facing and redraft. A repeat call that answers target_material_mismatch with the wanted block as beforeBlockId means it is already placed.
