package ai.moeru.airicraft.agent.rules;

import ai.moeru.airicraft.agent.attention.AttentionDecision;
import ai.moeru.airicraft.agent.attention.AttentionInputs;
import ai.moeru.airicraft.agent.attention.AttentionOutcome;
import ai.moeru.airicraft.agent.attention.RuleAttentionPolicy;
import ai.moeru.airicraft.agent.events.EventPolicyRule;
import ai.moeru.airicraft.agent.events.EventPolicyState;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.perception.SaliencePolicy;
import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleException;
import ai.moeru.airicraft.rules.RuleStepResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Replays recent history through the module that is running and the module the planner wants, and reports what would
 * have changed (spec 4.12). Both modules start from an empty state, so the comparison is like for like; the live state
 * (a half-spent budget, say) is not copied. Runs on a worker, never on the tick.
 */
public final class RuleDryRun {
	public static final int ATTENTION_EVENTS = 200;
	public static final int SALIENCE_STEPS = 64;
	static final int EXAMPLES = 10;

	private RuleDryRun() {
	}

	/**
	 * What a replay found.
	 *
	 * @param replayed history entries run through both modules
	 * @param skipped entries that could not be replayed (no recorded inputs, or the event has left the log)
	 * @param changed entries whose outcome differs
	 * @param gained wakes (attention) or percepts (salience) the new module adds
	 * @param lost wakes or percepts the new module removes
	 * @param newFailures steps the new module failed; any failure rejects the edit
	 * @param byType gained, lost and changed per event or percept type
	 */
	public record Diff(String hook, int replayed, int skipped, int changed, int gained, int lost, int newFailures,
		String firstFailure, Map<String, Map<String, Integer>> byType, List<Map<String, Object>> examples) {
		public boolean failed() {
			return newFailures > 0;
		}

		public Map<String, Object> toMap() {
			var map = new LinkedHashMap<String, Object>();
			map.put("hook", hook);
			map.put("replayed", replayed);
			map.put("skipped", skipped);
			map.put("changed", changed);
			map.put(hook.equals("attention") ? "wakesGained" : "perceptsGained", gained);
			map.put(hook.equals("attention") ? "wakesLost" : "perceptsLost", lost);
			map.put("newFailures", newFailures);
			if (firstFailure != null) map.put("firstFailure", firstFailure);
			map.put("byType", byType);
			map.put("examples", examples);
			return map;
		}
	}

	private static final class Tally {
		final Map<String, Map<String, Integer>> byType = new TreeMap<>();
		final List<Map<String, Object>> examples = new ArrayList<>();
		int replayed;
		int skipped;
		int changed;
		int gained;
		int lost;
		int failures;
		String firstFailure;

		void type(String type, String key, int amount) {
			byType.computeIfAbsent(type, ignored -> new LinkedHashMap<>()).merge(key, amount, Integer::sum);
		}

		void example(Map<String, Object> example) {
			if (examples.size() < EXAMPLES) examples.add(example);
		}

		Diff diff(String hook) {
			return new Diff(hook, replayed, skipped, changed, gained, lost, failures, firstFailure, byType, List.copyOf(examples));
		}
	}

	/**
	 * Re-decides the latest recorded attention decisions with both engines. {@code decisions} are oldest first; the
	 * newest {@value #ATTENTION_EVENTS} that carry their inputs and whose event is still in {@code events} are run.
	 */
	public static Diff attention(List<AttentionDecision> decisions, Map<Long, SemanticEvent> events, RuleEngine oldEngine,
		RuleEngine newEngine) {
		var tally = new Tally();
		var runnable = new ArrayList<AttentionDecision>();
		for (AttentionDecision decision : decisions) {
			AttentionDecision.Inputs inputs = decision.inputs();
			boolean complete = inputs != null && inputs.state() != null && inputs.evidence() != null && inputs.profile() != null;
			if (complete && events.containsKey(decision.seqNo())) runnable.add(decision);
			else tally.skipped++;
		}
		if (runnable.size() > ATTENTION_EVENTS) runnable = new ArrayList<>(runnable.subList(runnable.size() - ATTENTION_EVENTS, runnable.size()));

		var current = new AttentionInputs[1];
		var scratch = new SemanticEventBuffer(64);
		var before = RuleAttentionPolicy.forReplay(() -> current[0].state(), event -> current[0].evidence(), scratch, oldEngine);
		var after = RuleAttentionPolicy.forReplay(() -> current[0].state(), event -> current[0].evidence(), scratch, newEngine);
		long failuresSeen = 0;
		for (AttentionDecision recorded : runnable) {
			AttentionDecision.Inputs inputs = recorded.inputs();
			SemanticEvent event = events.get(recorded.seqNo());
			current[0] = new AttentionInputs(inputs.state(), inputs.evidence());
			var store = new EventPolicyState();
			for (EventPolicyRule rule : inputs.plannerRules()) store.upsert(rule);
			AttentionOutcome old = before.decide(event, inputs.profile(), store, inputs.plannerEnabled());
			AttentionOutcome now = after.decide(event, inputs.profile(), store, inputs.plannerEnabled());
			tally.replayed++;
			long failures = ((Number) after.debugState().get("failures")).longValue();
			if (failures > failuresSeen) {
				tally.failures += (int) (failures - failuresSeen);
				failuresSeen = failures;
				if (tally.firstFailure == null) tally.firstFailure = String.valueOf(after.debugState().get("lastFailure"));
			}
			boolean oldWakes = old.wake().wakes();
			boolean newWakes = now.wake().wakes();
			boolean differs = oldWakes != newWakes || old.wake().delivery() != now.wake().delivery()
				|| old.wake().urgency() != now.wake().urgency() || old.emitSemantic() != now.emitSemantic();
			if (!differs) continue;
			tally.changed++;
			tally.type(event.type(), "changed", 1);
			if (oldWakes && !newWakes) {
				tally.lost++;
				tally.type(event.type(), "lost", 1);
			}
			else if (!oldWakes && newWakes) {
				tally.gained++;
				tally.type(event.type(), "gained", 1);
			}
			var example = new LinkedHashMap<String, Object>();
			example.put("seqNo", event.seqNo());
			example.put("tick", event.tick());
			example.put("type", event.type());
			example.put("was", describe(old));
			example.put("now", describe(now));
			tally.example(example);
		}
		return tally.diff("attention");
	}

	private static String describe(AttentionOutcome outcome) {
		var wake = outcome.wake();
		String what = wake.wakes() ? wake.delivery() + " " + wake.urgency() : "no wake";
		return what + (outcome.emitSemantic() ? "" : ", not shown") + " (" + wake.ruleId() + ")";
	}

	/**
	 * Re-runs the latest recorded salience steps through {@code newEngine}, threading its own state from the first
	 * step's recorded state, and compares its percepts and drops with what the recorded module produced.
	 */
	public static Diff salience(List<SaliencePolicy.StepRecord> steps, RuleEngine newEngine) {
		var tally = new Tally();
		List<SaliencePolicy.StepRecord> recent = steps.size() > SALIENCE_STEPS ? steps.subList(steps.size() - SALIENCE_STEPS, steps.size()) : steps;
		String state = recent.isEmpty() ? "{}" : recent.getFirst().stateBefore();
		for (SaliencePolicy.StepRecord recorded : recent) {
			RuleStepResult result;
			try {
				result = newEngine.step(recorded.input().toString(), state);
			}
			catch (RuleException exception) {
				tally.replayed++;
				tally.failures++;
				if (tally.firstFailure == null) tally.firstFailure = exception.code() + ": " + exception.getMessage();
				continue;
			}
			state = result.stateJson();
			tally.replayed++;
			Map<String, Integer> was = typeCounts(recorded.percepts());
			Map<String, Integer> now = typeCounts(result.percepts());
			boolean differs = !was.equals(now) || !recorded.drops().equals(result.drops());
			if (!differs) continue;
			tally.changed++;
			var types = new TreeMap<String, Integer>(was);
			now.keySet().forEach(type -> types.putIfAbsent(type, 0));
			for (String type : types.keySet()) {
				int delta = now.getOrDefault(type, 0) - was.getOrDefault(type, 0);
				if (delta != 0) tally.type(type, "changed", 1);
				if (delta > 0) {
					tally.gained += delta;
					tally.type(type, "gained", delta);
				}
				else if (delta < 0) {
					tally.lost -= delta;
					tally.type(type, "lost", -delta);
				}
			}
			var example = new LinkedHashMap<String, Object>();
			example.put("tick", recorded.tick());
			example.put("was", was + ", " + recorded.drops().size() + " dropped");
			example.put("now", now + ", " + result.drops().size() + " dropped");
			tally.example(example);
		}
		return tally.diff("salience");
	}

	private static Map<String, Integer> typeCounts(JsonArray percepts) {
		var counts = new TreeMap<String, Integer>();
		for (JsonElement element : percepts) {
			if (!element.isJsonObject()) continue;
			JsonObject object = element.getAsJsonObject();
			String type = object.has("type") && object.get("type").isJsonPrimitive() ? object.get("type").getAsString() : "?";
			counts.merge(type, 1, Integer::sum);
		}
		return counts;
	}
}
