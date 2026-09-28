package ai.moeru.airicraft.agent.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentEventLog implements EventView {
	private final int capacity;
	private final ArrayDeque<SemanticEvent> events = new ArrayDeque<>();
	private long nextSeqNo = 1L;
	private long droppedCount;

	public AgentEventLog(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive");
		}
		this.capacity = capacity;
	}

	SemanticEvent append(long tick, long timestampMs, String type, Map<String, Object> payload, String source, EventCause cause) {
		Objects.requireNonNull(type, "type");
		Map<String, Object> safePayload = payload == null ? Map.of() : new LinkedHashMap<>(payload);
		SemanticEvent event = new SemanticEvent(nextSeqNo++, tick, timestampMs, type, Map.copyOf(safePayload), source, cause);
		if (events.size() == capacity) {
			events.removeFirst();
			droppedCount++;
		}
		events.addLast(event);
		return event;
	}

	@Override
	public SemanticEventQueryResult query(Long sinceSeqNo) {
		long oldestSeqNo = events.isEmpty() ? nextSeqNo : events.getFirst().seqNo();
		long latestSeqNo = events.isEmpty() ? nextSeqNo - 1L : events.getLast().seqNo();
		long effectiveSince = sinceSeqNo == null ? 0L : sinceSeqNo.longValue();
		List<SemanticEvent> matches = new ArrayList<>();
		for (SemanticEvent event : events) {
			if (event.seqNo() > effectiveSince) {
				matches.add(event);
			}
		}
		boolean truncated = sinceSeqNo != null && oldestSeqNo > 1L && sinceSeqNo < oldestSeqNo - 1L;
		return new SemanticEventQueryResult(oldestSeqNo, latestSeqNo, truncated, List.copyOf(matches));
	}

	@Override
	public long latestSeqNo() {
		return events.isEmpty() ? nextSeqNo - 1L : events.getLast().seqNo();
	}

	@Override
	public boolean containsType(String type) {
		for (SemanticEvent event : events) {
			if (event.type().equals(type)) {
				return true;
			}
		}
		return false;
	}

	public boolean containsTypeSince(long sinceSeqNo, String type) {
		return countTypeSince(sinceSeqNo, type) > 0;
	}

	public boolean containsTypeForPlayer(String type, String playerName) {
		for (SemanticEvent event : events) {
			if (event.type().equals(type) && playerName.equals(event.payload().get("player"))) {
				return true;
			}
		}
		return false;
	}

	public boolean containsTypeForPlayerSince(long sinceSeqNo, String type, String playerName) {
		return countTypeForPlayerSince(sinceSeqNo, type, playerName) > 0;
	}

	public int countTypeSince(long sinceSeqNo, String type) {
		int count = 0;
		for (SemanticEvent event : events) {
			if (event.seqNo() > sinceSeqNo && event.type().equals(type)) {
				count++;
			}
		}
		return count;
	}

	public int countTypeForPlayerSince(long sinceSeqNo, String type, String playerName) {
		int count = 0;
		for (SemanticEvent event : events) {
			if (event.seqNo() > sinceSeqNo && event.type().equals(type) && playerName.equals(event.payload().get("player"))) {
				count++;
			}
		}
		return count;
	}

	void clear() {
		events.clear();
		droppedCount = 0L;
		nextSeqNo = 1L;
	}

	void clearPreservingSequence() {
		events.clear();
		droppedCount = 0L;
	}

	public int size() {
		return events.size();
	}

	@Override
	public long droppedCount() {
		return droppedCount;
	}
}
