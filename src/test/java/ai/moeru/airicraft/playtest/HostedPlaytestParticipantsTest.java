package ai.moeru.airicraft.playtest;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HostedPlaytestParticipantsTest {
	private static final String COMPANION = "00000000-0000-4000-8000-000000000001";
	private static final HostedPlaytestParticipants.Participant AIRI = new HostedPlaytestParticipants.Participant(COMPANION, "Airi");
	private static final HostedPlaytestParticipants.Participant ALEX = new HostedPlaytestParticipants.Participant("00000000-0000-4000-8000-000000000002", "Alex");
	private static final HostedPlaytestParticipants.Participant SAM = new HostedPlaytestParticipants.Participant("00000000-0000-4000-8000-000000000003", "Sam");

	@Test void companionAloneIsNotATester() {
		var participants = new HostedPlaytestParticipants(COMPANION);
		assertEquals(List.of(), participants.update(List.of(AIRI), 10, "t0"));
		assertEquals(List.of(), participants.connectedTesters());
		assertEquals(0, participants.testersEverJoined());
	}

	@Test void joinAndLeaveAreReportedOnceWithTheObservationClock() {
		var participants = new HostedPlaytestParticipants(COMPANION);
		var joined = participants.update(List.of(AIRI, ALEX), 20, "t1");
		assertEquals(1, joined.size());
		assertEquals(Map.of("event", "join", "reason", "connected", "playerUuid", ALEX.uuid(), "playerName", "Alex",
			"observedServerTick", 20L, "observedAt", "t1", "connectedTesters", 1), joined.get(0));
		assertEquals(List.of(), participants.update(List.of(AIRI, ALEX), 40, "t2"), "An unchanged snapshot is not a new join");
		var left = participants.update(List.of(AIRI), 60, "t3");
		assertEquals(1, left.size());
		assertEquals("leave", left.get(0).get("event"));
		assertEquals("disconnected", left.get(0).get("reason"));
		assertEquals(60L, left.get(0).get("observedServerTick"));
		assertEquals(0, left.get(0).get("connectedTesters"));
		assertEquals(List.of(), participants.connectedTesters());
		assertEquals(1, participants.testersEverJoined());
	}

	@Test void reconnectsCountOnePersonAndSeveralTestersAreTrackedIndependently() {
		var participants = new HostedPlaytestParticipants(COMPANION);
		participants.update(List.of(AIRI, ALEX), 1, "t1");
		participants.update(List.of(AIRI), 2, "t2");
		var changes = participants.update(List.of(AIRI, ALEX, SAM), 3, "t3");
		assertEquals(List.of("join", "join"), changes.stream().map(record -> record.get("event")).toList());
		assertEquals(List.of(ALEX, SAM), participants.connectedTesters());
		assertEquals(2, participants.testersEverJoined());
		var swap = participants.update(List.of(AIRI, SAM), 4, "t4");
		assertEquals(List.of("leave"), swap.stream().map(record -> record.get("event")).toList());
		assertEquals(ALEX.uuid(), swap.get(0).get("playerUuid"));
		assertEquals(1, swap.get(0).get("connectedTesters"));
	}

	@Test void endingTheSessionClosesEveryOpenInterval() {
		var participants = new HostedPlaytestParticipants(COMPANION);
		participants.update(List.of(AIRI, ALEX, SAM), 5, "t1");
		var closed = participants.endSession(9, "t2");
		assertEquals(List.of(ALEX.uuid(), SAM.uuid()), closed.stream().map(record -> record.get("playerUuid")).toList());
		assertTrue(closed.stream().allMatch(record -> "session_ended".equals(record.get("reason"))));
		assertEquals(List.of(), participants.connectedTesters());
		assertEquals(List.of(), participants.endSession(10, "t3"));
		assertEquals(2, participants.testersEverJoined());
	}
}
