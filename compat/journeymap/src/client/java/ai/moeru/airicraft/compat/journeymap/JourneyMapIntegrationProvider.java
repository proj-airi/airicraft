package ai.moeru.airicraft.compat.journeymap;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.agent.integration.map.MapCapabilities;
import ai.moeru.airicraft.agent.integration.map.MapCapabilities.MapCapability;
import ai.moeru.airicraft.agent.integration.map.MapImageCapture;
import ai.moeru.airicraft.agent.integration.map.MapImageEncoder;
import ai.moeru.airicraft.agent.integration.map.MapImageRequest;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationProvider;
import ai.moeru.airicraft.agent.integration.map.MapWaypoint;
import ai.moeru.airicraft.agent.integration.map.MapWaypointQuery;
import ai.moeru.airicraft.agent.integration.map.MapWaypointWrite;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class JourneyMapIntegrationProvider implements MapIntegrationProvider {
	public static final String PROVIDER_ID = "journeymap";
	private static final String AIRICRAFT_MOD_ID = "airicraft";
	private static final int DEFAULT_WAYPOINT_COLOR = 0x33aaff;
	private static final int JOURNEYMAP_REGION_PIXELS = 512;
	private static final int MAX_WORLDMAP_REGION_RADIUS = 2;
	private static final int MINIMAP_IMAGE_SIZE = 512;
	private static final int PLAYER_MARKER_COLOR = 0xffff2d2d;

	private final IClientAPI jmAPI;

	public JourneyMapIntegrationProvider(IClientAPI jmAPI) {
		this.jmAPI = Objects.requireNonNull(jmAPI, "jmAPI");
	}

	@Override
	public String id() {
		return PROVIDER_ID;
	}

	@Override
	public boolean available() {
		return true;
	}

	@Override
	public MapCapabilities capabilities() {
		return MapCapabilities.of(
			MapCapability.READ_WAYPOINTS,
			MapCapability.WRITE_WAYPOINTS,
			MapCapability.DELETE_WAYPOINTS,
			MapCapability.MINIMAP_IMAGE,
			MapCapability.WORLDMAP_IMAGE
		);
	}

	@Override
	public List<MapWaypoint> listWaypoints(MapWaypointQuery query) {
		String dimension = query == null ? null : query.dimension();
		return jmAPI.getAllWaypoints().stream()
			.filter(waypoint -> dimension == null || dimension.isBlank() || dimension.equals(waypoint.getPrimaryDimension()))
			.map(JourneyMapIntegrationProvider::toMapWaypoint)
			.toList();
	}

	@Override
	public MapWaypoint upsertWaypoint(MapWaypointWrite request) {
		Objects.requireNonNull(request, "request");
		String id = request.id();
		Waypoint waypoint = id == null || id.isBlank()
			? WaypointFactory.createClientWaypoint(
				AIRICRAFT_MOD_ID,
				new BlockPos(request.x(), request.y(), request.z()),
				request.name(),
				request.dimension(),
				true
			)
			: jmAPI.getWaypoint(AIRICRAFT_MOD_ID, id);
		if (waypoint == null) {
			waypoint = WaypointFactory.createClientWaypoint(
				AIRICRAFT_MOD_ID,
				new BlockPos(request.x(), request.y(), request.z()),
				request.name(),
				request.dimension(),
				true
			);
		}
		waypoint.setName(request.name());
		waypoint.setPos(request.x(), request.y(), request.z());
		waypoint.setPrimaryDimension(request.dimension());
		waypoint.setColor(request.color() == null ? DEFAULT_WAYPOINT_COLOR : request.color());
		waypoint.setEnabled(request.enabled());
		jmAPI.addWaypoint(AIRICRAFT_MOD_ID, waypoint);
		return toMapWaypoint(waypoint);
	}

	@Override
	public boolean deleteWaypoint(String waypointId) {
		if (waypointId == null || waypointId.isBlank()) {
			return false;
		}
		Waypoint waypoint = jmAPI.getWaypoint(AIRICRAFT_MOD_ID, waypointId);
		if (waypoint == null) {
			return false;
		}
		jmAPI.removeWaypoint(AIRICRAFT_MOD_ID, waypoint);
		return true;
	}

	@Override
	public CompletableFuture<MapImageCapture> captureMap(MapImageRequest request) {
		MapImageRequest safeRequest = request == null
			? new MapImageRequest(PROVIDER_ID, "worldmap", null, 8, 0, false)
			: request;
		String kind = Objects.requireNonNullElse(safeRequest.kind(), "worldmap").toLowerCase(Locale.ROOT);
		if (!"worldmap".equals(kind) && !"minimap".equals(kind)) {
			return CompletableFuture.failedFuture(new BridgeUnavailableException("map_kind_unavailable", "JourneyMap map capture supports worldmap and minimap"));
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			return CompletableFuture.failedFuture(new BridgeUnavailableException("world_not_loaded", "No world is currently loaded"));
		}
		String currentDimension = minecraft.level.dimension().location().toString();
		if (safeRequest.dimension() != null && !safeRequest.dimension().isBlank() && !currentDimension.equals(safeRequest.dimension())) {
			return CompletableFuture.failedFuture(new BridgeUnavailableException("map_dimension_unavailable", "JourneyMap capture currently requires the active dimension"));
		}
		if (safeRequest.hasPartialOrigin()) {
			return CompletableFuture.failedFuture(new BridgeUnavailableException("invalid_request", "Map image origin requires both originX and originZ"));
		}

		return captureApiMapTile(minecraft, kind, safeRequest);
	}

	private CompletableFuture<MapImageCapture> captureApiMapTile(Minecraft minecraft, String kind, MapImageRequest safeRequest) {
		MapCaptureGeometry geometry = captureGeometry(kind, safeRequest);
		BlockPos playerBlock = minecraft.player.blockPosition();
		int originBlockX = safeRequest.originX() == null ? playerBlock.getX() : safeRequest.originX();
		int originBlockZ = safeRequest.originZ() == null ? playerBlock.getZ() : safeRequest.originZ();
		int radiusChunks = Math.max(1, Math.min(96, safeRequest.radiusChunks()));
		int centerChunkX = Math.floorDiv(originBlockX, 16);
		int centerChunkZ = Math.floorDiv(originBlockZ, 16);
		ChunkPos startChunk = new ChunkPos(centerChunkX - radiusChunks, centerChunkZ - radiusChunks);
		ChunkPos endChunk = new ChunkPos(centerChunkX + radiusChunks, centerChunkZ + radiusChunks);
		CompletableFuture<MapImageCapture> future = new CompletableFuture<>();
		try {
			jmAPI.requestMapTile(
				AIRICRAFT_MOD_ID,
				minecraft.level.dimension(),
				Context.MapType.Day,
				startChunk,
				endChunk,
				null,
				geometry.zoom(),
				geometry.grid(),
				nativeImage -> completeApiMapTile(
					future,
					nativeImage,
					kind,
					startChunk,
					endChunk,
					playerBlock.getX(),
					playerBlock.getZ(),
					minecraft.player.getYRot(),
					minecraft.level.dimension().location().toString(),
					originBlockX,
					originBlockZ,
					geometry
				)
			);
		}
		catch (RuntimeException exception) {
			return CompletableFuture.failedFuture(new BridgeUnavailableException("map_unavailable", "JourneyMap live map tile request failed: " + exception.getMessage()));
		}
		return future.orTimeout(10, TimeUnit.SECONDS);
	}

	private void completeApiMapTile(
		CompletableFuture<MapImageCapture> future,
		NativeImage nativeImage,
		String kind,
		ChunkPos startChunk,
		ChunkPos endChunk,
		int playerBlockX,
		int playerBlockZ,
		float yawDegrees,
		String dimension,
		int originBlockX,
		int originBlockZ,
		MapCaptureGeometry geometry
	) {
		if (nativeImage == null) {
			completeCachedMapTile(future, kind, originBlockX, originBlockZ, playerBlockX, playerBlockZ, yawDegrees, dimension, geometry);
			return;
		}
		try {
			BufferedImage image = nativeImageToBufferedImage(nativeImage);
			annotateApiMapImage(
				image,
				startChunk.getMinBlockX(),
				startChunk.getMinBlockZ(),
				endChunk.getMaxBlockX(),
				endChunk.getMaxBlockZ(),
				playerBlockX,
				playerBlockZ,
				yawDegrees,
				listWaypoints(new MapWaypointQuery(PROVIDER_ID, dimension))
			);
			future.complete(MapImageEncoder.encode(PROVIDER_ID, kind, image, System.currentTimeMillis()));
		}
		catch (RuntimeException exception) {
			future.completeExceptionally(new BridgeUnavailableException("map_capture_failed", "Failed to encode JourneyMap live map tile: " + exception.getMessage()));
		}
	}

	private void completeCachedMapTile(
		CompletableFuture<MapImageCapture> future,
		String kind,
		int originBlockX,
		int originBlockZ,
		int playerBlockX,
		int playerBlockZ,
		float yawDegrees,
		String dimension,
		MapCaptureGeometry geometry
	) {
		try {
			File dataPath = jmAPI.getDataPath(AIRICRAFT_MOD_ID);
			Path imageDir = cachedMapImageDirectory(dataPath == null ? null : dataPath.toPath(), dimension, "day");
			BufferedImage image = composeCenteredMapImage(
				imageDir,
				originBlockX,
				originBlockZ,
				geometry.outputSize(),
				playerBlockX,
				playerBlockZ,
				yawDegrees,
				listWaypoints(new MapWaypointQuery(PROVIDER_ID, dimension)),
				geometry.zoom(),
				geometry.grid()
			);
			future.complete(MapImageEncoder.encode(PROVIDER_ID, kind, image, System.currentTimeMillis()));
		}
		catch (RuntimeException exception) {
			String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
			future.completeExceptionally(new BridgeUnavailableException(
				"map_unavailable",
				"JourneyMap live map tile is unavailable and cached map fallback failed: " + detail
			));
		}
	}

	static MapCaptureGeometry captureGeometry(String kind, MapImageRequest safeRequest) {
		int radiusChunks = Math.max(0, Math.min(96, safeRequest.radiusChunks()));
		int outputSize = "minimap".equals(kind)
			? MINIMAP_IMAGE_SIZE
			: (Math.min(MAX_WORLDMAP_REGION_RADIUS, Math.max(0, (radiusChunks + 31) / 32)) * 2 + 1) * JOURNEYMAP_REGION_PIXELS;
		int zoom = Math.max(0, Math.min(8, safeRequest.zoom()));
		return new MapCaptureGeometry(outputSize, zoom, safeRequest.grid());
	}

	static MapWaypoint toMapWaypoint(Waypoint waypoint) {
		Objects.requireNonNull(waypoint, "waypoint");
		BlockPos pos = waypoint.getBlockPos();
		return JourneyMapWaypointMapper.toMapWaypoint(
			waypoint.getGuid(),
			waypoint.getName(),
			waypoint.getPrimaryDimension(),
			pos.getX(),
			pos.getY(),
			pos.getZ(),
			waypoint.getColor(),
			waypoint.isEnabled(),
			visibilityFlag(waypoint, "showOnMap", waypoint.isEnabled()),
			visibilityFlag(waypoint, "showInWorld", waypoint.isEnabled())
		);
	}

	static boolean visibilityFlag(Object waypoint, String methodName, boolean fallback) {
		if (waypoint == null || methodName == null || methodName.isBlank()) {
			return fallback;
		}
		try {
			Method method = waypoint.getClass().getMethod(methodName);
			if (!method.canAccess(waypoint)) {
				method.setAccessible(true);
			}
			Object value = method.invoke(waypoint);
			return value instanceof Boolean flag ? flag : fallback;
		}
		catch (ReflectiveOperationException | SecurityException exception) {
			return fallback;
		}
	}

	static int regionCoordinateForChunk(int chunkCoordinate) {
		return Math.floorDiv(chunkCoordinate, 32);
	}

	static Path cachedMapImageDirectory(Path addonDataPath, String dimension, String mapLayer) {
		if (addonDataPath == null || addonDataPath.getNameCount() < 3) {
			throw new BridgeUnavailableException("map_unavailable", "JourneyMap cached map directory is unavailable");
		}
		Path addonDataDir = addonDataPath.getParent();
		Path worldDir = addonDataDir == null ? null : addonDataDir.getParent();
		if (worldDir == null) {
			throw new BridgeUnavailableException("map_unavailable", "JourneyMap cached map directory is unavailable");
		}
		return worldDir.resolve(dimensionPathSegment(dimension)).resolve(mapLayer == null || mapLayer.isBlank() ? "day" : mapLayer);
	}

	private static String dimensionPathSegment(String dimension) {
		if (dimension == null || dimension.isBlank()) {
			return "overworld";
		}
		int namespaceSeparator = dimension.indexOf(':');
		String path = namespaceSeparator >= 0 ? dimension.substring(namespaceSeparator + 1) : dimension;
		return path.isBlank() ? "overworld" : path;
	}

	static BufferedImage composeCenteredMapImage(Path imageDir, int playerBlockX, int playerBlockZ, int outputSize, boolean drawMarker, float yawDegrees) {
		return composeCenteredMapImage(
			imageDir,
			playerBlockX,
			playerBlockZ,
			outputSize,
			drawMarker ? playerBlockX : null,
			drawMarker ? playerBlockZ : null,
			drawMarker ? yawDegrees : null,
			List.of()
		);
	}

	static BufferedImage composeCenteredMapImage(
		Path imageDir,
		int originBlockX,
		int originBlockZ,
		int outputSize,
		Integer playerBlockX,
		Integer playerBlockZ,
		Float yawDegrees,
		List<MapWaypoint> waypoints
	) {
		if (imageDir == null || !Files.isDirectory(imageDir)) {
			throw new BridgeUnavailableException("map_unavailable", "JourneyMap cached map directory is unavailable");
		}
		int safeOutputSize = Math.max(1, outputSize);
		int center = safeOutputSize / 2;
		int minWorldX = originBlockX - center;
		int minWorldZ = originBlockZ - center;
		int maxWorldX = minWorldX + safeOutputSize - 1;
		int maxWorldZ = minWorldZ + safeOutputSize - 1;
		BufferedImage output = new BufferedImage(safeOutputSize, safeOutputSize, BufferedImage.TYPE_INT_ARGB);
		ImageBounds contentBounds = new ImageBounds();

		boolean foundImage = false;
		Graphics2D graphics = output.createGraphics();
		try {
			for (int regionZ = Math.floorDiv(minWorldZ, JOURNEYMAP_REGION_PIXELS);
				 regionZ <= Math.floorDiv(maxWorldZ, JOURNEYMAP_REGION_PIXELS);
				 regionZ++) {
				for (int regionX = Math.floorDiv(minWorldX, JOURNEYMAP_REGION_PIXELS);
					 regionX <= Math.floorDiv(maxWorldX, JOURNEYMAP_REGION_PIXELS);
					 regionX++) {
					BufferedImage tile = readRegionImage(imageDir.resolve(regionX + "," + regionZ + ".png"));
					if (tile == null) {
						continue;
					}
					foundImage = drawIntersectingTile(
						graphics,
						tile,
						regionX * JOURNEYMAP_REGION_PIXELS,
						regionZ * JOURNEYMAP_REGION_PIXELS,
						minWorldX,
						minWorldZ,
						maxWorldX,
						maxWorldZ,
						contentBounds
					) || foundImage;
				}
			}
		}
		finally {
			graphics.dispose();
		}

		if (!foundImage) {
			throw new BridgeUnavailableException("map_unavailable", "No JourneyMap cached region images are available");
		}
		boolean drewWaypoints = drawWaypointMarkers(output, minWorldX, minWorldZ, maxWorldX, maxWorldZ, waypoints, contentBounds);
		boolean drewPlayer = false;
		if (playerBlockX != null && playerBlockZ != null && yawDegrees != null
			&& playerBlockX >= minWorldX && playerBlockX <= maxWorldX
			&& playerBlockZ >= minWorldZ && playerBlockZ <= maxWorldZ) {
			drawPlayerMarker(output, playerBlockX - minWorldX, playerBlockZ - minWorldZ, yawDegrees, contentBounds);
			drewPlayer = true;
		}
		BufferedImage cropped = cropToBounds(output, contentBounds);
		drawOverlayLegend(cropped, drewPlayer, drewWaypoints);
		return cropped;
	}

	static BufferedImage stitchCachedRegionImages(Path imageDir, int centerRegionX, int centerRegionZ, int regionRadius) {
		if (imageDir == null || !Files.isDirectory(imageDir)) {
			throw new BridgeUnavailableException("map_unavailable", "JourneyMap cached map directory is unavailable");
		}
		int safeRadius = Math.max(0, regionRadius);
		int regionCount = safeRadius * 2 + 1;
		BufferedImage stitched = new BufferedImage(regionCount * 512, regionCount * 512, BufferedImage.TYPE_INT_ARGB);
		boolean foundImage = false;
		Graphics2D graphics = stitched.createGraphics();
		try {
			for (int dz = -safeRadius; dz <= safeRadius; dz++) {
				for (int dx = -safeRadius; dx <= safeRadius; dx++) {
					int regionX = centerRegionX + dx;
					int regionZ = centerRegionZ + dz;
					BufferedImage tile = readRegionImage(imageDir.resolve(regionX + "," + regionZ + ".png"));
					if (tile != null) {
						foundImage = true;
						graphics.drawImage(tile, (dx + safeRadius) * 512, (dz + safeRadius) * 512, null);
					}
				}
			}
		}
		finally {
			graphics.dispose();
		}
		if (!foundImage) {
			throw new BridgeUnavailableException("map_unavailable", "No JourneyMap cached region images are available");
		}
		return stitched;
	}

	private static boolean drawIntersectingTile(
		Graphics2D graphics,
		BufferedImage tile,
		int tileWorldX,
		int tileWorldZ,
		int outputMinWorldX,
		int outputMinWorldZ,
		int outputMaxWorldX,
		int outputMaxWorldZ,
		ImageBounds contentBounds
	) {
		int intersectionMinWorldX = Math.max(outputMinWorldX, tileWorldX);
		int intersectionMinWorldZ = Math.max(outputMinWorldZ, tileWorldZ);
		int intersectionMaxWorldX = Math.min(outputMaxWorldX, tileWorldX + tile.getWidth() - 1);
		int intersectionMaxWorldZ = Math.min(outputMaxWorldZ, tileWorldZ + tile.getHeight() - 1);
		if (intersectionMinWorldX > intersectionMaxWorldX || intersectionMinWorldZ > intersectionMaxWorldZ) {
			return false;
		}

		int destinationX1 = intersectionMinWorldX - outputMinWorldX;
		int destinationY1 = intersectionMinWorldZ - outputMinWorldZ;
		int sourceX1 = intersectionMinWorldX - tileWorldX;
		int sourceY1 = intersectionMinWorldZ - tileWorldZ;
		int sourceX2 = intersectionMaxWorldX - tileWorldX + 1;
		int sourceY2 = intersectionMaxWorldZ - tileWorldZ + 1;
		ImageBounds sourceBounds = nonTransparentBounds(tile, sourceX1, sourceY1, sourceX2, sourceY2);
		if (sourceBounds.isEmpty()) {
			return false;
		}
		int visibleDestinationX1 = destinationX1 + sourceBounds.minX() - sourceX1;
		int visibleDestinationY1 = destinationY1 + sourceBounds.minY() - sourceY1;
		int visibleDestinationX2 = visibleDestinationX1 + sourceBounds.width();
		int visibleDestinationY2 = visibleDestinationY1 + sourceBounds.height();

		graphics.drawImage(
			tile,
			visibleDestinationX1,
			visibleDestinationY1,
			visibleDestinationX2,
			visibleDestinationY2,
			sourceBounds.minX(),
			sourceBounds.minY(),
			sourceBounds.maxX() + 1,
			sourceBounds.maxY() + 1,
			null
		);
		contentBounds.include(visibleDestinationX1, visibleDestinationY1, visibleDestinationX2 - 1, visibleDestinationY2 - 1);
		return true;
	}

	private static boolean drawWaypointMarkers(
		BufferedImage image,
		int outputMinWorldX,
		int outputMinWorldZ,
		int outputMaxWorldX,
		int outputMaxWorldZ,
		List<MapWaypoint> waypoints,
		ImageBounds contentBounds
	) {
		if (waypoints == null || waypoints.isEmpty()) {
			return false;
		}
		boolean drewAny = false;
		for (MapWaypoint waypoint : waypoints) {
			if (waypoint == null || !waypoint.enabled() || !waypoint.showOnMap()) {
				continue;
			}
			if (waypoint.x() < outputMinWorldX || waypoint.x() > outputMaxWorldX
				|| waypoint.z() < outputMinWorldZ || waypoint.z() > outputMaxWorldZ) {
				continue;
			}
			drawWaypointMarker(image, waypoint.x() - outputMinWorldX, waypoint.z() - outputMinWorldZ, waypoint, contentBounds);
			drewAny = true;
		}
		return drewAny;
	}

	private static void drawWaypointMarker(BufferedImage image, int centerX, int centerY, MapWaypoint waypoint, ImageBounds contentBounds) {
		int color = 0xff000000 | (waypoint.color() & 0x00ffffff);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setStroke(new BasicStroke(2.0F));
			graphics.setColor(Color.WHITE);
			graphics.fillOval(centerX - 6, centerY - 6, 12, 12);
			graphics.setColor(Color.BLACK);
			graphics.drawOval(centerX - 6, centerY - 6, 12, 12);
			graphics.setColor(new Color(color, true));
			graphics.fillOval(centerX - 4, centerY - 4, 8, 8);
			contentBounds.include(centerX - 7, centerY - 7, centerX + 7, centerY + 7);
			drawWaypointLabel(graphics, image, centerX, centerY, waypoint.name(), contentBounds);
		}
		finally {
			graphics.dispose();
		}
		if (isInside(image, centerX, centerY)) {
			image.setRGB(centerX, centerY, color);
		}
	}

	private static void drawWaypointLabel(Graphics2D graphics, BufferedImage image, int centerX, int centerY, String name, ImageBounds contentBounds) {
		if (image.getWidth() < 96 || image.getHeight() < 32 || name == null || name.isBlank()) {
			return;
		}
		String label = name.length() > 24 ? name.substring(0, 21) + "..." : name;
		var metrics = graphics.getFontMetrics();
		int width = metrics.stringWidth(label);
		if (width > image.getWidth() - 2) {
			return;
		}
		int labelX = Math.max(1, Math.min(image.getWidth() - width - 1, centerX + 9));
		int labelY = Math.max(metrics.getAscent() + 1, Math.min(image.getHeight() - metrics.getDescent() - 1, centerY - 7));
		graphics.setColor(new Color(0xaa000000, true));
		graphics.drawString(label, labelX + 1, labelY + 1);
		graphics.setColor(Color.WHITE);
		graphics.drawString(label, labelX, labelY);
		contentBounds.include(labelX, labelY - metrics.getAscent(), labelX + width + 1, labelY + metrics.getDescent() + 1);
	}

	private static void drawPlayerMarker(BufferedImage image, int centerX, int centerY, float yawDegrees, ImageBounds contentBounds) {
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setStroke(new BasicStroke(3.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			double headingRadians = Math.toRadians(yawDegrees);
			double headingDx = -Math.sin(headingRadians);
			double headingDy = Math.cos(headingRadians);
			double perpendicularDx = -headingDy;
			double perpendicularDy = headingDx;
			int headingX = centerX + (int) Math.round(headingDx * 22.0D);
			int headingY = centerY + (int) Math.round(headingDy * 22.0D);
			int arrowBaseX = centerX + (int) Math.round(headingDx * 13.0D);
			int arrowBaseY = centerY + (int) Math.round(headingDy * 13.0D);
			int wingLeftX = arrowBaseX + (int) Math.round(perpendicularDx * 6.0D);
			int wingLeftY = arrowBaseY + (int) Math.round(perpendicularDy * 6.0D);
			int wingRightX = arrowBaseX - (int) Math.round(perpendicularDx * 6.0D);
			int wingRightY = arrowBaseY - (int) Math.round(perpendicularDy * 6.0D);

			graphics.setColor(Color.WHITE);
			graphics.drawLine(centerX, centerY, headingX, headingY);
			graphics.fillOval(centerX - 8, centerY - 8, 16, 16);
			graphics.setColor(Color.BLACK);
			graphics.drawOval(centerX - 8, centerY - 8, 16, 16);
			graphics.drawLine(centerX, centerY, headingX, headingY);
			graphics.setColor(new Color(PLAYER_MARKER_COLOR, true));
			graphics.drawLine(centerX, centerY, headingX, headingY);
			graphics.fill(new Polygon(
				new int[] {headingX, wingLeftX, wingRightX},
				new int[] {headingY, wingLeftY, wingRightY},
				3
			));
			graphics.fillOval(centerX - 5, centerY - 5, 10, 10);
			graphics.setStroke(new BasicStroke(2.0F));
			graphics.setColor(Color.BLACK);
			graphics.drawOval(centerX - 5, centerY - 5, 10, 10);
			drawOverlayLabel(graphics, image, centerX + 10, centerY + 4, "PLAYER", contentBounds);
		}
		finally {
			graphics.dispose();
		}
		if (isInside(image, centerX, centerY)) {
			image.setRGB(centerX, centerY, PLAYER_MARKER_COLOR);
		}
		contentBounds.include(centerX - 24, centerY - 24, centerX + 42, centerY + 24);
	}

	private static void drawOverlayLabel(Graphics2D graphics, BufferedImage image, int labelX, int baselineY, String label, ImageBounds contentBounds) {
		var metrics = graphics.getFontMetrics();
		int width = metrics.stringWidth(label);
		if (labelX + width + 1 >= image.getWidth()) {
			labelX = Math.max(1, image.getWidth() - width - 2);
		}
		baselineY = Math.max(metrics.getAscent() + 1, Math.min(image.getHeight() - metrics.getDescent() - 1, baselineY));
		graphics.setColor(new Color(0xcc000000, true));
		graphics.drawString(label, labelX + 1, baselineY + 1);
		graphics.setColor(Color.WHITE);
		graphics.drawString(label, labelX, baselineY);
		contentBounds.include(labelX, baselineY - metrics.getAscent(), labelX + width + 1, baselineY + metrics.getDescent() + 1);
	}

	private static void drawOverlayLegend(BufferedImage image, boolean includePlayer, boolean includeWaypoints) {
		if ((!includePlayer && !includeWaypoints) || image.getWidth() < 120 || image.getHeight() < 64) {
			return;
		}
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 11.0F));
			int rowCount = (includePlayer ? 1 : 0) + (includeWaypoints ? 1 : 0);
			int legendWidth = Math.min(168, image.getWidth() - 16);
			int legendHeight = 18 + rowCount * 20;
			int x = 8;
			int y = image.getHeight() - legendHeight - 8;
			graphics.setColor(new Color(0xee111111, true));
			graphics.fillRoundRect(x, y, legendWidth, legendHeight, 6, 6);
			graphics.setColor(new Color(0xffffffff, true));
			graphics.drawRoundRect(x, y, legendWidth, legendHeight, 6, 6);
			graphics.drawString("LEGEND", x + 8, y + 14);
			int rowY = y + 27;
			graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, 11.0F));
			if (includePlayer) {
				drawLegendRow(graphics, x + 10, rowY, PLAYER_MARKER_COLOR, "Player + facing arrow");
				rowY += 20;
			}
			if (includeWaypoints) {
				drawLegendRow(graphics, x + 10, rowY, 0xff33aaff, "Waypoint");
			}
		}
		finally {
			graphics.dispose();
		}
	}

	private static void drawLegendRow(Graphics2D graphics, int x, int centerY, int color, String label) {
		graphics.setColor(Color.WHITE);
		graphics.fillOval(x - 5, centerY - 5, 10, 10);
		graphics.setColor(Color.BLACK);
		graphics.drawOval(x - 5, centerY - 5, 10, 10);
		graphics.setColor(new Color(color, true));
		graphics.fillOval(x - 4, centerY - 4, 8, 8);
		graphics.setColor(Color.WHITE);
		graphics.drawString(label, x + 13, centerY + 4);
		graphics.setColor(new Color(color, true));
		graphics.fillRect(x, centerY, 1, 1);
	}

	private static boolean isInside(BufferedImage image, int x, int y) {
		return x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight();
	}

	private static ImageBounds nonTransparentBounds(BufferedImage image, int minX, int minY, int maxXExclusive, int maxYExclusive) {
		ImageBounds bounds = new ImageBounds();
		for (int y = minY; y < maxYExclusive; y++) {
			for (int x = minX; x < maxXExclusive; x++) {
				if (((image.getRGB(x, y) >>> 24) & 0xff) != 0) {
					bounds.include(x, y, x, y);
				}
			}
		}
		return bounds;
	}

	private static BufferedImage cropToBounds(BufferedImage image, ImageBounds bounds) {
		if (bounds.isEmpty()) {
			return image;
		}
		int minX = Math.max(0, bounds.minX());
		int minY = Math.max(0, bounds.minY());
		int maxX = Math.min(image.getWidth() - 1, bounds.maxX());
		int maxY = Math.min(image.getHeight() - 1, bounds.maxY());
		if (minX == 0 && minY == 0 && maxX == image.getWidth() - 1 && maxY == image.getHeight() - 1) {
			return image;
		}
		BufferedImage cropped = new BufferedImage(maxX - minX + 1, maxY - minY + 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = cropped.createGraphics();
		try {
			graphics.drawImage(image, 0, 0, cropped.getWidth(), cropped.getHeight(), minX, minY, maxX + 1, maxY + 1, null);
		}
		finally {
			graphics.dispose();
		}
		return cropped;
	}

	static BufferedImage composeCenteredMapImage(
		Path imageDir,
		int originBlockX,
		int originBlockZ,
		int outputSize,
		Integer playerBlockX,
		Integer playerBlockZ,
		Float yawDegrees,
		List<MapWaypoint> waypoints,
		int zoom,
		boolean grid
	) {
		int safeOutputSize = Math.max(1, outputSize);
		int sourceWorldSize = sourceWorldSize(safeOutputSize, zoom);
		BufferedImage image = composeCenteredMapImage(
			imageDir,
			originBlockX,
			originBlockZ,
			sourceWorldSize,
			playerBlockX,
			playerBlockZ,
			yawDegrees,
			waypoints
		);
		if (zoom > 0 && (image.getWidth() != safeOutputSize || image.getHeight() != safeOutputSize)) {
			image = scaleImage(image, safeOutputSize, safeOutputSize);
		}
		if (grid) {
			drawGridOverlay(image, originBlockX, originBlockZ, sourceWorldSize);
		}
		return image;
	}

	private static BufferedImage readRegionImage(Path path) {
		if (!Files.isRegularFile(path)) {
			return null;
		}
		try {
			return ImageIO.read(path.toFile());
		}
		catch (IOException exception) {
			throw new BridgeUnavailableException("map_capture_failed", "Failed to read JourneyMap cached region image");
		}
	}

	private static BufferedImage nativeImageToBufferedImage(NativeImage nativeImage) {
		BufferedImage image = new BufferedImage(nativeImage.getWidth(), nativeImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, nativeImage.getWidth(), nativeImage.getHeight(), nativeImage.getPixels(), 0, nativeImage.getWidth());
		return image;
	}

	private static void annotateApiMapImage(
		BufferedImage image,
		int minWorldX,
		int minWorldZ,
		int maxWorldX,
		int maxWorldZ,
		int playerBlockX,
		int playerBlockZ,
		float yawDegrees,
		List<MapWaypoint> waypoints
	) {
		if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0 || maxWorldX < minWorldX || maxWorldZ < minWorldZ) {
			return;
		}
		ImageBounds contentBounds = new ImageBounds();
		boolean drewWaypoints = false;
		if (waypoints != null) {
			for (MapWaypoint waypoint : waypoints) {
				if (waypoint == null || !waypoint.enabled() || !waypoint.showOnMap()) {
					continue;
				}
				if (waypoint.x() < minWorldX || waypoint.x() > maxWorldX
					|| waypoint.z() < minWorldZ || waypoint.z() > maxWorldZ) {
					continue;
				}
				drawWaypointMarker(image, mapWorldToImageX(image, waypoint.x(), minWorldX, maxWorldX), mapWorldToImageY(image, waypoint.z(), minWorldZ, maxWorldZ), waypoint, contentBounds);
				drewWaypoints = true;
			}
		}
		boolean drewPlayer = false;
		if (playerBlockX >= minWorldX && playerBlockX <= maxWorldX && playerBlockZ >= minWorldZ && playerBlockZ <= maxWorldZ) {
			drawPlayerMarker(image, mapWorldToImageX(image, playerBlockX, minWorldX, maxWorldX), mapWorldToImageY(image, playerBlockZ, minWorldZ, maxWorldZ), yawDegrees, contentBounds);
			drewPlayer = true;
		}
		drawOverlayLegend(image, drewPlayer, drewWaypoints);
	}

	private static int mapWorldToImageX(BufferedImage image, int worldX, int minWorldX, int maxWorldX) {
		double fraction = (worldX - minWorldX) / (double) Math.max(1, maxWorldX - minWorldX);
		return Math.max(0, Math.min(image.getWidth() - 1, (int) Math.round(fraction * (image.getWidth() - 1))));
	}

	private static int mapWorldToImageY(BufferedImage image, int worldZ, int minWorldZ, int maxWorldZ) {
		double fraction = (worldZ - minWorldZ) / (double) Math.max(1, maxWorldZ - minWorldZ);
		return Math.max(0, Math.min(image.getHeight() - 1, (int) Math.round(fraction * (image.getHeight() - 1))));
	}

	private static int sourceWorldSize(int outputSize, int zoom) {
		int safeOutputSize = Math.max(1, outputSize);
		int safeZoom = Math.max(0, Math.min(8, zoom));
		return Math.max(1, safeOutputSize >> safeZoom);
	}

	private static BufferedImage scaleImage(BufferedImage source, int width, int height) {
		BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = scaled.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			graphics.drawImage(source, 0, 0, width, height, null);
		}
		finally {
			graphics.dispose();
		}
		return scaled;
	}

	private static void drawGridOverlay(BufferedImage image, int originBlockX, int originBlockZ, int sourceWorldSize) {
		int safeSourceWorldSize = Math.max(1, sourceWorldSize);
		int minWorldX = originBlockX - safeSourceWorldSize / 2;
		int minWorldZ = originBlockZ - safeSourceWorldSize / 2;
		double pixelsPerBlockX = image.getWidth() / (double) safeSourceWorldSize;
		double pixelsPerBlockZ = image.getHeight() / (double) safeSourceWorldSize;
		for (int worldX = Math.floorDiv(minWorldX, 16) * 16; worldX <= minWorldX + safeSourceWorldSize; worldX += 16) {
			int imageX = (int) Math.round((worldX - minWorldX) * pixelsPerBlockX);
			if (imageX >= 0 && imageX < image.getWidth()) {
				for (int y = 0; y < image.getHeight(); y++) {
					image.setRGB(imageX, y, 0x88000000);
				}
			}
		}
		for (int worldZ = Math.floorDiv(minWorldZ, 16) * 16; worldZ <= minWorldZ + safeSourceWorldSize; worldZ += 16) {
			int imageY = (int) Math.round((worldZ - minWorldZ) * pixelsPerBlockZ);
			if (imageY >= 0 && imageY < image.getHeight()) {
				for (int x = 0; x < image.getWidth(); x++) {
					image.setRGB(x, imageY, 0x88000000);
				}
			}
		}
	}

	private static final class ImageBounds {
		private int minX = Integer.MAX_VALUE;
		private int minY = Integer.MAX_VALUE;
		private int maxX = Integer.MIN_VALUE;
		private int maxY = Integer.MIN_VALUE;

		private void include(int includedMinX, int includedMinY, int includedMaxX, int includedMaxY) {
			minX = Math.min(minX, includedMinX);
			minY = Math.min(minY, includedMinY);
			maxX = Math.max(maxX, includedMaxX);
			maxY = Math.max(maxY, includedMaxY);
		}

		private boolean isEmpty() {
			return minX > maxX || minY > maxY;
		}

		private int minX() {
			return minX;
		}

		private int minY() {
			return minY;
		}

		private int maxX() {
			return maxX;
		}

		private int maxY() {
			return maxY;
		}

		private int width() {
			return maxX - minX + 1;
		}

		private int height() {
			return maxY - minY + 1;
		}
	}

	record MapCaptureGeometry(int outputSize, int zoom, boolean grid) {
	}
}
