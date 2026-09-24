package ai.moeru.airicraft.sim.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LookClampTest {
	@Test
	void clampsWithinLimits() {
		assertEquals(30f, LookClamp.clampDelta(30f, 45f));
		assertEquals(-30f, LookClamp.clampDelta(-30f, 45f));
	}

	@Test
	void clampsBeyondLimits() {
		assertEquals(45f, LookClamp.clampDelta(90f, 45f));
		assertEquals(-45f, LookClamp.clampDelta(-90f, 45f));
	}

	@Test
	void wrapsDegrees() {
		assertEquals(170f, LookClamp.wrapDegrees(170f));
		assertEquals(-170f, LookClamp.wrapDegrees(190f));
		assertEquals(180f, LookClamp.wrapDegrees(540f), 1e-4);
		assertEquals(0f, LookClamp.wrapDegrees(360f), 1e-4);
	}

	@Test
	void moveTowardCapsTurnRate() {
		// Wants to turn 100 deg right but capped at 45.
		assertEquals(45f, LookClamp.moveToward(0f, 100f, 45f), 1e-4);
	}

	@Test
	void moveTowardWrapsAcross180() {
		// From -170 to +170 is -20 deg across the wrap, not +340.
		float out = LookClamp.moveToward(-170f, 170f, 45f);
		assertEquals(170f, out, 1e-4);
	}

	@Test
	void moveTowardReachesTargetInsideLimit() {
		assertEquals(30f, LookClamp.moveToward(0f, 30f, 45f), 1e-4);
	}

	@Test
	void yawConvention() {
		// yaw 0 = +Z. Target due +Z of player -> yaw ~0; due +X -> yaw -90.
		assertTrue(Math.abs(LookClamp.yawTo(0, 0, 0, 10)) < 1e-3);
		assertTrue(Math.abs(LookClamp.yawTo(0, 0, 10, 0) - (-90f)) < 1e-3);
	}
}
