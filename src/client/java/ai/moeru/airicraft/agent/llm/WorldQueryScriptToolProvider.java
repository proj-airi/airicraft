package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.policy.GraalPolicyInvocation;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** Client-thread snapshot -> bounded guest query -> compact result and host-owned coverage. */
public final class WorldQueryScriptToolProvider implements PlannerToolProvider {
	private static final String GUIDANCE = PolicyDocsToolProvider.readResource("/prompts/planner-query-world.md");
	private final Executor clientExecutor;
	private final Function<JsonObject, WorldQuerySnapshot> capture;
	private final Consumer<WorldQuerySnapshot> observe;

	WorldQueryScriptToolProvider(Executor clientExecutor, Function<JsonObject, WorldQuerySnapshot> capture, Consumer<WorldQuerySnapshot> observe) {
		this.clientExecutor = clientExecutor;
		this.capture = capture;
		this.observe = observe;
	}

	public static WorldQueryScriptToolProvider forClient(LongSupplier serverTick, Consumer<List<BlockPos>> observed) {
		return new WorldQueryScriptToolProvider(command -> Minecraft.getInstance().execute(command),
			args -> WorldQuerySnapshot.capture(Minecraft.getInstance(), args, serverTick.getAsLong()),
			snapshot -> {
				if (Minecraft.getInstance().level != snapshot.world()) throw new IllegalStateException("world_changed");
				observed.accept(snapshot.observedPositions());
			});
	}

	@Override public String id() { return "world_query_script"; }
	@Override public boolean handles(String name) { return name.equals("query_world"); }
	@Override public String promptInstructions() { return GUIDANCE; }
	@Override public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider("query_world", "Run bounded read-only JavaScript over a fresh local world snapshot. "
			+ "Define function query(world,input) returning JSON. world has player, blocks, entities and metadata; see read_policy_docs for fields. "
			+ "Prefer for custom filtering/aggregation over repeated inspect_world or inspect_nearby_entities calls. No effects or host access. "
			+ "Returns result plus coverage metadata; loaded client state only, no route or full-world absence guarantee.",
			propertiesForProvider(
				propForProvider("source", stringForProvider("JavaScript defining function query(world,input); max 32768 characters.")),
				propForProvider("input", Map.of("type", "object", "description", "JSON query parameters; max 16384 characters.")),
				propForProvider("radius", Map.of("type", "integer", "minimum", 0, "maximum", 8, "description", "Horizontal box radius; default 4.")),
				propForProvider("verticalRadius", Map.of("type", "integer", "minimum", 0, "maximum", 4, "description", "Vertical box radius; default 2.")),
				propForProvider("center", Map.of("type", "object", "properties", Map.of("x", Map.of("type", "integer"), "y", Map.of("type", "integer"), "z", Map.of("type", "integer")),
					"required", List.of("x", "y", "z"), "additionalProperties", false, "description", "Default player block position; all corners must be within 64 blocks of player.")),
				propForProvider("includeBlocks", Map.of("type", "boolean", "description", "Capture blocks including air; default true.")),
				propForProvider("includeEntities", Map.of("type", "boolean", "description", "Capture up to 64 nearby entities excluding self; default true."))
			), List.of("source", "input")));
	}
	@Override public void validateArguments(String name, JsonObject args) {
		if (!handles(name)) throw new IllegalArgumentException("unknown_query_tool");
		if (!args.has("source") || !args.get("source").isJsonPrimitive() || !args.getAsJsonPrimitive("source").isString()
			|| args.get("source").getAsString().isBlank() || args.get("source").getAsString().length() > GraalPolicyInvocation.MAX_SOURCE_CHARS)
			throw new IllegalArgumentException("query_source_limit");
		if (!args.has("input") || !args.get("input").isJsonObject() || args.get("input").toString().length() > GraalPolicyInvocation.MAX_VALUE_CHARS)
			throw new IllegalArgumentException("query_input_limit");
		WorldQuerySnapshot.validateBounds(args);
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		return CompletableFuture.supplyAsync(() -> {
			validateArguments(call.name(), call.arguments());
			return capture.apply(call.arguments());
		}, clientExecutor).thenCompose(snapshot -> GraalPolicyInvocation.query(call.arguments().get("source").getAsString(),
			snapshot.data(), call.arguments().get("input")).thenApplyAsync(result -> {
				observe.accept(snapshot);
				if (result.isJsonPrimitive() && result.getAsJsonPrimitive().isString()) {
					return "Tool result for query_world:\n" + coverageText(snapshot.data().getAsJsonObject("metadata")) + "\n" + result.getAsString();
				}
				var response = new JsonObject();
				response.add("metadata", snapshot.data().get("metadata"));
				response.add("result", result);
				return "Tool result for query_world: " + response;
			}, clientExecutor)).exceptionally(error -> {
				Throwable cause = error;
				while (cause.getCause() != null) cause = cause.getCause();
				String message = cause.getMessage();
				return "TOOL_ERROR: query_world " + (message == null || message.isBlank() ? cause.getClass().getSimpleName() : message);
			});
	}
	/** Host-owned facts remain outside editable guest presentation. */
	private static String coverageText(JsonObject metadata) {
		var bounds = metadata.getAsJsonObject("bounds");
		var blocks = metadata.getAsJsonObject("blocks");
		var entities = metadata.getAsJsonObject("entities");
		return "Coverage: " + metadata.get("dimension").getAsString() + " tick=" + metadata.get("serverTick")
			+ " box=" + positionText(bounds.getAsJsonObject("min")) + ".." + positionText(bounds.getAsJsonObject("max"))
			+ "; blocks=" + blocks.get("returned") + "/" + blocks.get("requested")
			+ " unloaded=" + blocks.get("unloaded") + " outsideWorld=" + blocks.get("outsideWorld")
			+ " truncated=" + blocks.get("truncated")
			+ "; entities=" + (entities.get("included").getAsBoolean()
				? entities.get("returned") + "/" + entities.get("matched") + " truncated=" + entities.get("truncated") : "not captured");
	}
	private static String positionText(JsonObject p) {
		return p.get("x") + "," + p.get("y") + "," + p.get("z");
	}

}
