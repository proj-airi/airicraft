package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;

import java.util.Map;
import java.util.Objects;

/** {@code diagnostics} carries executor measurements for debugging; it is not part of the planner event. */
public record TaskTerminalEvent(
	String taskId,
	GoalSnapshot goal,
	TaskExecutionState terminalState,
	String message,
	TaskTerminationCause terminationCause,
	TaskFailureCode failureCode,
	Map<String, Object> diagnostics
) {
	public TaskTerminalEvent(
		String taskId,
		GoalSnapshot goal,
		TaskExecutionState terminalState,
		String message,
		TaskTerminationCause terminationCause,
		TaskFailureCode failureCode
	) {
		this(taskId, goal, terminalState, message, terminationCause, failureCode, Map.of());
	}

	public TaskTerminalEvent(
		String taskId,
		GoalSnapshot goal,
		TaskExecutionState terminalState,
		String message,
		TaskTerminationCause terminationCause
	) {
		this(taskId, goal, terminalState, message, terminationCause,
			terminalState == TaskExecutionState.FAILED ? TaskFailureCode.UNKNOWN : TaskFailureCode.NONE);
	}

	public TaskTerminalEvent {
		failureCode = failureCode == null
			? terminalState == TaskExecutionState.FAILED ? TaskFailureCode.UNKNOWN : TaskFailureCode.NONE
			: failureCode;
		diagnostics = Map.copyOf(Objects.requireNonNullElse(diagnostics, Map.of()));
	}
}
