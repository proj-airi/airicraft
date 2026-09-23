package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.dialogue.DialogueTurn;
import ai.moeru.airicraft.agent.events.EventPolicyChanges;
import ai.moeru.airicraft.agent.events.EventPolicyMatch;
import ai.moeru.airicraft.agent.events.EventPolicyRuleUpsert;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import ai.moeru.airicraft.agent.session.SessionMode;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.time.Clock;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlannerOrchestrator {
	/** Lifetime gameplay requests, including tool follow-ups but excluding transport retries. */
	public long gameplayDecisionCount() { return sessionCoordinator.gameplayDecisionCount(); }
	private static final Gson GSON = new Gson();
	private final DeferredWorkReceipts deferredWorkReceipts = new DeferredWorkReceipts();
	private static final Pattern FAILED_TOOL_ARGUMENTS_PATTERN = Pattern.compile("Invalid ([a-z0-9_]+) tool arguments:");
	private static final String VISUAL_TOOL_NAME = "take_a_look";
	private static final String WORLD_TOOL_NAME = "inspect_world";
	private static final String INVENTORY_TOOL_NAME = "inspect_inventory";
	private static final String CRAFTABLES_TOOL_NAME = "check_craftables";
	private static final String NEARBY_ENTITIES_TOOL_NAME = "inspect_nearby_entities";
	private static final String INVENTORY_BOOTSTRAP_TOOL_CALL_ID = "bootstrap_inspect_inventory";
	private static final String INVENTORY_BOOTSTRAP_PROMPT = "startup inventory context";
	private static final String NATIVE_TOOL_RESULT_TEXT = "Tool result for take_a_look: current first-person view attached.";
	private static final String TOOL_CALL_REPAIR_PREFIX = "TOOL CALL FORMAT REMINDER:";
	private static final String CHAT_REPAIR_PREFIX = "CHAT MESSAGE FORMAT REMINDER:";
	private static final int MAX_TOOL_CALLS_PER_TURN = 20;
	private static final int SESSION_MAX_CONSECUTIVE_REPAIRABLE_FAILURES = 2;
	private static final long SESSION_RETRY_BACKOFF_MS = 250L;
	private static final int CONVERSATION_HISTORY_CARD_LIMIT = 48;

	private final PlannerExecutor plannerExecutor;
	private final PlannerCompactionService compactionService;
	private final PlannerContextAggregator contextAggregator;
	private final PlannerSessionCoordinator sessionCoordinator;
	private final CurrentViewVisionTool visionTool;
	private final CurrentInventoryTool inventoryTool;
	private final PlannerVisionMode visionMode;
	private final String imageDetail;
	private final Clock clock;
	private final long coalesceStepMs;
	private final long coalesceMinMs;
	private final long coalesceMaxMs;
	private final AgentObservability observability;
	private final PlannerLifecycleListener lifecycleListener;
	private final AgentDebugRecorder debugRecorder;
	private final PlannerActionToolExecutor actionToolExecutor;
	private final PlannerToolNarrationSink narrationSink;
	private final PlannerToolRegistry toolRegistry;
	private final PlannerToolExecutionObserver toolExecutionObserver;
	private final PlannerTurnJournal turnJournal;
	private final PlannerConversationProjector conversationProjector;
	private final HashSet<Long> committedSnapshotGenerations = new HashSet<>();

	private PlannerRequest pendingSubmitRequest;
	private PendingToolExecution pendingToolExecution;
	private CompactionExecutionResult lastCompactionResult;
	private boolean awaitingAcceptedReplyRecord;
	private JsonElement pendingAcceptedAssistantRawContent;
	private volatile boolean captureInFlight;
	private boolean coalescePending;
	private long coalesceReadyAtMs = -1L;
	private long coalesceWindowMs;
	private PlannerContextSnapshot coalesceSupersededSnapshot;
	private Context turnContext;
	private boolean inventoryBootstrapPending = true;
	private final ArrayDeque<StalePlannerRejection> stalePlannerRejections = new ArrayDeque<>();
	private long minimumSafetyEpoch;
	private String currentSafetyHoldId;
	private boolean safetyLaunchBlocked;
	private boolean enabled = true;

	private final int plannerMaxImages;
	private final PlannerVisionService plannerVision;
	// Backend-managed history is remote; conservatively budget until its session resets.
	private int backendHistoryImages;

	public PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		CurrentInventoryTool inventoryTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		Clock clock,
		AgentObservability observability,
		PlannerLifecycleListener lifecycleListener,
		AgentDebugRecorder debugRecorder,
		PlannerActionToolExecutor actionToolExecutor,
		PlannerToolNarrationSink narrationSink,
		PlannerToolRegistry toolRegistry,
		PlannerToolExecutionObserver toolExecutionObserver
	) {
		this(
			plannerExecutor,
			compactionService,
			contextAggregator,
			visionTool,
			inventoryTool,
			visionMode,
			imageDetail,
			plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
			clock,
			observability,
			lifecycleListener,
			debugRecorder,
			actionToolExecutor,
			narrationSink,
			toolRegistry,
			toolExecutionObserver, 8,
			new PlannerVisionService(conversation -> { throw new IllegalStateException("Planner vision fallback not configured"); }));
	}

	public PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		CurrentInventoryTool inventoryTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		Clock clock,
		AgentObservability observability,
		PlannerLifecycleListener lifecycleListener,
		AgentDebugRecorder debugRecorder,
		PlannerActionToolExecutor actionToolExecutor,
		PlannerToolNarrationSink narrationSink,
		PlannerToolRegistry toolRegistry,
		PlannerToolExecutionObserver toolExecutionObserver,
		int plannerMaxImages,
		PlannerVisionService plannerVision
	) {
		this.plannerMaxImages = Math.max(1, plannerMaxImages);
		this.plannerVision = Objects.requireNonNull(plannerVision, "plannerVision");
		this.plannerExecutor = Objects.requireNonNull(plannerExecutor, "plannerExecutor");
		this.compactionService = Objects.requireNonNull(compactionService, "compactionService");
		this.contextAggregator = Objects.requireNonNull(contextAggregator, "contextAggregator");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.sessionCoordinator = new PlannerSessionCoordinator(
			plannerExecutor,
			this.clock,
			plannerSessionMaxConcurrentAttempts,
			SESSION_MAX_CONSECUTIVE_REPAIRABLE_FAILURES,
			SESSION_RETRY_BACKOFF_MS,
			this::recordSubmittedConversation
		);
		this.visionTool = Objects.requireNonNull(visionTool, "visionTool");
		this.inventoryTool = Objects.requireNonNull(inventoryTool, "inventoryTool");
		this.visionMode = Objects.requireNonNull(visionMode, "visionMode");
		this.imageDetail = Objects.requireNonNull(imageDetail, "imageDetail");
		this.coalesceStepMs = Math.max(0L, plannerSessionCoalesceStepMillis);
		this.coalesceMinMs = Math.max(0L, plannerSessionCoalesceMinMillis);
		this.coalesceMaxMs = Math.max(this.coalesceMinMs, plannerSessionCoalesceMaxMillis);
		this.observability = Objects.requireNonNull(observability, "observability");
		this.lifecycleListener = Objects.requireNonNull(lifecycleListener, "lifecycleListener");
		this.debugRecorder = Objects.requireNonNull(debugRecorder, "debugRecorder");
		this.actionToolExecutor = Objects.requireNonNull(actionToolExecutor, "actionToolExecutor");
		this.narrationSink = Objects.requireNonNull(narrationSink, "narrationSink");
		this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
		this.toolExecutionObserver = Objects.requireNonNull(toolExecutionObserver, "toolExecutionObserver");
		this.turnJournal = new PlannerTurnJournal(this.clock, CONVERSATION_HISTORY_CARD_LIMIT * 4);
		this.conversationProjector = new PlannerConversationProjector(CONVERSATION_HISTORY_CARD_LIMIT);
	}

	public void shareGenerationSequence(java.util.concurrent.atomic.AtomicLong sequence) { sessionCoordinator.shareGenerationSequence(sequence); }

	public boolean isConfigured() {
		return plannerExecutor.isConfigured();
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		if (this.enabled == enabled) {
			return;
		}
		this.enabled = enabled;
		if (!enabled) {
			pausePlanner();
		}
	}

	public List<Map<String, Object>> allAvailableTools() {
		return toolRegistry.allAvailableOpenAiTools();
	}

	public CompletableFuture<ExternalPlannerToolResult> executeExternalTool(String name, JsonObject arguments) {
		PlannerToolCall toolCall = PlannerToolCatalog.parseToolCall(name, arguments, toolRegistry);
		return requestExternalPlannerTool(toolCall).thenApply(outcome -> {
			if (!outcome.toolResultText().startsWith("TOOL_ERROR:")) toolRegistry.afterResultCommitted(toolCall.name());
			return new ExternalPlannerToolResult(toolCall.name(), outcome.toolResultText(),
				outcome instanceof ImageToolExecutionOutcome imageOutcome ? imageOutcome.imageAttachment() : null);
		});
	}

	public boolean hasInFlight() {
		return enabled && (contextAggregator.hasPendingOverflowFlush()
			|| coalescePending
			|| sessionCoordinator.hasInFlight()
			|| compactionService.hasInFlight()
			|| pendingToolExecution != null);
	}

	public PlannerOrchestratorDebugSnapshot debugSnapshot() {
		PlannerSessionSnapshot activeSession = sessionCoordinator.activeSnapshot();
		PlannerSessionPhase currentPhase = activeSession == null ? null : activeSession.phase();
		return new PlannerOrchestratorDebugSnapshot(
			isConfigured(),
			visionMode.wireValue(),
			hasInFlight(),
			sessionCoordinator.activeAttemptCount() > 0,
			compactionService.hasInFlight(),
			captureInFlight,
			pendingToolExecution != null,
			currentPhase == PlannerSessionPhase.TOOL_WAIT || currentPhase == PlannerSessionPhase.TOOL_FOLLOW_UP,
			activeSession == null ? pendingSubmitRequest : activeSession.request(),
			lastCompactionResult,
			contextAggregator.debugSnapshot(),
			sessionCoordinator.activeGeneration(),
			currentPhase == null ? null : currentPhase.name(),
			sessionCoordinator.activeAttemptCount(),
			sessionCoordinator.pendingNewestGeneration(),
			sessionCoordinator.supersededCount(),
			activeSession != null && activeSession.retryPending(),
			activeSession == null ? -1L : activeSession.retryReadyAtMs(),
			coalescePending,
			coalesceReadyAtMs,
			coalesceWindowMs
		);
	}

	public PlannerConversationDebugSnapshot conversationDebugSnapshot() {
		return conversationProjector.submittedSnapshot(turnJournal);
	}

	public PlannerConversationDebugSnapshot projectedConversationDebugSnapshot() {
		var snapshot = conversationProjector.projectedSnapshot(turnJournal);
		return plannerExecutor.streamPreview(sessionCoordinator.activeGeneration()).map(snapshot::withAppended).orElse(snapshot)
			.presented(toolRegistry.references());
	}

	public PlannerConversationDebugSnapshot canonicalConversationDebugSnapshot() {
		return conversationDebugSnapshot();
	}

	public List<String> contextExcerpt() {
		return conversationProjector.contextExcerpt(turnJournal);
	}

	/** Copy visible context only; keep private reasoning and image bytes in their owning session. */
	public String delegationContext() {
		var conversation = sessionCoordinator.conversationFor(sessionCoordinator.activeGeneration());
		if (conversation == null) {
			var snapshot = sessionCoordinator.contextSnapshotFor(sessionCoordinator.activeGeneration());
			if (snapshot == null) return String.join("\n", contextExcerpt());
			conversation = withRecordedToolExchanges(snapshot, recordedToolExchanges(sessionCoordinator.activeGeneration())).plannerConversation();
		}
		var entries = new java.util.ArrayDeque<String>();
		int size = 0;
		boolean truncated = false;
		for (var message : conversation.messages()) {
			if (message.role().equals("system")) continue;
			String entry = message.role() + ": " + message.content();
			for (var tool : message.toolCalls()) entry += "\nTool call " + tool.name() + ": " + tool.arguments();
			if (message.hasImageAttachment()) entry += "\n[image retained in controller history]";
			if (entry.length() > 6000) { entry = entry.substring(0, 6000) + " [truncated]"; truncated = true; }
			while (size + entry.length() > 23_000) { size -= entries.removeFirst().length(); truncated = true; }
			entries.addLast(entry); size += entry.length();
		}
		return (truncated ? "[older context or long entries truncated]\n" : "") + String.join("\n", entries);
	}

	private java.util.function.Supplier<PlannerDecisionContext> decisionContextSource;
	private String decisionWorldSessionId;
	private java.util.function.BooleanSupplier decisionAuthority = () -> true;
	public void configureMicroCompaction(PlannerMicroCompactor service) {
		service.onFinding(lifecycleListener::onObservationCompacted);
		contextAggregator.configureMicroCompaction(service);
	}
	public void configureDecisionAuthority(java.util.function.BooleanSupplier authority) { decisionAuthority = authority; }
	private long incorporatedDecisionEventSequence;
	private boolean decisionRefreshPending = true;

	/** The supplier reads game state synchronously on this orchestrator's owning client thread. */
	public void configureDecisionContext(java.util.function.Supplier<PlannerDecisionContext> source) {
		decisionContextSource = Objects.requireNonNull(source);
		contextAggregator.useDecisionContext();
		toolRegistry.freezeToolPrefix();
	}

	public boolean hasIncorporatedDecisionEvent(long sequence) {
		return decisionContextSource != null && sequence <= incorporatedDecisionEventSequence;
	}

	private LlmConversation appendDecisionContext(LlmConversation conversation) {
		conversation = appendQueueContext(conversation);
		if (decisionContextSource == null) {
			LlmConversation delivered = deferredWorkReceipts.deliver(conversation, Map.of());
			return contextAggregator.retainConversation(delivered);
		}
		PlannerDecisionContext context = decisionContextSource.get();
		conversation = deferredWorkReceipts.deliver(conversation, context.current());
		if (endsWithObservation(conversation)) return contextAggregator.retainConversation(conversation);
		var messages = new ArrayList<>(conversation.messages());
		var notices = takeTrailingNotices(messages);
		messages.addAll(PlannerObservation.exchange(observation(context, notices)));
		// Commit to the role's history, not to a provider response. Retries reuse this conversation.
		return contextAggregator.retainConversation(LlmConversation.of(messages));
	}

	/** Advances the event cursor: every observation is committed to the role's history. */
	private Map<String, Object> observation(PlannerDecisionContext context, List<String> notices) {
		if (!context.worldSessionId().equals(decisionWorldSessionId)) {
			decisionWorldSessionId = context.worldSessionId();
			incorporatedDecisionEventSequence = 0;
		}
		var payload = new java.util.LinkedHashMap<>(context.observation(incorporatedDecisionEventSequence, decisionRefreshPending));
		decisionRefreshPending = false;
		incorporatedDecisionEventSequence = context.observations().latestSeqNo();
		if (usesToolQueue()) payload.put("toolQueue", queueState());
		if (!notices.isEmpty()) payload.put("notices", notices);
		return payload;
	}

	private String observeToolResult() {
		if (decisionContextSource == null) return "observation_unavailable";
		return PlannerObservation.render(observation(decisionContextSource.get(), List.of()));
	}

	/** A trailing observe result already delivered current state. */
	private static boolean endsWithObservation(LlmConversation conversation) {
		var messages = conversation.messages();
		if (messages.isEmpty() || !"tool".equals(messages.getLast().role())) return false;
		String callId = messages.getLast().toolCallId();
		for (int index = messages.size() - 2; index >= 0; index--) {
			for (var call : messages.get(index).toolCalls()) {
				if (call.id().equals(callId)) return PlannerObservation.TOOL_NAME.equals(normalizedToolName(call));
			}
		}
		return false;
	}

	/** Runtime notices after the last model turn become observation fields instead of user turns. */
	private static List<String> takeTrailingNotices(List<LlmChatMessage> messages) {
		var notices = new ArrayList<String>();
		int start = messages.size();
		while (start > 0 && "user".equals(messages.get(start - 1).role())) start--;
		for (int index = start; index < messages.size(); ) {
			LlmChatMessage message = messages.get(index);
			if (message.kind() == LlmMessageKind.NOTICE && !message.hasImageAttachment()) {
				notices.add(ContextMessageRenderer.noticeText(message.content()));
				messages.remove(index);
			}
			else index++;
		}
		return notices;
	}

	public long lastObservedEventSeqNo() {
		return contextAggregator.lastObservedEventSeqNo();
	}

	public void invalidateIdleThinkTriggers() {
		contextAggregator.invalidateIdleThinkTriggers();
	}

	public void updateSafetyContext(long safetyEpoch, String holdId, boolean activeReflex) {
		minimumSafetyEpoch = Math.max(minimumSafetyEpoch, Math.max(0L, safetyEpoch));
		queueReflexActive = activeReflex;
		currentSafetyHoldId = holdId;
		// A reflex owns actuators, not reasoning. Executor policies gate physical tools.
		safetyLaunchBlocked = false;
	}

	public List<StalePlannerRejection> drainStalePlannerRejections() {
		if (stalePlannerRejections.isEmpty()) {
			return List.of();
		}
		ArrayList<StalePlannerRejection> drained = new ArrayList<>(stalePlannerRejections);
		stalePlannerRejections.clear();
		return List.copyOf(drained);
	}

	public boolean submit(PlannerRequest request) {
		Objects.requireNonNull(request, "request");
		if (request.triggerBatch() == null || request.triggerBatch().isEmpty()) {
			return false;
		}
		lifecycleListener.onPlannerTurnSubmitted(request);
		contextAggregator.recordPlannerRequestSeed(PlannerRequestSeed.fromRequest(request));
		contextAggregator.cancelPendingOverflowFlush();
		turnContext = observability.startTurnSpan(request, buildTurnId(request));
		for (PlannerTrigger trigger : request.triggerBatch().triggers()) {
			contextAggregator.enqueueTrigger(trigger);
		}
		pendingSubmitRequest = request;
		if (!enabled) {
			return discardSubmittedRequest();
		}
		if (compactionService.hasInFlight()) {
			return true;
		}
		sessionCoordinator.drainCompletedResults();
		if (awaitingAcceptedReplyRecord) {
			return true;
		}
		if (pendingSideEffectToolExecution()) {
			return true;
		}
		if (sessionCoordinator.hasReplaceableActiveSession() && request.triggerBatch().maySupersedeLaunchedTurn()) {
			long supersededGeneration = sessionCoordinator.activeGeneration();
			coalesceSupersededSnapshot = sessionCoordinator.supersedeActiveSessionIfReplaceable();
			committedSnapshotGenerations.remove(supersededGeneration);
			turnJournal.markSuperseded(supersededGeneration);
			recordConversationSources();
			armCoalesceWindow();
			return coalesceWindowMs > 0L || startQueuedWorkIfPossible();
		}
		if (coalescePending) {
			armCoalesceWindow();
			return coalesceWindowMs > 0L || startQueuedWorkIfPossible();
		}
		return startQueuedWorkIfPossible();
	}

	public PlannerExecutionResult poll() {
		contextAggregator.refreshMicroCompaction();
		tickToolQueue();
		if (plannerExecutor.hasInFlight()) recordConversationSources();
		if (compactionService.hasInFlight()) {
			return pollCompaction();
		}

		if (pendingToolExecution != null) {
			PlannerExecutionResult toolContinuation = pollToolContinuation();
			if (toolContinuation != null) {
				return toolContinuation;
			}
		}

		PlannerExecutionResult plannerResult = sessionCoordinator.poll();
		if (plannerResult == null) {
			pollCoalescedQueue();
			return null;
		}
		lifecycleListener.onPlannerModelCallCompleted(plannerResult);
		if (isStaleSafetyRequest(plannerResult.request())) {
			rejectStalePlannerResult(plannerResult);
			return null;
		}
		if (!plannerResult.succeeded()) {
			if (scheduleParseRepairRetry(plannerResult)) {
				return null;
			}
			return finishFailedPlannerResult(plannerResult);
		}

		return finishSuccessfulPlannerResult(plannerResult);
	}

	private PlannerExecutionResult pollCompaction() {
		CompactionExecutionResult compactionResult = compactionService.poll();
		if (compactionResult == null) {
			return null;
		}
		completeCompaction(compactionResult);
		if (compactionResult.succeeded() && pendingSubmitRequest != null && contextAggregator.hasQueuedTriggers()) {
			startQueuedWorkIfPossible();
		}
		return null;
	}

	private PlannerExecutionResult pollToolContinuation() {
		return continueAfterTool();
	}

	private void pollCoalescedQueue() {
		if (coalescePending) {
			startQueuedWorkIfPossible();
		}
	}

	private PlannerExecutionResult finishFailedPlannerResult(PlannerExecutionResult plannerResult) {
		appendFailureCard(plannerResult);
		debugRecorder.recordPlannerCompletion(plannerResult);
		observability.recordFailure(turnContext, plannerResult.failureType().name(), plannerResult.failureMessage(), null);
		lifecycleListener.onPlannerExecutionFailed(plannerResult);
		sessionCoordinator.finishGeneration(plannerResult.generation(), true);
		committedSnapshotGenerations.remove(plannerResult.generation());
		endTurnSpan();
		return plannerResult;
	}

	private PlannerExecutionResult finishSuccessfulPlannerResult(PlannerExecutionResult plannerResult) {

		if (plannerResult.phase() == PlannerSessionPhase.TOOL_FOLLOW_UP
			&& completedToolCallCount(plannerResult.generation()) + effectiveToolCalls(plannerResult.response()).size() > MAX_TOOL_CALLS_PER_TURN) {
			return yieldToolTurn(plannerResult);
		}
		PlannerExecutionResult visibleChatResult = validateOrRepairVisibleChat(plannerResult);
		if (visibleChatResult == null) {
			return null;
		}
		plannerResult = visibleChatResult;
		contextAggregator.recordUsage(plannerResult.usage());
		lifecycleListener.onPlannerExecutionSucceeded(plannerResult);
		List<PlannerToolCall> toolCalls = effectiveToolCalls(plannerResult.response());
		if (toolCalls.isEmpty()) {
			PlannerExecutionResult promotionFailure = promoteBackendCandidate(plannerResult);
			if (promotionFailure != null) {
				return finishFailedPlannerResult(promotionFailure);
			}
			acceptPlannerReply(plannerResult);
			lifecycleListener.onPlannerExecutionApplied(plannerResult);
			return plannerResult;
		}

		PlannerExecutionResult toolRequestFailure = validateToolRequest(plannerResult, toolCalls);
		if (toolRequestFailure != null) {
			if (scheduleParseRepairRetry(toolRequestFailure)) {
				return null;
			}
			appendFailureCard(toolRequestFailure);
			debugRecorder.recordPlannerCompletion(toolRequestFailure);
			sessionCoordinator.finishGeneration(plannerResult.generation(), true);
			committedSnapshotGenerations.remove(plannerResult.generation());
			return toolRequestFailure;
		}
		if (usesToolQueue())
			return enqueueTools(plannerResult, toolCalls);
		return startToolExecution(plannerResult, toolCalls);
	}

	private PlannerExecutionResult yieldToolTurn(PlannerExecutionResult result) {
		long generation = result.generation();
		int completedTools = completedToolCallCount(generation);
		commitSnapshotIfNeeded(generation);
		commitRecordedToolExchanges(generation);
		contextAggregator.recordUsage(result.usage());
		contextAggregator.retainConversation(sessionCoordinator.conversationFor(generation).withAppended(
			LlmChatMessage.user("TOOL TURN CHECKPOINT: The " + MAX_TOOL_CALLS_PER_TURN
				+ "-tool turn budget was reached. Completed tool results above are retained. The last proposed tool call was not executed."
				+ " Review fresh state before continuing the active goal; this is a turn boundary, not a tool-format error or task completion.", LlmMessageKind.NOTICE)));
		// Discard only the extra proposal; completed side effects and the accepted input history remain.
		sessionCoordinator.finishGeneration(generation, true);
		committedSnapshotGenerations.remove(generation);
		awaitingAcceptedReplyRecord = false;
		pendingAcceptedAssistantRawContent = null;
		if (!contextAggregator.hasQueuedTriggers()) {
			pendingSubmitRequest = null;
			clearCoalesceState();
		}
		endTurnSpan();
		PlannerExecutionResult checkpoint = withResponse(result, new PlannerResponse("", new PlannerIntent("none", null, null)));
		debugRecorder.recordPlannerCompletion(checkpoint);
		lifecycleListener.onPlannerExecutionSucceeded(checkpoint);
		lifecycleListener.onPlannerExecutionApplied(checkpoint);
		Airicraft.LOGGER.info("Planner tool turn yielded generation={} completedTools={}", generation, completedTools);
		if (contextAggregator.hasQueuedTriggers()) startQueuedWorkIfPossible();
		return checkpoint;
	}

	private PlannerExecutionResult promoteBackendCandidate(PlannerExecutionResult plannerResult) {
		try {
			if (sessionCoordinator.acceptGeneration(plannerResult.generation())) {
				return null;
			}
			return backendPromotionFailure(plannerResult, "Planner generation is no longer active");
		}
		catch (LlmBackendException exception) {
			return backendPromotionFailure(plannerResult, exception.getMessage());
		}
	}

	private static PlannerExecutionResult backendPromotionFailure(PlannerExecutionResult result, String message) {
		return new PlannerExecutionResult(
			result.request(),
			null,
			result.usage(),
			LlmFailureType.PROVIDER_ERROR,
			message == null || message.isBlank() ? "Failed to promote planner backend context" : message,
			result.generation(),
			result.attempt(),
			result.phase(),
			false
		);
	}

	private void acceptPlannerReply(PlannerExecutionResult plannerResult) {
		appendAssistantOutcomeCard(plannerResult);
		appendOperationCards(plannerResult);
		debugRecorder.recordPlannerCompletion(plannerResult);
		acceptGeneration(plannerResult);
	}

	private PlannerExecutionResult validateToolRequest(PlannerExecutionResult plannerResult, List<PlannerToolCall> toolCalls) {
		PlannerToolCall firstToolCall = toolCalls.getFirst();
		if (plannerResult.response().toolCall() == null && !hasToolCompatibleIntent(plannerResult.response())) {
			String toolIntentType = toolIntentType(plannerResult.response());
			Airicraft.LOGGER.warn(
				"Planner returned invalid legacy tool response intentType={} toolCallName={} replyText={}",
				toolIntentType,
				firstToolCall.name(),
				summarizeForLog(plannerResult.response().replyText())
			);
			return rejectToolRequest(plannerResult, "Tool requests cannot set goal intents");
		}
		for (PlannerToolCall toolCall : toolCalls) {
			if (!isValidToolCall(toolCall)) {
				String toolName = normalizedToolName(toolCall);
				Airicraft.LOGGER.warn(
					"Planner returned invalid tool call name={} narration={}",
					toolCall.name(),
					summarizeForLog(toolCall.narration())
				);
				return rejectToolRequest(
					plannerResult,
					toolRegistry.isKnownTool(toolName)
						? "tool_not_available_for_role: " + toolName
						: "Planner requested an invalid tool"
				);
			}
		}
		PlannerToolRegistry.ReadOnlyBatchAuthorization batchAuthorization = toolRegistry.authorizeReadOnlyBatch(toolCalls);
		if (!usesToolQueue() && toolCalls.size() > 1 && !batchAuthorization.authorized()) {
			Airicraft.LOGGER.warn(
				"Planner returned unsupported multi-tool batch names={} reason={} rejectedTool={}",
				toolCallNames(toolCalls),
				batchAuthorization.rejectionReason(),
				batchAuthorization.toolName()
			);
			String message = batchAuthorization.rejectionReason() == PlannerToolRegistry.BatchRejectionReason.UNKNOWN_TOOL
				? "Planner requested an invalid tool: " + batchAuthorization.toolName()
				: "Planner requested multiple tools; only read-only tools can be batched";
			return rejectToolRequest(plannerResult, message);
		}

		String toolIntentType = toolIntentType(plannerResult.response());
		if (plannerResult.response().toolCall() == null && !"none".equals(toolIntentType)) {
			Airicraft.LOGGER.info(
				"Planner returned legacy tool request with non-none intent; ignoring intentType={} toolCallName={}",
				toolIntentType,
				firstToolCall.name()
			);
		}
		if (plannerResult.response().replyText() != null && !plannerResult.response().replyText().isBlank()) {
			Airicraft.LOGGER.info(
				"Planner returned tool call with stray replyText; ignoring text={} toolCallName={}",
				summarizeForLog(plannerResult.response().replyText()),
				firstToolCall.name()
			);
		}
		return null;
	}

	private PlannerExecutionResult rejectToolRequest(PlannerExecutionResult plannerResult, String message) {
		return parseFailure(plannerResult, message);
	}

	private boolean scheduleParseRepairRetry(PlannerExecutionResult failure) {
		if (failure == null || failure.failureType() != LlmFailureType.PARSE_ERROR) {
			return false;
		}
		boolean scheduled = sessionCoordinator.scheduleParseRepairRetry(
			failure.generation(),
			LlmChatMessage.user(toolCallRepairMessage(failure.failureMessage()), LlmMessageKind.NOTICE)
		);
		if (scheduled) {
			Airicraft.LOGGER.info("Planner parse failure scheduled tool-call repair retry generation={} attempt={}", failure.generation(), failure.attempt());
		}
		return scheduled;
	}

	private PlannerExecutionResult validateOrRepairVisibleChat(PlannerExecutionResult result) {
		if (result == null || result.response() == null) {
			return result;
		}
		PlannerChatContract.ValidationResult chatValidation = PlannerChatContract.validateMessages(
			result.response().chatMessages(),
			"chatMessages"
		);
		PlannerChatContract.ValidationResult narrationValidation = validateToolNarration(result.response().toolCalls());
		if (chatValidation.valid() && narrationValidation.valid()) {
			return result;
		}
		String failureMessage = chatValidation.valid() ? narrationValidation.message() : chatValidation.message();
		// Cosmetic tool narration must not regenerate an otherwise valid gameplay decision.
		if (!chatValidation.valid() && result.attempt() <= 1 && scheduleChatRepairRetry(result, failureMessage)) {
			return null;
		}
		PlannerResponse contractedResponse = contractVisibleChat(result.response());
		Airicraft.LOGGER.warn(
			"Planner visible chat contract fallback generation={} attempt={} reason={} originalLength={} contractedLength={}",
			result.generation(),
			result.attempt(),
			failureMessage,
			visibleChatLength(result.response()),
			visibleChatLength(contractedResponse)
		);
		return withResponse(result, contractedResponse);
	}

	private boolean scheduleChatRepairRetry(PlannerExecutionResult result, String failureMessage) {
		boolean scheduled = sessionCoordinator.scheduleChatRepairRetry(
			result.generation(),
			LlmChatMessage.user(chatRepairMessage(failureMessage), LlmMessageKind.NOTICE)
		);
		if (scheduled) {
			Airicraft.LOGGER.info("Planner visible chat repair retry scheduled generation={} attempt={}", result.generation(), result.attempt());
		}
		return scheduled;
	}

	private static String chatRepairMessage(String failureMessage) {
		return CHAT_REPAIR_PREFIX
			+ " Previous visible Minecraft chat was rejected: "
			+ (failureMessage == null || failureMessage.isBlank() ? "invalid visible chat" : failureMessage)
			+ "\nFor normal replies, use chatMessages with 1-4 entries. Each text must be one plaintext line under "
			+ PlannerChatContract.MAX_MESSAGE_LENGTH
			+ " characters. Use delayTicks or delaySeconds for pauses between messages."
			+ "\nDo not use markdown, code fences, bullets, headings, links, decorative formatting, multiline text, or a leading slash."
			+ "\nIf you still need a tool, keep the same tool intent and make narration one short plaintext line under "
			+ PlannerChatContract.MAX_MESSAGE_LENGTH
			+ " characters.";
	}

	private static PlannerChatContract.ValidationResult validateToolNarration(List<PlannerToolCall> toolCalls) {
		for (int index = 0; index < (toolCalls == null ? 0 : toolCalls.size()); index++) {
			PlannerToolCall toolCall = toolCalls.get(index);
			if (toolCall == null || toolCall.narration() == null || toolCall.narration().isBlank()) {
				continue;
			}
			PlannerChatContract.ValidationResult validation = PlannerChatContract.validateText(
				toolCall.narration(),
				"toolCalls[" + index + "].narration"
			);
			if (!validation.valid()) {
				return validation;
			}
		}
		return PlannerChatContract.ValidationResult.ok();
	}

	private static PlannerResponse contractVisibleChat(PlannerResponse response) {
		List<PlannerToolCall> contractedToolCalls = response.toolCalls().stream()
			.map(PlannerOrchestrator::contractToolNarration)
			.toList();
		return response
			.withChatMessages(PlannerChatContract.contractMessages(response.chatMessages()))
			.withToolCalls(contractedToolCalls);
	}

	private static PlannerToolCall contractToolNarration(PlannerToolCall toolCall) {
		if (toolCall == null || toolCall.narration() == null || toolCall.narration().isBlank()) {
			return toolCall;
		}
		String contractedNarration = PlannerChatContract.contractText(toolCall.narration());
		JsonObject arguments = toolCall.arguments().deepCopy();
		arguments.addProperty("narration", contractedNarration);
		return new PlannerToolCall(
			toolCall.id(),
			toolCall.name(),
			arguments,
			contractedNarration,
			null,
			toolCall.repairedArgumentPaths()
		);
	}

	private static PlannerExecutionResult withResponse(PlannerExecutionResult result, PlannerResponse response) {
		return new PlannerExecutionResult(
			result.request(),
			response,
			result.usage(),
			null,
			null,
			result.generation(),
			result.attempt(),
			result.phase(),
			result.stale()
		);
	}

	private static int visibleChatLength(PlannerResponse response) {
		if (response == null) {
			return 0;
		}
		int length = response.chatMessages().stream()
			.mapToInt(message -> message == null || message.text() == null ? 0 : message.text().length())
			.sum();
		length += response.toolCalls().stream()
			.mapToInt(toolCall -> toolCall == null || toolCall.narration() == null ? 0 : toolCall.narration().length())
			.sum();
		return length;
	}

	private String toolCallRepairMessage(String failureMessage) {
		String reminder = TOOL_CALL_REPAIR_PREFIX
			+ " Previous response was rejected: "
			+ (failureMessage == null || failureMessage.isBlank() ? "parse error" : failureMessage)
			+ (usesToolQueue() ? "\nTool calls append to a sequential FIFO. Use continue to yield or clear_queue to abort and replace the plan."
				: "\nCall exactly one tool unless every call is a read-only text tool. Do not batch action calls.")
			+ "\nWhen calling a tool, leave assistant content empty and put visible pre-action text in the tool narration argument.";
		String failedToolSchema = failedToolSchema(failureMessage);
		if (failedToolSchema == null) {
			return reminder;
		}
		return reminder
			+ "\nCURRENT SCHEMA FOR THE REJECTED TOOL (use these exact field names and enum values):\n"
			+ failedToolSchema;
	}

	private String failedToolSchema(String failureMessage) {
		if (failureMessage == null || failureMessage.isBlank()) {
			return null;
		}
		Matcher matcher = FAILED_TOOL_ARGUMENTS_PATTERN.matcher(failureMessage);
		if (!matcher.find()) {
			return null;
		}
		return toolRegistry.activeOpenAiTool(matcher.group(1))
			.map(GSON::toJson)
			.orElse(null);
	}

	/** Each planner owns its plan; a delegated planner runs while its caller awaits the handoff. */
	private static final class QueueState {
		final PlannerToolQueue<ToolExecutionOutcome> tools = new PlannerToolQueue<>();
		final java.util.LinkedHashMap<String, ToolExecutionResult> reports = new java.util.LinkedHashMap<>();
		final java.util.Map<String, LlmImageAttachment> images = new java.util.HashMap<>();
		PlannerRequest seed;
		CompletableFuture<ToolExecutionOutcome> abort;
		PlannerToolCall abortCall;
		long observationTick;
		long firstResultMs;
		long lastResultMs;
		PlannerToolCall loneCall;
		PlannerToolCall reviewedLoneCall;
		long loneSinceMs;
	}
	private final QueueState toolQueue = new QueueState();
	private boolean queueReflexActive;
	private boolean usesToolQueue() { return toolRegistry.isKnownTool(PlannerQueueToolProvider.CONTINUE); }

	private PlannerExecutionResult enqueueTools(PlannerExecutionResult result, List<PlannerToolCall> calls) {
		PlannerExecutionResult promotionFailure = promoteBackendCandidate(result);
		if (promotionFailure != null) return finishFailedPlannerResult(promotionFailure);
		commitSnapshotIfNeeded(result.generation());
		var snapshot = sessionCoordinator.contextSnapshotFor(result.generation())
			.withConversation(sessionCoordinator.conversationFor(result.generation()));
		var receipts = new ArrayList<String>();
		// clear_queue is an immediate control, regardless of its position in the response.
		var clear = calls.stream().filter(c -> PlannerQueueToolProvider.CLEAR.equals(c.name())).findFirst();
		if (clear.isPresent()) {
			var cancelled = new ArrayList<>(toolQueue.tools.pending());
			if (toolQueue.tools.active() != null) cancelled.addFirst(toolQueue.tools.active());
			toolQueue.tools.clear((call, work) -> {});
			for (var call : cancelled) queueReport(call, new TextToolExecutionOutcome("Cancelled by clear_queue; completed effects are not undone."));
			toolQueue.abortCall = clear.get();
			toolQueue.abort = requestPlannerTools(List.of(clear.get()), snapshot.plannerConversation());
		}
		for (var call : calls) {
			String receipt;
			if (PlannerQueueToolProvider.CONTINUE.equals(call.name())) {
				receipt = "Plan retained; continue requested.";
				if (clear.isEmpty() && toolQueue.abort == null) {
					toolQueue.abortCall = call;
					toolQueue.abort = requestPlannerTools(List.of(call), snapshot.plannerConversation());
				}
			}
			else if (PlannerQueueToolProvider.CLEAR.equals(call.name())) receipt = "Queue cleared; aborting active work.";
			else {
				toolQueue.tools.append(List.of(call));
				receipt = "QUEUED: " + call.id() + "; execution result will arrive in a later review.";
			}
			receipts.add(receipt);
			recordToolExchange(result.generation(), snapshot, result.response().rawAssistantContent(), call, receipt, false);
			lifecycleListener.onToolRequested(result.generation(), call);
		}
		var acceptedConversation = contextAggregator.buildPlannerFollowUpConversation(snapshot, calls, receipts);
		contextAggregator.retainConversation(acceptedConversation);
		commitRecordedToolExchanges(result.generation());
		toolQueue.seed = result.request();
		debugRecorder.recordPlannerCompletion(result);
		sessionCoordinator.finishGeneration(result.generation(), false);
		committedSnapshotGenerations.remove(result.generation());
		endTurnSpan();
		lifecycleListener.onPlannerExecutionApplied(result);
		return null;
	}

	public boolean hasQueuedToolWork() { return !toolQueue.tools.isEmpty() || !toolQueue.reports.isEmpty() || toolQueue.abort != null; }

	public void tickToolQueue() {
		if (!usesToolQueue() || !enabled || !hasQueuedToolWork() || !decisionAuthority.getAsBoolean()) return;
		PlannerDecisionContext context = decisionContextSource == null ? null : decisionContextSource.get();
		toolQueue.observationTick = context == null ? (toolQueue.seed == null ? 0 : toolQueue.seed.tick()) : context.tick();
		if (context != null && decisionWorldSessionId != null
			&& !decisionWorldSessionId.equals(context.worldSessionId())) {
			toolQueue.tools.clear((call, id) -> {});
			toolQueue.reports.clear(); toolQueue.images.clear();
			return;
		}
		if (toolQueue.abort != null) {
			if (!toolQueue.abort.isDone()) return;
			ToolExecutionOutcome outcome;
			try { outcome = toolQueue.abort.join(); }
			catch (CompletionException error) { outcome = new TextToolExecutionOutcome(failedToolResultText(toolQueue.abortCall, error)); }
			if (!PlannerQueueToolProvider.CONTINUE.equals(toolQueue.abortCall.name())
				|| outcome.toolResultText().startsWith("TOOL_ERROR:")) queueReport(toolQueue.abortCall, outcome);
			toolQueue.abort = null;
			if (PlannerQueueToolProvider.CLEAR.equals(toolQueue.abortCall.name()) && outcome.toolResultText().startsWith("TOOL_ERROR:")) toolQueue.tools.clear((call, id) -> {});
		}
		var completion = toolQueue.tools.tick(call -> {
			narrationSink.onToolNarration(call);
			return requestPlannerTools(List.of(call), contextAggregator.retainedToolContext());
		}, outcome -> ongoingWorkId(outcome.toolResultText()), id -> terminalQueuedWork(id, context),
			!queueReflexActive && (toolQueue.tools.active() == null || !toolRegistry.endsTurn(toolQueue.tools.active().name())));
		if (completion != null) {
			ToolExecutionOutcome outcome = completion.failure() != null
				? new TextToolExecutionOutcome(failedToolResultText(completion.call(), completion.failure()))
				: completion.workOutcome() != null ? new TextToolExecutionOutcome(completion.workOutcome()) : completion.result();
			queueReport(completion.call(), outcome);
			if (!outcome.toolResultText().startsWith("TOOL_ERROR:") && !outcome.toolResultText().startsWith("TOOL_UNAVAILABLE:"))
				toolRegistry.afterResultCommitted(completion.call().name());
		}
		long now = clock.millis();
		var active = toolQueue.tools.active();
		var lone = toolQueue.tools.pending().isEmpty() ? active : null;
		if (lone != toolQueue.loneCall) {
			toolQueue.loneCall = lone;
			toolQueue.loneSinceMs = now;
		}
		boolean refill = lone != null && lone != toolQueue.reviewedLoneCall
			&& !toolRegistry.endsTurn(lone.name()) && now - toolQueue.loneSinceMs >= 1000;
		if ((!refill && toolQueue.reports.isEmpty()) || toolQueue.seed == null || sessionCoordinator.hasInFlight()
			|| pendingToolExecution != null || awaitingAcceptedReplyRecord || compactionService.hasInFlight()
			|| coalescePending) return;
		// Coalesce fast results even when a refill is due, but do not starve reports behind slow reads.
		boolean nextReadRunning = active != null && toolRegistry.isReadTool(active.name());
		if (!toolQueue.reports.isEmpty()
			&& (now - toolQueue.lastResultMs < 250 || nextReadRunning) && now - toolQueue.firstResultMs < 1000) return;
		PlannerRequest seed = toolQueue.seed;
		submit(PlannerRequest.ofTrigger(toolQueue.observationTick, now, seed.sessionMode(), seed.primaryInteractionPlayer(), seed.activeGoal(),
			PlannerTriggerType.SYSTEM, "tool_queue", toolQueue.reports.isEmpty()
				? "Queued work is still running. Plan the next steps assuming it succeeds, without treating it as succeeded."
				: "Queued tool results are ready for review.", null)
			.withSafetyContext(minimumSafetyEpoch, currentSafetyHoldId));
	}

	private void queueReport(PlannerToolCall call, ToolExecutionOutcome outcome) {
		if (toolQueue.reports.isEmpty()) toolQueue.firstResultMs = clock.millis();
		toolQueue.lastResultMs = clock.millis();
		toolQueue.reports.put(call.id(), new ToolExecutionResult(call, outcome.toolResultText(), outcome.hasImageAttachment()));
		if (outcome instanceof ImageToolExecutionOutcome image) toolQueue.images.put(call.id(), image.imageAttachment());
		debugRecorder.recordTerminalTool(toolQueue.observationTick, call, outcome.toolResultText(), outcome.hasImageAttachment());
		lifecycleListener.onToolExchange(call, outcome.toolResultText(), outcome.hasImageAttachment());
		lifecycleListener.onToolCompleted(0, outcome.toolResultText(), outcome.hasImageAttachment());
	}

	private static String ongoingWorkId(String text) {
		int start = text.indexOf(": ");
		if (!text.startsWith("Tool result for ") || start < 0) return null;
		try {
			var receipt = com.google.gson.JsonParser.parseString(text.substring(start + 2)).getAsJsonObject();
			return receipt.has("accepted") && receipt.get("accepted").getAsBoolean() && receipt.has("workId") && receipt.has("state")
				&& List.of("QUEUED", "RUNNING", "WAITING", "PAUSED").contains(receipt.get("state").getAsString())
				? receipt.get("workId").getAsString() : null;
		} catch (IllegalStateException | IllegalArgumentException | com.google.gson.JsonParseException error) { return null; }
	}

	private static String terminalQueuedWork(String workId, PlannerDecisionContext context) {
		if (context == null) return null;
		Object entries = context.current().get("work");
		if (entries instanceof List<?> work) for (Object entry : work) {
			if (entry instanceof Map<?, ?> value && workId.equals(value.get("workId"))
				&& List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(String.valueOf(value.get("state"))))
				return "Work outcome: " + GSON.toJson(ai.moeru.airicraft.agent.work.WorkSnapshot.summarize(value));
		}
		return null;
	}

	private LlmConversation appendQueueContext(LlmConversation conversation) {
		if (!usesToolQueue()) return conversation;
		var remaining = new java.util.LinkedHashMap<>(toolQueue.reports);
		var messages = new ArrayList<LlmChatMessage>();
		for (var message : conversation.messages()) {
			// Queue snapshots are current state, not growing historical narration.
			if (message.content().startsWith("TOOL QUEUE:")) continue;
			var report = remaining.remove(message.toolCallId());
			if (report == null) messages.add(message);
			else messages.add(new LlmChatMessage("tool", report.toolResultText(), LlmMessageKind.TOOL_RESULT,
				toolQueue.images.get(message.toolCallId()), null, List.of(), message.toolCallId()));
		}
		// Provider-managed history and role handoffs may not carry local tool envelopes.
		for (var report : remaining.values()) {
			String text = "Execution result for " + report.toolCall().id() + " (" + report.toolCall().name() + "): " + report.toolResultText();
			var image = toolQueue.images.get(report.toolCall().id());
			messages.add(image == null ? LlmChatMessage.user(text, LlmMessageKind.TOOL_RESULT)
				: LlmChatMessage.userWithImage(text, LlmMessageKind.TOOL_RESULT, image));
		}
		if (plannerExecutor.managesConversationHistory()) backendHistoryImages += toolQueue.images.size();
		toolQueue.reports.clear(); toolQueue.images.clear();
		if (decisionContextSource == null) messages.add(LlmChatMessage.user("TOOL QUEUE: " + GSON.toJson(queueState()), LlmMessageKind.NOTICE));
		return LlmConversation.of(messages);
	}

	private Map<String, Object> queueState() {
		var state = new java.util.LinkedHashMap<String, Object>();
		state.put("active", toolQueue.tools.active() == null ? null : queuedCallView(toolQueue.tools.active()));
		state.put("workId", toolQueue.tools.activeWorkId());
		state.put("pending", toolQueue.tools.pending().stream().map(PlannerOrchestrator::queuedCallView).toList());
		state.put("aborting", toolQueue.abort != null);
		if (toolQueue.tools.pending().isEmpty()) toolQueue.reviewedLoneCall = toolQueue.tools.active();
		return state;
	}

	private static Map<String, Object> queuedCallView(PlannerToolCall call) {
		return Map.of("id", call.id(), "name", call.name(), "arguments", call.arguments());
	}

	private PlannerExecutionResult startToolExecution(PlannerExecutionResult plannerResult, List<PlannerToolCall> toolCalls) {
		PlannerExecutionResult promotionFailure = promoteBackendCandidate(plannerResult);
		if (promotionFailure != null) {
			return finishFailedPlannerResult(promotionFailure);
		}
		commitSnapshotIfNeeded(plannerResult.generation());
		for (PlannerToolCall toolCall : toolCalls) {
			narrationSink.onToolNarration(toolCall);
		}
		appendToolRequestCard(plannerResult, toolCalls);
		debugRecorder.recordPlannerCompletion(plannerResult);
		for (PlannerToolCall toolCall : toolCalls) {
			lifecycleListener.onToolRequested(plannerResult.generation(), toolCall);
		}
		sessionCoordinator.markToolWait(plannerResult.generation());
		pendingToolExecution = new PendingToolExecution(
			plannerResult.generation(),
			toolCallsSummary(toolCalls),
			requestPlannerTools(toolCalls, sessionCoordinator.conversationFor(plannerResult.generation())),
			plannerResult.response().rawAssistantContent(),
			toolCalls
		);
		lifecycleListener.onPlannerExecutionApplied(plannerResult);
		return null;
	}

	private boolean pendingSideEffectToolExecution() {
		return pendingToolExecution != null && pendingToolExecution.toolCalls().stream().anyMatch(call -> !toolRegistry.isReadTool(call.name()));
	}

	private static boolean isSideEffectTool(PlannerToolCall toolCall) {
		String toolName = toolCall == null ? "" : toolCall.name();
		return PlannerToolCatalog.isKnownTool(toolName) && !PlannerToolCatalog.isReadTool(toolName);
	}

	public void injectMockResponse(PlannerResponse response) {
		if (enabled) {
			plannerExecutor.injectMockResponse(response);
		}
	}

	public void injectTimeout() {
		if (enabled) {
			plannerExecutor.injectTimeout();
		}
	}

	public void recordAssistantTurn(DialogueTurn turn) {
		contextAggregator.recordAgentTurn(turn, pendingAcceptedAssistantRawContent);
		pendingAcceptedAssistantRawContent = null;
	}

	public void onAcceptedReplyRecorded() {
		awaitingAcceptedReplyRecord = false;
		clearCoalesceState();
		if (
			(pendingSubmitRequest != null && contextAggregator.hasQueuedTriggers())
				|| contextAggregator.hasPendingOverflowFlush()
		) {
			startQueuedWorkIfPossible();
		}
	}

	public void recordEvents(SemanticEventQueryResult queryResult, long anchorTimeMs) {
		recordEvents(queryResult, new PlannerRequestSeed(anchorTimeMs / 50L, anchorTimeMs, SessionMode.OUT_OF_WORLD, null, null));
	}

	public void recordEvents(SemanticEventQueryResult queryResult, PlannerRequestSeed requestSeed) {
		contextAggregator.recordObservedEvents(queryResult, requestSeed);
		if (contextAggregator.hasPendingOverflowFlush()) {
			startQueuedWorkIfPossible();
		}
	}

	public boolean startDebugCompaction() {
		if (!enabled || !isConfigured() || hasInFlight() || fullCompactionWaitingForMicro()
			|| plannerExecutor.managesConversationHistory()) {
			return false;
		}
		lastCompactionResult = null;
		return compactionService.submit(contextAggregator.buildCompactionConversation());
	}

	public CompactionExecutionResult pollDebugCompaction() {
		if (!compactionService.hasInFlight()) {
			return lastCompactionResult;
		}
		CompactionExecutionResult compactionResult = compactionService.poll();
		if (compactionResult == null) {
			return null;
		}
		completeCompaction(compactionResult);
		return compactionResult;
	}

	public void reset() {
		decisionWorldSessionId = null;
		incorporatedDecisionEventSequence = 0;
		cancelPendingTool();
		sessionCoordinator.reset();
		compactionService.reset();
		clearRuntimeState("reset");
	}

	public void shutdown() {
		cancelPendingTool();
		sessionCoordinator.shutdown();
		compactionService.shutdown();
		plannerVision.close();
		contextAggregator.closeMicroCompaction();
		clearRuntimeState("shutdown");
	}

	private void clearRuntimeState(String reason) {
		backendHistoryImages = 0;
		deferredWorkReceipts.clear();
		contextAggregator.clear();
		turnJournal.clear(reason);
		pendingSubmitRequest = null;
		lastCompactionResult = null;
		awaitingAcceptedReplyRecord = false;
		pendingAcceptedAssistantRawContent = null;
		inventoryBootstrapPending = true;
		stalePlannerRejections.clear();
		minimumSafetyEpoch = 0L;
		currentSafetyHoldId = null;
		safetyLaunchBlocked = false;
		committedSnapshotGenerations.clear();
		recordConversationSources();
		clearCoalesceState();
		endTurnSpan();
		lifecycleListener.onReset(reason);
	}

	private boolean startQueuedWorkIfPossible() {
		if (!enabled) {
			return true;
		}
		if (safetyLaunchBlocked) {
			return true;
		}
		boolean hasRealTrigger = pendingSubmitRequest != null && contextAggregator.hasQueuedTriggers();
		boolean hasOverflowFlush = contextAggregator.hasPendingOverflowFlush();
		if (!hasRealTrigger && !hasOverflowFlush) {
			clearCoalesceState();
			return true;
		}
		if (awaitingAcceptedReplyRecord) {
			return true;
		}
		if (hasRealTrigger && hasOverflowFlush) {
			contextAggregator.cancelPendingOverflowFlush();
			hasOverflowFlush = false;
		}
		if (hasRealTrigger && contextAggregator.compactionPending()) {
			return startCompactionIfIdle();
		}
		if (!hasRealTrigger && hasOverflowFlush) {
			return startOverflowFlushIfIdle();
		}

		return startTriggeredPlannerTurn();
	}

	private boolean discardSubmittedRequest() {
		PlannerContextSnapshot snapshot = contextAggregator.freezePlannerSnapshot(pendingSubmitRequest);
		if (snapshot == null) {
			pendingSubmitRequest = null;
			endTurnSpan();
			return false;
		}
		snapshot = withInventoryBootstrapIfAvailable(snapshot);
		PlannerExecutionResult discarded = sessionCoordinator.recordDiscarded(
			snapshot,
			toolRegistry.openAiTools(),
			currentTurnContext()
		);
		lifecycleListener.onPlannerModelCallCompleted(discarded);
		debugRecorder.recordPlannerDiscarded(discarded);
		lifecycleListener.onPlannerExecutionDiscarded(discarded);
		contextAggregator.discardSnapshot(snapshot);
		pendingSubmitRequest = null;
		awaitingAcceptedReplyRecord = false;
		pendingAcceptedAssistantRawContent = null;
		clearCoalesceState();
		recordConversationSources();
		endTurnSpan();
		return false;
	}

	private void pausePlanner() {
		long activeGeneration = sessionCoordinator.activeGeneration();
		cancelPendingTool();
		sessionCoordinator.pause();
		compactionService.reset();
		if (activeGeneration > 0L) {
			turnJournal.markSuperseded(activeGeneration);
		}
		contextAggregator.discardPending();
		pendingSubmitRequest = null;
		awaitingAcceptedReplyRecord = false;
		pendingAcceptedAssistantRawContent = null;
		committedSnapshotGenerations.clear();
		clearCoalesceState();
		recordConversationSources();
		endTurnSpan();
		lifecycleListener.onReset("PLANNER OFF");
	}

	private boolean startCompactionIfIdle() {
		if (sessionCoordinator.hasInFlight() || pendingToolExecution != null || compactionService.hasInFlight()) {
			return true;
		}
		if (fullCompactionWaitingForMicro()) return true;
		// The HTTP adapter prefixes errors with their status. Validation/auth failures cannot
		// recover by resending this history. Preserve it and the visible failure until explicit
		// debug compaction or reset; timeouts, rate limits and server errors remain retryable.
		if (lastCompactionResult != null && lastCompactionResult.failureType() == LlmFailureType.PROVIDER_ERROR
			&& lastCompactionResult.failureMessage() != null
			&& lastCompactionResult.failureMessage().matches("(?s)^Provider returned HTTP (400|401|403|404|405|413|415|422)(?:\\D.*|$)")) {
			return false;
		}
		return compactionService.submit(contextAggregator.buildCompactionConversation());
	}

	private boolean fullCompactionWaitingForMicro() {
		return contextAggregator.microCompactionInFlight();
	}

	private boolean startOverflowFlushIfIdle() {
		if (sessionCoordinator.hasInFlight() || pendingToolExecution != null || compactionService.hasInFlight()) {
			return true;
		}
		PlannerContextSnapshot overflowSnapshot = contextAggregator.freezeOverflowFlushSnapshot();
		if (overflowSnapshot == null) {
			return true;
		}
		sessionCoordinator.submit(overflowSnapshot.withConversation(appendDecisionContext(overflowSnapshot.plannerConversation())), currentTurnContext());
		return true;
	}

	private boolean startTriggeredPlannerTurn() {
		if (coalescePending && clock.millis() < coalesceReadyAtMs) {
			return true;
		}
		if (sessionCoordinator.hasInFlight() || pendingToolExecution != null || compactionService.hasInFlight()) {
			return true;
		}
		dropSupersededGenerationBeforeTriggeredSubmit();
		PlannerContextSnapshot snapshot = contextAggregator.freezePlannerSnapshot(pendingSubmitRequest);
		if (snapshot == null) {
			return true;
		}
		snapshot = withInventoryBootstrapIfAvailable(snapshot);
		sessionCoordinator.submit(snapshot.withConversation(appendDecisionContext(snapshot.plannerConversation())), currentTurnContext());
		return true;
	}

	private void dropSupersededGenerationBeforeTriggeredSubmit() {
		if (coalescePending) {
			contextAggregator.dropSupersededGeneration(coalesceSupersededSnapshot);
			clearCoalesceState();
			return;
		}
		contextAggregator.dropSupersededGeneration(sessionCoordinator.contextSnapshotFor(sessionCoordinator.activeGeneration()));
	}

	private PlannerContextSnapshot withInventoryBootstrapIfAvailable(PlannerContextSnapshot snapshot) {
		if (!inventoryBootstrapPending || snapshot == null || snapshot.mode() != PlannerSnapshotMode.TRIGGERED) {
			return snapshot;
		}
		if (!isWorldLoadedSession(snapshot.request().sessionMode())) {
			return snapshot;
		}
		CompletableFuture<String> future;
		try {
			future = inventoryTool.inspectInventory(INVENTORY_BOOTSTRAP_PROMPT);
		}
		catch (RuntimeException exception) {
			Airicraft.LOGGER.warn("Inventory bootstrap request failed before submission", exception);
			return snapshot;
		}
		if (future == null || !future.isDone()) {
			return snapshot;
		}
		String toolResult;
		try {
			toolResult = future.join();
		}
		catch (RuntimeException exception) {
			Airicraft.LOGGER.warn("Inventory bootstrap request failed", exception);
			return snapshot;
		}
		inventoryBootstrapPending = false;
		return new PlannerContextSnapshot(
			snapshot.request(),
			snapshot.mode(),
			snapshot.triggerBatch(),
			withInventoryBootstrap(snapshot.plannerConversation(), toolResult),
			snapshot.includedSemanticEventSeqNoUpperBound(),
			snapshot.includedSemanticGapVersion(),
			snapshot.renderedAmbientContext(),
			snapshot.renderedTimeContextAtMs()
		);
	}

	private static boolean isWorldLoadedSession(SessionMode mode) {
		return mode == SessionMode.SINGLEPLAYER_LOCAL
			|| mode == SessionMode.SINGLEPLAYER_LAN_HOST
			|| mode == SessionMode.REMOTE_MULTIPLAYER;
	}

	private static LlmConversation withInventoryBootstrap(LlmConversation conversation, String toolResult) {
		ArrayList<LlmChatMessage> messages = new ArrayList<>();
		List<LlmChatMessage> existingMessages = conversation == null ? List.of() : conversation.messages();
		if (!existingMessages.isEmpty()) {
			messages.add(existingMessages.get(0));
		}
		messages.add(LlmChatMessage.assistantToolCall("", inventoryBootstrapToolCall()));
		messages.add(LlmChatMessage.tool(INVENTORY_BOOTSTRAP_TOOL_CALL_ID, inventoryBootstrapToolResultContent(toolResult)));
		if (existingMessages.size() > 1) {
			messages.addAll(existingMessages.subList(1, existingMessages.size()));
		}
		return LlmConversation.of(messages);
	}

	private static PlannerToolCall inventoryBootstrapToolCall() {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("prompt", INVENTORY_BOOTSTRAP_PROMPT);
		return new PlannerToolCall(
			INVENTORY_BOOTSTRAP_TOOL_CALL_ID,
			INVENTORY_TOOL_NAME,
			arguments,
			null,
			null
		);
	}

	private static String inventoryBootstrapToolResultContent(String toolResult) {
		if (toolResult == null || toolResult.isBlank()) {
			return "Tool result for inspect_inventory: none";
		}
		return toolResult;
	}

	private void acceptGeneration(PlannerExecutionResult acceptedResult) {
		commitSnapshotIfNeeded(acceptedResult.generation());
		commitRecordedToolExchanges(acceptedResult.generation());
		contextAggregator.retainConversation(sessionCoordinator.conversationFor(acceptedResult.generation()).withAppended(
			LlmChatMessage.assistant(acceptedResult.response().replyText(), acceptedResult.response().rawAssistantContent())));
		turnJournal.recordAcceptedReply(acceptedResult);
		sessionCoordinator.finishGeneration(acceptedResult.generation(), false);
		committedSnapshotGenerations.remove(acceptedResult.generation());
		boolean hasVisibleReply = acceptedResult.response() != null
			&& acceptedResult.response().replyText() != null
			&& !acceptedResult.response().replyText().isBlank();
		pendingAcceptedAssistantRawContent = hasVisibleReply && acceptedResult.response() != null
			? acceptedResult.response().rawAssistantContent()
			: null;
		if (!contextAggregator.hasQueuedTriggers()) {
			pendingSubmitRequest = null;
			awaitingAcceptedReplyRecord = hasVisibleReply;
			clearCoalesceState();
			endTurnSpan();
			if (!awaitingAcceptedReplyRecord && contextAggregator.hasPendingOverflowFlush()) {
				startQueuedWorkIfPossible();
			}
		}
		else if (!hasVisibleReply) {
			awaitingAcceptedReplyRecord = false;
			startQueuedWorkIfPossible();
		}
		else {
			awaitingAcceptedReplyRecord = true;
		}
	}

	private void commitSnapshotIfNeeded(long generation) {
		if (!committedSnapshotGenerations.add(generation)) {
			return;
		}
		PlannerContextSnapshot snapshot = sessionCoordinator.contextSnapshotFor(generation);
		if (snapshot == null) {
			committedSnapshotGenerations.remove(generation);
			return;
		}
		contextAggregator.commitAcceptedTriggerBatch(snapshot);
	}

	private PlannerExecutionResult continueAfterTool() {
		PendingToolExecution toolExecution = pendingToolExecution;
		if (toolExecution == null || !toolExecution.future().isDone()) {
			return null;
		}

		ToolExecutionOutcome toolOutcome;
		try {
			toolOutcome = toolExecution.future().join();
		}
		catch (CompletionException exception) {
			PlannerToolCall failedToolCall = toolExecution.toolCalls().isEmpty() ? null : toolExecution.toolCalls().getFirst();
			String resultText = VISUAL_TOOL_NAME.equals(normalizedToolName(failedToolCall))
				? "VISION_UNAVAILABLE: vision_failed"
				: failedToolResultText(failedToolCall, exception);
			toolOutcome = new TextToolExecutionOutcome(resultText);
			Airicraft.LOGGER.warn("Planner tool future failed generation={}", toolExecution.generation(), exception);
		}
		finally {
			pendingToolExecution = null;
			captureInFlight = false;
		}

		PlannerContextSnapshot snapshot = sessionCoordinator.contextSnapshotFor(toolExecution.generation());
		if (snapshot == null) {
			return null;
		}

		PlannerRequest followUpRequest = snapshot.request()
			.withToolResult(toolOutcome.toolResultText())
			.withSafetyContext(minimumSafetyEpoch, currentSafetyHoldId);
		PlannerContextSnapshot followUpSnapshot = snapshot.withConversation(
			sessionCoordinator.conversationFor(toolExecution.generation()));
		List<ToolExecutionResult> toolResults = toolOutcome.toolResults(toolExecution.toolCalls());
		for (ToolExecutionResult toolResult : toolResults) {
			recordToolExchange(
				toolExecution.generation(),
				snapshot,
				toolExecution.assistantRawContent(),
				toolResult.toolCall(),
				toolResult.toolResultText(),
				toolResult.imageAttached()
			);
		}
		LlmConversation completedToolConversation = toolOutcome.appendFollowUp(contextAggregator, followUpSnapshot, toolExecution.assistantRawContent(), toolExecution.toolCalls());
		completedToolConversation = contextAggregator.retainConversation(completedToolConversation);
		if (plannerExecutor.managesConversationHistory() && toolOutcome.hasImageAttachment()) backendHistoryImages++;
		// Completed effects remain evidence even when safety invalidates the next decision.
		boolean terminalTool = toolExecution.toolCalls().stream().anyMatch(call -> toolRegistry.endsTurn(call.name()))
			&& !toolOutcome.toolResultText().startsWith("TOOL_ERROR:");
		for (ToolExecutionResult result : toolResults) {
			if (deferredWorkReceipts.defer(result.toolCall(), result.toolResultText())) terminalTool = true;
		}
		boolean safetyContextChanged = isStaleSafetyRequest(snapshot.request());
		boolean toolMayReleaseHold = toolExecution.toolCalls().stream().anyMatch(PlannerOrchestrator::isSideEffectTool);
		boolean sameEpochHoldRelease = safetyContextChanged
			&& snapshot.request().safetyEpoch() == minimumSafetyEpoch
			&& toolMayReleaseHold;
		if (safetyContextChanged && !sameEpochHoldRelease) {
			commitRecordedToolExchanges(toolExecution.generation());
			recordStalePlannerRejection(toolExecution.generation(), snapshot.request(), "TOOL_WAIT");
			sessionCoordinator.finishGeneration(toolExecution.generation(), true);
			committedSnapshotGenerations.remove(toolExecution.generation());
			turnJournal.markSuperseded(toolExecution.generation());
			endTurnSpan();
			if (terminalTool) toolExecution.toolCalls().forEach(call -> toolRegistry.afterResultCommitted(call.name()));
			else if (!safetyLaunchBlocked) {
				startQueuedWorkIfPossible();
			}
			return null;
		}

		if (terminalTool) {
			commitRecordedToolExchanges(toolExecution.generation());
			for (ToolExecutionResult toolResult : toolResults)
				lifecycleListener.onToolCompleted(toolExecution.generation(), toolResult.toolResultText(), toolResult.imageAttached());
			sessionCoordinator.finishGeneration(toolExecution.generation(), true);
			committedSnapshotGenerations.remove(toolExecution.generation());
			endTurnSpan();
			toolExecution.toolCalls().forEach(call -> toolRegistry.afterResultCommitted(call.name()));
			return null;
		}
		sessionCoordinator.submitToolFollowUp(
			toolExecution.generation(),
			followUpRequest,
			appendDecisionContext(completedToolConversation)
		);
		for (ToolExecutionResult toolResult : toolResults) {
			lifecycleListener.onToolCompleted(toolExecution.generation(), toolResult.toolResultText(), toolResult.imageAttached());
		}
		appendToolFollowUpCard(toolExecution);
		return null;
	}

	private CompletableFuture<ToolExecutionOutcome> requestPlannerTools(List<PlannerToolCall> toolCalls, LlmConversation conversation) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return CompletableFuture.completedFuture(new TextToolExecutionOutcome("Tool result: none"));
		}
		if (toolCalls.size() == 1) {
			PlannerToolCall call = toolCalls.getFirst();
			boolean atImageLimit = Math.max(backendHistoryImages, PlannerVisionService.imageCount(conversation)) + toolQueue.images.size() >= plannerMaxImages;
			return requestPlannerTool(call).thenCompose(outcome -> {
				if (!atImageLimit || visionMode != PlannerVisionMode.NATIVE_TOOL_IMAGE
					|| !(outcome instanceof ImageToolExecutionOutcome image)) {
					return CompletableFuture.completedFuture(outcome);
				}
				String prompt = providerImagePrompt(call, image.toolResultText()) + "\nRequested focus: " + toolPrompt(call);
				return plannerVision.describe(image.imageAttachment(), prompt).<ToolExecutionOutcome>handle((text, failure) -> {
					// The metadata remains available, but do not claim an image was attached to this conversation.
					String metadata = image.toolResultText().replace("current first-person view attached.", "current first-person view captured.");
					if (failure != null) {
						Airicraft.LOGGER.warn("Planner image fallback failed tool={}", call.name(), failure);
						return new TextToolExecutionOutcome(metadata + "\nVISION_UNAVAILABLE: planner_vision_failed (image not attached)");
					}
					return new TextToolExecutionOutcome(metadata + "\nVision summary (fresh planner context; image not attached): " + text);
				});
			});
		}
		List<CompletableFuture<ToolExecutionResult>> futures = toolCalls.stream()
			.map(toolCall -> requestPlannerTool(toolCall).handle((outcome, throwable) -> {
				if (throwable != null) {
					String resultText = failedToolResultText(toolCall, throwable);
					Airicraft.LOGGER.warn("Planner batched tool future failed tool={}", toolCall.name(), throwable);
					return new ToolExecutionResult(toolCall, resultText, false);
				}
				return new ToolExecutionResult(toolCall, outcome.toolResultText(), outcome.hasImageAttachment());
			}))
			.toList();
		return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
			.thenApply(ignored -> new MultiTextToolExecutionOutcome(futures.stream()
				.map(CompletableFuture::join)
				.toList()));
	}

	private CompletableFuture<ToolExecutionOutcome> requestPlannerTool(PlannerToolCall toolCall) {
		toolExecutionObserver.beforePlannerToolExecution(toolCall);
		return requestPlannerTool(toolCall, false);
	}

	private CompletableFuture<ToolExecutionOutcome> requestExternalPlannerTool(PlannerToolCall toolCall) {
		toolExecutionObserver.beforePlannerToolExecution(toolCall);
		return requestPlannerTool(toolCall, true);
	}

	private CompletableFuture<ToolExecutionOutcome> requestPlannerTool(PlannerToolCall toolCall, boolean preserveImageAttachment) {
		return switch (normalizedToolName(toolCall)) {
			case VISUAL_TOOL_NAME -> preserveImageAttachment ? requestNativeVisionTool(toolCall) : requestVisionTool(toolCall);
			case PlannerObservation.TOOL_NAME -> CompletableFuture.completedFuture(new TextToolExecutionOutcome(observeToolResult()));
			case INVENTORY_TOOL_NAME -> inventoryTool.inspectInventory(toolPrompt(toolCall)).thenApply(TextToolExecutionOutcome::new);
			case CRAFTABLES_TOOL_NAME -> inventoryTool.checkCraftables(toolCall.arguments()).thenApply(TextToolExecutionOutcome::new);
			case NEARBY_ENTITIES_TOOL_NAME -> inventoryTool.inspectNearbyEntities(toolCall.arguments()).thenApply(TextToolExecutionOutcome::new);
			default -> {
				CompletableFuture<ToolExecutionOutcome> providerToolFuture = toolRegistry.providerFor(toolCall.name())
					.map(provider -> provider.executeResult(toolCall).thenCompose(result -> providerToolOutcome(toolCall, result, preserveImageAttachment)))
					.orElse(null);
				yield providerToolFuture == null
					? actionToolExecutor.execute(toolCall).<ToolExecutionOutcome>thenApply(TextToolExecutionOutcome::new)
					: providerToolFuture;
			}
			};
	}

	private CompletableFuture<ToolExecutionOutcome> providerToolOutcome(
		PlannerToolCall toolCall,
		PlannerProviderToolResult result,
		boolean preserveImageAttachment
	) {
		if (result.imageAttachment() == null) {
			return CompletableFuture.completedFuture(new TextToolExecutionOutcome(result.text()));
		}
		if (preserveImageAttachment || visionMode == PlannerVisionMode.NATIVE_TOOL_IMAGE) {
			return CompletableFuture.completedFuture(new ImageToolExecutionOutcome(result.text(), result.imageAttachment()));
		}
		if (!visionTool.isConfigured()) {
			return CompletableFuture.completedFuture(new TextToolExecutionOutcome(
				result.text() + "\nVISION_UNAVAILABLE: vision_provider_unavailable"
			));
		}

		return visionTool.requestDescription(result.imageAttachment(), providerImagePrompt(toolCall, result.text()))
			.<ToolExecutionOutcome>handle((description, throwable) -> {
				if (throwable == null) {
					return new TextToolExecutionOutcome(result.text() + "\nVision summary: " + description.text());
				}
				String code = visionFailureCode(throwable);
				Airicraft.LOGGER.warn("Provider image tool vision summary failed tool={} code={}", toolCall.name(), code, throwable);
				return new TextToolExecutionOutcome(result.text() + "\nVISION_UNAVAILABLE: " + code);
			});
	}

	private static String providerImagePrompt(PlannerToolCall toolCall, String toolResultText) {
		String normalizedName = normalizedToolName(toolCall);
		if ("take_map_look".equals(normalizedName)) {
			return "Describe this Minecraft map image for a planner that cannot see images. "
				+ "Mention the player marker or center point if visible, whether the minimap or worldmap appears off-center, "
				+ "nearby terrain, water, structures, waypoints, and useful directions. "
				+ "Tool metadata: " + (toolResultText == null || toolResultText.isBlank() ? "none" : toolResultText);
		}
		return "Describe this tool image for a planner that cannot see images. "
			+ "Call out visible state, labels, markers, spatial relationships, and anything actionable. "
			+ "Tool metadata: " + (toolResultText == null || toolResultText.isBlank() ? "none" : toolResultText);
	}

	private CompletableFuture<ToolExecutionOutcome> requestVisionTool(PlannerToolCall toolCall) {
		Context parentContext = currentTurnContext();
		try (Scope scope = parentContext.makeCurrent()) {
			if (visionMode == PlannerVisionMode.EXTERNAL_SUMMARY) {
				if (!visionTool.isConfigured()) {
					return CompletableFuture.completedFuture(new TextToolExecutionOutcome("VISION_UNAVAILABLE: vision_provider_unavailable"));
				}

				return requestCapture(toolCall)
					.handle((captureResult, throwable) -> {
						if (throwable != null) {
							String code = visionFailureCode(throwable);
							Airicraft.LOGGER.warn("Vision tool capture failed code={}", code, throwable);
							return CompletableFuture.<ToolExecutionOutcome>completedFuture(new TextToolExecutionOutcome("VISION_UNAVAILABLE: " + code));
						}
							return visionTool.requestDescription(captureResult.screenshot(), toolPrompt(toolCall))
								.<ToolExecutionOutcome>handle((description, throwable2) -> {
								if (throwable2 == null) {
									return new TextToolExecutionOutcome(appendCaptureMetadata(description.text(), captureResult.metadataLines()));
								}
								String code = visionFailureCode(throwable2);
								Airicraft.LOGGER.warn("Vision tool failed code={}", code, throwable2);
								return new TextToolExecutionOutcome(appendCaptureMetadata("VISION_UNAVAILABLE: " + code, captureResult.metadataLines()));
							});
					})
					.thenCompose(future -> future);
			}

			return requestNativeVisionTool(toolCall);
		}
	}

	private CompletableFuture<ToolExecutionOutcome> requestNativeVisionTool(PlannerToolCall toolCall) {
		Context parentContext = currentTurnContext();
		try (Scope scope = parentContext.makeCurrent()) {
			return requestCapture(toolCall).handle((captureResult, throwable) -> {
				if (throwable == null) {
					return new ImageToolExecutionOutcome(
						nativeToolResultText(captureResult.metadataLines()),
						new LlmImageAttachment(mimeType(captureResult.screenshot()), captureResult.screenshot().imageBytes(), imageDetail)
					);
				}
				String code = visionFailureCode(throwable);
				Airicraft.LOGGER.warn("Vision tool capture failed code={}", code, throwable);
				return new TextToolExecutionOutcome("VISION_UNAVAILABLE: " + code);
			});
		}
	}

	private CompletableFuture<ViewCaptureResult> requestCapture(PlannerToolCall toolCall) {
		captureInFlight = true;
		try {
			return visionTool.requestCapture(viewCaptureRequest(toolCall)).whenComplete((capture, throwable) -> captureInFlight = false);
		}
		catch (RuntimeException exception) {
			captureInFlight = false;
			return CompletableFuture.failedFuture(exception);
		}
	}

	private static ViewCaptureRequest viewCaptureRequest(PlannerToolCall toolCall) {
		JsonObject arguments = toolCall == null ? null : toolCall.arguments();
		if (arguments == null) {
			return ViewCaptureRequest.current();
		}
		String direction = stringArgument(arguments, "direction");
		if (direction != null) {
			return ViewCaptureRequest.direction(direction);
		}
		String targetPlayer = stringArgument(arguments, "targetPlayer");
		if (targetPlayer != null) {
			return ViewCaptureRequest.player(targetPlayer);
		}
		if (arguments.has("x") && arguments.has("y") && arguments.has("z")) {
			return ViewCaptureRequest.block(
				arguments.get("x").getAsInt(),
				arguments.get("y").getAsInt(),
				arguments.get("z").getAsInt()
			);
		}
		return ViewCaptureRequest.current();
	}

	private static String nativeToolResultText(List<String> metadataLines) {
		return appendCaptureMetadata(NATIVE_TOOL_RESULT_TEXT, metadataLines);
	}

	private static String appendCaptureMetadata(String text, List<String> metadataLines) {
		if (metadataLines == null || metadataLines.isEmpty()) {
			return text;
		}
		return text + "\n" + String.join("\n", metadataLines);
	}

	private static String stringArgument(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return null;
		}
		try {
			String value = object.get(key).getAsString();
			return value == null || value.isBlank() ? null : value.trim();
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	private void completeCompaction(CompactionExecutionResult compactionResult) {
		lastCompactionResult = compactionResult;
		lifecycleListener.onCompactionCompleted(compactionResult);
		turnJournal.recordCompaction(compactionResult);
		recordConversationSources();
		if (compactionResult.succeeded()) {
			contextAggregator.recordObservedUsage(compactionResult.usage());
			contextAggregator.applyCheckpoint(compactionResult.checkpoint());
			decisionRefreshPending = true;
			return;
		}
		Airicraft.LOGGER.warn("Planner compaction failed message={}", summarizeForLog(compactionResult.failureMessage()));
		contextAggregator.onCompactionFailure();
	}

	private void cancelPendingTool() {
		toolQueue.tools.clear((call, workId) -> {});
		toolQueue.loneCall = null;
		toolQueue.reviewedLoneCall = null;
		toolQueue.reports.clear(); toolQueue.images.clear();
		if (toolQueue.abort != null) toolQueue.abort.cancel(true);
		toolQueue.abort = null;
		if (pendingToolExecution != null) {
			pendingToolExecution.future().cancel(true);
			pendingToolExecution = null;
		}
		captureInFlight = false;
	}

	private void recordToolExchange(
		long generation,
		PlannerContextSnapshot snapshot,
		JsonElement assistantRawContent,
		PlannerToolCall toolCall,
		String toolResultText,
		boolean imageAttached
	) {
		turnJournal.recordToolExchange(generation, snapshot, assistantRawContent, toolCall, toolResultText, imageAttached);
		if (toolRegistry.endsTurn(toolCall.name()))
			debugRecorder.recordTerminalTool(snapshot.request().tick(), toolCall, toolResultText, imageAttached);
		lifecycleListener.onToolExchange(toolCall, toolResultText, imageAttached);
	}

	private void commitRecordedToolExchanges(long generation) {
		List<PlannerTurnEvent> exchanges = recordedToolExchanges(generation);
		for (PlannerTurnEvent exchange : exchanges) {
			if (exchange.toolCall() != null) {
				contextAggregator.recordAcceptedToolExchange(
					exchange.toolCall(),
					exchange.toolResultText(),
					exchange.tick(),
					exchange.request() == null ? exchange.timestampMs() : exchange.request().timestampMs()
				);
			}
			else {
				contextAggregator.recordAcceptedToolExchange(
					exchange.assistantRawContent(),
					exchange.toolResultText(),
					exchange.tick(),
					exchange.request() == null ? exchange.timestampMs() : exchange.request().timestampMs()
				);
			}
		}
	}

	private List<PlannerTurnEvent> recordedToolExchanges(long generation) {
		return turnJournal.toolExchanges(generation);
	}

	private PlannerContextSnapshot withRecordedToolExchanges(
		PlannerContextSnapshot snapshot,
		List<PlannerTurnEvent> exchanges
	) {
		if (snapshot == null || exchanges == null || exchanges.isEmpty()) {
			return snapshot;
		}
		return new PlannerContextSnapshot(
			snapshot.request(),
			snapshot.mode(),
			snapshot.triggerBatch(),
			conversationProjector.appendToolExchanges(snapshot.plannerConversation(), exchanges),
			snapshot.includedSemanticEventSeqNoUpperBound(),
			snapshot.includedSemanticGapVersion(),
			snapshot.renderedAmbientContext(),
			snapshot.renderedTimeContextAtMs()
		);
	}

	private int completedToolCallCount(long generation) {
		return turnJournal.toolExchanges(generation).size();
	}

	private void armCoalesceWindow() {
		coalescePending = true;
		coalesceWindowMs = computeCoalesceWindowMs(contextAggregator.queuedTriggerCount());
		coalesceReadyAtMs = clock.millis() + coalesceWindowMs;
	}

	private long computeCoalesceWindowMs(int queuedTriggerCount) {
		if (queuedTriggerCount <= 1) {
			return 0L;
		}
		long windowMs = (long) (queuedTriggerCount - 1) * coalesceStepMs;
		return Math.max(coalesceMinMs, Math.min(coalesceMaxMs, windowMs));
	}

	private void clearCoalesceState() {
		coalescePending = false;
		coalesceReadyAtMs = -1L;
		coalesceWindowMs = 0L;
		coalesceSupersededSnapshot = null;
	}

	private boolean isStaleSafetyRequest(PlannerRequest request) {
		return !decisionAuthority.getAsBoolean() || decisionContextSource != null && decisionWorldSessionId != null
			&& !decisionWorldSessionId.equals(decisionContextSource.get().worldSessionId()) || request != null && (
			request.safetyEpoch() < minimumSafetyEpoch
				|| request.safetyEpoch() == minimumSafetyEpoch
				&& !Objects.equals(request.safetyHoldId(), currentSafetyHoldId)
		);
	}

	private void rejectStalePlannerResult(PlannerExecutionResult result) {
		recordStalePlannerRejection(
			result.generation(),
			result.request(),
			result.phase() == null ? "UNKNOWN" : result.phase().name()
		);
		turnJournal.markSuperseded(result.generation());
		sessionCoordinator.finishGeneration(result.generation(), true);
		committedSnapshotGenerations.remove(result.generation());
		endTurnSpan();
		// Wait for an identified ownership/event wake; do not replay the obsolete trigger.
		pendingSubmitRequest = null;
		clearCoalesceState();
	}

	private void recordStalePlannerRejection(long generation, PlannerRequest request, String phase) {
		stalePlannerRejections.addLast(new StalePlannerRejection(
			generation,
			request == null ? 0L : request.safetyEpoch(),
			minimumSafetyEpoch,
			request == null ? null : request.safetyHoldId(),
			currentSafetyHoldId,
			phase
		));
	}

	private static String mimeType(FirstPersonScreenshotService.CapturedScreenshot capture) {
		return "image/" + capture.format().toLowerCase(Locale.ROOT);
	}

	private void recordSubmittedConversation(
		long generation,
		int attempt,
		PlannerSessionPhase phase,
		PlannerRequest request,
		LlmConversation conversation
	) {
		lifecycleListener.onConversationSubmitted(generation, attempt, phase, request, conversation);
		turnJournal.recordSubmission(generation, attempt, phase, request, conversation);
		debugRecorder.recordPlannerSubmission(generation, attempt, phase, clock.millis(), conversation);
		recordConversationSources();
	}

	private List<PlannerToolCall> effectiveToolCalls(PlannerResponse response) {
		if (response == null) {
			return List.of();
		}
		if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
			return response.toolCalls();
		}
		PlannerToolCall legacyToolCall = legacyToolCall(response.toolRequest());
		return legacyToolCall == null ? List.of() : List.of(legacyToolCall);
	}

	private boolean isValidToolCall(PlannerToolCall toolCall) {
		String name = normalizedToolName(toolCall);
		if (!toolRegistry.isActiveTool(name)) {
			return false;
		}
		return switch (name) {
			case VISUAL_TOOL_NAME -> visionMode == PlannerVisionMode.NATIVE_TOOL_IMAGE || !toolPrompt(toolCall).isBlank();
			default -> true;
			};
	}

	private static String normalizedToolType(PlannerToolRequest toolRequest) {
		return toolRequest == null || toolRequest.type() == null ? "" : toolRequest.type().toLowerCase(Locale.ROOT);
	}

	private static String normalizedToolName(PlannerToolCall toolCall) {
		return toolCall == null ? "" : PlannerToolCatalog.normalizeName(toolCall.name());
	}

	private static PlannerToolCall legacyToolCall(PlannerToolRequest toolRequest) {
		String name = normalizedToolType(toolRequest);
		if (name.isBlank()) {
			return null;
		}
		JsonObject arguments = new JsonObject();
		if (toolRequest.prompt() != null && !toolRequest.prompt().isBlank()) {
			arguments.addProperty("prompt", toolRequest.prompt());
		}
		return new PlannerToolCall("legacy_" + name, name, arguments, null, null);
	}

	private static String toolPrompt(PlannerToolCall toolCall) {
		if (toolCall == null || toolCall.arguments() == null || !toolCall.arguments().has("prompt")) {
			return "";
		}
		try {
			return toolCall.arguments().get("prompt").getAsString();
		}
		catch (RuntimeException exception) {
			return "";
		}
	}

	private static boolean hasToolCompatibleIntent(PlannerResponse response) {
		return switch (toolIntentType(response)) {
			case "none", "reply_only", "ask_clarification", "acknowledge_failure", "mission_update", "job_update" -> true;
			default -> false;
		};
	}

	private static String toolIntentType(PlannerResponse response) {
		PlannerIntent intent = response.intent();
		return intent == null || intent.type() == null ? "none" : intent.type();
	}

	private PlannerExecutionResult parseFailure(PlannerExecutionResult baseResult, String message) {
		return new PlannerExecutionResult(
			baseResult.request(),
			null,
			LlmUsageSnapshot.unknown(),
			LlmFailureType.PARSE_ERROR,
			message,
			baseResult.generation(),
			baseResult.attempt(),
			baseResult.phase(),
			false
		);
	}

	private static String buildTurnId(PlannerRequest request) {
		if (request == null) {
			return "session:none";
		}
		String sender = request.senderName() == null || request.senderName().isBlank() ? "unknown" : request.senderName();
		String mode = request.sessionMode() == null ? "unknown_mode" : request.sessionMode().name();
		return "session:" + mode + ":sender=" + sender + ":tick=" + request.tick();
	}

	private Context currentTurnContext() {
		return turnContext == null ? Context.current() : turnContext;
	}

	private void endTurnSpan() {
		if (turnContext != null) {
			observability.endSpan(turnContext);
			turnContext = null;
		}
	}

	private void appendAssistantOutcomeCard(PlannerExecutionResult result) {
		if (result == null || result.response() == null) {
			return;
		}
		String text = assistantOutcomeText(result.response());
		if (text == null || text.isBlank()) {
			return;
		}
		appendConversationCard(new PlannerConversationDebugMessage(
			"assistant",
			PlannerConversationDebugKind.ASSISTANT_TURN,
			text,
			result.generation(),
			result.phase().name(),
			result.attempt(),
			false
		));
	}

	private void appendToolRequestCard(PlannerExecutionResult result, List<PlannerToolCall> toolCalls) {
		if (result == null || toolCalls == null || toolCalls.isEmpty()) {
			return;
		}
		appendOperationCard(
			result.generation(),
			result.phase().name(),
			result.attempt(),
			toolCallsSummary(toolCalls)
		);
	}

	private void appendToolFollowUpCard(PendingToolExecution toolExecution) {
		if (toolExecution == null || toolExecution.toolSummary() == null || toolExecution.toolSummary().isBlank()) {
			return;
		}
		PlannerSessionSnapshot activeSnapshot = sessionCoordinator.activeSnapshot();
		String phase = activeSnapshot == null || activeSnapshot.phase() == null ? PlannerSessionPhase.TOOL_FOLLOW_UP.name() : activeSnapshot.phase().name();
		int attempt = activeSnapshot == null ? 0 : activeSnapshot.attemptCount();
		appendOperationCard(toolExecution.generation(), phase, attempt, toolExecution.toolSummary());
	}

	private void appendOperationCards(PlannerExecutionResult result) {
		if (result == null || result.response() == null) {
			return;
		}
		PlannerResponse response = result.response();
		String intentSummary = intentOperationSummary(response.intent());
		if (intentSummary != null) {
			appendOperationCard(result.generation(), result.phase().name(), result.attempt(), intentSummary);
		}
		for (String policySummary : eventPolicyOperationSummaries(response.eventPolicyChanges())) {
			appendOperationCard(result.generation(), result.phase().name(), result.attempt(), policySummary);
		}
	}

	private void appendOperationCard(long generation, String phase, int attempt, String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		appendConversationCard(new PlannerConversationDebugMessage(
			"assistant",
			PlannerConversationDebugKind.TASK,
			text,
			generation,
			phase,
			attempt,
			false
		));
	}

	private void appendFailureCard(PlannerExecutionResult result) {
		if (result == null || result.failureType() == null) {
			return;
		}
		String text = result.failureType().name() + ": " + (result.failureMessage() == null || result.failureMessage().isBlank()
			? "Planner execution failed"
			: result.failureMessage());
		appendConversationCard(new PlannerConversationDebugMessage(
			"system",
			PlannerConversationDebugKind.FAILURE,
			text,
			result.generation(),
			result.phase() == null ? "UNKNOWN" : result.phase().name(),
			result.attempt(),
			false
		));
	}

	private void appendConversationCard(PlannerConversationDebugMessage message) {
		if (message == null) {
			return;
		}
		turnJournal.recordDebugCard(message);
		recordConversationSources();
	}

	private void recordConversationSources() {
		debugRecorder.recordConversationSources(conversationDebugSnapshot(), projectedConversationDebugSnapshot());
	}

	private record ToolExecutionResult(
		PlannerToolCall toolCall,
		String toolResultText,
		boolean imageAttached
	) {
		private ToolExecutionResult {
			toolResultText = toolResultText == null ? "" : toolResultText;
		}
	}

	private sealed interface ToolExecutionOutcome permits TextToolExecutionOutcome, ImageToolExecutionOutcome, MultiTextToolExecutionOutcome {
		String toolResultText();

		List<ToolExecutionResult> toolResults(List<PlannerToolCall> toolCalls);

		boolean hasImageAttachment();

		LlmConversation appendFollowUp(
			PlannerContextAggregator contextAggregator,
			PlannerContextSnapshot snapshot,
			JsonElement assistantRawContent,
			List<PlannerToolCall> toolCalls
		);
	}

	private record TextToolExecutionOutcome(String toolResultText) implements ToolExecutionOutcome {
		@Override
		public List<ToolExecutionResult> toolResults(List<PlannerToolCall> toolCalls) {
			PlannerToolCall toolCall = toolCalls == null || toolCalls.isEmpty() ? null : toolCalls.getFirst();
			return List.of(new ToolExecutionResult(toolCall, toolResultText, false));
		}

		@Override
		public boolean hasImageAttachment() {
			return false;
		}

		@Override
		public LlmConversation appendFollowUp(
			PlannerContextAggregator contextAggregator,
			PlannerContextSnapshot snapshot,
			JsonElement assistantRawContent,
			List<PlannerToolCall> toolCalls
		) {
			PlannerToolCall toolCall = toolCalls == null || toolCalls.isEmpty() ? null : toolCalls.getFirst();
			if (toolCall != null) {
				return contextAggregator.buildPlannerFollowUpConversation(snapshot, toolCall, toolResultText);
			}
			return contextAggregator.buildPlannerFollowUpConversation(snapshot, assistantRawContent, toolResultText);
		}
	}

	private record ImageToolExecutionOutcome(String toolResultText, LlmImageAttachment imageAttachment) implements ToolExecutionOutcome {
		@Override
		public List<ToolExecutionResult> toolResults(List<PlannerToolCall> toolCalls) {
			PlannerToolCall toolCall = toolCalls == null || toolCalls.isEmpty() ? null : toolCalls.getFirst();
			return List.of(new ToolExecutionResult(toolCall, toolResultText, imageAttachment != null));
		}

		@Override
		public boolean hasImageAttachment() {
			return imageAttachment != null;
		}

		@Override
		public LlmConversation appendFollowUp(
			PlannerContextAggregator contextAggregator,
			PlannerContextSnapshot snapshot,
			JsonElement assistantRawContent,
			List<PlannerToolCall> toolCalls
		) {
			PlannerToolCall toolCall = toolCalls == null || toolCalls.isEmpty() ? null : toolCalls.getFirst();
			if (toolCall != null) {
				return contextAggregator.buildPlannerFollowUpConversation(snapshot, toolCall, toolResultText, imageAttachment);
			}
			return contextAggregator.buildPlannerFollowUpConversation(snapshot, assistantRawContent, toolResultText, imageAttachment);
		}
	}

	private record MultiTextToolExecutionOutcome(List<ToolExecutionResult> results) implements ToolExecutionOutcome {
		private MultiTextToolExecutionOutcome {
			results = results == null ? List.of() : List.copyOf(results);
		}

		@Override
		public String toolResultText() {
			if (results.isEmpty()) {
				return "Tool result: none";
			}
			return String.join("\n", results.stream()
				.map(ToolExecutionResult::toolResultText)
				.toList());
		}

		@Override
		public List<ToolExecutionResult> toolResults(List<PlannerToolCall> toolCalls) {
			return results;
		}

		@Override
		public boolean hasImageAttachment() {
			return results.stream().anyMatch(ToolExecutionResult::imageAttached);
		}

		@Override
		public LlmConversation appendFollowUp(
			PlannerContextAggregator contextAggregator,
			PlannerContextSnapshot snapshot,
			JsonElement assistantRawContent,
			List<PlannerToolCall> toolCalls
		) {
			return contextAggregator.buildPlannerFollowUpConversation(
				snapshot,
				results.stream().map(ToolExecutionResult::toolCall).toList(),
				results.stream().map(ToolExecutionResult::toolResultText).toList()
			);
		}
	}

	private record PendingToolExecution(
		long generation,
		String toolSummary,
		CompletableFuture<ToolExecutionOutcome> future,
		JsonElement assistantRawContent,
		List<PlannerToolCall> toolCalls
	) {
		private PendingToolExecution {
			toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		}
	}

	private static String visionFailureCode(Throwable throwable) {
		Throwable cause = throwable instanceof CompletionException completionException && completionException.getCause() != null
			? completionException.getCause()
			: throwable;
		if (cause instanceof BridgeUnavailableException bridgeUnavailableException) {
			return bridgeUnavailableException.code();
		}
		if (cause instanceof LlmBackendException backendException) {
			return switch (backendException.failureType()) {
				case PROVIDER_UNAVAILABLE -> "vision_provider_unavailable";
				case TIMEOUT -> "vision_timeout";
				case PROVIDER_ERROR, PARSE_ERROR -> "vision_failed";
			};
		}
		return "vision_failed";
	}

	private static String summarizeForLog(String text) {
		return OpenAiCompatibleChatClient.summarizeForLog(text);
	}

	private static String assistantOutcomeText(PlannerResponse response) {
		if (response == null) {
			return null;
		}
		if (response.replyText() != null && !response.replyText().isBlank()) {
			return response.replyText();
		}
		PlannerIntent intent = response.intent();
		if (intent == null || intent.type() == null || intent.type().isBlank()) {
			return null;
		}
		return switch (intent.type()) {
			case "ask_clarification" -> "Asked for clarification.";
			case "acknowledge_failure" -> "Acknowledged failure.";
			case "set_goal", "clear_goal", "reply_only", "none" -> null;
			default -> "Applied intent: " + intent.type() + ".";
		};
	}

	private static String toolCallSummary(PlannerToolCall toolCall) {
		if (toolCall == null || toolCall.name() == null || toolCall.name().isBlank()) {
			return null;
		}
		StringBuilder summary = new StringBuilder("Tool call: ").append(toolCall.name());
		if (toolCall.narration() != null && !toolCall.narration().isBlank()) {
			summary.append(" | narration: ").append(toolCall.narration());
		}
		String prompt = toolPrompt(toolCall);
		if (!prompt.isBlank()) {
			summary.append(" | ").append(prompt);
		}
		return summary.toString();
	}

	private static String toolCallsSummary(List<PlannerToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return null;
		}
		if (toolCalls.size() == 1) {
			return toolCallSummary(toolCalls.getFirst());
		}
		return "Tool calls: " + toolCallNames(toolCalls);
	}

	private static String toolCallNames(List<PlannerToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return "";
		}
		return String.join(",", toolCalls.stream()
			.map(toolCall -> PlannerToolCatalog.normalizeName(toolCall == null ? "" : toolCall.name()))
			.filter(name -> !name.isBlank())
			.toList());
	}

	private static String failedToolResultText(PlannerToolCall toolCall, Throwable throwable) {
		String name = normalizedToolName(toolCall);
		String code = throwable instanceof CompletionException completionException && completionException.getCause() != null
			? completionException.getCause().getClass().getSimpleName()
			: throwable == null ? "unknown" : throwable.getClass().getSimpleName();
		return "Tool result for " + (name.isBlank() ? "unknown_tool" : name) + ": failed error=" + code;
	}

	private static String intentOperationSummary(PlannerIntent intent) {
		if (intent == null || intent.type() == null || intent.type().isBlank()) {
			return null;
		}
		return switch (intent.type()) {
			case "set_goal" -> {
				if (intent.goalType() != null && intent.targetPlayer() != null && !intent.targetPlayer().isBlank()) {
					yield "Goal call: " + intent.goalType().name() + " -> " + intent.targetPlayer() + ".";
				}
				if (intent.goalType() != null) {
					yield "Goal call: " + intent.goalType().name() + ".";
				}
				yield "Goal call: set_goal.";
			}
			case "clear_goal" -> "Goal call: clear current goal.";
			case "job_update" -> intent.activeJob() == null ? "Job call: job_update." : "Job call: " + intent.activeJob().type().name() + ".";
			default -> null;
		};
	}

	private static List<String> eventPolicyOperationSummaries(EventPolicyChanges changes) {
		if (changes == null) {
			return List.of();
		}
		ArrayList<String> summaries = new ArrayList<>();
		if (changes.clearAll()) {
			summaries.add("Event filter: clear all rules.");
		}
		for (String ruleId : changes.removeRuleIds()) {
			if (ruleId != null && !ruleId.isBlank()) {
				summaries.add("Event filter: remove " + ruleId + ".");
			}
		}
		for (EventPolicyRuleUpsert upsert : changes.upserts()) {
			String summary = eventPolicyUpsertSummary(upsert);
			if (summary != null) {
				summaries.add(summary);
			}
		}
		return List.copyOf(summaries);
	}

	private static String eventPolicyUpsertSummary(EventPolicyRuleUpsert upsert) {
		if (upsert == null) {
			return null;
		}
		StringBuilder builder = new StringBuilder("Event filter: upsert");
		if (upsert.ruleId() != null && !upsert.ruleId().isBlank()) {
			builder.append(' ').append(upsert.ruleId());
		}
		if (upsert.effect() != null && !upsert.effect().isBlank()) {
			builder.append(" -> ").append(upsert.effect().trim().toUpperCase(Locale.ROOT));
		}
		String matchSummary = eventPolicyMatchSummary(upsert.match());
		if (matchSummary != null) {
			builder.append(" on ").append(matchSummary);
		}
		return builder.append('.').toString();
	}

	private static String eventPolicyMatchSummary(EventPolicyMatch match) {
		if (match == null || !match.isValid()) {
			return null;
		}
		ArrayList<String> filters = new ArrayList<>();
		appendMatchFilter(filters, "player", match.player());
		appendMatchFilter(filters, "speaker", match.speaker());
		appendMatchFilter(filters, "actor", match.actor());
		appendMatchFilter(filters, "itemId", match.itemId());
		appendMatchFilter(filters, "damageTypeId", match.damageTypeId());
		appendMatchFilter(filters, "attackerName", match.attackerName());
		if (filters.isEmpty()) {
			return match.eventType();
		}
		return match.eventType() + " [" + String.join(", ", filters) + "]";
	}

	private static void appendMatchFilter(List<String> filters, String key, String value) {
		if (value != null && !value.isBlank()) {
			filters.add(key + "=" + value);
		}
	}
}
