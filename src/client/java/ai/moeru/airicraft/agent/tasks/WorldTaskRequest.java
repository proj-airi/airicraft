package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;

import java.util.List;
import java.util.Objects;

public record WorldTaskRequest(String taskId, String sourceJobId, Task task) {
	public WorldTaskRequest {
		taskId = normalize(taskId, "taskId");
		sourceJobId = normalize(sourceJobId, "sourceJobId");
		task = Objects.requireNonNull(task, "task");
	}

	public WorldTaskType type() {
		return switch (task) {
			case Follow ignored -> WorldTaskType.FOLLOW;
			case Navigate ignored -> WorldTaskType.NAVIGATE;
			case Mine ignored -> WorldTaskType.MINE;
			case UnderwaterHarvest ignored -> WorldTaskType.UNDERWATER_HARVEST;
			case CraftRecipe ignored -> WorldTaskType.CRAFT_RECIPE;
			case DropItems ignored -> WorldTaskType.DROP_ITEMS;
			case SmeltItems ignored -> WorldTaskType.SMELT_ITEMS;
			case CollectSmeltedItems ignored -> WorldTaskType.COLLECT_SMELTED_ITEMS;
			case AttackEntity ignored -> WorldTaskType.ATTACK_ENTITY;
			case UseEntity ignored -> WorldTaskType.USE_ENTITY;
			case PlaceBlock ignored -> WorldTaskType.PLACE_BLOCK;
			case UseBlock ignored -> WorldTaskType.USE_BLOCK;
			case BreakBlocks ignored -> WorldTaskType.BREAK_BLOCKS;
			case TendCrops ignored -> WorldTaskType.TEND_CROPS;
			case LureEntities ignored -> WorldTaskType.LURE_ENTITIES;
			case FightEnderDragon ignored -> WorldTaskType.FIGHT_ENDER_DRAGON;
			case CollectObsidian ignored -> WorldTaskType.COLLECT_OBSIDIAN;
			case LocateStronghold ignored -> WorldTaskType.LOCATE_STRONGHOLD;
			case ExploreFor ignored -> WorldTaskType.EXPLORE_FOR;
			case CollectBlazeRods ignored -> WorldTaskType.COLLECT_BLAZE_RODS;
			case CollectFlint ignored -> WorldTaskType.COLLECT_FLINT;
			case ReturnToSurface ignored -> WorldTaskType.RETURN_TO_SURFACE;
		};
	}

	public GoalSnapshot goal() {
		return switch (task) {
			case Follow value -> value.goal();
			case Navigate value -> value.goal();
			case Mine value -> value.goal();
			case UnderwaterHarvest value -> value.goal();
			default -> null;
		};
	}

	public WorldTaskRequest withPickupSweepPositions(List<GoalPosition> positions) {
		if (!(task instanceof Mine value)) {
			throw new IllegalStateException("pickup sweep is only valid for mine tasks");
		}
		return new WorldTaskRequest(taskId, sourceJobId, new Mine(value.goal(), positions, value.mineGoalSatisfied()));
	}

	public WorldTaskRequest withMineGoalSatisfied(boolean satisfied) {
		Task updated = switch (task) {
			case Mine value -> new Mine(value.goal(), value.pickupSweepPositions(), satisfied);
			case UnderwaterHarvest value -> new UnderwaterHarvest(value.goal(), value.args(), satisfied);
			default -> throw new IllegalStateException("mine satisfaction is only valid for mining tasks");
		};
		return new WorldTaskRequest(taskId, sourceJobId, updated);
	}

	public static WorldTaskRequest direct(String taskId, GoalSnapshot goal) {
		Task task = switch (Objects.requireNonNull(goal, "goal").type()) {
			case FOLLOW_PLAYER -> new Follow(goal);
			case NAVIGATE_TO -> new Navigate(goal);
			case MINE_BLOCKS -> new Mine(goal, List.of(), false);
		};
		return new WorldTaskRequest(taskId, taskId, task);
	}

	public static WorldTaskRequest collectMine(String taskId, String sourceJobId, GoalSnapshot goal) {
		return collectMine(taskId, sourceJobId, goal, List.of());
	}

	public static WorldTaskRequest collectMine(String taskId, String sourceJobId, GoalSnapshot goal, List<GoalPosition> positions) {
		return new WorldTaskRequest(taskId, sourceJobId, new Mine(goal, positions, false));
	}

	public static WorldTaskRequest underwaterHarvest(String taskId, String sourceJobId, GoalSnapshot goal, UnderwaterHarvestStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new UnderwaterHarvest(goal, args, false));
	}

	public static WorldTaskRequest craftRecipe(String taskId, String sourceJobId, CraftRecipeStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new CraftRecipe(args));
	}

	public static WorldTaskRequest dropItems(String taskId, String sourceJobId, DropItemsStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new DropItems(args));
	}

	public static WorldTaskRequest attackEntity(String taskId, String sourceJobId, EntityInteractionStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new AttackEntity(args));
	}

	public static WorldTaskRequest useEntity(String taskId, String sourceJobId, EntityInteractionStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new UseEntity(args));
	}

	public static WorldTaskRequest smeltItems(String taskId, String sourceJobId, SmeltItemsStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new SmeltItems(args));
	}

	public static WorldTaskRequest collectSmeltedItems(String taskId, String sourceJobId, CollectSmeltedItemsStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new CollectSmeltedItems(args));
	}

	public static WorldTaskRequest returnToSurface(String taskId, String sourceJobId, ReturnToSurfaceStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new ReturnToSurface(args));
	}

	public static WorldTaskRequest placeBlock(String taskId, String sourceJobId, BlockPlacementStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new PlaceBlock(args));
	}

	public static WorldTaskRequest useBlock(String taskId, String sourceJobId, BlockUseStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new UseBlock(args));
	}

	public static WorldTaskRequest breakBlocks(String taskId, String sourceJobId, BlockBreakStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new BreakBlocks(args));
	}

	public static WorldTaskRequest tendCrops(String taskId, String sourceJobId, CropTendingStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new TendCrops(args));
	}

	public record TendCrops(CropTendingStepArgs args) implements Task { public TendCrops { Objects.requireNonNull(args, "cropTending"); } }

	public static WorldTaskRequest lureEntities(String taskId, String sourceJobId, LureEntitiesStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new LureEntities(args));
	}
	public record LureEntities(LureEntitiesStepArgs args) implements Task { public LureEntities { Objects.requireNonNull(args, "lureEntities"); } }

	public static WorldTaskRequest fightEnderDragon(String taskId, String sourceJobId) {
		return new WorldTaskRequest(taskId, sourceJobId, new FightEnderDragon());
	}
	public record FightEnderDragon() implements Task {}

	public static WorldTaskRequest collectObsidian(String taskId, String sourceJobId, CollectObsidianStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new CollectObsidian(args));
	}
	public record CollectObsidian(CollectObsidianStepArgs args) implements Task { public CollectObsidian { Objects.requireNonNull(args, "collectObsidian"); } }

	public static WorldTaskRequest locateStronghold(String taskId, String sourceJobId, LocateStrongholdStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new LocateStronghold(args));
	}
	public record LocateStronghold(LocateStrongholdStepArgs args) implements Task { public LocateStronghold { Objects.requireNonNull(args, "locateStronghold"); } }

	public static WorldTaskRequest exploreFor(String taskId, String sourceJobId, ExploreForStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new ExploreFor(args));
	}
	public record ExploreFor(ExploreForStepArgs args) implements Task { public ExploreFor { Objects.requireNonNull(args, "exploreFor"); } }

	public static WorldTaskRequest collectBlazeRods(String taskId, String sourceJobId, CollectBlazeRodsStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new CollectBlazeRods(args));
	}
	public record CollectBlazeRods(CollectBlazeRodsStepArgs args) implements Task { public CollectBlazeRods { Objects.requireNonNull(args, "collectBlazeRods"); } }

	public static WorldTaskRequest collectFlint(String taskId, String sourceJobId, CollectFlintStepArgs args) {
		return new WorldTaskRequest(taskId, sourceJobId, new CollectFlint(args));
	}
	public record CollectFlint(CollectFlintStepArgs args) implements Task { public CollectFlint { Objects.requireNonNull(args, "collectFlint"); } }

	public sealed interface Task {
	}

	public record Follow(GoalSnapshot goal) implements Task {
		public Follow { requireGoal(goal, GoalType.FOLLOW_PLAYER); }
	}

	public record Navigate(GoalSnapshot goal) implements Task {
		public Navigate { requireGoal(goal, GoalType.NAVIGATE_TO); }
	}

	public record Mine(GoalSnapshot goal, List<GoalPosition> pickupSweepPositions, boolean mineGoalSatisfied) implements Task {
		public Mine {
			requireGoal(goal, GoalType.MINE_BLOCKS);
			pickupSweepPositions = List.copyOf(pickupSweepPositions == null ? List.of() : pickupSweepPositions);
		}
	}

	public record UnderwaterHarvest(GoalSnapshot goal, UnderwaterHarvestStepArgs args, boolean mineGoalSatisfied) implements Task {
		public UnderwaterHarvest {
			requireGoal(goal, GoalType.MINE_BLOCKS);
			Objects.requireNonNull(args, "underwaterHarvest");
		}
	}

	public record CraftRecipe(CraftRecipeStepArgs args) implements Task { public CraftRecipe { Objects.requireNonNull(args, "craftRecipe"); } }
	public record DropItems(DropItemsStepArgs args) implements Task { public DropItems { Objects.requireNonNull(args, "dropItems"); } }
	public record SmeltItems(SmeltItemsStepArgs args) implements Task { public SmeltItems { Objects.requireNonNull(args, "smeltItems"); } }
	public record CollectSmeltedItems(CollectSmeltedItemsStepArgs args) implements Task { public CollectSmeltedItems { Objects.requireNonNull(args, "collectSmeltedItems"); } }
	public record AttackEntity(EntityInteractionStepArgs args) implements Task { public AttackEntity { Objects.requireNonNull(args, "entityInteraction"); } }
	public record UseEntity(EntityInteractionStepArgs args) implements Task { public UseEntity { Objects.requireNonNull(args, "entityInteraction"); } }
	public record PlaceBlock(BlockPlacementStepArgs args) implements Task { public PlaceBlock { Objects.requireNonNull(args, "blockPlacement"); } }
	public record UseBlock(BlockUseStepArgs args) implements Task { public UseBlock { Objects.requireNonNull(args, "blockUse"); } }
	public record BreakBlocks(BlockBreakStepArgs args) implements Task { public BreakBlocks { Objects.requireNonNull(args, "blockBreak"); } }
	public record ReturnToSurface(ReturnToSurfaceStepArgs args) implements Task { public ReturnToSurface { Objects.requireNonNull(args, "returnToSurface"); } }

	private static void requireGoal(GoalSnapshot goal, GoalType expectedType) {
		if (Objects.requireNonNull(goal, "goal").type() != expectedType) {
			throw new IllegalArgumentException("goal must be " + expectedType);
		}
	}

	private static String normalize(String value, String fieldName) {
		String trimmed = value == null ? null : value.trim();
		if (trimmed == null || trimmed.isEmpty()) {
			throw new IllegalArgumentException(fieldName + " is required");
		}
		return trimmed;
	}
}
