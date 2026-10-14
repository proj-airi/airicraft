package ai.moeru.airicraft.agent.tasks;

import com.google.gson.JsonObject;

/** How much obsidian the inventory should hold when the task ends. */
public record CollectObsidianStepArgs(int count) {
	public static final int DEFAULT_COUNT = 10;

	public CollectObsidianStepArgs {
		if (count < 1 || count > 64) throw new IllegalArgumentException("count must be 1..64");
	}

	public static CollectObsidianStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) if (!key.equals("count")) throw new IllegalArgumentException("unknown argument: " + key);
		var value = args.get("count");
		if (value == null || value.isJsonNull()) return new CollectObsidianStepArgs(DEFAULT_COUNT);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("count must be an integer");
		try { return new CollectObsidianStepArgs(value.getAsBigDecimal().intValueExact()); }
		catch (ArithmeticException ex) { throw new IllegalArgumentException("count must be an integer"); }
	}
}
