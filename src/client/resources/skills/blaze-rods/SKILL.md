---
name: blaze-rods
description: Getting blaze rods at a Nether fortress: what to bring, collect_blaze_rods, blaze_spawner, and its failure reasons.
---

Blaze rods come only from blazes, which spawn at a Nether fortress's blaze spawners. Each rod makes two blaze powder; each eye of ender takes one, and a brewing stand takes one rod. Seven rods (the default) cover twelve eyes with two to spare.

Bring before entering the Nether: a golden armor piece (piglins attack anyone wearing no gold; see the nether-trip skill), a sword, a bow with 32 or more arrows, a shield, plenty of cooked food, full armor, a stack of building blocks and a pickaxe. Fire resistance potions help most if you can brew them. A water bucket is useless there: water evaporates in the Nether, so it cannot put out burning.

Do not fight blazes with attack_entity or by walking at them, and never break the floor above a spawner room: a playtest body fell seven blocks into one and burned to death in seconds. Find the fortress (find_world_features or explore_for with featureKind nether_fortress), walk to it, then find_world_features featureKind=blaze_spawner. Spawners sit in open rooms or on platforms reached by stairs. Once a spawner or a blaze is in sight (within about 64 blocks), call collect_blaze_rods: it picks a safe spot near the spawner, walks there with short falls only, fights from cover (shield or strafe against fireballs, sword in reach, bow farther out), backs off when more than three blazes see it or health is low, and picks up the rods.

On failure the reason starts with rods=n (what you carry now):
- no_blazes_found: no spawner or blaze in sight here, or the spawner spawned nothing for 90 seconds. Walk the fortress corridors toward the next open room, or a different fortress wing, then call again.
- no_safe_position: every spot near the spawner was near lava, on an edge or out of view, or unreachable with falls of three blocks or less. Build a stair down to the room's level or a small platform walled off from lava with navigate_to and place_block, then call again.
- low_health: get out of sight of the blazes (back down the corridor), eat until health recovers, then call again.
- out_of_food: health cannot come back without food. Leave the fortress and get food first.
- timeout: progress so far is kept in the inventory; call again to go on.
