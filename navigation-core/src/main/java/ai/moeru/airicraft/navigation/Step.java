package ai.moeru.airicraft.navigation;

import java.util.List;

/**
 * One move of a path with the terrain edits it needs, in execution order.
 *
 * @param breaks       cells to break before moving, top first
 * @param place        cell to place a block into, or null
 * @param placeAgainst the existing solid cell whose face supports the placement, or null
 * @param doors        door, gate or trapdoor cells to use once so the body can pass
 */
public record Step(MoveType type, GridPos from, GridPos to, double cost, List<GridPos> breaks, GridPos place,
	GridPos placeAgainst, List<GridPos> doors) {
	public Step {
		breaks = List.copyOf(breaks);
		doors = List.copyOf(doors);
		if ((place == null) != (placeAgainst == null)) throw new IllegalArgumentException("placement needs a support cell");
	}

	public boolean edits() {
		return !breaks.isEmpty() || place != null;
	}

	public int dx() { return to.x() - from.x(); }
	public int dy() { return to.y() - from.y(); }
	public int dz() { return to.z() - from.z(); }
}
