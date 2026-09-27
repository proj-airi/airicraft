package ai.moeru.airicraft.agent.integration.rei;

import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReiRecipeSearchToolProviderTest {
	@Test
	void exposesSearchRecipesOnlyWhenBackendAvailable() {
		ReiRecipeSearchToolProvider unavailable = new ReiRecipeSearchToolProvider(new FakeBackend(false, "unused"));
		ReiRecipeSearchToolProvider available = new ReiRecipeSearchToolProvider(new FakeBackend(true, "unused"));

		assertTrue(unavailable.openAiTools().isEmpty());
		assertEquals("search_recipes", toolName(available.openAiTools().getFirst()));
		assertTrue(available.promptInstructions().contains("search_recipes"));
	}

	@Test
	void validatesSearchRecipeArguments() {
		ReiRecipeSearchToolProvider provider = new ReiRecipeSearchToolProvider(new FakeBackend(true, "unused"));

		JsonObject missingQuery = new JsonObject();
		assertThrows(JsonParseException.class, () -> provider.validateArguments("search_recipes", missingQuery));

		JsonObject unsupportedMode = new JsonObject();
		unsupportedMode.addProperty("query", "oak");
		unsupportedMode.addProperty("mode", "sideways");
		assertThrows(JsonParseException.class, () -> provider.validateArguments("search_recipes", unsupportedMode));
	}

	@Test
	void describesSearchAsItemCentric() {
		ReiRecipeSearchToolProvider provider = new ReiRecipeSearchToolProvider(new FakeBackend(true, "unused"));

		String prompt = provider.promptInstructions();
		assertTrue(prompt.contains("item ids"));
		assertTrue(prompt.contains("item names"));
		assertFalse(prompt.contains("recipe names"));
		assertFalse(prompt.contains("category names"));
		String queryDescription = queryDescription(provider.openAiTools().getFirst());
		assertTrue(queryDescription.contains("Item id or item name"));
		assertFalse(queryDescription.contains("recipe"));
		assertFalse(queryDescription.contains("category"));
	}

	@Test
	void executesSearchWithDefaultModeAndLimit() {
		FakeBackend backend = new FakeBackend(true, "Tool result for search_recipes: ok");
		ReiRecipeSearchToolProvider provider = new ReiRecipeSearchToolProvider(backend);
		JsonObject args = new JsonObject();
		args.addProperty("query", "oak planks");

		String result = provider.execute(new PlannerToolCall("call_search", "search_recipes", args, null)).join();

		assertEquals("Tool result for search_recipes: ok", result);
		assertEquals(new RecipeSearchRequest("oak planks", RecipeSearchMode.ALL, 12), backend.lastRequest);
	}

	private static String toolName(Map<String, Object> tool) {
		@SuppressWarnings("unchecked")
		Map<String, Object> function = (Map<String, Object>) tool.get("function");
		return (String) function.get("name");
	}

	private static String queryDescription(Map<String, Object> tool) {
		@SuppressWarnings("unchecked")
		Map<String, Object> function = (Map<String, Object>) tool.get("function");
		@SuppressWarnings("unchecked")
		Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
		@SuppressWarnings("unchecked")
		Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
		@SuppressWarnings("unchecked")
		Map<String, Object> query = (Map<String, Object>) properties.get("query");
		return (String) query.get("description");
	}

	private static final class FakeBackend implements RecipeSearchBackend {
		private final boolean available;
		private final String result;
		private RecipeSearchRequest lastRequest;

		private FakeBackend(boolean available, String result) {
			this.available = available;
			this.result = result;
		}

		@Override
		public boolean available() {
			return available;
		}

		@Override
		public CompletableFuture<String> search(RecipeSearchRequest request) {
			this.lastRequest = request;
			return CompletableFuture.completedFuture(result);
		}
	}
}
