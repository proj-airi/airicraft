package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockInteractionTaskExecutorTest {
	@Test
	void roofLipCanBeClickedBelowItsOccludedFaceCenter() {
		BlockPos support = new BlockPos(0, 136, 4);
		Vec3 eye = new Vec3(-0.5D, 135.62D, 5.5D);
		List<BlockPos> roof = List.of(support, new BlockPos(-1, 136, 4), new BlockPos(-1, 136, 5));
		Vec3 center = new Vec3(0.5D, 136.5D, 5D);
		assertEquals(new BlockPos(-1, 136, 5), roofHit(roof, eye, center, Direction.SOUTH).getBlockPos());
		Vec3 lower = new Vec3(0.5D, 136.1D, 5D);
		assertEquals(support, roofHit(roof, eye, lower, Direction.SOUTH).getBlockPos());
		assertEquals(Direction.SOUTH, roofHit(roof, eye, lower, Direction.SOUTH).getDirection());

		Optional<Vec3> selected = BlockInteractionTaskExecutor.selectPlacementHitPoint(support, Direction.SOUTH, point -> {
			BlockHitResult hit = roofHit(roof, eye, point, Direction.SOUTH);
			return hit != null && hit.getBlockPos().equals(support) && hit.getDirection() == Direction.SOUTH;
		});
		assertTrue(selected.isPresent(), "A visible part of the roof face must not require climbing onto the roof");
	}

	@Test
	void seeingTheUndersideDoesNotExposeTheRequestedSideFace() {
		BlockPos support = new BlockPos(0, 136, 4);
		BlockHitResult underside = roofHit(List.of(support),
			new Vec3(0.5D, 135.62D, 4.5D), new Vec3(0.5D, 136.5D, 5D), Direction.SOUTH);
		assertEquals(Direction.DOWN, underside.getDirection());
		assertFalse(BlockInteractionTaskExecutor.matchesSupportFace(underside, support, Direction.SOUTH));
		assertTrue(BlockInteractionTaskExecutor.matchesSupportFace(underside, support, Direction.DOWN));
	}

	private static BlockHitResult roofHit(List<BlockPos> roof, Vec3 eye, Vec3 point, Direction face) {
		Vec3 end = BlockInteractionTaskExecutor.supportRaycastEndpoint(point, face);
		return roof.stream()
			.map(pos -> AABB.clip(List.of(new AABB(0, 0, 0, 1, 1, 1)), eye, end, pos))
			.filter(java.util.Objects::nonNull)
			.min(java.util.Comparator.comparingDouble(hit -> eye.distanceToSqr(hit.getLocation())))
			.orElse(null);
	}

	@Test
	void directWaterPlacementRequiresAHorizontalCavity() {
		assertFalse(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(0));
		assertFalse(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(1));
		assertFalse(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(2));
		assertTrue(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(3));
		assertTrue(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(4));
	}

	@Test
	void directWaterPlacementCanReplaceSimpleFarmTerrainTargets() {
		assertTrue(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:air", true));
		assertTrue(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:short_grass", true));
		assertTrue(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:grass_block", false));
		assertTrue(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:dirt", false));
	}

	@Test
	void directWaterPlacementDoesNotReplaceArbitrarySolidTargets() {
		assertFalse(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:stone", false));
		assertFalse(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:oak_log", false));
		assertFalse(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:chest", false));
	}

	@Test
	void waterPlacementUsesOnlyClientInteractionInputsAndNeedsNoIntegratedServer() {
		assertTrue(BlockInteractionTaskExecutor.waterPlacementUsesNormalInteraction(
			"minecraft:water_bucket",
			"minecraft:air",
			true
		));
		assertFalse(BlockInteractionTaskExecutor.waterPlacementUsesNormalInteraction(
			"minecraft:bucket",
			"minecraft:air",
			true
		));
	}

	@Test
	void waterPlacementConfirmationRequiresWorldAndInventoryChanges() {
		assertEquals(
			BlockInteractionTaskExecutor.InteractionConfirmationOutcome.CONFIRMED,
			BlockInteractionTaskExecutor.waterPlacementConfirmationOutcome(true, 1, 0, 0, 1, 0)
		);
		assertEquals(
			BlockInteractionTaskExecutor.InteractionConfirmationOutcome.WAIT,
			BlockInteractionTaskExecutor.waterPlacementConfirmationOutcome(true, 1, 1, 0, 0, 20)
		);
		assertEquals(
			BlockInteractionTaskExecutor.InteractionConfirmationOutcome.WAIT,
			BlockInteractionTaskExecutor.waterPlacementConfirmationOutcome(false, 1, 0, 0, 1, 20)
		);
	}

	@Test
	void waterPlacementConfirmationFailsAfterTimeoutWithoutServerConfirmation() {
		assertEquals(
			BlockInteractionTaskExecutor.InteractionConfirmationOutcome.FAILED,
			BlockInteractionTaskExecutor.waterPlacementConfirmationOutcome(true, 1, 1, 0, 0, 21)
		);
		assertEquals(
			BlockInteractionTaskExecutor.InteractionConfirmationOutcome.FAILED,
			BlockInteractionTaskExecutor.waterPlacementConfirmationOutcome(false, 1, 0, 0, 1, 21)
		);
	}

	@Test
	void useBlockInteractionModeChoosesFluidItemUseForFluidTargets() {
		assertEquals(
			BlockInteractionTaskExecutor.UseBlockInteractionMode.FLUID_ITEM_USE,
			BlockInteractionTaskExecutor.useBlockInteractionMode(true, true)
		);
		assertEquals(
			BlockInteractionTaskExecutor.UseBlockInteractionMode.FLUID_ITEM_USE,
			BlockInteractionTaskExecutor.useBlockInteractionMode(true, false)
		);
	}

	@Test
	void useBlockInteractionModeChoosesSupportForNonFluidAirOrReplaceableTargets() {
		assertEquals(
			BlockInteractionTaskExecutor.UseBlockInteractionMode.SUPPORT_INTERACTION,
			BlockInteractionTaskExecutor.useBlockInteractionMode(false, true)
		);
	}

	@Test
	void useBlockInteractionModeChoosesBlockClickForSolidNonFluidTargets() {
		assertEquals(
			BlockInteractionTaskExecutor.UseBlockInteractionMode.BLOCK_INTERACTION,
			BlockInteractionTaskExecutor.useBlockInteractionMode(false, false)
		);
	}

	@Test
	void blockInteractionNavigationOutcomeWaitsWhilePathing() {
		assertEquals(
			BlockInteractionTaskExecutor.BlockInteractionNavigationOutcome.WAIT,
			BlockInteractionTaskExecutor.blockInteractionNavigationOutcome(Optional.empty(), 12)
		);
	}

	@Test
	void blockInteractionNavigationOutcomeFailsOnPathFailureOrTimeout() {
		assertEquals(
			BlockInteractionTaskExecutor.BlockInteractionNavigationOutcome.FAILED,
			BlockInteractionTaskExecutor.blockInteractionNavigationOutcome(Optional.of("CALC_FAILED"), 12)
		);
		assertEquals(
			BlockInteractionTaskExecutor.BlockInteractionNavigationOutcome.FAILED,
			BlockInteractionTaskExecutor.blockInteractionNavigationOutcome(Optional.of("cancelled"), 12)
		);
		assertEquals(
			BlockInteractionTaskExecutor.BlockInteractionNavigationOutcome.FAILED,
			BlockInteractionTaskExecutor.blockInteractionNavigationOutcome(Optional.empty(), 161)
		);
	}

	@Test
	void blockInteractionNavigationOutcomeDistinguishesReachedButStillOutOfRange() {
		assertEquals(
			BlockInteractionTaskExecutor.BlockInteractionNavigationOutcome.AT_GOAL_BUT_STILL_OUT_OF_RANGE,
			BlockInteractionTaskExecutor.blockInteractionNavigationOutcome(Optional.of("AT_GOAL"), 12)
		);
	}

	@Test
	void reachedNavigationEventsRetryInteractionWhenNavigationConfirmsGoal() {
		for (String event : List.of("AT_GOAL", "CANCELED", "cancelled")) {
			assertEquals(
				BlockInteractionTaskExecutor.BlockInteractionNavigationOutcome.RETRY_INTERACTION,
				BlockInteractionTaskExecutor.blockInteractionNavigationOutcome(Optional.of(event), 12, true)
			);
		}
	}

	@Test
	void reachedNavigationRequiresNavigationGoalConfirmation() {
		assertEquals(
			BlockInteractionTaskExecutor.BlockInteractionNavigationOutcome.FAILED,
			BlockInteractionTaskExecutor.blockInteractionNavigationOutcome(Optional.of("CANCELED"), 12, false)
		);
		assertEquals(
			BlockInteractionTaskExecutor.BlockInteractionNavigationOutcome.FAILED,
			BlockInteractionTaskExecutor.blockInteractionNavigationOutcome(Optional.of("CALC_FAILED"), 12, true)
		);
	}

	@Test
	void directInteractionApproachHandlesNearbyOutOfReachTargets() {
		assertTrue(BlockInteractionTaskExecutor.shouldUseDirectInteractionApproach(81.0D, false));
		assertTrue(BlockInteractionTaskExecutor.shouldUseDirectInteractionApproach(100.0D, false));
		assertTrue(BlockInteractionTaskExecutor.allowsDirectInteractionApproach(
			new BlockInteractionTaskExecutor.InteractionApproachReason(
				BlockInteractionTaskExecutor.InteractionApproachReason.Kind.OUT_OF_RANGE,
				"target_out_of_range supportPos=1,64,1"
			)
		));
		assertTrue(BlockInteractionTaskExecutor.allowsDirectInteractionApproach(
			new BlockInteractionTaskExecutor.InteractionApproachReason(
				BlockInteractionTaskExecutor.InteractionApproachReason.Kind.OUT_OF_RANGE,
				"fluid_target_out_of_view_reach"
			)
		));
	}

	@Test
	void directInteractionApproachDefersWhenFarOrStuck() {
		assertFalse(BlockInteractionTaskExecutor.shouldUseDirectInteractionApproach(100.1D, false));
		assertFalse(BlockInteractionTaskExecutor.shouldUseDirectInteractionApproach(16.0D, true));
	}

	@Test
	void directInteractionApproachDefersWhenMovementCannotCloseTheGap() {
		assertFalse(BlockInteractionTaskExecutor.shouldUseDirectInteractionApproach(16.0D, false, true, 0L));
		assertFalse(BlockInteractionTaskExecutor.shouldUseDirectInteractionApproach(16.0D, false, false, 81L));
		assertTrue(BlockInteractionTaskExecutor.shouldUseDirectInteractionApproach(16.0D, false, false, 80L));
	}

	@Test
	void directInteractionApproachDefersWhenTargetIsNotVisible() {
		assertFalse(BlockInteractionTaskExecutor.allowsDirectInteractionApproach(
			new BlockInteractionTaskExecutor.InteractionApproachReason(
				BlockInteractionTaskExecutor.InteractionApproachReason.Kind.TARGET_NOT_VISIBLE,
				"target_not_visible supportPos=1,64,1"
			)
		));
	}

	@Test
	void directInteractionApproachDefersWhenPlayerOverlapsPlacementTarget() {
		assertFalse(BlockInteractionTaskExecutor.allowsDirectInteractionApproach(
			new BlockInteractionTaskExecutor.InteractionApproachReason(
				BlockInteractionTaskExecutor.InteractionApproachReason.Kind.PLAYER_HITBOX_OVERLAPS_TARGET,
				"player_hitbox_overlaps_target supportPos=1,64,1"
			)
		));
	}

	@Test
	void directInteractionDecisionDoesNotDependOnHumanReadableReasonDetail() {
		assertEquals(
			BlockInteractionTaskExecutor.allowsDirectInteractionApproach(new BlockInteractionTaskExecutor.InteractionApproachReason(
				BlockInteractionTaskExecutor.InteractionApproachReason.Kind.TARGET_NOT_VISIBLE,
				"target_not_visible supportPos=1,64,1"
			)),
			BlockInteractionTaskExecutor.allowsDirectInteractionApproach(new BlockInteractionTaskExecutor.InteractionApproachReason(
				BlockInteractionTaskExecutor.InteractionApproachReason.Kind.TARGET_NOT_VISIBLE,
				"visibility changed after the camera moved"
			))
		);
		assertEquals(
			BlockInteractionTaskExecutor.allowsDirectInteractionApproach(new BlockInteractionTaskExecutor.InteractionApproachReason(
				BlockInteractionTaskExecutor.InteractionApproachReason.Kind.PLAYER_HITBOX_OVERLAPS_TARGET,
				"player_hitbox_overlaps_target supportPos=1,64,1"
			)),
			BlockInteractionTaskExecutor.allowsDirectInteractionApproach(new BlockInteractionTaskExecutor.InteractionApproachReason(
				BlockInteractionTaskExecutor.InteractionApproachReason.Kind.PLAYER_HITBOX_OVERLAPS_TARGET,
				"placement target overlaps the player body"
			))
		);
	}

	@Test
	void cancelledNearbyNavigationFallsBackToDirectApproach() {
		assertTrue(BlockInteractionTaskExecutor.shouldFallbackToDirectApproachAfterNavigationFailure(Optional.of("CANCELED"), 81.0D, false));
		assertTrue(BlockInteractionTaskExecutor.shouldFallbackToDirectApproachAfterNavigationFailure(Optional.of("cancelled"), 100.0D, false));
	}

	@Test
	void directApproachFallbackDefersForFarStuckOrNonCancelNavigationFailures() {
		assertFalse(BlockInteractionTaskExecutor.shouldFallbackToDirectApproachAfterNavigationFailure(Optional.of("CANCELED"), 100.1D, false));
		assertFalse(BlockInteractionTaskExecutor.shouldFallbackToDirectApproachAfterNavigationFailure(Optional.of("CANCELED"), 16.0D, true));
		assertFalse(BlockInteractionTaskExecutor.shouldFallbackToDirectApproachAfterNavigationFailure(Optional.of("CALC_FAILED"), 16.0D, false));
		assertFalse(BlockInteractionTaskExecutor.shouldFallbackToDirectApproachAfterNavigationFailure(Optional.empty(), 16.0D, false));
	}

	@Test
	void interactionStandCandidatesPreferPositionsBesideTargetAndSupport() {
		assertEquals(
			List.of(
				new BlockPos(10, 65, 9),
				new BlockPos(10, 66, 9),
				new BlockPos(10, 65, 11),
				new BlockPos(10, 66, 11),
				new BlockPos(9, 65, 10),
				new BlockPos(9, 66, 10),
				new BlockPos(11, 65, 10),
				new BlockPos(11, 66, 10),
				new BlockPos(10, 64, 9),
				new BlockPos(10, 65, 9),
				new BlockPos(10, 64, 11),
				new BlockPos(10, 65, 11),
				new BlockPos(9, 64, 10),
				new BlockPos(9, 65, 10),
				new BlockPos(11, 64, 10),
				new BlockPos(11, 65, 10)
			),
			BlockInteractionTaskExecutor.interactionStandCandidates(
				new BlockPos(10, 65, 10),
				new BlockPos(10, 64, 10)
			)
		);
	}

	@Test
	void placementStandCandidatesIncludeAdjacentCellsWithoutTargetOverlap() {
		BlockPos target = new BlockPos(10, 65, 10);
		List<BlockPos> candidates = BlockInteractionTaskExecutor.placementStandCandidates(
			target,
			new BlockPos(10, 64, 10)
		);

		assertTrue(candidates.contains(new BlockPos(10, 65, 8)));
		assertTrue(candidates.contains(new BlockPos(12, 65, 10)));
		assertTrue(candidates.contains(target.north()));
		assertTrue(candidates.contains(target.east()));
		assertFalse(candidates.contains(target));
	}

	@Test
	void caveLedgeAdjacentStanceStillRequiresStandabilityReachAndVisibility() {
		var target = new BlockPos(-226, 48, -1);
		var stand = target.south();
		var current = new BlockPos(-228, 46, -1);
		assertEquals(List.of(stand), BlockInteractionTaskExecutor.viablePlacementStandCandidates(
			target, target.below(), current, Set.of(), stand::equals, p -> true, p -> true));
		assertTrue(BlockInteractionTaskExecutor.viablePlacementStandCandidates(
			target, target.below(), current, Set.of(), p -> false, p -> true, p -> true).isEmpty());
		assertTrue(BlockInteractionTaskExecutor.viablePlacementStandCandidates(
			target, target.below(), current, Set.of(), stand::equals, p -> false, p -> true).isEmpty());
		assertTrue(BlockInteractionTaskExecutor.viablePlacementStandCandidates(
			target, target.below(), current, Set.of(), stand::equals, p -> true, p -> false).isEmpty());
	}

	@Test
	void overheadPlacementPrefersReachableGroundStanceWithSupportFaceLineOfSight() {
		BlockPos target = new BlockPos(35, 68, 155);
		BlockPos support = target.below();
		BlockPos current = new BlockPos(35, 66, 152);
		BlockPos groundStance = new BlockPos(35, 66, 154);
		BlockPos roofStance = new BlockPos(35, 69, 153);

		List<BlockPos> candidates = BlockInteractionTaskExecutor.viablePlacementStandCandidates(
			target,
			support,
			current,
			Set.of(),
			candidate -> candidate.equals(groundStance) || candidate.equals(roofStance),
			candidate -> true,
			candidate -> true
		);

		assertEquals(List.of(groundStance, roofStance), candidates);
	}

	@Test
	void overheadPlacementHasNoValidStanceWithoutSupportFaceLineOfSight() {
		BlockPos target = new BlockPos(35, 68, 155);
		BlockPos support = target.below();
		BlockPos current = new BlockPos(35, 66, 152);
		BlockPos groundStance = new BlockPos(35, 66, 154);
		BlockPos roofStance = new BlockPos(35, 69, 153);

		assertTrue(BlockInteractionTaskExecutor.viablePlacementStandCandidates(
			target,
			support,
			current,
			Set.of(),
			candidate -> candidate.equals(groundStance),
			candidate -> true,
			candidate -> false
		).isEmpty());

		assertTrue(BlockInteractionTaskExecutor.viablePlacementStandCandidates(
			target,
			support,
			current,
			Set.of(groundStance, roofStance),
			candidate -> candidate.equals(groundStance) || candidate.equals(roofStance),
			candidate -> true,
			candidate -> true
		).isEmpty());
	}

	@Test
	void placementTargetDetectsOnlyActualPlayerHitboxOverlap() {
		BlockPos target = new BlockPos(77, 68, -79);
		assertTrue(BlockInteractionTaskExecutor.playerIntersectsPlacementTarget(
			new AABB(77.7D, 68.0D, -78.8D, 78.3D, 69.8D, -78.2D),
			target
		));
		assertFalse(BlockInteractionTaskExecutor.playerIntersectsPlacementTarget(
			new AABB(78.0D, 68.0D, -78.8D, 78.6D, 69.8D, -78.2D),
			target
		));
	}

	@Test
	void supportRaycastEndpointsMoveInsideEveryClickedFace() {
		Vec3 surface = new Vec3(1.5D, 2.5D, 3.5D);
		assertEquals(new Vec3(1.5D, 2.49D, 3.5D), BlockInteractionTaskExecutor.supportRaycastEndpoint(surface, Direction.UP));
		assertEquals(new Vec3(1.5D, 2.51D, 3.5D), BlockInteractionTaskExecutor.supportRaycastEndpoint(surface, Direction.DOWN));
		assertEquals(new Vec3(1.5D, 2.5D, 3.51D), BlockInteractionTaskExecutor.supportRaycastEndpoint(surface, Direction.NORTH));
		assertEquals(new Vec3(1.5D, 2.5D, 3.49D), BlockInteractionTaskExecutor.supportRaycastEndpoint(surface, Direction.SOUTH));
		assertEquals(new Vec3(1.51D, 2.5D, 3.5D), BlockInteractionTaskExecutor.supportRaycastEndpoint(surface, Direction.WEST));
		assertEquals(new Vec3(1.49D, 2.5D, 3.5D), BlockInteractionTaskExecutor.supportRaycastEndpoint(surface, Direction.EAST));
	}

	@Test
	void interactionRayEndUsesThePlayersEyeAndVanillaReach() {
		assertEquals(
			new Vec3(10.0D, 65.62D, -5.5D),
			BlockInteractionTaskExecutor.interactionRayEnd(
				new Vec3(10.0D, 65.62D, -10.0D),
				new Vec3(0.0D, 0.0D, 1.0D),
				4.5D
			)
		);
	}

	@Test
	void interactionBusyDispositionClosesEmptyOpenContainer() {
		assertEquals(
			BlockInteractionTaskExecutor.InteractionBusyDisposition.CLOSE_OPEN_SCREEN,
			BlockInteractionTaskExecutor.interactionBusyDisposition(true, true)
		);
	}

	@Test
	void interactionBusyDispositionFailsWhenCursorCarriesItem() {
		assertEquals(
			BlockInteractionTaskExecutor.InteractionBusyDisposition.FAIL,
			BlockInteractionTaskExecutor.interactionBusyDisposition(true, false)
		);
		assertEquals(
			BlockInteractionTaskExecutor.InteractionBusyDisposition.FAIL,
			BlockInteractionTaskExecutor.interactionBusyDisposition(false, false)
		);
	}

	@Test
	void interactionBusyDispositionAllowsNormalPlayerInventory() {
		assertEquals(
			BlockInteractionTaskExecutor.InteractionBusyDisposition.READY,
			BlockInteractionTaskExecutor.interactionBusyDisposition(false, true)
		);
	}

	@Test
	void supportRaycastOnlyRequiredForPlacementStyleInteractions() {
		assertFalse(BlockInteractionTaskExecutor.requiresSupportRaycast(false, true));
		assertTrue(BlockInteractionTaskExecutor.requiresSupportRaycast(true, false));
		assertTrue(BlockInteractionTaskExecutor.requiresSupportRaycast(false, false));
	}

	@Test
	void cropPlantingItemsDoNotRequireSupportRaycastAfterReachableNavigation() {
		assertTrue(BlockInteractionTaskExecutor.isCropPlantingItemId("minecraft:wheat_seeds"));
		assertTrue(BlockInteractionTaskExecutor.isCropPlantingItemId("minecraft:carrot"));
		assertFalse(BlockInteractionTaskExecutor.isCropPlantingItemId("minecraft:oak_planks"));
	}

	@Test
	void placementConfirmationRequiresTargetToBecomeSolid() {
		assertFalse(BlockInteractionTaskExecutor.placementConfirmed(false));
		assertTrue(BlockInteractionTaskExecutor.placementConfirmed(true));
	}

	@Test
	void batchedRequestPausesWhenSessionGateBlocksActuation() {
		BlockInteractionTaskExecutor executor = new BlockInteractionTaskExecutor(() -> null);

		Optional<TaskTerminalEvent> event = executor.tick(
			new SessionSnapshot(SessionMode.SINGLEPLAYER_LOCAL, true, true, "minecraft:overworld", false, 0, 1L),
			Optional.of(request())
		);

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.PAUSED_BY_SESSION_GATE, executor.snapshot().state());
		assertEquals("session_gate", executor.snapshot().lastPathEvent());
	}

	@Test
	void batchedRequestFailsWhenWorldUnavailable() {
		BlockInteractionTaskExecutor executor = new BlockInteractionTaskExecutor(() -> null);

		Optional<TaskTerminalEvent> event = executor.tick(
			new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, 1L),
			Optional.of(request())
		);

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.FAILED, event.orElseThrow().terminalState());
		assertEquals("world_unavailable", event.orElseThrow().message());
	}

	private static WorldTaskRequest request() {
		return WorldTaskRequest.useBlock(
			"task-1",
			"job-1",
			new BlockUseStepArgs(
				"minecraft:wheat_seeds",
				List.of(
					new BlockUseStepArgs.Target(new GoalPosition(1, 65, 2, true), "down", List.of("minecraft:farmland"), "air"),
					new BlockUseStepArgs.Target(new GoalPosition(2, 65, 2, true), "down", List.of("minecraft:farmland"), "air")
				)
			)
		);
	}

}
