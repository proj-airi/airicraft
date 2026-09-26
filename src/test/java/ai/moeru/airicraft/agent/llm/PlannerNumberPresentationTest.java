package ai.moeru.airicraft.agent.llm;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PlannerNumberPresentationTest {
	@Test void canonicalObservationKeepsTypedFieldsAndExactValues() {
		String id = "1e5e7000-0000-4000-8000-000000000000";
		var exchange = PlannerObservation.exchange(Map.of("worldSessionId", id, "current", Map.of("physical", Map.of("position", Map.of("x", 12.3456789)))));
		var result = exchange.get(1);
		assertEquals("tool", result.role());
		assertEquals(id, result.fields().getAsJsonObject().get("worldSessionId").getAsString());
		assertEquals(12.3456789, result.fields().getAsJsonObject().getAsJsonObject("current").getAsJsonObject("physical").getAsJsonObject("position").get("x").getAsDouble());
		var wire = new PlannerReferences().presentMessages(LlmConversation.of(exchange));
		assertTrue(wire.get(1).getAsJsonObject().get("content").getAsString().contains("\"x\":12.3"));
		assertEquals(id, result.fields().getAsJsonObject().get("worldSessionId").getAsString());
	}
	@Test void roundsNumbersInStructuredToolEvidenceWithoutTouchingText() {
		String evidence = "{\"standingRisk\":175.13384099878414,\"facing\":{\"pitch\":68.29244790474574,\"yaw\":-21.316162109375},\"tiny\":-1.2e-8,\"whole\":66,\"delegationId\":\"1e5e7000-0000-4000-8000-000000000000\"}";
		var input = List.<Map<String,Object>>of(Map.of("role", "tool", "tool_call_id", "call_1", "content", evidence));
		var references = new PlannerReferences();
		var content = references.presentMessages(input).get(0).getAsJsonObject().get("content").getAsString();
		assertEquals("{\"standingRisk\":175.1,\"facing\":{\"pitch\":68.3,\"yaw\":-21.3},\"tiny\":0,\"whole\":66,\"delegationId\":\"" + references.present("1e5e7000-0000-4000-8000-000000000000") + "\"}", content);
		assertEquals(evidence, input.getFirst().get("content"));
	}
	@Test void preservesIntegersIdentifiersQuotedStringsAndUrls() {
		String text = "tick 123456789012345 version 1.21.8 http://127.0.0.1:8765/file/1.234 id_ab12.345 {\"id\":\"1.234\",\"field_1352\":2}";
		assertEquals(text, new PlannerReferences().present(text));
	}
	@Test void wirePresentationLeavesUnstructuredTextAndPairingExact() {
		var input = List.<Map<String,Object>>of(Map.of("role", "tool", "tool_call_id", "call_1.234", "content", "risk=121.31318561980031 delegationId=1e5e7000-0000-4000-8000-000000000000"));
		var wire = new PlannerReferences().presentMessages(input).get(0).getAsJsonObject();
		assertEquals(input.getFirst().get("content"), wire.get("content").getAsString());
		assertEquals("call_1.234", wire.get("tool_call_id").getAsString());
	}
}
