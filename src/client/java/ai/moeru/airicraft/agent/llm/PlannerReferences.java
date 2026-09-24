package ai.moeru.airicraft.agent.llm;

import com.google.gson.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Presentation references shared by controller, thinker and compactor. Native identities never change. */
public final class PlannerReferences {
	private static final AtomicLong NEXT = new AtomicLong();
	private static final Pattern NATIVE_ID = Pattern.compile("(?<![A-Za-z0-9_.:-])[A-Za-z0-9_.:-]*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?:[A-Za-z0-9_.:-]*[A-Za-z0-9_-])?");
	private static final Pattern REFERENCE = Pattern.compile("@r[0-9]+");
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private final int capacity;
	private final LinkedHashMap<String, String> references = new LinkedHashMap<>();
	private final Map<String, String> identities = new java.util.HashMap<>();

	public PlannerReferences() { this(8192); }
	PlannerReferences(int capacity) {
		if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
		this.capacity = capacity;
	}

	public synchronized String present(String text) {
		text = PlannerInputText.message("presentation", text);
		return NATIVE_ID.matcher(text).replaceAll(match -> {
			String nativeId = match.group();
			String reference = references.get(nativeId);
			if (reference == null) {
				reference = "@r" + NEXT.incrementAndGet();
				references.put(nativeId, reference);
				identities.put(reference, nativeId);
				if (references.size() > capacity) {
					String oldest = references.keySet().iterator().next();
					identities.remove(references.remove(oldest));
				}
			}
			return reference;
		});
	}

	/** Transform text/arguments only, never image bytes, cache keys or protocol tool-call IDs. */
	public JsonArray presentMessages(List<Map<String, Object>> messages) {
		JsonArray result = GSON.toJsonTree(messages).getAsJsonArray();
		var snapshots = new PlannerSnapshotPresentation();
		for (JsonElement entry : result) {
			JsonObject message = entry.getAsJsonObject();
			String role = message.get("role").getAsString();
			snapshots.observeCalls(message);
			JsonElement content = message.get("content");
			if (content != null && content.isJsonPrimitive()) message.addProperty("content", present(PlannerInputText.message(role, snapshots.message(message, content.getAsString()))));
			else if (content != null && content.isJsonArray()) {
				for (JsonElement block : content.getAsJsonArray()) {
					JsonObject value = block.getAsJsonObject();
					if (value.has("text")) value.addProperty("text", present(PlannerInputText.message(role, snapshots.message(message, value.get("text").getAsString()))));
				}
			}
			if (message.has("tool_calls")) for (JsonElement call : message.getAsJsonArray("tool_calls")) {
				JsonObject function = call.getAsJsonObject().getAsJsonObject("function");
				function.addProperty("arguments", present(function.get("arguments").getAsString()));
			}
		}
		return result;
	}

	public synchronized JsonObject resolveArguments(JsonObject arguments) {
		JsonObject result = arguments.deepCopy();
		resolve(result, "");
		return result;
	}

	private void resolve(JsonElement value, String key) {
		if (value.isJsonObject()) {
			for (var entry : value.getAsJsonObject().entrySet()) {
				JsonElement item = entry.getValue();
				if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
					entry.setValue(new JsonPrimitive(resolveText(item.getAsString(), entry.getKey())));
				} else resolve(item, entry.getKey());
			}
		} else if (value.isJsonArray()) {
			JsonArray array = value.getAsJsonArray();
			for (int i = 0; i < array.size(); i++) {
				JsonElement item = array.get(i);
				if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) array.set(i, new JsonPrimitive(resolveText(item.getAsString(), key)));
				else resolve(item, key);
			}
		}
	}

	private String resolveText(String text, String key) {
		boolean identity = key.equals("id") || key.equals("uuid") || key.equals("uuids") || key.endsWith("Id") || key.endsWith("Ids") || key.endsWith("Uuid");
		if (identity && REFERENCE.matcher(text).matches()) {
			String nativeId = identities.get(text);
			if (nativeId == null) throw new JsonParseException("unknown_or_expired_reference: " + text + "; inspect current state");
			return nativeId;
		}
		// Persist native references in planning notes and transfer canonical evidence on handoff.
		if (List.of("objective", "constraints", "completionCriteria", "reason", "evidence", "decision", "outcome", "assignment", "summary", "memory", "result").contains(key)) {
			return REFERENCE.matcher(text).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(identities.getOrDefault(match.group(), match.group())));
		}
		return text;
	}
}
