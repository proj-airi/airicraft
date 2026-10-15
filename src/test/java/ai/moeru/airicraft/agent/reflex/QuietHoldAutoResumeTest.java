package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.job.ActiveJobType;
import ai.moeru.airicraft.agent.reflex.QuietHoldAutoResume.Action;
import ai.moeru.airicraft.agent.reflex.QuietHoldAutoResume.Hold;
import org.junit.jupiter.api.Test;

import static ai.moeru.airicraft.agent.reflex.QuietHoldAutoResume.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuietHoldAutoResumeTest {
	@Test void selfContainedJobResumesAfterTheQuietPeriod() {
		assertEquals(Action.WAIT, decide(39, 20F, ActiveJobType.SHELTER_FOR_NIGHT, false, false, 0, 0).action());
		var resumed = decide(40, 20F, ActiveJobType.SHELTER_FOR_NIGHT, false, false, 0, 0);
		assertEquals(Action.RESUME_JOB, resumed.action());
		assertEquals(1, resumed.autoResumes());
		assertEquals(6, decide(40, 20F, ActiveJobType.HUNT_FOOD, false, false, 5, 0).autoResumes());
	}

	@Test void onlyTheSelfContainedTasksResume() {
		for (ActiveJobType type : ActiveJobType.values()) {
			Action action = decide(1000, 20F, type, false, false, 0, 0).action();
			assertEquals(QuietHoldAutoResume.SELF_CONTAINED.contains(type) ? Action.RESUME_JOB : Action.NONE, action, type.name());
		}
		assertEquals(11, QuietHoldAutoResume.SELF_CONTAINED.size());
		assertFalse(QuietHoldAutoResume.SELF_CONTAINED.contains(ActiveJobType.NAVIGATE_TO));
		assertTrue(QuietHoldAutoResume.SELF_CONTAINED.contains(ActiveJobType.FIGHT_ENDER_DRAGON));
	}

	@Test void lowHealthWaitsUnlessTheTaskHandlesItItself() {
		assertEquals(Action.WAIT, decide(1000, 9.5F, ActiveJobType.SLEEP_IN_BED, false, false, 0, 0).action());
		assertEquals("low_health", decide(1000, 9.5F, ActiveJobType.LOCATE_STRONGHOLD, false, false, 0, 0).reason());
		assertEquals(Action.WAIT, decide(1000, null, ActiveJobType.COLLECT_FLINT, false, false, 0, 0).action());
		assertEquals(Action.RESUME_JOB, decide(1000, 10F, ActiveJobType.COLLECT_OBSIDIAN, false, false, 0, 0).action());
		for (ActiveJobType type : QuietHoldAutoResume.HANDLES_LOW_HEALTH) {
			assertTrue(QuietHoldAutoResume.SELF_CONTAINED.contains(type), type.name());
			assertEquals(Action.RESUME_JOB, decide(40, 4F, type, false, false, 0, 0).action(), type.name());
		}
	}

	@Test void aRunningPlannerTurnGetsTheLongerGrace() {
		assertEquals(Action.WAIT, decide(399, 20F, ActiveJobType.EXPLORE_FOR, false, true, 0, 0).action());
		assertEquals(Action.RESUME_JOB, decide(400, 20F, ActiveJobType.EXPLORE_FOR, false, true, 0, 0).action());
		assertEquals(Action.WAIT, decide(399, 20F, null, false, true, 0, 0).action());
		assertEquals(Action.RELEASE_EMPTY_HOLD, decide(400, 20F, null, false, true, 0, 0).action());
	}

	@Test void capsLeaveTheHoldToThePlanner() {
		var job = decide(40, 20F, ActiveJobType.SHELTER_FOR_NIGHT, false, false, QuietHoldAutoResume.JOB_LIMIT, 0);
		assertEquals(Action.CAPPED, job.action());
		assertEquals("job_limit", job.reason());
		var window = decide(40, 20F, ActiveJobType.SHELTER_FOR_NIGHT, false, false, 2, QuietHoldAutoResume.WINDOW_LIMIT);
		assertEquals(Action.CAPPED, window.action());
		assertEquals("window_limit", window.reason());
		assertEquals(Action.RESUME_JOB, decide(40, 20F, ActiveJobType.SHELTER_FOR_NIGHT, false, false, 11, 2).action());
	}

	@Test void aHoldWithNoPausedWorkIsReleasedAfterLongerQuiet() {
		assertEquals(Action.WAIT, decide(99, 20F, null, false, false, 0, 0).action());
		assertEquals(Action.RELEASE_EMPTY_HOLD, decide(100, 2F, null, false, false, 0, 0).action());
		assertEquals(Action.NONE, decide(1000, 20F, null, true, false, 0, 0).action());
	}

	@Test void quietTicksRestartWhenThreatsReturnTheHoldChangesOrTheDimensionChanges() {
		var tracker = new QuietHoldAutoResume();
		assertEquals(Action.WAIT, tracker.observe(100, hold("hold-1", true, "overworld")).action());
		assertEquals(Action.WAIT, tracker.observe(130, hold("hold-1", true, "overworld")).action());
		assertEquals(Action.NONE, tracker.observe(131, hold("hold-1", false, "overworld")).action());
		assertEquals(Action.WAIT, tracker.observe(132, hold("hold-1", true, "overworld")).action());
		assertEquals(Action.WAIT, tracker.observe(171, hold("hold-1", true, "overworld")).action());
		assertEquals(Action.WAIT, tracker.observe(172, hold("hold-1", true, "the_nether")).action());
		assertEquals(Action.WAIT, tracker.observe(173, hold("hold-2", true, "the_nether")).action());
		assertEquals(Action.NONE, tracker.observe(180, null).action());
		assertEquals(Action.WAIT, tracker.observe(181, hold("hold-2", true, "the_nether")).action());
		var resumed = tracker.observe(221, hold("hold-2", true, "the_nether"));
		assertEquals(Action.RESUME_JOB, resumed.action());
		assertEquals(40, resumed.quietTicks());
	}

	@Test void trackerCountsResumesPerJobAndWithinTheWindow() {
		var tracker = new QuietHoldAutoResume();
		long tick = 0;
		// Three fights, each resumed after 40 quiet ticks, within 600 ticks.
		for (int fight = 1; fight <= 3; fight++) {
			tracker.observe(tick, hold("hold-" + fight, true, "overworld"));
			var resumed = tracker.observe(tick + 40, hold("hold-" + fight, true, "overworld"));
			assertEquals(Action.RESUME_JOB, resumed.action());
			assertEquals(fight, resumed.autoResumes());
			tick += 100;
		}
		tracker.observe(tick, hold("hold-4", true, "overworld"));
		var capped = tracker.observe(tick + 40, hold("hold-4", true, "overworld"));
		assertEquals(Action.CAPPED, capped.action());
		assertEquals("window_limit", capped.reason());
		assertEquals(3, capped.autoResumes());
		// Reported once; the hold then stays with the planner even after the window empties.
		assertEquals(Action.NONE, tracker.observe(tick + 41, hold("hold-4", true, "overworld")).action());
		assertEquals(Action.NONE, tracker.observe(tick + 2000, hold("hold-4", true, "overworld")).action());
		// A later fight past the window resumes again, counting on for the same job.
		tick += 2100;
		tracker.observe(tick, hold("hold-5", true, "overworld"));
		var again = tracker.observe(tick + 40, hold("hold-5", true, "overworld"));
		assertEquals(Action.RESUME_JOB, again.action());
		assertEquals(4, again.autoResumes());
		// Spread out, a job resumes 12 times in all.
		for (int fight = 6; fight <= 13; fight++) {
			tick += 1000;
			tracker.observe(tick, hold("hold-" + fight, true, "overworld"));
			assertEquals(Action.RESUME_JOB, tracker.observe(tick + 40, hold("hold-" + fight, true, "overworld")).action());
		}
		tick += 1000;
		tracker.observe(tick, hold("hold-14", true, "overworld"));
		var jobCapped = tracker.observe(tick + 40, hold("hold-14", true, "overworld"));
		assertEquals(Action.CAPPED, jobCapped.action());
		assertEquals("job_limit", jobCapped.reason());
		assertEquals(12, jobCapped.autoResumes());
		// Another job starts its own count.
		tick += 1000;
		tracker.observe(tick, new Hold("hold-15", true, "overworld", 20F, "job-2", ActiveJobType.SHELTER_FOR_NIGHT, false, false));
		var other = tracker.observe(tick + 40, new Hold("hold-15", true, "overworld", 20F, "job-2", ActiveJobType.SHELTER_FOR_NIGHT, false, false));
		assertEquals(Action.RESUME_JOB, other.action());
		assertEquals(1, other.autoResumes());
	}

	@Test void aDrowningHoldIsQuietOnceTheAirIsFull() {
		// Standing in a flooded canyon's shallow floor: the hold keeps the body afloat, but it breathes.
		assertTrue(QuietHoldAutoResume.breathingInWater(drowning(300)));
		assertFalse(QuietHoldAutoResume.breathingInWater(drowning(240)), "still catching its breath");
		var toLand = new SurvivalReflexSnapshot(SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.DROWNING,
			SurvivalReflexAction.REACH_SAFE_LAND, 1L, "hold-1", "job-1", null, java.util.List.of(), 20F, 20F, 300, 300, 0L, 0L, 0, null);
		assertFalse(QuietHoldAutoResume.breathingInWater(toLand), "still walking to land");
		var fight = new SurvivalReflexSnapshot(SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.MOB_ATTACK,
			SurvivalReflexAction.DEFEND, 1L, "hold-1", "job-1", null, java.util.List.of(), 20F, 20F, 300, 300, 0L, 0L, 0, null);
		assertFalse(QuietHoldAutoResume.breathingInWater(fight));
	}

	private static SurvivalReflexSnapshot drowning(int air) {
		return new SurvivalReflexSnapshot(SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.DROWNING,
			SurvivalReflexAction.SWIM_TO_AIR, 1L, "hold-1", "job-1", null, java.util.List.of(), 20F, 20F, air, 300, 0L, 0L, 0, null);
	}

	private static Hold hold(String holdId, boolean quiet, String world) {
		return new Hold(holdId, quiet, world, 20F, "job-1", ActiveJobType.SHELTER_FOR_NIGHT, false, false);
	}
}
