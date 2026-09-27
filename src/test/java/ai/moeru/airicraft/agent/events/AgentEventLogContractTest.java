package ai.moeru.airicraft.agent.events;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class AgentEventLogContractTest {
	abstract Fixture create(int capacity);

	interface Fixture {
		EventView view();
		SemanticEvent append(long tick, String type, Map<String, Object> payload);
		boolean containsTypeSince(long since, String type);
		boolean containsTypeForPlayer(String type, String player);
		boolean containsTypeForPlayerSince(long since, String type, String player);
		int countTypeSince(long since, String type);
		int countTypeForPlayerSince(long since, String type, String player);
		void clear();
		void clearPreservingSequence();
		int size();
	}

	@Test
	void querySinceFiltersAndMarksTruncationAfterRollover() {
		Fixture log = create(3);
		log.append(1L, "a", Map.of("value", 1));
		log.append(2L, "b", Map.of("value", 2));
		log.append(3L, "c", Map.of("value", 3));
		log.append(4L, "d", Map.of("value", 4));

		SemanticEventQueryResult result = log.view().query(0L);
		assertEquals(2L, result.oldestSeqNo());
		assertEquals(4L, result.latestSeqNo());
		assertTrue(result.truncated());
		assertEquals(3, result.events().size());
		assertEquals("b", result.events().getFirst().type());
		assertEquals("d", result.events().getLast().type());
		assertFalse(log.view().query(1L).truncated());
	}

	@Test
	void queryWithoutSinceReturnsCurrentLog() {
		Fixture log = create(3);
		log.append(10L, "session.world_loaded", Map.of());
		SemanticEventQueryResult result = log.view().query(null);
		assertEquals(1, result.events().size());
		assertEquals("session.world_loaded", result.events().getFirst().type());
		assertEquals(2_000L, result.events().getFirst().timestampMs());
		assertFalse(result.truncated());
	}

	@Test
	void containsAndCountsSinceFilterByTypeAndPlayer() {
		Fixture log = create(8);
		log.append(1L, "social.player_spoke", Map.of("player", "Alice"));
		log.append(2L, "social.player_spoke", Map.of("player", "Bob"));
		log.append(3L, "social.player_spoke", Map.of("player", "Alice"));
		log.append(4L, "planner.goal_set", Map.of("player", "Alice"));
		assertEquals(4L, log.view().latestSeqNo());
		assertTrue(log.view().containsType("planner.goal_set"));
		assertTrue(log.containsTypeSince(1L, "social.player_spoke"));
		assertEquals(2, log.countTypeSince(1L, "social.player_spoke"));
		assertTrue(log.containsTypeForPlayer("social.player_spoke", "Bob"));
		assertTrue(log.containsTypeForPlayerSince(1L, "social.player_spoke", "Alice"));
		assertEquals(1, log.countTypeForPlayerSince(1L, "social.player_spoke", "Alice"));
	}

	@Test
	void capacityEvictsOnlyOnTheNextAppend() {
		Fixture log = create(2);
		log.append(1L, "a", Map.of());
		log.append(2L, "b", Map.of());
		assertEquals(0L, log.view().droppedCount());
		assertEquals(2, log.size());
		log.append(3L, "c", Map.of());
		assertEquals(1L, log.view().droppedCount());
		assertEquals(2L, log.view().query(null).oldestSeqNo());
	}

	@Test
	void evictionBoundaryTruncatesOnlyOlderCursors() {
		Fixture log = create(2);
		log.append(1L, "a", Map.of());
		log.append(2L, "b", Map.of());
		log.append(3L, "c", Map.of());
		assertTrue(log.view().query(0L).truncated());
		assertFalse(log.view().query(1L).truncated());
		assertEquals(2, log.view().query(1L).events().size());
	}

	@Test
	void clearPreservingSequenceAllowsLateEvidence() {
		Fixture log = create(8);
		log.append(1L, "runtime.shutdown_started", Map.of());
		log.clearPreservingSequence();
		assertEquals(1L, log.view().latestSeqNo());
		assertFalse(log.view().query(1L).truncated());
		log.append(2L, "runtime.shutdown_finished", Map.of());
		SemanticEventQueryResult result = log.view().query(1L);
		assertEquals(2L, result.latestSeqNo());
		assertEquals("runtime.shutdown_finished", result.events().getFirst().type());
	}

	@Test
	void clearResetsSequenceAndDropCount() {
		Fixture log = create(1);
		log.append(1L, "a", Map.of());
		log.append(2L, "b", Map.of());
		log.clear();
		assertEquals(0L, log.view().latestSeqNo());
		assertEquals(0L, log.view().droppedCount());
		assertEquals(1L, log.append(3L, "c", Map.of()).seqNo());
	}
}
