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
		String preemption = "nothing_in_flight";
		@Override public String preemptInFlight() {
			log.add("preempt?");
			if ("preempted".equals(preemption)) inFlight = false;
			return preemption;
		}
	}

	@Test void aPreemptWakeBehindAnOrdinaryWakeStillPreemptsAndGoesFirst() {
		var scheduler = new WakeScheduler();
		var host = new Host();
		host.inFlight = true;
		host.preemption = "preempted";
		scheduler.offerTask(Wake.task(30, 10, 0, null));
		scheduler.offerTask(Wake.task(30, 11, 0, null).preempting());
		assertTrue(scheduler.releaseTaskWake(host));
		assertEquals(List.of("preempt?", "preempted:11:preempt.safety_epoch", "deliver:11"), host.log);
		assertTrue(scheduler.releaseTaskWake(host));
		assertEquals("deliver:10", host.log.getLast(), "the earlier wake stays queued behind the reflex");
	}

	@Test void thePendingSetIsBoundedAndKeepsAttentionAndPreemptingWakes() {
		var scheduler = new WakeScheduler();
		var host = new Host();
		host.inFlight = true;
		scheduler.offerTask(Wake.task(1, 1, 0, null).preempting());
		scheduler.offerAttention(Wake.attention(1, 2, 0));
		for (long seq = 3; seq < 3 + WakeScheduler.MAX_TASK_WAKES; seq++) scheduler.offerTask(Wake.task(1, seq, 0, null));
		assertEquals(WakeScheduler.MAX_TASK_WAKES, ((List<?>) scheduler.debugState().get("pending")).size());
		scheduler.releaseTaskWake(host);
		assertEquals(List.of("dropped:3:pending.bounded", "dropped:4:pending.bounded"), host.log.subList(0, 2),
			"the two oldest ordinary wakes went; attention and preempting wakes stayed");

		var triggers = new Triggers();
		for (long seq = 1; seq <= WakeScheduler.MAX_DEBOUNCED; seq++) scheduler.offerTrigger(percept(seq, 10, "LOW"), triggers);
		scheduler.offerTrigger(percept(100, 10, "NORMAL"), triggers);
		assertEquals("dropped:pending.bounded:1", triggers.log.getLast(), "the oldest LOW wake goes, not the NORMAL one");
	}

	@Test void aPreemptWakeCancelsAStaleTurnAndIsDeliveredAtOnce() {
		var scheduler = new WakeScheduler();
		var host = new Host();
		host.inFlight = true;
		scheduler.offerTask(Wake.task(1, 10, 0, null));
		assertFalse(scheduler.releaseTaskWake(host), "an ordinary wake waits for the turn");
		assertEquals(List.of(), host.log, "and never asks to preempt");

		var preempting = new WakeScheduler();
		preempting.offerTask(Wake.task(2, 11, 0, null).preempting());
		host.preemption = "side_effect_tool_ran";
		assertFalse(preempting.releaseTaskWake(host));
		assertFalse(preempting.releaseTaskWake(host));
		assertEquals(List.of("preempt?", "dropped:11:preempt.side_effect_tool_ran", "preempt?"), host.log,
			"a refusal is audited once and the wake keeps waiting");
		host.preemption = "preempted";
		assertTrue(preempting.releaseTaskWake(host));
		assertEquals(List.of("preempt?", "preempted:11:preempt.safety_epoch", "deliver:11"), host.log.subList(3, 6));
		var debugged = new WakeScheduler();
		debugged.offerTask(Wake.task(3, 12, 0, null).preempting());
		assertEquals("PREEMPT", ((java.util.Map<?, ?>) ((List<?>) debugged.debugState().get("pending")).getFirst()).get("delivery"));
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
		var debug = scheduler.debugState();
		assertEquals("G5.queued_tool_work", debug.get("retainedBy"));
		var pending = (List<?>) debug.get("pending");
		assertEquals(1, pending.size());
		assertEquals("W2", ((java.util.Map<?, ?>) pending.getFirst()).get("path"));
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

	private static class Triggers implements WakeScheduler.TriggerHost {
		boolean blocked, workHolds;
		long incorporatedThrough;
		final List<String> log = new ArrayList<>();
		@Override public boolean blockedGoal() { return blocked; }
		@Override public boolean workHoldsRoutineWakes() { return workHolds; }
		@Override public boolean incorporated(long seqNo) { return seqNo <= incorporatedThrough; }
		@Override public void audit(ai.moeru.airicraft.agent.llm.PlannerTrigger trigger, String kind, String gate) {
			log.add(kind + ":" + gate + (trigger.wake() == null ? "" : ":" + trigger.wake().seqNo()));
		}
		@Override public void deliver(List<ai.moeru.airicraft.agent.llm.PlannerTrigger> triggers) {
			log.add("deliver:" + String.join(",", triggers.stream().map(trigger -> trigger.wake() == null
				? trigger.type().name() : String.valueOf(trigger.wake().seqNo())).toList()));
		}
	}

	/** A planner with a replaceable turn in flight until something supersedes it. */
	private static final class Superseding extends Triggers {
		boolean replaceable = true;
		int queued;
		int released;
		@Override public boolean canSupersede() { return replaceable; }
		@Override public void releaseCoalesceHold() { released++; }
		@Override public int queuedTriggerCount() { return queued; }
		@Override public void deliver(List<ai.moeru.airicraft.agent.llm.PlannerTrigger> triggers, boolean supersede) {
			queued += triggers.size();
			log.add((supersede ? "supersede:" : "queue:") + triggers.getLast().tick());
			if (supersede) replaceable = false;
		}
	}

	private static ai.moeru.airicraft.agent.llm.PlannerTrigger chat(long tick) {
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.direct(ai.moeru.airicraft.agent.llm.PlannerTriggerType.CHAT, "Alex", "hi", tick, tick * 50);
	}

	@Test void aSupersedeOpensACoalesceWindowTimedInTicks() {
		var scheduler = new WakeScheduler();
		var host = new Superseding();
		host.queued = 1; // the running turn's trigger
		scheduler.offerTrigger(chat(10), host);
		assertEquals(List.of("supersede:10"), host.log);
		assertTrue(scheduler.coalescing());
		assertFalse(scheduler.releaseCoalesce(10), "10 ms rounds up to one tick");
		assertTrue(scheduler.releaseCoalesce(11));
		assertFalse(scheduler.coalescing());

		// Each batch delivered into the window re-arms it with the queue's size, clamped to the maximum (100 ms: 2 ticks).
		host.replaceable = true;
		host.queued = 1;
		scheduler.offerTrigger(chat(20), host);
		for (int index = 0; index < 15; index++) scheduler.offerTrigger(chat(20), host);
		assertEquals("queue:20", host.log.getLast(), "inside the window, direct guidance joins the held turn");
		assertFalse(scheduler.releaseCoalesce(21));
		assertTrue(scheduler.releaseCoalesce(22));

		var single = new WakeScheduler();
		var alone = new Superseding();
		single.offerTrigger(chat(30), alone);
		assertFalse(single.coalescing(), "a single queued trigger has nothing to coalesce");
		assertEquals(1, alone.released, "the planner starts at once, as before the move");

		var zero = new WakeScheduler();
		zero.configureCoalescing(0, 0, 0);
		var host0 = new Superseding();
		host0.queued = 3;
		zero.offerTrigger(chat(40), host0);
		assertFalse(zero.coalescing());
		assertEquals(1, host0.released, "a zero window closes at once");

		var reset = new WakeScheduler();
		var host1 = new Superseding();
		host1.queued = 2;
		reset.offerTrigger(chat(50), host1);
		assertTrue(reset.coalescing());
		reset.clearTaskWakes();
		assertFalse(reset.coalescing(), "a reset closes the window, so new guidance can supersede again");
	}

	@Test void theSupersedeBudgetQueuesDirectGuidanceOverThreeIn600Ticks() {
		var scheduler = new WakeScheduler();
		var host = new Superseding();
		for (long tick : new long[]{1, 40, 80, 120}) {
			host.replaceable = true;
			scheduler.offerTrigger(chat(tick), host);
			scheduler.releaseCoalesce(tick + 5);
		}
		assertEquals(List.of("supersede:1", "supersede:40", "supersede:80", "queued:supersede.budget", "queue:120"), host.log);
		host.replaceable = true;
		scheduler.offerTrigger(chat(601), host);
		assertEquals("supersede:601", host.log.getLast(), "the first supersede left the 600-tick window");
		scheduler.releaseCoalesce(700);
		scheduler.clearTaskWakes();
		assertTrue(((List<?>) scheduler.debugState().get("recentSupersedes")).isEmpty(), "resets clear the budget window");
	}

	private static ai.moeru.airicraft.agent.llm.PlannerTrigger percept(long seqNo, long tick, String urgency) {
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.autonomous(ai.moeru.airicraft.agent.llm.PlannerTriggerType.SYSTEM, "self",
			"noticed", tick, tick * 50, "perception:" + seqNo)
			.withWake(ai.moeru.airicraft.agent.llm.WakeRef.event(seqNo, "perception.block_noticed", urgency).debounce(true));
	}

	@Test void debouncedWakesWaitForQuietAndLeaveAsOneBatch() {
		var scheduler = new WakeScheduler();
		var host = new Triggers();
		scheduler.offerTrigger(percept(5, 100, "LOW"), host);
		scheduler.offerTrigger(percept(6, 104, "LOW"), host);
		assertFalse(scheduler.releaseDebounced(113, host), "only 9 quiet ticks since the last one");
		scheduler.offerTrigger(percept(7, 113, "LOW"), host);
		assertFalse(scheduler.releaseDebounced(122, host));
		assertTrue(scheduler.releaseDebounced(123, host));
		assertEquals(List.of("debounced:debounce.hold:5", "debounced:debounce.hold:6", "debounced:debounce.hold:7",
			"released:debounce.quiet:5", "released:debounce.quiet:6", "released:debounce.quiet:7", "deliver:5,6,7"), host.log);
		assertFalse(scheduler.hasDebouncedWakes());
	}

	@Test void aSteadyTrickleIsReleasedAfterTheMaximumHold() {
		var scheduler = new WakeScheduler();
		var host = new Triggers();
		for (long tick = 0; tick <= 100; tick += 5) {
			scheduler.offerTrigger(percept(tick + 1, tick, "LOW"), host);
			if (scheduler.releaseDebounced(tick, host)) {
				assertEquals(100, tick, "released once the first wake has waited 100 ticks");
				assertTrue(host.log.getLast().startsWith("deliver:1,6,11"));
				return;
			}
		}
		fail("never released: " + host.log);
	}

	@Test void anImmediateWakeTakesTheHeldOnesAlongAndProtectedUrgencyIsNeverHeld() {
		var scheduler = new WakeScheduler();
		var host = new Triggers();
		scheduler.offerTrigger(percept(3, 10, "LOW"), host);
		scheduler.offerTrigger(percept(4, 11, "HIGH"), host);
		assertEquals(List.of("debounced:debounce.hold:3", "released:debounce.piggyback:3", "deliver:3,4"), host.log);
	}

	@Test void heldWakesAlreadyObservedOrBlockedAreDroppedAndResetsClearThem() {
		var scheduler = new WakeScheduler();
		var host = new Triggers();
		scheduler.offerTrigger(percept(3, 10, "LOW"), host);
		scheduler.offerTrigger(percept(4, 10, "LOW"), host);
		host.incorporatedThrough = 3;
		assertTrue(scheduler.releaseDebounced(20, host));
		assertEquals(List.of("debounced:debounce.hold:3", "debounced:debounce.hold:4", "dropped:debounce.incorporated:3",
			"released:debounce.quiet:4", "deliver:4"), host.log);
		host.log.clear();
		scheduler.offerTrigger(percept(9, 30, "LOW"), host);
		host.blocked = true;
		host.log.clear();
		assertFalse(scheduler.releaseDebounced(40, host));
		assertEquals(List.of("dropped:G4.blocked_goal:9"), host.log);
		host.blocked = false;
		scheduler.offerTrigger(percept(10, 50, "LOW"), host);
		scheduler.clearTaskWakes();
		assertFalse(scheduler.hasDebouncedWakes());
		assertEquals(List.of(), ((List<?>) scheduler.debugState().get("debounced")));
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
