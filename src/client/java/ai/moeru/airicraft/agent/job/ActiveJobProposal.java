package ai.moeru.airicraft.agent.job;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.tasks.BlockBreakStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectObsidianStepArgs;
import ai.moeru.airicraft.agent.tasks.CropTendingStepArgs;
import ai.moeru.airicraft.agent.tasks.LureEntitiesStepArgs;
import ai.moeru.airicraft.agent.tasks.LocateStrongholdStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockPlacementStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockUseStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectSmeltedItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.DropItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.EntityInteractionStepArgs;
import ai.moeru.airicraft.agent.tasks.ReturnToSurfaceStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.TaskSpec;

import java.util.Objects;

public record ActiveJobProposal(
	ActiveJobType type,
	String targetPlayer,
	GoalPosition position,
	GoalMineSpec mineSpec,
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
	CropTendingStepArgs cropTending,
	LureEntitiesStepArgs lureEntities,
	CollectObsidianStepArgs collectObsidian,
	LocateStrongholdStepArgs locateStronghold
) {
	public ActiveJobProposal(
		ActiveJobType type,
		String targetPlayer,
		GoalPosition position,
		GoalMineSpec mineSpec,
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
		CropTendingStepArgs cropTending,
		LureEntitiesStepArgs lureEntities,
		CollectObsidianStepArgs collectObsidian
	) {
		this(type, targetPlayer, position, mineSpec, taskSpec, craftRecipe, dropItems, entityInteraction, smeltItems, collectSmeltedItems, returnToSurface, blockPlacement, blockUse, blockBreak, askPrompt, cropTending, lureEntities, collectObsidian, null);
	}

	public ActiveJobProposal(
		ActiveJobType type,
		String targetPlayer,
		GoalPosition position,
		GoalMineSpec mineSpec,
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
		CropTendingStepArgs cropTending,
		LureEntitiesStepArgs lureEntities
	) {
		this(type, targetPlayer, position, mineSpec, taskSpec, craftRecipe, dropItems, entityInteraction, smeltItems, collectSmeltedItems, returnToSurface, blockPlacement, blockUse, blockBreak, askPrompt, cropTending, lureEntities, null);
	}

	public ActiveJobProposal(
		ActiveJobType type,
		String targetPlayer,
		GoalPosition position,
		GoalMineSpec mineSpec,
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
		CropTendingStepArgs cropTending
	) {
		this(type, targetPlayer, position, mineSpec, taskSpec, craftRecipe, dropItems, entityInteraction, smeltItems, collectSmeltedItems, returnToSurface, blockPlacement, blockUse, blockBreak, askPrompt, cropTending, null);
	}

	public ActiveJobProposal(
		ActiveJobType type,
		String targetPlayer,
		GoalPosition position,
		GoalMineSpec mineSpec,
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
		String askPrompt
	) {
		this(type, targetPlayer, position, mineSpec, taskSpec, craftRecipe, dropItems, entityInteraction, smeltItems, collectSmeltedItems, returnToSurface, blockPlacement, blockUse, blockBreak, askPrompt, null);
	}

	public ActiveJobProposal(
		ActiveJobType type,
		String targetPlayer,
		GoalPosition position,
		GoalMineSpec mineSpec,
		TaskSpec taskSpec,
		CraftRecipeStepArgs craftRecipe,
		DropItemsStepArgs dropItems,
		String askPrompt
	) {
		this(type, targetPlayer, position, mineSpec, taskSpec, craftRecipe, dropItems, null, null, null, null, null, null, null, askPrompt);
	}

	public ActiveJobProposal(
		ActiveJobType type,
		String targetPlayer,
		GoalPosition position,
		GoalMineSpec mineSpec,
		TaskSpec taskSpec,
		CraftRecipeStepArgs craftRecipe,
		DropItemsStepArgs dropItems,
		EntityInteractionStepArgs entityInteraction,
		String askPrompt
	) {
		this(type, targetPlayer, position, mineSpec, taskSpec, craftRecipe, dropItems, entityInteraction, null, null, null, null, null, null, askPrompt);
	}

	public ActiveJobProposal {
		type = Objects.requireNonNull(type, "type");
		targetPlayer = targetPlayer == null || targetPlayer.isBlank() ? null : targetPlayer;
		askPrompt = askPrompt == null || askPrompt.isBlank() ? null : askPrompt.trim();
	}

	public static ActiveJobProposal followPlayer(String targetPlayer) {
		return new ActiveJobProposal(ActiveJobType.FOLLOW_PLAYER, targetPlayer, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	public static ActiveJobProposal navigateTo(GoalPosition position) {
		return new ActiveJobProposal(ActiveJobType.NAVIGATE_TO, null, position, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	public static ActiveJobProposal mineBlocks(GoalMineSpec mineSpec) {
		return new ActiveJobProposal(ActiveJobType.MINE_BLOCKS, null, null, mineSpec, null, null, null, null, null, null, null, null, null, null, null);
	}

	public static ActiveJobProposal ensureBlocksInInventory(GoalMineSpec mineSpec) {
		return new ActiveJobProposal(ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY, null, null, mineSpec, null, null, null, null, null, null, null, null, null, null, null);
	}

	public static ActiveJobProposal collectResource(TaskSpec taskSpec) {
		return new ActiveJobProposal(ActiveJobType.COLLECT_RESOURCE, null, null, null, taskSpec, null, null, null, null, null, null, null, null, null, null);
	}

	public static ActiveJobProposal craftRecipe(CraftRecipeStepArgs craftRecipe) {
		return new ActiveJobProposal(ActiveJobType.CRAFT_RECIPE, null, null, null, null, craftRecipe, null, null, null, null, null, null, null, null, null);
	}

	public static ActiveJobProposal dropItems(DropItemsStepArgs dropItems) {
		return new ActiveJobProposal(ActiveJobType.DROP_ITEMS, null, null, null, null, null, dropItems, null, null, null, null, null, null, null, null);
	}

	public static ActiveJobProposal smeltItems(SmeltItemsStepArgs smeltItems) {
		return new ActiveJobProposal(ActiveJobType.SMELT_ITEMS, null, null, null, null, null, null, null, smeltItems, null, null, null, null, null, null);
	}

	public static ActiveJobProposal collectSmeltedItems(CollectSmeltedItemsStepArgs collectSmeltedItems) {
		return new ActiveJobProposal(ActiveJobType.COLLECT_SMELTED_ITEMS, null, null, null, null, null, null, null, null, collectSmeltedItems, null, null, null, null, null);
	}

	public static ActiveJobProposal attackEntity(EntityInteractionStepArgs entityInteraction) {
		return new ActiveJobProposal(ActiveJobType.ATTACK_ENTITY, null, null, null, null, null, null, entityInteraction, null, null, null, null, null, null, null);
	}

	public static ActiveJobProposal useEntity(EntityInteractionStepArgs entityInteraction) {
		return new ActiveJobProposal(ActiveJobType.USE_ENTITY, null, null, null, null, null, null, entityInteraction, null, null, null, null, null, null, null);
	}

	public static ActiveJobProposal returnToSurface(ReturnToSurfaceStepArgs returnToSurface) {
		return new ActiveJobProposal(ActiveJobType.RETURN_TO_SURFACE, null, null, null, null, null, null, null, null, null, returnToSurface, null, null, null, null);
	}

	public static ActiveJobProposal placeBlock(BlockPlacementStepArgs blockPlacement) {
		return new ActiveJobProposal(ActiveJobType.PLACE_BLOCK, null, null, null, null, null, null, null, null, null, null, blockPlacement, null, null, null);
	}

	public static ActiveJobProposal useBlock(BlockUseStepArgs blockUse) {
		return new ActiveJobProposal(ActiveJobType.USE_BLOCK, null, null, null, null, null, null, null, null, null, null, null, blockUse, null, null);
	}

	public static ActiveJobProposal breakBlocks(BlockBreakStepArgs blockBreak) {
		return new ActiveJobProposal(ActiveJobType.BREAK_BLOCKS, null, null, null, null, null, null, null, null, null, null, null, null, blockBreak, null);
	}

	public static ActiveJobProposal tendCrops(CropTendingStepArgs args) {
		return new ActiveJobProposal(ActiveJobType.TEND_CROPS, null, null, null, null, null, null, null, null, null, null, null, null, null, null, Objects.requireNonNull(args));
	}

	public static ActiveJobProposal lureEntities(LureEntitiesStepArgs args) {
		return new ActiveJobProposal(ActiveJobType.LURE_ENTITIES, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, Objects.requireNonNull(args));
	}

	public static ActiveJobProposal fightEnderDragon() {
		return new ActiveJobProposal(ActiveJobType.FIGHT_ENDER_DRAGON, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	public static ActiveJobProposal locateStronghold(LocateStrongholdStepArgs args) {
		return new ActiveJobProposal(ActiveJobType.LOCATE_STRONGHOLD, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, Objects.requireNonNull(args));
	}

	public static ActiveJobProposal collectObsidian(CollectObsidianStepArgs args) {
		return new ActiveJobProposal(ActiveJobType.COLLECT_OBSIDIAN, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, Objects.requireNonNull(args));
	}

	public static ActiveJobProposal askUser(String askPrompt) {
		return new ActiveJobProposal(ActiveJobType.ASK_USER, null, null, null, null, null, null, null, null, null, null, null, null, null, askPrompt);
	}
}
