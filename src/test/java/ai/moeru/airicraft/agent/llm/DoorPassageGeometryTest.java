package ai.moeru.airicraft.agent.llm;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DoorPassageGeometryTest {
	private static final List<AABB> ACROSS_X = List.of(new AABB(0, 0, 0, 0.1875, 2, 1));
	private static final List<AABB> ACROSS_Z = List.of(new AABB(0, 0, 0, 1, 2, 0.1875));
	private static final List<AABB> EAST_WEST_WALLS = List.of(
		new AABB(0, 0, -1, 1, 2, 0), new AABB(0, 0, 1, 1, 2, 2));

	@Test
	void ordinaryDoorBlocksPassageUntilItsPanelRotates() {
		var result = DoorPassageGeometry.assess(ACROSS_X, ACROSS_Z, EAST_WEST_WALLS);
		assertEquals("east_west", result.inferredPassage());
		assertTrue(result.eastWest().doorBlocksNow());
		assertFalse(result.eastWest().doorBlocksAfterToggle());
		assertTrue(result.eastWest().description().contains("toClear=toggle_door"));
	}

	@Test
	void rotatedPlacementCanAlreadyAllowPassageAndTogglingWouldBlockIt() {
		// A nominally closed door placed across the wall instead of the opening.
		var result = DoorPassageGeometry.assess(ACROSS_Z, ACROSS_X, EAST_WEST_WALLS);
		assertEquals("east_west", result.inferredPassage());
		assertFalse(result.eastWest().doorBlocksNow());
		assertTrue(result.eastWest().doorBlocksAfterToggle());
		assertTrue(result.eastWest().description().contains("toClear=leave_as_is"));
	}

	@Test
	void northSouthPassageIsInferredFromSideWalls() {
		var result = DoorPassageGeometry.assess(ACROSS_Z, ACROSS_X,
			List.of(new AABB(-1, 0, 0, 0, 2, 1), new AABB(1, 0, 0, 2, 2, 1)));
		assertEquals("north_south", result.inferredPassage());
		assertTrue(result.northSouth().doorBlocksNow());
		assertFalse(result.northSouth().doorBlocksAfterToggle());
	}

	@Test
	void openAreaDoesNotInferPassageFromDoorOrientation() {
		var result = DoorPassageGeometry.assess(ACROSS_X, ACROSS_Z, List.of());
		assertEquals("ambiguous", result.inferredPassage());
		assertTrue(result.eastWest().doorBlocksNow());
		assertFalse(result.northSouth().doorBlocksNow());
	}

	@Test
	void surroundingObstructionIsNotReportedAsFixableByDoorToggle() {
		var result = DoorPassageGeometry.assess(ACROSS_X, ACROSS_Z,
			List.of(new AABB(-1, 0, 0, 0, 2, 1), new AABB(0, 0, -1, 1, 2, 0)));
		assertEquals("ambiguous", result.inferredPassage());
		assertTrue(result.eastWest().description().contains("toClear=inspect_surrounding_obstruction"));
	}

	@Test
	void floorTouchIsClearButFenceProtrudingFromBelowBlocksPassage() {
		var floor = new AABB(-1, -1, -1, 2, 0, 2);
		assertTrue(DoorPassageGeometry.assess(ACROSS_Z, ACROSS_X, List.of(floor)).eastWest().surroundingsClear());
		var fenceBelow = new AABB(-0.625, -1, 0.375, -0.375, 0.5, 0.625);
		assertFalse(DoorPassageGeometry.assess(ACROSS_Z, ACROSS_X, List.of(floor, fenceBelow)).eastWest().surroundingsClear());
	}

	@Test
	void upperHalfObstructionStillBlocksStandingPlayer() {
		var result = DoorPassageGeometry.assess(List.of(new AABB(0, 1, 0, 0.1875, 2, 1)),
			List.of(), EAST_WEST_WALLS);
		assertTrue(result.eastWest().doorBlocksNow());
	}
}
