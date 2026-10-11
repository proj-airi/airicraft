package ai.moeru.airicraft.airi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Text codec for AIRI channel server events.
 *
 * <p>The server writes SuperJSON, which wraps the event as {@code {"json": event, "meta": ...}}. It reads plain JSON as
 * well, so this codec writes plain JSON and unwraps SuperJSON on read. The events this link uses carry no values that
 * need SuperJSON metadata.
 */
public final class AiriWireCodec {
	private AiriWireCodec() {
	}

	public static String encode(String type, JsonObject data, AiriModuleIdentity source, String eventId) {
		JsonObject event = new JsonObject();
		event.addProperty("id", eventId);
		JsonObject metadata = new JsonObject();
		metadata.add("source", source.sourceJson());
		metadata.add("event", event);
		JsonObject envelope = new JsonObject();
		envelope.addProperty("type", type);
		envelope.add("data", data == null ? new JsonObject() : data);
		envelope.add("metadata", metadata);
		return envelope.toString();
	}

	public static AiriEvent decode(String text) {
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(text);
		}
		catch (JsonParseException exception) {
			throw new IllegalArgumentException("AIRI event is not JSON", exception);
		}
		if (!parsed.isJsonObject()) {
			throw new IllegalArgumentException("AIRI event is not a JSON object");
		}
		JsonObject envelope = parsed.getAsJsonObject();
		if (!envelope.has("type") && envelope.has("json") && envelope.get("json").isJsonObject()) {
			envelope = envelope.getAsJsonObject("json");
		}
		if (!envelope.has("type") || !envelope.get("type").isJsonPrimitive()) {
			throw new IllegalArgumentException("AIRI event has no type");
		}
		if (!envelope.has("data") || !envelope.get("data").isJsonObject()) {
			throw new IllegalArgumentException("AIRI event has no data object");
		}
		JsonObject metadata = envelope.has("metadata") && envelope.get("metadata").isJsonObject()
			? envelope.getAsJsonObject("metadata")
			: new JsonObject();
		return new AiriEvent(envelope.get("type").getAsString(), envelope.getAsJsonObject("data"), metadata);
	}
}
