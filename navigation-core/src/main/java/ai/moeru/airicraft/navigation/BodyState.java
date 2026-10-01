package ai.moeru.airicraft.navigation;

/**
 * What the motor reads about the player each tick.
 *
 * @param horizontalCollision the last move was blocked sideways
 * @param swimming            in the swimming pose: sprinting through water, 0.6 blocks tall with the eyes 0.4 above the feet
 * @param tick                a monotonic client tick counter
 */
public record BodyState(double x, double y, double z, double velocityY, boolean onGround, boolean inWater,
	boolean climbing, boolean horizontalCollision, boolean swimming, long tick) {
	public BodyState(double x, double y, double z, double velocityY, boolean onGround, boolean inWater,
		boolean climbing, boolean horizontalCollision, long tick) {
		this(x, y, z, velocityY, onGround, inWater, climbing, horizontalCollision, false, tick);
	}
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
