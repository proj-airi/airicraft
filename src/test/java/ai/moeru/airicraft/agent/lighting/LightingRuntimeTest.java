package ai.moeru.airicraft.agent.lighting;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightingRuntimeTest {
	@Test
	void surroundingStoneDoesNotDimTheAirAverageOrHaveItsLightQueried() {
		BlockPos origin = new BlockPos(10, 64, -8);
		var air = java.util.Set.of(origin, origin.north());
		var sampled = new java.util.HashSet<BlockPos>();
		double average = LightingRuntime.averageFootLevelLight(origin, air::contains, pos -> {
			assertTrue(air.contains(pos), "Occupied cells must never be queried for light: " + pos);
			sampled.add(pos.immutable());
			return pos.equals(origin) ? 5 : 6;
		});
		assertEquals(air, sampled);
		assertEquals(5.5, average);
		for (LightingPolicy.Mode mode : LightingPolicy.Mode.values()) {
			LightingPolicy policy = new LightingPolicy(true, mode, 4, true, 6, 1);
			assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, false, average, average, false));
		}
	}

	@Test
	void noAirSamplesDoNotTriggerTorchPlacement() {
		double average = LightingRuntime.averageFootLevelLight(BlockPos.ZERO, pos -> false, pos -> {
			throw new AssertionError("An occupied area has no light samples");
		});
		for (LightingPolicy.Mode mode : LightingPolicy.Mode.values()) {
			LightingPolicy policy = new LightingPolicy(true, mode, 15, true, 6, 1);
			assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, false, average, average, false));
		}
	}

	@Test
	void aSingleDarkAirCellStillTriggersPlacement() {
		BlockPos origin = new BlockPos(10, 64, -8);
		double average = LightingRuntime.averageFootLevelLight(origin, origin::equals, pos -> 0);
		assertEquals(0.0, average);
		for (LightingPolicy.Mode mode : LightingPolicy.Mode.values()) {
			LightingPolicy policy = new LightingPolicy(true, mode, 4, true, 6, 1);
			assertTrue(LightingPolicyEvaluator.shouldPlace(policy, true, true, false, average, average, false));
		}
	}
}
