package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunityResolver;
import ai.moeru.airicraft.agent.tasks.CraftingGridKind;
import ai.moeru.airicraft.agent.tasks.EntitySelectorResolver;
import ai.moeru.airicraft.agent.tasks.InventoryItemCounter;
import ai.moeru.airicraft.agent.tasks.NearbyEntityService;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class CurrentInventoryService implements CurrentInventoryTool {
	private static final int MAX_RECIPE_RESULTS = 24;
	private static final int TABLE_SEARCH_RADIUS = 10;
	private static final int TABLE_SEARCH_VERTICAL_RADIUS = 4;
	private static final double TABLE_INTERACTION_RANGE_SQUARED = 20.25D;
	private static final String CRAFTING_TABLE_ITEM_ID = "minecraft:crafting_table";

	private final Supplier<Minecraft> clientSupplier;
	private final InventoryItemCounter itemCounter = new InventoryItemCounter();

	public CurrentInventoryService(Supplier<Minecraft> clientSupplier) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
	}

	@Override
	public CompletableFuture<String> inspectInventory(String prompt) {
		Minecraft minecraft = clientSupplier.get();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			return CompletableFuture.completedFuture("INVENTORY_UNAVAILABLE: world_not_loaded");
		}

		List<ItemStack> stacks = new ArrayList<>();
		for (int slot = 0; slot < minecraft.player.getInventory().getContainerSize(); slot++) {
			stacks.add(minecraft.player.getInventory().getItem(slot));
		}

		String dimension = minecraft.level.dimension().location().toString();
		String position = minecraft.player.blockPosition().getX() + "," + minecraft.player.blockPosition().getY() + "," + minecraft.player.blockPosition().getZ();
		String equippedItemId = BuiltInRegistries.ITEM.getKey(minecraft.player.getMainHandItem().getItem()).toString();
		int selectedHotbarSlot = minecraft.player.getInventory().getSelectedSlot();
		List<String> durability = new ArrayList<>();
		int freeStorageSlots = 0;
		for (int slot = 0; slot < stacks.size(); slot++) {
			ItemStack stack = stacks.get(slot);
			if (slot < Inventory.INVENTORY_SIZE && stack.isEmpty()) freeStorageSlots++;
			if (!stack.isEmpty() && stack.isDamageableItem()) {
				durability.add(PlannerStateText.durability(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
					stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage()));
			}
		}
		Map<String, String> equipment = new LinkedHashMap<>();
		for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND))
			equipment.put(slot.getName(), BuiltInRegistries.ITEM.getKey(minecraft.player.getItemBySlot(slot).getItem()).toString());
		return CompletableFuture.completedFuture(
			"At " + position + " in " + PlannerStateText.item(dimension) + ".\n"
				+ PlannerStateText.inventory(itemCounter.count(minecraft.player.getInventory())) + " " + freeStorageSlots + " free storage slots.\n"
				+ (freeStorageSlots == 0 ? "No empty storage slots: only compatible non-full stacks can accept pickups. Free space before collecting other items.\n" : "")
				+ PlannerStateText.hotbar(hotbarItems(minecraft.player.getInventory()), selectedHotbarSlot) + "\n"
				+ PlannerStateText.equipment(equippedItemId, equipment) + "\n"
				+ (durability.isEmpty() ? "" : "Durability: " + String.join("; ", durability) + ".\n")
				+ PlannerStateText.vitals(Map.of("health", minecraft.player.getHealth(), "maxHealth", minecraft.player.getMaxHealth(),
					"food", minecraft.player.getFoodData().getFoodLevel(), "saturation", minecraft.player.getFoodData().getSaturationLevel(),
					"air", minecraft.player.getAirSupply(), "maxAir", minecraft.player.getMaxAirSupply()))
		);
	}

	@Override
	public CompletableFuture<String> checkCraftables(com.google.gson.JsonObject arguments) {
		Minecraft minecraft = clientSupplier.get();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			return CompletableFuture.completedFuture("CRAFTABLES_UNAVAILABLE: world_not_loaded");
		}

		List<CraftingOpportunity> opportunities = CraftingOpportunityResolver.availableCrafts(minecraft.player);
		Map<String, Integer> itemCounts = itemCounter.count(minecraft.player.getInventory());
		CraftingTableAccess tableAccess = craftingTableAccess(minecraft, itemCounts);
		String outputItemId = arguments.has("outputItemId") ? arguments.get("outputItemId").getAsString() : null;
		return CompletableFuture.completedFuture(formatCraftables(opportunities, tableAccess, outputItemId));
	}

	static String formatCraftables(List<CraftingOpportunity> opportunities, CraftingTableAccess tableAccess, String outputItemId) {
		Map<String, CraftingOpportunity> unique = new LinkedHashMap<>();
		for (CraftingOpportunity opportunity : opportunities) {
			if (outputItemId == null || outputItemId.equals(opportunity.outputItemId()))
				unique.putIfAbsent(opportunity.recipeId(), opportunity);
		}
		opportunities = List.copyOf(unique.values());
		List<CraftingOpportunity> craftableNow = opportunities.stream()
			.filter(opportunity -> opportunity.gridKind() == CraftingGridKind.PLAYER_2X2 || tableAccess == CraftingTableAccess.OPEN)
			.limit(MAX_RECIPE_RESULTS)
			.toList();
		List<CraftingOpportunity> craftableWithSetup = opportunities.stream()
			.filter(opportunity -> opportunity.gridKind() == CraftingGridKind.WORKBENCH_3X3 && tableAccess != CraftingTableAccess.OPEN && tableAccess != CraftingTableAccess.MISSING)
			.limit(MAX_RECIPE_RESULTS)
			.toList();
		List<CraftingOpportunity> blockedByTable = opportunities.stream()
			.filter(opportunity -> opportunity.gridKind() == CraftingGridKind.WORKBENCH_3X3 && tableAccess == CraftingTableAccess.MISSING)
			.limit(MAX_RECIPE_RESULTS)
			.toList();
		List<CraftingOpportunity> executable = new ArrayList<>();
		executable.addAll(craftableNow);
		executable.addAll(craftableWithSetup);
		String exactItemIds = executable.isEmpty()
			? "[]"
			: executable.stream()
				.map(CraftingOpportunity::recipeId)
				.collect(Collectors.joining(", ", "[", "]"));
		int returned = craftableNow.size() + craftableWithSetup.size() + blockedByTable.size();
		return "Tool result for check_craftables: "
				+ "craftableNow=" + formatCrafts(craftableNow)
				+ ", craftableWithSetup=" + formatCrafts(craftableWithSetup)
				+ ", blocked=" + formatCrafts(blockedByTable)
				+ ", exactRecipeIds=" + exactItemIds
				+ ", matchedRecipes=" + unique.size() + ", returnedRecipes=" + returned
				+ ", truncated=" + (returned < unique.size())
				+ ", craftingTableAccess=" + tableAccess.name().toLowerCase(java.util.Locale.ROOT)
				+ ", note=Use exactRecipeIds for CRAFT_RECIPE.recipeId. times means recipe runs, not output item count. 3x3 workbench recipes can automatically navigate to a nearby crafting table within 10 blocks, place one from inventory, or craft one from planks. If truncated, supply outputItemId to filter before the result limit.";
	}

	@Override
	public CompletableFuture<String> inspectNearbyEntities(com.google.gson.JsonObject arguments) {
		Minecraft minecraft = clientSupplier.get();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			return CompletableFuture.completedFuture("NEARBY_ENTITIES_UNAVAILABLE: world_not_loaded");
		}

		double radius = arguments.has("radius") ? arguments.get("radius").getAsDouble() : EntitySelectorResolver.DEFAULT_NEARBY_RADIUS_BLOCKS;
		int maxResults = arguments.has("maxResults") ? arguments.get("maxResults").getAsInt() : NearbyEntityService.DEFAULT_MAX_RESULTS;
		Set<String> entityTypeIds = arguments.has("entityTypeIds")
			? arguments.getAsJsonArray("entityTypeIds").asList().stream().map(com.google.gson.JsonElement::getAsString).collect(Collectors.toSet())
			: Set.of();
		List<NearbyEntityService.NearbyEntitySnapshot> nearbyEntities = NearbyEntityService.listNearbyEntities(minecraft, radius, maxResults, entityTypeIds);
		return CompletableFuture.completedFuture(
			"Tool result for inspect_nearby_entities: "
				+ "nearbyRadius=" + radius
				+ ", loadedEntitiesOnly=true, maxResults=" + maxResults
				+ ", entityCount=" + nearbyEntities.size()
				+ ", entities=" + formatNearbyEntities(nearbyEntities)
		);
	}

	private static String formatCrafts(List<CraftingOpportunity> opportunities) {
		if (opportunities == null || opportunities.isEmpty()) {
			return "none";
		}
		return opportunities.stream()
			.map(CraftingOpportunity::compactDescription)
			.collect(Collectors.joining("; ", "[", "]"));
	}

	private static List<PlannerStateText.HotbarSlot> hotbarItems(Inventory inventory) {
		var items = new ArrayList<PlannerStateText.HotbarSlot>();
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = inventory.getItem(slot);
			items.add(new PlannerStateText.HotbarSlot(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
		}
		return List.copyOf(items);
	}

	static String formatNearbyEntities(List<NearbyEntityService.NearbyEntitySnapshot> nearbyEntities) {
		if (nearbyEntities == null || nearbyEntities.isEmpty()) {
			return "none";
		}
		Map<String, String> uuidTokens = NearbyEntityService.plannerUuidTokens(nearbyEntities);
		return nearbyEntities.stream()
			.map(snapshot -> snapshot.compactDescription(uuidTokens.get(snapshot.uuid())))
			.collect(Collectors.joining(", ", "[", "]"));
	}

	private static CraftingTableAccess craftingTableAccess(Minecraft minecraft, Map<String, Integer> itemCounts) {
		return craftingTableAccess(
			minecraft.player.containerMenu instanceof CraftingMenu,
			hasUsableNearbyCraftingTable(minecraft, minecraft.player),
			itemCounts
		);
	}

	static CraftingTableAccess craftingTableAccess(boolean workbenchOpen, boolean usableNearbyTable, Map<String, Integer> itemCounts) {
		Map<String, Integer> safeItemCounts = itemCounts == null ? Map.of() : itemCounts;
		if (workbenchOpen) {
			return CraftingTableAccess.OPEN;
		}
		if (usableNearbyTable) {
			return CraftingTableAccess.NEARBY;
		}
		if (safeItemCounts.getOrDefault(CRAFTING_TABLE_ITEM_ID, 0) > 0) {
			return CraftingTableAccess.IN_INVENTORY;
		}
		if (plankCount(safeItemCounts) >= 4) {
			return CraftingTableAccess.CAN_CRAFT;
		}
		return CraftingTableAccess.MISSING;
	}

	private static boolean hasUsableNearbyCraftingTable(Minecraft minecraft, LocalPlayer player) {
		if (minecraft.level == null || player == null) {
			return false;
		}
		BlockPos origin = player.blockPosition();
		for (int dx = -TABLE_SEARCH_RADIUS; dx <= TABLE_SEARCH_RADIUS; dx++) {
			for (int dy = -TABLE_SEARCH_VERTICAL_RADIUS; dy <= TABLE_SEARCH_VERTICAL_RADIUS; dy++) {
				for (int dz = -TABLE_SEARCH_RADIUS; dz <= TABLE_SEARCH_RADIUS; dz++) {
					BlockPos pos = origin.offset(dx, dy, dz);
					if (origin.distSqr(pos) > TABLE_SEARCH_RADIUS * TABLE_SEARCH_RADIUS || !minecraft.level.hasChunkAt(pos)) {
						continue;
					}
					if (minecraft.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)
						&& (withinInteractionRange(player, pos) || hasStandableAdjacentPosition(minecraft, pos))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean hasStandableAdjacentPosition(Minecraft minecraft, BlockPos tablePos) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			if (isStandable(minecraft, tablePos.relative(direction))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isStandable(Minecraft minecraft, BlockPos pos) {
		if (minecraft.level == null || !minecraft.level.hasChunkAt(pos) || !minecraft.level.hasChunkAt(pos.above())) {
			return false;
		}
		BlockState feet = minecraft.level.getBlockState(pos);
		BlockState head = minecraft.level.getBlockState(pos.above());
		BlockState floor = minecraft.level.getBlockState(pos.below());
		return (feet.isAir() || feet.canBeReplaced())
			&& (head.isAir() || head.canBeReplaced())
			&& floor.isFaceSturdy(minecraft.level, pos.below(), Direction.UP);
	}

	private static boolean withinInteractionRange(LocalPlayer player, BlockPos pos) {
		return player.distanceToSqr(Vec3.atCenterOf(pos)) <= TABLE_INTERACTION_RANGE_SQUARED;
	}

	private static int plankCount(Map<String, Integer> itemCounts) {
		int count = 0;
		for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
			if (entry.getKey().endsWith("_planks")) {
				count += entry.getValue();
			}
		}
		return count;
	}

	enum CraftingTableAccess {
		OPEN,
		NEARBY,
		IN_INVENTORY,
		CAN_CRAFT,
		MISSING
	}

}
