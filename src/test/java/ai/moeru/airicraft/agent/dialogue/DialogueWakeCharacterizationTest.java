package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.debug.AgentDebugTimelineEntry;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.llm.*;
import ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore;
import ai.moeru.airicraft.agent.session.*;
import ai.moeru.airicraft.agent.wakes.*;
import ai.moeru.airicraft.agent.work.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DialogueWakeCharacterizationTest {
	@TempDir Path world;
	final class Harness implements AutoCloseable {
		volatile long tick = 1;
		final MutableClock clock = new MutableClock();
		final RecordingPlannerBackend backend = new RecordingPlannerBackend(() -> tick);
		final SemanticEventBuffer events = new SemanticEventBuffer(512);
		final PlannerGoalStore goal = new PlannerGoalStore(() -> world);
		final DialogueRuntime dialogue = DialogueWakeFixture.create(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY, goal, clock, PlannerToolRegistry.of(new PlannerQueueToolProvider(PlannerActionToolExecutor.DISABLED)));
		final List<AgentDebugTimelineEntry> audit = new ArrayList<>();
		final SessionSnapshot session = new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, 1);
		Harness() {
			dialogue.configureWakeAudit((tick, kind, fields) -> audit.add(new AgentDebugTimelineEntry(audit.size()+1, tick, clock.millis(), "planner_wake", kind, "", Map.of(), fields)));
			dialogue.configureDecisionContext(() -> new PlannerDecisionContext("world", tick, tick * 2, dialogue.decisionOwner(), "idle", Map.of(), events.query(null)));
		}
		void trigger(PlannerTrigger trigger) { dialogue.onPlannerTrigger(trigger, session, "Alex", Optional.empty(), null, null, events); }
		void poll() {
			dialogue.poll(tick, events, session, Optional.empty(), null, null);
			long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
			while (dialogue.plannerDebugSnapshot().inFlight() && !backend.held()) {
				dialogue.poll(tick, events, session, Optional.empty(), null, null);
				if (System.nanoTime() > deadline) throw new AssertionError("did not settle");
				Thread.yield();
			}
		}
		void advance(int ticks) { for(int i=0; i<ticks; i++) { tick++; clock.advance(Duration.ofMillis(50)); poll(); } }
		WakeTranscript transcript(String name) { return WakeTranscript.capture(name, backend.requests(), audit, events.query(null).events(), dialogue.allAvailableTools()); }
		void golden(String name) { transcript(name).assertMatchesGolden(name); }
		@Override public void close() { dialogue.shutdown(); }
	}
	private static WorkSnapshot work() { return new WorkSnapshot(new WorkHandle("OPERATION:policy"), "", WorkSnapshot.State.RUNNING, "run_policy", "POLICY", true, 1, Map.of()); }
	@Test void retainedWakeAuditRecordsEachGateTransitionOnce() {
		try (var h = new Harness()) {
			h.dialogue.observeAcceptedWork(work());
			h.dialogue.queueTaskWakeup(null, 1, h.events.append(1, "work.changed", work().payload()).seqNo());
			h.advance(300);
			assertTrue(h.backend.requests().isEmpty());
			assertEquals(1, h.audit.size(), "retained wake must not flood the audit on every poll");
			assertEquals("G5.run_policy", h.audit.getFirst().payload().get("gate"));
			h.dialogue.queueTaskAttention(h.tick, h.events.append(h.tick, "task.notice", Map.of("message", "stalled")).seqNo());
			h.advance(1);
			assertEquals(1, h.backend.requests().size(), "attention still bypasses the retained ordinary wake");
		}
	}
	@Test void work_stalled_attention_during_accepted_work() {
		try (var h = new Harness()) {
			h.dialogue.observeAcceptedWork(work());
			h.dialogue.queueTaskWakeup(null, 1, h.events.append(1, "work.changed", work().payload()).seqNo());
			h.poll(); assertTrue(h.backend.requests().isEmpty());
			h.dialogue.queueTaskAttention(2, h.events.append(2, "task.notice", Map.of("message", "stalled")).seqNo());
			h.advance(1); assertEquals(1, h.backend.requests().size());
			h.golden("work_stalled_attention_during_accepted_work");
		}
	}
	@Test void task_wakeups_superseded_by_guidance() {
		try (var h = new Harness()) {
			h.dialogue.queueTaskWakeup(null, 1, h.events.append(1, "task.notice", Map.of("message", "old work")).seqNo());
			h.trigger(PlannerTrigger.direct(PlannerTriggerType.CHAT, "Alex", "hello", 1, h.clock.millis())); h.poll();
			assertTrue(h.events.containsType("planner.internal_task_update_superseded"));
			h.golden("task_wakeups_superseded_by_guidance");
		}
	}
	@Test void blocked_goal_reconsider() throws Exception {
		try (var h = new Harness()) {
			var goal = h.goal.set("Build chest"); h.goal.block(goal.id(), "no wood", "exhausted", "new wood", List.of("work.changed"));
			h.poll();
			h.trigger(PlannerTrigger.autonomous(PlannerTriggerType.PICKUP, "self", "pickup", 1, h.clock.millis(), "pickup"));
			h.dialogue.queueTaskWakeup(null, 1, h.events.append(1, "task.notice", Map.of("message", "unrelated")).seqNo());
			h.poll(); assertTrue(h.backend.requests().isEmpty());
			h.events.append(2, "work.changed", work().payload()); h.advance(1);
			assertEquals(1, h.backend.requests().size()); h.golden("blocked_goal_reconsider");
		}
	}
	@Test void goal_continuation_idle_and_busy() throws Exception {
		try (var h = new Harness()) {
			h.goal.set("Build chest");
			h.dialogue.continuePlannerGoal(1, false, h.session, null, Optional.empty(), null, null, h.events);
			h.poll(); assertTrue(h.backend.requests().isEmpty());
			h.advance(19);
			h.dialogue.continuePlannerGoal(20, true, h.session, null, Optional.empty(), null, null, h.events);
			h.poll(); assertTrue(h.backend.requests().isEmpty());
			h.advance(1);
			h.dialogue.continuePlannerGoal(21, true, h.session, null, Optional.empty(), null, null, h.events);
			h.poll(); assertEquals(1, h.backend.requests().size()); h.golden("goal_continuation_idle_and_busy");
		}
	}
	@Test void safety_hold_awaiting_decision() throws Exception {
		try (var h = new Harness()) {
			h.goal.set("Build chest"); h.dialogue.updateSafetyContext(1, "hold-1", false);
			h.dialogue.continuePlannerGoal(1, true, h.session, null, Optional.empty(), null, null, h.events);
			h.poll(); assertEquals(1, h.backend.requests().size());
			assertTrue(h.backend.requests().getFirst().request().request().message().contains("still awaits your decision"));
			h.golden("safety_hold_awaiting_decision");
		}
	}
	@Test void stale_safety_response_rejected() {
		try (var h = new Harness()) {
			var held = h.backend.holdNext();
			h.trigger(PlannerTrigger.direct(PlannerTriggerType.CHAT, "Alex", "hello", 1, h.clock.millis()));
			h.backend.awaitRequests(1, Duration.ofSeconds(1));
			h.dialogue.updateSafetyContext(2, "hold-2", true);
			held.complete(RecordingPlannerBackend.yieldResponse()); h.poll();
			assertFalse(h.dialogue.drainStalePlannerRejections().isEmpty());
			h.advance(10); assertEquals(1, h.backend.requests().size()); h.golden("stale_safety_response_rejected");
		}
	}
	/** Moved here: direct invalidation pins G9 independently of a live world action executor. */
	@Test void idle_think_invalidated_by_action_goal() {
		try (var h = new Harness()) {
			var held = h.backend.holdNext();
			h.trigger(PlannerTrigger.direct(PlannerTriggerType.CHAT, "Alex", "hello", 1, h.clock.millis()));
			h.backend.awaitRequests(1, Duration.ofSeconds(1));
			h.trigger(PlannerTrigger.pending(PlannerTriggerType.IDLE_THINK, "self", "idle", 1, h.clock.millis()));
			h.dialogue.invalidateIdleThinkTriggers(); held.complete(RecordingPlannerBackend.yieldResponse()); h.poll(); h.advance(20);
			assertEquals(1, h.backend.requests().size()); h.golden("idle_think_invalidated_by_action_goal");
		}
	}
	@Test void d1_CONFIRMED_rawCursorSkipsPlannerBufferNotice() {
		try (var h = new Harness()) {
			for (int i = 1; i <= 10; i++) h.events.append(i, "task.notice", Map.of("message", "raw " + i));
			h.dialogue.queueTaskWakeup(null, 10, 10); h.poll(); h.advance(1);
			var planner = new SemanticEventBuffer(512);
			planner.append(11, "pickup.item_picked_up", Map.of("itemId", "minecraft:diamond", "count", 1));
			h.dialogue.onPlannerTrigger(PlannerTrigger.pending(PlannerTriggerType.PICKUP, "self", "pickup", 11, h.clock.millis()),
				h.session, null, Optional.empty(), null, null, planner);
			h.poll();
			assertEquals(2, h.backend.requests().size());
			assertFalse(h.backend.requests().getLast().request().conversation().messages().stream().anyMatch(m -> m.content().contains("minecraft:diamond")));
		}
	}
	@Test void task_wakeup_mission_changed() {
		try (var h = new Harness()) {
			h.dialogue.queueTaskWakeup("old-mission", 1, h.events.append(1, "task.notice", Map.of("message", "old")).seqNo());
			var mission = new ai.moeru.airicraft.agent.tasks.MissionSpec("new-mission", ai.moeru.airicraft.agent.tasks.MissionType.COLLECT_RESOURCE, "wood");
			var execution = new ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot(mission, null, null, null,
				ai.moeru.airicraft.agent.tasks.StepExecutionResult.idle(), ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot.idle());
			h.dialogue.poll(1, h.events, h.session, Optional.empty(), null, execution);
			assertTrue(h.backend.requests().isEmpty());
			assertTrue(h.events.query(null).events().stream().anyMatch(e -> "mission_changed".equals(e.payload().get("reason"))));
			h.golden("task_wakeup_mission_changed");
		}
	}
	@Test void delegation_start_and_return() throws Exception {
		try (var h = new Harness()) {
			var thinker = DialogueWakeFixture.create(h.backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY,
				h.goal, h.clock, PlannerToolRegistry.of(new PlannerQueueToolProvider(PlannerActionToolExecutor.DISABLED)));
			var field = DialogueRuntime.class.getDeclaredField("plannerOrchestrator"); field.setAccessible(true);
			var delegation = new ai.moeru.airicraft.agent.llm.delegation.PlannerDelegation();
			h.dialogue.configureDelegation((PlannerOrchestrator) field.get(thinker), delegation);
			h.dialogue.configureDecisionContext(() -> new PlannerDecisionContext("world", h.tick, h.tick*2, h.dialogue.decisionOwner(), "idle", Map.of(), h.events.query(null)));
			var result = delegation.delegate("Inspect cave", "Describe hazards", "Stay safe");
			h.poll(); assertEquals("thinking", h.dialogue.decisionOwner());
			assertEquals(1, h.backend.requests().size());
			delegation.requestReturn(delegation.id(), "success", "Safe entrance", true); h.advance(1);
			assertEquals("controller", h.dialogue.decisionOwner()); assertTrue(result.isDone());
			h.golden("delegation_start_and_return");
		}
	}

}
