package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.PlannerChatMessage;
import ai.moeru.airicraft.agent.llm.PlannerIntent;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
	void degradedModeEndsAfterACooldownThatGrowsUntilATurnSucceeds() {
		long cooldown = DialogueCore.DEGRADED_COOLDOWN_MILLIS;
		DialogueState state = failAt(DialogueState.initial().withConsecutiveFailureCount(2), 100_000L);
		assertTrue(state.degraded());
		assertEquals(100_000L + cooldown, state.degradedRecoverAtMillis());
		assertEquals(5_000L, DialogueCore.outageMillis(state, 105_000L));

		DialogueTransition early = DialogueCore.onDegradedCooldownElapsed(state, 100_000L + cooldown - 1);
		assertTrue(early.state().degraded());
		assertTrue(early.effects().isEmpty());

		DialogueTransition recovered = DialogueCore.onDegradedCooldownElapsed(state, 100_000L + cooldown);
		assertFalse(recovered.state().degraded());
		assertTrue(recovered.visibleResponses().isEmpty());
		assertEquals("planner.degraded_cleared", ((DialogueEffect.AppendSemanticEvent) recovered.effects().get(0)).type());

		// One more failure after recovery re-enters degraded mode with twice the cooldown; the outage keeps its start.
		state = failAt(recovered.state(), 200_000L);
		assertTrue(state.degraded());
		assertEquals(200_000L + 2 * cooldown, state.degradedRecoverAtMillis());
		assertEquals(100_000L, DialogueCore.outageMillis(state, 200_000L));
		for (int entry = 3; entry < 12; entry++) {
			long recoverAt = state.degradedRecoverAtMillis();
			state = failAt(DialogueCore.onDegradedCooldownElapsed(state, recoverAt).state(), recoverAt);
		}
		long recoverAt = state.degradedRecoverAtMillis();
		assertEquals(DialogueCore.MAX_DEGRADED_COOLDOWN_MILLIS, state.degradedRecoverAtMillis() - state.lastFailureTick());

		DialogueState healthy = DialogueCore.onPlannerSuccess(DialogueCore.onDegradedCooldownElapsed(state, recoverAt).state(),
			new PlannerResponse("Back.", new PlannerIntent("reply_only", null, null)), 20_000L).state();
		assertEquals(0, healthy.consecutiveFailureCount());
		assertEquals(0, healthy.degradedEntries());
		assertEquals(0L, DialogueCore.outageMillis(healthy, recoverAt + 1));
		healthy = failAt(failAt(failAt(healthy, 9_000_000L), 9_000_001L), 9_000_002L);
		assertEquals(9_000_002L + cooldown, healthy.degradedRecoverAtMillis());
	}

	// The tick and the wall clock coincide in these tests, so lastFailureTick reads back when the period began.
	private static DialogueState failAt(DialogueState state, long nowMillis) {
		return DialogueCore.onPlannerFailure(state, LlmFailureType.PROVIDER_ERROR, "Provider returned HTTP 502", false, nowMillis,
			nowMillis, DialogueCore.ResetGuidance.MANUAL).state();
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
			0L,
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
			0L,
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
			0L,
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

	@Test
	void characterMessagesVoiceEveryLineSentWithoutAPlannerReply() {
		DialogueMessages voiced = DialogueMessages.DEFAULTS.withOverrides(Map.of(
			"parseError", "wait, lost my train of thought",
			"timeout", "zoned out, say again?",
			"degraded", "brain lag, send @agent reset",
			"hostedAutoReset", "hold on, resetting myself once",
			"reset", "ok, fresh start"));

		assertEquals("wait, lost my train of thought", DialogueCore.onPlannerFailure(DialogueState.initial(),
			LlmFailureType.PARSE_ERROR, "bad json", false, 1L, 0L, DialogueCore.ResetGuidance.MANUAL, voiced).lastVisibleResponse().text());
		assertEquals("zoned out, say again?", DialogueCore.onPlannerFailure(DialogueState.initial(),
			LlmFailureType.TIMEOUT, "slow", true, 1L, 0L, DialogueCore.ResetGuidance.MANUAL, voiced).lastVisibleResponse().text());
		assertEquals(DialogueMessages.DEFAULTS.providerUnavailable(), DialogueCore.onPlannerFailure(DialogueState.initial(),
			LlmFailureType.PROVIDER_UNAVAILABLE, "down", true, 1L, 0L, DialogueCore.ResetGuidance.MANUAL, voiced).lastVisibleResponse().text(),
			"Lines the card leaves out keep their default text");

		DialogueState almostDegraded = DialogueState.initial().withConsecutiveFailureCount(DialogueCore.DEGRADED_FAILURE_THRESHOLD - 1);
		assertEquals("brain lag, send @agent reset", DialogueCore.onPlannerFailure(almostDegraded,
			LlmFailureType.PROVIDER_ERROR, "500", false, 2L, 0L, DialogueCore.ResetGuidance.MANUAL, voiced).lastVisibleResponse().text());
		assertEquals("hold on, resetting myself once", DialogueCore.onPlannerDegradedBlocked(DialogueState.initial().withDegraded(true),
			"Alex", true, 3L, DialogueCore.ResetGuidance.HOSTED_AUTO_PENDING, voiced).lastVisibleResponse().text());
		assertEquals("ok, fresh start", DialogueCore.onReset(DialogueState.initial(), "Alex", 4L, voiced).lastVisibleResponse().text());
	}

	@Test
	void unknownMessageOverridesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> DialogueMessages.DEFAULTS.withOverrides(Map.of("greeting", "hi")));
	}
}
