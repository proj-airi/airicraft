package ai.moeru.airicraft.agent.tasks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

/** How much raw meat to carry when the hunt ends, whether to cook it, and how far away an animal may be. */
public record HuntFoodStepArgs(int count, boolean cook, int maxDistance) {
	public static final int DEFAULT_COUNT = 6;
	public static final int MAX_COUNT = 16;
	public static final boolean DEFAULT_COOK = true;
	public static final int DEFAULT_MAX_DISTANCE = 32;
	public static final int MIN_MAX_DISTANCE = 8;
	public static final int MAX_MAX_DISTANCE = 48;
	private static final Set<String> KEYS = Set.of("count", "cook", "maxDistance");

	public HuntFoodStepArgs {
		if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("count must be 1.." + MAX_COUNT);
		if (maxDistance < MIN_MAX_DISTANCE || maxDistance > MAX_MAX_DISTANCE)
			throw new IllegalArgumentException("maxDistance must be " + MIN_MAX_DISTANCE + ".." + MAX_MAX_DISTANCE);
	}

	public static HuntFoodStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown argument: " + key);
		int count = integer(args.get("count"), "count", DEFAULT_COUNT);
		int maxDistance = integer(args.get("maxDistance"), "maxDistance", DEFAULT_MAX_DISTANCE);
		JsonElement cook = args.get("cook");
		boolean cookValue = DEFAULT_COOK;
		if (cook != null && !cook.isJsonNull()) {
			if (!cook.isJsonPrimitive() || !cook.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("cook must be true or false");
			cookValue = cook.getAsBoolean();
		}
		return new HuntFoodStepArgs(count, cookValue, maxDistance);
	}

	private static int integer(JsonElement value, String name, int fallback) {
		if (value == null || value.isJsonNull()) return fallback;
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(name + " must be an integer");
		try { return value.getAsBigDecimal().intValueExact(); }
		catch (ArithmeticException ex) { throw new IllegalArgumentException(name + " must be an integer"); }
	}

	public String describe() {
		return "count=" + count + " cook=" + cook + " maxDistance=" + maxDistance;
	}
}
