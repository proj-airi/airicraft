package ai.moeru.airicraft.dashboard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticReportDraftTest {
	@Test void holdsTheMarkedWindowAndDescriptionEvenAfterRecordingReset() throws Exception {
		var store = new DashboardObservationStore(1024 * 1024);
		store.advanceClock(2000, false, true);
		store.append("semantic_event", 2200, 123, Map.of("type", "task.failed"));
		String session = store.sessionId();
		var draft = DiagnosticReport.mark(store, Map.of("build", Map.of("revision", "test-build")), List.of());
		store.advanceClock(5000, false, true);
		store.startSession("reset", 6000, 999);
		String content = text(draft.prepare(new DiagnosticReport.Request(DiagnosticReport.Mode.SUMMARY, "Stopped moving"), List.of()));
		var manifest = manifest(content);
		assertEquals(2000, manifest.getAsJsonObject("window").get("toTick").getAsInt());
		assertEquals(session, manifest.getAsJsonObject("correlation").get("recordingSessionId").getAsString());
		assertEquals("Stopped moving", manifest.get("description").getAsString());
		assertTrue(content.contains("task.failed"));
		assertNotNull(manifest.get("summary"));
		assertTrue(manifest.getAsJsonObject("summary").get("text").getAsString().contains("Stopped moving"));
	}

	@Test void minimalReportOmitsAllRecordedContentAndRedactsTheDescriptionAndMetadata() throws Exception {
		var store = new DashboardObservationStore(1024 * 1024);
		store.append("runtime_snapshot", 1, 1, Map.of("world", Map.of("dimension", "private-dimension")));
		store.append("log", 1, 1, Map.of("message", "private-log"));
		var draft = DiagnosticReport.mark(store, Map.of("model", "known-key"), List.of("known-key"));
		String content = text(draft.prepare(new DiagnosticReport.Request(DiagnosticReport.Mode.MINIMAL, "known-key stopped"), List.of()));
		assertFalse(content.contains("known-key"));
		assertFalse(content.contains("private-dimension"));
		assertFalse(content.contains("private-log"));
		assertEquals(2, content.lines().count());
		assertFalse(manifest(content).getAsJsonObject("runtimeState").get("available").getAsBoolean());
	}

	@Test void fullDeveloperChoiceIncludesRecordedContentButRedactsBothOldAndCurrentCredentials() throws Exception {
		var store = new DashboardObservationStore(1024 * 1024);
		store.append("llm_request", 1, 1, Map.of("requestBody", "user conversation with old-key"));
		store.append("log", 1, 1, Map.of("message", "Authorization: Bearer unknown-key"));
		var draft = DiagnosticReport.mark(store, Map.of(), List.of("old-key"));
		String content = text(draft.prepare(new DiagnosticReport.Request(DiagnosticReport.Mode.DEVELOPER, "new-key is configured"), List.of("new-key")));
		assertTrue(content.contains("user conversation"));
		for (String key : List.of("old-key", "new-key", "unknown-key")) assertFalse(content.contains(key));
		assertEquals("developer", manifest(content).get("mode").getAsString());
	}

	@Test void boundsFrozenDeveloperEvidenceAndReportsSourceAndOutputOmissions() throws Exception {
		var store = new DashboardObservationStore(16 * 1024 * 1024);
		for (int i = 0; i < 22; i++) store.append("log", i, i, Map.of("message", "x".repeat(500_000)));
		var draft = DiagnosticReport.mark(store, Map.of(), List.of());
		var report = draft.prepare(new DiagnosticReport.Request(DiagnosticReport.Mode.DEVELOPER, ""), List.of());
		var coverage = report.preview().getAsJsonObject("coverage");
		assertTrue(coverage.get("sourceLimitOmitted").getAsInt() > 0);
		assertTrue(coverage.get("reportLimitOmitted").getAsInt() > 0);
		assertTrue(coverage.get("truncated").getAsBoolean());
		assertTrue(text(report).length() < 2 * 1024 * 1024 + 10_000);
	}

	@Test void redactsBeforeClippingAndAcceptsNonObjectDeveloperPayloads() throws Exception {
		String secret = "long-credential-" + "x".repeat(300);
		var store = new DashboardObservationStore(1024 * 1024);
		store.append("llm_call", 1, 1, Map.of("model", secret));
		store.append("future_type", 2, 2, List.of("opaque", "content"));
		var draft = DiagnosticReport.mark(store, Map.of(), List.of(secret));
		String summary = text(draft.prepare(new DiagnosticReport.Request(DiagnosticReport.Mode.SUMMARY, ""), List.of()));
		assertFalse(summary.contains("long-credential"));
		assertTrue(summary.contains("[REDACTED]"));
		String developer = text(draft.prepare(new DiagnosticReport.Request(DiagnosticReport.Mode.DEVELOPER, ""), List.of()));
		assertTrue(developer.contains("opaque"));
	}

	@Test void validatesDescriptionSizeAtTheInputBoundary() {
		assertThrows(IllegalArgumentException.class, () -> new DiagnosticReport.Request(DiagnosticReport.Mode.MINIMAL, "x".repeat(2001)));
		assertThrows(IllegalArgumentException.class, () -> new DiagnosticReport.Request(null, "description"));
	}

	private static String text(DiagnosticReport report) throws Exception {
		var output = new ByteArrayOutputStream(); report.writeTo(output); return output.toString(StandardCharsets.UTF_8);
	}
	private static JsonObject manifest(String content) { return JsonParser.parseString(content.lines().findFirst().orElseThrow()).getAsJsonObject(); }
}
