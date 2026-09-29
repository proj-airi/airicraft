package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class CraftingOpportunityResolver {
	private static final int PLAYER_GRID_INPUT_COUNT = InventoryMenu.CRAFT_SLOT_COUNT;
	private static final int WORKBENCH_GRID_INPUT_COUNT = 9;
	static final int MAX_PLACEMENT_VARIANTS_PER_RECIPE = 24;
	private static final Object RECIPE_CATALOG_LOCK = new Object();
	private static ServerRecipeDisplayCatalog.Snapshot cachedDisplayCatalog;
	private static RecipeCatalog cachedRecipeCatalog = RecipeCatalog.empty();

	private CraftingOpportunityResolver() {
	}

	public static List<CraftingOpportunity> availableCrafts(LocalPlayer player) {
		if (player == null) {
			return List.of();
		}
		return opportunities(resolvedOptions(player));
	}

	public static List<CraftingOpportunity> knownCrafts(LocalPlayer player) {
		if (player == null) {
			return List.of();
		}
		RecipeCatalog catalog = integratedServerRecipeCatalog();
		return mergeOpportunities(
			knownOptions(player.getRecipeBook().getCollections()),
			catalog.knownCrafts()
		);
	}

	public static CraftingOpportunitySnapshot inspect(LocalPlayer player) {
		if (player == null) {
			return CraftingOpportunitySnapshot.empty();
		}
		StackedItemContents stackedContents = recipeFinder(player);
		Map<Item, Integer> availableItems = inventoryCounts(player);
		RecipeCatalog catalog = integratedServerRecipeCatalog();
		return inspect(
			player.getRecipeBook().getCollections(),
			catalog.collections(),
			catalog.knownCrafts(),
			stackedContents,
			availableItems
		);
	}

	static CraftingOpportunitySnapshot inspect(
		List<RecipeCollection> recipeBookCollections,
		List<RecipeCollection> catalogCollections,
		List<CraftingOpportunity> catalogKnownCrafts,
		StackedItemContents stackedContents,
		Map<Item, Integer> availableItems
	) {
		return snapshot(
			opportunities(resolvedOptions(recipeBookCollections, stackedContents, availableItems)),
			opportunities(resolvedOptions(catalogCollections, stackedContents, availableItems, true)),
			knownOptions(recipeBookCollections),
			catalogKnownCrafts
		);
	}

	static CraftingOpportunitySnapshot snapshot(
		List<CraftingOpportunity> recipeBookAvailable,
		List<CraftingOpportunity> catalogAvailable,
		List<CraftingOpportunity> recipeBookKnown,
		List<CraftingOpportunity> catalogKnown
	) {
		return new CraftingOpportunitySnapshot(
			mergeOpportunities(recipeBookAvailable, catalogAvailable),
			mergeOpportunities(recipeBookKnown, catalogKnown)
		);
	}

	private static List<CraftingOpportunity> opportunities(List<ResolvedCraftingOption> options) {
		if (options == null || options.isEmpty()) {
			return List.of();
		}
		return options.stream().map(ResolvedCraftingOption::opportunity).toList();
	}

	@SafeVarargs
	private static List<CraftingOpportunity> mergeOpportunities(List<CraftingOpportunity>... sources) {
		Map<String, CraftingOpportunity> merged = new LinkedHashMap<>();
		for (List<CraftingOpportunity> source : sources) {
			if (source == null) {
				continue;
			}
			for (CraftingOpportunity opportunity : source) {
				if (opportunity != null) {
					merged.putIfAbsent(opportunity.recipeId(), opportunity);
				}
			}
		}
		return List.copyOf(merged.values());
	}

	static List<CraftingOpportunity> availableCrafts(List<RecipeCollection> collections, StackedItemContents stackedContents) {
		return availableCrafts(collections, stackedContents, Map.of());
	}

	static List<CraftingOpportunity> availableCrafts(List<RecipeCollection> collections, StackedItemContents stackedContents, Map<Item, Integer> availableItems) {
		if (collections == null || stackedContents == null) {
			return List.of();
		}
		Map<String, CraftingOpportunity> opportunities = new LinkedHashMap<>();
		for (ResolvedCraftingOption option : resolvedOptions(collections, stackedContents, availableItems)) {
			opportunities.putIfAbsent(option.opportunity().recipeId(), option.opportunity());
		}
		return List.copyOf(opportunities.values());
	}

	static List<CraftingOpportunity> knownCrafts(List<RecipeCollection> collections) {
		if (collections == null) {
			return List.of();
		}
		Map<String, CraftingOpportunity> opportunities = new LinkedHashMap<>();
		for (CraftingOpportunity opportunity : knownOptions(collections)) {
			opportunities.putIfAbsent(opportunity.recipeId(), opportunity);
		}
		return List.copyOf(opportunities.values());
	}

	static CraftingRecipeResolution resolve(LocalPlayer player, CraftRecipeStepArgs request) {
		if (player == null || request == null) {
			return CraftingRecipeResolution.failure("recipe_not_found");
		}
		return resolve(resolvedOptions(player), request.recipeId(), request.times());
	}

	static CraftingRecipeResolution resolve(List<RecipeCollection> collections, StackedItemContents stackedContents, String recipeId, int times) {
		return resolve(collections, stackedContents, Map.of(), recipeId, times);
	}

	static CraftingRecipeResolution resolve(List<RecipeCollection> collections, StackedItemContents stackedContents, Map<Item, Integer> availableItems, String recipeId, int times) {
		return resolve(resolvedOptions(collections, stackedContents, availableItems), recipeId, times);
	}

	private static CraftingRecipeResolution resolve(List<ResolvedCraftingOption> options, String recipeId, int times) {
		String requestedRecipeId = normalizeRequestedRecipeId(recipeId);
		if (requestedRecipeId.isBlank() || times <= 0) {
			return CraftingRecipeResolution.failure("recipe_not_found");
		}
		for (ResolvedCraftingOption option : options) {
			if (option.opportunity().recipeId().equals(requestedRecipeId)) {
				return CraftingRecipeResolution.success(
					option.networkRecipeId(),
					option.outputItem(),
					option.opportunity().outputCount(),
					times,
					option.gridKind(),
					option.placements()
				);
			}
		}

		return CraftingRecipeResolution.failure("recipe_not_found");
	}

	static CraftingRecipeResolution resolveCraftingTable(LocalPlayer player) {
		if (player == null) {
			return CraftingRecipeResolution.failure("crafting_table_missing_materials");
		}
		return resolveCraftingTable(player.getRecipeBook().getCollections(), recipeFinder(player), inventoryCounts(player));
	}

	static CraftingRecipeResolution resolveCraftingTable(List<RecipeCollection> collections, StackedItemContents stackedContents, Map<Item, Integer> availableItems) {
		for (ResolvedCraftingOption option : resolvedOptions(collections, stackedContents, availableItems)) {
			if (option.gridKind() == CraftingGridKind.PLAYER_2X2 && option.outputItem() == Items.CRAFTING_TABLE) {
				return CraftingRecipeResolution.success(
					option.networkRecipeId(),
					option.outputItem(),
					option.opportunity().outputCount(),
					1,
					option.gridKind(),
					option.placements()
				);
			}
		}
		return CraftingRecipeResolution.failure("crafting_table_missing_materials");
	}

	static boolean fitsPlayerGrid(RecipeDisplay display) {
		return gridKind(display) == CraftingGridKind.PLAYER_2X2;
	}

	static CraftingGridKind gridKind(RecipeDisplay display) {
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			return gridKindForShapedRecipe(shaped.width(), shaped.height());
		}
		if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			return gridKindForIngredientCount(shapeless.ingredients().size());
		}
		return null;
	}

	static CraftingGridKind gridKindForShapedRecipe(int width, int height) {
		if (width <= 2 && height <= 2) {
			return CraftingGridKind.PLAYER_2X2;
		}
		if (width <= 3 && height <= 3) {
			return CraftingGridKind.WORKBENCH_3X3;
		}
		return null;
	}

	static CraftingGridKind gridKindForIngredientCount(int size) {
		if (size <= PLAYER_GRID_INPUT_COUNT) {
			return CraftingGridKind.PLAYER_2X2;
		}
		if (size <= WORKBENCH_GRID_INPUT_COUNT) {
			return CraftingGridKind.WORKBENCH_3X3;
		}
		return null;
	}

	static ItemStack resultStack(RecipeDisplay display) {
		SlotDisplay result = display.result();
		if (result instanceof SlotDisplay.ItemStackSlotDisplay stackDisplay) {
			return stackDisplay.stack();
		}
		if (result instanceof SlotDisplay.ItemSlotDisplay itemDisplay) {
			return itemDisplay.item().value().getDefaultInstance();
		}
		return ItemStack.EMPTY;
	}

	private static String normalizeRequestedRecipeId(String recipeId) {
		if (recipeId == null) {
			return "";
		}
		return recipeId.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
	}

	private static StackedItemContents recipeFinder(LocalPlayer player) {
		StackedItemContents stackedContents = new StackedItemContents();
		player.getInventory().fillStackedContents(stackedContents);
		return stackedContents;
	}

	private static Map<Item, Integer> inventoryCounts(LocalPlayer player) {
		Map<Item, Integer> counts = new HashMap<>();
		for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
			ItemStack stack = player.getInventory().getItem(index);
			if (!stack.isEmpty()) {
				counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		return counts;
	}

	private static List<ResolvedCraftingOption> resolvedOptions(LocalPlayer player) {
		StackedItemContents stackedContents = recipeFinder(player);
		Map<Item, Integer> availableItems = inventoryCounts(player);
		RecipeCatalog catalog = integratedServerRecipeCatalog();
		List<ResolvedCraftingOption> options = new ArrayList<>(resolvedOptions(player.getRecipeBook().getCollections(), stackedContents, availableItems));
		options.addAll(resolvedOptions(catalog.collections(), stackedContents, availableItems, true));
		return List.copyOf(options);
	}

	private static RecipeCatalog integratedServerRecipeCatalog() {
		ServerRecipeDisplayCatalog.Snapshot displayCatalog = ServerRecipeDisplayCatalog.current();
		synchronized (RECIPE_CATALOG_LOCK) {
			if (displayCatalog == cachedDisplayCatalog) {
				return cachedRecipeCatalog;
			}
		}
		List<RecipeCollection> collections = displayCatalog.entries().isEmpty()
			? List.of()
			: List.of(new RecipeCollection(displayCatalog.entries()));
		RecipeCatalog catalog = new RecipeCatalog(collections, knownOptions(collections));
		synchronized (RECIPE_CATALOG_LOCK) {
			cachedDisplayCatalog = displayCatalog;
			cachedRecipeCatalog = catalog;
		}
		return catalog;
	}

	private static List<ResolvedCraftingOption> resolvedOptions(List<RecipeCollection> collections, StackedItemContents stackedContents, Map<Item, Integer> availableItems) {
		return resolvedOptions(collections, stackedContents, availableItems, false);
	}

	private static List<ResolvedCraftingOption> resolvedOptions(List<RecipeCollection> collections, StackedItemContents stackedContents, Map<Item, Integer> availableItems, boolean manualPlacementOnly) {
		if (collections == null || stackedContents == null) {
			return List.of();
		}
		Map<Item, Integer> safeAvailableItems = availableItems == null ? Map.of() : availableItems;
		List<ResolvedCraftingOption> options = new ArrayList<>();
		for (RecipeCollection collection : collections) {
			collection.selectRecipes(stackedContents, display -> gridKind(display) != null);
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				if (!collection.isCraftable(entry.id())) {
					continue;
				}
				ItemStack result = resultStack(entry.display());
				CraftingGridKind gridKind = gridKind(entry.display());
				if (result.isEmpty() || gridKind == null) {
					continue;
				}
				for (List<CraftingIngredientPlacement> placements : concretePlacements(ingredientPlacements(entry, gridKind), safeAvailableItems)) {
					if (placements.isEmpty()) {
						continue;
					}
					String outputItemId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
					List<String> inputItemIds = placements.stream()
						.map(CraftingIngredientPlacement::itemId)
						.toList();
					CraftingOpportunity opportunity = new CraftingOpportunity(
						recipeId(inputItemIds, outputItemId),
						outputItemId,
						result.getCount(),
						inputItemIds,
						gridKind
					);
					options.add(new ResolvedCraftingOption(manualPlacementOnly ? null : entry.id(), result.getItem(), gridKind, opportunity, placements));
				}
			}
		}
		return List.copyOf(options);
	}

	private static List<CraftingOpportunity> knownOptions(List<RecipeCollection> collections) {
		if (collections == null) {
			return List.of();
		}
		List<CraftingOpportunity> opportunities = new ArrayList<>();
		for (RecipeCollection collection : collections) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				ItemStack result = resultStack(entry.display());
				CraftingGridKind gridKind = gridKind(entry.display());
				if (result.isEmpty() || gridKind == null) {
					continue;
				}
				for (List<CraftingIngredientPlacement> placements : knowledgePlacements(ingredientPlacements(entry, gridKind))) {
					if (placements.isEmpty()) {
						continue;
					}
					String outputItemId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
					List<String> inputItemIds = placements.stream()
						.map(CraftingIngredientPlacement::itemId)
						.toList();
					opportunities.add(new CraftingOpportunity(
						recipeId(inputItemIds, outputItemId),
						outputItemId,
						result.getCount(),
						inputItemIds,
						gridKind
					));
				}
			}
		}
		return List.copyOf(opportunities);
	}

	private static List<IngredientPlacement> ingredientPlacements(RecipeDisplayEntry entry, CraftingGridKind gridKind) {
		if (entry.craftingRequirements().isEmpty()) {
			return List.of();
		}
		List<Ingredient> requirements = entry.craftingRequirements().get();
		if (entry.display() instanceof ShapedCraftingRecipeDisplay shaped) {
			return shapedIngredientPlacements(shaped, gridKind, requirements);
		}
		if (entry.display() instanceof ShapelessCraftingRecipeDisplay) {
			return shapelessIngredientPlacements(gridKind, requirements);
		}
		return List.of();
	}

	private static List<IngredientPlacement> shapedIngredientPlacements(ShapedCraftingRecipeDisplay shaped, CraftingGridKind gridKind, List<Ingredient> requirements) {
		List<IngredientPlacement> placements = new ArrayList<>();
		List<SlotDisplay> slots = shaped.ingredients();
		int requirementIndex = 0;
		int gridWidth = gridWidth(gridKind);
		for (int row = 0; row < shaped.height(); row++) {
			for (int column = 0; column < shaped.width(); column++) {
				int displayIndex = row * shaped.width() + column;
				if (displayIndex >= slots.size() || isEmptySlot(slots.get(displayIndex))) {
					continue;
				}
				if (requirementIndex >= requirements.size()) {
					return List.of();
				}
				Ingredient requirement = requirements.get(requirementIndex++);
				if (!requirement.isEmpty()) {
					placements.add(new IngredientPlacement(row * gridWidth + column, requirement));
				}
			}
		}
		return placements;
	}

	private static List<IngredientPlacement> shapelessIngredientPlacements(CraftingGridKind gridKind, List<Ingredient> requirements) {
		List<IngredientPlacement> placements = new ArrayList<>();
		for (Ingredient requirement : requirements) {
			if (!requirement.isEmpty()) {
				placements.add(new IngredientPlacement(placements.size(), requirement));
			}
		}
		return placements.size() <= gridInputCount(gridKind) ? List.copyOf(placements) : List.of();
	}

	private static boolean isEmptySlot(SlotDisplay display) {
		return display instanceof SlotDisplay.Empty;
	}

	private static List<List<CraftingIngredientPlacement>> concretePlacements(List<IngredientPlacement> placements, Map<Item, Integer> availableItems) {
		if (placements.isEmpty()) {
			return List.of();
		}
		Map<Item, Integer> safeAvailableItems = availableItems == null ? Map.of() : availableItems;
		List<List<Item>> choicesByPlacement = new ArrayList<>();
		for (IngredientPlacement placement : placements) {
			List<Item> matchingItems = placement.ingredient().items()
				.map(holder -> holder.value())
				.distinct()
				.filter(item -> safeAvailableItems.getOrDefault(item, 0) > 0)
				.sorted(Comparator.comparing(CraftingOpportunityResolver::itemId))
				.toList();
			if (matchingItems.isEmpty()) {
				return List.of();
			}
			choicesByPlacement.add(matchingItems);
		}
		List<List<Item>> itemVariants = boundedCombinations(choicesByPlacement, safeAvailableItems, MAX_PLACEMENT_VARIANTS_PER_RECIPE);
		List<List<CraftingIngredientPlacement>> variants = new ArrayList<>();
		for (List<Item> itemVariant : itemVariants) {
			List<CraftingIngredientPlacement> variant = new ArrayList<>();
			for (int index = 0; index < itemVariant.size(); index++) {
				Item item = itemVariant.get(index);
				variant.add(new CraftingIngredientPlacement(placements.get(index).gridIndex(), item, itemId(item)));
			}
			variants.add(List.copyOf(variant));
		}
		return List.copyOf(variants);
	}

	private static List<List<CraftingIngredientPlacement>> knowledgePlacements(List<IngredientPlacement> placements) {
		if (placements.isEmpty()) {
			return List.of();
		}
		List<List<Item>> choicesByPlacement = new ArrayList<>();
		Map<Item, Integer> availableItems = new HashMap<>();
		for (IngredientPlacement placement : placements) {
			List<Item> matchingItems = placement.ingredient().items()
				.map(holder -> holder.value())
				.distinct()
				.sorted(Comparator.comparing(CraftingOpportunityResolver::itemId))
				.toList();
			if (matchingItems.isEmpty()) {
				return List.of();
			}
			for (Item item : matchingItems) {
				availableItems.merge(item, placements.size(), Math::max);
			}
			choicesByPlacement.add(matchingItems);
		}
		List<List<Item>> itemVariants = boundedCombinations(choicesByPlacement, availableItems, MAX_PLACEMENT_VARIANTS_PER_RECIPE);
		List<List<CraftingIngredientPlacement>> variants = new ArrayList<>();
		for (List<Item> itemVariant : itemVariants) {
			List<CraftingIngredientPlacement> variant = new ArrayList<>();
			for (int index = 0; index < itemVariant.size(); index++) {
				Item item = itemVariant.get(index);
				variant.add(new CraftingIngredientPlacement(placements.get(index).gridIndex(), item, itemId(item)));
			}
			variants.add(List.copyOf(variant));
		}
		return List.copyOf(variants);
	}

	static <T> List<List<T>> boundedCombinations(List<List<T>> choices, Map<T, Integer> availableItems, int maxVariants) {
		if (choices == null || choices.isEmpty() || maxVariants <= 0) {
			return List.of();
		}
		LinkedHashSet<List<T>> variants = new LinkedHashSet<>();
		addRepresentativeCombinations(choices, availableItems == null ? Map.of() : availableItems, maxVariants, variants);
		backtrackBoundedCombinations(choices, availableItems == null ? Map.of() : availableItems, maxVariants, 0, new HashMap<>(), new ArrayList<>(), variants);
		return List.copyOf(variants);
	}

	private static <T> void addRepresentativeCombinations(
		List<List<T>> choices,
		Map<T, Integer> availableItems,
		int maxVariants,
		LinkedHashSet<List<T>> variants
	) {
		LinkedHashSet<T> representatives = new LinkedHashSet<>();
		for (List<T> positionChoices : choices) {
			representatives.addAll(positionChoices);
		}
		for (T representative : representatives) {
			if (variants.size() >= maxVariants) {
				return;
			}
			Map<T, Integer> usedItems = new HashMap<>();
			List<T> combination = new ArrayList<>();
			boolean valid = true;
			for (List<T> positionChoices : choices) {
				T selected = availableChoice(positionChoices, representative, availableItems, usedItems);
				if (selected == null) {
					valid = false;
					break;
				}
				combination.add(selected);
				usedItems.merge(selected, 1, Integer::sum);
			}
			if (valid) {
				variants.add(List.copyOf(combination));
			}
		}
	}

	private static <T> T availableChoice(
		List<T> choices,
		T preferred,
		Map<T, Integer> availableItems,
		Map<T, Integer> usedItems
	) {
		if (choices.contains(preferred) && hasRemaining(preferred, availableItems, usedItems)) {
			return preferred;
		}
		for (T choice : choices) {
			if (hasRemaining(choice, availableItems, usedItems)) {
				return choice;
			}
		}
		return null;
	}

	private static <T> boolean hasRemaining(T choice, Map<T, Integer> availableItems, Map<T, Integer> usedItems) {
		return availableItems.getOrDefault(choice, 0) > usedItems.getOrDefault(choice, 0);
	}

	private static <T> void backtrackBoundedCombinations(
		List<List<T>> choices,
		Map<T, Integer> availableItems,
		int maxVariants,
		int index,
		Map<T, Integer> usedItems,
		List<T> current,
		LinkedHashSet<List<T>> variants
	) {
		if (variants.size() >= maxVariants) {
			return;
		}
		if (index >= choices.size()) {
			variants.add(List.copyOf(current));
			return;
		}
		for (T choice : choices.get(index)) {
			if (variants.size() >= maxVariants) {
				break;
			}
			if (availableItems.getOrDefault(choice, 0) <= usedItems.getOrDefault(choice, 0)) {
				continue;
			}
			usedItems.merge(choice, 1, Integer::sum);
			current.add(choice);
			backtrackBoundedCombinations(choices, availableItems, maxVariants, index + 1, usedItems, current, variants);
			current.remove(current.size() - 1);
			int used = usedItems.getOrDefault(choice, 0) - 1;
			if (used <= 0) {
				usedItems.remove(choice);
			}
			else {
				usedItems.put(choice, used);
			}
		}
	}

	static String recipeId(List<String> inputItemIds, String outputItemId) {
		Map<String, Integer> counts = new TreeMap<>();
		for (String inputItemId : inputItemIds) {
			counts.merge(inputItemId, 1, Integer::sum);
		}
		String inputs = counts.entrySet().stream()
			.map(entry -> {
				String segment = CraftingOpportunity.recipeIdSegment(entry.getKey());
				return entry.getValue() == 1 ? segment : segment + "_x" + entry.getValue();
			})
			.filter(segment -> !segment.isBlank())
			.collect(java.util.stream.Collectors.joining("_and_"));
		String output = CraftingOpportunity.recipeIdSegment(outputItemId);
		return normalizeRequestedRecipeId(inputs + "_to_" + output);
	}

	private static String itemId(Item item) {
		ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
		return id == null ? "" : id.toString();
	}

	static int gridInputCount(CraftingGridKind gridKind) {
		return gridKind == CraftingGridKind.WORKBENCH_3X3 ? WORKBENCH_GRID_INPUT_COUNT : PLAYER_GRID_INPUT_COUNT;
	}

	private static int gridWidth(CraftingGridKind gridKind) {
		return gridKind == CraftingGridKind.WORKBENCH_3X3 ? 3 : 2;
	}

	static record CraftingRecipeResolution(
		RecipeDisplayId networkRecipeId,
		Item outputItem,
		int outputCount,
		int requestedTimes,
		CraftingGridKind gridKind,
		List<CraftingIngredientPlacement> placements,
		String failureReason
	) {
		private static CraftingRecipeResolution success(RecipeDisplayId recipeDisplayId, Item outputItem, int outputCount, int requestedTimes, CraftingGridKind gridKind, List<CraftingIngredientPlacement> placements) {
			return new CraftingRecipeResolution(recipeDisplayId, outputItem, outputCount, requestedTimes, gridKind, List.copyOf(placements), null);
		}

		private static CraftingRecipeResolution failure(String reason) {
			return new CraftingRecipeResolution(null, null, 0, 0, null, List.of(), reason);
		}
	}

	static record CraftingIngredientPlacement(
		int gridIndex,
		Item item,
		String itemId
	) {
		CraftingIngredientPlacement {
			Objects.requireNonNull(item, "item");
			if (gridIndex < 0 || gridIndex >= WORKBENCH_GRID_INPUT_COUNT) {
				throw new IllegalArgumentException("gridIndex outside crafting grid");
			}
			if (itemId == null || itemId.isBlank()) {
				throw new IllegalArgumentException("itemId must not be blank");
			}
		}
	}

	private record IngredientPlacement(int gridIndex, Ingredient ingredient) {
	}

	private record ResolvedCraftingOption(
		RecipeDisplayId networkRecipeId,
		Item outputItem,
		CraftingGridKind gridKind,
		CraftingOpportunity opportunity,
		List<CraftingIngredientPlacement> placements
	) {
	}

	private record RecipeCatalog(
		List<RecipeCollection> collections,
		List<CraftingOpportunity> knownCrafts
	) {
		private RecipeCatalog {
			collections = collections == null ? List.of() : List.copyOf(collections);
			knownCrafts = knownCrafts == null ? List.of() : List.copyOf(knownCrafts);
		}

		private static RecipeCatalog empty() {
			return new RecipeCatalog(List.of(), List.of());
		}
	}
}
