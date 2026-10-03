package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.TreeMap;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** Assembles SSE deltas into the same message envelope used by non-streaming responses. */
final class OpenAiChatStream implements Flow.Subscriber<String> {
	private final Consumer<String> preview;
	private final StringBuilder frame = new StringBuilder();
	private final JsonObject root = new JsonObject();
	private final JsonObject message = new JsonObject();
	private final TreeMap<Integer, JsonObject> tools = new TreeMap<>();
	private String channel = "";
	private String finishReason;
	private boolean done;
	private int characters;
	private int payloadCharacters;

	OpenAiChatStream(Consumer<String> preview) {
		this.preview = preview;
		message.addProperty("role", "assistant");
	}

	@Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
	@Override public void onError(Throwable error) { }
	@Override public void onComplete() { }

	@Override public void onNext(String line) {
		characters += line.length();
		// Some providers repeat a status envelope on every token chunk, so raw size is only a coarse guard; the payload cap below is the real bound.
		if (characters > 64 * 1024 * 1024) throw new JsonParseException("Planner stream exceeds 64 MiB");
		if (line.isEmpty()) {
			if (!frame.isEmpty()) acceptFrame();
		} else if (line.startsWith("data:")) {
			if (!frame.isEmpty()) frame.append('\n');
			String data = line.substring(5);
			frame.append(data.startsWith(" ") ? data.substring(1) : data);
		}
	}

	private void acceptFrame() {
		String data = frame.toString();
		frame.setLength(0);
		if (data.equals("[DONE]")) { done = true; return; }
		if (done) return;
		JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
		if (chunk.has("error")) throw new JsonParseException("Provider stream error: " + chunk.get("error"));
		for (String key : new String[]{"id", "model", "created", "usage"}) {
			if (chunk.has(key) && !chunk.get(key).isJsonNull()) root.add(key, chunk.get(key));
		}
		if (!chunk.has("choices") || chunk.get("choices").isJsonNull()) return;
		for (var element : chunk.getAsJsonArray("choices")) {
			JsonObject choice = element.getAsJsonObject();
			if (choice.has("index") && choice.get("index").getAsInt() != 0) continue;
			if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) finishReason = choice.get("finish_reason").getAsString();
			if (!choice.has("delta") || choice.get("delta").isJsonNull()) continue;
			JsonObject delta = choice.getAsJsonObject("delta");
			countPayload(delta, "reasoning");
			append(message, delta, "reasoning_content", "Reasoning");
			append(message, delta, "content", "Response");
			if (!delta.has("tool_calls") || delta.get("tool_calls").isJsonNull()) continue;
			for (var toolElement : delta.getAsJsonArray("tool_calls")) {
				JsonObject part = toolElement.getAsJsonObject();
				int index = part.get("index").getAsInt();
				JsonObject tool = tools.computeIfAbsent(index, ignored -> new JsonObject());
				append(tool, part, "id", null);
				if (part.has("type")) tool.add("type", part.get("type"));
				if (part.has("function") && !part.get("function").isJsonNull()) {
					if (!tool.has("function")) tool.add("function", new JsonObject());
					append(tool.getAsJsonObject("function"), part.getAsJsonObject("function"), "name", "Tool " + index + " name");
					append(tool.getAsJsonObject("function"), part.getAsJsonObject("function"), "arguments", "Tool " + index + " arguments");
				}
			}
		}
	}

	private void countPayload(JsonObject delta, String key) {
		if (delta.has(key) && delta.get(key).isJsonPrimitive()) addPayload(delta.get(key).getAsString().length());
	}

	private void addPayload(int length) {
		payloadCharacters += length;
		if (payloadCharacters > 4 * 1024 * 1024) throw new JsonParseException("Planner stream exceeds 4 MiB");
	}

	private void append(JsonObject target, JsonObject delta, String key, String nextChannel) {
		if (!delta.has(key) || delta.get(key).isJsonNull()) return;
		String text = delta.get(key).getAsString();
		if (text.isEmpty()) return;
		addPayload(text.length());
		target.addProperty(key, (target.has(key) ? target.get(key).getAsString() : "") + text);
		if (nextChannel != null) {
			if (!channel.equals(nextChannel)) {
				preview.accept("\n[" + nextChannel + "]\n");
				channel = nextChannel;
			}
			preview.accept(text);
		}
	}

	String response() {
		if (!frame.isEmpty()) acceptFrame();
		if (finishReason == null) throw new JsonParseException("Planner stream ended without a finish reason");
		if (!tools.isEmpty()) {
			JsonArray calls = new JsonArray();
			tools.values().forEach(calls::add);
			message.add("tool_calls", calls);
		}
		JsonObject choice = new JsonObject();
		choice.addProperty("index", 0);
		choice.addProperty("finish_reason", finishReason);
		choice.add("message", message);
		JsonArray choices = new JsonArray();
		choices.add(choice);
		root.add("choices", choices);
		return root.toString();
	}
}
