package ai.moeru.airicraft.sim.spawn;

import java.util.List;

/**
 * Pure spawn-validation logic (unit-testable, no Minecraft types).
 * Rule from the spec: external/manual spawning is fine, but never on the
 * player's face — a minimum engagement distance is enforced.
 */
public final class SpawnRules {
	/** Absolute floor for the minimum distance; requests below this are clamped up. */
	public static final double ABSOLUTE_MIN_DISTANCE = 2.0;
	public static final double DEFAULT_MIN_DISTANCE = 5.0;

	private SpawnRules() {}

	public record Result(boolean ok, double nearestDistance, String reason) {
		public static Result ok(double nearest) {
			return new Result(true, nearest, null);
		}

		public static Result reject(double nearest, String reason) {
			return new Result(false, nearest, reason);
		}
	}

	public static double effectiveMinDistance(Double requested) {
		double value = requested == null ? DEFAULT_MIN_DISTANCE : requested;
		return Math.max(ABSOLUTE_MIN_DISTANCE, value);
	}

	public static Result checkMinDistance(double x, double y, double z, List<double[]> playerPositions, double minDistance) {
		double nearest = Double.MAX_VALUE;
		for (double[] p : playerPositions) {
			double d = Math.sqrt(sq(x - p[0]) + sq(y - p[1]) + sq(z - p[2]));
			if (d < nearest) {
				nearest = d;
			}
		}
		if (nearest < minDistance) {
			return Result.reject(nearest,
					"spawn would be " + fmt(nearest) + "m from a player (min " + fmt(minDistance) + "m)");
		}
		return Result.ok(nearest);
	}

	private static double sq(double v) {
		return v * v;
	}

	private static String fmt(double v) {
		return String.format("%.1f", v);
	}
}
