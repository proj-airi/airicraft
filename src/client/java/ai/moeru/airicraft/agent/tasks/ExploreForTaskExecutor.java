package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Walks out to find a world feature that is not in loaded chunks yet. A playtest spawn was in a desert with no tree within
 * about 200 blocks: find_world_features only searches loaded chunks and found no forest, so the driver walked back and forth
 * by hand, routed into a lake several times (the drowning reflex pulled it out) and was killed at night. This task walks
 * outward in legs of about 48 blocks and searches the loaded chunks again after each one. A leg heads where the most
 * ground is new: far from everywhere the body has already walked, outward from the start, with unloaded chunks ahead,
 * keeping roughly the last heading. It ends on dry ground (never water or lava), avoids headings whose straight way
 * crosses water, and is walked without breaking, placing or sprinting, with water priced high. It succeeds with the
 * feature's position as soon as a search finds one. It fails with the explored extent on max_distance (no new ground
 * left within maxDistance of the start, or twice maxDistance walked), route_blocked (new ground left, but none dry and
 * reachable), low_health or night_fell, so the caller can shelter.
 */
public final class ExploreForTaskExecutor implements WorldTaskExecutor {
	static final int LEG_BLOCKS = 48;
	/** Shorter ends tried, and sideways offsets, when a heading's full-length end is not dry ground. */
	static final int[] LEG_REACHES = {48, 40, 32, 24};
	static final int[] LEG_OFFSETS = {0, 8, -8};
	static final int HEADINGS = 16;
	/** Search radius after each leg; the loaded chunks around the body usually reach about this far. */
	static final int SEARCH_RADIUS = 128;
	static final double ARRIVE_BLOCKS = 4.0D;
	/** A leg that ended this much closer to its end than it started was walked, even if it fell short. */
	static final double PARTIAL_LEG_BLOCKS = 16.0D;
	/** A heading whose legs failed this often is not tried again. */
	static final int MAX_HEADING_FAILURES = 2;
	static final int LEG_TIMEOUT_TICKS = 900;
	/** Gives up on a leg before the dispatcher's 100-tick stall watchdog would end the whole task. */
	static final long NAVIGATION_STALL_TICKS = 70;
	static final float LOW_HEALTH = 6.0F;
	/** Ends nearer than this to every point already walked show nothing new. */
	static final double MIN_NOVELTY_BLOCKS = 16.0D;
	static final double NOVELTY_CAP_BLOCKS = 96.0D;
	static final double VISIT_SPACING_BLOCKS = 8.0D;
	/** The walk may cover this many times maxDistance before it counts as done. */
	static final int WALK_BUDGET_FACTOR = 2;
	static final int TIMEOUT_TICKS = 36_000;
	static final double WATER_PENALTY = 50.0D;
	static final NavigationOptions OPTIONS = NavigationOptions.WALK_ONLY.withWaterPenalty(WATER_PENALTY);
	/** Sunset to sunrise in day ticks: hostile mobs spawn on the surface from about 13,000. */
	static final long NIGHT_START = 12_500L, NIGHT_END = 23_200L;

	private final NavigationFacade navigation;
	private final Environment environment;
	private WorldTaskRequest request;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	private ExploreForStepArgs args;
	private Phase phase = Phase.SEARCH;
	private int activeTicks, phaseTicks;
	private Vec3 origin, lastVisit, lastFeet;
	private final List<Vec3> visited = new ArrayList<>();
	private final int[] headingFailures = new int[HEADINGS];
	private Integer lastHeading;
	private Leg leg;
	private double legStartDistance;
	private double walked, furthest;
	private Vec3 furthestAt;
	private int legs, searches;
	private GoalPosition navigationGoal;
	private boolean navigationOwned, navigationArrived;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);

	public ExploreForTaskExecutor(NavigationFacade navigation) {
		this(navigation, new MinecraftExploreEnvironment());
	}

	ExploreForTaskExecutor(NavigationFacade navigation, Environment environment) {
		this.navigation = Objects.requireNonNull(navigation, "navigation");
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.EXPLORE_FOR) {
			if (request != null) release();
			request = null;
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest next = activeTask.get();
		if (request == null || !request.taskId().equals(next.taskId())) {
			release();
			request = next;
			reset();
		}
		if (terminal != null) return finishRelease();
		if (session == null || !session.companionActuationAllowed()) {
			release();
			setSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, "session_gate", null);
			return Optional.empty();
		}
		if (!navigation.isLoaded()) return finish(false, "navigation_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED);
		Body body = environment.observe();
		if (body == null) return finish(false, "world_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED);
		activeTicks++;
		phaseTicks++;
		track(body.feet());
		if (body.health() <= LOW_HEALTH)
			return finish(false, String.format(Locale.ROOT, "low_health health=%.1f; eat and let health recover, then call explore_for again",
				body.health()), TaskFailureCode.ENVIRONMENT_CHANGED);
		if (body.night())
			return finish(false, "night_fell; shelter until morning (hostile mobs spawn in the open), then call explore_for again",
				TaskFailureCode.ENVIRONMENT_CHANGED);
		if (activeTicks > TIMEOUT_TICKS) return finish(false, "timeout", TaskFailureCode.TRANSIENT);

		Optional<TaskTerminalEvent> ended = switch (phase) {
			case SEARCH -> search(body);
			case WALK -> walk(body);
		};
		if (ended.isPresent()) return ended;
		setSnapshot(TaskExecutionState.RUNNING, "working", body);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> search(Body body) {
		searches++;
		Optional<Feature> found = environment.findFeature(args.featureKind(), body.feet(), SEARCH_RADIUS);
		if (found.isPresent()) {
			Feature feature = found.get();
			return finish(true, String.format(Locale.ROOT, "feature_found featureKind=%s pos=%d,%d,%d distance=%.0f %s;",
				args.featureKind(), feature.x(), feature.y(), feature.z(),
				Math.hypot(feature.x() + 0.5D - body.feet().x, feature.z() + 0.5D - body.feet().z), feature.summary()), null);
		}
		if (walked >= (double) WALK_BUDGET_FACTOR * args.maxDistance())
			return finish(false, "max_distance; walked " + WALK_BUDGET_FACTOR + "x maxDistance without finding " + args.featureKind(),
				TaskFailureCode.MISSING_FACT);
		Choice choice = chooseLeg(body.feet(), origin, args.maxDistance(), visited, headingFailures, lastHeading, environment::ground);
		if (choice.leg() == null) {
			if (choice.exhausted()) return finish(false, "max_distance; no new ground left within maxDistance=" + args.maxDistance()
				+ " of the start and no " + args.featureKind() + " found", TaskFailureCode.MISSING_FACT);
			return finish(false, "route_blocked; no heading leads to new dry ground", TaskFailureCode.TRANSIENT);
		}
		leg = choice.leg();
		legStartDistance = horizontal(body.feet(), leg.x() + 0.5D, leg.z() + 0.5D);
		legs++;
		enter(Phase.WALK);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> walk(Body body) {
		double remaining = horizontal(body.feet(), leg.x() + 0.5D, leg.z() + 0.5D);
		boolean arrived = remaining <= ARRIVE_BLOCKS;
		boolean moving = !arrived && navigate(new GoalPosition(leg.x(), leg.y(), leg.z(), false));
		if (!arrived && !navigationArrived && moving && phaseTicks <= LEG_TIMEOUT_TICKS) return Optional.empty();
		stopNavigation();
		boolean walkedEnough = arrived || navigationArrived || legStartDistance - remaining >= PARTIAL_LEG_BLOCKS;
		if (walkedEnough) lastHeading = leg.heading();
		else headingFailures[leg.heading()]++;
		enter(Phase.SEARCH);
		return Optional.empty();
	}

	/** Remembers where the body has been, how far it walked and how far it got from the start. */
	private void track(Vec3 feet) {
		if (origin == null) {
			origin = feet;
			lastVisit = feet;
			lastFeet = feet;
			furthestAt = feet;
			visited.add(feet);
			return;
		}
		walked += horizontal(feet, lastFeet.x, lastFeet.z);
		lastFeet = feet;
		if (horizontal(feet, lastVisit.x, lastVisit.z) >= VISIT_SPACING_BLOCKS) {
			visited.add(feet);
			lastVisit = feet;
		}
		double out = horizontal(feet, origin.x, origin.z);
		if (out > furthest) {
			furthest = out;
			furthestAt = feet;
		}
	}

	/** One leg: where it ends, and the heading it was chosen on. */
	record Leg(int heading, int x, int y, int z, double score) { }

	/**
	 * The chosen leg, or null with whether that is because nothing new is left within the allowed distance (rather than
	 * new ground that is wet, unsafe or on a heading whose legs failed).
	 */
	record Choice(Leg leg, boolean exhausted) { }

	@FunctionalInterface
	interface GroundView {
		Ground ground(int x, int z, int nearY);
	}

	/**
	 * Picks the next leg: for each of {@link #HEADINGS} headings, the first dry end along it (full length, then shorter,
	 * then a little to either side) inside maxDistance of the start. Ends near ground already walked are skipped. The best
	 * end is the most new ground (far from every walked point, outward from the start, unloaded chunks ahead), keeping
	 * roughly to the last heading, minus water and lava on the straight way there.
	 */
	static Choice chooseLeg(Vec3 feet, Vec3 origin, int maxDistance, List<Vec3> visited, int[] headingFailures,
		Integer lastHeading, GroundView ground) {
		Leg best = null;
		boolean novelInBounds = false;
		int excluded = 0;
		int nearY = (int) Math.floor(feet.y);
		double fromOrigin = horizontal(feet, origin.x, origin.z);
		for (int heading = 0; heading < HEADINGS; heading++) {
			if (headingFailures[heading] >= MAX_HEADING_FAILURES) {
				excluded++;
				continue;
			}
			double angle = 2 * Math.PI * heading / HEADINGS;
			double dx = Math.cos(angle), dz = Math.sin(angle);
			Leg candidate = null;
			search:
			for (int reach : LEG_REACHES) {
				for (int offset : LEG_OFFSETS) {
					double px = feet.x + dx * reach - dz * offset, pz = feet.z + dz * reach + dx * offset;
					if (horizontal(origin, px, pz) > maxDistance) continue;
					double novelty = NOVELTY_CAP_BLOCKS;
					for (Vec3 point : visited) novelty = Math.min(novelty, horizontal(point, px, pz));
					if (novelty < MIN_NOVELTY_BLOCKS) continue;
					novelInBounds = true;
					int x = (int) Math.floor(px), z = (int) Math.floor(pz);
					Ground end = ground.ground(x, z, nearY);
					if (end.kind() != Ground.Kind.DRY) continue;
					int water = 0, danger = 0;
					int samples = Math.max(1, (int) Math.ceil(Math.hypot(px - feet.x, pz - feet.z) / 4.0D));
					for (int sample = 1; sample < samples; sample++) {
						double t = (double) sample / samples;
						Ground on = ground.ground((int) Math.floor(feet.x + (px - feet.x) * t), (int) Math.floor(feet.z + (pz - feet.z) * t), nearY);
						if (on.kind() == Ground.Kind.WATER) water++;
						else if (on.kind() == Ground.Kind.DANGER) danger++;
					}
					int unloaded = 0;
					for (int ahead : new int[]{16, 48, 80}) {
						if (ground.ground((int) Math.floor(px + dx * ahead), (int) Math.floor(pz + dz * ahead), nearY).kind() == Ground.Kind.UNLOADED)
							unloaded++;
					}
					double outward = (horizontal(origin, px, pz) - fromOrigin) / reach;
					double continuity = lastHeading == null ? 0 : Math.cos(2 * Math.PI * (heading - lastHeading) / HEADINGS);
					double score = novelty / NOVELTY_CAP_BLOCKS * 4.0D + outward * 2.0D + unloaded / 3.0D + continuity
						- water * 0.75D - danger * 3.0D - (LEG_BLOCKS - reach) * 0.02D;
					candidate = new Leg(heading, x, end.y(), z, score);
					break search;
				}
			}
			if (candidate != null && (best == null || candidate.score() > best.score())) best = candidate;
		}
		return new Choice(best, best == null && !novelInBounds && excluded == 0);
	}

	static boolean night(long timeOfDay) {
		long time = Math.floorMod(timeOfDay, 24_000L);
		return time >= NIGHT_START && time < NIGHT_END;
	}

	/** How far the walk got, for every terminal message. */
	String evidence() {
		if (origin == null) return "";
		return String.format(Locale.ROOT, " explored: start=%d,%d furthest=%.0f blocks at %d,%d walked=%.0f legs=%d searches=%d searchRadius=%d",
			(long) Math.floor(origin.x), (long) Math.floor(origin.z), furthest, (long) Math.floor(furthestAt.x), (long) Math.floor(furthestAt.z),
			walked, legs, searches, SEARCH_RADIUS);
	}

	private boolean navigate(GoalPosition goal) {
		if (!navigationOwned || navigationGoal == null || navigationGoal.x() != goal.x() || navigationGoal.z() != goal.z()) {
			stopNavigation();
			NavigationRelease.release(navigation);
			navigation.pollPathEvent();
			navigation.startNavigateNear(goal, 2, OPTIONS);
			navigationOwned = true;
			navigationGoal = goal;
			navigationArrived = false;
			navigationStall.clear();
		}
		String event = navigation.pollPathEvent().orElse("");
		if (event.equals("AT_GOAL")) navigationArrived = true;
		var progress = navigation.navigationProgress();
		if (progress.isEmpty()) navigationStall.clear();
		else if (navigationStall.observe(activeTicks, progress.get())) return false;
		return !event.contains("CALC_FAILED") && !event.equals("CANCELED");
	}

	private void stopNavigation() {
		if (navigationOwned) navigation.cancel();
		navigationOwned = false;
		navigationArrived = false;
		navigationGoal = null;
		navigationStall.clear();
	}

	private static double horizontal(Vec3 from, double x, double z) {
		return Math.hypot(from.x - x, from.z - z);
	}

	private void enter(Phase next) {
		phase = next;
		phaseTicks = 0;
	}

	private void release() {
		stopNavigation();
		environment.release();
	}

	private void reset() {
		terminal = null;
		emitted = false;
		args = ((WorldTaskRequest.ExploreFor) request.task()).args();
		phase = Phase.SEARCH;
		activeTicks = phaseTicks = 0;
		origin = lastVisit = lastFeet = furthestAt = null;
		visited.clear();
		java.util.Arrays.fill(headingFailures, 0);
		lastHeading = null;
		leg = null;
		walked = furthest = 0;
		legs = searches = 0;
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason, TaskFailureCode failureCode) {
		release();
		terminal = new TaskTerminalEvent(request.taskId(), null, success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED,
			reason + evidence(), success ? TaskTerminationCause.GOAL_REACHED : null, success ? null : failureCode);
		return finishRelease();
	}

	private Optional<TaskTerminalEvent> finishRelease() {
		release();
		NavigationRelease.release(navigation);
		snapshot = new TaskExecutionSnapshot(terminal.terminalState(), request.taskId(), null, "ExploreFor",
			terminal.message(), null, terminal.terminationCause());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}

	private void setSnapshot(TaskExecutionState state, String reason, Body body) {
		StringBuilder detail = new StringBuilder(reason).append(" phase=").append(phase);
		if (args != null) detail.append(" featureKind=").append(args.featureKind()).append(" maxDistance=").append(args.maxDistance());
		if (body != null) detail.append(String.format(Locale.ROOT, " feet=%.1f,%.1f,%.1f health=%.1f", body.feet().x,
			body.feet().y, body.feet().z, body.health()));
		if (phase == Phase.WALK && leg != null) detail.append(" leg=").append(leg.x()).append(',').append(leg.y()).append(',').append(leg.z());
		detail.append(evidence());
		snapshot = new TaskExecutionSnapshot(state, request == null ? null : request.taskId(), null, "ExploreFor", detail.toString(),
			null, null);
	}

	@Override
	public TaskExecutionSnapshot snapshot() { return snapshot; }

	@Override
	public void onWorldLeave() {
		release();
		request = null;
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override
	public void shutdown() { release(); }

	enum Phase { SEARCH, WALK }

	/** What the task reads from the player each tick; {@code night} is false where the sky has no day cycle. */
	record Body(Vec3 feet, float health, boolean night) { }

	/** A found feature: the block to go to and the search's own description of it. */
	record Feature(int x, int y, int z, String summary) { }

	/** The top of a column, where the body would stand: dry ground, water, lava or another hazard, or not loaded. */
	record Ground(Kind kind, int y) {
		enum Kind { DRY, WATER, DANGER, UNLOADED }

		static final Ground UNLOADED = new Ground(Kind.UNLOADED, 0);
	}

	interface Environment {
		/** Null when no world is loaded. */
		Body observe();
		/** The nearest feature of the kind in loaded chunks within {@code radius} blocks of the feet. */
		Optional<Feature> findFeature(String featureKind, Vec3 feet, int radius);
		/** The ground of one column, near {@code nearY} where the world has a ceiling. */
		Ground ground(int x, int z, int nearY);
		void release();
	}
}
