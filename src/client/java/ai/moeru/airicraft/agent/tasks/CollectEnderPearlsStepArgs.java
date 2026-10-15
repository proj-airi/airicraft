package ai.moeru.airicraft.agent.tasks;

import com.google.gson.JsonObject;

/** How many ender pearls the inventory should hold when the task ends. */
public record CollectEnderPearlsStepArgs(int count) {
	/** Twelve pearls make twelve eyes of ender: an empty End portal frame, before eyes lost to locate_stronghold throws. */
	public static final int DEFAULT_COUNT = 12;
	public static final int MAX_COUNT = 16;

	public CollectEnderPearlsStepArgs {
		if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("count must be 1.." + MAX_COUNT);
	}

	public static CollectEnderPearlsStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) if (!key.equals("count")) throw new IllegalArgumentException("unknown argument: " + key);
		var value = args.get("count");
		if (value == null || value.isJsonNull()) return new CollectEnderPearlsStepArgs(DEFAULT_COUNT);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("count must be an integer");
		try { return new CollectEnderPearlsStepArgs(value.getAsBigDecimal().intValueExact()); }
		catch (ArithmeticException ex) { throw new IllegalArgumentException("count must be an integer"); }
	}
}
