package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.agent.events.EventPolicyDecision;
import java.util.Objects;

/**
 * One routed event's full decision. {@code ruleMatch} is the planner-rule evaluation before the default rule,
 * kept separately because the host records it on the rule store; {@code policy} is the effective planner-rule
 * decision; {@code wake} decides the trigger.
 *
 * @param ruleMatchIndex index of the matching planner rule in the evaluated store, or -1
 */
public record AttentionOutcome(
	EventPolicyDecision ruleMatch,
	int ruleMatchIndex,
	EventPolicyDecision policy,
	boolean emitSemantic,
	WakeDecision wake
) {
	public AttentionOutcome {
		Objects.requireNonNull(ruleMatch, "ruleMatch");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(wake, "wake");
	}

	public AttentionOutcome withWake(WakeDecision replacement) {
		return new AttentionOutcome(ruleMatch, ruleMatchIndex, policy, emitSemantic, replacement);
	}
}
