---
name: exploring
description: A needed feature (forest, water, lava, fortress, portal) is not in loaded chunks: explore_for, and what to do when it fails.
---

find_world_features searches only loaded chunks, so an empty result means "not near here", not "nowhere". Do not walk back and forth by hand with navigate_to to look: it wastes daylight and has routed the body into lakes. Call explore_for with the featureKind you need (forest for logs, water_body for water, lava_pool for obsidian, nether_fortress in the Nether, end_portal inside a stronghold). It walks outward on dry land in legs of about 48 blocks and searches after each one, so the first result is usually the nearest one in that direction. Start it in the morning: it stops at dusk.

On feature_found, navigate_to the standPos it gives (or the pos) and remember_place it if you will come back. On failure the reason carries the explored extent (start, furthest point, blocks walked):
- night_fell: sleep in a bed if you carry one, otherwise call shelter_for_night (it digs in, waits for morning and climbs out), then call explore_for again; it starts from wherever you are.
- low_health: eat and let health recover first.
- max_distance: nothing within maxDistance of the start. Call again with a larger maxDistance, or from the furthest point it reached, or settle for what is nearby (in a desert: dead bushes for sticks, cactus, villages for wood; sand and sandstone for building).
- route_blocked: water or cliffs on every new heading. Walk around the obstacle with navigate_to (or cross by boat), then call again.
