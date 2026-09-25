package ai.moeru.airicraft.navigation;

/**
 * Read-only cell lookup. Implementations handed to search must not change while it runs; unknown
 * cells report {@link CellInfo#UNLOADED}.
 */
@FunctionalInterface
public interface TerrainView {
	CellInfo cell(int x, int y, int z);

	default CellInfo cell(GridPos pos) {
		return cell(pos.x(), pos.y(), pos.z());
	}
}
