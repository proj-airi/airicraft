package ai.moeru.airicraft.agent.memory;

import ai.moeru.airicraft.agent.llm.*;
import ai.moeru.airicraft.memory.InteractionLogbook;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

public final class InteractionLogbookToolProvider implements PlannerToolProvider {
	@Override public String id() { return "interaction_logbook"; }
	@Override public boolean handles(String name) { return "read_logbook".equals(normalizeName(name)); }
	@Override public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider("read_logbook",
			"Read significant server-observed interactions persisted in this world: crafting, drops, block/entity container put/take and last observed contents. Entity container records include stable containerEntityUuid and coordinates at interaction time. Historical observations, not guaranteed current stock. No write/delete capability.",
			propertiesForProvider(
				propForProvider("itemId", optionalStringForProvider("Optional exact item ID, matching transfers or observed contents.")),
				propForProvider("action", enumStringForProvider("Optional event filter.", List.of("crafted", "dropped", "container_put", "container_take", "container_observed"))),
				propForProvider("place", optionalStringForProvider("Optional exact location name from the active location-memory backend. Ambiguous names require placeId.")),
				propForProvider("placeId", optionalStringForProvider("Optional stable location ID instead of place.")),
				propForProvider("radius", Map.of("type", "integer", "description", "Radius around place, default 16, 0..128.")),
				propForProvider("limit", Map.of("type", "integer", "description", "Most recent matching entries, default 20, 1..100."))
			), List.of()));
	}
	@Override public void validateArguments(String name, JsonObject args) {
		for (String key : List.of("itemId", "action", "place", "placeId")) {
			if (args.has(key) && (!args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isString()))
				throw new JsonParseException(key + " must be a string");
		}
		if (args.has("action") && !List.of("crafted", "dropped", "container_put", "container_take", "container_observed").contains(args.get("action").getAsString()))
			throw new JsonParseException("unknown logbook action");
		integer(args, "limit", 20, 1, 100);
		integer(args, "radius", 16, 0, 128);
		if (args.has("place") && args.has("placeId")) throw new JsonParseException("supply either place or placeId");
		if (args.has("radius") && !args.has("place") && !args.has("placeId")) throw new JsonParseException("radius requires place or placeId");
	}
	@Override public String promptInstructions() {
		return "Before a long supply trip, inspect inventory and use containers to store surplus while keeping tools, shield, food, torches and building blocks. Open a known chest/barrel with use_block. For chest minecarts or chest boats, inspect_nearby_entities, copy uuid and use_entity to approach/open; do not break the vehicle to access loot. Then inspect_container, transfer_container with its exact syncId, inspect_container again to verify settled counts and close_container before other work. read_logbook retrieves automatic world-persistent history; query a remembered place and item before reacquiring supplies. Entity containers retain identity by containerEntityUuid when they move; coordinates describe the recorded interaction location. Container contents are last-seen stock at worldTick, not current truth: reopen and inspect before relying on them. Logbook entries cannot be written or deleted by planner tools.";
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		Minecraft minecraft = Minecraft.getInstance();
		CompletableFuture<String> result = new CompletableFuture<>();
		minecraft.execute(() -> {
			try {
				if (minecraft.level == null || minecraft.player == null || minecraft.getSingleplayerServer() == null)
					throw new IllegalStateException("logbook requires a locally hosted world save");
				var directory = minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
				String actor = minecraft.player.getStringUUID();
				JsonObject args = call.arguments();
				String item = text(args, "itemId");
				var itemHistory = InteractionLogbook.matchingItemHistory(item);
				String action = text(args, "action");
				String place = text(args, "place");
				String placeId = text(args, "placeId");
				LocationMemoryProvider.Location center = place.isEmpty() && placeId.isEmpty() ? null
					: LocationMemoryBridge.forClient(minecraft).recall(placeId.isEmpty() ? null : placeId, place.isEmpty() ? null : place);
				int radius = integer(args, "radius", 16, 0, 128);
				InteractionLogbook.query(directory, entry -> entry.actor().equals(actor)
					&& itemHistory.test(entry)
					&& (action.isEmpty() || entry.action().equals(action))
					&& (center == null || entry.dimension().equals(center.dimension())
						&& Math.pow(entry.x() - center.x(), 2) + Math.pow(entry.y() - center.y(), 2) + Math.pow(entry.z() - center.z(), 2) <= radius * radius),
					integer(args, "limit", 20, 1, 100)).whenComplete((entries, failure) -> {
						if (failure != null) result.complete("TOOL_ERROR: read_logbook " + failure.getMessage());
						else result.complete("Tool result for read_logbook: historical=true entries=" + new Gson().toJson(entries));
					});
			} catch (Exception exception) { result.complete("TOOL_ERROR: read_logbook " + exception.getMessage()); }
		});
		return result;
	}
	private static String text(JsonObject args, String key) { return args.has(key) ? args.get(key).getAsString() : ""; }
	private static int integer(JsonObject args, String key, int fallback, int min, int max) {
		if (!args.has(key)) return fallback;
		try {
			int value = args.get(key).getAsBigDecimal().intValueExact();
			if (!args.getAsJsonPrimitive(key).isNumber() || value < min || value > max) throw new ArithmeticException();
			return value;
		} catch (RuntimeException exception) { throw new JsonParseException(key + " must be integer " + min + ".." + max); }
	}
}
