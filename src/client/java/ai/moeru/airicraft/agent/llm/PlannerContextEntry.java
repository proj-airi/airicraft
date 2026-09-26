package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.semantic.SemanticContextUpdate;
import com.google.gson.JsonElement;

import java.util.List;
import java.util.Objects;

public record PlannerContextEntry(
	PlannerContextEntryType type,
	String speaker,
	String text,
	long tick,
	long timestampMs,
	SemanticContextUpdate semanticUpdate,
	JsonElement rawAssistantContent,
	List<PlannerToolCall> toolCalls,
	JsonElement fields
) {
	public PlannerContextEntry(PlannerContextEntryType type, String speaker, String text, long tick,
		long timestampMs, SemanticContextUpdate semanticUpdate, JsonElement rawAssistantContent,
		List<PlannerToolCall> toolCalls) {
		this(type, speaker, text, tick, timestampMs, semanticUpdate, rawAssistantContent, toolCalls, null);
	}
	public PlannerContextEntry(
		PlannerContextEntryType type,
		String speaker,
		String text,
		long tick,
		long timestampMs
	) {
		this(type, speaker, text, tick, timestampMs, null, null, List.of());
	}

		public PlannerContextEntry(
			PlannerContextEntryType type,
			String speaker,
			String text,
			long tick,
			long timestampMs,
			SemanticContextUpdate semanticUpdate,
			JsonElement rawAssistantContent,
			PlannerToolCall toolCall
		) {
			this(type, speaker, text, tick, timestampMs, semanticUpdate, rawAssistantContent, toolCall == null ? List.of() : List.of(toolCall));
		}

		public PlannerContextEntry {
			type = Objects.requireNonNull(type, "type");
			text = Objects.requireNonNull(text, "text");
			toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
			rawAssistantContent = rawAssistantContent == null || rawAssistantContent.isJsonNull()
				? null
				: rawAssistantContent.deepCopy();
			fields = fields == null || fields.isJsonNull()
				? type == PlannerContextEntryType.TOOL_RESULT ? PlannerFieldPresentation.fields(text) : null
				: fields.deepCopy();
		}

		@Override public JsonElement fields() { return fields == null ? null : fields.deepCopy(); }

		public PlannerToolCall toolCall() {
			return toolCalls.isEmpty() ? null : toolCalls.getFirst();
		}

	public static PlannerContextEntry semanticNotice(SemanticContextUpdate update) {
		Objects.requireNonNull(update, "update");
		return new PlannerContextEntry(
			PlannerContextEntryType.NOTICE,
			null,
			update.text(),
			update.tick(),
			update.timestampMs(),
			update,
			null,
			List.of()
		);
	}

	public static PlannerContextEntry toolRequest(JsonElement assistantRawContent, long tick, long timestampMs) {
		Objects.requireNonNull(assistantRawContent, "assistantRawContent");
		String visibleText = OpenAiCompatibleMessageContent.extractVisibleText(assistantRawContent);
		return new PlannerContextEntry(
			PlannerContextEntryType.TOOL_REQUEST,
			null,
			visibleText == null ? "" : visibleText,
			tick,
			timestampMs,
			null,
			assistantRawContent,
			List.of()
		);
	}

	public static PlannerContextEntry toolRequest(PlannerToolCall toolCall, long tick, long timestampMs) {
		Objects.requireNonNull(toolCall, "toolCall");
		return toolRequest(List.of(toolCall), tick, timestampMs);
	}

	public static PlannerContextEntry toolRequest(List<PlannerToolCall> toolCalls, long tick, long timestampMs) {
		Objects.requireNonNull(toolCalls, "toolCalls");
		return new PlannerContextEntry(
			PlannerContextEntryType.TOOL_REQUEST,
			null,
			"",
			tick,
			timestampMs,
			null,
			null,
			List.copyOf(toolCalls)
		);
	}

	public static PlannerContextEntry toolResult(String toolResultText, long tick, long timestampMs) {
		return toolResult(toolResultText, null, tick, timestampMs);
	}

	public static PlannerContextEntry toolResult(String toolResultText, PlannerToolCall toolCall, long tick, long timestampMs) {
		String body = toolResultText == null || toolResultText.isBlank() ? "none" : toolResultText;
		return new PlannerContextEntry(
			PlannerContextEntryType.TOOL_RESULT,
			null,
			"Tool result: " + body,
			tick,
			timestampMs,
			null,
			null,
			toolCall == null ? List.of() : List.of(toolCall),
			PlannerFieldPresentation.fields(body)
		);
	}
}
