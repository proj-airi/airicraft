package ai.moeru.airicraft.agent.reflex;

import org.junit.jupiter.api.Test;

import static ai.moeru.airicraft.agent.reflex.CreeperEvasion.Response.BLOCK;
import static ai.moeru.airicraft.agent.reflex.CreeperEvasion.Response.NONE;
import static ai.moeru.airicraft.agent.reflex.CreeperEvasion.Response.RUN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreeperEvasionTest {
	/** {@code Creeper.getSwelling(1)} after {@code swell} ticks of fuse. */
	private static float fuse(int swell) {
		return swell / CreeperEvasion.SWELL_SCALE;
	}

	private static CreeperEvasion.Response respond(double distance, int swellDir, int swell, boolean charged,
		boolean lineOfSight, boolean shield, int shieldUpTicks, boolean moving) {
		return CreeperEvasion.response(distance, swellDir, fuse(swell), charged, lineOfSight, shield, shieldUpTicks, true, moving);
	}

	@Test
	void readsTheTicksLeftOffTheFuse() {
		assertEquals(30, CreeperEvasion.fuseTicksLeft(0F));
		assertEquals(29, CreeperEvasion.fuseTicksLeft(fuse(1)));
		assertEquals(2, CreeperEvasion.fuseTicksLeft(1F), "getSwelling reaches 1 two ticks before the blast");
		assertEquals(0, CreeperEvasion.fuseTicksLeft(fuse(30)));
	}

	@Test
	void runsAtOnceWhenTheFuseLights() {
		// Lit as the body came within 3 blocks: a sprint gets past 7 blocks long before the blast, shield or not.
		assertEquals(RUN, respond(2.8, 1, 1, false, true, true, 0, false));
		assertEquals(RUN, respond(2.8, 1, 1, false, true, false, 0, false));
		// No stepping about first: the very first lit tick runs, whatever the attack cooldown or a previous escape.
		assertEquals(RUN, respond(2.0, 1, 0, false, true, false, 0, false));
		// Swelling but winding down (out of sight, or a hit pushed it away): keep the gap growing, shield down.
		assertEquals(RUN, respond(3.0, -1, 14, false, true, true, 0, false));
		assertEquals(RUN, respond(3.0, -1, 14, false, true, true, 4, false), "a shield is lowered once the fuse winds down");
	}

	@Test
	void leavesAnUnlitOrFarCreeperToTheOrdinaryRules() {
		assertEquals(NONE, respond(2.0, 0, 0, false, true, true, 0, false));
		assertEquals(NONE, respond(9.0, 1, 20, false, true, true, 0, false), "past 8 blocks its fuse winds down by itself");
		assertEquals(RUN, respond(9.0, 1, 20, true, true, true, 0, false), "a charged blast still kills at 9 blocks");
		assertEquals(NONE, respond(12.5, 1, 20, true, true, true, 0, false));
	}

	@Test
	void raisesTheShieldOnlyWhenTheRunCannotClearAndItStillComesUpInTime() {
		// Death #4: lit with the body at 1.93 blocks, 22 ticks left. A standing start cannot get past 7 blocks.
		assertEquals(BLOCK, respond(1.93, 1, 8, false, true, true, 0, false));
		assertEquals(RUN, respond(1.93, 1, 8, false, true, false, 0, false), "no shield: distance still cuts the blast");
		assertEquals(RUN, respond(1.93, 1, 8, false, false, true, 0, false), "a shield must face the creeper it blocks");
		// Already at speed the same gap clears in fewer ticks.
		assertEquals(RUN, respond(4.0, 1, 14, false, true, true, 0, true));
		assertEquals(BLOCK, respond(4.0, 1, 14, false, true, true, 0, false));
		// The shield needs five ticks up before it blocks, plus two for the server and the client's fuse lag.
		assertEquals(BLOCK, respond(2.0, 1, 23, false, true, true, 0, false), "7 ticks left: just in time");
		assertEquals(RUN, respond(2.0, 1, 24, false, true, true, 0, false), "6 ticks left: too late to raise it");
		// Once up it stays up until the blast, even where it would now be too late to raise it.
		assertEquals(BLOCK, respond(2.0, 1, 28, false, true, true, 6, false));
		assertEquals(BLOCK, respond(2.0, 1, 28, false, false, true, 6, false), "and through a blink of lost sight");
		assertEquals(RUN, respond(2.0, 1, 28, false, true, false, 6, false), "unless the shield is gone");
	}

	@Test
	void trustsARunLessAgainstAChargedCreeper() {
		// The same gap and fuse: an uncharged blast is run from, a charged one blocked, as a short run is fatal.
		assertEquals(RUN, respond(2.5, 1, 4, false, true, true, 0, false));
		assertEquals(BLOCK, respond(2.5, 1, 4, true, true, true, 0, false));
		assertEquals(RUN, respond(2.5, 1, 4, true, true, false, 0, false));
	}

	@Test
	void walkingTakesLongerToClear() {
		assertEquals(RUN, CreeperEvasion.response(2.5, 1, fuse(1), false, true, true, 0, true, false));
		assertEquals(BLOCK, CreeperEvasion.response(2.5, 1, fuse(1), false, true, true, 0, false, false));
		assertTrue(CreeperEvasion.ticksToClear(2.5, false, false) > CreeperEvasion.ticksToClear(2.5, true, false));
		assertEquals(0.0D, CreeperEvasion.ticksToClear(8.0, true, false));
	}

	@Test
	void shieldThresholdLeavesTheActivationTime() {
		assertTrue(CreeperEvasion.LATEST_SHIELD_TICKS > CreeperEvasion.SHIELD_DELAY_TICKS);
		assertTrue(CreeperEvasion.shieldInTime(CreeperEvasion.SHIELD_DELAY_TICKS + 2));
		assertFalse(CreeperEvasion.shieldInTime(CreeperEvasion.SHIELD_DELAY_TICKS + 1));
		assertFalse(CreeperEvasion.shieldInTime(CreeperEvasion.SHIELD_DELAY_TICKS));
		// The run check decides early enough that a failed run still leaves time for the shield.
		for (int swell = 0; swell <= 30; swell++) {
			int left = CreeperEvasion.fuseTicksLeft(fuse(swell));
			for (double distance = 0.5; distance <= 7.0; distance += 0.25) {
				var answer = respond(distance, 1, swell, false, true, true, 0, false);
				if (answer == BLOCK) assertTrue(left >= CreeperEvasion.LATEST_SHIELD_TICKS, swell + " " + distance);
			}
		}
	}

	@Test
	void neverClosesInAndHitsOnlyAnUnlitCreeperThatCameIntoReach() {
		assertTrue(CreeperEvasion.keepAwayDistance(false) > 3.0D + 1.0D, "kept past the 3-block ignition range");
		assertTrue(CreeperEvasion.keepAwayDistance(true) >= CreeperEvasion.keepAwayDistance(false));
		assertTrue(CreeperEvasion.mayHit(2.5, true, 1.0F, 0, 0F));
		assertFalse(CreeperEvasion.mayHit(2.5, true, 0.5F, 0, 0F), "weapon not ready: back off instead");
		assertFalse(CreeperEvasion.mayHit(3.5, true, 1.0F, 0, 0F), "out of reach: never walk up to it");
		assertFalse(CreeperEvasion.mayHit(2.5, true, 1.0F, 1, 0F), "lit: run, do not swing");
		assertFalse(CreeperEvasion.mayHit(2.5, true, 1.0F, -1, fuse(3)), "still winding down");
	}
}
