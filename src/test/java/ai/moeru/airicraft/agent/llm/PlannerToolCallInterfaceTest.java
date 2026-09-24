package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.AgentConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerToolCallInterfaceTest {
	@Test
	void normalPlannerRequestUsesToolsAndParsesPlaintext() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "role": "assistant",
			        "content": "I can help with that."
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 10,
			    "completion_tokens": 4,
			    "total_tokens": 14
			  }
			}
			""")) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port()));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent hello", LlmMessageKind.USER_TURN)
			)));

			assertEquals("I can help with that.", result.payload().replyText());
			assertNull(result.payload().toolCall());
			JsonObject body = JsonParser.parseString(bodyRef.get()).getAsJsonObject();
			assertFalse(body.has("response_format"));
			assertEquals("auto", body.get("tool_choice").getAsString());
			JsonArray tools = body.getAsJsonArray("tools");
			assertNotNull(tools);
			assertEquals(PlannerToolCatalog.openAiTools().size(), tools.size());
			assertEquals(PlannerToolCatalog.OBSERVE, tools.get(0).getAsJsonObject()
				.getAsJsonObject("function").get("name").getAsString());
		}
	}

	@Test
	void parsesSingleToolCallWithNarration() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "role": "assistant",
			        "content": null,
			        "tool_calls": [
			          {
			            "id": "call_inventory",
			            "type": "function",
			            "function": {
			              "name": "inspect_inventory",
			              "arguments": "{\\"narration\\":\\"I'm checking my inventory.\\",\\"prompt\\":\\"List current counts.\\"}"
			            }
			          }
			        ]
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 10,
			    "completion_tokens": 4,
			    "total_tokens": 14
			  }
			}
			""")) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port()));

			PlannerResponse response = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent inventory", LlmMessageKind.USER_TURN)
			))).payload();

			assertEquals("", response.replyText());
			assertEquals("inspect_inventory", response.toolCall().name());
			assertEquals("call_inventory", response.toolCall().id());
			assertEquals("I'm checking my inventory.", response.toolCall().narration());
			assertEquals("List current counts.", response.toolCall().arguments().get("prompt").getAsString());
		}
	}

	@Test
	void replayedRawToolCallUsesSynthesizedIdWhenProviderOmittedId() {
		JsonObject rawToolCall = JsonParser.parseString("""
			{
			  "type": "function",
			  "function": {
			    "name": "inspect_inventory",
			    "arguments": "{}"
			  }
			}
			""").getAsJsonObject();

		PlannerToolCall toolCall = PlannerToolCatalog.parseToolCall(rawToolCall);

		JsonObject replayed = PlannerToolCatalog.toOpenAiToolCalls(List.of(toolCall)).get(0).getAsJsonObject();
		assertEquals("call_planner_tool", toolCall.id());
		assertEquals(toolCall.id(), replayed.get("id").getAsString());
		assertEquals("inspect_inventory", replayed.getAsJsonObject("function").get("name").getAsString());
	}

	@Test
	void exposesAndParsesItemDropTools() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();

		assertTrue(toolNames(tools).contains("drop_items"));
		assertTrue(toolNames(tools).contains("give_player"));
		PlannerToolCall dropCall = PlannerToolCatalog.parseToolCall(toolCall("drop_items", """
			{"itemId":"minecraft:oak_log","quantity":2}
			"""));
		PlannerToolCall giveCall = PlannerToolCatalog.parseToolCall(toolCall("give_player", """
			{"targetPlayer":"Alice","itemId":"minecraft:oak_log","quantity":2}
			"""));

		assertEquals("drop_items", dropCall.name());
		assertEquals("minecraft:oak_log", dropCall.arguments().get("itemId").getAsString());
		assertEquals(2, dropCall.arguments().get("quantity").getAsInt());
		assertEquals("give_player", giveCall.name());
		assertEquals("Alice", giveCall.arguments().get("targetPlayer").getAsString());
	}

	@Test
	void exposesAndParsesEnsureBlocksInInventoryTool() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();
		JsonObject mineParameters = toolSchema(tools, "mine_blocks");

		assertTrue(toolNames(tools).contains("ensure_blocks_in_inventory"));
		assertTrue(mineParameters.getAsJsonObject("properties").has("allowUnilluminated"));
		PlannerToolCall ensureCall = PlannerToolCatalog.parseToolCall(toolCall("ensure_blocks_in_inventory", """
			{"blockIds":["minecraft:dirt"],"quantity":3,"allowUnilluminated":true}
			"""));

		assertEquals("ensure_blocks_in_inventory", ensureCall.name());
		assertEquals("minecraft:dirt", ensureCall.arguments().getAsJsonArray("blockIds").get(0).getAsString());
		assertEquals(3, ensureCall.arguments().get("quantity").getAsInt());
		assertTrue(ensureCall.arguments().get("allowUnilluminated").getAsBoolean());
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("ensure_blocks_in_inventory", """
				{"blockIds":["minecraft:dirt"],"quantity":0}
				"""))
		);
	}

	@Test
	void exposesAndParsesConfigurePathfindWithAllRuntimeSettings() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();
		JsonObject parameters = toolSchema(tools, "configure_pathfind");
		JsonObject settings = parameters.getAsJsonObject("properties").getAsJsonObject("settings");
		assertTrue(toolNames(tools).contains("configure_pathfind"));
		assertFalse(settings.has("properties"), "Setting catalog must be queried, not repeated in every request");
		assertEquals(3, settings.getAsJsonObject("additionalProperties").getAsJsonArray("anyOf").size());
		assertTrue(ai.moeru.airicraft.agent.baritone.BaritonePathfindSettings.describeSettings("allowDownward").toString().contains("staircases"));
		assertEquals("configure_pathfind", PlannerToolCatalog.parseToolCall(toolCall("configure_pathfind", """
			{"settings":{"allowDownward":true,"allowParkour":false}}
			""")).name());
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("configure_pathfind", "{\"settings\":{}}"))
		);
	}

	@Test
	void exposesAndParsesPlannerOwnedLightingPolicy() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();
		JsonObject parameters = toolSchema(tools, "configure_lighting");

		assertTrue(toolNames(tools).contains("configure_lighting"));
		assertEquals(4, parameters.getAsJsonArray("required").size());
		PlannerToolCall call = PlannerToolCatalog.parseToolCall(toolCall("configure_lighting", """
			{"enabled":true,"mode":"darkness","maxLightLevel":2,"minSpacingBlocks":6}
			"""));
		assertEquals("configure_lighting", call.name());
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("configure_lighting", """
				{"enabled":true,"mode":"darkness","maxLightLevel":16,"minSpacingBlocks":6}
				"""))
		);
	}

	@Test
	void exposesResumeTaskWithRequiredSafetyHoldId() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();
		JsonObject parameters = toolSchema(tools, "resume_task");

		assertTrue(toolNames(tools).contains("resume_task"));
		assertEquals("holdId", parameters.getAsJsonArray("required").get(0).getAsString());
		PlannerToolCall call = PlannerToolCatalog.parseToolCall(toolCall("resume_task", """
			{"holdId":"hold-42"}
			"""));
		assertEquals("hold-42", call.arguments().get("holdId").getAsString());
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("resume_task", "{}"))
		);
	}

	@Test
	void exposesAndParsesReturnToSurfaceTool() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();
		JsonObject parameters = toolSchema(tools, "return_to_surface");

		assertTrue(toolNames(tools).contains("return_to_surface"));
		assertEquals(0, parameters.getAsJsonArray("required").size());
		assertTrue(parameters.getAsJsonObject("properties").has("useTowering"));
		assertTrue(parameters.getAsJsonObject("properties").has("fillerBlockIds"));
		assertEquals("return_to_surface", PlannerToolCatalog.parseToolCall(toolCall("return_to_surface", "{}")).name());

		PlannerToolCall call = PlannerToolCatalog.parseToolCall(toolCall("return_to_surface", """
			{"useTowering":true,"fillerBlockIds":["minecraft:dirt","minecraft:cobblestone"]}
			"""));

		assertEquals("return_to_surface", call.name());
		assertTrue(call.arguments().get("useTowering").getAsBoolean());
		assertEquals("minecraft:dirt", call.arguments().getAsJsonArray("fillerBlockIds").get(0).getAsString());
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("return_to_surface", """
				{"useTowering":true,"fillerBlockIds":[]}
				"""))
		);
	}

	@Test
	void exposesAndParsesEntityInteractionTools() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();

		assertTrue(toolNames(tools).contains("attack_entity"));
		assertTrue(toolNames(tools).contains("use_entity"));
		PlannerToolCall attackCall = PlannerToolCatalog.parseToolCall(toolCall("attack_entity", """
			{"entityTypeId":"minecraft:sheep","mode":"hit_once"}
			"""));
		PlannerToolCall useCall = PlannerToolCatalog.parseToolCall(toolCall("use_entity", """
			{"name":"Dinner","itemId":"minecraft:shears"}
			"""));

		assertEquals("attack_entity", attackCall.name());
		assertEquals("minecraft:sheep", attackCall.arguments().get("entityTypeId").getAsString());
		assertEquals("hit_once", attackCall.arguments().get("mode").getAsString());
		assertEquals("use_entity", useCall.name());
		assertEquals("Dinner", useCall.arguments().get("name").getAsString());
		assertEquals("minecraft:shears", useCall.arguments().get("itemId").getAsString());
	}

	@Test
	void exposesAndParsesBlockInteractionTools() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();

		assertTrue(toolNames(tools).contains("place_block"));
		assertTrue(toolNames(tools).contains("use_block"));
		assertTrue(toolNames(tools).contains("break_blocks"));
		PlannerToolCall placeCall = PlannerToolCatalog.parseToolCall(toolCall("place_block", """
			{"itemId":"minecraft:dirt","x":1,"y":64,"z":2,"facePreference":"down","requireCurrentTargetMaterial":"air_or_replaceable"}
			"""));
		PlannerToolCall useCall = PlannerToolCatalog.parseToolCall(toolCall("use_block", """
			{"itemId":"minecraft:wheat_seeds","x":1,"y":65,"z":2,"expectedSupportBlockIds":["minecraft:farmland"],"expectedTargetMaterial":"air"}
			"""));

		assertEquals("place_block", placeCall.name());
		assertEquals("minecraft:dirt", placeCall.arguments().get("itemId").getAsString());
		assertEquals("use_block", useCall.name());
		assertEquals("minecraft:wheat_seeds", useCall.arguments().get("itemId").getAsString());
		PlannerToolCall breakCall = PlannerToolCatalog.parseToolCall(toolCall("break_blocks", """
			{"targets":[{"x":1,"y":64,"z":2,"expectedBlockIds":["minecraft:grass_block","minecraft:dirt"]}]}
			"""));

		assertEquals("break_blocks", breakCall.name());
		assertEquals(1, breakCall.arguments().getAsJsonArray("targets").size());
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("place_block", """
				{"itemId":"minecraft:dirt","x":1,"y":64,"z":2,"facePreference":"sideways"}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("use_block", """
				{"x":1,"y":64,"z":2,"expectedTargetMaterial":"solid"}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("break_blocks", """
				{"targets":[{"x":1,"y":64,"z":2}]}
				"""))
		);
	}

	@Test
	void parsesBatchedBlockInteractionTools() {
		PlannerToolCall placeCall = PlannerToolCatalog.parseToolCall(toolCall("place_block", """
			{
			  "itemId":"minecraft:dirt",
			  "facePreference":"down",
			  "requireCurrentTargetMaterial":"air_or_replaceable",
			  "targets":[
			    {"x":1,"y":64,"z":2},
			    {"x":2,"y":64,"z":2,"facePreference":"north","requireCurrentTargetMaterial":"air"}
			  ]
			}
			"""));
		PlannerToolCall useCall = PlannerToolCatalog.parseToolCall(toolCall("use_block", """
			{
			  "itemId":"minecraft:wheat_seeds",
			  "expectedSupportBlockIds":["minecraft:farmland"],
			  "expectedTargetMaterial":"air",
			  "targets":[
			    {"x":1,"y":65,"z":2},
			    {"x":2,"y":65,"z":2,"facePreference":"down","expectedSupportBlockIds":["minecraft:farmland"]}
			  ]
			}
			"""));

		assertEquals("place_block", placeCall.name());
		assertEquals(2, placeCall.arguments().getAsJsonArray("targets").size());
		assertEquals("use_block", useCall.name());
		assertEquals(2, useCall.arguments().getAsJsonArray("targets").size());
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("place_block", """
				{"itemId":"minecraft:dirt","x":1,"y":64,"z":2,"targets":[{"x":2,"y":64,"z":2}]}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("use_block", """
				{"targets":[]}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("use_block", """
				{"targets":[
				  {"x":1,"y":64,"z":2},
				  {"x":2,"y":64,"z":2},
				  {"x":3,"y":64,"z":2},
				  {"x":4,"y":64,"z":2},
				  {"x":5,"y":64,"z":2},
				  {"x":6,"y":64,"z":2},
				  {"x":7,"y":64,"z":2},
				  {"x":8,"y":64,"z":2},
				  {"x":9,"y":64,"z":2},
				  {"x":10,"y":64,"z":2},
				  {"x":11,"y":64,"z":2},
				  {"x":12,"y":64,"z":2},
				  {"x":13,"y":64,"z":2},
				  {"x":14,"y":64,"z":2},
				  {"x":15,"y":64,"z":2},
				  {"x":16,"y":64,"z":2},
				  {"x":17,"y":64,"z":2}
				]}
				"""))
		);
	}

	@Test
	void entityInteractionToolSchemasRequireUuid() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();
		JsonObject attackParameters = toolSchema(tools, "attack_entity");
		JsonObject useParameters = toolSchema(tools, "use_entity");

		assertRequiredUuid(attackParameters);
		assertRequiredUuid(useParameters);
	}

	@Test
	void exposesAndParsesNearbyEntityInspectionTool() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();

		assertTrue(toolNames(tools).contains("inspect_nearby_entities"));
		PlannerToolCall inspectCall = PlannerToolCatalog.parseToolCall(toolCall("inspect_nearby_entities", """
			{"prompt":"List nearby mobs I can interact with."}
			"""));

		assertEquals("inspect_nearby_entities", inspectCall.name());
		assertEquals("List nearby mobs I can interact with.", inspectCall.arguments().get("prompt").getAsString());
	}

	@Test
	void takeALookAcceptsDirectionBlockOrPlayerTargets() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();
		JsonObject parameters = toolSchema(tools, "take_a_look");

		assertTrue(parameters.getAsJsonObject("properties").has("direction"));
		assertTrue(parameters.getAsJsonObject("properties").has("x"));
		assertTrue(parameters.getAsJsonObject("properties").has("y"));
		assertTrue(parameters.getAsJsonObject("properties").has("z"));
		assertTrue(parameters.getAsJsonObject("properties").has("targetPlayer"));
		assertEquals("west", PlannerToolCatalog.parseToolCall(toolCall("take_a_look", """
			{"direction":"west"}
			""")).arguments().get("direction").getAsString());
		assertEquals(12, PlannerToolCatalog.parseToolCall(toolCall("take_a_look", """
			{"x":12,"y":64,"z":-8}
			""")).arguments().get("x").getAsInt());
		assertEquals("Alice", PlannerToolCatalog.parseToolCall(toolCall("take_a_look", """
			{"targetPlayer":"Alice"}
			""")).arguments().get("targetPlayer").getAsString());
	}

	@Test
	void takeALookRejectsConflictingOrPartialTargets() {
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("take_a_look", """
				{"direction":"west","targetPlayer":"Alice"}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("take_a_look", """
				{"x":12,"z":-8}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("take_a_look", """
				{"direction":"up"}
				"""))
		);
	}

	@Test
	void exposesCheckCraftablesInsteadOfInspectRecipes() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();

		assertTrue(toolNames(tools).contains("check_craftables"));
		assertFalse(toolNames(tools).contains("inspect_recipes"));
		assertEquals("check_craftables", PlannerToolCatalog.parseToolCall(toolCall("check_craftables", "{}")).name());
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("inspect_recipes", "{}"))
			);
	}

	@Test
	void exposesActionGraphPlannerTools() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();
		JsonObject startParameters = toolSchema(tools, "start_action_goal");
		JsonObject collectParameters = toolSchema(tools, "collect_resource");

		assertTrue(toolNames(tools).contains("start_action_goal"));
		assertTrue(toolNames(tools).contains("inspect_action_goal"));
		assertTrue(toolNames(tools).contains("cancel_action_goal"));
		assertTrue(toolNames(tools).contains("inspect_action_trace"));
		assertTrue(toolNames(tools).contains("list_action_capabilities"));
		assertTrue(startParameters.getAsJsonObject("properties").has("kind"));
		assertTrue(startParameters.getAsJsonObject("properties").has("itemId"));
		assertTrue(startParameters.getAsJsonObject("properties").has("quantity"));

		PlannerToolCall startCall = PlannerToolCatalog.parseToolCall(toolCall("start_action_goal", """
			{"kind":"inventory_item","itemId":"minecraft:bread","quantity":1}
			"""));
		PlannerToolCall cancelCall = PlannerToolCatalog.parseToolCall(toolCall("cancel_action_goal", """
			{"reason":"user_changed_task"}
			"""));

		assertEquals("start_action_goal", startCall.name());
		assertEquals("minecraft:bread", startCall.arguments().get("itemId").getAsString());
			assertEquals("cancel_action_goal", cancelCall.name());
			assertTrue(collectParameters
				.getAsJsonObject("properties")
				.getAsJsonObject("resourceKind")
				.getAsJsonArray("enum")
				.asList()
				.stream()
				.anyMatch(value -> "RAW_IRON".equals(value.getAsString())));
			PlannerToolCall resourceCall = PlannerToolCatalog.parseToolCall(toolCall("start_action_goal", """
				{"kind":"resource_collection","resourceKind":"RAW_IRON","quantity":3}
				"""));
			assertEquals("RAW_IRON", resourceCall.arguments().get("resourceKind").getAsString());
			assertThrows(com.google.gson.JsonParseException.class, () ->
				PlannerToolCatalog.parseToolCall(toolCall("start_action_goal", """
					{"kind":"inventory_item","itemId":"minecraft:bread"}
				"""))
		);
	}

	@Test
	void exposesAndParsesInspectWorldTool() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();
		JsonObject parameters = toolSchema(tools, "inspect_world");

		assertTrue(toolNames(tools).contains("inspect_world"));
		assertTrue(parameters.getAsJsonObject("properties").has("mode"));
		assertTrue(parameters.getAsJsonObject("properties").has("scope"));
		assertTrue(parameters.getAsJsonObject("properties").has("x1"));
		assertTrue(parameters.getAsJsonObject("properties").has("targetMaterial"));

		PlannerToolCall areaCall = PlannerToolCatalog.parseToolCall(toolCall("inspect_world", """
			{"mode":"inspect_area","scope":"self","horizontalRadius":6,"verticalRadius":2,"maxResults":12}
			"""));
		PlannerToolCall boxCall = PlannerToolCatalog.parseToolCall(toolCall("inspect_world", """
			{"mode":"inspect_area","scope":"box","x1":10,"y1":63,"z1":10,"x2":18,"y2":66,"z2":18}
			"""));
		PlannerToolCall findBlocksCall = PlannerToolCatalog.parseToolCall(toolCall("inspect_world", """
			{"mode":"find_blocks","scope":"self","blockIds":["minecraft:wheat"],"stateFilters":["age=7"],"maxResults":16}
			"""));
		PlannerToolCall placementCall = PlannerToolCatalog.parseToolCall(toolCall("inspect_world", """
			{"mode":"find_placement_sites","scope":"self","supportBlockIds":["minecraft:farmland"],"supportStateFilters":["moisture=7"],"targetMaterial":"air","requireStandableAdjacent":true,"maxResults":16}
			"""));

		assertEquals("inspect_world", areaCall.name());
		assertEquals(12, areaCall.arguments().get("maxResults").getAsInt());
		assertEquals("box", boxCall.arguments().get("scope").getAsString());
		assertEquals("age=7", findBlocksCall.arguments().getAsJsonArray("stateFilters").get(0).getAsString());
		assertEquals("air", placementCall.arguments().get("targetMaterial").getAsString());
	}

	@Test
	void inspectWorldRejectsInvalidModeScopeAndFieldCombinations() {
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("inspect_world", """
				{"mode":"inspect_area","scope":"self","x":1}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("inspect_world", """
				{"mode":"inspect_area","scope":"center","x":1,"y":64,"z":1,"x1":0}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("inspect_world", """
				{"mode":"inspect_area","scope":"box","x1":0,"y1":64,"z1":0,"x2":1,"y2":65,"z2":1,"horizontalRadius":4}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("inspect_world", """
				{"mode":"inspect_area","scope":"self","blockIds":["minecraft:dirt"]}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("inspect_world", """
				{"mode":"find_blocks","scope":"self","blockIds":["minecraft:wheat"],"stateFilters":["age"]}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("inspect_world", """
				{"mode":"find_blocks","scope":"self","blockIds":["minecraft:wheat"],"horizontalRadius":17}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("inspect_world", """
				{"mode":"inspect_area","scope":"self","maxResults":65}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("inspect_world", """
				{"mode":"find_placement_sites","scope":"self","targetMaterial":"solid"}
				"""))
		);
	}

	@Test
	void exposesAndParsesSmeltingTools() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();

		assertTrue(toolNames(tools).contains("check_smeltables"));
		assertTrue(toolNames(tools).contains("smelt_items"));
		assertTrue(toolNames(tools).contains("inspect_smelting"));
		assertTrue(toolNames(tools).contains("collect_smelted_items"));
		assertTrue(toolNames(tools).contains("cancel_smelting"));
		assertEquals("check_smeltables", PlannerToolCatalog.parseToolCall(toolCall("check_smeltables", "{}")).name());
		assertEquals("inspect_smelting", PlannerToolCatalog.parseToolCall(toolCall("inspect_smelting", "{}")).name());

		PlannerToolCall smeltCall = PlannerToolCatalog.parseToolCall(toolCall("smelt_items", """
			{"optionId":"smelt:iron:nearby-1","inputQuantity":3,"fuelMode":"manual","fuelItemId":"minecraft:coal","fuelQuantity":1,"confirmationToken":"confirm-1"}
			"""));
		PlannerToolCall collectCall = PlannerToolCatalog.parseToolCall(toolCall("collect_smelted_items", """
			{"processId":"smelt-process-1","confirmationToken":"confirm-2"}
			"""));
		PlannerToolCall cancelCall = PlannerToolCatalog.parseToolCall(toolCall("cancel_smelting", """
			{"processId":"smelt-process-1"}
			"""));

		assertEquals("smelt_items", smeltCall.name());
		assertEquals("smelt:iron:nearby-1", smeltCall.arguments().get("optionId").getAsString());
		assertEquals(3, smeltCall.arguments().get("inputQuantity").getAsInt());
		assertEquals("manual", smeltCall.arguments().get("fuelMode").getAsString());
		assertEquals("minecraft:coal", smeltCall.arguments().get("fuelItemId").getAsString());
		assertEquals(1, smeltCall.arguments().get("fuelQuantity").getAsInt());
		assertEquals("confirm-1", smeltCall.arguments().get("confirmationToken").getAsString());
		assertEquals("collect_smelted_items", collectCall.name());
		assertEquals("smelt-process-1", collectCall.arguments().get("processId").getAsString());
		assertEquals("confirm-2", collectCall.arguments().get("confirmationToken").getAsString());
		assertEquals("cancel_smelting", cancelCall.name());
	}

	@Test
	void smeltingToolSchemasRequireExecutableOptionAndPositiveQuantity() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();
		JsonObject smeltParameters = toolSchema(tools, "smelt_items");

		JsonArray required = smeltParameters.getAsJsonArray("required");
		assertNotNull(required);
		assertTrue(required.asList().stream().anyMatch(element -> "optionId".equals(element.getAsString())));
		assertTrue(required.asList().stream().anyMatch(element -> "inputQuantity".equals(element.getAsString())));
		assertTrue(smeltParameters.getAsJsonObject("properties").has("confirmationToken"));
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("smelt_items", """
				{"inputQuantity":1}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("smelt_items", """
				{"optionId":"smelt:iron","inputQuantity":0}
				"""))
		);
	}

	@Test
	void providerToolsAreExposedAndParsed() throws Exception {
		PlannerToolRegistry registry = PlannerToolRegistry.of(new StubPlannerToolProvider(
			"recipe_search",
			"search_recipes",
			"Search recipes through a provider.",
			"Use search_recipes for recipe viewer searches.",
			"Tool result for search_recipes: provider=stub"
		));
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "role": "assistant",
			        "content": null,
			        "tool_calls": [
			          {
			            "id": "call_search",
			            "type": "function",
			            "function": {
			              "name": "search_recipes",
			              "arguments": "{\\"query\\":\\"oak planks\\",\\"mode\\":\\"output\\"}"
			            }
			          }
			        ]
			      }
			    }
			  ]
			}
			""")) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port()), registry);

			PlannerResponse response = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent recipes for oak planks", LlmMessageKind.USER_TURN)
			))).payload();

			JsonObject body = JsonParser.parseString(bodyRef.get()).getAsJsonObject();
			assertTrue(toolNames(body.getAsJsonArray("tools")).contains("search_recipes"));
			assertEquals("search_recipes", response.toolCall().name());
			assertEquals("oak planks", response.toolCall().arguments().get("query").getAsString());
			assertEquals("output", response.toolCall().arguments().get("mode").getAsString());
		}
	}

	@Test
	void providerReadToolsCanBeParsedInToolCallBatch() throws Exception {
		PlannerToolRegistry registry = PlannerToolRegistry.of(new StubPlannerToolProvider(
			"smelting",
			"check_smeltables",
			"Check currently executable smelting options.",
			"Use check_smeltables for furnace options.",
			"Tool result for check_smeltables: provider=stub"
		));
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "role": "assistant",
			        "tool_calls": [
			          {"id":"call_craft","type":"function","function":{"name":"check_craftables","arguments":"{}"}},
			          {"id":"call_smelt","type":"function","function":{"name":"check_smeltables","arguments":"{\\"query\\":\\"iron ore\\"}"}}
			        ]
			      }
			    }
			  ]
			}
			""")) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port()), registry);

			PlannerResponse response = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent what can I make?", LlmMessageKind.USER_TURN)
			))).payload();

			assertEquals(List.of("check_craftables", "check_smeltables"), response.toolCalls().stream()
				.map(PlannerToolCall::name)
				.toList());
			assertEquals("iron ore", response.toolCalls().get(1).arguments().get("query").getAsString());
		}
	}

	@Test
	void rejectsGivePlayerWithoutTargetPlayer() {
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("give_player", """
				{"itemId":"minecraft:oak_log","quantity":2}
				"""))
		);
	}

	@Test
	void rejectsEntityInteractionWithoutSelector() {
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("attack_entity", """
				{}
				"""))
		);
	}

	@Test
	void parsesMultipleReadToolCallsInOneAssistantMessage() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "role": "assistant",
			        "tool_calls": [
			          {"id":"call_1","type":"function","function":{"name":"inspect_inventory","arguments":"{}"}},
			          {"id":"call_2","type":"function","function":{"name":"check_craftables","arguments":"{}"}}
			        ]
			      }
			    }
			  ]
			}
			""")) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port()));

		PlannerResponse response = backend.generate(LlmConversation.of(List.of(
			LlmChatMessage.system("system"),
			LlmChatMessage.user("Alice said just now: @agent inspect", LlmMessageKind.USER_TURN)
		))).payload();

		assertEquals(List.of("inspect_inventory", "check_craftables"), response.toolCalls().stream()
			.map(PlannerToolCall::name)
			.toList());
		assertEquals("inspect_inventory", response.toolCall().name());
	}
	}

	private static com.google.gson.Gson gson() {
		return new com.google.gson.Gson();
	}

	private static List<String> toolNames(JsonArray tools) {
		return tools.asList().stream()
			.map(element -> element.getAsJsonObject().getAsJsonObject("function").get("name").getAsString())
			.toList();
	}

	private static JsonObject toolSchema(JsonArray tools, String toolName) {
		return tools.asList().stream()
			.map(JsonElement::getAsJsonObject)
			.map(tool -> tool.getAsJsonObject("function"))
			.filter(function -> toolName.equals(function.get("name").getAsString()))
			.map(function -> function.getAsJsonObject("parameters"))
			.findFirst()
			.orElseThrow();
	}

	private static void assertRequiredUuid(JsonObject parameters) {
		JsonArray required = parameters.getAsJsonArray("required");
		assertNotNull(required);
		assertEquals(1, required.size());
		assertEquals("uuid", required.get(0).getAsString());
	}

	private static JsonObject toolCall(String name, String arguments) {
		JsonObject function = new JsonObject();
		function.addProperty("name", name);
		function.addProperty("arguments", arguments);
		JsonObject toolCall = new JsonObject();
		toolCall.addProperty("id", "call_" + name);
		toolCall.addProperty("type", "function");
		toolCall.add("function", function);
		return toolCall;
	}

	private record StubPlannerToolProvider(
		String id,
		String toolName,
		String description,
		String promptInstructions,
		String result
	) implements PlannerToolProvider {
		@Override
		public List<java.util.Map<String, Object>> openAiTools() {
			return List.of(PlannerToolCatalog.toolForProvider(
				toolName,
				description,
				PlannerToolCatalog.propertiesForProvider(
					PlannerToolCatalog.propForProvider("query", PlannerToolCatalog.stringForProvider("Item, recipe, or category query.")),
					PlannerToolCatalog.propForProvider("mode", PlannerToolCatalog.enumStringForProvider("Search mode.", List.of("output", "input", "all")))
				),
				List.of("query")
			));
		}

		@Override
		public boolean handles(String toolName) {
			return this.toolName.equals(PlannerToolCatalog.normalizeName(toolName));
		}

		@Override
		public java.util.concurrent.CompletableFuture<String> execute(PlannerToolCall toolCall) {
			return java.util.concurrent.CompletableFuture.completedFuture(result);
		}
	}

	@Test
	void legacyJsonContentIsPlaintextNotPlannerPayload() throws Exception {
		String legacyJson = "{\\\"replyText\\\":\\\"old\\\",\\\"intent\\\":{\\\"type\\\":\\\"reply_only\\\"},\\\"toolRequest\\\":null}";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "role": "assistant",
			        "content": "%s"
			      }
			    }
			  ]
			}
			""".formatted(legacyJson))) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port()));

			PlannerResponse response = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent old style", LlmMessageKind.USER_TURN)
			))).payload();

			assertEquals("{\"replyText\":\"old\",\"intent\":{\"type\":\"reply_only\"},\"toolRequest\":null}", response.replyText());
			assertNull(response.toolCall());
		}
	}

	private static AgentConfig.LlmConfig config(int port) {
		return new AgentConfig.LlmConfig(
			"http://127.0.0.1:" + port,
			"planner-key",
			"planner-model",
			"https://api.openai.com/v1",
			"",
			"",
			15_000,
			10_000,
			8,
			65_536,
			"low",
			false
		);
	}

	private static final class TestServer implements AutoCloseable {
		private final HttpServer server;

		private TestServer(HttpServer server) {
			this.server = server;
		}

		private static TestServer start(AtomicReference<String> bodyRef, String responseBody) throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.setExecutor(Executors.newCachedThreadPool());
			server.createContext("/chat/completions", exchange -> handle(exchange, bodyRef, responseBody));
			server.start();
			return new TestServer(server);
		}

		private static void handle(HttpExchange exchange, AtomicReference<String> bodyRef, String responseBody) throws IOException {
			bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		}

		private int port() {
			return server.getAddress().getPort();
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}
}
