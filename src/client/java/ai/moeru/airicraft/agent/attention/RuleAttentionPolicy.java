package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.events.EventPolicyDecision;
import ai.moeru.airicraft.agent.events.EventPolicyEffect;
import ai.moeru.airicraft.agent.events.EventPolicyRule;
import ai.moeru.airicraft.agent.events.EventPolicyState;
import ai.moeru.airicraft.agent.events.EventPublisher;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleException;
import ai.moeru.airicraft.rules.RuleModule;
import ai.moeru.airicraft.rules.RuleStepResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Stage B from a sandboxed GraalJS rule module, between the Java constitution and clamp:
 * <ol>
 *   <li>Stage A: direct chat, reset commands and the reflex safety handoff never reach the rules; evaluation
 *   suppression overrides a waking rule decision afterwards.</li>
 *   <li>Stage B: the rule module decides planner rules, the default rule and the ownership and social gates.</li>
 *   <li>Stage C: a protected type keeps its bypass, and the rules may not silence a wake the Java reference would
 *   deliver.</li>
 * </ol>
 * While the engine is cold, or when a step fails, {@link ReferenceAttentionPolicy} decides ({@code FALLBACK}).
 * After three consecutive failures an override reverts to the bundled module. The rule state is owned here, one
 * per runtime; the engine itself is shared and stateless.
 */
public final class RuleAttentionPolicy implements AttentionPolicy {
	public static final String SOURCE = "RuleAttentionPolicy";
	static final int REVERT_AFTER_FAILURES = 3;
	private static final List<String> MATCH_FIELDS = List.of("player", "speaker", "actor", "itemId", "damageTypeId", "attackerName", "state",
		"blockId", "change");

	private final Supplier<AttentionState> state;
	private final Function<SemanticEvent, AttentionEvidence> evidence;
	private final EventPublisher diagnostics;
	private RuleEngine engine;
	private String ruleState = "{}";
	private int consecutiveFailures;
	private long steps;
	private long fallbacks;
	private long failures;
	private long clamps;
	private long reverts;
	private long maxStepNanos;
	private String lastFailure = "";

	public RuleAttentionPolicy(Supplier<AttentionState> state, Function<SemanticEvent, AttentionEvidence> evidence,
		EventPublisher diagnostics, RuleModule module) {
		this.state = Objects.requireNonNull(state, "state");
		this.evidence = Objects.requireNonNull(evidence, "evidence");
		this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
		this.engine = RuleEngine.shared(Objects.requireNonNull(module, "module"));
	}

	@Override
	public AttentionOutcome decide(SemanticEvent event, EventRoutingProfile profile, EventPolicyState rules, boolean plannerEnabled) {
		AttentionState snapshot = state.get();
		AttentionEvidence facts = evidence.apply(event);
		AttentionOutcome reference = ReferenceAttentionPolicy.decide(snapshot, facts, event, profile, rules, plannerEnabled);
		if (ReferenceAttentionPolicy.CONSTITUTION_TYPES.contains(event.type())) return reference;

		AttentionOutcome ruled = runRules(event, profile, rules, plannerEnabled, snapshot, facts);
		if (ruled == null) return asFallback(reference);
		// Stage A precedes the rules: it applies whenever routing lets the event reach the gate, whatever the rules decided,
		// and to any wake the rules deliver, whatever policy effect they claim alongside it.
		boolean reachesGate = profile.triggerEligible() && ruled.policy().effect() != EventPolicyEffect.IGNORE
			&& ruled.policy().effect() != EventPolicyEffect.SEMANTIC_ONLY;
		if ((reachesGate || ruled.wake().wakes()) && snapshot.evaluationSuppressed()
			&& ReferenceAttentionPolicy.EVALUATION_SUPPRESSED.contains(event.type())) {
			ruled = ruled.withWake(WakeDecision.none(AttentionStage.CONSTITUTION, "constitution.evaluation_suppressed",
				"autonomous wakes are suppressed after an evaluation"));
		}
		return clamp(ruled, reference, profile, plannerEnabled).withInputs(snapshot, facts);
	}

	/** Engine and rule status for the bridge debug state and the dashboard. */
	public synchronized Map<String, Object> debugState() {
		var result = new LinkedHashMap<String, Object>();
		result.put("module", engine.module().origin());
		result.put("ready", engine.ready());
		result.put("steps", steps);
		result.put("fallbacks", fallbacks);
		result.put("failures", failures);
		result.put("consecutiveFailures", consecutiveFailures);
		result.put("clamps", clamps);
		result.put("reverts", reverts);
		result.put("rebuilds", engine.rebuilds());
		result.put("maxStepMicros", maxStepNanos / 1_000L);
		result.put("stateBytes", ruleState.getBytes(StandardCharsets.UTF_8).length);
		result.put("lastFailure", lastFailure);
		return result;
	}

	public synchronized RuleModule module() {
		return engine.module();
	}

	/** Switches to {@code module} (an override or the bundled module) with fresh rule state. */
	public synchronized void useModule(RuleModule module) {
		engine = RuleEngine.shared(Objects.requireNonNull(module, "module"));
		ruleState = "{}";
		consecutiveFailures = 0;
	}

	private synchronized AttentionOutcome runRules(SemanticEvent event, EventRoutingProfile profile, EventPolicyState rules,
		boolean plannerEnabled, AttentionState snapshot, AttentionEvidence facts) {
		RuleException loadFailure = engine.loadFailure();
		if (loadFailure != null) {
			fail(event, loadFailure);
			return null;
		}
		if (!engine.ready()) {
			fallbacks++;
			return null;
		}
		List<EventPolicyRule> activeRules = rules.activeRules();
		try {
			RuleStepResult result = engine.step(input(event, profile, plannerEnabled, snapshot, facts, activeRules).toString(), ruleState);
			AttentionOutcome outcome = parse(event, result.decisions(), activeRules);
			ruleState = result.stateJson();
			steps++;
			maxStepNanos = Math.max(maxStepNanos, result.nanos());
			consecutiveFailures = 0;
			return outcome;
		}
		catch (RuleException exception) {
			if ("cold".equals(exception.code())) {
				fallbacks++;
				return null;
			}
			fail(event, exception);
			return null;
		}
	}

	private void fail(SemanticEvent event, RuleException exception) {
		failures++;
		fallbacks++;
		consecutiveFailures++;
		lastFailure = exception.code() + ": " + exception.getMessage();
		String failedModule = engine.module().origin();
		diagnostics.publish(event.tick(), "rules.step_failed", Map.of(
			"module", failedModule,
			"code", exception.code(),
			"message", String.valueOf(exception.getMessage()),
			"eventSeqNo", event.seqNo()
		), SOURCE, null);
		boolean loadFailed = engine.loadFailure() != null;
		if (!engine.module().bundled() && (loadFailed || consecutiveFailures >= REVERT_AFTER_FAILURES)) {
			engine = RuleEngine.shared(RuleModule.bundledAttention());
			ruleState = "{}";
			consecutiveFailures = 0;
			reverts++;
			Airicraft.LOGGER.warn("Attention rules {} failed ({}); reverted to {}", failedModule, lastFailure, engine.module().origin());
			diagnostics.publish(event.tick(), "rules.reverted", Map.of(
				"from", failedModule,
				"to", engine.module().origin(),
				"reason", loadFailed ? "load_failed" : "consecutive_step_failures"
			), SOURCE, null);
		}
	}

	private static JsonObject input(SemanticEvent event, EventRoutingProfile profile, boolean plannerEnabled,
		AttentionState snapshot, AttentionEvidence facts, List<EventPolicyRule> rules) {
		var input = new JsonObject();
		input.addProperty("tick", event.tick());
		input.addProperty("seed", event.seqNo());
		var attention = new JsonObject();
		attention.addProperty("proactiveSocialMode", snapshot.proactiveSocialMode());
		attention.addProperty("reflexOwnsActuation", snapshot.reflexOwnsActuation());
		attention.addProperty("activeJobType", snapshot.activeJobType());
		attention.addProperty("activeJobIdle", snapshot.activeJobIdle());
		attention.addProperty("activeJobTerminal", snapshot.activeJobTerminal());
		attention.addProperty("pendingCraftToolResult", snapshot.pendingCraftToolResult());
		var targets = new JsonArray();
		snapshot.activeJobTargets().forEach(targets::add);
		attention.add("activeJobTargets", targets);
		attention.addProperty("routineWakesHeld", snapshot.routineWakesHeld());
		attention.addProperty("goalBlocked", snapshot.goalBlocked());
		input.add("attention", attention);
		var plannerRules = new JsonArray();
		for (int index = 0; index < rules.size(); index++) {
			var rule = rules.get(index);
			var entry = new JsonObject();
			entry.addProperty("index", index);
			entry.addProperty("ruleId", rule.ruleId());
			entry.addProperty("effect", rule.effect().name());
			entry.addProperty("reason", rule.reason());
			var match = new JsonObject();
			var fields = rule.match();
			match.addProperty("eventType", fields.eventType());
			match.addProperty("player", fields.player());
			match.addProperty("speaker", fields.speaker());
			match.addProperty("actor", fields.actor());
			match.addProperty("itemId", fields.itemId());
			match.addProperty("damageTypeId", fields.damageTypeId());
			match.addProperty("attackerName", fields.attackerName());
			entry.add("match", match);
			plannerRules.add(entry);
		}
		input.add("plannerRules", plannerRules);
		var projected = new JsonObject();
		projected.addProperty("seqNo", event.seqNo());
		projected.addProperty("type", event.type());
		// Rules see the payload fields the policy reads, as strings, never live objects.
		var fields = new JsonObject();
		for (String key : MATCH_FIELDS) {
			Object value = event.payload().get(key);
			if (value != null) fields.addProperty(key, String.valueOf(value));
		}
		projected.add("fields", fields);
		var routing = new JsonObject();
		routing.addProperty("semantic", profile.semanticEligible());
		routing.addProperty("trigger", profile.triggerEligible());
		routing.addProperty("bypass", profile.policyBypass());
		projected.add("profile", routing);
		projected.addProperty("plannerEnabled", plannerEnabled);
		var evidence = new JsonObject();
		evidence.addProperty("addressedToAgent", facts.addressedToAgent());
		evidence.addProperty("resetCommand", facts.resetCommand());
		evidence.addProperty("senderWithinChatDistance", facts.senderWithinChatDistance());
		projected.add("evidence", evidence);
		var events = new JsonArray();
		events.add(projected);
		input.add("events", events);
		return input;
	}

	private static AttentionOutcome parse(SemanticEvent event, JsonArray decisions, List<EventPolicyRule> rules) throws RuleException {
		JsonObject decision = null;
		try {
			for (JsonElement element : decisions) {
				if (element.isJsonObject() && element.getAsJsonObject().has("seqNo")
					&& element.getAsJsonObject().get("seqNo").getAsLong() == event.seqNo()) {
					decision = element.getAsJsonObject();
				}
			}
		}
		catch (RuntimeException exception) {
			// A string, null or object seqNo must fail the step like any other malformed decision, not the routing.
			throw new RuleException("malformed_decision", "a decision has an invalid seqNo: " + exception.getMessage());
		}
		if (decision == null) throw new RuleException("missing_decision", "no decision for event " + event.seqNo());
		try {
			EventPolicyState.RuleMatch ruleMatch = policy(decision.getAsJsonObject("ruleMatch"), rules);
			EventPolicyState.RuleMatch effective = policy(decision.getAsJsonObject("policy"), rules);
			var wake = decision.getAsJsonObject("wake");
			String ruleId = wake.get("ruleId").getAsString();
			String reason = wake.has("reason") && !wake.get("reason").isJsonNull() ? wake.get("reason").getAsString() : "";
			if (ruleId.isBlank() || ruleId.length() > 128 || reason.length() > 256) throw new IllegalArgumentException("rule id or reason out of bounds");
			return new AttentionOutcome(ruleMatch.decision(), ruleMatch.ruleIndex(), effective.decision(),
				decision.get("emitSemantic").getAsBoolean(),
				new WakeDecision(Delivery.valueOf(wake.get("delivery").getAsString()), Urgency.valueOf(wake.get("urgency").getAsString()),
					AttentionStage.RULES, ruleId, reason));
		}
		catch (RuntimeException exception) {
			throw new RuleException("malformed_decision", "decision for event " + event.seqNo() + " is invalid: " + exception.getMessage());
		}
	}

	/** A rule-evaluation record; a planner rule index must name that rule, so the host records the right match. */
	private static EventPolicyState.RuleMatch policy(JsonObject json, List<EventPolicyRule> rules) {
		int index = json.get("ruleIndex").getAsInt();
		String ruleId = json.get("ruleId").isJsonNull() ? null : json.get("ruleId").getAsString();
		String reason = json.get("reason").isJsonNull() ? null : json.get("reason").getAsString();
		if (index < -1 || index >= rules.size() || index >= 0 && !Objects.equals(ruleId, rules.get(index).ruleId())) {
			throw new IllegalArgumentException("ruleIndex " + index + " does not name planner rule " + ruleId);
		}
		var decision = new EventPolicyDecision(EventPolicyEffect.valueOf(json.get("effect").getAsString()), ruleId, reason,
			json.get("bypassed").getAsBoolean());
		return new EventPolicyState.RuleMatch(index, decision);
	}

	/** Stage C: protected types keep their bypass, and rules cannot silence a wake the reference would deliver. */
	private synchronized AttentionOutcome clamp(AttentionOutcome ruled, AttentionOutcome reference, EventRoutingProfile profile,
		boolean plannerEnabled) {
		if (!profile.policyBypass()) return ruled;
		AttentionOutcome clamped = ruled;
		boolean bypassKept = ruled.policy().bypassed() && ruled.ruleMatch().bypassed()
			&& ruled.emitSemantic() == (plannerEnabled && profile.semanticEligible());
		if (!bypassKept) {
			clamped = new AttentionOutcome(EventPolicyDecision.bypass(), -1, EventPolicyDecision.bypass(),
				plannerEnabled && profile.semanticEligible(), ruled.wake());
		}
		if (!clamped.wake().wakes() && reference.wake().wakes()) {
			WakeDecision restored = reference.wake();
			clamped = clamped.withWake(new WakeDecision(restored.delivery(), restored.urgency(), AttentionStage.CLAMP,
				restored.ruleId(), "protected type: a rule may not silence this wake"));
		}
		if (clamped != ruled) clamps++;
		return clamped;
	}

	private AttentionOutcome asFallback(AttentionOutcome reference) {
		WakeDecision wake = reference.wake();
		if (wake.stage() != AttentionStage.RULES) return reference;
		return reference.withWake(new WakeDecision(wake.delivery(), wake.urgency(), AttentionStage.FALLBACK, wake.ruleId(), wake.reason()));
	}

	static Set<String> matchFields() {
		return Set.copyOf(MATCH_FIELDS);
	}
}
