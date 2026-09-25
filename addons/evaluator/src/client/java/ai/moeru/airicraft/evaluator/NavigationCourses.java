package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.evaluator.NavigationCourse.Block;
import ai.moeru.airicraft.evaluator.NavigationCourse.Box;
import ai.moeru.airicraft.evaluator.NavigationCourse.Cell;
import ai.moeru.airicraft.evaluator.NavigationCourse.Expectation;
import ai.moeru.airicraft.evaluator.NavigationCourse.Kind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Navigation benchmark catalog for comparing navigation backends. Courses run along +x; corridors are
 * three cells wide (z 0..2) unless a course needs more room.
 */
final class NavigationCourses {
	/** Barrier walls rise this many cells above the highest course cell. */
	static final int SHELL_HEADROOM = 3;
	static final int FAR_TRAVEL_BLOCKS = 320;

	private static final Map<String, Integer> BUILDING_BLOCKS = Map.of("minecraft:cobblestone", 64);
	private static final List<NavigationCourse> COURSES = List.of(
		new Builder("flat_walk", "Walk 22 blocks along a flat corridor.")
			.fill(0, 0, 0, 24, 0, 2, Block.STONE)
			.build(new Cell(1, 1, 1), new Cell(23, 1, 1)),
		staircaseUp(),
		new Builder("drop_3", "Step off a ledge onto a floor three blocks below.")
			.fill(0, 0, 0, 5, 3, 2, Block.STONE)
			.fill(6, 0, 0, 15, 0, 2, Block.STONE)
			.build(new Cell(1, 4, 1), new Cell(13, 1, 1)),
		drop5WithStairs(),
		new Builder("river_crossing", "Swim across a six-wide, two-deep channel and climb out.")
			.fill(0, 0, 0, 6, 2, 2, Block.STONE)
			.fill(13, 0, 0, 20, 2, 2, Block.STONE)
			.fill(7, 0, 0, 12, 0, 2, Block.STONE)
			.fill(7, 1, 0, 12, 2, 2, Block.WATER)
			.build(new Cell(2, 3, 1), new Cell(18, 3, 1)),
		new Builder("dirt_wall", "Break through a two-thick, three-high dirt wall with an empty inventory.")
			.fill(0, 0, 0, 16, 0, 2, Block.STONE)
			.fill(8, 1, 0, 9, 3, 2, Block.DIRT)
			.build(new Cell(1, 1, 1), new Cell(15, 1, 1)),
		new Builder("gap_bridge", "Cross a five-wide gap between platforms; carried cobblestone allows bridging.")
			.fill(0, 0, 0, 6, 4, 2, Block.STONE)
			.fill(12, 0, 0, 18, 4, 2, Block.STONE)
			.loadout(BUILDING_BLOCKS)
			.build(new Cell(1, 5, 1), new Cell(16, 5, 1)),
		new Builder("pillar_pit", "Leave a six-deep stone pit; carried cobblestone allows pillaring.")
			.fill(0, 0, 0, 8, 6, 8, Block.STONE)
			.carve(3, 1, 3, 5, 6, 5)
			.loadout(BUILDING_BLOCKS)
			.build(new Cell(4, 1, 4), new Cell(1, 7, 1)),
		new Builder("door_house", "Leave a closed stone room through its oak door.")
			.fill(0, 0, 0, 16, 0, 6, Block.STONE)
			.fill(2, 1, 1, 8, 4, 5, Block.STONE)
			.carve(3, 1, 2, 7, 3, 4)
			.door(8, 1, 3)
			.build(new Cell(5, 1, 3), new Cell(14, 1, 3)),
		caveRoute(),
		new NavigationCourse(
			"far_xz",
			"Travel " + FAR_TRAVEL_BLOCKS + " blocks east over natural terrain from the recorded origin, crossing unloaded chunks.",
			Kind.TERRAIN,
			Expectation.ARRIVE,
			Map.of(),
			new Cell(0, 0, 0),
			new Cell(FAR_TRAVEL_BLOCKS, 0, 0),
			false,
			Map.of(),
			null
		),
		new Builder("travel_bounds_refusal", "Strategy travel bounds end halfway; the goal beyond them must not be reached.")
			.fill(0, 0, 0, 24, 0, 2, Block.STONE)
			.expect(Expectation.REFUSE)
			.travelBounds(new Box(new Cell(-1, -1, -1), new Cell(12, 4, 3)))
			.build(new Cell(1, 1, 1), new Cell(23, 1, 1))
	);

	private NavigationCourses() {
	}

	static List<NavigationCourse> all() {
		return COURSES;
	}

	static Optional<NavigationCourse> byId(String id) {
		return COURSES.stream().filter(course -> course.id().equals(id)).findFirst();
	}

	private static NavigationCourse staircaseUp() {
		Builder builder = new Builder("staircase_up", "Climb six one-block steps to a raised platform.")
			.fill(0, 0, 0, 4, 0, 2, Block.STONE)
			.fill(11, 0, 0, 15, 6, 2, Block.STONE);
		for (int step = 0; step < 6; step++) {
			builder.fill(5 + step, 0, 0, 5 + step, step + 1, 2, Block.STONE);
		}
		return builder.build(new Cell(1, 1, 1), new Cell(14, 7, 1));
	}

	private static NavigationCourse drop5WithStairs() {
		Builder builder = new Builder("drop_5_stairs",
			"Descend five blocks; a direct drop costs health, a staircase along the far lane does not.")
			.fill(0, 0, 0, 5, 5, 4, Block.STONE)
			.fill(6, 0, 0, 20, 0, 4, Block.STONE);
		for (int step = 0; step < 4; step++) {
			builder.fill(6 + step, 0, 4, 6 + step, 4 - step, 4, Block.STONE);
		}
		return builder.build(new Cell(1, 6, 2), new Cell(18, 1, 2));
	}

	private static NavigationCourse caveRoute() {
		return new Builder("cave_route", "Follow a dark, winding one-wide tunnel with a one-block step up.")
			.fill(0, 0, 0, 14, 5, 10, Block.STONE)
			.carve(1, 1, 1, 12, 3, 1)
			.carve(12, 1, 1, 12, 3, 5)
			.carve(8, 1, 5, 12, 3, 5)
			.carve(2, 2, 5, 7, 4, 5)
			.carve(2, 2, 5, 2, 4, 9)
			.carve(2, 2, 9, 12, 4, 9)
			.build(new Cell(1, 1, 1), new Cell(12, 2, 9));
	}

	private static final class Builder {
		private final String id;
		private final String description;
		private final LinkedHashMap<Cell, Block> blocks = new LinkedHashMap<>();
		private Expectation expectation = Expectation.ARRIVE;
		private Map<String, Integer> loadout = Map.of();
		private Box travelBounds;

		private Builder(String id, String description) {
			this.id = id;
			this.description = description;
		}

		Builder fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
			for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
				for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
					for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
						blocks.put(new Cell(x, y, z), block);
					}
				}
			}
			return this;
		}

		/** Leaves the cells as air. */
		Builder carve(int x1, int y1, int z1, int x2, int y2, int z2) {
			for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
				for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
					for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
						blocks.remove(new Cell(x, y, z));
					}
				}
			}
			return this;
		}

		/** A closed east-facing oak door whose lower half is at the cell. */
		Builder door(int x, int y, int z) {
			blocks.put(new Cell(x, y, z), Block.OAK_DOOR_LOWER);
			blocks.put(new Cell(x, y + 1, z), Block.OAK_DOOR_UPPER);
			return this;
		}

		Builder expect(Expectation expectation) {
			this.expectation = expectation;
			return this;
		}

		Builder loadout(Map<String, Integer> loadout) {
			this.loadout = loadout;
			return this;
		}

		Builder travelBounds(Box travelBounds) {
			this.travelBounds = travelBounds;
			return this;
		}

		NavigationCourse build(Cell start, Cell goal) {
			NavigationCourse interior = new NavigationCourse(id, description, Kind.FIXTURE, expectation, blocks,
				start, goal, true, loadout, travelBounds);
			Box box = interior.footprint();
			LinkedHashMap<Cell, Block> enclosed = new LinkedHashMap<>(blocks);
			int topY = box.max().y() + SHELL_HEADROOM;
			for (int x = box.min().x() - 1; x <= box.max().x() + 1; x++) {
				for (int z = box.min().z() - 1; z <= box.max().z() + 1; z++) {
					boolean wall = x == box.min().x() - 1 || x == box.max().x() + 1
						|| z == box.min().z() - 1 || z == box.max().z() + 1;
					for (int y = box.min().y() - 1; y <= topY; y++) {
						if (wall || y == box.min().y() - 1) {
							enclosed.putIfAbsent(new Cell(x, y, z), Block.BARRIER);
						}
					}
				}
			}
			return new NavigationCourse(id, description, Kind.FIXTURE, expectation, enclosed,
				start, goal, true, loadout, travelBounds);
		}
	}
}
