package ai.moeru.airicraft.evaluator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One deterministic navigation benchmark course in course-local cells, where y=0 is the lowest course layer.
 * Fixture courses are built in the air and enclosed by a barrier shell; terrain courses use the natural world.
 */
record NavigationCourse(
	String id,
	String description,
	Kind kind,
	Expectation expectation,
	Map<Cell, Block> blocks,
	Cell start,
	Cell goal,
	boolean exactY,
	Map<String, Integer> loadout,
	Box travelBounds
) {
	NavigationCourse {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(expectation, "expectation");
		Objects.requireNonNull(start, "start");
		Objects.requireNonNull(goal, "goal");
		blocks = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNullElse(blocks, Map.of())));
		loadout = Map.copyOf(Objects.requireNonNullElse(loadout, Map.of()));
	}

	/** Every cell a fixture writes, including air cells cleared when the course is removed. */
	Box footprint() {
		int minX = Math.min(start.x(), goal.x()), minY = Math.min(start.y(), goal.y()), minZ = Math.min(start.z(), goal.z());
		int maxX = Math.max(start.x(), goal.x()), maxY = Math.max(start.y(), goal.y()), maxZ = Math.max(start.z(), goal.z());
		for (Cell cell : blocks.keySet()) {
			minX = Math.min(minX, cell.x());
			minY = Math.min(minY, cell.y());
			minZ = Math.min(minZ, cell.z());
			maxX = Math.max(maxX, cell.x());
			maxY = Math.max(maxY, cell.y());
			maxZ = Math.max(maxZ, cell.z());
		}
		return new Box(new Cell(minX, minY, minZ), new Cell(maxX, maxY, maxZ));
	}

	Block blockAt(Cell cell) {
		return blocks.get(cell);
	}

	enum Kind {
		FIXTURE,
		TERRAIN
	}

	enum Expectation {
		ARRIVE,
		REFUSE
	}

	enum Block {
		STONE(true),
		DIRT(true),
		BARRIER(true),
		WATER(false),
		OAK_DOOR_LOWER(false),
		OAK_DOOR_UPPER(false);

		private final boolean supportsStanding;

		Block(boolean supportsStanding) {
			this.supportsStanding = supportsStanding;
		}

		boolean supportsStanding() {
			return supportsStanding;
		}
	}

	record Cell(int x, int y, int z) {
		Cell offset(int dx, int dy, int dz) {
			return new Cell(x + dx, y + dy, z + dz);
		}
	}

	/** Inclusive cell box. */
	record Box(Cell min, Cell max) {
		Box {
			Objects.requireNonNull(min, "min");
			Objects.requireNonNull(max, "max");
			if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
				throw new IllegalArgumentException("empty_box");
			}
		}

		boolean contains(Cell cell) {
			return cell.x() >= min.x() && cell.x() <= max.x()
				&& cell.y() >= min.y() && cell.y() <= max.y()
				&& cell.z() >= min.z() && cell.z() <= max.z();
		}

		int sizeX() {
			return max.x() - min.x() + 1;
		}

		int sizeY() {
			return max.y() - min.y() + 1;
		}

		int sizeZ() {
			return max.z() - min.z() + 1;
		}
	}
}
