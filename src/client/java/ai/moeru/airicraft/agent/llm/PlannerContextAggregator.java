package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.dialogue.DialogueTurn;
import com.google.gson.JsonElement;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PlannerContextAggregator {

	private final Clock clock;
	private final ZoneId zoneId;
	private final int compactionTriggerTokens;
	private final PlannerVisionMode visionMode;
	private final PlannerToolRegistry toolRegistry;
	private final boolean backendManagedHistory;
	private final String characterPrompt;

	private String fixedSystemPrompt;
	private boolean decisionContextEnabled;
	public void useDecisionContext() { decisionContextEnabled = true; }
	private LlmConversation retainedConversation;

	private PlannerContextState state = PlannerContextState.initial();
	private PlannerContextSnapshot lastFrozenSnapshot;

	public PlannerContextAggregator(Clock clock, int compactionTriggerTokens, PlannerVisionMode visionMode) {
		this(clock, compactionTriggerTokens, visionMode, PlannerToolRegistry.empty());
	}

	public PlannerContextAggregator(
		Clock clock,
		int compactionTriggerTokens,
		PlannerVisionMode visionMode,
		PlannerToolRegistry toolRegistry
	) {
		this(clock, compactionTriggerTokens, visionMode, toolRegistry, false);
	}

	public PlannerContextAggregator(
		Clock clock,
		int compactionTriggerTokens,
		PlannerVisionMode visionMode,
		PlannerToolRegistry toolRegistry,
		boolean backendManagedHistory
	) {
		this(clock, compactionTriggerTokens, visionMode, toolRegistry, backendManagedHistory, null);
	}

	/** A null character prompt uses the built-in character without an in-game name. */
	public PlannerContextAggregator(
		Clock clock,
		int compactionTriggerTokens,
		PlannerVisionMode visionMode,
		PlannerToolRegistry toolRegistry,
		boolean backendManagedHistory,
		String characterPrompt
	) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.zoneId = clock.getZone();
		this.compactionTriggerTokens = compactionTriggerTokens;
		this.visionMode = Objects.requireNonNull(visionMode, "visionMode");
		this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
		this.backendManagedHistory = backendManagedHistory;
		this.characterPrompt = characterPrompt;
	}

	public boolean compactionPending() {
		return state.compactionPending();
	}

	public boolean hasQueuedTriggers() {
		return !state.queuedTriggers().isEmpty();
	}

	/** A player's direct guidance waits in the queue, for example after the supersede budget held it back. */
	public boolean hasQueuedDirectGuidance() {
		return state.queuedTriggers().stream().anyMatch(PlannerTrigger::maySupersedeLaunchedTurn);
	}

	public int queuedTriggerCount() {
		return state.queuedTriggers().size();
	}

	public LlmUsageSnapshot lastObservedUsage() {
		return state.lastObservedUsage();
	}

	public PlannerContextDebugSnapshot debugSnapshot() {
		return new PlannerContextDebugSnapshot(
			compactionTriggerTokens,
			state.compactionPending(),
			state.acceptedHistoryTape().size(),
			lastFrozenSnapshot == null ? 0 : lastFrozenSnapshot.plannerConversation().messages().size(),
			state.queuedTriggers().size(),
			state.lastAcceptedTimeContextAtMs(),
			state.lastObservedUsage(),
			state.lastAcceptedAmbientContext(),
			state.activeCheckpoint()
		);
	}

	public void enqueueTrigger(PlannerTrigger trigger) {
		Objects.requireNonNull(trigger, "trigger");
		state = PlannerContextReducer.enqueueTrigger(state, trigger.withSeqNo(state.nextTriggerSeqNo()));
	}

	public void invalidateIdleThinkTriggers() {
		state = PlannerContextReducer.invalidateIdleThinkTriggers(state);
	}

	public PlannerContextSnapshot freezePlannerSnapshot(PlannerRequest request) {
		Objects.requireNonNull(request, "request");
		if (state.queuedTriggers().isEmpty()) {
			return null;
		}

		long nowMs = request.timestampMs();
		PlannerAmbientContext ambientContext = PlannerAmbientContext.fromRequest(request);
		long renderedTimeContextAtMs = PlannerContextPolicy.shouldInjectTimeBeacon(state.lastAcceptedTimeContextAtMs(), nowMs)
			? nowMs
			: -1L;
		List<LlmChatMessage> snapshotNotices = renderSnapshotNotices(request, ambientContext, renderedTimeContextAtMs);

		PlannerTriggerBatch triggerBatch = PlannerTriggerBatch.of(state.queuedTriggers());
		PlannerRequest combinedRequest = request.withTriggerBatch(triggerBatch);
		PlannerContextSnapshot snapshot = new PlannerContextSnapshot(
			combinedRequest,
			PlannerSnapshotMode.TRIGGERED,
			triggerBatch,
			composeConversation(nowMs, snapshotNotices, decisionContextEnabled
				? triggerBatch.toObservedMessages() : List.of(triggerBatch.toTerminalMessage())),
			ambientContext,
			renderedTimeContextAtMs
		);
		lastFrozenSnapshot = snapshot;
		return snapshot;
	}

	public void commitAcceptedTriggerBatch(PlannerContextSnapshot snapshot) {
		if (snapshot == null) {
			return;
		}
		state = PlannerContextReducer.commitAcceptedSnapshot(state, snapshot);
		retainConversation(snapshot.plannerConversation());
		if (backendManagedHistory) {
			state = withoutAcceptedProviderHistory(state);
		}
		lastFrozenSnapshot = null;
	}

	public void discardSnapshot(PlannerContextSnapshot snapshot) {
		if (snapshot == null) {
			return;
		}
		state = PlannerContextReducer.discardSnapshot(state, snapshot);
		lastFrozenSnapshot = null;
	}

	public void discardPending() {
		state = PlannerContextReducer.discardPending(state);
		lastFrozenSnapshot = null;
	}

	public void dropSupersededGeneration(PlannerContextSnapshot snapshot) {
		if (snapshot == null) {
			return;
		}
		if (lastFrozenSnapshot != null && lastFrozenSnapshot.equals(snapshot)) {
			lastFrozenSnapshot = null;
		}
	}

	LlmConversation retainedToolContext() { return retainedConversation == null ? LlmConversation.of(List.of()) : retainedConversation; }

	/**
	 * What the model would see if a request went out right now: the retained wire
	 * conversation (accepted replies, tool envelopes and checkpoints applied), or the
	 * rebuilt accepted-history base for client-managed history without a fixed prefix.
	 * Null when history is provider-managed and nothing is retained locally.
	 */
	public LlmConversation currentRetainedConversation(long anchorTimeMs) {
		if (retainedConversation != null) {
			return retainedConversation;
		}
		if (backendManagedHistory) {
			return null;
		}
		ArrayList<LlmChatMessage> messages = new ArrayList<>();
		messages.add(LlmChatMessage.system(systemPrompt()));
		if (state.activeCheckpoint() != null) {
			messages.add(LlmChatMessage.user(state.activeCheckpoint().renderMessage(), LlmMessageKind.CHECKPOINT));
		}
		messages.addAll(renderAcceptedHistory(anchorTimeMs));
		return LlmConversation.of(messages);
	}

	public LlmConversation buildPlannerConversation(PlannerRequest request) {
		Objects.requireNonNull(request, "request");
		if (request.triggerBatch() != null) {
			for (PlannerTrigger trigger : request.triggerBatch().triggers()) {
				enqueueTrigger(trigger);
			}
		}
		PlannerContextSnapshot snapshot = freezePlannerSnapshot(request);
		return snapshot == null ? composeConversation(request.timestampMs(), List.of(), List.of()) : snapshot.plannerConversation();
	}

	public LlmConversation buildPlannerFollowUpConversation(PlannerContextSnapshot snapshot, JsonElement priorAssistantRawContent, String toolResult) {
		if (snapshot == null) {
			throw new IllegalStateException("No planner context snapshot");
		}
		LlmConversation conversation = followUpBase(snapshot);
		if (backendManagedHistory) {
			return conversation.withAppended(LlmChatMessage.user(
				"Tool result: " + (toolResult == null || toolResult.isBlank() ? "none" : toolResult),
				LlmMessageKind.TOOL_RESULT
			));
		}
		if (priorAssistantRawContent != null) {
			conversation = conversation.withAppended(LlmChatMessage.assistant(
				OpenAiCompatibleMessageContent.extractVisibleText(priorAssistantRawContent),
				priorAssistantRawContent
			));
		}
		return conversation.withAppended(LlmChatMessage.user(
			"Tool result: " + (toolResult == null || toolResult.isBlank() ? "none" : toolResult),
			LlmMessageKind.TOOL_RESULT
		));
	}

	public LlmConversation buildPlannerFollowUpConversation(PlannerContextSnapshot snapshot, PlannerToolCall toolCall, String toolResult) {
		if (snapshot == null) {
			throw new IllegalStateException("No planner context snapshot");
		}
		if (toolCall == null) {
			throw new IllegalArgumentException("toolCall");
		}
		return buildPlannerFollowUpConversation(snapshot, List.of(toolCall), List.of(toolResult));
	}

	public LlmConversation buildPlannerFollowUpConversation(
		PlannerContextSnapshot snapshot,
		List<PlannerToolCall> toolCalls,
		List<String> toolResults
	) {
		if (snapshot == null) {
			throw new IllegalStateException("No planner context snapshot");
		}
		if (toolCalls == null || toolCalls.isEmpty()) {
			throw new IllegalArgumentException("toolCalls");
		}
		LlmConversation conversation = followUpBase(snapshot);
		if (!backendManagedHistory) {
			conversation = conversation.withAppended(LlmChatMessage.assistantToolCalls("", toolCalls));
		}
		for (int index = 0; index < toolCalls.size(); index++) {
			PlannerToolCall toolCall = toolCalls.get(index);
			String toolResult = toolResults == null || index >= toolResults.size() ? "" : toolResults.get(index);
			conversation = backendManagedHistory
				? conversation.withAppended(LlmChatMessage.user(
					"Tool result for " + toolCall.name() + ": " + toolResultContent(toolResult),
					LlmMessageKind.TOOL_RESULT
				))
				: conversation.withAppended(LlmChatMessage.tool(toolCall.id(), toolResultContent(toolResult)));
		}
		return conversation;
	}

	public LlmConversation buildPlannerFollowUpConversation(
		PlannerContextSnapshot snapshot,
		JsonElement priorAssistantRawContent,
		String toolResult,
		LlmImageAttachment imageAttachment
	) {
		if (snapshot == null) {
			throw new IllegalStateException("No planner context snapshot");
		}
		LlmConversation conversation = followUpBase(snapshot);
		if (backendManagedHistory) {
			return conversation.withAppended(
				LlmChatMessage.userWithImage(
					toolResult == null || toolResult.isBlank() ? "Tool result: image attached." : toolResult,
					LlmMessageKind.TOOL_RESULT,
					imageAttachment
				)
			);
		}
		if (priorAssistantRawContent != null) {
			conversation = conversation.withAppended(LlmChatMessage.assistant(
				OpenAiCompatibleMessageContent.extractVisibleText(priorAssistantRawContent),
				priorAssistantRawContent
			));
		}
		return conversation.withAppended(
			LlmChatMessage.userWithImage(
				toolResult == null || toolResult.isBlank() ? "Tool result: image attached." : toolResult,
				LlmMessageKind.TOOL_RESULT,
				imageAttachment
			)
			);
	}

	public LlmConversation buildPlannerFollowUpConversation(
		PlannerContextSnapshot snapshot,
		PlannerToolCall toolCall,
		String toolResult,
		LlmImageAttachment imageAttachment
	) {
		if (backendManagedHistory && imageAttachment != null) {
			return followUpBase(snapshot).withAppended(
				LlmChatMessage.userWithImage(
					toolResult == null || toolResult.isBlank()
						? "Tool result for " + toolCall.name() + ": image attached."
						: "Tool result for " + toolCall.name() + ": " + toolResult,
					LlmMessageKind.TOOL_RESULT,
					imageAttachment
				)
			);
		}
		LlmConversation conversation = buildPlannerFollowUpConversation(snapshot, toolCall, toolResult);
		if (imageAttachment == null) {
			return conversation;
		}
		return conversation.withAppended(
			LlmChatMessage.userWithImage(
				toolResult == null || toolResult.isBlank() ? "Tool result: image attached." : toolResult,
				LlmMessageKind.TOOL_RESULT,
				imageAttachment
			)
		);
	}

	public LlmConversation buildPlannerFollowUpConversation(String toolResult) {
		if (lastFrozenSnapshot == null) {
			throw new IllegalStateException("No frozen planner conversation");
		}
		return buildPlannerFollowUpConversation(lastFrozenSnapshot, (JsonElement) null, toolResult);
	}

	public LlmConversation buildPlannerFollowUpConversation(String toolResult, LlmImageAttachment imageAttachment) {
		if (lastFrozenSnapshot == null) {
			throw new IllegalStateException("No frozen planner conversation");
		}
		return buildPlannerFollowUpConversation(lastFrozenSnapshot, (JsonElement) null, toolResult, imageAttachment);
	}

	public LlmConversation buildCompactionConversation() {
		return composeConversation(
			clock.millis(),
			List.of(),
			List.of(LlmChatMessage.user(PlannerPromptPolicy.compactionInstruction(), LlmMessageKind.TASK))
		);
	}

	public void recordAgentTurn(DialogueTurn turn, JsonElement rawAssistantContent) {
		Objects.requireNonNull(turn, "turn");
		if (backendManagedHistory) {
			return;
		}
		state = PlannerContextReducer.recordAcceptedAssistantTurn(state, turn, rawAssistantContent);
	}

	public void recordAcceptedToolExchange(JsonElement assistantRawContent, String toolResultText, long tick, long timestampMs) {
		if (backendManagedHistory || assistantRawContent == null) {
			return;
		}
		state = PlannerContextReducer.recordAcceptedToolExchange(state, assistantRawContent, toolResultText, tick, timestampMs);
	}

	public void recordAcceptedToolExchange(PlannerToolCall toolCall, String toolResultText, long tick, long timestampMs) {
		if (backendManagedHistory || toolCall == null) {
			return;
		}
		state = PlannerContextReducer.recordAcceptedToolExchange(state, toolCall, toolResultText, tick, timestampMs);
	}

	public void recordAcceptedToolExchange(List<PlannerToolCall> toolCalls, List<String> toolResultTexts, long tick, long timestampMs) {
		if (backendManagedHistory || toolCalls == null || toolCalls.isEmpty()) {
			return;
		}
		state = PlannerContextReducer.recordAcceptedToolExchange(state, toolCalls, toolResultTexts, tick, timestampMs);
	}

	public void recordAgentTurn(DialogueTurn turn) {
		recordAgentTurn(turn, null);
	}

	public void recordUsage(LlmUsageSnapshot usage) {
		state = backendManagedHistory
			? PlannerContextReducer.updateObservedUsage(state, usage, false)
			: PlannerContextReducer.updateUsage(state, usage, compactionTriggerTokens);
	}

	public void recordObservedUsage(LlmUsageSnapshot usage) {
		state = PlannerContextReducer.updateObservedUsage(state, usage, state.compactionPending());
	}

	public void applyCheckpoint(CompactionCheckpoint checkpoint) {
		if (microCompactor != null) microCompactor.reset();
		state = PlannerContextReducer.clearCompactionPending(state, checkpoint, clock.millis());
		if (toolRegistry.hasFixedPrefix()) retainedConversation = LlmConversation.of(List.of(
			LlmChatMessage.system(systemPrompt()), LlmChatMessage.user(checkpoint.renderMessage(), LlmMessageKind.CHECKPOINT)));
		lastFrozenSnapshot = null;
	}

	public void onCompactionFailure() {
		lastFrozenSnapshot = null;
	}

	public void clear() {
		if (microCompactor != null) microCompactor.reset();
		state = PlannerContextState.initial();
		retainedConversation = null;
		lastFrozenSnapshot = null;
	}

	/** Keep the actual accepted wire conversation, including frozen notices and raw tool envelopes. */
	private PlannerMicroCompactor microCompactor;
	public void configureMicroCompaction(PlannerMicroCompactor service) { microCompactor = service; }
	public void refreshMicroCompaction() {
		if (retainedConversation != null && microCompactor != null) retainedConversation = microCompactor.update(retainedConversation);
	}
	public boolean microCompactionInFlight() { return microCompactor != null && microCompactor.hasInFlight(); }
	public void closeMicroCompaction() { if (microCompactor != null) microCompactor.close(); }
	public LlmConversation retainConversation(LlmConversation conversation) {
		if (microCompactor != null) conversation = microCompactor.update(conversation);
		if (toolRegistry.hasFixedPrefix() && !backendManagedHistory) retainedConversation = conversation;
		return conversation;
	}

	private String systemPrompt() {
		if (!toolRegistry.hasFixedPrefix()) return PlannerPromptPolicy.systemPrompt(visionMode, toolRegistry, characterPrompt);
		if (fixedSystemPrompt == null) fixedSystemPrompt = PlannerPromptPolicy.systemPrompt(visionMode, toolRegistry, characterPrompt);
		return fixedSystemPrompt;
	}

	private List<LlmChatMessage> renderSnapshotNotices(
		PlannerRequest request,
		PlannerAmbientContext ambientContext,
		long renderedTimeContextAtMs
	) {
		long anchorTimeMs = request.timestampMs();
		ArrayList<LlmChatMessage> messages = new ArrayList<>();
		String providerContext = decisionContextEnabled ? "" : toolRegistry.contextSnapshot();
		if (!providerContext.isBlank()) messages.add(LlmChatMessage.user(providerContext, LlmMessageKind.NOTICE));
		if (renderedTimeContextAtMs >= 0L) {
			messages.add(ContextMessageRenderer.renderEntry(new PlannerContextEntry(
				PlannerContextEntryType.NOTICE,
				null,
				PlannerContextPolicy.timeBeaconText(renderedTimeContextAtMs, zoneId),
				-1L,
				renderedTimeContextAtMs
			), anchorTimeMs));
		}
		for (PlannerContextEntry entry : PlannerAmbientContextRenderer.renderChanges(
			state.lastAcceptedAmbientContext(),
			ambientContext,
			request.tick(),
			anchorTimeMs
		)) {
			messages.add(ContextMessageRenderer.renderEntry(entry, anchorTimeMs));
		}
		return List.copyOf(messages);
	}

	private LlmConversation composeConversation(
		long anchorTimeMs,
		List<LlmChatMessage> snapshotNotices,
		List<LlmChatMessage> terminalMessages
	) {
		ArrayList<LlmChatMessage> messages = new ArrayList<>();
		if (toolRegistry.hasFixedPrefix() && retainedConversation != null) {
			messages.addAll(retainedConversation.messages());
			messages.addAll(snapshotNotices);
			messages.addAll(terminalMessages);
			return microCompactor == null ? LlmConversation.of(messages) : microCompactor.update(LlmConversation.of(messages));
		}
		messages.add(LlmChatMessage.system(systemPrompt()));
		if (!backendManagedHistory && state.activeCheckpoint() != null) {
			messages.add(LlmChatMessage.user(state.activeCheckpoint().renderMessage(), LlmMessageKind.CHECKPOINT));
		}
		if (!backendManagedHistory) {
			messages.addAll(renderAcceptedHistory(anchorTimeMs));
		}
		messages.addAll(snapshotNotices);
		messages.addAll(terminalMessages);
		return microCompactor == null ? LlmConversation.of(messages) : microCompactor.update(LlmConversation.of(messages));
	}

	private LlmConversation followUpBase(PlannerContextSnapshot snapshot) {
		if (!backendManagedHistory) {
			return withCurrentSystemPrompt(snapshot.plannerConversation());
		}
		return LlmConversation.of(List.of(LlmChatMessage.system(systemPrompt())));
	}

	private static PlannerContextState withoutAcceptedProviderHistory(PlannerContextState value) {
		return new PlannerContextState(
			List.of(),
			null,
			value.lastAcceptedAmbientContext(),
			value.lastAcceptedTimeContextAtMs(),
			false,
			value.lastObservedUsage(),
			value.queuedTriggers(),
			value.nextTriggerSeqNo()
		);
	}

	private LlmConversation withCurrentSystemPrompt(LlmConversation conversation) {
		if (conversation == null || conversation.messages().isEmpty()) {
			return LlmConversation.of(List.of(LlmChatMessage.system(systemPrompt())));
		}
		ArrayList<LlmChatMessage> messages = new ArrayList<>(conversation.messages());
		if ("system".equals(messages.getFirst().role())) {
			messages.set(0, LlmChatMessage.system(systemPrompt()));
		}
		else {
			messages.add(0, LlmChatMessage.system(systemPrompt()));
		}
		return LlmConversation.of(messages);
	}

	private List<LlmChatMessage> renderAcceptedHistory(long anchorTimeMs) {
		ArrayList<LlmChatMessage> messages = new ArrayList<>();
		for (PlannerContextEntry entry : state.acceptedHistoryTape()) {
			messages.add(renderAcceptedHistoryEntry(entry, anchorTimeMs));
		}
		return List.copyOf(messages);
	}

	private static LlmChatMessage renderAcceptedHistoryEntry(PlannerContextEntry entry, long anchorTimeMs) {
		return switch (entry.type()) {
			case USER_TURN -> LlmChatMessage.user(entry.text(), LlmMessageKind.USER_TURN, entry.fields());
			case ASSISTANT_TURN -> LlmChatMessage.assistant(entry.text(), entry.rawAssistantContent());
				case TOOL_REQUEST -> entry.toolCalls().isEmpty()
					? LlmChatMessage.assistant(entry.text(), entry.rawAssistantContent())
					: LlmChatMessage.assistantToolCalls(entry.text(), entry.toolCalls(), entry.rawAssistantContent());
				case TOOL_RESULT -> entry.toolCall() == null
					? LlmChatMessage.user(entry.text(), LlmMessageKind.TOOL_RESULT, entry.fields())
					: LlmChatMessage.tool(entry.toolCall().id(), toolResultContent(entry.text()), entry.fields());
				case NOTICE -> ContextMessageRenderer.renderEntry(entry, anchorTimeMs);
			};
		}

	private static String toolResultContent(String toolResult) {
		if (toolResult == null || toolResult.isBlank()) {
			return "Tool result: none";
		}
		return toolResult;
	}

}
