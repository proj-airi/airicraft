package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;

import java.util.List;
import java.util.Objects;

public record LlmChatMessage(
	String role,
	String content,
	LlmMessageKind kind,
	LlmImageAttachment imageAttachment,
	JsonElement rawContentOverride,
	List<PlannerToolCall> toolCalls,
	String toolCallId,
	JsonElement fields
) {
	public LlmChatMessage(String role, String content, LlmMessageKind kind, LlmImageAttachment imageAttachment,
		JsonElement rawContentOverride, List<PlannerToolCall> toolCalls, String toolCallId) {
		this(role, content, kind, imageAttachment, rawContentOverride, toolCalls, toolCallId, null);
	}
	public LlmChatMessage(String role, String content, LlmMessageKind kind, LlmImageAttachment imageAttachment) {
		this(role, content, kind, imageAttachment, null, List.of(), null);
	}

	public LlmChatMessage(String role, String content, LlmMessageKind kind, LlmImageAttachment imageAttachment, JsonElement rawContentOverride) {
		this(role, content, kind, imageAttachment, rawContentOverride, List.of(), null);
	}

	public LlmChatMessage {
		role = Objects.requireNonNull(role, "role");
		content = content == null ? "" : content;
		kind = Objects.requireNonNull(kind, "kind");
		rawContentOverride = rawContentOverride == null || rawContentOverride.isJsonNull()
			? null
			: rawContentOverride.deepCopy();
		fields = fields == null || fields.isJsonNull()
			? kind == LlmMessageKind.TOOL_RESULT ? PlannerFieldPresentation.fields(content) : null
			: fields.deepCopy();
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		toolCallId = toolCallId == null || toolCallId.isBlank() ? null : toolCallId;
	}

	public static LlmChatMessage system(String content) {
		return new LlmChatMessage("system", content, LlmMessageKind.SYSTEM, null);
	}

	public static LlmChatMessage user(String content, LlmMessageKind kind) {
		return new LlmChatMessage("user", content, kind, null);
	}

	public static LlmChatMessage user(String content, LlmMessageKind kind, JsonElement fields) {
		return new LlmChatMessage("user", content, kind, null, null, List.of(), null, fields);
	}

	public static LlmChatMessage userWithImage(String content, LlmMessageKind kind, LlmImageAttachment imageAttachment) {
		return new LlmChatMessage("user", content, kind, Objects.requireNonNull(imageAttachment, "imageAttachment"));
	}

	public static LlmChatMessage assistant(String content) {
		return new LlmChatMessage("assistant", content, LlmMessageKind.ASSISTANT_TURN, null);
	}

	public static LlmChatMessage assistant(String content, JsonElement rawContentOverride) {
		return new LlmChatMessage("assistant", content, LlmMessageKind.ASSISTANT_TURN, null, rawContentOverride);
	}

	public static LlmChatMessage assistantToolCall(String content, PlannerToolCall toolCall) {
		return new LlmChatMessage("assistant", content, LlmMessageKind.ASSISTANT_TURN, null, null, List.of(Objects.requireNonNull(toolCall, "toolCall")), null);
	}

	public static LlmChatMessage assistantToolCall(String content, PlannerToolCall toolCall, JsonElement rawContentOverride) {
		return new LlmChatMessage("assistant", content, LlmMessageKind.ASSISTANT_TURN, null, rawContentOverride, List.of(Objects.requireNonNull(toolCall, "toolCall")), null);
	}

	public static LlmChatMessage assistantToolCalls(String content, List<PlannerToolCall> toolCalls) {
		return assistantToolCalls(content, toolCalls, null);
	}

	public static LlmChatMessage assistantToolCalls(String content, List<PlannerToolCall> toolCalls, JsonElement rawContentOverride) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			throw new IllegalArgumentException("toolCalls");
		}
		return new LlmChatMessage("assistant", content, LlmMessageKind.ASSISTANT_TURN, null, rawContentOverride, toolCalls, null);
	}

	public static LlmChatMessage tool(String toolCallId, String content) {
		return new LlmChatMessage("tool", content, LlmMessageKind.TOOL_RESULT, null, null, List.of(), toolCallId);
	}

	public static LlmChatMessage tool(String toolCallId, String content, JsonElement fields) {
		return new LlmChatMessage("tool", content, LlmMessageKind.TOOL_RESULT, null, null, List.of(), toolCallId, fields);
	}

	@Override public JsonElement fields() {
		return fields == null ? null : fields.deepCopy();
	}

	public boolean hasImageAttachment() {
		return imageAttachment != null;
	}

	public boolean hasToolCalls() {
		return !toolCalls.isEmpty();
	}
}
