---
name: furnaces
description: Furnace and chest tools: check_smeltables, smelt_items, collect_smelted_items, fuel math.
---

inspect_container, transfer_container and close_container support chest-style storage, not furnace screens. A container_not_open response from these tools does not prove that a furnace GUI closed. For furnaces, use check_smeltables, start smelt_items with a returned optionId, inspect_smelting for process state, and collect_smelted_items for output. These actions manage furnace interaction; do not repeatedly reopen a furnace to make chest-container tools accept it.

Smelting requires enough fuel for the entire requested inputQuantity before insertion; valid fuel alone is not sufficient. check_smeltables maxInputQuantity reflects available input, not a fuel-backed batch size. If autoFuelForMaxInput=missing or manual fuelQuantity cannot cover the batch, reduce inputQuantity or obtain more fuel. An empty furnace does not waive the fuel requirement. Treat insufficient_fuel as a fuel-budget prerequisite, not evidence that a recognized fuel item is ignored.
