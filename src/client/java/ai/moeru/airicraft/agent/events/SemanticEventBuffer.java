package ai.moeru.airicraft.agent.events;

import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class SemanticEventBuffer implements EventStream {
	private final LongSupplier clock;
	private final AgentEventLog log;

	public SemanticEventBuffer(int capacity) {
		this(capacity, System::currentTimeMillis);
	}

	public SemanticEventBuffer(int capacity, LongSupplier clock) {
		this.log = new AgentEventLog(capacity);
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public SemanticEvent append(long tick, String type, Map<String, Object> payload) {
		return append(tick, clock.getAsLong(), type, payload);
	}

	public SemanticEvent append(long tick, String type, Map<String, Object> payload, String source, EventCause cause) {
		return append(tick, clock.getAsLong(), type, payload, source, cause);
	}

	public SemanticEvent append(long tick, long timestampMs, String type, Map<String, Object> payload) {
		return append(tick, timestampMs, type, payload, null, null);
	}

	public SemanticEvent append(long tick, long timestampMs, String type, Map<String, Object> payload, String source, EventCause cause) {
		return log.append(tick, timestampMs, type, payload, source, cause);
	}

	@Override
	public SemanticEvent publish(long tick, String type, Map<String, Object> payload, String source, EventCause cause) {
		return append(tick, type, payload, source, cause);
	}

	@Override
	public SemanticEventQueryResult query(Long sinceSeqNo) {
		return log.query(sinceSeqNo);
	}

	@Override
	public long latestSeqNo() {
		return log.latestSeqNo();
	}

	@Override
	public boolean containsType(String type) {
		return log.containsType(type);
	}

	public boolean containsTypeSince(long sinceSeqNo, String type) {
		return log.containsTypeSince(sinceSeqNo, type);
	}

	public boolean containsTypeForPlayer(String type, String playerName) {
		return log.containsTypeForPlayer(type, playerName);
	}

	public boolean containsTypeForPlayerSince(long sinceSeqNo, String type, String playerName) {
		return log.containsTypeForPlayerSince(sinceSeqNo, type, playerName);
	}

	public int countTypeSince(long sinceSeqNo, String type) {
		return log.countTypeSince(sinceSeqNo, type);
	}

	public int countTypeForPlayerSince(long sinceSeqNo, String type, String playerName) {
		return log.countTypeForPlayerSince(sinceSeqNo, type, playerName);
	}

	public void clear() {
		log.clear();
	}

	/** Clears retained payloads without reusing sequence numbers. */
	public void clearPreservingSequence() {
		log.clearPreservingSequence();
	}

	public int size() {
		return log.size();
	}

	@Override
	public long droppedCount() {
		return log.droppedCount();
	}
}
