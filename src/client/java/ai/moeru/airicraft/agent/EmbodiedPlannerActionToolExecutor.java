package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.actions.ActionGraphExecutionSnapshot;
import ai.moeru.airicraft.agent.job.ActiveJobType;
import ai.moeru.airicraft.agent.llm.PlannerActionToolExecutor;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexState;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

record EmbodiedPlannerActionToolExecutor(
	Supplier<ExecutionState> stateSupplier, Function<JsonObject, CompletableFuture<String>> craftExecutor,
	Function<PlannerToolCall, CompletableFuture<String>> blockExecutor, Function<PlannerToolCall, String> synchronousExecutor
) implements PlannerActionToolExecutor {

	@Override
	public CompletableFuture<String> execute(PlannerToolCall toolCall) {
		try {
			if (toolCall == null) {
				return CompletableFuture.completedFuture(synchronousExecutor.apply(null));
			}
			String toolName = PlannerToolCatalog.normalizeName(toolCall.name());
			ToolPolicy policy = policy(toolName);
			ExecutionState state = stateSupplier.get();
			if (state.itemUseActive() && policy.requiresLivingPlayer()) {
				return CompletableFuture.completedFuture("TOOL_ERROR: " + toolName
					+ " item_use_active. Wait for the current food consumption to finish.");
			}
			if (state.reflexState() == SurvivalReflexState.ACTIVE && !policy.allowedDuringActiveReflex()) {
				return CompletableFuture.completedFuture("TOOL_ERROR: " + toolName
					+ " reflex_active. Read, cancel/clear, and configure_reflex controls are allowed during an active survival reflex.");
			}
			if (policy.requiresLivingPlayer() && state.requiresRespawn()) {
				return CompletableFuture.completedFuture("TOOL_ERROR: " + toolName
					+ " player_dead. The controlled player died; the runtime cancelled all actions and is requesting respawn.");
			}
			if (policy == ToolPolicy.CRAFT) {
				return state.activeTaskInProgress()
					? CompletableFuture.completedFuture(activeTaskError(toolName, state))
					: craftExecutor.apply(toolCall.arguments());
			}
			if (policy == ToolPolicy.BLOCK_MODIFICATION) {
				return state.reflexState() != SurvivalReflexState.AWAITING_PLANNER && state.activeTaskInProgress()
					? CompletableFuture.completedFuture(activeTaskError(toolName, state))
					: blockExecutor.apply(toolCall);
			}
			if (state.activeGraph() && policy.preemptsGraph()) {
				return CompletableFuture.completedFuture(activeGraphError(toolName, state.actionGraphSnapshot()));
			}
			if (state.reflexState() != SurvivalReflexState.AWAITING_PLANNER
				&& state.activeTaskInProgress()
				&& policy.preemptsTask()
				&& !(PlannerToolCatalog.COLLECT_SMELTED_ITEMS.equals(toolName) && state.activeJobType() == ActiveJobType.SMELT_ITEMS)
			) {
				return CompletableFuture.completedFuture(activeTaskError(toolName, state));
			}
			return CompletableFuture.completedFuture(synchronousExecutor.apply(toolCall));
		}
		catch (RuntimeException exception) {
			String name = toolCall == null ? "unknown" : toolCall.name();
			String message = exception.getMessage();
			return CompletableFuture.completedFuture("TOOL_ERROR: " + name + " " + (message == null || message.isBlank()
				? exception.getClass().getSimpleName()
				: message.replace('\n', ' ').replace('\r', ' ').strip()));
		}
	}

	private static ToolPolicy policy(String toolName) {
		if (PlannerToolCatalog.isReadTool(toolName)) {
			return ToolPolicy.READ;
		}
		return switch (toolName) {
			case "inspect_work", "list_work", "cancel_work" -> ToolPolicy.READ;
			case "resume_work" -> ToolPolicy.ACTION;
			case PlannerToolCatalog.CANCEL_ACTION_GOAL, PlannerToolCatalog.CANCEL_SMELTING,
				PlannerToolCatalog.CONFIGURE_REFLEX, PlannerToolCatalog.CONFIGURE_FOOD -> ToolPolicy.READ;
			case PlannerToolCatalog.CANCEL_TASK, PlannerToolCatalog.CLEAR_GOAL -> ToolPolicy.GRAPH_CONTROL;
			case PlannerToolCatalog.UPDATE_EVENT_POLICY, PlannerToolCatalog.CONFIGURE_PATHFIND,
				PlannerToolCatalog.CONFIGURE_LIGHTING -> ToolPolicy.DEAD_SAFE;
			case PlannerToolCatalog.START_ACTION_GOAL, PlannerToolCatalog.EAT_FOOD -> ToolPolicy.TASK_MUTATION;
			case "run_policy", PlannerToolCatalog.CLOSE_CONTAINER, PlannerToolCatalog.TRANSFER_CONTAINER, PlannerToolCatalog.FOLLOW_PLAYER, PlannerToolCatalog.NAVIGATE_TO,
				PlannerToolCatalog.RETURN_TO_SURFACE, PlannerToolCatalog.MINE_BLOCKS,
				PlannerToolCatalog.ENSURE_BLOCKS_IN_INVENTORY, PlannerToolCatalog.COLLECT_RESOURCE,
				PlannerToolCatalog.SMELT_ITEMS, PlannerToolCatalog.COLLECT_SMELTED_ITEMS,
				PlannerToolCatalog.DROP_ITEMS, PlannerToolCatalog.GIVE_PLAYER,
				PlannerToolCatalog.EQUIP_ITEM,
				PlannerToolCatalog.ATTACK_ENTITY, PlannerToolCatalog.USE_ENTITY, PlannerToolCatalog.LURE_ENTITIES -> ToolPolicy.TASK_AND_GRAPH_MUTATION;
			case PlannerToolCatalog.CRAFT_RECIPE -> ToolPolicy.CRAFT;
			case PlannerToolCatalog.PLACE_BLOCK, PlannerToolCatalog.USE_BLOCK,
				PlannerToolCatalog.BREAK_BLOCKS, PlannerToolCatalog.TEND_CROPS -> ToolPolicy.BLOCK_MODIFICATION;
			default -> ToolPolicy.ACTION;
		};
	}

	private static String activeTaskError(String toolName, ExecutionState state) {
		TaskSnapshot task = state.taskSnapshot();
		TaskExecutionSnapshot execution = state.taskExecutionSnapshot();
		return "TOOL_ERROR: " + toolName + " denied reason=active_task_in_progress"
			+ " taskState=" + (task == null || task.state() == null ? "UNKNOWN" : task.state().name())
			+ " activeStepKind=" + (task == null || task.activeStepKind() == null ? "UNKNOWN" : task.activeStepKind().name())
			+ " taskExecutionState=" + (execution == null || execution.state() == null ? "UNKNOWN" : execution.state().name())
			+ " taskExecutionProcess=" + (execution == null || execution.processName() == null ? "UNKNOWN" : execution.processName())
			+ ". Task-changing tools would preempt the active job. Use clear_queue before replacing the plan.";
	}

	private static String activeGraphError(String toolName, ActionGraphExecutionSnapshot snapshot) {
		ActionGraphExecutionSnapshot graph = snapshot == null ? ActionGraphExecutionSnapshot.idle() : snapshot;
		return "TOOL_ERROR: " + toolName + " denied reason=active_action_graph_in_progress"
			+ " graphState=" + graph.state().name()
			+ " executionId=" + graph.executionId()
			+ " activeTaskId=" + graph.activeTaskId()
			+ ". A graph execution owns the mutation boundary. Use inspect_work/list_work to observe progress. Wait for the foreground lane, or use clear_queue to replace the plan.";
	}

	record ExecutionState(
		SurvivalReflexState reflexState,
		boolean requiresRespawn,
		boolean itemUseActive,
		boolean activeTaskInProgress,
		ActiveJobType activeJobType,
		boolean activeGraph,
		ActionGraphExecutionSnapshot actionGraphSnapshot,
		TaskSnapshot taskSnapshot,
		TaskExecutionSnapshot taskExecutionSnapshot
	) {
	}

	private record ToolPolicy(
		boolean requiresLivingPlayer,
		boolean allowedDuringActiveReflex,
		boolean preemptsTask,
		boolean preemptsGraph
	) {
		private static final ToolPolicy READ = new ToolPolicy(false, true, false, false);
		private static final ToolPolicy GRAPH_CONTROL = new ToolPolicy(false, true, false, true);
		private static final ToolPolicy DEAD_SAFE = new ToolPolicy(false, false, false, false);
		private static final ToolPolicy ACTION = new ToolPolicy(true, false, false, false);
		private static final ToolPolicy TASK_MUTATION = new ToolPolicy(true, false, true, false);
		private static final ToolPolicy TASK_AND_GRAPH_MUTATION = new ToolPolicy(true, false, true, true);
		private static final ToolPolicy CRAFT = new ToolPolicy(true, false, false, false);
		private static final ToolPolicy BLOCK_MODIFICATION = new ToolPolicy(true, false, false, false);
	}
}
