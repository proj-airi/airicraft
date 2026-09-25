package ai.moeru.airicraft;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiricraftConfigLoaderTest {
	@Test
	void fromMapReadsChatRuntimeSettings() {
		AiricraftConfig defaults = AiricraftConfig.defaults();

		AiricraftConfig parsed = AiricraftConfigLoader.fromMap(Map.of(
			"socialChatMaxDistanceBlocks", 96,
			"readSystemChatMessages", false,
			"enableProactiveSocialMode", true,
			"suppressAutoPauseOnFocusLost", false,
			"blockInteractionDelayTicks", 4,
			"cameraLerpDefaultTicks", 7
		), defaults);

		assertEquals(96, parsed.socialChatMaxDistanceBlocks());
		assertFalse(parsed.readSystemChatMessages());
		assertTrue(parsed.enableProactiveSocialMode());
		assertFalse(parsed.suppressAutoPauseOnFocusLost());
		assertEquals(4, parsed.blockInteractionDelayTicks());
		assertEquals(7, parsed.cameraLerpDefaultTicks());
		assertEquals(8765, parsed.debugDashboard().basePort());
		assertEquals(64L * 1024L * 1024L, parsed.debugDashboard().historyByteBudget());
	}

	@Test
	void navigationBackendDefaultsToBaritoneAndReadsAiricraft() {
		assertEquals("baritone", AiricraftConfigLoader.fromMap(Map.of(), AiricraftConfig.defaults()).navigationBackend());
		AiricraftConfig parsed = AiricraftConfigLoader.fromMapStrict(Map.of("navigation", Map.of("backend", "Airicraft")),
			AiricraftConfig.defaults());
		assertEquals("airicraft", parsed.navigationBackend());
	}

	@Test
	void strictLoadRejectsAnUnknownNavigationBackend() {
		assertThrows(IllegalArgumentException.class, () -> AiricraftConfigLoader.fromMapStrict(
			Map.of("navigation", Map.of("backend", "pathfinder")), AiricraftConfig.defaults()));
		assertEquals("baritone", AiricraftConfigLoader.fromMap(
			Map.of("navigation", Map.of("backend", "pathfinder")), AiricraftConfig.defaults()).navigationBackend());
	}

	@Test
	void fromMapReadsDebugDashboardSettings() {
		AiricraftConfig parsed = AiricraftConfigLoader.fromMap(Map.of(
			"debugDashboard", Map.of(
				"enabled", false,
				"basePort", 9100,
				"portScanLimit", 8,
				"historyMegabytes", 64,
				"visualCaptureEnabled", true,
				"visualCaptureIntervalTicks", 40
			)
		), AiricraftConfig.defaults());

		assertFalse(parsed.debugDashboard().enabled());
		assertEquals(9100, parsed.debugDashboard().basePort());
		assertEquals(8, parsed.debugDashboard().portScanLimit());
		assertEquals(64L * 1024L * 1024L, parsed.debugDashboard().historyByteBudget());
		assertTrue(parsed.debugDashboard().visualCaptureEnabled());
		assertEquals(40, parsed.debugDashboard().visualCaptureIntervalTicks());
	}

	@Test
	void fromMapDefaultsSuppressAutoPauseOnFocusLostWhenAbsent() {
		AiricraftConfig defaults = AiricraftConfig.defaults();

		AiricraftConfig parsed = AiricraftConfigLoader.fromMap(Map.of(), defaults);

		assertEquals(defaults.suppressAutoPauseOnFocusLost(), parsed.suppressAutoPauseOnFocusLost());
		assertEquals(defaults.blockInteractionDelayTicks(), parsed.blockInteractionDelayTicks());
		assertEquals(defaults.cameraLerpDefaultTicks(), parsed.cameraLerpDefaultTicks());
	}

	@Test
	void fromMapClampsNegativeInteractionDelays() {
		AiricraftConfig parsed = AiricraftConfigLoader.fromMap(Map.of(
			"blockInteractionDelayTicks", -5,
			"cameraLerpDefaultTicks", -3
		), AiricraftConfig.defaults());

		assertEquals(0, parsed.blockInteractionDelayTicks());
		assertEquals(0, parsed.cameraLerpDefaultTicks());
	}

	@Test
	void fromMapStrictRejectsInvalidBoolean() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
			AiricraftConfigLoader.fromMapStrict(Map.of(
				"readSystemChatMessages", "sometimes"
			), AiricraftConfig.defaults())
		);

		assertEquals("readSystemChatMessages must be true or false", exception.getMessage());
	}
}
