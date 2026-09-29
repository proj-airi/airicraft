package ai.moeru.airicraft.agent.spatial;

import ai.moeru.airicraft.agent.llm.*;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

public final class TravelPolicyToolProvider implements PlannerToolProvider {
	@Override public String id() { return "travel_policy"; }
	@Override public boolean handles(String name) { return name.equals("configure_travel") || name.equals("inspect_travel"); }
	@Override public boolean isReadTool(String name) { return name.equals("inspect_travel"); }
	@Override public List<Map<String,Object>> openAiTools() {
		var props = new LinkedHashMap<String,Object>();
		for (String key : List.of("minX","minY","minZ","maxX","maxY","maxZ")) props.put(key, Map.of("type","integer"));
		return List.of(toolForProvider("inspect_travel", "Inspect durable user and revisable strategy travel restrictions.", propertiesForProvider(), List.of()),
			toolForProvider("configure_travel", "Set explicit travel/edit bounds. User restrictions persist per dimension and cannot be relaxed by planner calls. Strategy bounds are revisable. Omit bounds only to clear strategy bounds. Does not alter acquisition search bounds.", propertiesForProvider(
				propForProvider("scope", Map.of("type","string","enum",List.of("user","strategy"))),
				propForProvider("bounds", Map.of("type","object","properties",props,"required",List.copyOf(props.keySet()),"additionalProperties",false))), List.of("scope")));
	}
	@Override public String promptInstructions() { return "Search constraints select resources, not routes. Use configure_travel only for actual travel restrictions. Record explicit user travel bounds with scope=user; your own search strategy must not become a durable user restriction. User bounds cannot be relaxed by planner tools; request user intervention if they need to change. Bounds describe all occupied cells including headroom, not just feet. These are conservative path eligibility checks, not recovery behavior."; }
	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		var result = new CompletableFuture<String>();
		Minecraft.getInstance().execute(() -> {
			try {
				if (call.name().equals("configure_travel")) {
					JsonObject args = call.arguments(); TravelBounds bounds = null;
					if (args.has("bounds")) bounds = new Gson().fromJson(args.get("bounds"), TravelBounds.class);
					WorldTravelPolicy.configure(Minecraft.getInstance(), args.get("scope").getAsString(), bounds);
				}
				result.complete("Tool result for " + call.name() + ": " + new Gson().toJson(WorldTravelPolicy.snapshot()));
			} catch (Exception e) { result.complete("TOOL_ERROR: " + call.name() + " " + e.getMessage()); }
		});
		return result;
	}
}
