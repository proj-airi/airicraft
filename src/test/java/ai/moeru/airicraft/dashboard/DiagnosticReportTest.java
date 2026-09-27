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
		assertEquals(0x50, Files.readAllBytes(file)[0]);
		String text = readReport(file);
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
	void savesUpdatedPreviewsSeparatelyWithoutChangingEarlierBundles() throws Exception {
		var store = new DashboardObservationStore(1024 * 1024);
		store.append("log", 1, 1, Map.of("message", "Developer evidence"));
		var draft = DiagnosticReport.mark(store, Map.of(), java.util.List.of());
		var minimal = draft.prepare(new DiagnosticReport.Request(DiagnosticReport.Mode.MINIMAL, "Initial description"), java.util.List.of());
		Path first = minimal.save(directory);
		byte[] original = Files.readAllBytes(first);
		var developer = draft.prepare(new DiagnosticReport.Request(DiagnosticReport.Mode.DEVELOPER, "Updated description"), java.util.List.of());
		Path second = developer.save(directory);
		assertNotEquals(first, second, "Each save must publish a separate bundle");
		assertArrayEquals(original, Files.readAllBytes(first), "A revised preview must not overwrite the earlier report");
		try (var zip = new java.util.zip.ZipFile(second.toFile())) {
			for (var attachment : developer.attachments().entrySet()) {
				assertEquals(attachment.getValue(), new String(zip.getInputStream(zip.getEntry(attachment.getKey())).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			}
		}
		var firstManifest = JsonParser.parseString(readReport(first).lines().findFirst().orElseThrow()).getAsJsonObject();
		var secondManifest = JsonParser.parseString(readReport(second).lines().findFirst().orElseThrow()).getAsJsonObject();
		assertEquals(firstManifest.get("reportId"), secondManifest.get("reportId"), "Both previews belong to the same marked incident");
		assertEquals("minimal", firstManifest.get("mode").getAsString());
		assertEquals("developer", secondManifest.get("mode").getAsString());
		assertEquals("Updated description", secondManifest.get("description").getAsString());
		assertTrue(readReport(second).contains("Developer evidence"));
		try (var files = Files.list(directory)) { assertEquals(java.util.Set.of(first, second), files.collect(java.util.stream.Collectors.toSet())); }
	}

	@Test
	void savingTheSamePreviewAgainPublishesAnotherCompleteBundle() throws Exception {
		var report = DiagnosticReport.capture(new DashboardObservationStore(1024 * 1024), Map.of());
		Path first = report.save(directory);
		Path second = report.save(directory);
		assertNotEquals(first, second);
		assertEquals(readReport(first), readReport(second));
		try (var files = Files.list(directory)) { assertEquals(java.util.Set.of(first, second), files.collect(java.util.stream.Collectors.toSet())); }
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
		String contents = readReport(DiagnosticReport.capture(store, Map.of()).save(directory));
		var manifest = JsonParser.parseString(contents.lines().findFirst().orElseThrow()).getAsJsonObject();
		assertTrue(manifest.getAsJsonObject("coverage").get("reportLimitOmitted").getAsInt() > 0);
		assertTrue(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 3 * 1024 * 1024);
	}

	@Test
	void retainsReflexCauseAndTokenUsageForDiagnosingFailures() throws Exception {
		var store = new DashboardObservationStore(1024 * 1024);
		store.append("decision_state", 0, 0, Map.of("reflex", Map.of("snapshot", Map.of("state", "ACTIVE", "cause", "DROWNING"))));
		store.append("llm_call", 0, 0, Map.of("usage", Map.of("promptTokens", 123, "completionTokens", 45, "totalTokens", 168)));
		String body = readReport(DiagnosticReport.capture(store, Map.of()).save(directory));
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
		String body = readReport(report);
		var manifest = JsonParser.parseString(body.lines().findFirst().orElseThrow()).getAsJsonObject();
		assertTrue(manifest.getAsJsonObject("coverage").get("truncated").getAsBoolean());
		assertEquals(1, manifest.getAsJsonObject("coverage").get("clippedFields").getAsInt());
		assertFalse(body.contains("private"));
		assertFalse(body.contains("x".repeat(257)));
		assertTrue(body.contains("FAILED"));
	}
	private static String readReport(Path path) throws Exception {
		try (var zip = new java.util.zip.ZipFile(path.toFile())) {
			assertNotNull(zip.getEntry("summary.txt"));
			return new String(zip.getInputStream(zip.getEntry("report.jsonl")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
	}

}
