package ai.moeru.airicraft.agent.tasks;

import net.minecraft.world.entity.Pose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementSneakControllerTest {
	@Test
	void pressesSneakAndWaitsBeforePlacementWhenPlayerIsStanding() {
		assertEquals(
			PlacementSneakController.Preparation.PRESS_AND_WAIT,
			PlacementSneakController.preparation(false, false, false, false)
		);
	}

	@Test
	void waitsForSneakInputToReachPlayerBeforePlacement() {
		assertEquals(
			PlacementSneakController.Preparation.WAITING,
			PlacementSneakController.preparation(true, true, false, false)
		);
		assertEquals(
			PlacementSneakController.Preparation.WAITING,
			PlacementSneakController.preparation(false, true, false, false)
		);
	}

	@Test
	void restoresOwnedSneakInputClearedDuringNavigationRelease() {
		assertEquals(
			PlacementSneakController.Preparation.PRESS_AND_WAIT,
			PlacementSneakController.preparation(true, false, false, false)
		);
		// The player flag can still reflect the preceding tick after the key was cleared.
		assertEquals(
			PlacementSneakController.Preparation.PRESS_AND_WAIT,
			PlacementSneakController.preparation(true, false, true, true)
		);
	}

	@Test
	void placementIsReadyOnlyAfterPlayerIsSneaking() {
		assertEquals(
			PlacementSneakController.Preparation.READY,
			PlacementSneakController.preparation(true, true, true, true)
		);
		assertEquals(
			PlacementSneakController.Preparation.READY,
			PlacementSneakController.preparation(false, true, true, true)
		);
	}
	@Test
	void shiftFlagAloneDoesNotMeanTheCrouchedEyePositionIsReady() {
		assertEquals(
			PlacementSneakController.Preparation.WAITING,
			PlacementSneakController.preparation(true, true, true, false)
		);
	}

	@Test
	void crouchedPoseWithoutActiveSneakDoesNotMeanReady() {
		assertEquals(
			PlacementSneakController.Preparation.WAITING,
			PlacementSneakController.preparation(true, true, false, true)
		);
		assertEquals(
			PlacementSneakController.Preparation.PRESS_AND_WAIT,
			PlacementSneakController.preparation(false, false, true, true)
		);
	}

	@Test
	void ordinaryStandingPoseMustWaitForTheCrouchedEyeHeight() {
		assertFalse(PlacementSneakController.poseReady(Pose.STANDING, true, true));
		assertTrue(PlacementSneakController.poseReady(Pose.CROUCHING, true, true));
	}

	@Test
	void flyingAndSwimmingPreserveReadinessInTheirNonCrouchingMode() {
		for (Pose pose : new Pose[] {Pose.STANDING, Pose.SWIMMING, Pose.FALL_FLYING, Pose.SPIN_ATTACK, Pose.SLEEPING}) {
			boolean ready = PlacementSneakController.poseReady(pose, false, true);
			assertTrue(ready, pose.name());
			assertEquals(PlacementSneakController.Preparation.READY,
				PlacementSneakController.preparation(true, true, true, ready));
		}
	}

	@Test
	void forcedCrawlCanPlaceWhenCrouchingDoesNotFit() {
		assertTrue(PlacementSneakController.poseReady(Pose.SWIMMING, true, false));
		assertFalse(PlacementSneakController.poseReady(Pose.STANDING, true, false));
		assertFalse(PlacementSneakController.poseReady(Pose.SWIMMING, true, true));
	}

}
