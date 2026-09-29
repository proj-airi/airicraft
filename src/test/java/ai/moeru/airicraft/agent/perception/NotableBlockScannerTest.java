package ai.moeru.airicraft.agent.perception;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotableBlockScannerTest {
	/** A stone world with a few placed blocks; air where cleared. Sight is a straight-line walk through opaque cells. */
	private static final class World implements NotableBlockScanner.Blocks, NotableBlockScanner.Sight {
		final Map<String, String> blocks = new HashMap<>();
		final Set<String> air = new HashSet<>();
		int raycasts;

		void put(int x, int y, int z, String id) { blocks.put(x + "," + y + "," + z, id); }
		void clear(int x, int y, int z) { air.add(x + "," + y + "," + z); }

		@Override public String blockId(int x, int y, int z) {
			String key = x + "," + y + "," + z;
			if (air.contains(key)) return null;
			return blocks.getOrDefault(key, "minecraft:stone");
		}

		@Override public boolean opaque(int x, int y, int z) {
			String key = x + "," + y + "," + z;
			return !air.contains(key) && !"minecraft:glass".equals(blocks.get(key));
		}

		@Override public boolean reaches(double eyeX, double eyeY, double eyeZ, int x, int y, int z, NotableBlockScanner.Face face) {
			raycasts++;
			double tx = x + .5 + face.dx * .49, ty = y + .5 + face.dy * .49, tz = z + .5 + face.dz * .49;
			for (double t = 0; t < 1; t += .01) {
				int cx = (int) Math.floor(eyeX + (tx - eyeX) * t), cy = (int) Math.floor(eyeY + (ty - eyeY) * t), cz = (int) Math.floor(eyeZ + (tz - eyeZ) * t);
				if (cx == x && cy == y && cz == z) return true;
				if (opaque(cx, cy, cz)) return false;
			}
			return true;
		}
	}

	/** An air corridor along x from 0 to 10 at y 0..1, eyes at (0.5, 1.6, 0.5). */
	private static World corridor() {
		var world = new World();
		for (int x = -1; x <= 10; x++) for (int y = 0; y <= 1; y++) world.clear(x, y, 0);
		return world;
	}

	private static List<PerceptCandidate> scanAll(NotableBlockScanner scanner, World world, long tick) {
		return scanner.scan(tick, "w|minecraft:overworld", .5, 1.6, .5, 12, 100_000, 10_000,
			id -> id.endsWith("_ore"), world, world);
	}

	@Test void anExposedOreInSightIsNoticedOnceAndAnEnclosedOneNever() {
		var world = corridor();
		world.put(5, 0, 1, "minecraft:diamond_ore");   // wall of the corridor, face NORTH exposed
		world.put(5, 0, 3, "minecraft:emerald_ore");   // inside the rock: every neighbour is stone
		var scanner = new NotableBlockScanner();
		var first = scanAll(scanner, world, 1);
		assertEquals(1, first.size(), first.toString());
		var candidate = first.getFirst();
		assertEquals("block", candidate.kind());
		assertEquals("minecraft:diamond_ore", candidate.fields().get("blockId"));
		assertEquals(List.of("north"), candidate.fields().get("exposedFaces"));
		assertEquals("minecraft:overworld", candidate.fields().get("dimension"));
		assertEquals("east", candidate.fields().get("direction"));
		assertTrue(scanAll(scanner, world, 2).isEmpty(), "remembered");
		assertEquals(1, scanAll(scanner, world, 2 + NotableBlockScanner.TTL_TICKS).size(), "noticed again after the TTL");
	}

	@Test void anExposedOreBehindAWallIsNotNoticedUntilItComesIntoView() {
		var world = new World();
		world.clear(0, 0, 0); world.clear(0, 1, 0);          // the player's cell
		for (int x = 4; x <= 6; x++) world.clear(x, 0, 0);   // a sealed pocket
		world.put(5, 0, 1, "minecraft:diamond_ore");
		var scanner = new NotableBlockScanner();
		assertTrue(scanAll(scanner, world, 1).isEmpty(), "exposed to a pocket the player cannot see into");
		for (int x = 1; x <= 3; x++) { world.clear(x, 0, 0); world.clear(x, 1, 0); }
		assertEquals(1, scanAll(scanner, world, 2).size(), "a hidden block is rescanned and noticed once visible");
	}

	@Test void glassCountsAsTransparentForExposure() {
		var world = corridor();
		world.put(3, 0, 1, "minecraft:glass");
		world.put(3, 0, 2, "minecraft:diamond_ore");
		// The ore's north face touches glass; the corridor side of the glass is air, so a ray can reach it.
		var scanner = new NotableBlockScanner();
		// Stand right in front of the window, so the ray passes through the glass.
		var found = scanner.scan(1, "w|o", 3.5, .6, .5, 12, 100_000, 10_000, id -> id.endsWith("_ore"), world, world);
		assertEquals(1, found.size());
		assertTrue(((List<?>) found.getFirst().fields().get("exposedFaces")).contains("north"));
	}

	@Test void budgetsBoundWorkAndTheScanResumes() {
		var world = corridor();
		world.put(5, 0, 1, "minecraft:diamond_ore");
		var scanner = new NotableBlockScanner();
		int ticks = 0;
		List<PerceptCandidate> found = List.of();
		while (found.isEmpty() && ticks < 1000) {
			world.raycasts = 0;
			found = scanner.scan(++ticks, "w|o", .5, 1.6, .5, 12, 64, 1, id -> id.endsWith("_ore"), world, world);
			assertTrue(world.raycasts <= 1, "at most R raycasts per tick");
		}
		assertEquals(1, found.size());
		assertTrue(ticks > 1, "64 positions per tick cannot cover the sphere at once");
	}

	@Test void facesOnlyCountFromTheSideTheyPointTo() {
		assertTrue(NotableBlockScanner.facesEye(NotableBlockScanner.Face.NORTH, 5, 0, 1, .5, 1.6, .5));
		assertFalse(NotableBlockScanner.facesEye(NotableBlockScanner.Face.SOUTH, 5, 0, 1, .5, 1.6, .5));
		assertEquals(0, NotableBlockScanner.sphere(0)[0]);
		assertEquals(7 * 3, NotableBlockScanner.sphere(1).length);
	}
}
