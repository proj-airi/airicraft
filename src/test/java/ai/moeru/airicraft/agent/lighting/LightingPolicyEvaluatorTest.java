package ai.moeru.airicraft.agent.lighting;

import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import ai.moeru.airicraft.agent.tasks.WorldTaskType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightingPolicyEvaluatorTest {
	@Test
	void skyAccessAtAnySampleIncludingAnEdgeVetoesBothLightingTriggers() {
		BlockPos origin = new BlockPos(10, 64, -8);
		for (BlockPos openCell : BlockPos.betweenClosed(origin.offset(-2, 0, -2), origin.offset(2, 0, 2))) {
			boolean skyAccess = LightingRuntime.hasFootLevelSkyAccess(origin, openCell::equals);
			assertTrue(skyAccess, "Every cell in the 5x5 foot-level area must be checked: " + openCell);
			for (LightingPolicy.Mode mode : LightingPolicy.Mode.values()) {
				LightingPolicy policy = new LightingPolicy(true, mode, 4, false, 6, 1);
				assertTrue(policy.requireUnderground(), "Legacy configuration cannot bypass sky exclusion");
				assertFalse(LightingPolicyEvaluator.shouldPlace(policy,
					LightingPolicyEvaluator.supportsActivity(WorldTaskType.MINE, false), true, skyAccess, 0, 0, false));
				assertFalse(LightingPolicyEvaluator.shouldPlace(policy,
					LightingPolicyEvaluator.supportsActivity(null, true), true, skyAccess, 0, 0, false));
			}
		}
	}

	@Test
	void fullyCoveredAreaRemainsEligibleAndSkyOutsideTheSampleDoesNotVetoIt() {
		BlockPos origin = new BlockPos(10, 64, -8);
		var sampled = new java.util.HashSet<BlockPos>();
		assertFalse(LightingRuntime.hasFootLevelSkyAccess(origin, pos -> {
			sampled.add(pos.immutable());
			return pos.getY() != origin.getY() || Math.abs(pos.getX() - origin.getX()) > 2
				|| Math.abs(pos.getZ() - origin.getZ()) > 2;
		}));
		assertEquals(25, sampled.size());
		assertTrue(LightingPolicyEvaluator.shouldPlace(LightingPolicy.defaults(), true, true, false, 0, 0, false));
	}

	@Test
	void averagesExactlyTheTwentyFiveFootLevelCellsWithoutRounding() {
		BlockPos origin = new BlockPos(10, 64, -8);
		var sampled = new java.util.HashSet<BlockPos>();
		double average = LightingRuntime.averageFootLevelLight(origin, pos -> true, pos -> {
			assertEquals(64, pos.getY());
			assertTrue(Math.abs(pos.getX() - origin.getX()) <= 2);
			assertTrue(Math.abs(pos.getZ() - origin.getZ()) <= 2);
			sampled.add(pos.immutable());
			return pos.equals(origin) ? 3 : 4;
		});
		assertEquals(25, sampled.size());
		assertEquals(3.96, average, 0.00001);
		assertTrue(LightingPolicyEvaluator.shouldPlace(LightingPolicy.defaults(), true, true, false, average, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(LightingPolicy.defaults(), true, true, false, 4, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(LightingPolicy.defaults(), true, true, false, 4.04, 0, false));
	}

	@Test
	void aDarkCenterDoesNotTriggerWhenTheAreaAverageIsBright() {
		BlockPos origin = BlockPos.ZERO;
		double average = LightingRuntime.averageFootLevelLight(origin, pos -> true, pos -> pos.equals(origin) ? 0 : 5);
		assertFalse(LightingPolicyEvaluator.shouldPlace(LightingPolicy.defaults(), true, true, false, average, 0, false));
	}

	@Test
	void idlePlacementRequiresFiveContinuousSecondsAndRestartsAfterMovement() {
		var runtime = new LightingRuntime();
		var position = new net.minecraft.world.phys.Vec3(0.5, 64, 0.5);
		for (long tick = 0; tick < 100; tick++) assertFalse(runtime.observeStationary(position, true, tick));
		assertTrue(runtime.observeStationary(position, true, 100));
		assertTrue(LightingPolicyEvaluator.supportsActivity(null, true));
		assertFalse(LightingPolicyEvaluator.supportsActivity(null, false));
		position = position.add(0.25, 0, 0); // Movement within the same block also resets the timer.
		for (long tick = 101; tick < 201; tick++) assertFalse(runtime.observeStationary(position, true, tick));
		assertTrue(runtime.observeStationary(position, true, 201));
		assertFalse(runtime.observeStationary(position, false, 202));
		assertFalse(runtime.observeStationary(position, true, 203));
		assertFalse(runtime.observeStationary(position, true, 1000), "Time under another actuator owner cannot count as standing still");
		runtime.reset();
		assertFalse(runtime.observeStationary(position, true, 1001));
	}

	@Test
	void standingStillDoesNotTakeControlFromOtherInteractions() {
		for (WorldTaskType activity : WorldTaskType.values()) {
			assertEquals(activity == WorldTaskType.MINE || activity == WorldTaskType.NAVIGATE,
				LightingPolicyEvaluator.supportsActivity(activity, true), activity.name());
		}
	}

	@Test
	void freshAndResetRuntimeAutomaticallyLightsDarkUndergroundWork() {
		var runtime = new LightingRuntime();
		assertTrue(LightingPolicyEvaluator.shouldPlace(runtime.policy(), true, true, false, 0, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(runtime.policy(), true, true, true, 15, 0, false));
		runtime.configure(false, LightingPolicy.Mode.DARKNESS, 0, true, 6);
		assertFalse(runtime.policy().enabled());
		runtime.reset();
		assertTrue(runtime.policy().enabled());
	}

	@Test
	void darknessPolicyRequiresSupportedActivityTorchAndUndergroundWhenConfigured() {
		LightingPolicy policy = new LightingPolicy(true, LightingPolicy.Mode.DARKNESS, 2, true, 6, 1L);

		assertTrue(LightingPolicyEvaluator.shouldPlace(policy, true, true, false, 1.96, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, false, true, false, 0, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, false, false, 0, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, true, 0, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, false, 3, 0, false));
	}

	@Test
	void lightingAccompaniesTravelAndMiningButNotOtherInteractionOwners() {
		for (WorldTaskType activity : WorldTaskType.values()) {
			assertEquals(activity == WorldTaskType.MINE || activity == WorldTaskType.NAVIGATE,
				LightingPolicyEvaluator.supportsActivity(activity, false), activity.name());
		}
		assertFalse(LightingPolicyEvaluator.supportsActivity(null, false));
	}

	@Test
	void spawnProofPolicyUsesBlockLightAndHonorsSpacing() {
		LightingPolicy policy = new LightingPolicy(true, LightingPolicy.Mode.SPAWN_PROOF, 7, false, 6, 1L);

		assertTrue(LightingPolicyEvaluator.shouldPlace(policy, true, true, false, 15, 6.96, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, false, 0, 8, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, false, 0, 0, true));
	}

	@Test
	void placementCandidatesPreferWallsOnTheLeftThenRightBeforeFloorFallbacks() {
		var candidates = LightingRuntime.placementCandidates(new BlockPos(10, 20, 30), Direction.NORTH);

		assertEquals(10, candidates.size());
		assertEquals(new BlockPos(9, 21, 31), candidates.get(0).support());
		assertEquals(Direction.EAST, candidates.get(0).face());
		assertEquals("left", candidates.get(0).side());
		assertEquals(LightingRuntime.PlacementSurface.WALL, candidates.get(0).surface());
		assertEquals(new BlockPos(11, 21, 31), candidates.get(3).support());
		assertEquals(Direction.WEST, candidates.get(3).face());
		assertEquals("right", candidates.get(3).side());
		assertEquals(LightingRuntime.PlacementSurface.WALL, candidates.get(3).surface());
		assertEquals(new BlockPos(10, 19, 31), candidates.get(6).support());
		assertEquals(Direction.UP, candidates.get(6).face());
		assertEquals("floor", candidates.get(6).side());
		assertEquals(LightingRuntime.PlacementSurface.FLOOR, candidates.get(6).surface());
	}
}
