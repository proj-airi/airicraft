package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.recording.AutomaticPlaytestRecording;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.*;
import ai.moeru.airicraft.dashboard.DashboardObservationStore;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticPlaytestRecordingTest {
	@TempDir Path root;

	@Test void flightCaptureWaitsForTheLauncherToFinalizeTheRequiredRecorderPlay() throws Exception {
		var runtime = EmbodiedAgentRuntime.createForTests(new NoopExecutor());
		var recording = new AutomaticPlaytestRecording(root, Map.of("world", "test"));
		var history = new DashboardObservationStore(1024 * 1024);
		recording.recordTick(runtime, history);
		recording.report("The work result contradicts the observed inventory.", 42);
		assertFalse(Files.exists(recording.incidentDirectory()));
		history.advanceClock(42, true, true);
		history.append("runtime_snapshot", 42, 1, Map.of("health", 20));
		recording.finish(runtime, history, Map.of("debugSessionId", "paused", "pauseEpoch", 1), new byte[]{1, 2, 3});
		assertFalse(Files.exists(recording.incidentDirectory()));
		Path dir = recording.pendingDirectory();
		for (String file : new String[]{"bug-report.json", "summary.json", "pause.json", "paused.png", "status-samples.jsonl", "planner-calls.jsonl", "agent-status-final.json", "agent-events-final.json", "agent-debug-timeline-final.json", "agent-debug-llm-calls-final.json", "world-evidence-final.json", "live-recording.jsonl"})
			assertTrue(Files.isRegularFile(dir.resolve(file)), file);
		assertEquals("CAPTURE_READY", JsonParser.parseString(Files.readString(dir.resolve("summary.json"))).getAsJsonObject().get("status").getAsString());
		assertTrue(Files.readAllLines(dir.resolve("live-recording.jsonl")).getLast().contains("export_complete"));
		assertTrue(Files.readString(dir.resolve("bug-report.json")).contains("contradicts"));
	}

	@Test void failedFinalizationKeepsTheSourceAndNeverPublishesACompletedIncident() throws Exception {
		var runtime = EmbodiedAgentRuntime.createForTests(new NoopExecutor());
		var recording = new AutomaticPlaytestRecording(root, Map.of());
		recording.report("bug", 1);
		Files.createDirectory(recording.pendingDirectory().resolve("pause.json"));
		assertThrows(IOException.class, () -> recording.finish(runtime, new DashboardObservationStore(1024 * 1024), Map.of(), new byte[0]));
		assertTrue(Files.exists(recording.pendingDirectory().resolve("bug-report.json")));
		assertFalse(Files.exists(recording.incidentDirectory()));
	}

	@Test void normalExitRetainsTheFullFlightDatasetWithoutABugReport() throws Exception {
		var first = new AutomaticPlaytestRecording(root, Map.of());
		var second = new AutomaticPlaytestRecording(root, Map.of());
		assertNotEquals(first.incidentDirectory(), second.incidentDirectory());
		var runtime = EmbodiedAgentRuntime.createForTests(new NoopExecutor());
		var history = new DashboardObservationStore(1024 * 1024);
		history.append("visual_frame", 1, 1, Map.of("imageBase64", jpeg(1)));
		first.finish(runtime, history, "world_left");
		assertTrue(Files.readString(first.pendingDirectory().resolve("summary.json")).contains("FINISHED"));
		for (String file : new String[]{"planner-calls.jsonl", "agent-status-final.json", "agent-events-final.json",
			"agent-debug-timeline-final.json", "agent-debug-llm-calls-final.json", "world-evidence-final.json"})
			assertTrue(Files.isRegularFile(first.pendingDirectory().resolve(file)), file);
		var lines = Files.readAllLines(first.pendingDirectory().resolve("live-recording.jsonl"));
		assertTrue(lines.stream().anyMatch(line -> line.contains("visual_frame")));
		assertFalse(lines.stream().anyMatch(line -> line.contains("imageBase64")));
		assertTrue(lines.getLast().contains("export_complete"));
		assertFalse(Files.exists(first.pendingDirectory().resolve("bug-report.json")));
		assertFalse(Files.exists(first.pendingDirectory().resolve("pause.json")));
		assertFalse(Files.exists(first.incidentDirectory()));
		assertTrue(Files.exists(second.pendingDirectory()));
	}

	@Test void retriesPreserveTheOriginalReportAndItsTime() throws Exception {
		var recording = new AutomaticPlaytestRecording(root, Map.of());
		recording.report("Original bug description", 42);
		String original = Files.readString(recording.pendingDirectory().resolve("bug-report.json"));
		recording.report("Duplicate delivery", 99);
		assertEquals(original, Files.readString(recording.pendingDirectory().resolve("bug-report.json")));
	}

	@Test void liveFramesSurviveRollingHistoryEvictionWithoutPosthocRendering() throws Exception {
		var runtime = EmbodiedAgentRuntime.createForTests(new NoopExecutor());
		var recording = new AutomaticPlaytestRecording(root, Map.of());
		var history = new DashboardObservationStore(1024 * 1024);
		history.append("log", 0, 0, Map.of("message", "title screen"));
		history.startSession("world_joined", 1, 1);
		history.advanceClock(1, false, true);
		history.append("visual_frame", 1, 1, Map.of("imageBase64", jpeg(1)));
		recording.recordTick(runtime, history);
		history.advanceClock(20000, false, true);
		history.append("visual_frame", 20000, 2, Map.of("imageBase64", jpeg(2)));
		recording.report("bug", 20000);
		recording.finish(runtime, history, Map.of(), new byte[0]);
		var lines = Files.readAllLines(recording.pendingDirectory().resolve("live-recording.jsonl"));
		assertTrue(lines.stream().anyMatch(line -> line.contains("visual_frame")));
		assertFalse(lines.stream().anyMatch(line -> line.contains("imageBase64")));
		assertEquals(2, Files.readAllLines(recording.pendingDirectory().resolve("screen-frames.jsonl")).size());
		assertTrue(Files.size(recording.pendingDirectory().resolve("screen.mp4")) > 0);
		assertFalse(JsonParser.parseString(lines.getLast()).getAsJsonObject().get("truncated").getAsBoolean());
	}

	@Test void pausedHostRetainsFramesAheadOfTheObservationClock() throws Exception {
		var runtime = EmbodiedAgentRuntime.createForTests(new NoopExecutor());
		var recording = new AutomaticPlaytestRecording(root, Map.of());
		var history = new DashboardObservationStore(1024 * 1024);
		history.advanceClock(1, false, true);
		history.appendFrame(history.sessionId(), 1, 1, 100, Map.of("imageBase64", jpeg(1)));
		recording.recordVisualHistory(history);

		// Rendering keeps sampling the server clock while the client tick is gated.
		history.appendFrame(history.sessionId(), 1, 3, 200, Map.of("imageBase64", jpeg(2)));
		recording.recordVisualHistory(history);
		history.advanceClock(4, false, true);
		recording.finish(runtime, history, "testers_left");

		Path dir = recording.pendingDirectory();
		var frameTicks = Files.readAllLines(dir.resolve("screen-frames.jsonl")).stream()
			.map(line -> JsonParser.parseString(line).getAsJsonObject().get("serverTickId").getAsLong())
			.toList();
		assertEquals(List.of(1L, 3L), frameTicks);
		var lines = Files.readAllLines(dir.resolve("live-recording.jsonl"));
		var footer = JsonParser.parseString(lines.getLast()).getAsJsonObject();
		assertEquals(2, footer.get("observations").getAsInt());
		assertFalse(footer.get("truncated").getAsBoolean());
		var summary = JsonParser.parseString(Files.readString(dir.resolve("summary.json"))).getAsJsonObject();
		assertFalse(summary.get("visualHistoryTruncated").getAsBoolean());
	}

	@Test void anUndrainedFrameEvictedFromHistoryStillMarksTheRecordingTruncated() throws Exception {
		var runtime = EmbodiedAgentRuntime.createForTests(new NoopExecutor());
		var recording = new AutomaticPlaytestRecording(root, Map.of());
		var history = new DashboardObservationStore(1024 * 1024);
		history.advanceClock(1, false, true);
		history.appendFrame(history.sessionId(), 1, 1, 100, Map.of("imageBase64", jpeg(1)));
		recording.recordVisualHistory(history);

		history.appendFrame(history.sessionId(), 2, 2, 200, Map.of("imageBase64", jpeg(2)));
		long laterTick = 3 + DashboardObservationStore.HISTORY_WINDOW_TICKS;
		history.advanceClock(laterTick, false, true);
		history.appendFrame(history.sessionId(), laterTick, laterTick, 300, Map.of("imageBase64", jpeg(3)));
		recording.finish(runtime, history, "testers_left");

		var summary = JsonParser.parseString(Files.readString(recording.pendingDirectory().resolve("summary.json"))).getAsJsonObject();
		assertTrue(summary.get("visualHistoryTruncated").getAsBoolean());
	}

	@Test void launcherRunIdCannotOverwriteAnEarlierRecording() throws Exception {
		var first = new AutomaticPlaytestRecording(root, "run-id", Map.of());
		first.report("original", 1);
		assertThrows(IOException.class, () -> new AutomaticPlaytestRecording(root, "run-id", Map.of()));
		assertTrue(Files.readString(first.pendingDirectory().resolve("bug-report.json")).contains("original"));
	}

	@Test void checkpointPreservesThePausedSaveIndependentlyOfDisconnectWrites() throws Exception {
		var recording = new AutomaticPlaytestRecording(root, Map.of());
		Path world = Files.createDirectory(root.resolve("worker-world"));
		Files.writeString(world.resolve("level.dat"), "paused state");
		Files.writeString(world.resolve("session.lock"), "locked");
		recording.saveWorldCheckpoint(world, Map.of("serverTickId", 42, "capturedWhilePaused", true));
		Files.writeString(world.resolve("level.dat"), "later shutdown state");
		assertEquals("paused state", Files.readString(recording.pendingDirectory().resolve("world-save/level.dat")));
		assertFalse(Files.exists(recording.pendingDirectory().resolve("world-save/session.lock")));
		assertTrue(Files.readString(recording.pendingDirectory().resolve("world-save.json")).contains("42"));
	}

	private static String jpeg(int color) throws IOException {
		var image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, color);
		var output = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "jpeg", output);
		return java.util.Base64.getEncoder().encodeToString(output.toByteArray());
	}

	private static final class NoopExecutor implements WorldTaskExecutor {
		public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> request) { return Optional.empty(); }
		public TaskExecutionSnapshot snapshot() { return TaskExecutionSnapshot.idle(); }
		public void onWorldLeave() {}
		public void shutdown() {}
	}
}
