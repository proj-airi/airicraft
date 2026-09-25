package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.SingleplayerWorldService;
import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import ai.moeru.airicraft.agent.evaluation.EvaluationScenario;
import ai.moeru.airicraft.agent.evaluation.EvaluationScenarioLoader;
import ai.moeru.airicraft.agent.evaluation.EvaluationScenarioRepository;
import ai.moeru.airicraft.agent.evaluation.EvaluationWorldFixtureService;
import ai.moeru.airicraft.agent.evaluation.ScenarioEvaluationRunner;
import ai.moeru.airicraft.bridge.BridgeRouteContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class EvaluationAddonRuntime {
	private static final DateTimeFormatter MANUAL_OUTPUT_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final java.util.List<String> CAPABILITIES = java.util.List.of(
		"scenario_list",
		"world_restore",
		"current_config",
		"planner_loop",
		"results",
		"evidence",
		"in_mod_recording"
	);

	private final EvaluationWorldFixtureService fixtures = EvaluationWorldFixtureService.createDefault();
	private final SingleplayerWorldService singleplayerWorldService = new SingleplayerWorldService();
	private final ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
	private final EvaluationFlightRecorder recorder = new EvaluationFlightRecorder();
	private final EvaluationWaypointSeeder waypointSeeder = new EvaluationWaypointSeeder();
	private final SurvivalSmokeFixtureService survivalFixtures = new SurvivalSmokeFixtureService();
	private final NavigationCourseFixtureService navigationCourses = new NavigationCourseFixtureService();

	private EvaluationScenario scenario;
	private boolean waypointsSeeded;
	private RunState runState = RunState.IDLE;
	private EmbodiedAgentRuntime acceptedRuntime;
	private EmbodiedAgentRuntime cleanupRuntime;

	public EvaluationWorldFixtureService fixtures() {
		return fixtures;
	}

	public void onClientTick(MinecraftClient client) {
		survivalFixtures.onClientTick(client);
		if (runState == RunState.CLEANUP) {
			continueCleanup();
			return;
		}
		if (runState != RunState.RUNNING) {
			return;
		}
		EvaluationScenario activeScenario = scenario;
		if (activeScenario == null) {
			return;
		}
		EmbodiedAgentRuntime runtime = AiricraftClient.runtimeController().agentRuntime();
		if (!waypointsSeeded && runtime.sessionSnapshot().worldLoaded()) {
			try {
				waypointSeeder.seed(activeScenario);
				waypointsSeeded = true;
			}
			catch (BridgeUnavailableException exception) {
				runner.failSetup(exception.getMessage(), runtime.tickCount());
				var report = runner.report(runtime.tickCount());
				recorder.recordTick(activeScenario, report, runtime, this::evidencePayload);
				beginCleanup(runtime);
				return;
			}
		}
		runner.onTick(new RuntimeEvaluationContext(runtime));
		var report = runner.report(runtime.tickCount());
		recorder.recordTick(activeScenario, report, runtime, this::evidencePayload);
		if (runner.terminal()) {
			beginCleanup(runtime);
		}
	}

	public void handleStatus(BridgeRouteContext context) throws Exception {
		if (!context.isMethod("GET")) {
			context.writeJson(405, Map.of("error", "method_not_allowed"));
			return;
		}
		context.writeJson(200, context.onClientThread(this::statusPayload));
	}

	public void handleScenarios(BridgeRouteContext context) throws Exception {
		if (!context.isMethod("GET")) {
			context.writeJson(405, Map.of("error", "method_not_allowed"));
			return;
		}
		try {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("scenarioRoot", fixtures.repository().root().toString());
			response.put("scenarios", fixtures.repository().list().stream().map(this::scenarioPayload).toList());
			response.put("report", reportPayload());
			context.writeJson(200, response);
		}
		catch (EvaluationScenarioRepository.EvaluationScenarioRepositoryException exception) {
			throw new BridgeUnavailableException(exception.code(), exception.getMessage());
		}
	}

	public void handleConfig(BridgeRouteContext context) throws Exception {
		if (!context.isMethod("GET")) {
			context.writeJson(405, Map.of("error", "method_not_allowed"));
			return;
		}
		context.writeJson(200, context.onClientThread(this::configPayload));
	}

	public void handleResults(BridgeRouteContext context) throws Exception {
		if (!context.isMethod("GET")) {
			context.writeJson(405, Map.of("error", "method_not_allowed"));
			return;
		}
		context.writeJson(200, context.onClientThread(this::resultsPayload));
	}

	public void handleEvidence(BridgeRouteContext context) throws Exception {
		if (!context.isMethod("GET")) {
			context.writeJson(405, Map.of("error", "method_not_allowed"));
			return;
		}
		context.writeJson(200, context.onClientThread(this::evidencePayload));
	}

	public void handleRun(BridgeRouteContext context) throws Exception {
		if (!context.isMethod("POST")) {
			context.writeJson(405, Map.of("error", "method_not_allowed"));
			return;
		}
		EvaluationRunRequest request = context.readJson(EvaluationRunRequest.class);
		if (request == null || request.scenario() == null || request.scenario().isBlank()) {
			throw new BridgeUnavailableException("invalid_request", "Missing scenario");
		}
		boolean startReserved = false;
		try {
			EvaluationScenario nextScenario = fixtures.repository().require(request.scenario());
			if (nextScenario.prompt() == null || nextScenario.prompt().isBlank()) {
				throw new BridgeUnavailableException("invalid_scenario", "Scenario prompt is empty: " + nextScenario.id());
			}
			var loadedIntegrations = EvaluationScenario.SUPPORTED_MODS.stream()
				.filter(FabricLoader.getInstance()::isModLoaded).toList();
			if (!new java.util.HashSet<>(loadedIntegrations).equals(new java.util.HashSet<>(nextScenario.requiredMods()))) {
				throw new BridgeUnavailableException("scenario_mod_mismatch",
					"Scenario requires " + nextScenario.requiredMods() + " but loaded integrations are " + loadedIntegrations
						+ "; relaunch with AIRICRAFT_EVALUATOR_SCENARIO_MANIFEST=" + nextScenario.configPath());
			}
			context.onClientThread(this::reserveRunStart);
			startReserved = true;
			var restoredWorld = fixtures.restoreScenarioWorld(nextScenario);
			Path outputDir = outputDir(request.outputDir(), nextScenario);
			Map<String, Object> payload = context.onClientThread(() -> {
				if (runState != RunState.STARTING) {
					throw new BridgeUnavailableException("evaluation_start_cancelled", "Evaluation start reservation was lost");
				}
				EmbodiedAgentRuntime runtime = AiricraftClient.runtimeController().agentRuntime();
				acceptedRuntime = runtime;
				runtime.prepareForEvaluation();
				scenario = nextScenario;
				waypointsSeeded = false;
				runner.start(nextScenario, runtime.tickCount(), System.currentTimeMillis());
				recorder.start(nextScenario, outputDir, restoredWorld);
				runState = RunState.RUNNING;
				return acceptedRunPayload(nextScenario, restoredWorld, outputDir, runner.report(runtime.tickCount()));
			});
			try {
				payload.put("join", singleplayerWorldService.joinWorldDirectory(restoredWorld.worldName()));
			}
			catch (SingleplayerWorldService.SingleplayerWorldException exception) {
				try {
					context.onClientThread(this::rollbackAcceptedRun);
				}
				catch (RuntimeException ignored) {
				}
				throw exception;
			}
			context.writeJson(200, payload);
		}
		catch (EvaluationScenarioRepository.EvaluationScenarioRepositoryException exception) {
			throw new BridgeUnavailableException(exception.code(), exception.getMessage());
		}
		catch (EvaluationWorldFixtureService.EvaluationWorldFixtureException exception) {
			throw new BridgeUnavailableException(exception.code(), exception.getMessage());
		}
		catch (SingleplayerWorldService.SingleplayerWorldException exception) {
			throw new BridgeUnavailableException(exception.code(), exception.getMessage());
		}
		finally {
			if (startReserved) {
				context.onClientThread(this::cancelRunStart);
			}
		}
	}

	public void handleClientStop(BridgeRouteContext context) throws Exception {
		if (!context.isMethod("POST")) {
			context.writeJson(405, Map.of("error", "method_not_allowed"));
			return;
		}
		context.writeJson(202, context.onClientThread(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			var runtime = AiricraftClient.runtimeController();
			if (runtime.automaticPlaytest().captureReady()) {
				runtime.automaticPlaytest().prepareShutdown(client).whenComplete((ignored, failure) -> client.execute(() -> {
					if (failure != null) ai.moeru.airicraft.Airicraft.LOGGER.error("Could not save paused playtest world", failure);
					// The checkpoint is immutable now. Disconnect must run normally for the recording profile.
					runtime.clientTickDebugRuntime().reset("client_stopping", "Finalizing the automatic playtest Recorder Play");
					client.scheduleStop();
				}));
			}
			else client.scheduleStop();
			return Map.of("stopping", true);
		}));
	}

	public void handleSurvivalFixture(BridgeRouteContext context) throws Exception {
		if (!context.isMethod("POST")) {
			context.writeJson(405, Map.of("error", "method_not_allowed"));
			return;
		}
		SurvivalSmokeFixtureService.Request request = context.readJson(SurvivalSmokeFixtureService.Request.class);
		try {
			context.writeJson(200, context.onClientThread(() -> survivalFixtures.apply(request)));
		}
		catch (SurvivalSmokeFixtureService.FixtureException exception) {
			throw new BridgeUnavailableException(exception.code(), exception.getMessage());
		}
	}

	public void handleNavigationCourse(BridgeRouteContext context) throws Exception {
		if (!context.isMethod("POST")) {
			context.writeJson(405, Map.of("error", "method_not_allowed"));
			return;
		}
		NavigationCourseFixtureService.Request request = context.readJson(NavigationCourseFixtureService.Request.class);
		try {
			context.writeJson(200, context.onClientThread(() -> navigationCourses.apply(request)));
		}
		catch (NavigationCourseFixtureService.FixtureException exception) {
			throw new BridgeUnavailableException(exception.code(), exception.getMessage());
		}
	}

	private Map<String, Object> acceptedRunPayload(
		EvaluationScenario nextScenario,
		EvaluationWorldFixtureService.RestoredWorld restoredWorld,
		Path outputDir,
		Object report
	) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("accepted", true);
		response.put("scenario", nextScenario.id());
		response.put("worldName", restoredWorld.worldName());
		response.put("worldPath", restoredWorld.path().toString());
		response.put("outputDir", outputDir.toString());
		response.put("report", report);
		return response;
	}

	private Void rollbackAcceptedRun() {
		EmbodiedAgentRuntime runtime = acceptedRuntime == null
			? AiricraftClient.runtimeController().agentRuntime()
			: acceptedRuntime;
		scenario = null;
		waypointsSeeded = false;
		runner.reset();
		recorder.reset();
		beginCleanup(runtime);
		return null;
	}

	private Void reserveRunStart() {
		if (runState == RunState.CLEANUP) {
			throw new BridgeUnavailableException(
				"evaluation_cleanup_in_progress",
				"The previous evaluation is still recording motor-shadow cleanup"
			);
		}
		if (runState != RunState.IDLE) {
			throw new BridgeUnavailableException("evaluation_in_progress", "An evaluation is already starting or running");
		}
		runState = RunState.STARTING;
		return null;
	}

	private Void cancelRunStart() {
		if (runState == RunState.STARTING) {
			acceptedRuntime = null;
			runState = RunState.IDLE;
		}
		return null;
	}

	private void beginCleanup(EmbodiedAgentRuntime runtime) {
		scenario = null;
		waypointsSeeded = false;
		cleanupRuntime = runtime;
		acceptedRuntime = null;
		runState = RunState.CLEANUP;
		runtime.finishEvaluation();
		continueCleanup();
	}

	private void continueCleanup() {
		EmbodiedAgentRuntime runtime = cleanupRuntime;
		if (runtime == null) {
			clearCleanup();
			return;
		}
		clearCleanup();
	}

	private void clearCleanup() {
		cleanupRuntime = null;
		runState = RunState.IDLE;
	}

	private Map<String, Object> statusPayload() {
		EmbodiedAgentRuntime runtime = AiricraftClient.runtimeController().agentRuntime();
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("available", true);
		response.put("sessionMode", runtime.sessionSnapshot().mode().name());
		response.put("worldLoaded", runtime.sessionSnapshot().worldLoaded());
		response.put("capabilities", CAPABILITIES);
		response.put("scenarioRoot", fixtures.repository().root().toString());
		response.put("report", runner.report(runtime.tickCount()));
		response.put("recording", recorder.statusPayload());
		response.put("runState", runState.name());
		response.put("postFinishCleanupPending", runState == RunState.CLEANUP);
		return response;
	}

	private Map<String, Object> configPayload() {
		try {
			Path configPath = fixtures.resolveCurrentScenarioConfig()
				.orElseThrow(() -> new BridgeUnavailableException("scenario_not_found", "No scenario config is associated with the current world"));
			EvaluationScenario loaded = EvaluationScenarioLoader.load(configPath);
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("scenarioId", loaded.id());
			response.put("scenarioRoot", fixtures.repository().root().toString());
			response.put("configPath", configPath.toString());
			response.put("worldArchivePath", fixtures.repository().archivePath(loaded).toString());
			response.put("scenario", scenarioPayload(loaded));
			return response;
		}
		catch (BridgeUnavailableException exception) {
			throw exception;
		}
		catch (EvaluationWorldFixtureService.EvaluationWorldFixtureException exception) {
			throw new BridgeUnavailableException(exception.code(), exception.getMessage());
		}
		catch (IOException exception) {
			throw new BridgeUnavailableException("scenario_load_failed", "Failed to load current scenario config: " + exception.getMessage());
		}
	}

	private Map<String, Object> resultsPayload() {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("available", true);
		response.put("report", reportPayload());
		return response;
	}

	private Object reportPayload() {
		EmbodiedAgentRuntime runtime = AiricraftClient.runtimeController().agentRuntime();
		return runner.report(runtime.tickCount());
	}

	private Map<String, Object> evidencePayload() {
		EmbodiedAgentRuntime runtime = AiricraftClient.runtimeController().agentRuntime();
		Map<String, Object> evidence = new LinkedHashMap<>();
		EvaluationScenario currentScenario = scenario;
		var settings = currentScenario == null ? ai.moeru.airicraft.agent.evaluation.EvaluationEvidenceSettings.defaults() : currentScenario.evidence();
		evidence.put("report", runner.report(runtime.tickCount()));
		evidence.put("session", runtime.sessionSnapshot());
		if (settings.includeTaskState()) {
			evidence.put("activeGoal", runtime.activeGoal().orElse(null));
			evidence.put("task", runtime.taskSnapshot());
			evidence.put("taskExecution", runtime.taskExecutionSnapshot());
			evidence.put("missionExecution", runtime.missionExecutionSnapshot());
			evidence.put("activeJob", runtime.activeJob());
		}
		if (settings.includePlannerJournal()) {
			evidence.put("planner", runtime.plannerDebugSnapshot());
			evidence.put("plannerJournal", runtime.plannerShellJournal());
			evidence.put("contextExcerpt", runtime.plannerContextExcerpt());
		}
		if (settings.includeDebugTimeline()) {
			evidence.put("debugTimeline", runtime.debugTimeline(null));
		}
		if (settings.includeRecentEvents()) {
			evidence.put("recentEvents", runtime.recentEvents(null));
		}
		if (settings.includeWorldSnapshot()) {
			evidence.put("worldEvidence", runtime.currentWorldEvidence());
		}
		evidence.put("plannerGoal", runtime.plannerGoalSnapshot().orElse(null));
		evidence.put("lastChatText", runtime.lastChatText());
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("available", true);
		response.put("evidence", evidence);
		return response;
	}

	private Map<String, Object> scenarioPayload(EvaluationScenario value) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("id", value.id());
		payload.put("name", value.name());
		payload.put("minecraftVersion", value.minecraftVersion());
		payload.put("airicraftVersion", value.airicraftVersion());
		payload.put("requiredMods", value.requiredMods());
		payload.put("worldArchive", value.worldArchive());
		payload.put("frozen", value.frozen());
		payload.put("promptConfigured", value.prompt() != null && !value.prompt().isBlank());
		payload.put("checkCount", value.checks().size());
		payload.put("maxPlannerTurns", value.budget().maxPlannerTurns());
		payload.put("maxElapsedTicks", value.budget().maxElapsedTicks());
		payload.put("controlMode", "native_planner_goal");
		return payload;
	}

	private static Path outputDir(String requestedOutputDir, EvaluationScenario scenario) {
		if (requestedOutputDir != null && !requestedOutputDir.isBlank()) {
			return Path.of(requestedOutputDir).toAbsolutePath().normalize();
		}
		return FabricLoader.getInstance().getGameDir()
			.resolve("..")
			.resolve("eval-output")
			.resolve("manual")
			.resolve(LocalDateTime.now().format(MANUAL_OUTPUT_SUFFIX) + "-" + scenario.id())
			.toAbsolutePath()
			.normalize();
	}

	private static final class RuntimeEvaluationContext implements ScenarioEvaluationRunner.Context {
		private final EmbodiedAgentRuntime runtime;

		private RuntimeEvaluationContext(EmbodiedAgentRuntime runtime) {
			this.runtime = runtime;
		}

		@Override
		public long tick() {
			return runtime.tickCount();
		}

		@Override
		public long nowMs() {
			return System.currentTimeMillis();
		}

		@Override
		public boolean worldLoaded() {
			return runtime.sessionSnapshot().worldLoaded();
		}

		@Override
		public boolean plannerConfigured() {
			return runtime.llmAvailable();
		}

		@Override
		public boolean externalDriverActive() {
			return runtime.codexDriverActive();
		}

		@Override
		public boolean plannerInFlight() {
			return runtime.plannerDebugSnapshot().inFlight();
		}

		@Override
		public long gameplayDecisionCount() { return runtime.plannerGameplayDecisionCount(); }

		@Override
		public Optional<ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore.Goal> plannerGoal() {
			return runtime.plannerGoalSnapshot();
		}

		@Override
		public int inventoryCount(String itemId) {
			return runtime.inventoryItemCount(itemId);
		}

		@Override
		public String blockIdAt(int x, int y, int z) {
			return runtime.blockIdAt(x, y, z);
		}

		@Override
		public Map<String, String> blockPropertiesAt(int x, int y, int z) {
			return runtime.blockPropertiesAt(x, y, z);
		}

		@Override
		public int playerBlockX() {
			return playerBlockPos().getX();
		}

		@Override
		public int playerBlockY() {
			return playerBlockPos().getY();
		}

		@Override
		public int playerBlockZ() {
			return playerBlockPos().getZ();
		}

		@Override
		public boolean eventContains(String eventType) {
			return runtime.semanticEventContains(eventType);
		}

		@Override
		public String lastChatText() {
			return runtime.lastChatText();
		}

		@Override
		public String taskState() {
			return runtime.taskSnapshot().state().name();
		}

		@Override
		public String taskExecutionState() {
			return runtime.taskExecutionSnapshot().state().name();
		}

		@Override
		public void startPlannerGoal(String objective) throws java.io.IOException {
			runtime.startEvaluationGoal(objective);
		}

		private static BlockPos playerBlockPos() {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client == null || client.player == null) {
				return BlockPos.ORIGIN;
			}
			return client.player.getBlockPos();
		}
	}

	private record EvaluationRunRequest(String scenario, String outputDir) {
	}

	private enum RunState {
		IDLE,
		STARTING,
		RUNNING,
		CLEANUP
	}
}
