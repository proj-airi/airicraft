package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldFeatureSearchToolProviderTest {
	@Test
	void exposesFindWorldFeaturesTool() {
		WorldFeatureSearchToolProvider provider = new WorldFeatureSearchToolProvider(stubTool(List.of()));
		PlannerToolRegistry registry = PlannerToolRegistry.of(provider);

		List<String> toolNames = registry.openAiTools().stream().map(WorldFeatureSearchToolProviderTest::toolName).toList();

		assertTrue(toolNames.contains("find_world_features"));
		assertTrue(registry.isReadTool("find_world_features"));
	}

	@Test
	void executesAndObservesReturnedPositions() {
		BlockPos target = new BlockPos(12, 63, 4);
		AtomicReference<List<BlockPos>> observed = new AtomicReference<>();
		WorldFeatureSearchToolProvider provider = new WorldFeatureSearchToolProvider(stubTool(List.of(target)), result -> observed.set(result.observedPositions()));
		JsonObject args = new JsonObject();
		args.addProperty("featureKind", "water_body");

		String text = provider.execute(new PlannerToolCall("call-feature", "find_world_features", args, null, null)).join();

		assertEquals("Tool result for find_world_features: ok", text);
		assertEquals(List.of(target), observed.get());
	}

	@Test
	void validatesFeatureKindDirectionAndIntegerFields() {
		WorldFeatureSearchToolProvider provider = new WorldFeatureSearchToolProvider(stubTool(List.of()));
		JsonObject valid = new JsonObject();
		valid.addProperty("featureKind", "forest");
		valid.addProperty("direction", "northwest");
		valid.addProperty("limit", 2);
		provider.validateArguments("find_world_features", valid);

		JsonObject missing = new JsonObject();
		assertThrows(JsonParseException.class, () -> provider.validateArguments("find_world_features", missing));
		JsonObject badDirection = new JsonObject();
		badDirection.addProperty("featureKind", "water_body");
		badDirection.addProperty("direction", "up");
		assertThrows(JsonParseException.class, () -> provider.validateArguments("find_world_features", badDirection));
	}

	@Test
	void plannerRegistryParsesProviderToolCall() {
		PlannerToolRegistry registry = PlannerToolRegistry.of(new WorldFeatureSearchToolProvider(stubTool(List.of())));
		JsonObject rawToolCall = JsonParser.parseString("""
			{
			  "id": "call-feature",
			  "type": "function",
			  "function": {
			    "name": "find_world_features",
			    "arguments": "{\\"featureKind\\":\\"water_body\\",\\"direction\\":\\"east\\",\\"maxDistanceBlocks\\":128}"
			  }
			}
			""").getAsJsonObject();

		PlannerToolCall call = PlannerToolCatalog.parseToolCall(rawToolCall, registry);

		assertEquals("find_world_features", call.name());
		assertEquals("water_body", call.arguments().get("featureKind").getAsString());
		assertEquals("east", call.arguments().get("direction").getAsString());
		assertEquals(128, call.arguments().get("maxDistanceBlocks").getAsInt());
	}

	private static WorldFeatureSearchTool stubTool(List<BlockPos> observedPositions) {
		return args -> CompletableFuture.completedFuture(new WorldFeatureSearchService.WorldFeatureSearchResult(
			"Tool result for find_world_features: ok",
			observedPositions
		));
	}

	private static String toolName(Map<String, Object> tool) {
		@SuppressWarnings("unchecked")
		Map<String, Object> function = (Map<String, Object>) tool.get("function");
		return (String) function.get("name");
	}
}
