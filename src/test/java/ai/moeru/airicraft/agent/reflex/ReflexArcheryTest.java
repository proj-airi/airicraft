package ai.moeru.airicraft.agent.reflex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflexArcheryTest {
	@Test
	void shootsVisibleRangedMobsBetweenSwordAndBowRange() {
		assertTrue(ReflexArchery.worthShooting(true, true, 11.0D, true, true));
		assertFalse(ReflexArchery.worthShooting(true, false, 11.0D, true, true), "no shot without line of sight");
		assertFalse(ReflexArchery.worthShooting(true, true, 3.0D, true, true), "the sword is better up close");
		assertFalse(ReflexArchery.worthShooting(true, true, 40.0D, true, true), "too far to hit");
		assertFalse(ReflexArchery.worthShooting(false, true, 11.0D, true, true), "melee mobs come to the sword");
		assertFalse(ReflexArchery.worthShooting(true, true, 11.0D, true, false), "no arrows");
	}
}
