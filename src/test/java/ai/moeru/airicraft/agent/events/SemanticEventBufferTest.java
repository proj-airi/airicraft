package ai.moeru.airicraft.agent.events;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticEventBufferTest extends AgentEventLogContractTest {
	@Test
	void boundPublisherStampsSourceAndOptionalCause() {
		SemanticEventBuffer buffer = new SemanticEventBuffer(2, () -> 2_000L);
		EventPublisher.Bound publisher = buffer.from("player_sensor");
		assertEquals("player_sensor", publisher.publish(1L, "a", Map.of()).source());
		EventCause cause = EventCause.event(1L);
		SemanticEvent event = publisher.publish(2L, "b", Map.of(), cause);
		assertEquals("player_sensor", event.source());
		assertEquals(cause, event.cause());
	}

	@Override
	Fixture create(int capacity) {
		SemanticEventBuffer buffer = new SemanticEventBuffer(capacity, () -> 2_000L);
		return new Fixture() {
			public EventView view() { return buffer; }
			public SemanticEvent append(long tick, String type, Map<String, Object> payload) { return buffer.append(tick, type, payload); }
			public boolean containsTypeSince(long since, String type) { return buffer.containsTypeSince(since, type); }
			public boolean containsTypeForPlayer(String type, String player) { return buffer.containsTypeForPlayer(type, player); }
			public boolean containsTypeForPlayerSince(long since, String type, String player) { return buffer.containsTypeForPlayerSince(since, type, player); }
			public int countTypeSince(long since, String type) { return buffer.countTypeSince(since, type); }
			public int countTypeForPlayerSince(long since, String type, String player) { return buffer.countTypeForPlayerSince(since, type, player); }
			public void clear() { buffer.clear(); }
			public void clearPreservingSequence() { buffer.clearPreservingSequence(); }
			public int size() { return buffer.size(); }
		};
	}
}
