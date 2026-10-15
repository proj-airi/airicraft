---
name: create-machines
description: Create mod machines: hand crank, shafts, water wheel, mechanical press and depot, with working layouts.
mod: create
---

General rules for modded blocks (holdTicks, placement order, inspecting first) are in the modded-machines skill; read it too if you have not.

A hand crank turns only while clicked, so use use_block with holdTicks. One click turns it for about half a second. It must be attached to a kinetic block: its back faces the first shaft, cogwheel or gearbox of the line, so a crank at the north end of a shaft line along z is facing=north. A crank on stone or any other non-kinetic block spins nothing.

A mechanical press needs one empty block between its head and the depot or belt under it. Put ingots on a depot with use_block and itemId; the press works the stack while the line turns. It does about one ingot per 80 ticks of crank, so hold for 80 ticks times the stack size.

Shaft axis follows the face it is placed against: on top of a pillar a shaft comes out axis=y, and expectedState create:shaft[axis=z] fails with target_not_visible. Place each shaft against the end of the line, or build the line with construct_blueprint. Create picks some facings from neighbouring kinetic blocks, so not every facing is attainable in every layout.

A working hand-cranked line: shafts axis=z, crank facing=north at the north end, press facing=north at the south end, a depot two blocks below the press head (one empty block between).

Water wheels need flowing water beside them. use_block refuses to pour water in a cell with fewer than three solid side neighbours, so build a walled source cell in the blueprint and pour into it afterwards with use_block and a water bucket.
