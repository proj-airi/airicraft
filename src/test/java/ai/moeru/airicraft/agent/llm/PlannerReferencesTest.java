package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlannerReferencesTest {
	@Test void longNonIdentityTokensDoNotStallPresentation() {
		String text = "a".repeat(30_000);
		assertTimeoutPreemptively(java.time.Duration.ofMillis(500),
			() -> assertEquals(text, new PlannerReferences().present(text)));
	}

	private static final String WORK = "JOB:job-11111111-2222-3333-4444-555555555555";
	private static final String HOLD = "hold-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
	@Test void resolvesExactNativeWorkAndHoldBeforeToolValidationAcrossRoles() {
		var controller = PlannerToolRegistry.of(new ai.moeru.airicraft.agent.work.WorkToolProvider(call -> java.util.concurrent.CompletableFuture.completedFuture("ok")));
		var thinker = PlannerToolRegistry.of(new ai.moeru.airicraft.agent.work.WorkToolProvider(call -> java.util.concurrent.CompletableFuture.completedFuture("ok")));
		thinker.shareReferences(controller);
		String work = controller.references().present(WORK), hold = controller.references().present(HOLD);
		assertTrue(work.length() < 12);
		assertEquals(work, thinker.references().present(WORK));
		var args = JsonParser.parseString("{\"workId\":\"" + work + "\",\"holdId\":\"" + hold + "\"}").getAsJsonObject();
		var call = PlannerToolCatalog.parseToolCall("resume_work", args, thinker);
		assertEquals(WORK, call.arguments().get("workId").getAsString());
		assertEquals(HOLD, call.arguments().get("holdId").getAsString());
		assertEquals(work, args.get("workId").getAsString());
	}

	@Test void retriesAndCompactionReuseReferencesWithoutTouchingPairingOrImages() {
		var references = new PlannerReferences();
		var messages = List.<Map<String,Object>>of(
			Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", HOLD, "type", "function", "function", Map.of("name", "inspect_work", "arguments", "{\"workId\":\"" + WORK + "\"}")))),
			Map.of("role", "tool", "tool_call_id", HOLD, "content", WORK),
			Map.of("role", "user", "content", List.of(Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + HOLD)), Map.of("type", "text", "text", WORK))));
		var wire = references.presentMessages(messages);
		assertEquals(wire, references.presentMessages(messages));
		assertEquals(HOLD, wire.get(0).getAsJsonObject().getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString());
		assertEquals(HOLD, wire.get(1).getAsJsonObject().get("tool_call_id").getAsString());
		assertEquals("data:image/png;base64," + HOLD, wire.get(2).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString());
		String checkpoint = "Context checkpoint: continue " + references.present(WORK);
		assertEquals(checkpoint, references.present(checkpoint));
	}

	@Test void presentedToolArgumentsKeepExactNumericParameters() {
		var references = new PlannerReferences();
		String arguments = "{\"workId\":\"" + WORK + "\",\"position\":{\"x\":12.3456789}}";
		var messages = List.<Map<String,Object>>of(Map.of("role", "assistant", "content", "",
			"tool_calls", List.of(Map.of("id", "call_1", "type", "function",
				"function", Map.of("name", "inspect_work", "arguments", arguments)))));
		String wire = references.presentMessages(messages).get(0).getAsJsonObject().getAsJsonArray("tool_calls")
			.get(0).getAsJsonObject().getAsJsonObject("function").get("arguments").getAsString();
		assertTrue(wire.contains("12.3456789"));
		assertTrue(wire.contains(references.present(WORK)));
	}

	@Test void expiredOrInventedReferencesNeverResolveToAnotherIdentity() {
		var references = new PlannerReferences(1);
		String old = references.present(WORK);
		references.present(HOLD);
		assertThrows(JsonParseException.class, () -> references.resolveArguments(JsonParser.parseString("{\"workId\":\"" + old + "\"}").getAsJsonObject()));
		assertNotEquals(old, references.present(WORK));
		assertNotEquals(old, new PlannerReferences().present(WORK));
	}

	@Test void notesPersistNativeIdentitiesButOrdinaryTextIsUntouched() {
		var references = new PlannerReferences();
		String ref = references.present(WORK);
		var args = JsonParser.parseString("{\"evidence\":\"failed " + ref + "\",\"narration\":\"literal " + ref + "\",\"uuids\":[\"" + references.present(HOLD) + "\"]}").getAsJsonObject();
		var resolved = references.resolveArguments(args);
		assertEquals("failed " + WORK, resolved.get("evidence").getAsString());
		assertEquals("literal " + ref, resolved.get("narration").getAsString());
		assertEquals(HOLD, resolved.getAsJsonArray("uuids").get(0).getAsString());
	}
	@Test void proseIsNotSearchedForAnIdentity() {
		var references = new PlannerReferences();
		String ref = references.present(WORK);
		assertEquals("Work " + WORK + ".", references.present("Work " + WORK + "."));
		assertEquals("Work " + WORK + ": next", references.present("Work " + WORK + ": next"));
		String child = WORK + ":child-2";
		assertEquals("Use " + child + ".", references.present("Use " + child + "."));
		assertNotEquals(child, references.present(child));
		assertEquals(WORK, references.resolveArguments(JsonParser.parseString("{\"workId\":\"" + ref + "\"}").getAsJsonObject()).get("workId").getAsString());
	}

}
