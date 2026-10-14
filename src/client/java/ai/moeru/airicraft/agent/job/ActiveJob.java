package ai.moeru.airicraft.agent.job;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.tasks.BlockBreakStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectObsidianStepArgs;
import ai.moeru.airicraft.agent.tasks.CropTendingStepArgs;
import ai.moeru.airicraft.agent.tasks.LureEntitiesStepArgs;
import ai.moeru.airicraft.agent.tasks.LocateStrongholdStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectSmeltedItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockPlacementStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockUseStepArgs;
import ai.moeru.airicraft.agent.tasks.DropItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.EntityInteractionStepArgs;
import ai.moeru.airicraft.agent.tasks.ReturnToSurfaceStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.TaskSpec;

import java.util.Objects;

public record ActiveJob(
	String jobId,
	ActiveJobType type,
	ActiveJobStatus status,
	GoalSnapshot directGoal,
	TaskSpec taskSpec,
	CraftRecipeStepArgs craftRecipe,
	DropItemsStepArgs dropItems,
	EntityInteractionStepArgs entityInteraction,
	SmeltItemsStepArgs smeltItems,
	CollectSmeltedItemsStepArgs collectSmeltedItems,
	ReturnToSurfaceStepArgs returnToSurface,
	BlockPlacementStepArgs blockPlacement,
	BlockUseStepArgs blockUse,
	BlockBreakStepArgs blockBreak,
	String askPrompt,
	long waitUntilTick,
	int baselineResourceCount,
	int collectedCount,
	String source,
	String blockedReason,
	String lastError,
	long updatedTick,
	CropTendingStepArgs cropTending,
	LureEntitiesStepArgs lureEntities,
	CollectObsidianStepArgs collectObsidian,
	LocateStrongholdStepArgs locateStronghold
) {
	public ActiveJob(
		String jobId,
		ActiveJobType type,
		ActiveJobStatus status,
		GoalSnapshot directGoal,
		TaskSpec taskSpec,
		CraftRecipeStepArgs craftRecipe,
		DropItemsStepArgs dropItems,
		EntityInteractionStepArgs entityInteraction,
		SmeltItemsStepArgs smeltItems,
		CollectSmeltedItemsStepArgs collectSmeltedItems,
		ReturnToSurfaceStepArgs returnToSurface,
		BlockPlacementStepArgs blockPlacement,
		BlockUseStepArgs blockUse,
		BlockBreakStepArgs blockBreak,
		String askPrompt,
		long waitUntilTick,
		int baselineResourceCount,
		int collectedCount,
		String source,
		String blockedReason,
		String lastError,
		long updatedTick,
		CropTendingStepArgs cropTending,
		LureEntitiesStepArgs lureEntities,
		CollectObsidianStepArgs collectObsidian
	) {
		this(jobId, type, status, directGoal, taskSpec, craftRecipe, dropItems, entityInteraction, smeltItems, collectSmeltedItems, returnToSurface, blockPlacement, blockUse, blockBreak, askPrompt, waitUntilTick, baselineResourceCount, collectedCount, source, blockedReason, lastError, updatedTick, cropTending, lureEntities, collectObsidian, null);
	}

	public ActiveJob(
		String jobId,
		ActiveJobType type,
		ActiveJobStatus status,
		GoalSnapshot directGoal,
		TaskSpec taskSpec,
		CraftRecipeStepArgs craftRecipe,
		DropItemsStepArgs dropItems,
		EntityInteractionStepArgs entityInteraction,
		SmeltItemsStepArgs smeltItems,
		CollectSmeltedItemsStepArgs collectSmeltedItems,
		ReturnToSurfaceStepArgs returnToSurface,
		BlockPlacementStepArgs blockPlacement,
		BlockUseStepArgs blockUse,
		BlockBreakStepArgs blockBreak,
		String askPrompt,
		long waitUntilTick,
		int baselineResourceCount,
		int collectedCount,
		String source,
		String blockedReason,
		String lastError,
		long updatedTick,
		CropTendingStepArgs cropTending,
		LureEntitiesStepArgs lureEntities
	) {
		this(jobId, type, status, directGoal, taskSpec, craftRecipe, dropItems, entityInteraction, smeltItems, collectSmeltedItems, returnToSurface, blockPlacement, blockUse, blockBreak, askPrompt, waitUntilTick, baselineResourceCount, collectedCount, source, blockedReason, lastError, updatedTick, cropTending, lureEntities, null);
	}

	public ActiveJob(
		String jobId,
		ActiveJobType type,
		ActiveJobStatus status,
		GoalSnapshot directGoal,
		TaskSpec taskSpec,
		CraftRecipeStepArgs craftRecipe,
		DropItemsStepArgs dropItems,
		EntityInteractionStepArgs entityInteraction,
		SmeltItemsStepArgs smeltItems,
		CollectSmeltedItemsStepArgs collectSmeltedItems,
		ReturnToSurfaceStepArgs returnToSurface,
		BlockPlacementStepArgs blockPlacement,
		BlockUseStepArgs blockUse,
		BlockBreakStepArgs blockBreak,
		String askPrompt,
		long waitUntilTick,
		int baselineResourceCount,
		int collectedCount,
		String source,
		String blockedReason,
		String lastError,
		long updatedTick,
		CropTendingStepArgs cropTending
	) {
		this(jobId, type, status, directGoal, taskSpec, craftRecipe, dropItems, entityInteraction, smeltItems, collectSmeltedItems, returnToSurface, blockPlacement, blockUse, blockBreak, askPrompt, waitUntilTick, baselineResourceCount, collectedCount, source, blockedReason, lastError, updatedTick, cropTending, null);
	}

	public ActiveJob(
		String jobId,
		ActiveJobType type,
		ActiveJobStatus status,
		GoalSnapshot directGoal,
		TaskSpec taskSpec,
		CraftRecipeStepArgs craftRecipe,
		DropItemsStepArgs dropItems,
		EntityInteractionStepArgs entityInteraction,
		SmeltItemsStepArgs smeltItems,
		CollectSmeltedItemsStepArgs collectSmeltedItems,
		ReturnToSurfaceStepArgs returnToSurface,
		BlockPlacementStepArgs blockPlacement,
		BlockUseStepArgs blockUse,
		BlockBreakStepArgs blockBreak,
		String askPrompt,
		long waitUntilTick,
		int baselineResourceCount,
		int collectedCount,
		String source,
		String blockedReason,
		String lastError,
		long updatedTick
	) {
		this(jobId, type, status, directGoal, taskSpec, craftRecipe, dropItems, entityInteraction, smeltItems, collectSmeltedItems, returnToSurface, blockPlacement, blockUse, blockBreak, askPrompt, waitUntilTick, baselineResourceCount, collectedCount, source, blockedReason, lastError, updatedTick, null);
	}

	public ActiveJob(
		String jobId,
		ActiveJobType type,
		ActiveJobStatus status,
		GoalSnapshot directGoal,
		TaskSpec taskSpec,
		CraftRecipeStepArgs craftRecipe,
		String askPrompt,
		long waitUntilTick,
		int baselineResourceCount,
		int collectedCount,
		String source,
		String blockedReason,
		String lastError,
		long updatedTick
	) {
		this(
			jobId,
			type,
			status,
			directGoal,
			taskSpec,
			craftRecipe,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			askPrompt,
			waitUntilTick,
			baselineResourceCount,
			collectedCount,
			source,
			blockedReason,
			lastError,
			updatedTick
		);
	}

	public ActiveJob(
		String jobId,
		ActiveJobType type,
		ActiveJobStatus status,
		GoalSnapshot directGoal,
		TaskSpec taskSpec,
		CraftRecipeStepArgs craftRecipe,
		DropItemsStepArgs dropItems,
		String askPrompt,
		long waitUntilTick,
		int baselineResourceCount,
		int collectedCount,
		String source,
		String blockedReason,
		String lastError,
		long updatedTick
	) {
		this(
			jobId,
			type,
			status,
			directGoal,
			taskSpec,
			craftRecipe,
			dropItems,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			askPrompt,
			waitUntilTick,
			baselineResourceCount,
			collectedCount,
			source,
			blockedReason,
			lastError,
			updatedTick
		);
	}

	public ActiveJob(
		String jobId,
		ActiveJobType type,
		ActiveJobStatus status,
		GoalSnapshot directGoal,
		TaskSpec taskSpec,
		CraftRecipeStepArgs craftRecipe,
		DropItemsStepArgs dropItems,
		EntityInteractionStepArgs entityInteraction,
		String askPrompt,
		long waitUntilTick,
		int baselineResourceCount,
		int collectedCount,
		String source,
		String blockedReason,
		String lastError,
		long updatedTick
	) {
		this(
			jobId,
			type,
			status,
			directGoal,
			taskSpec,
			craftRecipe,
			dropItems,
			entityInteraction,
			null,
			null,
			null,
			null,
			null,
			null,
			askPrompt,
			waitUntilTick,
			baselineResourceCount,
			collectedCount,
			source,
			blockedReason,
			lastError,
			updatedTick
		);
	}

	public ActiveJob {
		jobId = jobId == null || jobId.isBlank() ? "job-idle" : jobId;
		type = Objects.requireNonNull(type, "type");
		status = Objects.requireNonNull(status, "status");
		source = source == null || source.isBlank() ? "runtime" : source;
		askPrompt = askPrompt == null ? null : askPrompt.trim();
		blockedReason = blockedReason == null || blockedReason.isBlank() ? null : blockedReason;
		lastError = lastError == null || lastError.isBlank() ? null : lastError;
		collectedCount = Math.max(0, collectedCount);
	}

	public static ActiveJob idle() {
		return new ActiveJob(
			"job-idle",
			ActiveJobType.IDLE,
			ActiveJobStatus.IDLE,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			-1L,
			0,
			0,
			"runtime",
			null,
			null,
			-1L
		);
	}

	public boolean isIdle() {
		return type == ActiveJobType.IDLE || status == ActiveJobStatus.IDLE;
	}
}
