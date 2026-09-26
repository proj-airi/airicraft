package ai.moeru.airicraft.agent.observability;

import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.llm.LlmChatMessage;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleMessageContent;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolRequest;
import ai.moeru.airicraft.agent.llm.CompactionCheckpoint;
import ai.moeru.airicraft.agent.llm.VisionDescription;
import ai.moeru.airicraft.agent.llm.VisionRequest;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class TraceSanitizer {
	private static final Gson TRACE_GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final int LOG_LIMIT = 512;
	private static final int TRACE_LIMIT = 16_384;
	private static final int TRACE_TEXT_LIMIT = 4_096;
	private static final int TRACE_IMAGE_MAX_WIDTH = 320;
	private static final int TRACE_IMAGE_MAX_HEIGHT = 320;
	private static final int MAX_LOG_CONVERSATION_MESSAGES = 4;
	private static final int MAX_TRACE_CONVERSATION_MESSAGES = 64;
	private static final Pattern DATA_URL_PATTERN = Pattern.compile("data:[^;]+;base64,[A-Za-z0-9+/=]+");
	private static final Pattern BEARER_PATTERN = Pattern.compile("Bearer\\s+[^\\s]+");

	private TraceSanitizer() {
	}

	public static String summarizeForLog(String text) {
		return normalize(text, LOG_LIMIT);
	}

	public static String summarizeConversationForLog(LlmConversation conversation) {
		return sanitizeConversation(conversation, false, LOG_LIMIT, MAX_LOG_CONVERSATION_MESSAGES);
	}

	public static String summarizeChatResponseForLog(String responseBody) {
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			String model = getString(root, "model").orElse("");
			Optional<JsonObject> payload = extractMessagePayload(root);
			if (payload.isPresent()) {
				JsonObject object = payload.get();
				String replyText = getString(object, "replyText").orElse("");
				String intentType = getNestedString(object, "intent", "type").orElse("none");
				String toolType = getNestedString(object, "toolRequest", "type").orElse("");
				return summarizeForLog(
					"model=" + model
						+ " reply=" + replyText
						+ " intentType=" + intentType
							+ (toolType.isBlank() ? "" : " toolRequestType=" + toolType)
					);
			}
			String content = extractMessageContent(root).orElse("");
			return summarizeForLog("model=" + model + " text=" + content);
		}
		catch (IllegalStateException | JsonParseException exception) {
			return summarizeForLog("unparseable_response length=" + safeLength(responseBody));
		}
	}

	public static String summarizeVisionResponseForLog(String responseBody) {
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			String model = getString(root, "model").orElse("");
			String content = extractMessageContent(root).orElse("");
			return summarizeForLog("model=" + model + " text=" + content);
		}
		catch (IllegalStateException | JsonParseException exception) {
			return summarizeForLog("unparseable_response length=" + safeLength(responseBody));
		}
	}

	public static String sanitizeConversationForTrace(LlmConversation conversation, boolean captureImages) {
		return sanitizeConversation(conversation, captureImages, TRACE_LIMIT, MAX_TRACE_CONVERSATION_MESSAGES);
	}

	public static String sanitizeRequestPayloadForTrace(String requestBody) {
		return sanitizeRequestPayloadForTrace(requestBody, false);
	}

	public static String sanitizeRequestPayloadForTrace(String requestBody, boolean captureImages) {
		if (requestBody == null || requestBody.isBlank()) {
			return "";
		}
		try {
			return TRACE_GSON.toJson(sanitizeJsonElement(JsonParser.parseString(requestBody), captureImages));
		}
		catch (IllegalStateException | JsonParseException exception) {
			return sanitizeTraceText(requestBody, TRACE_LIMIT, captureImages);
		}
	}

	public static String sanitizePromptFromRequestPayload(String requestBody) {
		return sanitizePromptFromRequestPayload(requestBody, false);
	}

	public static String sanitizePromptFromRequestPayload(String requestBody, boolean captureImages) {
		Optional<JsonObject> root = tryParseJsonObject(requestBody);
		if (root.isEmpty()) {
			return "";
		}
		JsonArray prompt = new JsonArray();
		for (JsonElement messageElement : getMessages(root.get())) {
			if (!messageElement.isJsonObject()) {
				continue;
			}
			prompt.add(sanitizePromptMessageObject(messageElement.getAsJsonObject(), captureImages));
		}
		return TRACE_GSON.toJson(prompt);
	}

	public static String sanitizeSystemFromRequestPayload(String requestBody) {
		Optional<JsonObject> root = tryParseJsonObject(requestBody);
		if (root.isEmpty()) {
			return "";
		}
		StringBuilder systemPrompt = new StringBuilder();
		for (JsonElement messageElement : getMessages(root.get())) {
			if (!messageElement.isJsonObject()) {
				continue;
			}
			JsonObject message = messageElement.getAsJsonObject();
			String role = getString(message, "role").orElse("");
			if (!"system".equalsIgnoreCase(role)) {
				continue;
			}
			String content = sanitizeMessageContentForSystem(message.get("content"));
			if (content.isBlank()) {
				continue;
			}
			if (!systemPrompt.isEmpty()) {
				systemPrompt.append("\n\n");
			}
			systemPrompt.append(content);
		}
		return systemPrompt.toString();
	}

	public static String sanitizeConversationPromptForGenAi(LlmConversation conversation, boolean captureImages) {
		Objects.requireNonNull(conversation, "conversation");
		JsonArray prompt = new JsonArray();
		for (LlmChatMessage message : conversation.messages()) {
			prompt.add(chatMessage(message.role(), sanitizeConversationMessageContent(message, captureImages)));
		}
		return TRACE_GSON.toJson(prompt);
	}

	public static String sanitizeConversationSystemPrompt(LlmConversation conversation) {
		Objects.requireNonNull(conversation, "conversation");
		StringBuilder systemPrompt = new StringBuilder();
		for (LlmChatMessage message : conversation.messages()) {
			if (!"system".equalsIgnoreCase(message.role())) {
				continue;
			}
			String content = sanitizeTraceText(message.content(), TRACE_TEXT_LIMIT);
			if (content.isBlank()) {
				continue;
			}
			if (!systemPrompt.isEmpty()) {
				systemPrompt.append("\n\n");
			}
			systemPrompt.append(content);
		}
		return systemPrompt.toString();
	}

	public static String sanitizeVisionRequestForTrace(VisionRequest request, String imageDetail) {
		Objects.requireNonNull(request, "request");
		JsonObject root = new JsonObject();
		root.addProperty("prompt", sanitizeTraceText(request.prompt(), TRACE_TEXT_LIMIT));
		root.addProperty("capturedAtMs", request.capturedAtMs());
		JsonObject image = new JsonObject();
		image.addProperty("mimeType", sanitizeTraceText(request.mimeType(), 64));
		image.addProperty("detail", sanitizeTraceText(imageDetail, 32));
		image.addProperty("bytes", "redacted");
		root.add("image", image);
		return TRACE_GSON.toJson(root);
	}

	public static String sanitizePlannerResponseForTrace(PlannerResponse response) {
		if (response == null) {
			return "";
		}
		JsonObject root = new JsonObject();
		root.addProperty("role", "assistant");
		root.addProperty("content", sanitizeTraceText(response.replyText(), TRACE_TEXT_LIMIT));
		if (response.intent() != null) {
			JsonObject intent = new JsonObject();
			intent.addProperty("type", sanitizeTraceText(response.intent().type(), 64));
			if (response.intent().goalType() != null) {
				intent.addProperty("goalType", response.intent().goalType().name());
			}
			if (response.intent().targetPlayer() != null) {
				intent.addProperty("targetPlayer", sanitizeTraceText(response.intent().targetPlayer(), 64));
			}
			root.add("intent", intent);
		}
		PlannerToolRequest toolRequest = response.toolRequest();
		if (toolRequest != null && toolRequest.type() != null) {
			JsonObject tool = new JsonObject();
			tool.addProperty("type", sanitizeTraceText(toolRequest.type(), 64));
			if (toolRequest.prompt() != null && !toolRequest.prompt().isBlank()) {
				tool.addProperty("prompt", sanitizeTraceText(toolRequest.prompt(), TRACE_TEXT_LIMIT));
			}
			root.add("toolRequest", tool);
		}
		PlannerToolCall toolCall = response.toolCall();
		if (toolCall != null && toolCall.name() != null) {
			JsonObject tool = new JsonObject();
			tool.addProperty("name", sanitizeTraceText(toolCall.name(), 64));
			addSaidText(tool, "said", toolCall);
			root.add("toolCall", tool);
		}
		if (response.toolCalls() != null && response.toolCalls().size() > 1) {
			JsonArray toolCalls = new JsonArray();
			for (PlannerToolCall call : response.toolCalls()) {
				if (call == null || call.name() == null) {
					continue;
				}
				JsonObject tool = new JsonObject();
				tool.addProperty("name", sanitizeTraceText(call.name(), 64));
				addSaidText(tool, "said", call);
				toolCalls.add(tool);
			}
			root.add("toolCalls", toolCalls);
		}
		return TRACE_GSON.toJson(root);
	}

	/** A say call's text is visible chat, the only player-facing text a tool call carries. */
	private static void addSaidText(JsonObject target, String property, PlannerToolCall call) {
		if (call == null || !ai.moeru.airicraft.agent.llm.SayToolProvider.SAY.equals(call.name())) return;
		var text = call.arguments().get("text");
		if (text != null && text.isJsonPrimitive() && text.getAsJsonPrimitive().isString() && !text.getAsString().isBlank())
			target.addProperty(property, sanitizeTraceText(text.getAsString(), TRACE_TEXT_LIMIT));
	}

	public static String sanitizePlannerCompletionForGenAi(PlannerResponse response) {
		if (response == null) {
			return "";
		}
		if (response.toolCalls() != null && response.toolCalls().size() > 1) {
			JsonObject root = new JsonObject();
			JsonArray toolCalls = new JsonArray();
			for (PlannerToolCall toolCall : response.toolCalls()) {
				if (toolCall == null || toolCall.name() == null) {
					continue;
				}
				JsonObject tool = new JsonObject();
				tool.addProperty("name", sanitizeTraceText(toolCall.name(), 64));
				addSaidText(tool, "said", toolCall);
				toolCalls.add(tool);
			}
			root.add("tool_calls", toolCalls);
			return TRACE_GSON.toJson(root);
		}
		if (response.toolCall() != null) {
			JsonObject root = new JsonObject();
			root.addProperty("tool_call", sanitizeTraceText(response.toolCall().name(), 64));
			addSaidText(root, "said", response.toolCall());
			return TRACE_GSON.toJson(root);
		}
		if (response.replyText() == null) {
			return "";
		}
		return sanitizeTraceText(response.replyText(), TRACE_TEXT_LIMIT);
	}

	public static String sanitizeChatResponseForTrace(String responseBody) {
		if (responseBody == null || responseBody.isBlank()) {
			return "";
		}
		try {
			return TRACE_GSON.toJson(sanitizeJsonElement(JsonParser.parseString(responseBody)));
		}
		catch (IllegalStateException | JsonParseException exception) {
			return sanitizeTraceText(responseBody, TRACE_LIMIT);
		}
	}

	public static String sanitizeChatCompletionForGenAi(String responseBody) {
		Optional<JsonObject> root = tryParseJsonObject(responseBody);
		if (root.isEmpty()) {
			return "";
		}
		if (root.get().has("choices")) {
			JsonArray choices = root.get().getAsJsonArray("choices");
			if (!choices.isEmpty() && choices.get(0).isJsonObject()) {
				JsonObject choice = choices.get(0).getAsJsonObject();
				JsonObject message = choice.getAsJsonObject("message");
				if (message != null) {
					return TRACE_GSON.toJson(sanitizeMessageObject(message, false));
				}
			}
		}
		return "";
	}

	public static String sanitizeVisionDescriptionForTrace(VisionDescription description) {
		if (description == null) {
			return "";
		}
		JsonObject root = new JsonObject();
		root.addProperty("role", "assistant");
		root.addProperty("content", sanitizeTraceText(description.text(), TRACE_TEXT_LIMIT));
		root.addProperty("model", sanitizeTraceText(description.model(), 128));
		root.addProperty("capturedAtMs", description.capturedAtMs());
		return TRACE_GSON.toJson(root);
	}

	public static String sanitizeVisionCompletionForGenAi(VisionDescription description) {
		if (description == null || description.text() == null) {
			return "";
		}
		return sanitizeTraceText(description.text(), TRACE_TEXT_LIMIT);
	}

	public static String sanitizeCheckpointForTrace(CompactionCheckpoint checkpoint) {
		if (checkpoint == null) {
			return "";
		}
		JsonObject root = new JsonObject();
		root.addProperty("timeAnchor", sanitizeTraceText(checkpoint.timeAnchor(), 256));
		root.addProperty("sessionState", sanitizeTraceText(checkpoint.sessionState(), 256));
		root.addProperty("activeGoal", sanitizeTraceText(checkpoint.activeGoal(), 256));
		root.add("activeCommitments", sanitizeStringArray(checkpoint.activeCommitments(), 256));
		root.add("durableFacts", sanitizeStringArray(checkpoint.durableFacts(), 256));
		root.add("relevantPeople", sanitizeStringArray(checkpoint.relevantPeople(), 256));
		root.add("openLoops", sanitizeStringArray(checkpoint.openLoops(), 256));
		root.add("recentTimeline", sanitizeStringArray(checkpoint.recentTimeline(), 256));
		root.add("forgettableNoise", sanitizeStringArray(checkpoint.forgettableNoise(), 256));
		return TRACE_GSON.toJson(root);
	}

	public static String sanitizeCheckpointCompletionForGenAi(CompactionCheckpoint checkpoint) {
		return sanitizeCheckpointForTrace(checkpoint);
	}

	public static String sanitizeImageDetailSummary(LlmConversation conversation) {
		return conversation.messages().stream()
			.filter(LlmChatMessage::hasImageAttachment)
			.map(message -> message.imageAttachment().detail())
			.filter(detail -> detail != null && !detail.isBlank())
			.distinct()
			.reduce((left, right) -> left + "," + right)
			.orElse("none");
	}

	public static long conversationPromptLength(LlmConversation conversation) {
		return conversation.messages().stream()
			.map(LlmChatMessage::content)
			.filter(Objects::nonNull)
			.mapToLong(String::length)
			.sum();
	}

	public static String inferProviderName(String baseUrl) {
		String normalized = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
		if (normalized.contains("anthropic")) {
			return "anthropic";
		}
		if (normalized.contains("google") || normalized.contains("gemini")) {
			return "google";
		}
		return "openai";
	}

	public static Optional<String> responseModel(String responseBody) {
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			return getString(root, "model");
		}
		catch (IllegalStateException | JsonParseException exception) {
			return Optional.empty();
		}
	}

	public static String host(URI endpoint) {
		if (endpoint == null || endpoint.getHost() == null || endpoint.getHost().isBlank()) {
			return "";
		}
		return endpoint.getHost();
	}

	public static long port(URI endpoint) {
		if (endpoint == null) {
			return -1L;
		}
		return endpoint.getPort() >= 0 ? endpoint.getPort() : defaultPort(endpoint);
	}

	public static String normalizedUrl(URI endpoint) {
		return endpoint == null ? "" : endpoint.toString();
	}

	public static String safeFailureMessage(String message) {
		return normalize(message, 256);
	}

	public static String safeToolCode(String code) {
		return normalize(code, 64);
	}

	public static String sanitizedMimeType(FirstPersonScreenshotService.CapturedScreenshot capture) {
		return capture == null ? "" : "image/" + capture.format().toLowerCase(Locale.ROOT);
	}

	public static String imageCaptureDataUrl(FirstPersonScreenshotService.CapturedScreenshot capture) {
		if (capture == null || capture.imageBytes().length == 0) {
			return "";
		}
		String mimeType = sanitizedMimeType(capture);
		if (mimeType.isBlank()) {
			return "";
		}
		return "data:%s;base64,%s".formatted(
			mimeType,
			Base64.getEncoder().encodeToString(capture.imageBytes())
		);
	}

	public static String imageCapturePayloadForTrace(FirstPersonScreenshotService.CapturedScreenshot capture) {
		if (capture == null) {
			return "";
		}
		JsonObject root = new JsonObject();
		root.addProperty("type", "image_capture");
		root.addProperty("mimeType", sanitizedMimeType(capture));
		root.addProperty("width", capture.width());
		root.addProperty("height", capture.height());
		root.addProperty("sourceWidth", capture.sourceWidth());
		root.addProperty("sourceHeight", capture.sourceHeight());
		root.addProperty("capturedAtMs", capture.capturedAtMs());
		root.addProperty("imageIncludedInCompletion", capture.imageBytes().length > 0);
		return TRACE_GSON.toJson(root);
	}

	public static String imageCaptureCompletionForGenAi(FirstPersonScreenshotService.CapturedScreenshot capture) {
		if (capture == null) {
			return "";
		}
		String imageDataUrl = imageCaptureDisplayDataUrl(capture);
		if (imageDataUrl.isBlank()) {
			return "";
		}
		JsonObject imageUrl = new JsonObject();
		imageUrl.addProperty("url", imageDataUrl);
		imageUrl.addProperty("detail", "auto");

		JsonObject content = new JsonObject();
		content.addProperty("type", "image_url");
		content.add("image_url", imageUrl);

		JsonArray contents = new JsonArray();
		contents.add(content);

		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		message.add("content", contents);

		JsonArray completion = new JsonArray();
		completion.add(message);
		return TRACE_GSON.toJson(completion);
	}

	public static String imageCaptureDisplayDataUrl(FirstPersonScreenshotService.CapturedScreenshot capture) {
		return displayImageDataUrl(imageCaptureDataUrl(capture));
	}

	private static String sanitizeConversation(LlmConversation conversation, boolean captureImages, int maxLength, int maxMessages) {
		Objects.requireNonNull(conversation, "conversation");
		List<LlmChatMessage> messages = conversation.messages();
		int safeMaxMessages = Math.max(1, maxMessages);
		int skipCount = Math.max(0, messages.size() - safeMaxMessages);
		List<LlmChatMessage> visible = messages.subList(skipCount, messages.size());
		JsonArray parts = new JsonArray();
		if (skipCount > 0) {
			parts.add(chatMessage("system", skipCount + " earlier message(s) omitted for trace size"));
		}
		for (LlmChatMessage message : visible) {
			parts.add(chatMessage(message.role(), sanitizeConversationMessageContent(message, captureImages)));
		}
		String payload = TRACE_GSON.toJson(parts);
		if (payload.length() <= maxLength) {
			return payload;
		}
		return TRACE_GSON.toJson(List.of(
			chatMessage("system", "Conversation truncated for trace size"),
			chatMessage(
				visible.getLast().role(),
				sanitizeConversationMessageContent(visible.getLast(), captureImages)
			)
		));
	}

	private static JsonArray sanitizeStringArray(List<String> values, int itemLimit) {
		JsonArray sanitized = new JsonArray();
		if (values == null || values.isEmpty()) {
			return sanitized;
		}
		for (String value : values) {
			if (value == null || value.isBlank()) {
				continue;
			}
			sanitized.add(sanitizeTraceText(value, itemLimit));
		}
		return sanitized;
	}

	private static int defaultPort(URI endpoint) {
		if (endpoint == null || endpoint.getScheme() == null) {
			return -1;
		}
		return "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
	}

	private static int safeLength(String value) {
		return value == null ? 0 : value.length();
	}

	private static Optional<String> extractMessageContent(JsonObject root) {
		if (root == null || !root.has("choices") || !root.get("choices").isJsonArray() || root.getAsJsonArray("choices").isEmpty()) {
			return Optional.empty();
		}
			JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
			JsonObject message = choice.getAsJsonObject("message");
			if (message == null || !message.has("content")) {
				return Optional.empty();
			}
			return Optional.ofNullable(OpenAiCompatibleMessageContent.extractVisibleText(message.get("content")));
		}

	private static Optional<JsonObject> extractMessagePayload(JsonObject root) {
		if (root == null || !root.has("choices") || !root.get("choices").isJsonArray() || root.getAsJsonArray("choices").isEmpty()) {
			return Optional.empty();
		}
		JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
		JsonObject message = choice.getAsJsonObject("message");
		if (message == null || !message.has("content")) {
			return Optional.empty();
		}
		return OpenAiCompatibleMessageContent.extractJsonObject(message.get("content"));
	}

	private static Optional<JsonObject> tryParseJsonObject(String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(JsonParser.parseString(text).getAsJsonObject());
		}
		catch (IllegalStateException | JsonParseException exception) {
			return Optional.empty();
		}
	}

	private static List<JsonElement> getMessages(JsonObject root) {
		if (root == null || !root.has("messages") || !root.get("messages").isJsonArray()) {
			return List.of();
		}
		List<JsonElement> messages = new ArrayList<>();
		for (JsonElement element : root.getAsJsonArray("messages")) {
			messages.add(element);
		}
		return messages;
	}

	private static Optional<String> getString(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return Optional.empty();
		}
		String value = object.get(key).getAsString();
		return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
	}

	private static Optional<String> getNestedString(JsonObject object, String parentKey, String childKey) {
		if (object == null || !object.has(parentKey) || !object.get(parentKey).isJsonObject()) {
			return Optional.empty();
		}
		return getString(object.getAsJsonObject(parentKey), childKey);
	}

	private static JsonObject chatMessage(String role, String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", sanitizeTraceText(role, 32));
		message.addProperty("content", content == null ? "" : content);
		return message;
	}

	private static JsonObject sanitizeMessageObject(JsonObject message, boolean captureImages) {
		JsonObject sanitized = new JsonObject();
		if (message == null) {
			return sanitized;
		}
		String role = getString(message, "role").orElse("");
		if (!role.isBlank()) {
			sanitized.addProperty("role", sanitizeTraceText(role, 32));
		}
		if (message.has("content")) {
			sanitized.add("content", sanitizeJsonElement(message.get("content"), captureImages));
		}
		for (var entry : message.entrySet()) {
			if ("role".equals(entry.getKey()) || "content".equals(entry.getKey())) {
				continue;
			}
			sanitized.add(entry.getKey(), sanitizeJsonElement(entry.getValue(), captureImages));
		}
		return sanitized;
	}

	private static JsonObject sanitizePromptMessageObject(JsonObject message, boolean captureImages) {
		JsonObject sanitized = new JsonObject();
		if (message == null) {
			return sanitized;
		}
		String role = getString(message, "role").orElse("");
		if (!role.isBlank()) {
			sanitized.addProperty("role", sanitizeTraceText(role, 32));
		}
		if (message.has("content")) {
			sanitized.add("content", sanitizePromptContent(message.get("content"), captureImages));
		}
		for (var entry : message.entrySet()) {
			if ("role".equals(entry.getKey()) || "content".equals(entry.getKey())) {
				continue;
			}
			sanitized.add(entry.getKey(), sanitizeJsonElement(entry.getValue(), captureImages));
		}
		return sanitized;
	}

	private static String sanitizeConversationMessageContent(LlmChatMessage message, boolean captureImages) {
		StringBuilder content = new StringBuilder(sanitizeTraceText(message.content(), TRACE_TEXT_LIMIT));
		if (message.hasImageAttachment()) {
			if (!content.isEmpty()) {
				content.append('\n');
			}
			content.append("[image attachment");
			content.append(" mimeType=");
			content.append(captureImages
				? sanitizeTraceText(message.imageAttachment().mimeType(), 64)
				: "redacted");
			content.append(" detail=");
			content.append(sanitizeTraceText(message.imageAttachment().detail(), 32));
			content.append(']');
		}
		return content.toString();
	}

	private static String sanitizeTraceText(String text, int maxLength) {
		return sanitizeTraceText(text, maxLength, false);
	}

	private static String sanitizeTraceText(String text, int maxLength, boolean preserveImages) {
		if (text == null) {
			return "";
		}
		String sanitized = preserveImages ? text : DATA_URL_PATTERN.matcher(text).replaceAll("[image-data-redacted]");
		sanitized = BEARER_PATTERN.matcher(sanitized).replaceAll("Bearer [redacted]");
		if (!preserveImages && sanitized.length() > maxLength) {
			return sanitized.substring(0, maxLength) + "...";
		}
		return sanitized;
	}

	private static JsonElement sanitizeJsonElement(JsonElement element) {
		return sanitizeJsonElement(element, false);
	}

	private static JsonElement sanitizeJsonElement(JsonElement element, boolean captureImages) {
		if (element == null || element.isJsonNull()) {
			return JsonNull.INSTANCE;
		}
		if (element.isJsonArray()) {
			JsonArray sanitized = new JsonArray();
			for (JsonElement child : element.getAsJsonArray()) {
				sanitized.add(sanitizeJsonElement(child, captureImages));
			}
			return sanitized;
		}
		if (element.isJsonObject()) {
			JsonObject sanitized = new JsonObject();
			for (var entry : element.getAsJsonObject().entrySet()) {
				sanitized.add(entry.getKey(), sanitizeJsonElement(entry.getValue(), captureImages));
			}
			return sanitized;
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (primitive.isString()) {
			return new JsonPrimitive(sanitizeTraceText(primitive.getAsString(), TRACE_TEXT_LIMIT, captureImages));
		}
		if (primitive.isBoolean()) {
			return new JsonPrimitive(primitive.getAsBoolean());
		}
		if (primitive.isNumber()) {
			return new JsonPrimitive(primitive.getAsNumber());
		}
		return new JsonPrimitive(primitive.getAsString());
	}

	private static JsonElement sanitizePromptContent(JsonElement content, boolean captureImages) {
		if (content == null || content.isJsonNull()) {
			return JsonNull.INSTANCE;
		}
		if (content.isJsonArray()) {
			JsonArray sanitized = new JsonArray();
			for (JsonElement child : content.getAsJsonArray()) {
				sanitized.add(sanitizePromptContentPart(child, captureImages));
			}
			return sanitized;
		}
		return sanitizeJsonElement(content, captureImages);
	}

	private static JsonElement sanitizePromptContentPart(JsonElement element, boolean captureImages) {
		if (element == null || element.isJsonNull() || !element.isJsonObject()) {
			return sanitizeJsonElement(element, captureImages);
		}
		JsonObject object = element.getAsJsonObject();
		String type = getString(object, "type").orElse("").toLowerCase(Locale.ROOT);
		if ("image_url".equals(type) || "input_image".equals(type)) {
			return sanitizePromptImageContent(object, captureImages);
		}
		return sanitizeJsonElement(element, captureImages);
	}

	private static JsonObject sanitizePromptImageContent(JsonObject content, boolean captureImages) {
		JsonObject sanitized = new JsonObject();
		sanitized.addProperty("type", "input_image");
		if (content.has("image_url")) {
			sanitized.add("image_url", flattenImageUrlValue(content.get("image_url"), captureImages));
			extractImageDetail(content.get("image_url"))
				.ifPresent(detail -> sanitized.addProperty("detail", sanitizeTraceText(detail, 32)));
		}
		for (var entry : content.entrySet()) {
			if ("type".equals(entry.getKey()) || "image_url".equals(entry.getKey())) {
				continue;
			}
			sanitized.add(entry.getKey(), sanitizeJsonElement(entry.getValue(), captureImages));
		}
		return sanitized;
	}

	private static JsonElement flattenImageUrlValue(JsonElement imageUrl, boolean captureImages) {
		if (imageUrl == null || imageUrl.isJsonNull()) {
			return JsonNull.INSTANCE;
		}
		if (imageUrl.isJsonObject() && imageUrl.getAsJsonObject().has("url")) {
			return sanitizePromptImageUrl(imageUrl.getAsJsonObject().get("url"), captureImages);
		}
		return sanitizePromptImageUrl(imageUrl, captureImages);
	}

	private static Optional<String> extractImageDetail(JsonElement imageUrl) {
		if (imageUrl == null || !imageUrl.isJsonObject()) {
			return Optional.empty();
		}
		return getString(imageUrl.getAsJsonObject(), "detail");
	}

	private static String sanitizeMessageContentForSystem(JsonElement content) {
		JsonElement sanitized = sanitizeJsonElement(content);
		if (sanitized == null || sanitized.isJsonNull()) {
			return "";
		}
		if (sanitized.isJsonPrimitive() && sanitized.getAsJsonPrimitive().isString()) {
			return sanitized.getAsString();
		}
		return TRACE_GSON.toJson(sanitized);
	}

	private static String normalize(String text, int maxLength) {
		if (text == null) {
			return "";
		}
		String normalized = DATA_URL_PATTERN.matcher(text).replaceAll("[image-data-redacted]");
		normalized = BEARER_PATTERN.matcher(normalized).replaceAll("Bearer [redacted]");
		normalized = normalized
			.replace("\\", "\\\\")
			.replace("\r", "\\r")
			.replace("\n", "\\n");
		if (normalized.length() > maxLength) {
			return normalized.substring(0, maxLength) + "...";
		}
		return normalized;
	}

	private static JsonElement sanitizePromptImageUrl(JsonElement imageUrl, boolean captureImages) {
		JsonElement sanitized = sanitizeJsonElement(imageUrl, captureImages);
		if (!captureImages || !sanitized.isJsonPrimitive() || !sanitized.getAsJsonPrimitive().isString()) {
			return sanitized;
		}
		return new JsonPrimitive(displayImageDataUrl(sanitized.getAsString()));
	}

	private static String displayImageDataUrl(String dataUrl) {
		if (dataUrl == null || dataUrl.isBlank()) {
			return "";
		}
		if (!dataUrl.startsWith("data:image/")) {
			return dataUrl;
		}
		int commaIndex = dataUrl.indexOf(',');
		if (commaIndex <= 0) {
			return dataUrl;
		}
		String metadata = dataUrl.substring(5, commaIndex);
		if (!metadata.contains(";base64")) {
			return dataUrl;
		}
		String mimeType = metadata.substring(0, metadata.indexOf(';'));
		try {
			byte[] decoded = Base64.getDecoder().decode(dataUrl.substring(commaIndex + 1));
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(decoded));
			if (image == null) {
				return dataUrl;
			}
			BufferedImage resized = resizeForTrace(image);
			if (resized.getWidth() == image.getWidth() && resized.getHeight() == image.getHeight()) {
				return dataUrl;
			}
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ImageIO.write(resized, "png", output);
			return "data:%s;base64,%s".formatted(
				mimeType.isBlank() ? "image/png" : mimeType,
				Base64.getEncoder().encodeToString(output.toByteArray())
			);
		}
		catch (IllegalArgumentException | java.io.IOException exception) {
			return dataUrl;
		}
	}

	private static BufferedImage resizeForTrace(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		if (width <= TRACE_IMAGE_MAX_WIDTH && height <= TRACE_IMAGE_MAX_HEIGHT) {
			return image;
		}
		double scale = Math.min(
			(double) TRACE_IMAGE_MAX_WIDTH / width,
			(double) TRACE_IMAGE_MAX_HEIGHT / height
		);
		int targetWidth = Math.max(1, (int) Math.round(width * scale));
		int targetHeight = Math.max(1, (int) Math.round(height * scale));
		BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = resized.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
		}
		finally {
			graphics.dispose();
		}
		return resized;
	}
}
