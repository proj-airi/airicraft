package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.math.RoundingMode;

/** Projects typed evidence fields for a model request without changing the recorded values. */
final class PlannerFieldPresentation {
	private PlannerFieldPresentation() {}

	/** Recognize only a complete JSON object, possibly inside a generated tool envelope. */
	static JsonElement fields(String content) {
		String prefix = envelopePrefix(content);
		if (prefix == null) return null;
		try {
			JsonElement parsed = JsonParser.parseString(content.substring(prefix.length()));
			return parsed.isJsonObject() ? parsed : null;
		} catch (JsonParseException exception) {
			return null;
		}
	}

	static String envelopePrefix(String content) {
		if (content == null) return null;
		int offset = 0;
		while (content.startsWith("Tool result for ", offset) || content.startsWith("Tool result: ", offset)) {
			int separator = content.indexOf(": ", offset);
			if (separator < 0) return null;
			offset = separator + 2;
		}
		return content.startsWith("{", offset) ? content.substring(0, offset) : null;
	}

	static JsonElement project(JsonElement value, String key, PlannerReferences references) {
		return project(value, key, references, true);
	}

	static JsonElement arguments(JsonElement value, PlannerReferences references) {
		return project(value, null, references, false);
	}

	private static JsonElement project(JsonElement value, String key, PlannerReferences references, boolean roundEvidence) {
		if (value == null || value.isJsonNull()) return value;
		if (value.isJsonObject()) {
			JsonObject projected = new JsonObject();
			for (var field : value.getAsJsonObject().entrySet()) {
				projected.add(field.getKey(), project(field.getValue(), field.getKey(), references, roundEvidence));
			}
			return projected;
		}
		if (value.isJsonArray()) {
			JsonArray projected = new JsonArray();
			for (JsonElement item : value.getAsJsonArray()) projected.add(project(item, key, references, roundEvidence));
			return projected;
		}
		JsonPrimitive primitive = value.getAsJsonPrimitive();
		if (primitive.isNumber() && roundEvidence) {
			var number = primitive.getAsBigDecimal();
			if (number.stripTrailingZeros().scale() <= 0) return value.deepCopy();
			return new JsonPrimitive(number.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros());
		}
		if (references != null && primitive.isString() && isIdentityField(key)) {
			return new JsonPrimitive(references.present(primitive.getAsString()));
		}
		return value.deepCopy();
	}

	static boolean isIdentityField(String key) {
		return key != null && (key.equals("id") || key.equals("uuid") || key.equals("uuids")
			|| key.endsWith("Id") || key.endsWith("Ids") || key.endsWith("Uuid") || key.endsWith("UUID"));
	}
}
