package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.navigation.CellInfo;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.TerrainView;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Chunk sections copied on the client thread, then read and classified by the search thread. Only
 * each section's block-state container is copied, an array copy, so capturing costs little on the
 * client thread. Cells outside the box or in chunks that were not loaded are unloaded to search.
 */
public final class WorldTerrainSnapshot implements TerrainView {
	/** Horizontal reach of one snapshot around the start; farther goals are planned in segments. */
	static final int HORIZONTAL_RADIUS = 96;
	private static final int GOAL_MARGIN = 24;
	private static final int VERTICAL_MARGIN = 24;
	private static final int MAX_HEIGHT = 96;
	private static final BlockState AIR = Blocks.AIR.getDefaultState();

	private final int minX;
	private final int minY;
	private final int minZ;
	private final int maxX;
	private final int maxY;
	private final int maxZ;
	private final int minChunkX;
	private final int minChunkZ;
	private final int chunksZ;
	private final int minSection;
	private final int sections;
	private final boolean[] loadedColumns;
	/** Copied containers per chunk column and section; null for an empty section. */
	private final PalettedContainer<BlockState>[] containers;
	private final MinecraftCellClassifier classifier;
	private final long captureNanos;

	private WorldTerrainSnapshot(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int chunksZ, int minSection,
		int sections, boolean[] loadedColumns, PalettedContainer<BlockState>[] containers, MinecraftCellClassifier classifier,
		long captureNanos) {
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
		this.minChunkX = minX >> 4;
		this.minChunkZ = minZ >> 4;
		this.chunksZ = chunksZ;
		this.minSection = minSection;
		this.sections = sections;
		this.loadedColumns = loadedColumns;
		this.containers = containers;
		this.classifier = classifier;
		this.captureNanos = captureNanos;
	}

	/**
	 * Copies the box around {@code start} and toward {@code target} (a goal cell; its y is ignored
	 * when {@code targetHasY} is false). Must run on the client thread.
	 */
	@SuppressWarnings("unchecked")
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
		int maxY = Math.max(minY, Math.min(world.getTopYInclusive(), Math.min(highY + VERTICAL_MARGIN, start.y() + MAX_HEIGHT / 2)));
		int chunksX = (maxX >> 4) - (minX >> 4) + 1, chunksZ = (maxZ >> 4) - (minZ >> 4) + 1;
		int minSection = minY >> 4, sections = (maxY >> 4) - minSection + 1;
		boolean[] loadedColumns = new boolean[chunksX * chunksZ];
		PalettedContainer<BlockState>[] containers = new PalettedContainer[chunksX * chunksZ * sections];
		BlockPos.Mutable probe = new BlockPos.Mutable();
		for (int cx = 0; cx < chunksX; cx++) {
			for (int cz = 0; cz < chunksZ; cz++) {
				int chunkX = (minX >> 4) + cx, chunkZ = (minZ >> 4) + cz;
				if (!world.isChunkLoaded(probe.set(chunkX << 4, minY, chunkZ << 4))) continue;
				int column = cx * chunksZ + cz;
				loadedColumns[column] = true;
				WorldChunk chunk = world.getChunk(chunkX, chunkZ);
				ChunkSection[] chunkSections = chunk.getSectionArray();
				for (int section = 0; section < sections; section++) {
					int index = chunk.getSectionIndex((minSection + section) << 4);
					if (index < 0 || index >= chunkSections.length) continue;
					ChunkSection chunkSection = chunkSections[index];
					if (chunkSection == null || chunkSection.isEmpty()) continue;
					containers[column * sections + section] = chunkSection.getBlockStateContainer().copy();
				}
			}
		}
		return new WorldTerrainSnapshot(minX, minY, minZ, maxX, maxY, maxZ, chunksZ, minSection, sections, loadedColumns,
			containers, classifier, System.nanoTime() - started);
	}

	@Override
	public CellInfo cell(int x, int y, int z) {
		if (x < minX || y < minY || z < minZ || x > maxX || y > maxY || z > maxZ) return CellInfo.UNLOADED;
		int column = ((x >> 4) - minChunkX) * chunksZ + ((z >> 4) - minChunkZ);
		if (!loadedColumns[column]) return CellInfo.UNLOADED;
		PalettedContainer<BlockState> container = containers[column * sections + ((y >> 4) - minSection)];
		return classifier.classify(container == null ? AIR : container.get(x & 15, y & 15, z & 15));
	}

	public double captureMillis() {
		return captureNanos / 1_000_000.0;
	}

	/** Cells covered by the snapshot box. */
	public long cells() {
		return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
