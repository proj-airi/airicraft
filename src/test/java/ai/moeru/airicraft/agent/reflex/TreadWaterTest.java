package ai.moeru.airicraft.agent.reflex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreadWaterTest {
	@Test
	void anIdleBodyAfloatOrUnderTreadsWater() {
		assertTrue(TreadWater.shouldTread(true, true, true, false));
		assertTrue(TreadWater.shouldTread(true, true, false, false));
		assertTrue(TreadWater.shouldTread(true, true, true, true));
	}

	@Test
	void wadingInShallowWaterOrWorkingDoesNot() {
		assertFalse(TreadWater.shouldTread(true, true, false, true));
		assertFalse(TreadWater.shouldTread(false, true, true, false));
		assertFalse(TreadWater.shouldTread(true, false, false, false));
	}
}
