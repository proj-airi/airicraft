package ai.moeru.airicraft.agent.tasks;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What explore_for has seen of one world dimension, kept across calls so a second call (after a night, a fight or a
 * failure) does not walk the same ground again: the points the body walked, and for every chunk that was loaded at some
 * point, what its top surface looked like. The surface is what a player sees looking across the land (sand, grass,
 * tree tops, water), not what is under it.
 */
final class ExplorationMemory {
	static final int MAX_VISITED = 8_192;
	static final int MAX_CHUNKS = 65_536;
	/** Chunks closer than this to the body are already covered by the feature search. */
	static final double LURE_NEAR_BLOCKS = 24.0D;
	/** How wide around a heading the surface counts toward it. */
	static final double LURE_HALF_ANGLE_COS = Math.cos(Math.toRadians(35.0D));
	/** Distance at which a chunk's surface counts half as much as one next to the body. */
	static final double LURE_FALLOFF_BLOCKS = 128.0D;

	/** One chunk's surface: how many sampled columns were topped by each kind of block. */
	record Surface(int samples, int trees, int grass, int sand, int water) {
		double fraction(int count) { return samples <= 0 ? 0.0D : (double) count / samples; }
	}

	private String worldKey;
	private final List<Vec3> visited = new ArrayList<>();
	private final Map<Long, Surface> surfaces = new HashMap<>();
	/** The last roofed shelter pit, until the body climbs out of it; a later call at dawn starts by climbing out. */
	private ExploreShelter.Plan shelter;
	/** The day whose night was already handed back to the planner, so a call that night waits in the pit instead. */
	private long handedBackDay = Long.MIN_VALUE;

	/** Forgets everything when the body is in another world or dimension than before. */
	void enter(String key) {
		if (Objects.equals(worldKey, key)) return;
		worldKey = key;
		visited.clear();
		surfaces.clear();
		shelter = null;
		handedBackDay = Long.MIN_VALUE;
	}

	ExploreShelter.Plan shelter() { return shelter; }

	void shelter(ExploreShelter.Plan pit) { shelter = pit; }

	boolean handedBack(long day) { return handedBackDay == day; }

	void handBack(long day) { handedBackDay = day; }

	void visit(Vec3 feet) {
		visited.add(feet);
		if (visited.size() > MAX_VISITED) visited.subList(0, visited.size() - MAX_VISITED).clear();
	}

	List<Vec3> visited() { return visited; }

	boolean sampled(int chunkX, int chunkZ) { return surfaces.containsKey(key(chunkX, chunkZ)); }

	void record(int chunkX, int chunkZ, Surface surface) {
		if (surfaces.size() >= MAX_CHUNKS && !surfaces.containsKey(key(chunkX, chunkZ))) return;
		surfaces.put(key(chunkX, chunkZ), surface);
	}

	int chunks() { return surfaces.size(); }

	/** Whether any sampled chunk within {@code radius} blocks shows the feature, so the full search can find it. */
	boolean shows(String featureKind, Vec3 feet, double radius) {
		for (var entry : surfaces.entrySet()) {
			if (signal(featureKind, entry.getValue()) <= 0.0D) continue;
			if (horizontal(feet, centerX(entry.getKey()), centerZ(entry.getKey())) <= radius + 12.0D) return true;
		}
		return false;
	}

	/**
	 * How strongly the land seen around {@code heading} (radians, 0 = +x, pi/2 = +z) from {@code feet} looks like the
	 * feature: the weighted mean signal of the sampled chunks inside the heading's cone, nearer chunks counting more, or 0
	 * where the surface says nothing about the feature (lava, fortresses, portals) or nothing was seen that way.
	 */
	double lure(String featureKind, Vec3 feet, double heading) {
		if (!surfaceTells(featureKind)) return 0.0D;
		double dx = Math.cos(heading), dz = Math.sin(heading);
		double sum = 0.0D, weights = 0.0D;
		for (var entry : surfaces.entrySet()) {
			double vx = centerX(entry.getKey()) - feet.x, vz = centerZ(entry.getKey()) - feet.z;
			double distance = Math.hypot(vx, vz);
			if (distance < LURE_NEAR_BLOCKS) continue;
			double cos = (vx * dx + vz * dz) / distance;
			if (cos < LURE_HALF_ANGLE_COS) continue;
			double weight = (cos - LURE_HALF_ANGLE_COS) / (1.0D - LURE_HALF_ANGLE_COS) / (1.0D + distance / LURE_FALLOFF_BLOCKS);
			sum += weight * signal(featureKind, entry.getValue());
			weights += weight;
		}
		return weights <= 0.0D ? 0.0D : sum / weights;
	}

	static boolean surfaceTells(String featureKind) {
		return featureKind.equals("forest") || featureKind.equals("water_body");
	}

	/** 0..1: tree tops count fully toward a forest and grass a little (trees grow on grass, not sand); water toward water. */
	static double signal(String featureKind, Surface surface) {
		return switch (featureKind) {
			case "forest" -> Math.min(1.0D, surface.fraction(surface.trees()) * 2.0D + surface.fraction(surface.grass()) * 0.3D);
			case "water_body" -> Math.min(1.0D, surface.fraction(surface.water()) * 2.0D);
			default -> 0.0D;
		};
	}

	static long key(int chunkX, int chunkZ) { return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL); }

	private static double centerX(long key) { return ((int) (key >> 32) << 4) + 8.0D; }

	private static double centerZ(long key) { return ((int) key << 4) + 8.0D; }

	private static double horizontal(Vec3 from, double x, double z) { return Math.hypot(from.x - x, from.z - z); }
}
