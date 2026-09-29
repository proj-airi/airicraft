package ai.moeru.airicraft.agent.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Why the planner woke, as rendered in {@code observe.wake}. An {@code event} wake names the evidence by sequence
 * number; the evidence itself stays an ordinary {@code observe.events} entry. Chat is not a wake reason: it is the
 * user turn.
 *
 * @param seqNo the causal event's sequence number, or 0 for a reason without one
 * @param type the causal event's type, or {@code null}
 * @param urgency lowercase urgency, or {@code null}
 * @param debounced the attention policy chose {@code DEBOUNCE}: the scheduler holds it; never rendered
 */
public record WakeRef(String reason, long seqNo, String type, String urgency, boolean debounced) {
	public static final String EVENT = "event";
	private static final List<String> URGENCY_ORDER = List.of("critical", "direct", "high", "normal", "low", "self");

	public WakeRef {
		Objects.requireNonNull(reason, "reason");
		urgency = urgency == null ? null : urgency.toLowerCase(Locale.ROOT);
	}

	public WakeRef(String reason, long seqNo, String type, String urgency) {
		this(reason, seqNo, type, urgency, false);
	}

	public WakeRef debounce(boolean debounce) {
		return new WakeRef(reason, seqNo, type, urgency, debounce);
	}

	public static WakeRef event(long seqNo, String type, String urgency) {
		return new WakeRef(EVENT, seqNo, type, urgency);
	}

	public static WakeRef reason(String reason) {
		return new WakeRef(reason, 0L, null, null);
	}

	/** The trigger's explicit reference, or the reason its origin implies; {@code null} for chat. */
	public static WakeRef of(PlannerTrigger trigger) {
		if (trigger.type() == PlannerTriggerType.CHAT) return null;
		if (trigger.wake() != null) return trigger.wake();
		if (trigger.type() == PlannerTriggerType.IDLE_THINK) return reason("idle_think");
		if ("planner_goal".equals(trigger.coalescingKey())) return reason("goal_continuation");
		if ("delegation".equals(trigger.coalescingKey())) return reason("delegation");
		if ("tool_queue".equals(trigger.speaker())) return reason("tool_queue_review");
		if ("evaluation".equals(trigger.speaker())) return reason("evaluation");
		return reason("runtime");
	}

	/** One entry per event (most urgent kept) and per other reason, in first-seen order. */
	public static List<Map<String, Object>> render(List<PlannerTrigger> triggers) {
		var byKey = new LinkedHashMap<String, WakeRef>();
		for (PlannerTrigger trigger : triggers) {
			WakeRef ref = of(trigger);
			if (ref == null) continue;
			String key = EVENT.equals(ref.reason()) ? "event#" + ref.seqNo() : ref.reason();
			byKey.merge(key, ref, (kept, next) -> rank(next) < rank(kept) ? next : kept);
		}
		var rendered = new ArrayList<Map<String, Object>>();
		for (WakeRef ref : byKey.values()) {
			var entry = new LinkedHashMap<String, Object>();
			entry.put("reason", ref.reason());
			if (EVENT.equals(ref.reason())) {
				entry.put("seqNo", ref.seqNo());
				entry.put("type", ref.type());
				if (ref.urgency() != null) entry.put("urgency", ref.urgency());
			}
			rendered.add(entry);
		}
		return rendered;
	}

	private static int rank(WakeRef ref) {
		int index = ref.urgency() == null ? -1 : URGENCY_ORDER.indexOf(ref.urgency());
		return index < 0 ? URGENCY_ORDER.size() : index;
	}
}
