package ai.moeru.airicraft.agent.llm.codex;

import ai.moeru.airicraft.agent.llm.LlmBackendException;
import ai.moeru.airicraft.agent.llm.LlmChatMessage;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.PlannerChatMessage;
import ai.moeru.airicraft.agent.llm.PlannerInputText;
import ai.moeru.airicraft.agent.llm.PlannerIntent;
import ai.moeru.airicraft.agent.llm.PlannerObservation;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class CodexPlannerResponseCodec {
	private static final Gson GSON = new Gson();
	private static final String ADAPTER_INSTRUCTIONS = """

		CODEX APP-SERVER ADAPTER CONTRACT:
		- This thread is inference-only. Do not call Codex shell, file, web, MCP, skill, or collaboration tools.
		- The phrase "call a tool" in the planner instructions means propose it in the final JSON toolCalls array. Airicraft validates and executes it.
		- Return exactly one final JSON object matching the supplied output schema.
		- For a normal reply, set toolCalls=[] and provide one or more chatMessages.
		- For tool use, set chatMessages=[] and provide the proposed Airicraft toolCalls.
		- Encode each proposed tool's arguments as a JSON object string in argumentsJson.
		- Each chatMessages entry must include delayTicks; use 0 for an immediate line.
		""";

	private final PlannerToolRegistry toolRegistry;

	CodexPlannerResponseCodec(PlannerToolRegistry toolRegistry) {
		this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
	}

	String developerInstructions(LlmConversation conversation) {
		String system = conversation.messages().stream()
			.filter(message -> "system".equals(message.role()))
			.map(LlmChatMessage::content)
			.findFirst()
			.orElse("You are the Airicraft Minecraft planner.");
		return system + ADAPTER_INSTRUCTIONS;
	}

	JsonObject turnAdditionalContext() {
		List<Map<String, Object>> tools = toolRegistry.openAiTools();
		String toolContract = tools.isEmpty()
			? "No Airicraft tools are available in this turn. toolCalls must be empty."
			: "CURRENT AIRICRAFT TOOL SCHEMAS FOR THIS TURN (these supersede schemas from earlier turns):\n"
				+ GSON.toJson(tools);
		JsonObject entry = new JsonObject();
		entry.addProperty("kind", "application");
		entry.addProperty("value", toolContract);
		JsonObject context = new JsonObject();
		context.add("airicraft_tool_schemas", entry);
		return context;
	}

	JsonArray turnInput(LlmConversation conversation) {
		JsonArray input = new JsonArray();
		var observeCallIds = PlannerObservation.callIds(conversation.messages());
		for (LlmChatMessage message : conversation.messages()) {
			if ("system".equals(message.role()) || PlannerObservation.isCallOnly(message)) {
				continue;
			}
			String rendered = "tool".equals(message.role()) && observeCallIds.contains(message.toolCallId())
				? renderObservation(message.content())
				: renderMessage(message);
			if (!rendered.isBlank()) {
				JsonObject text = new JsonObject();
				text.addProperty("type", "text");
				text.addProperty("text", rendered);
				input.add(text);
			}
			if (message.hasImageAttachment()) {
				JsonObject image = new JsonObject();
				image.addProperty("type", "image");
				image.addProperty(
					"url",
					"data:" + message.imageAttachment().mimeType() + ";base64,"
						+ Base64.getEncoder().encodeToString(message.imageAttachment().imageBytes())
				);
				image.addProperty("detail", message.imageAttachment().detail());
				input.add(image);
			}
		}
		if (input.isEmpty()) {
			JsonObject text = new JsonObject();
			text.addProperty("type", "text");
			text.addProperty("text", "Review the current Airicraft planner state and respond.");
			input.add(text);
		}
		return input;
	}

	JsonObject outputSchema() {
		JsonObject root = objectSchema();
		JsonObject properties = new JsonObject();
		properties.add("chatMessages", arraySchema(chatMessageSchema(), 0, 4));
		int maximumToolCalls = toolRegistry.openAiTools().isEmpty() ? 0 : 4;
		properties.add("toolCalls", arraySchema(toolCallSchema(), 0, maximumToolCalls));
		root.add("properties", properties);
		root.add("required", strings("chatMessages", "toolCalls"));
		return root;
	}

	PlannerResponse parse(String rawResponse, long generation) throws LlmBackendException {
		try {
			JsonObject root = JsonParser.parseString(stripCodeFence(rawResponse)).getAsJsonObject();
			List<PlannerChatMessage> chatMessages = parseChatMessages(root.getAsJsonArray("chatMessages"));
			List<PlannerToolCall> toolCalls = parseToolCalls(root.getAsJsonArray("toolCalls"), generation);
			if (!chatMessages.isEmpty() && !toolCalls.isEmpty()) {
				throw new JsonParseException("Codex response cannot contain both chatMessages and toolCalls");
			}
			if (chatMessages.isEmpty() && toolCalls.isEmpty()) {
				throw new JsonParseException("Codex response must contain a reply or tool call");
			}
			if (!toolCalls.isEmpty()) {
				return PlannerResponse.toolCalls(toolCalls, root);
			}
			return new PlannerResponse(chatMessages, new PlannerIntent("reply_only", null, null), root);
		}
		catch (IllegalStateException | JsonParseException exception) {
			throw new LlmBackendException(LlmFailureType.PARSE_ERROR, "Failed to parse Codex planner response: " + exception.getMessage(), exception);
		}
	}

	private List<PlannerChatMessage> parseChatMessages(JsonArray array) {
		if (array == null || array.isEmpty()) {
			return List.of();
		}
		ArrayList<PlannerChatMessage> messages = new ArrayList<>();
		for (JsonElement element : array) {
			if (!element.isJsonObject()) {
				throw new JsonParseException("chatMessages entries must be objects");
			}
			JsonObject object = element.getAsJsonObject();
			String text = requiredString(object, "text");
			int delayTicks = object.has("delayTicks") ? Math.max(0, object.get("delayTicks").getAsInt()) : 0;
			messages.add(new PlannerChatMessage(text, delayTicks));
		}
		return List.copyOf(messages);
	}

	private List<PlannerToolCall> parseToolCalls(JsonArray array, long generation) {
		if (array == null || array.isEmpty()) {
			return List.of();
		}
		ArrayList<PlannerToolCall> calls = new ArrayList<>();
		for (int index = 0; index < array.size(); index++) {
			JsonObject proposal = array.get(index).getAsJsonObject();
			String name = requiredString(proposal, "name");
			try {
				String argumentsJson = requiredString(proposal, "argumentsJson");
				JsonObject function = new JsonObject();
				function.addProperty("name", name);
				function.addProperty("arguments", argumentsJson);
				JsonObject openAiShape = new JsonObject();
				openAiShape.addProperty("id", "codex-" + generation + "-" + (index + 1));
				openAiShape.addProperty("type", "function");
				openAiShape.add("function", function);
				calls.add(PlannerToolCatalog.parseToolCall(openAiShape, toolRegistry));
			}
			catch (IllegalStateException | JsonParseException exception) {
				throw new JsonParseException("Invalid " + name + " tool arguments: " + exception.getMessage(), exception);
			}
		}
		return List.copyOf(calls);
	}

	private JsonObject toolCallSchema() {
		JsonArray names = new JsonArray();
		for (Map<String, Object> tool : toolRegistry.openAiTools()) {
			Object functionValue = tool.get("function");
			if (!(functionValue instanceof Map<?, ?> function)) {
				continue;
			}
			Object nameValue = function.get("name");
			if (nameValue instanceof String name) {
				names.add(name);
			}
		}
		JsonObject schema = objectSchema();
		JsonObject properties = new JsonObject();
		JsonObject name = new JsonObject();
		name.addProperty("type", "string");
		if (!names.isEmpty()) {
			name.add("enum", names);
		}
		JsonObject argumentsJson = new JsonObject();
		argumentsJson.addProperty("type", "string");
		properties.add("name", name);
		properties.add("argumentsJson", argumentsJson);
		schema.add("properties", properties);
		schema.add("required", strings("name", "argumentsJson"));
		return schema;
	}

	private static JsonObject chatMessageSchema() {
		JsonObject schema = objectSchema();
		JsonObject properties = new JsonObject();
		JsonObject text = new JsonObject();
		text.addProperty("type", "string");
		JsonObject delay = new JsonObject();
		delay.addProperty("type", "integer");
		delay.addProperty("minimum", 0);
		properties.add("text", text);
		properties.add("delayTicks", delay);
		schema.add("properties", properties);
		schema.add("required", strings("text", "delayTicks"));
		return schema;
	}

	private static JsonObject arraySchema(JsonObject itemSchema, int minimum, int maximum) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "array");
		schema.add("items", itemSchema);
		schema.addProperty("minItems", minimum);
		schema.addProperty("maxItems", maximum);
		return schema;
	}

	private static JsonObject objectSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.addProperty("additionalProperties", false);
		return schema;
	}

	private static JsonArray strings(String... values) {
		JsonArray array = new JsonArray();
		for (String value : values) {
			array.add(value);
		}
		return array;
	}

	private static String requiredString(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			throw new JsonParseException("Missing " + key);
		}
		String value = object.get(key).getAsString();
		if (value == null || value.isBlank()) {
			throw new JsonParseException(key + " must not be blank");
		}
		return value;
	}

	private static String renderObservation(String content) {
		String rendered;
		try {
			rendered = PlannerInputText.observation(JsonParser.parseString(content).getAsJsonObject());
		}
		catch (JsonParseException | IllegalStateException exception) {
			rendered = content;
		}
		return PlannerInputText.message("presentation", ("AIRICRAFT OBSERVATION: " + rendered).strip());
	}

	private static String renderMessage(LlmChatMessage message) {
		String prefix = switch (message.role()) {
			case "assistant" -> "PRIOR PLANNER OUTPUT";
			case "tool" -> "AIRICRAFT TOOL RESULT";
			default -> "AIRICRAFT INPUT";
		};
		StringBuilder rendered = new StringBuilder(prefix).append(": ").append(message.content());
		if (message.hasToolCalls()) {
			rendered.append("\nPROPOSED AIRICRAFT TOOL CALLS: ").append(PlannerToolCatalog.toOpenAiToolCalls(message.toolCalls()));
		}
		return PlannerInputText.message("presentation", rendered.toString().strip());
	}

	private static String stripCodeFence(String value) {
		String text = value == null ? "" : value.strip();
		if (!text.startsWith("```")) {
			return text;
		}
		int newline = text.indexOf('\n');
		if (newline >= 0) {
			text = text.substring(newline + 1);
		}
		if (text.endsWith("```")) {
			text = text.substring(0, text.length() - 3);
		}
		return text.strip();
	}
}
