package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.attention.Wake;
import ai.moeru.airicraft.agent.attention.WakeScheduler;
import ai.moeru.airicraft.agent.events.EventStream;
import ai.moeru.airicraft.agent.events.EventCause;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.llm.CompactionExecutionResult;
import ai.moeru.airicraft.agent.llm.ExternalPlannerToolResult;
import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerExecutionResult;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.llm.PlannerOrchestratorDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.llm.PlannerRequestSeed;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerBatch;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import ai.moeru.airicraft.agent.llm.StalePlannerRejection;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import com.google.gson.JsonObject;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class DialogueRuntime {
	private final PlannerOrchestrator plannerOrchestrator;
	private PlannerOrchestrator thinkingOrchestrator;
	private ai.moeru.airicraft.agent.llm.delegation.PlannerDelegation delegation;
	private boolean delegationWorkIdle;
	private PlannerOrchestrator visibleReplyOwner;
	private long delegationEventCursor;
	private final ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore plannerGoal;
	private long nextGoalContinuationTick;
	private ai.moeru.airicraft.agent.work.WorkSnapshot acceptedWork;
	private final Clock clock;
	private final int maxRecentTurns;
	private final List<DialogueTurn> recentTurns = new ArrayList<>();
	private final WakeScheduler wakeScheduler = new WakeScheduler();
	private final Deque<PendingDialogueReply> pendingVisibleReplies = new ArrayDeque<>();

	private DialogueState state = DialogueCore.initialState();
	private int queuedTimeoutInjections;
	private boolean pendingTimeoutVisibleReply;
	private boolean hostedAutoResetAttempted;
	private DialogueMessages messages = DialogueMessages.DEFAULTS;

	private DialogueCore.ResetGuidance resetGuidance() {
		if (!Boolean.getBoolean("airicraft.hostedPlaytestAutoReset")) return DialogueCore.ResetGuidance.MANUAL;
		return hostedAutoResetAttempted
			? DialogueCore.ResetGuidance.HOSTED_AUTO_EXHAUSTED
			: DialogueCore.ResetGuidance.HOSTED_AUTO_PENDING;
	}
	private long userGuidanceRevision;
	private long safetyEpoch;
	private String safetyHoldId;
	private boolean reflexActive;
	private String lastSupervisedHold;
	private Object observedObjective;
	private long blockedEventCursor;
	private boolean externalDriverActive;
	private long nextPendingReplyId = 1L;

	public DialogueRuntime(PlannerOrchestrator plannerOrchestrator, int maxRecentTurns) {
		this(plannerOrchestrator, maxRecentTurns, Clock.systemDefaultZone());
	}

	public DialogueRuntime(PlannerOrchestrator plannerOrchestrator, int maxRecentTurns, Clock clock) {
		this(plannerOrchestrator, maxRecentTurns, clock, null);
	}

	public DialogueRuntime(PlannerOrchestrator plannerOrchestrator, int maxRecentTurns, Clock clock,
		ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore plannerGoal) {
		this.plannerGoal = plannerGoal;
		this.plannerOrchestrator = plannerOrchestrator;
		this.clock = clock;
		this.maxRecentTurns = Math.max(1, maxRecentTurns);
	}

	public void configurePolicyContinuation(ai.moeru.airicraft.agent.llm.PolicyContinuationPlanner planner,
		java.util.function.Predicate<JsonObject> blockMatches, ai.moeru.airicraft.agent.llm.PlannerActionToolExecutor execute) {
		policyContinuation = planner;
		continuationBlockMatches = blockMatches;
		continuationExecutor = execute;
	}

	private ai.moeru.airicraft.agent.llm.PolicyContinuationPlanner policyContinuation;
	private java.util.function.Predicate<JsonObject> continuationBlockMatches;
	private ai.moeru.airicraft.agent.llm.PlannerActionToolExecutor continuationExecutor;
	private ai.moeru.airicraft.agent.work.WorkSnapshot continuationParent;
	private CompletableFuture<String> continuationAdmission;
	private ai.moeru.airicraft.agent.llm.PlannerToolCall admittedContinuation;

	private boolean pollPolicyContinuation(long tick, EventStream events) {
		if (policyContinuation == null || decisionContextSource == null) return false;
		boolean handled = false;
		if (continuationAdmission != null) {
			if (!continuationAdmission.isDone()) return true;
			String receipt;
			try { receipt = continuationAdmission.join(); }
			catch (RuntimeException error) { receipt = "TOOL_ERROR: continuation admission failed"; }
			boolean accepted = !receipt.startsWith("TOOL_ERROR:") && !receipt.startsWith("TOOL_UNAVAILABLE:");
			events.from("DialogueRuntime").publish(tick, "policy.continuation." + (accepted ? "accepted" : "rejected"),
				Map.of("source", admittedContinuation.arguments().get("source").getAsString(),
					"input", admittedContinuation.arguments().get("input"), "receipt", receipt));
			continuationAdmission = null;
			admittedContinuation = null;
			handled = true;
		}
		boolean available = !externalDriverActive && plannerEnabled() && llmAvailable() && !isDegraded()
			&& !reflexActive && safetyHoldId == null && (delegation == null || !delegation.active())
			&& !plannerOrchestrator.hasInFlight() && (plannerGoal == null || plannerGoal.active());
		if (!available) policyContinuation.discard("planner_unavailable");
		else if (continuationParent != null) {
			var context = decisionContextSource.get();
			if (!continuationParent.state().terminal()) policyContinuation.start(continuationParent, context,
				plannerOrchestrator.delegationContext(), userGuidanceRevision, safetyEpoch);
			var candidate = policyContinuation.poll(context, continuationParent, userGuidanceRevision, safetyEpoch, continuationBlockMatches);
			if (candidate != null) {
				// Resolve and admit on the same client thread as the fresh guard checks.
				// The regular action executor still enforces policy, task and safety ownership.
				continuationParent = null;
				wakeScheduler.clearTaskWakes();
				admittedContinuation = candidate;
				continuationAdmission = continuationExecutor.execute(candidate);
				handled = true;
			}
		}
		for (var event : policyContinuation.drainEvents()) events.from("DialogueRuntime").publish(tick, "policy.continuation." + event.get("state"), event);
		return handled;
	}

	/** Lines sent without a planner reply, voiced by the character card. */
	public void configureMessages(DialogueMessages nextMessages) {
		messages = Objects.requireNonNull(nextMessages, "messages");
	}

	public void configureDelegation(PlannerOrchestrator thinker, ai.moeru.airicraft.agent.llm.delegation.PlannerDelegation handoff) {
		thinkingOrchestrator = Objects.requireNonNull(thinker);
		delegation = Objects.requireNonNull(handoff);
	}

	private java.util.function.Supplier<ai.moeru.airicraft.agent.llm.PlannerDecisionContext> decisionContextSource;
	public void configureDecisionContext(java.util.function.Supplier<ai.moeru.airicraft.agent.llm.PlannerDecisionContext> source) {
		decisionContextSource = source;
		plannerOrchestrator.configureDecisionAuthority(() -> delegation == null || !delegation.active());
		if (thinkingOrchestrator != null) thinkingOrchestrator.configureDecisionAuthority(() -> delegation != null && delegation.active());
		plannerOrchestrator.configureDecisionContext(() -> source.get().forOwner("controller"));
		if (thinkingOrchestrator != null) thinkingOrchestrator.configureDecisionContext(() -> source.get().forOwner("thinking"));
	}

	/**
	 * Receives wake audit records. {@code tick} and the {@code wakeTick} field are when the wake was created;
	 * sinks that know the current tick should stamp the record with it so the entry matches its server tick.
	 */
	public interface WakeAuditSink {
		WakeAuditSink NO_OP = (tick, kind, fields) -> { };
		void record(long tick, String kind, Map<String, Object> fields);
	}

	private WakeAuditSink wakeAudit = WakeAuditSink.NO_OP;

	public void configureWakeAudit(WakeAuditSink sink) {
		wakeAudit = Objects.requireNonNull(sink);
	}

	public String decisionOwner() { return delegation != null && delegation.active() ? "thinking" : "controller"; }

	/** Seed scenario intent once; ordinary goal scheduling owns every subsequent decision. */
	public void startEvaluationGoal(String objective) throws java.io.IOException {
		plannerGoal.set(objective);
		nextGoalContinuationTick = 0;
	}

	public java.util.Optional<ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore.Goal> plannerGoalSnapshot() {
		return java.util.Optional.ofNullable(plannerGoal == null ? null : plannerGoal.snapshot());
	}

	public long gameplayDecisionCount() {
		return planners().stream().mapToLong(PlannerOrchestrator::gameplayDecisionCount).sum();
	}

	public Object currentPlannerObjective() {
		return plannerGoal == null || plannerGoal.snapshot() == null ? Map.of() : plannerGoal.snapshot();
	}

	private PlannerOrchestrator activePlanner() {
		return delegation != null && delegation.active() ? thinkingOrchestrator : plannerOrchestrator;
	}

	private List<PlannerOrchestrator> planners() {
		return thinkingOrchestrator == null ? List.of(plannerOrchestrator) : List.of(plannerOrchestrator, thinkingOrchestrator);
	}

	public void updateGameplayWorkIdle(boolean idle) { delegationWorkIdle = idle; }

	public void observeAcceptedWork(ai.moeru.airicraft.agent.work.WorkSnapshot work) {
		if (!work.state().terminal() && work.state() != ai.moeru.airicraft.agent.work.WorkSnapshot.State.PAUSED) acceptedWork = work;
		if (work.label().equals("run_policy") && work.parentWorkId().isBlank()) continuationParent = work;
	}

	public void observeWork(List<ai.moeru.airicraft.agent.work.WorkSnapshot> work) {
		if (continuationParent != null) continuationParent = work.stream()
			.filter(current -> current.handle().equals(continuationParent.handle())).findFirst().orElse(null);
		if (acceptedWork == null) return;
		for (var current : work) {
			if (current.handle().equals(acceptedWork.handle())
				&& (current.state().terminal() || current.state() == ai.moeru.airicraft.agent.work.WorkSnapshot.State.PAUSED
					|| current.phase().equals("CHECK_OUTPUT"))) {
				acceptedWork = null;
				nextGoalContinuationTick = 0;
				return;
			}
		}
	}

	public boolean delegationWorkIdle() { return delegationWorkIdle && !reflexActive; }

	public Map<String, Object> system2Snapshot() {
		if (delegation == null) return Map.of("role", "planner", "delegationEnabled", false);
		return Map.of("ownership", delegation.snapshot(),
			"controllerInFlight", plannerOrchestrator.hasInFlight(), "thinkingInFlight", thinkingOrchestrator.hasInFlight(),
			"controllerHistoryMessages", plannerOrchestrator.canonicalConversationDebugSnapshot().messages().size(),
			"thinkingHistoryMessages", thinkingOrchestrator.canonicalConversationDebugSnapshot().messages().size());
	}

	private void resetPlanners(String reason) {
		if (policyContinuation != null) policyContinuation.discard(reason);
		continuationParent = null;
		continuationAdmission = null;
		admittedContinuation = null;
		lastSupervisedHold = null;
		acceptedWork = null;
		if (delegation != null) delegation.reset(reason);
		planners().forEach(PlannerOrchestrator::reset);
		planners().forEach(p -> p.updateSafetyContext(safetyEpoch, safetyHoldId, reflexActive));
	}

	public void refreshPlannerGoalWorld() {
		if (plannerGoal != null) plannerGoal.refreshWorld();
	}

	/** Called after action/reflex updates. Returning true reserves initiative for the current goal. */
	public boolean continuePlannerGoal(long tick, boolean workIdle, SessionSnapshot session,
		String primaryPlayer, Optional<GoalSnapshot> actionGoal, TaskSnapshot task,
		MissionExecutionSnapshot mission, EventStream events) {
		delegationWorkIdle = workIdle;
		boolean delegated = delegation != null && delegation.active();
		if (delegated && (delegation.starting() || delegation.returning())) return true;
		if (acceptedWork != null) return true;
		if (!delegated && plannerGoal != null && plannerGoal.blocked()) return true;
		if (!delegated && (plannerGoal == null || !plannerGoal.active())) return false;
		boolean awaitingSafetyDecision = !reflexActive && safetyHoldId != null;
		if (awaitingSafetyDecision && Objects.equals(lastSupervisedHold, safetyHoldId + ":" + reflexActive)) return true;
		if ((!workIdle && !awaitingSafetyDecision) || externalDriverActive || !plannerEnabled() || isDegraded() || !llmAvailable()
			|| reflexActive || session == null || !session.companionActuationAllowed()
			|| activePlanner().hasInFlight() || wakeScheduler.hasTaskWakes()) {
			nextGoalContinuationTick = tick + 20;
			return true;
		}
		if (tick < nextGoalContinuationTick) return true;
		nextGoalContinuationTick = tick + 20;
		var delegationPrompt = delegated ? delegation.continuationPrompt() : null;
		String continuation = delegated ? delegationPrompt.text() : "GOAL CONTINUATION: No action is running. Review fresh evidence and advance the active planner goal, "
			+ "change it if appropriate, or finish explicitly with success/give_up. A prior plaintext reply did not end it.\n"
			+ plannerGoal.context();
		if (awaitingSafetyDecision) continuation = "Safety hold " + safetyHoldId
			+ " still awaits your decision. The previous job remains paused. Use continue to keep and resume the plan, or clear_queue to abort and replace it."
			+ " Saying you will act does not release the hold.\n" + continuation;
		onPlannerTrigger(PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "self",
			continuation, tick, clock.millis(), "planner_goal", delegationPrompt == null ? null : delegationPrompt.fields()),
			session, primaryPlayer, actionGoal, task, mission, events);
		return true;
	}

	public Optional<DialogueResponse> lastResponse() {
		return Optional.ofNullable(state.lastResponse());
	}

	public boolean hasPendingReply() {
		return !pendingVisibleReplies.isEmpty();
	}

	public String pendingReplyReason() {
		return pendingVisibleReplies.isEmpty() ? state.pendingReplyReason() : pendingVisibleReplies.peekFirst().reason();
	}

	public Optional<PendingDialogueReply> pendingReplyReady(long tick) {
		if (pendingVisibleReplies.isEmpty()) {
			return Optional.empty();
		}
		PendingDialogueReply pendingReply = pendingVisibleReplies.peekFirst();
		if (tick < pendingReply.readyTick()) {
			return Optional.empty();
		}
		return Optional.of(pendingReply);
	}

	public boolean recordSentReply(PendingDialogueReply sentReply, boolean sendSucceeded) {
		if (!sendSucceeded || sentReply == null || pendingVisibleReplies.isEmpty()) {
			return false;
		}

		PendingDialogueReply pendingReply = pendingVisibleReplies.peekFirst();
		if (
			pendingReply.id() != sentReply.id()
				|| !Objects.equals(pendingReply.response().text(), sentReply.response().text())
		) {
			return false;
		}

		pendingVisibleReplies.removeFirst();
		recordAgentTurn(pendingReply.response().text(), pendingReply.response().tick());
		state = state.withPendingReply(!pendingVisibleReplies.isEmpty(), pendingReplyReason());
		return true;
	}

	/** True while any planner role still waits on a provider call or tool future. */
	public boolean plannerWorkRunning() {
		return planners().stream().anyMatch(PlannerOrchestrator::hasRunningWork);
	}

	public boolean isDegraded() {
		return state.degraded();
	}

	public boolean llmAvailable() {
		return activePlanner().isConfigured();
	}

	public boolean plannerEnabled() {
		return activePlanner().isEnabled();
	}

	public void setPlannerEnabled(boolean enabled) {
		planners().forEach(p -> p.setEnabled(enabled));
		if (!enabled && delegation != null) delegation.reset("planner disabled");
		if (enabled) {
			return;
		}
		queuedTimeoutInjections = 0;
		pendingTimeoutVisibleReply = false;
		wakeScheduler.clearTaskWakes();
		pendingVisibleReplies.clear();
		state = state.withPendingReply(false, null);
	}

	public void enableExternalDriver() {
		externalDriverActive = true;
		queuedTimeoutInjections = 0;
		pendingTimeoutVisibleReply = false;
		wakeScheduler.clearTaskWakes();
		resetPlanners("runtime reset");
	}

	public boolean externalDriverActive() {
		return externalDriverActive;
	}

	public List<Map<String, Object>> allAvailableTools() {
		return activePlanner().allAvailableTools();
	}

	public CompletableFuture<ExternalPlannerToolResult> executeExternalTool(String name, JsonObject arguments) {
		return activePlanner().executeExternalTool(name, arguments);
	}

	public PlannerOrchestratorDebugSnapshot plannerDebugSnapshot() {
		return activePlanner().debugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerConversationDebugSnapshot() {
		return activePlanner().conversationDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerProjectedConversationDebugSnapshot() {
		return activePlanner().projectedConversationDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerCanonicalConversationDebugSnapshot() {
		return activePlanner().canonicalConversationDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerChronicleConversationDebugSnapshot() {
		return plannerChronicleConversationDebugSnapshot(true);
	}

	public PlannerConversationDebugSnapshot plannerChronicleConversationDebugSnapshot(boolean verbose) {
		return activePlanner().chronicleConversationDebugSnapshot(verbose);
	}

	public PlannerConversationDebugSnapshot plannerContextConversationDebugSnapshot() {
		return activePlanner().contextConversationDebugSnapshot();
	}

	public List<String> plannerContextExcerpt() {
		return activePlanner().contextExcerpt();
	}

	public void invalidateIdleThinkTriggers() {
		activePlanner().invalidateIdleThinkTriggers();
	}

	public void updateSafetyContext(long replacementSafetyEpoch, String replacementSafetyHoldId, boolean activeReflex) {
		safetyEpoch = Math.max(safetyEpoch, Math.max(0L, replacementSafetyEpoch));
		safetyHoldId = replacementSafetyHoldId;
		reflexActive = activeReflex;
		planners().forEach(p -> p.updateSafetyContext(safetyEpoch, safetyHoldId, reflexActive));
	}

	public List<StalePlannerRejection> drainStalePlannerRejections() {
		return activePlanner().drainStalePlannerRejections();
	}

	public boolean startDebugCompaction() {
		return activePlanner().startDebugCompaction();
	}

	public CompactionExecutionResult pollDebugCompaction() {
		return activePlanner().pollDebugCompaction();
	}

	public long lastFailureTick() {
		return state.lastFailureTick();
	}

	public int consecutiveFailureCount() {
		return state.consecutiveFailureCount();
	}

	public LlmFailureType lastFailureType() {
		return state.lastFailureType();
	}

	public DialogueSnapshot snapshot() {
		return new DialogueSnapshot(
			List.copyOf(recentTurns),
			state.lastResponse(),
			hasPendingReply(),
			pendingReplyReason(),
			state.degraded(),
			state.consecutiveFailureCount(),
			state.lastFailureType(),
			state.lastFailureTick()
		);
	}

	public void injectMockResponse(PlannerResponse response) {
		activePlanner().injectMockResponse(response);
	}

	public void injectTimeout() {
		if (activePlanner().isEnabled()) {
			queuedTimeoutInjections++;
		}
	}

	public boolean handleResetCommand(String senderName, String plainTextMessage, long tick, EventStream eventBuffer) {
		if (!DialogueCore.isResetCommand(plainTextMessage)) {
			return false;
		}

		appendTurn(new DialogueTurn(senderName, plainTextMessage, tick, clock.millis()));
		supersedePendingInternalTaskUpdates("planner_reset", tick, eventBuffer);
		resetPlanners("runtime reset");
		queuedTimeoutInjections = 0;
		pendingVisibleReplies.clear();
		if (Boolean.getBoolean("airicraft.hostedPlaytestAutoReset") && state.degraded()) hostedAutoResetAttempted = true;
		applyTransition(DialogueCore.onReset(state, senderName, tick, messages), tick, eventBuffer);
		return true;
	}

	public void onPlayerChat(
		String senderName,
		String plainTextMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution,
		EventStream eventBuffer
	) {
		long timestampMs = clock.millis();
		appendTurn(new DialogueTurn(senderName, plainTextMessage, tick, timestampMs));
		submitPlannerTrigger(
			new PlannerRequest(
				tick,
				timestampMs,
				sessionSnapshot.mode(),
				primaryInteractionPlayer,
				activeGoal.orElse(null),
				activeTask,
				missionExecution,
				senderName,
				plainTextMessage,
				null
			),
			eventBuffer,
			timestampMs,
			true
		);
	}

	public void onContextTrigger(
		PlannerTriggerType triggerType,
		String senderName,
		String plainTextMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		EventStream eventBuffer
	) {
		long timestampMs = clock.millis();
		submitPlannerTrigger(
			PlannerRequest.ofTrigger(
				tick,
				timestampMs,
				sessionSnapshot.mode(),
				primaryInteractionPlayer,
				activeGoal.orElse(null),
				triggerType,
				senderName,
				plainTextMessage,
				null
			),
			eventBuffer,
			timestampMs,
			triggerType == PlannerTriggerType.CHAT && senderName != null && !"system".equalsIgnoreCase(senderName)
		);
	}

	public void onPlannerTrigger(
		PlannerTrigger trigger,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution,
		EventStream plannerEventBuffer
	) {
		wakeScheduler.offerTrigger(trigger, new WakeScheduler.TriggerHost() {
			@Override public boolean blockedGoal() {
				return plannerGoal != null && plannerGoal.blocked();
			}

			@Override public boolean workHoldsRoutineWakes() {
				return (acceptedWork != null || activePlanner().hasQueuedToolWork()) && safetyHoldId == null && !reflexActive;
			}

			@Override public void audit(PlannerTrigger audited, String kind, String gate) {
				auditTrigger(audited, kind, gate);
			}

			@Override public void deliver(PlannerTrigger delivered) {
				submitPlannerTrigger(
					new PlannerRequest(
						delivered.tick(),
						delivered.timestampMs(),
						sessionSnapshot.mode(),
						primaryInteractionPlayer,
						activeGoal.orElse(null),
						activeTask,
						missionExecution,
						PlannerTriggerBatch.of(List.of(delivered)),
						null
					),
					plannerEventBuffer,
					delivered.timestampMs(),
					delivered.maySupersedeLaunchedTurn(),
					(kind, gate) -> auditTrigger(delivered, kind, gate)
				);
			}
		});
	}

	public void onPlayerChat(
		String senderName,
		String plainTextMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		EventStream eventBuffer
	) {
		onPlayerChat(senderName, plainTextMessage, tick, sessionSnapshot, primaryInteractionPlayer, activeGoal, null, null, eventBuffer);
	}

	public void onInternalTaskUpdate(
		String updateMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		Optional<GoalSnapshot> activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution,
		EventStream eventBuffer
	) {
		long timestampMs = clock.millis();
		appendTurn(new DialogueTurn("system", updateMessage, tick, timestampMs));
		var event = eventBuffer.from("DialogueRuntime").publish(tick, "task.notice", Map.of("message", updateMessage));
		if (externalDriverActive || (state.degraded() && activePlanner().isEnabled()) || !activePlanner().isConfigured()) {
			return;
		}
		queueTaskWakeup(missionId(activeTask, missionExecution), tick, event.seqNo());
		submitNextPendingInternalTaskUpdate(eventBuffer, sessionSnapshot, activeGoal, activeTask, missionExecution);
	}

	public void onInternalTaskUpdate(
		String updateMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		Optional<GoalSnapshot> activeGoal,
		EventStream eventBuffer
	) {
		onInternalTaskUpdate(updateMessage, tick, sessionSnapshot, activeGoal, null, null, eventBuffer);
	}

	public DialogueResponse poll(long tick, EventStream eventBuffer) {
		return poll(tick, eventBuffer, null, Optional.empty(), null, null);
	}

	public DialogueResponse poll(
		long tick,
		EventStream eventBuffer,
		SessionSnapshot sessionSnapshot,
		Optional<GoalSnapshot> activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution
	) {
		if (externalDriverActive) {
			return null;
		}
		if (plannerGoal != null) {
			if (observedObjective != plannerGoal.snapshot()) {
				observedObjective = plannerGoal.snapshot();
				blockedEventCursor = eventBuffer.latestSeqNo();
			}
			if (plannerGoal.blocked()) {
				for (var event : eventBuffer.query(blockedEventCursor).events())
					if (plannerGoal.relevantToBlock(event.type())) queueTaskWakeup(null, tick, event.seqNo());
				blockedEventCursor = eventBuffer.latestSeqNo();
			}
		}

		if (pollPolicyContinuation(tick, eventBuffer)) return null;

		activePlanner().tickToolQueue();

		if (queuedTimeoutInjections > 0 && !activePlanner().hasInFlight()) {
			queuedTimeoutInjections--;
			applyTransition(DialogueCore.onPlannerFailure(state, LlmFailureType.TIMEOUT, "Injected LLM timeout",
				pendingTimeoutVisibleReply, tick, resetGuidance(), messages), tick, eventBuffer);
			pendingTimeoutVisibleReply = false;
			return null;
		}
		if ((delegation == null || !delegation.starting()) && submitNextPendingInternalTaskUpdate(eventBuffer, sessionSnapshot, activeGoal, activeTask, missionExecution)) {
			return null;
		}

		if (delegation != null && delegation.starting()) {
			delegationEventCursor = eventBuffer.latestSeqNo();
			var prompt = delegation.startPrompt(delegationFacts(tick, activeTask, missionExecution), delegationEventCursor);
			onPlannerTrigger(PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "controller", prompt.text(),
				tick, clock.millis(), "delegation", prompt.fields()), sessionSnapshot, null, activeGoal, activeTask, missionExecution, eventBuffer);
		}
		if (delegation != null && delegation.active()) {
			var events = eventBuffer.query(delegationEventCursor);
			if (events.truncated()) delegation.recordEventGap();
			for (var event : events.events()) delegation.recordEvent(event.seqNo(), event.type(), event.payload());
			delegationEventCursor = events.latestSeqNo();
		}
		PlannerOrchestrator owner = activePlanner();
		PlannerExecutionResult result = owner.poll();
		if (delegation != null && delegation.active() && result != null && !result.succeeded())
			delegation.failed(result.failureMessage());
		if (delegation != null && delegation.returning() && !owner.hasInFlight()) {
			delegation.finish(delegationFacts(tick, activeTask, missionExecution));
			return null;
		}
		if (result == null) {
			return null;
		}

		if (!result.succeeded()) {
			boolean timeoutVisibleReply = pendingTimeoutVisibleReply || isDirectChatRequest(result.request());
			applyTransition(
				DialogueCore.onPlannerFailure(
					state,
					result.failureType(),
					result.failureMessage(),
					timeoutVisibleReply,
					tick,
					resetGuidance(),
					messages
				),
				tick,
				eventBuffer
			);
			pendingTimeoutVisibleReply = false;
			submitNextPendingInternalTaskUpdate(eventBuffer, sessionSnapshot, activeGoal, activeTask, missionExecution);
			return null;
		}

		DialogueTransition transition = DialogueCore.onPlannerSuccess(state, result.response(), tick);
		applyTransition(transition, tick, eventBuffer);
		pendingTimeoutVisibleReply = false;
		activePlanner().onAcceptedReplyRecorded();
		submitNextPendingInternalTaskUpdate(eventBuffer, sessionSnapshot, activeGoal, activeTask, missionExecution);
		return transition.lastVisibleResponse();
	}

	private Map<String, Object> delegationFacts(long tick, TaskSnapshot task, MissionExecutionSnapshot mission) {
		Map<String, Object> facts = new java.util.LinkedHashMap<>();
		facts.put("tick", tick);
		if (decisionContextSource != null) facts.put("decisionContext", decisionContextSource.get());
		facts.put("workIdle", delegationWorkIdle());
		if (plannerGoal != null) facts.put("plannerGoal", plannerGoal.context());
		if (task != null) {
			facts.put("taskState", task.state());
			facts.put("taskId", task.taskId());
			facts.put("lastStepResult", task.lastStepResult());
		}
		if (mission != null && mission.evidence() != null) {
			var evidence = mission.evidence();
			facts.put("dimension", evidence.dimension());
			facts.put("position", Map.of("x", evidence.x(), "y", evidence.y(), "z", evidence.z()));
			facts.put("inventory", evidence.itemCounts());
		}
		return facts;
	}

	public void resetLlmState(long tick, EventStream eventBuffer) {
		resetPlanners("runtime reset");
		planners().forEach(p -> p.updateSafetyContext(safetyEpoch, safetyHoldId, reflexActive));
		queuedTimeoutInjections = 0;
		pendingTimeoutVisibleReply = false;
		wakeScheduler.clearTaskWakes();
		pendingVisibleReplies.clear();
		userGuidanceRevision = 0L;
		if (state.degraded()) {
			applyEffects(List.of(DialogueEffect.appendSemanticEvent("planner.degraded_cleared", java.util.Map.of())), tick, eventBuffer);
		}
		state = DialogueCore.initialState();
	}

	public void clear() {
		state = DialogueCore.initialState();
		queuedTimeoutInjections = 0;
		pendingTimeoutVisibleReply = false;
		wakeScheduler.clearTaskWakes();
		pendingVisibleReplies.clear();
		recentTurns.clear();
		userGuidanceRevision = 0L;
		resetPlanners("runtime reset");
		planners().forEach(p -> p.updateSafetyContext(safetyEpoch, safetyHoldId, reflexActive));
	}

	public void shutdown() {
		if (policyContinuation != null) policyContinuation.close();
		state = DialogueCore.initialState();
		queuedTimeoutInjections = 0;
		pendingTimeoutVisibleReply = false;
		wakeScheduler.clearTaskWakes();
		pendingVisibleReplies.clear();
		recentTurns.clear();
		userGuidanceRevision = 0L;
		if (delegation != null) delegation.reset("shutdown");
		planners().forEach(PlannerOrchestrator::shutdown);
	}

	public static boolean isResetCommand(String plainTextMessage) {
		return DialogueCore.isResetCommand(plainTextMessage);
	}

	/** Receives the final outcome of one wake attempt: {@code submitted}, or {@code dropped} with its gate. */
	@FunctionalInterface
	private interface WakeOutcome {
		WakeOutcome NONE = (kind, gate) -> { };
		void record(String kind, String gate);
	}

	private void submitPlannerTrigger(
		PlannerRequest request,
		EventStream eventBuffer,
		long timestampMs,
		boolean directUserGuidance
	) {
		submitPlannerTrigger(request, eventBuffer, timestampMs, directUserGuidance, WakeOutcome.NONE);
	}

	private void submitPlannerTrigger(
		PlannerRequest request,
		EventStream eventBuffer,
		long timestampMs,
		boolean directUserGuidance,
		WakeOutcome outcome
	) {
		if (externalDriverActive) {
			outcome.record("dropped", "G8.external_driver");
			return;
		}
		request = request.withSafetyContext(safetyEpoch, safetyHoldId);
		if (directUserGuidance) {
			if (delegation != null && delegation.active()) {
				delegation.recordGuidance(request.senderName(), request.message());
				if (delegation.starting() || delegation.returning()) {
					outcome.record("dropped", "G8.delegation_transition");
					return;
				}
			}
			supersedePendingInternalTaskUpdates("new_user_guidance", request.tick(), eventBuffer);
		}
		if (state.degraded() && activePlanner().isEnabled()) {
			outcome.record("dropped", "G8.degraded");
			applyTransition(
				DialogueCore.onPlannerDegradedBlocked(state, request.senderName(), directUserGuidance,
					request.tick(), resetGuidance(), messages),
				request.tick(),
				eventBuffer
			);
			return;
		}
		Long sinceSeqNo = activePlanner().lastObservedEventSeqNo();
		activePlanner().recordEvents(
			eventBuffer.query(sinceSeqNo <= 0L ? null : sinceSeqNo),
			new PlannerRequestSeed(
				request.tick(),
				timestampMs,
				request.sessionMode(),
				request.primaryInteractionPlayer(),
				request.activeGoal()
			)
		);
		// Record before submit: the orchestrator writes its own submission entry synchronously, and the
		// wake ledger attributes audits by timeline order. A disabled planner discards the request.
		outcome.record(activePlanner().isEnabled() ? "submitted" : "dropped", activePlanner().isEnabled() ? null : "G8.planner_disabled");
		boolean submitted = activePlanner().submit(request);
		if (submitted && safetyHoldId != null) lastSupervisedHold = safetyHoldId + ":" + reflexActive;
		pendingTimeoutVisibleReply = directUserGuidance && submitted;
	}

	public void queueTaskAttention(long tick, long eventSequence) {
		if (externalDriverActive) return;
		if (policyContinuation != null) policyContinuation.discard("execution_attention");
		continuationParent = null;
		wakeScheduler.offerAttention(Wake.attention(tick, eventSequence, userGuidanceRevision));
	}

	public void queueTaskWakeup(String missionId, long tick, long eventSequence) {
		if (!externalDriverActive) wakeScheduler.offerTask(Wake.task(tick, eventSequence, userGuidanceRevision, missionId));
	}

	private boolean submitNextPendingInternalTaskUpdate(
		EventStream eventBuffer, SessionSnapshot sessionSnapshot, Optional<GoalSnapshot> activeGoal,
		TaskSnapshot activeTask, MissionExecutionSnapshot missionExecution
	) {
		return wakeScheduler.releaseTaskWake(new TaskWakeHost(eventBuffer, sessionSnapshot, activeGoal, activeTask, missionExecution));
	}

	/** Planner and dialogue facts for one task-wake release, and the delivery of a released wake. */
	private final class TaskWakeHost implements WakeScheduler.TaskWakeHost {
		private final EventStream eventBuffer;
		private final SessionSnapshot sessionSnapshot;
		private final Optional<GoalSnapshot> activeGoal;
		private final TaskSnapshot activeTask;
		private final MissionExecutionSnapshot missionExecution;

		private TaskWakeHost(EventStream eventBuffer, SessionSnapshot sessionSnapshot, Optional<GoalSnapshot> activeGoal,
			TaskSnapshot activeTask, MissionExecutionSnapshot missionExecution) {
			this.eventBuffer = eventBuffer;
			this.sessionSnapshot = sessionSnapshot;
			this.activeGoal = activeGoal;
			this.activeTask = activeTask;
			this.missionExecution = missionExecution;
		}

		@Override public boolean externalDriverActive() { return externalDriverActive; }
		@Override public boolean plannerInFlight() { return activePlanner().hasInFlight(); }
		@Override public boolean plannerUnavailable() {
			return (state.degraded() && activePlanner().isEnabled()) || !activePlanner().isConfigured();
		}
		@Override public boolean runPolicyHold() {
			return acceptedWork != null && acceptedWork.label().equals("run_policy") && safetyHoldId == null && !reflexActive;
		}
		@Override public boolean queuedToolWork() { return activePlanner().hasQueuedToolWork(); }
		@Override public boolean satisfied(Wake wake) {
			return wake.eventRefs().stream().allMatch(activePlanner()::hasIncorporatedDecisionEvent);
		}
		@Override public boolean irrelevantToBlockedGoal(Wake wake) {
			return plannerGoal != null && plannerGoal.blocked() && !eventBuffer.query(wake.eventSequence() - 1).events().stream()
				.anyMatch(event -> event.seqNo() == wake.eventSequence() && (plannerGoal.relevantToBlock(event.type())
					|| isSupervisoryEvent(event.type())));
		}
		@Override public long guidanceRevision() { return userGuidanceRevision; }
		@Override public String currentMissionId() { return missionId(activeTask, missionExecution); }
		@Override public void audit(Wake wake, String kind, String gate) { recordTaskAudit(wake, kind, gate); }
		@Override public void superseded(Wake wake, String reason, String currentMissionId) {
			recordSupersededInternalTaskUpdate(wake, reason, currentMissionId, wake.tick(), eventBuffer);
		}
		@Override public void deliver(Wake wake) {
			// A wake contains no historical state. Evidence remains in the shared event buffer.
			String message = eventBuffer.query(wake.eventSequence() - 1).events().stream()
				.filter(event -> event.seqNo() == wake.eventSequence() && event.type().equals("task.notice"))
				.map(event -> Objects.toString(event.payload().get("message"))).findFirst()
				.orElse("Work changed.");
			submitPlannerTrigger(new PlannerRequest(wake.tick(), clock.millis(),
				sessionSnapshot == null ? SessionSnapshot.initial().mode() : sessionSnapshot.mode(), null,
				activeGoal == null ? null : activeGoal.orElse(null), activeTask, missionExecution,
				PlannerTriggerBatch.of(List.of(PlannerTrigger.pending(PlannerTriggerType.SYSTEM, "runtime", message, wake.tick(), clock.millis()))), null
			).withSafetyContext(safetyEpoch, safetyHoldId), eventBuffer, clock.millis(), false,
				(kind, gate) -> auditTask(wake, kind, gate));
		}
	}

	private void auditTrigger(PlannerTrigger trigger, String kind, String gate) {
		var fields = wakeFields(WakeScheduler.triggerPath(trigger), gate, trigger.tick());
		fields.put("triggerTypes", List.of(trigger.type().name()));
		fields.put("origins", List.of(trigger.origin().name()));
		fields.put("coalescingKeys", trigger.coalescingKey() == null ? List.of() : List.of(trigger.coalescingKey()));
		fields.put("speakers", List.of(trigger.speaker()));
		wakeAudit.record(trigger.tick(), kind, fields);
	}

	/** Task-wake audits go through the scheduler so its retained-gate de-duplication resets on every outcome. */
	private void auditTask(Wake wake, String kind, String gate) {
		wakeScheduler.outcomeRecorded();
		recordTaskAudit(wake, kind, gate);
	}

	private void recordTaskAudit(Wake wake, String kind, String gate) {
		var fields = wakeFields(wake.attention() ? "W3" : "W2", gate, wake.tick());
		fields.put("eventSequence", wake.eventSequence());
		fields.put("triggerTypes", List.of("SYSTEM"));
		fields.put("origins", List.of("AUTONOMOUS"));
		fields.put("coalescingKeys", List.of("system"));
		fields.put("speakers", List.of("runtime"));
		wakeAudit.record(wake.tick(), kind, fields);
	}

	private java.util.LinkedHashMap<String, Object> wakeFields(String path, String gate, long wakeTick) {
		var fields = new java.util.LinkedHashMap<String, Object>();
		fields.put("path", path);
		fields.put("wakeTick", wakeTick);
		fields.put("owner", decisionOwner());
		if (gate != null) fields.put("gate", gate);
		return fields;
	}

	private static boolean isSupervisoryEvent(String type) {
		return switch (type) {
			case "reflex.started", "reflex.resolved", "reflex.threat_detected", "reflex.actuator_failed" -> true;
			default -> false;
		};
	}

	private void supersedePendingInternalTaskUpdates(String reason, long tick, EventStream eventBuffer) {
		if (policyContinuation != null) policyContinuation.discard(reason);
		continuationParent = null;
		acceptedWork = null;
		userGuidanceRevision++;
		for (Wake wake : wakeScheduler.takeTaskWakes()) {
			recordSupersededInternalTaskUpdate(wake, reason, null, tick, eventBuffer);
		}
	}

	private void recordSupersededInternalTaskUpdate(
		Wake pendingUpdate,
		String reason,
		String currentMissionId,
		long supersededAtTick,
		EventStream eventBuffer
	) {
		if (pendingUpdate == null || eventBuffer == null) {
			return;
		}
		auditTask(pendingUpdate, "dropped", "G5.superseded");
		eventBuffer.from("DialogueRuntime").publish(supersededAtTick, "planner.internal_task_update_superseded", Map.of(
			"reason", reason,
			"updateTick", pendingUpdate.tick(),
			"supersededAtTick", supersededAtTick,
			"updateGuidanceRevision", pendingUpdate.guidanceRevision(),
			"currentGuidanceRevision", userGuidanceRevision,
			"updateMissionId", pendingUpdate.missionId() == null ? "" : pendingUpdate.missionId(),
			"currentMissionId", currentMissionId == null ? "" : currentMissionId
		), pendingUpdate.eventSequence() <= 0L ? null : EventCause.event(pendingUpdate.eventSequence()));
	}

	private static String missionId(TaskSnapshot activeTask, MissionExecutionSnapshot missionExecution) {
		if (
			activeTask != null
				&& activeTask.mission() != null
				&& activeTask.mission().missionId() != null
				&& !activeTask.mission().missionId().isBlank()
		) {
			return activeTask.mission().missionId();
		}
		if (
			missionExecution != null
				&& missionExecution.mission() != null
				&& missionExecution.mission().missionId() != null
				&& !missionExecution.mission().missionId().isBlank()
		) {
			return missionExecution.mission().missionId();
		}
		return null;
	}


	private void recordAgentTurn(String text, long tick) {
		DialogueTurn turn = new DialogueTurn(DialogueSpeakerLabels.AGENT, text, tick, clock.millis());
		appendTurn(turn);
		(visibleReplyOwner == null ? activePlanner() : visibleReplyOwner).recordAssistantTurn(turn);
	}

	private void appendTurn(DialogueTurn turn) {
		recentTurns.add(turn);
		while (recentTurns.size() > maxRecentTurns) {
			recentTurns.remove(0);
		}
	}

	private void applyTransition(DialogueTransition transition, long tick, EventStream eventBuffer) {
		visibleReplyOwner = activePlanner();
		state = transition.state();
		applyEffects(transition.effects(), tick, eventBuffer);
		// A new planner result must not erase accepted speech that has not been sent.
		long nextReadyTick = pendingVisibleReplies.isEmpty() ? tick
			: Math.max(tick, pendingVisibleReplies.peekLast().readyTick());
		for (DialogueResponse response : transition.visibleResponses()) {
			if (response != null && response.text() != null && !response.text().isBlank()) {
				nextReadyTick += response.delayTicks();
				pendingVisibleReplies.addLast(new PendingDialogueReply(nextPendingReplyId++, response, state.pendingReplyReason(), nextReadyTick));
			}
		}
		state = state.withPendingReply(!pendingVisibleReplies.isEmpty(), pendingReplyReason());
	}

	private static void applyEffects(List<DialogueEffect> effects, long tick, EventStream eventBuffer) {
		for (DialogueEffect effect : effects) {
			if (effect instanceof DialogueEffect.AppendSemanticEvent appendSemanticEvent) {
				eventBuffer.from("DialogueCore").publish(tick, appendSemanticEvent.type(), appendSemanticEvent.payload());
			}
		}
	}

	private static boolean isDirectChatRequest(PlannerRequest request) {
		if (request == null || request.triggerBatch() == null || request.triggerBatch().triggers().isEmpty()) {
			return false;
		}
		return request.triggerBatch().triggers().stream().allMatch(trigger ->
			trigger.type() == PlannerTriggerType.CHAT
				&& trigger.maySupersedeLaunchedTurn()
				&& trigger.speaker() != null
				&& !"system".equalsIgnoreCase(trigger.speaker())
		);
	}


}
