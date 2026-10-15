---
name: food
description: Getting food: hunt_food hunts animals and cooks the meat as one task, what it needs, and its results and failure reasons.
---

Health only comes back at 18 food or more, so run short of food and fights end early. Get food first after every respawn and before every Nether trip or long walk: a stack of cooked meat lasts a long time. Do not hunt by hand with inspect_nearby_entities, attack_entity and walking onto drops, then smelting each kind: call hunt_food.

hunt_food (count raw meat, default 6, up to 16; maxDistance 8..48, default 32) hunts adult cows, mooshrooms, pigs, sheep, chickens and rabbits you can see, nearest first, picks up the drops (leather, feathers and wool too), and with cook (the default) smelts the meat afterwards. It never kills babies, named or leashed animals, animals in a preserved place, or (unless keepHerd=false) the last 2 adults of a kind in sight, so the herd can breed and come back. Call it by day on open grassland or plains where animals graze; a sword or axe makes it much faster. For cooking carry a furnace or 8 cobblestone (it crafts one at a crafting table) and some fuel: coal or charcoal is best; planks and sticks work; logs burn only when nothing else covers the batch, and tools never burn. A furnace it places is picked back up when you carry a pickaxe.

It ends food_gathered raw=... cooked=... killed=n skipped=m with stopped=count_reached, no_animals_in_sight (it waited 400 ticks with nothing huntable in sight: walk on to more animals and call again for the rest) or already_carried. A cooking problem is a note on that success, and the meat is still yours: cookSkipped=no_furnace (carry a furnace or 8 cobblestone), cookSkipped=no_fuel (bring coal, planks or sticks), cookSkipped=smelt_failed:<reason> (cook it with check_smeltables, smelt_items and collect_smelted_items). Raw meat is food too: eat it when nothing else is left.

Failures carry what was gathered so far:
- no_animals: nothing huntable in sight at the start. When it says only adults kept for the herd are in sight and you are short of food, call again with keepHerd=false; else walk to grassland (explore_for forest often ends near open ground) and call again.
- night: night on the overworld surface. Sleep (sleep_in_bed) or shelter_for_night, then call again in the morning.
- no_weapon_and_low_health: health below 8 with no sword or axe. Eat, let health recover or craft a sword, then call again.
- timeout: 3600 ticks of hunting without reaching count. The animals ran or could not be reached; try another herd.
