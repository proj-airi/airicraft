---
name: ender-pearls
description: Getting ender pearls from endermen: where they are, the two-high pit that keeps you safe, collect_ender_pearls, and its failure reasons.
---

Ender pearls come from endermen (about one pearl in two kills without Looting). Each eye of ender is one ender pearl plus one blaze powder (crafted from a blaze rod; see the blaze-rods skill); an empty End portal frame takes 12 eyes and locate_stronghold breaks about one throw in five, so 12 to 16 pearls cover a run.

Where: in the overworld endermen spawn in the dark on any surface, so look at night on open flat ground with a long view (plains, deserts, snowy plains). In the Nether, warped forests (blue-green nylium and trees) are full of them at any time, and soul sand valleys have some. The End is full of them too.

Why a pit: an enderman is almost three blocks tall and hits for 7 on normal. It cannot enter a space two blocks high, and it can only hit what its eyes can see. Standing one block down with a block over your head, its eyes on the ground beside you cannot see yours under the roof, but you can hit its legs through the one-block gap. Never shoot an enderman (arrows, tridents and snowballs make it teleport away), never fight one in water or rain (it teleports), and do not look one in the eyes from close by (within about four blocks it teleports instead of coming). Do not wear a carved pumpkin: it stops a look from angering them.

Bring a sword (or axe), cooked food, armor and at least 3 cobblestone, netherrack or planks for the roof: not dirt, sand or gravel, which endermen pick up (and would take the roof with them). A pickaxe or shovel to dig the pit helps.

Once an enderman is in sight (within about 48 blocks), call collect_ender_pearls (count defaults to 12). It digs the pit next to you, roofs it, angers one enderman at a time by looking at its eyes from far away, hits it when it comes, and picks up the pearls (going out for far ones only when no angry enderman is near). Without roof blocks or diggable ground it fights in the open, only at 14 health or more, with a shield up between swings if you carry one. Do not use attack_entity on endermen.

On failure the reason starts with pearls=n (what you carry now):
- no_endermen_found: none in sight (at day in the overworld they are rare). Wait for night or move to more open ground, or go to a warped forest in the Nether, then call again.
- raining: rain makes overworld endermen teleport. Wait for it to stop (shelter_for_night or sleep), or hunt in the Nether.
- low_health: the body got low with an angry enderman close and no closed pit; eat, heal and call again with roof blocks.
- out_of_food: health cannot come back without food. Get food first.
- wrong_dimension: only the overworld, the Nether and the End.
- timeout: pearls so far stay in the inventory; call again to go on.
