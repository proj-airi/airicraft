package ai.moeru.airicraft.agent.recording;

import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import ai.moeru.airicraft.dashboard.DashboardObservationStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One local playtest recording; the launcher archives every stopped run. */
public final class AutomaticPlaytestRecording {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private final String id;
	private final Path pendingDirectory;
	private final Path incidentDirectory;
	private final RuntimeFlightRecorder recorder;
	private long nextSampleTick;
	private Map<String, Object> report;
	private long visualCursor;
	private long visualCount;
	private boolean visualTruncated;
	private String visualSession;
	private final PlaytestVideoRecorder video;

	public AutomaticPlaytestRecording(Path root, Map<String, Object> context) throws IOException {
		this(root, Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID(), context);
	}

	public AutomaticPlaytestRecording(Path root, String id, Map<String, Object> context) throws IOException {
		if (!id.matches("[A-Za-z0-9._-]+") || id.equals(".") || id.equals("..")) throw new IllegalArgumentException("Invalid playtest run ID");
		this.id = id;
		incidentDirectory = root.toAbsolutePath().normalize().resolve(id);
		pendingDirectory = root.toAbsolutePath().normalize().resolve(".in-progress").resolve(id);
		recorder = new RuntimeFlightRecorder(pendingDirectory);
		video = new PlaytestVideoRecorder(pendingDirectory);
		Files.writeString(pendingDirectory.resolve("recording-start.json"), GSON.toJson(Map.of("id", id,
			"startedAt", Instant.now().toString(), "context", context)) + "\n", StandardOpenOption.CREATE_NEW);
	}

	public Path incidentDirectory() { return incidentDirectory; }
	public Path pendingDirectory() { return pendingDirectory; }
	public String id() { return id; }

	/** The caller flushes the integrated server while its tick gate is paused before invoking this. */
	public void saveWorldCheckpoint(Path world, Map<String, Object> metadata) throws IOException {
		Path checkpoint = pendingDirectory.resolve(".world-save");
		try (var files = Files.walk(world)) {
			for (Path source : files.toList()) {
				if (source.getFileName().toString().equals("session.lock")) continue;
				Path target = checkpoint.resolve(world.relativize(source));
				if (Files.isDirectory(source)) Files.createDirectories(target);
				else Files.copy(source, target);
			}
		}
		Files.move(checkpoint, pendingDirectory.resolve("world-save"));
		writeJson("world-save.json", metadata);
	}

	public void recordTick(EmbodiedAgentRuntime runtime, DashboardObservationStore history) throws IOException {
		recorder.recordTick(runtime);
		recordVisualHistory(history);
		if (runtime.tickCount() >= nextSampleTick) {
			Files.writeString(pendingDirectory.resolve("status-samples.jsonl"), GSON.toJson(Map.of(
				"collectedAt", Instant.now().toString(), "kind", "automatic_playtest", "tick", runtime.tickCount(),
				"session", runtime.sessionSnapshot(), "activeJob", runtime.activeJob(), "taskExecution", runtime.taskExecutionSnapshot()
			)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			nextSampleTick = runtime.tickCount() + 100;
		}
	}

	/** Hosted playtests append tester join/leave observations; ordinary runs never create this file. */
	public void recordParticipants(java.util.List<Map<String, Object>> records) throws IOException {
		if (records.isEmpty()) return;
		StringBuilder lines = new StringBuilder();
		for (Map<String, Object> record : records) lines.append(GSON.toJson(record)).append('\n');
		Files.writeString(pendingDirectory.resolve("players.jsonl"), lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	public void report(String description, long tick) throws IOException {
		if (report == null) {
			report = Map.of("id", id, "kind", "suspected_interface_bug", "description", description,
				"reportedAt", Instant.now().toString(), "clientTick", tick);
		}
		writeJson("bug-report.json", report);
	}

	public void finish(EmbodiedAgentRuntime runtime, DashboardObservationStore history,
		Map<String, Object> pause, byte[] screenshot) throws IOException {
		writeJson("pause.json", pause);
		if (screenshot.length > 0) Files.write(pendingDirectory.resolve("paused.png"), screenshot);
		finish(runtime, history, "CAPTURE_READY", "bug_report");
	}

	/** Capture the same terminal evidence on ordinary disconnect, before the runtime is cleared. */
	public void finish(EmbodiedAgentRuntime runtime, DashboardObservationStore history, String reason) throws IOException {
		finish(runtime, history, "FINISHED", reason);
	}

	private void finish(EmbodiedAgentRuntime runtime, DashboardObservationStore history, String status, String reason) throws IOException {
		recordTick(runtime, history);
		recorder.writeFinalSnapshots(runtime);
		video.close();
		Files.writeString(pendingDirectory.resolve("live-recording.jsonl"), GSON.toJson(Map.of(
			"recordType", "export_complete", "observations", visualCount, "truncated", visualTruncated)) + "\n", StandardOpenOption.APPEND);
		Map<String, Object> summary = new LinkedHashMap<>(recorder.statusPayload());
		summary.put("id", id);
		summary.put("status", status);
		summary.put("reason", reason);
		summary.put("visualHistoryTruncated", visualTruncated);
		summary.put("screenVideoRequired", true);
		summary.put("finishedAt", Instant.now().toString());
		summary.put("outputDir", incidentDirectory.toString());
		writeJson("summary.json", summary);
		// The launcher closes the client, validates the required Recorder Play, then publishes the directory.
	}

	/** Drain rendered observations even when hosted gameplay ticks are paused. */
	public void recordVisualHistory(DashboardObservationStore history) throws IOException {
		video.checkFailure();
		var status = history.recordingStatus();
		long through = ((Number) status.get("latestSequence")).longValue();
		long to = ((Number) status.get("serverTickId")).longValue();
		String session = (String) status.get("sessionId");
		if (visualSession != null && !visualSession.equals(session)) throw new IOException("Visual recording session changed during playtest");
		if (visualSession == null) {
			var manifest = new LinkedHashMap<>(status);
			manifest.put("recordType", "manifest");
			manifest.put("includesFrames", false);
			manifest.put("screenVideo", "screen.mp4");
			Files.writeString(pendingDirectory.resolve("live-recording.jsonl"), GSON.toJson(manifest) + "\n", StandardOpenOption.CREATE_NEW);
			// Sequence numbers span dashboard sessions; earlier title-screen observations are not lost playtest data.
			visualCursor = ((Number) status.get("oldestSequence")).longValue() - 1L;
			visualSession = session;
		}
		if (through <= visualCursor) return;
		try (var writer = Files.newBufferedWriter(pendingDirectory.resolve("live-recording.jsonl"), StandardOpenOption.APPEND)) {
			while (true) {
				var page = history.recordingPage(0, to, visualCursor, through, 100, Set.of(), true);
				for (Object observation : (java.util.List<?>) page.get("observations")) {
					JsonObject entry = (JsonObject) observation;
					long sequence = entry.get("sequence").getAsLong();
					if (entry.get("type").getAsString().equals("visual_frame")) {
						video.append(entry);
						entry.getAsJsonObject("payload").remove("imageBase64");
					}
					visualTruncated |= sequence > visualCursor + 1L;
					GSON.toJson(observation, writer);
					writer.newLine();
					visualCursor = sequence;
					visualCount++;
				}
				long next = ((Number) page.get("nextCursor")).longValue();
				visualTruncated |= next > visualCursor;
				visualCursor = next;
				if (!Boolean.TRUE.equals(page.get("hasMore"))) break;
			}
		}
	}

	private void writeJson(String name, Object payload) throws IOException {
		Files.writeString(pendingDirectory.resolve(name), GSON.toJson(payload) + "\n");
	}
}
