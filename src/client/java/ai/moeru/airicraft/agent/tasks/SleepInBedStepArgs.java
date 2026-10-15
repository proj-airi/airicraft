package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

/** The bed to sleep in (either half), or null for the nearest bed in sight. */
public record SleepInBedStepArgs(GoalPosition bed) {
	private static final Set<String> KEYS = Set.of("x", "y", "z");

	public SleepInBedStepArgs {
		if (bed != null && !bed.exactY()) throw new IllegalArgumentException("the bed position needs an exact y");
	}

	public static SleepInBedStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown argument: " + key);
		Integer x = coordinate(args, "x"), y = coordinate(args, "y"), z = coordinate(args, "z");
		if (x == null && y == null && z == null) return new SleepInBedStepArgs(null);
		if (x == null || y == null || z == null) throw new IllegalArgumentException("give all of x, y and z for the bed, or none for the nearest bed in sight");
		return new SleepInBedStepArgs(new GoalPosition(x, y, z, true));
	}

	/** The arguments as the queued tool result shows them. */
	public String describe() {
		return bed == null ? "bed=nearest" : "bed=" + bed.x() + "," + bed.y() + "," + bed.z();
	}

	private static Integer coordinate(JsonObject args, String key) {
		JsonElement value = args.get(key);
		if (value == null || value.isJsonNull()) return null;
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be an integer");
		try { return value.getAsBigDecimal().intValueExact(); }
		catch (ArithmeticException ex) { throw new IllegalArgumentException(key + " must be an integer"); }
	}
}
