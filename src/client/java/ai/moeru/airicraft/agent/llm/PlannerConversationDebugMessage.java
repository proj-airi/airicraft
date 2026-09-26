package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;
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
	boolean superseded,
	JsonElement fields
) {
	public PlannerConversationDebugMessage(String role, PlannerConversationDebugKind kind, String text,
		long generation, String phase, int attempt, boolean hasImageAttachment, long timestampMs, boolean superseded) {
		this(role, kind, text, generation, phase, attempt, hasImageAttachment, timestampMs, superseded, null);
	}
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
		fields = fields == null || fields.isJsonNull()
			? kind == PlannerConversationDebugKind.TOOL_RESULT ? PlannerFieldPresentation.fields(text) : null
			: fields.deepCopy();
	}

	public PlannerConversationDebugMessage stamped(long newTimestampMs, boolean newSuperseded) {
		return new PlannerConversationDebugMessage(
			role, kind, text, generation, phase, attempt, hasImageAttachment,
			Math.max(0L, newTimestampMs), newSuperseded, fields
		);
	}

	@Override public JsonElement fields() {
		return fields == null ? null : fields.deepCopy();
	}
}
