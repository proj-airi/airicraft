package ai.moeru.airicraft.agent.llm;

import io.opentelemetry.context.Context;

final class PlannerSession {
	private final long generation;
	private final PlannerContextSnapshot contextSnapshot;
	private final Context parentContext;
	private PlannerRequest request;
	private PlannerSessionPhase phase;
	private LlmConversation conversation;
	private int attemptCount;
	private int consecutiveFailureCount;
	private int transientFailureCount;
	private boolean chatRepairUsed;
	private long retryReadyAtMs = -1L;

	PlannerSession(long generation, PlannerContextSnapshot contextSnapshot) {
		this(generation, contextSnapshot, Context.current());
	}

	PlannerSession(long generation, PlannerContextSnapshot contextSnapshot, Context parentContext) {
		this.generation = generation;
		this.contextSnapshot = contextSnapshot;
		this.parentContext = parentContext == null ? Context.root() : parentContext;
		this.request = contextSnapshot.request();
		this.phase = PlannerSessionPhase.PLANNER_REQUEST;
		this.conversation = contextSnapshot.plannerConversation();
	}

	long generation() {
		return generation;
	}

	PlannerContextSnapshot contextSnapshot() {
		return contextSnapshot;
	}

	Context parentContext() {
		return parentContext;
	}

	PlannerRequest request() {
		return request;
	}

	PlannerSessionPhase phase() {
		return phase;
	}

	LlmConversation conversation() {
		return conversation;
	}

	int attemptCount() {
		return attemptCount;
	}

	int consecutiveFailureCount() {
		return consecutiveFailureCount;
	}

	long retryReadyAtMs() {
		return retryReadyAtMs;
	}

	boolean retryPending() {
		return retryReadyAtMs >= 0L;
	}

	boolean readyForRetry(long nowMs) {
		return retryPending() && nowMs >= retryReadyAtMs;
	}

	boolean awaitingLaunch() {
		return phase != PlannerSessionPhase.TOOL_WAIT && conversation != null && attemptCount == 0 && !retryPending();
	}

	boolean replaceable() {
		return phase != PlannerSessionPhase.COMPLETED
			&& phase != PlannerSessionPhase.FAILED
			&& phase != PlannerSessionPhase.SUPERSEDED;
	}

	void beginAttempt() {
		attemptCount++;
		retryReadyAtMs = -1L;
	}

	void scheduleRetry(long whenMs) {
		retryReadyAtMs = whenMs;
	}

	void scheduleRetry(long whenMs, LlmConversation replacementConversation) {
		conversation = replacementConversation;
		retryReadyAtMs = whenMs;
	}

	int recordFailure() {
		consecutiveFailureCount++;
		return consecutiveFailureCount;
	}

	/** Counts provider-side failures (timeouts, gateway errors) separately from repairable model output. */
	int recordTransientFailure() {
		transientFailureCount++;
		return transientFailureCount;
	}

	/** One visible-chat repair per phase; transport retries do not use it up. Returns false once it was taken. */
	boolean takeChatRepair() {
		if (chatRepairUsed) return false;
		chatRepairUsed = true;
		return true;
	}

	void clearConsecutiveFailures() {
		consecutiveFailureCount = 0;
		transientFailureCount = 0;
	}

	void clearRetry() {
		retryReadyAtMs = -1L;
	}

	void moveToToolWait() {
		chatRepairUsed = false;
		phase = PlannerSessionPhase.TOOL_WAIT;
		// A tool wait suspends model execution, not the conversation that produced it.
		attemptCount = 0;
		clearConsecutiveFailures();
		retryReadyAtMs = -1L;
	}

	void moveToToolFollowUp(PlannerRequest replacementRequest, LlmConversation replacementConversation) {
		chatRepairUsed = false;
		request = replacementRequest;
		phase = PlannerSessionPhase.TOOL_FOLLOW_UP;
		conversation = replacementConversation;
		attemptCount = 0;
		clearConsecutiveFailures();
		retryReadyAtMs = -1L;
	}

	void markSuperseded() {
		phase = PlannerSessionPhase.SUPERSEDED;
		conversation = null;
		retryReadyAtMs = -1L;
	}

	void markCompleted() {
		phase = PlannerSessionPhase.COMPLETED;
		conversation = null;
		clearConsecutiveFailures();
		retryReadyAtMs = -1L;
	}

	void markFailed() {
		phase = PlannerSessionPhase.FAILED;
		conversation = null;
		retryReadyAtMs = -1L;
	}

	PlannerSessionSnapshot snapshot() {
		return new PlannerSessionSnapshot(generation, phase, attemptCount, retryPending(), retryReadyAtMs, request);
	}
}
