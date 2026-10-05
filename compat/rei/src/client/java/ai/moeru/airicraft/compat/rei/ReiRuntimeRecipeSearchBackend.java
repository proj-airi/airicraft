package ai.moeru.airicraft.compat.rei;

import ai.moeru.airicraft.agent.integration.rei.RecipeSearchBackend;
import ai.moeru.airicraft.agent.integration.rei.RecipeSearchMode;
import ai.moeru.airicraft.agent.integration.rei.RecipeSearchRequest;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class ReiRuntimeRecipeSearchBackend implements RecipeSearchBackend {
	private static final String TAG_CATEGORY = "minecraft:plugins/tag";
	private static final int MAX_MATCHED_ITEMS = 8;
	private static final int MAX_INGREDIENTS = 9;
	private static final int MAX_ALTERNATIVES = 4;
	private static final int MAX_WORKSTATIONS = 4;

	@Override
	public boolean available() {
		return true;
	}

	@Override
	public CompletableFuture<String> search(RecipeSearchRequest request) {
		try {
			return CompletableFuture.completedFuture(searchNow(request));
		}
		catch (RuntimeException exception) {
			return CompletableFuture.completedFuture("RECIPES_UNAVAILABLE: rei_search_failed " + exception.getClass().getSimpleName());
		}
	}

	private String searchNow(RecipeSearchRequest request) {
		RecipeSearchRequest safeRequest = request == null
			? new RecipeSearchRequest("", RecipeSearchMode.ALL, 12)
			: request;
		if (safeRequest.query().isBlank()) {
			return "RECIPES_UNAVAILABLE: missing_query";
		}

		List<EntryStack<?>> matchedItems = matchedItems(safeRequest.query()).stream()
			.limit(MAX_MATCHED_ITEMS)
			.toList();
		if (matchedItems.isEmpty()) {
			return "Tool result for search_recipes: provider=rei, query=\"" + sanitize(safeRequest.query())
				+ "\", mode=" + safeRequest.mode().toolValue()
				+ ", matchedItems=[], results=[], note=No REI item ingredients matched the query. Try a namespaced item id or a more exact item name.";
		}

		LinkedHashMap<String, RecipeSearchResult> results = new LinkedHashMap<>();
		for (EntryStack<?> matchedItem : matchedItems) {
			if (safeRequest.mode() == RecipeSearchMode.ALL || safeRequest.mode() == RecipeSearchMode.OUTPUT) {
				searchRole("output", matchedItem, safeRequest, results);
			}
			if (results.size() < safeRequest.maxResults()
				&& (safeRequest.mode() == RecipeSearchMode.ALL || safeRequest.mode() == RecipeSearchMode.INPUT)) {
				searchRole("input", matchedItem, safeRequest, results);
			}
			if (results.size() >= safeRequest.maxResults()) {
				break;
			}
		}

		return "Tool result for search_recipes: provider=rei"
			+ ", query=\"" + sanitize(safeRequest.query()) + "\""
			+ ", mode=" + safeRequest.mode().toolValue()
			+ ", matchedItems=" + matchedItems.stream().map(ReiRuntimeRecipeSearchBackend::entryId).toList()
			+ ", results=" + results.values().stream().map(RecipeSearchResult::compact).toList()
			+ ", note=REI results are recipe-viewer knowledge, not current craftability. Use check_craftables before craft_recipe.";
	}

	private static List<EntryStack<?>> matchedItems(String query) {
		String normalizedQuery = normalize(query);
		return EntryRegistry.getInstance().getEntryStacks()
			.filter(stack -> entryMatches(stack, normalizedQuery))
			.sorted(Comparator.comparing(ReiRuntimeRecipeSearchBackend::entryId))
			.toList();
	}

	private static boolean entryMatches(EntryStack<?> stack, String normalizedQuery) {
		return normalize(entryId(stack)).contains(normalizedQuery)
			|| normalize(entryName(stack)).contains(normalizedQuery);
	}

	private static void searchRole(
		String role,
		EntryStack<?> matchedItem,
		RecipeSearchRequest request,
		Map<String, RecipeSearchResult> results
	) {
		if (results.size() >= request.maxResults()) {
			return;
		}
		DisplayRegistry displayRegistry = DisplayRegistry.getInstance();
		for (Display display : displaysFor(role, matchedItem)) {
			if (results.size() >= request.maxResults()) {
				return;
			}
			if (displayRegistry.isDisplayVisible(display)) {
				addMatch(role, display.getCategoryIdentifier(), display, matchedItem, results);
			}
		}
	}

	private static Iterable<Display> displaysFor(String role, EntryStack<?> matchedItem) {
		return cachedDisplaysFor(role, matchedItem).orElseGet(() -> viewSearchDisplaysFor(role, matchedItem));
	}

	private static Optional<Iterable<Display>> cachedDisplaysFor(String role, EntryStack<?> matchedItem) {
		try {
			Object registry = DisplayRegistry.getInstance();
			Method cacheMethod = registry.getClass().getMethod("cache");
			cacheMethod.setAccessible(true);
			Object cache = cacheMethod.invoke(registry);
			String methodName = switch (role) {
				case "output" -> "getAllDisplaysByOutputs";
				case "input" -> "getAllDisplaysByInputs";
				default -> throw new IllegalArgumentException("unsupported REI display role: " + role);
			};
			Method lookupMethod = cache.getClass().getMethod(methodName, List.class);
			lookupMethod.setAccessible(true);
			Object displays = lookupMethod.invoke(cache, List.of(matchedItem));
			if (!(displays instanceof Iterable<?> iterable)) {
				return Optional.empty();
			}
			@SuppressWarnings("unchecked")
			Iterable<Display> typedDisplays = (Iterable<Display>) iterable;
			return Optional.of(typedDisplays);
		}
		catch (NoSuchMethodException | IllegalAccessException | ClassCastException | SecurityException exception) {
			return Optional.empty();
		}
		catch (InvocationTargetException exception) {
			throw new IllegalStateException("rei_cache_lookup_failed", exception.getCause());
		}
	}

	private static Iterable<Display> viewSearchDisplaysFor(String role, EntryStack<?> matchedItem) {
		ViewSearchBuilder builder = ViewSearchBuilder.builder().mergingDisplays(false);
		switch (role) {
			case "output" -> builder.addRecipesFor(matchedItem);
			case "input" -> builder.addUsagesFor(matchedItem);
			default -> throw new IllegalArgumentException("unsupported REI display role: " + role);
		}
		return builder.streamDisplays()
			.map(displaySpec -> displaySpec.provideInternalDisplay())
			.toList();
	}

	private static void addMatch(
		String role,
		CategoryIdentifier<?> categoryId,
		Display display,
		EntryStack<?> matchedItem,
		Map<String, RecipeSearchResult> results
	) {
		if (TAG_CATEGORY.equals(categoryId.getIdentifier().toString())) {
			// REI lists item tags as pseudo-recipes; they are membership data, not ways to make an item.
			return;
		}
		boolean matches = switch (role) {
			case "output" -> entriesMatch(display.getOutputEntries(), matchedItem);
			case "input" -> entriesMatch(display.getInputEntries(), matchedItem);
			default -> false;
		};
		if (!matches) {
			return;
		}
		String recipeId = display.getDisplayLocation().map(ResourceLocation::toString).orElse("unknown");
		String key = role + "|" + categoryId.getIdentifier() + "|" + recipeId + "|" + entryId(matchedItem);
		results.putIfAbsent(key, new RecipeSearchResult(
			role,
			entryId(matchedItem),
			categoryId.getIdentifier().toString(),
			categoryTitle(categoryId),
			recipeId,
			ingredientSummaries(display.getInputEntries()),
			ingredientSummaries(display.getOutputEntries()),
			workstations(categoryId)
		));
	}

	private static boolean entriesMatch(List<EntryIngredient> entries, EntryStack<?> matchedItem) {
		for (EntryIngredient ingredient : entries) {
			for (EntryStack<?> candidate : ingredient) {
				if (EntryStacks.equalsFuzzy(candidate, matchedItem)) {
					return true;
				}
			}
		}
		return false;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static String categoryTitle(CategoryIdentifier<?> categoryId) {
		Optional<CategoryRegistry.CategoryConfiguration<Display>> category = CategoryRegistry.getInstance().tryGet((CategoryIdentifier) categoryId);
		return category
			.map(CategoryRegistry.CategoryConfiguration::getCategory)
			.map(DisplayCategory::getTitle)
			.map(ReiRuntimeRecipeSearchBackend::title)
			.orElse(categoryId.getIdentifier().toString());
	}

	/** One string per ingredient: alternatives joined by "|", with an item count when it is not one. */
	private static List<String> ingredientSummaries(List<EntryIngredient> ingredients) {
		return ingredients.stream()
			.filter(ingredient -> !ingredient.isEmpty())
			.limit(MAX_INGREDIENTS)
			.map(ingredient -> ingredient.stream()
				.limit(MAX_ALTERNATIVES)
				.map(ReiRuntimeRecipeSearchBackend::countedEntry)
				.distinct()
				.collect(java.util.stream.Collectors.joining("|"))
				+ (ingredient.size() > MAX_ALTERNATIVES ? "|..." : ""))
			.toList();
	}

	private static String countedEntry(EntryStack<?> stack) {
		String id = entryId(stack);
		if (stack.getType() == VanillaEntryTypes.ITEM) {
			int count = stack.<ItemStack>castValue().getCount();
			if (count > 1) {
				return id + " x" + count;
			}
		}
		return id;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static List<String> workstations(CategoryIdentifier<?> categoryId) {
		Optional<CategoryRegistry.CategoryConfiguration<Display>> category =
			CategoryRegistry.getInstance().tryGet((CategoryIdentifier) categoryId);
		java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
		if (category.isPresent()) {
			for (EntryIngredient ingredient : category.get().getWorkstations()) {
				if (!ingredient.isEmpty() && ids.size() < MAX_WORKSTATIONS) {
					ids.add(entryId(ingredient.get(0)));
				}
			}
		}
		return List.copyOf(ids);
	}

	private static String entryId(EntryStack<?> stack) {
		return Optional.ofNullable(stack.getIdentifier())
			.map(ResourceLocation::toString)
			.orElse("unknown");
	}

	private static String entryName(EntryStack<?> stack) {
		return sanitize(stack.asFormatStrippedText().getString());
	}

	private static String normalize(String text) {
		return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	private static String sanitize(String text) {
		return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').strip();
	}

	private static String title(Component component) {
		return sanitize(component == null ? "" : component.getString());
	}

	private record RecipeSearchResult(
		String role,
		String matchedItem,
		String recipeType,
		String category,
		String recipeId,
		List<String> inputs,
		List<String> outputs,
		List<String> workstations
	) {
		private String compact() {
			return "{role=" + role
				+ ", matchedItem=" + matchedItem
				+ ", recipeType=" + recipeType
				+ ", category=\"" + category + "\""
				+ ", recipeId=" + recipeId
				+ ", inputs=" + inputs
				+ ", outputs=" + outputs
				+ ", workstations=" + workstations
				+ "}";
		}
	}
}
