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
			// The rule id is part of the decision (as in AttentionReplay), so a module that only renames it shows up.
			boolean differs = oldWakes != newWakes || old.wake().delivery() != now.wake().delivery()
				|| old.wake().urgency() != now.wake().urgency() || old.emitSemantic() != now.emitSemantic()
				|| !old.wake().ruleId().equals(now.wake().ruleId());
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
	 * Re-runs the latest recorded salience steps' inputs through the running module and the candidate, each from an
	 * empty state that it threads itself (the recorded outputs and states came from whichever versions ran then, so
	 * they are not used). Percepts are compared whole, by candidate ids and payload, not only by type. A step the running
	 * module cannot run is skipped, not compared.
	 */
	public static Diff salience(List<SaliencePolicy.StepRecord> steps, RuleEngine oldEngine, RuleEngine newEngine) {
		var tally = new Tally();
		List<SaliencePolicy.StepRecord> recent = steps.size() > SALIENCE_STEPS ? steps.subList(steps.size() - SALIENCE_STEPS, steps.size()) : steps;
		String oldState = "{}";
		String newState = "{}";
		for (SaliencePolicy.StepRecord recorded : recent) {
			String input = recorded.input().toString();
			RuleStepResult before = null;
			try {
				before = oldEngine.step(input, oldState);
				oldState = before.stateJson();
			}
			catch (RuleException exception) {
				// Not the candidate's fault: a cold or failing running module leaves nothing to compare with.
			}
			RuleStepResult after;
			try {
				after = newEngine.step(input, newState);
				newState = after.stateJson();
			}
			catch (RuleException exception) {
				tally.replayed++;
				tally.failures++;
				if (tally.firstFailure == null) tally.firstFailure = exception.code() + ": " + exception.getMessage();
				continue;
			}
			if (before == null) {
				tally.skipped++;
				continue;
			}
			tally.replayed++;
			Map<String, Integer> was = perceptCounts(before.percepts());
			Map<String, Integer> now = perceptCounts(after.percepts());
			boolean dropsDiffer = !perceptCounts(before.drops()).equals(perceptCounts(after.drops()));
			if (was.equals(now) && !dropsDiffer) continue;
			tally.changed++;
			int gained = 0;
			int lost = 0;
			var perType = new TreeMap<String, int[]>();
			for (var entry : now.entrySet()) {
				int delta = entry.getValue() - was.getOrDefault(entry.getKey(), 0);
				if (delta > 0) {
					gained += delta;
					perType.computeIfAbsent(perceptType(entry.getKey()), ignored -> new int[2])[0] += delta;
				}
			}
			for (var entry : was.entrySet()) {
				int delta = entry.getValue() - now.getOrDefault(entry.getKey(), 0);
				if (delta > 0) {
					lost += delta;
					perType.computeIfAbsent(perceptType(entry.getKey()), ignored -> new int[2])[1] += delta;
				}
			}
			tally.gained += gained;
			tally.lost += lost;
			for (var entry : perType.entrySet()) {
				tally.type(entry.getKey(), "changed", 1);
				if (entry.getValue()[0] > 0) tally.type(entry.getKey(), "gained", entry.getValue()[0]);
				if (entry.getValue()[1] > 0) tally.type(entry.getKey(), "lost", entry.getValue()[1]);
			}
			var example = new LinkedHashMap<String, Object>();
			example.put("tick", recorded.tick());
			example.put("was", summarize(before));
			example.put("now", summarize(after));
			tally.example(example);
		}
		return tally.diff("salience");
	}

	private static String summarize(RuleStepResult result) {
		var types = new TreeMap<String, Integer>();
		perceptCounts(result.percepts()).forEach((identity, count) -> types.merge(perceptType(identity), count, Integer::sum));
		return types + ", " + result.drops().size() + " dropped";
	}

	/** Percepts (or drops) by identity: the whole element as JSON, so a different candidate or payload is a different one. */
	private static Map<String, Integer> perceptCounts(JsonArray elements) {
		var counts = new TreeMap<String, Integer>();
		for (JsonElement element : elements) counts.merge(element.toString(), 1, Integer::sum);
		return counts;
	}

	private static String perceptType(String identity) {
		try {
			JsonElement parsed = com.google.gson.JsonParser.parseString(identity);
			if (parsed.isJsonObject() && parsed.getAsJsonObject().has("type") && parsed.getAsJsonObject().get("type").isJsonPrimitive()) {
				return parsed.getAsJsonObject().get("type").getAsString();
			}
		}
		catch (RuntimeException ignored) {
			// Fall through: an element that is not an object has no type.
		}
		return "?";
	}
}
