package ai.moeru.airicraft.debug;

import ai.moeru.airicraft.BridgeUnavailableException;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ClientTickWorldQueryService {
	public static final int DEFAULT_PAGE_LIMIT = 256;
	public static final int MAX_PAGE_LIMIT = 4_096;

	public Map<String, Object> metadata(
		Minecraft minecraft,
		ClientTickDebugController.ClientTickSnapshot snapshot
	) {
		ClientLevel level = requireMatchingWorld(minecraft, snapshot);
		Map<String, Object> response = baseResponse(snapshot);
		response.put("dimensionId", snapshot.dimensionId());
		response.put("worldTime", snapshot.worldTime());
		response.put("timeOfDay", snapshot.timeOfDay());
		response.put("player", snapshot.player());
		response.put("plannerGeneration", snapshot.plannerGeneration());
		response.put("plannerPhase", snapshot.plannerPhase());
		response.put("bottomY", level.getMinY());
		response.put("topYInclusive", level.getMaxY());
		return response;
	}

	public Map<String, Object> playerState(
		Minecraft minecraft,
		ClientTickDebugController.ClientTickSnapshot snapshot
	) {
		requireMatchingWorld(minecraft, snapshot);
		Map<String, Object> response = baseResponse(snapshot);
		response.put("player", snapshot.player());
		return response;
	}

	public Map<String, Object> block(
		Minecraft minecraft,
		ClientTickDebugController.ClientTickSnapshot snapshot,
		int x,
		int y,
		int z
	) {
		ClientLevel level = requireMatchingWorld(minecraft, snapshot);
		Map<String, Object> response = baseResponse(snapshot);
		response.put("block", blockPayload(level, new BlockPos(x, y, z)));
		return response;
	}

	public Map<String, Object> scanBox(
		Minecraft minecraft,
		ClientTickDebugController.ClientTickSnapshot snapshot,
		RegionBounds bounds,
		long cursor,
		int limit
	) {
		ClientLevel level = requireMatchingWorld(minecraft, snapshot);
		RegionPage page = bounds.page(cursor, limit);
		List<Map<String, Object>> blocks = new ArrayList<>(page.count());
		for (long index = page.startCursor(); index < page.endCursorExclusive(); index++) {
			blocks.add(blockPayload(level, bounds.positionAt(index)));
		}
		Map<String, Object> response = pageResponse(snapshot, bounds, page);
		response.put("blocks", blocks);
		return response;
	}

	public Map<String, Object> findBlocks(
		Minecraft minecraft,
		ClientTickDebugController.ClientTickSnapshot snapshot,
		RegionBounds bounds,
		Set<String> blockIds,
		long cursor,
		int limit
	) {
		ClientLevel level = requireMatchingWorld(minecraft, snapshot);
		Set<String> requestedIds = normalizedBlockIds(blockIds);
		RegionPage page = bounds.page(cursor, limit);
		List<Map<String, Object>> matches = new ArrayList<>();
		int unloadedCount = 0;
		for (long index = page.startCursor(); index < page.endCursorExclusive(); index++) {
			BlockPos pos = bounds.positionAt(index);
			if (!level.hasChunkAt(pos)) {
				unloadedCount++;
				continue;
			}
			BlockState state = level.getBlockState(pos);
			String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			if (requestedIds.contains(blockId)) {
				matches.add(blockPayload(level, pos));
			}
		}
		Map<String, Object> response = pageResponse(snapshot, bounds, page);
		response.put("blockIds", List.copyOf(requestedIds));
		response.put("matches", matches);
		response.put("matchCount", matches.size());
		response.put("unloadedCount", unloadedCount);
		return response;
	}

	public Map<String, Object> regionStats(
		Minecraft minecraft,
		ClientTickDebugController.ClientTickSnapshot snapshot,
		RegionBounds bounds,
		long cursor,
		int limit
	) {
		ClientLevel level = requireMatchingWorld(minecraft, snapshot);
		RegionPage page = bounds.page(cursor, limit);
		Map<String, Integer> blockCounts = new LinkedHashMap<>();
		int unloadedCount = 0;
		for (long index = page.startCursor(); index < page.endCursorExclusive(); index++) {
			BlockPos pos = bounds.positionAt(index);
			if (!level.hasChunkAt(pos)) {
				unloadedCount++;
				continue;
			}
			BlockState state = level.getBlockState(pos);
			String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			blockCounts.merge(blockId, 1, Integer::sum);
		}
		Map<String, Object> response = pageResponse(snapshot, bounds, page);
		response.put("blockCounts", blockCounts);
		response.put("unloadedCount", unloadedCount);
		return response;
	}

	static ClientLevel requireMatchingWorld(
		Minecraft minecraft,
		ClientTickDebugController.ClientTickSnapshot snapshot
	) {
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			throw new BridgeUnavailableException("world_not_loaded", "No world is currently loaded");
		}
		String currentDimension = minecraft.level.dimension().location().toString();
		if (!Objects.equals(currentDimension, snapshot.dimensionId())) {
			throw new BridgeUnavailableException("stale_snapshot", "The world dimension changed after the snapshot");
		}
		return minecraft.level;
	}

	static Map<String, Object> baseResponse(ClientTickDebugController.ClientTickSnapshot snapshot) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("available", true);
		response.put("debugSessionId", snapshot.debugSessionId());
		response.put("snapshotId", snapshot.snapshotId());
		response.put("clientTickId", snapshot.clientTickId());
		response.put("snapshotCapturedAtMs", snapshot.capturedAtMs());
		return response;
	}

	private static Map<String, Object> pageResponse(
		ClientTickDebugController.ClientTickSnapshot snapshot,
		RegionBounds bounds,
		RegionPage page
	) {
		Map<String, Object> response = baseResponse(snapshot);
		response.put("bounds", bounds);
		response.put("totalCellCount", page.totalCellCount());
		response.put("cursor", page.startCursor());
		response.put("scannedCellCount", page.count());
		response.put("nextCursor", page.complete() ? null : page.endCursorExclusive());
		response.put("complete", page.complete());
		return response;
	}

	private static Map<String, Object> blockPayload(ClientLevel level, BlockPos pos) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("pos", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
		boolean loaded = level.hasChunkAt(pos);
		payload.put("loaded", loaded);
		if (!loaded) {
			return payload;
		}
		BlockState state = level.getBlockState(pos);
		payload.put("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		payload.put("properties", blockProperties(state));
		payload.put("isAir", state.isAir());
		payload.put("isReplaceable", state.canBeReplaced());
		payload.put("fluidId", BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString());
		payload.put("light", level.getMaxLocalRawBrightness(pos));
		return payload;
	}

	private static Map<String, Object> blockProperties(BlockState state) {
		Map<String, Object> properties = new LinkedHashMap<>();
		for (Property<?> property : state.getProperties()) {
			properties.put(property.getName(), propertyValue(state, property));
		}
		return properties;
	}

	private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	private static Set<String> normalizedBlockIds(Set<String> blockIds) {
		if (blockIds == null || blockIds.isEmpty()) {
			throw new BridgeUnavailableException("invalid_request", "blockIds must contain at least one block identifier");
		}
		LinkedHashSet<String> normalized = new LinkedHashSet<>();
		for (String blockId : blockIds) {
			if (blockId == null || blockId.isBlank()) {
				throw new BridgeUnavailableException("invalid_request", "blockIds cannot contain blank values");
			}
			normalized.add(blockId.trim());
		}
		return normalized;
	}

	public record RegionBounds(
		int minX,
		int minY,
		int minZ,
		int maxX,
		int maxY,
		int maxZ
	) {
		public RegionBounds {
			if (minX > maxX || minY > maxY || minZ > maxZ) {
				throw new BridgeUnavailableException("invalid_request", "Region minimum coordinates must not exceed maximum coordinates");
			}
			totalCellCount(minX, minY, minZ, maxX, maxY, maxZ);
		}

		public long totalCellCount() {
			return totalCellCount(minX, minY, minZ, maxX, maxY, maxZ);
		}

		public BlockPos positionAt(long index) {
			long total = totalCellCount();
			if (index < 0L || index >= total) {
				throw new BridgeUnavailableException("invalid_request", "Region cursor is outside the requested region");
			}
			long sizeX = (long) maxX - minX + 1L;
			long sizeY = (long) maxY - minY + 1L;
			long xOffset = index % sizeX;
			long remaining = index / sizeX;
			long yOffset = remaining % sizeY;
			long zOffset = remaining / sizeY;
			return new BlockPos((int) (minX + xOffset), (int) (minY + yOffset), (int) (minZ + zOffset));
		}

		public RegionPage page(long cursor, int requestedLimit) {
			long total = totalCellCount();
			if (cursor < 0L || cursor > total) {
				throw new BridgeUnavailableException("invalid_request", "cursor must be within the requested region");
			}
			if (requestedLimit < 1 || requestedLimit > MAX_PAGE_LIMIT) {
				throw new BridgeUnavailableException("invalid_request", "limit must be between 1 and " + MAX_PAGE_LIMIT);
			}
			long end = Math.min(total, cursor + requestedLimit);
			return new RegionPage(cursor, end, total);
		}

		private static long totalCellCount(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
			try {
				long sizeX = (long) maxX - minX + 1L;
				long sizeY = (long) maxY - minY + 1L;
				long sizeZ = (long) maxZ - minZ + 1L;
				return Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
			}
			catch (ArithmeticException exception) {
				throw new BridgeUnavailableException("region_too_large", "The requested region is too large to index");
			}
		}
	}

	public record RegionPage(long startCursor, long endCursorExclusive, long totalCellCount) {
		public int count() {
			return Math.toIntExact(endCursorExclusive - startCursor);
		}

		public boolean complete() {
			return endCursorExclusive >= totalCellCount;
		}
	}
}
