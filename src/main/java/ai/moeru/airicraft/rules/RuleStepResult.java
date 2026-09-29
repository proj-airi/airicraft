package ai.moeru.airicraft.rules;

import com.google.gson.JsonArray;
import java.util.Objects;

/**
 * One step's output and the next rule state, as compact JSON the host stores until the next step. Attention steps
 * return {@code decisions}; salience steps return {@code percepts} and {@code drops}. The arrays a hook does not use
 * are empty.
 */
public record RuleStepResult(JsonArray decisions, JsonArray percepts, JsonArray drops, String stateJson, long nanos) {
	public RuleStepResult {
		Objects.requireNonNull(decisions, "decisions");
		Objects.requireNonNull(percepts, "percepts");
		Objects.requireNonNull(drops, "drops");
		Objects.requireNonNull(stateJson, "stateJson");
	}

	public RuleStepResult(JsonArray decisions, String stateJson, long nanos) {
		this(decisions, new JsonArray(), new JsonArray(), stateJson, nanos);
	}
}
