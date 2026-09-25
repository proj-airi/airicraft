package ai.moeru.airicraft.navigation;

/** What a {@link MoveExecutor} sees for one tick of one step, plus memory kept across its ticks. */
final class StepContext {
	/** Ticks to wait after using a door or placing a block before checking the terrain again. */
	static final int ACTION_COOLDOWN = 5;

	final Step step;
	final Step next;
	BodyState body;
	Moves live;
	MovementPolicy policy;
	int ticks;
	int cooldown;
	/** Doors this step used, so the follower can close them behind the player. */
	final java.util.List<GridPos> usedDoors = new java.util.ArrayList<>();

	StepContext(Step step, Step next) {
		this.step = step;
		this.next = next;
	}

	TerrainView terrain() {
		return live.terrain();
	}

	boolean atStart() {
		return body.feet().equals(step.from());
	}

	boolean atEnd() {
		return body.feet().equals(step.to());
	}

	double distanceToEnd() {
		return body.horizontalDistanceTo(step.to().x() + 0.5, step.to().z() + 0.5);
	}

	/** Whether the next step leaves in a different horizontal direction, so this one must end centred. */
	boolean turnsAfter() {
		if (next == null) return true;
		return Integer.signum(next.dx()) != Integer.signum(step.dx()) || Integer.signum(next.dz()) != Integer.signum(step.dz());
	}
}
