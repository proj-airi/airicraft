package ai.moeru.airicraft.agent.baritone;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;

import java.util.Optional;

public interface BaritoneFacade {
	boolean isLoaded();

	void applySettings();

	double walkOnWaterPenalty();

	void setWalkOnWaterPenalty(double value);

	void startFollow(String playerName);

	void startNavigate(GoalPosition position);

	void startNavigateNear(GoalPosition position, int radiusBlocks);

	void startMine(GoalMineSpec spec);

	boolean mineProcessActive();

	/**
	 * Whether one of the agent-owned Baritone processes still controls pathing.
	 * Implementations that cannot observe this may conservatively fall back to
	 * the process name exposed by Baritone.
	 */
	default boolean processActive() {
		return mineProcessActive();
	}

	/**
	 * Requests cancellation of the current operation.
	 *
	 * @return {@code true} when Baritone queued an internal {@code CANCELED}
	 * acknowledgement immediately (including one already pending); {@code false}
	 * when an unsafe movement may finish later without emitting that event
	 */
	boolean cancel();

	/** True while an internally requested cancellation still awaits its event. */
	default boolean cancellationPending() {
		return false;
	}

	/** Monotonic acknowledgement for internally requested cancellation events. */
	default long cancellationAcknowledgement() {
		return 0L;
	}

	Optional<String> activeProcessName();

	Optional<Double> estimatedTicksToGoal();

	Optional<String> pollPathEvent();

	/** Present only while navigation still needs movement; idle/arrived owners return empty. */
	default Optional<NavigationProgress> navigationProgress() { return Optional.empty(); }

	record NavigationProgress(double x, double y, double z, boolean supported, String breakingTarget, float breakingProgress) {}

	boolean navigationGoalReached(GoalPosition position);
}
