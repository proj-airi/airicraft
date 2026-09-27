package ai.moeru.airicraft.playtest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Tester connections in a hosted playtest, diffed from periodic server player-list snapshots. */
public final class HostedPlaytestParticipants {
	public record Participant(String uuid, String name) {
		public Participant {
			Objects.requireNonNull(uuid, "uuid");
			Objects.requireNonNull(name, "name");
		}
	}

	private final String companionUuid;
	private final Map<String, Participant> connected = new LinkedHashMap<>();
	private final Set<String> everJoined = new LinkedHashSet<>();

	public HostedPlaytestParticipants(String companionUuid) {
		this.companionUuid = Objects.requireNonNull(companionUuid, "companionUuid");
	}

	/** Returns join/leave records for the change since the previous snapshot; the companion is never a tester. */
	public List<Map<String, Object>> update(List<Participant> snapshot, long observedServerTick, String observedAt) {
		Map<String, Participant> next = new LinkedHashMap<>();
		for (Participant participant : snapshot) {
			if (!participant.uuid().equals(companionUuid)) next.put(participant.uuid(), participant);
		}
		List<Map<String, Object>> records = new ArrayList<>();
		for (Participant previous : connected.values()) {
			if (!next.containsKey(previous.uuid())) records.add(record("leave", previous, "disconnected", observedServerTick, observedAt, next.size()));
		}
		for (Participant current : next.values()) {
			if (!connected.containsKey(current.uuid())) {
				everJoined.add(current.uuid());
				records.add(record("join", current, "connected", observedServerTick, observedAt, next.size()));
			}
		}
		connected.clear();
		connected.putAll(next);
		return records;
	}

	/** Closes every open connection interval when the recording ends while testers are still connected. */
	public List<Map<String, Object>> endSession(long observedServerTick, String observedAt) {
		List<Map<String, Object>> records = new ArrayList<>();
		for (Participant participant : connected.values()) {
			records.add(record("leave", participant, "session_ended", observedServerTick, observedAt, 0));
		}
		connected.clear();
		return records;
	}

	public List<Participant> connectedTesters() { return List.copyOf(connected.values()); }
	public int testersEverJoined() { return everJoined.size(); }

	private static Map<String, Object> record(String event, Participant participant, String reason,
		long observedServerTick, String observedAt, int connectedTesters) {
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("event", event);
		record.put("reason", reason);
		record.put("playerUuid", participant.uuid());
		record.put("playerName", participant.name());
		record.put("observedServerTick", observedServerTick);
		record.put("observedAt", observedAt);
		record.put("connectedTesters", connectedTesters);
		return record;
	}
}
