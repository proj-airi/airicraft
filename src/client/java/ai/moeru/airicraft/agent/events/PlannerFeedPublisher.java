package ai.moeru.airicraft.agent.events;

import java.util.Map;
import java.util.Objects;

/**
 * Names the legacy planner-only write boundary (D1). Dialogue effects emitted while
 * processing W1 planner input stay in this feed, outside the raw log. Phase 3 will
 * remove this split; until then this wrapper preserves the existing cursor behavior.
 */
public final class PlannerFeedPublisher implements EventStream {
	private final SemanticEventBuffer buffer;

	private PlannerFeedPublisher(SemanticEventBuffer buffer) {
		this.buffer = Objects.requireNonNull(buffer, "buffer");
	}

	public static EventStream wrap(SemanticEventBuffer buffer) {
		return new PlannerFeedPublisher(buffer);
	}

	@Override
	public SemanticEvent publish(long tick, String type, Map<String, Object> payload, String source, EventCause cause) {
		return buffer.publish(tick, type, payload, source, cause);
	}

	@Override public SemanticEventQueryResult query(Long sinceSeqNo) { return buffer.query(sinceSeqNo); }
	@Override public long latestSeqNo() { return buffer.latestSeqNo(); }
	@Override public boolean containsType(String type) { return buffer.containsType(type); }
	@Override public long droppedCount() { return buffer.droppedCount(); }
}
