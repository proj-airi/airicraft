package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.character.CharacterCard;

public record AgentConfig(
	boolean verificationEnabled,
	boolean verificationAutoRunAll,
	LlmConfig llm,
	IdleConfig idle,
	ReflexConfig reflex,
	ObservabilityConfig observability,
	CharacterCard character
) {
	public AgentConfig {
		llm = llm == null ? LlmConfig.defaults() : llm;
		idle = idle == null ? IdleConfig.defaults() : idle;
		reflex = reflex == null ? ReflexConfig.defaults() : reflex;
		observability = observability == null ? ObservabilityConfig.defaults() : observability;
		character = character == null ? CharacterCard.defaults() : character;
	}

	public AgentConfig(
		boolean verificationEnabled,
		boolean verificationAutoRunAll,
		LlmConfig llm,
		IdleConfig idle,
		ReflexConfig reflex,
		ObservabilityConfig observability
	) {
		this(verificationEnabled, verificationAutoRunAll, llm, idle, reflex, observability, null);
	}

	/** The character comes from its own file, not agent.yml; loaders attach it here. */
	public AgentConfig withCharacter(CharacterCard nextCharacter) {
		return new AgentConfig(verificationEnabled, verificationAutoRunAll, llm, idle, reflex, observability, nextCharacter);
	}

	public AgentConfig(
		boolean verificationEnabled,
		boolean verificationAutoRunAll,
		LlmConfig llm,
		IdleConfig idle,
		ObservabilityConfig observability
	) {
		this(
			verificationEnabled,
			verificationAutoRunAll,
			llm,
			idle,
			ReflexConfig.defaults(),
			observability
		);
	}

	public static AgentConfig defaults() {
		return new AgentConfig(
			false,
			false,
			LlmConfig.defaults(),
			IdleConfig.defaults(),
			ReflexConfig.defaults(),
			ObservabilityConfig.defaults()
		);
	}

	public record ThinkingPlannerConfig(boolean enabled, String model, String reasoningEffort) {
		public ThinkingPlannerConfig {
			model = model == null ? "" : model.trim();
			reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? "medium" : reasoningEffort.trim();
		}
		public static ThinkingPlannerConfig defaults() { return new ThinkingPlannerConfig(false, "", "medium"); }
	}

	public record LlmConfig(
		String providerBaseUrl,
		String apiKey,
		String model,
		String visionProviderBaseUrl,
		String visionApiKey,
		String visionModel,
		int requestTimeoutMillis,
		int visionRequestTimeoutMillis,
		int maxRecentConversationTurns,
		int plannerCompactionTriggerTokens,
		int plannerPendingSemanticEventCap,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		String visionImageDetail,
		boolean plannerNativeVisionEnabled,
		boolean plannerUseJsonObjectResponseFormat,
		PlannerBackend plannerBackend,
		CodexAppServerConfig codexAppServer,
		String reasoningEffort,
		ThinkingPlannerConfig thinkingPlanner,
		int plannerMaxImages,
		boolean plannerSummarizeToolResults
	) {
		public LlmConfig {
			plannerMaxImages = Math.max(1, plannerMaxImages);
			thinkingPlanner = thinkingPlanner == null ? ThinkingPlannerConfig.defaults() : thinkingPlanner;
			reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim();
			plannerPendingSemanticEventCap = Math.max(1, plannerPendingSemanticEventCap);
			plannerSessionCoalesceStepMillis = Math.max(0, plannerSessionCoalesceStepMillis);
			plannerSessionCoalesceMinMillis = Math.max(0, plannerSessionCoalesceMinMillis);
			plannerSessionCoalesceMaxMillis = Math.max(plannerSessionCoalesceMinMillis, plannerSessionCoalesceMaxMillis);
			plannerBackend = plannerBackend == null ? PlannerBackend.OPENAI_COMPATIBLE : plannerBackend;
			if (plannerSummarizeToolResults && plannerBackend != PlannerBackend.OPENAI_COMPATIBLE)
				throw new IllegalArgumentException("plannerSummarizeToolResults requires openai-compatible conversation history");
			codexAppServer = codexAppServer == null ? CodexAppServerConfig.defaults() : codexAppServer;
		}

		public LlmConfig(
			String providerBaseUrl,
			String apiKey,
			String model,
			String visionProviderBaseUrl,
			String visionApiKey,
			String visionModel,
			int requestTimeoutMillis,
			int visionRequestTimeoutMillis,
			int maxRecentConversationTurns,
			int plannerCompactionTriggerTokens,
			int plannerPendingSemanticEventCap,
			int plannerSessionMaxConcurrentAttempts,
			int plannerSessionCoalesceStepMillis,
			int plannerSessionCoalesceMinMillis,
			int plannerSessionCoalesceMaxMillis,
			String visionImageDetail,
			boolean plannerNativeVisionEnabled,
			boolean plannerUseJsonObjectResponseFormat,
			PlannerBackend plannerBackend,
			CodexAppServerConfig codexAppServer,
			String reasoningEffort,
			ThinkingPlannerConfig thinkingPlanner,
			int plannerMaxImages
		) {
			this(
				providerBaseUrl,
				apiKey,
				model,
				visionProviderBaseUrl,
				visionApiKey,
				visionModel,
				requestTimeoutMillis,
				visionRequestTimeoutMillis,
				maxRecentConversationTurns,
				plannerCompactionTriggerTokens,
				plannerPendingSemanticEventCap,
				plannerSessionMaxConcurrentAttempts,
				plannerSessionCoalesceStepMillis,
				plannerSessionCoalesceMinMillis,
				plannerSessionCoalesceMaxMillis,
				visionImageDetail,
				plannerNativeVisionEnabled,
				plannerUseJsonObjectResponseFormat,
				plannerBackend,
				codexAppServer,
				reasoningEffort,
				thinkingPlanner,
				plannerMaxImages,
				plannerBackend != PlannerBackend.CODEX_APP_SERVER
			);
		}

		public LlmConfig(
			String providerBaseUrl,
			String apiKey,
			String model,
			String visionProviderBaseUrl,
			String visionApiKey,
			String visionModel,
			int requestTimeoutMillis,
			int visionRequestTimeoutMillis,
			int maxRecentConversationTurns,
			int plannerCompactionTriggerTokens,
			int plannerPendingSemanticEventCap,
			int plannerSessionMaxConcurrentAttempts,
			int plannerSessionCoalesceStepMillis,
			int plannerSessionCoalesceMinMillis,
			int plannerSessionCoalesceMaxMillis,
			String visionImageDetail,
			boolean plannerNativeVisionEnabled,
			boolean plannerUseJsonObjectResponseFormat,
			PlannerBackend plannerBackend,
			CodexAppServerConfig codexAppServer,
			String reasoningEffort,
			ThinkingPlannerConfig thinkingPlanner
		) {
			this(
				providerBaseUrl,
				apiKey,
				model,
				visionProviderBaseUrl,
				visionApiKey,
				visionModel,
				requestTimeoutMillis,
				visionRequestTimeoutMillis,
				maxRecentConversationTurns,
				plannerCompactionTriggerTokens,
				plannerPendingSemanticEventCap,
				plannerSessionMaxConcurrentAttempts,
				plannerSessionCoalesceStepMillis,
				plannerSessionCoalesceMinMillis,
				plannerSessionCoalesceMaxMillis,
				visionImageDetail,
				plannerNativeVisionEnabled,
				plannerUseJsonObjectResponseFormat,
				plannerBackend,
				codexAppServer,
				reasoningEffort,
				thinkingPlanner,
				8
			);
		}

		public LlmConfig(
			String providerBaseUrl,
			String apiKey,
			String model,
			String visionProviderBaseUrl,
			String visionApiKey,
			String visionModel,
			int requestTimeoutMillis,
			int visionRequestTimeoutMillis,
			int maxRecentConversationTurns,
			int plannerCompactionTriggerTokens,
			int plannerPendingSemanticEventCap,
			int plannerSessionMaxConcurrentAttempts,
			int plannerSessionCoalesceStepMillis,
			int plannerSessionCoalesceMinMillis,
			int plannerSessionCoalesceMaxMillis,
			String visionImageDetail,
			boolean plannerNativeVisionEnabled,
			boolean plannerUseJsonObjectResponseFormat,
			PlannerBackend plannerBackend,
			CodexAppServerConfig codexAppServer,
			String reasoningEffort
		) {
			this(
				providerBaseUrl,
				apiKey,
				model,
				visionProviderBaseUrl,
				visionApiKey,
				visionModel,
				requestTimeoutMillis,
				visionRequestTimeoutMillis,
				maxRecentConversationTurns,
				plannerCompactionTriggerTokens,
				plannerPendingSemanticEventCap,
				plannerSessionMaxConcurrentAttempts,
				plannerSessionCoalesceStepMillis,
				plannerSessionCoalesceMinMillis,
				plannerSessionCoalesceMaxMillis,
				visionImageDetail,
				plannerNativeVisionEnabled,
				plannerUseJsonObjectResponseFormat,
				plannerBackend,
				codexAppServer,
				reasoningEffort,
				ThinkingPlannerConfig.defaults()
			);
		}

		public LlmConfig(
			String providerBaseUrl,
			String apiKey,
			String model,
			String visionProviderBaseUrl,
			String visionApiKey,
			String visionModel,
			int requestTimeoutMillis,
			int visionRequestTimeoutMillis,
			int maxRecentConversationTurns,
			int plannerCompactionTriggerTokens,
			int plannerPendingSemanticEventCap,
			int plannerSessionMaxConcurrentAttempts,
			int plannerSessionCoalesceStepMillis,
			int plannerSessionCoalesceMinMillis,
			int plannerSessionCoalesceMaxMillis,
			String visionImageDetail,
			boolean plannerNativeVisionEnabled,
			boolean plannerUseJsonObjectResponseFormat,
			PlannerBackend plannerBackend,
			CodexAppServerConfig codexAppServer
		) {
			this(
				providerBaseUrl,
				apiKey,
				model,
				visionProviderBaseUrl,
				visionApiKey,
				visionModel,
				requestTimeoutMillis,
				visionRequestTimeoutMillis,
				maxRecentConversationTurns,
				plannerCompactionTriggerTokens,
				plannerPendingSemanticEventCap,
				plannerSessionMaxConcurrentAttempts,
				plannerSessionCoalesceStepMillis,
				plannerSessionCoalesceMinMillis,
				plannerSessionCoalesceMaxMillis,
				visionImageDetail,
				plannerNativeVisionEnabled,
				plannerUseJsonObjectResponseFormat,
				plannerBackend,
				codexAppServer,
				""
			);
		}

		public LlmConfig(
			String providerBaseUrl,
			String apiKey,
			String model,
			String visionProviderBaseUrl,
			String visionApiKey,
			String visionModel,
			int requestTimeoutMillis,
			int visionRequestTimeoutMillis,
			int maxRecentConversationTurns,
			int plannerCompactionTriggerTokens,
			int plannerPendingSemanticEventCap,
			int plannerSessionMaxConcurrentAttempts,
			int plannerSessionCoalesceStepMillis,
			int plannerSessionCoalesceMinMillis,
			int plannerSessionCoalesceMaxMillis,
			String visionImageDetail,
			boolean plannerNativeVisionEnabled,
			boolean plannerUseJsonObjectResponseFormat
		) {
			this(
				providerBaseUrl,
				apiKey,
				model,
				visionProviderBaseUrl,
				visionApiKey,
				visionModel,
				requestTimeoutMillis,
				visionRequestTimeoutMillis,
				maxRecentConversationTurns,
				plannerCompactionTriggerTokens,
				plannerPendingSemanticEventCap,
				plannerSessionMaxConcurrentAttempts,
				plannerSessionCoalesceStepMillis,
				plannerSessionCoalesceMinMillis,
				plannerSessionCoalesceMaxMillis,
				visionImageDetail,
				plannerNativeVisionEnabled,
				plannerUseJsonObjectResponseFormat,
				PlannerBackend.OPENAI_COMPATIBLE,
				CodexAppServerConfig.defaults()
			);
		}

		public LlmConfig(
			String providerBaseUrl,
			String apiKey,
			String model,
			String visionProviderBaseUrl,
			String visionApiKey,
			String visionModel,
			int requestTimeoutMillis,
			int visionRequestTimeoutMillis,
			int maxRecentConversationTurns,
			int plannerCompactionTriggerTokens,
			String visionImageDetail,
			boolean plannerNativeVisionEnabled
		) {
			this(
				providerBaseUrl,
				apiKey,
				model,
				visionProviderBaseUrl,
				visionApiKey,
				visionModel,
				requestTimeoutMillis,
				visionRequestTimeoutMillis,
				maxRecentConversationTurns,
				plannerCompactionTriggerTokens,
				visionImageDetail,
				plannerNativeVisionEnabled,
				true
			);
		}

		public LlmConfig(
			String providerBaseUrl,
			String apiKey,
			String model,
			String visionProviderBaseUrl,
			String visionApiKey,
			String visionModel,
			int requestTimeoutMillis,
			int visionRequestTimeoutMillis,
			int maxRecentConversationTurns,
			int plannerCompactionTriggerTokens,
			String visionImageDetail,
			boolean plannerNativeVisionEnabled,
			boolean plannerUseJsonObjectResponseFormat
		) {
			this(
				providerBaseUrl,
				apiKey,
				model,
				visionProviderBaseUrl,
				visionApiKey,
				visionModel,
				requestTimeoutMillis,
				visionRequestTimeoutMillis,
				maxRecentConversationTurns,
				plannerCompactionTriggerTokens,
				128,
				3,
				10,
				10,
				100,
				visionImageDetail,
				plannerNativeVisionEnabled,
				plannerUseJsonObjectResponseFormat
			);
		}

		public LlmConfig forRole(String profileModel, String profileEffort) {
			return new LlmConfig(
				providerBaseUrl,
				apiKey,
				profileModel,
				visionProviderBaseUrl,
				visionApiKey,
				visionModel,
				requestTimeoutMillis,
				visionRequestTimeoutMillis,
				maxRecentConversationTurns,
				plannerCompactionTriggerTokens,
				plannerPendingSemanticEventCap,
				plannerSessionMaxConcurrentAttempts,
				plannerSessionCoalesceStepMillis,
				plannerSessionCoalesceMinMillis,
				plannerSessionCoalesceMaxMillis,
				visionImageDetail,
				plannerNativeVisionEnabled,
				plannerUseJsonObjectResponseFormat,
				plannerBackend,
				codexAppServer,
				profileEffort,
				ThinkingPlannerConfig.defaults(),
				plannerMaxImages,
				plannerSummarizeToolResults
			);
		}

		public static LlmConfig defaults() {
			return new LlmConfig(
				"https://api.openai.com/v1",
				"",
				"",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				128,
				3,
				10,
					10,
					100,
					"low",
					false,
					true
				);
		}

		public boolean isConfigured() {
			if (plannerBackend == PlannerBackend.CODEX_APP_SERVER) {
				return codexAppServer.isConfigured();
			}
			return providerBaseUrl != null
				&& !providerBaseUrl.isBlank()
				&& apiKey != null
				&& !apiKey.isBlank()
				&& model != null
				&& !model.isBlank();
		}

		public boolean backendManagedHistory() {
			return plannerBackend == PlannerBackend.CODEX_APP_SERVER;
		}

		public boolean visionConfigured() {
			return visionProviderBaseUrl != null
				&& !visionProviderBaseUrl.isBlank()
				&& visionApiKey != null
				&& !visionApiKey.isBlank()
				&& visionModel != null
				&& !visionModel.isBlank();
		}

		public ai.moeru.airicraft.agent.llm.PlannerVisionMode plannerVisionMode() {
			return plannerNativeVisionEnabled
				? ai.moeru.airicraft.agent.llm.PlannerVisionMode.NATIVE_TOOL_IMAGE
				: ai.moeru.airicraft.agent.llm.PlannerVisionMode.EXTERNAL_SUMMARY;
		}
	}

	public enum PlannerBackend {
		OPENAI_COMPATIBLE("openai-compatible"),
		CODEX_APP_SERVER("codex-app-server");

		private final String wireValue;

		PlannerBackend(String wireValue) {
			this.wireValue = wireValue;
		}

		public String wireValue() {
			return wireValue;
		}

		public static PlannerBackend fromWireValue(String value) {
			for (PlannerBackend backend : values()) {
				if (backend.wireValue.equalsIgnoreCase(value == null ? "" : value.trim())) {
					return backend;
				}
			}
			throw new IllegalArgumentException("Unsupported plannerBackend: " + value);
		}
	}

	public record CodexAppServerConfig(
		String executable,
		String model,
		String reasoningEffort,
		String serviceTier,
		int startupTimeoutMillis,
		int turnTimeoutMillis
	) {
		public CodexAppServerConfig {
			executable = executable == null ? "" : executable.trim();
			model = model == null ? "" : model.trim();
			reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim();
			serviceTier = serviceTier == null ? "" : serviceTier.trim();
			startupTimeoutMillis = Math.max(1, startupTimeoutMillis);
			turnTimeoutMillis = Math.max(1, turnTimeoutMillis);
		}

		public static CodexAppServerConfig defaults() {
			return new CodexAppServerConfig("codex", "", "", "", 10_000, 120_000);
		}

		public boolean isConfigured() {
			return !executable.isBlank();
		}
	}

	public record IdleConfig(
		int initialDelaySeconds,
		int cooldownSeconds
	) {
		public IdleConfig {
			initialDelaySeconds = Math.max(0, initialDelaySeconds);
			cooldownSeconds = Math.max(0, cooldownSeconds);
		}

		public static IdleConfig defaults() {
			return new IdleConfig(30, 90);
		}

		public boolean automaticEnabled() {
			return initialDelaySeconds > 0 && cooldownSeconds > 0;
		}
	}

	public record ReflexConfig(
		boolean enabled,
		int lowAirTicks,
		double defendMinHealthRatio,
		int threatCooldownTicks
	) {
		public ReflexConfig {
			lowAirTicks = Math.max(0, lowAirTicks);
			defendMinHealthRatio = Math.max(0.0D, Math.min(1.0D, defendMinHealthRatio));
			threatCooldownTicks = Math.max(0, threatCooldownTicks);
		}

		public static ReflexConfig defaults() {
			return new ReflexConfig(true, 100, 0.5D, 60);
		}
	}

	public record ObservabilityConfig(
		boolean enabled,
		String exporter,
		String otlpEndpoint,
		java.util.Map<String, String> otlpHeaders,
		java.util.Map<String, String> resourceAttributes,
		String vendorProfile,
		boolean debugLogExports,
		boolean captureInputs,
		boolean captureOutputs,
		boolean captureImages
	) {
		public ObservabilityConfig {
			exporter = exporter == null ? "otlp_http" : exporter;
			otlpEndpoint = otlpEndpoint == null ? "" : otlpEndpoint;
			otlpHeaders = otlpHeaders == null ? java.util.Map.of() : java.util.Map.copyOf(otlpHeaders);
			resourceAttributes = resourceAttributes == null ? java.util.Map.of() : java.util.Map.copyOf(resourceAttributes);
			vendorProfile = vendorProfile == null ? "generic" : vendorProfile;
		}

		public static ObservabilityConfig defaults() {
			return new ObservabilityConfig(
				false,
				"otlp_http",
				"",
				java.util.Map.of(),
				java.util.Map.of(),
				"generic",
				false,
				false,
				false,
				false
			);
		}
	}

}
