package ai.moeru.airicraft.settings;

import java.util.*;

/** Named connection snapshots. The flat agent settings remain authoritative for runtime and YAML users. */
final class SettingsProfiles {
	private static final String FILE = "agent.yml";
	private static final String KEY = "settingsProfiles";
	private static final Set<String> FIELDS = Set.of(
		"plannerBackend", "providerBaseUrl", "apiKey", "model", "plannerReasoningEffort",
		"plannerNativeVisionEnabled", "visionProviderBaseUrl", "visionApiKey", "visionModel",
		"visionImageDetail", "plannerMaxImages", "requestTimeoutMillis", "visionRequestTimeoutMillis",
		"plannerCompactionTriggerTokens", "plannerSummarizeToolResults");
	private final SettingsDraft draft;
	private final Map<String, Object> defaults = new LinkedHashMap<>();
	private final Map<String, Map<String, Object>> profiles = new LinkedHashMap<>();
	private final boolean existed;
	private boolean managed;
	private String active;

	SettingsProfiles(SettingsDraft draft) {
		this.draft = draft;
		draft.agentDefaults().forEach((key, value) -> {
			if (FIELDS.contains(key) || key.startsWith("codexAppServer.") || key.startsWith("thinkingPlanner.")) defaults.put(key, value);
		});
		Map<?, ?> stored = draft.get(FILE, KEY, Map.of());
		existed = !stored.isEmpty();
		if (existed) {
			if (!(stored.get("active") instanceof String name) || !(stored.get("profiles") instanceof Map<?, ?> saved)) {
				throw new IllegalArgumentException("Invalid connection profiles");
			}
			active = name;
			saved.forEach((key, value) -> {
				if (!(key instanceof String profileName) || !(value instanceof Map<?, ?> settings)) throw new IllegalArgumentException("Invalid connection profile");
				validateName(profileName);
				Map<String, Object> values = new LinkedHashMap<>(defaults);
				settings.forEach((field, setting) -> {
					if (field instanceof String fieldName && defaults.containsKey(fieldName)) {
						Object fallback = defaults.get(fieldName);
						if (!(fallback instanceof Number && setting instanceof Number) && !fallback.getClass().isInstance(setting)) throw new IllegalArgumentException("Invalid profile setting");
						values.put(fieldName, setting);
					}
				});
				profiles.put(profileName, values);
			});
			if (!profiles.containsKey(active)) throw new IllegalArgumentException("Missing active profile");
		} else {
			active = "Default";
		}
		capture(); // Respect manual edits to the active flat configuration.
	}

	String active() { return active; }
	List<String> names() { return List.copyOf(profiles.keySet()); }

	private void capture() {
		Map<String, Object> values = new LinkedHashMap<>();
		defaults.forEach((key, fallback) -> values.put(key, draft.get(FILE, key, fallback)));
		profiles.put(active, values);
	}

	void select(String name) {
		if (!profiles.containsKey(name)) throw new IllegalArgumentException("Unknown profile");
		if (active.equals(name)) return;
		capture();
		active = name;
		profiles.get(name).forEach((key, value) -> draft.set(FILE, key, value));
		managed = true;
		stage();
	}

	void duplicate(String name) {
		name = availableName(name);
		capture();
		profiles.put(name, new LinkedHashMap<>(profiles.get(active)));
		active = name;
		managed = true;
		stage();
	}

	void rename(String name) {
		name = name.trim();
		if (active.equals(name)) return;
		name = availableName(name);
		capture();
		Map<String, Object> values = profiles.remove(active);
		profiles.put(name, values);
		active = name;
		managed = true;
		stage();
	}

	void delete() {
		if (profiles.size() == 1) throw new IllegalArgumentException("Keep at least one profile");
		String removed = active;
		select(names().stream().filter(name -> !name.equals(removed)).findFirst().orElseThrow());
		profiles.remove(removed);
		stage();
	}

	void stage() {
		capture();
		if (!existed && !managed) return;
		Map<String, Object> copies = new LinkedHashMap<>();
		profiles.forEach((name, values) -> copies.put(name, new LinkedHashMap<>(values)));
		draft.set(FILE, KEY, Map.of("active", active, "profiles", copies));
	}

	private String availableName(String name) {
		name = name.trim();
		validateName(name);
		if (profiles.containsKey(name)) throw new IllegalArgumentException("That profile name already exists.");
		return name;
	}
	private static void validateName(String name) {
		if (name.isBlank() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Use a name of 1–64 characters.");
		}
	}
}
