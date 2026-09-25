package ai.moeru.airicraft.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSearchTest {
	private static final MovementPolicy WALK_ONLY = MovementPolicy.defaults().noEdits();
	private static final MovementPolicy BUILDER = MovementPolicy.defaults().withPlaceableBlocks(64);

	@Test
	void walksStraightAcrossFlatGround() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			#########
			y1
			S.......G
			""");

		Path path = found(world.search(WALK_ONLY));

		assertEquals(8, path.steps().size());
		assertTrue(path.steps().stream().allMatch(step -> step.type() == MoveType.TRAVERSE));
		assertEquals(8, path.length(), 1e-9);
		assertEquals(8 * Costs.SPRINT, path.cost(), 1e-9);
	}

	@Test
	void climbsAStaircaseWithJumps() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			#####
			y1
			S.###
			y2
			...##
			y3
			....#
			y4
			....G
			""");

		Path path = found(world.search(WALK_ONLY));

		assertEquals(List.of(MoveType.TRAVERSE, MoveType.ASCEND, MoveType.ASCEND, MoveType.ASCEND), types(path));
	}

	@Test
	void stepsOntoASlabWithoutJumping() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			#####
			y1
			S._#.
			y2
			...G.
			""");

		Path path = found(world.search(WALK_ONLY));

		assertEquals(List.of(MoveType.TRAVERSE, MoveType.TRAVERSE, MoveType.ASCEND), types(path));
		assertEquals(Costs.WALK, path.steps().getLast().cost(), 1e-9, "slab to block is a step, not a jump");
	}

	@Test
	void dropsThreeBlocksButNotFour() {
		String three = """
			y0
			####
			y1
			#..G
			y2
			#...
			y3
			#...
			y4
			S...
			""";
		Path path = found(AsciiTerrain.parse(three).search(WALK_ONLY));
		assertEquals(MoveType.FALL, path.steps().getFirst().type());
		assertEquals(new GridPos(1, 1, 0), path.steps().getFirst().to());

		AsciiTerrain four = AsciiTerrain.parse("""
			y0
			####
			y1
			#..G
			y2
			#...
			y3
			#...
			y4
			#...
			y5
			S...
			""");
		assertFalse(four.search(WALK_ONLY) instanceof SearchResult.Found, "a four-block drop hurts");
	}

	@Test
	void fallsAnyHeightIntoWater() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			#####
			y1
			#www#
			y2
			#...#
			y3
			#...#
			y4
			#...#
			y5
			#...#
			y6
			#...#
			y7
			S...G
			""");

		Path path = found(world.search(WALK_ONLY, new Goal.Block(2, 1, 0)));

		Step fall = path.steps().stream().filter(step -> step.type() == MoveType.FALL).findFirst().orElseThrow();
		assertEquals(1, fall.to().y());
	}

	@Test
	void swimsAcrossAChannelAndClimbsOut() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			#########
			y1
			###www###
			y2
			###www###
			y3
			S..www..G
			""");

		Path path = found(world.search(WALK_ONLY));

		assertTrue(path.positions().stream().anyMatch(pos -> pos.x() == 4), "crosses the channel");
		assertEquals(new GridPos(8, 3, 0), path.end());
	}

	@Test
	void climbsOutOfWaterOneBlockBelowTheBank() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			#########
			y1
			###www###
			y2
			S.......G
			""");

		Path path = found(world.search(WALK_ONLY, new Goal.Block(8, 2, 0)));

		assertTrue(path.steps().stream().anyMatch(step -> step.type() == MoveType.ASCEND && step.from().x() == 5),
			"jumps from the water onto the bank: " + types(path));
	}

	@Test
	void walksAroundLava() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			##l##
			#####
			y1
			S...G
			.....
			""");

		Path path = found(world.search(WALK_ONLY));

		assertTrue(path.positions().stream().noneMatch(pos -> pos.x() == 2 && pos.z() == 0), "never above lava");
	}

	@Test
	void avoidsStandingOnMagma() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			##m##
			#####
			y1
			S...G
			.....
			""");

		Path path = found(world.search(WALK_ONLY));

		assertTrue(path.positions().stream().noneMatch(pos -> pos.x() == 2 && pos.z() == 0));
	}

	@Test
	void tunnelsThroughAWallTopFirst() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			#######
			#######
			#######
			y1
			BBBBBBB
			S..d..G
			BBBBBBB
			y2
			BBBBBBB
			...d...
			BBBBBBB
			y3
			BBBBBBB
			BBBBBBB
			BBBBBBB
			""");

		Path path = found(world.search(MovementPolicy.defaults()));

		Step tunnel = path.steps().stream().filter(Step::edits).findFirst().orElseThrow();
		assertEquals(List.of(new GridPos(3, 2, 1), new GridPos(3, 1, 1)), tunnel.breaks());
		assertEquals(2, path.breaks());
		assertFalse(world.search(WALK_ONLY) instanceof SearchResult.Found);
	}

	@Test
	void neverBreaksInsideAProtectedArea() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			#######
			#######
			#######
			y1
			BBBBBBB
			S..d..G
			BBBBBBB
			y2
			BBBBBBB
			...d...
			BBBBBBB
			y3
			BBBBBBB
			BBBBBBB
			BBBBBBB
			""");

		SearchResult result = world.search(MovementPolicy.defaults().withProtectedAreas(List.of(new Box(3, 0, 1, 3, 10, 1))));

		assertFalse(result instanceof SearchResult.Found);
	}

	@Test
	void doesNotUndermineSand() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			#######
			#######
			#######
			y1
			BBBBBBB
			S..d..G
			BBBBBBB
			y2
			BBBBBBB
			...d...
			BBBBBBB
			y3
			BBBBBBB
			...n...
			BBBBBBB
			y4
			BBBBBBB
			BBBBBBB
			BBBBBBB
			""");

		assertFalse(world.search(MovementPolicy.defaults()) instanceof SearchResult.Found);
	}

	@Test
	void bridgesAGapOnlyWithBlocks() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			###...###
			y1
			S.......G
			""");

		Path path = found(world.search(BUILDER));

		List<Step> bridges = path.steps().stream().filter(step -> step.type() == MoveType.BRIDGE).toList();
		assertEquals(3, bridges.size());
		assertEquals(new GridPos(3, 0, 0), bridges.getFirst().place());
		assertEquals(new GridPos(2, 0, 0), bridges.getFirst().placeAgainst());
		assertFalse(world.search(MovementPolicy.defaults()) instanceof SearchResult.Found, "no blocks to place");
	}

	@Test
	void pillarsOutOfAPit() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			#####
			y1
			BBSBB
			y2
			BB.BB
			y3
			BB.BB
			y4
			....G
			""");

		Path path = found(world.search(BUILDER));

		assertEquals(List.of(MoveType.PILLAR, MoveType.PILLAR, MoveType.ASCEND, MoveType.TRAVERSE), types(path));
		assertEquals(new GridPos(2, 1, 0), path.steps().getFirst().place());
		assertFalse(world.search(MovementPolicy.defaults()) instanceof SearchResult.Found, "bedrock walls, no blocks");
	}

	@Test
	void opensADoorCrossingItsPanel() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			BBB
			BBB
			BBB
			y1
			BSB
			BDB
			BGB
			y2
			B.B
			BDB
			B.B
			y3
			BBB
			BBB
			BBB
			""");

		MovementPolicy doors = new MovementPolicy(false, false, true, true, 3, 64, 0, 3, 2, 20, 2, null, List.of(), List.of());
		Path path = found(world.search(doors));
		assertTrue(path.steps().stream().anyMatch(step -> step.doors().contains(new GridPos(1, 1, 1))), "uses the door");

		assertFalse(world.search(WALK_ONLY) instanceof SearchResult.Found, "no doors in a walk-only policy");
	}

	@Test
	void walksAlongADoorPanelWithoutOpeningIt() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			BBB
			y1
			SDG
			y2
			.D.
			""");

		Path path = found(world.search(WALK_ONLY));

		assertTrue(path.steps().stream().allMatch(step -> step.doors().isEmpty()), "the panel is on the north edge");
	}

	@Test
	void ironDoorsStayShut() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			BBB
			BBB
			BBB
			y1
			BSB
			BIB
			BGB
			y2
			B.B
			BIB
			B.B
			y3
			BBB
			BBB
			BBB
			""");

		assertFalse(world.search(MovementPolicy.defaults()) instanceof SearchResult.Found);
	}

	@Test
	void upperSlabAtHeadHeightBlocksButATopTrapdoorDoesNot() {
		AsciiTerrain slab = AsciiTerrain.parse("""
			y0
			#####
			y1
			S...G
			y2
			..^..
			""");
		assertFalse(slab.search(WALK_ONLY) instanceof SearchResult.Found);

		CellInfo trapdoor = CellInfo.builder("trapdoor_top").pixels(0, 13, 0, 16, 16, 16).build();
		slab.terrain.set(2, 2, 0, trapdoor);
		found(slab.search(WALK_ONLY));
	}

	@Test
	void fencesCannotBeJumped() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			#####
			y1
			S.F.G
			""");

		assertFalse(world.search(WALK_ONLY) instanceof SearchResult.Found);
	}

	@Test
	void climbsALadder() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			BBB
			y1
			SHB
			y2
			.HB
			y3
			.HB
			y4
			..G
			""");

		Path path = found(world.search(WALK_ONLY));

		assertEquals(List.of(MoveType.TRAVERSE, MoveType.CLIMB_UP, MoveType.CLIMB_UP, MoveType.ASCEND), types(path));
	}

	@Test
	void avoidsCobwebsWithoutBreaking() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			#####
			#####
			y1
			S.c.G
			.....
			""");

		Path path = found(world.search(WALK_ONLY));

		assertTrue(path.positions().stream().noneMatch(pos -> pos.x() == 2 && pos.z() == 0));
	}

	@Test
	void cutsCornersDiagonallyOnlyWhenBothSidesAreClear() {
		AsciiTerrain open = AsciiTerrain.parse("""
			y0
			#####
			#####
			#####
			#####
			#####
			y1
			S....
			.....
			.....
			.....
			....G
			""");
		Path diagonal = found(open.search(WALK_ONLY));
		assertEquals(4, diagonal.steps().size());
		assertTrue(diagonal.steps().stream().allMatch(step -> step.type() == MoveType.DIAGONAL));

		AsciiTerrain walled = AsciiTerrain.parse("""
			y0
			###
			###
			y1
			SB.
			..G
			y2
			.B.
			...
			""");
		Path around = found(walled.search(WALK_ONLY));
		assertTrue(around.steps().stream().noneMatch(step -> step.type() == MoveType.DIAGONAL && step.from().equals(walled.start)),
			"the first diagonal would clip the pillar");
	}

	@Test
	void travelBoundsKeepEveryMoveInside() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			################
			y1
			S..............G
			""");
		Box bounds = new Box(0, 0, 0, 10, 5, 0);

		SearchResult result = world.search(MovementPolicy.defaults().withTravelBounds(bounds));

		SearchResult.Partial partial = assertInstanceOf(SearchResult.Partial.class, result);
		assertTrue(partial.path().positions().stream().allMatch(pos -> pos.x() <= 10));
		assertEquals(10, partial.path().end().x());
	}

	@Test
	void stopsAtUnloadedTerrainWithTheBestPrefix() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			##########
			y1
			S.........
			""");

		SearchResult result = world.search(WALK_ONLY, new Goal.Block(40, 1, 0));

		SearchResult.Partial partial = assertInstanceOf(SearchResult.Partial.class, result);
		assertEquals(SearchResult.Reason.UNLOADED_FRONTIER, partial.reason());
		assertEquals(new GridPos(9, 1, 0), partial.path().end());
	}

	@Test
	void nodeBudgetReturnsTheBestPrefix() {
		MapTerrain terrain = flatField(64);

		SearchResult result = PathSearch.search(terrain, WALK_ONLY, new GridPos(0, 1, 0), new Goal.Block(60, 1, 60),
			new SearchBudget(40, 1_000_000_000L, 2), () -> false);

		SearchResult.Partial partial = assertInstanceOf(SearchResult.Partial.class, result);
		assertEquals(SearchResult.Reason.NODE_BUDGET, partial.reason());
		assertTrue(partial.path().steps().size() > 2);
	}

	@Test
	void cancellationStopsTheSearch() {
		MapTerrain terrain = flatField(128);

		SearchResult result = PathSearch.search(terrain, WALK_ONLY, new GridPos(0, 1, 0), new Goal.Block(127, 1, 127),
			SearchBudget.defaults(), () -> true);

		// The first check happens after a batch of expansions; a short path may finish before it.
		assertTrue(result instanceof SearchResult.Cancelled || result instanceof SearchResult.Found);
	}

	@Test
	void nearAndColumnGoals() {
		AsciiTerrain world = AsciiTerrain.parse("""
			y0
			##########
			y1
			S.........
			""");

		Path near = found(world.search(WALK_ONLY, new Goal.Near(9, 1, 0, 3)));
		assertEquals(6, near.end().x());
		Path column = found(world.search(WALK_ONLY, new Goal.XZ(5, 0)));
		assertEquals(new GridPos(5, 1, 0), column.end());
	}

	@Test
	void plansAcrossALargeFieldQuickly() {
		MapTerrain terrain = flatField(200);
		Random random = new Random(7);
		CellInfo stone = AsciiTerrain.STONE;
		for (int i = 0; i < 3000; i++) {
			int x = random.nextInt(200), z = random.nextInt(200);
			if (x + z < 4 || x + z > 392) continue;
			terrain.set(x, 1, z, stone).set(x, 2, z, stone);
		}

		SearchResult result = PathSearch.search(terrain, MovementPolicy.defaults(), new GridPos(0, 1, 0),
			new Goal.Block(199, 1, 199), SearchBudget.defaults(), () -> false);

		Path path = found(result);
		System.out.printf("200x200 field: %d steps, %d expanded, %.1f ms%n", path.steps().size(),
			result.stats().expanded(), result.stats().millis());
		assertTrue(result.stats().millis() < 1000, "took " + result.stats().millis() + " ms");
	}

	static MapTerrain flatField(int size) {
		MapTerrain terrain = new MapTerrain(CellInfo.AIR, new Box(0, 0, 0, size - 1, 6, size - 1));
		for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) terrain.set(x, 0, z, AsciiTerrain.STONE);
		return terrain;
	}

	private static Path found(SearchResult result) {
		SearchResult.Found found = assertInstanceOf(SearchResult.Found.class, result, () -> "expected a path, got " + result);
		return found.path();
	}

	private static List<MoveType> types(Path path) {
		return path.steps().stream().map(Step::type).toList();
	}
}
