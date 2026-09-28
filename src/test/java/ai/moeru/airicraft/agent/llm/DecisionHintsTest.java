package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecisionHintsTest {
	private static PlannerTrigger wake(long seqNo, String type) {
		return PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "runtime", "text", 1, 50, type).withWake(WakeRef.event(seqNo, type, "HIGH"));
	}

	@Test void hintsAreKeyedByTheWakingEventAndLabelledAsRuntimeAdvice() {
		var events = List.of(
			new SemanticEvent(1, 1, 50, "action_graph.goal_terminal", Map.of("state", "FAILED", "failureCode", "unsupported_resource_kind")),
			new SemanticEvent(2, 1, 50, "action_graph.goal_terminal", Map.of("state", "FAILED", "failureCode", "no_path")),
			new SemanticEvent(3, 1, 50, "social.item_offered", Map.of("player", "Alex")),
			new SemanticEvent(4, 1, 50, "reflex.resolved", Map.of("reason", "combat_stalemate", "combatSummary", Map.of("text", "won"))),
			new SemanticEvent(5, 1, 50, "pickup.item_picked_up", Map.of()));
		var hints = DecisionHints.render(List.of(wake(1, "action_graph.goal_terminal"), wake(2, "action_graph.goal_terminal"),
			wake(3, "social.item_offered"), wake(4, "reflex.resolved"), wake(5, "pickup.item_picked_up")), events);
		assertEquals(List.of(1L, 3L, 4L), hints.stream().map(hint -> hint.get("seqNo")).toList(), "no hint where none applies");
		assertTrue(hints.stream().allMatch(hint -> DecisionHints.PROVENANCE.equals(hint.get("provenance"))));
		assertTrue(String.valueOf(hints.get(0).get("hint")).contains("unsupported"));
		assertTrue(String.valueOf(hints.get(1).get("hint")).contains("not a confirmed pickup"));
		assertTrue(String.valueOf(hints.get(2).get("hint")).contains("600 ticks"));
		assertTrue(String.valueOf(hints.get(2).get("hint")).contains("recorded combat outcomes"));
	}

	@Test void idleThinkHintCarriesTheCharactersInterestsAndIdeas() {
		var scheduler = new ai.moeru.airicraft.agent.idle.IdleIdeaScheduler(
			new ai.moeru.airicraft.agent.idle.IdleIdeasConfig(true, 1, 1, List.of("build a shelter")), List.of("collecting flowers"));
		var trigger = scheduler.fireNow(1, 50).orElseThrow();
		var hints = DecisionHints.render(List.of(trigger), List.of());
		assertEquals(1, hints.size());
		assertEquals("idle_think", hints.getFirst().get("reason"));
		assertTrue(String.valueOf(hints.getFirst().get("hint")).contains("collecting flowers"));
		assertTrue(String.valueOf(hints.getFirst().get("hint")).contains("build a shelter"));
	}

	@Test void onlyTaskStatementsKeepTheirProseInFrontOfTheObservation() {
		var event = wake(1, "smelting.output_ready");
		var continuation = PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "self", "GOAL CONTINUATION", 1, 50, "planner_goal");
		var idle = PlannerTrigger.pending(PlannerTriggerType.IDLE_THINK, "self", "IDLE THINK", 1, 50);
		var delegation = PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "controller", "DELEGATION", 1, 50, "delegation");
		var evaluation = PlannerTrigger.pending(PlannerTriggerType.SYSTEM, "evaluation", "EVALUATION", 1, 50);
		var review = PlannerTrigger.pending(PlannerTriggerType.SYSTEM, "tool_queue", "FIFO empty.", 1, 50);
		var chat = PlannerTrigger.direct(PlannerTriggerType.CHAT, "Alex", "hi", 1, 50);
		var messages = PlannerTriggerBatch.of(List.of(event, continuation, idle, delegation, evaluation, review, chat)).toObservedMessages();
		assertEquals(List.of("Alex: hi", "DELEGATION", "EVALUATION", "FIFO empty."), messages.stream().map(LlmChatMessage::content).toList());
	}
}
