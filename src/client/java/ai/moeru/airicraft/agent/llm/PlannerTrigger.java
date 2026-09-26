package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;
import java.util.Objects;

public record PlannerTrigger(
	long seqNo,
	PlannerTriggerType type,
	String speaker,
	String text,
	long tick,
	long timestampMs,
	PlannerTriggerOrigin origin,
	String coalescingKey,
	JsonElement fields
) {
	public PlannerTrigger(long seqNo, PlannerTriggerType type, String speaker, String text, long tick,
		long timestampMs, PlannerTriggerOrigin origin, String coalescingKey) {
		this(seqNo, type, speaker, text, tick, timestampMs, origin, coalescingKey, null);
	}

	public PlannerTrigger {
		type = Objects.requireNonNullElse(type, PlannerTriggerType.CHAT);
		speaker = speaker == null || speaker.isBlank() ? defaultSpeaker(type) : speaker;
		text = text == null ? "" : text;
		origin = origin == null ? defaultOrigin(type) : origin;
		coalescingKey = origin == PlannerTriggerOrigin.AUTONOMOUS
			? (coalescingKey == null || coalescingKey.isBlank() ? type.name().toLowerCase(java.util.Locale.ROOT) : coalescingKey)
			: null;
		fields = fields == null || fields.isJsonNull() ? null : fields.deepCopy();
	}

	public PlannerTrigger(long seqNo, PlannerTriggerType type, String speaker, String text, long tick, long timestampMs) {
		this(seqNo, type, speaker, text, tick, timestampMs, defaultOrigin(type), null);
	}

	public static PlannerTrigger pending(PlannerTriggerType type, String speaker, String text, long tick, long timestampMs) {
		return new PlannerTrigger(0L, type, speaker, text, tick, timestampMs, defaultOrigin(type), null);
	}

	public static PlannerTrigger direct(PlannerTriggerType type, String speaker, String text, long tick, long timestampMs) {
		return new PlannerTrigger(0L, type, speaker, text, tick, timestampMs, PlannerTriggerOrigin.DIRECT_GUIDANCE, null);
	}

	public static PlannerTrigger autonomous(
		PlannerTriggerType type,
		String speaker,
		String text,
		long tick,
		long timestampMs,
		String coalescingKey
	) {
		return new PlannerTrigger(0L, type, speaker, text, tick, timestampMs, PlannerTriggerOrigin.AUTONOMOUS, coalescingKey);
	}

	public static PlannerTrigger autonomous(PlannerTriggerType type, String speaker, String text,
		long tick, long timestampMs, String coalescingKey, JsonElement fields) {
		return new PlannerTrigger(0L, type, speaker, text, tick, timestampMs, PlannerTriggerOrigin.AUTONOMOUS, coalescingKey, fields);
	}

	public PlannerTrigger withSeqNo(long replacementSeqNo) {
		return new PlannerTrigger(replacementSeqNo, type, speaker, text, tick, timestampMs, origin, coalescingKey, fields);
	}

	@Override public JsonElement fields() { return fields == null ? null : fields.deepCopy(); }

	public boolean maySupersedeLaunchedTurn() {
		return origin == PlannerTriggerOrigin.DIRECT_GUIDANCE;
	}

	private static String defaultSpeaker(PlannerTriggerType type) {
		return switch (type) {
			case CHAT -> "player";
			case CRAFT -> "self";
			case DAMAGE -> "self";
			case PICKUP -> "self";
			case SYSTEM -> "server";
			case IDLE_THINK -> "self";
		};
	}

	private static PlannerTriggerOrigin defaultOrigin(PlannerTriggerType type) {
		return type == PlannerTriggerType.CHAT ? PlannerTriggerOrigin.DIRECT_GUIDANCE : PlannerTriggerOrigin.AUTONOMOUS;
	}
}
