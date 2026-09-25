package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.navigation.BodyState;
import ai.moeru.airicraft.navigation.Goal;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.MovementPolicy;
import ai.moeru.airicraft.navigation.Path;
import ai.moeru.airicraft.navigation.PathFollower;
import ai.moeru.airicraft.navigation.SearchBudget;
import ai.moeru.airicraft.navigation.SearchResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The in-house navigation backend behind the {@link BaritoneFacade} interface, so existing consumers
 * can run on it before they move to a typed navigation service. It plans in segments through loaded
 * terrain, follows with {@link PathFollower}, and reports Baritone-style path events: {@code AT_GOAL}
 * on arrival and {@code CALC_FAILED} when no route remains. Cancellation is synchronous and emits no
 * event. Client thread only; {@link #tick} runs once per client tick.
 */
public final class AiricraftNavigationFacade implements BaritoneFacade {
	private static final SearchBudget BUDGET = new SearchBudget(250_000, 1_500_000_000L, 2.0);
	private static final int SUPPORT_WAIT_TICKS = 40;
	/** Segments in a row that end no closer to the goal before navigation gives up. */
	private static final int MAX_SEGMENTS_WITHOUT_PROGRESS = 3;
	private static final int MAX_REPLANS = 40;
	private static final int FOLLOW_RADIUS = 3;
	private static final int FOLLOW_RETARGET_BLOCKS = 3;
	private static final int PLAN_HISTORY = 64;

	private enum Mode { IDLE, NAVIGATE, FOLLOW }

	private final MinecraftMotor motor;
	private final NavigationPlanner planner;
	private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
	private Mode mode = Mode.IDLE;
	private Goal goal;
	private GridPos goalCell;
	private boolean goalHasY;
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
	private double waterPenalty = MovementPolicy.defaults().waterPenalty();
	private long ticks;
	private MinecraftCellClassifier liveClassifier;
	private Object liveWorld;
	private final List<Map<String, Object>> plans = new ArrayList<>();
	private double pendingCaptureMillis;

	public AiricraftNavigationFacade(CameraController camera) {
		this(new MinecraftMotor(camera), NavigationPlanner.shared());
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
	public void applySettings() {
	}

	@Override
	public double walkOnWaterPenalty() {
		return waterPenalty;
	}

	@Override
	public void setWalkOnWaterPenalty(double value) {
		waterPenalty = value;
	}

	@Override
	public void startFollow(String playerName) {
		if (playerName == null || playerName.isBlank()) return;
		begin(Mode.FOLLOW, null, null, false);
		followName = playerName;
	}

	@Override
	public void startNavigate(GoalPosition position) {
		if (position == null) return;
		GridPos cell = new GridPos(position.x(), position.y(), position.z());
		Goal target = position.exactY() ? new Goal.Block(cell.x(), cell.y(), cell.z()) : new Goal.XZ(cell.x(), cell.z());
		begin(Mode.NAVIGATE, target, cell, position.exactY());
	}

	@Override
	public void startNavigateNear(GoalPosition position, int radiusBlocks) {
		if (position == null) return;
		GridPos cell = new GridPos(position.x(), position.y(), position.z());
		begin(Mode.NAVIGATE, new Goal.Near(cell.x(), cell.y(), cell.z(), Math.max(1, radiusBlocks)), cell, true);
	}

	@Override
	public boolean processActive() {
		return mode != Mode.IDLE;
	}

	/** Stops in the same call: keys are released now and no {@code CANCELED} event follows. */
	@Override
	public boolean cancel() {
		stop();
		events.clear();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.isOnThread()) motor.release(client);
		return false;
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
		MinecraftClient client = MinecraftClient.getInstance();
		if (mode == Mode.IDLE || client == null || client.player == null) return Optional.empty();
		ClientPlayerEntity player = client.player;
		BodyState body = body(player);
		if (goal != null && body.supported() && goal.isGoal(body.feet())) return Optional.empty();
		String breakingTarget = null;
		float progress = 0;
		if (motor.breaking() != null && client.interactionManager != null) {
			var accessor = (ai.moeru.airicraft.mixin.client.ClientPlayerInteractionManagerAccessor) client.interactionManager;
			if (accessor.airicraft$breakingBlock()) {
				breakingTarget = accessor.airicraft$currentBreakingPos().toShortString();
				progress = accessor.airicraft$currentBreakingProgress();
			}
		}
		return Optional.of(new NavigationProgress(player.getX(), player.getY(), player.getZ(), body.supported(), breakingTarget, progress));
	}

	@Override
	public boolean navigationGoalReached(GoalPosition position) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (position == null || client == null || client.player == null) return false;
		BodyState body = body(client.player);
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

	public void tick(MinecraftClient client) {
		ticks++;
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) {
			if (mode != Mode.IDLE) stop();
			liveWorld = null;
			return;
		}
		if (mode == Mode.IDLE) {
			motor.release(client);
			return;
		}
		BodyState body = body(player);
		if (mode == Mode.FOLLOW && !retarget(client)) {
			motor.apply(client, ai.moeru.airicraft.navigation.MotorIntent.IDLE);
			return;
		}
		GridPos feet = body.feet();
		if (goal.isGoal(feet) && body.supported()) {
			if (mode == Mode.NAVIGATE) {
				finish("AT_GOAL", null);
				motor.release(client);
				return;
			}
			cancelPlanning();
			follower = null;
			motor.apply(client, ai.moeru.airicraft.navigation.MotorIntent.IDLE);
			return;
		}
		if (follower == null && !plan(client, player, body)) return;

		PathFollower.Tick next = follower.tick(body, liveTerrain(client), plannedPolicy);
		String actionFailure = motor.apply(client, next.intent());
		if (actionFailure != null) {
			finish("CALC_FAILED", actionFailure);
			motor.release(client);
			return;
		}
		switch (next.status()) {
			case RUNNING -> { }
			case ARRIVED -> {
				follower = null;
				if (lastSegment) finish("CALC_FAILED", "goal_unreachable");
			}
			case REPLAN -> {
				follower = null;
				lastReplanReason = next.detail();
				if (++replans > MAX_REPLANS) finish("CALC_FAILED", "too_many_replans " + next.detail());
			}
			case FAILED -> finish("CALC_FAILED", next.detail());
		}
	}

	/** Starts or polls a plan. Returns true once a follower is ready this tick. */
	private boolean plan(MinecraftClient client, ClientPlayerEntity player, BodyState body) {
		if (pending == null) {
			if (!body.supported() && supportWait++ < SUPPORT_WAIT_TICKS) {
				motor.apply(client, ai.moeru.airicraft.navigation.MotorIntent.IDLE);
				return false;
			}
			supportWait = 0;
			MovementPolicy policy = NavigationPolicies.forPlayer(client, waterPenalty);
			if (policy == null) {
				finish("CALC_FAILED", "travel_policy_unavailable");
				motor.release(client);
				return false;
			}
			GridPos start = body.feet();
			GridPos target = goalHasY ? goalCell : new GridPos(goalCell.x(), start.y(), goalCell.z());
			WorldTerrainSnapshot snapshot = WorldTerrainSnapshot.capture(client.world, start, target, goalHasY,
				MinecraftCellClassifier.forPlayer(player));
			plannedPolicy = policy;
			pending = planner.submit(snapshot, policy, start, goal, target, BUDGET);
			pendingCaptureMillis = snapshot.captureMillis();
		}
		if (!pending.done()) {
			motor.apply(client, ai.moeru.airicraft.navigation.MotorIntent.IDLE);
			return false;
		}
		SearchResult result = pending.result();
		GridPos start = pending.start();
		pending = null;
		if (result == null) {
			finish("CALC_FAILED", "planner_error");
			motor.release(client);
			return false;
		}
		record(result, start);
		Path path = switch (result) {
			case SearchResult.Found found -> {
				lastSegment = false;
				yield found.path();
			}
			case SearchResult.Partial partial -> {
				lastSegment = partial.reason() == SearchResult.Reason.NO_ROUTE;
				yield progressed(partial.path()) ? partial.path() : null;
			}
			case SearchResult.Unreachable unreachable -> null;
			case SearchResult.Cancelled cancelled -> null;
		};
		if (path == null) {
			finish("CALC_FAILED", result instanceof SearchResult.Partial ? "no_progress" : "unreachable " + reason(result));
			motor.release(client);
			return false;
		}
		follower = new PathFollower(path);
		return true;
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
	private boolean retarget(MinecraftClient client) {
		PlayerEntity target = null;
		for (PlayerEntity candidate : client.world.getPlayers()) {
			if (candidate != client.player && candidate.getName() != null && followName.equalsIgnoreCase(candidate.getName().getString())) {
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

	private LiveWorldTerrain liveTerrain(MinecraftClient client) {
		if (liveWorld != client.world || liveClassifier == null || ticks % 200 == 0) {
			liveWorld = client.world;
			liveClassifier = MinecraftCellClassifier.forPlayer(client.player);
		}
		return new LiveWorldTerrain(client.world, liveClassifier);
	}

	private BodyState body(ClientPlayerEntity player) {
		return new BodyState(player.getX(), player.getY(), player.getZ(), player.getVelocity().y, player.isOnGround(),
			player.isTouchingWater(), player.isClimbing(), player.horizontalCollision, ticks);
	}

	private void begin(Mode next, Goal target, GridPos cell, boolean hasY) {
		stop();
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
