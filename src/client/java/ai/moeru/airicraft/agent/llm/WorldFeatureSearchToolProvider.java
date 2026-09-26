package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class WorldFeatureSearchToolProvider implements PlannerToolProvider {
	public static final String FIND_WORLD_FEATURES = "find_world_features";

	private static final List<String> FEATURES = List.of("water_body", "forest");
	private static final List<String> DIRECTIONS = List.of(
		"north",
		"northeast",
		"east",
		"southeast",
		"south",
		"southwest",
		"west",
		"northwest"
	);

	private final WorldFeatureSearchTool searchTool;
	private final Consumer<WorldFeatureSearchService.WorldFeatureSearchResult> resultObserver;

	public WorldFeatureSearchToolProvider(WorldFeatureSearchTool searchTool) {
		this(searchTool, ignored -> {
		});
	}

	public WorldFeatureSearchToolProvider(
		WorldFeatureSearchTool searchTool,
		Consumer<WorldFeatureSearchService.WorldFeatureSearchResult> resultObserver
	) {
		this.searchTool = Objects.requireNonNull(searchTool, "searchTool");
		this.resultObserver = Objects.requireNonNull(resultObserver, "resultObserver");
	}

	@Override
	public String id() {
		return "world_feature_search";
	}

	@Override
	public List<Map<String, Object>> openAiTools() {
		return List.of(PlannerToolCatalog.toolForProvider(
			FIND_WORLD_FEATURES,
			"Find coordinate-grounded world features in already-loaded chunks. V1 featureKind values are water_body and forest.",
			PlannerToolCatalog.propertiesForProvider(
				PlannerToolCatalog.propForProvider("featureKind", PlannerToolCatalog.enumStringForProvider("Feature kind to find.", FEATURES)),
				PlannerToolCatalog.propForProvider("surfaceOnly", Map.of("type", "boolean", "description", "Restrict target and standing positions to the surface, ignoring logs/leaves as roofs. Useful for surface water, farms and camps. Default false.")),
				PlannerToolCatalog.propForProvider("direction", PlannerToolCatalog.enumStringForProvider("Optional compass direction octant to search.", DIRECTIONS)),
				PlannerToolCatalog.propForProvider("maxDistanceBlocks", Map.of("type", "integer", "description", "Optional search distance in blocks. Default 192, maximum 256.")),
				PlannerToolCatalog.propForProvider("limit", Map.of("type", "integer", "description", "Optional result limit. Default 3, maximum 8.")),
				PlannerToolCatalog.propForProvider("minConnectedWaterSources", Map.of("type", "integer", "description", "Optional minimum connected water source blocks for water_body. Default 8.")),
				PlannerToolCatalog.propForProvider("minTreeCount", Map.of("type", "integer", "description", "Optional minimum tree stems for forest. Default 6."))
			),
			List.of("featureKind")
		));
	}

	@Override
	public String promptInstructions() {
		return """
			Use find_world_features when you need coordinate-grounded exploration targets beyond inspect_world radius, such as a water_body or forest. It returns exact centerPos, targetPos, standPos, distanceBlocks, direction, evidence, and confidence from already-loaded chunks only.
			For bucket filling, call find_world_features with featureKind=water_body and optional direction, navigate_to standPos, then use_block with itemId=minecraft:bucket on targetPos.
			Use surfaceOnly=true when seeking surface water or forest access for a farm or camp; water bodies may otherwise include connected underground sources.
			For forests, call find_world_features with featureKind=forest and optional direction, then navigate_to standPos or centerPos before gathering logs.
			If find_world_features returns no candidates, move or look toward the likely area and call it again; do not invent coordinates from vision alone.
			""";
	}

	@Override
	public boolean handles(String toolName) {
		return FIND_WORLD_FEATURES.equals(PlannerToolCatalog.normalizeName(toolName));
	}

	@Override
	public boolean isReadTool(String toolName) {
		return handles(toolName);
	}

	@Override
	public void validateArguments(String toolName, JsonObject arguments) {
		if (!handles(toolName)) {
			throw new JsonParseException("Unknown world feature search tool: " + toolName);
		}
		String featureKind = stringArg(arguments, "featureKind");
		if (featureKind == null || !FEATURES.contains(featureKind)) {
			throw new JsonParseException("featureKind must be one of " + FEATURES);
		}
		String direction = stringArg(arguments, "direction");
		if (direction != null && !DIRECTIONS.contains(direction)) {
			throw new JsonParseException("direction must be one of " + DIRECTIONS);
		}
		validateInt(arguments, "maxDistanceBlocks");
		validateInt(arguments, "limit");
		validateInt(arguments, "minConnectedWaterSources");
		validateInt(arguments, "minTreeCount");
		if (arguments.has("surfaceOnly") && (!arguments.get("surfaceOnly").isJsonPrimitive()
			|| !arguments.getAsJsonPrimitive("surfaceOnly").isBoolean())) {
			throw new JsonParseException("surfaceOnly must be a boolean");
		}
	}

	@Override
	public CompletableFuture<String> execute(PlannerToolCall toolCall) {
		return searchTool.findFeaturesDetailed(toolCall == null ? null : toolCall.arguments())
			.thenApply(result -> {
				resultObserver.accept(result);
				return result.text();
			});
	}

	private static void validateInt(JsonObject args, String key) {
		if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
			return;
		}
		try {
			args.get(key).getAsInt();
		}
		catch (RuntimeException exception) {
			throw new JsonParseException(key + " must be an integer", exception);
		}
	}

	private static String stringArg(JsonObject args, String key) {
		if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
			return null;
		}
		String value = args.get(key).getAsString();
		return value == null || value.isBlank() ? null : value;
	}
}
