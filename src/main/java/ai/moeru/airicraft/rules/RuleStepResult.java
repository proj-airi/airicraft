package ai.moeru.airicraft.rules;

import com.google.gson.JsonArray;
import java.util.Objects;

/** One step's decisions and the next rule state, as compact JSON the host stores until the next step. */
public record RuleStepResult(JsonArray decisions, String stateJson, long nanos) {
	public RuleStepResult {
		Objects.requireNonNull(decisions, "decisions");
		Objects.requireNonNull(stateJson, "stateJson");
	}
}
