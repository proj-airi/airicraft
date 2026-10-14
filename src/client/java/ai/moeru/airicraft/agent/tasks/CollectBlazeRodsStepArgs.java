package ai.moeru.airicraft.agent.tasks;

import com.google.gson.JsonObject;

/** How many blaze rods the inventory should hold when the task ends. */
public record CollectBlazeRodsStepArgs(int count) {
	/** Seven rods make fourteen blaze powder: twelve eyes of ender and two spare for eyes that break. */
	public static final int DEFAULT_COUNT = 7;
	public static final int MAX_COUNT = 16;

	public CollectBlazeRodsStepArgs {
		if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("count must be 1.." + MAX_COUNT);
	}

	public static CollectBlazeRodsStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) if (!key.equals("count")) throw new IllegalArgumentException("unknown argument: " + key);
		var value = args.get("count");
		if (value == null || value.isJsonNull()) return new CollectBlazeRodsStepArgs(DEFAULT_COUNT);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("count must be an integer");
		try { return new CollectBlazeRodsStepArgs(value.getAsBigDecimal().intValueExact()); }
		catch (ArithmeticException ex) { throw new IllegalArgumentException("count must be an integer"); }
	}
}
