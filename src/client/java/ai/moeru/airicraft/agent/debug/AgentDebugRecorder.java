package ai.moeru.airicraft.agent.debug;

import ai.moeru.airicraft.agent.dialogue.DialogueSnapshot;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugKind;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugMessage;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerExecutionResult;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerSessionPhase;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentDebugRecorder {
	private static final int DEFAULT_TIMELINE_CAPACITY = 256;
	private static final int DEFAULT_ATTEMPT_CAPACITY = 8;

	private final int timelineCapacity;
	private final int attemptCapacity;
	private final ArrayDeque<AgentDebugTimelineEntry> timeline = new ArrayDeque<>();
	private final ArrayList<PlannerAttemptDebugSnapshot> plannerAttempts = new ArrayList<>();

	private long nextEntryId = 1L;
	private long nextSubmissionId = 1L;
	private DialogueDebugSnapshot dialogue = DialogueDebugSnapshot.empty();
	private ChatDebugSnapshot chat = ChatDebugSnapshot.empty();
	private CollectResourceTaskDebugSnapshot collectResource = CollectResourceTaskDebugSnapshot.empty();
	private EventPipelineDebugSnapshot eventPipeline = EventPipelineDebugSnapshot.empty();
	private ConversationSourcesDebugSnapshot conversationSources = ConversationSourcesDebugSnapshot.empty();

	public AgentDebugRecorder() {
		this(DEFAULT_TIMELINE_CAPACITY, DEFAULT_ATTEMPT_CAPACITY);
	}

	public AgentDebugRecorder(int timelineCapacity, int attemptCapacity) {
		this.timelineCapacity = Math.max(1, timelineCapacity);
		this.attemptCapacity = Math.max(1, attemptCapacity);
	}

	public synchronized void recordExternalTool(long tick, String callId, String name, String phase, Object evidence) {
		appendTimeline(tick, System.currentTimeMillis(), "external_tool", phase,
			name + " " + phase, Map.of("callId", callId, "toolName", String.valueOf(name)),
			Map.of("evidence", evidence));
	}

	/** Terminal tools have no follow-up request to carry their receipt into flight records. */
	public synchronized void recordTerminalTool(long tick, PlannerToolCall call, String result, boolean imageAttached) {
		appendTimeline(tick, System.currentTimeMillis(), "planner", "terminal_tool_result",
			call.name() + " completed", Map.of("callId", call.id(), "toolName", call.name()),
			Map.of("arguments", call.arguments().deepCopy(), "result", result, "imageAttached", imageAttached));
	}

	public synchronized void recordPlannerSubmission(
		long generation,
		int attempt,
		PlannerSessionPhase phase,
		long timestampMs,
		LlmConversation canonicalConversation
	) {
		long submissionId = nextSubmissionId++;
		plannerAttempts.add(PlannerAttemptDebugSnapshot.submitted(
			submissionId,
			generation,
			attempt,
			phase,
			timestampMs,
			"messages=" + (canonicalConversation == null ? 0 : canonicalConversation.messages().size()),
			canonicalConversation
		));
		trimPlannerAttempts();
		appendTimeline(
			-1L,
			timestampMs,
			"planner",
			"submission",
			"Submitted planner conversation",
			Map.of(
				"submissionId", submissionId,
				"generation", generation,
				"attempt", attempt,
				"phase", phase == null ? "UNKNOWN" : phase.name()
			),
			Map.of(
				"messageCount", canonicalConversation == null ? 0 : canonicalConversation.messages().size(),
				"canonicalConversation", canonicalConversation == null ? List.of() : canonicalConversation.messages()
			)
		);
	}

	public synchronized void recordPlannerCompletion(PlannerExecutionResult result) {
		recordPlannerCompletion(result, result != null && result.succeeded() ? "SUCCEEDED" : "FAILED", null);
	}

	public synchronized void recordPlannerDiscarded(PlannerExecutionResult result) {
		recordPlannerCompletion(result, "DISCARDED", "discarded");
	}

	private void recordPlannerCompletion(PlannerExecutionResult result, String status, String eventKind) {
		if (result == null) {
			return;
		}
		int index = findAttempt(result.generation(), result.attempt(), result.phase());
		if (index < 0) {
			return;
		}
		String failureType = result.failureType() == null ? null : result.failureType().name();
		String summary = "DISCARDED".equals(status) ? "PLANNER OFF" : summarizePlannerResult(result);
		PlannerResponse response = result.response();
		PlannerAttemptDebugSnapshot current = plannerAttempts.get(index);
		plannerAttempts.set(index, current.completed(
			System.currentTimeMillis(),
			status,
			failureType,
			summary,
			response,
			result.failureMessage()
		));
		appendTimeline(
			result.request() == null ? -1L : result.request().tick(),
			System.currentTimeMillis(),
			"planner",
			eventKind == null ? (result.succeeded() ? "completion" : "failure") : eventKind,
			summary,
			Map.of(
				"submissionId", current.submissionId(),
				"generation", result.generation(),
				"attempt", result.attempt(),
				"phase", result.phase() == null ? "UNKNOWN" : result.phase().name()
			),
			result.succeeded()
				? plannerSuccessPayload(response)
				: Map.of("failureType", failureType == null ? "" : failureType, "failureMessage", result.failureMessage() == null ? "" : result.failureMessage())
		);
	}

	public synchronized void recordConversationSources(
		PlannerConversationDebugSnapshot canonicalConversation,
		PlannerConversationDebugSnapshot projectedConversation
	) {
		PlannerConversationDebugSnapshot safeCanonical = canonicalConversation == null ? PlannerConversationDebugSnapshot.empty() : canonicalConversation;
		PlannerConversationDebugSnapshot safeProjected = projectedConversation == null ? PlannerConversationDebugSnapshot.empty() : projectedConversation;
		conversationSources = new ConversationSourcesDebugSnapshot(
			safeCanonical,
			safeProjected,
			safeCanonical.messages().size(),
			safeProjected.messages().size(),
			countKind(safeCanonical, PlannerConversationDebugKind.USER_TURN),
			countKind(safeProjected, PlannerConversationDebugKind.USER_TURN),
			hiddenKinds(safeCanonical, safeProjected)
		);
	}

	public synchronized void recordDialogueState(DialogueSnapshot snapshot) {
		if (snapshot == null) {
			dialogue = DialogueDebugSnapshot.empty();
			return;
		}
		dialogue = new DialogueDebugSnapshot(
			snapshot.pendingReply(),
			snapshot.pendingReplyReason(),
			snapshot.lastResponse(),
			snapshot.degraded(),
			snapshot.consecutiveFailureCount(),
			snapshot.lastFailureType(),
			snapshot.lastFailureTick()
		);
	}

	public synchronized void recordChatAttempt(long tick, String text, String source, boolean reusedPriorResponse) {
		chat = new ChatDebugSnapshot(
			tick,
			text,
			source,
			reusedPriorResponse,
			false,
			chat.lastEmissionTick(),
			chat.lastEmissionText(),
			chat.lastEmissionSource()
		);
		appendTimeline(
			tick,
			System.currentTimeMillis(),
			"chat",
			"attempt",
			source == null ? "Attempting chat emission" : "Attempting chat emission from " + source,
			Map.of(),
			Map.of(
				"text", text == null ? "" : text,
				"source", source == null ? "" : source,
				"reusedPriorResponse", reusedPriorResponse
			)
		);
	}

	public synchronized void recordChatResult(long tick, String text, String source, boolean reusedPriorResponse, boolean succeeded) {
		chat = new ChatDebugSnapshot(
			chat.lastAttemptTick(),
			chat.lastAttemptText(),
			chat.lastAttemptSource(),
			reusedPriorResponse,
			succeeded,
			succeeded ? tick : chat.lastEmissionTick(),
			succeeded ? text : chat.lastEmissionText(),
			succeeded ? source : chat.lastEmissionSource()
		);
		appendTimeline(
			tick,
			System.currentTimeMillis(),
			"chat",
			succeeded ? "sent" : "send_failed",
			succeeded ? "Chat emitted" : "Chat emission failed",
			Map.of(),
			Map.of(
				"text", text == null ? "" : text,
				"source", source == null ? "" : source,
				"reusedPriorResponse", reusedPriorResponse
			)
		);
	}

	/** Executor measurements travel with terminal events but stay out of planner-visible events. */
	public synchronized void recordTaskDiagnostics(
		long tick,
		String taskId,
		String goalType,
		String terminalState,
		Map<String, Object> diagnostics
	) {
		appendTimeline(tick, System.currentTimeMillis(), "task", "terminal_diagnostics",
			goalType + " " + terminalState, Map.of("taskId", taskId == null ? "" : taskId), diagnostics);
	}

	public synchronized void recordCollectResourceProbe(CollectResourceTaskDebugSnapshot snapshot) {
		CollectResourceTaskDebugSnapshot safeSnapshot = snapshot == null ? CollectResourceTaskDebugSnapshot.empty() : snapshot;
		if (Objects.equals(collectResource, safeSnapshot)) {
			return;
		}
		collectResource = safeSnapshot;
		appendTimeline(
			safeSnapshot.updatedTick(),
			System.currentTimeMillis(),
			"task",
			"collect_resource_probe",
			safeSnapshot.active() ? "Collect-resource progress updated" : "Collect-resource probe cleared",
			Map.of("jobId", safeSnapshot.jobId() == null ? "" : safeSnapshot.jobId()),
			collectResourcePayload(safeSnapshot)
		);
	}

	public synchronized void recordEventRouting(
		long tick,
		long timestampMs,
		long rawEventSeqNo,
		String eventType,
		String decisionEffect,
		boolean emitSemantic,
		boolean emitTrigger,
		long plannerEventSeqNo,
		String triggerType
	) {
		eventPipeline = new EventPipelineDebugSnapshot(
			Math.max(eventPipeline.rawLatestSeqNo(), rawEventSeqNo),
			Math.max(eventPipeline.plannerLatestSeqNo(), plannerEventSeqNo),
			eventPipeline.rawDroppedCount(),
			eventPipeline.plannerDroppedCount(),
			Math.max(eventPipeline.lastProcessedRawSeqNo(), rawEventSeqNo),
			rawEventSeqNo,
			plannerEventSeqNo,
			eventType,
			decisionEffect,
			triggerType,
			emitSemantic,
			emitTrigger
		);
		appendTimeline(
			tick,
			timestampMs,
			"event_pipeline",
			"route",
			"Routed raw event " + (eventType == null ? "-" : eventType),
			Map.of("rawEventSeqNo", rawEventSeqNo, "plannerEventSeqNo", plannerEventSeqNo),
			Map.of(
				"eventType", eventType == null ? "" : eventType,
				"decisionEffect", decisionEffect == null ? "" : decisionEffect,
				"emitSemantic", emitSemantic,
				"emitTrigger", emitTrigger,
				"triggerType", triggerType == null ? "" : triggerType
			)
		);
	}

	public synchronized void updateEventPipelineBufferState(long rawLatestSeqNo, long plannerLatestSeqNo, long rawDroppedCount, long plannerDroppedCount, long lastProcessedRawSeqNo) {
		eventPipeline = new EventPipelineDebugSnapshot(
			rawLatestSeqNo,
			plannerLatestSeqNo,
			rawDroppedCount,
			plannerDroppedCount,
			lastProcessedRawSeqNo,
			eventPipeline.lastRawEventSeqNo(),
			eventPipeline.lastPlannerEventSeqNo(),
			eventPipeline.lastEventType(),
			eventPipeline.lastDecisionEffect(),
			eventPipeline.lastTriggerType(),
			eventPipeline.lastEmitSemantic(),
			eventPipeline.lastEmitTrigger()
		);
	}

	public synchronized DialogueDebugSnapshot dialogueSnapshot() {
		return dialogue;
	}

	public synchronized ChatDebugSnapshot chatSnapshot() {
		return chat;
	}

	public synchronized CollectResourceTaskDebugSnapshot collectResourceSnapshot() {
		return collectResource;
	}

	public synchronized EventPipelineDebugSnapshot eventPipelineSnapshot() {
		return eventPipeline;
	}

	public synchronized ConversationSourcesDebugSnapshot conversationSourcesSnapshot() {
		return conversationSources;
	}

	public synchronized List<PlannerAttemptDebugSnapshot> plannerAttempts() {
		return List.copyOf(plannerAttempts);
	}

	public synchronized List<AgentDebugTimelineEntry> timelineTail() {
		return List.copyOf(new ArrayList<>(timeline));
	}

	public synchronized AgentDebugTimelineQueryResult queryTimeline(Long sinceEntryId) {
		long oldestEntryId = timeline.isEmpty() ? nextEntryId : timeline.peekFirst().entryId();
		long latestEntryId = timeline.isEmpty() ? 0L : timeline.peekLast().entryId();
		long effectiveSince = sinceEntryId == null ? 0L : sinceEntryId.longValue();
		ArrayList<AgentDebugTimelineEntry> matches = new ArrayList<>();
		for (AgentDebugTimelineEntry entry : timeline) {
			if (entry.entryId() > effectiveSince) {
				matches.add(entry);
			}
		}
		boolean truncated = sinceEntryId != null && oldestEntryId > 1L && sinceEntryId < oldestEntryId - 1L;
		return new AgentDebugTimelineQueryResult(oldestEntryId, latestEntryId, truncated, matches);
	}

	private void appendTimeline(
		long tick,
		long timestampMs,
		String domain,
		String action,
		String summary,
		Map<String, Object> correlation,
		Map<String, Object> payload
	) {
		timeline.addLast(new AgentDebugTimelineEntry(
			nextEntryId++,
			tick,
			timestampMs,
			domain,
			action,
			summary,
			correlation,
			payload
		));
		while (timeline.size() > timelineCapacity) {
			timeline.removeFirst();
		}
	}

	private int findAttempt(long generation, int attempt, PlannerSessionPhase phase) {
		String phaseName = phase == null ? "UNKNOWN" : phase.name();
		for (int index = plannerAttempts.size() - 1; index >= 0; index--) {
			PlannerAttemptDebugSnapshot candidate = plannerAttempts.get(index);
			if (candidate.generation() == generation && candidate.attempt() == attempt && Objects.equals(candidate.phase(), phaseName)) {
				return index;
			}
		}
		return -1;
	}

	private void trimPlannerAttempts() {
		while (plannerAttempts.size() > attemptCapacity) {
			plannerAttempts.remove(0);
		}
	}

	private static int countKind(PlannerConversationDebugSnapshot snapshot, PlannerConversationDebugKind kind) {
		int count = 0;
		for (PlannerConversationDebugMessage message : snapshot.messages()) {
			if (message.kind() == kind) {
				count++;
			}
		}
		return count;
	}

	private static List<String> hiddenKinds(PlannerConversationDebugSnapshot canonical, PlannerConversationDebugSnapshot projected) {
		EnumMap<PlannerConversationDebugKind, Integer> canonicalCounts = kindCounts(canonical);
		EnumMap<PlannerConversationDebugKind, Integer> projectedCounts = kindCounts(projected);
		ArrayList<String> hidden = new ArrayList<>();
		for (PlannerConversationDebugKind kind : PlannerConversationDebugKind.values()) {
			if (canonicalCounts.getOrDefault(kind, 0) > projectedCounts.getOrDefault(kind, 0)) {
				hidden.add(kind.name());
			}
		}
		return List.copyOf(hidden);
	}

	private static EnumMap<PlannerConversationDebugKind, Integer> kindCounts(PlannerConversationDebugSnapshot snapshot) {
		EnumMap<PlannerConversationDebugKind, Integer> counts = new EnumMap<>(PlannerConversationDebugKind.class);
		for (PlannerConversationDebugMessage message : snapshot.messages()) {
			counts.merge(message.kind(), 1, Integer::sum);
		}
		return counts;
	}

	private static String summarizePlannerResult(PlannerExecutionResult result) {
		if (result == null) {
			return "";
		}
		if (!result.succeeded()) {
			return (result.failureType() == null ? "FAILED" : result.failureType().name())
				+ ": "
				+ (result.failureMessage() == null ? "" : result.failureMessage());
		}
		PlannerResponse response = result.response();
		if (response == null) {
			return "Planner completed without response";
		}
		String reply = response.replyText() == null ? "" : response.replyText();
		if (reply.isBlank() && response.toolCalls() != null && response.toolCalls().size() > 1) {
			reply = "Tool calls: " + String.join(",", response.toolCalls().stream()
				.map(toolCall -> toolCall == null ? "" : toolCall.name())
				.filter(name -> name != null && !name.isBlank())
				.toList());
		}
		if (reply.isBlank() && response.toolCall() != null) {
			reply = "Tool call: " + response.toolCall().name();
		}
		if (reply.isBlank() && response.toolRequest() != null) {
			reply = "Tool request: " + response.toolRequest().type();
		}
		return reply.isBlank() ? "Planner completed" : reply;
	}

	private static Map<String, Object> plannerSuccessPayload(PlannerResponse response) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		if (response != null) {
			payload.put("plannerResponse", response);
		}
		return payload;
	}

	private static Map<String, Object> collectResourcePayload(CollectResourceTaskDebugSnapshot snapshot) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("resourceKind", snapshot.resourceKind() == null ? "" : snapshot.resourceKind());
		payload.put("baselineResourceCount", snapshot.baselineResourceCount());
		payload.put("currentResourceCount", snapshot.currentResourceCount());
		payload.put("inventoryDelta", snapshot.inventoryDelta());
		payload.put("targetQuantity", snapshot.targetQuantity());
		payload.put("collected", snapshot.collected());
		payload.put("remaining", snapshot.remaining());
		payload.put("activeJobStatus", snapshot.activeJobStatus() == null ? "" : snapshot.activeJobStatus());
		payload.put("primitiveExecutionState", snapshot.primitiveExecutionState() == null ? "" : snapshot.primitiveExecutionState());
		payload.put("blockedReason", snapshot.blockedReason() == null ? "" : snapshot.blockedReason());
		payload.put("completionReason", snapshot.completionReason() == null ? "" : snapshot.completionReason());
		return payload;
	}
}
