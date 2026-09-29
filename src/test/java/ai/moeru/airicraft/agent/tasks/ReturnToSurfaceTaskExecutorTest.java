package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReturnToSurfaceTaskExecutorTest {
	@Test
	void exactNavigationTerminalUndergroundWithoutToweringFailsInsteadOfCompleting() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.SurfaceTargetOutcome.FAIL,
			ReturnToSurfaceTaskExecutor.surfaceTargetOutcome(false, false, "nearest_surface", false)
		);
	}

	@Test
	void proximityTwoBlocksBelowRememberedSurfaceRefinesToExactNavigation() {
		GoalPosition rememberedSurface = new GoalPosition(36, 66, 156, false);

		assertTrue(ReturnToSurfaceTaskExecutor.reachedTarget(new BlockPos(37, 64, 156), rememberedSurface));
		assertEquals(
			ReturnToSurfaceTaskExecutor.SurfaceTargetOutcome.NAVIGATE_EXACT,
			ReturnToSurfaceTaskExecutor.surfaceTargetOutcome(false, true, "nearest_surface", true)
		);
	}

	@Test
	void exactNavigationTerminalAtRememberedSurfaceFailsInsteadOfToweringPastTarget() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.SurfaceTargetOutcome.FAIL,
			ReturnToSurfaceTaskExecutor.surfaceTargetOutcome(false, true, "nearest_surface", false)
		);
		assertEquals(
			ReturnToSurfaceTaskExecutor.SurfaceTargetOutcome.FAIL,
			ReturnToSurfaceTaskExecutor.surfaceTargetOutcome(false, true, "last_surface", false)
		);
	}

	@Test
	void targetReachedLastGroundWithToweringFallsBackToTowering() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.SurfaceTargetOutcome.TOWER,
			ReturnToSurfaceTaskExecutor.surfaceTargetOutcome(false, true, "last_ground", true)
		);
	}

	@Test
	void targetReachedOnSafeSurfaceCompletes() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.SurfaceTargetOutcome.COMPLETE,
			ReturnToSurfaceTaskExecutor.surfaceTargetOutcome(true, false, "nearest_surface", true)
		);
	}

	@Test
	void toweringStopsAtRememberedSurfaceElevation() {
		ReturnToSurfaceStepArgs nearestSurface = new ReturnToSurfaceStepArgs(
			new GoalPosition(1, 64, 1, false),
			"nearest_surface",
			true,
			ReturnToSurfaceStepArgs.DEFAULT_FILLER_BLOCK_IDS
		);
		ReturnToSurfaceStepArgs lastGround = new ReturnToSurfaceStepArgs(
			new GoalPosition(1, 64, 1, false),
			"last_ground",
			true,
			ReturnToSurfaceStepArgs.DEFAULT_FILLER_BLOCK_IDS
		);

		assertFalse(ReturnToSurfaceTaskExecutor.shouldStopToweringAtSurfaceTargetElevation(nearestSurface, 63));
		assertTrue(ReturnToSurfaceTaskExecutor.shouldStopToweringAtSurfaceTargetElevation(nearestSurface, 64));
		assertTrue(ReturnToSurfaceTaskExecutor.shouldStopToweringAtSurfaceTargetElevation(nearestSurface, 90));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldStopToweringAtSurfaceTargetElevation(lastGround, 90));
	}

	@Test
	void underwaterWithoutUsefulHorizontalTargetAscends() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.ASCENDING,
			ReturnToSurfaceTaskExecutor.recoveryMovement(true, false, 0.0D, false)
		);
		assertEquals(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.ASCENDING,
			ReturnToSurfaceTaskExecutor.recoveryMovement(true, true, 4.0D, false)
		);
	}

	@Test
	void underwaterWithUsefulHorizontalTargetMovesTowardTarget() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.TOWARD_TARGET,
			ReturnToSurfaceTaskExecutor.recoveryMovement(true, true, 4.01D, false)
		);
	}

	@Test
	void breathableButNotSurfaceDoesNotCompleteRecovery() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.BREATHABLE,
			ReturnToSurfaceTaskExecutor.recoveryMovement(false, true, 100.0D, false)
		);
	}

	@Test
	void breathableRecoveryRequiresStableWindowBeforeExiting() {
		assertFalse(ReturnToSurfaceTaskExecutor.shouldExitUnderwaterRecovery(false, 99));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldExitUnderwaterRecovery(true, 11));
		assertTrue(ReturnToSurfaceTaskExecutor.shouldExitUnderwaterRecovery(true, 12));
	}

	@Test
	void breathableWaterContactDoesNotReenterRecovery() {
		assertTrue(ReturnToSurfaceTaskExecutor.shouldEnterUnderwaterRecovery(true, false, false));
		assertTrue(ReturnToSurfaceTaskExecutor.shouldEnterUnderwaterRecovery(false, true, false));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldEnterUnderwaterRecovery(true, false, true));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldEnterUnderwaterRecovery(false, false, false));
	}

	@Test
	void underwaterStuckReportsStuckRecoveryState() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.STUCK,
			ReturnToSurfaceTaskExecutor.recoveryMovement(true, true, 100.0D, true)
		);
	}

	@Test
	void underwaterStuckRecoveryAlternatesEscapeInputsWithoutFailing() {
		ReturnToSurfaceTaskExecutor.UnderwaterRecoveryKeys first = ReturnToSurfaceTaskExecutor.underwaterRecoveryKeys(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.STUCK,
			0
		);
		ReturnToSurfaceTaskExecutor.UnderwaterRecoveryKeys second = ReturnToSurfaceTaskExecutor.underwaterRecoveryKeys(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.STUCK,
			20
		);
		ReturnToSurfaceTaskExecutor.UnderwaterRecoveryKeys third = ReturnToSurfaceTaskExecutor.underwaterRecoveryKeys(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.STUCK,
			40
		);
		ReturnToSurfaceTaskExecutor.UnderwaterRecoveryKeys fourth = ReturnToSurfaceTaskExecutor.underwaterRecoveryKeys(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.STUCK,
			60
		);

		assertTrue(first.forward());
		assertTrue(first.left());
		assertTrue(second.forward());
		assertTrue(second.right());
		assertTrue(third.back());
		assertTrue(third.left());
		assertTrue(fourth.back());
		assertTrue(fourth.right());
	}

	@Test
	void normalUnderwaterRecoveryKeysStayFocusedOnAscendingOrTarget() {
		ReturnToSurfaceTaskExecutor.UnderwaterRecoveryKeys ascending = ReturnToSurfaceTaskExecutor.underwaterRecoveryKeys(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.ASCENDING,
			100
		);
		ReturnToSurfaceTaskExecutor.UnderwaterRecoveryKeys towardTarget = ReturnToSurfaceTaskExecutor.underwaterRecoveryKeys(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.TOWARD_TARGET,
			100
		);

		assertFalse(ascending.forward());
		assertFalse(ascending.left());
		assertFalse(ascending.right());
		assertFalse(ascending.back());
		assertTrue(towardTarget.forward());
		assertTrue(towardTarget.sprint());
		assertFalse(towardTarget.left());
		assertFalse(towardTarget.right());
		assertFalse(towardTarget.back());
	}

	@Test
	void towerHeadroomClearsOnlySolidDryObstructions() {
		assertTrue(ReturnToSurfaceTaskExecutor.shouldClearTowerHeadroom(true, false, false));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldClearTowerHeadroom(false, false, false));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldClearTowerHeadroom(true, true, false));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldClearTowerHeadroom(true, false, true));
	}


	@Test
	void toweringTerminatesAfterAStableSupportFailureWindow() {
		assertFalse(ReturnToSurfaceTaskExecutor.towerSupportUnavailableTimedOut(99));
		assertTrue(ReturnToSurfaceTaskExecutor.towerSupportUnavailableTimedOut(100));
	}
}
