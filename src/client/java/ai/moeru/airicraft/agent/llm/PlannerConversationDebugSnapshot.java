package ai.moeru.airicraft.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record PlannerConversationDebugSnapshot(
	long generation,
	String phase,
	int attempt,
	List<PlannerConversationDebugMessage> messages
) {
	public PlannerConversationDebugSnapshot {
		phase = phase == null ? "UNKNOWN" : phase;
		attempt = Math.max(0, attempt);
		messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
	}

	public static PlannerConversationDebugSnapshot empty() {
		return new PlannerConversationDebugSnapshot(0L, "UNKNOWN", 0, List.of());
	}

	public static PlannerConversationDebugSnapshot fromConversation(
		long generation,
		PlannerSessionPhase phase,
		int attempt,
		LlmConversation conversation
	) {
		return fromConversation(generation, phase == null ? null : phase.name(), attempt, conversation);
	}

	public static PlannerConversationDebugSnapshot fromConversation(
		long generation,
		String phase,
		int attempt,
		LlmConversation conversation
	) {
		if (conversation == null) {
			return empty();
		}
		ArrayList<PlannerConversationDebugMessage> messages = new ArrayList<>();
		for (LlmChatMessage message : conversation.messages()) {
			messages.add(new PlannerConversationDebugMessage(
				message.role(),
				PlannerConversationDebugKind.fromMessageKind(message.kind()),
				debugText(message),
				generation,
				phase,
				attempt,
				message.hasImageAttachment(),
				0L,
				false,
				message.fields()
			));
		}
		return new PlannerConversationDebugSnapshot(generation, phase, attempt, messages);
	}

	public boolean isEmpty() {
		return messages.isEmpty();
	}

	public PlannerConversationDebugSnapshot withAppended(PlannerConversationDebugMessage message) {
		ArrayList<PlannerConversationDebugMessage> updated = new ArrayList<>(messages);
		updated.add(Objects.requireNonNull(message, "message"));
		return new PlannerConversationDebugSnapshot(generation, phase, attempt, updated);
	}

	/** Present typed tool evidence through the model's shared reference table. */
	public PlannerConversationDebugSnapshot presented(PlannerReferences references) {
		return new PlannerConversationDebugSnapshot(generation, phase, attempt, messages.stream().map(message ->
			new PlannerConversationDebugMessage(message.role(), message.kind(),
				references.presentContent(message.role(), message.text(), message.fields()),
				message.generation(), message.phase(), message.attempt(), message.hasImageAttachment(),
				message.timestampMs(), message.superseded(), message.fields())).toList());
	}

	private static String debugText(LlmChatMessage message) {
		if (message == null) {
			return "";
		}
		if (!message.hasToolCalls()) {
			return message.content();
		}
		ArrayList<String> summaries = new ArrayList<>();
		for (PlannerToolCall toolCall : message.toolCalls()) {
			StringBuilder summary = new StringBuilder("Tool call: ");
			summary.append(toolCall.name());
			if (toolCall.arguments() != null && !toolCall.arguments().isEmpty()) {
				summary.append(" args=").append(toolCall.arguments());
			}
			if (toolCall.narration() != null && !toolCall.narration().isBlank()) {
				summary.append(" | narration: ").append(toolCall.narration());
			}
			summaries.add(summary.toString());
		}
		if (message.content() != null && !message.content().isBlank()) {
			summaries.add(0, message.content());
		}
		return String.join("\n", summaries);
	}
}
