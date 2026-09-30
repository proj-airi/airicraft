package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnderwaterHarvestTaskExecutorTest {
	@Test
	void sameTaskKeepsTheHandoffOriginImmutable() {
		UnderwaterHarvestTaskExecutor executor = new UnderwaterHarvestTaskExecutor(() -> null, null, null);
		WorldTaskRequest first = request(new GoalPosition(4, 52, -3, true));
		WorldTaskRequest updatedPayload = WorldTaskRequest.underwaterHarvest(
			first.taskId(),
			first.sourceJobId(),
			first.goal(),
			new UnderwaterHarvestStepArgs(new GoalPosition(99, 70, 99, true))
		);

		executor.tick(session(0L), Optional.of(first));
		executor.tick(session(1L), Optional.of(updatedPayload));

		assertEquals(new BlockPos(4, 52, -3), executor.searchOriginSnapshot());
	}

	@Test
	void recoveryStatePreservesOriginAndPausesTheActualPickupWindow() {
		BlockPos origin = new BlockPos(4, 52, -3);
		UnderwaterHarvestTaskExecutor.HarvestRun run = new UnderwaterHarvestTaskExecutor.HarvestRun(origin);
		run.startPickupWindow(80);

		for (int recoveryTick = 0; recoveryTick < 12; recoveryTick++) {
			run.tickPickup(true);
		}

		UnderwaterHarvestTaskExecutor.RunSnapshot recovering = run.snapshot();
		assertEquals(origin, recovering.searchOrigin());
		assertEquals(80, recovering.pickupTicksRemaining());
		run.tickPickup(false);
		assertEquals(79, run.snapshot().pickupTicksRemaining());
	}

	@Test
	void recoveryAndPickupPrecedeTheNextToolRequiringSourceAction() {
		assertEquals(
			UnderwaterHarvestPolicy.PreSourceAction.RECOVER_AIR,
			UnderwaterHarvestPolicy.preSourceAction(false, true, false, false, true)
		);
		assertEquals(
			UnderwaterHarvestPolicy.PreSourceAction.PICK_UP,
			UnderwaterHarvestPolicy.preSourceAction(false, false, false, true, true)
		);
		assertEquals(
			UnderwaterHarvestPolicy.PreSourceAction.SOURCE_ACTION,
			UnderwaterHarvestPolicy.preSourceAction(false, false, false, true, false)
		);
	}

	@Test
	void requiredToolSourcesIncludeOffhandButExcludeEquipmentAndCraftingSlots() {
		assertTrue(UnderwaterHarvestTaskExecutor.isRequiredToolSourceSlot(InventoryMenu.INV_SLOT_START));
		assertTrue(UnderwaterHarvestTaskExecutor.isRequiredToolSourceSlot(InventoryMenu.USE_ROW_SLOT_START));
		assertTrue(UnderwaterHarvestTaskExecutor.isRequiredToolSourceSlot(InventoryMenu.USE_ROW_SLOT_END - 1));
		assertTrue(UnderwaterHarvestTaskExecutor.isRequiredToolSourceSlot(InventoryMenu.SHIELD_SLOT));
		assertFalse(UnderwaterHarvestTaskExecutor.isRequiredToolSourceSlot(InventoryMenu.ARMOR_SLOT_START));
		assertFalse(UnderwaterHarvestTaskExecutor.isRequiredToolSourceSlot(InventoryMenu.CRAFT_SLOT_START));
	}

	@Test
	void absoluteApproachTimeoutExcludesTheTargetAndRequiresAFreshScan() {
		BlockPos targetPos = new BlockPos(8, 52, -3);
		UnderwaterHarvestTaskExecutor.HarvestTarget target = target(
			targetPos,
			UnderwaterHarvestPolicy.SourceEnvironment.WATER_ADJACENT
		);
		UnderwaterHarvestTaskExecutor.HarvestRun run = new UnderwaterHarvestTaskExecutor.HarvestRun(
			new BlockPos(4, 52, -3)
		);
		run.installBatch(new UnderwaterHarvestTaskExecutor.SourceScan(List.of(target), Set.of(targetPos)));
		assertTrue(run.prepareApproach(targetPos, 10.0D));

		UnderwaterHarvestPolicy.ApproachUpdate update = null;
		for (int activeTick = 1; activeTick <= UnderwaterHarvestPolicy.APPROACH_TIMEOUT_TICKS; activeTick++) {
			double distance = 10.0D - (activeTick / 20) * 0.30D;
			update = run.observeApproach(distance);
		}
		assertEquals(UnderwaterHarvestPolicy.ApproachDecision.EXCLUDE_TARGET, update.decision());

		run.excludeCurrentTarget(targetPos);
		UnderwaterHarvestTaskExecutor.RunSnapshot excluded = run.snapshot();
		assertTrue(excluded.needsSourceScan());
		assertTrue(excluded.unreachableTargets().contains(targetPos));

		run.installBatch(new UnderwaterHarvestTaskExecutor.SourceScan(List.of(), Set.of(targetPos)));
		assertTrue(run.snapshot().needsSourceScan());
		assertTrue(run.snapshot().unreachableTargets().contains(targetPos));
		assertEquals(
			UnderwaterHarvestPolicy.SourceExhaustion.RESOURCE_UNREACHABLE_NEARBY,
			UnderwaterHarvestPolicy.sourceExhaustion(1, run.snapshot().unreachableTargets().size(), true)
		);
	}

	@Test
	void changedFluidStateInvalidatesTheCachedTargetAndUsesTheFreshClassification() {
		BlockPos targetPos = new BlockPos(8, 52, -3);
		UnderwaterHarvestTaskExecutor.HarvestRun run = new UnderwaterHarvestTaskExecutor.HarvestRun(
			new BlockPos(4, 52, -3)
		);
		run.installBatch(new UnderwaterHarvestTaskExecutor.SourceScan(
			List.of(target(targetPos, UnderwaterHarvestPolicy.SourceEnvironment.DRY)),
			Set.of(targetPos)
		));

		assertFalse(run.reconcileEnvironment(UnderwaterHarvestPolicy.SourceEnvironment.WATER_ADJACENT));
		assertTrue(run.snapshot().needsSourceScan());
		assertTrue(run.snapshot().unreachableTargets().isEmpty());

		run.installBatch(new UnderwaterHarvestTaskExecutor.SourceScan(
			List.of(target(targetPos, UnderwaterHarvestPolicy.SourceEnvironment.WATER_ADJACENT)),
			Set.of(targetPos)
		));
		assertEquals(
			UnderwaterHarvestPolicy.SourceEnvironment.WATER_ADJACENT,
			run.snapshot().currentEnvironment()
		);
	}

	@Test
	void approachEffectRoutesDryOnlyToNavigationAndWaterOnlyToDirectMovement() {
		AtomicInteger navigationCalls = new AtomicInteger();
		AtomicInteger directCalls = new AtomicInteger();

		String dry = UnderwaterHarvestTaskExecutor.routeApproachEffect(
			UnderwaterHarvestPolicy.SourceEnvironment.DRY,
			() -> {
				navigationCalls.incrementAndGet();
				return "navigationFacade";
			},
			() -> {
				directCalls.incrementAndGet();
				return "direct";
			}
		);
		String adjacent = UnderwaterHarvestTaskExecutor.routeApproachEffect(
			UnderwaterHarvestPolicy.SourceEnvironment.WATER_ADJACENT,
			() -> {
				navigationCalls.incrementAndGet();
				return "navigationFacade";
			},
			() -> {
				directCalls.incrementAndGet();
				return "direct";
			}
		);
		String contained = UnderwaterHarvestTaskExecutor.routeApproachEffect(
			UnderwaterHarvestPolicy.SourceEnvironment.FLUID_CONTAINED,
			() -> {
				navigationCalls.incrementAndGet();
				return "navigationFacade";
			},
			() -> {
				directCalls.incrementAndGet();
				return "direct";
			}
		);

		assertEquals("navigationFacade", dry);
		assertEquals("direct", adjacent);
		assertEquals("direct", contained);
		assertEquals(1, navigationCalls.get());
		assertEquals(2, directCalls.get());
	}

	private static UnderwaterHarvestTaskExecutor.HarvestTarget target(
		BlockPos pos,
		UnderwaterHarvestPolicy.SourceEnvironment environment
	) {
		return new UnderwaterHarvestTaskExecutor.HarvestTarget(pos, "minecraft:clay", environment);
	}

	private static WorldTaskRequest request(GoalPosition origin) {
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:clay"), 4, List.of("minecraft:clay_ball"), List.of()),
			0L,
			"action_graph"
		);
		return WorldTaskRequest.underwaterHarvest(
			"underwater-task",
			"mine-job",
			goal,
			new UnderwaterHarvestStepArgs(origin)
		);
	}

	private static SessionSnapshot session(long tick) {
		return new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			tick
		);
	}
}
