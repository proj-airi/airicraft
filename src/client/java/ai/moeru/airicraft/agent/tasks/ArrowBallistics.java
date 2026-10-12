package ai.moeru.airicraft.agent.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Vanilla arrow flight, as {@code AbstractArrow.tick} runs it: move by the velocity, then multiply it
 * by 0.99 and subtract 0.05 from its vertical part. A fully drawn bow launches at 3 blocks per tick.
 * Coordinates are relative to the launch point: {@code horizontal} along the ground toward the target,
 * {@code vertical} up.
 */
public final class ArrowBallistics {
	public static final double FULL_DRAW_SPEED = 3.0D;
	static final double DRAG = 0.99D;
	static final double GRAVITY = 0.05D;
	static final int MAX_TICKS = 200;
	private static final double MIN_ELEVATION = -80.0D;
	private static final double MAX_ELEVATION = 80.0D;
	private static final double SCAN_STEP = 0.5D;

	private ArrowBallistics() {
	}

	/** A firing solution: elevation in degrees above the horizon, and the ticks until the arrow arrives. */
	public record Solution(double elevationDegrees, int flightTicks) {
		/** Minecraft pitch, which is negative when looking up. */
		public float pitch() { return (float) -elevationDegrees; }
	}

	/** The flatter of the two arcs that pass through the target, or empty when it is out of range. */
	public static Optional<Solution> solve(double horizontal, double vertical, double speed) {
		if (!(horizontal > 0.0D) || !(speed > 0.0D) || !Double.isFinite(vertical)) return Optional.empty();
		// Height at the target distance rises with elevation up to the longest-range angle, so the first
		// crossing on an upward scan is the flat arc; bisect inside the bracketing step.
		double previous = MIN_ELEVATION;
		double previousError = heightError(previous, horizontal, vertical, speed);
		for (double angle = MIN_ELEVATION + SCAN_STEP; angle <= MAX_ELEVATION; angle += SCAN_STEP) {
			double error = heightError(angle, horizontal, vertical, speed);
			if (Double.isFinite(previousError) && Double.isFinite(error) && previousError < 0.0D && error >= 0.0D) {
				double low = previous, high = angle;
				for (int i = 0; i < 40; i++) {
					double middle = (low + high) / 2.0D;
					if (heightError(middle, horizontal, vertical, speed) < 0.0D) low = middle;
					else high = middle;
				}
				double elevation = (low + high) / 2.0D;
				return Optional.of(new Solution(elevation, ticksToReach(elevation, horizontal, speed)));
			}
			previous = angle;
			previousError = error;
		}
		return Optional.empty();
	}

	/** Points of the flight path at each tick, ending where it crosses {@code horizontal}; for clearance checks. */
	public static List<double[]> path(double elevationDegrees, double horizontal, double speed) {
		var points = new ArrayList<double[]>();
		double radians = Math.toRadians(elevationDegrees);
		double x = 0.0D, y = 0.0D, vx = speed * Math.cos(radians), vy = speed * Math.sin(radians);
		points.add(new double[] {x, y});
		for (int tick = 0; tick < MAX_TICKS && vx > 1.0E-6D; tick++) {
			double nx = x + vx, ny = y + vy;
			if (nx >= horizontal) {
				points.add(new double[] {horizontal, y + (ny - y) * (horizontal - x) / (nx - x)});
				return points;
			}
			x = nx;
			y = ny;
			points.add(new double[] {x, y});
			vx *= DRAG;
			vy = vy * DRAG - GRAVITY;
		}
		return points;
	}

	/** Arrow height minus target height where the arrow reaches the target distance; NaN when it never does. */
	static double heightError(double elevationDegrees, double horizontal, double vertical, double speed) {
		List<double[]> points = path(elevationDegrees, horizontal, speed);
		double[] last = points.getLast();
		return last[0] >= horizontal ? last[1] - vertical : Double.NaN;
	}

	private static int ticksToReach(double elevationDegrees, double horizontal, double speed) {
		return path(elevationDegrees, horizontal, speed).size() - 1;
	}
}
