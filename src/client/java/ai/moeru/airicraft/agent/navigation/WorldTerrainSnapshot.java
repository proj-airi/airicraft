package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.navigation.CellInfo;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.TerrainView;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Block states copied from loaded chunks on the client thread, then classified lazily by the search
 * thread. Cells outside the box or in chunks that were not loaded are unloaded to search.
 */
public final class WorldTerrainSnapshot implements TerrainView {
	/** Horizontal reach of one snapshot around the start; farther goals are planned in segments. */
	static final int HORIZONTAL_RADIUS = 96;
	private static final int GOAL_MARGIN = 24;
	private static final int VERTICAL_MARGIN = 24;
	private static final int MAX_HEIGHT = 96;

	private final int minX;
	private final int minY;
	private final int minZ;
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final BlockState[] states;
	private final MinecraftCellClassifier classifier;
	private final long captureNanos;

	private WorldTerrainSnapshot(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, BlockState[] states,
		MinecraftCellClassifier classifier, long captureNanos) {
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.states = states;
		this.classifier = classifier;
		this.captureNanos = captureNanos;
	}

	/**
	 * Copies the box around {@code start} and toward {@code target} (a goal cell; its y is ignored
	 * when {@code targetHasY} is false). Must run on the client thread.
	 */
	public static WorldTerrainSnapshot capture(ClientWorld world, GridPos start, GridPos target, boolean targetHasY,
		MinecraftCellClassifier classifier) {
		long started = System.nanoTime();
		GridPos aim = target == null ? start : target;
		int minX = clamp(Math.min(start.x(), aim.x()) - GOAL_MARGIN, start.x() - HORIZONTAL_RADIUS, start.x());
		int maxX = clamp(Math.max(start.x(), aim.x()) + GOAL_MARGIN, start.x(), start.x() + HORIZONTAL_RADIUS);
		int minZ = clamp(Math.min(start.z(), aim.z()) - GOAL_MARGIN, start.z() - HORIZONTAL_RADIUS, start.z());
		int maxZ = clamp(Math.max(start.z(), aim.z()) + GOAL_MARGIN, start.z(), start.z() + HORIZONTAL_RADIUS);
		int lowY = targetHasY ? Math.min(start.y(), aim.y()) : start.y();
		int highY = targetHasY ? Math.max(start.y(), aim.y()) : start.y();
		int minY = Math.max(world.getBottomY(), Math.max(lowY - VERTICAL_MARGIN, start.y() - MAX_HEIGHT / 2));
		int maxY = Math.min(world.getTopYInclusive(), Math.min(highY + VERTICAL_MARGIN, start.y() + MAX_HEIGHT / 2));
		int sizeX = maxX - minX + 1, sizeY = Math.max(1, maxY - minY + 1), sizeZ = maxZ - minZ + 1;
		BlockState[] states = new BlockState[sizeX * sizeY * sizeZ];
		BlockPos.Mutable probe = new BlockPos.Mutable();
		for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
			for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
				if (!world.isChunkLoaded(probe.set(chunkX << 4, minY, chunkZ << 4))) continue;
				WorldChunk chunk = world.getChunk(chunkX, chunkZ);
				ChunkSection[] sections = chunk.getSectionArray();
				int x0 = Math.max(minX, chunkX << 4), x1 = Math.min(maxX, (chunkX << 4) + 15);
				int z0 = Math.max(minZ, chunkZ << 4), z1 = Math.min(maxZ, (chunkZ << 4) + 15);
				for (int y = minY; y <= maxY; y++) {
					int sectionIndex = chunk.getSectionIndex(y);
					if (sectionIndex < 0 || sectionIndex >= sections.length) continue;
					ChunkSection section = sections[sectionIndex];
					for (int x = x0; x <= x1; x++) {
						for (int z = z0; z <= z1; z++) {
							BlockState state = section == null || section.isEmpty()
								? net.minecraft.block.Blocks.AIR.getDefaultState()
								: section.getBlockState(x & 15, y & 15, z & 15);
							states[((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX)] = state;
						}
					}
				}
			}
		}
		return new WorldTerrainSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ, states, classifier, System.nanoTime() - started);
	}

	@Override
	public CellInfo cell(int x, int y, int z) {
		int dx = x - minX, dy = y - minY, dz = z - minZ;
		if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ) return CellInfo.UNLOADED;
		BlockState state = states[(dy * sizeZ + dz) * sizeX + dx];
		return state == null ? CellInfo.UNLOADED : classifier.classify(state);
	}

	public double captureMillis() {
		return captureNanos / 1_000_000.0;
	}

	public int cells() {
		return states.length;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
