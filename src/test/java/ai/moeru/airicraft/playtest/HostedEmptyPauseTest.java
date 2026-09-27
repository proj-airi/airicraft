package ai.moeru.airicraft.playtest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static ai.moeru.airicraft.playtest.HostedEmptyPause.Action.*;

class HostedEmptyPauseTest {
	@Test void freezesEmptyHostAndResumesOnJoinWithoutRepeatedCommands() {
		var pause = new HostedEmptyPause();
		assertEquals(FREEZE, pause.update(true, false, true));
		assertEquals(NONE, pause.update(true, true, true));
		assertEquals(RESUME, pause.update(false, true, true));
		assertEquals(NONE, pause.update(false, false, true));
		assertEquals(FREEZE, pause.update(true, false, true));
	}
	@Test void waitsForStartupBeforeFreezingAnEmptyHost() {
		var pause = new HostedEmptyPause();
		for (int tick = 0; tick < 1000; tick++) {
			assertEquals(NONE, pause.update(true, false, false));
		}
		assertEquals(FREEZE, pause.update(true, false, true));
		assertEquals(RESUME, pause.update(false, true, true));
		assertEquals(FREEZE, pause.update(true, false, true));
	}
	@Test void doesNotUnfreezeAnExistingManualPause() {
		var pause = new HostedEmptyPause();
		assertEquals(NONE, pause.update(true, true, true));
		assertEquals(NONE, pause.update(false, true, true));
	}
}
