package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.agent.dialogue.DialogueSpeakerLabels;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The planner-facing wording of each wake. The attention policy decides whether an event wakes; this class only
 * writes the trigger text, keyed by event type. It returns {@code null} when the payload lacks what the text needs.
 * The text reaches the model only without a decision context; with {@code observe}, an event wake is a reference
 * ({@code observe.wake}) to an {@code observe.events} entry, and coaching comes from {@code DecisionHints}.
 */
public final class WakePresenter {
	private final Supplier<String> reflexState;
	private final Supplier<Object> reflexPolicy;

	/** The survival-reflex state name and policy are reported in the reflex-resolved text. */
	public WakePresenter(Supplier<String> reflexState, Supplier<Object> reflexPolicy) {
		this.reflexState = Objects.requireNonNull(reflexState, "reflexState");
		this.reflexPolicy = Objects.requireNonNull(reflexPolicy, "reflexPolicy");
	}

	/** The planner trigger for a routed event that the attention policy lets wake, or {@code null} without one. */
	public PlannerTrigger present(SemanticEvent event) {
		String eventType = event.type();
		if (eventType == null) {
			return null;
		}
		return switch (eventType) {
			case "social.player_spoke" -> createPlayerSpokeTrigger(event);
			case "social.player_addressed_agent" -> createAddressedChatTrigger(event);
			case "social.local_controller_spoke" -> createLocalControllerTrigger(event);
			case "social.system_message" -> createSystemTrigger(event);
			case "pickup.item_picked_up" -> createPickupTrigger(event);
			case "social.item_offered" -> createItemOfferTrigger(event);
			case "crafting.item_crafted" -> createCraftTrigger(event);
			case "combat.damage_taken" -> createDamageTrigger(event);
			case "player.physical" -> createPhysicalTrigger(event);
			case "reflex.resolved" -> createReflexResolvedTrigger(event);
			case "smelting.output_ready" -> createSmeltingOutputReadyTrigger(event);
			case "task.blocked" -> createTaskBlockedTrigger(event);
			case "action_graph.goal_suspended" -> createActionGraphSuspendedTrigger(event);
			case "action_graph.goal_terminal" -> createActionGraphTerminalTrigger(event);
			case "perception.block_noticed", "perception.item_noticed", "perception.entity_noticed", "perception.entity_lost",
				"perception.environment_changed" -> createPerceptionTrigger(event);
			default -> null;
		};
	}

	private PlannerTrigger createPlayerSpokeTrigger(SemanticEvent event) {
		String player = stringPayloadValue(event.payload(), "player");
		String message = stringPayloadValue(event.payload(), "message");
		if (player == null || message == null) {
			return null;
		}
		return PlannerTrigger.autonomous(PlannerTriggerType.CHAT, player, message, event.tick(), event.timestampMs(), "ambient_player_chat");
	}

	private PlannerTrigger createAddressedChatTrigger(SemanticEvent event) {
		String player = stringPayloadValue(event.payload(), "player");
		String message = stringPayloadValue(event.payload(), "message");
		if (player == null || message == null) {
			return null;
		}
		return PlannerTrigger.direct(PlannerTriggerType.CHAT, player, message, event.tick(), event.timestampMs());
	}

	private PlannerTrigger createLocalControllerTrigger(SemanticEvent event) {
		String message = stringPayloadValue(event.payload(), "message");
		if (message == null) {
			return null;
		}
		return PlannerTrigger.direct(
			PlannerTriggerType.CHAT,
			DialogueSpeakerLabels.SAME_CLIENT_ADMIN,
			message,
			event.tick(),
			event.timestampMs()
		);
	}

	private PlannerTrigger createSystemTrigger(SemanticEvent event) {
		String message = stringPayloadValue(event.payload(), "message");
		if (message == null) {
			return null;
		}
		return PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "server", message, event.tick(), event.timestampMs(), "system_message");
	}

	private PlannerTrigger createActionGraphSuspendedTrigger(SemanticEvent event) {
		String executionId = stringPayloadValue(event.payload(), "executionId");
		String pendingWatch = stringPayloadValue(event.payload(), "pendingWatch");
		if (executionId == null) {
			return null;
		}
		String message = "ACTION GRAPH SUSPENDED: executionId=" + executionId
			+ " pendingWatch=" + (pendingWatch == null ? "" : pendingWatch)
			+ ". The goal released foreground actuation while it waits for a world condition. "
			+ "You may send one short chat message explaining the wait, start at most one useful new high-level goal with start_action_goal, or simply acknowledge without taking action. "
			+ "Do not invent filler work. The suspended goal will resume automatically after its condition is fulfilled and current foreground work finishes.";
		return PlannerTrigger.autonomous(
			PlannerTriggerType.SYSTEM,
			"action_graph",
			message,
			event.tick(),
			event.timestampMs(),
			"action_graph_suspended:" + executionId
		);
	}

	private PlannerTrigger createActionGraphTerminalTrigger(SemanticEvent event) {
		String executionId = stringPayloadValue(event.payload(), "executionId");
		if (executionId == null) {
			return null;
		}
		String goal = stringPayloadValue(event.payload(), "goal");
		String failureCode = stringPayloadValue(event.payload(), "failureCode");
		String failureMessage = stringPayloadValue(event.payload(), "message");
		String failedPrimitive = stringPayloadValue(event.payload(), "failedPrimitive");
		String failedTarget = stringPayloadValue(event.payload(), "failedTarget");
		Object failedArgs = event.payload().get("failedArgs");
		String normalizedCode = failureCode == null ? "failed" : failureCode;
		String message = "ACTION GRAPH FAILED: executionId=" + executionId
			+ " goal=" + (goal == null ? "" : goal)
			+ " failedPrimitive=" + (failedPrimitive == null ? "" : failedPrimitive)
			+ " failedTarget=" + (failedTarget == null ? "" : failedTarget)
			+ " failedArgs=" + (failedArgs == null ? "{}" : failedArgs)
			+ " failureCode=" + normalizedCode
			+ " message=" + (failureMessage == null ? "" : failureMessage)
			+ ". Explain the terminal failure accurately. ";
		if ("unknown_acquisition_method".equals(normalizedCode) || "unsupported_resource_kind".equals(normalizedCode)) {
			message += "Airicraft has no registered acquisition method for this request. This requested capability is unsupported; do not retry it unchanged or invent an acquisition method. Another observed supported action may still serve the objective.";
		}
		else {
			message += "Do not claim completion. Assess the identified failure and choose a supported next attempt toward the objective.";
		}
		return PlannerTrigger.autonomous(
			PlannerTriggerType.SYSTEM,
			"action_graph",
			message,
			event.tick(),
			event.timestampMs(),
			"action_graph_terminal:" + executionId
		);
	}

	private PlannerTrigger createPickupTrigger(SemanticEvent event) {
		String itemId = stringPayloadValue(event.payload(), "itemId");
		Float count = floatPayloadValue(event.payload(), "count");
		if (itemId == null || count == null) {
			return null;
		}
		return PlannerTrigger.autonomous(
			PlannerTriggerType.PICKUP,
			"self",
			"Picked up " + formatDecimal(count) + "x " + itemId + ".",
			event.tick(),
			event.timestampMs(),
			"pickup:" + itemId
		);
	}

	private PlannerTrigger createCraftTrigger(SemanticEvent event) {
		String itemId = stringPayloadValue(event.payload(), "itemId");
		Float count = floatPayloadValue(event.payload(), "count");
		if (itemId == null || count == null) {
			return null;
		}
		return PlannerTrigger.autonomous(
			PlannerTriggerType.CRAFT,
			"self",
			"I crafted " + formatDecimal(count) + "x " + itemId + ".",
			event.tick(),
			event.timestampMs(),
			"craft:" + itemId
		);
	}

	private PlannerTrigger createDamageTrigger(SemanticEvent event) {
		Map<String, Object> payload = event.payload();
		String damageTypeId = stringPayloadValue(payload, "damageTypeId");
		String attackerName = stringPayloadValue(payload, "attackerName");
		Float amount = floatPayloadValue(payload, "amount");
		Float resultingHealth = floatPayloadValue(payload, "healthAfter");
		if (amount == null && resultingHealth == null) {
			return null;
		}

		StringBuilder message = new StringBuilder("I took ")
			.append(formatDecimal(amount == null ? 0.0F : amount))
			.append(" damage");
		if (attackerName != null) {
			message.append(" from ").append(attackerName);
		}
		else if (damageTypeId != null) {
			message.append(" from ").append(damageTypeId);
		}
		if (resultingHealth != null) {
			message.append(" and dropped to ").append(formatDecimal(resultingHealth)).append(" health");
		}
		message.append('.');
		return PlannerTrigger.autonomous(
			PlannerTriggerType.DAMAGE,
			"self",
			message.toString(),
			event.tick(),
			event.timestampMs(),
			"damage"
		);
	}

	private PlannerTrigger createItemOfferTrigger(SemanticEvent event) {
		return PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "self",
			"Possible item offer: " + event.payload().get("player") + " dropped " + event.payload().get("count")
				+ "x " + event.payload().get("itemId") + " toward me at " + event.payload().get("position")
				+ ". The player and intent are inferred from spawn position and motion; this is not confirmed pickup."
				+ " Decide whether to collect or acknowledge the items using current world evidence.",
			event.tick(), event.timestampMs(), "item_offer:" + event.payload().get("playerUuid"),
			new com.google.gson.Gson().toJsonTree(event.payload()));
	}

	/** A noticed thing. With a decision context the model reads the event itself; this text is the fallback view. */
	private PlannerTrigger createPerceptionTrigger(SemanticEvent event) {
		var payload = event.payload();
		Object subject = payload.get("blockId") != null ? payload.get("blockId")
			: payload.get("itemId") != null ? payload.get("itemId")
			: payload.get("entityType") != null ? payload.get("entityType") : payload.get("change");
		return PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "self",
			"Noticed (" + event.type() + "): " + new com.google.gson.Gson().toJson(payload)
				+ ". This is an observation, not a request; act on it only if it serves the current situation.",
			event.tick(), event.timestampMs(), "perception:" + event.type() + ":" + subject,
			new com.google.gson.Gson().toJsonTree(payload));
	}

	private PlannerTrigger createPhysicalTrigger(SemanticEvent event) {
		return PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "self",
			"Physical observation: " + new com.google.gson.Gson().toJson(event.payload())
				+ ". These are observed changes, not proof of an involuntary cause. Use the actual position and task context to decide whether recovery is needed.",
			event.tick(), event.timestampMs(), "physical:" + event.payload().get("kind"));
	}

	private PlannerTrigger createReflexResolvedTrigger(SemanticEvent event) {
		String holdId = stringPayloadValue(event.payload(), "holdId");
		String cause = stringPayloadValue(event.payload(), "cause");
		String reason = stringPayloadValue(event.payload(), "reason");
		String nextState = stringPayloadValue(event.payload(), "nextState");
		String message = "SURVIVAL UPDATE: reflex resolved cause=" + (cause == null ? "unknown" : cause)
			+ " reason=" + (reason == null ? "safe" : reason)
			+ " state=" + (nextState == null ? reflexState.get() : nextState)
			+ " holdId=" + (holdId == null ? "none" : holdId)
			+ (holdId == null
				? ". Review the consolidated safety episode; no interrupted task requires resumption."
				: ". Review the consolidated safety episode and use continue to retain and resume the plan, or clear_queue to abort and replace it.");
		if (event.payload().get("combatSummary") instanceof Map<?, ?> combatSummary) {
			message += " " + combatSummary.get("text") + " Resolution position=" + event.payload().get("position")
				+ ". Use these recorded outcomes directly; re-observe only facts that remain unknown or may have changed.";
		}
		if ("combat_stalemate".equals(reason)) {
			message += " Combat is still unresolved and made no target-health or closing progress for "
				+ event.payload().get("noProgressTicks") + " ticks. Position=" + event.payload().get("position")
				+ "; remainingThreats=" + event.payload().get("remainingThreats")
				+ ". Inspect local geometry and inventory, then execute a concrete escape or cover plan. Do not resume the same stalled pursuit."
				+ " You have a bounded recovery window: ordinary mob pressure will not preempt your action for 600 ticks after releasing the hold."
				+ " Critical health, an imminent creeper, or drowning can interrupt. Mining steps or towering may be needed to escape a pit.";
		}
		if ("combat_approach_stalled".equals(reason)) {
			message += " Combat is unresolved: pursuit made no closer approach to the distant threats for "
				+ event.payload().get("noProgressTicks") + " ticks. Position=" + event.payload().get("position")
				+ "; remainingThreats=" + event.payload().get("remainingThreats")
				+ ". Choose a tactical next step from fresh geometry and inventory; repeated pursuit has made no progress."
				+ " These distant attackers are deferred while you act; close danger, incoming projectiles, damage or a new attacker reactivate defense.";
		}
		message += " Automatic reflex policy=" + reflexPolicy.get()
			+ ". Use configure_reflex to read or override it for a deliberate tactic; policy changes do not resume paused work.";
		return PlannerTrigger.autonomous(
			PlannerTriggerType.SYSTEM,
			"survival_runtime",
			message,
			event.tick(),
			event.timestampMs(),
			"survival_reflex_resolved",
			new com.google.gson.Gson().toJsonTree(event.payload())
		);
	}

	private PlannerTrigger createSmeltingOutputReadyTrigger(SemanticEvent event) {
		Map<String, Object> payload = event.payload();
		String processId = stringPayloadValue(payload, "processId");
		String outputItemId = stringPayloadValue(payload, "outputItemId");
		Float outputCount = floatPayloadValue(payload, "outputCount");
		String station = stringPayloadValue(payload, "station");
		boolean estimated = booleanPayloadValue(payload, "estimated");
		if (processId == null || outputItemId == null || outputCount == null) {
			return null;
		}
		StringBuilder message = new StringBuilder("Smelting output ready: processId=")
			.append(processId)
			.append(" output=")
			.append(outputItemId)
			.append("x")
			.append(formatDecimal(outputCount));
		if (estimated) {
			message.append(" estimated=true");
		}
		if (station != null) {
			message.append(" station=").append(station);
		}
		message.append(". Output still needs collection: call collect_smelted_items with this processId, then verify inventory.");
		return PlannerTrigger.autonomous(
			PlannerTriggerType.SYSTEM,
			"runtime",
			message.toString(),
			event.tick(),
			event.timestampMs(),
			"smelting_output:" + processId
		);
	}

	private PlannerTrigger createTaskBlockedTrigger(SemanticEvent event) {
		Map<String, Object> payload = event.payload();
		String taskType = stringPayloadValue(payload, "taskType");
		String resourceKind = stringPayloadValue(payload, "resourceKind");
		String blockedReason = stringPayloadValue(payload, "blockedReason");
		Float collected = floatPayloadValue(payload, "collected");
		Float remaining = floatPayloadValue(payload, "remaining");
		if (taskType == null || blockedReason == null) {
			return null;
		}

		StringBuilder message = new StringBuilder("Task blocked: taskType=").append(taskType);
		if (resourceKind != null) {
			message.append(" resourceKind=").append(resourceKind);
		}
		message.append(" reason=").append(blockedReason);
		if (collected != null) {
			message.append(" collected=").append(formatDecimal(collected));
		}
		if (remaining != null) {
			message.append(" remaining=").append(formatDecimal(remaining));
		}
		message.append('.');
		return PlannerTrigger.autonomous(
			PlannerTriggerType.SYSTEM,
			"runtime",
			message.toString(),
			event.tick(),
			event.timestampMs(),
			"task_blocked"
		);
	}

	private static String stringPayloadValue(Map<String, Object> payload, String key) {
		if (payload == null) {
			return null;
		}
		Object value = payload.get(key);
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value);
		return text.isBlank() ? null : text;
	}

	private static Float floatPayloadValue(Map<String, Object> payload, String key) {
		if (payload == null) {
			return null;
		}
		Object value = payload.get(key);
		if (value instanceof Number number) {
			return number.floatValue();
		}
		if (value == null) {
			return null;
		}
		try {
			return Float.parseFloat(String.valueOf(value));
		}
		catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static boolean booleanPayloadValue(Map<String, Object> payload, String key) {
		if (payload == null) {
			return false;
		}
		Object value = payload.get(key);
		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}
		return value != null && Boolean.parseBoolean(String.valueOf(value));
	}

	private static String formatDecimal(float value) {
		if (Math.abs(value - Math.round(value)) < 0.001F) {
			return Integer.toString(Math.round(value));
		}
		String text = String.format(java.util.Locale.ROOT, "%.2f", value);
		int trimIndex = text.length();
		while (trimIndex > 0 && text.charAt(trimIndex - 1) == '0') {
			trimIndex--;
		}
		if (trimIndex > 0 && text.charAt(trimIndex - 1) == '.') {
			trimIndex--;
		}
		return text.substring(0, trimIndex);
	}
}
