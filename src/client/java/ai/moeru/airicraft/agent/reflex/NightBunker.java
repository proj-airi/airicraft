package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.tasks.ShelterForNightTaskExecutor;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * When the reflex digs in for the night instead of running again. A playtest body that respawned with nothing was left
 * on the overworld surface at night: with no weapon it fled from zombies about twenty-five times in a row, each flight
 * ending {@code escaped_unarmed} twenty or thirty blocks on, where the same zombie or a new one found it again. With its
 * food down to five it could no longer sprint, dug in with three zombies six blocks away, was knocked out of the column
 * by the first hit and was punched to death bare-handed. Running on the surface at night only meets more mobs.
 *
 * <p>So in the overworld at night (the {@code shelter_for_night} window: day time 11,000 to 300 after sunrise), unless a
 * running task already handles the night ({@code shelter_for_night}, {@code sleep_in_bed} and {@code explore_for} dig in
 * or sleep themselves), the bunker becomes due after {@value #FLIGHTS} flights within {@value #WINDOW_TICKS} ticks, after
 * a flight was given up (for any reason but a weapon turning up), or when an unarmed body too hungry to sprint faces a
 * melee mob. Once due, the reflex digs {@link EmergencyBunker}'s shaft as soon as no flight is running and no melee mob
 * is within {@link SurvivalReflexRuntime#BUNKER_MELEE_CLEARANCE} blocks, or at once when the pursuers cannot be shaken
 * off; it then stays until morning and climbs out. A bunker that fails is tried again (at most {@value #MAX_ATTEMPTS}
 * times a night). Client thread only, apart from the static rules.
 */
final class NightBunker {
	/** Flights within {@link #WINDOW_TICKS} that make running pointless. */
	static final int FLIGHTS = 3;
	static final long WINDOW_TICKS = 1_200L;
	/** Bunkers started in one night before the reflex stops trying (each failure is a knock out of the column or a slow dig). */
	static final int MAX_ATTEMPTS = 3;
	static final String REPEATED_FLIGHT = "repeated_flight";
	static final String FLIGHT_GAVE_UP = "flight_gave_up";
	static final String CANNOT_SPRINT = "cannot_sprint";

	private final ArrayDeque<Long> flights = new ArrayDeque<>();
	private long gaveUpTick = Long.MIN_VALUE;
	private String gaveUpReason;
	private String due;
	private long dueTick = Long.MIN_VALUE;
	private int attempts;

	/** Night for this rule: the window in which {@code shelter_for_night} digs in rather than answering already_day. */
	static boolean night(long dayTime) {
		return ShelterForNightTaskExecutor.shelterTime(dayTime);
	}

	/**
	 * Why the body should dig in for the night now, or null: only in the overworld at night with no night task running,
	 * after a flight was given up, when it cannot sprint away, or after {@value #FLIGHTS} flights in the window.
	 */
	static String trigger(boolean overworld, long dayTime, boolean taskHandlesNight, int recentFlights, boolean recentlyGaveUp,
		boolean cannotSprint) {
		if (!overworld || !night(dayTime) || taskHandlesNight) return null;
		if (recentlyGaveUp) return FLIGHT_GAVE_UP;
		if (cannotSprint) return CANNOT_SPRINT;
		return recentFlights >= FLIGHTS ? REPEATED_FLIGHT : null;
	}

	/**
	 * Whether to start digging now that the bunker is due: right away when the pursuers cannot be shaken off, otherwise
	 * once no flight is running and the nearest melee mob is far enough for the dig to finish first.
	 */
	static boolean digNow(boolean flightActive, double nearestMeleeDistance, boolean cannotShake) {
		return cannotShake || !flightActive && SurvivalReflexRuntime.roomToDig(nearestMeleeDistance);
	}

	/** Only a flight that found no way on, was outrun or ran out of time counts; one ended by a weapon turning up does not. */
	static boolean countsAsGivenUp(String reason) {
		return reason != null && !"armed".equals(reason);
	}

	void flightStarted(long tick) {
		flights.addLast(tick);
	}

	void flightGaveUp(String reason, long tick) {
		if (!countsAsGivenUp(reason)) return;
		gaveUpReason = reason;
		gaveUpTick = tick;
	}

	/**
	 * Reads the conditions for this tick. Returns the reason when the bunker became due on this tick (for the event),
	 * else null. Day, another dimension or a night task clears it.
	 */
	String update(long tick, boolean overworld, long dayTime, boolean taskHandlesNight, boolean cannotSprint) {
		while (!flights.isEmpty() && tick - flights.peekFirst() > WINDOW_TICKS) flights.removeFirst();
		boolean recentlyGaveUp = gaveUpTick != Long.MIN_VALUE && tick - gaveUpTick <= WINDOW_TICKS;
		if (!overworld || !night(dayTime) || taskHandlesNight) {
			due = null;
			attempts = 0;
			return null;
		}
		if (due != null) return null;
		String reason = trigger(true, dayTime, false, flights.size(), recentlyGaveUp, cannotSprint);
		if (reason == null) return null;
		due = reason;
		dueTick = tick;
		return reason;
	}

	/** The reason the bunker is due, or null. */
	String due() {
		return due;
	}

	boolean mayAttempt() {
		return attempts < MAX_ATTEMPTS;
	}

	void attempted() {
		attempts++;
	}

	int attempts() {
		return attempts;
	}

	int flightsInWindow() {
		return flights.size();
	}

	String gaveUpReason() {
		return gaveUpReason;
	}

	/** The night was waited out: start counting afresh. */
	void finished() {
		flights.clear();
		gaveUpTick = Long.MIN_VALUE;
		gaveUpReason = null;
		due = null;
		dueTick = Long.MIN_VALUE;
		attempts = 0;
	}

	void reset() {
		finished();
	}

	Map<String, Object> evidence() {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("due", due);
		evidence.put("dueTick", dueTick == Long.MIN_VALUE ? null : dueTick);
		evidence.put("flights", flights.size());
		evidence.put("gaveUpReason", gaveUpReason);
		evidence.put("attempts", attempts);
		return evidence;
	}
}
