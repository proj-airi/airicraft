package ai.moeru.airicraft.agent.llm;

/**
 * A planner turn discarded because the safety context moved on: rejected when its stale result arrived, or
 * {@code preempted} before it finished.
 */
public record StalePlannerRejection(
	long generation,
	long requestSafetyEpoch,
	long currentSafetyEpoch,
	String requestHoldId,
	String currentHoldId,
	String phase,
	boolean preempted
) {
	public StalePlannerRejection(long generation, long requestSafetyEpoch, long currentSafetyEpoch, String requestHoldId,
		String currentHoldId, String phase) {
		this(generation, requestSafetyEpoch, currentSafetyEpoch, requestHoldId, currentHoldId, phase, false);
	}
}
