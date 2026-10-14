package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.PlannerChatMessage;
import ai.moeru.airicraft.agent.llm.PlannerResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DialogueCore {
	static final int DEGRADED_FAILURE_THRESHOLD = 3;
	/**
	 * First degraded cooldown (30 s); each further period without a successful turn doubles it, up to 10 min.
	 * Wall-clock time: a provider recovers on its own schedule, and the game may be paused while it does.
	 */
	static final long DEGRADED_COOLDOWN_MILLIS = 30_000L;
	static final long MAX_DEGRADED_COOLDOWN_MILLIS = 600_000L;
	public static final String RESET_COMMAND = "@agent reset";

	private DialogueCore() {
	}

	enum ResetGuidance {
		MANUAL, HOSTED_AUTO_PENDING, HOSTED_AUTO_EXHAUSTED;

		String message(DialogueMessages messages) {
			return switch (this) {
				case MANUAL -> messages.degraded();
				case HOSTED_AUTO_PENDING -> messages.hostedAutoReset();
				case HOSTED_AUTO_EXHAUSTED -> messages.hostedResetExhausted();
			};
		}
	}

	public static DialogueState initialState() {
		return DialogueState.initial();
	}

	public static boolean isResetCommand(String plainTextMessage) {
		if (plainTextMessage == null) {
			return false;
		}
		return plainTextMessage.stripLeading().equalsIgnoreCase(RESET_COMMAND);
	}

	public static DialogueState markReplyObserved(DialogueState state) {
		return state.withPendingReply(false, null);
	}

	public static DialogueTransition onPlannerSuccess(DialogueState state, PlannerResponse plannerResponse, long tick) {
		DialogueIntentType mappedIntentType = DialogueIntentType.fromWire(plannerResponse.intent().type()).orElse(null);
		ArrayList<DialogueEffect> effects = new ArrayList<>();
		if (mappedIntentType == null) {
			effects.add(DialogueEffect.appendSemanticEvent("planner.unknown_intent", Map.of(
				"type", plannerResponse.intent().type()
			)));
			mappedIntentType = DialogueIntentType.NONE;
		}

		DialogueIntent intent = new DialogueIntent(
				mappedIntentType,
				plannerResponse.intent().goalType(),
				plannerResponse.intent().targetPlayer(),
				plannerResponse.intent().position(),
				plannerResponse.intent().mineSpec(),
				plannerResponse.intent().taskSpec(),
				plannerResponse.intent().taskLedger(),
				plannerResponse.intent().activeJob()
			);
		List<DialogueResponse> visibleResponses = visibleResponses(plannerResponse, intent, tick);
		DialogueResponse response = visibleResponses.isEmpty()
			? new DialogueResponse("", intent, tick, plannerResponse.eventPolicyChanges())
			: visibleResponses.get(visibleResponses.size() - 1);
		DialogueState nextState = state
			.withConsecutiveFailureCount(0)
			.withDegradedRecovery(0, -1L, -1L)
			.withLastResponse(response)
			.withPendingReply(visibleResponses.stream().anyMatch(DialogueCore::hasVisibleText), visibleResponses.isEmpty() ? null : "planner_success");
		return new DialogueTransition(nextState, visibleResponses.isEmpty() ? List.of(response) : visibleResponses, List.copyOf(effects));
	}

	public static DialogueTransition onPlannerFailure(
		DialogueState state,
		LlmFailureType failureType,
		String failureMessage,
		boolean directChatTrigger,
		long tick,
		long nowMillis,
		ResetGuidance resetGuidance
	) {
		return onPlannerFailure(state, failureType, failureMessage, directChatTrigger, tick, nowMillis, resetGuidance, DialogueMessages.DEFAULTS);
	}

	public static DialogueTransition onPlannerFailure(
		DialogueState state,
		LlmFailureType failureType,
		String failureMessage,
		boolean directChatTrigger,
		long tick,
		long nowMillis,
		ResetGuidance resetGuidance,
		DialogueMessages messages
	) {
		ArrayList<DialogueEffect> effects = new ArrayList<>();
		effects.add(DialogueEffect.appendSemanticEvent(failureEventType(failureType), Map.of(
			"failureType", failureType.name(),
			"message", failureMessage == null ? "" : failureMessage
		)));

		ArrayList<DialogueResponse> visibleResponses = new ArrayList<>();
		if (failureType == LlmFailureType.PARSE_ERROR) {
			visibleResponses.add(new DialogueResponse(
				messages.parseError(),
				new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
				tick
			));
		}
		else if (failureType == LlmFailureType.TIMEOUT && directChatTrigger) {
			visibleResponses.add(new DialogueResponse(
				messages.timeout(),
				new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
				tick
			));
		}
		else if (failureType == LlmFailureType.PROVIDER_UNAVAILABLE && directChatTrigger) {
			visibleResponses.add(new DialogueResponse(
				messages.providerUnavailable(),
				new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
				tick
			));
		}

		int consecutiveFailureCount = state.consecutiveFailureCount() + 1;
		boolean degraded = state.degraded();
		int degradedEntries = state.degradedEntries();
		long degradedRecoverAtMillis = state.degradedRecoverAtMillis();
		long outageStartedAtMillis = state.outageStartedAtMillis();
		if (consecutiveFailureCount >= DEGRADED_FAILURE_THRESHOLD && !degraded) {
			degraded = true;
			degradedEntries++;
			long cooldown = degradedCooldownMillis(degradedEntries);
			degradedRecoverAtMillis = nowMillis + cooldown;
			if (outageStartedAtMillis < 0L) outageStartedAtMillis = nowMillis;
			effects.add(DialogueEffect.appendSemanticEvent("planner.degraded_entered", Map.of(
				"failureType", failureType.name(),
				"consecutiveFailureCount", consecutiveFailureCount,
				"retryAfterMillis", cooldown
			)));
			visibleResponses.add(new DialogueResponse(
				resetGuidance.message(messages),
				new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
				tick
			));
		}

		DialogueResponse lastResponse = visibleResponses.isEmpty() ? null : visibleResponses.get(visibleResponses.size() - 1);
		String pendingReplyReason = null;
		if (lastResponse != null && hasVisibleText(lastResponse)) {
			pendingReplyReason = switch (failureType) {
				case PARSE_ERROR -> "parse_error_visible_reply";
				case TIMEOUT -> "timeout_visible_reply";
				case PROVIDER_UNAVAILABLE -> "provider_unavailable_visible_reply";
				default -> null;
			};
		}
		DialogueState nextState = state
			.withLastFailureType(failureType)
			.withLastFailureTick(tick)
			.withConsecutiveFailureCount(consecutiveFailureCount)
			.withDegraded(degraded)
			.withDegradedRecovery(degradedEntries, degradedRecoverAtMillis, outageStartedAtMillis)
			.withLastResponse(lastResponse)
			.withPendingReply(lastResponse != null && hasVisibleText(lastResponse), pendingReplyReason);
		return new DialogueTransition(nextState, List.copyOf(visibleResponses), List.copyOf(effects));
	}

	static long degradedCooldownMillis(int degradedEntries) {
		long cooldown = DEGRADED_COOLDOWN_MILLIS;
		for (int i = 1; i < degradedEntries && cooldown < MAX_DEGRADED_COOLDOWN_MILLIS; i++) cooldown *= 2;
		return Math.min(cooldown, MAX_DEGRADED_COOLDOWN_MILLIS);
	}

	static boolean degradedCooldownElapsed(DialogueState state, long nowMillis) {
		return state.degraded() && state.degradedRecoverAtMillis() >= 0L && nowMillis >= state.degradedRecoverAtMillis();
	}

	/** How long the provider has been failing: since the first degraded period after the last successful turn. */
	public static long outageMillis(DialogueState state, long nowMillis) {
		return state.outageStartedAtMillis() < 0L ? 0L : Math.max(0L, nowMillis - state.outageStartedAtMillis());
	}

	/**
	 * Ends a degraded period whose cooldown has passed, so the next wake reaches the provider again. The failure
	 * count stays one short of the threshold: one more failure re-enters degraded mode with a longer cooldown.
	 */
	public static DialogueTransition onDegradedCooldownElapsed(DialogueState state, long nowMillis) {
		if (!degradedCooldownElapsed(state, nowMillis)) return new DialogueTransition(state, List.of(), List.of());
		DialogueState nextState = state
			.withDegraded(false)
			.withConsecutiveFailureCount(Math.min(state.consecutiveFailureCount(), DEGRADED_FAILURE_THRESHOLD - 1))
			.withDegradedRecovery(state.degradedEntries(), -1L, state.outageStartedAtMillis());
		return new DialogueTransition(nextState, List.of(), List.of(DialogueEffect.appendSemanticEvent("planner.degraded_cleared",
			Map.of("reason", "cooldown_elapsed", "degradedEntries", state.degradedEntries()))));
	}

	public static DialogueTransition onPlannerDegradedBlocked(DialogueState state, String senderName, boolean directChatTrigger,
		long tick, ResetGuidance resetGuidance) {
		return onPlannerDegradedBlocked(state, senderName, directChatTrigger, tick, resetGuidance, DialogueMessages.DEFAULTS);
	}

	public static DialogueTransition onPlannerDegradedBlocked(DialogueState state, String senderName, boolean directChatTrigger,
		long tick, ResetGuidance resetGuidance, DialogueMessages messages) {
		ArrayList<DialogueEffect> effects = new ArrayList<>();
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("directChat", directChatTrigger);
		payload.put("consecutiveFailureCount", state.consecutiveFailureCount());
		if (state.lastFailureType() != null) {
			payload.put("failureType", state.lastFailureType().name());
		}
		if (senderName != null && !senderName.isBlank()) {
			payload.put("speaker", senderName);
		}
		effects.add(DialogueEffect.appendSemanticEvent("planner.degraded_blocked", payload));

		if (!directChatTrigger) {
			return new DialogueTransition(state, List.of(), List.copyOf(effects));
		}

		DialogueResponse response = new DialogueResponse(
			resetGuidance.message(messages),
			new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
			tick
		);
		DialogueState nextState = state
			.withLastResponse(response)
			.withPendingReply(true, "planner_degraded_visible_reply");
		return new DialogueTransition(nextState, List.of(response), List.copyOf(effects));
	}

	public static DialogueTransition onReset(DialogueState state, String senderName, long tick) {
		return onReset(state, senderName, tick, DialogueMessages.DEFAULTS);
	}

	public static DialogueTransition onReset(DialogueState state, String senderName, long tick, DialogueMessages messages) {
		ArrayList<DialogueEffect> effects = new ArrayList<>();
		effects.add(DialogueEffect.appendSemanticEvent("planner.reset_requested", Map.of(
			"player", senderName
		)));
		if (state.degraded()) {
			effects.add(DialogueEffect.appendSemanticEvent("planner.degraded_cleared", Map.of()));
		}

		DialogueResponse response = new DialogueResponse(
			messages.reset(),
			new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, senderName),
			tick
		);
		DialogueState nextState = DialogueState.initial()
			.withLastResponse(response)
			.withPendingReply(true, "reset");
		return new DialogueTransition(nextState, List.of(response), List.copyOf(effects));
	}

	private static boolean hasVisibleText(DialogueResponse response) {
		return response != null && response.text() != null && !response.text().isBlank();
	}

	private static List<DialogueResponse> visibleResponses(PlannerResponse plannerResponse, DialogueIntent intent, long tick) {
		List<PlannerChatMessage> chatMessages = plannerResponse.chatMessages();
		if (chatMessages == null || chatMessages.isEmpty()) {
			String replyText = plannerResponse.replyText() == null ? "" : plannerResponse.replyText();
			if (replyText.isBlank()) {
				return List.of();
			}
			return List.of(new DialogueResponse(replyText, intent, tick, plannerResponse.eventPolicyChanges()));
		}
		ArrayList<DialogueResponse> responses = new ArrayList<>();
		for (PlannerChatMessage chatMessage : chatMessages) {
			responses.add(new DialogueResponse(
				chatMessage.text(),
				intent,
				tick,
				chatMessage.delayTicks(),
				plannerResponse.eventPolicyChanges()
			));
		}
		return List.copyOf(responses);
	}

	private static String failureEventType(LlmFailureType failureType) {
		return switch (failureType) {
			case TIMEOUT -> "planner.timeout";
			case PARSE_ERROR -> "planner.parse_error";
			case PROVIDER_ERROR, PROVIDER_UNAVAILABLE -> "planner.provider_error";
		};
	}
}
