package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.AcquisitionConstraints;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** System 1 owns acquisition. Baritone receives only an observed work position. */
public final class TargetAcquisitionTaskExecutor implements WorldTaskExecutor {
	private final BaritoneFacade navigation;
	private final Environment environment;
	private WorldTaskRequest request;
	private AcquisitionConstraints constraints;
	private Candidate target;
	private Phase phase = Phase.SELECT;
	private final Set<String> rejected = new HashSet<>();
	private final Set<GoalPosition> observedSources = new HashSet<>();
	private int activeTicks;
	private int phaseTicks;
	private int progressTicks;
	private int targetInventoryCount;
	private GoalPosition progressPosition;
	private boolean navigationOwned;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private String lastRejection;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public TargetAcquisitionTaskExecutor(BaritoneFacade navigation, ai.moeru.airicraft.agent.control.CameraController cameraController) {
		this(navigation, new MinecraftAcquisitionEnvironment(cameraController));
	}

	TargetAcquisitionTaskExecutor(BaritoneFacade navigation, Environment environment) {
		this.navigation = Objects.requireNonNull(navigation);
		this.environment = Objects.requireNonNull(environment);
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty()) {
			release();
			// A reflex temporarily withdraws the same request. Keep its scope and budgets;
			// a replacement task identity or world leave discards the continuation.
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest next = activeTask.orElseThrow();
		if (!session.companionActuationAllowed()) {
			release();
			snapshot = new TaskExecutionSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, next.taskId(), next.goal(),
				"TargetAcquisition", "session_gate", null, null);
			return Optional.empty();
		}
		if (request == null || !request.taskId().equals(next.taskId())) {
			release();
			request = next;
			constraints = next.goal().mineSpec().constraints().anchoredAt(environment.position());
			rejected.clear();
			observedSources.clear();
			lastRejection = "none";
			activeTicks = 0;
			target = null;
			terminal = null;
			emitted = false;
			enter(Phase.SELECT);
		}
		request = next;
		if (terminal != null) return finishRelease();
		activeTicks++;
		phaseTicks++;
		GoalMineSpec spec = request.goal().mineSpec();
		int count = environment.inventoryCount(spec);
		if (count >= spec.quantity()) return finish(true, "inventory_target_reached itemCount=" + count);
		if (!environment.requiredToolAvailable(spec))
			return finish(false, "missing_required_harvest_tool itemIds=" + spec.requiredToolItemIds(), TaskFailureCode.MISSING_ITEM);
		if (activeTicks > 2400) return finish(false, "acquisition_budget_exhausted itemCount=" + count);
		if (!BaritoneReleaseBarrier.released(navigation) && !navigationOwned) {
			if (phaseTicks > 100) return finish(false, "acquisition_release_timeout");
			setSnapshot(TaskExecutionState.RUNNING, "waiting_for_navigation_release");
			return Optional.empty();
		}
		if (phase == Phase.SETTLE) {
			// Give delayed server drops a bounded grace period, but never sleep
			// through inventory confirmation or a drop that is already observable.
			if (count > targetInventoryCount || environment.dropsAvailable(spec, constraints) || phaseTicks >= 20)
				enter(Phase.SELECT);
		}
		if (phase == Phase.SELECT) {
			// Pickup movement can hide a vein we already saw. Keep that knowledge for
			// this attempt, while the environment rechecks blocks and work positions.
			if (constraints.visibleOnly()) observedSources.addAll(environment.observeSources(spec, constraints));
			List<Candidate> candidates = environment.candidates(spec, constraints, rejected, observedSources);
			if (candidates.isEmpty()) {
				boolean brokenEnough = ((WorldTaskRequest.Mine) request.task()).mineGoalSatisfied();
				return finish(brokenEnough, (brokenEnough ? "requested_blocks_broken" : "no_eligible_resource_in_search_region")
					+ " evidence=" + new com.google.gson.Gson().toJson(java.util.Map.of("failedPredicate", "eligible_loaded_resource", "scope", "searched_region", "actorPosition", environment.position(), "bounds", constraints)) + " itemCount=" + count + " rejectedTargets=" + rejected.size() + " lastRejection=" + lastRejection);
			}
			target = candidates.getFirst();
			targetInventoryCount = count;
			if (((WorldTaskRequest.Mine) request.task()).mineGoalSatisfied() && target.kind() == Kind.BLOCK)
				return finish(true, "requested_blocks_broken");
			enter(Phase.APPROACH);
			progressPosition = environment.position();
			progressTicks = 0;
		}
		else if (phase != Phase.SETTLE && !environment.targetPresent(target)) {
			release();
			// A collected/despawned item cannot produce another drop to settle.
			enter(target.kind() == Kind.DROP ? Phase.SELECT : Phase.SETTLE);
		}
		else if (target != null && target.kind() == Kind.DROP && !environment.canCollectDrop(target)) {
			return finish(false, "inventory_full cannot_pick_up target=" + target.id()
				+ " itemCount=" + count + "; free storage space before retrying", TaskFailureCode.BUSY);
		}
		else if (phase == Phase.APPROACH) {
			// The shared navigation watchdog measures break progress. Do not let
			// this position-only retry timer interrupt ongoing route excavation.
			if (navigation.navigationProgress().map(progress -> progress.breakingProgress() > 0).orElse(false)) progressTicks = 0;
			boolean reached = environment.canInteract(target);
			if (reached) {
				release();
				enter(target.kind() == Kind.BLOCK ? Phase.BREAK : Phase.PICKUP);
			}
			// Excavating a route can exceed twelve seconds while still advancing.
			// Bound it by actual stalls and the whole attempt's active-tick budget.
			else if (progressTicks > 80) reject("approach_stalled");
			else {
				GoalPosition position = environment.position();
				// Valid routes may initially move away from the target to leave a room or go around terrain.
				if (progressPosition == null || distanceSquared(position, progressPosition) >= 1) {
					progressPosition = position;
					progressTicks = 0;
				}
				else progressTicks++;
				if (!navigationOwned) {
					navigation.pollPathEvent();
					navigation.startNavigate(target.workPosition());
					navigationOwned = true;
				}
				String event = navigation.pollPathEvent().orElse("");
				if (event.contains("FAIL") || event.equals("CANCELED") || event.equals("CANCELLED")) reject("approach_" + event);
			}
		}
		else if (phase == Phase.BREAK) {
			if (phaseTicks > 240) reject("break_timeout");
			else {
				BreakResult result = environment.breakTarget(target, spec);
				if (result instanceof ToolFailure failure)
					return finish(false, failure.reason(), TaskFailureCode.MISSING_ITEM);
				if (result == BreakStatus.FAILED) reject("break_unavailable");
				else if (result == BreakStatus.BROKEN) {
					environment.cancelBreaking();
					enter(Phase.SETTLE);
				}
			}
		}
		else if (phase == Phase.PICKUP && phaseTicks > 40) reject("pickup_not_collected");
		setSnapshot(TaskExecutionState.RUNNING, "acquisition phase=" + phase + " target="
			+ (target == null ? "none" : target.id() + "@" + target.position())
			+ " workPosition=" + (target == null ? "none" : target.workPosition())
			+ " itemCount=" + count + " targetCount=" + spec.quantity() + " rejected=" + rejected.size() + " lastRejection=" + lastRejection);
		return Optional.empty();
	}

	private void reject(String reason) {
		rejected.add(target.key());
		lastRejection = reason;
		release();
		enter(Phase.SELECT);
		setSnapshot(TaskExecutionState.RUNNING, reason + " target=" + target.position());
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason) {
		return finish(success, reason, TaskFailureCode.MISSING_FACT);
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason, TaskFailureCode failureCode) {
		terminal = new TaskTerminalEvent(request.taskId(), request.goal(),
			success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED,
			reason, success ? TaskTerminationCause.GOAL_REACHED : null,
			success ? TaskFailureCode.NONE : failureCode);
		release();
		enter(Phase.RELEASE);
		return finishRelease();
	}

	private Optional<TaskTerminalEvent> finishRelease() {
		if (!BaritoneReleaseBarrier.releaseAndDrain(navigation)) {
			setSnapshot(TaskExecutionState.RUNNING, "releasing_acquisition_navigation");
			return Optional.empty();
		}
		setSnapshot(terminal.terminalState(), terminal.message());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}

	private void enter(Phase next) { phase = next; phaseTicks = 0; }
	private void release() {
		if (navigationOwned) navigation.cancel();
		navigationOwned = false;
		environment.cancelBreaking();
	}
	private void setSnapshot(TaskExecutionState state, String detail) {
		snapshot = new TaskExecutionSnapshot(state, request.taskId(), request.goal(), "TargetAcquisition",
			detail + " observedSources=" + observedSources.size(), null,
			terminal == null ? null : terminal.terminationCause());
	}
	static double distanceSquared(GoalPosition a, GoalPosition b) {
		double x = (double) a.x() - b.x(), y = (double) a.y() - b.y(), z = (double) a.z() - b.z();
		return x*x + y*y + z*z;
	}
	@Override public TaskExecutionSnapshot snapshot() { return snapshot; }
	@Override public void onWorldLeave() { release(); request = null; observedSources.clear(); snapshot = TaskExecutionSnapshot.idle(); }
	@Override public void shutdown() { onWorldLeave(); }

	enum Phase { SELECT, APPROACH, BREAK, PICKUP, SETTLE, RELEASE }
	enum Kind { DROP, BLOCK }
	sealed interface BreakResult {}
	enum BreakStatus implements BreakResult { BREAKING, BROKEN, FAILED }
	record ToolFailure(String reason) implements BreakResult {}
	record Candidate(Kind kind, String id, GoalPosition position, GoalPosition workPosition) {
		String key() { return kind + ":" + id + ":" + position + ":" + workPosition; }
	}
	interface Environment {
		GoalPosition position();
		int inventoryCount(GoalMineSpec spec);
		boolean requiredToolAvailable(GoalMineSpec spec);
		boolean inScope(GoalPosition position, AcquisitionConstraints constraints, boolean standing);
		Set<GoalPosition> observeSources(GoalMineSpec spec, AcquisitionConstraints constraints);
		List<Candidate> candidates(GoalMineSpec spec, AcquisitionConstraints constraints, Set<String> rejected, Set<GoalPosition> observedSources);
		boolean targetPresent(Candidate target);
		boolean dropsAvailable(GoalMineSpec spec, AcquisitionConstraints constraints);
		boolean canCollectDrop(Candidate target);
		boolean canInteract(Candidate target);
		BreakResult breakTarget(Candidate target, GoalMineSpec spec);
		void cancelBreaking();
	}
}
