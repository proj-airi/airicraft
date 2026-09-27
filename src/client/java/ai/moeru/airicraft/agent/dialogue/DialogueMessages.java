package ai.moeru.airicraft.agent.dialogue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed chat lines sent without a planner reply; a character card may voice them. */
public record DialogueMessages(
	String degraded,
	String hostedAutoReset,
	String hostedResetExhausted,
	String reset,
	String parseError,
	String timeout,
	String providerUnavailable
) {
	/** Override keys, in the order a card lists them. */
	public static final List<String> KEYS = List.of(
		"degraded", "hostedAutoReset", "hostedResetExhausted", "reset", "parseError", "timeout", "providerUnavailable");

	public static final DialogueMessages DEFAULTS = new DialogueMessages(
		"I'm having trouble understanding right now. Send '" + DialogueCore.RESET_COMMAND + "' to recover my planner.",
		"I'm having trouble with my planner. This playtest will try one automatic reset.",
		"My planner is having trouble again. This playtest has already used its automatic reset and won't reset again.",
		"Planner state reset.",
		"I got confused for a moment.",
		"I hit a timeout just now. Please try again.",
		"I can't reach the LLM provider right now. Please try again."
	);

	public DialogueMessages {
		Objects.requireNonNull(degraded, "degraded");
		Objects.requireNonNull(hostedAutoReset, "hostedAutoReset");
		Objects.requireNonNull(hostedResetExhausted, "hostedResetExhausted");
		Objects.requireNonNull(reset, "reset");
		Objects.requireNonNull(parseError, "parseError");
		Objects.requireNonNull(timeout, "timeout");
		Objects.requireNonNull(providerUnavailable, "providerUnavailable");
	}

	/** Replaces the named lines; keys must come from {@link #KEYS}. */
	public DialogueMessages withOverrides(Map<String, String> overrides) {
		Map<String, String> lines = new LinkedHashMap<>(asMap());
		for (Map.Entry<String, String> override : overrides.entrySet()) {
			if (!lines.containsKey(override.getKey())) throw new IllegalArgumentException("Unknown dialogue message: " + override.getKey());
			lines.put(override.getKey(), Objects.requireNonNull(override.getValue(), override.getKey()));
		}
		return new DialogueMessages(lines.get("degraded"), lines.get("hostedAutoReset"), lines.get("hostedResetExhausted"),
			lines.get("reset"), lines.get("parseError"), lines.get("timeout"), lines.get("providerUnavailable"));
	}

	private Map<String, String> asMap() {
		Map<String, String> lines = new LinkedHashMap<>();
		lines.put("degraded", degraded);
		lines.put("hostedAutoReset", hostedAutoReset);
		lines.put("hostedResetExhausted", hostedResetExhausted);
		lines.put("reset", reset);
		lines.put("parseError", parseError);
		lines.put("timeout", timeout);
		lines.put("providerUnavailable", providerUnavailable);
		return lines;
	}
}
