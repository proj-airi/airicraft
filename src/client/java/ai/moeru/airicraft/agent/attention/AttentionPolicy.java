package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.agent.events.EventPolicyDecision;
import ai.moeru.airicraft.agent.events.EventPolicyState;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Decides, without side effects, whether one routed event feeds the planner's semantic input and whether it may
 * wake the planner. The caller applies the outcome: rule counters, intervention records, the semantic feed and
 * the trigger factory.
 */
@FunctionalInterface
public interface AttentionPolicy {
	AttentionOutcome decide(SemanticEvent event, EventRoutingProfile profile, EventPolicyState rules, boolean plannerEnabled);

	/**
	 * Catalog routing (G1) and planner-authored rules (G2) only; every trigger-eligible event may wake.
	 * {@code defaultRule} supplies the bundled default decision when no planner rule matches, or {@code null}.
	 */
	static AttentionPolicy routingOnly(BiFunction<SemanticEvent, EventRoutingProfile, EventPolicyDecision> defaultRule) {
		Objects.requireNonNull(defaultRule, "defaultRule");
		return (event, profile, rules, plannerEnabled) -> route(event, profile, rules, plannerEnabled, defaultRule.apply(event, profile));
	}

	/**
	 * Shared G1/G2 routing. A planner rule wins over {@code defaultDecision}; bypassed types ignore both.
	 * The wake is {@code IMMEDIATE} when the event is trigger-eligible after rules, otherwise {@code NONE}.
	 */
	static AttentionOutcome route(SemanticEvent event, EventRoutingProfile profile, EventPolicyState rules,
		boolean plannerEnabled, EventPolicyDecision defaultDecision) {
		EventPolicyState.RuleMatch match = rules.match(event, profile.policyBypass());
		EventPolicyDecision decision = match.decision();
		// As before the refactor: a rule without an id does not shadow the default rule.
		if (!decision.bypassed() && decision.matchedRuleId() == null && defaultDecision != null) {
			decision = defaultDecision;
		}
		boolean emitSemantic = plannerEnabled && profile.semanticEligible();
		boolean emitTrigger = profile.triggerEligible();
		switch (decision.effect()) {
			case IGNORE -> {
				emitSemantic = false;
				emitTrigger = false;
			}
			case SEMANTIC_ONLY -> emitTrigger = false;
			case TRIGGER_ONLY -> emitSemantic = false;
			case ALLOW -> {
			}
		}
		String ruleId = decision.intervened()
			? (decision.matchedRuleId() == null ? "planner_rule" : decision.matchedRuleId())
			: profile.triggerEligible() ? "catalog.trigger" : "catalog.semantic";
		String reason = decision.intervened() ? decision.effect().name().toLowerCase(java.util.Locale.ROOT) : "";
		WakeDecision wake = emitTrigger
			? WakeDecision.immediate(Urgency.LOW, AttentionStage.RULES, ruleId, reason)
			: WakeDecision.none(AttentionStage.RULES, ruleId, reason);
		return new AttentionOutcome(match.decision(), match.ruleIndex(), decision, emitSemantic, wake);
	}
}
