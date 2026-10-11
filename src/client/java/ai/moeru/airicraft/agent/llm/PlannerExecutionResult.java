package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonPrimitive;

public record PlannerExecutionResult(
	PlannerRequest request,
	PlannerResponse response,
	LlmUsageSnapshot usage,
	LlmFailureType failureType,
	String failureMessage,
	long generation,
	int attempt,
	PlannerSessionPhase phase,
	boolean stale,
	long retryAfterMillis,
	boolean retryable
) {
	public PlannerExecutionResult(PlannerRequest request, PlannerResponse response, LlmUsageSnapshot usage,
		LlmFailureType failureType, String failureMessage, long generation, int attempt, PlannerSessionPhase phase, boolean stale,
		long retryAfterMillis) {
		this(request, response, usage, failureType, failureMessage, generation, attempt, phase, stale, retryAfterMillis, true);
	}

	public PlannerExecutionResult(PlannerRequest request, PlannerResponse response, LlmUsageSnapshot usage,
		LlmFailureType failureType, String failureMessage, long generation, int attempt, PlannerSessionPhase phase, boolean stale) {
		this(request, response, usage, failureType, failureMessage, generation, attempt, phase, stale, 0L);
	}

	public PlannerExecutionResult(
		PlannerRequest request,
		PlannerResponse response,
		LlmUsageSnapshot usage,
		LlmFailureType failureType,
		String failureMessage
	) {
		this(request, response, usage, failureType, failureMessage, 0L, 1, PlannerSessionPhase.PLANNER_REQUEST, false);
	}

	public boolean succeeded() {
		return failureType == null;
	}

	public static PlannerExecutionResult discarded(
		PlannerRequest request,
		long generation,
		int attempt,
		PlannerSessionPhase phase
	) {
		return new PlannerExecutionResult(
			request,
			new PlannerResponse(
				"",
				new PlannerIntent("none", null, null),
				null,
				null,
				new JsonPrimitive("PLANNER OFF")
			),
			LlmUsageSnapshot.unknown(),
			null,
			null,
			generation,
			attempt,
			phase,
			false
		);
	}
}
