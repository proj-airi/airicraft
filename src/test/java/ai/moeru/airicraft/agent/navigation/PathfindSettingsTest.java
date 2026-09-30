package ai.moeru.airicraft.agent.navigation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathfindSettingsTest {
	@AfterEach
	void restoreDefaults() {
		PathfindSettings.reset();
	}

	@Test
	void appliesAValidSubsetAndLeavesTheRestAlone() {
		PathfindSettings.ApplyResult result = PathfindSettings.apply(json("{\"allowBreak\":false,\"maxFallHeight\":2,\"waterCost\":9.5}"));

		assertTrue(result.accepted());
		assertEquals(3, result.changed().size());
		PathfindSettings.Values now = PathfindSettings.current();
		assertFalse(now.allowBreak());
		assertEquals(2, now.maxFallHeight());
		assertEquals(9.5, now.waterCost());
		assertEquals(PathfindSettings.Values.DEFAULTS.allowPlace(), now.allowPlace());
		assertEquals(PathfindSettings.Values.DEFAULTS.avoidMobs(), now.avoidMobs());
	}

	@Test
	void aRequestWithOneBadValueChangesNothing() {
		PathfindSettings.ApplyResult result = PathfindSettings.apply(json("{\"allowBreak\":false,\"maxFallHeight\":99}"));

		assertFalse(result.accepted());
		assertTrue(result.error().startsWith("out_of_range maxFallHeight"), result.error());
		assertEquals(PathfindSettings.Values.DEFAULTS, PathfindSettings.current(), "atomic: the valid allowBreak was not applied");
	}

	@Test
	void rejectsUnknownNamesWrongTypesAndFractionalIntegers() {
		assertEquals("unknown_setting allowParkour", PathfindSettings.apply(json("{\"allowParkour\":true}")).error());
		assertTrue(PathfindSettings.apply(json("{\"allowPlace\":\"no\"}")).error().startsWith("invalid_type allowPlace"));
		assertTrue(PathfindSettings.apply(json("{\"maxFallHeight\":2.5}")).error().startsWith("invalid_type maxFallHeight"));
		assertTrue(PathfindSettings.apply(json("{\"waterCost\":-1}")).error().startsWith("out_of_range waterCost"));
		assertFalse(PathfindSettings.apply(new JsonObject()).accepted());
		assertEquals(PathfindSettings.Values.DEFAULTS, PathfindSettings.current());
	}

	@Test
	void resetRestoresTheDefaults() {
		PathfindSettings.apply(json("{\"allowPlace\":false,\"avoidMobs\":false,\"allowInventoryToolSwap\":false}"));
		assertFalse(PathfindSettings.current().allowInventoryToolSwap());

		PathfindSettings.reset();

		assertEquals(PathfindSettings.Values.DEFAULTS, PathfindSettings.current());
	}

	@Test
	void inspectReportsValueDefaultAndMeaningForEverySetting() {
		PathfindSettings.apply(json("{\"maxFallHeight\":5}"));

		Map<String, Object> inspected = PathfindSettings.inspect();

		assertEquals(Set.of("allowBreak", "allowPlace", "maxFallHeight", "waterCost", "avoidMobs", "allowInventoryToolSwap"),
			inspected.keySet());
		@SuppressWarnings("unchecked") Map<String, Object> fall = (Map<String, Object>) inspected.get("maxFallHeight");
		assertEquals(5, fall.get("value"));
		assertEquals(3, fall.get("default"));
		assertFalse(String.valueOf(fall.get("description")).isBlank());
		assertEquals(inspected.keySet(), PathfindSettings.plannerProperties().keySet(), "the schema and the readback describe the same settings");
	}

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}
}
