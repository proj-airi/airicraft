package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;

import java.util.Objects;

public record PlannerTurnEvent(
	long id,
	Kind kind,
	long timestampMs,
	long generation,
	int attempt,
	String phase,
	PlannerRequest request,
	LlmConversation conversation,
	PlannerConversationDebugMessage debugMessage,
	JsonElement assistantRawContent,
	PlannerToolCall toolCall,
	String toolResultText,
	long tick,
	boolean imageAttached
) {
	public enum Kind {
		SUBMISSION,
		DEBUG_CARD,
		TOOL_EXCHANGE,
		ACCEPTED_REPLY,
		COMPACTION,
		SUPERSEDED,
		RESET
	}

	public PlannerTurnEvent {
		kind = Objects.requireNonNull(kind, "kind");
		phase = phase == null ? "UNKNOWN" : phase;
		conversation = conversation == null ? null : LlmConversation.of(conversation.messages());
		debugMessage = debugMessage == null ? null : new PlannerConversationDebugMessage(
			debugMessage.role(),
			debugMessage.kind(),
			debugMessage.text(),
			debugMessage.generation(),
			debugMessage.phase(),
			debugMessage.attempt(),
			debugMessage.hasImageAttachment(),
			debugMessage.timestampMs(),
			debugMessage.superseded()
		);
		assistantRawContent = assistantRawContent == null || assistantRawContent.isJsonNull()
			? null
			: assistantRawContent.deepCopy();
		toolResultText = toolResultText == null ? "" : toolResultText;
		attempt = Math.max(0, attempt);
	}
}
