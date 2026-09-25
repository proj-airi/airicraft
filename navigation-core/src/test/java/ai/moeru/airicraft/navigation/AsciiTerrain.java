package ai.moeru.airicraft.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Terrain drawn as text. Layers are listed bottom first and start with a line {@code y<n>}; each
 * row is one z (growing south), each character one x (growing east). Cells outside the drawn box,
 * plus {@link #AIR_ABOVE} air layers over it, are unloaded.
 */
final class AsciiTerrain {
	static final int AIR_ABOVE = 4;
	static final CellInfo STONE = CellInfo.builder("stone").full().breakTicks(30).build();
	static final CellInfo DIRT = CellInfo.builder("dirt").full().breakTicks(15).build();
	static final CellInfo BEDROCK = CellInfo.builder("bedrock").full().build();
	static final CellInfo SAND = CellInfo.builder("sand").full().breakTicks(10).falling().build();
	static final CellInfo SLAB = CellInfo.builder("slab").pixels(0, 0, 0, 16, 8, 16).breakTicks(30).build();
	static final CellInfo UPPER_SLAB = CellInfo.builder("upper_slab").pixels(0, 8, 0, 16, 16, 16).breakTicks(30).build();
	static final CellInfo STAIRS = CellInfo.builder("stairs").pixels(0, 0, 0, 16, 8, 16).pixels(0, 8, 0, 16, 16, 8)
		.breakTicks(30).build();
	static final CellInfo FENCE = CellInfo.builder("fence").pixels(6, 0, 6, 10, 24, 10).breakTicks(20).build();
	static final CellInfo GRASS = CellInfo.builder("short_grass").replaceable().breakTicks(0).build();
	static final CellInfo MAGMA = CellInfo.builder("magma").full().hazards(CellInfo.HAZARD_FLOOR).breakTicks(10).build();
	static final CellInfo COBWEB = CellInfo.builder("cobweb").hazards(CellInfo.HAZARD_BODY).breakTicks(20).build();
	static final CellInfo LADDER = CellInfo.builder("ladder").pixels(0, 0, 13, 16, 16, 16).climbable().breakTicks(5).build();
	static final CellInfo DOOR_OPEN = CellInfo.builder("oak_door_open").pixels(0, 0, 0, 3, 16, 16)
		.openable(CellInfo.Openable.DOOR, null).breakTicks(20).build();
	/** A closed oak door with its panel on the north edge; using it swings the panel to the west edge. */
	static final CellInfo DOOR = CellInfo.builder("oak_door").pixels(0, 0, 0, 16, 16, 3)
		.openable(CellInfo.Openable.DOOR, DOOR_OPEN).breakTicks(20).build();
	static final CellInfo IRON_DOOR = CellInfo.builder("iron_door").pixels(0, 0, 0, 16, 16, 3).build();

	static final Map<Character, CellInfo> LEGEND = Map.ofEntries(
		Map.entry('.', CellInfo.AIR), Map.entry('S', CellInfo.AIR), Map.entry('G', CellInfo.AIR),
		Map.entry('#', STONE), Map.entry('d', DIRT), Map.entry('B', BEDROCK), Map.entry('n', SAND),
		Map.entry('_', SLAB), Map.entry('^', UPPER_SLAB), Map.entry('s', STAIRS), Map.entry('F', FENCE),
		Map.entry('g', GRASS), Map.entry('m', MAGMA), Map.entry('c', COBWEB), Map.entry('H', LADDER),
		Map.entry('D', DOOR), Map.entry('I', IRON_DOOR), Map.entry('w', CellInfo.WATER), Map.entry('l', CellInfo.LAVA)
	);

	final MapTerrain terrain;
	final GridPos start;
	final GridPos goal;

	private AsciiTerrain(MapTerrain terrain, GridPos start, GridPos goal) {
		this.terrain = terrain;
		this.start = start;
		this.goal = goal;
	}

	static AsciiTerrain parse(String text) {
		List<List<String>> layers = new ArrayList<>();
		for (String raw : text.strip().split("\n")) {
			String line = raw.strip();
			if (line.isEmpty()) continue;
			if (line.matches("y\\d+")) {
				if (Integer.parseInt(line.substring(1)) != layers.size()) throw new IllegalArgumentException("layers out of order: " + line);
				layers.add(new ArrayList<>());
				continue;
			}
			if (layers.isEmpty()) throw new IllegalArgumentException("row before a layer header");
			layers.getLast().add(line);
		}
		int depth = layers.getFirst().size(), width = layers.getFirst().getFirst().length();
		MapTerrain terrain = new MapTerrain(CellInfo.AIR, new Box(0, 0, 0, width - 1, layers.size() - 1 + AIR_ABOVE, depth - 1));
		GridPos start = null, goal = null;
		for (int y = 0; y < layers.size(); y++) {
			List<String> rows = layers.get(y);
			if (rows.size() != depth) throw new IllegalArgumentException("layer y" + y + " has " + rows.size() + " rows");
			for (int z = 0; z < depth; z++) {
				String row = rows.get(z);
				if (row.length() != width) throw new IllegalArgumentException("row " + z + " of y" + y + " is not " + width + " wide");
				for (int x = 0; x < width; x++) {
					char symbol = row.charAt(x);
					CellInfo cell = LEGEND.get(symbol);
					if (cell == null) throw new IllegalArgumentException("unknown cell '" + symbol + "'");
					terrain.set(x, y, z, cell);
					if (symbol == 'S') start = new GridPos(x, y, z);
					if (symbol == 'G') goal = new GridPos(x, y, z);
				}
			}
		}
		return new AsciiTerrain(terrain, start, goal);
	}

	SearchResult search(MovementPolicy policy) {
		return search(policy, new Goal.Block(goal.x(), goal.y(), goal.z()));
	}

	SearchResult search(MovementPolicy policy, Goal target) {
		return PathSearch.search(terrain, policy, start, target, SearchBudget.defaults(), () -> false);
	}
}
