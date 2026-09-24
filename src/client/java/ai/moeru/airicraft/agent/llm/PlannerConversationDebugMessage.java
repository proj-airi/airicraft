package ai.moeru.airicraft.agent.llm;

import java.util.Objects;

public record PlannerConversationDebugMessage(
	String role,
	PlannerConversationDebugKind kind,
	String text,
	long generation,
	String phase,
	int attempt,
	boolean hasImageAttachment,
	long timestampMs,
	boolean superseded
) {
	public PlannerConversationDebugMessage(
		String role,
		PlannerConversationDebugKind kind,
		String text,
		long generation,
		String phase,
		int attempt,
		boolean hasImageAttachment
	) {
		this(role, kind, text, generation, phase, attempt, hasImageAttachment, 0L, false);
	}

	public PlannerConversationDebugMessage {
		role = role == null || role.isBlank() ? "system" : role;
		kind = Objects.requireNonNull(kind, "kind");
		text = text == null ? "" : text;
		phase = phase == null ? "UNKNOWN" : phase;
		attempt = Math.max(0, attempt);
		timestampMs = Math.max(0L, timestampMs);
	}

	public PlannerConversationDebugMessage stamped(long newTimestampMs, boolean newSuperseded) {
		return new PlannerConversationDebugMessage(
			role, kind, text, generation, phase, attempt, hasImageAttachment,
			Math.max(0L, newTimestampMs), newSuperseded
		);
	}
}
