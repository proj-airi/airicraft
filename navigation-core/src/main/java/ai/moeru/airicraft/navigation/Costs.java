package ai.moeru.airicraft.navigation;

/** Move costs in client ticks, from vanilla movement speeds. */
public final class Costs {
	public static final double WALK = 20 / 4.317;
	public static final double SPRINT = 20 / 5.612;
	public static final double SNEAK = 20 / 1.3;
	public static final double WALK_IN_WATER = 20 / 2.2;
	public static final double LADDER_UP = 20 / 2.35;
	public static final double LADDER_DOWN = 20 / 3.0;
	public static final double WALK_OFF_EDGE = WALK * 0.8;
	public static final double CENTER_AFTER_FALL = WALK - WALK_OFF_EDGE;
	public static final double JUMP_ONE_BLOCK = computeFallTicks(1.25) - computeFallTicks(0.25);
	/** Opening or closing a door on the way, including the pause for the server to apply it. */
	public static final double DOOR = 6;
	/** Extra cost per move that ends with the head under water. */
	public static final double SUBMERGED = 4;
	/** Per-block lower bound used by the heuristics; slightly below the sprint cost. */
	public static final double HEURISTIC_PER_BLOCK = 3.563;

	private static final int FALL_TABLE_SIXTEENTHS = 16 * 400;
	private static final double[] FALL_TABLE = new double[FALL_TABLE_SIXTEENTHS + 1];

	static {
		for (int i = 0; i <= FALL_TABLE_SIXTEENTHS; i++) FALL_TABLE[i] = computeFallTicks(i / 16.0);
	}

	private Costs() {
	}

	/** Ticks to fall a distance from rest, with vanilla gravity and drag. */
	public static double fallTicks(double distance) {
		double sixteenths = distance * 16;
		long index = Math.round(sixteenths);
		if (index >= 0 && index <= FALL_TABLE_SIXTEENTHS && Math.abs(sixteenths - index) < 1e-9) return FALL_TABLE[(int) index];
		return computeFallTicks(distance);
	}

	private static double computeFallTicks(double distance) {
		if (distance <= 0) return 0;
		double remaining = distance;
		int ticks = 0;
		while (true) {
			double step = (Math.pow(0.98, ticks) - 1) * -3.92;
			if (remaining <= step) return ticks + remaining / step;
			remaining -= step;
			ticks++;
		}
	}
}
