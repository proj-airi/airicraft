package ai.moeru.airicraft.policy;

import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/** Shared function names; argument contracts remain owned by the native tool catalog. */
public final class PolicyTools {
	private PolicyTools() { }
	public static final Set<String> TOOLS = Set.of(
		"navigate_to", "return_to_surface", "follow_player", "mine_blocks", "ensure_blocks_in_inventory",
		"collect_resource", "craft_recipe", "smelt_items", "collect_smelted_items", "equip_item", "eat_food",
		"drop_items", "give_player", "attack_entity", "use_entity", "place_block", "use_block", "break_blocks",
		"tend_crops", "lure_entities", "start_action_goal", "close_container", "transfer_container",
		"configure_pathfind", "configure_lighting", "configure_opportunistic_mining", "configure_reflex", "inspect_inventory", "inspect_world",
		"inspect_nearby_entities", "inspect_container", "check_craftables", "check_smeltables", "inspect_smelting",
		"list_action_capabilities", "query_world", "search_recipes", "find_world_features", "survey_cave",
		"read_policy_docs");
	public static Map<String, String> methods() {
		var methods = new LinkedHashMap<String, String>();
		TOOLS.stream().sorted().forEach(tool -> {
			String[] parts = tool.split("_");
			StringBuilder method = new StringBuilder(parts[0]);
			for (int i = 1; i < parts.length; i++) method.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
			methods.put(method.toString(), tool);
		});
		return Map.copyOf(methods);
	}
}
