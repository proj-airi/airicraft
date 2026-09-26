package ai.moeru.airicraft.agent.integration.rei;

import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import ai.moeru.airicraft.agent.llm.PlannerToolProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class ReiRecipeSearchToolProvider implements PlannerToolProvider {
	public static final String TOOL_NAME = "search_recipes";
	private static final int DEFAULT_MAX_RESULTS = 12;

	private final RecipeSearchBackend backend;

	public ReiRecipeSearchToolProvider() {
		this(new RecipeSearchBackend() {
			@Override
			public boolean available() {
				return ReiRecipeSearchBridge.backend().available();
			}

			@Override
			public CompletableFuture<String> search(RecipeSearchRequest request) {
				return ReiRecipeSearchBridge.backend().search(request);
			}
		});
	}

	ReiRecipeSearchToolProvider(RecipeSearchBackend backend) {
		this.backend = Objects.requireNonNull(backend, "backend");
	}

	@Override
	public String id() {
		return "rei_recipe_search";
	}

	@Override
	public boolean available() {
		return backend.available();
	}

	@Override
	public List<Map<String, Object>> openAiTools() {
		if (!available()) {
			return List.of();
		}
		return List.of(PlannerToolCatalog.toolForProvider(
			TOOL_NAME,
			"Search REI recipe-viewer recipes and item uses by item.",
			PlannerToolCatalog.propertiesForProvider(
				PlannerToolCatalog.propForProvider("query", PlannerToolCatalog.stringForProvider("Item id or item name to search for.")),
				PlannerToolCatalog.propForProvider("mode", PlannerToolCatalog.enumStringForProvider("Search direction.", List.of("all", "output", "input"))),
				PlannerToolCatalog.propForProvider("maxResults", Map.of(
					"type", "integer",
					"description", "Optional result cap from 1 to 48."
				))
			),
			List.of("query")
		));
	}

	@Override
	public String promptInstructions() {
		if (!available()) {
			return "";
		}
		return """
			If REI recipe-viewer knowledge is needed, call search_recipes.
			search_recipes.query accepts item ids or item names.
			search_recipes.mode="output" finds recipes that make an item; mode="input" finds uses; mode="all" searches both.
			search_recipes is read-only recipe knowledge. Do not pass its recipe ids to craft_recipe unless check_craftables also lists them as currently craftable.
			""";
	}

	@Override
	public boolean handles(String toolName) {
		return TOOL_NAME.equals(PlannerToolCatalog.normalizeName(toolName));
	}

	@Override
	public void validateArguments(String toolName, JsonObject arguments) {
		if (!handles(toolName)) {
			throw new JsonParseException("Unsupported REI tool: " + toolName);
		}
		String query = stringArg(arguments, "query");
		if (query == null || query.isBlank()) {
			throw new JsonParseException("query must be a non-empty string");
		}
		try {
			RecipeSearchMode.parse(stringArg(arguments, "mode"));
		}
		catch (IllegalArgumentException exception) {
			throw new JsonParseException(exception.getMessage(), exception);
		}
		if (arguments != null && arguments.has("maxResults")) {
			JsonElement maxResultsElem = arguments.get("maxResults");
			if (!maxResultsElem.isJsonPrimitive()) {
				throw new JsonParseException("maxResults must be an integer");
			}
			int maxResults;
			try {
				maxResults = maxResultsElem.getAsInt();
			}
			catch (RuntimeException e) {
				throw new JsonParseException("maxResults must be an integer", e);
			}
			if (maxResults <= 0 || maxResults > 48) {
				throw new JsonParseException("maxResults must be between 1 and 48");
			}
		}
	}

	@Override
	public CompletableFuture<String> execute(PlannerToolCall toolCall) {
		return backend.search(toRequest(toolCall == null ? null : toolCall.arguments()));
	}

	private static RecipeSearchRequest toRequest(JsonObject arguments) {
		String query = stringArg(arguments, "query");
		RecipeSearchMode mode = RecipeSearchMode.parse(stringArg(arguments, "mode"));
		int maxResults = intArg(arguments, "maxResults", DEFAULT_MAX_RESULTS);
		return new RecipeSearchRequest(query, mode, maxResults);
	}

	private static String stringArg(JsonObject arguments, String key) {
		if (arguments == null || !arguments.has(key) || !arguments.get(key).isJsonPrimitive()) {
			return null;
		}
		return arguments.get(key).getAsString();
	}

	private static int intArg(JsonObject arguments, String key, int fallback) {
		if (arguments == null || !arguments.has(key) || !arguments.get(key).isJsonPrimitive()) {
			return fallback;
		}
		return arguments.get(key).getAsInt();
	}
}
