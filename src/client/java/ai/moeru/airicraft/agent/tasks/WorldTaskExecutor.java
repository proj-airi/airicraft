package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorldTaskExecutor {
	Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask);

	default void onPlayerItemPickupObserved(
		int entityId,
		UUID entityUuid,
		String itemId,
		int pickupDelta,
		int agentAttributedQuantity,
		UUID collectorIdentity,
		UUID observationId
	) {
	}

	TaskExecutionSnapshot snapshot();

	/** Milestones reported since the last call, oldest first; most tasks report none. */
	default List<TaskProgress> drainProgress() {
		return List.of();
	}

	void onWorldLeave();

	/**
	 * The survival reflex takes the body and later hands the same task back (same task id). An executor that can go on
	 * where it left off lets go of every control here (navigation, leases, held keys, shield, bow, the camera) but keeps
	 * its request, phase and counters, and re-acquires what it needs on its next tick for that request. By default it
	 * resets as on leaving the world, so the resumed task starts over.
	 */
	default void pauseForReflex() {
		onWorldLeave();
	}

	void shutdown();
}
