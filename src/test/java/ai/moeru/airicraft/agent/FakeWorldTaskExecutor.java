package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.WorldTaskExecutor;

import java.util.Optional;


final class FakeWorldTaskExecutor implements WorldTaskExecutor {
	TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();
	TaskExecutionSnapshot forcedSnapshot;
	Optional<TaskTerminalEvent> nextTerminalEvent = Optional.empty();
	Optional<WorldTaskRequest> lastActiveTask = Optional.empty();
	int onWorldLeaveCalls;

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		lastActiveTask = activeTask;
		if (forcedSnapshot != null) {
			snapshot = forcedSnapshot;
		}
		else if (!sessionSnapshot.companionActuationAllowed()) {
			snapshot = new TaskExecutionSnapshot(
				TaskExecutionState.PAUSED_BY_SESSION_GATE,
				activeTask.map(WorldTaskRequest::taskId).orElse(null),
				activeTask.map(WorldTaskRequest::goal).orElse(null),
				null,
				null,
				null,
				null
			);
		}
		else if (nextTerminalEvent.isPresent()) {
			TaskTerminalEvent terminalEvent = nextTerminalEvent.get();
			snapshot = new TaskExecutionSnapshot(
				terminalEvent.terminalState(),
				terminalEvent.taskId(),
				terminalEvent.goal(),
				null,
				terminalEvent.message(),
				null,
				terminalEvent.terminationCause()
			);
		}
		else {
			snapshot = new TaskExecutionSnapshot(
				activeTask.isPresent() ? TaskExecutionState.RUNNING : TaskExecutionState.IDLE,
				activeTask.map(WorldTaskRequest::taskId).orElse(null),
				activeTask.map(WorldTaskRequest::goal).orElse(null),
				null,
				null,
				null,
				null
			);
		}

		Optional<TaskTerminalEvent> result = nextTerminalEvent;
		nextTerminalEvent = Optional.empty();
		return result;
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		onWorldLeaveCalls++;
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override
	public void shutdown() {
		snapshot = TaskExecutionSnapshot.idle();
	}
}
