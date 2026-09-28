package ai.moeru.airicraft.agent.llm;

import java.util.List;

public record PlannerContextState(
	List<PlannerContextEntry> acceptedHistoryTape,
	CompactionCheckpoint activeCheckpoint,
	PlannerAmbientContext lastAcceptedAmbientContext,
	long lastAcceptedTimeContextAtMs,
	boolean compactionPending,
	LlmUsageSnapshot lastObservedUsage,
	List<PlannerTrigger> queuedTriggers,
	long nextTriggerSeqNo
) {
	public static PlannerContextState initial() {
		return new PlannerContextState(
			List.of(),
			null,
			null,
			-1L,
			false,
			LlmUsageSnapshot.unknown(),
			List.of(),
			1L
		);
	}
}
