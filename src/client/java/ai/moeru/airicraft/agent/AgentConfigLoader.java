package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.ConfigLoadException;
import com.google.gson.Gson;
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

public final class AgentConfigLoader {
	private static final Gson GSON = new Gson();
	private static final Yaml YAML = createYaml();
	private static final String TEMPLATE_RESOURCE = "/config/airicraft/agent.yml.example";
	private static final String TEMPLATE_FILENAME = "agent.yml.example";
	private static final String CONFIG_FILENAME = "agent.yml";
	private static final String LEGACY_CONFIG_FILENAME = "agent.json";

	private AgentConfigLoader() {
	}

	public static AgentConfig load() {
		try {
			return loadInternal(false);
		}
		catch (ConfigLoadException exception) {
			Airicraft.LOGGER.warn("Failed to load Airicraft agent config; using defaults", exception);
			return AgentConfig.defaults();
		}
	}

	public static AgentConfig loadStrict() throws ConfigLoadException {
		return loadInternal(true);
	}

	private static AgentConfig loadInternal(boolean strict) throws ConfigLoadException {
		AgentConfig defaults = AgentConfig.defaults();
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve("airicraft");
		Path templatePath = configDir.resolve(TEMPLATE_FILENAME);
		Path configPath = configDir.resolve(CONFIG_FILENAME);
		Path legacyConfigPath = configDir.resolve(LEGACY_CONFIG_FILENAME);

		try {
			Files.createDirectories(configDir);
			ensureFile(templatePath);
			if (Files.notExists(configPath)) {
				if (Files.exists(legacyConfigPath)) {
					migrateLegacyJsonConfig(legacyConfigPath, configPath, defaults);
				}
				else {
					Files.copy(templatePath, configPath);
				}
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

	static AgentConfig fromMap(Map<String, Object> root, AgentConfig defaults) {
		return fromMap(root, defaults, false);
	}

	public static AgentConfig fromMapStrict(Map<String, Object> root, AgentConfig defaults) {
		return fromMap(root, defaults, true);
	}

	private static AgentConfig fromMap(Map<String, Object> root, AgentConfig defaults, boolean strict) {
		warnIfMalformedObject(root, "codexAppServer", strict);
		Map<String, Object> codexRoot = readObjectMap(root, "codexAppServer", strict);
		AgentConfig.CodexAppServerConfig codexAppServer = new AgentConfig.CodexAppServerConfig(
			readString(codexRoot, "executable", defaults.llm().codexAppServer().executable(), strict),
			readString(codexRoot, "model", defaults.llm().codexAppServer().model(), strict),
			readString(codexRoot, "reasoningEffort", defaults.llm().codexAppServer().reasoningEffort(), strict),
			readString(codexRoot, "serviceTier", defaults.llm().codexAppServer().serviceTier(), strict),
			readInt(codexRoot, "startupTimeoutMillis", defaults.llm().codexAppServer().startupTimeoutMillis()),
			readInt(codexRoot, "turnTimeoutMillis", defaults.llm().codexAppServer().turnTimeoutMillis())
		);
		Map<String, Object> thinkingRoot = readObjectMap(root, "thinkingPlanner", strict);
		var thinkingPlanner = new AgentConfig.ThinkingPlannerConfig(
			readBoolean(thinkingRoot, "enabled", defaults.llm().thinkingPlanner().enabled(), strict),
			readString(thinkingRoot, "model", defaults.llm().thinkingPlanner().model(), strict),
			readString(thinkingRoot, "reasoningEffort", defaults.llm().thinkingPlanner().reasoningEffort(), strict));
		AgentConfig.LlmConfig llm = new AgentConfig.LlmConfig(
			readString(root, "providerBaseUrl", defaults.llm().providerBaseUrl(), strict),
			readString(root, "apiKey", defaults.llm().apiKey(), strict),
			readString(root, "model", defaults.llm().model(), strict),
			readString(root, "visionProviderBaseUrl", defaults.llm().visionProviderBaseUrl(), strict),
			readString(root, "visionApiKey", defaults.llm().visionApiKey(), strict),
			readString(root, "visionModel", defaults.llm().visionModel(), strict),
			readInt(root, "requestTimeoutMillis", defaults.llm().requestTimeoutMillis()),
			readInt(root, "visionRequestTimeoutMillis", defaults.llm().visionRequestTimeoutMillis()),
			readInt(root, "maxRecentConversationTurns", defaults.llm().maxRecentConversationTurns()),
			readInt(root, "plannerCompactionTriggerTokens", defaults.llm().plannerCompactionTriggerTokens()),
			readInt(root, "plannerPendingSemanticEventCap", defaults.llm().plannerPendingSemanticEventCap()),
			readInt(root, "plannerSessionMaxConcurrentAttempts", defaults.llm().plannerSessionMaxConcurrentAttempts()),
			readInt(root, "plannerSessionCoalesceStepMillis", defaults.llm().plannerSessionCoalesceStepMillis()),
			readInt(root, "plannerSessionCoalesceMinMillis", defaults.llm().plannerSessionCoalesceMinMillis()),
			readInt(root, "plannerSessionCoalesceMaxMillis", defaults.llm().plannerSessionCoalesceMaxMillis()),
			readString(root, "visionImageDetail", defaults.llm().visionImageDetail(), strict),
			readBoolean(root, "plannerNativeVisionEnabled", defaults.llm().plannerNativeVisionEnabled(), strict),
			readBoolean(root, "plannerUseJsonObjectResponseFormat", defaults.llm().plannerUseJsonObjectResponseFormat(), strict),
			readPlannerBackend(root, defaults.llm().plannerBackend(), strict),
			codexAppServer,
			readString(root, "plannerReasoningEffort", defaults.llm().reasoningEffort(), strict),
			thinkingPlanner,
			readInt(root, "plannerMaxImages", defaults.llm().plannerMaxImages()),
			readBoolean(root, "plannerSummarizeToolResults", defaults.llm().plannerSummarizeToolResults() && readPlannerBackend(root, defaults.llm().plannerBackend(), strict) != AgentConfig.PlannerBackend.CODEX_APP_SERVER, strict)
		);
		AgentConfig.IdleConfig idle = new AgentConfig.IdleConfig(
			readInt(root, "idleInitialDelaySeconds", defaults.idle().initialDelaySeconds()),
			readInt(root, "idleCooldownSeconds", defaults.idle().cooldownSeconds())
		);
		warnIfMalformedObject(root, "reflex", strict);
		Map<String, Object> reflexRoot = readObjectMap(root, "reflex", strict);
		AgentConfig.ReflexConfig reflex = new AgentConfig.ReflexConfig(
			readBoolean(reflexRoot, "enabled", defaults.reflex().enabled(), strict),
			readInt(reflexRoot, "lowAirTicks", defaults.reflex().lowAirTicks()),
			readDouble(reflexRoot, "defendMinHealthRatio", defaults.reflex().defendMinHealthRatio()),
			readInt(reflexRoot, "threatCooldownTicks", defaults.reflex().threatCooldownTicks())
		);
		warnIfMalformedObject(root, "observability", strict);
		Map<String, Object> observabilityRoot = readObjectMap(root, "observability", strict);
		AgentConfig.ObservabilityConfig observability = new AgentConfig.ObservabilityConfig(
			readBoolean(observabilityRoot, "enabled", defaults.observability().enabled(), strict),
			readString(observabilityRoot, "exporter", defaults.observability().exporter(), strict),
			readString(observabilityRoot, "otlpEndpoint", defaults.observability().otlpEndpoint(), strict),
			readStringMap(observabilityRoot, "otlpHeaders", defaults.observability().otlpHeaders(), strict),
			readStringMap(observabilityRoot, "resourceAttributes", defaults.observability().resourceAttributes(), strict),
			readString(observabilityRoot, "vendorProfile", defaults.observability().vendorProfile(), strict),
			readBoolean(observabilityRoot, "debugLogExports", defaults.observability().debugLogExports(), strict),
			readBoolean(observabilityRoot, "captureInputs", defaults.observability().captureInputs(), strict),
			readBoolean(observabilityRoot, "captureOutputs", defaults.observability().captureOutputs(), strict),
			readBoolean(observabilityRoot, "captureImages", defaults.observability().captureImages(), strict)
		);
		return new AgentConfig(defaults.verificationEnabled(), defaults.verificationAutoRunAll(), llm, idle, reflex, observability);
	}

	private static void ensureFile(Path path) throws IOException {
		if (Files.exists(path)) {
			return;
		}

		try (InputStream stream = AgentConfigLoader.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
			if (stream == null) {
				throw new IOException("Missing embedded agent config template: " + TEMPLATE_RESOURCE);
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

	private static void migrateLegacyJsonConfig(Path legacyConfigPath, Path yamlConfigPath, AgentConfig defaults) throws IOException {
		String json = Files.readString(legacyConfigPath, StandardCharsets.UTF_8);
		@SuppressWarnings("unchecked")
		Map<String, Object> root = GSON.fromJson(json, Map.class);
		if (root == null) {
			Files.copy(yamlConfigPath.getParent().resolve(TEMPLATE_FILENAME), yamlConfigPath);
			return;
		}

		Map<String, Object> yamlData = new LinkedHashMap<>();
		yamlData.put("plannerBackend", defaults.llm().plannerBackend().wireValue());
		yamlData.put("providerBaseUrl", readString(root, "providerBaseUrl", defaults.llm().providerBaseUrl(), false));
		yamlData.put("apiKey", readString(root, "apiKey", defaults.llm().apiKey(), false));
		yamlData.put("model", readString(root, "model", defaults.llm().model(), false));
		yamlData.put("plannerReasoningEffort", readString(root, "plannerReasoningEffort", defaults.llm().reasoningEffort(), false));
		yamlData.put("visionProviderBaseUrl", readString(root, "visionProviderBaseUrl", defaults.llm().visionProviderBaseUrl(), false));
		yamlData.put("visionApiKey", readString(root, "visionApiKey", defaults.llm().visionApiKey(), false));
		yamlData.put("visionModel", readString(root, "visionModel", defaults.llm().visionModel(), false));
		yamlData.put("requestTimeoutMillis", readInt(root, "requestTimeoutMillis", defaults.llm().requestTimeoutMillis()));
		yamlData.put("visionRequestTimeoutMillis", readInt(root, "visionRequestTimeoutMillis", defaults.llm().visionRequestTimeoutMillis()));
		yamlData.put("maxRecentConversationTurns", readInt(root, "maxRecentConversationTurns", defaults.llm().maxRecentConversationTurns()));
		yamlData.put("plannerSummarizeToolResults", readBoolean(root, "plannerSummarizeToolResults", defaults.llm().plannerSummarizeToolResults() && readPlannerBackend(root, defaults.llm().plannerBackend(), false) != AgentConfig.PlannerBackend.CODEX_APP_SERVER, false));
		yamlData.put("plannerMaxImages", readInt(root, "plannerMaxImages", defaults.llm().plannerMaxImages()));
		yamlData.put("plannerCompactionTriggerTokens", readInt(root, "plannerCompactionTriggerTokens", defaults.llm().plannerCompactionTriggerTokens()));
		yamlData.put("plannerPendingSemanticEventCap", readInt(root, "plannerPendingSemanticEventCap", defaults.llm().plannerPendingSemanticEventCap()));
		yamlData.put("plannerSessionMaxConcurrentAttempts", readInt(root, "plannerSessionMaxConcurrentAttempts", defaults.llm().plannerSessionMaxConcurrentAttempts()));
		yamlData.put("plannerSessionCoalesceStepMillis", readInt(root, "plannerSessionCoalesceStepMillis", defaults.llm().plannerSessionCoalesceStepMillis()));
		yamlData.put("plannerSessionCoalesceMinMillis", readInt(root, "plannerSessionCoalesceMinMillis", defaults.llm().plannerSessionCoalesceMinMillis()));
		yamlData.put("plannerSessionCoalesceMaxMillis", readInt(root, "plannerSessionCoalesceMaxMillis", defaults.llm().plannerSessionCoalesceMaxMillis()));
		yamlData.put("idleInitialDelaySeconds", defaults.idle().initialDelaySeconds());
		yamlData.put("idleCooldownSeconds", defaults.idle().cooldownSeconds());
		yamlData.put("reflex", Map.of(
			"enabled", defaults.reflex().enabled(),
			"lowAirTicks", defaults.reflex().lowAirTicks(),
			"defendMinHealthRatio", defaults.reflex().defendMinHealthRatio(),
			"threatCooldownTicks", defaults.reflex().threatCooldownTicks()
		));
		yamlData.put("visionImageDetail", readString(root, "visionImageDetail", defaults.llm().visionImageDetail(), false));
		yamlData.put("plannerNativeVisionEnabled", readBoolean(root, "plannerNativeVisionEnabled", defaults.llm().plannerNativeVisionEnabled(), false));
		yamlData.put(
			"plannerUseJsonObjectResponseFormat",
			readBoolean(root, "plannerUseJsonObjectResponseFormat", defaults.llm().plannerUseJsonObjectResponseFormat(), false)
		);
		yamlData.put("codexAppServer", Map.of(
			"executable", defaults.llm().codexAppServer().executable(),
			"model", defaults.llm().codexAppServer().model(),
			"reasoningEffort", defaults.llm().codexAppServer().reasoningEffort(),
			"serviceTier", defaults.llm().codexAppServer().serviceTier(),
			"startupTimeoutMillis", defaults.llm().codexAppServer().startupTimeoutMillis(),
			"turnTimeoutMillis", defaults.llm().codexAppServer().turnTimeoutMillis()
		));
		yamlData.put("observability", Map.of(
			"enabled", defaults.observability().enabled(),
			"exporter", defaults.observability().exporter(),
			"otlpEndpoint", defaults.observability().otlpEndpoint(),
			"otlpHeaders", defaults.observability().otlpHeaders(),
			"resourceAttributes", defaults.observability().resourceAttributes(),
			"vendorProfile", defaults.observability().vendorProfile(),
			"debugLogExports", defaults.observability().debugLogExports(),
			"captureInputs", defaults.observability().captureInputs(),
			"captureOutputs", defaults.observability().captureOutputs(),
			"captureImages", defaults.observability().captureImages()
		));
		Files.writeString(yamlConfigPath, dumpYaml(yamlData), StandardCharsets.UTF_8);
	}

	private static AgentConfig.PlannerBackend readPlannerBackend(
		Map<String, Object> root,
		AgentConfig.PlannerBackend fallback,
		boolean strict
	) {
		String wireValue = readString(root, "plannerBackend", fallback.wireValue(), strict);
		try {
			return AgentConfig.PlannerBackend.fromWireValue(wireValue);
		}
		catch (IllegalArgumentException exception) {
			if (strict) {
				throw exception;
			}
			Airicraft.LOGGER.warn("Unsupported plannerBackend {}; using {}", wireValue, fallback.wireValue());
			return fallback;
		}
	}

	private static void warnIfMalformedObject(Map<String, Object> root, String fieldName, boolean strict) {
		if (root == null || !root.containsKey(fieldName)) {
			return;
		}
		Object value = root.get(fieldName);
		if (value == null || value instanceof Map<?, ?>) {
			return;
		}
		if (strict) {
			throw new IllegalArgumentException(fieldName + " must be a YAML mapping");
		}
		Airicraft.LOGGER.warn("Expected {} to be a YAML mapping; ignoring malformed value and using defaults for nested fields", fieldName);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> readObjectMap(Map<String, Object> root, String fieldName, boolean strict) {
		if (root == null || !root.containsKey(fieldName) || root.get(fieldName) == null) {
			return Map.of();
		}
		Object value = root.get(fieldName);
		if (!(value instanceof Map<?, ?> map)) {
			if (strict) {
				throw new IllegalArgumentException(fieldName + " must be a YAML mapping");
			}
			return Map.of();
		}
		Map<String, Object> typed = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			typed.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return typed;
	}

	private static Map<String, String> readStringMap(Map<String, Object> root, String fieldName, Map<String, String> fallback, boolean strict) {
		Map<String, Object> raw = readObjectMap(root, fieldName, strict);
		if (raw.isEmpty()) {
			return fallback;
		}
		Map<String, String> typed = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : raw.entrySet()) {
			if (strict && (entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof java.util.List<?>)) {
				throw new IllegalArgumentException(fieldName + "." + entry.getKey() + " must be a scalar value");
			}
			typed.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
		}
		return typed;
	}

	private static String readString(Map<String, Object> root, String fieldName, String fallback, boolean strict) {
		if (root == null || !root.containsKey(fieldName) || root.get(fieldName) == null) {
			return fallback;
		}
		Object rawValue = root.get(fieldName);
		if (strict && (rawValue instanceof Map<?, ?> || rawValue instanceof java.util.List<?>)) {
			throw new IllegalArgumentException(fieldName + " must be a scalar value");
		}
		String value = String.valueOf(rawValue);
		return value == null ? fallback : value;
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

	private static long readLong(Map<String, Object> root, String fieldName, long fallback) {
		if (root == null || !root.containsKey(fieldName) || root.get(fieldName) == null) {
			return fallback;
		}
		Object value = root.get(fieldName);
		if (value instanceof Number number) {
			return number.longValue();
		}
		return Long.parseLong(String.valueOf(value));
	}

	private static double readDouble(Map<String, Object> root, String fieldName, double fallback) {
		if (root == null || !root.containsKey(fieldName) || root.get(fieldName) == null) {
			return fallback;
		}
		Object value = root.get(fieldName);
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		return Double.parseDouble(String.valueOf(value));
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
