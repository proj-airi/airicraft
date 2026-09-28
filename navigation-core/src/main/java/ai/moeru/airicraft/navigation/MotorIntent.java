package ai.moeru.airicraft.navigation;

/**
 * One tick of desired player control. The adapter turns the world-space direction into movement
 * keys relative to the current yaw, points the camera at {@code look}, and performs {@code action}.
 *
 * @param moveX  world-space x of the desired horizontal direction; zero with moveZ to stand still
 * @param moveZ  world-space z of the desired horizontal direction
 * @param look   where to point the camera, or null to leave it
 * @param action a block interaction to perform this tick, or null
 */
public record MotorIntent(double moveX, double moveZ, boolean jump, boolean sneak, boolean sprint, Point look, Action action) {
	public static final MotorIntent IDLE = new MotorIntent(0, 0, false, false, false, null, null);

	public boolean moving() {
		return moveX != 0 || moveZ != 0;
	}

	public MotorIntent withAction(Action next) {
		return new MotorIntent(moveX, moveZ, jump, sneak, sprint, look, next);
	}

	public record Point(double x, double y, double z) {
		public static Point center(GridPos pos) {
			return new Point(pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
		}
	}

	public sealed interface Action permits Break, Place, Use {
		GridPos pos();
	}

	/** Keep mining this cell; the adapter starts or continues breaking and picks the tool. */
	public record Break(GridPos pos) implements Action { }

	/** Place a throwaway block into {@code pos} against a face of {@code against}. */
	public record Place(GridPos pos, GridPos against) implements Action { }

	/** Use the door, gate or trapdoor in this cell once. */
	public record Use(GridPos pos) implements Action { }
}
