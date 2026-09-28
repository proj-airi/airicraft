package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import ai.moeru.airicraft.agent.recording.RuntimeFlightRecorder;
import ai.moeru.airicraft.agent.evaluation.EvaluationReport;
import ai.moeru.airicraft.agent.evaluation.EvaluationScenario;
import ai.moeru.airicraft.agent.evaluation.EvaluationStatus;
import ai.moeru.airicraft.agent.evaluation.EvaluationWorldFixtureService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class EvaluationFlightRecorder {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final long STATUS_SAMPLE_INTERVAL_TICKS = 100L;

	private Path outputDir;
	private EvaluationScenario scenario;
	private String startedAt;
	private long nextStatusSampleTick;
	private boolean terminalWritten;
	private RuntimeFlightRecorder runtimeRecorder;

	public void start(
		EvaluationScenario nextScenario,
		Path nextOutputDir,
		EvaluationWorldFixtureService.RestoredWorld restoredWorld
	) {
		this.outputDir = nextOutputDir.toAbsolutePath().normalize();
		this.scenario = nextScenario;
		this.startedAt = now();
		this.nextStatusSampleTick = 0L;
		this.terminalWritten = false;
		try {
			runtimeRecorder = new RuntimeFlightRecorder(outputDir);
			writeJson(outputDir.resolve("recording-start.json"), Map.of(
				"startedAt", startedAt,
				"scenario", nextScenario.id(),
				"outputDir", outputDir.toString(),
				"worldName", restoredWorld.worldName(),
				"worldPath", restoredWorld.path().toString()
			));
		}
		catch (IOException exception) {
			Airicraft.LOGGER.warn("Failed to initialize evaluation flight recorder", exception);
		}
	}

	public void recordTick(
		EvaluationScenario currentScenario,
		EvaluationReport report,
		EmbodiedAgentRuntime runtime,
		Supplier<Map<String, Object>> evidenceSupplier
	) {
		if (outputDir == null || currentScenario == null || report == null || runtime == null) {
			return;
		}
		if (terminalWritten) {
			return;
		}
		try {
			Files.createDirectories(outputDir);
			String collectedAt = now();
			if (runtimeRecorder == null) runtimeRecorder = new RuntimeFlightRecorder(outputDir);
			runtimeRecorder.recordTick(runtime);
			boolean terminal = terminal(report.status());
			if (runtime.tickCount() >= nextStatusSampleTick || terminal) {
				appendJsonl(outputDir.resolve("status-samples.jsonl"), Map.of(
					"collectedAt", collectedAt,
					"kind", "evaluation_results",
					"payload", Map.of("available", true, "report", report)
				));
				nextStatusSampleTick = runtime.tickCount() + STATUS_SAMPLE_INTERVAL_TICKS;
			}
			writeSummary(report, terminal ? collectedAt : null);
			if (terminal && !terminalWritten) {
				writeFinalSnapshots(report, runtime, evidenceSupplier);
				terminalWritten = true;
			}
		}
		catch (IOException exception) {
			Airicraft.LOGGER.warn("Failed to record evaluation flight data", exception);
		}
	}

	public Map<String, Object> statusPayload() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("active", outputDir != null);
		payload.put("outputDir", outputDir == null ? null : outputDir.toString());
		payload.put("scenarioId", scenario == null ? null : scenario.id());
		payload.put("eventsTruncated", recordingStatus().get("eventsTruncated"));
		payload.put("timelineTruncated", recordingStatus().get("debugTimelineTruncated"));
		payload.put("llmCallsTruncated", recordingStatus().get("llmCallsTruncated"));
		return payload;
	}

	public void reset() {
		runtimeRecorder = null;
		outputDir = null;
		scenario = null;
		startedAt = null;
		nextStatusSampleTick = 0L;
		terminalWritten = false;
	}

	private Map<String, Object> recordingStatus() {
		return runtimeRecorder == null ? Map.of("eventsTruncated", false, "debugTimelineTruncated", false, "llmCallsTruncated", false)
			: runtimeRecorder.statusPayload();
	}

	private void writeSummary(EvaluationReport report, String finishedAt) throws IOException {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("id", scenario.id());
		summary.put("name", scenario.name());
		summary.put("startedAt", startedAt);
		if (finishedAt != null) {
			summary.put("finishedAt", finishedAt);
		}
		summary.put("harnessStatus", "IN_MOD");
		summary.put("reportStatus", report.status().name());
		summary.put("message", report.message());
		summary.put("elapsedTicks", report.elapsedTicks());
		summary.put("plannerTurns", report.plannerTurns());
		summary.put("evidenceReviewRequired", report.evidenceReviewRequired());
		summary.put("outputDir", outputDir.toString());
		summary.putAll(recordingStatus());
		writeJson(outputDir.resolve("summary.json"), summary);
	}

	private void writeFinalSnapshots(
		EvaluationReport report,
		EmbodiedAgentRuntime runtime,
		Supplier<Map<String, Object>> evidenceSupplier
	) throws IOException {
		runtimeRecorder.writeFinalSnapshots(runtime);
		writeJson(outputDir.resolve("results-final.json"), Map.of("available", true, "report", report));
		writeJson(outputDir.resolve("evidence-final.json"), evidenceSupplier.get());
	}

	private static boolean terminal(EvaluationStatus status) {
		return status.terminal();
	}

	private static void appendJsonl(Path path, Object value) throws IOException {
		Files.writeString(
			path,
			GSON.toJson(value) + "\n",
			StandardOpenOption.CREATE,
			StandardOpenOption.APPEND
		);
	}

	private static void writeJson(Path path, Object value) throws IOException {
		Files.writeString(
			path,
			GSON.toJson(value) + "\n",
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING
		);
	}

	private static String now() {
		return Instant.now().toString();
	}
}
