---
name: beds
description: Beds and nights: a respawn point near your work so a death costs a short walk, sleeping through the night, and shelter_for_night when you have no bed.
---

A death drops everything and sends you back to the world spawn, which can be a thousand blocks from your base or portal. Set your respawn point early, as soon as you have a base or a portal you will come back to.

Make a bed from three wool and three planks of one kind at a crafting table. Wool comes from sheep: kill three (each drops one) or shear them with shears (two iron ingots). The three wool must be the same colour (three white wool make a white bed); mixed colours make nothing.

Place the bed on flat ground near your base or portal with two free cells in a row, then use_block on it. Sleeping in it sets your respawn point; at night it also skips to morning, which is the safest way to get through a night on the surface. You cannot sleep with a hostile mob within about eight blocks ("You may not rest now, there are monsters nearby"): deal with it or wall the bed in first. Using the bed by day may only say you can sleep at night; check the chat message for "Respawn point set". When the night is skipped, the observation's time of day turns to day and a dawn event follows; query_world's metadata.timeOfDay jumps to about 0, while metadata.worldTime (the game clock) keeps counting and never says whether it is night. Carry the bed with you on a long trip (break it, it drops itself) and sleep at dusk wherever you are, walled in by a few blocks.

Without a bed, call shelter_for_night at dusk (day time 11000 or later) whenever you are on the surface away from a closed base. It digs three blocks down where you stand, roofs the hole with a carried block, waits until morning and no mob is by the hole, then climbs back out, all as one task; carry a few dirt or cobblestone for the roof and the climb. Do not dig a pit and place a roof through separate tool calls: place_block walks out of the pit to stand where it can place, leaving you outside at night.

Never use a bed in the Nether or the End: it explodes. If the bed is broken or blocked when you die, you respawn at the world spawn again.
