package ai.moeru.airicraft.agent.tasks;

import com.google.gson.JsonObject;

/** How many eyes of ender the stronghold search may throw. */
public record LocateStrongholdStepArgs(int maxEyes) {
	public static final int DEFAULT_MAX_EYES = 4;
	public static final int MAX_EYES = 12;

	public LocateStrongholdStepArgs {
		if (maxEyes < 1 || maxEyes > MAX_EYES) throw new IllegalArgumentException("maxEyes must be 1.." + MAX_EYES);
	}

	public static LocateStrongholdStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) if (!key.equals("maxEyes")) throw new IllegalArgumentException("unknown argument: " + key);
		var value = args.get("maxEyes");
		if (value == null || value.isJsonNull()) return new LocateStrongholdStepArgs(DEFAULT_MAX_EYES);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("maxEyes must be an integer");
		try { return new LocateStrongholdStepArgs(value.getAsBigDecimal().intValueExact()); }
		catch (ArithmeticException ex) { throw new IllegalArgumentException("maxEyes must be an integer"); }
	}
}
