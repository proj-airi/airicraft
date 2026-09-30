package ai.moeru.airicraft;

import ai.moeru.airicraft.dashboard.DebugDashboardConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AiricraftConfigLoader {
	private static final Yaml YAML = createYaml();
	private static final String TEMPLATE_RESOURCE = "/config/airicraft/airicraft.yml.example";
	private static final String TEMPLATE_FILENAME = "airicraft.yml.example";
	private static final String CONFIG_FILENAME = "airicraft.yml";
	private static final String AGENT_CONFIG_FILENAME = "agent.yml";

	private AiricraftConfigLoader() {
	}

	public static AiricraftConfig load() {
		try {
			return loadInternal(false);
		}
		catch (ConfigLoadException exception) {
			Airicraft.LOGGER.warn("Failed to load Airicraft mod config; using defaults", exception);
			return AiricraftConfig.defaults();
		}
	}

	public static AiricraftConfig loadStrict() throws ConfigLoadException {
		return loadInternal(true);
	}

	private static AiricraftConfig loadInternal(boolean strict) throws ConfigLoadException {
		AiricraftConfig defaults = AiricraftConfig.defaults();
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve("airicraft");
		Path templatePath = configDir.resolve(TEMPLATE_FILENAME);
		Path configPath = configDir.resolve(CONFIG_FILENAME);
		Path agentConfigPath = configDir.resolve(AGENT_CONFIG_FILENAME);

		try {
			Files.createDirectories(configDir);
			ensureFile(templatePath);
			if (Files.notExists(configPath)) {
				createInitialConfig(configPath, templatePath, agentConfigPath, defaults);
			}

			try (Reader fileReader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
				return strict ? fromMapStrict(parseYaml(fileReader), defaults) : fromMap(parseYaml(fileReader), defaults);
			}
		}
		catch (IOException | RuntimeException exception) {
			throw new ConfigLoadException(
				configPath,
				"Failed to load %s: %s".formatted(configPath.getFileName(), nonEmpty(exception.getMessage(), exception.getClass().getSimpleName())),
				exception
			);
		}
	}

	static AiricraftConfig fromMap(Map<String, Object> root, AiricraftConfig defaults) {
		return fromMap(root, defaults, false);
	}

	public static AiricraftConfig fromMapStrict(Map<String, Object> root, AiricraftConfig defaults) {
		return fromMap(root, defaults, true);
	}

	private static AiricraftConfig fromMap(Map<String, Object> root, AiricraftConfig defaults, boolean strict) {
		return new AiricraftConfig(
			readInt(root, "socialChatMaxDistanceBlocks", defaults.socialChatMaxDistanceBlocks()),
			readBoolean(root, "readSystemChatMessages", defaults.readSystemChatMessages(), strict),
			readBoolean(root, "enableProactiveSocialMode", defaults.enableProactiveSocialMode(), strict),
			readBoolean(root, "suppressAutoPauseOnFocusLost", defaults.suppressAutoPauseOnFocusLost(), strict),
			readInt(root, "blockInteractionDelayTicks", defaults.blockInteractionDelayTicks()),
			readInt(root, "cameraLerpDefaultTicks", defaults.cameraLerpDefaultTicks()),
			readDebugDashboardConfig(root, defaults.debugDashboard())
		);
	}

	private static DebugDashboardConfig readDebugDashboardConfig(
		Map<String, Object> root,
		DebugDashboardConfig defaults
	) {
		Map<String, Object> dashboard = childMap(root, "debugDashboard");
		int historyMegabytes = readInt(
			dashboard,
			"historyMegabytes",
			(int) (defaults.historyByteBudget() / (1024L * 1024L))
		);
		return new DebugDashboardConfig(
			readBoolean(dashboard, "enabled", defaults.enabled(), false),
			readInt(dashboard, "basePort", defaults.basePort()),
			readInt(dashboard, "portScanLimit", defaults.portScanLimit()),
			Math.max(1L, historyMegabytes) * 1024L * 1024L,
			readBoolean(dashboard, "visualCaptureEnabled", defaults.visualCaptureEnabled(), false),
			readInt(dashboard, "visualCaptureIntervalTicks", defaults.visualCaptureIntervalTicks())
		);
	}

	private static Map<String, Object> childMap(Map<String, Object> root, String fieldName) {
		if (root == null || !(root.get(fieldName) instanceof Map<?, ?> raw)) {
			return Map.of();
		}
		Map<String, Object> typed = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : raw.entrySet()) {
			typed.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return typed;
	}

	private static void createInitialConfig(
		Path configPath,
		Path templatePath,
		Path agentConfigPath,
		AiricraftConfig defaults
	) throws IOException {
		if (Files.exists(agentConfigPath)) {
			Map<String, Object> migratedData;
			try (Reader fileReader = Files.newBufferedReader(agentConfigPath, StandardCharsets.UTF_8)) {
				Map<String, Object> agentRoot = parseYaml(fileReader);
				migratedData = new LinkedHashMap<>();
				migratedData.put("socialChatMaxDistanceBlocks", defaults.socialChatMaxDistanceBlocks());
				migratedData.put("readSystemChatMessages", defaults.readSystemChatMessages());
				migratedData.put(
					"enableProactiveSocialMode",
					readBoolean(agentRoot, "enableProactiveSocialMode", defaults.enableProactiveSocialMode(), false)
				);
				migratedData.put("suppressAutoPauseOnFocusLost", defaults.suppressAutoPauseOnFocusLost());
				migratedData.put("blockInteractionDelayTicks", defaults.blockInteractionDelayTicks());
				migratedData.put("cameraLerpDefaultTicks", defaults.cameraLerpDefaultTicks());
				migratedData.put("debugDashboard", Map.of(
					"enabled", defaults.debugDashboard().enabled(),
					"basePort", defaults.debugDashboard().basePort(),
					"portScanLimit", defaults.debugDashboard().portScanLimit(),
					"historyMegabytes", defaults.debugDashboard().historyByteBudget() / (1024L * 1024L),
					"visualCaptureEnabled", defaults.debugDashboard().visualCaptureEnabled(),
					"visualCaptureIntervalTicks", defaults.debugDashboard().visualCaptureIntervalTicks()
				));
			}
			Files.writeString(configPath, dumpYaml(migratedData), StandardCharsets.UTF_8);
			return;
		}

		Files.copy(templatePath, configPath);
	}

	private static void ensureFile(Path path) throws IOException {
		if (Files.exists(path)) {
			return;
		}

		try (InputStream stream = AiricraftConfigLoader.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
			if (stream == null) {
				throw new IOException("Missing embedded Airicraft config template: " + TEMPLATE_RESOURCE);
			}
			Files.copy(stream, path);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseYaml(Reader reader) {
		Object loaded = YAML.load(reader);
		if (loaded instanceof Map<?, ?> map) {
			Map<String, Object> typed = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				typed.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			return typed;
		}
		return Map.of();
	}

	private static int readInt(Map<String, Object> root, String fieldName, int fallback) {
		if (root == null || !root.containsKey(fieldName) || root.get(fieldName) == null) {
			return fallback;
		}
		Object value = root.get(fieldName);
		if (value instanceof Number number) {
			return number.intValue();
		}
		return Integer.parseInt(String.valueOf(value));
	}

	private static boolean readBoolean(Map<String, Object> root, String fieldName, boolean fallback, boolean strict) {
		if (root == null || !root.containsKey(fieldName) || root.get(fieldName) == null) {
			return fallback;
		}
		Object value = root.get(fieldName);
		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}
		String text = String.valueOf(value);
		if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
			return Boolean.parseBoolean(text);
		}
		if (strict) {
			throw new IllegalArgumentException(fieldName + " must be true or false");
		}
		return Boolean.parseBoolean(text);
	}

	private static Yaml createYaml() {
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setPrettyFlow(true);
		options.setIndent(2);
		return new Yaml(options);
	}

	private static String dumpYaml(Map<String, Object> yamlData) {
		return YAML.dump(yamlData);
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
