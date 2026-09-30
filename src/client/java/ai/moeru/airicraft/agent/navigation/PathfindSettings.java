package ai.moeru.airicraft.agent.navigation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The movement policy the planner may tune through {@code configure_pathfind}. Session-local: it
 * resets on reload and every navigation request reads the current values when it plans.
 */
public final class PathfindSettings {
	public static final int MAX_FALL_HEIGHT_LIMIT = 20;
	public static final double MAX_WATER_COST = 100.0;

	public record Values(boolean allowBreak, boolean allowPlace, int maxFallHeight, double waterCost,
		boolean avoidMobs, boolean allowInventoryToolSwap) {
		public static final Values DEFAULTS = new Values(true, true, 3, 3.0, true, true);
	}

	private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

	static {
		DESCRIPTIONS.put("allowBreak", "Whether paths may break blocks to tunnel through terrain.");
		DESCRIPTIONS.put("allowPlace", "Whether paths may place carried blocks to bridge gaps and pillar up.");
		DESCRIPTIONS.put("maxFallHeight", "Highest fall in blocks a path may take onto solid ground, 1.."
			+ MAX_FALL_HEIGHT_LIMIT + ". Raising it risks fall damage.");
		DESCRIPTIONS.put("waterCost", "Extra path cost per move that ends in water, 0.." + (int) MAX_WATER_COST
			+ ". Raise it to prefer land routes.");
		DESCRIPTIONS.put("avoidMobs", "Whether paths keep away from hostile mobs.");
		DESCRIPTIONS.put("allowInventoryToolSwap", "Whether navigation may move a tool or block from the main inventory into the hotbar. When off, only hotbar items are used.");
	}

	private static volatile Values current = Values.DEFAULTS;

	private PathfindSettings() {
	}

	public static Values current() {
		return current;
	}

	/** Back to the defaults; a reload or a new agent runtime calls this. */
	public static void reset() {
		current = Values.DEFAULTS;
	}

	/** The fixed part of the planner's {@code configure_pathfind} schema: property name to JSON schema. */
	public static Map<String, Object> plannerProperties() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("allowBreak", Map.of("type", "boolean", "description", DESCRIPTIONS.get("allowBreak")));
		properties.put("allowPlace", Map.of("type", "boolean", "description", DESCRIPTIONS.get("allowPlace")));
		properties.put("maxFallHeight", Map.of("type", "integer", "minimum", 1, "maximum", MAX_FALL_HEIGHT_LIMIT,
			"description", DESCRIPTIONS.get("maxFallHeight")));
		properties.put("waterCost", Map.of("type", "number", "minimum", 0, "maximum", MAX_WATER_COST,
			"description", DESCRIPTIONS.get("waterCost")));
		properties.put("avoidMobs", Map.of("type", "boolean", "description", DESCRIPTIONS.get("avoidMobs")));
		properties.put("allowInventoryToolSwap", Map.of("type", "boolean", "description", DESCRIPTIONS.get("allowInventoryToolSwap")));
		return properties;
	}

	/** Validates every requested value first and applies them together, or changes nothing. */
	public static ApplyResult apply(JsonObject requested) {
		if (requested == null || requested.isEmpty()) return ApplyResult.rejected("provide at least one setting");
		Values before = current;
		boolean allowBreak = before.allowBreak(), allowPlace = before.allowPlace(), avoidMobs = before.avoidMobs();
		boolean swap = before.allowInventoryToolSwap();
		int maxFall = before.maxFallHeight();
		double waterCost = before.waterCost();
		List<String> changed = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : requested.entrySet()) {
			JsonElement value = entry.getValue();
			switch (entry.getKey()) {
				case "allowBreak" -> {
					if (!isBoolean(value)) return ApplyResult.rejected("invalid_type allowBreak expects boolean");
					allowBreak = value.getAsBoolean();
				}
				case "allowPlace" -> {
					if (!isBoolean(value)) return ApplyResult.rejected("invalid_type allowPlace expects boolean");
					allowPlace = value.getAsBoolean();
				}
				case "avoidMobs" -> {
					if (!isBoolean(value)) return ApplyResult.rejected("invalid_type avoidMobs expects boolean");
					avoidMobs = value.getAsBoolean();
				}
				case "allowInventoryToolSwap" -> {
					if (!isBoolean(value)) return ApplyResult.rejected("invalid_type allowInventoryToolSwap expects boolean");
					swap = value.getAsBoolean();
				}
				case "maxFallHeight" -> {
					if (!isNumber(value) || value.getAsDouble() != Math.rint(value.getAsDouble())) {
						return ApplyResult.rejected("invalid_type maxFallHeight expects integer");
					}
					int blocks = (int) value.getAsDouble();
					if (blocks < 1 || blocks > MAX_FALL_HEIGHT_LIMIT) {
						return ApplyResult.rejected("out_of_range maxFallHeight must be 1.." + MAX_FALL_HEIGHT_LIMIT);
					}
					maxFall = blocks;
				}
				case "waterCost" -> {
					if (!isNumber(value)) return ApplyResult.rejected("invalid_type waterCost expects number");
					double cost = value.getAsDouble();
					if (Double.isNaN(cost) || cost < 0 || cost > MAX_WATER_COST) {
						return ApplyResult.rejected("out_of_range waterCost must be 0.." + (int) MAX_WATER_COST);
					}
					waterCost = cost;
				}
				default -> {
					return ApplyResult.rejected("unknown_setting " + entry.getKey());
				}
			}
			changed.add(entry.getKey() + "=" + value.getAsString());
		}
		current = new Values(allowBreak, allowPlace, maxFall, waterCost, avoidMobs, swap);
		return ApplyResult.accepted(changed);
	}

	/** Every setting with its current and default value, for {@code inspect_pathfind}. */
	public static Map<String, Object> inspect() {
		Values now = current, defaults = Values.DEFAULTS;
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("allowBreak", entry(now.allowBreak(), defaults.allowBreak(), "allowBreak"));
		result.put("allowPlace", entry(now.allowPlace(), defaults.allowPlace(), "allowPlace"));
		result.put("maxFallHeight", entry(now.maxFallHeight(), defaults.maxFallHeight(), "maxFallHeight"));
		result.put("waterCost", entry(now.waterCost(), defaults.waterCost(), "waterCost"));
		result.put("avoidMobs", entry(now.avoidMobs(), defaults.avoidMobs(), "avoidMobs"));
		result.put("allowInventoryToolSwap", entry(now.allowInventoryToolSwap(), defaults.allowInventoryToolSwap(), "allowInventoryToolSwap"));
		return result;
	}

	private static Map<String, Object> entry(Object value, Object defaultValue, String name) {
		return Map.of("value", value, "default", defaultValue, "description", DESCRIPTIONS.get(name));
	}

	private static boolean isBoolean(JsonElement value) {
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
	}

	private static boolean isNumber(JsonElement value) {
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
	}

	public record ApplyResult(boolean accepted, List<String> changed, String error) {
		public ApplyResult {
			changed = changed == null ? List.of() : List.copyOf(changed);
			error = error == null ? "" : error;
		}

		static ApplyResult accepted(List<String> changed) {
			return new ApplyResult(true, changed, "");
		}

		static ApplyResult rejected(String error) {
			return new ApplyResult(false, List.of(), Objects.requireNonNullElse(error, "invalid_settings"));
		}
	}
}
