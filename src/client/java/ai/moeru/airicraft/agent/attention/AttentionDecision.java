package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.agent.events.EventPolicyRule;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import java.util.List;

/**
 * One recorded attention decision. {@code wakeProduced} is whether a planner trigger resulted; it can be false
 * for a waking decision when the trigger factory rejects an invalid payload. {@code inputs} holds what the policy
 * decided from, so a recorded run can be replayed; it is {@code null} for raw-only events.
 */
public record AttentionDecision(
	long seqNo,
	long tick,
	String type,
	boolean emitSemantic,
	Delivery delivery,
	Urgency urgency,
	AttentionStage stage,
	String ruleId,
	String reason,
	boolean wakeProduced,
	Inputs inputs
) {
	public AttentionDecision(long seqNo, long tick, String type, boolean emitSemantic, Delivery delivery, Urgency urgency,
		AttentionStage stage, String ruleId, String reason, boolean wakeProduced) {
		this(seqNo, tick, type, emitSemantic, delivery, urgency, stage, ruleId, reason, wakeProduced, null);
	}

	/** The decision without its replay inputs, for compact debug views. */
	public AttentionDecision withoutInputs() {
		return inputs == null ? this
			: new AttentionDecision(seqNo, tick, type, emitSemantic, delivery, urgency, stage, ruleId, reason, wakeProduced);
	}

	/**
	 * Everything a policy decision depends on besides the event itself.
	 *
	 * @param state runtime facts, or {@code null} when the policy read none
	 * @param plannerRules the planner-authored rules in evaluation order
	 */
	public record Inputs(
		AttentionState state,
		AttentionEvidence evidence,
		boolean plannerEnabled,
		EventRoutingProfile profile,
		List<EventPolicyRule> plannerRules
	) {
		public Inputs {
			plannerRules = plannerRules == null ? List.of() : List.copyOf(plannerRules);
		}
	}
}
