package ai.moeru.airicraft.dashboard;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticReportTest {
	@TempDir Path directory;

	@Test
	void savedBundleIsFrozenBeforeResetAndPublishesOnlyCompleteFiles() throws Exception {
		var store = new DashboardObservationStore(1024 * 1024);
		store.advanceClock(12, true, true);
		store.append("semantic_event", 40, 123, Map.of("type", "task.failed"));
		var report = DiagnosticReport.capture(store, Map.of("modVersion", "test-version"));
		store.startSession("reload", 0, 456);
		Path file = report.save(directory);
		String text = Files.readString(file);
		assertTrue(text.contains("task.failed"));
		assertTrue(text.contains("test-version"));
		assertEquals("integrity", JsonParser.parseString(text.lines().toList().getLast()).getAsJsonObject().get("recordType").getAsString());
		try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
		Path blocked = directory.resolve("not-a-directory");
		Files.writeString(blocked, "keep me");
		assertThrows(java.io.IOException.class, () -> report.save(blocked));
		assertEquals("keep me", Files.readString(blocked));
	}

	@Test
	void bundlesCarryBuildIdentityEvenWhenRunningFromAReleasedJar() throws Exception {
		Properties properties = new Properties();
		try (var input = getClass().getResourceAsStream("/airicraft-build.properties")) {
			assertNotNull(input, "Build identity must be packaged, not looked up from the user's checkout");
			properties.load(input);
		}
		assertFalse(properties.getProperty("revision").isBlank());
		assertFalse(properties.getProperty("modVersion").isBlank());
		assertEquals("1.21.8", properties.getProperty("minecraftVersion"));
	}

	@Test
	void boundsBytesEvenWhenTheRecordCountIsBelowItsLimit() throws Exception {
		var store = new DashboardObservationStore(16 * 1024 * 1024);
		for (int i = 0; i < 1900; i++) store.append("llm_call", 0, i, Map.of(
			"model", "m".repeat(256), "responseModel", "r".repeat(256), "providerName", "p".repeat(256),
			"status", "s".repeat(256), "requestKind", "k".repeat(256)));
		String contents = Files.readString(DiagnosticReport.capture(store, Map.of()).save(directory));
		var manifest = JsonParser.parseString(contents.lines().findFirst().orElseThrow()).getAsJsonObject();
		assertTrue(manifest.getAsJsonObject("coverage").get("reportLimitOmitted").getAsInt() > 0);
		assertTrue(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 3 * 1024 * 1024);
	}

	@Test
	void retainsReflexCauseAndTokenUsageForDiagnosingFailures() throws Exception {
		var store = new DashboardObservationStore(1024 * 1024);
		store.append("decision_state", 0, 0, Map.of("reflex", Map.of("snapshot", Map.of("state", "ACTIVE", "cause", "DROWNING"))));
		store.append("llm_call", 0, 0, Map.of("usage", Map.of("promptTokens", 123, "completionTokens", 45, "totalTokens", 168)));
		String body = Files.readString(DiagnosticReport.capture(store, Map.of()).save(directory));
		assertTrue(body.contains("DROWNING"));
		assertTrue(body.contains("promptTokens"));
		assertTrue(body.contains("completionTokens"));
	}

	@Test
	void reportsStringClippingAndNeverIncludesUnknownNestedFields() throws Exception {
		var store = new DashboardObservationStore(1024 * 1024);
		store.append("llm_call", 1, 10, Map.of("model", "x".repeat(1000), "requestBody", "private"));
		store.append("runtime_snapshot", 1, 11, Map.of("taskExecution", Map.of("state", "FAILED", "newSecret", "private")));
		Path report = DiagnosticReport.capture(store, Map.of()).save(directory);
		String body = Files.readString(report);
		var manifest = JsonParser.parseString(body.lines().findFirst().orElseThrow()).getAsJsonObject();
		assertTrue(manifest.getAsJsonObject("coverage").get("truncated").getAsBoolean());
		assertEquals(1, manifest.getAsJsonObject("coverage").get("clippedFields").getAsInt());
		assertFalse(body.contains("private"));
		assertFalse(body.contains("x".repeat(257)));
		assertTrue(body.contains("FAILED"));
	}
}
