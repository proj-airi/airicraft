package ai.moeru.airicraft.agent.tasks;

import java.util.Objects;

/**
 * A milestone a long-running task reports while it keeps running, so the planner can follow along and narrate: the
 * task found what it looked for, settled in for the night, went hunting. It is evidence, not a request; the task does
 * not wait for an answer.
 */
public record TaskProgress(String milestone, String message) {
	public TaskProgress {
		Objects.requireNonNull(milestone, "milestone");
		Objects.requireNonNull(message, "message");
	}
}
