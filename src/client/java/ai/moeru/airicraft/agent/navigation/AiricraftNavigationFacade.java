package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.agent.control.ControlPlane;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.navigation.BodyState;
import ai.moeru.airicraft.navigation.Goal;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.MovementPolicy;
import ai.moeru.airicraft.navigation.Path;
import ai.moeru.airicraft.navigation.PathFollower;
import ai.moeru.airicraft.navigation.SearchBudget;
import ai.moeru.airicraft.navigation.SearchResult;
import ai.moeru.airicraft.navigation.TerrainView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The in-house navigation backend behind the {@link NavigationFacade} interface, so existing consumers
 * can run on it before they move to a typed navigation service. It plans in segments through loaded
 * terrain, follows with {@link PathFollower}, and reports path events: {@code AT_GOAL}
 * on arrival and {@code CALC_FAILED} when no route remains. Cancellation is synchronous and emits no
 * event. Client thread only; {@link #tick} runs once per client tick.
 */
public final class AiricraftNavigationFacade implements NavigationFacade {
	private static final SearchBudget BUDGET = new SearchBudget(250_000, 1_500_000_000L, 2.0);
	private static final int SUPPORT_WAIT_TICKS = 40;
	/** Segments in a row that end no closer to the goal before navigation gives up. */
	private static final int MAX_SEGMENTS_WITHOUT_PROGRESS = 3;
	private static final int MAX_REPLANS = 40;
	private static final int FOLLOW_RADIUS = 3;
	private static final int FOLLOW_RETARGET_BLOCKS = 3;
	private static final int PLAN_HISTORY = 64;

	private enum Mode { IDLE, NAVIGATE, FOLLOW }

	/** Goals that are a property of the terrain, not a cell: they are bound to the terrain they are checked against. */
	private enum AirGoal { BREATHE, SHORE }

	private final MinecraftMotor motor;
	private final NavigationPlanner planner;
	private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
	private Mode mode = Mode.IDLE;
	private Goal goal;
	private GridPos goalCell;
	private boolean goalHasY;
	private AirGoal airGoal;
	/** An air request that found no route within the air budget plans again, ignoring it: swim and hope. */
	private boolean airRetry;
	/** The current last segment stopped short for want of air, not for want of a route. */
	private boolean lastSegmentAir;
	private String followName;
	private NavigationPlanner.Pending pending;
	private MovementPolicy plannedPolicy;
	private PathFollower follower;
	/** The current path is the closest the goal can be approached: fail at its end. */
	private boolean lastSegment;
	private int supportWait;
	private int replans;
	private int segmentsWithoutProgress;
	private double bestRemaining = Double.POSITIVE_INFINITY;
	private String lastReplanReason;
	private String failure;
	private NavigationOptions options = NavigationOptions.DEFAULT;
	private long ticks;
	private MinecraftCellClassifier liveClassifier;
	private Object liveWorld;
	private final List<Map<String, Object>> plans = new ArrayList<>();
	private double pendingCaptureMillis;

	public AiricraftNavigationFacade(ControlPlane plane) {
		this(new MinecraftMotor(plane), NavigationPlanner.shared());
	}

	AiricraftNavigationFacade(MinecraftMotor motor, NavigationPlanner planner) {
		this.motor = motor;
		this.planner = planner;
	}

	@Override
	public boolean isLoaded() {
		return true;
	}

	@Override
	public void startFollow(String playerName) {
		if (playerName == null || playerName.isBlank()) return;
		begin(Mode.FOLLOW, null, null, false, NavigationOptions.DEFAULT);
		followName = playerName;
	}

	@Override
	public void startNavigate(GoalPosition position, NavigationOptions requestOptions) {
		if (position == null) return;
		GridPos cell = new GridPos(position.x(), position.y(), position.z());
		Goal target = position.exactY() ? new Goal.Block(cell.x(), cell.y(), cell.z()) : new Goal.XZ(cell.x(), cell.z());
		begin(Mode.NAVIGATE, target, cell, position.exactY(), requestOptions);
	}

	@Override
	public void startNavigateNear(GoalPosition position, int radiusBlocks, NavigationOptions requestOptions) {
		if (position == null) return;
		GridPos cell = new GridPos(position.x(), position.y(), position.z());
		begin(Mode.NAVIGATE, new Goal.Near(cell.x(), cell.y(), cell.z(), Math.max(1, radiusBlocks)), cell, true, requestOptions);
	}

	@Override
	public boolean startNavigateToAir(boolean shore, NavigationOptions requestOptions) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.player == null || minecraft.level == null) return false;
		GridPos here = body(minecraft.player).feet();
		begin(Mode.NAVIGATE, new Goal.Block(here.x(), here.y(), here.z()), here, false, requestOptions);
		airGoal = shore ? AirGoal.SHORE : AirGoal.BREATHE;
		airRetry = false;
		return true;
	}

	@Override
	public boolean processActive() {
		return mode != Mode.IDLE;
	}

	/** Stops in the same call: keys are released now and no {@code CANCELED} event follows. */
	@Override
	public void cancel() {
		stop();
		events.clear();
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null && minecraft.isSameThread()) motor.release(minecraft);
	}

	@Override
	public Optional<String> activeProcessName() {
		return switch (mode) {
			case IDLE -> Optional.empty();
			case NAVIGATE -> Optional.of("Airicraft navigate");
			case FOLLOW -> Optional.of("Airicraft follow");
		};
	}

	@Override
	public Optional<Double> estimatedTicksToGoal() {
		return follower == null ? Optional.empty() : Optional.of(follower.remainingCost());
	}

	@Override
	public Optional<String> pollPathEvent() {
		return Optional.ofNullable(events.poll());
	}

	@Override
	public Optional<NavigationProgress> navigationProgress() {
		Minecraft minecraft = Minecraft.getInstance();
		if (mode == Mode.IDLE || minecraft == null || minecraft.player == null) return Optional.empty();
		LocalPlayer player = minecraft.player;
		BodyState body = body(player);
		if (goal != null && body.supported() && reached(minecraft, body.feet())) return Optional.empty();
		String breakingTarget = null;
		float progress = 0;
		if (motor.breaking() != null && minecraft.gameMode != null) {
			var gameMode = minecraft.gameMode;
			if (gameMode.isDestroying()) {
				breakingTarget = gameMode.destroyBlockPos.toShortString();
				progress = gameMode.destroyProgress;
			}
		}
		return Optional.of(new NavigationProgress(player.getX(), player.getY(), player.getZ(), body.supported(), breakingTarget, progress));
	}

	@Override
	public boolean navigationGoalReached(GoalPosition position) {
		Minecraft minecraft = Minecraft.getInstance();
		if (position == null || minecraft == null || minecraft.player == null) return false;
		BodyState body = body(minecraft.player);
		if (!body.supported()) return false;
		GridPos feet = body.feet();
		return feet.x() == position.x() && feet.z() == position.z() && (!position.exactY() || feet.y() == position.y());
	}

	/** Plan outcomes and timings for the task's terminal diagnostics. */
	@Override
	public Map<String, Object> navigationDiagnostics() {
		Map<String, Object> diagnostics = new LinkedHashMap<>();
		diagnostics.put("backend", "airicraft");
		diagnostics.put("plans", plans.size());
		diagnostics.put("replans", replans);
		if (lastReplanReason != null) diagnostics.put("lastReplanReason", lastReplanReason);
		if (failure != null) diagnostics.put("failure", failure);
		List<Double> millis = plans.stream().map(plan -> ((Number) plan.get("searchMillis")).doubleValue()).sorted().toList();
		if (!millis.isEmpty()) {
			diagnostics.put("maxSearchMillis", millis.getLast());
			diagnostics.put("medianSearchMillis", millis.get(millis.size() / 2));
		}
		diagnostics.put("plansDetail", List.copyOf(plans));
		return diagnostics;
	}

	/**
	 * Lets go of keys still held from a navigation that stopped outside this backend's tick. Runs
	 * before other actuators write this tick's input, so the release never overwrites theirs.
	 */
	public void releaseIfIdle(Minecraft minecraft) {
		if (mode == Mode.IDLE) motor.release(minecraft);
	}

	public void tick(Minecraft minecraft) {
		ticks++;
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			if (mode != Mode.IDLE) stop();
			liveWorld = null;
			return;
		}
		if (mode == Mode.IDLE) return;
		BodyState body = body(player);
		if (mode == Mode.FOLLOW && !retarget(minecraft)) {
			motor.apply(minecraft, ai.moeru.airicraft.navigation.MotorIntent.IDLE);
			return;
		}
		GridPos feet = body.feet();
		if (reached(minecraft, feet) && body.supported()) {
			if (mode == Mode.NAVIGATE) {
				finish("AT_GOAL", null);
				motor.release(minecraft);
				return;
			}
			cancelPlanning();
			follower = null;
			motor.apply(minecraft, ai.moeru.airicraft.navigation.MotorIntent.IDLE);
			return;
		}
		if (follower == null && !plan(minecraft, player, body)) return;
		// A stronger holder has the player (a reflex, say): hold the route and its progress until it lets go.
		if (!motor.holdControl()) return;

		PathFollower.Tick next = follower.tick(body, liveTerrain(minecraft), plannedPolicy);
		String actionFailure = motor.apply(minecraft, next.intent());
		if (actionFailure != null) {
			finish("CALC_FAILED", actionFailure);
			motor.release(minecraft);
			return;
		}
		switch (next.status()) {
			case RUNNING -> { }
			case ARRIVED -> {
				follower = null;
				if (lastSegment) finish("CALC_FAILED", lastSegmentAir ? "air_budget" : "goal_unreachable");
			}
			case REPLAN -> {
				follower = null;
				lastReplanReason = next.detail();
				if (++replans > MAX_REPLANS) finish("CALC_FAILED", "too_many_replans " + next.detail());
			}
			case FAILED -> finish("CALC_FAILED", next.detail());
		}
		if (mode == Mode.IDLE) motor.release(minecraft);
	}

	/** Starts or polls a plan. Returns true once a follower is ready this tick. */
	private boolean plan(Minecraft minecraft, LocalPlayer player, BodyState body) {
		if (pending == null) {
			if (!body.supported() && supportWait++ < SUPPORT_WAIT_TICKS) {
				motor.apply(minecraft, ai.moeru.airicraft.navigation.MotorIntent.IDLE);
				return false;
			}
			supportWait = 0;
			MovementPolicy policy = NavigationPolicies.forPlayer(minecraft, options);
			if (policy != null && airRetry) policy = policy.withBreath(MovementPolicy.Breath.UNLIMITED);
			if (policy == null) {
				finish("CALC_FAILED", "travel_policy_unavailable");
				motor.release(minecraft);
				return false;
			}
			GridPos start = body.feet();
			GridPos target = goalHasY ? goalCell : new GridPos(goalCell.x(), start.y(), goalCell.z());
			WorldTerrainSnapshot snapshot = WorldTerrainSnapshot.capture(minecraft.level, start, target, goalHasY,
				MinecraftCellClassifier.forPlayer(player));
			plannedPolicy = policy;
			pending = planner.submit(snapshot, policy, start, goalOn(snapshot, policy), target, BUDGET);
			pendingCaptureMillis = snapshot.captureMillis();
		}
		if (!pending.done()) {
			motor.apply(minecraft, ai.moeru.airicraft.navigation.MotorIntent.IDLE);
			return false;
		}
		SearchResult result = pending.result();
		GridPos start = pending.start();
		pending = null;
		if (result == null) {
			finish("CALC_FAILED", "planner_error");
			motor.release(minecraft);
			return false;
		}
		record(result, start);
		if (airGoal != null && !airRetry && airBudget(result)) {
			// No air within reach of the supply left: head for the nearest anyway, it is the best chance there is.
			airRetry = true;
			return false;
		}
		Path path = switch (result) {
			case SearchResult.Found found -> {
				lastSegment = false;
				yield found.path();
			}
			case SearchResult.Partial partial -> {
				lastSegment = partial.reason() == SearchResult.Reason.NO_ROUTE || partial.reason() == SearchResult.Reason.AIR_BUDGET;
				lastSegmentAir = partial.reason() == SearchResult.Reason.AIR_BUDGET;
				yield progressed(partial.path()) ? partial.path() : null;
			}
			case SearchResult.Unreachable unreachable -> null;
			case SearchResult.Cancelled cancelled -> null;
		};
		if (path == null) {
			finish("CALC_FAILED", result instanceof SearchResult.Partial ? "no_progress" : "unreachable " + reason(result));
			motor.release(minecraft);
			return false;
		}
		follower = new PathFollower(path);
		return true;
	}

	private static boolean airBudget(SearchResult result) {
		return result instanceof SearchResult.Partial partial && partial.reason() == SearchResult.Reason.AIR_BUDGET
			|| result instanceof SearchResult.Unreachable unreachable && unreachable.reason() == SearchResult.Reason.AIR_BUDGET;
	}

	/** The goal as it is checked against {@code terrain}; terrain goals are bound to it. */
	private Goal goalOn(TerrainView terrain, MovementPolicy policy) {
		if (airGoal == null) return goal;
		return airGoal == AirGoal.BREATHE ? new Goal.Breathable(terrain) : new Goal.DryLand(terrain, policy);
	}

	private boolean reached(Minecraft minecraft, GridPos feet) {
		if (airGoal == null) return goal.isGoal(feet);
		MovementPolicy policy = plannedPolicy != null ? plannedPolicy : MovementPolicy.defaults();
		return goalOn(liveTerrain(minecraft), policy).isGoal(feet);
	}

	/** A segment must end closer to the goal than earlier ones, within a few tries. */
	private boolean progressed(Path path) {
		GridPos end = path.end();
		double remaining = goal.heuristic(end.x(), end.y(), end.z());
		if (remaining < bestRemaining - 1) {
			bestRemaining = remaining;
			segmentsWithoutProgress = 0;
			return true;
		}
		return ++segmentsWithoutProgress < MAX_SEGMENTS_WITHOUT_PROGRESS;
	}

	/** Follow targets the named player's cell and replans once it has moved a few blocks. */
	private boolean retarget(Minecraft minecraft) {
		Player target = null;
		for (Player candidate : minecraft.level.players()) {
			if (candidate != minecraft.player && candidate.getName() != null && followName.equalsIgnoreCase(candidate.getName().getString())) {
				target = candidate;
				break;
			}
		}
		if (target == null) {
			cancelPlanning();
			follower = null;
			return false;
		}
		GridPos cell = new GridPos(target.getBlockX(), (int) Math.floor(target.getY() + 0.1251), target.getBlockZ());
		if (goalCell == null || goalCell.manhattan(cell) > FOLLOW_RETARGET_BLOCKS) {
			goalCell = cell;
			goalHasY = true;
			goal = new Goal.Near(cell.x(), cell.y(), cell.z(), FOLLOW_RADIUS);
			cancelPlanning();
			follower = null;
			bestRemaining = Double.POSITIVE_INFINITY;
			segmentsWithoutProgress = 0;
		}
		return true;
	}

	private void record(SearchResult result, GridPos start) {
		Map<String, Object> plan = new LinkedHashMap<>();
		plan.put("outcome", result.outcome());
		if (reason(result) != null) plan.put("reason", reason(result));
		plan.put("start", start.toString());
		plan.put("expanded", result.stats().expanded());
		plan.put("searchMillis", Math.round(result.stats().millis() * 100) / 100.0);
		plan.put("captureMillis", Math.round(pendingCaptureMillis * 100) / 100.0);
		Path path = result instanceof SearchResult.Found found ? found.path()
			: result instanceof SearchResult.Partial partial ? partial.path() : null;
		if (path != null) {
			plan.put("steps", path.steps().size());
			plan.put("cost", Math.round(path.cost() * 100) / 100.0);
			plan.put("end", path.end().toString());
			plan.put("breaks", path.breaks());
			plan.put("places", path.places());
		}
		if (plans.size() == PLAN_HISTORY) plans.removeFirst();
		plans.add(plan);
	}

	private static String reason(SearchResult result) {
		return switch (result) {
			case SearchResult.Partial partial -> partial.reason().name();
			case SearchResult.Unreachable unreachable -> unreachable.reason().name();
			default -> null;
		};
	}

	private LiveWorldTerrain liveTerrain(Minecraft minecraft) {
		if (liveWorld != minecraft.level || liveClassifier == null || ticks % 200 == 0) {
			liveWorld = minecraft.level;
			liveClassifier = MinecraftCellClassifier.forPlayer(minecraft.player);
		}
		return new LiveWorldTerrain(minecraft.level, liveClassifier);
	}

	private BodyState body(LocalPlayer player) {
		return new BodyState(player.getX(), player.getY(), player.getZ(), player.getDeltaMovement().y, player.onGround(),
			player.isInWater(), player.onClimbable(), player.horizontalCollision, player.isSwimming(), ticks);
	}

	private void begin(Mode next, Goal target, GridPos cell, boolean hasY, NavigationOptions requestOptions) {
		stop();
		options = requestOptions == null ? NavigationOptions.DEFAULT : requestOptions;
		events.clear();
		plans.clear();
		replans = 0;
		lastReplanReason = null;
		failure = null;
		mode = next;
		goal = target;
		goalCell = cell;
		goalHasY = hasY;
	}

	private void finish(String event, String reason) {
		failure = reason;
		stop();
		events.add(event);
	}

	private void stop() {
		cancelPlanning();
		mode = Mode.IDLE;
		follower = null;
		goal = null;
		airGoal = null;
		airRetry = false;
		lastSegmentAir = false;
		goalCell = null;
		followName = null;
		lastSegment = false;
		supportWait = 0;
		segmentsWithoutProgress = 0;
		bestRemaining = Double.POSITIVE_INFINITY;
	}

	private void cancelPlanning() {
		if (pending != null) pending.cancel();
		pending = null;
	}
}
