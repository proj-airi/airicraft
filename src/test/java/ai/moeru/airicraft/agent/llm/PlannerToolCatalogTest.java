package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlannerToolCatalogTest {
	@Test void foodPolicyCanBeReadAndChangedAtomically() {
		assertEquals("configure_food", PlannerToolCatalog.parseToolCall(toolCall("configure_food", "{}")).name());
		assertEquals("heal", PlannerToolCatalog.parseToolCall(toolCall("configure_food",
			"{\"goal\":\"heal\",\"foodChoice\":\"cooked_only\"}"))
			.arguments().get("goal").getAsString());
		for (String bad : java.util.List.of("{\"goal\":\"heal\"}",
			"{\"goal\":\"always\",\"foodChoice\":\"any\"}",
			"{\"goal\":\"movement\",\"foodChoice\":\"poisonous\"}")) {
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall("configure_food", bad)));
		}
	}

	@Test void validatesAtomicReflexPolicyAndEmptyQuery() {
		assertEquals("configure_reflex", PlannerToolCatalog.parseToolCall(toolCall("configure_reflex", "{}")).name());
		String policy = "{\"combatEnabled\":false,\"drowningEnabled\":true,\"maxThreatDistance\":16,\"requireLineOfSight\":true}";
		PlannerToolCatalog.parseToolCall(toolCall("configure_reflex", policy));
		for (String bad : java.util.List.of("{\"combatEnabled\":false}", policy.replace("16", "1.5"),
			policy.replace("16", "33"), policy.replace("16", "0"), policy.replace("false", "\"false\""))) {
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall("configure_reflex", bad)));
		}
	}
	@Test void validatesBoundedEntitySearch() {
		PlannerToolCall call = PlannerToolCatalog.parseToolCall(toolCall(PlannerToolCatalog.INSPECT_NEARBY_ENTITIES,
			"{\"radius\":128,\"maxResults\":4,\"entityTypeIds\":[\"minecraft:sheep\"]}"));
		assertEquals(128, call.arguments().get("radius").getAsInt());
		for (String args : java.util.List.of("{\"radius\":129}", "{\"radius\":0}", "{\"radius\":1.5}",
			"{\"maxResults\":65}", "{\"maxResults\":\"4\"}", "{\"entityTypeIds\":[]}"))
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall(PlannerToolCatalog.INSPECT_NEARBY_ENTITIES, args)));
	}

	@Test void validatesAcquisitionScopeAcrossIntentLevels() {
		for (String name : java.util.List.of(PlannerToolCatalog.COLLECT_RESOURCE, PlannerToolCatalog.MINE_BLOCKS, PlannerToolCatalog.ENSURE_BLOCKS_IN_INVENTORY)) {
			String selector = name.equals(PlannerToolCatalog.COLLECT_RESOURCE) ? "\"resourceKind\":\"WOOD_LOGS\"" : "\"blockIds\":[\"minecraft:oak_log\"]";
			String args = "{" + selector + ",\"quantity\":2,\"constraints\":{\"surfaceOnly\":true,\"visibleOnly\":true,\"radius\":24,\"center\":{\"x\":1,\"y\":64,\"z\":-3}}}";
			assertEquals(24, PlannerToolCatalog.parseToolCall(toolCall(name,args)).arguments().getAsJsonObject("constraints").get("radius").getAsInt());
			assertEquals(true, PlannerToolCatalog.parseToolCall(toolCall(name,args)).arguments().getAsJsonObject("constraints").get("visibleOnly").getAsBoolean());
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall(name, args.replace("\"visibleOnly\":true", "\"visibleOnly\":\"true\""))));
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall(name, args.replace("24", "33"))));
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall(name, args.replace("surfaceOnly", "surfaceOnli"))));
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall(name, args.replace("\"y\":64,", ""))));
		}
	}

	@Test
	void parsesArgumentFreeObserve() {
		PlannerToolCall call = PlannerToolCatalog.parseToolCall(toolCall(PlannerToolCatalog.OBSERVE, "{}"));

		assertEquals(PlannerToolCatalog.OBSERVE, call.name());
		assertEquals(0, call.arguments().size());
	}

	@Test
	void parsesEquipmentAndFoodToolsWithExactItemIds() {
		PlannerToolCall equip = PlannerToolCatalog.parseToolCall(toolCall(
			PlannerToolCatalog.EQUIP_ITEM,
			"{\"itemId\":\"minecraft:iron_chestplate\"}"
		));
		PlannerToolCall eat = PlannerToolCatalog.parseToolCall(toolCall(
			PlannerToolCatalog.EAT_FOOD,
			"{\"itemId\":\"minecraft:bread\"}"
		));

		assertEquals("minecraft:iron_chestplate", equip.arguments().get("itemId").getAsString());
		assertEquals("minecraft:bread", eat.arguments().get("itemId").getAsString());
	}

	@Test void acceptsSingleAndBatchContainerTransfersAndRejectsAmbiguousBatches() {
		String single = "{\"syncId\":1,\"direction\":\"deposit\",\"itemId\":\"minecraft:dirt\",\"quantity\":3}";
		String batch = "{\"syncId\":1,\"direction\":\"withdraw\",\"items\":[{\"itemId\":\"minecraft:dirt\",\"quantity\":3},{\"itemId\":\"minecraft:stone\",\"quantity\":2}]}";
		PlannerToolCatalog.parseToolCall(toolCall("transfer_container", single));
		PlannerToolCatalog.parseToolCall(toolCall("transfer_container", batch));
		for (String bad : java.util.List.of(batch.replace("3", "0"), batch.replace("3", "1.5"),
			batch.replace("\"items\":", "\"itemId\":\"minecraft:dirt\",\"items\":"),
			"{\"syncId\":1,\"direction\":\"deposit\",\"items\":[]}",
			batch.replace("\"quantity\":3", "\"quantiti\":3"))) {
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall("transfer_container", bad)), bad);
		}
	}

	private static JsonObject toolCall(String name, String arguments) {
		JsonObject toolCall = new JsonObject();
		toolCall.addProperty("id", "call_test");
		toolCall.addProperty("type", "function");
		JsonObject function = new JsonObject();
		function.addProperty("name", name);
		function.addProperty("arguments", arguments);
		toolCall.add("function", function);
		return toolCall;
	}
}
