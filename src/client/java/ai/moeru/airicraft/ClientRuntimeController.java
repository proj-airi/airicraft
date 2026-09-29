package ai.moeru.airicraft;

import ai.moeru.airicraft.agent.tasks.TargetAcquisitionTaskExecutor;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.AgentConfigLoader;
import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.baritone.LiveBaritoneFacade;
import ai.moeru.airicraft.agent.character.CharacterCardLoader;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.idle.IdleIdeasConfig;
import ai.moeru.airicraft.agent.idle.IdleIdeasLoader;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.tasks.BaritoneTaskExecutor;
import ai.moeru.airicraft.agent.tasks.BlockBreakTaskExecutor;
import ai.moeru.airicraft.agent.tasks.BlockInteractionTaskExecutor;
import ai.moeru.airicraft.agent.tasks.UnderwaterHarvestTaskExecutor;
import ai.moeru.airicraft.agent.tasks.CraftingTaskExecutor;
import ai.moeru.airicraft.agent.tasks.DispatchingWorldTaskExecutor;
import ai.moeru.airicraft.agent.tasks.DropItemsTaskExecutor;
import ai.moeru.airicraft.agent.tasks.EntityInteractionTaskExecutor;
import ai.moeru.airicraft.agent.tasks.ReturnToSurfaceTaskExecutor;
import ai.moeru.airicraft.agent.tasks.SmeltingProcessManager;
import ai.moeru.airicraft.agent.tasks.SmeltingTaskExecutor;
import ai.moeru.airicraft.agent.tasks.WorldTaskExecutor;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.dashboard.DashboardObservationCollector;
import ai.moeru.airicraft.dashboard.DashboardObservationStore;
import ai.moeru.airicraft.dashboard.DebugDashboardServer;
import ai.moeru.airicraft.debug.ClientTickDebugRuntime;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ClientRuntimeController {
	private volatile AiricraftConfig config;
	private volatile boolean plannerEnabled = true;
	private final HighlightManager highlightManager = new HighlightManager();
	private final FirstPersonScreenshotService screenshotService = new FirstPersonScreenshotService();
	private final WorldCameraService worldCameraService = new WorldCameraService(screenshotService);
	private final ClientTickDebugRuntime clientTickDebugRuntime = new ClientTickDebugRuntime(screenshotService);
	private final BaritoneFacade baritoneFacade = new LiveBaritoneFacade();
	private final CameraController cameraController;
	private volatile EmbodiedAgentRuntime agentRuntime;
	private final ModBridgeServer bridgeServer;
	private final DashboardObservationStore dashboardObservationStore;
	private final DashboardObservationCollector dashboardObservationCollector;
	private final DebugDashboardServer debugDashboardServer;
	private String announcedDashboardUrl = "";
	private long lastDashboardCaptureFailureLogAtMs;
	private final PlannerDebugOverlay plannerDebugOverlay = new PlannerDebugOverlay();
	private final ClientTickIndicator clientTickIndicator = new ClientTickIndicator();
	private final ai.moeru.airicraft.playtest.AutomaticPlaytestRuntime automaticPlaytest =
		new ai.moeru.airicraft.playtest.AutomaticPlaytestRuntime(this, java.nio.file.Path.of(
			System.getProperty("airicraft.automaticPlaytestDir", "automatic_playtest")));

	public ai.moeru.airicraft.playtest.AutomaticPlaytestRuntime automaticPlaytest() { return automaticPlaytest; }

	public ClientRuntimeController() {
		this.config = AiricraftConfigLoader.load();
		this.cameraController = new CameraController(config.cameraLerpDefaultTicks());
		this.agentRuntime = createRuntime(config, AgentConfigLoader.load().withCharacter(CharacterCardLoader.load()));
		this.agentRuntime.updateIdleIdeasConfig(IdleIdeasLoader.load());
		this.dashboardObservationStore = new DashboardObservationStore(config.debugDashboard().historyByteBudget());
		this.dashboardObservationCollector = new DashboardObservationCollector(
			dashboardObservationStore,
			() -> {
				var dashboard = config.debugDashboard();
				return ai.moeru.airicraft.playtest.AutomaticPlaytestRuntime.enabled()
					? new ai.moeru.airicraft.dashboard.DebugDashboardConfig(dashboard.enabled(), dashboard.basePort(), dashboard.portScanLimit(),
						dashboard.historyByteBudget(), true, 1)
					: dashboard;
			}
		);
		this.debugDashboardServer = new DebugDashboardServer(dashboardObservationStore, this::diagnosticEnvironment, this::diagnosticSecrets);
		this.bridgeServer = new ModBridgeServer(
			this::highlightManager,
			this::agentRuntime,
			this::screenshotService,
			this::worldCameraService,
			this::clientTickDebugRuntime,
			this::reload,
			cameraController,
			BridgeDiscoveryFile.createDefault(),
			debugDashboardServer::statusPayload,
			automaticPlaytest::statusPayload
		);
	}

	public AiricraftConfig config() {
		return config;
	}

	public HighlightManager highlightManager() {
		return highlightManager;
	}

	public EmbodiedAgentRuntime agentRuntime() {
		return currentAgentRuntime();
	}

	public PlannerDebugOverlayMode plannerDebugOverlayMode() {
		return plannerDebugOverlay.mode();
	}

	public boolean plannerDebugOverlayEnabled() {
		return plannerDebugOverlay.enabled();
	}

	public boolean plannerEnabled() {
		return plannerEnabled;
	}

	public boolean togglePlannerEnabled() {
		setPlannerEnabled(!plannerEnabled);
		return plannerEnabled;
	}

	public void setPlannerEnabled(boolean enabled) {
		plannerEnabled = enabled;
		currentAgentRuntime().setPlannerEnabled(enabled);
	}

	public void setPlannerDebugOverlayMode(PlannerDebugOverlayMode mode) {
		plannerDebugOverlay.setMode(mode);
	}

	public PlannerConversationView plannerDebugConversationView() {
		return plannerDebugOverlay.conversationView();
	}

	public void setPlannerDebugConversationView(PlannerConversationView view) {
		plannerDebugOverlay.setConversationView(view);
	}

	public boolean plannerDebugConversationVerbose() {
		return plannerDebugOverlay.conversationVerbose();
	}

	public void setPlannerDebugConversationVerbose(boolean verbose) {
		plannerDebugOverlay.setConversationVerbose(verbose);
	}

	public DashboardObservationStore liveRecording() {
		return dashboardObservationStore;
	}

	private Map<String, Object> diagnosticEnvironment() {
		return ai.moeru.airicraft.dashboard.DiagnosticEnvironment.capture(currentAgentRuntime().config().llm());
	}

	private java.util.List<String> diagnosticSecrets() {
		var agent = currentAgentRuntime().config();
		var secrets = new java.util.ArrayList<String>(agent.observability().otlpHeaders().values());
		secrets.add(agent.llm().apiKey()); secrets.add(agent.llm().visionApiKey()); secrets.add(bridgeServer.diagnosticCredential());
		return secrets.stream().filter(java.util.Objects::nonNull).toList();
	}

	public ai.moeru.airicraft.dashboard.DiagnosticReport.Draft markDiagnosticReport() { return debugDashboardServer.markReport(); }

	public java.util.concurrent.CompletableFuture<ai.moeru.airicraft.dashboard.DiagnosticReport> previewDiagnosticReport(
		ai.moeru.airicraft.dashboard.DiagnosticReport.Draft draft, ai.moeru.airicraft.dashboard.DiagnosticReport.Request request) {
		return java.util.concurrent.CompletableFuture.supplyAsync(() -> debugDashboardServer.previewReport(draft, request));
	}

	public java.util.concurrent.CompletableFuture<java.nio.file.Path> saveDiagnosticReport(ai.moeru.airicraft.dashboard.DiagnosticReport report) {
		return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try { return report.save(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("airicraft-reports")); }
			catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
		});
	}

	public FirstPersonScreenshotService screenshotService() {
		return screenshotService;
	}

	public WorldCameraService worldCameraService() {
		return worldCameraService;
	}

	public ClientTickDebugRuntime clientTickDebugRuntime() {
		return clientTickDebugRuntime;
	}

	public void onClientStarted(MinecraftClient client) {
		currentAgentRuntime().onClientStarted(client);
		dashboardObservationCollector.startSession("client_started", currentAgentRuntime());
		try {
			debugDashboardServer.start(config.debugDashboard());
		}
		catch (RuntimeException exception) {
			Airicraft.LOGGER.error("Failed to start Airicraft debug dashboard", exception);
			debugDashboardServer.stop();
		}
		bridgeServer.start();
	}

	public void onWorldLeave() {
		automaticPlaytest.worldLeft("world_left");
		worldCameraService.clear();
		ai.moeru.airicraft.agent.memory.WorldPlacePreservation.clear();
		dashboardObservationCollector.worldLeft();
		if (!automaticPlaytest.captureReady()) clientTickDebugRuntime.reset("world_left", "The world closed during a client tick debug capture");
		screenshotService.failActiveCapture("capture_failed", "Screenshot capture was interrupted");
		currentAgentRuntime().onWorldLeave();
		cameraController.clear();
		highlightManager.clear();
	}

	public CameraController cameraController() { return cameraController; }

	public void onClientTick(MinecraftClient client) {
		ai.moeru.airicraft.agent.memory.WorldPlacePreservation.tick(client);
		if (!automaticPlaytest.freezing()) currentAgentRuntime().onClientTick(client);
		cameraController.tick(client);
		highlightManager.tick();
		clientTickDebugRuntime.onClientTickCompleted(client, currentAgentRuntime());
		try {
			dashboardObservationCollector.capture(client, currentAgentRuntime());
		}
		catch (RuntimeException exception) {
			long now = System.currentTimeMillis();
			if (now - lastDashboardCaptureFailureLogAtMs >= 10_000L) {
				lastDashboardCaptureFailureLogAtMs = now;
				Airicraft.LOGGER.warn("Debug dashboard observation failed; game execution is unaffected", exception);
			}
		}
		automaticPlaytest.onClientTick(client);
		pollConversationScrollKeys(client);
		announceDashboardUrl(client);
	}

	// The HUD has no cursor to hover the pane, and the wheel drives the hotbar;
	// PgUp/PgDn/Home/End scroll the conversation overlay when no screen is open.
	private void pollConversationScrollKeys(MinecraftClient client) {
		if (client == null || client.currentScreen != null || client.getWindow() == null
			|| plannerDebugOverlay.mode() != PlannerDebugOverlayMode.CONVERSATION) {
			return;
		}
		long handle = client.getWindow().getHandle();
		if (InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_PAGE_UP)) {
			plannerDebugOverlay.scrollConversationBy(-PlannerDebugOverlay.CONVERSATION_KEY_SCROLL_STEP_PX);
		}
		if (InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_PAGE_DOWN)) {
			plannerDebugOverlay.scrollConversationBy(PlannerDebugOverlay.CONVERSATION_KEY_SCROLL_STEP_PX);
		}
		if (InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_HOME)) {
			plannerDebugOverlay.scrollConversationToStart();
		}
		if (InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_END)) {
			plannerDebugOverlay.scrollConversationToEnd();
		}
	}

	private void announceDashboardUrl(MinecraftClient client) {
		String url = debugDashboardServer.status().primaryUrl();
		if (client == null || client.player == null || url.isBlank() || url.equals(announcedDashboardUrl)) {
			return;
		}
		announcedDashboardUrl = url;
		Text link = Text.literal(url).styled(style -> style
			.withColor(Formatting.AQUA)
			.withUnderline(true)
			.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
		client.player.sendMessage(Text.literal("Airicraft debug dashboard: ").append(link), false);
	}

	public void onChatReceived(String senderName, String plainTextMessage) {
		currentAgentRuntime().onChatReceived(senderName, plainTextMessage);
	}

	public void onSystemChatReceived(String plainTextMessage) {
		currentAgentRuntime().onSystemChatReceived(plainTextMessage);
	}

	public void onPlayerCraftedItem(String itemId, int count) {
		currentAgentRuntime().onPlayerCraftedItem(itemId, count);
	}

	public void onPlayerPickedUpItem(String itemId, int count) {
		currentAgentRuntime().onPlayerPickedUpItem(itemId, count);
	}

	public void onPlayerItemPickupObserved(
		int entityId,
		UUID entityUuid,
		String itemId,
		int pickupDelta,
		int agentAttributedQuantity,
		UUID collectorIdentity,
		UUID observationId
	) {
		currentAgentRuntime().onPlayerItemPickupObserved(
			entityId,
			entityUuid,
			itemId,
			pickupDelta,
			agentAttributedQuantity,
			collectorIdentity,
			observationId
		);
	}

	public void onPlayerMinedBlock(String blockId, int x, int y, int z) {
		currentAgentRuntime().onPlayerMinedBlock(blockId, x, y, z);
	}

	public void onPlayerDamageObserved(DamageSource damageSource) {
		currentAgentRuntime().onPlayerDamageObserved(damageSource);
	}

	public void onPlayerHealthUpdated(boolean healthInitialized, float healthBefore, float healthAfter) {
		currentAgentRuntime().onPlayerHealthUpdated(healthInitialized, healthBefore, healthAfter);
	}

	public void onPlayerRespawned() {
		currentAgentRuntime().onPlayerRespawned();
	}

	public void onPlayerJoinedGame(UUID playerUuid, String playerName) {
		currentAgentRuntime().onPlayerJoinedGame(playerUuid, playerName);
	}

	public void onPlayerLeftGame(UUID playerUuid) {
		currentAgentRuntime().onPlayerLeftGame(playerUuid);
	}

	public void onWorldRender(WorldRenderContext context) {
		highlightManager.render(context);
	}

	public void onHudRender(DrawContext drawContext, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.currentScreen != null) {
			return;
		}
		plannerDebugOverlay.render(client, drawContext, currentAgentRuntime(), System.currentTimeMillis());
		renderClientTickIndicators(client, drawContext);
	}

	public void onScreenRender(DrawContext drawContext) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.currentScreen == null) {
			return;
		}
		plannerDebugOverlay.render(client, drawContext, currentAgentRuntime(), System.currentTimeMillis());
		renderClientTickIndicators(client, drawContext);
	}

	private void renderClientTickIndicators(MinecraftClient client, DrawContext drawContext) {
		clientTickIndicator.render(
			client,
			drawContext,
			clientTickDebugRuntime.status(),
			clientTickDebugRuntime.traceStatus(),
			plannerEnabled,
			automaticPlaytest.emptyHostPaused()
		);
	}

	public boolean onScreenMouseScroll(double mouseX, double mouseY, double verticalAmount) {
		return plannerDebugOverlay.onMouseScroll(mouseX, mouseY, verticalAmount);
	}

	public void onFirstPersonFrameRendered() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			screenshotService.onWorldRendered(client);
			try {
				dashboardObservationCollector.onRenderedFrame(client, currentAgentRuntime());
			}
			catch (RuntimeException exception) {
				long now = System.currentTimeMillis();
				if (now - lastDashboardCaptureFailureLogAtMs >= 10_000L) {
					lastDashboardCaptureFailureLogAtMs = now;
					Airicraft.LOGGER.warn("Live recording frame observation failed", exception);
				}
			}
		}
	}

	public synchronized ReloadResult reload() {
		AiricraftConfig nextConfig;
		AgentConfig nextAgentConfig;
		IdleIdeasConfig nextIdleIdeasConfig;
		try {
			nextConfig = AiricraftConfigLoader.loadStrict();
			nextAgentConfig = AgentConfigLoader.loadStrict().withCharacter(CharacterCardLoader.loadStrict());
			nextIdleIdeasConfig = IdleIdeasLoader.loadStrict();
		}
		catch (ConfigLoadException exception) {
			throw new BridgeUnavailableException("invalid_config", exception.getMessage());
		}

		automaticPlaytest.worldLeft("runtime_reloaded");
		clientTickDebugRuntime.reset("runtime_reloaded", "Airicraft reloaded during a client tick debug capture");
		screenshotService.failActiveCapture("capture_failed", "Screenshot capture was interrupted");
		cameraController.clear();
		cameraController.updateDefaultLerpTicks(nextConfig.cameraLerpDefaultTicks());
		EmbodiedAgentRuntime previousRuntime = currentAgentRuntime();
		EmbodiedAgentRuntime nextRuntime = createRuntime(nextConfig, nextAgentConfig);
		nextRuntime.updateIdleIdeasConfig(nextIdleIdeasConfig);
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			nextRuntime.onClientStarted(client);
		}

		config = nextConfig;
		agentRuntime = nextRuntime;
		previousRuntime.shutdown();
		dashboardObservationCollector.startSession("runtime_reloaded", nextRuntime);
		try {
			debugDashboardServer.reconfigure(nextConfig.debugDashboard());
		}
		catch (RuntimeException exception) {
			Airicraft.LOGGER.error("Failed to reconfigure Airicraft debug dashboard", exception);
			debugDashboardServer.stop();
		}
		announcedDashboardUrl = "";
		return new ReloadResult(nextConfig, nextAgentConfig, nextIdleIdeasConfig, nextRuntime.sessionSnapshot());
	}

	public void shutdown() {
		automaticPlaytest.worldLeft("client_stopping");
		if (!automaticPlaytest.captureReady()) clientTickDebugRuntime.reset("client_stopping", "The client stopped during a client tick debug capture");
		screenshotService.failActiveCapture("capture_failed", "Screenshot capture was interrupted");
		plannerDebugOverlay.setMode(PlannerDebugOverlayMode.OFF);
		currentAgentRuntime().shutdown();
		cameraController.clear();
		highlightManager.clear();
		bridgeServer.stop();
		debugDashboardServer.stop();
		dashboardObservationCollector.close();
	}

	private EmbodiedAgentRuntime currentAgentRuntime() {
		return agentRuntime;
	}

	private EmbodiedAgentRuntime createRuntime(AiricraftConfig airicraftConfig, AgentConfig agentConfig) {
		var miningOpportunityPolicy = new ai.moeru.airicraft.agent.tasks.MiningOpportunityPolicyState();
		var miningOpportunityJournal = new ai.moeru.airicraft.agent.tasks.MiningOpportunityJournal();
		SmeltingProcessManager smeltingProcessManager = new SmeltingProcessManager();
		BaritoneTaskExecutor baritoneTaskExecutor = new BaritoneTaskExecutor(baritoneFacade);
		UnderwaterHarvestTaskExecutor underwaterHarvestTaskExecutor = new UnderwaterHarvestTaskExecutor(
			baritoneFacade,
			cameraController
		);
		WorldTaskExecutor worldTaskExecutor = new DispatchingWorldTaskExecutor(new DispatchingWorldTaskExecutor.ExecutorSet(
			baritoneTaskExecutor,
			new CraftingTaskExecutor(baritoneFacade, cameraController),
			new DropItemsTaskExecutor(baritoneFacade, cameraController),
			new EntityInteractionTaskExecutor(baritoneFacade, cameraController),
			new SmeltingTaskExecutor(smeltingProcessManager, baritoneFacade),
			new ReturnToSurfaceTaskExecutor(baritoneFacade, cameraController),
			new BlockInteractionTaskExecutor(airicraftConfig.blockInteractionDelayTicks(), cameraController, baritoneFacade),
			new BlockBreakTaskExecutor(cameraController),
			new TargetAcquisitionTaskExecutor(baritoneFacade, cameraController, miningOpportunityPolicy, miningOpportunityJournal),
			underwaterHarvestTaskExecutor,
			new ai.moeru.airicraft.agent.tasks.CropTendingTaskExecutor(baritoneFacade, cameraController,
				new BlockInteractionTaskExecutor(airicraftConfig.blockInteractionDelayTicks(), cameraController, baritoneFacade)),
			new ai.moeru.airicraft.agent.tasks.LureEntitiesTaskExecutor(baritoneFacade)
		),
			baritoneFacade
		);
		EmbodiedAgentRuntime runtime = new EmbodiedAgentRuntime(
			airicraftConfig,
			agentConfig,
			screenshotService,
			worldTaskExecutor,
			AgentObservability.create(agentConfig.observability()),
			smeltingProcessManager,
			cameraController,
			baritoneFacade,
			miningOpportunityPolicy,
			miningOpportunityJournal
		);
		runtime.setPlannerEnabled(plannerEnabled);
		return runtime;
	}

	public record ReloadResult(
		AiricraftConfig airicraftConfig,
		AgentConfig agentConfig,
		IdleIdeasConfig idleIdeasConfig,
		SessionSnapshot sessionSnapshot
	) {
		public Map<String, Object> toPayload() {
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("available", true);
			payload.put("reloaded", true);
			payload.put("agentStateReset", true);
			payload.put("sessionMode", sessionSnapshot.mode().name());
			payload.put("worldLoaded", sessionSnapshot.worldLoaded());
			payload.put("plannerVisionMode", agentConfig.llm().plannerVisionMode().wireValue());
			payload.put("llmConfigured", agentConfig.llm().isConfigured());
			payload.put("visionConfigured", agentConfig.llm().visionConfigured());
			payload.put("observabilityEnabled", agentConfig.observability().enabled());
			payload.put("config", configPayload());
			payload.put("llm", llmPayload());
			payload.put("observability", observabilityPayload());
			payload.put("idleIdeas", idleIdeasPayload());
			payload.put("character", Map.of(
				"name", agentConfig.character().name(),
				"source", agentConfig.character().source()));
			return payload;
		}

		public String feedbackText() {
			return "Airicraft reloaded: plannerVisionMode=%s, sessionMode=%s, worldLoaded=%s"
				.formatted(agentConfig.llm().plannerVisionMode().wireValue(), sessionSnapshot.mode().name(), sessionSnapshot.worldLoaded());
		}

		private Map<String, Object> configPayload() {
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("socialChatMaxDistanceBlocks", airicraftConfig.socialChatMaxDistanceBlocks());
			payload.put("readSystemChatMessages", airicraftConfig.readSystemChatMessages());
			payload.put("enableProactiveSocialMode", airicraftConfig.enableProactiveSocialMode());
			payload.put("suppressAutoPauseOnFocusLost", airicraftConfig.suppressAutoPauseOnFocusLost());
			payload.put("blockInteractionDelayTicks", airicraftConfig.blockInteractionDelayTicks());
			payload.put("cameraLerpDefaultTicks", airicraftConfig.cameraLerpDefaultTicks());
			payload.put("debugDashboard", Map.of(
				"enabled", airicraftConfig.debugDashboard().enabled(),
				"basePort", airicraftConfig.debugDashboard().basePort(),
				"portScanLimit", airicraftConfig.debugDashboard().portScanLimit(),
				"historyByteBudget", airicraftConfig.debugDashboard().historyByteBudget(),
				"visualCaptureEnabled", airicraftConfig.debugDashboard().visualCaptureEnabled(),
				"visualCaptureIntervalTicks", airicraftConfig.debugDashboard().visualCaptureIntervalTicks()
			));
			return payload;
		}

		private Map<String, Object> llmPayload() {
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("plannerBackend", agentConfig.llm().plannerBackend().wireValue());
			payload.put("providerBaseUrl", agentConfig.llm().providerBaseUrl());
			payload.put("model", agentConfig.llm().model());
			payload.put("requestTimeoutMillis", agentConfig.llm().requestTimeoutMillis());
			payload.put("visionProviderBaseUrl", agentConfig.llm().visionProviderBaseUrl());
			payload.put("visionModel", agentConfig.llm().visionModel());
			payload.put("visionRequestTimeoutMillis", agentConfig.llm().visionRequestTimeoutMillis());
			payload.put("maxRecentConversationTurns", agentConfig.llm().maxRecentConversationTurns());
			payload.put("plannerCompactionTriggerTokens", agentConfig.llm().plannerCompactionTriggerTokens());
			payload.put("plannerPendingSemanticEventCap", agentConfig.llm().plannerPendingSemanticEventCap());
			payload.put("plannerSessionMaxConcurrentAttempts", agentConfig.llm().plannerSessionMaxConcurrentAttempts());
			payload.put("plannerSessionCoalesceStepMillis", agentConfig.llm().plannerSessionCoalesceStepMillis());
			payload.put("plannerSessionCoalesceMinMillis", agentConfig.llm().plannerSessionCoalesceMinMillis());
			payload.put("plannerSessionCoalesceMaxMillis", agentConfig.llm().plannerSessionCoalesceMaxMillis());
			payload.put("visionImageDetail", agentConfig.llm().visionImageDetail());
			payload.put("plannerNativeVisionEnabled", agentConfig.llm().plannerNativeVisionEnabled());
			payload.put("codexExecutable", agentConfig.llm().codexAppServer().executable());
			payload.put("codexModel", agentConfig.llm().codexAppServer().model());
			payload.put("codexReasoningEffort", agentConfig.llm().codexAppServer().reasoningEffort());
			payload.put("codexServiceTier", agentConfig.llm().codexAppServer().serviceTier());
			payload.put("codexStartupTimeoutMillis", agentConfig.llm().codexAppServer().startupTimeoutMillis());
			payload.put("codexTurnTimeoutMillis", agentConfig.llm().codexAppServer().turnTimeoutMillis());
			return payload;
		}

		private Map<String, Object> observabilityPayload() {
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("enabled", agentConfig.observability().enabled());
			payload.put("exporter", agentConfig.observability().exporter());
			payload.put("otlpEndpoint", agentConfig.observability().otlpEndpoint());
			payload.put("otlpHeaders", agentConfig.observability().otlpHeaders());
			payload.put("resourceAttributes", agentConfig.observability().resourceAttributes());
			payload.put("vendorProfile", agentConfig.observability().vendorProfile());
			payload.put("debugLogExports", agentConfig.observability().debugLogExports());
			payload.put("captureInputs", agentConfig.observability().captureInputs());
			payload.put("captureOutputs", agentConfig.observability().captureOutputs());
			payload.put("captureImages", agentConfig.observability().captureImages());
			return payload;
		}

		private Map<String, Object> idleIdeasPayload() {
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("enabled", idleIdeasConfig.enabled() && agentConfig.idle().automaticEnabled());
			payload.put("initialDelaySeconds", agentConfig.idle().initialDelaySeconds());
			payload.put("cooldownSeconds", agentConfig.idle().cooldownSeconds());
			payload.put("ideaCount", idleIdeasConfig.ideas().size());
			return payload;
		}
	}
}
