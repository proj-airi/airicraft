package ai.moeru.airicraft.agent.goals;

public record GoalPosition(
	int x,
	int y,
	int z,
	boolean exactY
) {
	/** Read by the planner in status messages, so it is coordinates rather than a record dump. */
	@Override
	public String toString() {
		return "(" + x + "," + y + "," + z + (exactY ? "" : ", any y") + ")";
	}
}
