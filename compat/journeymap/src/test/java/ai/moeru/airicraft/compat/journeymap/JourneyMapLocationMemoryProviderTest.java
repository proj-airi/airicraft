package ai.moeru.airicraft.compat.journeymap;

import ai.moeru.airicraft.agent.memory.LocationMemoryService;
import ai.moeru.airicraft.agent.memory.PlaceMemory;
import com.google.gson.JsonParser;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.common.waypoint.Waypoint;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class JourneyMapLocationMemoryProviderTest {
	@Test
	void coordinatesRemainInThePrimaryDimensionWhenNativeDisplayCoordinatesAreScaled() throws Exception {
		var store = new NativeStore();
		store.nativePoint("home", "Home", "journeymap");
		store.values.get("home").putAll(Map.of("X", 1218, "Z", 24, "DisplayScale", 8));
		assertEquals(152, store.api.getAllWaypoints().getFirst().getX());
		var location = new LocationMemoryService(store.provider()).recall("home", null);
		assertEquals("minecraft:overworld", location.dimension());
		assertEquals(1218, location.x());
		assertEquals(24, location.z());
	}

	@Test
	void crudPersistsOnNativeWaypointsWithIdsMetadataAndDimensions() throws Exception {
		var store = new NativeStore();
		var service = new LocationMemoryService(store.provider());
		var area = new PlaceMemory.PreservedArea(0, 60, 0, 5, 70, 5);
		var home = service.remember(null, new PlaceMemory.Place("home", "minecraft:overworld", 2, 64, 2, "shelter", area));
		var reopened = new LocationMemoryService(store.provider());
		assertEquals(home, reopened.recall(home.id(), null));
		assertEquals(home, reopened.recall(null, "home"));
		assertEquals(true, store.values.get(home.id()).get("Persistent"));
		var renamed = reopened.remember(home.id(), new PlaceMemory.Place("base", "minecraft:the_nether", 4, 80, 9, "moved"));
		assertEquals(home.id(), renamed.id());
		assertNull(renamed.preserveArea());
		assertEquals(List.of(renamed), new LocationMemoryService(store.provider()).list());
		assertEquals(List.of("minecraft:the_nether"), store.values.get(home.id()).get("Dimensions"));
		assertTrue(service.forget(home.id(), null));
		assertFalse(service.forget(home.id(), null));
		assertTrue(reopened.list().isEmpty());
	}

	@Test
	void nativeUiAndDeathWaypointsAreReadAndMutatedWithoutCopiesOrOwnerChanges() throws Exception {
		var store = new NativeStore();
		store.nativePoint("native", "Home", "journeymap");
		store.nativePoint("death", "Last Death", "journeymap");
		store.values.get("death").put("GroupId", "death-group");
		store.values.get("native").put("CustomData", "{\"other\":{\"keep\":42}}");
		var service = new LocationMemoryService(store.provider());
		assertEquals(2, service.list().size());
		assertEquals("", service.recall("death", null).note());
		assertNull(service.recall("death", null).preserveArea());
		service.remember("native", new PlaceMemory.Place("Base", "minecraft:overworld", 3, 66, 5, "note"));
		assertEquals(2, store.values.size());
		assertEquals("journeymap", store.values.get("native").get("ModId"));
		assertEquals(42, JsonParser.parseString((String) store.values.get("native").get("CustomData"))
			.getAsJsonObject().getAsJsonObject("other").get("keep").getAsInt());
		// Native UI writes are visible without a separate synchronization pass.
		store.values.get("native").put("Name", "Edited in JourneyMap");
		store.values.get("native").put("X", 100);
		assertEquals(100, service.recall(null, "Edited in JourneyMap").x());
		assertEquals("note", service.recall("native", null).note());
		assertTrue(service.forget("death", null));
		assertFalse(store.values.containsKey("death"));
		store.nativePoint("next-death", "Last Death", "journeymap");
		assertEquals(2, service.list().size());
		assertEquals("next-death", service.recall(null, "Last Death").id());
	}

	@Test
	void duplicateNamesAndReadinessAreHandledWithoutWrongWorldWrites() throws Exception {
		var store = new NativeStore();
		store.nativePoint("a", "Home", "journeymap");
		store.nativePoint("b", "Home", "journeymap");
		var provider = store.provider();
		var service = new LocationMemoryService(provider);
		assertTrue(assertThrows(IllegalArgumentException.class, () -> service.recall(null, "Home")).getMessage().contains("ambiguous_place"));
		assertEquals("a", service.recall("a", null).id());
		store.ready.set(false);
		assertThrows(IllegalStateException.class, service::list);
		assertThrows(IllegalStateException.class, () -> provider.delete("a"));
		assertThrows(IllegalStateException.class, () -> provider.save("a", new PlaceMemory.Place("Changed", "minecraft:overworld", 0, 64, 0, "")));
		assertEquals("Home", store.values.get("a").get("Name"));
		store.values.clear(); // A different native world/server store is loaded before readiness changes.
		store.nativePoint("world-two", "Other world", "journeymap");
		store.ready.set(true);
		assertEquals(List.of("world-two"), service.list().stream().map(value -> value.id()).toList());
		assertThrows(IllegalArgumentException.class, () -> service.recall("a", null));
	}

	@Test
	void metadataErrorsCannotEraseProtectionOrUnrelatedOpaqueData() {
		var place = new PlaceMemory.Place("Home", "minecraft:overworld", 0, 64, 0, "note",
			new PlaceMemory.PreservedArea(0, 60, 0, 1, 65, 1));
		String serialized = JourneyMapLocationMetadata.write("{\"other\":true}", place);
		assertEquals(place.preserveArea(), JourneyMapLocationMetadata.read(serialized).preserveArea());
		assertEquals("note", JourneyMapLocationMetadata.read(serialized).note());
		assertNull(JourneyMapLocationMetadata.read("opaque custom data").preserveArea());
		assertThrows(IllegalStateException.class, () -> JourneyMapLocationMetadata.write("opaque custom data", place));
		for (String broken : List.of("{\"airicraft:location\":", "{\"airicraft:location\":{\"version\":2}}",
			"{\"airicraft:location\":{\"version\":1,\"preserveArea\":{\"x1\":5,\"x2\":1}}}")) {
			assertThrows(IllegalStateException.class, () -> JourneyMapLocationMetadata.read(broken));
		}
	}

	/** The API returns copies. Saving one preserves its GUID, owner, and unrelated native fields. */
	private static final class NativeStore {
		final Map<String, Map<String, Object>> values = new LinkedHashMap<>();
		final AtomicBoolean ready = new AtomicBoolean(true);
		final IClientAPI api = (IClientAPI) Proxy.newProxyInstance(IClientAPI.class.getClassLoader(), new Class<?>[] { IClientAPI.class }, (proxy, method, args) -> {
			return switch (method.getName()) {
				case "getAllWaypoints" -> values.values().stream().map(value -> waypoint(new HashMap<>(value))).toList();
				case "addWaypoint" -> {
					Waypoint value = (Waypoint) args[1];
					@SuppressWarnings("unchecked") var fields = (Map<String, Object>) valueFor(value);
					values.put(value.getGuid(), new HashMap<>(fields));
					yield null;
				}
				case "removeWaypoint" -> { values.remove(((Waypoint) args[1]).getGuid()); yield null; }
				default -> throw new UnsupportedOperationException(method.getName());
			};
		});

		JourneyMapLocationMemoryProvider provider() {
			return new JourneyMapLocationMemoryProvider(api, ready::get, place -> {
				var fields = fields(UUID.randomUUID().toString(), place.name(), "airicraft");
				return waypoint(fields);
			});
		}
		void nativePoint(String id, String name, String owner) { values.put(id, fields(id, name, owner)); }
		private static Map<String, Object> fields(String id, String name, String owner) {
			return new HashMap<>(Map.of("Guid", id, "Name", name, "ModId", owner, "PrimaryDimension", "minecraft:overworld", "X", 1, "Y", 64, "Z", 2));
		}
		private static Object valueFor(Waypoint waypoint) { return ((Fields) Proxy.getInvocationHandler(waypoint)).values; }
		private static Waypoint waypoint(Map<String, Object> fields) {
			return (Waypoint) Proxy.newProxyInstance(Waypoint.class.getClassLoader(), new Class<?>[] { Waypoint.class }, new Fields(fields));
		}
	}

	private record Fields(Map<String, Object> values) implements java.lang.reflect.InvocationHandler {
		@Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
			String name = method.getName();
			if (name.equals("getBlockPos")) return new net.minecraft.core.BlockPos((int) values.get("X"), (int) values.get("Y"), (int) values.get("Z"));
			if (name.equals("getX") || name.equals("getZ")) return (int) values.get(name.substring(3)) / (int) values.getOrDefault("DisplayScale", 1);
			if (name.equals("setPos")) { values.put("X", args[0]); values.put("Y", args[1]); values.put("Z", args[2]); return null; }
			if (name.startsWith("set")) { values.put(name.substring(3), args[0]); return null; }
			if (name.startsWith("get")) return values.get(name.substring(3));
			if (name.startsWith("is")) return values.getOrDefault(name.substring(2), false);
			throw new UnsupportedOperationException(name);
		}
	}
}
