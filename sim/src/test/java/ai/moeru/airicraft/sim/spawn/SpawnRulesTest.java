package ai.moeru.airicraft.sim.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpawnRulesTest {
	private static final List<double[]> ONE_PLAYER = List.of(new double[] {0, 64, 0});

	@Test
	void rejectsSpawnOnPlayerFace() {
		SpawnRules.Result r = SpawnRules.checkMinDistance(1.0, 64, 0, ONE_PLAYER, 5.0);
		assertFalse(r.ok());
		assertEquals(1.0, r.nearestDistance(), 1e-6);
	}

	@Test
	void acceptsSpawnBeyondMinDistance() {
		SpawnRules.Result r = SpawnRules.checkMinDistance(10.0, 64, 0, ONE_PLAYER, 5.0);
		assertTrue(r.ok());
		assertEquals(10.0, r.nearestDistance(), 1e-6);
	}

	@Test
	void boundaryDistanceRejectedJustBelow() {
		assertFalse(SpawnRules.checkMinDistance(4.999, 64, 0, ONE_PLAYER, 5.0).ok());
	}

	@Test
	void boundaryDistanceAcceptedAtExactly() {
		assertTrue(SpawnRules.checkMinDistance(5.0, 64, 0, ONE_PLAYER, 5.0).ok());
	}

	@Test
	void nearestPlayerGovernsWhenMultiple() {
		List<double[]> players = List.of(
				new double[] {0, 64, 0},
				new double[] {100, 64, 0});
		// 3m from player 1 though far from player 2 — must still reject.
		assertFalse(SpawnRules.checkMinDistance(3, 64, 0, players, 5.0).ok());
	}

	@Test
	void emptyPlayerListAlwaysOk() {
		assertTrue(SpawnRules.checkMinDistance(0, 0, 0, List.of(), 5.0).ok());
	}

	@Test
	void minDistanceClampedToAbsoluteFloor() {
		assertEquals(SpawnRules.ABSOLUTE_MIN_DISTANCE, SpawnRules.effectiveMinDistance(0.5));
		assertEquals(8.0, SpawnRules.effectiveMinDistance(8.0));
		assertEquals(SpawnRules.DEFAULT_MIN_DISTANCE, SpawnRules.effectiveMinDistance(null));
	}
}
