package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.baritone.BaritonePathfindSettings;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** Live readback; cached tool schemas cannot describe mutable runtime settings. */
public final class PathfindSettingsToolProvider implements PlannerToolProvider {
	private static final List<String> DEFAULT_NAMES = List.of("allowBreak", "allowPlace", "allowParkour",
		"allowWaterBucketFall", "maxFallHeightNoWater", "maxFallHeightBucket", "walkOnWaterOnePenalty");

	@Override public String id() { return "pathfind_settings"; }
	@Override public boolean handles(String name) { return "inspect_pathfind".equals(normalizeName(name)); }
	@Override public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider("inspect_pathfind", "Read current settings by names (up to 32), or search documentation by query (up to 16 matches). Omit both for movement safety values.",
			propertiesForProvider(propForProvider("names", Map.of("type", "array", "items", Map.of("type", "string"),
				"minItems", 1, "maxItems", 32)), propForProvider("query", stringForProvider("Setting name or description fragment, e.g. parkour or fall."))), List.of()));
	}
	@Override public String promptInstructions() {
		return "Use inspect_pathfind for actual current values before changing movement constraints and after interruptions. Tool schemas contain defaults, not live state. Save prior values and restore them when leaving a constrained exploration intent.";
	}
	@Override public void validateArguments(String name, JsonObject args) {
		for (String key : args.keySet()) if (!List.of("names", "query").contains(key)) throw new JsonParseException("unknown argument: " + key);
		if (args.has("query")) {
			if (args.has("names") || !args.get("query").isJsonPrimitive() || !args.getAsJsonPrimitive("query").isString()
				|| args.get("query").getAsString().isBlank()) throw new JsonParseException("supply query or names");
		} else names(args);
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		var result = new CompletableFuture<String>();
		Minecraft.getInstance().execute(() -> {
			try {
				validateArguments(call.name(), call.arguments());
				result.complete("Tool result for inspect_pathfind: " + new Gson().toJson(call.arguments().has("query")
					? BaritonePathfindSettings.describeSettings(call.arguments().get("query").getAsString()) : BaritonePathfindSettings.inspect(names(call.arguments()))));
			}
			catch (RuntimeException exception) { result.complete("TOOL_ERROR: inspect_pathfind " + exception.getMessage()); }
		});
		return result;
	}
	static List<String> names(JsonObject args) {
		if (!args.has("names")) return DEFAULT_NAMES;
		if (!args.get("names").isJsonArray()) throw new JsonParseException("names must be an array");
		var values = args.getAsJsonArray("names");
		if (values.isEmpty() || values.size() > 32) throw new JsonParseException("names must contain 1..32 setting names");
		List<String> names = new ArrayList<>();
		for (var value : values) {
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
				throw new JsonParseException("each setting name must be a nonempty string");
			names.add(value.getAsString());
		}
		return List.copyOf(names);
	}
}
