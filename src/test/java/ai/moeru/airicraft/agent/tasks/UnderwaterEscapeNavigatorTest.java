package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
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
	void startsEachExactNavigationTargetOnlyOnce() {
		RecordingNavigation navigationFacade = new RecordingNavigation();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(navigationFacade, driver);
		List<UnderwaterEscapeSearch.Candidate> candidates = List.of(candidate(0, 1));

		navigator.tick(candidates, observation(0, 0, false));
		navigator.tick(candidates, observation(0, 1, false));
		navigator.tick(candidates, observation(0, 2, false));

		assertEquals(List.of(new GoalPosition(1, 40, 0, true)), navigationFacade.navigateCalls);
		assertEquals(UnderwaterEscapeNavigator.Phase.NAVIGATION_EXACT, navigator.snapshot().phase());
		assertTrue(navigator.snapshot().ownsNavigation());
	}

	@Test
	void fallsBackToStoredWaterWaypointsAfterCalculationFailure() {
		RecordingNavigation navigationFacade = new RecordingNavigation();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(navigationFacade, driver);
		List<UnderwaterEscapeSearch.Candidate> candidates = List.of(candidate(0, 2));

		navigator.tick(candidates, observation(0, 0, false));
		navigationFacade.pathEvents.add("CALC_FAILED");
		UnderwaterEscapeNavigator.Snapshot fallback = navigator.tick(candidates, observation(0, 1, false));
		navigator.tick(candidates, observation(0, 2, false));

		assertEquals(UnderwaterEscapeNavigator.Phase.WAYPOINT_FALLBACK, fallback.phase());
		assertEquals(position(1), fallback.waypoint());
		assertEquals(List.of(position(1)), driver.moveCalls);
		assertEquals(0, navigationFacade.cancelCalls);
		assertFalse(fallback.ownsNavigation());
	}

	@Test
	void noProgressFallsBackWithoutRestartingNavigation() {
		RecordingNavigation navigationFacade = new RecordingNavigation();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(navigationFacade, driver);
		List<UnderwaterEscapeSearch.Candidate> candidates = List.of(candidate(0, 2));

		navigator.tick(candidates, observation(0, 0, false));
		UnderwaterEscapeNavigator.Snapshot snapshot = navigator.tick(candidates, observation(0, 40, false));

		assertEquals(UnderwaterEscapeNavigator.Phase.WAYPOINT_FALLBACK, snapshot.phase(), "release is synchronous, so no waiting tick");
		assertEquals("navigation_no_progress", snapshot.lastTransition());
		assertEquals(1, navigationFacade.navigateCalls.size());
		assertEquals(1, navigationFacade.cancelCalls);
		assertFalse(snapshot.ownsNavigation());
	}

	@Test
	void failedWaypointCandidateIsSuppressedAndNextCandidateStarts() {
		RecordingNavigation navigationFacade = new RecordingNavigation();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(navigationFacade, driver);
		UnderwaterEscapeSearch.Candidate first = candidate(0, 1);
		UnderwaterEscapeSearch.Candidate second = candidate(0, -1);
		List<UnderwaterEscapeSearch.Candidate> candidates = List.of(first, second);

		navigator.tick(candidates, observation(0, 0, false));
		navigationFacade.pathEvents.add("CANCELLED");
		navigator.tick(candidates, observation(0, 1, false));
		UnderwaterEscapeNavigator.Snapshot retry = navigator.tick(candidates, observation(0, 41, false));

		assertTrue(retry.failedTargets().contains(first.target()));
		assertEquals(second.target(), retry.target());
		assertEquals(UnderwaterEscapeNavigator.Phase.NAVIGATION_EXACT, retry.phase());
		assertEquals(2, navigationFacade.navigateCalls.size());
	}

	@Test
	void requestsResearchWithoutBlindMovementWhenNoStoredRouteJoins() {
		RecordingNavigation navigationFacade = new RecordingNavigation();
		navigationFacade.loaded = false;
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(navigationFacade, driver);
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
		assertTrue(navigationFacade.navigateCalls.isEmpty());
	}

	@Test
	void waypointFallbackCannotJumpToAGeometricallyNearLaterRouteCell() {
		RecordingNavigation navigationFacade = new RecordingNavigation();
		navigationFacade.loaded = false;
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(navigationFacade, driver);
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
	void navigationOffRouteProgressRebasesWaypointsWithoutRetryingTheExactTarget() {
		RecordingNavigation navigationFacade = new RecordingNavigation();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(navigationFacade, driver);
		UnderwaterEscapeSearch.Candidate original = candidate(0, 2);

		navigator.tick(List.of(original), observation(0, 0, false));
		navigationFacade.pathEvents.add("CALC_FAILED");
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
		assertEquals(1, navigationFacade.navigateCalls.size());

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
		assertEquals("navigation_already_attempted", fallback.lastTransition());
		assertEquals(1, navigationFacade.navigateCalls.size());
		assertTrue(fallback.failedTargets().isEmpty());
	}

	@Test
	void failedSafeLandTargetIsNeverRestarted() {
		RecordingNavigation navigationFacade = new RecordingNavigation();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(navigationFacade, driver);
		UnderwaterEscapeSearch.Candidate safeLand = new UnderwaterEscapeSearch.Candidate(
			position(1),
			UnderwaterEscapeSearch.SearchMode.SAFE_STANDING,
			List.of(position(0), position(1))
		);

		navigator.tick(List.of(safeLand), observation(0, 0, false));
		navigationFacade.pathEvents.add("CALC_FAILED");
		navigator.tick(List.of(safeLand), observation(0, 1, false));
		navigator.tick(List.of(safeLand), observation(0, 41, false));
		navigator.tick(List.of(safeLand), observation(0, 100, false));

		assertEquals(UnderwaterEscapeNavigator.Phase.EXHAUSTED, navigator.snapshot().phase());
		assertTrue(navigator.snapshot().failedTargets().contains(safeLand.target()));
		assertEquals(1, navigationFacade.navigateCalls.size());
	}

	@Test
	void failedSafeLandTargetStaysSuppressedAcrossRecoveryModeRestart() {
		RecordingNavigation navigationFacade = new RecordingNavigation();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(navigationFacade, driver);
		UnderwaterEscapeSearch.Candidate safeLand = new UnderwaterEscapeSearch.Candidate(
			position(1),
			UnderwaterEscapeSearch.SearchMode.SAFE_STANDING,
			List.of(position(0), position(1))
		);

		navigator.tick(List.of(safeLand), observation(0, 0, false));
		navigationFacade.pathEvents.add("CALC_FAILED");
		navigator.tick(List.of(safeLand), observation(0, 1, false));
		navigator.tick(List.of(safeLand), observation(0, 41, false));
		assertTrue(navigator.snapshot().failedTargets().contains(safeLand.target()));

		navigator.restartSearch();
		UnderwaterEscapeNavigator.Snapshot restarted = navigator.tick(
			List.of(safeLand),
			observation(0, 42, false)
		);

		assertEquals(UnderwaterEscapeNavigator.Phase.EXHAUSTED, restarted.phase());
		assertEquals(1, navigationFacade.navigateCalls.size());
	}

	@Test
	void recoveryModeRestartNeverRetriesAnAlreadyAttemptedNavigationTarget() {
		RecordingNavigation navigationFacade = new RecordingNavigation();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(navigationFacade, driver);
		UnderwaterEscapeSearch.Candidate safeLand = new UnderwaterEscapeSearch.Candidate(
			position(1),
			UnderwaterEscapeSearch.SearchMode.SAFE_STANDING,
			List.of(position(0), position(1))
		);

		navigator.tick(List.of(safeLand), observation(0, 0, false));
		navigationFacade.pathEvents.add("CALC_FAILED");
		navigator.tick(List.of(safeLand), observation(0, 1, false));
		navigator.restartSearch();

		UnderwaterEscapeNavigator.Snapshot restarted = navigator.tick(
			List.of(safeLand),
			observation(0, 2, false)
		);

		assertEquals(UnderwaterEscapeNavigator.Phase.WAYPOINT_FALLBACK, restarted.phase());
		assertEquals("navigation_already_attempted", restarted.lastTransition());
		assertEquals(1, navigationFacade.navigateCalls.size());
	}

	@Test
	void verifiedCompletionCancelsOnlyOwnedNavigationAndStopsMovement() {
		RecordingNavigation navigationFacade = new RecordingNavigation();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(navigationFacade, driver);
		List<UnderwaterEscapeSearch.Candidate> candidates = List.of(candidate(0, 1));

		navigator.tick(candidates, observation(0, 0, false));
		UnderwaterEscapeNavigator.Snapshot reached = navigator.tick(candidates, observation(1, 1, true));

		assertEquals(UnderwaterEscapeNavigator.Phase.REACHED, reached.phase());
		assertEquals(1, navigationFacade.cancelCalls);
		assertFalse(reached.ownsNavigation());
		assertTrue(driver.stopCalls >= 2);
	}

	@Test
	void alreadySatisfiedTargetDoesNotLeaveNavigationRunning() {
		RecordingNavigation navigationFacade = new RecordingNavigation();
		RecordingWaypointDriver driver = new RecordingWaypointDriver();
		UnderwaterEscapeNavigator navigator = new UnderwaterEscapeNavigator(navigationFacade, driver);

		UnderwaterEscapeNavigator.Snapshot reached = navigator.tick(
			List.of(candidate(0, 1)),
			observation(1, 0, true)
		);

		assertEquals(UnderwaterEscapeNavigator.Phase.REACHED, reached.phase());
		assertTrue(navigationFacade.navigateCalls.isEmpty());
		assertEquals(0, navigationFacade.cancelCalls);
		assertFalse(reached.ownsNavigation());
	}

	@Test
	void recognizesBothNavigationCancellationSpellings() {
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

	private static final class RecordingNavigation implements NavigationFacade {
		private final List<GoalPosition> navigateCalls = new ArrayList<>();
		private final ArrayDeque<String> pathEvents = new ArrayDeque<>();
		private boolean loaded = true;
		private boolean active;
		private int cancelCalls;

		@Override
		public boolean isLoaded() {
			return loaded;
		}

		@Override
		public void startFollow(String playerName) {
		}

		@Override
		public void startNavigate(GoalPosition position, NavigationOptions options) {
			navigateCalls.add(position);
			active = true;
		}

		@Override
		public void startNavigateNear(GoalPosition position, int radiusBlocks, NavigationOptions options) {
		}

		@Override
		public boolean processActive() {
			return active;
		}

		@Override
		public void cancel() {
			if (active) {
				cancelCalls++;
				active = false;
			}
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
	}
}
