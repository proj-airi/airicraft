package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable game-thread observation. Evidence exists independently of a planner wakeup. */
public record PlannerDecisionContext(
	String worldSessionId, long tick, long serverTick, String decisionOwner,
	String actuatorOwner, Map<String, Object> current, SemanticEventQueryResult observations
) {
	public PlannerDecisionContext {
		Objects.requireNonNull(worldSessionId);
		Objects.requireNonNull(decisionOwner);
		Objects.requireNonNull(actuatorOwner);
		current = Map.copyOf(current);
		Objects.requireNonNull(observations);
	}

	public PlannerDecisionContext forOwner(String owner) {
		return new PlannerDecisionContext(worldSessionId, tick, serverTick, owner, actuatorOwner, current, observations);
	}

	public Map<String, Object> observation(long sinceSequence) {
		return observation(sinceSequence, false);
	}

	/** Payload of an {@code observe} result: the full {@code current} state plus relevant events after the cursor. */
	public Map<String, Object> observation(long sinceSequence, boolean refresh) {
		var events = observations.events().stream()
			.filter(event -> event.seqNo() > sinceSequence && relevant(event.type()))
			.map(event -> Map.of("seqNo", event.seqNo(), "tick", event.tick(), "type", event.type(), "payload",
				event.type().equals("work.changed") ? ai.moeru.airicraft.agent.work.WorkSnapshot.summarize(event.payload()) : event.payload())).toList();
		boolean gap = observations.oldestSeqNo() > Math.max(1, sinceSequence + 1);
		var facts = new java.util.LinkedHashMap<>(current);
		if (current.get("work") instanceof List<?> work) {
			facts.put("work", work.stream().map(value -> (Map<?, ?>) value)
				.filter(value -> refresh || gap || sinceSequence == 0 || !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(String.valueOf(value.get("state"))))
				.map(ai.moeru.airicraft.agent.work.WorkSnapshot::summarize).toList());
		}
		var payload = new java.util.LinkedHashMap<String, Object>();
		payload.put("worldSessionId", worldSessionId);
		payload.put("tick", tick);
		payload.put("serverTick", serverTick);
		payload.put("decisionOwner", decisionOwner);
		payload.put("actuatorOwner", actuatorOwner);
		payload.put("current", facts);
		if (refresh || gap) payload.put("stateBaseline", true);
		payload.put("afterEventSequence", sinceSequence);
		payload.put("throughEventSequence", observations.latestSeqNo());
		if (gap) payload.put("missingEventRange", Map.of("from", sinceSequence + 1, "to", observations.oldestSeqNo() - 1));
		payload.put("events", events);
		return payload;
	}

	private static boolean relevant(String type) {
		return List.of("task.", "work.", "player.", "combat.", "pickup.", "crafting.", "smelting.",
			"container.", "interaction.", "objective.", "policy.", "inventory.", "reflex.", "survival.", "session.", "lighting.", "food.").stream().anyMatch(type::startsWith);
	}
}
