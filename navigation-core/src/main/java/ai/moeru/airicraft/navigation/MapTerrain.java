package ai.moeru.airicraft.navigation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Sparse terrain for tests and dry runs. Cells outside the loaded box are {@link CellInfo#UNLOADED}. */
public final class MapTerrain implements TerrainView {
	private final Map<Long, CellInfo> cells = new HashMap<>();
	private final CellInfo fill;
	private final Box loaded;

	public MapTerrain(CellInfo fill, Box loaded) {
		this.fill = Objects.requireNonNull(fill, "fill");
		this.loaded = Objects.requireNonNull(loaded, "loaded");
	}

	public MapTerrain set(int x, int y, int z, CellInfo cell) {
		cells.put(GridPos.key(x, y, z), Objects.requireNonNull(cell, "cell"));
		return this;
	}

	public Box loadedBox() {
		return loaded;
	}

	@Override
	public CellInfo cell(int x, int y, int z) {
		if (!loaded.contains(x, y, z)) return CellInfo.UNLOADED;
		return cells.getOrDefault(GridPos.key(x, y, z), fill);
	}
}
