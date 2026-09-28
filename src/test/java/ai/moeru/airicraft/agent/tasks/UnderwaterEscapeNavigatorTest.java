package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnderwaterEscapeNavigatorTest {
	@Test
	void startsEachExactBaritoneTargetOnlyOnce() {
		RecordingBaritone baritone = new RecordingBaritone();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(baritone, driver);
		List<UnderwaterEscapeSearch.Candidate> candidates = List.of(candidate(0, 1));

		navigator.tick(candidates, observation(0, 0, false));
		navigator.tick(candidates, observation(0, 1, false));
		navigator.tick(candidates, observation(0, 2, false));

		assertEquals(List.of(new GoalPosition(1, 40, 0, true)), baritone.navigateCalls);
		assertEquals(UnderwaterEscapeNavigator.Phase.BARITONE_EXACT, navigator.snapshot().phase());
		assertTrue(navigator.snapshot().ownsBaritone());
	}

	@Test
	void fallsBackToStoredWaterWaypointsAfterCalculationFailure() {
		RecordingBaritone baritone = new RecordingBaritone();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(baritone, driver);
		List<UnderwaterEscapeSearch.Candidate> candidates = List.of(candidate(0, 2));

		navigator.tick(candidates, observation(0, 0, false));
		baritone.pathEvents.add("CALC_FAILED");
		UnderwaterEscapeNavigator.Snapshot fallback = navigator.tick(candidates, observation(0, 1, false));
		navigator.tick(candidates, observation(0, 2, false));

		assertEquals(UnderwaterEscapeNavigator.Phase.WAYPOINT_FALLBACK, fallback.phase());
		assertEquals(position(1), fallback.waypoint());
		assertEquals(List.of(position(1)), driver.moveCalls);
		assertEquals(0, baritone.cancelCalls);
		assertFalse(fallback.ownsBaritone());
	}

	@Test
	void noProgressFallsBackWithoutRestartingBaritone() {
		RecordingBaritone baritone = new RecordingBaritone();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(baritone, driver);
		List<UnderwaterEscapeSearch.Candidate> candidates = List.of(candidate(0, 2));

		navigator.tick(candidates, observation(0, 0, false));
		UnderwaterEscapeNavigator.Snapshot waiting = navigator.tick(candidates, observation(0, 40, false));
		baritone.acknowledgeCancellation();
		UnderwaterEscapeNavigator.Snapshot snapshot = navigator.tick(candidates, observation(0, 41, false));

		assertEquals(UnderwaterEscapeNavigator.Phase.WAITING_FOR_BARITONE_RELEASE, waiting.phase());
		assertEquals(UnderwaterEscapeNavigator.Phase.WAYPOINT_FALLBACK, snapshot.phase());
		assertEquals("baritone_no_progress", snapshot.lastTransition());
		assertEquals(1, baritone.navigateCalls.size());
	}

	@Test
	void failedWaypointCandidateIsSuppressedAndNextCandidateStarts() {
		RecordingBaritone baritone = new RecordingBaritone();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(baritone, driver);
		UnderwaterEscapeSearch.Candidate first = candidate(0, 1);
		UnderwaterEscapeSearch.Candidate second = candidate(0, -1);
		List<UnderwaterEscapeSearch.Candidate> candidates = List.of(first, second);

		navigator.tick(candidates, observation(0, 0, false));
		baritone.pathEvents.add("CANCELLED");
		navigator.tick(candidates, observation(0, 1, false));
		UnderwaterEscapeNavigator.Snapshot retry = navigator.tick(candidates, observation(0, 41, false));

		assertTrue(retry.failedTargets().contains(first.target()));
		assertEquals(second.target(), retry.target());
		assertEquals(UnderwaterEscapeNavigator.Phase.BARITONE_EXACT, retry.phase());
		assertEquals(2, baritone.navigateCalls.size());
	}

	@Test
	void requestsResearchWithoutBlindMovementWhenNoStoredRouteJoins() {
		RecordingBaritone baritone = new RecordingBaritone();
		baritone.loaded = false;
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(baritone, driver);
		UnderwaterEscapeSearch.Candidate distantRoute = new UnderwaterEscapeSearch.Candidate(
			position(1),
			UnderwaterEscapeSearch.SearchMode.BREATHABLE,
			List.of(position(0), position(1))
		);

		UnderwaterEscapeNavigator.Snapshot snapshot = navigator.tick(
			List.of(distantRoute),
			new UnderwaterEscapeNavigator.Observation(100.0D, 40.0D, 100.0D, 0, false)
		);

		assertEquals(UnderwaterEscapeNavigator.Phase.RESEARCH_REQUIRED, snapshot.phase());
		assertTrue(snapshot.failedTargets().isEmpty());
		assertTrue(driver.moveCalls.isEmpty());
		assertTrue(baritone.navigateCalls.isEmpty());
	}

	@Test
	void waypointFallbackCannotJumpToAGeometricallyNearLaterRouteCell() {
		RecordingBaritone baritone = new RecordingBaritone();
		baritone.loaded = false;
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(baritone, driver);
		UnderwaterEscapeSearch.Candidate winding = new UnderwaterEscapeSearch.Candidate(
			new UnderwaterEscapeSearch.Position(0, 40, 1),
			UnderwaterEscapeSearch.SearchMode.BREATHABLE,
			List.of(
				new UnderwaterEscapeSearch.Position(0, 40, 0),
				new UnderwaterEscapeSearch.Position(1, 40, 0),
				new UnderwaterEscapeSearch.Position(1, 40, 1),
				new UnderwaterEscapeSearch.Position(0, 40, 1)
			)
		);

		UnderwaterEscapeNavigator.Snapshot snapshot = navigator.tick(
			List.of(winding),
			new UnderwaterEscapeNavigator.Observation(-0.4D, 40.0D, 1.5D, 0L, false)
		);

		assertEquals(UnderwaterEscapeNavigator.Phase.RESEARCH_REQUIRED, snapshot.phase());
		assertTrue(snapshot.failedTargets().isEmpty());
		assertTrue(driver.moveCalls.isEmpty());
	}

	@Test
	void baritoneOffRouteProgressRebasesWaypointsWithoutRetryingTheExactTarget() {
		RecordingBaritone baritone = new RecordingBaritone();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(baritone, driver);
		UnderwaterEscapeSearch.Candidate original = candidate(0, 2);

		navigator.tick(List.of(original), observation(0, 0, false));
		baritone.pathEvents.add("CALC_FAILED");
		UnderwaterEscapeNavigator.Observation movedOffRoute = new UnderwaterEscapeNavigator.Observation(
			1.5D,
			40.0D,
			1.5D,
			1L,
			false
		);
		UnderwaterEscapeNavigator.Snapshot research = navigator.tick(
			List.of(original),
			movedOffRoute
		);

		assertEquals(UnderwaterEscapeNavigator.Phase.RESEARCH_REQUIRED, research.phase());
		assertTrue(research.failedTargets().isEmpty());
		assertTrue(driver.moveCalls.isEmpty());
		assertEquals(1, baritone.navigateCalls.size());

		UnderwaterEscapeSearch.Position rebasedStart = new UnderwaterEscapeSearch.Position(1, 40, 1);
		UnderwaterEscapeSearch.Position rebasedWaypoint = new UnderwaterEscapeSearch.Position(2, 40, 1);
		UnderwaterEscapeSearch.Candidate rebased = new UnderwaterEscapeSearch.Candidate(
			original.target(),
			UnderwaterEscapeSearch.SearchMode.BREATHABLE,
			List.of(rebasedStart, rebasedWaypoint, original.target())
		);
		navigator.restartSearch();
		UnderwaterEscapeNavigator.Snapshot fallback = navigator.tick(
			List.of(rebased),
			new UnderwaterEscapeNavigator.Observation(1.5D, 40.0D, 1.5D, 2L, false)
		);

		assertEquals(UnderwaterEscapeNavigator.Phase.WAYPOINT_FALLBACK, fallback.phase());
		assertEquals(rebasedWaypoint, fallback.waypoint());
		assertEquals("baritone_already_attempted", fallback.lastTransition());
		assertEquals(1, baritone.navigateCalls.size());
		assertTrue(fallback.failedTargets().isEmpty());
	}

	@Test
	void failedSafeLandTargetIsNeverRestarted() {
		RecordingBaritone baritone = new RecordingBaritone();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(baritone, driver);
		UnderwaterEscapeSearch.Candidate safeLand = new UnderwaterEscapeSearch.Candidate(
			position(1),
			UnderwaterEscapeSearch.SearchMode.SAFE_STANDING,
			List.of(position(0), position(1))
		);

		navigator.tick(List.of(safeLand), observation(0, 0, false));
		baritone.pathEvents.add("CALC_FAILED");
		navigator.tick(List.of(safeLand), observation(0, 1, false));
		navigator.tick(List.of(safeLand), observation(0, 41, false));
		navigator.tick(List.of(safeLand), observation(0, 100, false));

		assertEquals(UnderwaterEscapeNavigator.Phase.EXHAUSTED, navigator.snapshot().phase());
		assertTrue(navigator.snapshot().failedTargets().contains(safeLand.target()));
		assertEquals(1, baritone.navigateCalls.size());
	}

	@Test
	void failedSafeLandTargetStaysSuppressedAcrossRecoveryModeRestart() {
		RecordingBaritone baritone = new RecordingBaritone();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(baritone, driver);
		UnderwaterEscapeSearch.Candidate safeLand = new UnderwaterEscapeSearch.Candidate(
			position(1),
			UnderwaterEscapeSearch.SearchMode.SAFE_STANDING,
			List.of(position(0), position(1))
		);

		navigator.tick(List.of(safeLand), observation(0, 0, false));
		baritone.pathEvents.add("CALC_FAILED");
		navigator.tick(List.of(safeLand), observation(0, 1, false));
		navigator.tick(List.of(safeLand), observation(0, 41, false));
		assertTrue(navigator.snapshot().failedTargets().contains(safeLand.target()));

		navigator.restartSearch();
		UnderwaterEscapeNavigator.Snapshot restarted = navigator.tick(
			List.of(safeLand),
			observation(0, 42, false)
		);

		assertEquals(UnderwaterEscapeNavigator.Phase.EXHAUSTED, restarted.phase());
		assertEquals(1, baritone.navigateCalls.size());
	}

	@Test
	void recoveryModeRestartNeverRetriesAnAlreadyAttemptedBaritoneTarget() {
		RecordingBaritone baritone = new RecordingBaritone();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(baritone, driver);
		UnderwaterEscapeSearch.Candidate safeLand = new UnderwaterEscapeSearch.Candidate(
			position(1),
			UnderwaterEscapeSearch.SearchMode.SAFE_STANDING,
			List.of(position(0), position(1))
		);

		navigator.tick(List.of(safeLand), observation(0, 0, false));
		baritone.pathEvents.add("CALC_FAILED");
		navigator.tick(List.of(safeLand), observation(0, 1, false));
		navigator.restartSearch();

		UnderwaterEscapeNavigator.Snapshot restarted = navigator.tick(
			List.of(safeLand),
			observation(0, 2, false)
		);

		assertEquals(UnderwaterEscapeNavigator.Phase.WAYPOINT_FALLBACK, restarted.phase());
		assertEquals("baritone_already_attempted", restarted.lastTransition());
		assertEquals(1, baritone.navigateCalls.size());
	}

	@Test
	void verifiedCompletionCancelsOnlyOwnedBaritoneAndStopsMovement() {
		RecordingBaritone baritone = new RecordingBaritone();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(baritone, driver);
		List<UnderwaterEscapeSearch.Candidate> candidates = List.of(candidate(0, 1));

		navigator.tick(candidates, observation(0, 0, false));
		UnderwaterEscapeNavigator.Snapshot waiting = navigator.tick(candidates, observation(1, 1, true));
		baritone.acknowledgeCancellation();
		UnderwaterEscapeNavigator.Snapshot reached = navigator.tick(candidates, observation(1, 2, true));

		assertEquals(UnderwaterEscapeNavigator.Phase.WAITING_FOR_BARITONE_RELEASE, waiting.phase());
		assertEquals(UnderwaterEscapeNavigator.Phase.REACHED, reached.phase());
		assertEquals(1, baritone.cancelCalls);
		assertFalse(reached.ownsBaritone());
		assertTrue(driver.stopCalls >= 2);
	}

	@Test
	void alreadySatisfiedTargetDoesNotLeaveBaritoneRunning() {
		RecordingBaritone baritone = new RecordingBaritone();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(baritone, driver);

		UnderwaterEscapeNavigator.Snapshot reached = navigator.tick(
			List.of(candidate(0, 1)),
			observation(1, 0, true)
		);

		assertEquals(UnderwaterEscapeNavigator.Phase.REACHED, reached.phase());
		assertTrue(baritone.navigateCalls.isEmpty());
		assertEquals(0, baritone.cancelCalls);
		assertFalse(reached.ownsBaritone());
	}

	@Test
	void recognizesBothBaritoneCancellationSpellings() {
		assertTrue(UnderwaterEscapeNavigator.isFailedPathEvent("CANCELED"));
		assertTrue(UnderwaterEscapeNavigator.isFailedPathEvent("cancelled"));
		assertTrue(UnderwaterEscapeNavigator.isFailedPathEvent("CALCULATION_FAILED"));
		assertFalse(UnderwaterEscapeNavigator.isFailedPathEvent("AT_GOAL"));
	}

	private static UnderwaterEscapeSearch.Candidate candidate(int startX, int targetX) {
		List<UnderwaterEscapeSearch.Position> route = new ArrayList<>();
		int direction = Integer.compare(targetX, startX);
		for (int x = startX; ; x += direction) {
			route.add(position(x));
			if (x == targetX) {
				break;
			}
		}
		return new UnderwaterEscapeSearch.Candidate(
			position(targetX),
			UnderwaterEscapeSearch.SearchMode.BREATHABLE,
			route
		);
	}

	private static UnderwaterEscapeNavigator.Observation observation(int x, long tick, boolean satisfied) {
		return new UnderwaterEscapeNavigator.Observation(x + 0.5D, 40.0D, 0.5D, tick, satisfied);
	}

	private static UnderwaterEscapeSearch.Position position(int x) {
		return new UnderwaterEscapeSearch.Position(x, 40, 0);
	}

	private static final class RecordingWaypointDriver implements UnderwaterEscapeNavigator.WaypointDriver {
		private final List<UnderwaterEscapeSearch.Position> moveCalls = new ArrayList<>();
		private int stopCalls;

		@Override
		public void moveToward(UnderwaterEscapeSearch.Position waypoint, long tick) {
			moveCalls.add(waypoint);
		}

		@Override
		public void stop() {
			stopCalls++;
		}
	}

	private static final class RecordingBaritone implements BaritoneFacade {
		private final List<GoalPosition> navigateCalls = new ArrayList<>();
		private final ArrayDeque<String> pathEvents = new ArrayDeque<>();
		private boolean loaded = true;
		private boolean active;
		private boolean cancellationPending;
		private long cancellationAcknowledgement;
		private int cancelCalls;

		@Override
		public boolean isLoaded() {
			return loaded;
		}

		@Override
		public void applySettings() {
		}

		@Override
		public double walkOnWaterPenalty() {
			return 0;
		}

		@Override
		public void setWalkOnWaterPenalty(double value) {
		}

		@Override
		public void startFollow(String playerName) {
		}

		@Override
		public void startNavigate(GoalPosition position) {
			if (cancellationPending) {
				throw new IllegalStateException("release pending");
			}
			navigateCalls.add(position);
			active = true;
		}

		@Override
		public void startNavigateNear(GoalPosition position, int radiusBlocks) {
		}

		@Override
		public boolean processActive() {
			return active;
		}

		@Override
		public boolean cancel() {
			if (active && !cancellationPending) {
				cancelCalls++;
				active = false;
				cancellationPending = true;
			}
			return cancellationPending;
		}

		@Override
		public boolean cancellationPending() {
			return cancellationPending;
		}

		@Override
		public long cancellationAcknowledgement() {
			return cancellationAcknowledgement;
		}

		@Override
		public Optional<String> activeProcessName() {
			return Optional.empty();
		}

		@Override
		public Optional<Double> estimatedTicksToGoal() {
			return Optional.empty();
		}

		@Override
		public Optional<String> pollPathEvent() {
			String event = pathEvents.pollFirst();
			if (event != null && UnderwaterEscapeNavigator.isFailedPathEvent(event)) {
				active = false;
			}
			return Optional.ofNullable(event);
		}

		@Override
		public boolean navigationGoalReached(GoalPosition position) {
			return false;
		}

		private void acknowledgeCancellation() {
			if (cancellationPending) {
				cancellationPending = false;
				cancellationAcknowledgement++;
			}
		}
	}
}
