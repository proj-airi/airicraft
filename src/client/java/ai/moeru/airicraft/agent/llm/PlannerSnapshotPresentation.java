package ai.moeru.airicraft.agent.llm;

import com.google.gson.*;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Request-local projection: replaying the same accepted history produces the same deltas.
 * Full observations remain available to the flight recorder. No unsent observation advances a baseline.
 * The first observation, a world change or an event gap presents full state; later ones present a JSON Patch. */
final class PlannerSnapshotPresentation {
	private final Set<String> observeCallIds = new HashSet<>();
	private JsonObject previous;
	private JsonElement world;

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
		JsonObject current = payload.getAsJsonObject("current");
		// The authoritative objective is already supplied as current state or a patch.
		if (payload.has("events") && payload.get("events").isJsonArray()) {
			for (JsonElement item : payload.getAsJsonArray("events")) {
				if (!item.isJsonObject()) continue;
				JsonObject event = item.getAsJsonObject();
				if (!new JsonPrimitive("objective.changed").equals(event.get("type"))
					|| !event.has("payload") || !event.get("payload").isJsonObject()) continue;
				JsonObject evidence = event.getAsJsonObject("payload");
				if (evidence.has("objective") && evidence.get("objective").equals(current.get("objective"))) evidence.remove("objective");
			}
		}
		boolean baseline = previous == null || !Objects.equals(world, payload.get("worldSessionId"))
			|| payload.has("missingEventRange") || payload.has("stateBaseline");
		payload.remove("stateBaseline");
		if (!baseline) {
			JsonArray patch = new JsonArray();
			diff(previous, current, "", patch);
			rebuild(payload, patch);
		}
		previous = current;
		world = payload.get("worldSessionId");
		return PlannerInputText.observation(PlannerFieldPresentation.project(payload, null, references).getAsJsonObject());
	}

	private static void rebuild(JsonObject payload, JsonArray patch) {
		JsonObject copy = payload.deepCopy();
		for (String key : copy.keySet()) payload.remove(key);
		for (var entry : copy.entrySet()) {
			if (entry.getKey().equals("current")) payload.add("currentPatch", patch);
			else payload.add(entry.getKey(), entry.getValue());
		}
	}

	private static void diff(JsonObject before, JsonObject after, String path, JsonArray patch) {
		for (String key : before.keySet()) if (!after.has(key)) patch.add(operation("remove", path + "/" + escape(key), null));
		for (var entry : after.entrySet()) {
			JsonElement old = before.get(entry.getKey()), value = entry.getValue();
			if (value.equals(old)) continue;
			String next = path + "/" + escape(entry.getKey());
			if (old != null && old.isJsonObject() && value.isJsonObject()) diff(old.getAsJsonObject(), value.getAsJsonObject(), next, patch);
			else patch.add(operation(old == null ? "add" : "replace", next, value));
		}
	}

	private static JsonObject operation(String op, String path, JsonElement value) {
		JsonObject operation = new JsonObject();
		operation.addProperty("op", op);
		operation.addProperty("path", path);
		if (value != null) operation.add("value", value);
		return operation;
	}

	private static String escape(String key) {
		return key.replace("~", "~0").replace("/", "~1");
	}
}
