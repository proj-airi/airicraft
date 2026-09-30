package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;

import java.util.Objects;

/**
 * Immutable context captured when a navigation mining task hands ownership to
 * the local underwater harvester.
 */
public record UnderwaterHarvestStepArgs(GoalPosition searchOrigin) {
	public UnderwaterHarvestStepArgs {
		searchOrigin = Objects.requireNonNull(searchOrigin, "searchOrigin");
	}
}
