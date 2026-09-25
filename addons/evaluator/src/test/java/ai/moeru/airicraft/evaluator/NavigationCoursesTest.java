package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.evaluator.NavigationCourse.Block;
import ai.moeru.airicraft.evaluator.NavigationCourse.Box;
import ai.moeru.airicraft.evaluator.NavigationCourse.Cell;
import ai.moeru.airicraft.evaluator.NavigationCourse.Expectation;
import ai.moeru.airicraft.evaluator.NavigationCourse.Kind;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationCoursesTest {
	@Test
	void catalogCoversThePlannedCoursesWithUniqueIds() {
		List<String> ids = NavigationCourses.all().stream().map(NavigationCourse::id).toList();

		assertEquals(new HashSet<>(ids).size(), ids.size());
		assertEquals(List.of("flat_walk", "staircase_up", "drop_3", "drop_5_stairs", "river_crossing", "dirt_wall",
			"gap_bridge", "pillar_pit", "door_house", "cave_route", "far_xz", "travel_bounds_refusal"), ids);
	}

	@Test
	void fixtureStartAndGoalCellsAreStandable() {
		for (NavigationCourse course : fixtures()) {
			assertStandable(course, course.start(), "start");
			assertStandable(course, course.goal(), "goal");
		}
	}

	@Test
	void fixturesAreEnclosedByABarrierShellWithAFloor() {
		for (NavigationCourse course : fixtures()) {
			Box box = course.footprint();
			for (int x = box.min().x(); x <= box.max().x(); x++) {
				for (int z = box.min().z(); z <= box.max().z(); z++) {
					assertEquals(Block.BARRIER, course.blockAt(new Cell(x, box.min().y(), z)),
						course.id() + " floor at " + x + "," + z);
				}
			}
			for (int y = box.min().y(); y <= box.max().y(); y++) {
				for (int x = box.min().x(); x <= box.max().x(); x++) {
					assertEquals(Block.BARRIER, course.blockAt(new Cell(x, y, box.min().z())), course.id() + " north wall");
					assertEquals(Block.BARRIER, course.blockAt(new Cell(x, y, box.max().z())), course.id() + " south wall");
				}
				for (int z = box.min().z(); z <= box.max().z(); z++) {
					assertEquals(Block.BARRIER, course.blockAt(new Cell(box.min().x(), y, z)), course.id() + " west wall");
					assertEquals(Block.BARRIER, course.blockAt(new Cell(box.max().x(), y, z)), course.id() + " east wall");
				}
			}
			assertTrue(inside(box, course.start()) && inside(box, course.goal()), course.id() + " endpoints inside the shell");
		}
	}

	@Test
	void fixturesStayWithinASmallBuildVolume() {
		for (NavigationCourse course : fixtures()) {
			Box box = course.footprint();
			assertTrue(box.sizeX() <= 32 && box.sizeZ() <= 16 && box.sizeY() <= 14,
				course.id() + " footprint " + box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ());
		}
	}

	@Test
	void doorHalvesArePaired() {
		for (NavigationCourse course : fixtures()) {
			for (Map.Entry<Cell, Block> entry : course.blocks().entrySet()) {
				if (entry.getValue() == Block.OAK_DOOR_LOWER) {
					assertEquals(Block.OAK_DOOR_UPPER, course.blockAt(entry.getKey().offset(0, 1, 0)), course.id());
				}
				if (entry.getValue() == Block.OAK_DOOR_UPPER) {
					assertEquals(Block.OAK_DOOR_LOWER, course.blockAt(entry.getKey().offset(0, -1, 0)), course.id());
				}
			}
		}
	}

	@Test
	void staircaseRisesOneBlockPerStep() {
		NavigationCourse course = NavigationCourses.byId("staircase_up").orElseThrow();
		int previousTop = 0;
		for (int x = 5; x <= 10; x++) {
			int top = highestSolid(course, x, 1);
			assertEquals(previousTop + 1, top, "step at x=" + x);
			previousTop = top;
		}
	}

	@Test
	void directDropIsFiveBlocksWhileTheStairLaneDescendsByOne() {
		NavigationCourse course = NavigationCourses.byId("drop_5_stairs").orElseThrow();
		assertEquals(5, highestSolid(course, 5, 0) - highestSolid(course, 6, 0));
		int previousTop = highestSolid(course, 5, 4);
		for (int x = 6; x <= 10; x++) {
			int top = highestSolid(course, x, 4);
			assertEquals(previousTop - 1, top, "stair at x=" + x);
			previousTop = top;
		}
	}

	@Test
	void caveRouteStepUpHasHeadClearance() {
		NavigationCourse course = NavigationCourses.byId("cave_route").orElseThrow();
		// Jumping from feet (8,1,5) to (7,2,5) needs air at (8,3,5) and at (7,2..3,5).
		assertNull(course.blockAt(new Cell(8, 3, 5)));
		assertNull(course.blockAt(new Cell(7, 2, 5)));
		assertNull(course.blockAt(new Cell(7, 3, 5)));
		assertEquals(Block.STONE, course.blockAt(new Cell(7, 1, 5)));
	}

	@Test
	void gapIsTooWideToJumpAndBuildingCoursesCarryBlocks() {
		NavigationCourse gap = NavigationCourses.byId("gap_bridge").orElseThrow();
		for (int x = 7; x <= 11; x++) {
			assertEquals(-1, highestSolid(gap, x, 1), "gap column x=" + x);
		}
		assertEquals(64, gap.loadout().get("minecraft:cobblestone"));
		assertEquals(64, NavigationCourses.byId("pillar_pit").orElseThrow().loadout().get("minecraft:cobblestone"));
		assertTrue(NavigationCourses.byId("dirt_wall").orElseThrow().loadout().isEmpty());
	}

	@Test
	void refusalCourseBoundsContainTheStartButNotTheGoal() {
		NavigationCourse course = NavigationCourses.byId("travel_bounds_refusal").orElseThrow();

		assertEquals(Expectation.REFUSE, course.expectation());
		assertTrue(course.travelBounds().contains(course.start()));
		assertTrue(course.travelBounds().contains(course.start().offset(0, 1, 0)));
		assertFalse(course.travelBounds().contains(course.goal()));
	}

	@Test
	void farTravelIsAHorizontalTerrainGoal() {
		NavigationCourse course = NavigationCourses.byId("far_xz").orElseThrow();

		assertEquals(Kind.TERRAIN, course.kind());
		assertTrue(course.blocks().isEmpty());
		assertFalse(course.exactY());
		assertEquals(NavigationCourses.FAR_TRAVEL_BLOCKS, course.goal().x() - course.start().x());
	}

	private static List<NavigationCourse> fixtures() {
		return NavigationCourses.all().stream().filter(course -> course.kind() == Kind.FIXTURE).toList();
	}

	private static void assertStandable(NavigationCourse course, Cell cell, String label) {
		String where = course.id() + " " + label + " " + cell;
		assertNull(course.blockAt(cell), where + " feet must be air");
		assertNull(course.blockAt(cell.offset(0, 1, 0)), where + " head must be air");
		Block below = course.blockAt(cell.offset(0, -1, 0));
		assertTrue(below != null && below.supportsStanding(), where + " needs solid support, found " + below);
	}

	private static boolean inside(Box shell, Cell cell) {
		return cell.x() > shell.min().x() && cell.x() < shell.max().x()
			&& cell.y() > shell.min().y()
			&& cell.z() > shell.min().z() && cell.z() < shell.max().z();
	}

	/** Highest solid (non-barrier) course block in a column, or -1 when the column is open to the shell floor. */
	private static int highestSolid(NavigationCourse course, int x, int z) {
		int top = -1;
		for (Map.Entry<Cell, Block> entry : course.blocks().entrySet()) {
			Cell cell = entry.getKey();
			if (cell.x() == x && cell.z() == z && entry.getValue() != Block.BARRIER && entry.getValue().supportsStanding()) {
				top = Math.max(top, cell.y());
			}
		}
		return top;
	}
}
