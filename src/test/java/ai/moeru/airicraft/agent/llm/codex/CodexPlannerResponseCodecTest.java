package ai.moeru.airicraft.agent.llm.codex;

import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
import ai.moeru.airicraft.agent.memory.PlaceMemoryToolProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodexPlannerResponseCodecTest {
	private static java.util.Map<String, Object> observation(int tick, int dirt) {
		return java.util.Map.of("worldSessionId", "w", "tick", tick, "serverTick", tick, "decisionOwner", "controller", "actuatorOwner", "idle",
			"current", java.util.Map.of("inventory", java.util.Map.of("minecraft:dirt", dirt),
				"travelRestrictions", java.util.Map.of("coordinateMeaning", "inclusive occupied cells, including head and edits; search bounds are separate")),
			"afterEventSequence", 0, "throughEventSequence", 0, "events", java.util.List.of());
	}

	private static String text(JsonArray input) {
		var out = new StringBuilder();
		for (var item : input) out.append(item.getAsJsonObject().get("text").getAsString()).append('\n');
		return out.toString();
	}

	@Test void observationsAreDeltasWithinAndAcrossTurnsOfOneThread() {
		var codec = new CodexPlannerResponseCodec(PlannerToolRegistry.of());
		var presenter = new ai.moeru.airicraft.agent.llm.ObservationPresenter();
		var first = new java.util.ArrayList<ai.moeru.airicraft.agent.llm.LlmChatMessage>();
		first.addAll(ai.moeru.airicraft.agent.llm.PlannerObservation.exchange(observation(1, 2)));
		first.addAll(ai.moeru.airicraft.agent.llm.PlannerObservation.exchange(observation(2, 3)));
		String turnOne = text(codec.turnInput(ai.moeru.airicraft.agent.llm.LlmConversation.of(first), presenter));
		assertTrue(turnOne.contains("Carrying 2 dirt"), turnOne);
		assertTrue(turnOne.contains("Inventory: dirt +1 (3)"), turnOne);

		var second = ai.moeru.airicraft.agent.llm.PlannerObservation.exchange(observation(3, 3));
		String turnTwo = text(codec.turnInput(ai.moeru.airicraft.agent.llm.LlmConversation.of(second), presenter.copy()));
		assertTrue(turnTwo.contains("State unchanged since the previous observation."), "the thread already holds turn one\n" + turnTwo);
		String fresh = text(codec.turnInput(ai.moeru.airicraft.agent.llm.LlmConversation.of(second)));
		assertTrue(fresh.contains("Carrying 3 dirt"), "a new thread starts from full state\n" + fresh);
	}

	@Test void sharesStructureRepairWithNativeToolCalls() throws Exception {
		var registry = PlannerToolRegistry.of(new PlaceMemoryToolProvider(
			() -> { throw new AssertionError("No world access"); }, Runnable::run));
		JsonObject args = new JsonObject(); args.addProperty("name", "home");
		args.addProperty("position", "{\"x\":1,\"y\":2,\"z\":3}");
		JsonObject proposal = new JsonObject(); proposal.addProperty("name", "remember_place");
		proposal.addProperty("argumentsJson", new JsonPrimitive(args.toString()).toString());
		JsonArray calls = new JsonArray(); calls.add(proposal);
		JsonObject response = new JsonObject(); response.add("toolCalls", calls); response.add("chatMessages", new JsonArray());
		var result = new CodexPlannerResponseCodec(registry).parse(response.toString(), 1);
		assertEquals(1, result.toolCall().arguments().getAsJsonObject("position").get("x").getAsInt());
		assertEquals(java.util.List.of("", "/position"), result.toolCall().repairedArgumentPaths());
	}
}
