package ai.moeru.airicraft.agent.llm;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Semantic presentation rules for known observations; unknown fields pass through. */
public final class PlannerInputText {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private PlannerInputText() {}

	/** The canonical history stays intact for cursors, replay and recorder dispatch metadata. */
	public static String message(String role, String content) {
		// Round only planner-facing evidence; canonical state and protocol identities stay exact.
		// Quote alternatives are disjoint: possessive repetition avoids recursive backtracking
		// (and stack overflow) on long quoted inspection evidence.
		var numbers = java.util.regex.Pattern.compile(
		"\"(?:\\\\.|[^\"\\\\])*+\"|(?<![\\p{L}\\p{N}_./@+-])[-+]?(?:[0-9]+\\.[0-9]+|\\.[0-9]+|[0-9]+[eE][+-]?[0-9]+)(?:[eE][+-]?[0-9]+)?(?![\\p{L}\\p{N}_./@])").matcher(content);
		var rounded = new StringBuilder();
		while (numbers.find()) {
			String value = numbers.group();
			String replacement = value.startsWith("\"") ? value : new java.math.BigDecimal(value)
				.setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
			numbers.appendReplacement(rounded, java.util.regex.Matcher.quoteReplacement(replacement));
		}
		numbers.appendTail(rounded);
		content = rounded.toString();

		return role.equals("tool") ? toolResult(content) : content;
	}

	public static String observation(Map<String, Object> payload) {
		return observation(GSON.toJsonTree(payload).getAsJsonObject());
	}

	/** Prose rendering of an {@code observe} result; the canonical JSON stays in history. */
	public static String observation(JsonObject payload) {
		Fields context = new Fields(payload);
		StringBuilder out = new StringBuilder();
		out.append(context.phrase("worldSessionId", "World ")).append(context.phrase("tick", "; client tick "))
			.append(context.phrase("serverTick", "; server tick ")).append(".\n");
		out.append(context.phrase("decisionOwner", "Decisions: ")).append(context.phrase("actuatorOwner", "; actuation: ")).append(".\n");
		JsonElement patch = context.take("currentPatch");
		if (patch != null) out.append(patch.isJsonArray() && patch.getAsJsonArray().isEmpty()
			? "State unchanged since the previous observation.\n"
			: "State changes (RFC 6902 JSON Patch against the previous observation's state): " + text(patch) + "\n");
		context.take("stateBaseline");
		JsonElement current = context.take("current");
		JsonElement currentWork = current != null && current.isJsonObject() ? current.getAsJsonObject().get("work") : null;
		if (current != null && current.isJsonObject()) {
			Fields facts = new Fields(current.getAsJsonObject());
			for (String key : List.of("session", "dimension", "objective", "inventory", "vitals", "physical", "reflex", "travelRestrictions", "work")) {
				JsonElement value = facts.take(key);
				if (value == null) continue;
				out.append(switch (key) {
					case "objective" -> "Objective: " + objective(value);
					case "inventory" -> inventory(value);
					case "vitals" -> vitals(value);
					case "physical" -> "Physical: " + physical(value);
					case "reflex" -> "Reflex: " + reflex(value);
					case "work" -> "Current work: " + workList(value);
					case "dimension" -> "Dimension: " + PlannerStateText.item(value.getAsString());
					default -> key + ": " + text(value);
				}).append('\n');
			}
			out.append(facts.rest());
		} else if (current != null) out.append("Current: ").append(text(current)).append('\n');
		JsonElement queue = context.take("toolQueue");
		if (queue != null) out.append("Tool queue: ").append(text(queue)).append('\n');
		JsonElement notices = context.take("notices");
		if (notices != null && notices.isJsonArray()) for (var notice : notices.getAsJsonArray()) out.append("Notice: ").append(text(notice)).append('\n');
		out.append(context.phrase("afterEventSequence", "Evidence after ")).append(context.phrase("throughEventSequence", " through ")).append(".\n");
		JsonElement missing = context.take("missingEventRange");
		if (missing != null) out.append("MISSING evidence: ").append(text(missing)).append('\n');
		JsonElement events = context.take("events");
		if (events != null && events.isJsonArray()) {
			if (events.getAsJsonArray().isEmpty()) out.append("No new events.\n");
			for (var event : events.getAsJsonArray()) out.append(event(event, currentWork)).append('\n');
		} else if (events != null) out.append("Events: ").append(text(events)).append('\n');
		return out.append(context.rest()).toString().stripTrailing();
	}

	/** Only generated JSON tool envelopes are eligible. Plain text and unknown shapes stay exact. */
	public static String toolResult(String content) {
		if (!content.startsWith("Tool result for ")) return content;
		int separator = content.indexOf(": ");
		if (separator < 0) return content;
		String body = content.substring(separator + 2);
		if (!body.startsWith("{")) return content;
		JsonElement parsed;
		try { parsed = JsonParser.parseString(body); }
		catch (JsonParseException exception) { return content; }
		if (!parsed.isJsonObject()) return content;
		JsonObject object = parsed.getAsJsonObject();
		if (!object.has("workId") && !object.has("accepted")) return content;
		return content.substring(0, separator + 2) + work(object);
	}

	static String work(JsonObject object) {
		Fields f = new Fields(object);
		var parts = new ArrayList<String>();
		JsonElement accepted = f.take("accepted");
		if (accepted != null) parts.add(accepted.isJsonPrimitive() && accepted.getAsJsonPrimitive().isBoolean()
			? accepted.getAsBoolean() ? "Accepted" : "Rejected" : "accepted " + text(accepted));
		add(parts, f.phrase("workId", "Work "));
		add(parts, f.phrase("parentWorkId", "child of "));
		add(parts, f.phrase("label", "task "));
		JsonElement state = f.take("state"), phase = f.take("phase");
		if (state != null && state.equals(phase)) parts.add("state and phase " + text(state));
		else { if (state != null) parts.add("state " + text(state)); if (phase != null) parts.add("phase " + text(phase)); }
		for (String key : List.of("updatedTick", "collected", "holdId", "message", "result"))
			add(parts, f.phrase(key, switch (key) { case "updatedTick" -> "updated tick "; case "holdId" -> "hold "; default -> key + " "; }));
		add(parts, f.rest());
		return String.join("; ", parts) + ".";
	}

	private static String workList(JsonElement value) {
		if (value.isJsonObject()) return work(value.getAsJsonObject());
		if (!value.isJsonArray()) return text(value);
		if (value.getAsJsonArray().isEmpty()) return "none.";
		return value.getAsJsonArray().asList().stream().map(item -> item.isJsonObject() ? work(item.getAsJsonObject()) : text(item))
			.collect(java.util.stream.Collectors.joining("\n  ", "\n  ", ""));
	}

	private static String inventory(JsonElement value) {
		if (!value.isJsonObject()) return "Inventory: " + text(value);
		var counts = new java.util.TreeMap<String, Integer>();
		for (var entry : value.getAsJsonObject().entrySet()) {
			if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isNumber()) return "Inventory: " + text(value);
			counts.put(entry.getKey(), entry.getValue().getAsInt());
		}
		return PlannerStateText.inventory(counts);
	}

	private static String vitals(JsonElement value) {
		if (!value.isJsonObject()) return "Vitals: " + text(value);
		var values = new java.util.TreeMap<String, Number>();
		for (var entry : value.getAsJsonObject().entrySet()) {
			if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isNumber()) return "Vitals: " + text(value);
			values.put(entry.getKey(), entry.getValue().getAsNumber());
		}
		return PlannerStateText.vitals(values);
	}

	private static String objective(JsonElement value) {
		if (!value.isJsonObject()) return text(value);
		Fields f = new Fields(value.getAsJsonObject());
		var parts = new ArrayList<String>();
		for (String key : List.of("id", "status", "objective", "outcome", "constraints", "completionCriteria", "decisions"))
			add(parts, f.phrase(key, switch (key) { case "id", "status" -> ""; case "objective" -> "pursue "; case "completionCriteria" -> "completion criteria "; default -> key + " "; }));
		add(parts, f.rest());
		return String.join("; ", parts);
	}

	private static String physical(JsonElement value) {
		if (!value.isJsonObject()) return text(value);
		Fields f = new Fields(value.getAsJsonObject());
		var parts = new ArrayList<String>();
		for (String key : List.of("position", "velocity")) {
			JsonElement vector = f.take(key);
			if (vector != null) parts.add(key + " " + vector(vector));
		}
		add(parts, f.flag("grounded", "on ground", "off ground"));
		add(parts, f.flag("touchingWater", "touching water", "not touching water"));
		add(parts, f.flag("climbing", "climbing", "not climbing"));
		add(parts, f.rest());
		return String.join("; ", parts);
	}

	private static String vector(JsonElement value) {
		if (value.isJsonObject() && value.getAsJsonObject().keySet().equals(java.util.Set.of("x", "y", "z"))) {
			var v = value.getAsJsonObject();
			return "(" + text(v.get("x")) + "," + text(v.get("y")) + "," + text(v.get("z")) + ")";
		}
		return text(value);
	}

	private static String reflex(JsonElement value) {
		if (!value.isJsonObject()) return text(value);
		Fields f = new Fields(value.getAsJsonObject());
		var parts = new ArrayList<String>();
		add(parts, f.phrase("state", ""));
		add(parts, f.phrase("safetyEpoch", "safety epoch "));
		add(parts, f.phrase("threats", "threats "));
		for (String key : List.of("health", "air")) {
			String maximum = key.equals("health") ? "maxHealth" : "maxAir";
			if (f.has(key) && f.has(maximum)) parts.add(key + " " + text(f.take(key)) + "/" + text(f.take(maximum)));
		}
		add(parts, f.phrase("startedTick", "started tick "));
		add(parts, f.phrase("lastDangerTick", "last danger tick "));
		add(parts, f.phrase("breathableTicks", "breathable ticks "));
		add(parts, f.rest());
		return String.join("; ", parts);
	}

	private static String event(JsonElement value, JsonElement currentWork) {
		if (!value.isJsonObject()) return text(value);
		Fields f = new Fields(value.getAsJsonObject());
		String prefix = f.phrase("seqNo", "Event ") + f.phrase("tick", " at tick ") + f.phrase("type", ": ");
		JsonElement payload = f.take("payload");
		String body = payload == null ? "" : eventPayload(payload);
		if (payload != null && payload.isJsonObject() && payload.getAsJsonObject().has("workId")
			&& currentWork != null && currentWork.isJsonArray() && currentWork.getAsJsonArray().asList().contains(payload)) {
			body = "same snapshot as current work " + text(payload.getAsJsonObject().get("workId")) + ".";
		}
		return prefix + ": " + body + f.rest();
	}

	private static String eventPayload(JsonElement value) {
		if (!value.isJsonObject()) return text(value);
		var object = value.getAsJsonObject();
		if (object.has("workId")) return work(object);
		if (object.has("missionId") && object.has("activeStepKind")) {
			Fields f = new Fields(object);
			var parts = new ArrayList<String>();
			add(parts, f.phrase("missionId", "Mission "));
			add(parts, f.phrase("state", "state "));
			// Factor equal kinds without losing which roles share them.
			var kinds = new java.util.LinkedHashMap<String, List<String>>();
			for (String key : List.of("missionType", "taskType", "activeStepKind")) {
				JsonElement kind = f.take(key);
				if (kind != null) kinds.computeIfAbsent(text(kind), ignored -> new ArrayList<>()).add(key);
			}
			kinds.forEach((kind, roles) -> parts.add(String.join("/", roles) + " " + kind));
			for (String key : List.of("activeStepId", "source", "resourceKind", "quantity", "collected", "remaining")) add(parts, f.phrase(key, key + " "));
			add(parts, f.rest());
			return String.join("; ", parts);
		}
		if (object.has("observed") && object.get("observed").isJsonObject()) {
			Fields outer = new Fields(object);
			Fields f = new Fields(outer.take("observed").getAsJsonObject());
			var parts = new ArrayList<String>();
			for (String key : List.of("actor", "action", "worldTick", "timestampMs", "dimension")) add(parts, f.phrase(key, key + " "));
			if (f.has("x") && f.has("y") && f.has("z")) parts.add("at (" + text(f.take("x")) + "," + text(f.take("y")) + "," + text(f.take("z")) + ")");
			for (String key : List.of("itemId", "count", "containerType", "containerEntityUuid")) add(parts, f.phrase(key, key + " "));
			JsonElement contents = f.take("contents");
			if (contents != null && contents.isJsonObject()) {
				var counts = new java.util.TreeMap<String, Integer>();
				boolean itemCounts = contents.getAsJsonObject().entrySet().stream().allMatch(entry -> entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber());
				if (itemCounts) {
					contents.getAsJsonObject().entrySet().forEach(entry -> counts.put(entry.getKey(), entry.getValue().getAsInt()));
					parts.add("Container contents: " + PlannerStateText.inventory(counts));
				} else parts.add("Contents " + text(contents));
			} else if (contents != null) parts.add("Contents " + text(contents));
			add(parts, f.rest()); add(parts, outer.rest());
			return String.join("; ", parts);
		}
		return text(value);
	}

	private static String text(JsonElement value) {
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
			String text = value.getAsString();
			return text.isEmpty() ? "none" : text;
		}
		// Explicitly empty values mean none; absent and null fields are never inferred empty.
		if (value.isJsonArray() && value.getAsJsonArray().isEmpty()) return "none";
		if (value.isJsonObject() && value.getAsJsonObject().isEmpty()) return "none";
		return GSON.toJson(value);
	}

	private static void add(List<String> parts, String value) { if (!value.isEmpty()) parts.add(value); }

	private static final class Fields {
		private final JsonObject remaining;
		Fields(JsonObject object) { remaining = object.deepCopy(); }
		boolean has(String key) { return remaining.has(key); }
		JsonElement take(String key) { return remaining.remove(key); }
		String phrase(String key, String label) {
			JsonElement value = take(key);
			if (value == null) return "";
			String rendered = text(value);
			if (List.of("dimension", "itemId", "containerType").contains(key) && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) rendered = PlannerStateText.item(rendered);
			return label + rendered;
		}
		String flag(String key, String yes, String no) {
			JsonElement value = take(key);
			return value == null ? "" : value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
				? value.getAsBoolean() ? yes : no : key + " " + text(value);
		}
		String rest() { return remaining.isEmpty() ? "" : "Additional fields: " + GSON.toJson(remaining); }
	}
}
