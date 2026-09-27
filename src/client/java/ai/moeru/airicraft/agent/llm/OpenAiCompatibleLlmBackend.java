package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.observability.TraceSanitizer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.opentelemetry.context.Context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

public final class OpenAiCompatibleLlmBackend implements LlmBackend {
	private final AgentConfig.LlmConfig config;
	private final OpenAiCompatibleChatClient chatClient;
	private final AgentObservability observability;
	private final PlannerToolRegistry toolRegistry;
	private final Deque<Object> injectedOutcomes = new ArrayDeque<>();

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config) {
		this(config, NoopObservability.INSTANCE, PlannerToolRegistry.empty());
	}

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config, PlannerToolRegistry toolRegistry) {
		this(config, NoopObservability.INSTANCE, toolRegistry);
	}

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config, AgentObservability observability) {
		this(config, observability, PlannerToolRegistry.empty());
	}

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config, AgentObservability observability, PlannerToolRegistry toolRegistry) {
		this(config, observability, toolRegistry, null);
	}

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config, AgentObservability observability, PlannerToolRegistry toolRegistry, String cacheKey) {
		this.config = Objects.requireNonNull(config, "config");
		this.observability = Objects.requireNonNull(observability, "observability");
		this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
		this.chatClient = new OpenAiCompatibleChatClient(config, observability, toolRegistry, cacheKey);
	}

	@Override
	public synchronized LlmCallResult<PlannerResponse> generate(LlmConversation conversation) throws LlmBackendException {
		return generate(conversation, ignored -> {});
	}

	@Override
	public LlmCallResult<PlannerResponse> generate(PlannerBackendRequest request, java.util.function.Consumer<String> preview) throws LlmBackendException {
		return generate(request.conversation(), preview);
	}

	private synchronized LlmCallResult<PlannerResponse> generate(LlmConversation conversation, java.util.function.Consumer<String> preview) throws LlmBackendException {
		Objects.requireNonNull(conversation, "conversation");

		Object injected = injectedOutcomes.pollFirst();
		if (injected instanceof PlannerResponse plannerResponse) {
			observability.recordLlmResponse(Context.current(), null, config.model(), LlmUsageSnapshot.unknown(), plannerResponse);
			return LlmCallResult.of(plannerResponse, LlmUsageSnapshot.unknown(), null, config.model());
		}
		if (injected instanceof TimeoutException timeoutException) {
			observability.recordFailure(Context.current(), LlmFailureType.TIMEOUT.name(), timeoutException.getMessage(), timeoutException);
			throw new LlmBackendException(LlmFailureType.TIMEOUT, timeoutException.getMessage(), timeoutException);
		}

		LlmCallResult<String> rawResponse = chatClient.complete(conversation, LlmRequestOptions.planner(), preview);
		PlannerResponse plannerResponse = parsePlannerResponse(conversation, rawResponse);
		observability.recordLlmResponse(Context.current(), rawResponse.statusCode(), rawResponse.responseModel(), rawResponse.usage(), plannerResponse);
		return LlmCallResult.of(plannerResponse, rawResponse.usage(), rawResponse.statusCode(), rawResponse.responseModel());
	}

	@Override
	public synchronized void injectMockResponse(PlannerResponse response) {
		injectedOutcomes.addLast(Objects.requireNonNull(response, "response"));
	}

	@Override
	public synchronized void injectTimeout() {
		injectedOutcomes.addLast(new TimeoutException("Injected LLM timeout"));
	}

	@Override
	public boolean isConfigured() {
		return config.isConfigured();
	}

	@Override
	public void resetBackend() {
		chatClient.resetProtocolMode();
	}

	private PlannerResponse parsePlannerResponse(LlmConversation conversation, LlmCallResult<String> rawResponse) throws LlmBackendException {
		String responseBody = rawResponse == null ? "" : rawResponse.payload();
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			JsonArray choices = root.getAsJsonArray("choices");
			if (choices == null || choices.isEmpty()) {
				throw new JsonParseException("Missing choices");
			}

			JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
			if (message == null) {
				throw new JsonParseException("Missing message");
			}

			JsonElement rawAssistantContent = OpenAiCompatibleMessageContent.rawMessageForReplay(message);
			String visibleText = OpenAiCompatibleMessageContent.extractVisibleText(message.get("content"));
			List<PlannerToolCall> toolCalls = parseToolCalls(message);
			if (!toolCalls.isEmpty()) {
				Airicraft.LOGGER.info(
					"Planner parsed tool_calls names={} count={}",
					summarizeToolCallNames(toolCalls),
					toolCalls.size()
				);
				return PlannerResponse.toolCalls(toolCalls, rawAssistantContent);
			}
			List<PlannerChatMessage> chatMessages = parseChatMessages(message.get("content"));
			if (!chatMessages.isEmpty()) {
				Airicraft.LOGGER.info("Planner parsed chat message plan count={}", chatMessages.size());
				return new PlannerResponse(chatMessages, new PlannerIntent("reply_only", null, null), rawAssistantContent);
			}
			String replyText = visibleText.strip();
			if (replyText.isEmpty()) throw new JsonParseException("Empty planner completion: no text or tool calls");
			Airicraft.LOGGER.info("Planner parsed plaintext reply={}", summarizeForLog(replyText));
			return new PlannerResponse(replyText, List.of(), rawAssistantContent);
		}
		catch (IllegalArgumentException | JsonParseException exception) {
			String failureMessage = plannerParseFailureMessage(exception);
			Airicraft.LOGGER.warn("Failed to parse planner response summary={}", TraceSanitizer.summarizeChatResponseForLog(responseBody), exception);
			observability.recordFailedLlmInput(Context.current(), conversation, rawResponse == null ? null : rawResponse.requestBody());
			observability.recordLlmResponse(
				Context.current(),
				rawResponse == null ? null : rawResponse.statusCode(),
				rawResponse == null ? config.model() : rawResponse.responseModel(),
				rawResponse == null ? LlmUsageSnapshot.unknown() : rawResponse.usage(),
				responseBody
			);
			observability.recordFailure(Context.current(), LlmFailureType.PARSE_ERROR.name(), failureMessage, exception);
			throw new LlmBackendException(LlmFailureType.PARSE_ERROR, failureMessage, exception);
		}
	}

	private List<PlannerChatMessage> parseChatMessages(JsonElement contentElement) {
		return OpenAiCompatibleMessageContent.extractJsonObject(contentElement)
			.filter(object -> object.has("chatMessages") && object.get("chatMessages").isJsonArray())
			.map(object -> parseChatMessages(object.getAsJsonArray("chatMessages")))
			.orElse(List.of());
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
			String text = object.has("text") && !object.get("text").isJsonNull()
				? object.get("text").getAsString()
				: "";
			int delayTicks = parseDelayTicks(object);
			messages.add(new PlannerChatMessage(text, delayTicks));
		}
		return List.copyOf(messages);
	}

	private static int parseDelayTicks(JsonObject object) {
		if (object == null) {
			return 0;
		}
		if (object.has("delayTicks") && !object.get("delayTicks").isJsonNull()) {
			return Math.max(0, object.get("delayTicks").getAsInt());
		}
		if (object.has("delaySeconds") && !object.get("delaySeconds").isJsonNull()) {
			return Math.max(0, (int) Math.round(object.get("delaySeconds").getAsDouble() * 20.0D));
		}
		return 0;
	}

	private List<PlannerToolCall> parseToolCalls(JsonObject message) {
		if (message == null || !message.has("tool_calls") || !message.get("tool_calls").isJsonArray()) {
			return List.of();
		}
		JsonArray toolCalls = message.getAsJsonArray("tool_calls");
		if (toolCalls.isEmpty()) {
			return List.of();
		}
		ArrayList<PlannerToolCall> parsed = new ArrayList<>();
		for (JsonElement toolCall : toolCalls) {
			if (!toolCall.isJsonObject()) {
				throw new JsonParseException("Planner tool call must be an object");
			}
			parsed.add(PlannerToolCatalog.parseToolCall(toolCall.getAsJsonObject(), toolRegistry));
		}
		if (parsed.size() == 1) {
			return List.copyOf(parsed);
		}
		if (allReadToolCalls(parsed)) {
			return List.copyOf(parsed);
		}
		if (parsed.size() > 1) {
			PlannerToolCall selected = selectEntityActionToolCall(parsed);
			if (selected != null) {
				Airicraft.LOGGER.warn("Planner returned multiple tool calls names={} selected={}", summarizeToolCallNames(parsed), selected.name());
				return List.of(selected);
			}
			throw new JsonParseException("Planner returned multiple tool calls: " + summarizeToolCallNames(parsed));
		}
		return List.copyOf(parsed);
	}

	private boolean allReadToolCalls(List<PlannerToolCall> toolCalls) {
		return toolCalls.stream()
			.allMatch(toolCall -> toolRegistry.isReadTool(toolCall.name()));
	}

	private PlannerToolCall selectEntityActionToolCall(List<PlannerToolCall> toolCalls) {
		List<PlannerToolCall> entityActionCalls = toolCalls.stream()
			.filter(this::isEntityInteractionToolCall)
			.toList();
		if (entityActionCalls.isEmpty()) {
			return null;
		}

		boolean onlyReadOrEntityActions = toolCalls.stream().allMatch(call ->
			isEntityInteractionToolCall(call) || toolRegistry.isReadTool(call.name())
		);
		if (!onlyReadOrEntityActions) {
			return null;
		}

		long distinctEntityActionNames = entityActionCalls.stream()
			.map(PlannerToolCall::name)
			.map(PlannerToolCatalog::normalizeName)
			.distinct()
			.count();
		if (distinctEntityActionNames != 1L) {
			return null;
		}

		return entityActionCalls.getFirst();
	}

	private boolean isEntityInteractionToolCall(PlannerToolCall toolCall) {
		String name = toolCall == null ? "" : PlannerToolCatalog.normalizeName(toolCall.name());
		return PlannerToolCatalog.ATTACK_ENTITY.equals(name) || PlannerToolCatalog.USE_ENTITY.equals(name);
	}

	private static String summarizeToolCallNames(List<PlannerToolCall> toolCalls) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		for (PlannerToolCall toolCall : toolCalls == null ? List.<PlannerToolCall>of() : toolCalls) {
			names.add(PlannerToolCatalog.normalizeName(toolCall == null ? "" : toolCall.name()));
		}
		return String.join(",", names);
	}

	private static String plannerParseFailureMessage(Exception exception) {
		String detail = exception == null ? "" : exception.getMessage();
		if (detail == null || detail.isBlank()) {
			return "Failed to parse planner response";
		}
		return "Failed to parse planner response: " + detail;
	}

	static String stripMarkdownCodeFences(String text) {
		String trimmed = text.strip();
		if (trimmed.startsWith("```")) {
			int firstNewline = trimmed.indexOf('\n');
			if (firstNewline >= 0) {
				trimmed = trimmed.substring(firstNewline + 1);
			}
			if (trimmed.endsWith("```")) {
				trimmed = trimmed.substring(0, trimmed.length() - 3);
			}
			return trimmed.strip();
		}
		return text;
	}

	private static String summarizeForLog(String text) {
		return OpenAiCompatibleChatClient.summarizeForLog(text);
	}
}
