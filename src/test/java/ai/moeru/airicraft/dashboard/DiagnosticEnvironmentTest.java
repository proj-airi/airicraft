package ai.moeru.airicraft.dashboard;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.AgentConfigLoader;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticEnvironmentTest {
	@Test
	void identifiesProvidersWithoutCopyingCredentialsOrEndpointPaths() {
		var config = AgentConfigLoader.fromMapStrict(Map.of("providerBaseUrl", "https://user:PRIVATE@example.org/PRIVATE?key=PRIVATE",
			"apiKey", "PRIVATE", "model", "test-model", "visionModel", "vision-model"), AgentConfig.defaults());
		String json = new Gson().toJson(DiagnosticEnvironment.providers(config.llm()));
		assertTrue(json.contains("example.org"));
		assertTrue(json.contains("test-model"));
		assertTrue(json.contains("vision-model"));
		assertFalse(json.contains("PRIVATE"));
	}

	@Test
	void identifiesTheActiveCodexModelAndHandlesMalformedProviderUrls() {
		var config = AgentConfigLoader.fromMapStrict(Map.of("plannerBackend", "codex-app-server",
			"codexAppServer", Map.of("model", "codex-test-model"), "providerBaseUrl", "not a uri PRIVATE"), AgentConfig.defaults());
		String json = new Gson().toJson(DiagnosticEnvironment.providers(config.llm()));
		assertTrue(json.contains("codex-test-model"));
		assertTrue(json.contains("codex-app-server"));
		assertFalse(json.contains("PRIVATE"));
	}

	@ParameterizedTest
	@CsvSource({
		"openai-compatible, true, '', gpt-example",
		"openai-compatible, true, '   ', gpt-example",
		"openai-compatible, true, thinking-override, thinking-override",
		"openai-compatible, false, '', ''",
		"codex-app-server, true, '', codex-model",
		"codex-app-server, true, thinking-override, codex-model"
	})
	void reportsTheEffectiveModelForEnabledThinkingPlanners(String backend, boolean enabled, String override, String expectedModel) {
		var config = AgentConfigLoader.fromMapStrict(Map.of("plannerBackend", backend, "model", "gpt-example",
			"codexAppServer", Map.of("model", "codex-model"),
			"thinkingPlanner", Map.of("enabled", enabled, "model", override)), AgentConfig.defaults());
		var thinker = new Gson().toJsonTree(DiagnosticEnvironment.providers(config.llm())).getAsJsonObject().getAsJsonObject("thinker");
		assertEquals(expectedModel, thinker.get("model").getAsString());
		assertEquals(enabled, thinker.get("enabled").getAsBoolean());
	}
}
