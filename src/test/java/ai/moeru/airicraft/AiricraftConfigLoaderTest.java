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
	void theAiriLinkIsOffByDefaultAndReadsItsBlock() {
		assertFalse(AiricraftConfig.defaults().airi().enabled());
		assertEquals("ws://127.0.0.1:6121/ws", AiricraftConfig.defaults().airi().url());

		AiricraftConfig parsed = AiricraftConfigLoader.fromMapStrict(Map.of(
			"airi", Map.of("enabled", true, "url", "ws://127.0.0.1:7000/ws", "token", " secret ")
		), AiricraftConfig.defaults());

		assertTrue(parsed.airi().enabled());
		assertEquals("ws://127.0.0.1:7000/ws", parsed.airi().url());
		assertEquals("secret", parsed.airi().token());
	}

	@Test
	void anAiriUrlThatIsNotAWebSocketIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> AiricraftConfigLoader.fromMapStrict(
			Map.of("airi", Map.of("url", "http://127.0.0.1:6121")), AiricraftConfig.defaults()));
	}

	@Test
	void anObsoleteNavigationBackendKeyIsIgnored() {
		Map<String, Object> obsolete = Map.of("navigation", Map.of("backend", "navigationFacade"));

		assertEquals(AiricraftConfig.defaults(), AiricraftConfigLoader.fromMap(obsolete, AiricraftConfig.defaults()));
		assertEquals(AiricraftConfig.defaults(), AiricraftConfigLoader.fromMapStrict(obsolete, AiricraftConfig.defaults()));
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
