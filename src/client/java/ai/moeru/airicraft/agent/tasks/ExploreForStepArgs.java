package ai.moeru.airicraft.agent.tasks;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

/**
 * Which world feature to walk out and look for, how far from the start the walk may go, for forests how many trees
 * count (one tree already gives the logs for a crafting table and wooden tools), and whether a night is waited out in
 * the shelter pit inside the task rather than handed back to the planner once the pit is roofed.
 */
public record ExploreForStepArgs(String featureKind, int maxDistance, int minTrees, boolean waitAtNight) {
	/** The kinds find_world_features knows. */
	public static final List<String> FEATURE_KINDS = List.of("forest", "water_body", "lava_pool", "nether_fortress", "end_portal");
	public static final int DEFAULT_MAX_DISTANCE = 640;
	public static final int MIN_MAX_DISTANCE = 32;
	public static final int MAX_MAX_DISTANCE = 2048;
	public static final int DEFAULT_MIN_TREES = 3;
	public static final int MAX_MIN_TREES = 32;
	private static final Set<String> KEYS = Set.of("featureKind", "maxDistance", "minTrees", "waitAtNight");

	public ExploreForStepArgs(String featureKind, int maxDistance) {
		this(featureKind, maxDistance, DEFAULT_MIN_TREES);
	}

	public ExploreForStepArgs(String featureKind, int maxDistance, int minTrees) {
		this(featureKind, maxDistance, minTrees, false);
	}

	public ExploreForStepArgs {
		if (!FEATURE_KINDS.contains(featureKind)) throw new IllegalArgumentException("featureKind must be one of " + FEATURE_KINDS);
		if (maxDistance < MIN_MAX_DISTANCE || maxDistance > MAX_MAX_DISTANCE)
			throw new IllegalArgumentException("maxDistance must be " + MIN_MAX_DISTANCE + ".." + MAX_MAX_DISTANCE);
		if (minTrees < 1 || minTrees > MAX_MIN_TREES) throw new IllegalArgumentException("minTrees must be 1.." + MAX_MIN_TREES);
	}

	public static ExploreForStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) {
			if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown argument: " + key);
		}
		var kind = args.get("featureKind");
		if (kind == null || !kind.isJsonPrimitive() || !kind.getAsJsonPrimitive().isString())
			throw new IllegalArgumentException("featureKind must be one of " + FEATURE_KINDS);
		return new ExploreForStepArgs(kind.getAsString(), integer(args, "maxDistance", DEFAULT_MAX_DISTANCE),
			integer(args, "minTrees", DEFAULT_MIN_TREES), bool(args, "waitAtNight"));
	}

	private static boolean bool(JsonObject args, String key) {
		var value = args.get(key);
		if (value == null || value.isJsonNull()) return false;
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + " must be true or false");
		return value.getAsBoolean();
	}

	private static int integer(JsonObject args, String key, int fallback) {
		var value = args.get(key);
		if (value == null || value.isJsonNull()) return fallback;
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be an integer");
		try { return value.getAsBigDecimal().intValueExact(); }
		catch (ArithmeticException ex) { throw new IllegalArgumentException(key + " must be an integer"); }
	}
}
