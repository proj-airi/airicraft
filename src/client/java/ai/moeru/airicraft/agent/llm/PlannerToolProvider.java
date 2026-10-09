package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface PlannerToolProvider {
	String id();

	/** Cancel provider-owned background work on planner reset or shutdown. */
	default void reset() {}

	default boolean dynamicTools() { return false; }

	default boolean available() {
		return true;
	}

	List<Map<String, Object>> openAiTools();

	default String promptInstructions() {
		return "";
	}

	/** Current facts are appended to the conversation, never embedded in system instructions. */
	default String contextSnapshot() { return ""; }

	/** Commit this tool result and yield without another model call. */
	default boolean endsTurn(String toolName) { return false; }

	/** Run after a successful terminal tool receipt has been committed to the journal. */
	default void afterResultCommitted(String toolName) {}

	boolean handles(String toolName);

	default boolean isReadTool(String toolName) {
		return true;
	}

	default boolean isBatchSafeReadTool(String toolName) {
		return isReadTool(toolName);
	}

	default void validateArguments(String toolName, JsonObject arguments) {
	}

	CompletableFuture<String> execute(PlannerToolCall toolCall);

	default CompletableFuture<PlannerProviderToolResult> executeResult(PlannerToolCall toolCall) {
		return execute(toolCall).thenApply(PlannerProviderToolResult::text);
	}
}
