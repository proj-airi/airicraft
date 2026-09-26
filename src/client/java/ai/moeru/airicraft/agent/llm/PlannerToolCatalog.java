package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.tasks.EntityAttackMode;
import ai.moeru.airicraft.agent.baritone.BaritonePathfindSettings;
import ai.moeru.airicraft.agent.tasks.ResourceGatheringCatalog;
import ai.moeru.airicraft.agent.tasks.ReturnToSurfaceStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltingFuelMode;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class PlannerToolCatalog {
	private static final Gson GSON = new Gson();
	public static final String OBSERVE = "observe";
	public static final String TAKE_A_LOOK = "take_a_look";
	public static final String INSPECT_WORLD = "inspect_world";
	public static final String CLOSE_CONTAINER = "close_container";
	public static final String INSPECT_CONTAINER = "inspect_container";
	public static final String TRANSFER_CONTAINER = "transfer_container";
	public static final String INSPECT_INVENTORY = "inspect_inventory";
	public static final String CHECK_CRAFTABLES = "check_craftables";
	public static final String CHECK_SMELTABLES = "check_smeltables";
	public static final String INSPECT_SMELTING = "inspect_smelting";
	public static final String INSPECT_NEARBY_ENTITIES = "inspect_nearby_entities";
	public static final String START_ACTION_GOAL = "start_action_goal";
	public static final String LIST_ACTION_GOALS = "list_action_goals";
	public static final String INSPECT_ACTION_GOAL = "inspect_action_goal";
	public static final String CANCEL_ACTION_GOAL = "cancel_action_goal";
	public static final String INSPECT_ACTION_TRACE = "inspect_action_trace";
	public static final String LIST_ACTION_CAPABILITIES = "list_action_capabilities";
	public static final String FOLLOW_PLAYER = "follow_player";
	public static final String NAVIGATE_TO = "navigate_to";
	public static final String RETURN_TO_SURFACE = "return_to_surface";
	public static final String MINE_BLOCKS = "mine_blocks";
	public static final String ENSURE_BLOCKS_IN_INVENTORY = "ensure_blocks_in_inventory";
	public static final String COLLECT_RESOURCE = "collect_resource";
	public static final String CRAFT_RECIPE = "craft_recipe";
	public static final String SMELT_ITEMS = "smelt_items";
	public static final String COLLECT_SMELTED_ITEMS = "collect_smelted_items";
	public static final String CANCEL_SMELTING = "cancel_smelting";
	public static final String EQUIP_ITEM = "equip_item";
	public static final String EAT_FOOD = "eat_food";
	public static final String DROP_ITEMS = "drop_items";
	public static final String GIVE_PLAYER = "give_player";
	public static final String ATTACK_ENTITY = "attack_entity";
	public static final String USE_ENTITY = "use_entity";
	public static final String PLACE_BLOCK = "place_block";
	public static final String USE_BLOCK = "use_block";
	public static final String BREAK_BLOCKS = "break_blocks";
	public static final String TEND_CROPS = "tend_crops";
	public static final String LURE_ENTITIES = "lure_entities";
	public static final String CANCEL_TASK = "cancel_task";
	public static final String RESUME_TASK = "resume_task";
	public static final String CLEAR_GOAL = "clear_goal";
	public static final String UPDATE_EVENT_POLICY = "update_event_policy";
	public static final String CONFIGURE_PATHFIND = "configure_pathfind";
	public static final String CONFIGURE_LIGHTING = "configure_lighting";
	public static final String CONFIGURE_FOOD = "configure_food";
	public static final String CONFIGURE_REFLEX = "configure_reflex";

	private static final Consumer<JsonObject> NO_ARGUMENT_VALIDATION = arguments -> {
	};
	private static final List<BuiltInTool> BUILT_IN_TOOLS = createBuiltInTools();

	private static List<BuiltInTool> createBuiltInTools() {
		return List.of(
		builtInTool(OBSERVE, true, false, tool(OBSERVE, "Observe current state, tool queue, runtime notices and relevant events since the previous observation. "
				+ "The runtime calls this before every decision; call it yourself only to refresh state mid-turn. "
				+ "State after the first observation is shown as an RFC 6902 JSON Patch against the previous observation.",
				properties(), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(TAKE_A_LOOK, true, tool(TAKE_A_LOOK, "Inspect current first-person view.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("prompt", string("Short prompt describing what to inspect.")),
				prop("direction", enumString("Optional compass direction to face before capture.", List.of(
					"north",
					"northeast",
					"east",
					"southeast",
					"south",
					"southwest",
					"west",
					"northwest"
				))),
				prop("x", integer("Optional target block x coordinate. Provide x, y, and z together.")),
				prop("y", integer("Optional target block y coordinate. Provide x, y, and z together.")),
				prop("z", integer("Optional target block z coordinate. Provide x, y, and z together.")),
				prop("targetPlayer", optionalString("Optional loaded player name to look at before capture."))
			), List.of()), PlannerToolCatalog::validateTakeALookArguments),
		builtInTool(INSPECT_WORLD, true, tool(INSPECT_WORLD, "Inspect loaded world blocks. Summaries group identical patches; unknown cells stay unknown. Use detail=blocks for individual records.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("detail", enumString("inspect_area/placement sites: summary (default) or individual blocks.", List.of("summary", "blocks"))),
				prop("feetY", Map.of("type", "number", "description", "check_position only: exact feet height for slabs/partial blocks, within one block of y. Defaults to y. check_interaction treats query center as target and returns local approach positions, reach and obstruction; neither mode guarantees routes.")),
				prop("mode", enumString("World query mode.", List.of("inspect_area", "find_blocks", "find_placement_sites", "check_position", "check_interaction"))),
				prop("scope", enumString("Query scope.", List.of("self", "center", "box"))),
				prop("x", integer("Center block x coordinate for scope=center.")),
				prop("y", integer("Center block y coordinate for scope=center.")),
				prop("z", integer("Center block z coordinate for scope=center.")),
				prop("x1", integer("First box corner x coordinate for scope=box.")),
				prop("y1", integer("First box corner y coordinate for scope=box.")),
				prop("z1", integer("First box corner z coordinate for scope=box.")),
				prop("x2", integer("Second box corner x coordinate for scope=box.")),
				prop("y2", integer("Second box corner y coordinate for scope=box.")),
				prop("z2", integer("Second box corner z coordinate for scope=box.")),
				prop("horizontalRadius", integer("Horizontal radius for scope=self or scope=center. Default 8, maximum 16.")),
				prop("verticalRadius", integer("Vertical radius for scope=self or scope=center. Default 4, maximum 8.")),
				prop("maxResults", integer("Maximum returned blocks or sites. Default 32, maximum 64.")),
				prop("blockIds", stringArray("Block ids for find_blocks.")),
				prop("stateFilters", stringArray("Exact block-state filters for find_blocks, using key=value syntax.")),
				prop("targetMaterial", enumString("Allowed target block material for find_placement_sites.", List.of("air", "replaceable", "air_or_replaceable"))),
				prop("supportBlockIds", stringArray("Optional block ids required directly below each placement target.")),
				prop("supportStateFilters", stringArray("Exact support block-state filters using key=value syntax.")),
				prop("requireSolidTopSupport", bool("Whether the support block top face must be solid.")),
				prop("requireAirAbove", bool("Whether the block above the target must be air or replaceable.")),
				prop("requireStandableAdjacent", bool("Whether at least one adjacent standable player position is required. Default true.")),
				prop("requireWithinInteractionRange", bool("Whether the target must be within current interaction range.")),
				prop("nearbyRequiredBlockIds", stringArray("Optional nearby block ids required around each placement target.")),
				prop("nearbyRequiredHorizontalRadius", integer("Horizontal radius for nearbyRequiredBlockIds. Default 4, maximum 16.")),
				prop("nearbyRequiredVerticalRadius", integer("Vertical radius for nearbyRequiredBlockIds. Default 1, maximum 8."))
			), List.of("mode", "scope")), PlannerToolCatalog::validateInspectWorldArguments),
		builtInTool(CLOSE_CONTAINER, false, tool(CLOSE_CONTAINER,
			"Close the current chest, barrel or chest-style entity container after transfers. Refuses a nonempty cursor. Use before resuming travel or other work.", properties(), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(INSPECT_CONTAINER, true, tool(INSPECT_CONTAINER,
			"Inspect the currently open chest, barrel, chest minecart or chest boat, including syncId, slots and carried storage. Open blocks with use_block; find entity containers with inspect_nearby_entities and open their copied uuid with use_entity. No remote or unopened inventory access.", properties(), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(TRANSFER_CONTAINER, false, tool(TRANSFER_CONTAINER,
			"Deposit or withdraw items in one open chest-style container. Prefer items[] to transfer several item types in one call. Copy syncId from inspect_container. The entire batch preflights source quantities and shared destination space before any clicks; preserves stack components and leaves cursor empty. Result is submitted client prediction: inspect_container again to verify settled counts.", properties(
				prop("syncId", integer("Open container syncId from inspect_container; stale windows are rejected.")),
				prop("direction", enumString("Direction for the entire batch.", List.of("deposit", "withdraw"))),
				prop("items", array("1..36 item quantities. Do not combine with top-level itemId/quantity. Equipped armor and offhand are excluded.",
					Map.of("type", "object", "properties", properties(
						prop("itemId", string("Exact namespaced item ID.")), prop("quantity", integer("Exact quantity, 1..2304."))),
						"required", List.of("itemId", "quantity"), "additionalProperties", false))),
				prop("itemId", string("Single-item form; omit when using items[].")),
				prop("quantity", integer("Single-item form quantity, 1..2304; omit when using items[]."))
			), List.of("syncId", "direction")), args -> {
				requireInt(args, "syncId");
				if (!List.of("deposit", "withdraw").contains(requireString(args, "direction"))) throw new JsonParseException("direction must be deposit or withdraw");
				if (args.has("items")) {
					if (args.has("itemId") || args.has("quantity")) throw new JsonParseException("Use items or itemId/quantity, not both");
					if (!args.get("items").isJsonArray() || args.getAsJsonArray("items").isEmpty() || args.getAsJsonArray("items").size() > 36)
						throw new JsonParseException("items must contain 1..36 entries");
					for (JsonElement item : args.getAsJsonArray("items")) {
						if (!item.isJsonObject()) throw new JsonParseException("items entries must be objects");
						validateTransferItem(item.getAsJsonObject());
					}
				} else validateTransferItem(args);
			}),
		builtInTool(INSPECT_INVENTORY, true, tool(INSPECT_INVENTORY, "Inspect current inventory counts.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("prompt", string("Optional inventory question."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(CHECK_CRAFTABLES, true, tool(CHECK_CRAFTABLES, "Check currently executable crafting options. Optionally filter by exact outputItemId before the bounded result limit; truncation is reported.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("prompt", string("Optional crafting question.")),
				prop("outputItemId", optionalString("Exact output item id, for example minecraft:barrel. Omit to list all currently craftable outputs."))
			), List.of()), args -> {
				if (args.has("outputItemId")) requireString(args, "outputItemId");
			}),
		builtInTool(CHECK_SMELTABLES, true, tool(CHECK_SMELTABLES, "Check currently executable smelting options and ranked furnace candidates.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("prompt", string("Optional smelting question."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(INSPECT_SMELTING, true, tool(INSPECT_SMELTING, "Inspect Airicraft-owned smelting processes and nearby furnace observations.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("prompt", string("Optional smelting status question."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(INSPECT_NEARBY_ENTITIES, true, tool(INSPECT_NEARBY_ENTITIES, "Search loaded entities by exact type, with nearest-first bounded results and selectors, positions and health. Absence does not describe unloaded terrain. Navigate within 32 blocks before interacting with a distant result.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("prompt", string("Optional nearby-entity question.")),
				prop("radius", integer("Search radius in blocks, 1 to 128. Default 32. Loaded entities only.")),
				prop("maxResults", integer("Maximum nearest matching entities, 1 to 64. Default 32.")),
				prop("entityTypeIds", stringArray("Optional exact entity type IDs, for example minecraft:sheep. Omit for all types."))
			), List.of()), PlannerToolCatalog::validateNearbyEntitiesArguments),
		builtInTool(START_ACTION_GOAL, false, tool(START_ACTION_GOAL, "Start one runtime-owned action graph goal from a high-level typed intent. Prefer this over low-level action tools for execution.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("kind", enumString("Typed action goal kind. crafting_output and smelting_output are aliases of inventory_item.", List.of(
					"inventory_item",
					"resource_collection",
					"smelting_output",
					"crafting_output"
				))),
				prop("itemId", optionalString("Inventory/crafting/smelting output item id, for example minecraft:bread.")),
				prop("quantity", integer("Desired minimum quantity.")),
				prop("resourceKind", optionalString("Resource kind for resource_collection goals. Supported values: " + String.join(", ", ResourceGatheringCatalog.supportedKindNames()) + "."))
			), List.of("kind")), PlannerToolCatalog::validateStartActionGoalArguments),
		builtInTool(LIST_ACTION_GOALS, true, tool(LIST_ACTION_GOALS, "List foreground, suspended, runnable, and recent terminal action graph executions.", properties(
				prop("narration", optionalString("Optional pre-action narration."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(INSPECT_ACTION_GOAL, true, tool(INSPECT_ACTION_GOAL, "Inspect an action graph goal. Without executionId, selects foreground or the most recently updated nonterminal execution.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("executionId", optionalString("Optional action graph execution id."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(CANCEL_ACTION_GOAL, false, tool(CANCEL_ACTION_GOAL, "Cancel an action graph goal and its foreground primitive, if any. executionId is required when multiple suspended goals make the target ambiguous.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("executionId", optionalString("Optional action graph execution id.")),
				prop("reason", optionalString("Optional cancellation reason."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(INSPECT_ACTION_TRACE, true, tool(INSPECT_ACTION_TRACE, "Inspect an action graph trace, route, facts, watches, and terminal status.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("executionId", optionalString("Optional action graph execution id."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(LIST_ACTION_CAPABILITIES, true, tool(LIST_ACTION_CAPABILITIES, "List runtime action graph capabilities, primitives, providers, and supported goal kinds.", properties(
				prop("narration", optionalString("Optional pre-action narration."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(FOLLOW_PLAYER, false, tool(FOLLOW_PLAYER, "Continuously follow a named player until the goal is cleared, cancelled, or replaced. Use navigate_to when only reaching a fixed position once is needed.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("targetPlayer", string("Player name to follow."))
			), List.of("targetPlayer")), PlannerToolCatalog::validateFollowPlayerArguments),
		builtInTool(NAVIGATE_TO, false, tool(NAVIGATE_TO, "Navigate to a block position.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("x", number("Block x coordinate.")),
				prop("y", number("Block y coordinate.")),
				prop("z", number("Block z coordinate.")),
				prop("exactY", bool("True requires the exact x/y/z block; false navigates to x/z at any height and ignores y."))
			), List.of("x", "y", "z", "exactY")), PlannerToolCatalog::validateNavigateToArguments),
		builtInTool(RETURN_TO_SURFACE, false, tool(RETURN_TO_SURFACE, "Return to the remembered surface or last safe ground after mining. Optionally tower upward with filler blocks if trapped in a shaft.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("useTowering", bool("Whether the executor may build a pillar underfoot while jumping if path navigation cannot return to the surface. Defaults to true when omitted.")),
				prop("fillerBlockIds", stringArray("Optional namespaced block/item ids to use for towering. Omit to use defaults: " + String.join(", ", ReturnToSurfaceStepArgs.DEFAULT_FILLER_BLOCK_IDS) + "."))
			), List.of()), PlannerToolCatalog::validateReturnToSurfaceArguments),
		builtInTool(MINE_BLOCKS, false, tool(MINE_BLOCKS, "Acquire matching blocks inside a fixed loaded area, using System 1 target selection and bounded excavation approaches, including fully buried sources. Use when observed block resources and this supported mining capability serve the objective. Do not pass item ids from inventory itemCounts. Likely underground work requires at least one torch unless explicitly overridden.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("blockIds", stringArray("Namespaced block ids to mine, for example minecraft:iron_ore. These must be block ids, not item ids such as minecraft:raw_iron.")),
				prop("quantity", integer("Number of blocks to mine.")),
				prop("constraints", acquisitionConstraintsSchema()),
				prop("allowUnilluminated", bool("Explicitly allow predicted underground or unilluminated mining with no torches. Default false."))
			), List.of("blockIds", "quantity")), PlannerToolCatalog::validateMineBlocksArguments),
		builtInTool(ENSURE_BLOCKS_IN_INVENTORY, false, tool(ENSURE_BLOCKS_IN_INVENTORY, "Ensure the inventory contains at least a target count from mined block drops. Do not pass inventory item ids.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("blockIds", stringArray("Namespaced block ids whose drops count toward the target, for example minecraft:iron_ore. These must be block ids, not item ids such as minecraft:raw_iron.")),
				prop("quantity", integer("Minimum matching item count required in inventory. Existing inventory and pickups count.")),
				prop("constraints", acquisitionConstraintsSchema()),
				prop("allowUnilluminated", bool("Explicitly allow predicted underground or unilluminated mining with no torches. Default false."))
			), List.of("blockIds", "quantity")), PlannerToolCatalog::validateMineBlocksArguments),
		builtInTool(COLLECT_RESOURCE, false, tool(COLLECT_RESOURCE, "Collect a supported resource kind within a fixed loaded area. System 1 selects targets, approaches, breaks and collects drops. Optional constraints restrict the search; no speculative mining or distant exploration. Use break_blocks for exact coordinates.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
					prop("resourceKind", enumString("Resource kind.", ResourceGatheringCatalog.supportedKindNames())),
				prop("quantity", integer("Additional items to collect; completion requires inventory gain.")),
				prop("constraints", acquisitionConstraintsSchema())
			), List.of("resourceKind", "quantity")), PlannerToolCatalog::validateCollectResourceArguments),
		builtInTool(CRAFT_RECIPE, false, tool(CRAFT_RECIPE, "Run a listed crafting recipe, including automatic crafting-table setup for 3x3 recipes.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("recipeId", string("Exact recipe id from check_craftables.")),
				prop("times", integer("Recipe run count."))
			), List.of("recipeId", "times")), PlannerToolCatalog::validateCraftRecipeArguments),
		builtInTool(SMELT_ITEMS, false, tool(SMELT_ITEMS, "Start one background smelting process from an exact optionId returned by check_smeltables.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("optionId", string("Exact optionId from check_smeltables.")),
				prop("inputQuantity", integer("Number of input items to smelt.")),
				prop("fuelMode", enumString("Fuel mode. Use auto unless explicitly selecting fuel.", List.of("auto", "manual"))),
				prop("fuelItemId", optionalString("Required when fuelMode is manual. Exact namespaced fuel item id.")),
				prop("fuelQuantity", integer("Fuel item quantity for manual fuel. Use 0 or omit for auto fuel.")),
				prop("confirmationToken", optionalString("Short-lived token returned when an occupied or stale furnace requires confirmation."))
			), List.of("optionId", "inputQuantity")), PlannerToolCatalog::validateSmeltItemsArguments),
		builtInTool(COLLECT_SMELTED_ITEMS, false, tool(COLLECT_SMELTED_ITEMS, "Collect output from an Airicraft-owned smelting process, or from an untracked occupied furnace with confirmation.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("processId", optionalString("Airicraft-owned process id from smelt_items or inspect_smelting.")),
				prop("confirmationToken", optionalString("Short-lived token required for untracked or occupied furnace collection."))
			), List.of()), PlannerToolCatalog::validateCollectSmeltedItemsArguments),
		builtInTool(CANCEL_SMELTING, false, tool(CANCEL_SMELTING, "Stop tracking an Airicraft-owned smelting process without reclaiming furnace contents.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("processId", string("Airicraft-owned process id to stop tracking."))
			), List.of("processId")), PlannerToolCatalog::validateCancelSmeltingArguments),
		builtInTool(EQUIP_ITEM, false, tool(EQUIP_ITEM, "Equip an exact inventory item. Armor is worn through normal item use; weapons and tools become the selected main-hand item.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("itemId", string("Exact namespaced item id from inspect_inventory itemCounts."))
			), List.of("itemId")), PlannerToolCatalog::validateInventoryItemArguments),
		builtInTool(EAT_FOOD, false, tool(EAT_FOOD, "Eat one exact food item from inventory. The action holds item use until consumption is confirmed or times out.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("itemId", string("Exact namespaced food item id from inspect_inventory itemCounts."))
			), List.of("itemId")), PlannerToolCatalog::validateInventoryItemArguments),
		builtInTool(DROP_ITEMS, false, tool(DROP_ITEMS, "Drop exact items from current inventory at the current position.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("itemId", string("Exact namespaced item id from inspect_inventory itemCounts.")),
				prop("quantity", integer("Number of items to drop."))
			), List.of("itemId", "quantity")), PlannerToolCatalog::validateDropItemsArguments),
		builtInTool(GIVE_PLAYER, false, tool(GIVE_PLAYER, "Drop exact items for a named nearby player to pick up.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("targetPlayer", string("Nearby player name receiving the items.")),
				prop("itemId", string("Exact namespaced item id from inspect_inventory itemCounts.")),
				prop("quantity", integer("Number of items to drop."))
			), List.of("targetPlayer", "itemId", "quantity")), PlannerToolCatalog::validateGivePlayerArguments),
		builtInTool(ATTACK_ENTITY, false, tool(ATTACK_ENTITY, "Attack one nearby entity. Default mode kill attacks until death, then collects nearby item drops within 4 blocks of the death position for up to 200 active ticks. Completion waits for local drops to clear and reports collectedItems as observed inventory gains in its terminal result, including partial gains on failure; full inventory or unreachable drops report a failure after the kill. hit_once stops after one landed hit without collection. Always copy the uuid token shown by inspect_nearby_entities or focus, and optionally include name or entityTypeId.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("uuid", optionalString("Entity uuid token copied from inspect_nearby_entities or focus. Full uuid also works.")),
				prop("name", optionalString("Visible custom name or display name when available.")),
				prop("entityTypeId", optionalString("Exact namespaced entity type id, for example minecraft:sheep.")),
				prop("mode", enumString("Attack mode. Use kill unless the user asks for one hit.", List.of("kill", "hit_once")))
			), List.of("uuid")), PlannerToolCatalog::validateAttackEntityArguments),
		builtInTool(USE_ENTITY, false, tool(USE_ENTITY, "Use current hand or an optional item on one nearby entity, including opening a chest minecart or chest boat. Then inspect_container and transfer_container using the opened syncId. Always copy the uuid token shown by inspect_nearby_entities or focus, and optionally include name or entityTypeId.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("uuid", optionalString("Entity uuid token copied from inspect_nearby_entities or focus. Full uuid also works.")),
				prop("name", optionalString("Visible custom name or display name when available.")),
				prop("entityTypeId", optionalString("Exact namespaced entity type id, for example minecraft:sheep.")),
				prop("itemId", optionalString("Optional exact namespaced item id to equip first, for example minecraft:shears."))
			), List.of("uuid")), PlannerToolCatalog::validateUseEntityArguments),
		builtInTool(PLACE_BLOCK, false, tool(PLACE_BLOCK, "Place a block item at one or more intended modified target positions. Target positions must have been observed by a world read tool such as inspect_world or find_world_features within the last 10 planner tool calls.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("itemId", string("Exact namespaced item id from inspect_inventory itemCounts.")),
				prop("x", integer("Intended modified target block x coordinate.")),
				prop("y", integer("Intended modified target block y coordinate.")),
				prop("z", integer("Intended modified target block z coordinate.")),
				prop("facePreference", enumString("Direction from the target cell to its support neighbor. Use down for the floor below (clicks its top), up for the ceiling above. Prefer auto unless a specific support is required.", List.of("auto", "down", "north", "south", "east", "west", "up"))),
				prop("requireCurrentTargetMaterial", enumString("Required current target material before placement. Default air_or_replaceable.", List.of("air", "replaceable", "air_or_replaceable"))),
				prop("targets", array("Ordered target blocks to place into. Maximum 16. Root facePreference and requireCurrentTargetMaterial apply as defaults.", placeBlockTargetSchema()))
			), List.of("itemId")), PlannerToolCatalog::validatePlaceBlockArguments),
		builtInTool(USE_BLOCK, false, tool(USE_BLOCK, "Interact with an existing block (including opening chests, furnaces, or doors), or use an optional item at a target position. To open a chest, call with x,y,z only; omit itemId and expectedTargetMaterial. If target is air/replaceable, runtime clicks adjacent support such as farmland below seeds. Target positions must have been observed by a world read tool such as inspect_world or find_world_features within the last 10 planner tool calls.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("itemId", optionalString("Optional exact namespaced item id to equip first, for example minecraft:wheat_seeds.")),
				prop("x", integer("Intended modified target block x coordinate.")),
				prop("y", integer("Intended modified target block y coordinate.")),
				prop("z", integer("Intended modified target block z coordinate.")),
				prop("facePreference", enumString("For air/replaceable targets, direction from target to support: down selects the floor below and clicks its top (for example placing a torch). For an existing solid target, selects the clicked face. Prefer auto.", List.of("auto", "down", "north", "south", "east", "west", "up"))),
				prop("expectedSupportBlockIds", stringArray("Optional exact block ids expected on the clicked support block.")),
				prop("expectedTargetMaterial", enumString("Optional precondition for an air/replaceable target, such as planting. Omit when interacting with existing solid blocks (chests, furnaces, doors); every listed value rejects them.", List.of("air", "replaceable", "air_or_replaceable"))),
				prop("targets", array("Ordered target blocks to use. Maximum 16. Root facePreference, expectedSupportBlockIds, and expectedTargetMaterial apply as defaults.", useBlockTargetSchema()))
			), List.of()), PlannerToolCatalog::validateUseBlockArguments),
		builtInTool(LURE_ENTITIES, false, tool(LURE_ENTITIES, "Lure 1..8 observed animals into an inclusive destination box within 64 blocks. Animals must respond to the held food item. System 1 approaches moving followers, pauses travel for catch-up and chooses standing positions inside the area. Completion requires every selected animal's body inside; player arrival is insufficient. Does not feed animals or close gates. Use a roomy interior box and an open entrance. Uses temporary walking-only path settings, restored on release. Resume a safety hold after combat to reacquire followers.", properties(
			prop("narration", optionalString("Optional visible narration.")),
			prop("uuids", stringArray("1..8 distinct UUID tokens copied from inspect_nearby_entities. Targets must initially be within 32 blocks.")),
			prop("itemId", string("Held lure item, for example minecraft:wheat_seeds for chickens or minecraft:wheat for cows.")),
			prop("x1", integer("Minimum destination x.")), prop("y1", integer("Minimum destination y.")), prop("z1", integer("Minimum destination z.")),
			prop("x2", integer("Maximum destination x.")), prop("y2", integer("Maximum destination y.")), prop("z2", integer("Maximum destination z."))
		), List.of("uuids", "itemId", "x1", "y1", "z1", "x2", "y2", "z2")), ai.moeru.airicraft.agent.tasks.LureEntitiesStepArgs::parse),
		builtInTool(TEND_CROPS, false, tool(TEND_CROPS, "Tend one existing flat crop plot, at most 16 by 16 blocks within 64 blocks of you. System 1 inspects the plot, harvests mature crops, collects drops and replants, and plants empty farmland when seeds are available. Leaves immature crops and other blocks intact. Deliberately edits crops within preserved places. One pass; does not wait for growth or till soil. Its terminal result reports counts and missing seeds.", properties(
			prop("narration", optionalString("Optional visible narration.")),
			prop("seedItemId", string("Crop planting item, e.g. minecraft:wheat_seeds, minecraft:carrot, minecraft:potato, minecraft:beetroot_seeds.")),
			prop("x1", integer("Minimum plot x.")), prop("y", integer("Crop block y; soil is one block below.")),
			prop("z1", integer("Minimum plot z.")), prop("x2", integer("Maximum plot x.")), prop("z2", integer("Maximum plot z."))
		), List.of("seedItemId", "x1", "y", "z1", "x2", "z2")), ai.moeru.airicraft.agent.tasks.CropTendingStepArgs::parse),
		builtInTool(BREAK_BLOCKS, false, tool(BREAK_BLOCKS, "Break exact target blocks in order. Use this for precise terrain editing, not resource mining. Every target position must have been observed by a world read tool such as inspect_world or find_world_features within the last 10 planner tool calls.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("targets", array("Ordered target blocks to break. Maximum 16.", breakBlockTargetSchema()))
			), List.of("targets")), PlannerToolCatalog::validateBreakBlocksArguments),
		builtInTool(RESUME_TASK, false, tool(RESUME_TASK, "Resume the exact task paused by a resolved survival reflex. The holdId must match the current safety hold.", properties(
			prop("narration", optionalString("Optional visible narration before resuming the task.")),
			prop("holdId", string("Exact holdId from the survival update."))
		), List.of("holdId")), arguments -> requireString(arguments, "holdId")),
		builtInTool(CANCEL_TASK, false, tool(CANCEL_TASK, "Cancel the current task or job.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("reason", string("Optional cancellation reason."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(CLEAR_GOAL, false, tool(CLEAR_GOAL, "Clear the current goal.", properties(
				prop("narration", optionalString("Optional pre-action narration."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(UPDATE_EVENT_POLICY, false, tool(UPDATE_EVENT_POLICY, "Update future event routing policy.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("clearAll", bool("Clear all active planner policy rules.")),
				prop("removeRuleIds", stringArray("Rule ids to remove.")),
				prop("upserts", array("Policy rule upserts.", policyUpsertSchema()))
			), List.of()), PlannerToolCatalog::validatePolicyArguments),
		builtInTool(CONFIGURE_PATHFIND, false, tool(CONFIGURE_PATHFIND, "Atomically update runtime Baritone pathfinding settings. Use this only when the current route needs a deliberate capability or risk trade-off; settings reset to Airicraft defaults on client restart.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("settings", BaritonePathfindSettings.plannerSettingsSchema())
			), List.of("settings")), PlannerToolCatalog::validateConfigurePathfindArguments),
		builtInTool(CONFIGURE_REFLEX, false, tool(CONFIGURE_REFLEX, "Read or replace System 1 automatic survival policy. Call with {} to read; provide all four settings to replace. Defaults: combatEnabled=true, drowningEnabled=true, maxThreatDistance=16, requireLineOfSight=true. Engagement is limited to known aggressive or visible hostile mobs, with melee engagement additionally limited to 6 blocks. Nearby threat awareness extends to maxThreatDistance, including remembered hidden flankers, for terrain-aware swarm kiting and circling; awareness alone does not trigger distant/hidden pursuit. Disabling combat also disables automatic shield/melee actions; manual gameplay tools remain available. Policy lasts until agent reload/recreation, including across death. Allowed during a reflex; stops disabled reflex actuation next tick but does not resume an interrupted job: wait for the survival update and use continue to resume the plan or clear_queue to replace it. Use deliberately when automatic behavior conflicts with your task, and restore settings when that tactic ends.", properties(
			prop("combatEnabled", bool("Enable automatic combat, including melee defense and shield blocking.")),
			prop("drowningEnabled", bool("Enable automatic drowning recovery.")),
			prop("maxThreatDistance", integer("Maximum eligible mob distance in blocks, 1..32; default 16.")),
			prop("requireLineOfSight", bool("Ignore mobs out of line of sight when true; default true."))
		), List.of()), PlannerToolCatalog::validateConfigureReflexArguments),
		builtInTool(CONFIGURE_FOOD, false, tool(CONFIGURE_FOOD, "Read or replace automatic inventory eating policy. Call with {} to read; provide goal and foodChoice together to replace. Defaults: movement and any. movement eats only when hunger prevents sprinting; heal eats while injured to sustain natural regeneration; off disables idle eating. Combat reflex may eat below half health after a safe retreat, even when idle eating is off. cooked_only limits automatic choices to ordinary cooked food; any allows known ordinary safe vanilla food. Special and modded food remains available through eat_food. Policy lasts until agent session reset.", properties(
			prop("goal", enumString("Automatic idle eating goal.", List.of("off", "movement", "heal"))),
			prop("foodChoice", enumString("Automatic food selection.", List.of("any", "cooked_only")))
		), List.of()), PlannerToolCatalog::validateConfigureFoodArguments),
		builtInTool(CONFIGURE_LIGHTING, false, tool(CONFIGURE_LIGHTING, "Configure automatic torch placement while mining, navigating, or idle after standing still for five seconds. Uses the average over only air cells in a centered 5x5 horizontal square at foot level; occupied cells do not count. Any sky-visible cell in that square prevents placement. Enabled by default underground when average combined light is strictly below 4 (spacing 6); can be disabled explicitly. Keeps offhand equipment such as a shield, temporarily uses a carried torch and restores the held item. Does not interrupt combat, item use or active block breaking. Confirmed placements are batched into the next planner window.", properties(
				prop("narration", optionalString("Optional pre-action narration.")),
				prop("enabled", bool("Whether automatic torch placement is enabled.")),
				prop("mode", enumString("Lighting rule. darkness averages combined light; spawn_proof averages block light.", List.of("darkness", "spawn_proof"))),
				prop("maxLightLevel", integer("Place when the selected 5x5 foot-level average is strictly below this threshold, from 0 to 15; default 4.")),
				prop("minSpacingBlocks", integer("Minimum search radius around the player without an existing torch, from 1 to 16."))
			), List.of("enabled", "mode", "maxLightLevel", "minSpacingBlocks")), PlannerToolCatalog::validateConfigureLightingArguments)
	);
	}
	private static final Map<String, BuiltInTool> BUILT_IN_TOOLS_BY_NAME = builtInToolsByName();

	private PlannerToolCatalog() {
	}

	public static List<Map<String, Object>> openAiTools() {
		return BUILT_IN_TOOLS.stream()
			.map(BuiltInTool::openAiTool)
			.toList();
	}

	public static PlannerToolCall parseToolCall(JsonObject object) {
		return parseToolCall(object, PlannerToolRegistry.empty());
	}

	public static PlannerToolCall parseToolCall(JsonObject object, PlannerToolRegistry toolRegistry) {
		if (object == null) {
			throw new JsonParseException("Missing tool call");
		}
		String id = getString(object, "id").orElse(null);
		String type = getString(object, "type").orElse("function");
		if (!"function".equals(type)) {
			throw new JsonParseException("Unsupported tool call type: " + type);
		}
		JsonObject function = object.has("function") && object.get("function").isJsonObject()
			? object.getAsJsonObject("function")
			: null;
		if (function == null) {
			throw new JsonParseException("Missing tool function");
		}
		String name = getString(function, "name").orElseThrow(() -> new JsonParseException("Missing tool name"));
		PlannerJsonRepair.Result repair = repairArguments(name, parseArguments(getString(function, "arguments").orElse("{}")), toolRegistry);
		JsonObject arguments = toolRegistry.references().resolveArguments(repair.value().getAsJsonObject());
		validateArguments(name, arguments, toolRegistry);
		return new PlannerToolCall(id, name, arguments, getString(arguments, "narration").orElse(null), object, repair.paths());
	}

	public static PlannerToolCall parseToolCall(String name, JsonObject arguments, PlannerToolRegistry toolRegistry) {
		String normalizedName = normalizeName(name);
		if (normalizedName.isBlank()) {
			throw new JsonParseException("Missing tool name");
		}
		PlannerJsonRepair.Result repair = repairArguments(normalizedName, arguments == null ? new JsonObject() : arguments, toolRegistry);
		JsonObject effectiveArguments = toolRegistry.references().resolveArguments(repair.value().getAsJsonObject());
		validateArguments(normalizedName, effectiveArguments, toolRegistry);
		return new PlannerToolCall(
			"call_external_" + normalizedName,
			normalizedName,
			effectiveArguments,
			getString(effectiveArguments, "narration").orElse(null),
			null,
			repair.paths()
		);
	}

	public static JsonArray toOpenAiToolCalls(List<PlannerToolCall> toolCalls) {
		JsonArray array = new JsonArray();
		if (toolCalls == null) {
			return array;
		}
		for (PlannerToolCall toolCall : toolCalls) {
			if (toolCall == null) {
				continue;
			}
			if (toolCall.rawToolCall() != null && toolCall.rawToolCall().isJsonObject()) {
				JsonObject replayed = toolCall.rawToolCall().getAsJsonObject().deepCopy();
				replayed.addProperty("id", toolCall.id());
				if (!replayed.has("type") || replayed.get("type").isJsonNull()) {
					replayed.addProperty("type", "function");
				}
				array.add(replayed);
				continue;
			}
			JsonObject function = new JsonObject();
			function.addProperty("name", toolCall.name());
			function.addProperty("arguments", toolCall.arguments().toString());
			JsonObject item = new JsonObject();
			item.addProperty("id", toolCall.id());
			item.addProperty("type", "function");
			item.add("function", function);
			array.add(item);
		}
		return array;
	}

	public static boolean isReadTool(String name) {
		BuiltInTool tool = builtInTool(name);
		return tool != null && tool.readTool();
	}

	public static boolean isBatchSafeReadTool(String name) {
		BuiltInTool tool = builtInTool(name);
		return tool != null && tool.batchSafeReadTool();
	}

	public static boolean isKnownTool(String name) {
		return builtInTool(name) != null;
	}

	public static String normalizeName(String name) {
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
	}

	public static Map<String, Object> toolForProvider(
		String name,
		String description,
		Map<String, Object> properties,
		List<String> required
	) {
		return tool(name, description, properties, required);
	}

	@SafeVarargs
	public static Map<String, Object> propertiesForProvider(Map.Entry<String, Object>... entries) {
		return properties(entries);
	}

	public static Map.Entry<String, Object> propForProvider(String name, Map<String, Object> schema) {
		return prop(name, schema);
	}

	public static Map<String, Object> stringForProvider(String description) {
		return string(description);
	}

	public static Map<String, Object> optionalStringForProvider(String description) {
		return optionalString(description);
	}

	public static Map<String, Object> enumStringForProvider(String description, List<String> values) {
		return enumString(description, values);
	}

	private static PlannerJsonRepair.Result repairArguments(String name, JsonElement arguments, PlannerToolRegistry registry) {
		var tool = registry.activeOpenAiTool(name).or(() -> registry.allAvailableOpenAiTools().stream()
			.filter(candidate -> normalizeName(name).equals(normalizeName((String) ((Map<?, ?>) candidate.get("function")).get("name"))))
			.findFirst());
		JsonObject schema = tool.map(definition -> GSON.toJsonTree(definition).getAsJsonObject()
			.getAsJsonObject("function").getAsJsonObject("parameters")).orElseGet(() -> {
				JsonObject object = new JsonObject(); object.addProperty("type", "object"); return object;
			});
		PlannerJsonRepair.Result result = PlannerJsonRepair.repair(arguments, schema);
		if (!result.value().isJsonObject()) throw new JsonParseException("Tool arguments must be a JSON object");
		return result;
	}

	private static JsonElement parseArguments(String raw) {
		if (raw == null || raw.isBlank()) {
			return new JsonObject();
		}
		try {
			return JsonParser.parseString(raw);
		}
		catch (JsonParseException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new JsonParseException("Invalid tool arguments", exception);
		}
	}

	private static void validateArguments(String name, JsonObject arguments, PlannerToolRegistry toolRegistry) {
		if (!isKnownTool(name)) {
			Objects.requireNonNullElse(toolRegistry, PlannerToolRegistry.empty()).validateProviderArguments(name, arguments);
			return;
		}
		validateArguments(name, arguments);
	}

	private static void validateArguments(String name, JsonObject arguments) {
		BuiltInTool tool = builtInTool(name);
		if (tool == null) {
			throw new JsonParseException("Unknown planner tool: " + name);
		}
		tool.validate(arguments);
	}

	private static BuiltInTool builtInTool(String name) {
		return BUILT_IN_TOOLS_BY_NAME.get(normalizeName(name));
	}

	private static BuiltInTool builtInTool(String name, boolean readTool, Map<String, Object> openAiTool, Consumer<JsonObject> validator) {
		return builtInTool(name, readTool, readTool, openAiTool, validator);
	}

	private static BuiltInTool builtInTool(
		String name,
		boolean readTool,
		boolean batchSafeReadTool,
		Map<String, Object> openAiTool,
		Consumer<JsonObject> validator
	) {
		return new BuiltInTool(normalizeName(name), readTool, batchSafeReadTool, openAiTool, validator);
	}

	private static Map<String, BuiltInTool> builtInToolsByName() {
		LinkedHashMap<String, BuiltInTool> tools = new LinkedHashMap<>();
		for (BuiltInTool tool : BUILT_IN_TOOLS) {
			BuiltInTool previous = tools.put(tool.name(), tool);
			if (previous != null) {
				throw new IllegalStateException("Duplicate planner tool: " + tool.name());
			}
		}
		return Map.copyOf(tools);
	}

	private static void validateFollowPlayerArguments(JsonObject arguments) {
		requireString(arguments, "targetPlayer");
	}

	private static void validateNearbyEntitiesArguments(JsonObject arguments) {
		for (String key : List.of("radius", "maxResults")) {
			if (!arguments.has(key)) continue;
			int maximum = key.equals("radius") ? 128 : 64;
			JsonElement value = arguments.get(key);
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
				|| value.getAsDouble() != Math.rint(value.getAsDouble())
				|| value.getAsDouble() < 1 || value.getAsDouble() > maximum)
				throw new JsonParseException(key + " must be an integer from 1 to " + maximum);
		}
		if (arguments.has("entityTypeIds")) requireStringArray(arguments, "entityTypeIds");
	}

	private static void validateNavigateToArguments(JsonObject arguments) {
		requireInt(arguments, "x");
		requireInt(arguments, "y");
		requireInt(arguments, "z");
		requireBoolean(arguments, "exactY");
	}

	private static void validateMineBlocksArguments(JsonObject arguments) {
		validateAcquisitionConstraints(arguments);
		requireStringArray(arguments, "blockIds");
		requirePositiveInt(arguments, "quantity");
		if (arguments.has("allowUnilluminated") && !arguments.get("allowUnilluminated").isJsonNull()) {
			requireBoolean(arguments, "allowUnilluminated");
		}
	}

	private static void validateAcquisitionConstraints(JsonObject args) {
		if (!args.has("constraints")) return;
		if (!args.get("constraints").isJsonObject()) throw new JsonParseException("constraints must be an object");
		JsonObject value = args.getAsJsonObject("constraints");
		for (String key : value.keySet()) {
			if (!List.of("center", "radius", "verticalRadius", "surfaceOnly", "visibleOnly").contains(key))
				throw new JsonParseException("Unknown acquisition constraint: " + key);
		}
		for (String key : List.of("radius", "verticalRadius")) {
			if (value.has(key)) {
				int n = requireInt(value, key);
				if (n < 1 || n > 32) throw new JsonParseException("constraints." + key + " must be 1..32");
			}
		}
		if (value.has("surfaceOnly")) requireBoolean(value, "surfaceOnly");
		if (value.has("visibleOnly") && (!value.get("visibleOnly").isJsonPrimitive()
			|| !value.getAsJsonPrimitive("visibleOnly").isBoolean()))
			throw new JsonParseException("visibleOnly must be boolean");
		if (value.has("center")) {
			if (!value.get("center").isJsonObject()) throw new JsonParseException("constraints.center must be an object");
			JsonObject center = value.getAsJsonObject("center");
			if (!center.keySet().equals(java.util.Set.of("x", "y", "z"))) throw new JsonParseException("constraints.center requires exactly x, y, z");
			requireInt(center, "x"); requireInt(center, "y"); requireInt(center, "z");
		}
	}

	private static void validateReturnToSurfaceArguments(JsonObject arguments) {
		if (arguments.has("useTowering") && !arguments.get("useTowering").isJsonNull()) {
			requireBoolean(arguments, "useTowering");
		}
		if (arguments.has("fillerBlockIds") && !arguments.get("fillerBlockIds").isJsonNull()) {
			requireStringArray(arguments, "fillerBlockIds");
		}
	}

	private static void validateCollectResourceArguments(JsonObject arguments) {
		validateAcquisitionConstraints(arguments);
		String kind = requireString(arguments, "resourceKind");
		if (ResourceGatheringCatalog.entry(kind).isEmpty()) {
			throw new JsonParseException("Unsupported resourceKind: " + kind);
		}
		requirePositiveInt(arguments, "quantity");
	}

	private static void validateStartActionGoalArguments(JsonObject arguments) {
		String kind = requireString(arguments, "kind");
		if ("inventory_item".equals(kind) || "smelting_output".equals(kind) || "crafting_output".equals(kind)) {
			requireString(arguments, "itemId");
			requirePositiveInt(arguments, "quantity");
		}
		else if ("resource_collection".equals(kind)) {
			String resourceKind = requireString(arguments, "resourceKind");
			if (ResourceGatheringCatalog.entry(resourceKind).isEmpty()) {
				throw new JsonParseException("Unsupported resourceKind: " + resourceKind);
			}
			requirePositiveInt(arguments, "quantity");
		}
		else {
			throw new JsonParseException("Unsupported action goal kind: " + kind);
		}
	}

	private static void validateCraftRecipeArguments(JsonObject arguments) {
		requireString(arguments, "recipeId");
		requirePositiveInt(arguments, "times");
	}

	private static void validateCollectSmeltedItemsArguments(JsonObject arguments) {
		if (arguments.has("processId") && !arguments.get("processId").isJsonNull()) {
			requireString(arguments, "processId");
		}
		if (arguments.has("confirmationToken") && !arguments.get("confirmationToken").isJsonNull()) {
			requireString(arguments, "confirmationToken");
		}
	}

	private static void validateCancelSmeltingArguments(JsonObject arguments) {
		requireString(arguments, "processId");
	}

	private static void validateInventoryItemArguments(JsonObject arguments) {
		requireString(arguments, "itemId");
	}

	private static void validateDropItemsArguments(JsonObject arguments) {
		requireString(arguments, "itemId");
		requirePositiveInt(arguments, "quantity");
	}

	private static void validateGivePlayerArguments(JsonObject arguments) {
		requireString(arguments, "targetPlayer");
		requireString(arguments, "itemId");
		requirePositiveInt(arguments, "quantity");
	}

	private static void validateAttackEntityArguments(JsonObject arguments) {
		requireEntitySelector(arguments);
		if (arguments.has("mode") && !arguments.get("mode").isJsonNull()) {
			requireAttackMode(arguments);
		}
	}

	private static void validateUseEntityArguments(JsonObject arguments) {
		requireEntitySelector(arguments);
		if (arguments.has("itemId") && !arguments.get("itemId").isJsonNull()) {
			requireString(arguments, "itemId");
		}
	}

	private static void validatePlaceBlockArguments(JsonObject arguments) {
		requireString(arguments, "itemId");
		validateFacePreference(arguments, "facePreference");
		validateTargetMaterial(arguments, "requireCurrentTargetMaterial");
		validateBlockTargetShape(arguments, target -> {
			validateFacePreference(target, "facePreference");
			validateTargetMaterial(target, "requireCurrentTargetMaterial");
		});
	}

	private static void validateUseBlockArguments(JsonObject arguments) {
		if (arguments.has("itemId") && !arguments.get("itemId").isJsonNull()) {
			requireString(arguments, "itemId");
		}
		validateFacePreference(arguments, "facePreference");
		if (arguments.has("expectedSupportBlockIds") && !arguments.get("expectedSupportBlockIds").isJsonNull()) {
			requireStringArray(arguments, "expectedSupportBlockIds");
		}
		validateTargetMaterial(arguments, "expectedTargetMaterial");
		validateBlockTargetShape(arguments, target -> {
			validateFacePreference(target, "facePreference");
			if (target.has("expectedSupportBlockIds") && !target.get("expectedSupportBlockIds").isJsonNull()) {
				requireStringArray(target, "expectedSupportBlockIds");
			}
			validateTargetMaterial(target, "expectedTargetMaterial");
		});
	}

	private static void validateBreakBlocksArguments(JsonObject arguments) {
		if (!arguments.has("targets") || !arguments.get("targets").isJsonArray()) {
			throw new JsonParseException("targets must be an array");
		}
		JsonArray targets = arguments.getAsJsonArray("targets");
		if (targets.isEmpty()) {
			throw new JsonParseException("targets must not be empty");
		}
		if (targets.size() > ai.moeru.airicraft.agent.tasks.BlockBreakStepArgs.MAX_TARGETS) {
			throw new JsonParseException("targets must contain at most " + ai.moeru.airicraft.agent.tasks.BlockBreakStepArgs.MAX_TARGETS + " entries");
		}
		for (JsonElement element : targets) {
			if (!element.isJsonObject()) {
				throw new JsonParseException("targets entries must be objects");
			}
			JsonObject target = element.getAsJsonObject();
			requireInt(target, "x");
			requireInt(target, "y");
			requireInt(target, "z");
			requireStringArray(target, "expectedBlockIds");
		}
	}

	private static void validateBlockTargetShape(JsonObject arguments, Consumer<JsonObject> targetValidator) {
		boolean hasTargets = arguments.has("targets") && !arguments.get("targets").isJsonNull();
		boolean hasAnyRootCoordinate = arguments.has("x") || arguments.has("y") || arguments.has("z");
		if (hasTargets && hasAnyRootCoordinate) {
			throw new JsonParseException("targets cannot be combined with root x/y/z");
		}
		if (!hasTargets) {
			requireInt(arguments, "x");
			requireInt(arguments, "y");
			requireInt(arguments, "z");
			return;
		}
		if (!arguments.get("targets").isJsonArray()) {
			throw new JsonParseException("targets must be an array");
		}
		JsonArray targets = arguments.getAsJsonArray("targets");
		if (targets.isEmpty()) {
			throw new JsonParseException("targets must not be empty");
		}
		if (targets.size() > ai.moeru.airicraft.agent.tasks.BlockBreakStepArgs.MAX_TARGETS) {
			throw new JsonParseException("targets must contain at most " + ai.moeru.airicraft.agent.tasks.BlockBreakStepArgs.MAX_TARGETS + " entries");
		}
		for (JsonElement element : targets) {
			if (!element.isJsonObject()) {
				throw new JsonParseException("targets entries must be objects");
			}
			JsonObject target = element.getAsJsonObject();
			requireInt(target, "x");
			requireInt(target, "y");
			requireInt(target, "z");
			targetValidator.accept(target);
		}
	}

	private static void validateFacePreference(JsonObject arguments, String key) {
		if (!arguments.has(key) || arguments.get(key).isJsonNull()) {
			return;
		}
		String value = requireString(arguments, key);
		if (!List.of("auto", "down", "north", "south", "east", "west", "up").contains(value)) {
			throw new JsonParseException("Unsupported " + key + ": " + value);
		}
	}

	private static void validateTargetMaterial(JsonObject arguments, String key) {
		if (!arguments.has(key) || arguments.get(key).isJsonNull()) {
			return;
		}
		String value = requireString(arguments, key);
		if (!List.of("air", "replaceable", "air_or_replaceable").contains(value)) {
			throw new JsonParseException("Unsupported " + key + ": " + value);
		}
	}

	private static void requireEntitySelector(JsonObject arguments) {
		boolean hasUuid = getString(arguments, "uuid").isPresent();
		boolean hasName = getString(arguments, "name").isPresent();
		boolean hasEntityTypeId = getString(arguments, "entityTypeId").isPresent();
		if (!hasUuid && !hasName && !hasEntityTypeId) {
			throw new JsonParseException("entity selector requires uuid, name, or entityTypeId");
		}
	}

	private static void validateTakeALookArguments(JsonObject arguments) {
		boolean hasDirection = getString(arguments, "direction").isPresent();
		boolean hasPlayer = getString(arguments, "targetPlayer").isPresent();
		boolean hasAnyCoordinate = arguments.has("x") || arguments.has("y") || arguments.has("z");
		boolean hasBlock = hasAnyCoordinate;
		int targetModes = (hasDirection ? 1 : 0) + (hasBlock ? 1 : 0) + (hasPlayer ? 1 : 0);
		if (targetModes > 1) {
			throw new JsonParseException("take_a_look accepts only one of direction, block coordinates, or targetPlayer");
		}
		if (hasDirection) {
			String direction = requireString(arguments, "direction").toLowerCase(Locale.ROOT);
			if (!List.of("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest").contains(direction)) {
				throw new JsonParseException("Unsupported direction: " + direction);
			}
		}
		if (hasBlock) {
			requireInt(arguments, "x");
			requireInt(arguments, "y");
			requireInt(arguments, "z");
		}
	}

	private static void validateInspectWorldArguments(JsonObject arguments) {
		String mode = requireString(arguments, "mode");
		String scope = requireString(arguments, "scope");
		if (arguments.has("detail") && !List.of("summary", "blocks").contains(requireString(arguments, "detail"))) throw new JsonParseException("Unsupported detail");
		if (!List.of("inspect_area", "find_blocks", "find_placement_sites", "check_position", "check_interaction").contains(mode)) {
			throw new JsonParseException("Unsupported inspect_world mode: " + mode);
		}
		validateInspectWorldScope(arguments, scope);
		validateOptionalIntRange(arguments, "maxResults", 1, 64);
		validateOptionalIntRange(arguments, "nearbyRequiredHorizontalRadius", 0, 16);
		validateOptionalIntRange(arguments, "nearbyRequiredVerticalRadius", 0, 8);

		if ("inspect_area".equals(mode)) {
			rejectAny(arguments, "blockIds", "stateFilters", "targetMaterial", "supportBlockIds", "supportStateFilters",
				"requireSolidTopSupport", "requireAirAbove", "requireStandableAdjacent", "requireWithinInteractionRange",
				"nearbyRequiredBlockIds", "nearbyRequiredHorizontalRadius", "nearbyRequiredVerticalRadius");
			return;
		}
		if ("find_blocks".equals(mode)) {
			requireStringArray(arguments, "blockIds");
			validateStateFilters(arguments, "stateFilters");
			rejectAny(arguments, "targetMaterial", "supportBlockIds", "supportStateFilters",
				"requireSolidTopSupport", "requireAirAbove", "requireStandableAdjacent", "requireWithinInteractionRange",
				"nearbyRequiredBlockIds", "nearbyRequiredHorizontalRadius", "nearbyRequiredVerticalRadius");
			return;
		}

		rejectAny(arguments, "blockIds", "stateFilters");
		if (arguments.has("targetMaterial") && !arguments.get("targetMaterial").isJsonNull()) {
			String targetMaterial = requireString(arguments, "targetMaterial");
			if (!List.of("air", "replaceable", "air_or_replaceable").contains(targetMaterial)) {
				throw new JsonParseException("Unsupported targetMaterial: " + targetMaterial);
			}
		}
		if (arguments.has("supportBlockIds") && !arguments.get("supportBlockIds").isJsonNull()) {
			requireStringArray(arguments, "supportBlockIds");
		}
		validateStateFilters(arguments, "supportStateFilters");
		if (arguments.has("requireSolidTopSupport") && !arguments.get("requireSolidTopSupport").isJsonNull()) {
			requireBoolean(arguments, "requireSolidTopSupport");
		}
		if (arguments.has("requireAirAbove") && !arguments.get("requireAirAbove").isJsonNull()) {
			requireBoolean(arguments, "requireAirAbove");
		}
		if (arguments.has("requireStandableAdjacent") && !arguments.get("requireStandableAdjacent").isJsonNull()) {
			requireBoolean(arguments, "requireStandableAdjacent");
		}
		if (arguments.has("requireWithinInteractionRange") && !arguments.get("requireWithinInteractionRange").isJsonNull()) {
			requireBoolean(arguments, "requireWithinInteractionRange");
		}
		if (arguments.has("nearbyRequiredBlockIds") && !arguments.get("nearbyRequiredBlockIds").isJsonNull()) {
			requireStringArray(arguments, "nearbyRequiredBlockIds");
		}
	}

	private static void validateInspectWorldScope(JsonObject arguments, String scope) {
		switch (scope) {
			case "self" -> {
				rejectAny(arguments, "x", "y", "z", "x1", "y1", "z1", "x2", "y2", "z2");
				validateOptionalIntRange(arguments, "horizontalRadius", 0, 16);
				validateOptionalIntRange(arguments, "verticalRadius", 0, 8);
			}
			case "center" -> {
				requireInt(arguments, "x");
				requireInt(arguments, "y");
				requireInt(arguments, "z");
				rejectAny(arguments, "x1", "y1", "z1", "x2", "y2", "z2");
				validateOptionalIntRange(arguments, "horizontalRadius", 0, 16);
				validateOptionalIntRange(arguments, "verticalRadius", 0, 8);
			}
			case "box" -> {
				requireInt(arguments, "x1");
				requireInt(arguments, "y1");
				requireInt(arguments, "z1");
				requireInt(arguments, "x2");
				requireInt(arguments, "y2");
				requireInt(arguments, "z2");
				rejectAny(arguments, "x", "y", "z", "horizontalRadius", "verticalRadius");
			}
			default -> throw new JsonParseException("Unsupported inspect_world scope: " + scope);
		}
	}

	private static void validateStateFilters(JsonObject arguments, String key) {
		if (!arguments.has(key) || arguments.get(key).isJsonNull()) {
			return;
		}
		requireStringArray(arguments, key);
		for (JsonElement element : arguments.getAsJsonArray(key)) {
			String filter = element.getAsString();
			int separator = filter.indexOf('=');
			if (separator <= 0 || separator != filter.lastIndexOf('=') || separator == filter.length() - 1) {
				throw new JsonParseException(key + " values must use exact key=value syntax");
			}
		}
	}

	private static void validateOptionalIntRange(JsonObject arguments, String key, int min, int max) {
		if (!arguments.has(key) || arguments.get(key).isJsonNull()) {
			return;
		}
		int value = requireInt(arguments, key);
		if (value < min || value > max) {
			throw new JsonParseException(key + " must be between " + min + " and " + max);
		}
	}

	private static void rejectAny(JsonObject arguments, String... keys) {
		for (String key : keys) {
			if (arguments.has(key) && !arguments.get(key).isJsonNull()) {
				throw new JsonParseException("inspect_world field not allowed for this mode/scope: " + key);
			}
		}
	}

	private static void requireAttackMode(JsonObject arguments) {
		String mode = requireString(arguments, "mode");
		try {
			EntityAttackMode.fromWireValue(mode);
		}
		catch (IllegalArgumentException exception) {
			throw new JsonParseException(exception.getMessage(), exception);
		}
	}

	private static void validateSmeltItemsArguments(JsonObject arguments) {
		requireString(arguments, "optionId");
		requirePositiveInt(arguments, "inputQuantity");
		String fuelMode = getString(arguments, "fuelMode").orElse(null);
		try {
			SmeltingFuelMode parsedFuelMode = SmeltingFuelMode.fromWireValue(fuelMode);
			if (parsedFuelMode == SmeltingFuelMode.MANUAL) {
				requireString(arguments, "fuelItemId");
				requirePositiveInt(arguments, "fuelQuantity");
			}
			else if (arguments.has("fuelQuantity") && !arguments.get("fuelQuantity").isJsonNull()) {
				int fuelQuantity = requireInt(arguments, "fuelQuantity");
				if (fuelQuantity < 0) {
					throw new JsonParseException("fuelQuantity must be non-negative");
				}
			}
		}
		catch (IllegalArgumentException exception) {
			throw new JsonParseException(exception.getMessage(), exception);
		}
		if (arguments.has("confirmationToken") && !arguments.get("confirmationToken").isJsonNull()) {
			requireString(arguments, "confirmationToken");
		}
	}

	private static void validatePolicyArguments(JsonObject arguments) {
		if (arguments.has("clearAll") && !arguments.get("clearAll").isJsonPrimitive()) {
			throw new JsonParseException("clearAll must be boolean");
		}
		if (arguments.has("removeRuleIds")) {
			requireStringArray(arguments, "removeRuleIds", true);
		}
		if (!arguments.has("upserts") || arguments.get("upserts").isJsonNull()) {
			return;
		}
		if (!arguments.get("upserts").isJsonArray()) {
			throw new JsonParseException("upserts must be an array");
		}
		for (JsonElement element : arguments.getAsJsonArray("upserts")) {
			if (!element.isJsonObject()) {
				throw new JsonParseException("upsert must be an object");
			}
			JsonObject upsert = element.getAsJsonObject();
			requireString(upsert, "effect");
			if (!upsert.has("match") || !upsert.get("match").isJsonObject()) {
				throw new JsonParseException("upsert match must be an object");
			}
			requireString(upsert.getAsJsonObject("match"), "eventType");
		}
	}

	private static void validateConfigurePathfindArguments(JsonObject arguments) {
		if (arguments == null || !arguments.has("settings") || !arguments.get("settings").isJsonObject()
			|| arguments.getAsJsonObject("settings").isEmpty()) {
			throw new JsonParseException("settings must be a non-empty object");
		}
	}

	private static void validateConfigureLightingArguments(JsonObject arguments) {
		requireBoolean(arguments, "enabled");
		String mode = requireString(arguments, "mode");
		if (!List.of("darkness", "spawn_proof").contains(mode)) {
			throw new JsonParseException("Unsupported mode: " + mode);
		}
		int maxLightLevel = requireInt(arguments, "maxLightLevel");
		if (maxLightLevel < 0 || maxLightLevel > 15) {
			throw new JsonParseException("maxLightLevel must be between 0 and 15");
		}
		int minSpacingBlocks = requireInt(arguments, "minSpacingBlocks");
		if (minSpacingBlocks < 1 || minSpacingBlocks > 16) {
			throw new JsonParseException("minSpacingBlocks must be between 1 and 16");
		}
	}

	private static void validateConfigureFoodArguments(JsonObject arguments) {
		if (arguments.isEmpty()) return;
		String goal = requireString(arguments, "goal");
		String foodChoice = requireString(arguments, "foodChoice");
		if (arguments.size() != 2 || !List.of("off", "movement", "heal").contains(goal)
			|| !List.of("any", "cooked_only").contains(foodChoice)) {
			throw new JsonParseException("Expected goal=off|movement|heal and foodChoice=any|cooked_only");
		}
	}

	private static void validateConfigureReflexArguments(JsonObject arguments) {
		if (arguments.isEmpty()) return;
		for (String key : List.of("combatEnabled", "drowningEnabled", "requireLineOfSight")) {
			if (!arguments.has(key) || !arguments.get(key).isJsonPrimitive()
				|| !arguments.getAsJsonPrimitive(key).isBoolean()) throw new JsonParseException(key + " must be boolean");
		}
		if (!arguments.has("maxThreatDistance") || !arguments.get("maxThreatDistance").isJsonPrimitive()
			|| !arguments.getAsJsonPrimitive("maxThreatDistance").isNumber()) {
			throw new JsonParseException("maxThreatDistance must be an integer between 1 and 32");
		}
		double distance = arguments.get("maxThreatDistance").getAsDouble();
		if (!Double.isFinite(distance) || distance != Math.rint(distance) || distance < 1 || distance > 32) {
			throw new JsonParseException("maxThreatDistance must be an integer between 1 and 32");
		}
	}

	private static String requireString(JsonObject object, String key) {
		String value = getString(object, key).orElse(null);
		if (value == null || value.isBlank()) {
			throw new JsonParseException(key + " must be a non-empty string");
		}
		return value;
	}

	private static int requireInt(JsonObject object, String key) {
		try {
			if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
				throw new JsonParseException(key + " must be an integer");
			}
			return object.get(key).getAsInt();
		}
		catch (RuntimeException exception) {
			throw new JsonParseException(key + " must be an integer", exception);
		}
	}

	private static int requirePositiveInt(JsonObject object, String key) {
		int value = requireInt(object, key);
		if (value <= 0) {
			throw new JsonParseException(key + " must be positive");
		}
		return value;
	}

	private static void requireBoolean(JsonObject object, String key) {
		try {
			if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
				throw new JsonParseException(key + " must be boolean");
			}
			object.get(key).getAsBoolean();
		}
		catch (RuntimeException exception) {
			throw new JsonParseException(key + " must be boolean", exception);
		}
	}

	private static void requireStringArray(JsonObject object, String key) {
		requireStringArray(object, key, false);
	}

	private static void requireStringArray(JsonObject object, String key, boolean allowEmpty) {
		if (!object.has(key) || !object.get(key).isJsonArray()) {
			throw new JsonParseException(key + " must be a string array");
		}
		JsonArray array = object.getAsJsonArray(key);
		if (!allowEmpty && array.isEmpty()) {
			throw new JsonParseException(key + " must not be empty");
		}
		for (JsonElement element : array) {
			if (!element.isJsonPrimitive() || element.getAsString().isBlank()) {
				throw new JsonParseException(key + " must contain non-empty strings");
			}
		}
	}

	private static Optional<String> getString(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
			return Optional.empty();
		}
		try {
			String value = object.get(fieldName).getAsString();
			return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static void validateTransferItem(JsonObject item) {
		requireString(item, "itemId");
		int quantity = requireInt(item, "quantity");
		var value = item.get("quantity").getAsJsonPrimitive();
		if (!value.isNumber() || value.getAsDouble() != quantity || quantity < 1 || quantity > 2304) throw new JsonParseException("quantity must be 1..2304");
	}

	private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
		LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("type", "object");
		parameters.put("properties", properties);
		parameters.put("required", required);
		parameters.put("additionalProperties", false);
		return Map.of(
			"type", "function",
			"function", Map.of(
				"name", name,
				"description", description,
				"parameters", parameters
			)
		);
	}

	@SafeVarargs
	private static Map<String, Object> properties(Map.Entry<String, Object>... entries) {
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : entries) {
			map.put(entry.getKey(), entry.getValue());
		}
		return map;
	}

	private static Map.Entry<String, Object> prop(String name, Object schema) {
		return Map.entry(name, schema);
	}

	private static Map<String, Object> string(String description) {
		return Map.of("type", "string", "description", description);
	}

	private static Map<String, Object> optionalString(String description) {
		return Map.of("type", "string", "description", description);
	}

	private static Map<String, Object> integer(String description) {
		return Map.of("type", "integer", "description", description);
	}

	private static Map<String, Object> number(String description) {
		return Map.of("type", "number", "description", description);
	}

	private static Map<String, Object> bool(String description) {
		return Map.of("type", "boolean", "description", description);
	}

	private static Map<String, Object> enumString(String description, List<String> values) {
		LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "string");
		schema.put("description", description);
		schema.put("enum", values);
		return schema;
	}

	private static Map<String, Object> stringArray(String description) {
		return array(description, string("Array item."));
	}

	private static Map<String, Object> array(String description, Object items) {
		LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "array");
		schema.put("description", description);
		schema.put("items", items);
		return schema;
	}

	private static Map<String, Object> acquisitionConstraintsSchema() {
		return Map.of("type", "object", "additionalProperties", false, "description",
			"Optional acquisition scope. Defaults: center at submission, radius 16, verticalRadius 16, surfaceOnly false, visibleOnly false. The center stays fixed during this job.",
			"properties", properties(
				prop("center", Map.of("type", "object", "additionalProperties", false,
					"properties", properties(prop("x", integer("Center x.")), prop("y", integer("Center y.")), prop("z", integer("Center z."))),
					"required", List.of("x", "y", "z"))),
				prop("radius", integer("Horizontal search radius, 1..32 blocks.")),
				prop("verticalRadius", integer("Vertical search radius, 1..32 blocks.")),
				prop("visibleOnly", bool("Discover block sources from sparse first-hit rays within 24 blocks of the current player instead of searching buried blocks. Remember seen sources for this attempt and re-sample after mining and movement; recheck blocks before working them. Drops are still collected in scope. May miss visible sources; false preserves loaded-block excavation. Does not change pathfinding terrain permissions.")),
				prop("surfaceOnly", bool("Restrict sources and work positions to the top ground layer or above, ignoring tree logs/leaves as roofs. Stop acquisition if travel leaves this scope; does not override survival reflexes."))));
	}

	private static Map<String, Object> breakBlockTargetSchema() {
		return Map.of(
			"type", "object",
			"properties", properties(
				prop("x", integer("Target block x coordinate.")),
				prop("y", integer("Target block y coordinate.")),
				prop("z", integer("Target block z coordinate.")),
				prop("expectedBlockIds", stringArray("Exact block ids allowed at this target, copied from inspect_world."))
			),
			"required", List.of("x", "y", "z", "expectedBlockIds"),
			"additionalProperties", false
		);
	}

	private static Map<String, Object> placeBlockTargetSchema() {
		return Map.of(
			"type", "object",
			"properties", properties(
				prop("x", integer("Intended modified target block x coordinate.")),
				prop("y", integer("Intended modified target block y coordinate.")),
				prop("z", integer("Intended modified target block z coordinate.")),
				prop("facePreference", enumString("Direction from the target cell to its support neighbor. Use down for the floor below (clicks its top), up for the ceiling above. Prefer auto unless a specific support is required. Overrides root default.", List.of("auto", "down", "north", "south", "east", "west", "up"))),
				prop("requireCurrentTargetMaterial", enumString("Required current target material before placement. Overrides root default.", List.of("air", "replaceable", "air_or_replaceable")))
			),
			"required", List.of("x", "y", "z"),
			"additionalProperties", false
		);
	}

	private static Map<String, Object> useBlockTargetSchema() {
		return Map.of(
			"type", "object",
			"properties", properties(
				prop("x", integer("Intended modified target block x coordinate.")),
				prop("y", integer("Intended modified target block y coordinate.")),
				prop("z", integer("Intended modified target block z coordinate.")),
				prop("facePreference", enumString("For air/replaceable targets, direction from target to support: down selects the floor below and clicks its top (for example placing a torch). For an existing solid target, selects the clicked face. Prefer auto. Overrides root default.", List.of("auto", "down", "north", "south", "east", "west", "up"))),
				prop("expectedSupportBlockIds", stringArray("Optional exact block ids expected on the clicked support block. Overrides root default.")),
				prop("expectedTargetMaterial", enumString("Optional precondition for an air/replaceable target, such as planting. Omit when interacting with existing solid blocks (chests, furnaces, doors); every listed value rejects them. Overrides root default.", List.of("air", "replaceable", "air_or_replaceable")))
			),
			"required", List.of("x", "y", "z"),
			"additionalProperties", false
		);
	}

	private static Map<String, Object> policyUpsertSchema() {
		return Map.of(
			"type", "object",
			"properties", properties(
				prop("ruleId", string("Optional stable rule id.")),
				prop("effect", enumString("Policy effect.", List.of("allow", "ignore", "semantic_only", "trigger_only"))),
				prop("match", Map.of(
					"type", "object",
					"properties", properties(
						prop("eventType", string("Event type to match.")),
						prop("player", string("Optional player match.")),
						prop("speaker", string("Optional speaker match.")),
						prop("actor", string("Optional actor match.")),
						prop("itemId", string("Optional item id match.")),
						prop("damageTypeId", string("Optional damage type id match.")),
						prop("attackerName", string("Optional attacker name match."))
					),
					"required", List.of("eventType"),
					"additionalProperties", false
				)),
				prop("reason", string("Optional reason."))
			),
			"required", List.of("effect", "match"),
			"additionalProperties", false
		);
	}

	private record BuiltInTool(
		String name,
		boolean readTool,
		boolean batchSafeReadTool,
		Map<String, Object> openAiTool,
		Consumer<JsonObject> validator
	) {
		private void validate(JsonObject arguments) {
			validator.accept(arguments);
		}
	}
}
