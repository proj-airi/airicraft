package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaterStallRecoveryTest {
	@Test
	void stationaryWaterTriggersRecoveryAtThreshold() {
		WaterStallRecovery recovery = new WaterStallRecovery();
		WaterStallRecovery.Sample sample = new WaterStallRecovery.Sample(true, 4.0D, 62.0D, -8.0D);

		assertEquals(WaterStallRecovery.Decision.NONE, recovery.observe(10L, sample, true));
		assertEquals(WaterStallRecovery.Decision.NONE, recovery.observe(69L, sample, true));
		assertEquals(WaterStallRecovery.Decision.RAISE_AND_REPLAN, recovery.observe(70L, sample, true));
	}

	@Test
	void meaningfulProgressRestartsStallWindow() {
		WaterStallRecovery recovery = new WaterStallRecovery();

		assertEquals(WaterStallRecovery.Decision.NONE,
			recovery.observe(0L, new WaterStallRecovery.Sample(true, 0.0D, 62.0D, 0.0D), true));
		assertEquals(WaterStallRecovery.Decision.NONE,
			recovery.observe(50L, new WaterStallRecovery.Sample(true, 1.0D, 62.0D, 0.0D), true));
		assertEquals(WaterStallRecovery.Decision.NONE,
			recovery.observe(60L, new WaterStallRecovery.Sample(true, 1.0D, 62.0D, 0.0D), true));
		assertEquals(WaterStallRecovery.Decision.RAISE_AND_REPLAN,
			recovery.observe(110L, new WaterStallRecovery.Sample(true, 1.0D, 62.0D, 0.0D), true));
	}

	@Test
	void stationaryWaterDoesNotTriggerWhileNavigationHasNoActivePath() {
		WaterStallRecovery recovery = new WaterStallRecovery();
		WaterStallRecovery.Sample sample = new WaterStallRecovery.Sample(true, 0.0D, 62.0D, 0.0D);

		assertEquals(WaterStallRecovery.Decision.NONE, recovery.observe(0L, sample, false));
		assertEquals(WaterStallRecovery.Decision.NONE, recovery.observe(120L, sample, false));
	}

	@Test
	void recoveryEndsAfterLeavingWaterOrMovingTwoBlocks() {
		WaterStallRecovery recovery = activatedRecovery();

		assertEquals(WaterStallRecovery.Decision.NONE,
			recovery.observe(61L, new WaterStallRecovery.Sample(true, 1.0D, 62.0D, 0.0D), true));
		assertEquals(WaterStallRecovery.Decision.RESTORE,
			recovery.observe(62L, new WaterStallRecovery.Sample(true, 2.0D, 62.0D, 0.0D), true));

		recovery = activatedRecovery();
		assertEquals(WaterStallRecovery.Decision.RESTORE,
			recovery.observe(61L, new WaterStallRecovery.Sample(false, 0.0D, 62.0D, 0.0D), true));
	}

	private static WaterStallRecovery activatedRecovery() {
		WaterStallRecovery recovery = new WaterStallRecovery();
		WaterStallRecovery.Sample sample = new WaterStallRecovery.Sample(true, 0.0D, 62.0D, 0.0D);
		recovery.observe(0L, sample, true);
		recovery.observe(WaterStallRecovery.STALL_TICKS, sample, true);
		return recovery;
	}
}
