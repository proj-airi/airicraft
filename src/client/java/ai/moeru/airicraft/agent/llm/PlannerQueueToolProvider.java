package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

public final class PlannerQueueToolProvider implements PlannerToolProvider {
	public static final String CONTINUE = "continue", CLEAR = "clear_queue", REPORT = "report_to_me";
	private final PlannerActionToolExecutor executor;
	public PlannerQueueToolProvider(PlannerActionToolExecutor executor) { this.executor = executor; }
	public String id() { return "tool_queue"; }
	public boolean isReadTool(String name) { return !CLEAR.equals(name); }
	public boolean handles(String name) { return CONTINUE.equals(name) || CLEAR.equals(name) || REPORT.equals(name); }
	public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider(CONTINUE, "Keep the current plan and skip this decision turn. Resume paused work if its safety reflex has released control; never override an active reflex.", propertiesForProvider(), List.of()),
			toolForProvider(CLEAR, "Immediately discard pending tool calls and abort current running work. New calls in this response form a replacement plan. Completed effects are not undone.", propertiesForProvider(), List.of()),
			toolForProvider(REPORT, "Queued checkpoint: wake the planner with completed results, without stopping execution. Omit includeTools to include all buffered outputs; FIFO empty also requests a review.", propertiesForProvider(
				propForProvider("question", stringForProvider("What to assess or decide when this checkpoint is reached.")),
				propForProvider("includeTools", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 32, "description", "Names of tools whose buffered raw outputs to include. Omit for all outputs. Other outcomes are acknowledged without raw output."))), List.of()));
	}
	public void validateArguments(String name, JsonObject arguments) {
		for (String key : arguments.keySet()) {
			if (!REPORT.equals(name) || !List.of("question", "includeTools").contains(key)) throw new IllegalArgumentException("unknown report argument: " + key);
		}
		if (arguments.has("question") && (!arguments.get("question").isJsonPrimitive() || !arguments.getAsJsonPrimitive("question").isString()))
			throw new IllegalArgumentException("question must be a string");
		if (arguments.has("includeTools")) {
			if (!arguments.get("includeTools").isJsonArray() || arguments.getAsJsonArray("includeTools").size() > 32) throw new IllegalArgumentException("includeTools must be an array of at most 32 tool names");
			for (var value : arguments.getAsJsonArray("includeTools")) if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) throw new IllegalArgumentException("includeTools requires nonempty tool names");
		}
	}
	public CompletableFuture<String> execute(PlannerToolCall call) {
		validateArguments(call.name(), call.arguments());
		return REPORT.equals(call.name()) ? CompletableFuture.completedFuture("Report checkpoint reached; execution continues.") : executor.execute(call);
	}
	public String promptInstructions() {
		return "Plan ahead by returning multiple tool calls in intended order. Calls append to a FIFO and execute sequentially, "
			+ "waiting for actual work completion. Execution continues while you think. Routine reviews happen only when a queued report_to_me completes or the FIFO empties. Insert report_to_me with question and optional includeTools to choose where to think and which raw results you need. User messages and urgent safety events can still interrupt. "
			+ "TOOL QUEUE shows active and pending calls at request time; it may advance before your reply. "
			+ "Use continue to retain the plan and resume work after a resolved safety hold, or clear_queue to discard pending calls AND abort active work before a replacement plan. "
			+ "New calls append behind existing pending calls. Only queue calls whose arguments are already known; do not invent outputs of earlier queries.";
	}
}
