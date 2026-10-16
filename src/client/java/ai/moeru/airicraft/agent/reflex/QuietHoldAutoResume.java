package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.job.ActiveJobType;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Hands a quiet survival hold back without a planner turn. A night in a dug shelter meets zombies one at a time, and
 * every fight the reflex wins leaves the paused job held until someone says {@code continue}; a planner that missed one
 * wake (or was held by the loop watchdog or degraded) left the body idle for the rest of the night. The self-contained
 * System 1 tasks handle their own danger and resume with their state after a reflex pause, so a routine fight does not
 * need a decision to carry on.
 *
 * <p>A hold is quiet while the reflex no longer owns actuation, no planner tactic is pending, the snapshot lists no
 * threat (nothing hostile within the reflex's observation range) and the body is alive. After {@value #QUIET_TICKS}
 * quiet ticks a paused job of a {@link #SELF_CONTAINED} type resumes, the way {@code continue} resumes it; while a
 * planner turn is still running the wait is {@value #PLANNER_GRACE_TICKS} ticks, so a turn the fight woke is not made
 * stale by the hold ending under it. A hold with no paused work at all is released after {@value #EMPTY_HOLD_QUIET_TICKS}
 * quiet ticks. A job resumes at most {@value #JOB_LIMIT} times, and resumes stop for a hold once {@value #WINDOW_LIMIT}
 * happened within {@value #WINDOW_TICKS} ticks; such a hold stays with the planner.
 */
public final class QuietHoldAutoResume {
	public static final long QUIET_TICKS = 40L;
	public static final long PLANNER_GRACE_TICKS = 400L;
	public static final long EMPTY_HOLD_QUIET_TICKS = 100L;
	public static final float MIN_HEALTH = 10.0F;
	public static final int JOB_LIMIT = 12;
	public static final int WINDOW_LIMIT = 3;
	public static final long WINDOW_TICKS = 600L;

	/** Tasks that handle their own danger and keep their state across a reflex pause. */
	public static final Set<ActiveJobType> SELF_CONTAINED = Set.copyOf(EnumSet.of(
		ActiveJobType.SHELTER_FOR_NIGHT, ActiveJobType.SLEEP_IN_BED, ActiveJobType.EXPLORE_FOR, ActiveJobType.HUNT_FOOD,
		ActiveJobType.COLLECT_ENDER_PEARLS, ActiveJobType.COLLECT_BLAZE_RODS, ActiveJobType.COLLECT_OBSIDIAN,
		ActiveJobType.COLLECT_FLINT, ActiveJobType.LOCATE_STRONGHOLD, ActiveJobType.ENTER_END_PORTAL,
		ActiveJobType.FIGHT_ENDER_DRAGON));

	/**
	 * Tasks that act on low health themselves, so they resume below {@value #MIN_HEALTH} health: the shelter wait is the
	 * safe place and eats while waiting; exploring eats and regenerates or fails {@code low_health}; blaze rods, ender
	 * pearls and the dragon fight hide, eat in the pit or hand back {@code low_health}. The rest wait for health, which
	 * the quiet hold's idle eating restores.
	 */
	public static final Set<ActiveJobType> HANDLES_LOW_HEALTH = Set.copyOf(EnumSet.of(
		ActiveJobType.SHELTER_FOR_NIGHT, ActiveJobType.EXPLORE_FOR, ActiveJobType.COLLECT_BLAZE_RODS,
		ActiveJobType.COLLECT_ENDER_PEARLS, ActiveJobType.FIGHT_ENDER_DRAGON));

	public enum Action {
		/** Not a quiet hold of a kind this resumes; the planner decides as before. */
		NONE,
		/** Quiet so far; not yet. */
		WAIT,
		RESUME_JOB,
		RELEASE_EMPTY_HOLD,
		/** The caps were reached for this hold; reported once, then {@link #NONE}. */
		CAPPED
	}

	public record Decision(Action action, String reason, int autoResumes, long quietTicks) {
		static Decision of(Action action, String reason, long quietTicks) {
			return new Decision(action, reason, 0, quietTicks);
		}
	}

	/**
	 * One tick's view of the hold.
	 *
	 * @param quiet the reflex awaits the planner with no threat in its snapshot, does not own actuation, has no planner
	 *              tactic pending, and the body is alive and actuatable
	 * @param worldKey the dimension; a change restarts the quiet period
	 * @param pausedJobType the type of the job the reflex paused, or null when none is paused
	 * @param otherPausedWork paused work other than a job (an action graph), which is left to the planner
	 * @param plannerTurnRunning a planner provider call or tool is still running
	 */
	public record Hold(String holdId, boolean quiet, String worldKey, Float health, String pausedJobId,
		ActiveJobType pausedJobType, boolean otherPausedWork, boolean plannerTurnRunning) {
	}

	/**
	 * A drowning hold over paused work keeps the body at the surface ({@code SWIM_TO_AIR}) for as long as it touches
	 * water, so it owns actuation even when the body breathes. On a flooded canyon floor that was forever, and the paused
	 * task never resumed. A full air supply means the danger is over and the hold is as quiet as any other; a hold still
	 * walking the body to land ({@code REACH_SAFE_LAND}) is left to finish.
	 */
	public static boolean breathingInWater(SurvivalReflexSnapshot reflex) {
		return reflex != null && reflex.state() == SurvivalReflexState.AWAITING_PLANNER
			&& reflex.cause() == SurvivalReflexCause.DROWNING && reflex.action() != SurvivalReflexAction.REACH_SAFE_LAND
			&& reflex.air() != null && reflex.maxAir() != null
			&& reflex.air() >= reflex.maxAir();
	}

	/** The pure rule; the counts are what happened before this tick. */
	public static Decision decide(long quietTicks, Float health, ActiveJobType pausedJobType, boolean otherPausedWork,
		boolean plannerTurnRunning, int jobResumes, int recentResumes) {
		long required = plannerTurnRunning ? PLANNER_GRACE_TICKS : QUIET_TICKS;
		if (pausedJobType == null) {
			if (otherPausedWork) return Decision.of(Action.NONE, "paused_work_not_a_job", quietTicks);
			long emptyRequired = Math.max(EMPTY_HOLD_QUIET_TICKS, required);
			return quietTicks >= emptyRequired ? Decision.of(Action.RELEASE_EMPTY_HOLD, "quiet_hold_no_work", quietTicks)
				: Decision.of(Action.WAIT, "quiet", quietTicks);
		}
		if (!SELF_CONTAINED.contains(pausedJobType)) return Decision.of(Action.NONE, "job_not_self_contained", quietTicks);
		if (!HANDLES_LOW_HEALTH.contains(pausedJobType) && (health == null || health < MIN_HEALTH))
			return Decision.of(Action.WAIT, "low_health", quietTicks);
		if (quietTicks < required) return Decision.of(Action.WAIT, "quiet", quietTicks);
		if (jobResumes >= JOB_LIMIT) return Decision.of(Action.CAPPED, "job_limit", quietTicks);
		if (recentResumes >= WINDOW_LIMIT) return Decision.of(Action.CAPPED, "window_limit", quietTicks);
		return new Decision(Action.RESUME_JOB, "quiet_hold", jobResumes + 1, quietTicks);
	}

	private final ArrayDeque<Long> recentResumes = new ArrayDeque<>();
	private String trackedHoldId;
	private String trackedWorldKey;
	private long quietSince = -1L;
	private String cappedHoldId;
	private String countedJobId;
	private int countedJobResumes;

	/** Feeds one tick; {@code hold} is null when the reflex is not awaiting the planner. Records the resumes it returns. */
	public Decision observe(long tick, Hold hold) {
		if (hold == null || hold.holdId() == null || !hold.quiet()) {
			quietSince = -1L;
			if (hold == null) trackedHoldId = null;
			return Decision.of(Action.NONE, hold == null ? "no_hold" : "not_quiet", 0L);
		}
		if (!hold.holdId().equals(trackedHoldId) || !Objects.equals(hold.worldKey(), trackedWorldKey) || quietSince < 0L) {
			trackedHoldId = hold.holdId();
			trackedWorldKey = hold.worldKey();
			quietSince = tick;
		}
		if (hold.holdId().equals(cappedHoldId)) return Decision.of(Action.NONE, "capped", tick - quietSince);
		while (!recentResumes.isEmpty() && tick - recentResumes.peekFirst() >= WINDOW_TICKS) recentResumes.removeFirst();
		int jobResumes = hold.pausedJobId() != null && hold.pausedJobId().equals(countedJobId) ? countedJobResumes : 0;
		Decision decision = decide(tick - quietSince, hold.health(), hold.pausedJobType(), hold.otherPausedWork(),
			hold.plannerTurnRunning(), jobResumes, recentResumes.size());
		switch (decision.action()) {
			case RESUME_JOB -> {
				countedJobId = hold.pausedJobId();
				countedJobResumes = decision.autoResumes();
				recentResumes.addLast(tick);
				quietSince = -1L;
			}
			case RELEASE_EMPTY_HOLD -> quietSince = -1L;
			case CAPPED -> cappedHoldId = hold.holdId();
			default -> { }
		}
		if (decision.action() == Action.CAPPED) return new Decision(Action.CAPPED, decision.reason(), jobResumes, decision.quietTicks());
		return decision;
	}

	public void reset() {
		recentResumes.clear();
		trackedHoldId = null;
		trackedWorldKey = null;
		quietSince = -1L;
		cappedHoldId = null;
		countedJobId = null;
		countedJobResumes = 0;
	}
}
