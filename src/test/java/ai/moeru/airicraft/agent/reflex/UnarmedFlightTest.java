package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.navigation.CellInfo;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.TerrainView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnarmedFlightTest {
	private static final CellInfo STONE = CellInfo.builder("stone").full().breakTicks(30).build();
	private static final GridPos FEET = new GridPos(0, 64, 0);

	/** Flat stone with its top at y=63 within 40 blocks, unloaded beyond. */
	private interface Ground {
		CellInfo at(int x, int y, int z);
	}

	private static TerrainView terrain(Ground override) {
		return (x, y, z) -> {
			if (Math.abs(x) > 40 || Math.abs(z) > 40) return CellInfo.UNLOADED;
			CellInfo cell = override.at(x, y, z);
			if (cell != null) return cell;
			return y <= 63 ? STONE : CellInfo.AIR;
		};
	}

	/** A zombie standing in the middle of cell (x, z). */
	private static UnarmedFlight.Threat zombie(int x, int z) {
		return new UnarmedFlight.Threat(x + 0.5, z + 0.5, 20F, false, false, Math.hypot(x, z), true);
	}

	@Test
	void anUntouchedFlightKeepsRunningPastItsTimeAFewTimes() {
		assertTrue(UnarmedFlight.keepsRunning(0, 0));
		assertTrue(UnarmedFlight.keepsRunning(0, UnarmedFlight.MAX_EXTENSIONS - 1));
		assertFalse(UnarmedFlight.keepsRunning(0, UnarmedFlight.MAX_EXTENSIONS));
		assertFalse(UnarmedFlight.keepsRunning(1, 0), "a pursuer that lands hits is not being outrun");
	}

	@Test
	void walksAwayOnlyFromCreepersWhenTooHungryToSprint() {
		var pack = List.of(zombie(2, 0), zombie(-2, 0), zombie(0, 2));
		assertEquals(UnarmedFlight.OUTMATCHED, UnarmedFlight.fleeReason(5.0D, 9F, 20F, pack, true));
		assertNull(UnarmedFlight.fleeReason(5.0D, 9F, 20F, pack, false), "walking cannot outpace zombies");
		assertNull(UnarmedFlight.fleeReason(1.0D, 20F, 20F, pack, false));
		var creeper = List.of(new UnarmedFlight.Threat(3, 0, 20F, false, true, 3, true));
		assertEquals("creeper", UnarmedFlight.fleeReason(1.0D, 20F, 20F, creeper, false));
	}

	@Test
	void runsFromAnythingButOneWeakMeleeMobWithoutAWeapon() {
		var zombie = List.of(zombie(3, 0));
		assertEquals("strong_mob", UnarmedFlight.fleeReason(1.0D, 20F, 20F, zombie), "a fist against twenty health");
		assertEquals("strong_mob", UnarmedFlight.fleeReason(3.0D, 20F, 20F, zombie), "a stone pickaxe is no sword");
		assertNull(UnarmedFlight.fleeReason(4.0D, 20F, 20F, zombie), "a wooden sword fights");
		assertNull(UnarmedFlight.fleeReason(7.0D, 20F, 20F, List.of(zombie(3, 0), zombie(-3, 0))), "an axe fights a pack");
		assertEquals("outmatched", UnarmedFlight.fleeReason(7.0D, 6F, 20F, List.of(zombie(3, 0), zombie(-3, 0))),
			"but not at six health");
		assertEquals("outmatched", UnarmedFlight.fleeReason(4.0D, 9F, 20F, zombie), "a wooden sword at nine health");
		assertNull(UnarmedFlight.fleeReason(6.0D, 10F, 20F, zombie), "an iron sword wins in four swings");
		assertEquals("outnumbered", UnarmedFlight.fleeReason(1.0D, 20F, 20F, List.of(zombie(3, 0), zombie(-3, 0))));
		var silverfish = List.of(new UnarmedFlight.Threat(2, 0, 8F, false, false, 2, true));
		assertNull(UnarmedFlight.fleeReason(1.0D, 20F, 20F, silverfish), "trivially beatable near full health");
		assertNull(UnarmedFlight.fleeReason(1.0D, 16F, 20F, silverfish));
		assertEquals("hurt", UnarmedFlight.fleeReason(1.0D, 12F, 20F, silverfish));
		var wornZombie = List.of(new UnarmedFlight.Threat(2, 0, 6F, false, false, 2, true));
		assertNull(UnarmedFlight.fleeReason(1.0D, 20F, 20F, wornZombie), "a zombie beaten down to six health");
		assertEquals("ranged_mob", UnarmedFlight.fleeReason(1.0D, 20F, 20F,
			List.of(new UnarmedFlight.Threat(9, 0, 6F, true, false, 9, true))));
		assertEquals("creeper", UnarmedFlight.fleeReason(1.0D, 20F, 20F,
			List.of(new UnarmedFlight.Threat(3, 0, 6F, false, true, 3, true))));
		assertNull(UnarmedFlight.fleeReason(1.0D, 20F, 20F, List.of()));
	}

	@Test
	void stopsRunningOnceThreatsAreFarOrOutOfSight() {
		assertTrue(UnarmedFlight.clear(List.of()));
		assertTrue(UnarmedFlight.clear(List.of(new UnarmedFlight.Threat(20, 0, 20F, false, false, 20, true))));
		assertFalse(UnarmedFlight.clear(List.of(new UnarmedFlight.Threat(12, 0, 20F, false, false, 12, true))));
		assertTrue(UnarmedFlight.clear(List.of(new UnarmedFlight.Threat(12, 0, 20F, false, false, 12, false))), "lost sight");
		assertFalse(UnarmedFlight.clear(List.of(new UnarmedFlight.Threat(8, 0, 20F, false, false, 8, false))), "too close to hide");
	}

	@Test
	void turnsToFightOnlyALonePursuerThatKeepsHitting() {
		var close = List.of(new UnarmedFlight.Threat(2, 0, 16F, false, false, 2, true));
		assertTrue(UnarmedFlight.outrun(4, close));
		assertFalse(UnarmedFlight.outrun(3, close));
		assertFalse(UnarmedFlight.outrun(4, List.of(zombie(2, 0), zombie(-2, 0))), "a pack is still worse");
		assertFalse(UnarmedFlight.outrun(4, List.of(new UnarmedFlight.Threat(6, 0, 16F, false, false, 6, true))));
	}

	@Test
	void runsStraightAwayOverOpenGround() {
		UnarmedFlight.Leg leg = UnarmedFlight.fleeTarget(terrain((x, y, z) -> null), FEET, List.of(zombie(3, 0)), 20);
		assertNotNull(leg);
		assertEquals(new GridPos(-20, 64, 0), leg.stand());
		assertEquals(0, leg.waterColumns());
		assertTrue(leg.alignment() > 0.99D);
	}

	@Test
	void goesAroundALakeRatherThanThroughIt() {
		// A lake strip west of x=-4, three blocks either side of the straight way out.
		TerrainView lake = terrain((x, y, z) -> x <= -4 && Math.abs(z) <= 3 && y >= 61 && y <= 63 ? CellInfo.WATER : null);
		UnarmedFlight.Leg leg = UnarmedFlight.fleeTarget(lake, FEET, List.of(zombie(3, 0)), 20);
		assertNotNull(leg);
		assertEquals(0, leg.waterColumns(), "a dry diagonal beats wading");
		assertTrue(leg.stand().x() < 0 && Math.abs(leg.stand().z()) > 3, leg.toString());
		assertEquals(64, leg.stand().y());
	}

	@Test
	void neverRunsPastAThreatOrIntoLava() {
		// Pinned between two zombies east and west: only north or south lead away from both.
		UnarmedFlight.Leg leg = UnarmedFlight.fleeTarget(terrain((x, y, z) -> null), FEET, List.of(zombie(4, 0), zombie(-4, 0)), 20);
		assertNotNull(leg);
		assertTrue(Math.abs(leg.stand().z()) > 2 * Math.abs(leg.stand().x()), leg.toString());
		// Lava fills the whole west half: the flight takes the side away from it, even sideways to the zombie.
		TerrainView lava = terrain((x, y, z) -> x <= -3 && y == 63 ? CellInfo.LAVA : null);
		UnarmedFlight.Leg dry = UnarmedFlight.fleeTarget(lava, FEET, List.of(zombie(3, 0)), 20);
		assertNotNull(dry);
		assertTrue(dry.stand().x() > -3, dry.toString());
	}

	@Test
	void doesNotDropOffACliffDeeperThanThree() {
		// West of x=-6 the ground falls six blocks.
		TerrainView cliff = terrain((x, y, z) -> x <= -6 && y >= 58 && y <= 63 ? CellInfo.AIR : null);
		UnarmedFlight.Leg leg = UnarmedFlight.fleeTarget(cliff, FEET, List.of(zombie(3, 0)), 20);
		assertNotNull(leg);
		assertTrue(leg.stand().x() > -6, leg.toString());
		assertEquals(64, leg.stand().y());
		// Three blocks down is fine.
		TerrainView step = terrain((x, y, z) -> x <= -6 && y >= 61 && y <= 63 ? CellInfo.AIR : null);
		UnarmedFlight.Leg down = UnarmedFlight.fleeTarget(step, FEET, List.of(zombie(3, 0)), 20);
		assertEquals(new GridPos(-20, 61, 0), down.stand());
	}

	@Test
	void findsNoLegOutOfAPit() {
		TerrainView pit = terrain((x, y, z) -> (x != 0 || z != 0) && y <= 67 ? STONE : null);
		assertNull(UnarmedFlight.fleeTarget(pit, FEET, List.of(zombie(0, 2)), 20));
	}

	@Test
	void aLegIsOverOnceAThreatGetsNearerItsEnd() {
		var end = new GridPos(-20, 64, 0);
		assertFalse(UnarmedFlight.threatNearer(end, 0.5, 0.5, List.of(zombie(3, 0))));
		assertTrue(UnarmedFlight.threatNearer(end, 0.5, 0.5, List.of(zombie(-10, 2))));
	}

	@Test
	void triesAgainAfterNoEscapeRouteButKeepsOtherVerdicts() {
		assertFalse(UnarmedFlight.retryable("no_escape_route", 100, 159, 0), "too soon: the body has hardly moved");
		assertTrue(UnarmedFlight.retryable("no_escape_route", 100, 160, 0));
		assertFalse(UnarmedFlight.retryable("no_escape_route", 100, 400, UnarmedFlight.MAX_RETRIES), "a few retries an episode");
		for (String reason : List.of("armed", "outrun", "timeout", "navigation_unavailable"))
			assertFalse(UnarmedFlight.retryable(reason, 100, 10_000, 0), reason);
	}

	@Test
	void aRetriedFlightCountsItsRetriesUntilTheEpisodeEnds() {
		UnarmedFlight flight = new UnarmedFlight();
		flight.start(0, Long.MIN_VALUE);
		flight.abandon("no_escape_route", 49, null);
		assertTrue(flight.abandoned());
		assertFalse(flight.mayRetry(60));
		assertTrue(flight.mayRetry(109));
		flight.start(109, Long.MIN_VALUE);
		assertFalse(flight.abandoned());
		assertEquals(1, flight.retries());
		flight.abandon("outrun", 200, null);
		assertFalse(flight.mayRetry(10_000), "outrun stands for the episode");
		flight.reset(null);
		assertFalse(flight.abandoned());
		assertEquals(0, flight.retries());
	}

	@Test
	void flightRoutesSprintButNeverEditTerrainOrDropFar() {
		assertTrue(UnarmedFlight.OPTIONS.noEdits());
		assertFalse(UnarmedFlight.OPTIONS.walkOnly(), "walkOnly would forbid sprinting");
		assertEquals(3, UnarmedFlight.OPTIONS.maxSafeFall());
		assertEquals(UnarmedFlight.WATER_PENALTY, UnarmedFlight.OPTIONS.waterPenalty());
	}
}
