---
name: nether-trip
description: Preparing for and travelling in the Nether: gold armor against piglins, food, what to carry, and finding the fortress.
---

Piglins attack a player who wears no gold. Wear at least one golden armor piece (a golden helmet takes five gold ingots) before stepping through a portal; carried armor is put on automatically, and in the Nether one golden piece is kept on even when better armor is carried. Never attack a piglin: the whole group turns on you. Gold comes from gold ore below y 32 in the overworld (iron pickaxe) or from nether gold ore, which any pickaxe mines into nuggets (nine make an ingot).

Lighting the portal takes flint and steel (one flint and one iron ingot). collect_flint makes flint from carried gravel by itself, placing and breaking it beside you; do not place and break gravel by hand through separate tool calls.

Before entering: eat to full and carry at least a stack of cooked food (hunt_food gathers and cooks it; see the food skill), a sword, a shield, full armor, a pickaxe, a stack of building blocks, and a bow with arrows if you have them. Health only regenerates at 18 food or more, so eat before long walks rather than during a fight; eat_food is refused while another job runs.

Remember the arrival portal with remember_place. Lava lakes, magma blocks and soul sand valleys are common; navigation keeps a margin from lava, but do not dig straight down. Find the fortress with find_world_features featureKind=nether_fortress (loaded chunks only), then explore_for featureKind=nether_fortress. Blaze rods are the blaze-rods skill.
