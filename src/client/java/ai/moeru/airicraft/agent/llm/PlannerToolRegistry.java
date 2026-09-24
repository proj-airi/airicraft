package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class PlannerToolRegistry {
	private final List<PlannerToolProvider> providers;
	private final boolean includeNativeTools;
	private List<Map<String, Object>> fixedTools;
	private String fixedInstructions;
	private PlannerReferences references = new PlannerReferences();
	public PlannerReferences references() { return references; }
	public void shareReferences(PlannerToolRegistry other) { references = other.references; }

	/** Freeze native schemas for one model session; self tools remain dynamic. */
	public void freezeToolPrefix() {
		fixedTools = availableOpenAiTools().stream().filter(tool -> !isDynamic(toolName(tool))).toList();
		fixedInstructions = providers.stream().filter(PlannerToolProvider::available)
			.map(PlannerToolProvider::promptInstructions).filter(value -> !value.isBlank())
			.collect(Collectors.joining("\n"));
	}

	private boolean isDynamic(String name) {
		return providers.stream().anyMatch(p -> p.dynamicTools() && p.handles(name));
	}

	public boolean hasFixedPrefix() { return fixedTools != null; }

	public boolean endsTurn(String name) {
		return providers.stream().anyMatch(provider -> provider.handles(name) && provider.endsTurn(name));
	}

	public void afterResultCommitted(String name) {
		providers.stream().filter(provider -> provider.handles(name) && provider.endsTurn(name))
			.forEach(provider -> provider.afterResultCommitted(name));
	}

	public String contextSnapshot() {
		return providers.stream().filter(PlannerToolProvider::available)
			.map(PlannerToolProvider::contextSnapshot).filter(value -> !value.isBlank())
			.collect(Collectors.joining("\n"));
	}

	private PlannerToolRegistry(List<PlannerToolProvider> providers) { this(providers, true); }

	private PlannerToolRegistry(List<PlannerToolProvider> providers, boolean includeNativeTools) {
		this.providers = List.copyOf(providers);
		this.includeNativeTools = includeNativeTools;
	}

	/** A proposal backend must not inherit the normal planner's gameplay tool catalog. */
	public static PlannerToolRegistry isolated(PlannerToolProvider provider) { return new PlannerToolRegistry(List.of(provider), false); }

	public static PlannerToolRegistry noTools() { return new PlannerToolRegistry(List.of(), false); }

	public static PlannerToolRegistry empty() {
		return new PlannerToolRegistry(List.of());
	}

	public static PlannerToolRegistry of(PlannerToolProvider... providers) {
		if (providers == null || providers.length == 0) {
			return empty();
		}
		return new PlannerToolRegistry(Arrays.stream(providers)
			.filter(Objects::nonNull)
			.toList());
	}

	public List<Map<String, Object>> openAiTools() {
		var tools = new ArrayList<>(fixedTools == null ? availableOpenAiTools() : fixedTools);
		tools.removeIf(tool -> isDynamic(toolName(tool)));
		providers.stream().filter(p -> p.available() && p.dynamicTools()).forEach(p -> tools.addAll(p.openAiTools()));
		return List.copyOf(tools);
	}

	public Optional<Map<String, Object>> activeOpenAiTool(String toolName) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		return openAiTools().stream()
			.filter(tool -> normalized.equals(PlannerToolCatalog.normalizeName(toolName(tool))))
			.findFirst();
	}

	public List<Map<String, Object>> allAvailableOpenAiTools() {
		return availableOpenAiTools();
	}

	public List<String> activeToolNames() {
		return availableToolNames(openAiTools());
	}

	public boolean isActiveTool(String toolName) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		return availableToolNames(openAiTools()).contains(normalized);
	}

	private List<Map<String, Object>> availableOpenAiTools() {
		ArrayList<Map<String, Object>> tools = new ArrayList<>(includeNativeTools ? PlannerToolCatalog.openAiTools() : List.of());
		for (PlannerToolProvider provider : providers) {
			if (provider.available()) {
				tools.addAll(provider.openAiTools());
			}
		}
		if (providers.stream().anyMatch(provider -> provider.id().equals("work"))) {
			tools.removeIf(tool -> List.of("list_action_goals", "inspect_action_goal", "cancel_action_goal",
				"cancel_task", "clear_goal", "resume_task", "cancel_smelting").contains(toolName(tool)));
		}
		return List.copyOf(tools);
	}

	public String promptInstructions() {
		if (fixedInstructions != null) return fixedInstructions;
		return providers.stream()
			.filter(PlannerToolProvider::available)
			.map(PlannerToolProvider::promptInstructions)
			.filter(instruction -> instruction != null && !instruction.isBlank())
			.collect(Collectors.joining("\n"));
	}

	private static List<String> availableToolNames(Collection<Map<String, Object>> tools) {
		return tools.stream()
			.map(PlannerToolRegistry::toolName)
			.filter(name -> !name.isBlank())
			.map(PlannerToolCatalog::normalizeName)
			.toList();
	}

	public boolean isKnownTool(String toolName) {
		return executionMetadata(toolName).isPresent();
	}

	public boolean isReadTool(String toolName) {
		return executionMetadata(toolName)
			.map(ToolExecutionMetadata::readOnly)
			.orElse(false);
	}

	public boolean isBatchSafeReadTool(String toolName) {
		return executionMetadata(toolName)
			.map(ToolExecutionMetadata::batchSafeRead)
			.orElse(false);
	}

	public ReadOnlyBatchAuthorization authorizeReadOnlyBatch(List<PlannerToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return ReadOnlyBatchAuthorization.reject(BatchRejectionReason.UNKNOWN_TOOL, "");
		}
		for (PlannerToolCall toolCall : toolCalls) {
			String toolName = PlannerToolCatalog.normalizeName(toolCall == null ? null : toolCall.name());
			Optional<ToolExecutionMetadata> metadata = executionMetadata(toolName);
			if (metadata.isEmpty()) {
				return ReadOnlyBatchAuthorization.reject(BatchRejectionReason.UNKNOWN_TOOL, toolName);
			}
			if (!metadata.get().readOnly() || !metadata.get().batchSafeRead()) {
				return ReadOnlyBatchAuthorization.reject(BatchRejectionReason.NOT_BATCH_SAFE, toolName);
			}
		}
		return ReadOnlyBatchAuthorization.allow();
	}

	public Optional<ToolExecutionMetadata> executionMetadata(String toolName) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		if (PlannerToolCatalog.isKnownTool(normalized)) {
			return Optional.of(new ToolExecutionMetadata(
				normalized,
				true,
				PlannerToolCatalog.isReadTool(normalized),
				PlannerToolCatalog.isBatchSafeReadTool(normalized)
			));
		}
		return providerFor(normalized)
			.map(provider -> new ToolExecutionMetadata(
				normalized,
				true,
				provider.isReadTool(normalized),
				provider.isBatchSafeReadTool(normalized)
			));
	}

	public Optional<PlannerToolProvider> providerFor(String toolName) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		return providers.stream()
			.filter(provider -> provider.handles(normalized))
			.findFirst();
	}

	public void validateProviderArguments(String toolName, JsonObject arguments) {
		providerFor(toolName)
			.orElseThrow(() -> new com.google.gson.JsonParseException("Unknown planner tool: " + toolName))
			.validateArguments(toolName, arguments);
	}

	public CompletableFuture<String> execute(PlannerToolCall toolCall) {
		return providerFor(toolCall == null ? null : toolCall.name())
			.orElseThrow(() -> new IllegalArgumentException("No provider for tool: " + (toolCall == null ? "null" : toolCall.name())))
			.execute(toolCall);
	}

	private static String toolName(Map<String, Object> tool) {
		Object function = tool == null ? null : tool.get("function");
		if (!(function instanceof Map<?, ?> functionMap)) {
			return "";
		}
		Object name = functionMap.get("name");
		return name instanceof String string ? string : "";
	}

	public enum BatchRejectionReason {
		UNKNOWN_TOOL,
		NOT_BATCH_SAFE
	}

	public record ToolExecutionMetadata(
		String name,
		boolean registered,
		boolean readOnly,
		boolean batchSafeRead
	) {
		public ToolExecutionMetadata {
			name = PlannerToolCatalog.normalizeName(name);
		}
	}

	public record ReadOnlyBatchAuthorization(
		boolean authorized,
		BatchRejectionReason rejectionReason,
		String toolName
	) {
		public ReadOnlyBatchAuthorization {
			toolName = PlannerToolCatalog.normalizeName(toolName);
		}

		private static ReadOnlyBatchAuthorization allow() {
			return new ReadOnlyBatchAuthorization(true, null, "");
		}

		private static ReadOnlyBatchAuthorization reject(BatchRejectionReason reason, String toolName) {
			return new ReadOnlyBatchAuthorization(false, reason, toolName);
		}
	}
}
