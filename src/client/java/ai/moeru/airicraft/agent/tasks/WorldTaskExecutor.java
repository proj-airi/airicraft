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

	void shutdown();
}
