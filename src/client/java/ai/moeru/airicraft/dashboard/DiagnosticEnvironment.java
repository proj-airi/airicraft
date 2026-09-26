package ai.moeru.airicraft.dashboard;

import ai.moeru.airicraft.agent.AgentConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public final class DiagnosticEnvironment {
	private DiagnosticEnvironment() {}

	public static Map<String, Object> capture(AgentConfig.LlmConfig config) {
		Properties build = new Properties();
		try (var input = DiagnosticEnvironment.class.getResourceAsStream("/airicraft-build.properties")) {
			if (input != null) build.load(input);
		} catch (IOException exception) {
			// The report explicitly marks unavailable metadata; recording evidence is still useful.
			build.clear();
		}
		var mods = FabricLoader.getInstance().getAllMods().stream().map(mod -> Map.of(
			"id", mod.getMetadata().getId(), "version", mod.getMetadata().getVersion().getFriendlyString()))
			.sorted(java.util.Comparator.comparing(mod -> mod.get("id"))).toList();
		return Map.of("build", Map.of("revision", build.getProperty("revision", "unknown"),
			"modVersion", build.getProperty("modVersion", "unknown"),
			"minecraftVersion", build.getProperty("minecraftVersion", "unknown")),
			"mods", mods, "javaVersion", System.getProperty("java.version"),
			"providers", providers(config));
	}

	static Map<String, Object> providers(AgentConfig.LlmConfig config) {
		boolean codex = config.plannerBackend() == AgentConfig.PlannerBackend.CODEX_APP_SERVER;
		String provider = codex ? "codex-app-server" : host(config.providerBaseUrl());
		String model = codex ? config.codexAppServer().model() : config.model();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("controller", Map.of("backend", config.plannerBackend().wireValue(),
			"provider", provider, "model", model, "configured", config.isConfigured()));
		result.put("vision", Map.of("provider", host(config.visionProviderBaseUrl()),
			"model", config.visionModel(), "configured", config.visionConfigured()));
		var thinking = config.thinkingPlanner();
		// forRole retains the Codex model; blank OpenAI-compatible overrides use the controller model.
		String thinkingModel = thinking.enabled() && (codex || thinking.model().isBlank()) ? model : thinking.model();
		result.put("thinker", Map.of("provider", provider, "model", thinkingModel, "enabled", thinking.enabled()));
		return result;
	}

	private static String host(String endpoint) {
		try {
			String host = URI.create(endpoint == null ? "" : endpoint).getHost();
			return host == null ? "unknown" : host;
		} catch (IllegalArgumentException invalidEndpoint) { return "unknown"; }
	}
}
