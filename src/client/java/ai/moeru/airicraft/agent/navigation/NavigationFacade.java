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

	/**
	 * Swims, walks or climbs to the nearest air (or, with {@code shore}, the nearest dry standing place),
	 * planned against the air the player has left. Ends with {@code AT_GOAL} on arrival and
	 * {@code CALC_FAILED} when none can be found. Returns false when this backend cannot plan it.
	 */
	default boolean startNavigateToAir(boolean shore, NavigationOptions options) {
		return false;
	}

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

	boolean navigationGoalReached(GoalPosition position);

	/** Backend details for a finished task's diagnostics, such as plan outcomes and timings. */
	default java.util.Map<String, Object> navigationDiagnostics() {
		return java.util.Map.of();
	}
}
