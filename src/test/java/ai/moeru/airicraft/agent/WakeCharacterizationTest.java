package ai.moeru.airicraft.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WakeCharacterizationTest {
	private static List<String> auditOutcomes(WakeScenarioHarness h) {
		var outcomes = new ArrayList<String>();
		for (var value : h.transcript("audit").data().getAsJsonArray("wakeAudit")) {
			var entry = value.getAsJsonObject();
			outcomes.add(entry.get("kind").getAsString() + ":" + entry.get("path").getAsString()
				+ (entry.has("gate") ? ":" + entry.get("gate").getAsString() : ""));
		}
		return outcomes;
	}
	@Test void identicalScenariosHaveIdenticalTranscripts() {
		String first;
		try (var h = new WakeScenarioHarness()) { h.tick(1); h.chat("Alex", "@agent hello"); h.tick(5); first = h.transcript("same").json(); }
		try (var h = new WakeScenarioHarness()) { h.tick(1); h.chat("Alex", "@agent hello"); h.tick(5); assertEquals(first, h.transcript("same").json()); }
	}
	@Test void addressed_chat_idle() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1); h.chat("Alex", "@agent hello"); h.tick(5);
			assertEquals(1, h.backend.requests().size());
			assertTrue(h.transcript("addressed_chat_idle").json().contains("DIRECT_GUIDANCE"));
			h.transcript("addressed_chat_idle").assertMatchesGolden("addressed_chat_idle");
		}
	}
	@Test void pickup_and_craft_idle() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.runtime.onPlayerPickedUpItem("minecraft:oak_log", 2);
			h.tick(10);
			h.runtime.onPlayerCraftedItem("minecraft:oak_planks", 4);
			h.tick(10);
			assertEquals(2, h.backend.requests().size());
			assertEquals(List.of("submitted:W1", "submitted:W1"), auditOutcomes(h));
			assertEquals(ai.moeru.airicraft.agent.llm.PlannerTriggerType.PICKUP,
				h.backend.requests().get(0).request().request().triggerBatch().triggers().getFirst().type());
			assertEquals(ai.moeru.airicraft.agent.llm.PlannerTriggerType.CRAFT,
				h.backend.requests().get(1).request().request().triggerBatch().triggers().getFirst().type());
			assertEquals("Recent context updates require one combined response.", h.backend.requests().get(0).request().request().message());
			h.transcript("pickup_and_craft_idle").assertMatchesGolden("pickup_and_craft_idle");
		}
	}
	@Test void item_offer_and_physical_episode() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.event("social.item_offered", Map.of("player", "Alex", "playerUuid", "alex", "itemId", "minecraft:diamond", "count", 1, "position", Map.of("x", 1, "y", 64, "z", 1)));
			h.event("player.physical", Map.of("kind", "pushed", "message", "Pushed by Alex"));
			h.tick(10);
			h.transcript("item_offer_and_physical_episode").assertMatchesGolden("item_offer_and_physical_episode");
		}
	}
	@Test void smelting_output_and_graph_failure() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.event("smelting.output_ready", Map.of("processId", "furnace-1", "outputItemId", "minecraft:iron_ingot", "outputCount", 1));
			h.tick(10);
			h.event("action_graph.goal_terminal", Map.of("executionId", "graph-1", "state", "FAILED", "message", "no path"));
			h.tick(10);
			assertEquals(2, h.backend.requests().size());
			h.transcript("smelting_output_and_graph_failure").assertMatchesGolden("smelting_output_and_graph_failure");
		}
	}
	@Test void reflex_started_then_resolved_with_hold() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.reflex(new ai.moeru.airicraft.agent.reflex.SurvivalReflexEvent("reflex.started", Map.of("reason", "threat")));
			h.tick(10);
			h.reflex(new ai.moeru.airicraft.agent.reflex.SurvivalReflexEvent("reflex.resolved", Map.of("holdId", "hold-1", "reason", "safe")));
			h.tick(10);
			assertEquals(2, h.backend.requests().size());
			assertEquals(List.of("submitted:W2", "submitted:W1", "dropped:W2:G5.incorporated"), auditOutcomes(h));
			h.transcript("reflex_started_then_resolved_with_hold").assertMatchesGolden("reflex_started_then_resolved_with_hold");
		}
	}
	@Test void damage_outside_reflex() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1); h.runtime.onPlayerHealthUpdated(true, 20, 16); h.tick(10);
			assertEquals(1, h.backend.requests().size());
			assertEquals(List.of("submitted:W1"), auditOutcomes(h));
			assertEquals(ai.moeru.airicraft.agent.llm.PlannerTriggerType.DAMAGE,
				h.backend.requests().getFirst().request().request().triggerBatch().triggers().getFirst().type());
			assertEquals("Recent context updates require one combined response.", h.backend.requests().getFirst().request().request().message());
			h.transcript("damage_outside_reflex").assertMatchesGolden("damage_outside_reflex");
		}
	}
	@Test void external_driver() {
		String prior = System.getProperty("airicraft.codexDriver");
		System.setProperty("airicraft.codexDriver", "true");
		try (var h = new WakeScenarioHarness()) {
			h.tick(1); h.chat("Alex", "@agent hello"); h.runtime.onPlayerPickedUpItem("minecraft:oak_log", 1); h.tick(10);
			assertTrue(h.backend.requests().isEmpty());
			h.transcript("external_driver").assertMatchesGolden("external_driver");
		} finally {
			if (prior == null) System.clearProperty("airicraft.codexDriver"); else System.setProperty("airicraft.codexDriver", prior);
		}
	}
	@Test void addressed_chat_while_turn_in_flight() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			var held = h.backend.holdNext();
			h.chat("Alex", "@agent gather wood");
			h.backend.awaitRequests(1, Duration.ofSeconds(1));
			h.chat("Alex", "@agent stop and listen");
			assertEquals(1, h.runtime.dialogueRuntimeForTests().plannerDebugSnapshot().supersededCount(),
				"the held first generation must be superseded by direct guidance");
			h.tick(20);
			held.complete(ai.moeru.airicraft.agent.wakes.RecordingPlannerBackend.yieldResponse());
			h.settle();
			assertEquals(2, h.backend.requests().size());
			assertTrue(h.backend.requests().get(1).request().generation() > h.backend.requests().getFirst().request().generation());
			var secondDelta = h.transcript("addressed_chat_while_turn_in_flight").data().getAsJsonArray("requests")
				.get(1).getAsJsonObject().getAsJsonArray("newMessages");
			assertFalse(secondDelta.toString().contains("\"text\":\"Alex: @agent gather wood\""),
				"the second request delta must omit the old standalone user turn");
			var transcript = h.transcript("addressed_chat_while_turn_in_flight");
			// Supersession cancels the pending generation before a discarded-result record exists.
			transcript.data().getAsJsonArray("requests").get(0).getAsJsonObject().addProperty("superseded",
				h.runtime.dialogueRuntimeForTests().plannerDebugSnapshot().supersededCount() == 1);
			transcript.assertMatchesGolden("addressed_chat_while_turn_in_flight");
		}
	}
	@Test void ambient_chat_proactive_on() { ambient(true); }
	@Test void ambient_chat_proactive_off() { ambient(false); }
	private void ambient(boolean proactive) {
		String name = "ambient_chat_proactive_" + (proactive ? "on" : "off");
		try (var h = new WakeScenarioHarness(Map.of(), proactive)) {
			h.tick(1); h.chat("Alex", "nice weather"); h.tick(20);
			assertEquals(proactive ? 1 : 0, h.backend.requests().size());
			h.transcript(name).assertMatchesGolden(name);
		}
	}
	@Test void pickup_during_mining_job() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.runtime.execute(new ai.moeru.airicraft.agent.llm.PlannerToolCall("mine", "mine_blocks",
				com.google.gson.JsonParser.parseString("{\"blockIds\":[\"minecraft:cobblestone\"],\"quantity\":3}").getAsJsonObject(), null)).join();
			h.tick(1); h.runtime.onPlayerPickedUpItem("minecraft:cobblestone", 1); h.tick(10);
			assertEquals("default-mining-pickup-semantic-only", h.runtime.lastEventPolicyDecision().matchedRuleId());
			assertTrue(h.backend.requests().isEmpty());
			h.transcript("pickup_during_mining_job").assertMatchesGolden("pickup_during_mining_job");
		}
	}
	@Test void navigation_failure_cascade() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.runtime.executePlannerAction(new ai.moeru.airicraft.agent.llm.PlannerToolCall("nav", "navigate_to",
				com.google.gson.JsonParser.parseString("{\"x\":12,\"y\":64,\"z\":8,\"exactY\":true}").getAsJsonObject(), null)).join();
			h.tick(1);
			var request = h.executor.lastActiveTask.orElseThrow();
			h.executor.nextTerminalEvent = java.util.Optional.of(new ai.moeru.airicraft.agent.tasks.TaskTerminalEvent(request.taskId(), request.goal(),
				ai.moeru.airicraft.agent.tasks.TaskExecutionState.FAILED, "CALC_FAILED", ai.moeru.airicraft.agent.tasks.TaskTerminationCause.CALCULATION_FAILED));
			h.tick(10);
			assertEquals(2, h.backend.requests().size());
			assertEquals(List.of("submitted:W2", "submitted:W2"), auditOutcomes(h));
			h.transcript("navigation_failure_cascade").assertMatchesGolden("navigation_failure_cascade");
		}
	}
	@Test void idle_think_after_delay() { idlePause("idle_think_after_delay", Duration.ofSeconds(31)); }
	@Test void tick_debug_pause_then_resume() { idlePause("tick_debug_pause_then_resume", Duration.ofMinutes(5)); }
	@Test void idle_think_invalidated_by_action_goal() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			var held = h.backend.holdNext();
			h.chat("Alex", "@agent hello");
			h.backend.awaitRequests(1, Duration.ofSeconds(1));
			h.runtime.dialogueRuntimeForTests().onPlannerTrigger(
				ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(ai.moeru.airicraft.agent.llm.PlannerTriggerType.IDLE_THINK,
					"self", "idle", h.tick, h.clock.millis()),
				new ai.moeru.airicraft.agent.session.SessionSnapshot(ai.moeru.airicraft.agent.session.SessionMode.REMOTE_MULTIPLAYER,
					true, true, "minecraft:overworld", false, 0, 0), null, java.util.Optional.empty(), null, null,
					h.runtimeEvents());
			var started = h.runtime.startActionGoalDetailed(
				ai.moeru.airicraft.agent.actions.ActionGoal.inventoryItem("minecraft:bread", 1), "test");
			assertEquals(ai.moeru.airicraft.agent.actions.ActionGraphAdmission.STARTED, started.admission());
			held.complete(ai.moeru.airicraft.agent.wakes.RecordingPlannerBackend.yieldResponse());
			h.settle();
			assertEquals(1, h.backend.requests().size(), "new action goal should invalidate the queued idle request");
			h.transcript("idle_think_invalidated_by_action_goal").assertMatchesGolden("idle_think_invalidated_by_action_goal");
		}
	}
	private void idlePause(String name, Duration pause) {
		try (var h = new WakeScenarioHarness()) {
			h.runtime.overrideSessionSnapshotForTests(new ai.moeru.airicraft.agent.session.SessionSnapshot(
				ai.moeru.airicraft.agent.session.SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, 0));
			h.tick(1); h.advanceWallClock(pause); h.tick(1);
			assertEquals(1, h.backend.requests().size());
			assertTrue(h.transcript(name).json().contains("W5"));
			h.transcript(name).assertMatchesGolden(name);
		}
	}
	@Test void degraded_mode() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			for (int i = 0; i < 3; i++) { h.runtime.injectPlannerTimeout(); h.tick(1); }
			h.chat("Alex", "@agent hello"); h.tick(5);
			assertTrue(h.runtime.dialogueRuntimeForTests().isDegraded());
			assertTrue(h.backend.requests().isEmpty());
			h.transcript("degraded_mode").assertMatchesGolden("degraded_mode");
		}
	}
	@Test void evaluation_chat_then_suppression() {
		try (var h = new WakeScenarioHarness()) {
			// prepareForEvaluation's live client setup is outside this null-client fixture.
			h.tick(1); h.runtime.emitEvaluationChat("collect wood"); h.settle(); h.tick(5);
			int before = h.backend.requests().size();
			h.runtime.finishEvaluation(); h.runtime.onPlayerPickedUpItem("minecraft:oak_log", 1); h.tick(5);
			assertEquals(before, h.backend.requests().size());
			h.transcript("evaluation_chat_then_suppression").assertMatchesGolden("evaluation_chat_then_suppression");
		}
	}

	@Test void death_then_respawn() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1); h.chat("Alex", "@agent remember this conversation"); h.tick(5);
			int before = h.backend.requests().size();
			h.runtime.onPlayerHealthUpdated(true, 20, 0); h.tick(5);
			// Current behavior: fatal damage itself produces a wake before respawn.
			assertEquals(before + 1, h.backend.requests().size());
			h.tick(5); assertEquals(before + 1, h.backend.requests().size());
			h.runtime.onPlayerRespawned(); h.chat("Alex", "@agent hello again"); h.tick(5);
			assertTrue(h.backend.requests().getLast().request().conversation().messages().stream()
				.anyMatch(m -> m.content().contains("remember this conversation")));
			h.transcript("death_then_respawn").assertMatchesGolden("death_then_respawn");
		}
	}

	@Test void item_offer_and_physical_episode_during_reflex() throws Exception {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			var runtimeField = EmbodiedAgentRuntime.class.getDeclaredField("survivalReflexRuntime"); runtimeField.setAccessible(true);
			var reflex = (ai.moeru.airicraft.agent.reflex.SurvivalReflexRuntime) runtimeField.get(h.runtime);
			var state = reflex.getClass().getDeclaredField("snapshot"); state.setAccessible(true);
			state.set(reflex, new ai.moeru.airicraft.agent.reflex.SurvivalReflexSnapshot(
				ai.moeru.airicraft.agent.reflex.SurvivalReflexState.ACTIVE, ai.moeru.airicraft.agent.reflex.SurvivalReflexCause.DROWNING,
				ai.moeru.airicraft.agent.reflex.SurvivalReflexAction.SWIM_TO_AIR, 1, "hold-1", null, null, java.util.List.of(), 10f, 20f, 100, 300, 1, 1, 0, null));
			h.event("social.item_offered", Map.of("player", "Alex", "playerUuid", "alex", "itemId", "minecraft:diamond", "count", 1));
			h.event("player.physical", Map.of("kind", "pushed"));
			var drain = EmbodiedAgentRuntime.class.getDeclaredMethod("drainEventPipeline"); drain.setAccessible(true); drain.invoke(h.runtime);
			h.settle();
			assertEquals(1, h.backend.requests().size(), "Item offer remains a wake; physical episode is suppressed during reflex actuation");
			h.transcript("item_offer_and_physical_episode_during_reflex").assertMatchesGolden("item_offer_and_physical_episode_during_reflex");
		}
	}

}
