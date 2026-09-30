package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.navigation.PathfindSettings;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** Live readback; cached tool schemas cannot describe mutable runtime settings. */
public final class PathfindSettingsToolProvider implements PlannerToolProvider {
	@Override public String id() { return "pathfind_settings"; }
	@Override public boolean handles(String name) { return "inspect_pathfind".equals(normalizeName(name)); }
	@Override public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider("inspect_pathfind", "Read the current movement policy: every navigation setting with its live value, default and meaning.",
			propertiesForProvider(), List.of()));
	}
	@Override public String promptInstructions() {
		return "Use inspect_pathfind for actual current values before changing movement constraints and after interruptions. Tool schemas contain defaults, not live state. Save prior values and restore them when leaving a constrained exploration intent.";
	}
	@Override public void validateArguments(String name, JsonObject args) {
		if (args != null && !args.isEmpty()) throw new JsonParseException("inspect_pathfind takes no arguments");
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		var result = new CompletableFuture<String>();
		Minecraft.getInstance().execute(() -> {
			try {
				validateArguments(call.name(), call.arguments());
				result.complete("Tool result for inspect_pathfind: " + new Gson().toJson(PathfindSettings.inspect()));
			}
			catch (RuntimeException exception) { result.complete("TOOL_ERROR: inspect_pathfind " + exception.getMessage()); }
		});
		return result;
	}
}
