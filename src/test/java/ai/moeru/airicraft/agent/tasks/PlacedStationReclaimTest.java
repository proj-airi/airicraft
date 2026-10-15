package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static ai.moeru.airicraft.agent.tasks.PlacedStationReclaim.DropStep.DONE;
import static ai.moeru.airicraft.agent.tasks.PlacedStationReclaim.DropStep.LOST;
import static ai.moeru.airicraft.agent.tasks.PlacedStationReclaim.DropStep.WAIT;
import static ai.moeru.airicraft.agent.tasks.PlacedStationReclaim.DropStep.WALK;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlacedStationReclaimTest {
	@BeforeAll
	static void bootstrapVanilla() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void pickedUpDropEndsTheReclaimAtOnce() {
		assertEquals(DONE, PlacedStationReclaim.dropStep(1, true, null));
		assertEquals(DONE, PlacedStationReclaim.dropStep(PlacedStationReclaim.DROP_TIMEOUT_TICKS + 5, true, 3.0D));
	}

	@Test
	void dropIsWaitedForBeforeWalkingOntoIt() {
		assertEquals(WAIT, PlacedStationReclaim.dropStep(PlacedStationReclaim.PICKUP_WAIT_TICKS, false, 3.0D));
		assertEquals(WALK, PlacedStationReclaim.dropStep(PlacedStationReclaim.PICKUP_WAIT_TICKS + 1, false, 3.0D));
		assertEquals(WALK, PlacedStationReclaim.dropStep(PlacedStationReclaim.PICKUP_WAIT_TICKS + 1, false, 0.4D));
	}

	@Test
	void farVanishedOrStuckDropsAreGivenUp() {
		assertEquals(LOST, PlacedStationReclaim.dropStep(PlacedStationReclaim.PICKUP_WAIT_TICKS + 1, false,
			PlacedStationReclaim.DROP_WALK_RADIUS + 0.5D));
		assertEquals(WAIT, PlacedStationReclaim.dropStep(PlacedStationReclaim.DROP_VANISHED_TICKS, false, null));
		assertEquals(LOST, PlacedStationReclaim.dropStep(PlacedStationReclaim.DROP_VANISHED_TICKS + 1, false, null));
		assertEquals(LOST, PlacedStationReclaim.dropStep(PlacedStationReclaim.DROP_TIMEOUT_TICKS + 1, false, 1.0D));
	}

	@Test
	void breakChildTargetsExactlyThePlacedStation() {
		WorldTaskRequest parent = WorldTaskRequest.craftRecipe("craft-1", "graph-1", new CraftRecipeStepArgs("minecraft:iron_pickaxe", 1));
		WorldTaskRequest child = PlacedStationReclaim.breakRequest(parent, "table", new BlockPos(2, 59, -5), "minecraft:crafting_table");
		BlockBreakStepArgs args = ((WorldTaskRequest.BreakBlocks) child.task()).args();

		assertEquals(WorldTaskType.BREAK_BLOCKS, child.type());
		assertEquals("craft-1:reclaim-table", child.taskId());
		assertEquals("graph-1", child.sourceJobId());
		assertEquals(1, args.targets().size());
		assertEquals(new GoalPosition(2, 59, -5, true), args.targets().getFirst().position());
		assertEquals(List.of("minecraft:crafting_table"), args.targets().getFirst().expectedBlockIds());
	}

	@Test
	void outcomeTextNamesWhereTheStationWent() {
		BlockPos pos = new BlockPos(2, 59, -5);
		assertEquals("tableReclaimed=2,59,-5", PlacedStationReclaim.reclaimed("table", pos));
		assertEquals("furnaceLeftAt=2,59,-5 reason=no_pickaxe", PlacedStationReclaim.leftAt("furnace", pos, "no_pickaxe"));
		assertEquals("target_out_of_range", PlacedStationReclaim.firstToken("target_out_of_range targetPos=2,59,-5"));
		assertEquals("unknown", PlacedStationReclaim.firstToken(" "));
		assertEquals(5.0D, PlacedStationReclaim.horizontalDistance(new Vec3(0, 64, 0), new Vec3(3, 70, 4)), 1e-9);
	}

	@Test
	void withoutAWorldTheStationIsLeftAndTheChildReleased() {
		RecordingBreakExecutor breaker = new RecordingBreakExecutor();
		WorldTaskRequest parent = WorldTaskRequest.craftRecipe("craft-1", "graph-1", new CraftRecipeStepArgs("minecraft:iron_pickaxe", 1));
		PlacedStationReclaim reclaim = new PlacedStationReclaim("table", new BlockPos(1, 2, 3), Blocks.CRAFTING_TABLE,
			Items.CRAFTING_TABLE, parent, breaker, () -> null, null);

		assertEquals(Optional.of("tableLeftAt=1,2,3 reason=world_unavailable"), reclaim.tick(null));
		assertEquals(0, breaker.ticks);
		assertEquals(1, breaker.worldLeaveCount);
		assertEquals(Optional.of("tableLeftAt=1,2,3 reason=world_unavailable"), reclaim.tick(null));
	}

	private static final class RecordingBreakExecutor implements WorldTaskExecutor {
		private int ticks;
		private int worldLeaveCount;

		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			ticks++;
			return Optional.empty();
		}

		@Override
		public TaskExecutionSnapshot snapshot() {
			return TaskExecutionSnapshot.idle();
		}

		@Override
		public void onWorldLeave() {
			worldLeaveCount++;
		}

		@Override
		public void shutdown() {
		}
	}
}
