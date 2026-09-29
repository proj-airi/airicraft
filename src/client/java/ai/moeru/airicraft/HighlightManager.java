package ai.moeru.airicraft;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.client.renderer.debug.GameTestDebugRenderer;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HighlightManager {
	private final Map<String, HighlightRecord> highlights = new LinkedHashMap<>();

	public String addBlock(BlockPos pos, int colorArgb, Long durationMs, String overlayText) {
		long now = Util.getMillis();
		purgeExpired(now);
		String highlightId = UUID.randomUUID().toString();
		highlights.put(highlightId, new BlockHighlight(
			highlightId,
			pos.immutable(),
			colorArgb,
			defaultText(overlayText, pos.toShortString()),
			expiresAt(now, durationMs)
		));
		rebuildBlockMarkers();
		return highlightId;
	}

	public String addRegion(BlockPos posA, BlockPos posB, int colorArgb, Long durationMs, String overlayText) {
		long now = Util.getMillis();
		purgeExpired(now);
		BlockPos min = new BlockPos(
			Math.min(posA.getX(), posB.getX()),
			Math.min(posA.getY(), posB.getY()),
			Math.min(posA.getZ(), posB.getZ())
		);
		BlockPos max = new BlockPos(
			Math.max(posA.getX(), posB.getX()),
			Math.max(posA.getY(), posB.getY()),
			Math.max(posA.getZ(), posB.getZ())
		);
		String highlightId = UUID.randomUUID().toString();
		highlights.put(highlightId, new RegionHighlight(
			highlightId,
			min,
			max,
			colorArgb,
			defaultText(overlayText, min.toShortString() + " -> " + max.toShortString()),
			expiresAt(now, durationMs)
		));
		rebuildBlockMarkers();
		return highlightId;
	}

	public List<Map<String, Object>> list() {
		purgeExpired(Util.getMillis());
		rebuildBlockMarkers();
		List<Map<String, Object>> payload = new ArrayList<>();
		for (HighlightRecord highlight : highlights.values()) {
			payload.add(highlight.toPayload());
		}
		return payload;
	}

	public boolean clearById(String highlightId) {
		purgeExpired(Util.getMillis());
		boolean removed = highlights.remove(highlightId) != null;
		if (removed) {
			rebuildBlockMarkers();
		}
		return removed;
	}

	public int clear() {
		int count = highlights.size();
		highlights.clear();
		clearRendererMarkers();
		return count;
	}

	public void tick() {
		if (purgeExpired(Util.getMillis())) {
			rebuildBlockMarkers();
		}
	}

	public void render(WorldRenderContext context) {
		long now = Util.getMillis();
		if (purgeExpired(now)) {
			rebuildBlockMarkers();
		}
		var minecraft = Minecraft.getInstance();
		if (minecraft.levelRenderer == null || minecraft.level == null) {
			return;
		}
		if (context.matrixStack() == null || context.consumers() == null || context.camera() == null) {
			return;
		}

		for (HighlightRecord highlight : highlights.values()) {
			if (highlight instanceof RegionHighlight region) {
				renderRegion(region, context);
			}
		}
	}

	private void renderRegion(RegionHighlight region, WorldRenderContext context) {
		AABB box = regionBox(region).inflate(0.002D);
		DebugRenderer.renderFilledBox(
			context.matrixStack(),
			context.consumers(),
			box,
			colorComponent(region.colorArgb, 16),
			colorComponent(region.colorArgb, 8),
			colorComponent(region.colorArgb, 0),
			colorComponent(region.colorArgb, 24)
		);

		double labelX = (box.minX + box.maxX) * 0.5D;
		double labelY = box.maxY + 0.2D;
		double labelZ = (box.minZ + box.maxZ) * 0.5D;
		DebugRenderer.renderFloatingText(
			context.matrixStack(),
			context.consumers(),
			region.overlayText,
			labelX,
			labelY,
			labelZ,
			0xFFFFFFFF,
			0.02F,
			true,
			0.0F,
			true
		);
	}

	private void rebuildBlockMarkers() {
		var minecraft = Minecraft.getInstance();
		if (minecraft.debugRenderer == null) {
			return;
		}

		clearRendererMarkers();
		long now = Util.getMillis();

		for (HighlightRecord highlight : highlights.values()) {
			if (highlight instanceof BlockHighlight block) {
				addColoredMarker(minecraft.debugRenderer.gameTestDebugRenderer, block, now);
			}
		}
	}

	private void clearRendererMarkers() {
		var minecraft = Minecraft.getInstance();
		if (minecraft.debugRenderer != null) {
			minecraft.debugRenderer.gameTestDebugRenderer.clear();
		}
	}

	private boolean purgeExpired(long now) {
		boolean removed = highlights.values().removeIf(highlight -> highlight.isExpired(now));
		return removed;
	}

	private static void addColoredMarker(GameTestDebugRenderer renderer, BlockHighlight block, long now) {
		renderer.addMarker(
			block.pos,
			block.colorArgb,
			block.overlayText,
			markerDurationMillis(now, block.expiresAtEpochMillis)
		);
	}

	private static Long expiresAt(long now, Long durationMs) {
		if (durationMs == null) {
			return null;
		}
		return now + Math.max(durationMs, 1L);
	}

	private static String defaultText(String overlayText, String fallback) {
		return overlayText == null || overlayText.isBlank() ? fallback : overlayText;
	}

	private static AABB regionBox(RegionHighlight region) {
		return new AABB(
			region.minPos.getX(),
			region.minPos.getY(),
			region.minPos.getZ(),
			region.maxPos.getX() + 1.0D,
			region.maxPos.getY() + 1.0D,
			region.maxPos.getZ() + 1.0D
		);
	}

	private static int markerDurationMillis(long now, Long expiresAtEpochMillis) {
		if (expiresAtEpochMillis == null) {
			return Integer.MAX_VALUE;
		}
		long remaining = Math.max(expiresAtEpochMillis - now, 1L);
		return (int) Math.min(remaining, Integer.MAX_VALUE);
	}

	private static float colorComponent(int colorArgb, int shift) {
		return ((colorArgb >> shift) & 0xFF) / 255.0F;
	}

	private static Map<String, Object> blockPosPayload(BlockPos pos) {
		return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
	}

	private sealed interface HighlightRecord permits BlockHighlight, RegionHighlight {
		String highlightId();

		int colorArgb();

		String overlayText();

		Long expiresAtEpochMillis();

		Map<String, Object> toPayload();

		default boolean isExpired(long now) {
			Long expiresAtEpochMillis = expiresAtEpochMillis();
			return expiresAtEpochMillis != null && expiresAtEpochMillis <= now;
		}
	}

	private record BlockHighlight(
		String highlightId,
		BlockPos pos,
		int colorArgb,
		String overlayText,
		Long expiresAtEpochMillis
	) implements HighlightRecord {
		@Override
		public Map<String, Object> toPayload() {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("highlightId", highlightId);
			payload.put("kind", "block");
			payload.put("color", String.format("%08X", colorArgb));
			payload.put("overlayText", overlayText);
			payload.put("expiresAtEpochMillis", expiresAtEpochMillis);
			payload.put("pos", blockPosPayload(pos));
			return payload;
		}
	}

	private record RegionHighlight(
		String highlightId,
		BlockPos minPos,
		BlockPos maxPos,
		int colorArgb,
		String overlayText,
		Long expiresAtEpochMillis
	) implements HighlightRecord {
		@Override
		public Map<String, Object> toPayload() {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("highlightId", highlightId);
			payload.put("kind", "region");
			payload.put("color", String.format("%08X", colorArgb));
			payload.put("overlayText", overlayText);
			payload.put("expiresAtEpochMillis", expiresAtEpochMillis);
			payload.put("minPos", blockPosPayload(minPos));
			payload.put("maxPos", blockPosPayload(maxPos));
			return payload;
		}
	}
}
