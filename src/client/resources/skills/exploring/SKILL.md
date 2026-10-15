---
name: exploring
description: A needed feature (forest, water, lava, fortress, portal) is not in loaded chunks: explore_for, its night handback, and what to do when it fails.
---

find_world_features searches only loaded chunks, so an empty result means "not near here", not "nowhere". Do not walk back and forth by hand with navigate_to to look: it wastes daylight and has routed the body into lakes. Call explore_for with the featureKind you need (forest for logs, water_body for water, lava_pool for obsidian, nether_fortress in the Nether, end_portal inside a stronghold) and let it run: it heads toward land that looks like the feature, remembers where it has been, eats and hunts on the way, swims across water when that is the only way on, and walks to the feature once it sees it. In a desert, minTrees=1 is enough: one tree gives the logs for a crafting table and wooden tools.

While it runs it sends task.progress milestones (land in sight, distance walked, a crossing, a hunt, dusk, morning, the find). They are for following along and telling the player what is happening; the work keeps running, so do not cancel it or call it again because of one.

Before a long walk, carry a few blocks of dirt, cobblestone or sandstone if you can: they roof the night shelter (sand and gravel cannot, they fall). With none it walks on through the night.

At dusk it digs a 3-deep pit, roofs it and ends with sheltered_for_night: the trip is paused, not done, and the night is yours. Either:
- stay put: call explore_for again with the same featureKind; it waits in the pit and walks on at dawn;
- or mine to pass the night: with a pickaxe, dig a tunnel from the pit floor (strip mining for coal and iron), placing a torch every few blocks so nothing spawns in it, and stay within 48 blocks of the pit. Without torches, stay in the pit. At dawn (perception.environment_changed dawn) call explore_for again: it climbs back out through the pit and walks on.

On feature_found the body is already there (reached=true) or close; remember_place it if you will come back. On failure the reason carries the explored extent (start, furthest point, blocks walked):
- low_health: nothing to eat. Find food (an animal, or berries) before calling again.
- max_distance: nothing within maxDistance of the start. Call again (it skips ground it has already walked) with a larger maxDistance, or settle for what is nearby (in a desert: dead bushes for sticks, cactus, villages for wood; sand and sandstone for building).
- route_blocked: water or cliffs on every new heading and no shore in sight. Cross by boat, or bridge with navigate_to, then call again. Underground (far from the pit), go up with return_to_surface first.
- shelter_exit_failed: break the roof over the head and climb out, then call again.
