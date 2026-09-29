package ai.moeru.airicraft.agent.llm.goal;

import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

public final class PlannerGoalToolProvider implements PlannerToolProvider {
	private final PlannerGoalStore store;
	private final Executor clientExecutor;
	private final boolean controller;
	private final java.util.function.BooleanSupplier ownsDecisions;

	public PlannerGoalToolProvider(PlannerGoalStore store, Executor clientExecutor) {
		this(store, clientExecutor, true, () -> true);
	}

	public PlannerGoalToolProvider(PlannerGoalStore store, Executor clientExecutor, boolean controller, java.util.function.BooleanSupplier ownsDecisions) {
		this.controller = controller;
		this.ownsDecisions = ownsDecisions;
		this.store = store;
		this.clientExecutor = clientExecutor;
	}

	@Override public String id() { return "planner_goal"; }
	@Override public boolean handles(String name) {
		return name.equals("inspect_planner_goal") || controller && List.of("set_planner_goal", "change_planner_goal", "finish_planner_goal", "block_planner_goal", "resume_planner_goal", "record_decision").contains(name);
	}
	@Override public boolean isReadTool(String name) { return "inspect_planner_goal".equals(name); }

	@Override public List<Map<String, Object>> openAiTools() {
		var objective = propForProvider("objective", stringForProvider("Concrete longer-term objective, durable constraints and observable completion conditions, up to 2048 characters. Do not store current inventory, health, or temporary progress as facts in the objective."));
		var id = propForProvider("goalId", stringForProvider("Exact current planner goal id."));
		var tools = List.of(
			toolForProvider("set_planner_goal", "Start a persistent planner goal. Requires no active planner goal. Jobs are individual steps toward this objective.", propertiesForProvider(objective, propForProvider("constraints", optionalStringForProvider("Durable user restrictions; empty if none.")), propForProvider("completionCriteria", optionalStringForProvider("Observable completion conditions."))), List.of("objective")),
			toolForProvider("change_planner_goal", "Replace the active objective, preserving the reason. Returns a new goal id. Does not cancel a running action; cancel that separately when necessary.", propertiesForProvider(id, objective, propForProvider("reason", stringForProvider("Why the objective changed."))), List.of("goalId", "objective", "reason")),
			toolForProvider("finish_planner_goal", "End the planner goal explicitly with verified success or a reason for giving up. Stops automatic goal continuation; does not cancel a running action.", propertiesForProvider(id,
				propForProvider("status", Map.of("type", "string", "enum", List.of("success", "give_up"))),
				propForProvider("outcome", stringForProvider("Concrete completion evidence, or why progress cannot continue. Up to 2048 characters."))), List.of("goalId", "status", "outcome")),
			toolForProvider("block_planner_goal", "Pause goal continuation until a relevant event or user guidance permits reassessment. A failed attempt alone is not a blocked objective.", propertiesForProvider(id,
				propForProvider("reason", stringForProvider("Why the objective cannot currently progress.")),
				propForProvider("evidence", stringForProvider("Supporting work/event identities and observed facts.")),
				propForProvider("requiredChange", stringForProvider("What must change before reconsideration.")),
				propForProvider("reconsiderEvents", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 8))), List.of("goalId", "reason", "evidence", "requiredChange", "reconsiderEvents")),
			toolForProvider("resume_planner_goal", "Explicitly resume a blocked objective after evaluating fresh evidence.", propertiesForProvider(id, propForProvider("reason", stringForProvider("Observed change that permits another attempt."))), List.of("goalId", "reason")),
			toolForProvider("record_decision", "Store or replace one of at most 16 named planning decisions. Never store inventory or geometry observations here. Replacement retains the preceding reason.", propertiesForProvider(id,
				propForProvider("name", stringForProvider("Stable decision name, at most 64 characters.")), propForProvider("decision", stringForProvider("Chosen strategy or design.")), propForProvider("reason", stringForProvider("Why this choice replaces the previous one."))), List.of("goalId", "name", "decision", "reason")),
			toolForProvider("inspect_planner_goal", "Read the current world-persisted planner objective, identity, status, and outcome.", propertiesForProvider(), List.of())
		);
		return controller ? tools : tools.stream().filter(tool -> ((Map<?,?>) tool.get("function")).get("name").equals("inspect_planner_goal")).toList();
	}

	@Override public String promptInstructions() {
		if (!controller) return "Only the controller owns the overall objective. Inspect its constraints; finish your assignment with return_control. Never create, change, block, resume or finish the overall objective.";
		return "For autonomous work set an objective with separate constraints and completion criteria. Plaintext, failed work and tool-budget checkpoints yield; they do not end the objective. "
			+ "Ongoing attempts automatically yield until a meaningful event. Block an objective only when no useful attempt can progress, recording evidence and the change needed. Blocked objectives do not retry on idle ticks. "
			+ "Only finish with observed success or an explicit decision to give up. User stop means cancel work and finish give_up. Never silently reactivate a finished objective. "
			+ "record_decision stores strategy, not facts about current supplies or geometry. Those require fresh observations.";
	}

	@Override public String contextSnapshot() { return "Current planner goal (stored intent; any inventory, health or progress claims are historical, not current observations): " + store.context(); }

	@Override public void validateArguments(String name, JsonObject args) {
		List<String> fields = switch (name) {
			case "set_planner_goal" -> List.of("objective");
			case "block_planner_goal" -> List.of("goalId", "reason", "evidence", "requiredChange", "reconsiderEvents");
			case "resume_planner_goal" -> List.of("goalId", "reason");
			case "record_decision" -> List.of("goalId", "name", "decision", "reason");
			case "change_planner_goal" -> List.of("goalId", "objective", "reason");
			case "finish_planner_goal" -> List.of("goalId", "status", "outcome");
			case "inspect_planner_goal" -> List.of();
			default -> throw new JsonParseException("unknown planner goal tool");
		};
		for (String key : args.keySet()) if (!fields.contains(key) && !(name.equals("set_planner_goal") && List.of("constraints", "completionCriteria").contains(key))) throw new JsonParseException("unknown argument: " + key);
		for (String key : fields) if (key.equals("reconsiderEvents")) {
			if (!args.has(key) || !args.get(key).isJsonArray() || args.getAsJsonArray(key).size() > 8) throw new JsonParseException("reconsiderEvents must be an array of at most 8 event types");
		} else text(args, key);
		if (name.equals("finish_planner_goal") && !List.of("success", "give_up").contains(text(args, "status"))) throw new JsonParseException("status must be success or give_up");
	}

	private static String optionalText(JsonObject args, String name) { return args.has(name) ? args.get(name).getAsString() : ""; }
	private static String text(JsonObject args, String name) {
		if (!args.has(name) || !args.get(name).isJsonPrimitive() || !args.getAsJsonPrimitive(name).isString()) throw new JsonParseException("missing string: " + name);
		try { return PlannerGoalStore.checked(args.get(name).getAsString(), name); }
		catch (IllegalArgumentException exception) { throw new JsonParseException(exception.getMessage()); }
	}

	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				var args = call.arguments();
				validateArguments(call.name(), args);
				if (!call.name().equals("inspect_planner_goal") && (!controller || !ownsDecisions.getAsBoolean())) throw new IllegalStateException("objective_control_requires_controller_ownership");
				store.refreshWorld();
				switch (call.name()) {
					case "set_planner_goal" -> store.set(text(args, "objective"), optionalText(args, "constraints"), optionalText(args, "completionCriteria"));
					case "block_planner_goal" -> store.block(text(args, "goalId"), text(args, "reason"), text(args, "evidence"), text(args, "requiredChange"), java.util.stream.StreamSupport.stream(args.getAsJsonArray("reconsiderEvents").spliterator(), false).map(com.google.gson.JsonElement::getAsString).toList());
					case "resume_planner_goal" -> store.resume(text(args, "goalId"), text(args, "reason"));
					case "record_decision" -> store.decide(text(args, "goalId"), text(args, "name"), text(args, "decision"), text(args, "reason"));
					case "change_planner_goal" -> store.change(text(args, "goalId"), text(args, "objective"), text(args, "reason"));
					case "finish_planner_goal" -> store.finish(text(args, "goalId"), text(args, "status").equals("success") ? PlannerGoalStore.Status.SUCCEEDED : PlannerGoalStore.Status.GIVEN_UP, text(args, "outcome"));
					case "inspect_planner_goal" -> { }
					default -> throw new IllegalArgumentException("unknown planner goal tool");
				}
				return "Tool result for " + call.name() + ": " + store.context();
			}
			catch (IOException | RuntimeException exception) { return "TOOL_ERROR: " + call.name() + ": " + exception.getMessage(); }
		}, clientExecutor);
	}
}
