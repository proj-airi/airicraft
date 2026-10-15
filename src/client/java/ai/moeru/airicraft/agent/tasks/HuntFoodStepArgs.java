package ai.moeru.airicraft.agent.tasks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

/**
 * How much raw meat to carry when the hunt ends, whether to cook it, how far away an animal may be, and whether the last
 * adults of a kind in sight are spared for the herd.
 */
public record HuntFoodStepArgs(int count, boolean cook, int maxDistance, boolean keepHerd) {
	public static final int DEFAULT_COUNT = 6;
	public static final int MAX_COUNT = 16;
	public static final boolean DEFAULT_COOK = true;
	public static final boolean DEFAULT_KEEP_HERD = true;
	public static final int DEFAULT_MAX_DISTANCE = 32;
	public static final int MIN_MAX_DISTANCE = 8;
	public static final int MAX_MAX_DISTANCE = 48;
	private static final Set<String> KEYS = Set.of("count", "cook", "maxDistance", "keepHerd");

	public HuntFoodStepArgs(int count, boolean cook, int maxDistance) {
		this(count, cook, maxDistance, DEFAULT_KEEP_HERD);
	}

	public HuntFoodStepArgs {
		if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("count must be 1.." + MAX_COUNT);
		if (maxDistance < MIN_MAX_DISTANCE || maxDistance > MAX_MAX_DISTANCE)
			throw new IllegalArgumentException("maxDistance must be " + MIN_MAX_DISTANCE + ".." + MAX_MAX_DISTANCE);
	}

	public static HuntFoodStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown argument: " + key);
		int count = integer(args.get("count"), "count", DEFAULT_COUNT);
		int maxDistance = integer(args.get("maxDistance"), "maxDistance", DEFAULT_MAX_DISTANCE);
		boolean cook = bool(args.get("cook"), "cook", DEFAULT_COOK);
		boolean keepHerd = bool(args.get("keepHerd"), "keepHerd", DEFAULT_KEEP_HERD);
		return new HuntFoodStepArgs(count, cook, maxDistance, keepHerd);
	}

	private static boolean bool(JsonElement value, String name, boolean fallback) {
		if (value == null || value.isJsonNull()) return fallback;
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(name + " must be true or false");
		return value.getAsBoolean();
	}

	private static int integer(JsonElement value, String name, int fallback) {
		if (value == null || value.isJsonNull()) return fallback;
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(name + " must be an integer");
		try { return value.getAsBigDecimal().intValueExact(); }
		catch (ArithmeticException ex) { throw new IllegalArgumentException(name + " must be an integer"); }
	}

	public String describe() {
		return "count=" + count + " cook=" + cook + " maxDistance=" + maxDistance + " keepHerd=" + keepHerd;
	}
}
