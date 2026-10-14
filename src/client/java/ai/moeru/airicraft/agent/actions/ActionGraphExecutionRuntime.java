package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.work.ReadableIds;
import ai.moeru.actionplan.MethodKey;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.SmeltingOption;
import ai.moeru.airicraft.agent.tasks.SmeltingRecipeKnowledge;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskFailureCode;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

public final class ActionGraphExecutionRuntime {
	private static final int MAX_STEP_RETRIES = 2;
	private static final int MAX_REPLANS = 8;
	private static final int MAX_TICK_TRANSITIONS = 32;

	private final ActionGraphPrimitiveDispatcher primitiveDispatcher;
	private final Executor resolutionExecutor;
	private final AutoCommittingRoutePlanner routePlanner;

	private ActionGraphExecutionState state = ActionGraphExecutionState.IDLE;
	private String executionId = "";
	private ActionGoal goal;
	private ActionRoute route = ActionRoute.empty();
	private int cursor;
	private ActionPlanStep currentStep;
	private int stepAttempt;
	private int replanCount;
	private String activeTaskId = "";
	private String failureCode = "";
	private String message = "";
	private long startedTick = -1L;
	private ActionResolverContext lastContext;
	private final ActionFactStore facts = new ActionFactStore();
	private BlockAcquisitionIndex blockAcquisitions = BlockAcquisitionIndex.empty();
	private NearbyBlockAvailability nearbyBlockAvailability = NearbyBlockAvailability.unknown();
	private final List<ActionTraceEvent> trace = new ArrayList<>();
	private final List<Map<String, Object>> recoveryHistory = new ArrayList<>();
	private final Set<MethodKey> blockedAlternatives = new LinkedHashSet<>();
	private final Map<String, PendingWatch> watches = new LinkedHashMap<>();
	private final Map<ActionFactIdentity, FactTraceFingerprint> tracedFactObservations = new LinkedHashMap<>();
	private final Set<String> observedInventoryItemIds = new LinkedHashSet<>();
	private Map<String, Object> dispatchPayload = Map.of();
	private Map<String, Object> taskPayload = Map.of();
	private Map<String, Object> taskExecutionPayload = Map.of();
	private int observedInventoryFactCount;
	private int assumedInventoryFactCount;
	private long observeNotBeforeTick = -1L;
	private boolean refreshRouteAfterObservation;
	private String boundSmeltingProcessId = "";
	private Future<ActionResolveResult> resolutionTask;

	public ActionGraphExecutionRuntime(ActionGraphPrimitiveDispatcher primitiveDispatcher) {
		this(primitiveDispatcher, Runnable::run);
	}

	ActionGraphExecutionRuntime(ActionGraphPrimitiveDispatcher primitiveDispatcher, Executor resolutionExecutor) {
		this(primitiveDispatcher, resolutionExecutor, new AutoCommittingRoutePlanner());
	}

	ActionGraphExecutionRuntime(
		ActionGraphPrimitiveDispatcher primitiveDispatcher,
		Executor resolutionExecutor,
		AutoCommittingRoutePlanner routePlanner
	) {
		this.primitiveDispatcher = Objects.requireNonNull(primitiveDispatcher, "primitiveDispatcher");
		this.resolutionExecutor = Objects.requireNonNull(resolutionExecutor, "resolutionExecutor");
		this.routePlanner = Objects.requireNonNull(routePlanner, "routePlanner");
	}

	public synchronized ActionGraphExecutionSnapshot submit(
		ActionGoal goal,
		Map<String, Integer> assumedInventory,
		ActionResolverContext context,
		long tick
	) {
		return submit(goal, assumedInventory, context, tick, ReadableIds.next("graph"));
	}

	public synchronized ActionGraphExecutionSnapshot submit(
		ActionGoal goal,
		Map<String, Integer> assumedInventory,
		ActionResolverContext context,
		long tick,
		String executionId
	) {
		cancelResolution();
		this.executionId = executionId == null || executionId.isBlank()
			? ReadableIds.next("graph")
			: executionId;
		this.goal = Objects.requireNonNull(goal, "goal");
		this.route = ActionRoute.empty();
		this.cursor = 0;
		this.currentStep = null;
		this.stepAttempt = 0;
		this.replanCount = 0;
		this.activeTaskId = "";
		this.failureCode = "";
		this.message = "";
		this.startedTick = tick;
		this.lastContext = Objects.requireNonNull(context, "context");
		this.state = ActionGraphExecutionState.RESOLVING;
		this.facts.clear();
		this.blockAcquisitions = BlockAcquisitionIndex.empty();
		this.nearbyBlockAvailability = NearbyBlockAvailability.unknown();
		this.trace.clear();
		this.recoveryHistory.clear();
		this.blockedAlternatives.clear();
		this.watches.clear();
		this.tracedFactObservations.clear();
		this.observedInventoryItemIds.clear();
		this.dispatchPayload = Map.of();
		this.taskPayload = Map.of();
		this.taskExecutionPayload = Map.of();
		this.observedInventoryFactCount = 0;
		this.assumedInventoryFactCount = 0;
		this.observeNotBeforeTick = -1L;
		this.refreshRouteAfterObservation = false;
		this.boundSmeltingProcessId = "";
		addInventoryFacts(assumedInventory, ActionFactProvenance.EXECUTOR_REPORTED, context, false);
		trace("execution_started", "", "", "", Map.of("goal", goal.normalizedKey(), "executionId", executionId));
		return snapshot();
	}

	public synchronized ActionGraphExecutionSnapshot tick(ActionGraphExecutionInput input) {
		return tickForeground(input);
	}

	public synchronized ActionGraphExecutionSnapshot tickForeground(ActionGraphExecutionInput input) {
		Objects.requireNonNull(input, "input");
		lastContext = input.context();
		ingestObservedFacts(input);
		if (state == ActionGraphExecutionState.IDLE
			|| state == ActionGraphExecutionState.SUCCEEDED
			|| state == ActionGraphExecutionState.FAILED
			|| state == ActionGraphExecutionState.CANCELLED) {
			return snapshot();
		}
		if (!input.worldLoaded()) {
			block("world_not_loaded", "World is not loaded");
			return snapshot();
		}
		if (state == ActionGraphExecutionState.BLOCKED && input.actuationAllowed()) {
			state = !activeTaskId.isBlank()
				? ActionGraphExecutionState.WAITING_PRIMITIVE
				: currentStep == null ? ActionGraphExecutionState.READY : ActionGraphExecutionState.DISPATCHING;
			trace("recovery_selected", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of("decision", "resume_after_block"));
		}

		TaskTerminalEvent terminalEvent = input.terminalTaskEvent();
		if (terminalEvent != null && matchesActiveTask(terminalEvent)) {
			handleTerminalEvent(terminalEvent);
		}

		for (int transitions = 0; transitions < MAX_TICK_TRANSITIONS; transitions++) {
			if (terminal()) {
				return snapshot();
			}
			if (state == ActionGraphExecutionState.OBSERVING
				&& observeNotBeforeTick > input.context().currentTick()
				&& !goalSatisfied(input.context())) {
				return snapshot();
			}
			switch (state) {
				case RESOLVING, REPLANNING -> {
					if (!resolveRoute(input.context())) {
						return snapshot();
					}
				}
				case READY, OBSERVING -> {
					advance(input);
					if (state == ActionGraphExecutionState.WATCHING) {
						return snapshot();
					}
				}
				case DISPATCHING -> dispatchCurrentStep(input);
				case WATCHING -> {
					pollWatch(input);
					if (state == ActionGraphExecutionState.WATCHING) {
						return snapshot();
					}
				}
				case WAITING_PRIMITIVE -> {
					if (!input.actuationAllowed()) {
						block("session_gate", "Actuation is currently blocked by session gate");
					}
					return snapshot();
				}
				case BLOCKED -> {
					return snapshot();
				}
				case IDLE, SUCCEEDED, FAILED, CANCELLED -> {
					return snapshot();
				}
			}
		}
		fail("budget_exceeded", "Action graph tick transition budget exceeded");
		return snapshot();
	}

	public synchronized ActionGraphExecutionSnapshot tickPassive(ActionGraphExecutionInput input) {
		Objects.requireNonNull(input, "input");
		lastContext = input.context();
		ingestObservedFacts(input);
		if (state == ActionGraphExecutionState.WATCHING) {
			pollWatch(input);
		}
		return snapshot();
	}

	public synchronized ActionGraphExecutionSnapshot cancel(String reason, long tick) {
		if (state == ActionGraphExecutionState.IDLE || terminal()) {
			return snapshot();
		}
		cancelResolution();
		state = ActionGraphExecutionState.CANCELLED;
		message = reason == null || reason.isBlank() ? "cancelled" : reason;
		trace("execution_cancelled", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of("tick", tick, "reason", message));
		return snapshot();
	}

	public synchronized ActionGraphExecutionSnapshot pauseForReflex(long tick) {
		if (state == ActionGraphExecutionState.IDLE || terminal()) {
			return snapshot();
		}
		if (state != ActionGraphExecutionState.BLOCKED || !"reflex".equals(failureCode)) {
			block("reflex", "Actuation is paused by the survival reflex safety hold");
			trace("reflex_pause", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of("tick", tick));
		}
		return snapshot();
	}

	public synchronized void clear() {
		cancelResolution();
		state = ActionGraphExecutionState.IDLE;
		executionId = "";
		goal = null;
		route = ActionRoute.empty();
		cursor = 0;
		currentStep = null;
		stepAttempt = 0;
		replanCount = 0;
		activeTaskId = "";
		failureCode = "";
		message = "";
		startedTick = -1L;
		lastContext = null;
		facts.clear();
		blockAcquisitions = BlockAcquisitionIndex.empty();
		nearbyBlockAvailability = NearbyBlockAvailability.unknown();
		trace.clear();
		recoveryHistory.clear();
		blockedAlternatives.clear();
		watches.clear();
		tracedFactObservations.clear();
		observedInventoryItemIds.clear();
		dispatchPayload = Map.of();
		taskPayload = Map.of();
		taskExecutionPayload = Map.of();
		observedInventoryFactCount = 0;
		assumedInventoryFactCount = 0;
		observeNotBeforeTick = -1L;
		refreshRouteAfterObservation = false;
		boundSmeltingProcessId = "";
	}

	public synchronized ActionGraphExecutionSnapshot snapshot() {
		return new ActionGraphExecutionSnapshot(
			true,
			executionId,
			state,
			goal,
			route,
			cursor,
			currentStep,
			stepAttempt,
			replanCount,
			watches.size(),
			watches.keySet().stream().findFirst().orElse(""),
			activeTaskId,
			failureCode,
			message,
			Map.of(
				"observedInventory", observedInventoryFactCount,
				"assumedInventory", assumedInventoryFactCount,
				"knownFacts", facts.size()
			),
			trace,
			recoveryHistory,
			dispatchPayload,
			taskPayload,
			taskExecutionPayload
		);
	}

	public synchronized boolean active() {
		return state != ActionGraphExecutionState.IDLE && !terminal();
	}

	public synchronized List<ActionGraphWatchSnapshot> pendingWatches() {
		return watches.values().stream()
			.map(watch -> new ActionGraphWatchSnapshot(
				executionId,
				watch.watchId,
				watch.step.stepId(),
				watch.spec,
				watch.consumedEligibleTicks,
				watch.progressEligible,
				watch.pauseReason
			))
			.toList();
	}

	private boolean resolveRoute(ActionResolverContext context) {
		if (resolutionTask == null) {
			ActionGoal resolutionGoal = goal;
			List<ActionFact> factSnapshot = facts.queryAll();
			BlockAcquisitionIndex blockAcquisitionSnapshot = blockAcquisitions;
			NearbyBlockAvailability nearbyBlockAvailabilitySnapshot = nearbyBlockAvailability;
			Set<MethodKey> blockedSnapshot = Set.copyOf(blockedAlternatives);
			FutureTask<ActionResolveResult> task = new FutureTask<>(() -> {
				return routePlanner.adviseAndCommit(new AiricraftPlanningSnapshot(
					factSnapshot,
					blockAcquisitionSnapshot,
					nearbyBlockAvailabilitySnapshot,
					context
				), resolutionGoal, blockedSnapshot);
			});
			resolutionTask = task;
			trace("resolution_scheduled", "", "", "", Map.of(
				"executionId", executionId,
				"factCount", factSnapshot.size(),
				"state", state.name()
			));
			try {
				resolutionExecutor.execute(task);
			}
			catch (RuntimeException exception) {
				resolutionTask = null;
				fail("resolution_dispatch_failed", nonEmpty(exception.getMessage(), exception.getClass().getSimpleName()));
				return true;
			}
		}
		if (!resolutionTask.isDone()) {
			return false;
		}

		ActionResolveResult result;
		try {
			result = resolutionTask.get();
		}
		catch (CancellationException exception) {
			resolutionTask = null;
			fail("resolution_cancelled", "Route resolution was cancelled");
			return true;
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			resolutionTask = null;
			fail("resolution_interrupted", "Interrupted while applying route resolution");
			return true;
		}
		catch (ExecutionException exception) {
			resolutionTask = null;
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			fail("resolution_failed", nonEmpty(cause.getMessage(), cause.getClass().getSimpleName()));
			return true;
		}
		resolutionTask = null;
		trace.addAll(result.trace());
		if (!result.resolved()) {
			fail(result.failureCode(), result.message());
			return true;
		}
		route = result.route();
		cursor = 0;
		currentStep = null;
		stepAttempt = 0;
		activeTaskId = "";
		watches.clear();
		refreshRouteAfterObservation = false;
		state = ActionGraphExecutionState.READY;
		trace("route_started", "", "", "", Map.of(
			"executionId", executionId,
			"cost", route.cost(),
			"stepCount", route.steps().size(),
			"replanCount", replanCount
		));
		return true;
	}

	private void cancelResolution() {
		if (resolutionTask != null) {
			resolutionTask.cancel(true);
			resolutionTask = null;
		}
	}

	private void advance(ActionGraphExecutionInput input) {
		if (goalSatisfied(input.context())) {
			trace("step_skipped", "", "", "", Map.of("reason", "goal_already_satisfied", "cursor", cursor));
			succeed("goal_satisfied");
			return;
		}
		if (state == ActionGraphExecutionState.OBSERVING && observeNotBeforeTick > input.context().currentTick()) {
			return;
		}
		if (state == ActionGraphExecutionState.OBSERVING) {
			observeNotBeforeTick = -1L;
			if (refreshRouteAfterObservation) {
				refreshRouteAfterObservation = false;
				state = ActionGraphExecutionState.REPLANNING;
				trace("route_replanned", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of("reason", "primitive_success_observed"));
				return;
			}
		}
		if (cursor >= route.steps().size()) {
			if (replanCount < MAX_REPLANS) {
				replanCount++;
				state = ActionGraphExecutionState.REPLANNING;
				trace("route_replanned", "", "", "", Map.of("reason", "terminal_goal_not_satisfied", "replanCount", replanCount));
				return;
			}
			fail("goal_not_satisfied", "Route completed but terminal goal was not observed");
			return;
		}
		currentStep = route.steps().get(cursor);
		trace("step_selected", currentStep.actionId(), currentStep.alternativeId(), currentStep.stepId(), Map.of(
			"kind", currentStep.kind().name(),
			"targetId", currentStep.targetId(),
			"cursor", cursor
		));
		if (currentStep.kind() == ActionStepKind.PRIMITIVE) {
			state = ActionGraphExecutionState.DISPATCHING;
			return;
		}
		if (currentStep.kind() == ActionStepKind.WATCH) {
			registerWatch(input);
			return;
		}
		fail("unsupported_step_kind", "Action graph execution cannot run step kind " + currentStep.kind().name());
	}

	private void dispatchCurrentStep(ActionGraphExecutionInput input) {
		if (!input.actuationAllowed()) {
			block("session_gate", "Actuation is currently blocked by session gate");
			return;
		}
		ActionPlanStep dispatchStep = bindPrimitiveStep(currentStep);
		ActionGraphPrimitiveDispatchResult result = primitiveDispatcher.dispatch(dispatchStep);
		if (!result.accepted()) {
			stepAttempt++;
			handleStepFailure(result.failureCode(), result.message(), false);
			return;
		}
		stepAttempt++;
		activeTaskId = result.taskId();
		dispatchPayload = result.payload();
		if ("smelt_item".equals(currentStep.targetId())) {
			String processId = stringPayload(result.payload(), "processId");
			if (!processId.isBlank()) {
				boundSmeltingProcessId = processId;
				trace("smelting_process_bound", currentStep.actionId(), currentStep.alternativeId(), currentStep.stepId(), Map.of(
					"processId", processId
				));
			}
		}
		taskPayload = result.task();
		taskExecutionPayload = result.taskExecution().isEmpty()
			? Map.of("taskId", activeTaskId, "state", TaskExecutionState.RUNNING.name())
			: result.taskExecution();
		state = ActionGraphExecutionState.WAITING_PRIMITIVE;
		trace("primitive_dispatched", currentStep.actionId(), currentStep.alternativeId(), currentStep.stepId(), Map.of(
			"taskId", activeTaskId,
			"attempt", stepAttempt,
			"payload", result.payload()
		));
	}

	private void handleTerminalEvent(TaskTerminalEvent event) {
		taskExecutionPayload = Map.of(
			"taskId", nonEmpty(event.taskId(), activeTaskId),
			"state", event.terminalState().name(),
			"failureCode", event.failureCode().id(),
			"message", nonEmpty(event.message(), "")
		);
		trace("primitive_terminal", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of(
			"taskId", nonEmpty(event.taskId(), activeTaskId),
			"state", event.terminalState().name(),
			"failureCode", event.failureCode().id(),
			"message", nonEmpty(event.message(), "")
		));
		if (event.terminalState() == TaskExecutionState.COMPLETED) {
			trace("step_succeeded", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of("attempt", stepAttempt));
			cursor++;
			activeTaskId = "";
			stepAttempt = 0;
			refreshRouteAfterObservation = refreshAfterSuccessfulStep(currentStep);
			observeNotBeforeTick = lastContext == null ? -1L : lastContext.currentTick() + 20L;
			state = ActionGraphExecutionState.OBSERVING;
			return;
		}
		handleStepFailure(event.failureCode(), event.message(), true);
	}

	private static boolean refreshAfterSuccessfulStep(ActionPlanStep step) {
		if (step == null) {
			return false;
		}
		return switch (step.targetId()) {
			case "craft_item", "collect_resource", "mine_block", "collect_smelted_item" -> true;
			default -> false;
		};
	}

	private void handleStepFailure(TaskFailureCode typedFailureCode, String failureMessage, boolean fromTerminalEvent) {
		TaskFailureCode safeFailureCode = typedFailureCode == null ? TaskFailureCode.UNKNOWN : typedFailureCode;
		String classified = normalizeFailureCode(safeFailureCode);
		LinkedHashMap<String, Object> recovery = new LinkedHashMap<>();
		recovery.put("stepId", stepId(currentStep));
		recovery.put("targetId", currentStep == null ? "" : currentStep.targetId());
		recovery.put("failureCode", classified);
		recovery.put("typedFailureCode", safeFailureCode.id());
		recovery.put("message", nonEmpty(failureMessage, safeFailureCode.id()));
		recovery.put("attempt", stepAttempt);
		recoveryHistory.add(recovery);
		trace("step_failed", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), recovery);

		if (fromTerminalEvent
			&& "transient".equals(classified)
			&& isBusyFailure(safeFailureCode)
			&& stepAttempt <= MAX_STEP_RETRIES) {
			activeTaskId = "";
			observeNotBeforeTick = lastContext == null ? -1L : lastContext.currentTick() + 20L;
			state = ActionGraphExecutionState.OBSERVING;
			trace("recovery_selected", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of(
				"decision", "wait_before_retry",
				"failureCode", classified,
				"retryAfterTick", observeNotBeforeTick
			));
			return;
		}
		if ("transient".equals(classified) && stepAttempt <= MAX_STEP_RETRIES) {
			activeTaskId = "";
			state = ActionGraphExecutionState.DISPATCHING;
			trace("recovery_selected", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of(
				"decision", "retry_step",
				"nextAttempt", stepAttempt + 1
			));
			return;
		}
		if (fromTerminalEvent && "transient".equals(classified) && replanCount < MAX_REPLANS) {
			activeTaskId = "";
			stepAttempt = 0;
			replanCount++;
			state = ActionGraphExecutionState.REPLANNING;
			trace("recovery_selected", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of(
				"decision", "replan_after_transient",
				"replanCount", replanCount
			));
			trace("route_replanned", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of("failureCode", classified));
			return;
		}
		if (canReplan(classified)) {
			if (shouldBlockAlternativeForFailure(classified)) {
				blockCurrentAlternative();
			}
			activeTaskId = "";
			stepAttempt = 0;
			replanCount++;
			state = ActionGraphExecutionState.REPLANNING;
			trace("recovery_selected", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of(
				"decision", "replan",
				"replanCount", replanCount
			));
			trace("route_replanned", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of("failureCode", classified));
			return;
		}
		if (fromTerminalEvent && "transient".equals(classified)) {
			fail("budget_exceeded", "Step retry budget exceeded for " + stepId(currentStep));
			return;
		}
		fail(classified, nonEmpty(failureMessage, safeFailureCode.id()));
	}

	private void registerWatch(ActionGraphExecutionInput input) {
		String watchId = executionId + ":" + currentStep.stepId();
		ActionWatchSpec spec = currentStep.watchSpec() == null
			? legacyWatchSpec(currentStep.args(), input.context())
			: currentStep.watchSpec();
		spec = bindSmeltingWatch(spec, input);
		if (spec.progressKind() == ActionWatchProgressKind.AREA_TICKING && spec.anchor() == null && input.agentPosition() != null) {
			ActionGraphAgentPosition position = input.agentPosition();
			spec = new ActionWatchSpec(
				spec.condition(),
				spec.sourceFactIdentity(),
				spec.timeoutTicks(),
				spec.progressKind(),
				new ActionWatchAnchor(position.worldId(), position.dimension(), position.x(), position.y(), position.z(), true)
			);
			trace("watch_anchor_fallback", currentStep.actionId(), currentStep.alternativeId(), currentStep.stepId(), Map.of(
				"watchId", watchId,
				"reason", "matched_fact_missing_origin",
				"origin", Map.of("x", position.x(), "y", position.y(), "z", position.z())
			));
		}
		PendingWatch watch = new PendingWatch(watchId, currentStep, spec, input.context().currentTick());
		watches.put(currentStep.stepId(), watch);
		activeTaskId = "";
		state = ActionGraphExecutionState.WATCHING;
		trace("watch_registered", currentStep.actionId(), currentStep.alternativeId(), currentStep.stepId(), Map.of(
			"watchId", watchId,
			"timeoutTicks", watch.spec.timeoutTicks(),
			"sourceFactIdentity", watch.spec.sourceFactIdentity() == null ? Map.of() : watch.spec.sourceFactIdentity().keys(),
			"progressKind", watch.spec.progressKind().name()
		));
	}

	private ActionPlanStep bindPrimitiveStep(ActionPlanStep step) {
		if (step == null || boundSmeltingProcessId.isBlank() || !"collect_smelted_item".equals(step.targetId())) {
			return step;
		}
		LinkedHashMap<String, Object> args = new LinkedHashMap<>(step.args());
		args.put("processId", boundSmeltingProcessId);
		return new ActionPlanStep(
			step.kind(),
			step.actionId(),
			step.alternativeId(),
			step.stepId(),
			step.targetId(),
			args,
			step.watchSpec()
		);
	}

	private ActionWatchSpec bindSmeltingWatch(ActionWatchSpec spec, ActionGraphExecutionInput input) {
		if (spec.condition().factType() != ActionFactType.SMELTING_PROCESS || boundSmeltingProcessId.isBlank()) {
			return spec;
		}
		LinkedHashMap<String, String> exactKeys = new LinkedHashMap<>(spec.condition().queryKeys());
		exactKeys.put("processId", boundSmeltingProcessId);
		ActionFact source = facts.query(ActionFactType.SMELTING_PROCESS, exactKeys).stream()
			.filter(fact -> fact.provenance().authoritative())
			.filter(fact -> !fact.isStaleAt(input.context().currentTick()))
			.findFirst()
			.orElse(null);
		ActionWatchAnchor anchor = smeltingAnchor(source, input.context());
		return new ActionWatchSpec(
			new ActionFactCondition(ActionFactType.SMELTING_PROCESS, exactKeys, spec.condition().minimums()),
			source == null ? null : source.identity(),
			spec.timeoutTicks(),
			spec.progressKind(),
			anchor
		);
	}

	private static ActionWatchAnchor smeltingAnchor(ActionFact source, ActionResolverContext context) {
		if (source == null || !(source.payload().get("origin") instanceof Map<?, ?> origin)) {
			return null;
		}
		Object x = origin.get("x");
		Object y = origin.get("y");
		Object z = origin.get("z");
		if (!(x instanceof Number xNumber) || !(y instanceof Number yNumber) || !(z instanceof Number zNumber)) {
			return null;
		}
		return new ActionWatchAnchor(
			context.worldId(),
			context.dimension(),
			xNumber.intValue(),
			yNumber.intValue(),
			zNumber.intValue(),
			false
		);
	}

	private static String stringPayload(Map<String, Object> payload, String key) {
		if (payload == null || payload.get(key) == null) {
			return "";
		}
		return String.valueOf(payload.get(key)).trim();
	}

	private void pollWatch(ActionGraphExecutionInput input) {
		if (currentStep == null) {
			state = ActionGraphExecutionState.READY;
			return;
		}
		PendingWatch watch = watches.get(currentStep.stepId());
		if (watch == null) {
			state = ActionGraphExecutionState.READY;
			return;
		}
		if (goalSatisfied(input.context()) || watchSatisfied(watch, input.context())) {
			watches.remove(currentStep.stepId());
			trace("watch_fulfilled", currentStep.actionId(), currentStep.alternativeId(), currentStep.stepId(), Map.of("watchId", watch.watchId()));
			cursor++;
			state = ActionGraphExecutionState.OBSERVING;
			return;
		}
		updateWatchEligibility(watch, input);
		if (watch.consumedEligibleTicks >= watch.spec.timeoutTicks()) {
			watches.remove(currentStep.stepId());
			trace("watch_timed_out", currentStep.actionId(), currentStep.alternativeId(), currentStep.stepId(), Map.of(
				"watchId", watch.watchId(),
				"consumedEligibleTicks", watch.consumedEligibleTicks
			));
			handleStepFailure(TaskFailureCode.MISSING_FACT, "watch timed out", false);
		}
	}

	private void updateWatchEligibility(PendingWatch watch, ActionGraphExecutionInput input) {
		long currentTick = input.context().currentTick();
		long elapsed = Math.max(0L, currentTick - watch.lastCheckedTick);
		watch.lastCheckedTick = currentTick;
		ActionWatchProgressObservation observation = watch.spec.progressKind() == ActionWatchProgressKind.NONE
			? ActionWatchProgressObservation.active()
			: input.watchProgress().get(watch.watchId);
		if (observation == null) {
			observation = input.agentPosition() == null
				? ActionWatchProgressObservation.active()
				: ActionWatchProgressObservation.paused("progress_unknown");
		}
		if (observation.eligible()) {
			watch.consumedEligibleTicks += elapsed;
		}
		if (watch.eligibilityInitialized
			&& watch.progressEligible == observation.eligible()
			&& Objects.equals(watch.pauseReason, observation.pauseReason())) {
			return;
		}
		watch.eligibilityInitialized = true;
		watch.progressEligible = observation.eligible();
		watch.pauseReason = observation.pauseReason();
		trace(
			observation.eligible() ? "watch_progress_resumed" : "watch_progress_paused",
			watch.step.actionId(),
			watch.step.alternativeId(),
			watch.step.stepId(),
			Map.of(
				"watchId", watch.watchId,
				"reason", observation.pauseReason(),
				"consumedEligibleTicks", watch.consumedEligibleTicks
			)
		);
	}

	private boolean watchSatisfied(PendingWatch watch, ActionResolverContext context) {
		ActionFactCondition condition = watch.spec.condition();
		return facts.query(condition.factType(), condition.queryKeys()).stream()
			.filter(fact -> fact.provenance().authoritative())
			.filter(fact -> !fact.isStaleAt(context.currentTick()))
			.anyMatch(condition::satisfiedBy);
	}

	private static ActionWatchSpec legacyWatchSpec(Map<String, Object> factSpec, ActionResolverContext context) {
		ActionFactType type = ActionFactType.fromId(String.valueOf(factSpec.getOrDefault("fact", "")))
			.orElseThrow(() -> new IllegalArgumentException("watch fact type is required"));
		LinkedHashMap<String, String> keys = new LinkedHashMap<>();
		keys.put("worldId", context.worldId());
		if (worldDimensionScoped(type)) {
			keys.put("dimension", context.dimension());
		}
		for (String key : List.of("itemId", "toolTag", "cropId", "siteId", "plotId", "candidateId", "sourceId", "sampleId", "entityId", "recipeId")) {
			Object value = factSpec.get(key);
			if (value != null && !String.valueOf(value).isBlank()) {
				keys.put(key, String.valueOf(value));
			}
		}
		LinkedHashMap<String, Integer> minimums = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : factSpec.entrySet()) {
			if (entry.getKey().endsWith("AtLeast") && entry.getValue() instanceof Number number) {
				minimums.put(payloadKeyForMinimum(entry.getKey()), number.intValue());
			}
		}
		return new ActionWatchSpec(
			new ActionFactCondition(type, keys, minimums),
			null,
			longArg(factSpec, "timeoutTicks", 24000L),
			ActionWatchProgressKind.NONE,
			null
		);
	}

	private static boolean worldDimensionScoped(ActionFactType type) {
		return type.name().startsWith("WORLD_");
	}

	private void block(String code, String blockMessage) {
		failureCode = code;
		message = blockMessage;
		state = ActionGraphExecutionState.BLOCKED;
		trace("execution_blocked", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of(
			"failureCode", code,
			"message", blockMessage
		));
	}

	private void succeed(String successMessage) {
		state = ActionGraphExecutionState.SUCCEEDED;
		failureCode = "";
		message = successMessage;
		trace("execution_succeeded", "", "", "", Map.of("executionId", executionId, "message", successMessage));
	}

	private void fail(String code, String failureMessage) {
		state = ActionGraphExecutionState.FAILED;
		failureCode = nonEmpty(code, "failed");
		message = nonEmpty(failureMessage, failureCode);
		trace("execution_failed", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of(
			"failureCode", failureCode,
			"message", message
		));
	}

	private boolean terminal() {
		return state == ActionGraphExecutionState.SUCCEEDED
			|| state == ActionGraphExecutionState.FAILED
			|| state == ActionGraphExecutionState.CANCELLED;
	}

	private boolean matchesActiveTask(TaskTerminalEvent event) {
		return event != null && !activeTaskId.isBlank() && Objects.equals(activeTaskId, event.taskId());
	}

	private boolean canReplan(String classifiedFailure) {
		if (replanCount >= MAX_REPLANS) {
			return false;
		}
		return "missing_fact".equals(classifiedFailure) || "environment_changed".equals(classifiedFailure);
	}

	private static boolean shouldBlockAlternativeForFailure(String classifiedFailure) {
		return "environment_changed".equals(classifiedFailure);
	}

	private void blockCurrentAlternative() {
		if (currentStep != null && !currentStep.actionId().isBlank() && !currentStep.alternativeId().isBlank()) {
			blockedAlternatives.add(currentStep.methodKey());
		}
	}

	private void ingestObservedFacts(ActionGraphExecutionInput input) {
		blockAcquisitions = input.blockAcquisitions();
		nearbyBlockAvailability = input.nearbyBlockAvailability();
		addInventoryFacts(input.observedInventory(), ActionFactProvenance.OBSERVED, input.context(), true);
		addResourceFacts(input.observedResources(), ActionFactProvenance.OBSERVED, input.context());
		addCraftRecipeFacts(input.availableCrafts(), ActionFactProvenance.OBSERVED, input.context());
		addCraftRecipeFacts(input.knownCrafts(), ActionFactProvenance.INFERRED, input.context());
		addSmeltRecipeFacts(input.availableSmelts(), input.context());
		addInferredSmeltRecipeFacts(input.knownSmelts(), input.context());
		addObservedFacts(input.observedFacts());
	}

	private void addObservedFacts(List<ActionFact> observedFacts) {
		if (observedFacts == null || observedFacts.isEmpty()) {
			return;
		}
		for (ActionFact fact : observedFacts) {
			if (fact == null) {
				continue;
			}
			ActionFact stored = facts.upsert(fact);
			if (stored != fact) {
				continue;
			}
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("fact", fact.identity().type().id());
			payload.putAll(fact.identity().keys());
			payload.put("provenance", fact.provenance().name());
			traceFactObservedIfChanged(fact, payload);
		}
	}

	private void addCraftRecipeFacts(
		List<CraftingOpportunity> availableCrafts,
		ActionFactProvenance provenance,
		ActionResolverContext context
	) {
		addCraftRecipeFacts(availableCrafts, provenance, context, context.currentTick() + 1);
	}

	private void addCraftRecipeFacts(
		List<CraftingOpportunity> availableCrafts,
		ActionFactProvenance provenance,
		ActionResolverContext context,
		long staleAtTick
	) {
		if (availableCrafts == null || availableCrafts.isEmpty()) {
			return;
		}
		for (CraftingOpportunity opportunity : availableCrafts) {
			LinkedHashMap<String, Integer> inputCounts = new LinkedHashMap<>();
			for (String inputItemId : opportunity.inputItemIds()) {
				inputCounts.merge(inputItemId, 1, Integer::sum);
			}
			ActionFact fact = new ActionFact(
				ActionFactIdentity.craftRecipe(context.worldId(), context.actorId(), opportunity.recipeId()),
				Map.of(
					"outputItemId", opportunity.outputItemId(),
					"outputCount", opportunity.outputCount(),
					"inputItemIds", opportunity.inputItemIds(),
					"inputCounts", inputCounts,
					"gridKind", opportunity.gridKind().name()
				),
				provenance,
				context.currentTick(),
				staleAtTick
			);
			ActionFact stored = facts.upsert(fact);
			if (stored != fact) {
				continue;
			}
			traceFactObservedIfChanged(fact, Map.of(
				"fact", ActionFactType.CRAFT_RECIPE.id(),
				"recipeId", opportunity.recipeId(),
				"outputItemId", opportunity.outputItemId(),
				"provenance", provenance.name()
			));
		}
	}

	private void addSmeltRecipeFacts(List<SmeltingOption> availableSmelts, ActionResolverContext context) {
		if (availableSmelts == null || availableSmelts.isEmpty()) {
			return;
		}
		for (SmeltingOption option : availableSmelts) {
			ActionFact fact = new ActionFact(
				ActionFactIdentity.smeltRecipe(context.worldId(), context.actorId(), option.optionId()),
				Map.of(
					"inputItemId", option.inputItemId(),
					"outputItemId", option.outputItemId(),
					"outputCount", option.outputCount(),
					"maxInputQuantity", option.maxInputQuantity(),
					"cookTimeTicks", option.cookTimeTicks()
				),
				ActionFactProvenance.OBSERVED,
				context.currentTick(),
				context.currentTick() + 1
			);
			ActionFact stored = facts.upsert(fact);
			if (stored != fact) {
				continue;
			}
			traceFactObservedIfChanged(fact, Map.of(
				"fact", ActionFactType.SMELT_RECIPE.id(),
				"optionId", option.optionId(),
				"inputItemId", option.inputItemId(),
				"outputItemId", option.outputItemId(),
				"provenance", ActionFactProvenance.OBSERVED.name()
			));
		}
	}

	private void addInferredSmeltRecipeFacts(
		List<SmeltingRecipeKnowledge> inferredSmelts,
		ActionResolverContext context
	) {
		if (inferredSmelts == null || inferredSmelts.isEmpty()) {
			return;
		}
		for (SmeltingRecipeKnowledge recipe : inferredSmelts) {
			ActionFact fact = new ActionFact(
				ActionFactIdentity.smeltRecipe(context.worldId(), context.actorId(), recipe.optionId()),
				Map.of(
					"inputItemId", recipe.inputItemId(),
					"outputItemId", recipe.outputItemId(),
					"outputCount", recipe.outputCount(),
					"maxInputQuantity", recipe.maxInputQuantity(),
					"cookTimeTicks", recipe.cookTimeTicks(),
					"stationItemId", recipe.stationItemId(),
					"stationItemCount", recipe.stationItemCount()
				),
				ActionFactProvenance.INFERRED,
				context.currentTick(),
				ActionFact.NEVER_STALE
			);
			ActionFact stored = facts.upsert(fact);
			if (stored != fact) {
				continue;
			}
			traceFactObservedIfChanged(fact, Map.of(
				"fact", ActionFactType.SMELT_RECIPE.id(),
				"optionId", recipe.optionId(),
				"inputItemId", recipe.inputItemId(),
				"outputItemId", recipe.outputItemId(),
				"provenance", ActionFactProvenance.INFERRED.name()
			));
		}
	}

	private void addInventoryFacts(
		Map<String, Integer> inventory,
		ActionFactProvenance provenance,
		ActionResolverContext context,
		boolean observed
	) {
		if ((inventory == null || inventory.isEmpty()) && !observed) {
			return;
		}
		int count = 0;
		Map<String, Integer> safeInventory = inventory == null ? Map.of() : inventory;
		Set<String> presentItemIds = new LinkedHashSet<>();
		for (Map.Entry<String, Integer> entry : safeInventory.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank()) {
				continue;
			}
			presentItemIds.add(entry.getKey());
			int itemCount = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
			if (upsertInventoryFact(entry.getKey(), itemCount, provenance, context)) {
				count++;
			}
		}
		if (observed) {
			for (String missingItemId : List.copyOf(observedInventoryItemIds)) {
				if (!presentItemIds.contains(missingItemId) && upsertInventoryFact(missingItemId, 0, provenance, context)) {
					count++;
				}
			}
			observedInventoryItemIds.clear();
			observedInventoryItemIds.addAll(presentItemIds);
		}
		if (observed) {
			observedInventoryFactCount = count;
		}
		else {
			assumedInventoryFactCount = count;
		}
	}

	private boolean upsertInventoryFact(
		String itemId,
		int itemCount,
		ActionFactProvenance provenance,
		ActionResolverContext context
	) {
		ActionFact fact = new ActionFact(
			ActionFactIdentity.inventoryItem(context.worldId(), context.actorId(), itemId),
			Map.of("count", Math.max(0, itemCount)),
			provenance,
			context.currentTick(),
			ActionFact.NEVER_STALE
		);
		ActionFact stored = facts.upsert(fact);
		if (stored != fact) {
			return false;
		}
		traceFactObservedIfChanged(fact, Map.of(
			"fact", ActionFactType.INVENTORY_ITEM.id(),
			"itemId", itemId,
			"count", Math.max(0, itemCount),
			"provenance", provenance.name()
		));
		return true;
	}

	private void addResourceFacts(
		Map<String, Integer> resources,
		ActionFactProvenance provenance,
		ActionResolverContext context
	) {
		if (resources == null || resources.isEmpty()) {
			return;
		}
		for (Map.Entry<String, Integer> entry : resources.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank()) {
				continue;
			}
			int count = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
			ActionFact fact = new ActionFact(
				ActionFactIdentity.inventoryResource(context.worldId(), context.actorId(), entry.getKey()),
				Map.of("count", count),
				provenance,
				context.currentTick(),
				ActionFact.NEVER_STALE
			);
			ActionFact stored = facts.upsert(fact);
			if (stored != fact) {
				continue;
			}
			traceFactObservedIfChanged(fact, Map.of(
				"fact", ActionFactType.INVENTORY_RESOURCE.id(),
				"resourceKind", entry.getKey(),
				"count", count,
				"provenance", provenance.name()
			));
		}
	}

	private boolean goalSatisfied(ActionResolverContext context) {
		return facts.query(goal.factType(), goal.keys()).stream()
			.filter(fact -> fact.provenance().authoritative())
			.filter(fact -> !fact.isStaleAt(context.currentTick()))
			.anyMatch(fact -> minimumsSatisfied(goal.minimums(), fact.payload()));
	}

	private static boolean minimumsSatisfied(Map<String, Integer> minimums, Map<String, Object> payload) {
		for (Map.Entry<String, Integer> entry : minimums.entrySet()) {
			String payloadKey = payloadKeyForMinimum(entry.getKey());
			Object value = payload.get(payloadKey);
			if (!(value instanceof Number number) || number.intValue() < entry.getValue()) {
				return false;
			}
		}
		return true;
	}

	private static String payloadKeyForMinimum(String minimumKey) {
		if ("countAtLeast".equals(minimumKey)) {
			return "count";
		}
		if (minimumKey.endsWith("AtLeast")) {
			return minimumKey.substring(0, minimumKey.length() - "AtLeast".length());
		}
		return minimumKey;
	}

	private static String normalizeFailureCode(TaskFailureCode failureCode) {
		return switch (ActionGraphFailurePolicy.category(failureCode)) {
			case RETRY -> "transient";
			case MISSING_FACT -> "missing_fact";
			case BLOCKED -> "environment_changed";
			case INVALID_REQUEST -> "invalid_action";
			case TERMINAL -> failureCode == TaskFailureCode.DESTRUCTIVE_DENIED ? "destructive_denied" : "failed";
		};
	}

	private static boolean isBusyFailure(TaskFailureCode failureCode) {
		return failureCode == TaskFailureCode.BUSY;
	}

	private void trace(String eventType, String actionId, String alternativeId, String stepId, Map<String, Object> payload) {
		trace.add(new ActionTraceEvent(eventType, actionId, alternativeId, stepId, payload));
	}

	private void traceFactObservedIfChanged(ActionFact fact, Map<String, Object> payload) {
		FactTraceFingerprint fingerprint = new FactTraceFingerprint(fact.provenance(), fact.payload());
		if (fingerprint.equals(tracedFactObservations.get(fact.identity()))) {
			return;
		}
		tracedFactObservations.put(fact.identity(), fingerprint);
		trace("fact_observed", "", "", "", payload);
	}

	private static long longArg(Map<String, Object> args, String key, long defaultValue) {
		Object value = args.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof String text) {
			try {
				return Long.parseLong(text.trim());
			}
			catch (NumberFormatException ignored) {
				return defaultValue;
			}
		}
		return defaultValue;
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static String actionId(ActionPlanStep step) {
		return step == null ? "" : step.actionId();
	}

	private static String alternativeId(ActionPlanStep step) {
		return step == null ? "" : step.alternativeId();
	}

	private static String stepId(ActionPlanStep step) {
		return step == null ? "" : step.stepId();
	}

	private static final class PendingWatch {
		private final String watchId;
		private final ActionPlanStep step;
		private final ActionWatchSpec spec;
		private long lastCheckedTick;
		private long consumedEligibleTicks;
		private boolean eligibilityInitialized;
		private boolean progressEligible;
		private String pauseReason = "";

		private PendingWatch(String watchId, ActionPlanStep step, ActionWatchSpec spec, long registeredTick) {
			this.watchId = watchId;
			this.step = step;
			this.spec = spec;
			this.lastCheckedTick = registeredTick;
		}

		private String watchId() {
			return watchId;
		}
	}

	private record FactTraceFingerprint(ActionFactProvenance provenance, Map<String, Object> payload) {
		FactTraceFingerprint {
			payload = payload == null || payload.isEmpty() ? Map.of() : Map.copyOf(payload);
		}
	}
}
