package ai.moeru.airicraft.agent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** Translates a planner turn to and from the Anthropic-compatible Messages wire format. */
final class AnthropicMessagesCodec {
	private static final Gson GSON = new Gson();
	private static final int MAX_OUTPUT_TOKENS = 8192;

	private AnthropicMessagesCodec() {
	}

	static String requestBody(String chatCompletionBody) {
		JsonObject chat = JsonParser.parseString(chatCompletionBody).getAsJsonObject();
		JsonObject request = new JsonObject();
		request.add("model", chat.get("model").deepCopy());
		request.addProperty("max_tokens", MAX_OUTPUT_TOKENS);
		request.addProperty("stream", false);
		StringBuilder system = new StringBuilder();
		JsonArray messages = new JsonArray();
		for (JsonElement element : chat.getAsJsonArray("messages")) {
			JsonObject message = element.getAsJsonObject();
			String role = message.get("role").getAsString();
			if ("system".equals(role)) {
				if (!system.isEmpty()) system.append("\n\n");
				system.append(message.get("content").getAsString());
				continue;
			}
			if (messages.isEmpty() && !"user".equals(role)) {
				append(messages, "user", textBlock("Begin."));
			}
			if ("tool".equals(role)) {
				JsonObject result = new JsonObject();
				result.addProperty("type", "tool_result");
				result.add("tool_use_id", message.get("tool_call_id").deepCopy());
				result.add("content", message.get("content").deepCopy());
				append(messages, "user", result);
				continue;
			}
			JsonArray blocks = contentBlocks(message.get("content"));
			if ("assistant".equals(role) && message.has("tool_calls")) {
				for (JsonElement callElement : message.getAsJsonArray("tool_calls")) {
					JsonObject call = callElement.getAsJsonObject();
					JsonObject function = call.getAsJsonObject("function");
					JsonObject use = new JsonObject();
					use.addProperty("type", "tool_use");
					use.add("id", call.get("id").deepCopy());
					use.add("name", function.get("name").deepCopy());
					use.add("input", JsonParser.parseString(function.get("arguments").getAsString()));
					blocks.add(use);
				}
			}
			if (!"assistant".equals(role) && !"user".equals(role)) {
				throw new JsonParseException("Unsupported conversation role: " + role);
			}
			for (JsonElement block : blocks) append(messages, role, block);
		}
		if (!system.isEmpty()) request.addProperty("system", system.toString());
		request.add("messages", messages);
		if (chat.has("tools")) {
			JsonArray tools = new JsonArray();
			for (JsonElement toolElement : chat.getAsJsonArray("tools")) {
				JsonObject function = toolElement.getAsJsonObject().getAsJsonObject("function");
				JsonObject tool = new JsonObject();
				tool.add("name", function.get("name").deepCopy());
				if (function.has("description")) tool.add("description", function.get("description").deepCopy());
				tool.add("input_schema", function.get("parameters").deepCopy());
				tools.add(tool);
			}
			request.add("tools", tools);
			JsonObject choice = new JsonObject();
			choice.addProperty("type", "auto");
			request.add("tool_choice", choice);
		}
		return GSON.toJson(request);
	}

	static String chatCompletionResponse(String messagesResponseBody) {
		JsonObject nativeResponse = JsonParser.parseString(messagesResponseBody).getAsJsonObject();
		JsonArray content = nativeResponse.getAsJsonArray("content");
		if (content == null) throw new JsonParseException("Missing Messages content");
		StringBuilder visibleText = new StringBuilder();
		JsonArray toolCalls = new JsonArray();
		for (JsonElement element : content) {
			JsonObject block = element.getAsJsonObject();
			switch (block.get("type").getAsString()) {
				case "text" -> visibleText.append(block.get("text").getAsString());
				case "tool_use" -> {
					JsonObject function = new JsonObject();
					function.add("name", block.get("name").deepCopy());
					function.addProperty("arguments", GSON.toJson(block.get("input")));
					JsonObject call = new JsonObject();
					call.add("id", block.get("id").deepCopy());
					call.addProperty("type", "function");
					call.add("function", function);
					toolCalls.add(call);
				}
				default -> { /* Thinking and signatures are not replayed as user-visible content. */ }
			}
		}
		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		if (visibleText.isEmpty()) message.add("content", JsonNull.INSTANCE);
		else message.addProperty("content", visibleText.toString());
		if (!toolCalls.isEmpty()) message.add("tool_calls", toolCalls);
		JsonObject choice = new JsonObject();
		choice.addProperty("index", 0);
		choice.add("message", message);
		choice.addProperty("finish_reason", toolCalls.isEmpty() ? "stop" : "tool_calls");
		JsonArray choices = new JsonArray();
		choices.add(choice);
		JsonObject response = new JsonObject();
		if (nativeResponse.has("id")) response.add("id", nativeResponse.get("id").deepCopy());
		response.addProperty("object", "chat.completion");
		if (nativeResponse.has("model")) response.add("model", nativeResponse.get("model").deepCopy());
		response.add("choices", choices);
		if (nativeResponse.has("usage")) response.add("usage", nativeResponse.get("usage").deepCopy());
		return GSON.toJson(response);
	}

	private static JsonArray contentBlocks(JsonElement content) {
		JsonArray blocks = new JsonArray();
		if (content == null || content.isJsonNull()) return blocks;
		if (content.isJsonPrimitive()) {
			String text = content.getAsString();
			if (!text.isEmpty()) blocks.add(textBlock(text));
			return blocks;
		}
		for (JsonElement element : content.getAsJsonArray()) {
			JsonObject part = element.getAsJsonObject();
			if ("text".equals(part.get("type").getAsString())) {
				blocks.add(part.deepCopy());
			} else if ("image_url".equals(part.get("type").getAsString())) {
				String url = part.getAsJsonObject("image_url").get("url").getAsString();
				JsonObject source = new JsonObject();
				if (url.startsWith("data:") && url.contains(";base64,")) {
					int separator = url.indexOf(";base64,");
					source.addProperty("type", "base64");
					source.addProperty("media_type", url.substring(5, separator));
					source.addProperty("data", url.substring(separator + 8));
				} else {
					source.addProperty("type", "url");
					source.addProperty("url", url);
				}
				JsonObject image = new JsonObject();
				image.addProperty("type", "image");
				image.add("source", source);
				blocks.add(image);
			} else {
				throw new JsonParseException("Unsupported message content part: " + part.get("type"));
			}
		}
		return blocks;
	}

	private static JsonObject textBlock(String text) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		return block;
	}

	private static void append(JsonArray messages, String role, JsonElement block) {
		JsonObject message;
		if (!messages.isEmpty() && role.equals(messages.get(messages.size() - 1).getAsJsonObject().get("role").getAsString())) {
			message = messages.get(messages.size() - 1).getAsJsonObject();
		} else {
			message = new JsonObject();
			message.addProperty("role", role);
			message.add("content", new JsonArray());
			messages.add(message);
		}
		message.getAsJsonArray("content").add(block.deepCopy());
	}
}
