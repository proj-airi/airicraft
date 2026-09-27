package ai.moeru.airicraft.agent.events;

import java.util.Map;

public record SemanticEvent(
	long seqNo,
	long tick,
	long timestampMs,
	String type,
	Map<String, Object> payload,
	String source,
	EventCause cause
) {
	public SemanticEvent(long seqNo, long tick, long timestampMs, String type, Map<String, Object> payload) {
		this(seqNo, tick, timestampMs, type, payload, null, null);
	}
}
