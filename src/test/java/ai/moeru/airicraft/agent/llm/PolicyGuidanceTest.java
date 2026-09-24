package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.policy.GraalPolicyInvocation;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PolicyGuidanceTest {
	@Test void queryToolsRemainAvailableWithoutActionPolicyAfterReset() {
		var registry = PlannerToolRegistry.of(
			new PolicyToolProvider(call -> { throw new AssertionError("unexpected action"); }),
			WorldQueryScriptToolProvider.forClient(() -> -1L, ignored -> {}),
			new PolicyDocsToolProvider());
		for (int pass = 0; pass < 2; pass++) {
			for (String name : List.of("query_world", "read_policy_docs")) {
				assertTrue(registry.activeOpenAiTool(name).isPresent(), name);
			}
			String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY, registry);
			assertFalse(prompt.contains("run_policy"));
			assertTrue(prompt.contains("Prefer query_world"));
			assertTrue(registry.activeOpenAiTool("run_policy").isEmpty());
		}
		assertFalse(PlannerToolRegistry.of(new PolicyDocsToolProvider()).activeOpenAiTool("run_policy").isPresent());
	}

	@Test void helpIsWorldIndependentAndDoesNotYieldOrInvokeActions() throws Exception {
		var docs = new PolicyDocsToolProvider();
		var registry = PlannerToolRegistry.of(docs);
		assertTrue(registry.isReadTool("read_policy_docs"));
		assertFalse(registry.endsTurn("read_policy_docs"));
		String text = registry.execute(new PlannerToolCall("docs", "read_policy_docs", new JsonObject(), null, null)).get();
		assertTrue(text.contains("## query_world"));
		assertFalse(text.contains("run_policy"));
		assertTrue(text.contains("## Self-created read-only tools"));
		var invalid = new JsonObject();
		invalid.addProperty("path", "/tmp/arbitrary-file");
		assertThrows(IllegalArgumentException.class, () -> docs.validateArguments("read_policy_docs", invalid));
	}

	@Test void frozenPromptDoesNotAdvertiseDisabledPolicy() {
		var registry = PlannerToolRegistry.of(new PolicyToolProvider(call -> { throw new AssertionError("unexpected action"); }), new PolicyDocsToolProvider());
		registry.freezeToolPrefix();
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY, registry);
		assertFalse(prompt.contains("run_policy"));
		assertFalse(prompt.contains("function* main(p, input)"));
		assertTrue(registry.activeOpenAiTool("run_policy").isEmpty());

		var readOnlyRegistry = PlannerToolRegistry.of(new PolicyDocsToolProvider());
		readOnlyRegistry.freezeToolPrefix();
		assertFalse(PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY, readOnlyRegistry).contains("Prefer run_policy"));
	}

	@Test void restockExampleTransfersOnlyMissingStockAndCloses() throws Exception {
		var result = run(0, "{\"stock\":{\"minecraft:bread\":8,\"minecraft:torch\":16}}", "{\"minecraft:bread\":3,\"minecraft:torch\":16}", "{\"minecraft:bread\":20}");
		assertTrue(result.value().getAsJsonObject().get("restocked").getAsBoolean());
		assertEquals(List.of("observe_container", "withdraw", "close_container"), result.effects());
		assertEquals(5, result.value().getAsJsonObject().getAsJsonArray("transferred").get(0).getAsJsonObject().get("quantity").getAsInt());
		var repeat = run(0, "{\"stock\":{\"minecraft:bread\":8}}", "{\"minecraft:bread\":8}", "{}");
		assertEquals(List.of("observe_container", "close_container"), repeat.effects());
		var shortfall = run(0, "{\"stock\":{\"minecraft:bread\":8}}", "{}", "{\"minecraft:bread\":2}");
		assertFalse(shortfall.value().getAsJsonObject().get("restocked").getAsBoolean());
		assertEquals(List.of("observe_container"), shortfall.effects());
	}

	@Test void conditionalWithdrawalAndProjectionExamplesKeepTheirContracts() throws Exception {
		var spare = run(1, "{}", "{}", "{\"minecraft:torch\":10}");
		assertEquals(2, spare.value().getAsJsonObject().get("taken").getAsInt());
		var reserve = run(1, "{}", "{}", "{\"minecraft:torch\":7}");
		assertEquals(List.of("observe_container", "close_container"), reserve.effects());
		var counts = run(2, "{\"ids\":[\"minecraft:coal\",\"minecraft:iron_ingot\"]}", "{\"minecraft:coal\":3}", "{\"minecraft:iron_ingot\":4}");
		assertEquals(List.of("observe_container"), counts.effects());
		assertEquals(3, counts.value().getAsJsonArray().get(0).getAsJsonObject().get("carried").getAsInt());
		assertEquals(4, counts.value().getAsJsonArray().get(1).getAsJsonObject().get("stored").getAsInt());
	}

	private record Result(JsonElement value, List<String> effects) {}
	private static Result run(int example, String input, String inventory, String container) throws Exception {
		var sources = java.util.regex.Pattern.compile("```js\\n(.*?)```", java.util.regex.Pattern.DOTALL)
			.matcher(PolicyDocsToolProvider.readResource("/prompts/planner-policy.md")).results().map(m -> m.group(1)).toList();
		assertEquals(5, sources.size());
		var snapshot = new JsonObject();
		snapshot.addProperty("syncId", 7);
		snapshot.add("inventory", JsonParser.parseString(inventory));
		snapshot.add("container", JsonParser.parseString(container));
		List<String> effects = new ArrayList<>();
		try (var invocation = new GraalPolicyInvocation(sources.get(example), JsonParser.parseString(input))) {
			JsonElement response = JsonNull.INSTANCE;
			for (int i = 0; i < 8; i++) {
				var step = invocation.resume(response).get(15, TimeUnit.SECONDS).getAsJsonObject();
				if (step.get("done").getAsBoolean()) return new Result(step.get("value"), effects);
				var effect = step.getAsJsonObject("value");
				String operation = effect.get("operation").getAsString();
				effects.add(operation);
				if (operation.equals("withdraw")) {
					assertEquals(7, effect.getAsJsonObject("arguments").get("syncId").getAsInt());
					for (JsonElement element : effect.getAsJsonObject("arguments").getAsJsonArray("items")) {
						var item = element.getAsJsonObject();
						String id = item.get("itemId").getAsString();
						int quantity = item.get("quantity").getAsInt();
						assertTrue(quantity > 0);
						var stored = snapshot.getAsJsonObject("container");
						assertTrue(stored.get(id).getAsInt() >= quantity);
						stored.addProperty(id, stored.get(id).getAsInt() - quantity);
						var carried = snapshot.getAsJsonObject("inventory");
						carried.addProperty(id, (carried.has(id) ? carried.get(id).getAsInt() : 0) + quantity);
					}
				}
				response = operation.equals("close_container") ? JsonParser.parseString("{\"closed\":true,\"syncId\":7}") : snapshot;
			}
		}
		throw new AssertionError("Example did not terminate");
	}
}
