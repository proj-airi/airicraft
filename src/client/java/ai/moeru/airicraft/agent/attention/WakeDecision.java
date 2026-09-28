package ai.moeru.airicraft.agent.attention;

import java.util.Objects;

/** Whether and how one event may wake the planner, with the rule that decided it. */
public record WakeDecision(Delivery delivery, Urgency urgency, AttentionStage stage, String ruleId, String reason) {
	public WakeDecision {
		Objects.requireNonNull(delivery, "delivery");
		Objects.requireNonNull(urgency, "urgency");
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(ruleId, "ruleId");
		reason = reason == null ? "" : reason;
	}

	public static WakeDecision none(AttentionStage stage, String ruleId, String reason) {
		return new WakeDecision(Delivery.NONE, Urgency.LOW, stage, ruleId, reason);
	}

	public static WakeDecision immediate(Urgency urgency, AttentionStage stage, String ruleId, String reason) {
		return new WakeDecision(Delivery.IMMEDIATE, urgency, stage, ruleId, reason);
	}

	public boolean wakes() {
		return delivery != Delivery.NONE;
	}
}
