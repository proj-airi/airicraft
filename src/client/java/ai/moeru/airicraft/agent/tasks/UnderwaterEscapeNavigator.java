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
 * It owns only Baritone goals that it starts and never issues unverified vertical movement.
 */
public final class UnderwaterEscapeNavigator {
	private final NavigationFacade baritone;
	private final WaypointDriver waypointDriver;
	private final Config config;
	private final Set<UnderwaterEscapeSearch.Position> failedTargets = new LinkedHashSet<>();
	private final Set<UnderwaterEscapeSearch.Position> baritoneAttemptedTargets = new LinkedHashSet<>();

	private UnderwaterEscapeSearch.Candidate currentCandidate;
	private Phase phase = Phase.IDLE;
	private boolean ownsBaritone;
	private long phaseStartedTick = -1L;
	private long lastProgressTick = -1L;
	private double progressCheckpointDistance = Double.POSITIVE_INFINITY;
	private int waypointIndex;
	private String lastTransition = "idle";
	private AfterRelease afterRelease;
	private String afterReleaseReason;

	public UnderwaterEscapeNavigator(NavigationFacade baritone, WaypointDriver waypointDriver) {
		this(baritone, waypointDriver, Config.defaults());
	}

	public UnderwaterEscapeNavigator(NavigationFacade baritone, WaypointDriver waypointDriver, Config config) {
		this.baritone = baritone;
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
			case BARITONE_EXACT -> tickBaritone(candidates, observation);
			case WAITING_FOR_BARITONE_RELEASE -> tickBaritoneRelease(candidates, observation);
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
		cancelOwnedBaritone();
		waypointDriver.stop();
		if (clearFailedTargets) {
			failedTargets.clear();
			baritoneAttemptedTargets.clear();
		}
		currentCandidate = null;
		phase = Phase.IDLE;
		phaseStartedTick = -1L;
		lastProgressTick = -1L;
		progressCheckpointDistance = Double.POSITIVE_INFINITY;
		waypointIndex = 0;
		afterRelease = null;
		afterReleaseReason = null;
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
		return new Snapshot(phase, target, waypoint, failedTargets, ownsBaritone, lastTransition);
	}

	private Snapshot tickBaritone(
		List<UnderwaterEscapeSearch.Candidate> candidates,
		Observation observation
	) {
		updateProgress(observation, currentCandidate.target());
		Optional<String> pathEvent = baritone == null ? Optional.empty() : baritone.pollPathEvent();
		if (pathEvent.filter(UnderwaterEscapeNavigator::isFailedPathEvent).isPresent()) {
			beginRelease(
				candidates,
				observation,
				AfterRelease.START_WAYPOINT,
				"baritone_" + normalizeEvent(pathEvent.orElseThrow())
			);
		}
		else if (observation.tick() - phaseStartedTick >= config.baritoneDeadlineTicks()) {
			beginRelease(candidates, observation, AfterRelease.START_WAYPOINT, "baritone_deadline");
		}
		else if (observation.tick() - lastProgressTick >= config.progressWindowTicks()) {
			beginRelease(candidates, observation, AfterRelease.START_WAYPOINT, "baritone_no_progress");
		}
		return snapshot();
	}

	private Snapshot tickBaritoneRelease(
		List<UnderwaterEscapeSearch.Candidate> candidates,
		Observation observation
	) {
		if (!BaritoneReleaseBarrier.releaseAndDrain(baritone)) {
			lastTransition = "waiting_for_baritone_release";
			return snapshot();
		}
		ownsBaritone = false;
		AfterRelease next = afterRelease;
		String reason = afterReleaseReason;
		afterRelease = null;
		afterReleaseReason = null;
		if (next == AfterRelease.COMPLETE) {
			waypointDriver.stop();
			phase = Phase.REACHED;
			lastTransition = reason;
		}
		else if (next == AfterRelease.EXHAUST) {
			waypointDriver.stop();
			phase = Phase.EXHAUSTED;
			lastTransition = reason;
		}
		else if (next == AfterRelease.START_WAYPOINT) {
			startWaypointFallback(candidates, observation, reason);
		}
		else {
			startExactOrWaypoints(candidates, observation, reason);
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
		if (baritone != null && baritone.isLoaded()) {
			if (!BaritoneReleaseBarrier.released(baritone)) {
				phase = Phase.WAITING_FOR_BARITONE_RELEASE;
				afterRelease = AfterRelease.START_EXACT;
				afterReleaseReason = reason;
				lastTransition = "waiting_for_baritone_release";
				return;
			}
			if (baritoneAttemptedTargets.contains(currentCandidate.target())) {
				startWaypointFallback(candidates, observation, "baritone_already_attempted");
				return;
			}
			try {
				UnderwaterEscapeSearch.Position target = currentCandidate.target();
				baritoneAttemptedTargets.add(target);
				baritone.startNavigate(new GoalPosition(target.x(), target.y(), target.z(), true));
				ownsBaritone = true;
				phase = Phase.BARITONE_EXACT;
				lastTransition = reason;
				return;
			}
			catch (RuntimeException ignored) {
				ownsBaritone = false;
				startWaypointFallback(candidates, observation, "baritone_start_failed");
				return;
			}
		}
		startWaypointFallback(candidates, observation, "baritone_unavailable");
	}

	private void startWaypointFallback(
		List<UnderwaterEscapeSearch.Candidate> candidates,
		Observation observation,
		String reason
	) {
		ownsBaritone = false;
		waypointDriver.stop();
		int currentCellIndex = currentRouteCellIndex(currentCandidate.route(), observation);
		if (currentCellIndex < 0
			|| distance(observation, currentCandidate.route().get(currentCellIndex)) > config.routeJoinToleranceBlocks()) {
			// Baritone may have made useful progress outside the origin-anchored
			// route before failing. Ask the controller to verify and search again
			// from the current cell; keep both target provenance sets so the exact
			// target is not handed back to Baritone a second time.
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
		cancelOwnedBaritone();
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
		if (!ownsBaritone && BaritoneReleaseBarrier.released(baritone)) {
			if (next == AfterRelease.COMPLETE) {
				phase = Phase.REACHED;
				lastTransition = reason;
			}
			else if (next == AfterRelease.EXHAUST) {
				phase = Phase.EXHAUSTED;
				lastTransition = reason;
			}
			else if (next == AfterRelease.START_WAYPOINT) {
				startWaypointFallback(candidates, Objects.requireNonNull(observation, "observation"), reason);
			}
			else {
				startExactOrWaypoints(candidates, Objects.requireNonNull(observation, "observation"), reason);
			}
			return;
		}
		if (ownsBaritone && baritone != null && baritone.isLoaded()) {
			baritone.cancel();
		}
		phase = Phase.WAITING_FOR_BARITONE_RELEASE;
		afterRelease = next;
		afterReleaseReason = reason;
		lastTransition = "waiting_for_baritone_release";
		if (BaritoneReleaseBarrier.released(baritone)) {
			ownsBaritone = false;
			if (next == AfterRelease.COMPLETE) {
				phase = Phase.REACHED;
				lastTransition = reason;
			}
			else if (next == AfterRelease.EXHAUST) {
				phase = Phase.EXHAUSTED;
				lastTransition = reason;
			}
			else if (next == AfterRelease.START_WAYPOINT) {
				startWaypointFallback(candidates, Objects.requireNonNull(observation, "observation"), reason);
			}
		}
	}

	private void cancelOwnedBaritone() {
		if (ownsBaritone && baritone != null && baritone.isLoaded()) {
			baritone.cancel();
		}
		ownsBaritone = false;
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
		BARITONE_EXACT,
		WAITING_FOR_BARITONE_RELEASE,
		WAYPOINT_FALLBACK,
		RESEARCH_REQUIRED,
		REACHED,
		EXHAUSTED
	}

	private enum AfterRelease {
		START_EXACT,
		START_WAYPOINT,
		COMPLETE,
		EXHAUST
	}

	public record Observation(double x, double y, double z, long tick, boolean targetSatisfied) {
	}

	public record Config(
		long baritoneDeadlineTicks,
		long waypointDeadlineTicks,
		long progressWindowTicks,
		double minimumProgressBlocks,
		double waypointToleranceBlocks,
		double routeJoinToleranceBlocks
	) {
		public Config {
			if (baritoneDeadlineTicks < 1L || waypointDeadlineTicks < 1L || progressWindowTicks < 1L) {
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
		boolean ownsBaritone,
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
