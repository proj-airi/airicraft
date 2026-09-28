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

	private static final class Triggers implements WakeScheduler.TriggerHost {
		boolean blocked, workHolds;
		final List<String> log = new ArrayList<>();
		@Override public boolean blockedGoal() { return blocked; }
		@Override public boolean workHoldsRoutineWakes() { return workHolds; }
		@Override public void audit(ai.moeru.airicraft.agent.llm.PlannerTrigger trigger, String kind, String gate) { log.add(kind + ":" + gate); }
		@Override public void deliver(ai.moeru.airicraft.agent.llm.PlannerTrigger trigger) { log.add("deliver:" + trigger.type()); }
	}

	private static ai.moeru.airicraft.agent.llm.PlannerTrigger autonomous(ai.moeru.airicraft.agent.llm.PlannerTriggerType type, String key) {
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.autonomous(type, "self", "text", 1, 1, key);
	}

	@Test void blockedGoalHoldsEveryTriggerButDirectGuidance() {
		var scheduler = new WakeScheduler();
		var host = new Triggers();
		host.blocked = true;
		scheduler.offerTrigger(autonomous(ai.moeru.airicraft.agent.llm.PlannerTriggerType.SYSTEM, "smelting"), host);
		scheduler.offerTrigger(ai.moeru.airicraft.agent.llm.PlannerTrigger.direct(ai.moeru.airicraft.agent.llm.PlannerTriggerType.CHAT, "Alex", "hi", 1, 1), host);
		scheduler.offerTrigger(null, host);
		assertEquals(List.of("dropped:G4.blocked_goal", "deliver:CHAT"), host.log);
	}

	@Test void acceptedWorkHoldsOnlyRoutineProgressAndIdleThink() {
		var scheduler = new WakeScheduler();
		var host = new Triggers();
		host.workHolds = true;
		for (var type : ai.moeru.airicraft.agent.llm.PlannerTriggerType.values()) {
			if (type == ai.moeru.airicraft.agent.llm.PlannerTriggerType.CHAT) continue;
			scheduler.offerTrigger(autonomous(type, null), host);
		}
		// CRAFT, DAMAGE, PICKUP, SYSTEM, IDLE_THINK in declaration order.
		assertEquals(List.of("dropped:G4.accepted_work", "deliver:DAMAGE", "dropped:G4.accepted_work", "deliver:SYSTEM",
			"dropped:G4.accepted_work"), host.log);
	}

	@Test void triggerPathsKeepTheirAuditLabels() {
		assertEquals("W5", WakeScheduler.triggerPath(ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(
			ai.moeru.airicraft.agent.llm.PlannerTriggerType.IDLE_THINK, "self", "", 1, 1)));
		assertEquals("W4", WakeScheduler.triggerPath(autonomous(ai.moeru.airicraft.agent.llm.PlannerTriggerType.SYSTEM, "planner_goal")));
		assertEquals("W6", WakeScheduler.triggerPath(autonomous(ai.moeru.airicraft.agent.llm.PlannerTriggerType.SYSTEM, "delegation")));
		assertEquals("W7", WakeScheduler.triggerPath(ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(
			ai.moeru.airicraft.agent.llm.PlannerTriggerType.CHAT, "evaluation", "", 1, 1)));
		assertEquals("W1", WakeScheduler.triggerPath(autonomous(ai.moeru.airicraft.agent.llm.PlannerTriggerType.PICKUP, "pickup:x")));
	}

	@Test void takeTaskWakesEmptiesTheQueueOldestFirst() {
		var scheduler = new WakeScheduler();
		scheduler.offerTask(Wake.task(1, 10, 0, null));
		scheduler.offerAttention(Wake.attention(1, 11, 0));
		assertEquals(List.of(11L, 10L), scheduler.takeTaskWakes().stream().map(Wake::eventSequence).toList());
		assertFalse(scheduler.hasTaskWakes());
	}
}
