package ai.moeru.airicraft.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaircaseTest {
	/** Solid stone from y=0 to y=60 with the surface at y=61, open air above. */
	private static MapTerrain rock() {
		MapTerrain terrain = new MapTerrain(CellInfo.AIR, new Box(-40, 0, -40, 40, 80, 40));
		for (int x = -40; x <= 40; x++) for (int z = -40; z <= 40; z++) for (int y = 0; y <= 60; y++) {
			terrain.set(x, y, z, y == 0 ? AsciiTerrain.BEDROCK : AsciiTerrain.STONE);
		}
		return terrain;
	}

	@Test
	void digsOneStepForwardAndOneDownAtATime() {
		Path stairs = Staircase.build(rock(), MovementPolicy.defaults(), new GridPos(0, 61, 0), new GridPos(0, 31, 0), 40);

		assertNotNull(stairs);
		assertEquals(31, stairs.end().y(), "reaches the target level");
		GridPos previous = stairs.start();
		for (Step step : stairs.steps()) {
			assertEquals(MoveType.DESCEND, step.type());
			assertEquals(-1, step.dy());
			assertEquals(1, Math.abs(step.dx()) + Math.abs(step.dz()));
			assertTrue(!step.breaks().isEmpty(), "digs through rock");
			assertEquals(previous, step.from());
			previous = step.to();
		}
	}

	@Test
	void headsTowardTheTargetColumn() {
		Path stairs = Staircase.build(rock(), MovementPolicy.defaults(), new GridPos(0, 61, 0), new GridPos(20, 41, 0), 40);

		assertNotNull(stairs);
		assertEquals(new GridPos(20, 41, 0), stairs.end());
	}

	@Test
	void turnsAwayFromLavaInsteadOfDiggingNextToIt() {
		MapTerrain terrain = rock();
		// A lava pocket on the straight line east, a few steps down.
		for (int x = 3; x <= 6; x++) for (int y = 55; y <= 57; y++) for (int z = -1; z <= 1; z++) terrain.set(x, y, z, CellInfo.LAVA);

		Path stairs = Staircase.build(terrain, MovementPolicy.defaults(), new GridPos(0, 61, 0), new GridPos(30, 31, 0), 40);

		assertNotNull(stairs);
		for (Step step : stairs.steps()) {
			for (GridPos broken : step.breaks()) {
				for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}, {0, 0, 0}}) {
					CellInfo next = terrain.cell(broken.x() + d[0], broken.y() + d[1], broken.z() + d[2]);
					assertTrue(next.fluid() != CellInfo.Fluid.LAVA, "broke " + broken + " beside lava");
				}
			}
		}
		assertTrue(stairs.end().y() < 55, "still gets below the pocket, ended at " + stairs.end());
	}

	@Test
	void stopsAtUnbreakableRock() {
		MapTerrain terrain = new MapTerrain(AsciiTerrain.BEDROCK, new Box(-5, 0, -5, 5, 20, 5));
		for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = 11; y <= 20; y++) terrain.set(x, y, z, CellInfo.AIR);

		assertNull(Staircase.build(terrain, MovementPolicy.defaults(), new GridPos(0, 11, 0), new GridPos(0, 2, 0), 10));
	}

	@Test
	void deepGoalsUnderRockPlanAStaircaseSegment() {
		SearchResult result = SegmentPlanning.plan(rock(), MovementPolicy.defaults(), new GridPos(0, 61, 0),
			new Goal.Block(4, 21, 2), new GridPos(4, 21, 2), SearchBudget.defaults(), () -> false);

		SearchResult.Partial segment = assertInstanceOf(SearchResult.Partial.class, result);
		assertEquals(SearchResult.Reason.SEGMENT, segment.reason());
		assertEquals(61 - Staircase.MAX_STEPS, segment.path().end().y(), "one full staircase segment down");
	}

	@Test
	void shallowGoalsStillUseTheSearch() {
		SearchResult result = SegmentPlanning.plan(rock(), MovementPolicy.defaults(), new GridPos(0, 61, 0),
			new Goal.Block(3, 58, 0), new GridPos(3, 58, 0), SearchBudget.defaults(), () -> false);

		assertInstanceOf(SearchResult.Found.class, result);
	}
}
