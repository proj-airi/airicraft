package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Situation-specific coaching for a wake, rendered as {@code observe.hints}. Standing rules live in the system prompt
 * and tool descriptions, and facts live in event payloads and {@code current}; a hint is what is left, advice that
 * depends on this particular event. Every hint names its provenance so it is never mistaken for a request.
 */
public final class DecisionHints {
	public static final String PROVENANCE = "runtime_hint";

	private DecisionHints() {
	}

	/** Hints for the wakes in this batch; {@code events} are the observation's retained events. */
	public static List<Map<String, Object>> render(List<PlannerTrigger> wakes, List<SemanticEvent> events) {
		var bySeqNo = new LinkedHashMap<Long, SemanticEvent>();
		for (SemanticEvent event : events) bySeqNo.put(event.seqNo(), event);
		var hints = new ArrayList<Map<String, Object>>();
		var seen = new java.util.HashSet<String>();
		for (PlannerTrigger trigger : wakes) {
			WakeRef ref = WakeRef.of(trigger);
			if (ref == null) continue;
			if (WakeRef.EVENT.equals(ref.reason())) {
				SemanticEvent event = bySeqNo.get(ref.seqNo());
				String hint = event == null ? null : eventHint(event);
				if (hint != null && seen.add("event#" + event.seqNo())) hints.add(entry(event.seqNo(), event.type(), null, hint));
			}
			else if ("idle_think".equals(ref.reason()) && seen.add("idle_think")) {
				String hint = idleThinkHint(trigger.fields());
				if (hint != null) hints.add(entry(0L, null, "idle_think", hint));
			}
		}
		return hints;
	}

	static String eventHint(SemanticEvent event) {
		Map<String, Object> payload = event.payload();
		return switch (event.type()) {
			case "action_graph.goal_terminal" -> {
				Object code = payload.get("failureCode");
				yield "unknown_acquisition_method".equals(code) || "unsupported_resource_kind".equals(code)
					? "Airicraft has no registered acquisition method for this request, so this capability is unsupported. "
						+ "Do not retry it unchanged or invent an acquisition method; another observed supported action may still serve the objective."
					: null;
			}
			case "social.item_offered" -> "The player and intent are inferred from spawn position and motion; this is not a confirmed pickup. "
				+ "Decide whether to collect or acknowledge the items using current world evidence.";
			case "reflex.resolved" -> reflexHint(payload);
			default -> null;
		};
	}

	private static String reflexHint(Map<String, Object> payload) {
		var parts = new ArrayList<String>();
		if (payload.get("combatSummary") != null) {
			parts.add("Use the recorded combat outcomes directly; re-observe only facts that remain unknown or may have changed.");
		}
		Object reason = payload.get("reason");
		if ("combat_stalemate".equals(reason)) {
			parts.add("Combat made no target-health or closing progress. Inspect local geometry and inventory, then execute a concrete escape "
				+ "or cover plan; do not resume the same stalled pursuit. After you release the hold, ordinary mob pressure will not preempt your "
				+ "action for 600 ticks; critical health, an imminent creeper or drowning still can. Mining steps or towering may be needed to escape a pit.");
		}
		if ("combat_approach_stalled".equals(reason)) {
			parts.add("Pursuit made no closer approach to the distant threats. Choose a tactical next step from fresh geometry and inventory. "
				+ "These distant attackers are deferred while you act; close danger, incoming projectiles, damage or a new attacker reactivate defense.");
		}
		return parts.isEmpty() ? null : String.join(" ", parts);
	}

	private static String idleThinkHint(JsonElement fields) {
		if (fields == null || !fields.isJsonObject()) return null;
		JsonObject object = fields.getAsJsonObject();
		List<String> interests = strings(object.get("interests"));
		List<String> ideas = strings(object.get("ideas"));
		if (interests.isEmpty() && ideas.isEmpty()) return null;
		var text = new StringBuilder();
		if (!interests.isEmpty()) text.append("Things you enjoy (pick what fits the moment; not a checklist): ").append(String.join("; ", interests)).append(". ");
		if (!ideas.isEmpty()) {
			text.append("Useful survival progress you can also choose, only when its preconditions are met now: ").append(String.join("; ", ideas)).append(". ")
				.append("If torches would improve mining readiness and none are available, consider smelting a log into minecraft:charcoal, then crafting minecraft:torch from charcoal and sticks. ");
		}
		return text.toString().strip();
	}

	private static List<String> strings(JsonElement element) {
		if (element == null || !element.isJsonArray()) return List.of();
		var values = new ArrayList<String>();
		for (JsonElement value : (JsonArray) element) if (value.isJsonPrimitive()) values.add(value.getAsString());
		return values;
	}

	private static Map<String, Object> entry(long seqNo, String type, String reason, String hint) {
		var entry = new LinkedHashMap<String, Object>();
		if (reason != null) entry.put("reason", reason);
		else {
			entry.put("seqNo", seqNo);
			entry.put("type", Objects.requireNonNull(type));
		}
		entry.put("hint", hint);
		entry.put("provenance", PROVENANCE);
		return entry;
	}
}
