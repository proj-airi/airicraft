package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentConfigLoaderTest {
	@Test void shortTermFindingsDefaultOnAndPreserveExplicitOptOut() {
		assertTrue(AgentConfig.defaults().llm().plannerSummarizeToolResults());
		var config = AgentConfigLoader.fromMapStrict(Map.of("plannerSummarizeToolResults", true), AgentConfig.defaults()).llm();
		assertTrue(config.plannerSummarizeToolResults());
		assertTrue(config.forRole("thinker", "medium").plannerSummarizeToolResults());
		var disabled = AgentConfigLoader.fromMapStrict(Map.of("plannerSummarizeToolResults", false), AgentConfig.defaults()).llm();
		assertFalse(disabled.forRole("thinker", "medium").plannerSummarizeToolResults());
		assertFalse(AgentConfigLoader.fromMapStrict(Map.of("plannerBackend", "codex-app-server"), AgentConfig.defaults()).llm().plannerSummarizeToolResults());
	}

	@Test void shortTermFindingsRejectBackendOwnedHistory() {
		assertThrows(IllegalArgumentException.class, () -> AgentConfigLoader.fromMapStrict(
			Map.of("plannerSummarizeToolResults", true, "plannerBackend", "codex-app-server"), AgentConfig.defaults()));
	}

	@Test void readsNativeImageLimitAndPreservesItForPlannerRoles() {
		assertEquals(8, AgentConfig.defaults().llm().plannerMaxImages());
		var config = AgentConfigLoader.fromMapStrict(Map.of("plannerMaxImages", 3), AgentConfig.defaults()).llm();
		assertEquals(3, config.plannerMaxImages());
		assertEquals(3, config.forRole("thinker", "high").plannerMaxImages());
		assertEquals(1, AgentConfigLoader.fromMapStrict(Map.of("plannerMaxImages", 0), AgentConfig.defaults()).llm().plannerMaxImages());
	}

	@Test void readsIndependentThinkingProfile() {
		var config = AgentConfigLoader.fromMapStrict(Map.of("model", "controller-model", "thinkingPlanner",
			Map.of("enabled", true, "model", "thinking-model", "reasoningEffort", "medium")), AgentConfig.defaults()).llm();
		assertTrue(config.thinkingPlanner().enabled());
		var controller = config.forRole(config.model(), "none");
		var thinking = config.forRole(config.thinkingPlanner().model(), config.thinkingPlanner().reasoningEffort());
		assertEquals("controller-model", controller.model());
		assertEquals("none", controller.reasoningEffort());
		assertEquals("thinking-model", thinking.model());
		assertEquals("medium", thinking.reasoningEffort());
		assertFalse(thinking.thinkingPlanner().enabled());
	}

	@Test
	void readsOptionalOpenAiReasoningEffortIndependentlyOfCodex() {
		assertEquals("", AgentConfig.defaults().llm().reasoningEffort());
		for (String effort : java.util.List.of("low", "none")) {
			var parsed = AgentConfigLoader.fromMapStrict(Map.of("plannerReasoningEffort", effort), AgentConfig.defaults());
			assertEquals(effort, parsed.llm().reasoningEffort());
			assertEquals("", parsed.llm().codexAppServer().reasoningEffort());
		}
	}

	@Test
	void defaultsToOpenAiCompatiblePlannerBackend() {
		AgentConfig.LlmConfig llm = AgentConfig.defaults().llm();

		assertEquals(AgentConfig.PlannerBackend.OPENAI_COMPATIBLE, llm.plannerBackend());
		assertEquals("codex", llm.codexAppServer().executable());
		assertEquals("", llm.codexAppServer().reasoningEffort());
		assertFalse(llm.backendManagedHistory());
		assertEquals("", llm.codexAppServer().serviceTier());
	}

	@Test
	void readsOptionalCodexServiceTier() {
		for (String tier : java.util.List.of("", "fast", "priority")) {
			var parsed = AgentConfigLoader.fromMapStrict(Map.of(
				"codexAppServer", Map.of("serviceTier", " " + tier + " ")
			), AgentConfig.defaults());
			assertEquals(tier, parsed.llm().codexAppServer().serviceTier());
		}
		assertThrows(IllegalArgumentException.class, () -> AgentConfigLoader.fromMapStrict(
			Map.of("codexAppServer", Map.of("serviceTier", java.util.List.of("fast"))), AgentConfig.defaults()));
	}

	@Test
	void readsCodexAppServerPlannerBackendWithoutApiKey() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"plannerBackend", "codex-app-server",
			"codexAppServer", Map.of(
				"executable", "/opt/codex/bin/codex",
				"model", "local-codex-model",
				"reasoningEffort", "high",
				"startupTimeoutMillis", 4321,
				"turnTimeoutMillis", 98765
			)
		), AgentConfig.defaults());

		assertEquals(AgentConfig.PlannerBackend.CODEX_APP_SERVER, parsed.llm().plannerBackend());
		assertEquals("/opt/codex/bin/codex", parsed.llm().codexAppServer().executable());
		assertEquals("local-codex-model", parsed.llm().codexAppServer().model());
		assertEquals("high", parsed.llm().codexAppServer().reasoningEffort());
		assertEquals(4321, parsed.llm().codexAppServer().startupTimeoutMillis());
		assertEquals(98765, parsed.llm().codexAppServer().turnTimeoutMillis());
		assertTrue(parsed.llm().isConfigured());
		assertTrue(parsed.llm().backendManagedHistory());
	}

	@Test
	void strictConfigRejectsUnknownPlannerBackend() {
		assertThrows(IllegalArgumentException.class, () -> AgentConfigLoader.fromMapStrict(
			Map.of("plannerBackend", "polling-codex"),
			AgentConfig.defaults()
		));
	}

	@Test
	void fromMapReadsVisionFieldsAndKeepsVisionDisabledWhenUnset() {
		AgentConfig defaults = AgentConfig.defaults();

		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"providerBaseUrl", "https://example.test/v1",
			"apiKey", "test-key",
			"model", "planner-model",
			"visionProviderBaseUrl", "https://vision.example.test/v1",
			"visionApiKey", "vision-key",
			"visionRequestTimeoutMillis", 7777,
			"visionImageDetail", "high",
			"plannerNativeVisionEnabled", true,
			"plannerUseJsonObjectResponseFormat", false
		), defaults);

		assertEquals("https://example.test/v1", parsed.llm().providerBaseUrl());
		assertEquals("test-key", parsed.llm().apiKey());
		assertEquals("planner-model", parsed.llm().model());
		assertEquals("https://vision.example.test/v1", parsed.llm().visionProviderBaseUrl());
		assertEquals("vision-key", parsed.llm().visionApiKey());
		assertEquals("", parsed.llm().visionModel());
		assertEquals(7777, parsed.llm().visionRequestTimeoutMillis());
		assertEquals(65_536, parsed.llm().plannerCompactionTriggerTokens());
		assertEquals(128, parsed.llm().plannerPendingSemanticEventCap());
		assertEquals(10, parsed.llm().plannerSessionCoalesceStepMillis());
		assertEquals(10, parsed.llm().plannerSessionCoalesceMinMillis());
		assertEquals(100, parsed.llm().plannerSessionCoalesceMaxMillis());
		assertEquals(30, parsed.idle().initialDelaySeconds());
		assertEquals(90, parsed.idle().cooldownSeconds());
		assertEquals("high", parsed.llm().visionImageDetail());
		assertEquals(true, parsed.llm().plannerNativeVisionEnabled());
		assertEquals(false, parsed.llm().plannerUseJsonObjectResponseFormat());
		assertFalse(parsed.llm().visionConfigured());
	}

	@Test
	void defaultsEnablePlannerJsonObjectResponseFormat() {
		assertEquals(true, AgentConfig.defaults().llm().plannerUseJsonObjectResponseFormat());
	}

	@Test
	void fromMapReadsAndNormalizesReflexFields() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"reflex", Map.of(
				"enabled", false,
				"lowAirTicks", -5,
				"defendMinHealthRatio", 1.5,
				"threatCooldownTicks", -2
			)
		), AgentConfig.defaults());

		assertFalse(parsed.reflex().enabled());
		assertEquals(0, parsed.reflex().lowAirTicks());
		assertEquals(1.0D, parsed.reflex().defendMinHealthRatio());
		assertEquals(0, parsed.reflex().threatCooldownTicks());
	}

	@Test
	void fromMapReadsAndClampsPerceptionBudgets() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"perception", Map.of(
				"enabled", false,
				"radius", 99,
				"positionsPerTick", 0,
				"raycastsPerTick", 8,
				"candidatesPerStep", 20,
				"entityEnterRange", 24,
				"entityExitRange", 10
			)
		), AgentConfig.defaults());

		assertFalse(parsed.perception().enabled());
		assertEquals(32, parsed.perception().radius());
		assertEquals(1, parsed.perception().positionsPerTick());
		assertEquals(8, parsed.perception().raycastsPerTick());
		assertEquals(20, parsed.perception().candidatesPerStep());
		assertEquals(24, parsed.perception().entityEnterRange());
		assertEquals(24, parsed.perception().entityExitRange(), "exit range never falls below the enter range");
	}

	@Test
	void missingPerceptionSectionUsesDefaults() {
		assertEquals(AgentConfig.PerceptionConfig.defaults(), AgentConfigLoader.fromMap(Map.of(), AgentConfig.defaults()).perception());
		assertEquals(AgentConfig.PerceptionConfig.defaults(), AgentConfig.defaults().withCharacter(null).perception());
	}

	@Test
	void fromMapReadsPlannerCoalesceFields() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"plannerPendingSemanticEventCap", 256,
			"plannerSessionCoalesceStepMillis", 25,
			"plannerSessionCoalesceMinMillis", 30,
			"plannerSessionCoalesceMaxMillis", 90
		), AgentConfig.defaults());

		assertEquals(256, parsed.llm().plannerPendingSemanticEventCap());
		assertEquals(25, parsed.llm().plannerSessionCoalesceStepMillis());
		assertEquals(30, parsed.llm().plannerSessionCoalesceMinMillis());
		assertEquals(90, parsed.llm().plannerSessionCoalesceMaxMillis());
	}

	@Test
	void fromMapNormalizesPlannerCoalesceFields() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"plannerPendingSemanticEventCap", -10,
			"plannerSessionCoalesceStepMillis", -10,
			"plannerSessionCoalesceMinMillis", -5,
			"plannerSessionCoalesceMaxMillis", -1
		), AgentConfig.defaults());

		assertEquals(1, parsed.llm().plannerPendingSemanticEventCap());
		assertEquals(0, parsed.llm().plannerSessionCoalesceStepMillis());
		assertEquals(0, parsed.llm().plannerSessionCoalesceMinMillis());
		assertEquals(0, parsed.llm().plannerSessionCoalesceMaxMillis());

		AgentConfig reordered = AgentConfigLoader.fromMap(Map.of(
			"plannerSessionCoalesceStepMillis", 5,
			"plannerSessionCoalesceMinMillis", 50,
			"plannerSessionCoalesceMaxMillis", 20
		), AgentConfig.defaults());

		assertEquals(5, reordered.llm().plannerSessionCoalesceStepMillis());
		assertEquals(50, reordered.llm().plannerSessionCoalesceMinMillis());
		assertEquals(50, reordered.llm().plannerSessionCoalesceMaxMillis());
	}

	@Test
	void fromMapReadsIdleTimerFields() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"idleInitialDelaySeconds", 5,
			"idleCooldownSeconds", 0
		), AgentConfig.defaults());

		assertEquals(5, parsed.idle().initialDelaySeconds());
		assertEquals(0, parsed.idle().cooldownSeconds());
		assertFalse(parsed.idle().automaticEnabled());
	}

	@Test
	void fromMapNormalizesIdleTimerFields() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"idleInitialDelaySeconds", -5,
			"idleCooldownSeconds", -10
		), AgentConfig.defaults());

		assertEquals(0, parsed.idle().initialDelaySeconds());
		assertEquals(0, parsed.idle().cooldownSeconds());
		assertFalse(parsed.idle().automaticEnabled());
	}

	@Test
	void fromMapReadsObservabilityResourceAttributes() {
		AgentConfig defaults = AgentConfig.defaults();

		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"observability", Map.of(
				"enabled", true,
				"vendorProfile", "weave",
				"resourceAttributes", Map.of(
					"wandb.entity", "shinohara-rin",
					"wandb.project", "airicraft"
				)
			)
		), defaults);

		assertEquals(true, parsed.observability().enabled());
		assertEquals("weave", parsed.observability().vendorProfile());
		assertEquals("shinohara-rin", parsed.observability().resourceAttributes().get("wandb.entity"));
		assertEquals("airicraft", parsed.observability().resourceAttributes().get("wandb.project"));
	}

	@Test
	void fromMapStrictRejectsMalformedObservability() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
			AgentConfigLoader.fromMapStrict(Map.of(
				"observability", "enabled"
			), AgentConfig.defaults())
		);

		assertEquals("observability must be a YAML mapping", exception.getMessage());
	}
}
