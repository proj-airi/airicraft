package ai.moeru.airicraft.portal;

import ai.moeru.airicraft.blueprint.ConstructionEscape;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetherPortalFrameTest {
	private static ConstructionEscape.Position p(int x, int y, int z) { return new ConstructionEscape.Position(x, y, z); }

	@Test void minimalFrameHasTenObsidianFourCornersAndSixOpeningCells() {
		var frame = new NetherPortalFrame(10, 64, -5, NetherPortalFrame.Axis.Z);
		assertEquals(10, frame.positions(NetherPortalFrame.Role.OBSIDIAN).size());
		assertEquals(4, frame.positions(NetherPortalFrame.Role.CORNER).size());
		assertEquals(6, frame.positions(NetherPortalFrame.Role.INTERIOR).size());
		assertTrue(frame.positions(NetherPortalFrame.Role.OBSIDIAN).containsAll(List.of(p(10, 68, -4), p(10, 68, -3), p(10, 64, -4), p(10, 66, -5))));
		assertTrue(frame.positions(NetherPortalFrame.Role.INTERIOR).containsAll(List.of(p(10, 65, -4), p(10, 67, -3))));
		assertEquals(p(10, 65, -4), frame.ignitionCell());
		assertTrue(frame.slots().stream().allMatch(s -> s.position().x() == 10));
	}

	@Test void constructionCellsKeepExistingCornersAndPlanAirInTheOpening() {
		var frame = new NetherPortalFrame(0, 64, 0, NetherPortalFrame.Axis.X);
		var cells = frame.constructionCells("minecraft:cobblestone", Map.of(p(0, 64, 0), "minecraft:grass_block[snowy=false]"));
		assertEquals(20, cells.size());
		var kept = cells.stream().filter(c -> c.x() == 0 && c.y() == 64).findFirst().orElseThrow();
		assertEquals("minecraft:grass_block[snowy=false]", kept.state());
		assertEquals("minecraft:grass_block", kept.item());
		assertEquals("minecraft:cobblestone", cells.stream().filter(c -> c.x() == 3 && c.y() == 68).findFirst().orElseThrow().state());
		assertEquals(6, cells.stream().filter(c -> c.state().equals("minecraft:air")).count());
	}

	/** Solid ground below y=64 except where holes are; air everywhere above. */
	private static NetherPortalFrame.Terrain flat(Set<ConstructionEscape.Position> obstacles, Set<ConstructionEscape.Position> holes) {
		return new NetherPortalFrame.Terrain() {
			@Override public boolean solidGround(ConstructionEscape.Position position) { return position.y() < 64 && !holes.contains(position) || obstacles.contains(position); }
			@Override public boolean clear(ConstructionEscape.Position position) { return position.y() >= 64 && !obstacles.contains(position); }
		};
	}

	@Test void findsTheNearestFlatSiteAwayFromThePlayer() {
		var feet = p(0, 64, 0);
		var site = NetherPortalFrame.findSite(feet, 8, flat(Set.of(), Set.of())).orElseThrow();
		assertEquals(64, site.y());
		for (var slot : site.slots()) assertFalse(slot.position().x() == 0 && slot.position().z() == 0, "frame overlaps the player: " + site);
		assertTrue(site.lightingStance(flat(Set.of(), Set.of())).isPresent());
	}

	@Test void skipsSitesWithObstaclesOrMissingGround() {
		var feet = p(0, 64, 0);
		var obstacles = new HashSet<ConstructionEscape.Position>();
		var holes = new HashSet<ConstructionEscape.Position>();
		for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
			if (Math.abs(x) <= 6 && Math.abs(z) <= 6 && (x + z) % 2 == 0) obstacles.add(p(x, 66, z));
			if (z < 0) holes.add(p(x, 63, z));
		}
		var terrain = flat(obstacles, holes);
		var site = NetherPortalFrame.findSite(feet, 12, terrain).orElseThrow();
		for (var slot : site.slots()) {
			assertTrue(terrain.clear(slot.position()), slot.toString());
			assertTrue(slot.position().z() >= 0);
		}
	}

	@Test void noSiteInsideATunnel() {
		var terrain = new NetherPortalFrame.Terrain() {
			@Override public boolean solidGround(ConstructionEscape.Position position) { return true; }
			@Override public boolean clear(ConstructionEscape.Position position) { return position.z() == 0 && position.y() >= 64 && position.y() <= 65; }
		};
		assertTrue(NetherPortalFrame.findSite(p(0, 64, 0), 8, terrain).isEmpty());
	}
}
