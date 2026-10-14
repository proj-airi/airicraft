package ai.moeru.airicraft.agent.tasks;

import com.google.gson.JsonObject;

import java.util.List;

/** Which world feature to walk out and look for, and how far from the start the walk may go. */
public record ExploreForStepArgs(String featureKind, int maxDistance) {
	/** The kinds find_world_features knows. */
	public static final List<String> FEATURE_KINDS = List.of("forest", "water_body", "lava_pool", "nether_fortress", "end_portal");
	public static final int DEFAULT_MAX_DISTANCE = 320;
	public static final int MIN_MAX_DISTANCE = 32;
	public static final int MAX_MAX_DISTANCE = 1024;

	public ExploreForStepArgs {
		if (!FEATURE_KINDS.contains(featureKind)) throw new IllegalArgumentException("featureKind must be one of " + FEATURE_KINDS);
		if (maxDistance < MIN_MAX_DISTANCE || maxDistance > MAX_MAX_DISTANCE)
			throw new IllegalArgumentException("maxDistance must be " + MIN_MAX_DISTANCE + ".." + MAX_MAX_DISTANCE);
	}

	public static ExploreForStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) {
			if (!key.equals("featureKind") && !key.equals("maxDistance")) throw new IllegalArgumentException("unknown argument: " + key);
		}
		var kind = args.get("featureKind");
		if (kind == null || !kind.isJsonPrimitive() || !kind.getAsJsonPrimitive().isString())
			throw new IllegalArgumentException("featureKind must be one of " + FEATURE_KINDS);
		var value = args.get("maxDistance");
		if (value == null || value.isJsonNull()) return new ExploreForStepArgs(kind.getAsString(), DEFAULT_MAX_DISTANCE);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("maxDistance must be an integer");
		try { return new ExploreForStepArgs(kind.getAsString(), value.getAsBigDecimal().intValueExact()); }
		catch (ArithmeticException ex) { throw new IllegalArgumentException("maxDistance must be an integer"); }
	}
}
