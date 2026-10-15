package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.StrongholdTriangulation.Estimate;
import ai.moeru.airicraft.agent.tasks.StrongholdTriangulation.Ray;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds the stronghold with eyes of ender without waiting on the planner, whose hand-driven throws were too slow to read
 * an eye's flight. An eye flies from where it is thrown toward the stronghold for about 80 ticks; its horizontal flight is
 * a bearing. In order: throw an eye and read its bearing, pick the dropped eye back up, walk about 200 blocks at an angle
 * to that bearing and throw again, intersect the bearings, walk toward the estimate (throwing once more on the way when it
 * is far and eyes allow) and throw near it to confirm. An eye that flies down means the stronghold is right here.
 * Never digs: it ends with the estimated x,z and the throws behind it.
 */
public final class LocateStrongholdTaskExecutor implements WorldTaskExecutor {
	/** Walking two thousand blocks takes about 10,000 ticks; this leaves room for detours and night. */
	static final int TIMEOUT_TICKS = 48_000;
	static final int THROW_TIMEOUT_TICKS = 60;
	/** The eye entity reaches the client a tick or two after the server spawns it. */
	static final int SPAWN_WAIT_TICKS = 30;
	/** Uses that never put an eye in the air (no stronghold to signal, or the use was refused) before giving up. */
	static final int MAX_FAILED_USES = 3;
	/** An eye lives 80 ticks. */
	static final int FLIGHT_TIMEOUT_TICKS = 140;
	/** The dropped eye appears where the eye ended; without one by now it shattered. */
	static final int DROP_WAIT_TICKS = 30;
	static final int PICKUP_TIMEOUT_TICKS = 300;
	static final double KNOWN_EYE_RADIUS = 32.0D;
	static final double SPAWN_RADIUS = 4.0D;
	static final double DROP_SEARCH_RADIUS = 6.0D;
	/** Horizontal flight needed to read a bearing; a far eye flies about 12 blocks. */
	static final double MIN_FLIGHT_BLOCKS = 0.5D;
	/** An eye that ends this much lower than it started is going down into the stronghold under it. */
	static final double DOWN_BLOCKS = 0.75D;
	static final double BASELINE_BLOCKS = 200.0D;
	/** Degrees between the bearing and the sideways walk: half the walk widens the crossing, half closes in. */
	static final double BASELINE_TURN_DEGREES = 45.0D;
	/** Sideways distance from the last bearing's line that still gives a usable crossing when the walk is cut short. */
	static final double MIN_BASELINE_OFFSET = 48.0D;
	static final double ARRIVE_BLOCKS = 24.0D;
	/** Farther than this, two bearings that barely cross are noise rather than a stronghold. */
	static final double MAX_ESTIMATE_BLOCKS = 6_000.0D;
	/** On a long walk toward a far estimate, throw again after this far when two or more throws are left. */
	static final double REFINE_BLOCKS = 300.0D;
	static final double SEGMENT_BLOCKS = 40.0D;
	static final double DETOUR_SEGMENT_BLOCKS = 24.0D;
	static final double SEGMENT_REACHED_BLOCKS = 3.0D;
	static final int SEGMENT_TIMEOUT_TICKS = 900;
	/** Headings tried, relative to the destination, after a segment fails; a reached segment starts over. */
	static final double[] DETOURS = {35.0D, -35.0D, 70.0D, -70.0D, 110.0D, -110.0D};
	/** A walk that has not come this much closer to its destination for {@link #APPROACH_TICKS} is blocked. */
	static final double APPROACH_BLOCKS = 8.0D;
	static final int APPROACH_TICKS = 1_800;
	static final float LOW_HEALTH = 6.0F;
	/** Gives up on a route before the dispatcher's 100-tick stall watchdog would end the whole task. */
	static final long NAVIGATION_STALL_TICKS = 70;

	private final NavigationFacade navigation;
	private final Environment environment;
	private WorldTaskRequest request;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	private Phase phase = Phase.START;
	private int activeTicks, phaseTicks;
	private int maxEyes;
	private int throwsMade, failedUses, eyesRecovered, eyesLost;
	private final List<Throw> history = new ArrayList<>();
	private final List<Ray> rays = new ArrayList<>();
	private Action lastAction = Action.NONE;
	private Set<Integer> knownEyes = Set.of();
	private Vec3 throwFeet;
	private int eyesBeforeThrow, eyesAfterThrow;
	private int flightId = -1;
	private Vec3 flightFirst, flightLast;
	private Vec3 dropAt;
	private Travel travel;
	private boolean triedOtherSide;
	private GoalPosition navigationGoal;
	private boolean navigationOwned;
	private boolean navigationArrived;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);
	private String lastNote = "";

	public LocateStrongholdTaskExecutor(NavigationFacade navigation, ai.moeru.airicraft.agent.control.CameraController camera) {
		this(navigation, new MinecraftStrongholdEnvironment(camera));
	}

	LocateStrongholdTaskExecutor(NavigationFacade navigation, Environment environment) {
		this.navigation = Objects.requireNonNull(navigation, "navigation");
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.LOCATE_STRONGHOLD) {
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
		if (!body.overworld())
			return finish(false, "wrong_dimension; eyes of ender lead to strongholds only in the overworld", TaskFailureCode.INVALID_ACTION);
		if (phase == Phase.START) {
			if (body.eyes() <= 0)
				return finish(false, "no_eyes_of_ender; craft them from ender pearls and blaze powder", TaskFailureCode.MISSING_ITEM);
			enter(Phase.THROW);
		}
		if (body.health() <= LOW_HEALTH)
			return finish(false, String.format(Locale.ROOT, "low_health health=%.1f; eat and let health recover, then call locate_stronghold again",
				body.health()), TaskFailureCode.ENVIRONMENT_CHANGED);
		if (activeTicks > TIMEOUT_TICKS) return finish(false, "timeout", TaskFailureCode.TRANSIENT);

		Optional<TaskTerminalEvent> ended = switch (phase) {
			case START, THROW -> throwEye(body);
			case AWAIT_EYE -> awaitEye(body);
			case FLIGHT -> flight(body);
			case PICKUP -> pickup(body);
			case DECIDE -> decide(body);
			case TRAVEL -> travel(body);
		};
		if (ended.isPresent()) return ended;
		// A decision made this tick acts on the next one; the snapshot shows where it went.
		setSnapshot(TaskExecutionState.RUNNING, "working", body);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> throwEye(Body body) {
		if (phaseTicks <= 1) {
			stopNavigation();
			NavigationRelease.release(navigation);
		}
		if (body.eyes() <= 0) return outOfThrows(body);
		knownEyes = environment.eyesNear(body.feet(), KNOWN_EYE_RADIUS);
		switch (environment.throwEye()) {
			case USED -> {
				throwFeet = body.feet();
				eyesBeforeThrow = body.eyes();
				enter(Phase.AWAIT_EYE);
			}
			case NO_EYE -> {
				return outOfThrows(body);
			}
			case WORKING -> {
				if (phaseTicks > THROW_TIMEOUT_TICKS)
					return finish(false, "cannot_throw; could not hold and use an eye of ender", TaskFailureCode.TRANSIENT);
			}
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> awaitEye(Body body) {
		for (int id : environment.eyesNear(throwFeet, SPAWN_RADIUS)) {
			if (knownEyes.contains(id)) continue;
			flightId = id;
			flightFirst = flightLast = environment.eye(id).orElse(throwFeet);
			throwsMade++;
			failedUses = 0;
			enter(Phase.FLIGHT);
			return Optional.empty();
		}
		if (phaseTicks <= SPAWN_WAIT_TICKS) return Optional.empty();
		if (body.eyes() < eyesBeforeThrow) {
			// Spent, but its flight was never seen: a throw without a bearing.
			throwsMade++;
			eyesLost++;
			history.add(new Throw(throwFeet.x, throwFeet.z, null, false, null));
			lastNote = "eye_not_seen";
			lastAction = Action.THREW;
			enter(Phase.DECIDE);
			return Optional.empty();
		}
		if (++failedUses >= MAX_FAILED_USES)
			return finish(false, "no_eye_flight; " + MAX_FAILED_USES + " uses of an eye of ender sent no eye flying: this world may have no"
				+ " stronghold to lead to", TaskFailureCode.MISSING_FACT);
		enter(Phase.THROW);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> flight(Body body) {
		Optional<Vec3> at = environment.eye(flightId);
		if (at.isPresent() && phaseTicks <= FLIGHT_TIMEOUT_TICKS) {
			flightLast = at.get();
			return Optional.empty();
		}
		boolean down = flightLast.y - flightFirst.y < -DOWN_BLOCKS;
		Ray ray = StrongholdTriangulation.ray(throwFeet.x, throwFeet.z, flightLast.x, flightLast.z, MIN_FLIGHT_BLOCKS).orElse(null);
		history.add(new Throw(throwFeet.x, throwFeet.z, ray, down, flightLast));
		if (ray != null && !down) rays.add(ray);
		lastNote = down ? "eye_went_down" : ray == null ? "eye_barely_moved" : "bearing=" + ray.describe();
		lastAction = Action.THREW;
		eyesAfterThrow = body.eyes();
		dropAt = flightLast;
		enter(Phase.PICKUP);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> pickup(Body body) {
		if (body.eyes() > eyesAfterThrow) {
			eyesRecovered++;
			stopNavigation();
			enter(Phase.DECIDE);
			return Optional.empty();
		}
		Optional<Vec3> drop = environment.droppedEye(dropAt, DROP_SEARCH_RADIUS);
		if (drop.isEmpty()) {
			if (phaseTicks > DROP_WAIT_TICKS) {
				eyesLost++;
				stopNavigation();
				enter(Phase.DECIDE);
			}
			return Optional.empty();
		}
		dropAt = drop.get();
		if (!navigate(column(dropAt, body.feet())) || phaseTicks > PICKUP_TIMEOUT_TICKS) {
			eyesLost++;
			lastNote = "eye_drop_unreachable";
			stopNavigation();
			enter(Phase.DECIDE);
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> decide(Body body) {
		Throw last = history.isEmpty() ? null : history.getLast();
		if (lastAction == Action.THREW && last != null && last.down()) {
			return finish(true, String.format(Locale.ROOT, "stronghold_located estimate=%d,%d distance=%.0f eye_went_down=true; the stronghold is"
				+ " under this spot", blockOf(last.eyeEnd().x), blockOf(last.eyeEnd().z),
				Math.hypot(last.eyeEnd().x - body.feet().x, last.eyeEnd().z - body.feet().z)), null);
		}
		boolean throwable = throwsMade < maxEyes && body.eyes() > 0;
		Vec3 feet = body.feet();
		Optional<Estimate> found = estimate();
		if (found.isPresent()) {
			Estimate estimate = found.get();
			double distance = estimate.distanceFrom(feet.x, feet.z);
			if (distance <= ARRIVE_BLOCKS) {
				// Thrown from here, or no throw left to check it with.
				if (lastAction == Action.THREW || !throwable) return located(estimate, distance, lastAction == Action.THREW);
				enter(Phase.THROW);
				return Optional.empty();
			}
			if (lastAction != Action.THREW) {
				// The walk ended short of the estimate: look again from here.
				if (throwable) {
					enter(Phase.THROW);
					return Optional.empty();
				}
				if (lastAction == Action.TRAVEL_FAILED)
					return finish(false, "route_blocked; no way on toward the estimate", TaskFailureCode.TRANSIENT);
				return outOfThrows(body);
			}
			startTravel(Purpose.ESTIMATE, estimate.x(), estimate.z(), ARRIVE_BLOCKS - 4.0D, 0);
			return Optional.empty();
		}
		Ray latest = rays.isEmpty() ? null : rays.getLast();
		if (lastAction == Action.THREW) {
			if (!throwable) return outOfThrows(body);
			if (last == null || last.ray() == null) {
				// No bearing from that throw: throw again from here.
				enter(Phase.THROW);
				return Optional.empty();
			}
			// A bearing with nothing to cross: walk off at an angle to it and throw again.
			triedOtherSide = false;
			startBaseline(latest, feet, 1);
			return Optional.empty();
		}
		if (lastAction == Action.TRAVEL_FAILED && latest != null && latest.offset(feet.x, feet.z) < MIN_BASELINE_OFFSET) {
			if (!triedOtherSide) {
				triedOtherSide = true;
				startBaseline(latest, feet, travel == null ? -1 : -travel.side);
				return Optional.empty();
			}
			return finish(false, "route_blocked; could not walk off the bearing line to throw again", TaskFailureCode.TRANSIENT);
		}
		if (!throwable) return outOfThrows(body);
		enter(Phase.THROW);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> travel(Body body) {
		Vec3 feet = body.feet();
		Travel t = travel;
		t.ticks++;
		double remaining = Math.hypot(t.destX - feet.x, t.destZ - feet.z);
		if (remaining <= t.arrive) {
			stopNavigation();
			lastAction = Action.TRAVELED;
			enter(Phase.DECIDE);
			return Optional.empty();
		}
		if (remaining < t.bestRemaining - APPROACH_BLOCKS) {
			t.bestRemaining = remaining;
			t.bestTick = t.ticks;
		}
		if (t.ticks - t.bestTick > APPROACH_TICKS) return travelFailed("no_approach");
		if (t.purpose == Purpose.ESTIMATE && refineDue(body, remaining)) {
			stopNavigation();
			lastNote = "refining_on_the_way";
			enter(Phase.THROW);
			return Optional.empty();
		}
		if (t.segment != null && Math.hypot(t.segment.x() + 0.5D - feet.x, t.segment.z() + 0.5D - feet.z) <= SEGMENT_REACHED_BLOCKS) {
			t.segment = null;
			t.failures = 0;
		}
		if (t.segment == null) {
			t.segment = waypoint(feet, t, remaining);
			t.segmentTicks = 0;
		}
		t.segmentTicks++;
		boolean moving = navigate(t.segment);
		if (navigationArrived) {
			stopNavigation();
			t.segment = null;
			t.failures = 0;
			return Optional.empty();
		}
		if (!moving || t.segmentTicks > SEGMENT_TIMEOUT_TICKS) {
			stopNavigation();
			t.segment = null;
			if (++t.failures > DETOURS.length) return travelFailed("segments_failed");
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> travelFailed(String why) {
		stopNavigation();
		lastNote = "walk_" + why;
		lastAction = Action.TRAVEL_FAILED;
		enter(Phase.DECIDE);
		return Optional.empty();
	}

	/** Far from the estimate with throws to spare: a bearing from nearer narrows it. */
	private boolean refineDue(Body body, double remaining) {
		if (remaining <= REFINE_BLOCKS || maxEyes - throwsMade < 2 || body.eyes() < 2) return false;
		Throw last = history.isEmpty() ? null : history.getLast();
		return last != null && Math.hypot(body.feet().x - last.x(), body.feet().z - last.z()) >= REFINE_BLOCKS;
	}

	private static GoalPosition waypoint(Vec3 feet, Travel travel, double remaining) {
		double dx = (travel.destX - feet.x) / remaining, dz = (travel.destZ - feet.z) / remaining;
		double length = Math.min(SEGMENT_BLOCKS, remaining);
		if (travel.failures > 0) {
			Ray turned = new Ray(feet.x, feet.z, dx, dz).turned(DETOURS[travel.failures - 1]);
			dx = turned.dx();
			dz = turned.dz();
			length = Math.min(DETOUR_SEGMENT_BLOCKS, length);
		}
		return new GoalPosition((int) Math.floor(feet.x + dx * length), (int) Math.floor(feet.y), (int) Math.floor(feet.z + dz * length), false);
	}

	private void startBaseline(Ray bearing, Vec3 feet, int side) {
		Ray heading = new Ray(feet.x, feet.z, bearing.dx(), bearing.dz()).turned(side * BASELINE_TURN_DEGREES);
		startTravel(Purpose.BASELINE, feet.x + heading.dx() * BASELINE_BLOCKS, feet.z + heading.dz() * BASELINE_BLOCKS, 16.0D, side);
	}

	private void startTravel(Purpose purpose, double x, double z, double arrive, int side) {
		travel = new Travel(purpose, x, z, arrive, side);
		enter(Phase.TRAVEL);
	}

	private Optional<Estimate> estimate() {
		return StrongholdTriangulation.intersect(rays).filter(estimate -> history.isEmpty()
			|| estimate.distanceFrom(history.getLast().x(), history.getLast().z()) <= MAX_ESTIMATE_BLOCKS);
	}

	private Optional<TaskTerminalEvent> located(Estimate estimate, double distance, boolean confirmed) {
		return finish(true, String.format(Locale.ROOT, "stronghold_located estimate=%d,%d distance=%.0f confirmed=%s",
			estimate.blockX(), estimate.blockZ(), distance, confirmed), null);
	}

	private Optional<TaskTerminalEvent> outOfThrows(Body body) {
		if (body.eyes() <= 0) return finish(false, "out_of_eyes; no eye of ender left to throw", TaskFailureCode.MISSING_ITEM);
		return finish(false, "bearing_inconclusive; threw maxEyes=" + maxEyes + " without bearings that cross", TaskFailureCode.MISSING_FACT);
	}

	/** Throws, bearings and the best estimate so far, for every terminal message. */
	String evidence() {
		StringBuilder text = new StringBuilder();
		text.append(" throws=").append(throwsMade).append('/').append(maxEyes)
			.append(" eyesRecovered=").append(eyesRecovered).append(" eyesLost=").append(eyesLost);
		Optional<Estimate> estimate = estimate();
		if (estimate.isPresent()) {
			text.append(String.format(Locale.ROOT, " best_estimate=%d,%d crossing=%.1fdeg spread=%.0f", estimate.get().blockX(),
				estimate.get().blockZ(), estimate.get().crossingDegrees(), estimate.get().residual()));
		} else text.append(" best_estimate=none");
		if (!history.isEmpty()) {
			text.append(" bearings=[").append(history.stream().map(Throw::describe).collect(Collectors.joining("; "))).append(']');
		}
		return text.toString();
	}

	private boolean navigate(GoalPosition goal) {
		if (!navigationOwned || navigationGoal == null || navigationGoal.x() != goal.x() || navigationGoal.z() != goal.z()) {
			stopNavigation();
			NavigationRelease.release(navigation);
			navigation.pollPathEvent();
			navigation.startNavigate(goal, NavigationOptions.DEFAULT);
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

	private static GoalPosition column(Vec3 at, Vec3 feet) {
		return new GoalPosition((int) Math.floor(at.x), (int) Math.floor(feet.y), (int) Math.floor(at.z), false);
	}

	private static long blockOf(double coordinate) {
		return (long) Math.floor(coordinate);
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
		phase = Phase.START;
		activeTicks = phaseTicks = 0;
		maxEyes = ((WorldTaskRequest.LocateStronghold) request.task()).args().maxEyes();
		throwsMade = failedUses = eyesRecovered = eyesLost = 0;
		history.clear();
		rays.clear();
		lastAction = Action.NONE;
		knownEyes = Set.of();
		throwFeet = flightFirst = flightLast = dropAt = null;
		flightId = -1;
		travel = null;
		triedOtherSide = false;
		lastNote = "";
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
		snapshot = new TaskExecutionSnapshot(terminal.terminalState(), request.taskId(), null, "LocateStronghold",
			terminal.message(), null, terminal.terminationCause());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}

	private void setSnapshot(TaskExecutionState state, String reason, Body body) {
		StringBuilder detail = new StringBuilder(reason).append(" phase=").append(phase);
		if (body != null) detail.append(String.format(Locale.ROOT, " feet=%.1f,%.1f,%.1f health=%.1f eyes=%d", body.feet().x,
			body.feet().y, body.feet().z, body.health(), body.eyes()));
		if (phase == Phase.TRAVEL && travel != null) {
			double remaining = body == null ? Double.NaN : Math.hypot(travel.destX - body.feet().x, travel.destZ - body.feet().z);
			detail.append(String.format(Locale.ROOT, " walk=%s to=%d,%d remaining=%.0f", travel.purpose.name().toLowerCase(Locale.ROOT),
				blockOf(travel.destX), blockOf(travel.destZ), remaining));
		}
		detail.append(evidence());
		if (!lastNote.isEmpty()) detail.append(' ').append(lastNote);
		snapshot = new TaskExecutionSnapshot(state, request == null ? null : request.taskId(), null, "LocateStronghold", detail.toString(),
			null, null);
	}

	@Override
	public TaskExecutionSnapshot snapshot() { return snapshot; }

	/** The reflex hands the same task back: keep the throws, bearings and estimate; let go of the aim and route. */
	@Override
	public void pauseForReflex() {
		if (request == null) return;
		release();
		// The next segment is chosen from where the body is now.
		if (phase == Phase.TRAVEL && travel != null) travel.segment = null;
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override
	public void onWorldLeave() {
		release();
		request = null;
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override
	public void shutdown() { release(); }

	enum Phase { START, THROW, AWAIT_EYE, FLIGHT, PICKUP, DECIDE, TRAVEL }

	/** What the last step did, which the decision after it depends on. */
	enum Action { NONE, THREW, TRAVELED, TRAVEL_FAILED }

	enum Purpose { BASELINE, ESTIMATE }

	enum ThrowResult { WORKING, USED, NO_EYE }

	/** What the task reads from the player each tick. */
	record Body(Vec3 feet, float health, int eyes, boolean overworld) {}

	/**
	 * One thrown eye: where from, the bearing it flew ({@code null} when unread), whether it went down, and where it was
	 * last seen ({@code null} when never seen).
	 */
	record Throw(double x, double z, Ray ray, boolean down, Vec3 eyeEnd) {
		String describe() {
			String from = (long) Math.floor(x) + "," + (long) Math.floor(z);
			if (down) return from + " down";
			if (ray == null) return from + " no_bearing";
			return String.format(Locale.ROOT, "%s@%.1fdeg", from, ray.bearingDegrees());
		}
	}

	private static final class Travel {
		final Purpose purpose;
		final double destX, destZ, arrive;
		final int side;
		GoalPosition segment;
		int segmentTicks, failures, ticks, bestTick;
		double bestRemaining = Double.MAX_VALUE;

		Travel(Purpose purpose, double destX, double destZ, double arrive, int side) {
			this.purpose = purpose;
			this.destX = destX;
			this.destZ = destZ;
			this.arrive = arrive;
			this.side = side;
		}
	}

	interface Environment {
		/** Null when no world is loaded. */
		Body observe();
		/** Entity ids of eyes of ender in flight within {@code radius} blocks of the point. */
		Set<Integer> eyesNear(Vec3 point, double radius);
		/** Where that eye is while it flies; empty once it has burst or dropped. */
		Optional<Vec3> eye(int entityId);
		/** Puts an eye of ender in the main hand, looks up, away from any end portal frame, and uses it. */
		ThrowResult throwEye();
		/** Where a dropped eye-of-ender item near the point lies. */
		Optional<Vec3> droppedEye(Vec3 near, double radius);
		void release();
	}
}
