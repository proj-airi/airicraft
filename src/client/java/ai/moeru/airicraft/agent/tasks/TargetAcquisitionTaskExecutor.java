package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.AcquisitionConstraints;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** System 1 owns acquisition. Baritone receives only an observed work position. */
public final class TargetAcquisitionTaskExecutor implements WorldTaskExecutor {
	private final BaritoneFacade navigation;
	private final Environment environment;
	private final MiningOpportunityPolicyState opportunityPolicy;
	private final MiningOpportunityJournal opportunityJournal;
	private WorldTaskRequest request;
	private AcquisitionConstraints constraints;
	private Candidate target;
	private GoalMineSpec opportunitySpec;
	private GoalMineSpec opportunityPickupSpec;
	private GoalPosition opportunitySource;
	private boolean opportunityAfterRequestedQuota;
	private int opportunityPickupCount;
	private int extraBlocks;
	private int extraTicks;
	private final Map<String, Integer> extraMinedByBlock = new LinkedHashMap<>();
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
		this(navigation, cameraController, new MiningOpportunityPolicyState());
	}

	public TargetAcquisitionTaskExecutor(BaritoneFacade navigation, ai.moeru.airicraft.agent.control.CameraController cameraController,
		MiningOpportunityPolicyState opportunityPolicy) {
		this(navigation, cameraController, opportunityPolicy, new MiningOpportunityJournal());
	}

	public TargetAcquisitionTaskExecutor(BaritoneFacade navigation, ai.moeru.airicraft.agent.control.CameraController cameraController,
		MiningOpportunityPolicyState opportunityPolicy, MiningOpportunityJournal opportunityJournal) {
		this(navigation, new MinecraftAcquisitionEnvironment(cameraController), opportunityPolicy, opportunityJournal);
	}

	TargetAcquisitionTaskExecutor(BaritoneFacade navigation, Environment environment) {
		this(navigation, environment, new MiningOpportunityPolicyState());
	}

	TargetAcquisitionTaskExecutor(BaritoneFacade navigation, Environment environment, MiningOpportunityPolicyState opportunityPolicy) {
		this(navigation, environment, opportunityPolicy, new MiningOpportunityJournal());
	}

	TargetAcquisitionTaskExecutor(BaritoneFacade navigation, Environment environment, MiningOpportunityPolicyState opportunityPolicy,
		MiningOpportunityJournal opportunityJournal) {
		this.navigation = Objects.requireNonNull(navigation);
		this.environment = Objects.requireNonNull(environment);
		this.opportunityPolicy = Objects.requireNonNull(opportunityPolicy);
		this.opportunityJournal = Objects.requireNonNull(opportunityJournal);
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
			endInterruptedOpportunity("task_replaced");
			release();
			request = next;
			constraints = next.goal().mineSpec().constraints().anchoredAt(environment.position());
			rejected.clear();
			observedSources.clear();
			lastRejection = "none";
			activeTicks = 0;
			extraBlocks = 0;
			extraTicks = 0;
			extraMinedByBlock.clear();
			opportunitySpec = null;
			opportunityPickupSpec = null;
			opportunitySource = null;
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
		boolean goalMet = count >= spec.quantity() || ((WorldTaskRequest.Mine) request.task()).mineGoalSatisfied();
		if (!opportunityPolicy.policy().enabled() && (opportunitySpec != null || opportunityPickupSpec != null)) {
			endInterruptedOpportunity("policy_disabled");
			release();
			opportunitySpec = null;
			opportunityPickupSpec = null;
			enter(Phase.SELECT);
		}
		if (opportunityPickupSpec != null && environment.inventoryCount(opportunityPickupSpec) > opportunityPickupCount) {
			observeOpportunity(MiningOpportunityJournal.Stage.MATCHING_ITEM_GAIN_OBSERVED,
				environment.inventoryCount(opportunityPickupSpec) - opportunityPickupCount, null);
			release();
			opportunityPickupSpec = null;
			enter(Phase.SELECT);
		}
		if (goalMet && opportunitySpec == null && opportunityPickupSpec == null && phase != Phase.SELECT
			&& (count >= spec.quantity() || target == null || target.kind() == Kind.BLOCK)) {
			release();
			enter(Phase.SELECT);
		}
		if ((opportunitySpec != null || opportunityPickupSpec != null) && ++extraTicks > opportunityPolicy.policy().maxExtraTicks()) {
			endInterruptedOpportunity("opportunity_tick_limit");
			release();
			opportunitySpec = null;
			opportunityPickupSpec = null;
			enter(Phase.SELECT);
		}
		if (!goalMet && !environment.requiredToolAvailable(spec))
			return finish(false, "missing_required_harvest_tool itemIds=" + spec.requiredToolItemIds(), TaskFailureCode.MISSING_ITEM);
		if (activeTicks > 2400) return goalMet ? finish(true, "opportunity_budget_exhausted")
			: finish(false, "acquisition_budget_exhausted itemCount=" + count);
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
			if (opportunityPickupSpec != null) {
				List<Candidate> drops = environment.dropCandidates(opportunityPickupSpec, constraints, rejected).stream()
					.filter(candidate -> opportunitySource != null
						&& distanceSquared(candidate.position(), opportunitySource) <= 16
						&& distanceSquared(candidate.workPosition(), environment.position()) <= 16).toList();
				if (!drops.isEmpty()) {
					target = drops.getFirst();
					enter(Phase.APPROACH);
					progressPosition = environment.position();
					progressTicks = 0;
				} else if (phaseTicks < 20) {
					setSnapshot(TaskExecutionState.RUNNING, "waiting_for_opportunity_drop");
					return Optional.empty();
				} else {
					observeOpportunity(MiningOpportunityJournal.Stage.PICKUP_UNCONFIRMED, 0, "drop_not_observed");
					opportunityPickupSpec = null;
				}
			}
			if (phase == Phase.APPROACH) {
				setSnapshot(TaskExecutionState.RUNNING, "opportunity_drop target=" + target.position());
				return Optional.empty();
			}
			if (count < spec.quantity()) {
				List<Candidate> requestedDrops = environment.dropCandidates(spec, constraints, rejected);
				if (!requestedDrops.isEmpty()) {
					target = requestedDrops.getFirst();
					targetInventoryCount = count;
					enter(Phase.APPROACH);
					progressPosition = environment.position();
					progressTicks = 0;
					setSnapshot(TaskExecutionState.RUNNING, "requested_drop target=" + target.position());
					return Optional.empty();
				}
			}
			if (startOpportunity(spec, goalMet)) {
				setSnapshot(TaskExecutionState.RUNNING, "opportunity target=" + target.position() + " extraBlocks=" + extraBlocks);
				return Optional.empty();
			}
			if (goalMet) return finish(true, "requested_blocks_broken extraBlocks=" + extraBlocks);
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
			if (opportunitySpec != null) observeOpportunity(MiningOpportunityJournal.Stage.ABANDONED, 0, "target_disappeared");
			release();
			if (opportunitySpec != null) {
				opportunitySpec = null;
				enter(Phase.SELECT);
			} else {
				// A collected/despawned item cannot produce another drop to settle.
				enter(target.kind() == Kind.DROP ? Phase.SELECT : Phase.SETTLE);
			}
		}
		else if (target != null && target.kind() == Kind.DROP && !environment.canCollectDrop(target)) {
			if (opportunityPickupSpec != null) {
				observeOpportunity(MiningOpportunityJournal.Stage.PICKUP_UNCONFIRMED, 0, "drop_uncollectible");
				release();
				opportunityPickupSpec = null;
				enter(Phase.SELECT);
				return Optional.empty();
			}
			return finish(false, "inventory_full cannot_pick_up target=" + target.id()
				+ " itemCount=" + count + "; free storage space before retrying", TaskFailureCode.BUSY);
		}
		else if (phase == Phase.APPROACH) {
			if (target.kind() == Kind.BLOCK && opportunityPickupSpec == null && startOpportunity(spec, false)) {
				setSnapshot(TaskExecutionState.RUNNING, "opportunity target=" + target.position() + " extraBlocks=" + extraBlocks);
				return Optional.empty();
			}
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
				BreakResult result = environment.breakTarget(target, opportunitySpec == null ? spec : opportunitySpec);
				if (result instanceof ToolFailure failure) {
					if (opportunitySpec != null) reject("opportunity_" + failure.reason());
					else return finish(false, failure.reason(), TaskFailureCode.MISSING_ITEM);
				}
				if (result == BreakStatus.FAILED) reject("break_unavailable");
				else if (result == BreakStatus.BROKEN) {
					environment.cancelBreaking();
					if (opportunitySpec != null) {
						observeOpportunity(MiningOpportunityJournal.Stage.BROKEN, 0, null);
						extraBlocks++;
						extraMinedByBlock.merge(target.id(), 1, Integer::sum);
						opportunityPickupSpec = opportunitySpec;
						opportunitySpec = null;
						enter(Phase.SELECT);
					} else enter(Phase.SETTLE);
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
		if (opportunitySpec != null) observeOpportunity(MiningOpportunityJournal.Stage.ABANDONED, 0, reason);
		else if (opportunityPickupSpec != null) observeOpportunity(MiningOpportunityJournal.Stage.PICKUP_UNCONFIRMED, 0, reason);
		rejected.add(target.key());
		lastRejection = reason;
		release();
		opportunitySpec = null;
		if (target.kind() == Kind.DROP) opportunityPickupSpec = null;
		enter(Phase.SELECT);
		setSnapshot(TaskExecutionState.RUNNING, reason + " target=" + target.position());
	}

	private boolean startOpportunity(GoalMineSpec spec, boolean goalMet) {
		MiningOpportunityPolicy policy = opportunityPolicy.policy();
		if (!policy.enabled() || policy.maxExtraBlocks() == 0
			|| extraBlocks >= policy.maxExtraBlocks() || extraTicks >= policy.maxExtraTicks()
			|| spec.blockIds().stream().noneMatch(TargetAcquisitionTaskExecutor::isOreId)) return false;
		for (Candidate candidate : environment.opportunityCandidates(spec, constraints, goalMet, rejected)) {
			if (candidate.kind() != Kind.BLOCK || (goalMet && !spec.blockIds().contains(candidate.id()))
				|| (!goalMet && spec.blockIds().contains(candidate.id()))) continue;
			release();
			target = candidate;
			opportunitySource = candidate.position();
			opportunityAfterRequestedQuota = goalMet;
			opportunitySpec = new GoalMineSpec(List.of(candidate.id()), 1,
				opportunityPolicy.matchingItems(candidate.id()), List.of());
			opportunityPickupCount = environment.inventoryCount(opportunitySpec);
			observeOpportunity(MiningOpportunityJournal.Stage.STARTED, 0, null);
			enter(Phase.BREAK);
			return true;
		}
		return false;
	}

	private void endInterruptedOpportunity(String reason) {
		if (opportunitySpec != null) observeOpportunity(MiningOpportunityJournal.Stage.ABANDONED, 0, reason);
		else if (opportunityPickupSpec != null) observeOpportunity(MiningOpportunityJournal.Stage.PICKUP_UNCONFIRMED, 0, reason);
	}

	private void observeOpportunity(MiningOpportunityJournal.Stage stage, int observedItemGain, String reason) {
		GoalMineSpec active = opportunitySpec != null ? opportunitySpec : opportunityPickupSpec;
		if (active == null || request == null || opportunitySource == null) return;
		opportunityJournal.record(new MiningOpportunityJournal.Notice(request.taskId(), request.sourceJobId(),
			request.goal().mineSpec().blockIds(), active.blockIds().getFirst(), active.matchingItemIds(), opportunitySource,
			opportunityAfterRequestedQuota, stage, observedItemGain, reason));
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason) {
		return finish(success, reason, TaskFailureCode.MISSING_FACT);
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason, TaskFailureCode failureCode) {
		endInterruptedOpportunity("task_finished");
		if (!extraMinedByBlock.isEmpty()) reason += " opportunityBreaks=" + extraMinedByBlock;
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
	static boolean isOreId(String blockId) {
		return blockId != null && (blockId.endsWith("_ore") || blockId.equals("minecraft:ancient_debris"));
	}
	@Override public TaskExecutionSnapshot snapshot() { return snapshot; }
	@Override public void onWorldLeave() {
		release();
		request = null;
		opportunitySpec = null;
		opportunityPickupSpec = null;
		opportunitySource = null;
		opportunityJournal.clear();
		observedSources.clear();
		snapshot = TaskExecutionSnapshot.idle();
	}
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
		default List<Candidate> opportunityCandidates(GoalMineSpec spec, AcquisitionConstraints constraints,
			boolean goalMet, Set<String> rejected) { return List.of(); }
		List<Candidate> dropCandidates(GoalMineSpec spec, AcquisitionConstraints constraints, Set<String> rejected);
		List<Candidate> candidates(GoalMineSpec spec, AcquisitionConstraints constraints, Set<String> rejected, Set<GoalPosition> observedSources);
		boolean targetPresent(Candidate target);
		boolean dropsAvailable(GoalMineSpec spec, AcquisitionConstraints constraints);
		boolean canCollectDrop(Candidate target);
		boolean canInteract(Candidate target);
		BreakResult breakTarget(Candidate target, GoalMineSpec spec);
		void cancelBreaking();
	}
}
