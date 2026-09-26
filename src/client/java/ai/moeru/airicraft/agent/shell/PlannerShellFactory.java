package ai.moeru.airicraft.agent.shell;

import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.character.CharacterPrompt;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.dialogue.DialogueMessages;
import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationBridge;
import ai.moeru.airicraft.agent.integration.map.MapPlannerToolProvider;
import ai.moeru.airicraft.agent.integration.rei.ReiRecipeSearchToolProvider;
import ai.moeru.airicraft.agent.llm.CurrentInventoryService;
import ai.moeru.airicraft.agent.llm.CurrentWorldQueryService;
import ai.moeru.airicraft.agent.llm.CurrentWorldQueryToolProvider;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionService;
import ai.moeru.airicraft.agent.llm.CompositePlannerLifecycleListener;
import ai.moeru.airicraft.agent.llm.LlmBackend;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleChatClient;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleLlmBackend;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleVisionBackend;
import ai.moeru.airicraft.agent.llm.PlannerActionToolExecutor;
import ai.moeru.airicraft.agent.llm.PlannerCompactionService;
import ai.moeru.airicraft.agent.llm.PlannerContextAggregator;
import ai.moeru.airicraft.agent.llm.PlannerExecutor;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.llm.PlannerToolExecutionObserver;
import ai.moeru.airicraft.agent.llm.PlannerToolNarrationSink;
import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
import ai.moeru.airicraft.agent.llm.WorldFeatureSearchService;
import ai.moeru.airicraft.agent.llm.WorldFeatureSearchToolProvider;
import ai.moeru.airicraft.agent.memory.PlaceMemoryToolProvider;
import ai.moeru.airicraft.agent.llm.codex.CodexAppServerLlmBackend;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.recording.PlannerCallJournal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class PlannerShellFactory {
	private PlannerShellFactory() {
	}

	public static PlannerShellComponents create(
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		AgentObservability observability,
		Clock clock,
		AgentDebugRecorder debugRecorder
	) {
		return create(
			config,
			screenshotService,
			observability,
			clock,
			debugRecorder,
			PlannerActionToolExecutor.DISABLED,
			PlannerToolNarrationSink.NO_OP,
			PlannerToolExecutionObserver.NO_OP,
			ignored -> {
			},
			new CameraController()
		);
	}

	public static PlannerShellComponents create(
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		AgentObservability observability,
		Clock clock,
		AgentDebugRecorder debugRecorder,
		PlannerActionToolExecutor actionToolExecutor,
		PlannerToolNarrationSink narrationSink
	) {
		return create(
			config,
			screenshotService,
			observability,
			clock,
			debugRecorder,
			actionToolExecutor,
			narrationSink,
			PlannerToolExecutionObserver.NO_OP,
			ignored -> {
			},
			new CameraController()
		);
	}

	public static PlannerShellComponents create(
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		AgentObservability observability,
		Clock clock,
		AgentDebugRecorder debugRecorder,
		PlannerActionToolExecutor actionToolExecutor,
		PlannerToolNarrationSink narrationSink,
		PlannerToolExecutionObserver toolExecutionObserver,
		Consumer<List<BlockPos>> worldReadObserver
	) {
		return create(
			config,
			screenshotService,
			observability,
			clock,
			debugRecorder,
			actionToolExecutor,
			narrationSink,
			toolExecutionObserver,
			worldReadObserver,
			new CameraController()
		);
	}

	public static PlannerShellComponents create(
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		AgentObservability observability,
		Clock clock,
		AgentDebugRecorder debugRecorder,
		PlannerActionToolExecutor actionToolExecutor,
		PlannerToolNarrationSink narrationSink,
		PlannerToolExecutionObserver toolExecutionObserver,
		Consumer<List<BlockPos>> worldReadObserver,
		CameraController cameraController
	) {
		return create(
			config,
			screenshotService,
			observability,
			clock,
			debugRecorder,
			actionToolExecutor,
			narrationSink,
			toolExecutionObserver,
			worldReadObserver,
			cameraController,
			() -> -1L
		);
	}

	public static PlannerShellComponents create(
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		AgentObservability observability,
		Clock clock,
		AgentDebugRecorder debugRecorder,
		PlannerActionToolExecutor actionToolExecutor,
		PlannerToolNarrationSink narrationSink,
		PlannerToolExecutionObserver toolExecutionObserver,
		Consumer<List<BlockPos>> worldReadObserver,
		CameraController cameraController,
		LongSupplier serverTickSupplier
	) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(screenshotService, "screenshotService");
		Objects.requireNonNull(observability, "observability");
		CameraController effectiveCameraController = Objects.requireNonNull(cameraController, "cameraController");
		PlannerActionToolExecutor effectiveActionToolExecutor = Objects.requireNonNull(actionToolExecutor, "actionToolExecutor");
		PlannerToolNarrationSink effectiveNarrationSink = Objects.requireNonNull(narrationSink, "narrationSink");
		PlannerToolExecutionObserver effectiveToolExecutionObserver = Objects.requireNonNull(toolExecutionObserver, "toolExecutionObserver");
		Consumer<List<BlockPos>> effectiveWorldReadObserver = Objects.requireNonNull(worldReadObserver, "worldReadObserver");
		LongSupplier effectiveServerTickSupplier = Objects.requireNonNull(serverTickSupplier, "serverTickSupplier");
		Clock effectiveClock = Objects.requireNonNull(clock, "clock");
		PlannerShellJournal journal = new PlannerShellJournal(128, effectiveClock);
		CurrentViewVisionService visionService = new CurrentViewVisionService(
			screenshotService,
			new OpenAiCompatibleVisionBackend(config.llm(), observability),
			MinecraftClient::getInstance,
			observability,
			effectiveCameraController
		);
		CurrentInventoryService inventoryService = new CurrentInventoryService(MinecraftClient::getInstance);
		CurrentWorldQueryService worldQueryService = new CurrentWorldQueryService(MinecraftClient::getInstance);
		WorldFeatureSearchService worldFeatureSearchService = new WorldFeatureSearchService(MinecraftClient::getInstance);
		var plannerGoal = new ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			return client == null || client.world == null || client.getServer() == null ? null
				: client.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT);
		});
		plannerGoal.refreshWorld();
		var scriptedQueries = ai.moeru.airicraft.agent.llm.WorldQueryScriptToolProvider.forClient(effectiveServerTickSupplier, effectiveWorldReadObserver);
		var sharedProviders = new java.util.ArrayList<>(List.<ai.moeru.airicraft.agent.llm.PlannerToolProvider>of(
			new ai.moeru.airicraft.agent.work.WorkToolProvider(effectiveActionToolExecutor),
			new ai.moeru.airicraft.agent.llm.PlannerQueueToolProvider(effectiveActionToolExecutor),
			new ai.moeru.airicraft.agent.spatial.TravelPolicyToolProvider(),
			new CurrentWorldQueryToolProvider(worldQueryService, result -> effectiveWorldReadObserver.accept(result.observedPositions())),
			scriptedQueries,
			new ai.moeru.airicraft.agent.llm.SelfToolProvider(scriptedQueries),
			new ai.moeru.airicraft.agent.llm.PolicyDocsToolProvider(),
			new WorldFeatureSearchToolProvider(worldFeatureSearchService, result -> effectiveWorldReadObserver.accept(result.observedPositions())),
			PlaceMemoryToolProvider.forClient(),
			new ai.moeru.airicraft.agent.memory.InteractionLogbookToolProvider(),
			new ai.moeru.airicraft.agent.llm.CaveSurveyToolProvider(effectiveWorldReadObserver),
			new ai.moeru.airicraft.agent.llm.CaveMapToolProvider(),
			new ai.moeru.airicraft.agent.llm.PathfindSettingsToolProvider(),
			new ReiRecipeSearchToolProvider(),
			new MapPlannerToolProvider(MapIntegrationBridge::registry)
		));
		// Hosted testers are mid-game; a planner bug report must never freeze their world.
		if (ai.moeru.airicraft.playtest.AutomaticPlaytestRuntime.enabled() && !ai.moeru.airicraft.playtest.AutomaticPlaytestRuntime.hosted()) {
			sharedProviders.add(new ai.moeru.airicraft.playtest.SomethingWrongToolProvider(
				description -> ai.moeru.airicraft.AiricraftClient.runtimeController().automaticPlaytest().report(description),
				() -> ai.moeru.airicraft.AiricraftClient.runtimeController().automaticPlaytest().resultCommitted(),
				command -> MinecraftClient.getInstance().execute(command)));
		}
		boolean dual = config.llm().thinkingPlanner().enabled();
		if (dual && config.llm().plannerBackend() != AgentConfig.PlannerBackend.OPENAI_COMPATIBLE)
			throw new IllegalArgumentException("thinkingPlanner requires the openai-compatible backend");
		var handoff = new ai.moeru.airicraft.agent.llm.delegation.PlannerDelegation();
		var controllerRef = new java.util.concurrent.atomic.AtomicReference<PlannerOrchestrator>();
		var dialogueRef = new java.util.concurrent.atomic.AtomicReference<DialogueRuntime>();
		java.util.concurrent.Executor clientExecutor = command -> MinecraftClient.getInstance().execute(command);
		var controllerProviders = new java.util.ArrayList<>(sharedProviders);
		controllerProviders.add(new ai.moeru.airicraft.agent.llm.goal.PlannerGoalToolProvider(plannerGoal, clientExecutor, true, () -> !handoff.active()));
		if (dual) controllerProviders.add(new ai.moeru.airicraft.agent.llm.delegation.PlannerDelegationToolProvider(
			ai.moeru.airicraft.agent.llm.delegation.PlannerDelegationToolProvider.Role.CONTROLLER, handoff, clientExecutor,
			plannerGoal::context, () -> dialogueRef.get().delegationWorkIdle()));
		PlannerToolRegistry toolRegistry = PlannerToolRegistry.of(controllerProviders.toArray(ai.moeru.airicraft.agent.llm.PlannerToolProvider[]::new));
		if (dual || config.llm().plannerSummarizeToolResults()) toolRegistry.freezeToolPrefix();
		var controllerConfig = dual ? config.llm().forRole(config.llm().model(), "none") : config.llm();
		String cacheSession = dual ? "airicraft:" + java.util.UUID.randomUUID() : null;
		PlannerCallJournal plannerCallJournal = new PlannerCallJournal(effectiveClock, effectiveServerTickSupplier,
			controllerConfig.plannerBackend().wireValue(), plannerModelName(controllerConfig), toolRegistry::openAiTools);
		// Both roles speak to players, so they share one character; it is fixed until reload for prompt caching.
		String characterPrompt = CharacterPrompt.render(config.character(), inGameName());
		PlannerOrchestrator orchestrator = createOrchestrator(controllerConfig, toolRegistry, visionService, inventoryService,
			effectiveClock, observability, debugRecorder, effectiveActionToolExecutor, effectiveNarrationSink,
			effectiveToolExecutionObserver, CompositePlannerLifecycleListener.of(journal, plannerCallJournal),
			dual ? cacheSession + ":controller" : null, characterPrompt);
		controllerRef.set(orchestrator);
		DialogueRuntime dialogue = new DialogueRuntime(orchestrator, config.llm().maxRecentConversationTurns(), effectiveClock, plannerGoal);
		dialogue.configureMessages(DialogueMessages.DEFAULTS.withOverrides(config.character().messages()));
		dialogueRef.set(dialogue);

		if (dual) {
			var thinkingProviders = new java.util.ArrayList<>(sharedProviders);
			thinkingProviders.add(new ai.moeru.airicraft.agent.llm.goal.PlannerGoalToolProvider(plannerGoal, clientExecutor, false, () -> false));
			thinkingProviders.add(new ai.moeru.airicraft.agent.llm.delegation.PlannerDelegationToolProvider(
				ai.moeru.airicraft.agent.llm.delegation.PlannerDelegationToolProvider.Role.THINKING, handoff, clientExecutor,
				() -> "", dialogue::delegationWorkIdle));
			var thinkingRegistry = PlannerToolRegistry.of(thinkingProviders.toArray(ai.moeru.airicraft.agent.llm.PlannerToolProvider[]::new));
			thinkingRegistry.freezeToolPrefix();
			thinkingRegistry.shareReferences(toolRegistry);
			var thinkingProfile = config.llm().thinkingPlanner();
			var thinkingConfig = config.llm().forRole(thinkingProfile.model().isBlank() ? config.llm().model() : thinkingProfile.model(), thinkingProfile.reasoningEffort());
			var thinkingCalls = plannerCallJournal.forkRole("thinking", thinkingConfig.plannerBackend().wireValue(), plannerModelName(thinkingConfig), thinkingRegistry::openAiTools);
			var handoffEvidence = new ai.moeru.airicraft.agent.llm.PlannerLifecycleListener() {
				@Override public void onObservationCompacted(ai.moeru.airicraft.agent.llm.PlannerToolCall call, String finding) {
					handoff.recordObservationFinding(call, finding);
				}
				@Override public void onToolExchange(ai.moeru.airicraft.agent.llm.PlannerToolCall call, String result, boolean imageAttached) {
					handoff.recordToolExchange(call, result, imageAttached);
				}
			};
			var thinker = createOrchestrator(thinkingConfig, thinkingRegistry, visionService, inventoryService,
				effectiveClock, observability, debugRecorder, effectiveActionToolExecutor, effectiveNarrationSink,
				effectiveToolExecutionObserver, CompositePlannerLifecycleListener.of(journal, thinkingCalls, handoffEvidence), cacheSession + ":thinking",
				characterPrompt);
			var generations = new java.util.concurrent.atomic.AtomicLong(1L);
			orchestrator.shareGenerationSequence(generations);
			thinker.shareGenerationSequence(generations);
			dialogue.configureDelegation(thinker, handoff);
		}
		return new PlannerShellComponents(visionService, dialogue, journal, plannerCallJournal, orchestrator);
	}

	private static PlannerOrchestrator createOrchestrator(AgentConfig.LlmConfig llm, PlannerToolRegistry tools,
		CurrentViewVisionService vision, CurrentInventoryService inventory, Clock clock, AgentObservability observability,
		AgentDebugRecorder debug, PlannerActionToolExecutor actions, PlannerToolNarrationSink narration,
		PlannerToolExecutionObserver toolObserver, ai.moeru.airicraft.agent.llm.PlannerLifecycleListener listener, String cacheKey,
		String characterPrompt) {
		LlmBackend backend = switch (llm.plannerBackend()) {
			case OPENAI_COMPATIBLE -> new OpenAiCompatibleLlmBackend(llm, observability, tools, cacheKey);
			case CODEX_APP_SERVER -> new CodexAppServerLlmBackend(llm, observability, tools);
		};
		var orchestrator = new PlannerOrchestrator(new PlannerExecutor(backend, observability),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(llm, observability, tools,
				cacheKey == null ? null : cacheKey + ":compaction"), observability),
			new PlannerContextAggregator(clock, llm.plannerCompactionTriggerTokens(), llm.plannerPendingSemanticEventCap(),
				llm.plannerVisionMode(), tools, llm.backendManagedHistory(), characterPrompt), vision, inventory, llm.plannerVisionMode(),
			llm.visionImageDetail(), 1, llm.plannerSessionCoalesceStepMillis(),
			llm.plannerSessionCoalesceMinMillis(), llm.plannerSessionCoalesceMaxMillis(), clock, observability,
			listener, debug, actions, narration, tools, toolObserver, llm.plannerMaxImages(),
			new ai.moeru.airicraft.agent.llm.PlannerVisionService(llm, observability));
		if (llm.plannerSummarizeToolResults()) orchestrator.configureMicroCompaction(new ai.moeru.airicraft.agent.llm.PlannerMicroCompactor(llm, observability, tools));
		return orchestrator;
	}

	/** Players address the companion by its account name; an empty card name adopts it. */
	private static String inGameName() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client == null || client.getSession() == null ? null : client.getSession().getUsername();
	}

	private static String plannerModelName(AgentConfig.LlmConfig config) {
		String configured = switch (config.plannerBackend()) {
			case OPENAI_COMPATIBLE -> config.model();
			case CODEX_APP_SERVER -> config.codexAppServer().model();
		};
		return configured == null || configured.isBlank() ? "default" : configured;
	}
}
