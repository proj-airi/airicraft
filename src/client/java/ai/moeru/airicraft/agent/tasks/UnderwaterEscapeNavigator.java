package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Effect shell for one underwater escape search generation.
 * It owns only navigation goals that it starts and never issues unverified vertical movement.
 */
public final class UnderwaterEscapeNavigator {
	private final NavigationFacade navigationFacade;
	private final WaypointDriver waypointDriver;
	private final Config config;
	private final Set<UnderwaterEscapeSearch.Position> failedTargets = new LinkedHashSet<>();
	private final Set<UnderwaterEscapeSearch.Position> navigationAttemptedTargets = new LinkedHashSet<>();

	private UnderwaterEscapeSearch.Candidate currentCandidate;
	private Phase phase = Phase.IDLE;
	private boolean ownsNavigation;
	private long phaseStartedTick = -1L;
	private long lastProgressTick = -1L;
	private double progressCheckpointDistance = Double.POSITIVE_INFINITY;
	private int waypointIndex;
	private String lastTransition = "idle";

	public UnderwaterEscapeNavigator(NavigationFacade navigationFacade, WaypointDriver waypointDriver) {
		this(navigationFacade, waypointDriver, Config.defaults());
	}

	public UnderwaterEscapeNavigator(NavigationFacade navigationFacade, WaypointDriver waypointDriver, Config config) {
		this.navigationFacade = navigationFacade;
		this.waypointDriver = Objects.requireNonNull(waypointDriver, "waypointDriver");
		this.config = Objects.requireNonNull(config, "config");
	}

	public Snapshot tick(List<UnderwaterEscapeSearch.Candidate> candidates, Observation observation) {
		Objects.requireNonNull(candidates, "candidates");
		Objects.requireNonNull(observation, "observation");
		if (phase == Phase.REACHED) {
			return snapshot();
		}
		if (observation.targetSatisfied()) {
			if (currentCandidate != null) {
				complete("target_satisfied");
			}
			else {
				waypointDriver.stop();
				phase = Phase.REACHED;
				lastTransition = "target_already_satisfied";
			}
			return snapshot();
		}
		if (currentCandidate == null) {
			startNextCandidate(candidates, observation, "candidate_selected");
			return snapshot();
		}
		return switch (phase) {
			case NAVIGATION_EXACT -> tickNavigation(candidates, observation);
			case WAYPOINT_FALLBACK -> tickWaypoints(candidates, observation);
			case IDLE, EXHAUSTED -> {
				startNextCandidate(candidates, observation, "candidate_selected");
				yield snapshot();
			}
			case RESEARCH_REQUIRED, REACHED -> snapshot();
		};
	}

	public void reset() {
		resetNavigation(true);
	}

	/** Starts another search mode without forgetting failed targets in this recovery. */
	public void restartSearch() {
		resetNavigation(false);
	}

	private void resetNavigation(boolean clearFailedTargets) {
		cancelOwnedNavigation();
		waypointDriver.stop();
		if (clearFailedTargets) {
			failedTargets.clear();
			navigationAttemptedTargets.clear();
		}
		currentCandidate = null;
		phase = Phase.IDLE;
		phaseStartedTick = -1L;
		lastProgressTick = -1L;
		progressCheckpointDistance = Double.POSITIVE_INFINITY;
		waypointIndex = 0;
		lastTransition = "reset";
	}

	public Snapshot snapshot() {
		UnderwaterEscapeSearch.Position target = currentCandidate == null ? null : currentCandidate.target();
		UnderwaterEscapeSearch.Position waypoint = phase == Phase.WAYPOINT_FALLBACK
			&& currentCandidate != null
			&& waypointIndex >= 0
			&& waypointIndex < currentCandidate.route().size()
			? currentCandidate.route().get(waypointIndex)
			: null;
		return new Snapshot(phase, target, waypoint, failedTargets, ownsNavigation, lastTransition);
	}

	private Snapshot tickNavigation(
		List<UnderwaterEscapeSearch.Candidate> candidates,
		Observation observation
	) {
		updateProgress(observation, currentCandidate.target());
		Optional<String> pathEvent = navigationFacade == null ? Optional.empty() : navigationFacade.pollPathEvent();
		if (pathEvent.filter(UnderwaterEscapeNavigator::isFailedPathEvent).isPresent()) {
			beginRelease(
				candidates,
				observation,
				AfterRelease.START_WAYPOINT,
				"navigation_" + normalizeEvent(pathEvent.orElseThrow())
			);
		}
		else if (observation.tick() - phaseStartedTick >= config.navigationDeadlineTicks()) {
			beginRelease(candidates, observation, AfterRelease.START_WAYPOINT, "navigation_deadline");
		}
		else if (observation.tick() - lastProgressTick >= config.progressWindowTicks()) {
			beginRelease(candidates, observation, AfterRelease.START_WAYPOINT, "navigation_no_progress");
		}
		return snapshot();
	}

	private Snapshot tickWaypoints(
		List<UnderwaterEscapeSearch.Candidate> candidates,
		Observation observation
	) {
		advanceReachedWaypoints(observation);
		if (waypointIndex >= currentCandidate.route().size()) {
			waypointIndex = currentCandidate.route().size() - 1;
		}
		UnderwaterEscapeSearch.Position waypoint = currentCandidate.route().get(waypointIndex);
		updateProgress(observation, waypoint);
		if (observation.tick() - phaseStartedTick >= config.waypointDeadlineTicks()) {
			failCurrentAndContinue(candidates, observation, "waypoint_deadline");
		}
		else if (observation.tick() - lastProgressTick >= config.progressWindowTicks()) {
			failCurrentAndContinue(candidates, observation, "waypoint_no_progress");
		}
		else {
			waypointDriver.moveToward(waypoint, observation.tick());
		}
		return snapshot();
	}

	private void startNextCandidate(
		List<UnderwaterEscapeSearch.Candidate> candidates,
		Observation observation,
		String reason
	) {
		currentCandidate = candidates.stream()
			.filter(Objects::nonNull)
			.filter(candidate -> !failedTargets.contains(candidate.target()))
			.findFirst()
			.orElse(null);
		if (currentCandidate == null) {
			beginRelease(candidates, observation, AfterRelease.EXHAUST, "candidates_exhausted");
			waypointDriver.stop();
			return;
		}
		waypointDriver.stop();
		initializeProgress(observation, currentCandidate.target());
		startExactOrWaypoints(candidates, observation, reason);
	}

	private void startExactOrWaypoints(
		List<UnderwaterEscapeSearch.Candidate> candidates,
		Observation observation,
		String reason
	) {
		initializeProgress(observation, currentCandidate.target());
		if (navigationFacade != null && navigationFacade.isLoaded()) {
			NavigationRelease.release(navigationFacade);
			if (navigationAttemptedTargets.contains(currentCandidate.target())) {
				startWaypointFallback(candidates, observation, "navigation_already_attempted");
				return;
			}
			try {
				UnderwaterEscapeSearch.Position target = currentCandidate.target();
				navigationAttemptedTargets.add(target);
				navigationFacade.startNavigate(new GoalPosition(target.x(), target.y(), target.z(), true));
				ownsNavigation = true;
				phase = Phase.NAVIGATION_EXACT;
				lastTransition = reason;
				return;
			}
			catch (RuntimeException ignored) {
				ownsNavigation = false;
				startWaypointFallback(candidates, observation, "navigation_start_failed");
				return;
			}
		}
		startWaypointFallback(candidates, observation, "navigation_unavailable");
	}

	private void startWaypointFallback(
		List<UnderwaterEscapeSearch.Candidate> candidates,
		Observation observation,
		String reason
	) {
		ownsNavigation = false;
		waypointDriver.stop();
		int currentCellIndex = currentRouteCellIndex(currentCandidate.route(), observation);
		if (currentCellIndex < 0
			|| distance(observation, currentCandidate.route().get(currentCellIndex)) > config.routeJoinToleranceBlocks()) {
			// Navigation may have made useful progress outside the origin-anchored
			// route before failing. Ask the controller to verify and search again
			// from the current cell; keep both target provenance sets so the exact
			// target is not handed back to navigation a second time.
			currentCandidate = null;
			phase = Phase.RESEARCH_REQUIRED;
			lastTransition = reason + "_route_research_required";
			return;
		}
		waypointIndex = currentCellIndex;
		advanceReachedWaypoints(observation);
		if (waypointIndex >= currentCandidate.route().size()) {
			waypointIndex = currentCandidate.route().size() - 1;
		}
		phase = Phase.WAYPOINT_FALLBACK;
		initializeProgress(observation, currentCandidate.route().get(waypointIndex));
		lastTransition = reason;
	}

	private void failCurrentAndContinue(
		List<UnderwaterEscapeSearch.Candidate> candidates,
		Observation observation,
		String reason
	) {
		cancelOwnedNavigation();
		waypointDriver.stop();
		failedTargets.add(currentCandidate.target());
		currentCandidate = null;
		phase = Phase.IDLE;
		lastTransition = reason;
		startNextCandidate(candidates, observation, reason);
	}

	private void complete(String reason) {
		waypointDriver.stop();
		beginRelease(List.of(), null, AfterRelease.COMPLETE, reason);
	}

	private void beginRelease(
		List<UnderwaterEscapeSearch.Candidate> candidates,
		Observation observation,
		AfterRelease next,
		String reason
	) {
		// Cancellation is synchronous: the goal we own is released when this returns.
		cancelOwnedNavigation();
		switch (next) {
			case COMPLETE -> {
				phase = Phase.REACHED;
				lastTransition = reason;
			}
			case EXHAUST -> {
				phase = Phase.EXHAUSTED;
				lastTransition = reason;
			}
			case START_WAYPOINT -> startWaypointFallback(candidates, Objects.requireNonNull(observation, "observation"), reason);
		}
	}

	private void cancelOwnedNavigation() {
		if (ownsNavigation && navigationFacade != null && navigationFacade.isLoaded()) {
			navigationFacade.cancel();
		}
		ownsNavigation = false;
	}

	private void initializeProgress(Observation observation, UnderwaterEscapeSearch.Position target) {
		phaseStartedTick = observation.tick();
		lastProgressTick = observation.tick();
		progressCheckpointDistance = distance(observation, target);
	}

	private void updateProgress(Observation observation, UnderwaterEscapeSearch.Position target) {
		double currentDistance = distance(observation, target);
		if (progressCheckpointDistance - currentDistance >= config.minimumProgressBlocks()) {
			progressCheckpointDistance = currentDistance;
			lastProgressTick = observation.tick();
		}
	}

	private void advanceReachedWaypoints(Observation observation) {
		while (waypointIndex < currentCandidate.route().size()
			&& distance(observation, currentCandidate.route().get(waypointIndex)) <= config.waypointToleranceBlocks()) {
			waypointIndex++;
		}
	}

	private static int currentRouteCellIndex(List<UnderwaterEscapeSearch.Position> route, Observation observation) {
		int blockX = (int) Math.floor(observation.x());
		int blockY = (int) Math.floor(observation.y());
		int blockZ = (int) Math.floor(observation.z());
		for (int index = 0; index < route.size(); index++) {
			UnderwaterEscapeSearch.Position position = route.get(index);
			if (position.x() == blockX && position.y() == blockY && position.z() == blockZ) {
				return index;
			}
		}
		return -1;
	}

	private static double distance(Observation observation, UnderwaterEscapeSearch.Position position) {
		double dx = observation.x() - (position.x() + 0.5D);
		double dy = observation.y() - position.y();
		double dz = observation.z() - (position.z() + 0.5D);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	static boolean isFailedPathEvent(String event) {
		if (event == null) {
			return false;
		}
		String normalized = normalizeEvent(event);
		return "calc_failed".equals(normalized)
			|| "calculation_failed".equals(normalized)
			|| "cancelled".equals(normalized)
			|| "canceled".equals(normalized);
	}

	private static String normalizeEvent(String event) {
		return event.trim().toLowerCase(Locale.ROOT);
	}

	public enum Phase {
		IDLE,
		NAVIGATION_EXACT,
		WAYPOINT_FALLBACK,
		RESEARCH_REQUIRED,
		REACHED,
		EXHAUSTED
	}

	private enum AfterRelease {
		START_WAYPOINT,
		COMPLETE,
		EXHAUST
	}

	public record Observation(double x, double y, double z, long tick, boolean targetSatisfied) {
	}

	public record Config(
		long navigationDeadlineTicks,
		long waypointDeadlineTicks,
		long progressWindowTicks,
		double minimumProgressBlocks,
		double waypointToleranceBlocks,
		double routeJoinToleranceBlocks
	) {
		public Config {
			if (navigationDeadlineTicks < 1L || waypointDeadlineTicks < 1L || progressWindowTicks < 1L) {
				throw new IllegalArgumentException("deadlines and progress window must be positive");
			}
			if (minimumProgressBlocks <= 0.0D || waypointToleranceBlocks <= 0.0D || routeJoinToleranceBlocks <= 0.0D) {
				throw new IllegalArgumentException("distance thresholds must be positive");
			}
		}

		public static Config defaults() {
			return new Config(60L, 100L, 40L, 0.25D, 0.8D, 1.75D);
		}
	}

	public record Snapshot(
		Phase phase,
		UnderwaterEscapeSearch.Position target,
		UnderwaterEscapeSearch.Position waypoint,
		Set<UnderwaterEscapeSearch.Position> failedTargets,
		boolean ownsNavigation,
		String lastTransition
	) {
		public Snapshot {
			Objects.requireNonNull(phase, "phase");
			failedTargets = Set.copyOf(failedTargets);
			Objects.requireNonNull(lastTransition, "lastTransition");
		}
	}

	@FunctionalInterface
	public interface WaypointDriver {
		void moveToward(UnderwaterEscapeSearch.Position waypoint, long tick);

		default void stop() {
		}
	}
}
