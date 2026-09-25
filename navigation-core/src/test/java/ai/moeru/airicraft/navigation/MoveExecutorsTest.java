package ai.moeru.airicraft.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveExecutorsTest {
	private static final MovementPolicy POLICY = MovementPolicy.defaults().noEdits();

	@Test
	void sneaksWhenMomentumWouldCarryTheBodyOffACliff() {
		MapTerrain terrain = ledge();
		Step north = new Moves(terrain, POLICY).stepBetween(new GridPos(10, 11, 6), new GridPos(10, 11, 5));

		MotorIntent sliding = walk(terrain, north, new BodyState(10.8, 11, 6.5, 0, true, false, false, false, 2), 0.28);
		MotorIntent resting = walk(terrain, north, new BodyState(10.5, 11, 6.5, 0, true, false, false, false, 2), 0);

		assertTrue(sliding.sneak(), "sliding east toward the drop");
		assertFalse(sliding.sprint());
		assertFalse(resting.sneak(), "no momentum, no guard");
	}

	@Test
	void ignoresDropsTheStepItselfTakes() {
		MapTerrain terrain = ledge();
		// Walking east onto the ledge's last cell: the next column east is a drop the path never uses,
		// but the body is not moving fast enough to reach it.
		Step east = new Moves(terrain, POLICY).stepBetween(new GridPos(8, 11, 6), new GridPos(9, 11, 6));
		MotorIntent intent = walk(terrain, east, new BodyState(8.5, 11, 6.5, 0, true, false, false, false, 2), 0.2);
		assertFalse(intent.sneak());
	}

	private static MotorIntent walk(TerrainView terrain, Step step, BodyState body, double velocityX) {
		StepContext context = new StepContext(step, null);
		context.body = body;
		context.velocityX = velocityX;
		context.live = new Moves(terrain, POLICY);
		context.policy = POLICY;
		return MoveExecutors.WALK.tick(context).intent();
	}

	/** A one-wide stone ledge along z=6 up to x=10, then north along x=10; everything else drops to y=0. */
	private static MapTerrain ledge() {
		MapTerrain terrain = new MapTerrain(CellInfo.AIR, new Box(-1, 0, -1, 15, 16, 9));
		for (int x = -1; x <= 15; x++) for (int z = -1; z <= 9; z++) terrain.set(x, 0, z, AsciiTerrain.STONE);
		for (int y = 1; y <= 10; y++) {
			for (int x = 0; x <= 10; x++) terrain.set(x, y, 6, AsciiTerrain.STONE);
			for (int z = 0; z <= 6; z++) terrain.set(10, y, z, AsciiTerrain.STONE);
		}
		return terrain;
	}
}
