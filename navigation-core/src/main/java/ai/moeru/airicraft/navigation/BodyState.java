package ai.moeru.airicraft.navigation;

/**
 * What the motor reads about the player each tick.
 *
 * @param horizontalCollision the last move was blocked sideways
 * @param tick                a monotonic client tick counter
 */
public record BodyState(double x, double y, double z, double velocityY, boolean onGround, boolean inWater,
	boolean climbing, boolean horizontalCollision, long tick) {
	/** Raises the sample point so a player on soul sand or a dirt path is in the cell above the floor. */
	static final double FEET_OFFSET = 0.1251;

	public GridPos feet() {
		return new GridPos((int) Math.floor(x), (int) Math.floor(y + FEET_OFFSET), (int) Math.floor(z));
	}

	public boolean supported() {
		return onGround || inWater || climbing;
	}

	public double horizontalDistanceTo(double tx, double tz) {
		double dx = tx - x, dz = tz - z;
		return Math.sqrt(dx * dx + dz * dz);
	}
}
