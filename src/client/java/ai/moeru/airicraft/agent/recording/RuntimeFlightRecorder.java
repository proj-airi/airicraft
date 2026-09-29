package ai.moeru.airicraft.agent.recording;

import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import ai.moeru.airicraft.agent.attention.AttentionDecisionLog;
import ai.moeru.airicraft.agent.debug.LlmFlightRecordQueryResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/** Shared runtime evidence files for evaluation and automatic playtests. */
public final class RuntimeFlightRecorder {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private final Path outputDir;
	private Long latestEventSeqNo;
	private Long latestTimelineEntryId;
	private Long latestLlmSequenceId;
	private Long latestAttentionSeqNo;
	private Long latestSalienceStep;
	private boolean salienceStepsTruncated;
	private boolean eventsTruncated;
	private boolean timelineTruncated;
	private boolean llmCallsTruncated;
	private boolean attentionDecisionsTruncated;
	private final TreeSet<Long> pendingLlmCalls = new TreeSet<>();

	public RuntimeFlightRecorder(Path outputDir) throws IOException {
		this.outputDir = outputDir.toAbsolutePath().normalize();
		Files.createDirectories(this.outputDir);
	}

	public void recordTick(EmbodiedAgentRuntime runtime) throws IOException {
		String collectedAt = Instant.now().toString();
		drainEvents(runtime, collectedAt);
		drainTimeline(runtime, collectedAt);
		drainLlmCalls(runtime::llmFlightRecords, collectedAt);
		drainAttentionDecisions(runtime.attentionDecisionLog(), collectedAt);
		drainSalienceSteps(runtime.salienceStepLog(), collectedAt);
	}

	public Map<String, Object> statusPayload() {
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("latestEventSeqNo", latestEventSeqNo);
		status.put("debugTimelineLatestEntryId", latestTimelineEntryId);
		status.put("llmCallsLatestSequenceId", latestLlmSequenceId);
		status.put("eventsTruncated", eventsTruncated);
		status.put("debugTimelineTruncated", timelineTruncated);
		status.put("llmCallsTruncated", llmCallsTruncated);
		status.put("attentionDecisionsLatestSeqNo", latestAttentionSeqNo);
		status.put("attentionDecisionsTruncated", attentionDecisionsTruncated);
		status.put("salienceStepsTruncated", salienceStepsTruncated);
		return status;
	}

	public void writeFinalSnapshots(EmbodiedAgentRuntime runtime) throws IOException {
		runtime.finalizePlannerCallRecordsForEvaluation();
		writeJson(outputDir.resolve("agent-status-final.json"), Map.of(
			"available", true,
			"session", runtime.sessionSnapshot(),
			"task", runtime.taskSnapshot(),
			"taskExecution", runtime.taskExecutionSnapshot(),
			"missionExecution", runtime.missionExecutionSnapshot(),
			"activeJob", runtime.activeJob(),
			"degraded", runtime.isDegraded()
		));
		writeJson(outputDir.resolve("agent-events-final.json"), runtime.recentEvents(null));
		writeJson(outputDir.resolve("agent-debug-timeline-final.json"), runtime.debugTimeline(null));
		writeJson(outputDir.resolve("agent-debug-llm-calls-final.json"), runtime.llmFlightRecords(null));
		writeJsonlSnapshot(outputDir.resolve("planner-calls.jsonl"), runtime.plannerCallRecords());
		writeJson(outputDir.resolve("world-evidence-final.json"), runtime.currentWorldEvidence());
	}

	private void drainEvents(EmbodiedAgentRuntime runtime, String collectedAt) throws IOException {
		var result = runtime.recentEvents(latestEventSeqNo);
		latestEventSeqNo = result.latestSeqNo();
		eventsTruncated = eventsTruncated || result.truncated();
		for (var event : result.events()) {
			appendJsonl(outputDir.resolve("events.jsonl"), Map.of("collectedAt", collectedAt, "event", event));
		}
	}

	private void drainTimeline(EmbodiedAgentRuntime runtime, String collectedAt) throws IOException {
		var result = runtime.debugTimeline(latestTimelineEntryId);
		latestTimelineEntryId = result.latestEntryId();
		timelineTruncated = timelineTruncated || result.truncated();
		for (var entry : result.entries()) {
			appendJsonl(outputDir.resolve("debug-timeline.jsonl"), Map.of("collectedAt", collectedAt, "entry", entry));
		}
	}

	/** Decisions with their inputs, so {@code AttentionReplay} can re-decide the run. */
	void drainAttentionDecisions(AttentionDecisionLog log, String collectedAt) throws IOException {
		var result = log.query(latestAttentionSeqNo);
		attentionDecisionsTruncated = attentionDecisionsTruncated || result.truncated();
		for (var decision : result.decisions()) {
			appendJsonl(outputDir.resolve("attention-decisions.jsonl"), Map.of("collectedAt", collectedAt, "decision", decision));
			latestAttentionSeqNo = decision.seqNo();
		}
	}

	/** Salience steps with their input and prior state, so {@code SalienceReplay} can re-run them. */
	void drainSalienceSteps(ai.moeru.airicraft.agent.perception.SalienceStepLog log, String collectedAt) throws IOException {
		var result = log.query(latestSalienceStep);
		salienceStepsTruncated = salienceStepsTruncated || result.truncated();
		for (var entry : result.entries()) {
			var step = entry.step();
			var record = new LinkedHashMap<String, Object>();
			record.put("tick", step.tick());
			record.put("module", step.module());
			record.put("input", step.input());
			record.put("stateBefore", step.stateBefore());
			record.put("percepts", step.percepts());
			record.put("drops", step.drops());
			appendJsonl(outputDir.resolve("salience-steps.jsonl"), Map.of("collectedAt", collectedAt, "sequence", entry.sequence(), "step", record));
			latestSalienceStep = entry.sequence();
		}
	}

	void drainLlmCalls(Function<Long, LlmFlightRecordQueryResult> query, String collectedAt) throws IOException {
		// Responses update the original sequence in place. Keep polling unfinished calls;
		// append their terminal version before advancing past them, including out-of-order completion.
		Long since = latestLlmSequenceId;
		if (!pendingLlmCalls.isEmpty()) since = pendingLlmCalls.first() - 1L;
		var result = query.apply(since);
		llmCallsTruncated = llmCallsTruncated || result.truncated();
		for (var record : result.records()) {
			boolean terminal = record.status().equals("COMPLETED") || record.status().equals("FAILED");
			boolean newCall = latestLlmSequenceId == null || record.sequenceId() > latestLlmSequenceId;
			if (newCall || terminal && pendingLlmCalls.contains(record.sequenceId())) {
				appendJsonl(outputDir.resolve("llm-calls.jsonl"), Map.of("collectedAt", collectedAt, "record", record));
			}
			if (terminal) pendingLlmCalls.remove(record.sequenceId());
			else pendingLlmCalls.add(record.sequenceId());
		}
		pendingLlmCalls.removeIf(sequence -> sequence < result.oldestSequenceId());
		latestLlmSequenceId = result.latestSequenceId();
	}

	private static void appendJsonl(Path path, Object value) throws IOException {
		try (var writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
			GSON.toJson(value, writer);
			writer.newLine();
		}
	}

	private static void writeJson(Path path, Object value) throws IOException {
		try (var writer = Files.newBufferedWriter(path)) {
			GSON.toJson(value, writer);
			writer.newLine();
		}
	}

	private static void writeJsonlSnapshot(Path path, List<?> values) throws IOException {
		try (var writer = Files.newBufferedWriter(path)) {
			for (Object value : values) {
				GSON.toJson(value, writer);
				writer.newLine();
			}
		}
	}

}
