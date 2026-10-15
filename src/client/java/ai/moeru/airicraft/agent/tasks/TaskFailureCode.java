package ai.moeru.airicraft.agent.tasks;

import java.util.Locale;

public enum TaskFailureCode {
	NONE(""),
	TRANSIENT("transient"),
	BUSY("busy"),
	MISSING_FACT("missing_fact"),
	MISSING_ITEM("missing_item"),
	ENVIRONMENT_CHANGED("environment_changed"),
	INVALID_ACTION("invalid_action"),
	DESTRUCTIVE_DENIED("destructive_denied"),
	UNKNOWN("failed");

	private final String id;

	TaskFailureCode(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public static TaskFailureCode fromLegacyDetail(String detail) {
		String text = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
		if (text.contains("required_item_missing") || text.contains("missing_ingredients") || text.contains("ingredients_ran_out")) {
			return MISSING_ITEM;
		}
		if (text.contains("busy") || text.contains("occupied")) {
			return BUSY;
		}
		if (text.contains("timeout") || text.contains("temporary") || text.contains("path")) {
			return TRANSIENT;
		}
		if (text.contains("missing") || text.contains("not_found") || text.contains("not found") || text.contains("target")) {
			return MISSING_FACT;
		}
		if (text.contains("unloaded") || text.contains("changed") || text.contains("gone")) {
			return ENVIRONMENT_CHANGED;
		}
		if (text.contains("unsupported") || text.contains("invalid")) {
			return INVALID_ACTION;
		}
		if (text.contains("denied") || text.contains("destructive")) {
			return DESTRUCTIVE_DENIED;
		}
		return UNKNOWN;
	}

	public static TaskFailureCode fromLegacyCode(String code) {
		if (code == null || code.isBlank()) {
			return UNKNOWN;
		}
		return switch (code.trim().toLowerCase(Locale.ROOT)) {
			case "transient", "timeout", "temporary" -> TRANSIENT;
			case "busy", "occupied" -> BUSY;
			case "missing_fact", "recipe_not_found", "recipe_not_craftable", "smelting_option_not_found" -> MISSING_FACT;
			case "missing_item", "missing_ingredients", "ingredients_ran_out" -> MISSING_ITEM;
			case "environment_changed" -> ENVIRONMENT_CHANGED;
			case "invalid_action", "invalid_step_args", "unsupported_primitive", "unsupported_step_kind", "missing_step" -> INVALID_ACTION;
			case "destructive_denied" -> DESTRUCTIVE_DENIED;
			default -> UNKNOWN;
		};
	}
}
