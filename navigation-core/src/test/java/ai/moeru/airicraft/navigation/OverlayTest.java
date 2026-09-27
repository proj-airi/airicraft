package ai.moeru.airicraft.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class OverlayTest {
	@Test
	void carriesEveryEarlierEditAndShowsTheLatestStateOfACell() {
		Moves.Overlay first = extend(null, new int[]{0, 0, 0}, Moves.Overlay.PLACED);
		Moves.Overlay second = extend(first, new int[]{5, 1, 0}, CellInfo.AIR);
		Moves.Overlay third = extend(second, new int[]{0, 0, 0}, CellInfo.AIR);

		assertSame(Moves.Overlay.PLACED, second.cell(0, 0, 0), "a grandchild still sees the first placement");
		assertSame(CellInfo.AIR, second.cell(5, 1, 0));
		assertSame(CellInfo.AIR, third.cell(0, 0, 0), "breaking the placed block wins");
		assertEquals(2, third.size());
		assertNull(third.cell(9, 9, 9));
	}

	@Test
	void keepsOnlyTheNewestCells() {
		Moves.Overlay overlay = null;
		for (int i = 0; i < Moves.Overlay.MAX_CELLS + 10; i++) overlay = extend(overlay, new int[]{i, 0, 0}, CellInfo.AIR);

		assertEquals(Moves.Overlay.MAX_CELLS, overlay.size());
		assertSame(CellInfo.AIR, overlay.cell(Moves.Overlay.MAX_CELLS + 9, 0, 0));
		assertNull(overlay.cell(0, 0, 0));
	}

	@Test
	void aMoveWithoutEditsPassesItsParentsOverlayOn() {
		MapTerrain terrain = PathSearchTest.flatField(8);
		Moves moves = new Moves(terrain, MovementPolicy.defaults());
		Moves.Result result = new Moves.Result();
		int kind = moves.probe(1, 1, 1);
		moves.evaluate(0, 1, 1, 1, kind, moves.probeBase(), result);
		Moves.Overlay parent = extend(null, new int[]{3, 0, 3}, CellInfo.AIR);

		assertSame(parent, result.overlay(parent));
	}

	private static Moves.Overlay extend(Moves.Overlay parent, int[] cell, CellInfo info) {
		return Moves.Overlay.extend(parent, new int[]{cell[0]}, new int[]{cell[1]}, new int[]{cell[2]}, new CellInfo[]{info}, 1);
	}
}
