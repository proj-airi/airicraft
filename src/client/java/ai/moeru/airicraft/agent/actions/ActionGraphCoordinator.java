package ai.moeru.airicraft.agent.actions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class ActionGraphCoordinator {
	public static final int MAX_NONTERMINAL_EXECUTIONS = 16;
	public static final int MAX_TERMINAL_EXECUTIONS = 32;

	private final Supplier<ActionGraphExecutionRuntime> runtimeFactory;
	private final ExecutorService resolutionExecutor;
	private volatile java.util.concurrent.Executor resolutionExecutorOverride;
	private final Map<String, ManagedExecution> executions = new LinkedHashMap<>();
	private final PriorityQueue<RunnableEntry> runnable = new PriorityQueue<>(Comparator
		.comparingLong(RunnableEntry::fulfilledTick)
		.thenComparingLong(RunnableEntry::creationOrder));
	private final List<ActionGraphCoordinatorEvent> events = new ArrayList<>();
	private String foregroundExecutionId = "";
	private long nextCreationOrder;

	public ActionGraphCoordinator(ActionGraphPrimitiveDispatcher dispatcher) {
		this.resolutionExecutor = Executors.newSingleThreadExecutor(
			Thread.ofPlatform().name("airicraft-action-advisor").daemon(true).factory()
		);
		this.runtimeFactory = () -> new ActionGraphExecutionRuntime(dispatcher,
			resolutionExecutorOverride == null ? resolutionExecutor : resolutionExecutorOverride);
	}

	/**
	 * Test seam: route resolutions of executions submitted afterwards run on {@code executor}, so a harness can
	 * decide when they complete instead of racing the advisor thread. Production never calls this.
	 */
	public void overrideResolutionExecutorForTests(java.util.concurrent.Executor executor) {
		resolutionExecutorOverride = Objects.requireNonNull(executor, "executor");
	}

	private ActionGraphCoordinator(Supplier<ActionGraphExecutionRuntime> runtimeFactory, ExecutorService resolutionExecutor) {
		this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
		this.resolutionExecutor = resolutionExecutor;
	}

	public synchronized ActionGraphStartResult submit(
		ActionGoal goal,
		Map<String, Integer> assumedInventory,
		ActionResolverContext context,
		long tick
	) {
		Objects.requireNonNull(goal, "goal");
		Optional<ManagedExecution> identical = executions.values().stream()
			.filter(ManagedExecution::nonterminal)
			.filter(managed -> managed.runtime.snapshot().goal() != null)
			.filter(managed -> goal.normalizedKey().equals(managed.runtime.snapshot().goal().normalizedKey()))
			.findFirst();
		if (identical.isPresent()) {
			return startResult(ActionGraphAdmission.EXISTING, identical.get(), "", "");
		}
		ManagedExecution laneOwner = nextLaneOwner();
		if (laneOwner != null) {
			return startResult(
				ActionGraphAdmission.BUSY,
				laneOwner,
				"foreground_busy",
				"A different action graph execution owns foreground actuation or the next runnable slot"
			);
		}
		if (nonterminalCount() >= MAX_NONTERMINAL_EXECUTIONS) {
			return startResult(
				ActionGraphAdmission.BUSY,
				selectedManaged().orElse(null),
				"capacity_reached",
				"The action graph coordinator has reached its nonterminal execution limit"
			);
		}

		String executionId = "action-graph-" + UUID.randomUUID();
		ActionGraphExecutionRuntime runtime = runtimeFactory.get();
		runtime.submit(goal, assumedInventory, context, tick, executionId);
		ManagedExecution managed = new ManagedExecution(runtime, nextCreationOrder++, tick, tick, ActionGraphResidency.FOREGROUND);
		executions.put(executionId, managed);
		foregroundExecutionId = executionId;
		events.add(new ActionGraphCoordinatorEvent("action_graph.goal_started", executionId, Map.of("goal", goal.normalizedKey())));
		return startResult(ActionGraphAdmission.STARTED, managed, "", "");
	}

	public synchronized void tick(ActionGraphExecutionInput input, boolean foregroundAllowed) {
		Objects.requireNonNull(input, "input");
		if (!input.worldLoaded()) {
			cancelAll("world_left", input.context().currentTick());
			return;
		}
		pollSuspended(input);
		if (foregroundAllowed && !foregroundExecutionId.isBlank()) {
			ManagedExecution foreground = executions.get(foregroundExecutionId);
			if (foreground != null) {
				foreground.runtime.tickForeground(input);
				foreground.updatedTick = input.context().currentTick();
				updateForegroundResidency(foreground, input.context().currentTick());
			}
		}
		if (foregroundAllowed && input.actuationAllowed()) {
			resumeRunnable(input);
		}
		pruneTerminalHistory();
	}

	private void pollSuspended(ActionGraphExecutionInput input) {
		for (ManagedExecution managed : executions.values()) {
			if (managed.residency != ActionGraphResidency.SUSPENDED) {
				continue;
			}
			managed.runtime.tickPassive(input.withoutTerminalTaskEvent());
			managed.updatedTick = input.context().currentTick();
			ActionGraphExecutionSnapshot snapshot = managed.runtime.snapshot();
			if (snapshot.state() != ActionGraphExecutionState.WATCHING) {
				managed.residency = terminal(snapshot.state()) ? ActionGraphResidency.TERMINAL : ActionGraphResidency.RUNNABLE;
				if (managed.residency == ActionGraphResidency.RUNNABLE) {
					runnable.add(new RunnableEntry(snapshot.executionId(), input.context().currentTick(), managed.creationOrder));
					events.add(new ActionGraphCoordinatorEvent("action_graph.goal_runnable", snapshot.executionId(), Map.of()));
				}
			}
		}
	}

	private void resumeRunnable(ActionGraphExecutionInput input) {
		for (int transitions = 0; transitions < MAX_NONTERMINAL_EXECUTIONS && foregroundExecutionId.isBlank(); transitions++) {
			RunnableEntry next = runnable.poll();
			if (next == null) {
				return;
			}
			ManagedExecution managed = executions.get(next.executionId());
			if (managed == null || managed.residency != ActionGraphResidency.RUNNABLE || !managed.nonterminal()) {
				continue;
			}
			managed.residency = ActionGraphResidency.FOREGROUND;
			managed.updatedTick = input.context().currentTick();
			foregroundExecutionId = next.executionId();
			events.add(new ActionGraphCoordinatorEvent("action_graph.goal_resumed", next.executionId(), Map.of()));
			managed.runtime.tickForeground(input.withoutTerminalTaskEvent());
			managed.updatedTick = input.context().currentTick();
			updateForegroundResidency(managed, input.context().currentTick());
		}
	}

	private void updateForegroundResidency(ManagedExecution managed, long tick) {
		ActionGraphExecutionSnapshot snapshot = managed.runtime.snapshot();
		if (snapshot.state() == ActionGraphExecutionState.WATCHING) {
			if (!snapshot.activeTaskId().isBlank()) {
				throw new IllegalStateException("watching execution still owns active task " + snapshot.activeTaskId());
			}
			managed.residency = ActionGraphResidency.SUSPENDED;
			foregroundExecutionId = "";
			events.add(new ActionGraphCoordinatorEvent("action_graph.goal_suspended", snapshot.executionId(), Map.of(
				"watchCount", snapshot.watchCount(),
				"pendingWatch", snapshot.pendingWatch()
			)));
			return;
		}
		if (terminal(snapshot.state())) {
			managed.residency = ActionGraphResidency.TERMINAL;
			managed.updatedTick = tick;
			foregroundExecutionId = "";
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("state", snapshot.state().name());
			payload.put("goal", snapshot.goal() == null ? "" : snapshot.goal().normalizedKey());
			payload.put("failureCode", snapshot.failureCode());
			payload.put("message", snapshot.message());
			ActionPlanStep failedStep = snapshot.currentStep();
			if (snapshot.state() == ActionGraphExecutionState.FAILED && failedStep != null) {
				payload.put("failedPrimitive", failedStep.targetId());
				payload.put("failedTarget", failedTarget(failedStep));
				payload.put("failedArgs", failedStep.args());
			}
			events.add(new ActionGraphCoordinatorEvent("action_graph.goal_terminal", snapshot.executionId(), payload));
		}
	}

	private static String failedTarget(ActionPlanStep step) {
		Object itemId = step.args().get("itemId");
		if (itemId != null && !String.valueOf(itemId).isBlank()) {
			return String.valueOf(itemId);
		}
		Object blockIds = step.args().get("blockIds");
		return blockIds == null ? step.targetId() : String.valueOf(blockIds);
	}

	public synchronized ActionGraphExecutionView cancel(String executionId, String reason, long tick) {
		ManagedExecution managed = executions.get(executionId == null ? "" : executionId);
		if (managed == null) {
			return null;
		}
		if (!managed.nonterminal()) {
			return view(managed);
		}
		String activeTaskId = managed.runtime.snapshot().activeTaskId();
		managed.runtime.cancel(reason, tick);
		managed.residency = ActionGraphResidency.TERMINAL;
		managed.updatedTick = tick;
		if (Objects.equals(foregroundExecutionId, executionId)) {
			foregroundExecutionId = "";
		}
		runnable.removeIf(entry -> entry.executionId().equals(executionId));
		events.add(new ActionGraphCoordinatorEvent("action_graph.goal_cancelled", executionId, Map.of(
			"reason", reason == null ? "" : reason,
			"activeTaskId", activeTaskId
		)));
		pruneTerminalHistory();
		return view(managed);
	}

	public synchronized ActionGraphExecutionView cancelSelected(String reason, long tick) {
		String executionId = foregroundExecutionId;
		List<ManagedExecution> nonterminal = executions.values().stream().filter(ManagedExecution::nonterminal).toList();
		if (executionId.isBlank() && nonterminal.size() == 1) {
			executionId = nonterminal.getFirst().runtime.snapshot().executionId();
		}
		if (executionId.isBlank() && nonterminal.size() > 1) {
			throw new IllegalArgumentException("execution_id_required");
		}
		return cancel(executionId, reason, tick);
	}

	public synchronized void cancelAll(String reason, long tick) {
		for (Map.Entry<String, ManagedExecution> entry : executions.entrySet()) {
			ManagedExecution managed = entry.getValue();
			if (!managed.nonterminal()) {
				continue;
			}
			String activeTaskId = managed.runtime.snapshot().activeTaskId();
			managed.runtime.cancel(reason, tick);
			managed.residency = ActionGraphResidency.TERMINAL;
			managed.updatedTick = tick;
			events.add(new ActionGraphCoordinatorEvent("action_graph.goal_cancelled", entry.getKey(), Map.of(
				"reason", reason == null ? "" : reason,
				"activeTaskId", activeTaskId
			)));
		}
		foregroundExecutionId = "";
		runnable.clear();
		pruneTerminalHistory();
	}

	public synchronized void clear() {
		executions.values().forEach(managed -> managed.runtime.clear());
		executions.clear();
		foregroundExecutionId = "";
		runnable.clear();
		events.clear();
	}

	public synchronized void shutdown() {
		clear();
		if (resolutionExecutor != null) {
			resolutionExecutor.shutdownNow();
		}
	}

	public synchronized void pauseForegroundForReflex(long tick) {
		ManagedExecution foreground = executions.get(foregroundExecutionId);
		if (foreground != null) {
			foreground.runtime.pauseForReflex(tick);
			foreground.updatedTick = tick;
		}
	}

	public synchronized boolean hasNonterminal() {
		return nonterminalCount() > 0;
	}

	public synchronized boolean hasForeground() {
		return !foregroundExecutionId.isBlank();
	}

	public synchronized String foregroundExecutionId() {
		return foregroundExecutionId;
	}

	public synchronized List<ActionGraphExecutionView> list() {
		return executions.values().stream().map(this::view).toList();
	}

	public synchronized ActionGraphExecutionView inspect(String executionId) {
		ManagedExecution managed = executionId == null || executionId.isBlank()
			? selectedManaged().orElse(null)
			: executions.get(executionId);
		return managed == null ? null : view(managed);
	}

	public synchronized List<ActionGraphWatchSnapshot> pendingWatches() {
		return executions.values().stream()
			.filter(ManagedExecution::nonterminal)
			.flatMap(managed -> managed.runtime.pendingWatches().stream())
			.toList();
	}

	public synchronized List<ActionGraphCoordinatorEvent> drainEvents() {
		List<ActionGraphCoordinatorEvent> drained = List.copyOf(events);
		events.clear();
		return drained;
	}

	public synchronized List<ActionGraphExecutionView> nonterminalExecutions() {
		return executions.values().stream().filter(ManagedExecution::nonterminal).map(this::view).toList();
	}

	private Optional<ManagedExecution> selectedManaged() {
		if (!foregroundExecutionId.isBlank()) {
			return Optional.ofNullable(executions.get(foregroundExecutionId));
		}
		Optional<ManagedExecution> nonterminal = executions.values().stream()
			.filter(ManagedExecution::nonterminal)
			.max(Comparator.comparingLong((ManagedExecution managed) -> managed.updatedTick)
				.thenComparingLong(managed -> managed.creationOrder));
		if (nonterminal.isPresent()) {
			return nonterminal;
		}
		return executions.values().stream()
			.max(Comparator.comparingLong((ManagedExecution managed) -> managed.updatedTick)
				.thenComparingLong(managed -> managed.creationOrder));
	}

	private int nonterminalCount() {
		return (int) executions.values().stream().filter(ManagedExecution::nonterminal).count();
	}

	private ManagedExecution nextLaneOwner() {
		if (!foregroundExecutionId.isBlank()) {
			return executions.get(foregroundExecutionId);
		}
		RunnableEntry next = runnable.peek();
		if (next != null) {
			ManagedExecution managed = executions.get(next.executionId());
			if (managed != null && managed.residency == ActionGraphResidency.RUNNABLE && managed.nonterminal()) {
				return managed;
			}
		}
		return executions.values().stream()
			.filter(ManagedExecution::nonterminal)
			.filter(managed -> managed.residency == ActionGraphResidency.RUNNABLE)
			.findFirst()
			.orElse(null);
	}

	private int count(ActionGraphResidency residency) {
		return (int) executions.values().stream().filter(managed -> managed.residency == residency).count();
	}

	private ActionGraphStartResult startResult(
		ActionGraphAdmission admission,
		ManagedExecution managed,
		String failureCode,
		String message
	) {
		return new ActionGraphStartResult(
			admission,
			managed == null ? null : view(managed),
			foregroundExecutionId,
			count(ActionGraphResidency.SUSPENDED),
			count(ActionGraphResidency.RUNNABLE),
			failureCode,
			message
		);
	}

	private ActionGraphExecutionView view(ManagedExecution managed) {
		return new ActionGraphExecutionView(
			managed.residency,
			managed.creationOrder,
			managed.createdTick,
			managed.updatedTick,
			managed.runtime.snapshot()
		);
	}

	private void pruneTerminalHistory() {
		List<Map.Entry<String, ManagedExecution>> terminals = executions.entrySet().stream()
			.filter(entry -> entry.getValue().residency == ActionGraphResidency.TERMINAL)
			.sorted(Comparator
				.comparingLong((Map.Entry<String, ManagedExecution> entry) -> entry.getValue().updatedTick)
				.thenComparingLong(entry -> entry.getValue().creationOrder))
			.toList();
		int excess = terminals.size() - MAX_TERMINAL_EXECUTIONS;
		for (int index = 0; index < excess; index++) {
			executions.remove(terminals.get(index).getKey());
		}
	}

	private static boolean terminal(ActionGraphExecutionState state) {
		return state == ActionGraphExecutionState.SUCCEEDED
			|| state == ActionGraphExecutionState.FAILED
			|| state == ActionGraphExecutionState.CANCELLED;
	}

	private static final class ManagedExecution {
		private final ActionGraphExecutionRuntime runtime;
		private final long creationOrder;
		private final long createdTick;
		private long updatedTick;
		private ActionGraphResidency residency;

		private ManagedExecution(
			ActionGraphExecutionRuntime runtime,
			long creationOrder,
			long createdTick,
			long updatedTick,
			ActionGraphResidency residency
		) {
			this.runtime = runtime;
			this.creationOrder = creationOrder;
			this.createdTick = createdTick;
			this.updatedTick = updatedTick;
			this.residency = residency;
		}

		private boolean nonterminal() {
			return residency != ActionGraphResidency.TERMINAL;
		}
	}

	private record RunnableEntry(String executionId, long fulfilledTick, long creationOrder) {
	}
}
