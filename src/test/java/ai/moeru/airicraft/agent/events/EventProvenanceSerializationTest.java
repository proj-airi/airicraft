package ai.moeru.airicraft.agent.events;

import ai.moeru.airicraft.dashboard.DashboardObservationStore;
import ai.moeru.airicraft.dashboard.DiagnosticReport;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EventProvenanceSerializationTest {
	@Test
	void legacyEventKeepsItsExactDefaultJsonShape() {
		var event = new SemanticEvent(1, 2, 3, "task.failed", Map.of("code", "stuck"));
		assertEquals("{\"seqNo\":1,\"tick\":2,\"timestampMs\":3,\"type\":\"task.failed\",\"payload\":{\"code\":\"stuck\"}}",
			new GsonBuilder().disableHtmlEscaping().create().toJson(event));
	}

	@Test
	void appendedProvenanceAppearsInDefaultJsonWithoutChangingEventData() {
		var buffer = new SemanticEventBuffer(2, () -> 3);
		var event = buffer.append(2, "task.failed", Map.of("code", "stuck"), "task_executor", EventCause.work("work-7"));
		assertEquals("{\"seqNo\":1,\"tick\":2,\"timestampMs\":3,\"type\":\"task.failed\",\"payload\":{\"code\":\"stuck\"},\"source\":\"task_executor\",\"cause\":{\"kind\":\"WORK\",\"ref\":\"work-7\"}}",
			new GsonBuilder().disableHtmlEscaping().create().toJson(event));
		assertEquals(event, buffer.query(null).events().getFirst());
		assertEquals(new EventCause(EventCause.Kind.EVENT, "12"), EventCause.event(12));
		assertEquals(new EventCause(EventCause.Kind.TOOL_CALL, "call-3"), EventCause.toolCall("call-3"));
		assertEquals(new EventCause(EventCause.Kind.GENERATION, "4"), EventCause.generation(4));
	}

	@Test
	void diagnosticDeveloperKeepsProvenanceAndSummaryKeepsItsAllowlist() {
		var store = new DashboardObservationStore(1024 * 1024);
		var event = new SemanticEvent(1, 2, 3, "task.failed", Map.of("code", "stuck"),
			"task_executor", EventCause.work("work-7"));
		store.append("semantic_event", 2, 3, event);
		var draft = DiagnosticReport.mark(store, Map.of(), List.of());

		JsonObject developer = eventPayload(draft.prepare(new DiagnosticReport.Request(DiagnosticReport.Mode.DEVELOPER, ""), List.of()));
		assertEquals("task_executor", developer.get("source").getAsString());
		assertEquals("WORK", developer.getAsJsonObject("cause").get("kind").getAsString());
		assertEquals("work-7", developer.getAsJsonObject("cause").get("ref").getAsString());

		JsonObject summary = eventPayload(draft.prepare(new DiagnosticReport.Request(DiagnosticReport.Mode.SUMMARY, ""), List.of()));
		assertEquals("task.failed", summary.get("type").getAsString());
		assertFalse(summary.has("source"));
		assertFalse(summary.has("cause"));
	}

	private static JsonObject eventPayload(DiagnosticReport report) {
		String jsonl = report.attachments().get("report.jsonl");
		return jsonl.lines()
			.map(JsonParser::parseString)
			.map(value -> value.getAsJsonObject())
			.filter(value -> value.has("type") && value.get("type").getAsString().equals("semantic_event"))
			.findFirst().orElseThrow().getAsJsonObject("payload");
	}
}
