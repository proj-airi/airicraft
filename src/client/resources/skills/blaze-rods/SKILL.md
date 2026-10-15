---
name: blaze-rods
description: Getting blaze rods at a Nether fortress: what to bring, collect_blaze_rods, blaze_spawner, and its failure reasons.
---

Blaze rods come only from blazes, which spawn at a Nether fortress's blaze spawners. Each rod makes two blaze powder; each eye of ender takes one, and a brewing stand takes one rod. Seven rods (the default) cover twelve eyes with two to spare.

Bring before entering the Nether: a golden armor piece (piglins attack anyone wearing no gold; see the nether-trip skill), a sword, a bow with 32 or more arrows, a shield, plenty of cooked food, full armor, a stack of building blocks and a pickaxe. Fire resistance potions help most if you can brew them. A water bucket is useless there: water evaporates in the Nether, so it cannot put out burning.

Do not fight blazes with attack_entity or by walking at them, and never break the floor above a spawner room: a playtest body fell seven blocks into one and burned to death in seconds. Find the fortress (find_world_features or explore_for with featureKind nether_fortress), walk to it, then find_world_features featureKind=blaze_spawner. Spawners sit in open rooms or on platforms reached by stairs. Once find_world_features lists a blaze_spawner within about 128 blocks, or a blaze is in sight, call collect_blaze_rods (it finds that spawner itself and walks there): it picks a safe spot near the spawner, walks there with short falls only, fights from cover (shield or strafe against fireballs, sword in reach, bow farther out), backs off behind cover when more than three blazes see it, when hit on the way in or when health is low, and picks up the rods.

Without a bow, a spawner with many blazes is fought from cover around a corner: out of their sight they fly to the body one or two at a time, into sword reach. The task does that itself, but it needs a corner within about 8 blocks; a bow with 32 or more arrows makes the fight far safer, since it can shoot them from the spot. A shield blocks fireballs from one direction only, so it is no answer to several blazes at once.

On failure the reason starts with rods=n (what you carry now):
- no_blazes_found: no spawner or blaze in sight here, or the spawner spawned nothing for 90 seconds. Walk the fortress corridors toward the next open room, or a different fortress wing, then call again.
- no_safe_position: every spot near the spawner was near lava, on an edge or out of view, or unreachable with falls of three blocks or less. Build a stair down to the room's level or a small platform walled off from lava with navigate_to and place_block, then call again.
- overwhelmed: more than three blazes saw the body and no cover was within about 8 blocks. Back out of their sight the way you came, then come at the spawner along a corridor with a corner near it (or wall one off with building blocks), or bring a bow and arrows, and call again.
- low_health: health fell to 12 or less with blazes in sight and no cover could be reached, or it did not come back behind cover. Get out of sight of the blazes (back down the corridor), eat until health recovers, then call again.
- out_of_food: health cannot come back without food. Leave the fortress and get food first.
- timeout: progress so far is kept in the inventory; call again to go on.
