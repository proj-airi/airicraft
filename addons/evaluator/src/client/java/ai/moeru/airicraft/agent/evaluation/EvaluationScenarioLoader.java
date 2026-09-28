package ai.moeru.airicraft.agent.evaluation;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EvaluationScenarioLoader {
	private static final Yaml YAML = createYaml();

	private EvaluationScenarioLoader() {
	}

	public static EvaluationScenario load(Path configPath) throws IOException {
		try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
			return fromMap(parseYaml(reader), configPath);
		}
	}

	public static void write(Path configPath, EvaluationScenario scenario) throws IOException {
		Files.createDirectories(configPath.getParent());
		try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
			YAML.dump(toMap(scenario), writer);
		}
	}

	public static EvaluationScenario fromMap(Map<String, Object> root, Path configPath) {
		String fallbackId = configPath == null || configPath.getParent() == null
			? ""
			: String.valueOf(configPath.getParent().getFileName());
		Map<String, Object> metadata = object(root, "metadata");
		Map<String, Object> budgetRoot = object(root, "budget");
		Map<String, Object> evidenceRoot = object(root, "evidence");
		return new EvaluationScenario(
			string(root, "id", fallbackId),
			string(root, "name", fallbackId),
			string(metadata, "minecraftVersion", ""),
			string(metadata, "airicraftVersion", ""),
			configPath,
			string(root, "worldArchive", "world.zip"),
			bool(root, "frozen", false),
			string(root, "prompt", ""),
			new EvaluationBudget(
				integer(budgetRoot, "maxPlannerTurns", EvaluationBudget.defaults().maxPlannerTurns()),
				longValue(budgetRoot, "maxElapsedTicks", EvaluationBudget.defaults().maxElapsedTicks()),
				longValue(budgetRoot, "maxElapsedMillis", EvaluationBudget.defaults().maxElapsedMillis()),
				longValue(budgetRoot, "heartbeatIntervalTicks", EvaluationBudget.defaults().heartbeatIntervalTicks()),
				longValue(budgetRoot, "maxStallTicks", EvaluationBudget.defaults().maxStallTicks())
			),
			checks(root.get("checks")),
			waypoints(root.get("waypoints")),
			new EvaluationEvidenceSettings(
				bool(evidenceRoot, "includePlannerJournal", true),
				bool(evidenceRoot, "includeDebugTimeline", true),
				bool(evidenceRoot, "includeRecentEvents", true),
				bool(evidenceRoot, "includeTaskState", true),
				bool(evidenceRoot, "includeWorldSnapshot", true)
			),
			requiredMods(root)
		);
	}

	public static Map<String, Object> toMap(EvaluationScenario scenario) {
		LinkedHashMap<String, Object> root = new LinkedHashMap<>();
		root.put("id", scenario.id());
		root.put("name", scenario.name());
		root.put("metadata", orderedMap(
			"minecraftVersion", scenario.minecraftVersion(),
			"airicraftVersion", scenario.airicraftVersion()
		));
		root.put("requiredMods", scenario.requiredMods());
		root.put("worldArchive", scenario.worldArchive());
		root.put("frozen", scenario.frozen());
		root.put("prompt", scenario.prompt());
		root.put("budget", orderedMap(
			"maxPlannerTurns", scenario.budget().maxPlannerTurns(),
			"maxElapsedTicks", scenario.budget().maxElapsedTicks(),
			"maxElapsedMillis", scenario.budget().maxElapsedMillis(),
			"heartbeatIntervalTicks", scenario.budget().heartbeatIntervalTicks(),
			"maxStallTicks", scenario.budget().maxStallTicks()
		));
		ArrayList<Map<String, Object>> checkMaps = new ArrayList<>();
		for (EvaluationCheck check : scenario.checks()) {
			LinkedHashMap<String, Object> checkMap = new LinkedHashMap<>();
			checkMap.put("type", check.type());
			checkMap.putAll(check.fields());
			checkMaps.add(checkMap);
		}
		root.put("checks", checkMaps);
		ArrayList<Map<String, Object>> waypointMaps = new ArrayList<>();
		for (EvaluationWaypoint waypoint : scenario.waypoints()) {
			LinkedHashMap<String, Object> waypointMap = new LinkedHashMap<>();
			putIfPresent(waypointMap, "provider", waypoint.provider());
			putIfPresent(waypointMap, "id", waypoint.id());
			waypointMap.put("name", waypoint.name());
			waypointMap.put("dimension", waypoint.dimension());
			waypointMap.put("x", waypoint.x());
			waypointMap.put("y", waypoint.y());
			waypointMap.put("z", waypoint.z());
			waypointMaps.add(waypointMap);
		}
		if (!waypointMaps.isEmpty()) {
			root.put("waypoints", waypointMaps);
		}
		root.put("evidence", orderedMap(
			"includePlannerJournal", scenario.evidence().includePlannerJournal(),
			"includeDebugTimeline", scenario.evidence().includeDebugTimeline(),
			"includeRecentEvents", scenario.evidence().includeRecentEvents(),
			"includeTaskState", scenario.evidence().includeTaskState(),
			"includeWorldSnapshot", scenario.evidence().includeWorldSnapshot()
		));
		return root;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseYaml(Reader reader) {
		Object loaded = YAML.load(reader);
		if (loaded instanceof Map<?, ?> map) {
			LinkedHashMap<String, Object> typed = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				typed.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			return typed;
		}
		return Map.of();
	}

	private static List<String> requiredMods(Map<String, Object> root) {
		if (!root.containsKey("requiredMods")) {
			return List.of();
		}
		if (!(root.get("requiredMods") instanceof List<?> list)
			|| list.stream().anyMatch(value -> !(value instanceof String))) {
			throw new IllegalArgumentException("requiredMods must be a list of supported mod IDs");
		}
		return list.stream().map(String.class::cast).toList();
	}

	private static List<EvaluationCheck> checks(Object value) {
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		ArrayList<EvaluationCheck> checks = new ArrayList<>();
		for (Object current : list) {
			if (!(current instanceof Map<?, ?> map)) {
				continue;
			}
			LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
			String type = null;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey());
				if ("type".equals(key)) {
					type = String.valueOf(entry.getValue());
				}
				else {
					fields.put(key, entry.getValue());
				}
			}
			checks.add(new EvaluationCheck(type, fields));
		}
		return checks;
	}

	private static List<EvaluationWaypoint> waypoints(Object value) {
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		ArrayList<EvaluationWaypoint> waypoints = new ArrayList<>();
		for (Object current : list) {
			if (!(current instanceof Map<?, ?> map)) {
				continue;
			}
			LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				fields.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			waypoints.add(new EvaluationWaypoint(
				string(fields, "provider", null),
				string(fields, "id", null),
				string(fields, "name", null),
				string(fields, "dimension", "minecraft:overworld"),
				integer(fields, "x", 0),
				integer(fields, "y", 0),
				integer(fields, "z", 0)
			));
		}
		return List.copyOf(waypoints);
	}

	private static Map<String, Object> object(Map<String, Object> root, String key) {
		if (root == null || !(root.get(key) instanceof Map<?, ?> map)) {
			return Map.of();
		}
		LinkedHashMap<String, Object> typed = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			typed.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return typed;
	}

	private static String string(Map<String, Object> root, String key, String fallback) {
		if (root == null || root.get(key) == null) {
			return fallback;
		}
		return String.valueOf(root.get(key));
	}

	private static boolean bool(Map<String, Object> root, String key, boolean fallback) {
		if (root == null || root.get(key) == null) {
			return fallback;
		}
		Object value = root.get(key);
		return value instanceof Boolean booleanValue ? booleanValue : Boolean.parseBoolean(String.valueOf(value));
	}

	private static int integer(Map<String, Object> root, String key, int fallback) {
		return (int) longValue(root, key, fallback);
	}

	private static long longValue(Map<String, Object> root, String key, long fallback) {
		if (root == null || root.get(key) == null) {
			return fallback;
		}
		Object value = root.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		return Long.parseLong(String.valueOf(value));
	}

	private static LinkedHashMap<String, Object> orderedMap(Object... keysAndValues) {
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
			map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
		}
		return map;
	}

	private static void putIfPresent(Map<String, Object> map, String key, Object value) {
		if (value != null) {
			map.put(key, value);
		}
	}

	private static Yaml createYaml() {
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setPrettyFlow(true);
		options.setIndent(2);
		return new Yaml(options);
	}
}
