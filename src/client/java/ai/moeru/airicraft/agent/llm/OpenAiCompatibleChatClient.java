package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.observability.TraceSanitizer;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.opentelemetry.context.Context;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class OpenAiCompatibleChatClient {
	private static final Gson GSON = new Gson();
	private static final String JSON_OBJECT_RESPONSE_FORMAT = "json_object";

	private final AgentConfig.LlmConfig config;
	private final AgentObservability observability;
	private final PlannerToolRegistry toolRegistry;
	private final String cacheKey;
	private final String providerSessionId = java.util.UUID.randomUUID().toString();
	private final AtomicReference<ProtocolState> protocolState = new AtomicReference<>(new ProtocolState(0, false));
	private final HttpClient httpClient = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.build();

	public OpenAiCompatibleChatClient(AgentConfig.LlmConfig config) {
		this(config, NoopObservability.INSTANCE, PlannerToolRegistry.empty());
	}

	public OpenAiCompatibleChatClient(AgentConfig.LlmConfig config, PlannerToolRegistry toolRegistry) {
		this(config, NoopObservability.INSTANCE, toolRegistry);
	}

	public OpenAiCompatibleChatClient(AgentConfig.LlmConfig config, AgentObservability observability) {
		this(config, observability, PlannerToolRegistry.empty());
	}

	public OpenAiCompatibleChatClient(AgentConfig.LlmConfig config, AgentObservability observability, PlannerToolRegistry toolRegistry) {
		this(config, observability, toolRegistry, null);
	}

	public OpenAiCompatibleChatClient(AgentConfig.LlmConfig config, AgentObservability observability, PlannerToolRegistry toolRegistry, String cacheKey) {
		this.cacheKey = cacheKey;
		this.config = Objects.requireNonNull(config, "config");
		this.observability = Objects.requireNonNull(observability, "observability");
		this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
	}

	LlmCallResult<String> complete(LlmConversation conversation) throws LlmBackendException {
		return complete(conversation, LlmRequestOptions.plain());
	}

	LlmCallResult<String> complete(LlmConversation conversation, LlmRequestOptions options) throws LlmBackendException {
		return complete(conversation, options, ignored -> {});
	}

	LlmCallResult<String> complete(LlmConversation conversation, LlmRequestOptions options, java.util.function.Consumer<String> preview) throws LlmBackendException {
		Objects.requireNonNull(conversation, "conversation");
		Objects.requireNonNull(options, "options");
		if (!config.isConfigured()) {
			throw new LlmBackendException(LlmFailureType.PROVIDER_UNAVAILABLE, "LLM provider is not configured");
		}

		URI uri;
		try {
			uri = buildUri("chat/completions");
		}
		catch (LlmBackendException exception) {
			observability.recordFailure(Context.current(), exception.failureType().name(), exception.getMessage(), exception);
			throw exception;
		}

		String requestBody = GSON.toJson(buildRequestPayload(conversation, options));
		ProtocolState sessionProtocol = protocolState.get();
		if (options.plannerTools() && sessionProtocol.anthropicMessages()) {
			return completeAnthropic(conversation, requestBody, preview);
		}
		HttpResponse<String> response = sendHttpRequest(uri, conversation, requestBody, options.plannerTools(), preview);
		if (options.plannerTools() && response.statusCode() == 400) {
			Airicraft.LOGGER.info("Planner HTTP 400; trying Anthropic-compatible Messages for this session");
			LlmCallResult<String> fallback = completeAnthropic(conversation, requestBody, preview);
			protocolState.compareAndSet(sessionProtocol, new ProtocolState(sessionProtocol.epoch(), true));
			return fallback;
		}
		if (response.statusCode() >= 400) {
			throw providerError(response);
		}
		return LlmCallResult.of(
			response.body(),
			parseUsage(response.body()),
			response.statusCode(),
			responseModel(response.body()).orElse(config.model()),
			requestBody
		);
	}

	void resetProtocolMode() {
		protocolState.updateAndGet(previous -> new ProtocolState(previous.epoch() + 1, false));
	}

	private record ProtocolState(long epoch, boolean anthropicMessages) {
	}

	private LlmCallResult<String> completeAnthropic(LlmConversation conversation, String chatRequestBody,
		java.util.function.Consumer<String> preview) throws LlmBackendException {
		String requestBody;
		URI uri;
		try {
			requestBody = AnthropicMessagesCodec.requestBody(chatRequestBody);
			uri = buildUri("messages");
		} catch (JsonParseException | IllegalStateException exception) {
			throw new LlmBackendException(LlmFailureType.PARSE_ERROR, "Cannot translate planner request to Messages", exception);
		}
		HttpResponse<String> response = sendHttpRequest(uri, conversation, requestBody, false, preview, true);
		if (response.statusCode() >= 400) throw providerError(response);
		String normalized;
		try {
			normalized = AnthropicMessagesCodec.chatCompletionResponse(response.body());
		} catch (JsonParseException | IllegalStateException exception) {
			throw new LlmBackendException(LlmFailureType.PARSE_ERROR, "Cannot translate Messages response", exception);
		}
		return LlmCallResult.of(normalized, parseUsage(normalized), response.statusCode(),
			responseModel(normalized).orElse(config.model()), requestBody);
	}

	private LlmBackendException providerError(HttpResponse<String> response) {
		String message = providerErrorMessage(response.statusCode(), response.body());
		long retryAfter = response.statusCode() == 429
			? retryAfterMillis(response.headers().firstValue("Retry-After").orElse(null), java.time.Instant.now()) : 0L;
		observability.recordFailure(Context.current(), LlmFailureType.PROVIDER_ERROR.name(), message, null);
		return new LlmBackendException(LlmFailureType.PROVIDER_ERROR, message, null, retryAfter);
	}

	static long retryAfterMillis(String header, java.time.Instant now) {
		if (header == null || header.isBlank()) return 30_000L;
		try {
			long seconds = Long.parseLong(header.trim());
			if (seconds < 0) return 30_000L;
			return Math.max(1_000L, seconds > Long.MAX_VALUE / 1_000L ? Long.MAX_VALUE : seconds * 1_000L);
		} catch (NumberFormatException ignored) {
			try {
				var date = java.time.ZonedDateTime.parse(header.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
				return Math.max(1_000L, java.time.Duration.between(now, date.toInstant()).toMillis());
			} catch (java.time.DateTimeException | ArithmeticException invalidHeader) {
				return 30_000L;
			}
		}
	}

	private URI buildUri(String path) throws LlmBackendException {
		try {
			String baseUrl = config.providerBaseUrl().endsWith("/")
				? config.providerBaseUrl().substring(0, config.providerBaseUrl().length() - 1)
				: config.providerBaseUrl();
			return URI.create(baseUrl + "/" + path);
		}
		catch (IllegalArgumentException exception) {
			throw new LlmBackendException(LlmFailureType.PROVIDER_UNAVAILABLE, "Invalid LLM provider URL", exception);
		}
	}

	HttpRequest buildHttpRequest(URI uri, String requestBody) {
		return buildHttpRequest(uri, requestBody, false);
	}

	private HttpRequest buildHttpRequest(URI uri, String requestBody, boolean anthropic) {
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
			.uri(uri)
			.timeout(Duration.ofMillis(config.requestTimeoutMillis()))
			.header("Content-Type", "application/json")
			.header("User-Agent", "Airicraft/1.0");
		if ("opencode.ai".equalsIgnoreCase(uri.getHost())) {
			requestBuilder.header("x-opencode-session", cacheKey == null || cacheKey.isBlank() ? providerSessionId : cacheKey);
		}
		if (anthropic) requestBuilder.header("anthropic-version", "2023-06-01");
		if (config.apiKey() != null && !config.apiKey().isBlank()) {
			if (anthropic) requestBuilder.header("x-api-key", config.apiKey());
			else requestBuilder.header("Authorization", "Bearer " + config.apiKey());
		}
		return requestBuilder
			.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
			.build();
	}

	private HttpResponse<String> sendHttpRequest(URI uri, LlmConversation conversation, String requestBody, boolean streaming, java.util.function.Consumer<String> preview) throws LlmBackendException {
		return sendHttpRequest(uri, conversation, requestBody, streaming, preview, false);
	}

	private HttpResponse<String> sendHttpRequest(URI uri, LlmConversation conversation, String requestBody, boolean streaming,
		java.util.function.Consumer<String> preview, boolean anthropic) throws LlmBackendException {
		observability.recordLlmRequest(
			Context.current(),
			TraceSanitizer.inferProviderName(config.providerBaseUrl()),
			uri,
			config.model(),
			config.requestTimeoutMillis(),
			conversation,
			requestBody
		);
		Airicraft.LOGGER.info(
			"LLM request model={} messages={} preview={}",
			config.model(),
			conversation.messages().size(),
			TraceSanitizer.summarizeForLog(TraceSanitizer.sanitizeRequestPayloadForTrace(requestBody))
		);

		HttpRequest httpRequest = buildHttpRequest(uri, requestBody, anthropic);

		try {
			HttpResponse<String> response;
			if (streaming) {
				var streamPreview = preview.andThen(observability.streamLlmResponse(Context.current()));
				var future = httpClient.sendAsync(httpRequest, info -> {
					if (info.statusCode() >= 400 || !info.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"))
						return HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
					var stream = new OpenAiChatStream(streamPreview);
					return HttpResponse.BodySubscribers.fromLineSubscriber(stream, OpenAiChatStream::response, StandardCharsets.UTF_8, null);
				});
				try {
					response = future.get(config.requestTimeoutMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
				} catch (java.util.concurrent.TimeoutException exception) {
					future.cancel(true);
					throw new java.net.http.HttpTimeoutException("Planner stream timed out");
				} catch (InterruptedException exception) {
					future.cancel(true);
					throw exception;
				} catch (java.util.concurrent.ExecutionException exception) {
					Throwable cause = exception.getCause();
					if (cause instanceof IOException io) throw io;
					String message = requestFailureMessage("Invalid planner stream", cause);
					observability.recordFailure(Context.current(), LlmFailureType.PARSE_ERROR.name(), message, cause);
					throw new LlmBackendException(LlmFailureType.PARSE_ERROR, message, cause);
				}
			} else {
				response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			}
			observability.recordRawLlmResponse(
				Context.current(),
				response.statusCode(),
				responseModel(response.body()).orElse(config.model()),
				parseUsage(response.body()),
				response.body()
			);
			Airicraft.LOGGER.info(
				"LLM response model={} status={} summary={}",
				config.model(),
				response.statusCode(),
				TraceSanitizer.summarizeChatResponseForLog(response.body())
			);
			return response;
		}
		catch (java.net.http.HttpTimeoutException exception) {
			observability.recordFailure(Context.current(), LlmFailureType.TIMEOUT.name(), "LLM request timed out", exception);
			throw new LlmBackendException(LlmFailureType.TIMEOUT, "LLM request timed out", exception);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			observability.recordFailure(Context.current(), LlmFailureType.TIMEOUT.name(), "LLM request interrupted", exception);
			throw new LlmBackendException(LlmFailureType.TIMEOUT, "LLM request interrupted", exception);
		}
		catch (IOException exception) {
			String message = requestFailureMessage("LLM request failed", exception);
			Airicraft.LOGGER.warn("LLM request transport failed model={} cause={}", config.model(), message, exception);
			observability.recordFailure(Context.current(), LlmFailureType.PROVIDER_UNAVAILABLE.name(), message, exception);
			throw new LlmBackendException(LlmFailureType.PROVIDER_UNAVAILABLE, message, exception);
		}
	}

	static String requestFailureMessage(String prefix, Throwable exception) {
		return prefix + ": " + exceptionSummary(exception);
	}

	private static String exceptionSummary(Throwable exception) {
		if (exception == null) {
			return "unknown";
		}
		String type = exception.getClass().getSimpleName();
		String message = exception.getMessage();
		if (message == null || message.isBlank()) {
			return type;
		}
		return type + ": " + message;
	}

	private Map<String, Object> buildRequestPayload(LlmConversation conversation, LlmRequestOptions options) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("model", config.model());
		if (cacheKey != null) payload.put("prompt_cache_key", cacheKey);
		// Compaction has its own client/cache identity and summarizes evidence without gameplay reasoning.
		String reasoningEffort = options.equals(LlmRequestOptions.compaction()) ? "none" : config.reasoningEffort();
		if (reasoningEffort != null && !reasoningEffort.isBlank()) {
			payload.put("reasoning_effort", reasoningEffort);
		}
		if (options.jsonObjectResponseFormat()) {
			payload.put("response_format", Map.of("type", JSON_OBJECT_RESPONSE_FORMAT));
		}
		if (options.plannerTools()) {
			payload.put("stream", true);
			payload.put("stream_options", Map.of("include_usage", true));
			payload.put("tools", toolRegistry.openAiTools());
			payload.put("tool_choice", "auto");
		}
		var messages = toolRegistry.references().presentMessages(conversation);
		if (options.equals(LlmRequestOptions.compaction())) {
			// Bound the entire serialized history, including raw replay content. Work on a copy:
			// the live transcript and tool-call/result pairs must survive compaction failure intact.
			var bounded = GSON.toJsonTree(messages).getAsJsonArray();
			int remainingImages = config.plannerMaxImages();
			for (int m = bounded.size() - 1; m >= 0; m--) {
				var content = bounded.get(m).getAsJsonObject().get("content");
				if (content == null || !content.isJsonArray()) continue;
				var parts = content.getAsJsonArray();
				for (int p = parts.size() - 1; p >= 0; p--) {
					if (!parts.get(p).isJsonObject()) continue;
					var part = parts.get(p).getAsJsonObject();
					if (!part.has("type") || !"image_url".equals(part.get("type").getAsString())) continue;
					if (remainingImages-- > 0) continue;
					var omitted = new JsonObject();
					omitted.addProperty("type", "text");
					omitted.addProperty("text", "[Older image omitted for compaction; surrounding observations retained.]");
					parts.set(p, omitted);
				}
			}
			payload.put("messages", bounded);
		} else {
			payload.put("messages", messages);
		}
		return payload;
	}

	public static List<Map<String, Object>> canonicalRequestMessages(LlmConversation conversation) {
		Objects.requireNonNull(conversation, "conversation");
		return canonicalRequestEntries(conversation).stream().map(RequestMessage::wire).toList();
	}

	static record RequestMessage(Map<String, Object> wire, JsonElement fields) {}

	static List<RequestMessage> canonicalRequestEntries(LlmConversation conversation) {
		Objects.requireNonNull(conversation, "conversation");
		return compactRequestMessages(conversation.messages());
	}

	private static Map<String, Object> toRequestMessage(LlmChatMessage message) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("role", message.role());
		JsonObject replayMessage = OpenAiCompatibleMessageContent.replayMessageObject(message.rawContentOverride())
			.orElse(null);
		if (replayMessage != null) {
			payload.put("content", replayMessage.has("content") ? replayMessage.get("content") : null);
			if (replayMessage.has("reasoning_content")) {
				payload.put("reasoning_content", replayMessage.get("reasoning_content"));
			}
			if (message.hasToolCalls()) {
				payload.put("tool_calls", PlannerToolCatalog.toOpenAiToolCalls(message.toolCalls()));
			}
		}
		else if (message.rawContentOverride() != null) {
			payload.put("content", message.rawContentOverride());
			if (message.hasToolCalls()) {
				payload.put("tool_calls", PlannerToolCatalog.toOpenAiToolCalls(message.toolCalls()));
			}
		}
		else if (message.hasToolCalls()) {
			payload.put("content", message.content().isBlank() ? null : message.content());
			payload.put("tool_calls", PlannerToolCatalog.toOpenAiToolCalls(message.toolCalls()));
		}
		else {
			payload.put("content", message.hasImageAttachment() ? multimodalContent(message) : message.content());
		}
		if ("tool".equals(message.role()) && message.toolCallId() != null) {
			payload.put("tool_call_id", message.toolCallId());
		}
		return payload;
	}

	private static List<RequestMessage> compactRequestMessages(List<LlmChatMessage> messages) {
		ArrayList<RequestMessage> compacted = new ArrayList<>();
		for (LlmChatMessage message : messages) {
			Map<String, Object> requestMessage = toRequestMessage(message);
			if (!compacted.isEmpty() && compacted.getLast().fields() == null && message.fields() == null
				&& shouldMergeUserMessage(compacted.getLast().wire(), requestMessage)) {
				compacted.set(compacted.size() - 1, new RequestMessage(mergeUserMessages(compacted.getLast().wire(), requestMessage), null));
				continue;
			}
			compacted.add(new RequestMessage(requestMessage,
				message.rawContentOverride() == null ? message.fields() : null));
		}
		return List.copyOf(compacted);
	}

	private static boolean shouldMergeUserMessage(Map<String, Object> previous, Map<String, Object> current) {
		return "user".equals(previous.get("role")) && "user".equals(current.get("role"));
	}

	private static Map<String, Object> mergeUserMessages(Map<String, Object> previous, Map<String, Object> current) {
		LinkedHashMap<String, Object> merged = new LinkedHashMap<>(previous);
		merged.put("content", mergeUserContent(previous.get("content"), current.get("content")));
		return merged;
	}

	private static Object mergeUserContent(Object previous, Object current) {
		if (previous instanceof String previousText && current instanceof String currentText) {
			if (previousText.isBlank()) {
				return currentText;
			}
			if (currentText.isBlank()) {
				return previousText;
			}
			return previousText + "\n\n" + currentText;
		}
		ArrayList<Map<String, Object>> parts = new ArrayList<>();
		appendContentParts(parts, previous);
		appendContentParts(parts, current);
		return List.copyOf(parts);
	}

	@SuppressWarnings("unchecked")
	private static void appendContentParts(List<Map<String, Object>> parts, Object content) {
		if (content == null) {
			return;
		}
		if (content instanceof String text) {
			if (!text.isBlank()) {
				parts.add(Map.of("type", "text", "text", text));
			}
			return;
		}
		if (content instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof Map<?, ?> map) {
					parts.add((Map<String, Object>) map);
				}
			}
		}
	}

	private static List<Map<String, Object>> multimodalContent(LlmChatMessage message) {
		LlmImageAttachment imageAttachment = Objects.requireNonNull(message.imageAttachment(), "imageAttachment");
		String imageUrl = "data:%s;base64,%s".formatted(
			imageAttachment.mimeType(),
			Base64.getEncoder().encodeToString(imageAttachment.imageBytes())
		);
		return List.of(
			Map.of("type", "text", "text", message.content()),
			Map.of(
				"type", "image_url",
				"image_url", Map.of(
					"url", imageUrl,
					"detail", imageAttachment.detail()
				)
			)
		);
	}

	static LlmUsageSnapshot parseUsage(String responseBody) {
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			if (!root.has("usage") || !root.get("usage").isJsonObject()) {
				return LlmUsageSnapshot.unknown();
			}
			JsonObject usage = root.getAsJsonObject("usage");
			Integer promptTokens = getUsageInt(usage, "prompt_tokens");
			if (promptTokens == null) {
				promptTokens = getUsageInt(usage, "input_tokens");
			}
			Integer completionTokens = getUsageInt(usage, "completion_tokens");
			if (completionTokens == null) {
				completionTokens = getUsageInt(usage, "output_tokens");
			}
			Integer totalTokens = getUsageInt(usage, "total_tokens");
			return new LlmUsageSnapshot(promptTokens, completionTokens, totalTokens);
		}
		catch (IllegalStateException | JsonParseException exception) {
			return LlmUsageSnapshot.unknown();
		}
	}

	private static Integer getUsageInt(JsonObject usage, String fieldName) {
		if (usage == null || !usage.has(fieldName) || usage.get(fieldName).isJsonNull()) {
			return null;
		}
		return usage.get(fieldName).getAsInt();
	}

	static java.util.Optional<String> responseModel(String responseBody) {
		return TraceSanitizer.responseModel(responseBody);
	}

	private static String providerErrorMessage(int statusCode, String responseBody) {
		String detail = extractErrorDetail(responseBody);
		if (detail == null || detail.isBlank()) {
			return "Provider returned HTTP " + statusCode;
		}
		return "Provider returned HTTP " + statusCode + ": " + detail;
	}

	private static String extractErrorDetail(String responseBody) {
		if (responseBody == null || responseBody.isBlank()) {
			return null;
		}
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			if (root.has("error") && !root.get("error").isJsonNull()) {
				return root.get("error").isJsonPrimitive()
					? root.get("error").getAsString()
					: summarizeForLog(root.get("error").toString());
			}
			if (root.has("message") && !root.get("message").isJsonNull()) {
				return root.get("message").getAsString();
			}
		}
		catch (IllegalStateException | JsonParseException ignored) {
			// Fall back to plain-text summary below.
		}
		return summarizeForLog(responseBody);
	}

	static String summarizeForLog(String text) {
		return TraceSanitizer.summarizeForLog(text);
	}
}
