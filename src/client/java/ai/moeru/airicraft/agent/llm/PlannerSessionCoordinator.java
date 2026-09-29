package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.observability.AgentObservability;
import io.opentelemetry.context.Context;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PlannerSessionCoordinator {
	@FunctionalInterface
	public interface SubmissionObserver {
		void onSubmitted(long generation, int attempt, PlannerSessionPhase phase, PlannerRequest request, LlmConversation conversation);
	}

	private static final SubmissionObserver NO_OP_SUBMISSION_OBSERVER = (generation, attempt, phase, request, conversation) -> {
	};

	private final PlannerExecutor plannerExecutor;
	private final Clock clock;
	private final int maxConcurrentAttempts;
	private final int maxConsecutiveRepairableFailures;
	private final long retryBackoffMs;
	private final SubmissionObserver submissionObserver;
	private final ArrayDeque<PlannerExecutionResult> readyResults = new ArrayDeque<>();

	private PlannerSession activeSession;
	private long nextGeneration = 1L;
	private java.util.concurrent.atomic.AtomicLong sharedGenerations;

	public void shareGenerationSequence(java.util.concurrent.atomic.AtomicLong sequence) { sharedGenerations = sequence; }
	private long allocateGeneration() { return sharedGenerations == null ? nextGeneration++ : sharedGenerations.getAndIncrement(); }
	private long gameplayDecisionCount;
	public long gameplayDecisionCount() { return gameplayDecisionCount; }

	private long supersededCount;
	private long providerRetryNotBeforeMillis;

	public PlannerSessionCoordinator(PlannerExecutor plannerExecutor, Clock clock, int maxConcurrentAttempts, int maxConsecutiveRepairableFailures, long retryBackoffMs) {
		this(plannerExecutor, clock, maxConcurrentAttempts, maxConsecutiveRepairableFailures, retryBackoffMs, NO_OP_SUBMISSION_OBSERVER);
	}

	public PlannerSessionCoordinator(
		PlannerExecutor plannerExecutor,
		Clock clock,
		int maxConcurrentAttempts,
		int maxConsecutiveRepairableFailures,
		long retryBackoffMs,
		SubmissionObserver submissionObserver
	) {
		this.plannerExecutor = Objects.requireNonNull(plannerExecutor, "plannerExecutor");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.maxConcurrentAttempts = Math.max(1, maxConcurrentAttempts);
		this.maxConsecutiveRepairableFailures = Math.max(0, maxConsecutiveRepairableFailures);
		this.retryBackoffMs = Math.max(0L, retryBackoffMs);
		this.submissionObserver = Objects.requireNonNull(submissionObserver, "submissionObserver");
	}

	public boolean hasInFlight() {
		return activeSession != null || plannerExecutor.hasInFlight() || !readyResults.isEmpty();
	}

	public int activeAttemptCount() {
		return plannerExecutor.activeAttemptCount();
	}

	public long activeGeneration() {
		return activeSession == null ? 0L : activeSession.generation();
	}

	/** The active session's phase, or {@code null} without one. */
	public PlannerSessionPhase activePhase() {
		return activeSession == null ? null : activeSession.phase();
	}

	public long pendingNewestGeneration() {
		return activeSession != null && activeSession.awaitingLaunch() ? activeSession.generation() : 0L;
	}

	public long supersededCount() {
		return supersededCount;
	}

	public PlannerSessionSnapshot activeSnapshot() {
		return activeSession == null ? null : activeSession.snapshot();
	}

	public LlmConversation conversationFor(long generation) {
		return activeSession != null && activeSession.generation() == generation ? activeSession.conversation() : null;
	}

	public PlannerContextSnapshot contextSnapshotFor(long generation) {
		if (activeSession == null || activeSession.generation() != generation) {
			return null;
		}
		return activeSession.contextSnapshot();
	}

	public boolean hasReplaceableActiveSession() {
		return activeSession != null && activeSession.replaceable();
	}

	public boolean hasReadyResultForActiveSession() {
		return activeSession != null
			&& !readyResults.isEmpty()
			&& readyResults.peekFirst().generation() == activeSession.generation();
	}

	public PlannerContextSnapshot supersedeActiveSessionIfReplaceable() {
		if (activeSession == null || !activeSession.replaceable()) {
			return null;
		}
		PlannerContextSnapshot contextSnapshot = activeSession.contextSnapshot();
		plannerExecutor.discardGeneration(activeSession.generation());
		activeSession.markSuperseded();
		supersededCount++;
		activeSession = null;
		return contextSnapshot;
	}

	public void submit(PlannerContextSnapshot contextSnapshot) {
		submit(contextSnapshot, Context.current());
	}

	public void submit(PlannerContextSnapshot contextSnapshot, Context parentContext) {
		Objects.requireNonNull(contextSnapshot, "contextSnapshot");
		activeSession = new PlannerSession(allocateGeneration(), contextSnapshot, parentContext);
		launchIfPossible(activeSession);
	}

	public PlannerExecutionResult recordDiscarded(
		PlannerContextSnapshot contextSnapshot,
		List<Map<String, Object>> tools,
		Context parentContext
	) {
		Objects.requireNonNull(contextSnapshot, "contextSnapshot");
		Objects.requireNonNull(tools, "tools");
		long generation = allocateGeneration();
		int attempt = 1;
		PlannerSessionPhase phase = PlannerSessionPhase.PLANNER_REQUEST;
		submissionObserver.onSubmitted(
			generation,
			attempt,
			phase,
			contextSnapshot.request(),
			contextSnapshot.plannerConversation()
		);
		return plannerExecutor.recordDiscarded(
			generation,
			attempt,
			phase,
			contextSnapshot.request(),
			contextSnapshot.plannerConversation(),
			tools,
			parentContext,
			spanNameFor(phase)
		);
	}

	public PlannerExecutionResult poll() {
		drainCompletedResults();
		if (!readyResults.isEmpty()) {
			return readyResults.pollFirst();
		}
		if (activeSession != null) {
			if (
				activeSession.readyForRetry(clock.millis())
				&& clock.millis() >= providerRetryNotBeforeMillis
				&& plannerExecutor.activeAttemptCount() < maxConcurrentAttempts
				&& activeSession.conversation() != null
			) {
				activeSession.clearRetry();
				activeSession.beginAttempt();
				if (activeSession.attemptCount() == 1) gameplayDecisionCount++;
				submissionObserver.onSubmitted(
					activeSession.generation(),
					activeSession.attemptCount(),
					activeSession.phase(),
					activeSession.request(),
					activeSession.conversation()
				);
				plannerExecutor.submit(
					activeSession.generation(),
					activeSession.attemptCount(),
					activeSession.phase(),
					activeSession.request(),
					activeSession.conversation(),
					activeSession.parentContext(),
					spanNameFor(activeSession.phase())
				);
			}
			else if (activeSession.awaitingLaunch()) {
				launchIfPossible(activeSession);
			}
		}
		if (!readyResults.isEmpty()) {
			return readyResults.pollFirst();
		}
		return null;
	}

	public void markToolWait(long generation) {
		if (activeSession == null || activeSession.generation() != generation) {
			return;
		}
		activeSession.moveToToolWait();
	}

	public boolean submitToolFollowUp(long generation, PlannerRequest request, LlmConversation conversation) {
		if (activeSession == null || activeSession.generation() != generation || !activeSession.replaceable()) {
			return false;
		}
		activeSession.moveToToolFollowUp(request, conversation);
		launchIfPossible(activeSession);
		return true;
	}

	public boolean scheduleParseRepairRetry(long generation, LlmChatMessage repairMessage) {
		if (
			activeSession == null
				|| activeSession.generation() != generation
				|| !activeSession.replaceable()
				|| activeSession.conversation() == null
		) {
			return false;
		}
		if (activeSession.recordFailure() > maxConsecutiveRepairableFailures) {
			return false;
		}
		activeSession.scheduleRetry(clock.millis(), activeSession.conversation().withAppended(repairMessage));
		return true;
	}

	public boolean scheduleChatRepairRetry(long generation, LlmChatMessage repairMessage) {
		if (
			activeSession == null
				|| activeSession.generation() != generation
				|| !activeSession.replaceable()
				|| activeSession.conversation() == null
		) {
			return false;
		}
		activeSession.scheduleRetry(clock.millis(), activeSession.conversation().withAppended(repairMessage));
		return true;
	}

	public void finishGeneration(long generation, boolean failed) {
		if (activeSession == null || activeSession.generation() != generation) {
			return;
		}
		if (failed) {
			plannerExecutor.discardGeneration(generation);
			activeSession.markFailed();
		}
		else {
			activeSession.markCompleted();
		}
		activeSession = null;
	}

	public boolean acceptGeneration(long generation) throws LlmBackendException {
		if (activeSession == null || activeSession.generation() != generation) {
			return false;
		}
		plannerExecutor.acceptGeneration(generation);
		return true;
	}

	public void reset() {
		activeSession = null;
		nextGeneration = 1L;
		supersededCount = 0L;
		readyResults.clear();
		plannerExecutor.reset();
	}

	public void pause() {
		if (activeSession != null) {
			plannerExecutor.pauseGeneration(activeSession.generation());
			activeSession.markSuperseded();
			activeSession = null;
		}
		readyResults.clear();
	}

	public void shutdown() {
		activeSession = null;
		readyResults.clear();
		plannerExecutor.shutdown();
	}

	private void launchIfPossible(PlannerSession session) {
		if (session == null || !session.awaitingLaunch() || plannerExecutor.activeAttemptCount() >= maxConcurrentAttempts) {
			return;
		}
		if (clock.millis() < providerRetryNotBeforeMillis) {
			session.scheduleRetry(providerRetryNotBeforeMillis);
			return;
		}
		session.beginAttempt();
		gameplayDecisionCount++;
		submissionObserver.onSubmitted(
			session.generation(),
			session.attemptCount(),
			session.phase(),
			session.request(),
			session.conversation()
		);
		plannerExecutor.submit(
			session.generation(),
			session.attemptCount(),
			session.phase(),
			session.request(),
			session.conversation(),
			session.parentContext(),
			spanNameFor(session.phase())
		);
	}

	public void drainCompletedResults() {
		PlannerExecutionResult result;
		while ((result = plannerExecutor.poll()) != null) {
			handleCompletedResult(result);
		}
	}

	private void handleCompletedResult(PlannerExecutionResult result) {
		if (result.retryAfterMillis() > 0L) {
			long now = clock.millis();
			providerRetryNotBeforeMillis = Math.max(providerRetryNotBeforeMillis,
				now + Math.min(result.retryAfterMillis(), Long.MAX_VALUE - now));
		}
		if (
			activeSession == null
			|| result.generation() != activeSession.generation()
			|| result.phase() != activeSession.phase()
		) {
			return;
		}

		if (result.retryAfterMillis() > 0L) {
			activeSession.scheduleRetry(providerRetryNotBeforeMillis);
			ai.moeru.airicraft.Airicraft.LOGGER.info("Planner rate limited; retry scheduled generation={} retryReadyAtMs={}",
				result.generation(), providerRetryNotBeforeMillis);
			return;
		}

		if (
			!result.succeeded()
				&& shouldRetry(result.failureType())
				&& activeSession.recordFailure() <= maxConsecutiveRepairableFailures
		) {
			activeSession.scheduleRetry(clock.millis() + retryBackoffMs);
			return;
		}

		readyResults.addLast(result);
	}

	private static boolean shouldRetry(LlmFailureType failureType) {
		if (failureType == null) {
			return false;
		}
		return switch (failureType) {
			case TIMEOUT, PROVIDER_UNAVAILABLE -> true;
			case PARSE_ERROR, PROVIDER_ERROR -> false;
		};
	}

	private static String spanNameFor(PlannerSessionPhase phase) {
		return phase == PlannerSessionPhase.TOOL_FOLLOW_UP
			? AgentObservability.FOLLOW_UP_SPAN_NAME
			: AgentObservability.PLANNER_REQUEST_SPAN_NAME;
	}
}
