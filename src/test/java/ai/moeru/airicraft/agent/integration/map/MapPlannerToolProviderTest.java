package ai.moeru.airicraft.agent.integration.map;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.agent.llm.LlmImageAttachment;
import ai.moeru.airicraft.agent.llm.PlannerProviderToolResult;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapPlannerToolProviderTest {
	@Test
	void exposesMapToolsWhenProviderIsAvailable() {
		MapPlannerToolProvider provider = new MapPlannerToolProvider(registrySupplier(new StubMapProvider()));
		PlannerToolRegistry registry = PlannerToolRegistry.of(provider);

		List<String> toolNames = registry.openAiTools().stream().map(MapPlannerToolProviderTest::toolName).toList();

		assertEquals(List.of("take_map_look"), provider.openAiTools().stream().map(MapPlannerToolProviderTest::toolName).toList());
		for (String removed : List.of("inspect_map_waypoints", "set_map_waypoint", "delete_map_waypoint")) {
			org.junit.jupiter.api.Assertions.assertFalse(toolNames.contains(removed));
			org.junit.jupiter.api.Assertions.assertFalse(provider.handles(removed));
			org.junit.jupiter.api.Assertions.assertFalse(provider.promptInstructions().contains(removed));
		}
	}

	@Test
	void takeMapLookReturnsImageAttachment() {
		StubMapProvider mapProvider = new StubMapProvider();
		MapPlannerToolProvider provider = new MapPlannerToolProvider(registrySupplier(mapProvider));
		JsonObject args = new JsonObject();
		args.addProperty("kind", "worldmap");

		PlannerProviderToolResult result = provider.executeResult(new PlannerToolCall("call-map", "take_map_look", args, null, null)).join();

		assertTrue(result.text().contains("Tool result for take_map_look"));
		LlmImageAttachment attachment = result.imageAttachment();
		assertNotNull(attachment);
		assertEquals("image/png", attachment.mimeType());
		assertEquals("auto", attachment.detail());
	}

	@Test
	void takeMapLookPassesOriginCoordinates() {
		StubMapProvider mapProvider = new StubMapProvider();
		MapPlannerToolProvider provider = new MapPlannerToolProvider(registrySupplier(mapProvider));
		JsonObject args = new JsonObject();
		args.addProperty("originX", 128);
		args.addProperty("originZ", -64);

		provider.executeResult(new PlannerToolCall("call-map", "take_map_look", args, null, null)).join();

		assertEquals(128, mapProvider.lastImageRequest.originX());
		assertEquals(-64, mapProvider.lastImageRequest.originZ());
	}

	@Test
	void takeMapLookPassesZoomAndGrid() {
		StubMapProvider mapProvider = new StubMapProvider();
		MapPlannerToolProvider provider = new MapPlannerToolProvider(registrySupplier(mapProvider));
		JsonObject args = new JsonObject();
		args.addProperty("zoom", 2);
		args.addProperty("grid", true);

		provider.executeResult(new PlannerToolCall("call-map", "take_map_look", args, null, null)).join();

		assertEquals(2, mapProvider.lastImageRequest.zoom());
		assertEquals(true, mapProvider.lastImageRequest.grid());
	}

	@Test
	void takeMapLookReturnsStableMapErrorForCaptureFailure() {
		StubMapProvider mapProvider = new StubMapProvider();
		mapProvider.captureFailure = new BridgeUnavailableException("world_not_loaded", "No world is currently loaded");
		MapPlannerToolProvider provider = new MapPlannerToolProvider(registrySupplier(mapProvider));

		PlannerProviderToolResult result = provider.executeResult(new PlannerToolCall("call-map", "take_map_look", new JsonObject(), null, null)).join();

		assertEquals("MAP_UNAVAILABLE: world_not_loaded - No world is currently loaded", result.text());
		assertNull(result.imageAttachment());
	}

	private static Supplier<MapIntegrationRegistry> registrySupplier(MapIntegrationProvider provider) {
		return () -> MapIntegrationRegistry.of(provider);
	}

	private static String toolName(Map<String, Object> tool) {
		@SuppressWarnings("unchecked")
		Map<String, Object> function = (Map<String, Object>) tool.get("function");
		return (String) function.get("name");
	}

	private static final class StubMapProvider implements MapIntegrationProvider {
		private MapImageRequest lastImageRequest;
		private RuntimeException captureFailure;

		@Override
		public String id() {
			return "journeymap";
		}

		@Override
		public boolean available() {
			return true;
		}

		@Override
		public MapCapabilities capabilities() {
			return MapCapabilities.of(
				MapCapabilities.MapCapability.READ_WAYPOINTS,
				MapCapabilities.MapCapability.WRITE_WAYPOINTS,
				MapCapabilities.MapCapability.DELETE_WAYPOINTS,
				MapCapabilities.MapCapability.WORLDMAP_IMAGE
			);
		}

		@Override
		public List<MapWaypoint> listWaypoints(MapWaypointQuery query) {
			return List.of(new MapWaypoint(id(), "guid-1", "Home", "minecraft:overworld", 1, 64, 2, 0x3366ff, true, true, true));
		}

		@Override
		public MapWaypoint upsertWaypoint(MapWaypointWrite request) {
			return new MapWaypoint(id(), "guid-1", request.name(), request.dimension(), request.x(), request.y(), request.z(), 0x3366ff, true, true, true);
		}

		@Override
		public boolean deleteWaypoint(String waypointId) {
			return true;
		}

		@Override
		public CompletableFuture<MapImageCapture> captureMap(MapImageRequest request) {
			lastImageRequest = request;
			if (captureFailure != null) {
				return CompletableFuture.failedFuture(captureFailure);
			}
			return CompletableFuture.completedFuture(new MapImageCapture(id(), "worldmap", "png", 1, 1, 100L, new byte[] {1, 2, 3}));
		}
	}
}
