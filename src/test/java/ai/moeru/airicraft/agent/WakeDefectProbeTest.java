package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.work.*;
import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pins defects without correcting production behavior. D1/D7/D8 also have dialogue/unit probes. */
class WakeDefectProbeTest {
	@Test void d2_CONFIRMED_pickupAppearsInBothNoticesAndObservedEvents() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1); h.runtime.onPlayerPickedUpItem("minecraft:diamond", 1); h.tick(5); h.chat("Alex", "@agent hello"); h.tick(5);
			var observe = h.transcript("d2").data().getAsJsonArray("requests").get(0).getAsJsonObject().getAsJsonObject("observe");
			assertTrue(observe.get("events").toString().contains("pickup.item_picked_up"));
			assertTrue(observe.get("notices").toString().contains("minecraft:diamond"));
		}
	}
	@Test void d3_CONFIRMED_ignoreSuppressesWakeButKeepsRawEvidence() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			String result = h.runtime.execute(new PlannerToolCall("ignore", "update_event_policy", JsonParser.parseString("""
				{"upserts":[{"ruleId":"quiet-pickups","match":{"eventType":"pickup.item_picked_up"},"effect":"ignore"}]}
				""").getAsJsonObject(), null)).join();
			assertTrue(result.contains("applied"), result);
			h.runtime.onPlayerPickedUpItem("minecraft:diamond", 1); h.tick(5);
			assertTrue(h.backend.requests().isEmpty());
			h.chat("Alex", "@agent hello"); h.tick(5);
			var observe = h.transcript("d3").data().getAsJsonArray("requests").get(0).getAsJsonObject().getAsJsonObject("observe");
			assertTrue(observe.get("events").toString().contains("pickup.item_picked_up"));
		}
	}
	@Test void d4_CONFIRMED_reflexHasTwoWakePathsButIncorporationDropsSecond() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.reflex(new ai.moeru.airicraft.agent.reflex.SurvivalReflexEvent("reflex.resolved", Map.of("holdId", "hold-1")));
			h.tick(5);
			var audit = h.transcript("d4").data().getAsJsonArray("wakeAudit");
			assertEquals(2, audit.size());
			assertEquals("W1", audit.get(0).getAsJsonObject().get("path").getAsString());
			assertEquals("G5.incorporated", audit.get(1).getAsJsonObject().get("gate").getAsString());
			assertEquals(1, h.backend.requests().size());
		}
	}
	/** REFUTED for direct navigation: both W2 references identify the causal task/work event.
	 * The generic Work changed text is still used because only task.notice has message prose. */
	@Test void d5_REFUTED_navigationWakeReferencesMatchRetainedOutcomes() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.runtime.executePlannerAction(new PlannerToolCall("nav", "navigate_to",
				JsonParser.parseString("{\"x\":12,\"y\":64,\"z\":8,\"exactY\":true}").getAsJsonObject(), null)).join();
			h.tick(1); var request = h.executor.lastActiveTask.orElseThrow();
			h.executor.nextTerminalEvent = java.util.Optional.of(new ai.moeru.airicraft.agent.tasks.TaskTerminalEvent(request.taskId(), request.goal(),
				ai.moeru.airicraft.agent.tasks.TaskExecutionState.FAILED, "CALC_FAILED", ai.moeru.airicraft.agent.tasks.TaskTerminationCause.CALCULATION_FAILED));
			h.tick(10);
			var events = h.runtime.recentEvents(null).events();
			var audit = h.runtime.debugTimeline(null).entries().stream().filter(e -> e.domain().equals("planner_wake") && e.action().equals("submitted")).toList();
			assertEquals(2, audit.size()); assertEquals(2, h.backend.requests().size());
			assertEquals(java.util.List.of("task.failed", "work.changed"), audit.stream().map(a -> events.stream()
				.filter(e -> e.seqNo() == ((Number)a.payload().get("eventSequence")).longValue()).findFirst().orElseThrow().type()).toList());
			assertTrue(h.backend.requests().stream().allMatch(r -> r.request().request().message().equals("Work changed.")));
		}
	}

	/** Synthetic single-tick flood reproduces the ring-state dependency, not its frequency in gameplay. */
	@Test void d6_CONFIRMED_evictionLosesEatingCompletion() throws Exception {
		assertEquals(WorkSnapshot.State.SUCCEEDED, eatingAfterFlood(0));
		assertEquals(WorkSnapshot.State.RUNNING, eatingAfterFlood(512));
	}
	private WorkSnapshot.State eatingAfterFlood(int count) throws Exception {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			var field = EmbodiedAgentRuntime.class.getDeclaredField("workHistory"); field.setAccessible(true);
			var history = (WorkHistory) field.get(h.runtime);
			var handle = new WorkHandle("OPERATION:eat");
			history.observe(new WorkSnapshot(handle, "", WorkSnapshot.State.RUNNING, "eat", "EATING", false, 1, Map.of("afterEventSequence", 0L)));
			h.event("food.eaten", Map.of("itemId", "minecraft:bread"));
			for(int i=0; i<count; i++) h.event("interaction.example", Map.of());
			h.tick(1);
			return history.find(handle).orElseThrow().state();
		}
	}
}
