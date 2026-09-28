package ai.moeru.airicraft.agent.evaluation;

public enum EvaluationStatus {
	IDLE,
	PENDING_WORLD,
	RUNNING,
	PASSED,
	FAILED,
	NEEDS_REVIEW,
	/** No planner turn, planner call or agent event for the scenario's stall budget. */
	STALLED;

	public boolean terminal() {
		return this == PASSED || this == FAILED || this == NEEDS_REVIEW || this == STALLED;
	}
}
