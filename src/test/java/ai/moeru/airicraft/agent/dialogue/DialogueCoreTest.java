package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.PlannerChatMessage;
import ai.moeru.airicraft.agent.llm.PlannerIntent;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueCoreTest {
	@Test
	void plannerSuccessUsesStructuredChatMessagesAsVisibleResponses() {
		DialogueTransition transition = DialogueCore.onPlannerSuccess(
			DialogueState.initial(),
			new PlannerResponse(
				List.of(
					new PlannerChatMessage("I found the cave.", 0),
					new PlannerChatMessage("I will head back now.", 30)
				),
				new PlannerIntent("reply_only", null, null),
				null
			),
			99L
		);

		assertEquals(2, transition.visibleResponses().size());
		assertEquals("I found the cave.", transition.visibleResponses().get(0).text());
		assertEquals(0, transition.visibleResponses().get(0).delayTicks());
		assertEquals("I will head back now.", transition.visibleResponses().get(1).text());
		assertEquals(30, transition.visibleResponses().get(1).delayTicks());
		assertEquals("planner_success", transition.state().pendingReplyReason());
	}

	@Test
	void plannerSuccessResetsFailureTrackingWithoutEffects() {
		DialogueState state = DialogueState.initial()
			.withConsecutiveFailureCount(2)
			.withLastFailureType(LlmFailureType.TIMEOUT)
			.withLastFailureTick(42L);

		DialogueTransition transition = DialogueCore.onPlannerSuccess(
			state,
			new PlannerResponse("Following.", new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "Alice")),
			99L
		);

		assertEquals(0, transition.state().consecutiveFailureCount());
		assertFalse(transition.state().degraded());
		assertTrue(transition.effects().isEmpty());
		assertEquals("Following.", transition.lastVisibleResponse().text());
	}

	@Test
	void thirdFailureEntersDegradedAndEmitsSemanticEffects() {
		DialogueState state = DialogueState.initial()
			.withConsecutiveFailureCount(2);

		DialogueTransition transition = DialogueCore.onPlannerFailure(
			state,
			LlmFailureType.TIMEOUT,
			"planner timed out",
			false,
			77L,
			DialogueCore.ResetGuidance.MANUAL
		);

		assertTrue(transition.state().degraded());
		assertEquals(3, transition.state().consecutiveFailureCount());
		assertEquals(2, transition.effects().size());
		assertTrue(transition.lastVisibleResponse().text().contains("@agent reset"));
	}

	@Test
	void directChatTimeoutUsesFreshVisibleReply() {
		DialogueTransition transition = DialogueCore.onPlannerFailure(
			DialogueState.initial(),
			LlmFailureType.TIMEOUT,
			"planner timed out",
			true,
			91L,
			DialogueCore.ResetGuidance.MANUAL
		);

		assertEquals("I hit a timeout just now. Please try again.", transition.lastVisibleResponse().text());
		assertEquals("timeout_visible_reply", transition.state().pendingReplyReason());
	}

	@Test
	void directChatProviderUnavailableUsesFreshVisibleReply() {
		DialogueTransition transition = DialogueCore.onPlannerFailure(
			DialogueState.initial(),
			LlmFailureType.PROVIDER_UNAVAILABLE,
			"LLM request failed: ConnectException",
			true,
			91L,
			DialogueCore.ResetGuidance.MANUAL
		);

		assertEquals("I can't reach the LLM provider right now. Please try again.", transition.lastVisibleResponse().text());
		assertEquals("provider_unavailable_visible_reply", transition.state().pendingReplyReason());
	}

	@Test
	void resetClearsDegradedStateAndEmitsRecoveryEffects() {
		DialogueState state = DialogueState.initial()
			.withDegraded(true)
			.withConsecutiveFailureCount(3)
			.withLastFailureType(LlmFailureType.TIMEOUT)
			.withLastFailureTick(12L);

		DialogueTransition transition = DialogueCore.onReset(state, "Alice", 88L);

		assertFalse(transition.state().degraded());
		assertEquals(0, transition.state().consecutiveFailureCount());
		assertEquals("Planner state reset.", transition.lastVisibleResponse().text());
		assertEquals(2, transition.effects().size());
	}
}
