package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.actions.ActionGraphExecutionSnapshot;
import ai.moeru.airicraft.agent.actions.ActionGraphExecutionState;
import ai.moeru.airicraft.agent.actions.ActionRoute;
import ai.moeru.airicraft.agent.job.ActiveJobType;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexState;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EmbodiedPlannerActionToolExecutorTest {
	@Test void policyCannotPreemptExistingGraphOrSafetyOwner() {
		assertTrue(execute("run_policy", ActionGraphExecutionState.REPLANNING, false, false, SurvivalReflexState.IDLE, false).contains("active_action_graph_in_progress"));
		assertTrue(execute("run_policy", ActionGraphExecutionState.REPLANNING, false, false, SurvivalReflexState.ACTIVE, false).contains("reflex_active"));
		assertTrue(execute("run_policy", ActionGraphExecutionState.REPLANNING, false, false, SurvivalReflexState.IDLE, true).contains("player_dead"));
	}
	@Test void reflexPolicyCanBeChangedWhileReflexOwnsABusyGraph() {
		assertEquals("executed:configure_reflex", execute("configure_reflex",
			ActionGraphExecutionState.WAITING_PRIMITIVE, true, false, SurvivalReflexState.ACTIVE, false));
	}
	@Test void eatsBetweenGraphPrimitivesWithoutCancellingTheGraph() {
		for (var phase : List.of(ActionGraphExecutionState.REPLANNING, ActionGraphExecutionState.WATCHING,
			ActionGraphExecutionState.OBSERVING, ActionGraphExecutionState.READY)) {
			assertEquals("executed:eat_food", execute("eat_food", phase, false, false, SurvivalReflexState.IDLE, false));
			assertTrue(execute("navigate_to", phase, false, false, SurvivalReflexState.IDLE, false).contains("active_action_graph_in_progress"));
		}
	}

	@Test void activeTaskCombatDeathAndExistingConsumptionStillPreventEating() {
		assertTrue(execute("eat_food", ActionGraphExecutionState.WAITING_PRIMITIVE, true, false, SurvivalReflexState.IDLE, false).contains("active_task_in_progress"));
		assertTrue(execute("eat_food", ActionGraphExecutionState.REPLANNING, false, false, SurvivalReflexState.ACTIVE, false).contains("reflex_active"));
		assertTrue(execute("eat_food", ActionGraphExecutionState.REPLANNING, false, false, SurvivalReflexState.IDLE, true).contains("player_dead"));
		assertTrue(execute("eat_food", ActionGraphExecutionState.REPLANNING, false, true, SurvivalReflexState.IDLE, false).contains("item_use_active"));
		assertTrue(execute("start_action_goal", ActionGraphExecutionState.REPLANNING, false, true, SurvivalReflexState.IDLE, false).contains("item_use_active"));
		assertEquals("executed:inspect_inventory", execute("inspect_inventory", ActionGraphExecutionState.REPLANNING, false, true, SurvivalReflexState.IDLE, false));
	}

	private static String execute(String name, ActionGraphExecutionState phase, boolean taskBusy, boolean eating,
		SurvivalReflexState reflex, boolean dead) {
		var graph = new ActionGraphExecutionSnapshot(true, "graph-food-test", phase, null, ActionRoute.empty(),
			0, null, 0, 0, 0, "", "", "", "", Map.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of());
		var executor = new EmbodiedPlannerActionToolExecutor(
			() -> new EmbodiedPlannerActionToolExecutor.ExecutionState(reflex, dead, eating, taskBusy,
				ActiveJobType.SMELT_ITEMS, true, graph, TaskSnapshot.idle(), TaskExecutionSnapshot.idle()),
			args -> { throw new AssertionError("unexpected craft"); },
			call -> { throw new AssertionError("unexpected block edit"); },
			call -> "executed:" + call.name());
		return executor.execute(new PlannerToolCall("food-test", name, new JsonObject(), null)).join();
	}
}
