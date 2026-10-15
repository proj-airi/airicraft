---
name: beds
description: Beds and nights: a respawn point near your work so a death costs a short walk, sleep_in_bed through the night, and shelter_for_night when you have no bed.
---

A death drops everything and sends you back to the world spawn, which can be a thousand blocks from your base or portal. Set your respawn point early, as soon as you have a base or a portal you will come back to.

Make a bed from three wool and three planks of one kind at a crafting table. Wool comes from sheep: kill three (each drops one) or shear them with shears (two iron ingots). The three wool must be the same colour (three white wool make a white bed); mixed colours make nothing.

Place the bed on flat ground near your base or portal with two free cells in a row and a free cell beside it, then call sleep_in_bed (pass its x,y,z, or nothing for the nearest bed in sight). Do not use_block a bed: it reports success whether or not you lay down. By day sleep_in_bed only sets your respawn point and ends respawn_set. At night (day time about 12542 or later, sooner in rain) it waits for monsters near the bed to go, lies down and stays in bed until morning; slept_until_morning means the night was skipped, which is the safest way through a night on the surface. Its failures say what to do: monsters_nearby names the monsters within about eight blocks (fight them or wall the bed in, then call again); not_sleeping quotes the server (occupied, obstructed: break the block over the bed); woken_early means you were attacked or woken and it is still night; timeout in multiplayer means not enough players slept. When the night is skipped, the observation's time of day turns to day and a dawn event follows; query_world's metadata.timeOfDay jumps to about 0, while metadata.worldTime (the game clock) keeps counting and never says whether it is night. Carry the bed with you on a long trip (break it, it drops itself), place it at dusk wherever you are, walled in by a few blocks, and call sleep_in_bed.

Without a bed, call shelter_for_night at dusk (day time 11000 or later) whenever you are on the surface away from a closed base. It digs three blocks down where you stand, roofs the hole with a carried block, waits until morning and no mob is by the hole, then climbs back out, all as one task; carry a few dirt or cobblestone for the roof and the climb. Do not dig a pit and place a roof through separate tool calls: place_block walks out of the pit to stand where it can place, leaving you outside at night.

Never use a bed in the Nether or the End: it explodes. If the bed is broken or blocked when you die, you respawn at the world spawn again.
