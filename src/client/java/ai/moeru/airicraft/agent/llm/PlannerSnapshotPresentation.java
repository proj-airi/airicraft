package ai.moeru.airicraft.agent.llm;

import com.google.gson.*;
import java.util.HashSet;
import java.util.Set;

/** Request-local projection: replaying the same accepted history produces the same deltas.
 * Full observations remain available to the flight recorder. No unsent observation advances a baseline.
 * Baseline and delta rules are {@link ObservationPresenter}'s, shared with the Codex backend. */
final class PlannerSnapshotPresentation {
	private final Set<String> observeCallIds = new HashSet<>();
	private final ObservationPresenter presenter = new ObservationPresenter();

	/** Record wire tool calls so later results can be matched to {@code observe}. */
	void observeCalls(JsonObject message) {
		if (!message.has("tool_calls") || !message.get("tool_calls").isJsonArray()) return;
		for (JsonElement call : message.getAsJsonArray("tool_calls")) {
			if (!call.isJsonObject()) continue;
			JsonObject value = call.getAsJsonObject();
			JsonElement function = value.get("function");
			if (value.has("id") && function != null && function.isJsonObject()
				&& new JsonPrimitive(PlannerObservation.TOOL_NAME).equals(function.getAsJsonObject().get("name")))
				observeCallIds.add(value.get("id").getAsString());
		}
	}

	String message(JsonObject message, String content, JsonElement fields, PlannerReferences references) {
		JsonElement callId = message.get("tool_call_id");
		if (!new JsonPrimitive("tool").equals(message.get("role")) || callId == null || !callId.isJsonPrimitive()
			|| !observeCallIds.contains(callId.getAsString())) return content;
		JsonObject payload;
		try { payload = fields != null && fields.isJsonObject() ? fields.getAsJsonObject().deepCopy()
			: JsonParser.parseString(content).getAsJsonObject(); }
		catch (JsonParseException | IllegalStateException exception) { return content; }
		if (!payload.has("current") || !payload.get("current").isJsonObject()) return content;
		JsonObject presented = presenter.present(payload, !PlannerObservation.isRuntimeCall(callId.getAsString()));
		return PlannerInputText.observation(PlannerFieldPresentation.project(presented, null, references).getAsJsonObject());
	}
}
