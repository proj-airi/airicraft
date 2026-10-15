package ai.moeru.airicraft.agent.reflex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightBunkerTest {
	private static final long MIDNIGHT = 18_000L;
	private static final long NOON = 6_000L;

	@Test void nightIsTheShelterWindowFromDuskToJustAfterSunrise() {
		assertFalse(NightBunker.night(NOON));
		assertFalse(NightBunker.night(10_999));
		assertTrue(NightBunker.night(11_000));
		assertTrue(NightBunker.night(MIDNIGHT));
		assertTrue(NightBunker.night(23_999));
		assertTrue(NightBunker.night(299));
		assertFalse(NightBunker.night(300));
		assertTrue(NightBunker.night(24_000L * 7 + MIDNIGHT), "the day count does not matter");
	}

	@Test void threeFlightsAtNightInTheOverworldDigIn() {
		assertEquals(NightBunker.REPEATED_FLIGHT, NightBunker.trigger(true, MIDNIGHT, false, 3, false, false));
		assertNull(NightBunker.trigger(true, MIDNIGHT, false, 2, false, false), "two flights are still worth running");
		assertNull(NightBunker.trigger(true, NOON, false, 5, false, false), "by day the flight stays as it was");
		assertNull(NightBunker.trigger(false, MIDNIGHT, false, 5, true, true), "the Nether and the End have no night to wait out");
	}

	@Test void aGivenUpFlightOrNoSprintDigsInAtOnce() {
		assertEquals(NightBunker.FLIGHT_GAVE_UP, NightBunker.trigger(true, MIDNIGHT, false, 0, true, false));
		assertEquals(NightBunker.CANNOT_SPRINT, NightBunker.trigger(true, MIDNIGHT, false, 0, false, true));
		assertNull(NightBunker.trigger(true, NOON, false, 0, true, true));
		assertTrue(NightBunker.countsAsGivenUp(UnarmedFlight.NO_ESCAPE_ROUTE));
		assertTrue(NightBunker.countsAsGivenUp("outrun"));
		assertTrue(NightBunker.countsAsGivenUp("timeout"));
		assertFalse(NightBunker.countsAsGivenUp("armed"), "a weapon turning up is a reason to fight, not to dig");
	}

	@Test void tasksThatHandleTheNightKeepItToThemselves() {
		assertNull(NightBunker.trigger(true, MIDNIGHT, true, 5, true, true));
		NightBunker night = new NightBunker();
		for (long tick : new long[]{100, 200, 300}) night.flightStarted(tick);
		assertNull(night.update(310, true, MIDNIGHT, true, false), "shelter_for_night, sleep_in_bed or explore_for runs");
		assertEquals(NightBunker.REPEATED_FLIGHT, night.update(311, true, MIDNIGHT, false, false), "once it no longer runs");
		assertNull(night.update(312, true, MIDNIGHT, true, false));
		assertNull(night.due(), "a night task started since takes the night over");
	}

	@Test void flightsCountOnlyWithinTheWindow() {
		NightBunker night = new NightBunker();
		night.flightStarted(0);
		night.flightStarted(1_300);
		night.flightStarted(1_400);
		assertNull(night.update(1_400, true, MIDNIGHT, false, false), "the first one is too old");
		assertEquals(2, night.flightsInWindow());
		night.flightStarted(1_500);
		assertEquals(NightBunker.REPEATED_FLIGHT, night.update(1_500, true, MIDNIGHT, false, false));
		assertNull(night.update(1_501, true, MIDNIGHT, false, false), "reported once");
		assertEquals(NightBunker.REPEATED_FLIGHT, night.due(), "and stays due");
	}

	@Test void aGivenUpFlightCountsWithinTheWindowUnlessItWasAWeapon() {
		NightBunker night = new NightBunker();
		night.flightGaveUp("armed", 10);
		assertNull(night.update(11, true, MIDNIGHT, false, false));
		night.flightGaveUp(UnarmedFlight.NO_ESCAPE_ROUTE, 20);
		assertEquals(NightBunker.FLIGHT_GAVE_UP, night.update(21, true, MIDNIGHT, false, false));
		assertEquals(UnarmedFlight.NO_ESCAPE_ROUTE, night.gaveUpReason());
		NightBunker stale = new NightBunker();
		stale.flightGaveUp("outrun", 0);
		assertNull(stale.update(NightBunker.WINDOW_TICKS + 1, true, MIDNIGHT, false, false), "long ago");
	}

	@Test void morningOrAnotherDimensionClearsIt() {
		NightBunker night = new NightBunker();
		night.flightGaveUp("timeout", 0);
		assertEquals(NightBunker.FLIGHT_GAVE_UP, night.update(1, true, MIDNIGHT, false, false));
		night.attempted();
		assertNull(night.update(2, true, 24_000L + 400, false, false));
		assertNull(night.due(), "morning");
		assertEquals(0, night.attempts());
		assertEquals(NightBunker.FLIGHT_GAVE_UP, night.update(3, true, MIDNIGHT, false, false), "still recent the same night");
		assertNull(night.update(4, false, MIDNIGHT, false, false));
		assertNull(night.due(), "through a portal");
	}

	@Test void aWaitedOutNightStartsCountingAfresh() {
		NightBunker night = new NightBunker();
		for (long tick : new long[]{1, 2, 3}) night.flightStarted(tick);
		night.flightGaveUp("outrun", 3);
		assertEquals(NightBunker.FLIGHT_GAVE_UP, night.update(4, true, MIDNIGHT, false, false));
		night.finished();
		assertNull(night.due());
		assertEquals(0, night.flightsInWindow());
		assertNull(night.update(5, true, MIDNIGHT, false, false));
	}

	@Test void triesTheBunkerOnlyAFewTimesANight() {
		NightBunker night = new NightBunker();
		for (int i = 0; i < NightBunker.MAX_ATTEMPTS; i++) {
			assertTrue(night.mayAttempt());
			night.attempted();
		}
		assertFalse(night.mayAttempt(), "knocked out of its column every time: the old flight and fight take over");
	}

	@Test void digsOnceThePursuersAreShakenOffOrAtOnceWhenTheyCannotBe() {
		double clear = SurvivalReflexRuntime.BUNKER_MELEE_CLEARANCE;
		assertTrue(NightBunker.digNow(false, clear, false));
		assertTrue(NightBunker.digNow(false, Double.POSITIVE_INFINITY, false), "no melee mob at all");
		assertFalse(NightBunker.digNow(false, clear - 0.1D, false), "a zombie that close knocks the body out of its column");
		assertFalse(NightBunker.digNow(true, 15.0D, false), "a running flight is left to finish shaking them off");
		assertTrue(NightBunker.digNow(true, 1.0D, true), "a flight that cannot get away digs in at once");
	}

	/** The flights of death #5: unarmed at night, about every 200 to 800 ticks, each one escaped and found again. */
	@Test void theRecordedNightOfFlightsWouldHaveDugInAtTheThirdWithinTheWindow() {
		long[] fled = {50_029, 50_535, 51_319, 51_535, 51_797, 51_938, 52_307, 52_580};
		NightBunker night = new NightBunker();
		String dueAt = null;
		long dueTick = -1;
		for (long tick : fled) {
			night.flightStarted(tick);
			String reason = night.update(tick + 1, true, MIDNIGHT, false, false);
			if (reason != null && dueAt == null) {
				dueAt = reason;
				dueTick = tick;
			}
		}
		assertEquals(NightBunker.REPEATED_FLIGHT, dueAt);
		assertEquals(51_535, dueTick, "50,535, 51,319 and 51,535 fall within 1,200 ticks");
	}
}
