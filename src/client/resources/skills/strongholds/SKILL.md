---
name: strongholds
description: Finding the stronghold and End portal: eyes of ender, locate_stronghold, digging down, end_portal.
---

The End portal is in a stronghold's portal room, underground in the overworld. Each eye of ender is one ender pearl and one blaze powder; the portal needs up to 12 eyes in its frames (some frames generate filled), and locate_stronghold spends about one in five throws, so bring several spare. Do not throw eyes by hand: the flight is too quick to read between turns. Call locate_stronghold from the surface in the overworld.

locate_stronghold ends with stronghold_located estimate=x,z (confirmed=false means no eye was left to check it near the spot). Eyes lead to the stronghold's starting staircase, under that column, usually between y=10 and y=50; its corridors spread tens of blocks around it. Mark the spot with remember_place, then navigate_to the estimate with a deep y (about 30): navigation digs a staircase down, never a straight shaft. Look for stone bricks. Once inside, use find_world_features featureKind=end_portal to locate exposed portal frames in loaded chunks; walk the corridors toward stone brick and cracked/mossy variants if it returns nothing yet. Before entering the portal, fill every empty frame with use_block itemId=minecraft:ender_eye on the frame, and prepare for the dragon fight (fight_ender_dragon).

On failure the reason carries best_estimate and the bearings so far: no_eyes_of_ender or out_of_eyes (craft more and call again from the last throw point), bearing_inconclusive (raise maxEyes or move a few hundred blocks and call again), route_blocked (walk around the obstacle with navigate_to, then call again), wrong_dimension (strongholds exist only in the overworld), no_eye_flight (no stronghold the eye can lead to).
