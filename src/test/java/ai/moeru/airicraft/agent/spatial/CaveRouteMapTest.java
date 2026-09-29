package ai.moeru.airicraft.agent.spatial;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static ai.moeru.airicraft.agent.spatial.CaveRouteMap.*;
import static org.junit.jupiter.api.Assertions.*;

class CaveRouteMapTest {
	private static Grid flat(Set<BlockPos> floor) {
		return new Grid(new BlockPos(0, 1, 0), 20, 5, p -> {
			if (!floor.contains(new BlockPos(p.getX(), 0, p.getZ()))) return Cell.BLOCKED;
			return p.getY() == 0 ? Cell.SUPPORT : p.getY() > 0 && p.getY() < 4 ? Cell.OPEN : Cell.BLOCKED;
		});
	}
	private static void rectangle(Set<BlockPos> set, int x1, int x2, int z1, int z2) {
		for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) set.add(new BlockPos(x,0,z));
	}
	@Test void connectedAirAcrossARavineDoesNotInventAWalkableCrossing() {
		var g = new Grid(new BlockPos(-3,1,0), 12, 8, p -> {
			if (p.getY() == 0 && (p.getX() < 0 || p.getX() > 3)) return Cell.SUPPORT;
			return p.getY() >= 0 ? Cell.OPEN : Cell.BLOCKED;
		});
		var result = map(g, new BlockPos(-3,1,0), new BlockPos(5,1,0), 1);
		assertTrue(result.airCells() > result.floorCells());
		assertEquals("target_not_reachable", result.status());
	}
	@Test void unknownCellsAreBoundariesRatherThanAir() {
		var g = new Grid(new BlockPos(0,1,0), 10, 4, p -> {
			if (p.getX() >= 3) return Cell.UNKNOWN;
			return p.getY() == 0 ? Cell.SUPPORT : p.getY() > 0 ? Cell.OPEN : Cell.BLOCKED;
		});
		assertEquals("target_not_reachable", map(g, new BlockPos(0,1,0), new BlockPos(4,1,0), 1).status());
	}
	@Test void canStepUpButCannotClimbATwoBlockWallOrFitUnderLowCeilings() {
		for (int height : List.of(1, 2)) {
			var g = new Grid(new BlockPos(0,1,0), 8, 6, p -> {
				if (p.getZ() != 0) return Cell.BLOCKED;
				int floor = p.getX() >= 2 ? height : 0;
				return p.getY() == floor ? Cell.SUPPORT : p.getY() > floor && p.getY() <= 5 ? Cell.OPEN : Cell.BLOCKED;
			});
			assertEquals(height == 1 ? "mapped" : "target_not_reachable", map(g, new BlockPos(0,1,0), new BlockPos(5,height+1,0), 1).status());
		}
		var low = new Grid(new BlockPos(0,1,0), 8, 4, p -> p.getY() == 0 ? Cell.SUPPORT : p.getY() == 1 ? Cell.OPEN : Cell.BLOCKED);
		assertEquals("origin_not_standable", map(low, new BlockPos(0,1,0), null, 1).status());
	}
	@Test void oneCellGapNeedsJumpHeadroomAndAvoidsHazards() {
		for (Cell gap : List.of(Cell.WATER, Cell.HAZARD)) {
			var g = new Grid(new BlockPos(0,1,0), 8, 4, p -> {
				if (p.getZ() != 0) return Cell.BLOCKED;
				if (p.getY() == 0) return p.getX() == 2 ? gap : Cell.SUPPORT;
				return p.getY() > 0 && p.getY() <= 3 ? Cell.OPEN : Cell.BLOCKED;
			});
			assertEquals(gap == Cell.WATER ? "mapped" : "target_not_reachable", map(g, new BlockPos(0,1,0), new BlockPos(5,1,0), 1).status());
		}
	}
	@Test void opennessPrefersABroadAlternativeWithoutUnboundedDetours() {
		Set<BlockPos> floors = new HashSet<>();
		rectangle(floors, -8, 8, 0, 0); // short, one-wide tunnel
		rectangle(floors, -8, -6, 0, 5); rectangle(floors, 6, 8, 0, 5);
		rectangle(floors, -8, 8, 3, 7); // longer open chamber route
		Grid grid = flat(floors);
		BlockPos from = new BlockPos(-8,1,0), to = new BlockPos(8,1,0);
		var shortest = map(grid, from, to, 0);
		var wider = map(grid, from, to, 2);
		assertTrue(shortest.route().stream().allMatch(p -> p.getZ() == 0));
		assertTrue(wider.route().stream().anyMatch(p -> p.getZ() >= 3), "Expected the moderate detour through broad floor space: " + wider.route());
		assertTrue(wider.route().size() <= shortest.route().size() * 2);
	}
	@Test void compressedWaypointsKeepTurnsAndHeightChanges() {
		List<BlockPos> path = List.of(new BlockPos(0,1,0), new BlockPos(1,1,0), new BlockPos(2,1,0),
			new BlockPos(2,1,1), new BlockPos(2,2,2), new BlockPos(2,2,3));
		assertEquals(List.of(path.get(2), path.get(3), path.get(4), path.get(5)), waypoints(path));
	}
	@Test void floodIsBoundedAndReportsTruncation() {
		var g = new Grid(new BlockPos(0,1,0), 32, 24, p -> p.getY() == 0 ? Cell.SUPPORT : p.getY() > 0 ? Cell.OPEN : Cell.BLOCKED);
		var r = map(g, new BlockPos(0,1,0), new BlockPos(5,1,0), 1);
		assertTrue(r.truncated()); assertEquals(32768, r.airCells()); assertEquals("mapped", r.status());
	}
	@Test void continuousStairsDoNotRequireACommandForEveryStep() {
		List<BlockPos> path = new ArrayList<>();
		for (int i = 0; i <= 7; i++) path.add(new BlockPos(i, i + 1, 0));
		assertEquals(List.of(path.get(4), path.get(7)), waypoints(path));
	}
	@Test void ordinaryExplorationDoesNotDropIntoAnUnclimbablePocket() {
		var g = new Grid(new BlockPos(0,3,0), 8, 5, p -> {
			if (p.getZ() != 0) return Cell.BLOCKED;
			int floor = p.getX() < 2 ? 2 : 0;
			return p.getY() == floor ? Cell.SUPPORT : p.getY() > floor && p.getY() <= 5 ? Cell.OPEN : Cell.BLOCKED;
		});
		assertEquals("target_not_reachable", map(g, new BlockPos(0,3,0), new BlockPos(5,1,0), 1).status());
	}
	@Test void descendingStairsRetainTheirLandings() {
		List<BlockPos> path = new ArrayList<>();
		for (int i = 0; i <= 7; i++) path.add(new BlockPos(i, 8 - i, 0));
		assertEquals(path.subList(1, path.size()), waypoints(path));
	}
}
