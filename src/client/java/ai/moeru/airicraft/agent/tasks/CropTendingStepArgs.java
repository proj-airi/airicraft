package ai.moeru.airicraft.agent.tasks;

/** One flat, bounded plot; y is the crop block, not the soil below it. */
public record CropTendingStepArgs(String seedItemId, int x1, int y, int z1, int x2, int z2) {
	public CropTendingStepArgs {
		if (seedItemId == null || !seedItemId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
			throw new IllegalArgumentException("seedItemId must be a namespaced crop planting item");
		if (x1 > x2 || z1 > z2 || (long) x2 - x1 >= 16 || (long) z2 - z1 >= 16)
			throw new IllegalArgumentException("crop plot must have ordered bounds and be at most 16 by 16 blocks");
	}
	public static CropTendingStepArgs parse(com.google.gson.JsonObject args) {
		for (String key : args.keySet()) if (!java.util.List.of("seedItemId", "x1", "y", "z1", "x2", "z2").contains(key))
			throw new IllegalArgumentException("unknown argument: " + key);
		var seed = args.get("seedItemId");
		if (seed == null || !seed.isJsonPrimitive() || !seed.getAsJsonPrimitive().isString())
			throw new IllegalArgumentException("seedItemId must be a string");
		return new CropTendingStepArgs(seed.getAsString(), integer(args, "x1"), integer(args, "y"), integer(args, "z1"), integer(args, "x2"), integer(args, "z2"));
	}

	private static int integer(com.google.gson.JsonObject args, String key) {
		var value = args.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be an integer");
		try { return value.getAsBigDecimal().intValueExact(); }
		catch (ArithmeticException exception) { throw new IllegalArgumentException(key + " must be a 32-bit integer"); }
	}
}
