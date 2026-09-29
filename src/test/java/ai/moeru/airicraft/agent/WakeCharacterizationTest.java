package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.attention.Delivery;

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
			// Intentionally cross the existing 30-minute time-beacon threshold.
			h.advanceWallClock(Duration.ofMinutes(31));
			h.reflex(new ai.moeru.airicraft.agent.reflex.SurvivalReflexEvent("reflex.resolved", Map.of("holdId", "hold-1", "reason", "safe")));
			h.tick(10);
			assertEquals(2, h.backend.requests().size());
			assertEquals(List.of("submitted:W2", "submitted:W1", "dropped:W2:G5.incorporated"), auditOutcomes(h));
			h.transcript("reflex_started_then_resolved_with_hold").assertMatchesGolden("reflex_started_then_resolved_with_hold");
		}
	}
	/** Phase 5: a reflex start opens a new safety epoch, and its wake preempts the turn that epoch made stale. */
	@Test void safety_epoch_preempts_turn() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			var held = h.backend.holdNext();
			h.chat("Alex", "@agent gather wood");
			h.backend.awaitRequests(1, Duration.ofSeconds(1));
			h.reflexStarted(1);
			h.tick(1);
			assertTrue(held.isCancelled(), "the stale call is cancelled, not paid for to the end");
			assertEquals(2, h.backend.requests().size(), "the reflex wake is delivered in the same tick");
			assertEquals(h.backend.requests().get(0).observedAtTick() + 1, h.backend.requests().get(1).observedAtTick());
			assertEquals(List.of("submitted:W1", "preempted:W2:preempt.safety_epoch", "submitted:W2"), auditOutcomes(h));
			assertTrue(h.runtime.recentEvents(null).events().stream().anyMatch(event -> event.type().equals("planner.turn_preempted")));
			assertTrue(h.runtime.recentEvents(null).events().stream().noneMatch(event -> event.type().equals("planner.stale_response_rejected")));
			h.tick(10);
			assertEquals(2, h.backend.requests().size());
			h.transcript("safety_epoch_preempts_turn").assertMatchesGolden("safety_epoch_preempts_turn");
		}
	}
	/** Phase 5: direct guidance supersedes at most three times per 600 ticks; the fourth supersede waits for the turn. */
	@Test void chat_spam_supersede_budget() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			var holds = new java.util.ArrayList<java.util.concurrent.CompletableFuture<ai.moeru.airicraft.agent.llm.PlannerResponse>>();
			holds.add(h.backend.holdNext());
			h.chat("Alex", "@agent line 0");
			h.backend.awaitRequests(1, Duration.ofSeconds(1));
			for (int line = 1; line <= 4; line++) {
				h.tick(40 - 1);
				holds.add(h.backend.holdNext());
				h.chat("Alex", "@agent line " + line);
				h.tick(1);
				if (line < 4) h.backend.awaitRequests(line + 1, Duration.ofSeconds(1));
			}
			assertEquals(4, h.backend.requests().size(), "three supersedes, then the running turn is left alone");
			for (int line = 0; line < 3; line++) assertTrue(holds.get(line).isCancelled(), "superseded turn " + line);
			assertFalse(holds.get(3).isDone(), "the third supersede's turn keeps running");
			assertTrue(auditOutcomes(h).contains("queued:W1:supersede.budget"), auditOutcomes(h).toString());
			h.transcript("chat_spam_supersede_budget").assertMatchesGolden("chat_spam_supersede_budget");
			// The queued line starts the next turn once the running one ends (its tick depends on provider threads).
			holds.get(3).complete(new ai.moeru.airicraft.agent.llm.PlannerResponse("Working on it.", List.of(), null));
			long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
			while (h.backend.requests().size() < 5 && System.nanoTime() < deadline) h.tick(1);
			assertEquals(5, h.backend.requests().size(), "the queued line is answered next");
			assertTrue(h.backend.requests().getLast().request().request().triggerBatch().triggers().stream()
				.anyMatch(trigger -> "@agent line 4".equals(trigger.text())));
		}
	}
	/** W9: the tool queue asks for its own review at a report_to_me checkpoint and when its FIFO runs empty. */
	@Test void tool_queue_review() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.backend.injectMockResponse(ai.moeru.airicraft.agent.llm.PlannerResponse.toolCalls(List.of(
				new ai.moeru.airicraft.agent.llm.PlannerToolCall("look", ai.moeru.airicraft.agent.llm.PlannerToolCatalog.INSPECT_INVENTORY, new com.google.gson.JsonObject(), null),
				new ai.moeru.airicraft.agent.llm.PlannerToolCall("report", ai.moeru.airicraft.agent.llm.PlannerQueueToolProvider.REPORT, new com.google.gson.JsonObject(), null)), null));
			h.chat("Alex", "@agent check your inventory and tell me");
			h.tick(20);
			var outcomes = auditOutcomes(h);
			assertTrue(outcomes.stream().anyMatch(outcome -> outcome.startsWith("submitted:W9")), outcomes.toString());
			assertTrue(h.backend.requests().size() >= 2, "the review wakes the planner");
			h.transcript("tool_queue_review").assertMatchesGolden("tool_queue_review");
		}
	}
	/** Phase 5: a pickup storm spends the autonomous-wake budget; the muted pickups stay evidence. */
	@Test void autonomous_wake_budget() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			for (int pickup = 0; pickup < 20; pickup++) {
				h.runtime.onPlayerPickedUpItem("minecraft:oak_log", 1);
				h.tick(1);
			}
			h.tick(10);
			var pickups = h.runtime.attentionDecisionLog().query(null).decisions().stream()
				.filter(decision -> decision.type().equals("pickup.item_picked_up")).toList();
			assertEquals(20, pickups.size());
			assertEquals(10, pickups.stream().filter(decision -> decision.delivery() != Delivery.NONE).count(), "a burst of 10");
			assertTrue(pickups.subList(10, 20).stream().allMatch(decision -> "budget.autonomous_wakes".equals(decision.ruleId())));
			assertEquals(20, h.runtime.recentEvents(null).events().stream().filter(event -> event.type().equals("pickup.item_picked_up")).count(),
				"every pickup stays in the log for observe");
			h.transcript("autonomous_wake_budget").assertMatchesGolden("autonomous_wake_budget");
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
			h.settle(); h.tick(1);
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
	/** The {@code observe.wake} of the request's decision-context observation, as JSON text. */
	private static String wake(ai.moeru.airicraft.agent.wakes.RecordingPlannerBackend.Request request) {
		var messages = request.request().conversation().messages();
		for (int index = messages.size() - 1; index >= 0; index--) {
			String content = messages.get(index).content();
			if (content != null && content.startsWith("{") && content.contains("\"worldSessionId\"")) {
				var observation = com.google.gson.JsonParser.parseString(content).getAsJsonObject();
				return observation.has("wake") ? observation.get("wake").toString() : "";
			}
		}
		return "";
	}

	private static String decisionRule(WakeScenarioHarness h, String type) {
		return h.runtime.attentionDecisionLog().latest(64).stream().filter(decision -> decision.type().equals(type))
			.reduce((first, second) -> second).map(decision -> decision.delivery() + ":" + decision.ruleId()).orElse("none");
	}

	@Test void percept_block_idle() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.candidate(WakeScenarioHarness.block("minecraft:diamond_ore", 3, 40, 7, 7.5));
			h.candidate(WakeScenarioHarness.block("minecraft:diamond_ore", 4, 40, 7, 6.2));
			h.tick(9);
			assertEquals("DEBOUNCE:percept.notice", decisionRule(h, "perception.block_noticed"));
			assertTrue(h.backend.requests().isEmpty(), "held until 10 quiet ticks");
			h.tick(3);
			assertEquals(1, h.backend.requests().size());
			assertTrue(wake(h.backend.requests().getFirst()).contains("perception.block_noticed"), wake(h.backend.requests().getFirst()));
			h.transcript("percept_block_idle").assertMatchesGolden("percept_block_idle");
		}
	}

	@Test void percept_owned_by_mining() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.runtime.execute(new ai.moeru.airicraft.agent.llm.PlannerToolCall("mine", "mine_blocks",
				com.google.gson.JsonParser.parseString("{\"blockIds\":[\"minecraft:diamond_ore\"],\"quantity\":3}").getAsJsonObject(), null)).join();
			h.tick(1);
			h.event("perception.block_noticed", Map.of("blockId", "minecraft:diamond_ore", "count", 1));
			h.tick(20);
			assertEquals("NONE:ownership.active_job_target", decisionRule(h, "perception.block_noticed"));
			assertTrue(h.backend.requests().isEmpty(), "the mining job owns a percept about its own target");
			h.event("perception.block_noticed", Map.of("blockId", "minecraft:emerald_ore", "count", 1));
			h.tick(20);
			assertEquals("DEBOUNCE:percept.notice", decisionRule(h, "perception.block_noticed"));
			assertEquals(1, h.backend.requests().size(), "another ore still wakes while the job runs");
			h.transcript("percept_owned_by_mining").assertMatchesGolden("percept_owned_by_mining");
		}
	}

	@Test void percept_batch() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.candidate(WakeScenarioHarness.block("minecraft:diamond_ore", 3, 40, 7, 7));
			h.tick(5);
			h.candidate(WakeScenarioHarness.block("minecraft:spawner", 30, 40, 7, 9));
			h.tick(5);
			h.candidate(WakeScenarioHarness.block("minecraft:chest", -20, 40, 7, 11));
			h.tick(20);
			assertEquals(1, h.backend.requests().size(), "three percepts within the quiet window make one wake");
			String wake = wake(h.backend.requests().getFirst());
			assertTrue(wake.contains("minecraft") || wake.contains("perception.block_noticed"), wake);
			assertEquals(3, com.google.gson.JsonParser.parseString(wake).getAsJsonArray().size(), wake);
			h.transcript("percept_batch").assertMatchesGolden("percept_batch");
		}
	}

	@Test void percept_offer_dedup() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.event("social.item_offered", Map.of("player", "Alex", "playerUuid", "alex", "itemEntityUuid", "item-1",
				"itemId", "minecraft:bread", "count", 3, "position", Map.of("x", 1, "y", 64, "z", 1)));
			h.candidate(new ai.moeru.airicraft.agent.perception.PerceptCandidate("item:item-1", "item", Map.of("itemId", "minecraft:bread",
				"count", 3, "x", 1, "y", 64, "z", 1, "distance", 2, "attribution", "thrown_by_player", "offered", true, "itemEntityUuid", "item-1")));
			h.tick(20);
			assertEquals(1, h.backend.requests().size());
			String wake = wake(h.backend.requests().getFirst());
			assertTrue(wake.contains("social.item_offered") && !wake.contains("perception.item_noticed"), wake);
			assertTrue(h.runtime.recentEvents(null).events().stream().noneMatch(event -> event.type().equals("perception.item_noticed")),
				"one physical drop is one offer, not also a noticed item");
			h.transcript("percept_offer_dedup").assertMatchesGolden("percept_offer_dedup");
		}
	}

	@Test void percept_protected_not_debounced() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1);
			h.candidate(WakeScenarioHarness.block("minecraft:diamond_ore", 3, 40, 7, 7));
			h.tick(3);
			assertTrue(h.backend.requests().isEmpty());
			h.chat("Alex", "@agent what is over there?");
			h.backend.awaitRequests(1, Duration.ofSeconds(1));
			assertEquals(1, h.backend.requests().size(), "direct chat is never delayed");
			assertTrue(wake(h.backend.requests().getFirst()).contains("perception.block_noticed"),
				"the held percept rides along in the same batch");
			h.tick(20);
			assertEquals(1, h.backend.requests().size(), "the percept is not delivered twice");
			h.transcript("percept_protected_not_debounced").assertMatchesGolden("percept_protected_not_debounced");
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
	@Test void idle_think_after_delay() {
		// Idle time is counted in agent ticks (D7): 31 seconds of ticks passes the 30-second initial delay.
		try (var h = idleHarness()) {
			h.tick(1);
			h.tick(31 * 20);
			assertEquals(1, h.backend.requests().size());
			assertTrue(h.transcript("idle_think_after_delay").json().contains("W5"));
			h.transcript("idle_think_after_delay").assertMatchesGolden("idle_think_after_delay");
		}
	}
	@Test void tick_debug_pause_then_resume() {
		// D7 fixed: five minutes of paused wall time with no ticks is not idle time, so resuming does not wake.
		// A normal 30 seconds of idle ticks afterwards still does.
		try (var h = idleHarness()) {
			h.tick(1); h.advanceWallClock(Duration.ofMinutes(5)); h.tick(1);
			assertEquals(0, h.backend.requests().size());
			h.tick(30 * 20);
			assertEquals(1, h.backend.requests().size());
			assertTrue(h.transcript("tick_debug_pause_then_resume").json().contains("W5"));
			h.transcript("tick_debug_pause_then_resume").assertMatchesGolden("tick_debug_pause_then_resume");
		}
	}
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
			h.settle(); h.tick(1);
			assertEquals(1, h.backend.requests().size(), "new action goal should invalidate the queued idle request");
			h.transcript("idle_think_invalidated_by_action_goal").assertMatchesGolden("idle_think_invalidated_by_action_goal");
		}
	}
	private static WakeScenarioHarness idleHarness() {
		var h = new WakeScenarioHarness();
		h.runtime.overrideSessionSnapshotForTests(new ai.moeru.airicraft.agent.session.SessionSnapshot(
			ai.moeru.airicraft.agent.session.SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, 0));
		return h;
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
			var transcript = h.transcript("death_then_respawn");
			// The chat supersedes the damage request; its observation (events 5-7) stays in history, so the chat
			// request must not cite damage event 5 again as a new wake.
			var last = transcript.data().getAsJsonArray("requests").get(2).getAsJsonObject().getAsJsonObject("observe");
			assertEquals(7, last.get("afterEventSequence").getAsLong());
			assertFalse(last.has("wake"), last.toString());
			transcript.assertMatchesGolden("death_then_respawn");
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
