package ai.moeru.airicraft.agent.wakes;

import ai.moeru.airicraft.agent.debug.AgentDebugTimelineEntry;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.llm.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public record WakeTranscript(JsonObject data) {
	private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	public static WakeTranscript capture(String name, List<RecordingPlannerBackend.Request> recorded,
		List<AgentDebugTimelineEntry> timeline, List<SemanticEvent> events, Object tools) {
		var root = new JsonObject();
		root.addProperty("scenario", name);
		var requests = new JsonArray();
		List<LlmChatMessage> previousMessages = List.of();
		for (var item : recorded) {
			var backend = item.request();
			var request = new JsonObject();
			request.addProperty("index", item.index());
			request.addProperty("tick", item.observedAtTick());
			request.addProperty("generation", backend.generation());
			request.addProperty("discarded", timeline.stream().anyMatch(e -> e.domain().equals("planner") && e.action().equals("discarded")
				&& e.correlation().get("generation") instanceof Number n && n.longValue() == backend.generation()));
			request.addProperty("phase", backend.phase().name());
			var messages = backend.conversation().messages();
			JsonObject observe = new JsonObject();
			for (var message : messages) {
				if (message.toolCallId() != null && message.toolCallId().startsWith("call_observe_")) {
					observe = JsonParser.parseString(message.content()).getAsJsonObject();
					observe.remove("current");
					observe.remove("worldSessionId");
				}
			}
			request.addProperty("owner", observe.has("decisionOwner") ? observe.get("decisionOwner").getAsString() : "controller");
			request.add("observe", observe);
			int start = 0;
			while (start < previousMessages.size() && start < messages.size()
				&& previousMessages.get(start).equals(messages.get(start))) start++;
			var delta = new JsonArray();
			for (var message : messages.subList(start, messages.size())) {
				if (message.role().equals("system") || PlannerObservation.isCallOnly(message)
					|| message.role().equals("assistant") && message.hasToolCalls()
					|| message.toolCallId() != null && message.toolCallId().startsWith("call_observe_")) continue;
				var entry = new JsonObject();
				entry.addProperty("role", message.role());
				entry.addProperty("kind", message.kind().name());
				entry.addProperty("text", message.content());
				delta.add(entry);
			}
			request.add("newMessages", delta);
			previousMessages = messages;
			String system = messages.stream().filter(m -> m.role().equals("system")).map(LlmChatMessage::content).reduce("", String::concat);
			request.add("prefix", JSON.toJsonTree(Map.of("systemSha", sha(system), "toolsSha", sha(JSON.toJson(normalize(JSON.toJsonTree(tools)))))));
			requests.add(request);
		}
		root.add("requests", requests);
		var audit = new JsonArray();
		for (var entry : timeline) {
			if (!entry.domain().equals("planner_wake")) continue;
			var item = JSON.toJsonTree(entry.payload()).getAsJsonObject();
			item.addProperty("tick", entry.tick());
			item.addProperty("kind", entry.action());
			audit.add(item);
		}
		root.add("wakeAudit", audit);
		var facts = new JsonArray();
		for (var event : events) facts.add(JSON.toJsonTree(Map.of("seqNo", event.seqNo(), "tick", event.tick(), "type", event.type(), "payload", event.payload())));
		root.add("events", facts);
		return new WakeTranscript(normalize(root).getAsJsonObject());
	}
	public String json() { return JSON.toJson(data) + "\n"; }
	public void assertMatchesGolden(String name) {
		Path path = Path.of("src/test/resources/planner/wakes", name + ".golden.json");
		try {
			if ("1".equals(System.getenv("AIRICRAFT_UPDATE_WAKE_GOLDENS"))) {
				Files.writeString(path, json());
				fail("golden updated: " + path);
			}
			assertTrue(Files.exists(path), "Missing golden: " + path);
			String expected = Files.readString(path);
			if (!expected.equals(json())) {
				Path actual = Files.createTempFile("wake-actual-", ".json");
				Files.writeString(actual, json());
				var diff = new ProcessBuilder("diff", "-u", path.toString(), actual.toString()).redirectErrorStream(true).start();
				String output = new String(diff.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				diff.waitFor();
				Files.delete(actual);
				fail(output);
			}
		} catch (Exception e) { throw new AssertionError(e); }
	}
	public static JsonElement normalize(JsonElement element) {
		if (element.isJsonObject()) {
			var result = new JsonObject();
			new TreeSet<>(element.getAsJsonObject().keySet()).forEach(key -> {
				if (key.toLowerCase(Locale.ROOT).contains("timestamp") || key.endsWith("AtMs")) result.addProperty(key, "<ms>");
				else result.add(key, normalize(element.getAsJsonObject().get(key)));
			});
			return result;
		}
		if (element.isJsonArray()) { var result = new JsonArray(); element.getAsJsonArray().forEach(e -> result.add(normalize(e))); return result; }
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			if (element.getAsString().startsWith("It is now around ")) return new JsonPrimitive("<time>");
			String text = element.getAsString();
			int jsonStart = text.indexOf('{'), jsonEnd = text.lastIndexOf('}');
			if (jsonStart >= 0 && jsonEnd > jsonStart) {
				try {
					var embedded = JsonParser.parseString(text.substring(jsonStart, jsonEnd + 1));
					text = text.substring(0, jsonStart) + new Gson().toJson(normalize(embedded)) + text.substring(jsonEnd + 1);
				} catch (JsonParseException ignored) { /* Prose can contain braces without JSON. */ }
			}
			return new JsonPrimitive(text.replaceAll("call_observe_\\d+", "call_observe_N")
				.replaceAll("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", "<uuid>")
				.replaceAll("@r\\d+", "@rN").replaceAll("\\b1[0-9]{12}\\b", "<ms>")
				.replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z", "<time>"));
		}
		return element.deepCopy();
	}
	public static String sha(String value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
		catch (Exception e) { throw new AssertionError(e); }
	}
}
