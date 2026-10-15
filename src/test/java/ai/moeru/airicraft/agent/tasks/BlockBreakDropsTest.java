package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.BlockBreakDrops.Drop;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ai.moeru.airicraft.agent.tasks.BlockBreakDrops.Kind.DONE;
import static ai.moeru.airicraft.agent.tasks.BlockBreakDrops.Kind.LEAVE;
import static ai.moeru.airicraft.agent.tasks.BlockBreakDrops.Kind.WAIT;
import static ai.moeru.airicraft.agent.tasks.BlockBreakDrops.Kind.WALK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockBreakDropsTest {
	private static final BlockPos BED = new BlockPos(10, 64, -3);
	private static final int AFTER_WAIT = DropPickup.PICKUP_WAIT_TICKS + 1;
	private static final int TIMEOUT = BlockBreakDrops.timeoutTicks(1);

	@BeforeAll
	static void bootstrapVanilla() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void onlyNewItemsNearABrokenCellCount() {
		List<BlockPos> broken = List.of(BED);
		Vec3 beside = new Vec3(11.2D, 64.0D, -2.5D);
		assertTrue(BlockBreakDrops.counts(7, beside, Set.of(), broken));
		assertFalse(BlockBreakDrops.counts(7, beside, Set.of(7), broken), "an item lying there before the job is not a drop of it");
		assertFalse(BlockBreakDrops.counts(8, new Vec3(16.0D, 64.0D, -2.5D), Set.of(), broken), "too far to the side");
		assertTrue(BlockBreakDrops.counts(9, new Vec3(10.5D, 64.0D - BlockBreakDrops.SEARCH_BELOW, -2.5D), Set.of(), broken),
			"a drop that fell down a ledge still counts");
		assertFalse(BlockBreakDrops.counts(9, new Vec3(10.5D, 63.0D - BlockBreakDrops.SEARCH_BELOW, -2.5D), Set.of(), broken));
		assertFalse(BlockBreakDrops.counts(9, new Vec3(10.5D, 65.5D + BlockBreakDrops.SEARCH_ABOVE, -2.5D), Set.of(), broken));
		assertTrue(BlockBreakDrops.counts(9, new Vec3(30.5D, 20.0D, 0.5D), Set.of(), List.of(BED, new BlockPos(30, 20, 0))),
			"any broken cell will do");
	}

	@Test
	void pickupBoxGetsTheFirstTicksAndNoDropEndsCollecting() {
		Drop near = drop(1, 2.0D, 1.0D);
		assertEquals(WAIT, BlockBreakDrops.decide(DropPickup.PICKUP_WAIT_TICKS, TIMEOUT, List.of(near)).kind());
		assertEquals(WAIT, BlockBreakDrops.decide(1, TIMEOUT, List.of()).kind(), "a drop may not have appeared yet");
		assertEquals(DONE, BlockBreakDrops.decide(AFTER_WAIT, TIMEOUT, List.of()).kind(), "glass drops nothing");
	}

	@Test
	void nearestSettledDropIsWalkedToFirst() {
		Drop far = drop(1, 5.0D, 1.0D);
		Drop nearest = drop(2, 1.5D, 3.0D);
		BlockBreakDrops.Decision decision = BlockBreakDrops.decide(AFTER_WAIT, TIMEOUT, List.of(far, nearest));
		assertEquals(WALK, decision.kind());
		assertEquals(2, decision.drop().id());

		Drop flying = new Drop(3, "minecraft:red_bed", new Vec3(0, 70, 0), false, false, true, 0.5D, 0.5D);
		assertEquals(1, BlockBreakDrops.decide(AFTER_WAIT, TIMEOUT, List.of(far, flying)).drop().id(), "a drop still flying is not walked to");
		assertEquals(WAIT, BlockBreakDrops.decide(AFTER_WAIT, TIMEOUT, List.of(flying)).kind());
	}

	@Test
	void unsafeFarOrUnholdableDropsAreLeftWithAReason() {
		Drop inLava = new Drop(1, "minecraft:cobblestone", new Vec3(0, 64, 0), true, true, true, 2.0D, 1.0D);
		Drop tooFar = new Drop(2, "minecraft:cobblestone", new Vec3(0, 64, 0), true, false, true, 3.0D,
			DropPickup.DROP_WALK_RADIUS + 0.5D);
		Drop noRoom = new Drop(3, "minecraft:cobblestone", new Vec3(0, 64, 0), false, false, false, 1.0D, 1.0D);
		assertLeft(BlockBreakDrops.decide(AFTER_WAIT, TIMEOUT, List.of(inLava)), 1, "lava_or_fire");
		assertLeft(BlockBreakDrops.decide(AFTER_WAIT, TIMEOUT, List.of(tooFar)), 2, "too_far");
		assertLeft(BlockBreakDrops.decide(AFTER_WAIT, TIMEOUT, List.of(noRoom)), 3, "inventory_full");
		assertLeft(BlockBreakDrops.decide(TIMEOUT + 1, TIMEOUT, List.of(drop(4, 1.0D, 1.0D))), 4, "timeout");
		assertEquals(DONE, BlockBreakDrops.decide(TIMEOUT + 1, TIMEOUT, List.of()).kind());
	}

	@Test
	void aFailedWalkSaysWhetherTheDropLiesTooDeep() {
		assertEquals("unreachable", BlockBreakDrops.walkFailure(1.0D));
		assertEquals("unreachable", BlockBreakDrops.walkFailure(BlockBreakDrops.MAX_SAFE_FALL));
		assertEquals("deep_drop", BlockBreakDrops.walkFailure(BlockBreakDrops.MAX_SAFE_FALL + 1.0D));
		assertEquals(3, BlockBreakDrops.OPTIONS.maxSafeFall());
		assertTrue(BlockBreakDrops.OPTIONS.walkOnly());
	}

	@Test
	void collectingTimeGrowsWithTheBrokenCellsUpToACap() {
		assertEquals(DropPickup.DROP_TIMEOUT_TICKS, BlockBreakDrops.timeoutTicks(1));
		assertEquals(DropPickup.DROP_TIMEOUT_TICKS + 2 * BlockBreakDrops.TIMEOUT_PER_EXTRA_CELL_TICKS, BlockBreakDrops.timeoutTicks(3));
		assertEquals(BlockBreakDrops.MAX_TIMEOUT_TICKS, BlockBreakDrops.timeoutTicks(BlockBreakStepArgs.MAX_TARGETS));
	}

	@Test
	void outcomeNamesWhatWasPickedAndWhatWasLeft() {
		Map<String, Integer> before = new LinkedHashMap<>(Map.of("minecraft:dirt", 5, "minecraft:iron_pickaxe", 1));
		Map<String, Integer> after = new LinkedHashMap<>();
		after.put("minecraft:dirt", 7);
		after.put("minecraft:red_bed", 1);
		Map<String, Integer> picked = BlockBreakDrops.gains(before, after);
		assertEquals(Map.of("minecraft:dirt", 2, "minecraft:red_bed", 1), picked, "a used-up tool is not a negative pickup");

		assertEquals("picked=minecraft:dirt x2,minecraft:red_bed x1", BlockBreakDrops.outcomeText(picked, List.of()));
		assertEquals("picked=none dropsLeft=minecraft:cobblestone@1,2,3,minecraft:dirt@4,5,6 reason=lava_or_fire,too_far",
			BlockBreakDrops.outcomeText(Map.of(), List.of(
				new BlockBreakDrops.Left("minecraft:cobblestone", new BlockPos(1, 2, 3), "lava_or_fire"),
				new BlockBreakDrops.Left("minecraft:dirt", new BlockPos(4, 5, 6), "too_far"))));
		assertEquals("picked=none dropsLeft=minecraft:dirt@10,64,-3,minecraft:dirt@10,64,-3 reason=timeout",
			BlockBreakDrops.outcomeText(Map.of(), List.of(new BlockBreakDrops.Left("minecraft:dirt", BED, "timeout"),
				new BlockBreakDrops.Left("minecraft:dirt", BED, "timeout"))), "each reason is named once");
	}

	@Test
	void walkGoalIsTheCellTheDropRestsIn() {
		assertEquals(new GoalPosition(10, 64, -3, true), DropPickup.goal(new Vec3(10.4D, 63.9D, -2.6D)));
	}

	@Test
	void pickupTextIsAppendedWithoutChangingTheBreakOutcome() {
		TaskTerminalEvent completed = new TaskTerminalEvent("job-1", null, TaskExecutionState.COMPLETED,
			"break_blocks_succeeded brokenTargets=2 skippedTargets=0", TaskTerminationCause.GOAL_REACHED);
		TaskTerminalEvent withPickup = BlockBreakJobExecutor.withPickup(completed, "picked=none dropsLeft=minecraft:red_bed@1,2,3 reason=too_far");
		assertEquals(TaskExecutionState.COMPLETED, withPickup.terminalState(), "a failed pickup never fails the break");
		assertEquals(TaskFailureCode.NONE, withPickup.failureCode());
		assertEquals("break_blocks_succeeded brokenTargets=2 skippedTargets=0 picked=none dropsLeft=minecraft:red_bed@1,2,3 reason=too_far",
			withPickup.message());

		TaskTerminalEvent failed = new TaskTerminalEvent("job-1", null, TaskExecutionState.FAILED, "break_timeout targetPos=1,2,3",
			null, TaskFailureCode.TRANSIENT);
		TaskTerminalEvent failedWithPickup = BlockBreakJobExecutor.withPickup(failed, "picked=minecraft:dirt x1");
		assertEquals(TaskExecutionState.FAILED, failedWithPickup.terminalState());
		assertEquals(TaskFailureCode.TRANSIENT, failedWithPickup.failureCode());
		assertEquals("break_timeout targetPos=1,2,3 picked=minecraft:dirt x1", failedWithPickup.message());
	}

	@Test
	void aBreakThatBrokeNothingIsReportedUnchanged() {
		BlockBreakJobExecutor executor = new BlockBreakJobExecutor(() -> null, new BlockBreakTaskExecutor(() -> null), null);
		WorldTaskRequest request = WorldTaskRequest.breakBlocks("job-1", "job-1", new BlockBreakStepArgs(List.of(
			new BlockBreakStepArgs.Target(new GoalPosition(10, 64, -3, true), List.of("minecraft:red_bed")))));
		SessionSnapshot session = new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", false, 0, 1L);

		Optional<TaskTerminalEvent> event = executor.tick(session, Optional.of(request));

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.FAILED, event.get().terminalState());
		assertEquals("world_unavailable", event.get().message());
		assertEquals(TaskExecutionState.FAILED, executor.snapshot().state());
		assertEquals(Optional.empty(), executor.tick(session, Optional.of(request)), "the end is reported once");
		executor.tick(session, Optional.empty());
		assertEquals(TaskExecutionState.IDLE, executor.snapshot().state());
	}

	private static Drop drop(int id, double fromFeet, double fromBroken) {
		return new Drop(id, "minecraft:red_bed", new Vec3(0, 64, 0), true, false, true, fromFeet, fromBroken);
	}

	private static void assertLeft(BlockBreakDrops.Decision decision, int id, String reason) {
		assertEquals(LEAVE, decision.kind());
		assertEquals(id, decision.drop().id());
		assertEquals(reason, decision.reason());
	}
}
