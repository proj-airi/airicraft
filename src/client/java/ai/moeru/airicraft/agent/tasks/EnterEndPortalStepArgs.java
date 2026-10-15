package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

/**
 * Any end portal frame block of the portal to light, or null for the nearest frame in sight, and whether to walk into the
 * portal once it is lit.
 */
public record EnterEndPortalStepArgs(GoalPosition frame, boolean enter) {
	public static final boolean DEFAULT_ENTER = true;
	private static final Set<String> KEYS = Set.of("x", "y", "z", "enter");

	public EnterEndPortalStepArgs {
		if (frame != null && !frame.exactY()) throw new IllegalArgumentException("the frame position needs an exact y");
	}

	public static EnterEndPortalStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown argument: " + key);
		boolean enter = bool(args.get("enter"));
		Integer x = coordinate(args, "x"), y = coordinate(args, "y"), z = coordinate(args, "z");
		if (x == null && y == null && z == null) return new EnterEndPortalStepArgs(null, enter);
		if (x == null || y == null || z == null)
			throw new IllegalArgumentException("give all of x, y and z of an end portal frame, or none for the nearest frame in sight");
		return new EnterEndPortalStepArgs(new GoalPosition(x, y, z, true), enter);
	}

	/** The arguments as the queued tool result shows them. */
	public String describe() {
		return (frame == null ? "frame=nearest" : "frame=" + frame.x() + "," + frame.y() + "," + frame.z()) + " enter=" + enter;
	}

	private static boolean bool(JsonElement value) {
		if (value == null || value.isJsonNull()) return DEFAULT_ENTER;
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("enter must be true or false");
		return value.getAsBoolean();
	}

	private static Integer coordinate(JsonObject args, String key) {
		JsonElement value = args.get(key);
		if (value == null || value.isJsonNull()) return null;
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be an integer");
		try { return value.getAsBigDecimal().intValueExact(); }
		catch (ArithmeticException ex) { throw new IllegalArgumentException(key + " must be an integer"); }
	}
}
