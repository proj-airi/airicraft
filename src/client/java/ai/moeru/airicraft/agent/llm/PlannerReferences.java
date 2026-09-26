package ai.moeru.airicraft.agent.llm;

import com.google.gson.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Presentation references shared by controller, thinker and compactor. Native identities never change. */
public final class PlannerReferences {
	private static final AtomicLong NEXT = new AtomicLong();
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private final int capacity;
	private final LinkedHashMap<String, String> references = new LinkedHashMap<>();
	private final Map<String, String> identities = new java.util.HashMap<>();

	public PlannerReferences() { this(8192); }
	PlannerReferences(int capacity) {
		if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
		this.capacity = capacity;
	}

	/** Shorten one typed native identity. Ordinary prose is never searched or rewritten. */
	public synchronized String present(String nativeId) {
		if (!nativeIdentity(nativeId)) return nativeId;
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
	}

	private static boolean nativeIdentity(String value) {
		if (value == null || value.length() < 36 || !Character.isLetterOrDigit(value.charAt(0))
			|| !Character.isLetterOrDigit(value.charAt(value.length() - 1))) return false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!Character.isLetterOrDigit(c) && c != '_' && c != '.' && c != ':' && c != '-') return false;
		}
		for (int start = 0; start <= value.length() - 36; start++) {
			boolean uuid = true;
			for (int i = 0; i < 36; i++) {
				char c = value.charAt(start + i);
				if (i == 8 || i == 13 || i == 18 || i == 23) uuid &= c == '-';
				else uuid &= Character.digit(c, 16) >= 0;
			}
			if (uuid) return true;
		}
		return false;
	}

	/** Project typed fields in content and arguments, never pairing IDs or image bytes. */
	public JsonArray presentMessages(List<Map<String, Object>> messages) {
		return projectEntries(messages.stream().map(message -> new OpenAiCompatibleChatClient.RequestMessage(message, null)).toList());
	}

	public JsonArray presentMessages(LlmConversation conversation) {
		return projectEntries(OpenAiCompatibleChatClient.canonicalRequestEntries(conversation));
	}

	private JsonArray projectEntries(List<OpenAiCompatibleChatClient.RequestMessage> entries) {
		JsonArray result = new JsonArray();
		var snapshots = new PlannerSnapshotPresentation();
		for (var entry : entries) {
			JsonObject message = GSON.toJsonTree(entry.wire()).getAsJsonObject();
			result.add(message);
			String role = message.get("role").getAsString();
			snapshots.observeCalls(message);
			JsonElement fields = entry.fields();
			JsonElement content = message.get("content");
			if (content != null && content.isJsonPrimitive()) {
				String raw = content.getAsString();
				message.addProperty("content", presentContent(role, snapshots.message(message, raw, fields, this), fields));
			}
			else if (content != null && content.isJsonArray()) {
				for (JsonElement block : content.getAsJsonArray()) {
					JsonObject value = block.getAsJsonObject();
					if (value.has("text")) {
						String raw = value.get("text").getAsString();
						value.addProperty("text", presentContent(role, snapshots.message(message, raw, fields, this), fields));
					}
				}
			}
			if (message.has("tool_calls")) for (JsonElement call : message.getAsJsonArray("tool_calls")) {
				JsonObject function = call.getAsJsonObject().getAsJsonObject("function");
				String raw = function.get("arguments").getAsString();
				try {
					JsonElement parsed = JsonParser.parseString(raw);
					JsonElement projected = PlannerFieldPresentation.arguments(parsed, this);
					if (!projected.equals(parsed)) function.addProperty("arguments", GSON.toJson(projected));
				} catch (JsonParseException exception) {
					// Keep malformed or non-JSON arguments exact for the provider to reject.
				}
			}
		}
		return result;
	}

	String presentContent(String role, String content) {
		return presentContent(role, content, null);
	}

	String presentContent(String role, String content, JsonElement fields) {
		if (role.equals("user") && fields != null) {
			JsonElement projected = PlannerFieldPresentation.project(fields, null, this);
			if (content.startsWith("{")) return GSON.toJson(projected);
			int assignment = content.indexOf("DELEGATED TASK: ");
			int continuation = content.indexOf("DELEGATED TASK CONTINUATION: ");
			int marker = assignment >= 0 ? assignment : continuation;
			if (marker >= 0) {
				String label = assignment >= 0 ? "DELEGATED TASK: " : "DELEGATED TASK CONTINUATION: ";
				return content.substring(0, marker) + label + GSON.toJson(projected);
			}
		}
		if (role.equals("user") && fields != null
			&& (content.startsWith("Tool result for ") || content.startsWith("Tool result: "))) role = "tool";
		if (!role.equals("tool")) return content;
		String prefix = PlannerFieldPresentation.envelopePrefix(content);
		if (prefix == null) return content;
		JsonElement parsed = fields == null ? PlannerFieldPresentation.fields(content) : fields;
		if (parsed == null || !parsed.isJsonObject()) return content;
		JsonObject projected = PlannerFieldPresentation.project(parsed, null, this).getAsJsonObject();
		return !prefix.isEmpty() && (projected.has("workId") || projected.has("accepted"))
			? PlannerInputText.toolResult(prefix, projected) : prefix + GSON.toJson(projected);
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
		boolean identity = PlannerFieldPresentation.isIdentityField(key);
		if (identity && reference(text)) {
			String nativeId = identities.get(text);
			if (nativeId == null) throw new JsonParseException("unknown_or_expired_reference: " + text + "; inspect current state");
			return nativeId;
		}
		// Persist native references in planning notes and transfer canonical evidence on handoff.
		if (List.of("objective", "constraints", "completionCriteria", "reason", "evidence", "decision", "outcome", "assignment", "summary", "memory", "result").contains(key)) {
			return restoreReferences(text);
		}
		return text;
	}

	private static boolean reference(String text) {
		if (text == null || text.length() < 3 || !text.startsWith("@r")) return false;
		for (int i = 2; i < text.length(); i++) if (!asciiDigit(text.charAt(i))) return false;
		return true;
	}

	private String restoreReferences(String text) {
		StringBuilder restored = new StringBuilder();
		for (int index = 0; index < text.length();) {
			if (text.charAt(index) == '@' && index + 2 < text.length() && text.charAt(index + 1) == 'r'
				&& asciiDigit(text.charAt(index + 2))) {
				int end = index + 3;
				while (end < text.length() && asciiDigit(text.charAt(end))) end++;
				String token = text.substring(index, end);
				restored.append(identities.getOrDefault(token, token));
				index = end;
			} else restored.append(text.charAt(index++));
		}
		return restored.toString();
	}

	private static boolean asciiDigit(char value) { return value >= '0' && value <= '9'; }
}
