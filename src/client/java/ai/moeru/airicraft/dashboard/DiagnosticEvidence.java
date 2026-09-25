package ai.moeru.airicraft.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Readable pages derived only from the already redacted, frozen attachment bytes. */
public final class DiagnosticEvidence {
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private DiagnosticEvidence() {}

	public record Page(String title, String text, String imageDataUrl) {}

	public static List<Page> pages(Map<String, String> attachments) {
		List<Page> pages = new ArrayList<>();
		textPages(pages, "summary.txt", attachments.get("summary.txt"));
		int line = 0;
		for (String record : attachments.get("report.jsonl").lines().toList()) {
			JsonElement value = JsonParser.parseString(record);
			String label = "Evidence";
			if (value.isJsonObject() && value.getAsJsonObject().has("recordType")) {
				var object = value.getAsJsonObject();
				label = switch (object.get("recordType").getAsString()) {
					case "manifest" -> "Build, session and report metadata";
					case "observation" -> "Observation " + object.get("sequence") + " · " + object.get("type").getAsString();
					case "integrity" -> "Integrity footer";
					default -> "Evidence";
				};
			}
			String title = label + " · report.jsonl line " + ++line;
			List<Page> images = new ArrayList<>();
			JsonElement readable = inspect(value, title, images);
			textPages(pages, title, PRETTY.toJson(readable));
			pages.addAll(images);
		}
		return List.copyOf(pages);
	}

	private static void textPages(List<Page> pages, String title, String text) {
		// Keep native text layout bounded while making every character reachable.
		for (int offset = 0, part = 1; offset < text.length(); part++) {
			int end = Math.min(offset + 12000, text.length());
			if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
			pages.add(new Page(title + (text.length() > 12000 ? " · part " + part : ""), text.substring(offset, end), null));
			offset = end;
		}
	}

	private static JsonElement inspect(JsonElement value, String path, List<Page> images) {
		if (value.isJsonObject()) {
			var object = value.getAsJsonObject().deepCopy();
			for (var entry : value.getAsJsonObject().entrySet()) {
				String childPath = path + "." + entry.getKey();
				if (entry.getKey().equals("imageBase64") && entry.getValue().isJsonPrimitive()
					&& object.has("format") && object.get("format").isJsonPrimitive()) {
					String data = "data:image/" + object.get("format").getAsString() + ";base64," + entry.getValue().getAsString();
					if (image(data)) { object.addProperty(entry.getKey(), imagePage(images, childPath, data)); continue; }
				}
				object.add(entry.getKey(), inspect(entry.getValue(), childPath, images));
			}
			return object;
		}
		if (value.isJsonArray()) {
			var array = new com.google.gson.JsonArray();
			for (int i = 0; i < value.getAsJsonArray().size(); i++) array.add(inspect(value.getAsJsonArray().get(i), path + "[" + i + "]", images));
			return array;
		}
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
			String text = value.getAsString();
			if (image(text)) return new JsonPrimitive(imagePage(images, path, text));
			// Model envelopes can contain JSON serialized inside a string.
			if (text.stripLeading().startsWith("{") || text.stripLeading().startsWith("[")) {
				try {
					JsonElement embedded = JsonParser.parseString(text);
					if (embedded.isJsonObject() || embedded.isJsonArray()) return new JsonPrimitive(PRETTY.toJson(inspect(embedded, path, images)));
				} catch (com.google.gson.JsonParseException ignored) { /* Ordinary text stays unchanged. */ }
			}
		}
		return value.deepCopy();
	}

	private static boolean image(String value) { return value.startsWith("data:image/png;base64,") || value.startsWith("data:image/jpeg;base64,"); }

	private static String imagePage(List<Page> images, String path, String data) {
		images.add(new Page("Screenshot · " + path, "Attached pixels; not automatically redacted.", data));
		return "[Image shown on the following screenshot page; encoded bytes remain in report.jsonl]";
	}
}
