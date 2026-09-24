package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade.NavigationProgress;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationStallWatchdogTest {
	@Test void interruptedBreakingOfSameBlockStillTimesOut() {
		var watchdog = new NavigationStallWatchdog();
		for (int tick = 0; tick < 100; tick++) {
			assertFalse(watchdog.observe(tick, new NavigationProgress(0, 64, 0, true,
				tick % 2 == 0 ? "stone" : null, tick % 2 == 0 ? 0.1f : 0)));
		}
		assertTrue(watchdog.observe(100, new NavigationProgress(0, 64, 0, true, "stone", 0.1f)));
	}

	@Test void alternatingUnfinishedBlocksStillTimesOut() {
		var watchdog = new NavigationStallWatchdog();
		for (int tick = 0; tick < 101; tick++) {
			assertFalse(watchdog.observe(tick, new NavigationProgress(0, 64, 0, true,
				tick % 2 == 0 ? "left" : "right", 0.1f)));
		}
		assertTrue(watchdog.observe(101, new NavigationProgress(0, 64, 0, true, "right", 0.1f)));
	}

	@Test void jumpsReturningToSameFloorDoNotResetStallBudget() {
		var watchdog = new NavigationStallWatchdog();
		for (int tick=0; tick<100; tick++)
			assertFalse(watchdog.observe(tick, new NavigationProgress(0,tick%2==0?64:65.2,0,tick%2==0,null,0)));
		assertTrue(watchdog.observe(100, new NavigationProgress(0,64,0,true,null,0)));
	}
	@Test void progressingExcavationDoesNotTimeOutWhileStationary() {
		var watchdog = new NavigationStallWatchdog();
		for (int tick=0; tick<200; tick++)
			assertFalse(watchdog.observe(tick, new NavigationProgress(0,64,0,true,"stone",tick/250f)));
		assertTrue(watchdog.observe(300, new NavigationProgress(0,64,0,true,"stone",199/250f)));
	}
	@Test void GroundedAscentAndSwimmingRemainProgress() {
		var watchdog = new NavigationStallWatchdog();
		for (int tick=0; tick<300; tick++)
			assertFalse(watchdog.observe(tick, new NavigationProgress(0,64+tick/50,0,true,null,0)));
	}
	@Test void idleAndPauseResetBudget() {
		var watchdog = new NavigationStallWatchdog();
		assertFalse(watchdog.observe(0,0,64,0));
		watchdog.clear();
		assertFalse(watchdog.observe(1000,0,64,0));
	}
}
