package ai.moeru.airicraft.agent.events;

import java.util.Map;

class ArrayDequeAgentEventLogTest extends AgentEventLogContractTest {
	@Override
	Fixture create(int capacity) {
		AgentEventLog log = new AgentEventLog(capacity);
		return new Fixture() {
			public EventView view() { return log; }
			public SemanticEvent append(long tick, String type, Map<String, Object> payload) { return log.append(tick, 2_000L, type, payload, null, null); }
			public boolean containsTypeSince(long since, String type) { return log.containsTypeSince(since, type); }
			public boolean containsTypeForPlayer(String type, String player) { return log.containsTypeForPlayer(type, player); }
			public boolean containsTypeForPlayerSince(long since, String type, String player) { return log.containsTypeForPlayerSince(since, type, player); }
			public int countTypeSince(long since, String type) { return log.countTypeSince(since, type); }
			public int countTypeForPlayerSince(long since, String type, String player) { return log.countTypeForPlayerSince(since, type, player); }
			public void clear() { log.clear(); }
			public void clearPreservingSequence() { log.clearPreservingSequence(); }
			public int size() { return log.size(); }
		};
	}
}
