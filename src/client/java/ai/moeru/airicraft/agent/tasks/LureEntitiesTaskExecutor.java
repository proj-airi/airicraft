package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Owns acquisition, follower pacing and animal arrival; gates remain explicit world actions. */
public final class LureEntitiesTaskExecutor implements WorldTaskExecutor {
	private final NavigationFacade navigation;
	private final Environment environment;
	private WorldTaskRequest request;
	private LureEntitiesStepArgs args;
	private Phase phase = Phase.ACQUIRE;
	private final Set<String> acquired = new HashSet<>();
	private final Set<GoalPosition> triedLeadPositions = new HashSet<>();
	private GoalPosition navigationGoal, lead;
	private boolean navigationOwned, initialized, emitted;
	private int activeTicks, phaseTicks, atLeadTicks, pathTicks, navigationRadius;
	private TaskTerminalEvent terminal;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public LureEntitiesTaskExecutor(NavigationFacade navigation) { this(navigation, new MinecraftLureEntitiesEnvironment()); }
	LureEntitiesTaskExecutor(NavigationFacade navigation, Environment environment) {
		this.navigation = java.util.Objects.requireNonNull(navigation);
		this.environment = java.util.Objects.requireNonNull(environment);
	}

	@Override public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.LURE_ENTITIES) {
			release();
			acquired.clear();
			phase = Phase.ACQUIRE;
			phaseTicks = 0;
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest next = activeTask.get();
		if (request == null || !request.taskId().equals(next.taskId())) {
			release();
			request = next;
			args = ((WorldTaskRequest.LureEntities) request.task()).args();
			acquired.clear();
			triedLeadPositions.clear();
			lead = null;
			initialized = emitted = false;
			terminal = null;
			activeTicks = phaseTicks = atLeadTicks = 0;
			phase = Phase.ACQUIRE;
		}
		if (terminal != null) return finishRelease();
		if (session == null || !session.companionActuationAllowed()) {
			release();
			acquired.clear();
			phase = Phase.ACQUIRE;
			phaseTicks = 0;
			setSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, "session_gate", List.of());
			return Optional.empty();
		}
		if (!initialized) {
			String error = environment.initialize(args);
			if (error != null) return finish(false, error);
			initialized = true;
		}
		if (!navigation.isLoaded()) return finish(false, "navigation_unavailable");
		List<Follower> followers = environment.followers();
		if (followers.size() != args.uuids().size()) return finish(false, "follower_missing_or_dead");
		if (followers.stream().allMatch(Follower::inside)) return finish(true, "animals_arrived count=" + followers.size());
		if (++activeTicks > 6000) return finish(false, "lure_timeout");
		phaseTicks++;
		Vec3 player = environment.position();
		String error = environment.holdItem();
		if (error != null) return finish(false, error);
		environment.beginTravel();
		// Vanilla temptation ignores visibility; a nearby animal may already be following around a corner.
		for (Follower follower : followers) if (distance(player, follower) <= 3.1) acquired.add(follower.uuid());

		if (phase == Phase.WAIT) {
			stopNavigation();
			boolean caughtUp = followers.stream().filter(f -> acquired.contains(f.uuid())).allMatch(f -> distance(player, f) <= 3.1);
			if (caughtUp) enter(acquired.size() == followers.size() ? Phase.LEAD : Phase.ACQUIRE);
			else if (phaseTicks >= 80) {
				// Approach lagging animals again, without feeding them.
				followers.stream().filter(f -> distance(player, f) > 3.1).forEach(f -> acquired.remove(f.uuid()));
				enter(Phase.ACQUIRE);
			}
		}
		else if (followers.stream().anyMatch(f -> acquired.contains(f.uuid()) && distance(player, f) > 5.5)) {
			stopNavigation();
			enter(Phase.WAIT);
		}
		else if (phase == Phase.ACQUIRE) {
			if (acquired.size() == followers.size()) { stopNavigation(); enter(Phase.LEAD); }
			else {
				if (phaseTicks > 1200) return finish(false, "follower_not_approaching");
				Follower target = followers.stream().filter(f -> !acquired.contains(f.uuid()))
					.min(Comparator.comparingDouble(f -> distance(player, f))).orElseThrow();
				GoalPosition goal = feet(target.position());
				int radius = target.visible() ? 2 : 0;
				if (navigationOwned && pathTicks >= 10
					&& (navigationRadius != radius || squaredDistance(navigationGoal, goal) >= 4)) stopNavigation();
				if (!navigate(goal, radius)) return finish(false, "follower_approach_failed uuid=" + target.uuid());
			}
		}
		else if (phase == Phase.LEAD) {
			if (phaseTicks > 1200) return finish(false, "lure_navigation_timeout");
			if (lead == null) {
				lead = environment.leadPositions(followers).stream().filter(p -> !triedLeadPositions.contains(p)).findFirst().orElse(null);
				if (lead == null || triedLeadPositions.size() >= 8) return finish(false, "destination_too_small_or_unreachable");
			}
			if (navigation.navigationGoalReached(lead)) {
				stopNavigation();
				if (++atLeadTicks >= 60) {
					triedLeadPositions.add(lead);
					lead = null;
					atLeadTicks = 0;
				}
			}
			else if (!navigate(lead, 0)) {
				stopNavigation();
				triedLeadPositions.add(lead);
				lead = null;
			}
		}
		setSnapshot(TaskExecutionState.RUNNING, "luring", followers);
		return Optional.empty();
	}

	private boolean navigate(GoalPosition goal, int nearRadius) {
		if (!navigationOwned) {
			if (!BaritoneReleaseBarrier.released(navigation)) return true;
			navigation.pollPathEvent();
			if (nearRadius > 0) navigation.startNavigateNear(goal, nearRadius); else navigation.startNavigate(goal);
			navigationOwned = true;
			navigationGoal = goal;
			navigationRadius = nearRadius;
			pathTicks = 0;
		}
		pathTicks++;
		String event = navigation.pollPathEvent().orElse("");
		return !event.contains("CALC_FAILED") && !event.equals("CANCELED");
	}

	private void enter(Phase next) { phase = next; phaseTicks = 0; }
	private void stopNavigation() {
		if (navigationOwned) navigation.cancel();
		navigationOwned = false;
		navigationGoal = null;
	}
	private void release() { stopNavigation(); environment.release(); }
	private Optional<TaskTerminalEvent> finish(boolean success, String reason) {
		release();
		terminal = new TaskTerminalEvent(request.taskId(), null, success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED,
			reason, success ? TaskTerminationCause.GOAL_REACHED : null, success ? null : TaskFailureCode.MISSING_FACT);
		return finishRelease();
	}
	private Optional<TaskTerminalEvent> finishRelease() {
		release();
		if (!BaritoneReleaseBarrier.releaseAndDrain(navigation)) return Optional.empty();
		setSnapshot(terminal.terminalState(), terminal.message(), List.of());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}
	private void setSnapshot(TaskExecutionState state, String reason, List<Follower> followers) {
		snapshot = new TaskExecutionSnapshot(state, request.taskId(), null, "LureEntities",
			reason + " phase=" + phase + " acquired=" + acquired.size() + "/" + args.uuids().size()
				+ " lead=" + lead + " navigationGoal=" + navigationGoal + " phaseTicks=" + phaseTicks
				+ " tried=" + triedLeadPositions + " activeTicks=" + activeTicks + " followers=" + followers, null, null);
	}
	private static double distance(Vec3 player, Follower follower) { return player.distanceTo(follower.position()); }
	private static GoalPosition feet(Vec3 p) { return new GoalPosition((int)Math.floor(p.x), (int)Math.floor(p.y + 0.125), (int)Math.floor(p.z), true); }
	private static double squaredDistance(GoalPosition a, GoalPosition b) {
		double x = (double)a.x()-b.x(), y = (double)a.y()-b.y(), z = (double)a.z()-b.z();
		return x*x+y*y+z*z;
	}
	@Override public TaskExecutionSnapshot snapshot() { return snapshot; }
	@Override public void onWorldLeave() { release(); request = null; snapshot = TaskExecutionSnapshot.idle(); }
	@Override public void shutdown() { onWorldLeave(); }
	enum Phase { ACQUIRE, LEAD, WAIT }
	record Follower(String uuid, Vec3 position, boolean inside, boolean visible) {}
	interface Environment {
		String initialize(LureEntitiesStepArgs args);
		Vec3 position();
		List<Follower> followers();
		String holdItem();
		List<GoalPosition> leadPositions(List<Follower> followers);
		void beginTravel();
		void release();
	}
}
