package ai.moeru.airicraft.agent.tasks;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Inclusive destination blocks. Completion concerns the animals, not the player's arrival. */
public record LureEntitiesStepArgs(List<String> uuids, String itemId, int x1, int y1, int z1, int x2, int y2, int z2) {
	public LureEntitiesStepArgs {
		uuids = List.copyOf(uuids).stream().map(s -> s.replace("-", "").toLowerCase(Locale.ROOT)).toList();
		if (uuids.isEmpty() || uuids.size() > 8 || uuids.stream().distinct().count() != uuids.size()
			|| uuids.stream().anyMatch(s -> !s.matches("[0-9a-f]{8,32}")))
			throw new IllegalArgumentException("uuids must contain 1..8 distinct observed UUID tokens (at least 8 hex digits)");
		if (itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
			throw new IllegalArgumentException("itemId must be namespaced");
		if (x1 > x2 || y1 > y2 || z1 > z2 || (long)x2 - x1 >= 16 || (long)y2 - y1 >= 8 || (long)z2 - z1 >= 16)
			throw new IllegalArgumentException("destination must have ordered bounds, at most 16 by 8 by 16 blocks");
	}

	public static LureEntitiesStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) if (!List.of("uuids", "itemId", "x1", "y1", "z1", "x2", "y2", "z2").contains(key))
			throw new IllegalArgumentException("unknown argument: " + key);
		if (!args.has("uuids") || !args.get("uuids").isJsonArray()) throw new IllegalArgumentException("uuids must be an array");
		List<String> ids = new ArrayList<>();
		for (var id : args.getAsJsonArray("uuids")) {
			if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("uuid must be a string");
			ids.add(id.getAsString());
		}
		var item = args.get("itemId");
		if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("itemId must be a string");
		return new LureEntitiesStepArgs(ids, item.getAsString(), integer(args,"x1"), integer(args,"y1"), integer(args,"z1"),
			integer(args,"x2"), integer(args,"y2"), integer(args,"z2"));
	}

	private static int integer(JsonObject args, String key) {
		var value = args.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be an integer");
		try { return value.getAsBigDecimal().intValueExact(); }
		catch (ArithmeticException ex) { throw new IllegalArgumentException(key + " must be a 32-bit integer"); }
	}
}
