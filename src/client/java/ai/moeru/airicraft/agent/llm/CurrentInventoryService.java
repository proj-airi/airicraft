package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunityResolver;
import ai.moeru.airicraft.agent.tasks.CraftingGridKind;
import ai.moeru.airicraft.agent.tasks.EntitySelectorResolver;
import ai.moeru.airicraft.agent.tasks.InventoryItemCounter;
import ai.moeru.airicraft.agent.tasks.NearbyEntityService;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

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

	private final Supplier<MinecraftClient> clientSupplier;
	private final InventoryItemCounter itemCounter = new InventoryItemCounter();

	public CurrentInventoryService(Supplier<MinecraftClient> clientSupplier) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
	}

	@Override
	public CompletableFuture<String> inspectInventory(String prompt) {
		MinecraftClient client = clientSupplier.get();
		if (client == null || client.world == null || client.player == null) {
			return CompletableFuture.completedFuture("INVENTORY_UNAVAILABLE: world_not_loaded");
		}

		List<ItemStack> stacks = new ArrayList<>();
		for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
			stacks.add(client.player.getInventory().getStack(slot));
		}

		String dimension = client.world.getRegistryKey().getValue().toString();
		String position = client.player.getBlockPos().getX() + "," + client.player.getBlockPos().getY() + "," + client.player.getBlockPos().getZ();
		String equippedItemId = Registries.ITEM.getId(client.player.getMainHandStack().getItem()).toString();
		int selectedHotbarSlot = client.player.getInventory().getSelectedSlot();
		List<String> durability = new ArrayList<>();
		int freeStorageSlots = 0;
		for (int slot = 0; slot < stacks.size(); slot++) {
			ItemStack stack = stacks.get(slot);
			if (slot < PlayerInventory.MAIN_SIZE && stack.isEmpty()) freeStorageSlots++;
			if (!stack.isEmpty() && stack.isDamageable()) {
				durability.add(PlannerStateText.durability(slot, Registries.ITEM.getId(stack.getItem()).toString(),
					stack.getMaxDamage() - stack.getDamage(), stack.getMaxDamage()));
			}
		}
		Map<String, String> equipment = new LinkedHashMap<>();
		for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND))
			equipment.put(slot.getName(), Registries.ITEM.getId(client.player.getEquippedStack(slot).getItem()).toString());
		return CompletableFuture.completedFuture(
			"At " + position + " in " + PlannerStateText.item(dimension) + ".\n"
				+ PlannerStateText.inventory(itemCounter.count(client.player.getInventory())) + " " + freeStorageSlots + " free storage slots.\n"
				+ (freeStorageSlots == 0 ? "No empty storage slots: only compatible non-full stacks can accept pickups. Free space before collecting other items.\n" : "")
				+ PlannerStateText.hotbar(hotbarItems(client.player.getInventory()), selectedHotbarSlot) + "\n"
				+ PlannerStateText.equipment(equippedItemId, equipment) + "\n"
				+ (durability.isEmpty() ? "" : "Durability: " + String.join("; ", durability) + ".\n")
				+ PlannerStateText.vitals(Map.of("health", client.player.getHealth(), "maxHealth", client.player.getMaxHealth(),
					"food", client.player.getHungerManager().getFoodLevel(), "saturation", client.player.getHungerManager().getSaturationLevel(),
					"air", client.player.getAir(), "maxAir", client.player.getMaxAir()))
		);
	}

	@Override
	public CompletableFuture<String> checkCraftables(com.google.gson.JsonObject arguments) {
		MinecraftClient client = clientSupplier.get();
		if (client == null || client.world == null || client.player == null) {
			return CompletableFuture.completedFuture("CRAFTABLES_UNAVAILABLE: world_not_loaded");
		}

		List<CraftingOpportunity> opportunities = CraftingOpportunityResolver.availableCrafts(client.player);
		Map<String, Integer> itemCounts = itemCounter.count(client.player.getInventory());
		CraftingTableAccess tableAccess = craftingTableAccess(client, itemCounts);
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
		MinecraftClient client = clientSupplier.get();
		if (client == null || client.world == null || client.player == null) {
			return CompletableFuture.completedFuture("NEARBY_ENTITIES_UNAVAILABLE: world_not_loaded");
		}

		double radius = arguments.has("radius") ? arguments.get("radius").getAsDouble() : EntitySelectorResolver.DEFAULT_NEARBY_RADIUS_BLOCKS;
		int maxResults = arguments.has("maxResults") ? arguments.get("maxResults").getAsInt() : NearbyEntityService.DEFAULT_MAX_RESULTS;
		Set<String> entityTypeIds = arguments.has("entityTypeIds")
			? arguments.getAsJsonArray("entityTypeIds").asList().stream().map(com.google.gson.JsonElement::getAsString).collect(Collectors.toSet())
			: Set.of();
		List<NearbyEntityService.NearbyEntitySnapshot> nearbyEntities = NearbyEntityService.listNearbyEntities(client, radius, maxResults, entityTypeIds);
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

	private static List<PlannerStateText.HotbarSlot> hotbarItems(PlayerInventory inventory) {
		var items = new ArrayList<PlannerStateText.HotbarSlot>();
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = inventory.getStack(slot);
			items.add(new PlannerStateText.HotbarSlot(slot, Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount()));
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

	private static CraftingTableAccess craftingTableAccess(MinecraftClient client, Map<String, Integer> itemCounts) {
		return craftingTableAccess(
			client.player.currentScreenHandler instanceof CraftingScreenHandler,
			hasUsableNearbyCraftingTable(client, client.player),
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

	private static boolean hasUsableNearbyCraftingTable(MinecraftClient client, ClientPlayerEntity player) {
		if (client.world == null || player == null) {
			return false;
		}
		BlockPos origin = player.getBlockPos();
		for (int dx = -TABLE_SEARCH_RADIUS; dx <= TABLE_SEARCH_RADIUS; dx++) {
			for (int dy = -TABLE_SEARCH_VERTICAL_RADIUS; dy <= TABLE_SEARCH_VERTICAL_RADIUS; dy++) {
				for (int dz = -TABLE_SEARCH_RADIUS; dz <= TABLE_SEARCH_RADIUS; dz++) {
					BlockPos pos = origin.add(dx, dy, dz);
					if (origin.getSquaredDistance(pos) > TABLE_SEARCH_RADIUS * TABLE_SEARCH_RADIUS || !client.world.isChunkLoaded(pos)) {
						continue;
					}
					if (client.world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)
						&& (withinInteractionRange(player, pos) || hasStandableAdjacentPosition(client, pos))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean hasStandableAdjacentPosition(MinecraftClient client, BlockPos tablePos) {
		for (Direction direction : Direction.Type.HORIZONTAL) {
			if (isStandable(client, tablePos.offset(direction))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isStandable(MinecraftClient client, BlockPos pos) {
		if (client.world == null || !client.world.isChunkLoaded(pos) || !client.world.isChunkLoaded(pos.up())) {
			return false;
		}
		BlockState feet = client.world.getBlockState(pos);
		BlockState head = client.world.getBlockState(pos.up());
		BlockState floor = client.world.getBlockState(pos.down());
		return (feet.isAir() || feet.isReplaceable())
			&& (head.isAir() || head.isReplaceable())
			&& floor.isSideSolidFullSquare(client.world, pos.down(), Direction.UP);
	}

	private static boolean withinInteractionRange(ClientPlayerEntity player, BlockPos pos) {
		return player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= TABLE_INTERACTION_RANGE_SQUARED;
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
