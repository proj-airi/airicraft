package ai.moeru.airicraft.agent.tasks;

import com.google.gson.JsonObject;

/** How much flint the inventory should hold when the task ends. */
public record CollectFlintStepArgs(int count) {
	public static final int DEFAULT_COUNT = 1;
	public static final int MAX_COUNT = 8;

	public CollectFlintStepArgs {
		if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("count must be 1.." + MAX_COUNT);
	}

	public static CollectFlintStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) if (!key.equals("count")) throw new IllegalArgumentException("unknown argument: " + key);
		var value = args.get("count");
		if (value == null || value.isJsonNull()) return new CollectFlintStepArgs(DEFAULT_COUNT);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("count must be an integer");
		try { return new CollectFlintStepArgs(value.getAsBigDecimal().intValueExact()); }
		catch (ArithmeticException ex) { throw new IllegalArgumentException("count must be an integer"); }
	}
}
