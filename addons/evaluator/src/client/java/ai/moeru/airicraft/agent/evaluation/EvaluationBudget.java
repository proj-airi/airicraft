package ai.moeru.airicraft.agent.evaluation;

/**
 * heartbeatIntervalTicks is retained only to read frozen legacy manifests; it is never scheduled.
 * maxStallTicks ends a running scenario as STALLED after that many ticks without a planner turn,
 * an in-flight planner call or a new agent event; 0 disables it.
 */
public record EvaluationBudget(
	int maxPlannerTurns,
	long maxElapsedTicks,
	long maxElapsedMillis,
	long heartbeatIntervalTicks,
	long maxStallTicks
) {
	private static final int DEFAULT_MAX_PLANNER_TURNS = 12;
	private static final long DEFAULT_MAX_ELAPSED_TICKS = 2_400L;
	private static final long DEFAULT_HEARTBEAT_INTERVAL_TICKS = 80L;
	public static final long DEFAULT_MAX_STALL_TICKS = 6_000L;

	public EvaluationBudget {
		maxPlannerTurns = maxPlannerTurns <= 0 ? DEFAULT_MAX_PLANNER_TURNS : maxPlannerTurns;
		maxElapsedTicks = maxElapsedTicks <= 0L ? DEFAULT_MAX_ELAPSED_TICKS : maxElapsedTicks;
		maxElapsedMillis = Math.max(0L, maxElapsedMillis);
		heartbeatIntervalTicks = heartbeatIntervalTicks <= 0L ? DEFAULT_HEARTBEAT_INTERVAL_TICKS : heartbeatIntervalTicks;
		maxStallTicks = Math.max(0L, maxStallTicks);
	}

	public EvaluationBudget(int maxPlannerTurns, long maxElapsedTicks, long maxElapsedMillis, long heartbeatIntervalTicks) {
		this(maxPlannerTurns, maxElapsedTicks, maxElapsedMillis, heartbeatIntervalTicks, DEFAULT_MAX_STALL_TICKS);
	}

	public static EvaluationBudget defaults() {
		return new EvaluationBudget(
			DEFAULT_MAX_PLANNER_TURNS,
			DEFAULT_MAX_ELAPSED_TICKS,
			0L,
			DEFAULT_HEARTBEAT_INTERVAL_TICKS,
			DEFAULT_MAX_STALL_TICKS
		);
	}
}
