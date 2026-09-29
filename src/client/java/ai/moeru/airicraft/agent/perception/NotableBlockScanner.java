package ai.moeru.airicraft.agent.perception;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The honest half of block noticing (spec section 5). Each tick it scans the next {@code positions} offsets of a
 * sphere around the eyes, nearest first, and considers only blocks the salience module declared interest in. Such
 * a block is a candidate only if at least one face is exposed to a non-opaque neighbour <em>and</em> a raycast from
 * the eyes reaches that face, so a fully enclosed block never is: no X-ray. A noticed block is remembered, and a
 * hidden one is rescanned later, since it may come into view.
 */
public final class NotableBlockScanner {
	public static final long TTL_TICKS = 12_000L;

	/** Block ids and opacity at integer positions. */
	public interface Blocks {
		String blockId(int x, int y, int z);

		boolean opaque(int x, int y, int z);
	}

	/** Whether a ray from the eyes reaches {@code face} of the block at x, y, z without hitting anything else. */
	public interface Sight {
		boolean reaches(double eyeX, double eyeY, double eyeZ, int x, int y, int z, Face face);
	}

	public enum Face {
		DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

		public final int dx, dy, dz;

		Face(int dx, int dy, int dz) {
			this.dx = dx;
			this.dy = dy;
			this.dz = dz;
		}
	}

	private final NoticedMemory memory = new NoticedMemory();
	private int[] offsets = new int[0];
	private int radius = -1;
	private int cursor;

	/** Scans within budget and returns the blocks newly noticed this tick. */
	public List<PerceptCandidate> scan(long tick, String scope, double eyeX, double eyeY, double eyeZ, int radius,
		int positions, int raycasts, Predicate<String> interesting, Blocks blocks, Sight sight) {
		memory.scope(scope);
		if (radius != this.radius) {
			offsets = sphere(radius);
			this.radius = radius;
			cursor = 0;
		}
		var candidates = new ArrayList<PerceptCandidate>();
		if (offsets.length == 0) return candidates;
		int baseX = (int) Math.floor(eyeX), baseY = (int) Math.floor(eyeY), baseZ = (int) Math.floor(eyeZ);
		int count = offsets.length / 3;
		int budget = raycasts;
		for (int scanned = 0; scanned < Math.min(positions, count); scanned++) {
			int x = baseX + offsets[cursor * 3], y = baseY + offsets[cursor * 3 + 1], z = baseZ + offsets[cursor * 3 + 2];
			String blockId = blocks.blockId(x, y, z);
			if (blockId != null && interesting.test(blockId)) {
				String key = "block:" + blockId + "@" + x + "," + y + "," + z;
				if (!memory.noticed(key, tick)) {
					var exposed = new ArrayList<Face>();
					for (Face face : Face.values()) if (!blocks.opaque(x + face.dx, y + face.dy, z + face.dz)) exposed.add(face);
					if (!exposed.isEmpty()) {
						boolean seen = false;
						for (Face face : exposed) {
							if (!facesEye(face, x, y, z, eyeX, eyeY, eyeZ)) continue;
							// Out of raycasts: resume this position next tick instead of skipping it.
							if (budget == 0) return candidates;
							budget--;
							if (sight.reaches(eyeX, eyeY, eyeZ, x, y, z, face)) {
								seen = true;
								break;
							}
						}
						if (seen) {
							memory.notice(key, tick, TTL_TICKS);
							candidates.add(candidate(key, blockId, x, y, z, exposed, scope, eyeX, eyeY, eyeZ));
						}
					}
				}
			}
			cursor = (cursor + 1) % count;
		}
		return candidates;
	}

	public void clear() {
		memory.clear();
		cursor = 0;
	}

	/** A face can only be seen from the side it points to. */
	static boolean facesEye(Face face, int x, int y, int z, double eyeX, double eyeY, double eyeZ) {
		double cx = x + .5 + face.dx * .5, cy = y + .5 + face.dy * .5, cz = z + .5 + face.dz * .5;
		return (eyeX - cx) * face.dx + (eyeY - cy) * face.dy + (eyeZ - cz) * face.dz > 0;
	}

	private static PerceptCandidate candidate(String key, String blockId, int x, int y, int z, List<Face> exposed, String scope,
		double eyeX, double eyeY, double eyeZ) {
		var fields = new LinkedHashMap<String, Object>();
		fields.put("blockId", blockId);
		fields.put("x", x);
		fields.put("y", y);
		fields.put("z", z);
		double dx = x + .5 - eyeX, dy = y + .5 - eyeY, dz = z + .5 - eyeZ;
		fields.put("distance", Compass.round(Math.sqrt(dx * dx + dy * dy + dz * dz)));
		fields.put("direction", Compass.direction(dx, dz));
		fields.put("exposedFaces", exposed.stream().map(face -> face.name().toLowerCase(Locale.ROOT)).toList());
		fields.put("dimension", dimension(scope));
		return new PerceptCandidate(key, "block", fields);
	}

	static String dimension(String scope) {
		int split = scope == null ? -1 : scope.lastIndexOf('|');
		return split < 0 ? scope : scope.substring(split + 1);
	}

	/** Integer offsets within {@code radius}, nearest first (ties in a fixed order), as x, y, z triples. */
	static int[] sphere(int radius) {
		var cells = new ArrayList<int[]>();
		for (int x = -radius; x <= radius; x++) {
			for (int y = -radius; y <= radius; y++) {
				for (int z = -radius; z <= radius; z++) {
					int squared = x * x + y * y + z * z;
					if (squared <= radius * radius) cells.add(new int[] {x, y, z, squared});
				}
			}
		}
		cells.sort((a, b) -> a[3] != b[3] ? Integer.compare(a[3], b[3]) : Arrays.compare(a, 0, 3, b, 0, 3));
		int[] flat = new int[cells.size() * 3];
		for (int index = 0; index < cells.size(); index++) System.arraycopy(cells.get(index), 0, flat, index * 3, 3);
		return flat;
	}
}
