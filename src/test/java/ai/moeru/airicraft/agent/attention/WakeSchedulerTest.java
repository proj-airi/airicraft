package ai.moeru.airicraft.agent.attention;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WakeSchedulerTest {
	private static final class Host implements WakeScheduler.TaskWakeHost {
		boolean external, inFlight, unavailable, runPolicy, queuedTools;
		long revision;
		String mission;
		final Set<Long> incorporated = new HashSet<>();
		final Set<Long> irrelevant = new HashSet<>();
		final List<String> log = new ArrayList<>();

		@Override public boolean externalDriverActive() { return external; }
		@Override public boolean plannerInFlight() { return inFlight; }
		@Override public boolean plannerUnavailable() { return unavailable; }
		@Override public boolean runPolicyHold() { return runPolicy; }
		@Override public boolean queuedToolWork() { return queuedTools; }
		@Override public boolean satisfied(Wake wake) { return incorporated.containsAll(wake.eventRefs()); }
		@Override public boolean irrelevantToBlockedGoal(Wake wake) { return irrelevant.contains(wake.eventSequence()); }
		@Override public long guidanceRevision() { return revision; }
		@Override public String currentMissionId() { return mission; }
		@Override public void audit(Wake wake, String kind, String gate) { log.add(kind + ":" + wake.eventSequence() + ":" + gate); }
		@Override public void superseded(Wake wake, String reason, String currentMissionId) { log.add("superseded:" + wake.eventSequence() + ":" + reason); }
		@Override public void deliver(Wake wake) { log.add("deliver:" + wake.eventSequence()); }
	}

	@Test void releasesOneWakeInOrderWithAttentionFirst() {
		var scheduler = new WakeScheduler();
		var host = new Host();
		scheduler.offerTask(Wake.task(1, 10, 0, null));
		scheduler.offerTask(Wake.task(1, 11, 0, null));
		scheduler.offerAttention(Wake.attention(2, 12, 0));
		assertTrue(scheduler.releaseTaskWake(host));
		assertTrue(scheduler.releaseTaskWake(host));
		assertTrue(scheduler.releaseTaskWake(host));
		assertFalse(scheduler.releaseTaskWake(host));
		assertEquals(List.of("deliver:12", "deliver:10", "deliver:11"), host.log);
	}

	@Test void waitsWhileInFlightOrExternalAndClearsWhenUnavailable() {
		var scheduler = new WakeScheduler();
		var host = new Host();
		scheduler.offerTask(Wake.task(1, 10, 0, null));
		host.inFlight = true;
		assertFalse(scheduler.releaseTaskWake(host));
		host.inFlight = false;
		host.external = true;
		assertFalse(scheduler.releaseTaskWake(host));
		assertTrue(scheduler.hasTaskWakes());
		host.external = false;
		host.unavailable = true;
		assertFalse(scheduler.releaseTaskWake(host));
		assertFalse(scheduler.hasTaskWakes());
		assertEquals(List.of(), host.log);
	}

	@Test void retainedGatesHoldOrdinaryWakesAndAuditEachTransitionOnce() {
		var scheduler = new WakeScheduler();
		var host = new Host();
		scheduler.offerTask(Wake.task(1, 10, 0, null));
		host.runPolicy = true;
		assertFalse(scheduler.releaseTaskWake(host));
		assertFalse(scheduler.releaseTaskWake(host));
		host.runPolicy = false;
		host.queuedTools = true;
		assertFalse(scheduler.releaseTaskWake(host));
		assertFalse(scheduler.releaseTaskWake(host));
		scheduler.outcomeRecorded();
		assertFalse(scheduler.releaseTaskWake(host));
		assertEquals(List.of("dropped:10:G5.run_policy", "dropped:10:G5.queued_tool_work", "dropped:10:G5.queued_tool_work"), host.log);
		// Attention bypasses both holds.
		scheduler.offerAttention(Wake.attention(2, 11, 0));
		assertTrue(scheduler.releaseTaskWake(host));
		assertEquals("deliver:11", host.log.getLast());
	}

	@Test void dropsSatisfiedIrrelevantAndSupersededWakesOnTheWay() {
		var scheduler = new WakeScheduler();
		var host = new Host();
		host.incorporated.add(10L);
		host.irrelevant.add(11L);
		host.revision = 1;
		host.mission = "m2";
		scheduler.offerTask(Wake.task(1, 10, 1, null));
		scheduler.offerTask(Wake.task(1, 11, 1, null));
		scheduler.offerTask(Wake.task(1, 12, 0, null));
		scheduler.offerTask(Wake.task(1, 13, 1, "m1"));
		scheduler.offerTask(Wake.task(1, 14, 1, "m2"));
		assertTrue(scheduler.releaseTaskWake(host));
		assertEquals(List.of("dropped:10:G5.incorporated", "dropped:11:G5.blocked_irrelevant",
			"superseded:12:new_user_guidance", "superseded:13:mission_changed", "deliver:14"), host.log);
	}

	@Test void takeTaskWakesEmptiesTheQueueOldestFirst() {
		var scheduler = new WakeScheduler();
		scheduler.offerTask(Wake.task(1, 10, 0, null));
		scheduler.offerAttention(Wake.attention(1, 11, 0));
		assertEquals(List.of(11L, 10L), scheduler.takeTaskWakes().stream().map(Wake::eventSequence).toList());
		assertFalse(scheduler.hasTaskWakes());
	}
}
