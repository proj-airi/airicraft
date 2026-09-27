package ai.moeru.airicraft.agent.reflex;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Observed combat outcomes, independent of combat control and kill attribution. */
final class CombatEpisode {
	enum Outcome { ALIVE, CONFIRMED_DEAD, UNCONFIRMED }
	record Target(String uuid, String name, String entityTypeId, Outcome outcome, float health, double distance) {
		Target unconfirmed() {
			return new Target(uuid, name, entityTypeId, Outcome.UNCONFIRMED, health, distance);
		}
		Map<String, Object> payload() {
			return Map.of("uuid", uuid, "name", name, "entityTypeId", entityTypeId,
				"outcome", outcome.name(), "lastObservedHealth", health, "lastObservedDistance", distance);
		}
	}
	private final long startedTick;
	private final float healthBefore;
	private float healthAfter;
	private float observedHealthLoss;
	private final Map<String, Target> targets = new LinkedHashMap<>();

	CombatEpisode(long tick, float health) {
		startedTick = tick;
		healthBefore = health;
		healthAfter = health;
	}

	void observe(float health, List<Target> observations) {
		observedHealthLoss += Math.max(0, healthAfter - health);
		healthAfter = health;
		targets.replaceAll((id, target) -> target.outcome() == Outcome.CONFIRMED_DEAD ? target : target.unconfirmed());
		for (Target target : observations) {
			Target previous = targets.get(target.uuid());
			if (previous == null || previous.outcome() != Outcome.CONFIRMED_DEAD) targets.put(target.uuid(), target);
		}
	}

	Map<String, Object> summary(long tick, String reason) {
		List<Target> dead = withOutcome(Outcome.CONFIRMED_DEAD);
		List<Target> alive = withOutcome(Outcome.ALIVE);
		List<Target> unknown = withOutcome(Outcome.UNCONFIRMED);
		String text = "Combat summary: " + (tick - startedTick) + " ticks; health " + number(healthBefore)
			+ " -> " + number(healthAfter) + "; observed health loss " + number(observedHealthLoss) + ". "
			+ describe("Confirmed dead", dead) + describe("Still alive", alive) + describe("Outcome unknown", unknown)
			+ "Resolution: " + reason + ". Deaths are observed outcomes, not kill credit; missing targets are not confirmed dead.";
		return Map.of("durationTicks", tick - startedTick, "healthBefore", healthBefore, "healthAfter", healthAfter,
			"observedHealthLoss", observedHealthLoss, "confirmedDead", dead.stream().map(Target::payload).toList(),
			"surviving", alive.stream().map(Target::payload).toList(), "unconfirmed", unknown.stream().map(Target::payload).toList(),
			"reason", reason, "text", text);
	}

	private List<Target> withOutcome(Outcome outcome) {
		return targets.values().stream().filter(target -> target.outcome() == outcome).toList();
	}

	private static String describe(String label, List<Target> targets) {
		return label + " (" + targets.size() + "): " + (targets.isEmpty() ? "none" : targets.stream().map(target ->
			target.name() + " [" + target.entityTypeId() + "]"
				+ (target.outcome() == Outcome.CONFIRMED_DEAD ? "" : " last health=" + number(target.health())
					+ ", distance=" + number(target.distance()) + " blocks"))
			.collect(Collectors.joining("; "))) + ". ";
	}

	private static String number(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
