package ai.moeru.airicraft.agent.llm;

public record PlannerContextDebugSnapshot(
	int compactionTriggerTokens,
	boolean compactionPending,
	int acceptedTurnCount,
	int frozenPlannerMessageCount,
	int queuedTriggerCount,
	long lastAcceptedTimeContextAtMs,
	LlmUsageSnapshot lastObservedUsage,
	PlannerAmbientContext acceptedAmbientContext,
	CompactionCheckpoint activeCheckpoint
) {
}
