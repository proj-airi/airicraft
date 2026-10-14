package ai.moeru.airicraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderOutagePauseTest {
	@Test
	void holdsOnlyADegradedSingleplayerGameWithNoOtherPlayers() {
		assertTrue(ProviderOutagePause.eligible(true, 1, true, true, false));
		assertFalse(ProviderOutagePause.eligible(true, 1, false, true, false), "a healthy planner never holds the game");
		assertFalse(ProviderOutagePause.eligible(true, 2, true, true, false), "another player on a LAN world keeps playing");
		assertFalse(ProviderOutagePause.eligible(false, 0, true, true, false), "a remote server cannot be paused");
		assertFalse(ProviderOutagePause.eligible(true, 1, true, false, false), "a disabled planner is not waiting on a provider");
		assertFalse(ProviderOutagePause.eligible(true, 1, true, true, true), "an external driver owns the game");
	}
}
