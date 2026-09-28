package ai.moeru.airicraft.agent.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class EventPolicyState {
	private static final int DEFAULT_INTERVENTION_CAP = 64;

	private final int interventionCap;
	private final ArrayList<EventPolicyRule> rules = new ArrayList<>();
	private final ArrayDeque<EventPolicyIntervention> recentInterventions = new ArrayDeque<>();
	private EventPolicyDecision lastDecision;

	public EventPolicyState() {
		this(DEFAULT_INTERVENTION_CAP);
	}

	public EventPolicyState(int interventionCap) {
		this.interventionCap = Math.max(1, interventionCap);
	}

	public EventPolicyDecision evaluate(SemanticEvent event, boolean bypassed) {
		RuleMatch match = match(event, bypassed);
		recordEvaluation(match, event.timestampMs());
		return match.decision();
	}

	/** Finds the decisive rule without recording it; the latest matching rule wins. */
	public RuleMatch match(SemanticEvent event, boolean bypassed) {
		Objects.requireNonNull(event, "event");
		if (bypassed) {
			return new RuleMatch(-1, EventPolicyDecision.bypass());
		}
		for (int index = rules.size() - 1; index >= 0; index--) {
			EventPolicyRule rule = rules.get(index);
			if (rule.match().matches(event)) {
				return new RuleMatch(index, new EventPolicyDecision(rule.effect(), rule.ruleId(), rule.reason(), false));
			}
		}
		return new RuleMatch(-1, EventPolicyDecision.allow());
	}

	/** Records a {@link #match} result: the matched rule's counters and the last decision. */
	public void recordEvaluation(RuleMatch match, long timestampMs) {
		if (match.ruleIndex() >= 0 && match.ruleIndex() < rules.size()) {
			rules.set(match.ruleIndex(), rules.get(match.ruleIndex()).noteMatched(timestampMs));
		}
		lastDecision = match.decision();
	}

	/** A rule evaluation; {@code ruleIndex} is -1 when no rule matched or the event bypasses rules. */
	public record RuleMatch(int ruleIndex, EventPolicyDecision decision) {
	}

	public void upsert(EventPolicyRule rule) {
		Objects.requireNonNull(rule, "rule");
		String ruleId = rule.ruleId();
		if (ruleId != null) {
			removeRuleId(ruleId);
		}
		rules.add(rule);
	}

	public void clear() {
		rules.clear();
		recentInterventions.clear();
		lastDecision = null;
	}

	public void clearInterventions() {
		recentInterventions.clear();
	}

	public void removeRuleIds(List<String> ruleIds) {
		if (ruleIds == null || ruleIds.isEmpty()) {
			return;
		}
		for (String ruleId : ruleIds) {
			removeRuleId(ruleId);
		}
	}

	public int activeRuleCount() {
		return rules.size();
	}

	public int recentInterventionCount() {
		return recentInterventions.size();
	}

	public List<EventPolicyRule> activeRules() {
		return List.copyOf(rules);
	}

	public List<EventPolicyIntervention> recentInterventions() {
		return List.copyOf(recentInterventions);
	}

	public Optional<EventPolicyDecision> lastDecision() {
		return Optional.ofNullable(lastDecision);
	}

	public void recordIntervention(EventPolicyIntervention intervention) {
		Objects.requireNonNull(intervention, "intervention");
		recentInterventions.addLast(intervention);
		while (recentInterventions.size() > interventionCap) {
			recentInterventions.removeFirst();
		}
		lastDecision = new EventPolicyDecision(
			intervention.effect(),
			intervention.matchedRuleId(),
			intervention.reason(),
			false
		);
	}

	private void removeRuleId(String ruleId) {
		if (ruleId == null || ruleId.isBlank()) {
			return;
		}
		rules.removeIf(rule -> ruleId.equals(rule.ruleId()));
	}
}
