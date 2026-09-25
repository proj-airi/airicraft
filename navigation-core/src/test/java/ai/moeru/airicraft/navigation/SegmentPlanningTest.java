package ai.moeru.airicraft.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentPlanningTest {
	@Test
	void farGoalsGetAWaypointOneSegmentAway() {
		Goal waypoint = SegmentPlanning.waypoint(new GridPos(0, 64, 0), 320, 0);
		assertEquals(new Goal.NearXZ(SegmentPlanning.SEGMENT_BLOCKS, 0, SegmentPlanning.WAYPOINT_RADIUS), waypoint);
		assertNull(SegmentPlanning.waypoint(new GridPos(0, 64, 0), 60, 10), "close goals are planned directly");
	}

	@Test
	void plansTheFirstSegmentOfALongCorridor() {
		MapTerrain terrain = corridor(300);

		SearchResult result = SegmentPlanning.plan(terrain, MovementPolicy.defaults(), new GridPos(0, 1, 1),
			new Goal.Block(299, 1, 1), new GridPos(299, 1, 1), SearchBudget.defaults(), () -> false);

		SearchResult.Partial segment = assertInstanceOf(SearchResult.Partial.class, result);
		assertEquals(SearchResult.Reason.SEGMENT, segment.reason());
		int endX = segment.path().end().x();
		assertTrue(endX >= SegmentPlanning.SEGMENT_BLOCKS - SegmentPlanning.WAYPOINT_RADIUS && endX <= SegmentPlanning.SEGMENT_BLOCKS,
			"ends near the waypoint, x=" + endX);
		assertTrue(result.stats().expanded() < 1000, "expanded " + result.stats().expanded());
	}

	@Test
	void fallsBackToTheGoalWhenTheWaypointIsWalledOff() {
		MapTerrain terrain = corridor(300);
		// A bedrock plug around the waypoint; the corridor detours below it.
		for (int x = 50; x <= 80; x++) for (int y = 1; y <= 6; y++) for (int z = 0; z <= 2; z++) terrain.set(x, y, z, AsciiTerrain.BEDROCK);

		SearchResult result = SegmentPlanning.plan(terrain, MovementPolicy.defaults(), new GridPos(0, 1, 1),
			new Goal.Block(299, 1, 1), new GridPos(299, 1, 1), SearchBudget.defaults(), () -> false);

		assertTrue(!(result instanceof SearchResult.Partial partial) || partial.reason() != SearchResult.Reason.SEGMENT,
			"no waypoint segment through bedrock: " + result.outcome());
	}

	private static MapTerrain corridor(int length) {
		MapTerrain terrain = new MapTerrain(CellInfo.AIR, new Box(0, 0, 0, length - 1, 8, 2));
		for (int x = 0; x < length; x++) for (int z = 0; z <= 2; z++) terrain.set(x, 0, z, AsciiTerrain.STONE);
		return terrain;
	}
}
