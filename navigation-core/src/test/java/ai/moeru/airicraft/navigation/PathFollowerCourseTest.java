package ai.moeru.airicraft.navigation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plans and follows the evaluator's benchmark courses (see NavigationCourses in the evaluator addon)
 * with a simulated body, replanning like the navigation service does.
 */
class PathFollowerCourseTest {
	private static final CellInfo BARRIER = CellInfo.builder("barrier").full().build();
	private static final CellInfo DOOR_EAST_OPEN = CellInfo.builder("oak_door_open").pixels(0, 0, 0, 16, 16, 3)
		.openable(CellInfo.Openable.DOOR, null).build();
	private static final CellInfo DOOR_EAST = CellInfo.builder("oak_door").pixels(0, 0, 0, 3, 16, 16)
		.openable(CellInfo.Openable.DOOR, DOOR_EAST_OPEN).breakTicks(90).build();
	private static final MovementPolicy POLICY = MovementPolicy.defaults();

	@Test
	void flatWalk() {
		Course course = new Course().fill(0, 0, 0, 24, 0, 2, AsciiTerrain.STONE);
		Run run = course.drive(new GridPos(1, 1, 1), new GridPos(23, 1, 1), POLICY);
		run.assertArrived();
		assertTrue(run.ticks < 120, "took " + run.ticks + " ticks");
	}

	@Test
	void staircaseUp() {
		Course course = new Course().fill(0, 0, 0, 4, 0, 2, AsciiTerrain.STONE).fill(11, 0, 0, 15, 6, 2, AsciiTerrain.STONE);
		for (int step = 0; step < 6; step++) course.fill(5 + step, 0, 0, 5 + step, step + 1, 2, AsciiTerrain.STONE);
		course.drive(new GridPos(1, 1, 1), new GridPos(14, 7, 1), POLICY).assertArrived();
	}

	@Test
	void dropThree() {
		Course course = new Course().fill(0, 0, 0, 5, 3, 2, AsciiTerrain.STONE).fill(6, 0, 0, 15, 0, 2, AsciiTerrain.STONE);
		Run run = course.drive(new GridPos(1, 4, 1), new GridPos(13, 1, 1), POLICY);
		run.assertArrived();
		assertEquals(0, run.body.healthLost);
	}

	@Test
	void dropFiveTakesTheStairs() {
		Course course = new Course().fill(0, 0, 0, 5, 5, 4, AsciiTerrain.STONE).fill(6, 0, 0, 20, 0, 4, AsciiTerrain.STONE);
		for (int step = 0; step < 4; step++) course.fill(6 + step, 0, 4, 6 + step, 4 - step, 4, AsciiTerrain.STONE);
		Run run = course.drive(new GridPos(1, 6, 2), new GridPos(18, 1, 2), POLICY);
		run.assertArrived();
		assertEquals(0, run.body.healthLost);
	}

	@Test
	void riverCrossing() {
		Course course = new Course().fill(0, 0, 0, 6, 2, 2, AsciiTerrain.STONE).fill(13, 0, 0, 20, 2, 2, AsciiTerrain.STONE)
			.fill(7, 0, 0, 12, 0, 2, AsciiTerrain.STONE).fill(7, 1, 0, 12, 2, 2, CellInfo.WATER);
		course.drive(new GridPos(2, 3, 1), new GridPos(18, 3, 1), POLICY).assertArrived();
	}

	@Test
	void dirtWall() {
		Course course = new Course().fill(0, 0, 0, 16, 0, 2, AsciiTerrain.STONE).fill(8, 1, 0, 9, 3, 2, AsciiTerrain.DIRT);
		Run run = course.drive(new GridPos(1, 1, 1), new GridPos(15, 1, 1), POLICY);
		run.assertArrived();
		assertTrue(run.body.broken >= 2, "broke " + run.body.broken);
	}

	@Test
	void gapBridge() {
		Course course = new Course().fill(0, 0, 0, 6, 4, 2, AsciiTerrain.STONE).fill(12, 0, 0, 18, 4, 2, AsciiTerrain.STONE);
		Run run = course.drive(new GridPos(1, 5, 1), new GridPos(16, 5, 1), POLICY.withPlaceableBlocks(64));
		run.assertArrived();
		// Pillaring onto the barrier shell and walking along it is cheaper than five bridges.
		assertTrue(run.body.placed >= 2, "placed " + run.body.placed);
		assertEquals(0, run.body.healthLost);
	}

	@Test
	void pillarPit() {
		Course course = new Course().fill(0, 0, 0, 8, 6, 8, AsciiTerrain.STONE).carve(3, 1, 3, 5, 6, 5);
		Run run = course.drive(new GridPos(4, 1, 4), new GridPos(1, 7, 1), POLICY.withPlaceableBlocks(64));
		run.assertArrived();
		assertTrue(run.body.placed >= 5, "placed " + run.body.placed);
	}

	@Test
	void doorHouse() {
		Course course = new Course().fill(0, 0, 0, 16, 0, 6, AsciiTerrain.STONE).fill(2, 1, 1, 8, 4, 5, AsciiTerrain.STONE)
			.carve(3, 1, 2, 7, 3, 4).set(8, 1, 3, DOOR_EAST).set(8, 2, 3, DOOR_EAST);
		Run run = course.drive(new GridPos(5, 1, 3), new GridPos(14, 1, 3), POLICY);
		run.assertArrived();
		assertEquals(0, run.body.broken, "opens the door instead of digging");
		assertEquals(2, run.body.used, "opens the door and closes it behind");
	}

	@Test
	void caveRoute() {
		Course course = new Course().fill(0, 0, 0, 14, 5, 10, AsciiTerrain.STONE)
			.carve(1, 1, 1, 12, 3, 1).carve(12, 1, 1, 12, 3, 5).carve(8, 1, 5, 12, 3, 5).carve(2, 2, 5, 7, 4, 5)
			.carve(2, 2, 5, 2, 4, 9).carve(2, 2, 9, 12, 4, 9);
		Run run = course.drive(new GridPos(1, 1, 1), new GridPos(12, 2, 9), POLICY);
		run.assertArrived();
		assertEquals(0, run.body.broken, "follows the tunnel");
	}

	@Test
	void sprintingTurnAtACliffEdgeStaysOnTheLedge() {
		// A one-wide ledge 10 blocks up runs east, then turns north at the cliff edge.
		Course course = new Course().fill(0, 0, 0, 14, 0, 8, AsciiTerrain.STONE)
			.fill(0, 1, 6, 10, 10, 6, AsciiTerrain.STONE).fill(10, 1, 0, 10, 10, 6, AsciiTerrain.STONE);
		Run run = course.drive(new GridPos(0, 11, 6), new GridPos(10, 11, 0), POLICY.noEdits());
		run.assertArrived();
		assertEquals(0, run.body.healthLost);
	}

	@Test
	void travelBoundsRefusalStaysInside() {
		Course course = new Course().fill(0, 0, 0, 24, 0, 2, AsciiTerrain.STONE);
		Run run = course.drive(new GridPos(1, 1, 1), new GridPos(23, 1, 1), POLICY.withTravelBounds(new Box(-1, -1, -1, 12, 4, 3)));
		assertTrue(!run.arrived, "must not arrive");
		assertTrue(run.body.x < 13, "stayed inside, x=" + run.body.x);
	}

	private static final class Course {
		private final List<int[]> fills = new ArrayList<>();
		private final List<CellInfo> cells = new ArrayList<>();

		Course fill(int x1, int y1, int z1, int x2, int y2, int z2, CellInfo cell) {
			fills.add(new int[]{x1, y1, z1, x2, y2, z2});
			cells.add(cell);
			return this;
		}

		Course carve(int x1, int y1, int z1, int x2, int y2, int z2) {
			return fill(x1, y1, z1, x2, y2, z2, CellInfo.AIR);
		}

		Course set(int x, int y, int z, CellInfo cell) {
			return fill(x, y, z, x, y, z, cell);
		}

		MapTerrain build() {
			int maxX = 0, maxY = 0, maxZ = 0;
			for (int[] fill : fills) {
				maxX = Math.max(maxX, fill[3]);
				maxY = Math.max(maxY, fill[4]);
				maxZ = Math.max(maxZ, fill[5]);
			}
			int top = maxY + 3;
			MapTerrain terrain = new MapTerrain(CellInfo.AIR, new Box(-1, -1, -1, maxX + 1, top + 4, maxZ + 1));
			for (int x = -1; x <= maxX + 1; x++) {
				for (int z = -1; z <= maxZ + 1; z++) {
					boolean wall = x == -1 || x == maxX + 1 || z == -1 || z == maxZ + 1;
					for (int y = -1; y <= top; y++) if (wall || y == -1) terrain.set(x, y, z, BARRIER);
				}
			}
			for (int i = 0; i < fills.size(); i++) {
				int[] f = fills.get(i);
				for (int x = f[0]; x <= f[3]; x++) for (int y = f[1]; y <= f[4]; y++) for (int z = f[2]; z <= f[5]; z++) {
					terrain.set(x, y, z, cells.get(i));
				}
			}
			return terrain;
		}

		Run drive(GridPos start, GridPos goalCell, MovementPolicy policy) {
			MapTerrain terrain = build();
			Goal goal = new Goal.Block(goalCell.x(), goalCell.y(), goalCell.z());
			SimBody body = new SimBody(terrain, start);
			PathFollower follower = null;
			List<String> log = new ArrayList<>();
			int plans = 0;
			for (int tick = 0; tick < 2400; tick++) {
				BodyState state = body.state();
				if (goal.isGoal(state.feet()) && state.supported()) return new Run(true, tick, body, log);
				if (follower == null) {
					if (!state.supported()) {
						body.apply(MotorIntent.IDLE);
						continue;
					}
					if (++plans > 12) break;
					SearchResult result = PathSearch.search(terrain, policy, state.feet(), goal, SearchBudget.defaults(), () -> false);
					log.add("t" + tick + " plan from " + state.feet() + ": " + result.outcome()
						+ (result instanceof SearchResult.Partial partial ? " " + partial.reason() : ""));
					Path path = switch (result) {
						case SearchResult.Found found -> found.path();
						case SearchResult.Partial partial when partial.reason() != SearchResult.Reason.NO_ROUTE -> partial.path();
						default -> null;
					};
					if (path == null) break;
					log.add("  " + path.steps().stream().map(step -> step.type() + "->" + step.to()).toList());
					follower = new PathFollower(path);
				}
				PathFollower.Tick next = follower.tick(state, terrain, policy);
				body.apply(next.intent());
				if (next.status() != PathFollower.Status.RUNNING) {
					log.add("t" + tick + " " + next.status() + (next.detail() == null ? "" : " " + next.detail()) + " at " + state.feet());
					follower = null;
				}
			}
			return new Run(false, 2400, body, log);
		}
	}

	private record Run(boolean arrived, int ticks, SimBody body, List<String> log) {
		void assertArrived() {
			assertTrue(arrived, () -> "did not arrive; body at " + body.state() + "\n" + String.join("\n", log));
		}
	}
}
