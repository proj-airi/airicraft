package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.agent.goals.GoalPosition;

import java.util.Optional;

/**
 * What navigation consumers use to move the player. Requests carry their own {@link NavigationOptions};
 * nothing outlives a request. Cancellation is synchronous: control is released in the calling tick and no
 * event follows it. Path events are {@code AT_GOAL} on arrival and {@code CALC_FAILED} when no route remains.
 */
public interface NavigationFacade {
	boolean isLoaded();

	void startFollow(String playerName);

	default void startNavigate(GoalPosition position) {
		startNavigate(position, NavigationOptions.DEFAULT);
	}

	void startNavigate(GoalPosition position, NavigationOptions options);

	default void startNavigateNear(GoalPosition position, int radiusBlocks) {
		startNavigateNear(position, radiusBlocks, NavigationOptions.DEFAULT);
	}

	void startNavigateNear(GoalPosition position, int radiusBlocks, NavigationOptions options);

	/** Whether a navigation or follow request still controls movement. */
	boolean processActive();

	/** Ends the current request now. Idempotent. */
	void cancel();

	Optional<String> activeProcessName();

	Optional<Double> estimatedTicksToGoal();

	Optional<String> pollPathEvent();

	/** Present only while navigation still needs movement; idle/arrived owners return empty. */
	default Optional<NavigationProgress> navigationProgress() { return Optional.empty(); }

	record NavigationProgress(double x, double y, double z, boolean supported, String breakingTarget, float breakingProgress) {}

	/** Where the current navigate or follow request is headed; empty while idle. */
	default Optional<Destination> destination() { return Optional.empty(); }

	/**
	 * A request's target: within {@code radius} blocks of a cell, or of its column without {@code exactY}.
	 */
	record Destination(int x, int y, int z, boolean exactY, int radius) {
		public String key() {
			return (exactY ? x + "," + y + "," + z : x + ",*," + z) + (radius > 0 ? "~" + radius : "");
		}

		/** Blocks from a body position to the nearest edge of the target, horizontal only without {@code exactY}. */
		public double distanceFrom(double px, double py, double pz) {
			double dx = x + 0.5 - px, dy = exactY ? y - py : 0, dz = z + 0.5 - pz;
			return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - radius);
		}
	}

	boolean navigationGoalReached(GoalPosition position);

	/** Backend details for a finished task's diagnostics, such as plan outcomes and timings. */
	default java.util.Map<String, Object> navigationDiagnostics() {
		return java.util.Map.of();
	}
}
