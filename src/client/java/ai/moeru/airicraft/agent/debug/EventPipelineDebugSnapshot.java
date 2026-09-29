package ai.moeru.airicraft.agent.debug;

public record EventPipelineDebugSnapshot(
	long rawLatestSeqNo,
	long rawDroppedCount,
	long lastProcessedRawSeqNo,
	long lastRawEventSeqNo,
	String lastEventType,
	String lastDecisionEffect,
	String lastTriggerType,
	boolean lastEmitSemantic,
	boolean lastEmitTrigger
) {
	public static EventPipelineDebugSnapshot empty() {
		return new EventPipelineDebugSnapshot(0L, 0L, 0L, 0L, null, null, null, false, false);
	}
}
