package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.goals.AcquisitionConstraints;

import ai.moeru.airicraft.AiricraftConfig;
import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.SingleplayerWorldService;
import ai.moeru.airicraft.agent.behavior.BehaviorTreeRuntime;
import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.baritone.BaritonePathfindSettings;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.behavior.BehaviorTreeSnapshot;
import ai.moeru.airicraft.agent.chat.ChatService;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.debug.AgentDebugTimelineQueryResult;
import ai.moeru.airicraft.agent.debug.ChatDebugSnapshot;
import ai.moeru.airicraft.agent.debug.CollectResourceTaskDebugSnapshot;
import ai.moeru.airicraft.agent.debug.ConversationSourcesDebugSnapshot;
import ai.moeru.airicraft.agent.debug.DialogueDebugSnapshot;
import ai.moeru.airicraft.agent.debug.EventPipelineDebugSnapshot;
import ai.moeru.airicraft.agent.debug.LlmFlightRecordQueryResult;
import ai.moeru.airicraft.agent.debug.LlmFlightRecorder;
import ai.moeru.airicraft.agent.debug.PlannerAttemptDebugSnapshot;
import ai.moeru.airicraft.agent.actions.ActionGraphExecutionInput;
import ai.moeru.airicraft.agent.actions.ActionGraphExecutionSnapshot;
import ai.moeru.airicraft.agent.actions.ActionGraphAdmission;
import ai.moeru.airicraft.agent.actions.ActionGraphCoordinator;
import ai.moeru.airicraft.agent.actions.ActionGraphCoordinatorEvent;
import ai.moeru.airicraft.agent.actions.ActionGraphExecutionView;
import ai.moeru.airicraft.agent.actions.ActionGraphStartResult;
import ai.moeru.airicraft.agent.actions.ActionGraphAgentPosition;
import ai.moeru.airicraft.agent.actions.ActionGraphWatchSnapshot;
import ai.moeru.airicraft.agent.actions.ActionWatchAnchor;
import ai.moeru.airicraft.agent.actions.ActionWatchProgressKind;
import ai.moeru.airicraft.agent.actions.ActionWatchProgressObservation;
import ai.moeru.airicraft.agent.actions.BlockAcquisitionIndex;
import ai.moeru.airicraft.agent.actions.ActionGraphDebugService;
import ai.moeru.airicraft.agent.actions.ActionGraphPrimitiveDispatch;
import ai.moeru.airicraft.agent.actions.ActionGraphPrimitiveDispatchResult;
import ai.moeru.airicraft.agent.actions.ActionGraphPrimitiveMapper;
import ai.moeru.airicraft.agent.actions.ActionFact;
import ai.moeru.airicraft.agent.actions.ActionFactProvenance;
import ai.moeru.airicraft.agent.actions.ActionFactIdentity;
import ai.moeru.airicraft.agent.actions.ActionFactType;
import ai.moeru.airicraft.agent.actions.ActionGoal;
import ai.moeru.airicraft.agent.actions.ActionPlanStep;
import ai.moeru.airicraft.agent.actions.ActionResolverContext;
import ai.moeru.airicraft.agent.actions.FarmBootstrapFactProvider;
import ai.moeru.airicraft.agent.actions.MinecraftBlockAcquisitionKnowledgeService;
import ai.moeru.airicraft.agent.actions.NearbyBlockAvailability;
import ai.moeru.airicraft.agent.dialogue.DialogueIntent;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.dialogue.DialogueSpeakerLabels;
import ai.moeru.airicraft.agent.dialogue.DialogueSnapshot;
import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.events.AgentEventPipeline;
import ai.moeru.airicraft.agent.events.PhysicalEventObserver;
import ai.moeru.airicraft.agent.events.EventPolicyChanges;
import ai.moeru.airicraft.agent.events.EventPolicyDecision;
import ai.moeru.airicraft.agent.events.EventPolicyEffect;
import ai.moeru.airicraft.agent.events.EventPolicyIntervention;
import ai.moeru.airicraft.agent.events.EventPolicyMatch;
import ai.moeru.airicraft.agent.events.EventPolicyRule;
import ai.moeru.airicraft.agent.events.EventPolicyRuleUpsert;
import ai.moeru.airicraft.agent.events.EventPolicyState;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.FlightRecordingObservability;
import ai.moeru.airicraft.agent.recording.PlannerCallJournal;
import ai.moeru.airicraft.agent.recording.PlannerCallRecordV1;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import ai.moeru.airicraft.agent.follow.FollowCapability;
import ai.moeru.airicraft.agent.follow.FollowState;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.idle.IdleIdeaScheduler;
import ai.moeru.airicraft.agent.idle.IdleIdeasConfig;
import ai.moeru.airicraft.agent.job.ActiveJob;
import ai.moeru.airicraft.agent.job.ActiveJobProposal;
import ai.moeru.airicraft.agent.job.ActiveJobStatus;
import ai.moeru.airicraft.agent.job.ActiveJobType;
import ai.moeru.airicraft.agent.job.ActiveJobRuntime;
import ai.moeru.airicraft.agent.lighting.LightingPolicy;
import ai.moeru.airicraft.agent.lighting.LightingRuntime;
import ai.moeru.airicraft.agent.lighting.MiningIlluminationPreflight;
import ai.moeru.airicraft.agent.llm.CompactionExecutionResult;
import ai.moeru.airicraft.agent.llm.CurrentWorldQueryService;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionService;
import ai.moeru.airicraft.agent.llm.ExternalPlannerToolResult;
import ai.moeru.airicraft.agent.llm.LlmBackendException;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleChatClient;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleLlmBackend;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleVisionBackend;
import ai.moeru.airicraft.agent.llm.PlannerActionToolExecutor;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerContextAggregator;
import ai.moeru.airicraft.agent.llm.PlannerExecutor;
import ai.moeru.airicraft.agent.llm.PlannerIntent;
import ai.moeru.airicraft.agent.llm.PlannerCompactionService;
import ai.moeru.airicraft.agent.llm.PlannerOrchestratorDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import ai.moeru.airicraft.agent.llm.VisionDescription;
import ai.moeru.airicraft.agent.llm.WorldReadLedger;
import ai.moeru.airicraft.agent.session.AutoLanOpenState;
import ai.moeru.airicraft.agent.session.LanHostingService;
import ai.moeru.airicraft.agent.session.PlayerLifecycleState;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.session.SessionRuntime;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexEvent;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexRuntime;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexSnapshot;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexState;
import ai.moeru.airicraft.agent.shell.PlannerShellComponents;
import ai.moeru.airicraft.agent.shell.PlannerShellEvent;
import ai.moeru.airicraft.agent.shell.PlannerShellFactory;
import ai.moeru.airicraft.agent.shell.PlannerShellJournal;
import ai.moeru.airicraft.agent.social.ChatIngestService;
import ai.moeru.airicraft.agent.social.NearbyPlayerSnapshot;
import ai.moeru.airicraft.agent.social.NearbyPlayerTracker;
import ai.moeru.airicraft.agent.social.PrimaryInteractionPlayer;
import ai.moeru.airicraft.agent.social.PrimaryInteractionResolver;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskFailureCode;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.agent.tasks.InventoryItemCounter;
import ai.moeru.airicraft.agent.tasks.InventoryResourceCounter;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.NearbyEntityService;
import ai.moeru.airicraft.agent.tasks.ResourceGatheringCatalog;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.tasks.BlockBreakStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockPlacementStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockUseStepArgs;
import ai.moeru.airicraft.agent.tasks.TaskState;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.TaskTerminationCause;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import ai.moeru.airicraft.agent.tasks.WorldTaskExecutor;
import ai.moeru.airicraft.agent.tasks.CollectSmeltedItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunityResolver;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunitySnapshot;
import ai.moeru.airicraft.agent.tasks.DropItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.EntityAttackMode;
import ai.moeru.airicraft.agent.tasks.EntityInteractionStepArgs;
import ai.moeru.airicraft.agent.tasks.EntitySelector;
import ai.moeru.airicraft.agent.tasks.ReturnToSurfaceStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltingActionResult;
import ai.moeru.airicraft.agent.tasks.SmeltingFuelMode;
import ai.moeru.airicraft.agent.tasks.SmeltingOption;
import ai.moeru.airicraft.agent.tasks.SmeltingOutputReadyEvent;
import ai.moeru.airicraft.agent.tasks.SmeltingPlannerService;
import ai.moeru.airicraft.agent.tasks.SmeltingOpportunitySnapshot;
import ai.moeru.airicraft.agent.tasks.SmeltingProcessManager;
import ai.moeru.airicraft.agent.tasks.SmeltingProcessSnapshot;
import ai.moeru.airicraft.agent.tasks.SurfaceMemory;
import ai.moeru.airicraft.agent.tasks.WorldTaskType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.TreeMap;

public final class EmbodiedAgentRuntime implements PlannerActionToolExecutor {
	static final long CHAT_ECHO_SUPPRESSION_TICKS = 40L;
	static final int CRAFT_TOOL_RESULT_TIMEOUT_TICKS = 40;
	static final int BLOCK_MODIFICATION_TOOL_RESULT_TIMEOUT_TICKS = 40;
	private static final long SMELTING_OUTPUT_READY_POLL_INTERVAL_TICKS = 20L;
	private static final long RESPAWN_RETRY_TICKS = 20L;
	private static final int NEARBY_BLOCK_HORIZONTAL_RADIUS = 16;
	private static final int NEARBY_BLOCK_VERTICAL_RADIUS = 16;
	private static final long NEARBY_BLOCK_SCAN_INTERVAL_TICKS = 10L;
	private static final int NEARBY_BLOCK_SCAN_MOVEMENT_THRESHOLD = 4;
	private static final List<String> KNOWN_NON_BLOCK_MINE_ITEM_IDS = List.of(
		"minecraft:raw_iron",
		"minecraft:iron_ingot"
	);
	private final Map<String, EventRoutingProfile> eventRoutingProfiles = createEventRoutingProfiles();

	private final AiricraftConfig airicraftConfig;
	private final AgentConfig config;
	private final SingleplayerWorldService singleplayerWorldService = new SingleplayerWorldService();
	private final SessionRuntime sessionRuntime = new SessionRuntime();
	private final LanHostingService lanHostingService = new LanHostingService();
	private final AutoLanOpenState autoLanOpenState = new AutoLanOpenState();
	private final AgentObservability observability;
	private final LlmFlightRecorder llmFlightRecorder = new LlmFlightRecorder();
	private final AgentDebugRecorder debugRecorder = new AgentDebugRecorder();
	private final SemanticEventBuffer eventBuffer = new SemanticEventBuffer(512);
	private final SemanticEventBuffer plannerEventBuffer = new SemanticEventBuffer(512);
	private final EventPolicyState eventPolicyState = new EventPolicyState();
	private final ActiveJobRuntime activeJobRuntime = new ActiveJobRuntime();
	private final AgentEventPipeline eventPipeline = new AgentEventPipeline(
		eventBuffer,
		plannerEventBuffer,
		eventPolicyState,
		eventRoutingProfiles,
		debugRecorder,
		this::resolveDefaultEventPolicy
	);
	private final ChatIngestService chatIngestService = new ChatIngestService();
	private final LocalDamageTracker localDamageTracker = new LocalDamageTracker();
	private PhysicalEventObserver physicalEventObserver;
	private ai.moeru.airicraft.agent.events.SlowMiningObserver slowMiningObserver;
	private Object physicalObservationWorld;
	private final NearbyPlayerTracker nearbyPlayerTracker;
	private final PrimaryInteractionResolver primaryInteractionResolver = new PrimaryInteractionResolver(200L);
	private final IdleIdeaScheduler idleIdeaScheduler;
	private final FollowCapability followCapability = new FollowCapability();
	private final BehaviorTreeRuntime behaviorTreeRuntime;
	private final ChatService chatService = new ChatService();
	private final CurrentViewVisionService visionService;
	private final DialogueRuntime dialogueRuntime;
	private final PlannerShellJournal plannerJournal;
	private final PlannerCallJournal plannerCallJournal;
	private final WorldTaskExecutor worldTaskExecutor;
	private final InventoryResourceCounter inventoryResourceCounter = new InventoryResourceCounter();
	private final InventoryItemCounter inventoryItemCounter = new InventoryItemCounter();
	private final SurfaceMemory surfaceMemory = new SurfaceMemory();
	private final SmeltingProcessManager smeltingProcessManager;
	private final SmeltingPlannerService smeltingPlannerService = new SmeltingPlannerService();
	private final WorldReadLedger worldReadLedger = new WorldReadLedger();
	private final CurrentWorldQueryService guardedWorldQueryService = new CurrentWorldQueryService(MinecraftClient::getInstance);
	private final ActionGraphCoordinator actionGraphCoordinator;
	private final SurvivalReflexRuntime survivalReflexRuntime;
	private final LightingRuntime lightingRuntime = new LightingRuntime();
	private final PlayerItemUseController playerItemUseController = new PlayerItemUseController();
	private final FoodRuntime foodRuntime = new FoodRuntime();
	private final SurvivalReflexRuntime.CombatEating combatEating = new SurvivalReflexRuntime.CombatEating() {
		@Override public boolean needed(net.minecraft.client.network.ClientPlayerEntity player) {
			return foodRuntime.policy().shouldSeekCombatHeal(player.getHungerManager().getFoodLevel(),
				player.getHealth(), player.getMaxHealth());
		}
		@Override public boolean hasEligibleFood(net.minecraft.client.network.ClientPlayerEntity player) {
			return FoodSelector.choose(foodCandidates(player), foodRuntime.policy().foodChoice(),
				player.getHungerManager().getFoodLevel()).isPresent();
		}
		@Override public Optional<String> candidate(net.minecraft.client.network.ClientPlayerEntity player) {
			if (player.currentScreenHandler != player.playerScreenHandler
				|| !player.currentScreenHandler.getCursorStack().isEmpty()) return Optional.empty();
			return foodRuntime.combatCandidate(player.getHungerManager().getFoodLevel(), player.getHealth(),
				player.getMaxHealth(), foodCandidates(player));
		}
		@Override public boolean ready(long tick) { return foodRuntime.readyToEat(tick); }
		@Override public boolean eating() { return playerItemUseController.eating(); }
		@Override public void start(MinecraftClient client, String itemId, long tick) {
			foodRuntime.recordAttempt(tick);
			playerItemUseController.eat(client, itemId, tick);
		}
		@Override public void cancel(MinecraftClient client) { playerItemUseController.reset(client); }
	};
	private final EmbodiedPlannerActionToolExecutor plannerActionToolExecutor;
	private final MinecraftBlockAcquisitionKnowledgeService blockAcquisitionKnowledgeService = new MinecraftBlockAcquisitionKnowledgeService();
	private final boolean codexDriverActive;
	private ai.moeru.airicraft.policy.PolicyRuntime policyRuntime;
	private ai.moeru.airicraft.agent.work.WorkHandle policyWork;
	private final ai.moeru.airicraft.agent.llm.PlannerOrchestrator policyToolDispatcher;
	private boolean dispatchingPolicyTool;
	private final java.util.Set<ai.moeru.airicraft.agent.work.WorkHandle> policyChildren = new java.util.HashSet<>();

	private boolean initialized;
	private volatile long tickCount;
	private final ai.moeru.airicraft.agent.work.WorkHistory workHistory = new ai.moeru.airicraft.agent.work.WorkHistory();
	private Object workWorld;
	private long worldLoadTick = -1L;
	private Boolean proactiveSocialModeOverride;
	private boolean evaluationPlannerSuppressed;
	private SessionSnapshot sessionSnapshot = SessionSnapshot.initial();
	private SessionSnapshot sessionSnapshotOverrideForTests;
	private BlockAcquisitionIndex blockAcquisitionsOverrideForTests;
	private FollowState followState = FollowState.idle();
	private TaskSnapshot taskSnapshot = TaskSnapshot.idle();
	private TaskExecutionSnapshot taskExecutionSnapshot = TaskExecutionSnapshot.idle();
	private MissionExecutionSnapshot missionExecutionSnapshot = MissionExecutionSnapshot.idle();
	private long lastSystemChatTick = -1L;
	private String lastSystemChatText;
	private Float lastKnownPlayerHealth;
	private boolean deathBoundaryApplied;
	private long lastRespawnRequestTick = -1L;
	private long lastSmeltingOutputReadyPollTick = Long.MIN_VALUE;
	private Object nearbyBlockSnapshotWorld;
	private BlockPos nearbyBlockSnapshotOrigin;
	private long nearbyBlockSnapshotTick = Long.MIN_VALUE;
	private Map<String, Integer> nearbyBlockSnapshot = Map.of();
	private Map<String, Integer> nearbyHarvestableBlockSnapshot;
	private final Map<UUID, String> seenPlayerNames = new LinkedHashMap<>();
	private volatile PendingCraftToolResult pendingCraftToolResult;
	private record ObservedInteractions(net.minecraft.server.MinecraftServer server, List<ai.moeru.airicraft.memory.InteractionLogbook.Entry> entries) { }
	private final java.util.concurrent.ArrayBlockingQueue<ObservedInteractions> pendingInteractions = new java.util.concurrent.ArrayBlockingQueue<>(128);
	private final java.util.concurrent.atomic.AtomicInteger droppedInteractionBatches = new java.util.concurrent.atomic.AtomicInteger();
	private Object lastObservedObjective;
	private final AtomicReference<PendingBlockModificationToolResult> pendingBlockModificationToolResult = new AtomicReference<>();
	private TaskTerminalEvent pendingActionGraphTerminalEvent;

	public EmbodiedAgentRuntime(
		AiricraftConfig airicraftConfig,
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		WorldTaskExecutor worldTaskExecutor,
		AgentObservability observability,
		SmeltingProcessManager smeltingProcessManager,
		CameraController cameraController,
		BaritoneFacade baritoneFacade
	) {
		this.airicraftConfig = Objects.requireNonNull(airicraftConfig, "airicraftConfig");
		this.config = Objects.requireNonNull(config, "config");
		this.codexDriverActive = Boolean.getBoolean("airicraft.codexDriver");
		this.survivalReflexRuntime = new SurvivalReflexRuntime(this.config.reflex(), baritoneFacade, cameraController);
		this.worldTaskExecutor = Objects.requireNonNull(worldTaskExecutor, "worldTaskExecutor");
		this.observability = new FlightRecordingObservability(Objects.requireNonNull(observability, "observability"), llmFlightRecorder);
		this.smeltingProcessManager = Objects.requireNonNull(smeltingProcessManager, "smeltingProcessManager");
		this.actionGraphCoordinator = new ActionGraphCoordinator(this::dispatchActionGraphPrimitive);
		CameraController effectiveCameraController = Objects.requireNonNull(cameraController, "cameraController");
		this.behaviorTreeRuntime = new BehaviorTreeRuntime(effectiveCameraController);
		this.nearbyPlayerTracker = new NearbyPlayerTracker(resolveNearbyPlayerTrackingRadius(airicraftConfig));
		this.idleIdeaScheduler = new IdleIdeaScheduler(effectiveIdleIdeasConfig(IdleIdeasConfig.defaults()));
		this.plannerActionToolExecutor = new EmbodiedPlannerActionToolExecutor(
			this::plannerActionToolExecutionState,
			this::executeCraftRecipePlannerTool,
			this::executeBlockModificationPlannerTool,
			this::executePlannerToolCallNow
		);
		Clock clock = Clock.systemDefaultZone();
		PlannerShellComponents plannerShell = PlannerShellFactory.create(
			config,
				Objects.requireNonNull(screenshotService, "screenshotService"),
				this.observability,
				clock,
				debugRecorder,
				this::executePlannerAction,
				this::emitPlannerToolNarration,
				this::beforePlannerToolExecution,
				worldReadLedger::recordObserved,
				effectiveCameraController,
				EmbodiedAgentRuntime::integratedServerTick
			);
		this.visionService = plannerShell.visionService();
		this.dialogueRuntime = plannerShell.dialogueRuntime();
		this.policyToolDispatcher = plannerShell.controllerPlanner();
		ai.moeru.airicraft.memory.InteractionLogbookRecorder.observe((server, entries) -> {
			if (!pendingInteractions.offer(new ObservedInteractions(server, entries))) droppedInteractionBatches.incrementAndGet();
		});
		this.dialogueRuntime.configureDecisionContext(this::currentPlannerDecisionContext);
		this.llmFlightRecorder.configureClock(() -> tickCount, EmbodiedAgentRuntime::integratedServerTick);
		if (codexDriverActive) {
			this.dialogueRuntime.enableExternalDriver();
		}
		this.plannerJournal = plannerShell.plannerJournal();
		this.plannerCallJournal = plannerShell.plannerCallJournal();
		this.debugRecorder.recordDialogueState(this.dialogueRuntime.snapshot());
	}

	static EmbodiedAgentRuntime createForTests(WorldTaskExecutor worldTaskExecutor) {
		AiricraftConfig airicraftConfig = AiricraftConfig.defaults();
		AgentConfig agentConfig = AgentConfig.defaults();
		return new EmbodiedAgentRuntime(
			airicraftConfig,
			agentConfig,
			new FirstPersonScreenshotService(),
			worldTaskExecutor,
			AgentObservability.create(agentConfig.observability()),
			new SmeltingProcessManager(),
			new CameraController(airicraftConfig.cameraLerpDefaultTicks()),
			null
		);
	}

	public AgentConfig config() {
		return config;
	}

	public void updateIdleIdeasConfig(IdleIdeasConfig idleIdeasConfig) {
		idleIdeaScheduler.updateConfig(effectiveIdleIdeasConfig(idleIdeasConfig));
	}

	public Map<String, Object> observabilityDebugSnapshot() {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("implementation", observability.getClass().getName());
		snapshot.put("enabled", config.observability().enabled());
		snapshot.put("exporter", config.observability().exporter());
		snapshot.put("vendorProfile", config.observability().vendorProfile());
		snapshot.put("otlpEndpoint", config.observability().otlpEndpoint());
		snapshot.put("captureInputs", config.observability().captureInputs());
		snapshot.put("captureOutputs", config.observability().captureOutputs());
		snapshot.put("captureImages", config.observability().captureImages());
		snapshot.put("debugLogExports", config.observability().debugLogExports());
		return snapshot;
	}

	public void onClientStarted(MinecraftClient client) {
		initialized = true;
		sessionRuntime.onClientStarted(client, tickCount, eventBuffer);
		sessionSnapshot = sessionRuntime.snapshot();
	}

	public void onWorldLeave() {
		sessionRuntime.onWorldLeave(tickCount, eventBuffer);
		sessionSnapshot = sessionRuntime.snapshot();
		autoLanOpenState.clear();
		localDamageTracker.clear();
		physicalObserver().reset();
		if (slowMiningObserver != null) slowMiningObserver.reset();
		physicalObservationWorld = null;
		sessionSnapshotOverrideForTests = null;
		blockAcquisitionsOverrideForTests = null;
		nearbyPlayerTracker.clear(tickCount, eventBuffer);
		primaryInteractionResolver.clear();
		eventPolicyState.clear();
		eventPipeline.clearPlannerFeed();
		completePendingCraftToolResult("Tool result for craft_recipe: cancelled reason=world_left");
		cancelPolicy("world_left");
		cancelPendingBlockModificationToolResult(PendingBlockModificationStopReason.WORLD_LEFT);
		dialogueRuntime.clear();
		worldTaskExecutor.onWorldLeave();
		surfaceMemory.clear();
		worldReadLedger.clear();
		actionGraphCoordinator.cancelAll("world_left", tickCount);
		actionGraphCoordinator.clear();
		blockAcquisitionKnowledgeService.reset();
		clearNearbyBlockSnapshot();
		pendingActionGraphTerminalEvent = null;
		activeJobRuntime.clear();
		idleIdeaScheduler.reset();
		followCapability.clear();
		followState = FollowState.idle();
		taskSnapshot = TaskSnapshot.idle();
		taskExecutionSnapshot = TaskExecutionSnapshot.idle();
		missionExecutionSnapshot = MissionExecutionSnapshot.idle();
		behaviorTreeRuntime.stop(MinecraftClient.getInstance());
		chatService.clear();
		proactiveSocialModeOverride = null;
		evaluationPlannerSuppressed = false;
		lastSystemChatTick = -1L;
		lastSystemChatText = null;
		lastKnownPlayerHealth = null;
		deathBoundaryApplied = false;
		lastRespawnRequestTick = -1L;
		survivalReflexRuntime.reset(MinecraftClient.getInstance());
		playerItemUseController.reset(MinecraftClient.getInstance());
		foodRuntime.reset();
		lightingRuntime.reset();
		seenPlayerNames.clear();
	}

	public void onClientTick(MinecraftClient client) {
		try { tickClient(client); }
		finally { refreshWorkHistory(); }
		tickPolicy();
	}

	private void tickClient(MinecraftClient client) {
		drainInteractionEvidence(client);
		ai.moeru.airicraft.agent.spatial.WorldTravelPolicy.tick(client, activeTaskInProgress());
		stopWorkOutsideTravelBounds(client);
		dialogueRuntime.refreshPlannerGoalWorld();
		tickCount++;
		localDamageTracker.pruneStale(tickCount);
		BehaviorTreeSnapshot previousTreeSnapshot = behaviorTreeRuntime.snapshot();
		boolean wasWorldLoaded = sessionSnapshot.worldLoaded();
		sessionSnapshot = sessionSnapshotOverrideForTests != null
			? sessionSnapshotOverrideForTests.withTickCount(tickCount)
			: sessionRuntime.poll(client, tickCount, eventBuffer);
		blockAcquisitionKnowledgeService.tick(client);
		activeJobRuntime.updateBlockAcquisitions(blockAcquisitions());
		enforcePlayerLifecycle(client);
		if (!wasWorldLoaded && sessionSnapshot.worldLoaded()) {
			worldLoadTick = tickCount;
			localDamageTracker.onLifecycleReset(tickCount);
			physicalObserver().reset();
			if (slowMiningObserver != null) slowMiningObserver.reset();
		}
		if (sessionSnapshot.requiresRespawn()) {
			physicalObserver().reset();
			if (slowMiningObserver != null) slowMiningObserver.reset();
			behaviorTreeRuntime.tick(
				client,
				sessionSnapshot,
				dialogueRuntime,
				chatService,
				debugRecorder,
				Optional.empty(),
				FollowState.idle(),
				TaskExecutionSnapshot.idle(),
				tickCount
			);
			drainEventPipeline();
			lastKnownPlayerHealth = currentPlayerHealth(client);
			return;
		}
		observePhysicalEvents(client);
		observeSlowMining(client);
		openLanIfSingleplayerLocal(client);
		surfaceMemory.tick(client, tickCount);
		playerItemUseController.tick(client, tickCount).ifPresent(result -> eventBuffer.append(
			tickCount,
			result.completed() ? "food.eaten" : "food.eat_failed",
			Map.of("itemId", result.itemId(), "reason", result.reason())
		));
		tickSurvivalReflex(client);
		tickIdleEating(client);
		drainEventPipeline();

		nearbyPlayerTracker.poll(client, tickCount, eventBuffer);
		primaryInteractionResolver.current().ifPresent(current ->
			primaryInteractionResolver.clearIfNotNearby(current.uuid(), nearbyPlayerTracker.isNearby(current.uuid()))
		);
		primaryInteractionResolver.expireInactive(tickCount);
		recordSmeltingOutputReadyEvents(client);
		drainEventPipeline();

		WorldEvidence worldEvidence = currentWorldEvidence(client);
		// Idle/primitive jobs may not project a semantic task, but every planner trigger needs fresh evidence.
		missionExecutionSnapshot = missionExecutionSnapshot.withEvidence(worldEvidence);
		dialogueRuntime.updateGameplayWorkIdle(!policyActive() && !actionGraphCoordinator.hasNonterminal()
			&& isIdleForIdleIdeaScheduling(activeJobRuntime.current()) && activeGoal().isEmpty()
			&& !playerItemUseController.eating());
		DialogueResponse completedDialogueResponse = dialogueRuntime.poll(
			tickCount,
			eventBuffer,
			sessionSnapshot,
			activeGoal(),
			taskSnapshot,
			missionExecutionSnapshot
		);
		recordStalePlannerRejections();
		if (completedDialogueResponse != null) {
			Optional<GoalSnapshot> previousGoal = activeGoal();
			applyPlannerEventPolicyChanges(completedDialogueResponse.eventPolicyChanges());
			applyTaskIntent(completedDialogueResponse, worldEvidence);
			recordPlannerOutcome(completedDialogueResponse, previousGoal, activeGoal());
			drainEventPipeline();
		}
		debugRecorder.recordDialogueState(dialogueRuntime.snapshot());
		behaviorTreeRuntime.tickChat(client, sessionSnapshot, dialogueRuntime, chatService, debugRecorder, tickCount);
		if (survivalReflexRuntime.snapshot().holdsNormalTasks()) {
			tickActionGraph(worldEvidence, false);
			pauseNormalWorkForReflex(client);
			drainEventPipeline();
			if (survivalReflexRuntime.snapshot().state() == SurvivalReflexState.AWAITING_PLANNER) {
				maybeFireIdleIdeaTrigger(activeGoal());
			}
			lastKnownPlayerHealth = currentPlayerHealth(client);
			return;
		}

		if (policyActive() && !activeTaskInProgress() && !actionGraphCoordinator.hasNonterminal()) {
			behaviorTreeRuntime.stop(client);
			lastKnownPlayerHealth = currentPlayerHealth(client);
			return; // Between child actions, the policy owns the foreground lane.
		}

		TaskSnapshot previousTaskSnapshot = taskSnapshot;
		tickActionGraph(worldEvidence, true);
		activeJobRuntime.tick(
			taskExecutionSnapshot,
			worldEvidence,
			sessionSnapshot.companionActuationAllowed(),
			true, // The acquisition executor owns scoped discovery, including item drops.
			tickCount
		);
		TaskSnapshot projectedTaskSnapshot = activeJobRuntime.taskSnapshot();
		if (isSemanticTaskSnapshot(projectedTaskSnapshot)) {
			taskSnapshot = projectedTaskSnapshot;
			missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
		}
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
		recordSemanticTaskTransition(previousTaskSnapshot, taskSnapshot);
		Optional<GoalSnapshot> activeGoal = activeGoal();
		Optional<WorldTaskRequest> activeTaskRequest = activeJobRuntime.activeTaskRequest();
		maybeFireIdleIdeaTrigger(activeGoal);

		followState = followCapability.tick(
			client,
			sessionSnapshot,
			activeGoal,
			nearbyPlayerTracker,
			tickCount,
			eventBuffer
		);
		TaskExecutionSnapshot previousTaskExecutionSnapshot = taskExecutionSnapshot;
		Optional<TaskTerminalEvent> terminalTaskEvent = worldTaskExecutor.tick(sessionSnapshot, activeTaskRequest);
		taskExecutionSnapshot = worldTaskExecutor.snapshot();
		boolean semanticTaskContext = hasSemanticTaskContext(previousTaskSnapshot, taskSnapshot);
		recordTaskStateTransition(previousTaskExecutionSnapshot, taskExecutionSnapshot, semanticTaskContext);
		terminalTaskEvent.ifPresent(event -> {
			ActiveJobRuntime.TerminalTaskReport report = activeJobRuntime.reportTerminalTaskEvent(event, activeTaskRequest);
			report.warning().ifPresent(this::handleInternalTaskWarning);
			report.event().ifPresent(this::completePendingCraftToolResult);
			report.event().ifPresent(reportedEvent -> completePendingBlockModificationToolResult(reportedEvent, activeTaskRequest));
			report.event().ifPresent(reportedEvent -> {
				captureActionGraphTerminalEvent(reportedEvent);
				handleTerminalTaskEvent(reportedEvent, semanticTaskContext, activeTaskRequest);
			});
		});
		WorldTaskType lightingActivity = activeTaskRequest
			.map(WorldTaskRequest::type).orElse(null);
		boolean lightingActuationAllowed = sessionSnapshot.companionActuationAllowed()
			&& (activeTaskRequest.isEmpty() || taskExecutionSnapshot.state() == TaskExecutionState.RUNNING);
		lightingRuntime.tick(client, lightingActivity, lightingActuationAllowed, tickCount).ifPresent(event ->
			eventBuffer.append(tickCount, "lighting.torch_placed", event.payload())
		);
		completePendingCraftToolResultFromTaskSnapshot(taskSnapshot);
		completePendingBlockModificationToolResultFromTaskSnapshot(taskSnapshot);
		expirePendingCraftToolResultIfTimedOut();
		expirePendingBlockModificationToolResultIfTimedOut();
		behaviorTreeRuntime.tick(
			client,
			sessionSnapshot,
			dialogueRuntime,
			chatService,
			debugRecorder,
			activeGoal,
			followState,
			taskExecutionSnapshot,
			tickCount
		);
		BehaviorTreeSnapshot currentTreeSnapshot = behaviorTreeRuntime.snapshot();
		if (
			followState.goalActive()
			&& followState.targetNearby()
			&& !previousTreeSnapshot.movement().stuck()
			&& currentTreeSnapshot.movement().stuck()
		) {
			eventBuffer.append(tickCount, "follow.stuck", Map.of(
				"player", followState.targetPlayer(),
				"distanceToTarget", followState.distanceToTarget()
			));
		}
		drainEventPipeline();

		lastKnownPlayerHealth = currentPlayerHealth(client);
	}

	private PhysicalEventObserver physicalObserver() {
		if (physicalEventObserver == null) physicalEventObserver = new PhysicalEventObserver();
		return physicalEventObserver;
	}

	private Map<String, Object> physicalTaskContext() {
		var job = activeJobRuntime.current();
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("reflexState", survivalReflexRuntime.snapshot().state().name());
		if (job.jobId() != null && (!job.status().terminal() || tickCount - job.updatedTick() <= 100)) {
			context.put("taskId", job.jobId());
			context.put("taskType", job.type().name());
			context.put("taskStatus", job.status().name());
			GoalSnapshot goal = job.directGoal() != null ? job.directGoal()
				: Objects.equals(job.jobId(), taskExecutionSnapshot.taskId()) ? taskExecutionSnapshot.activeGoal() : null;
			if (goal != null && goal.position() != null) {
				var target = goal.position();
				context.put("target", Map.of("x", target.x(), "y", target.y(), "z", target.z(), "exactY", target.exactY()));
			}
		}
		return Map.copyOf(context);
	}

	private Map<String, Object> currentPhysicalState() {
		var client = MinecraftClient.getInstance();
		if (client == null || client.player == null) return Map.of();
		var player = client.player;
		var velocity = player.getVelocity();
		return Map.of("position", Map.of("x",player.getX(),"y",player.getY(),"z",player.getZ()),
			"velocity", Map.of("x",velocity.x,"y",velocity.y,"z",velocity.z),
			"grounded",player.isOnGround(),"touchingWater",player.isTouchingWater(),"climbing",player.isClimbing());
	}

	ai.moeru.airicraft.agent.llm.PlannerDecisionContext currentPlannerDecisionContext() {
		ai.moeru.airicraft.agent.spatial.WorldTravelPolicy.observeChanges(change ->
			eventBuffer.append(tickCount, "policy.travel_changed", change));
		refreshWorkHistory();
		var client = MinecraftClient.getInstance();
		var facts = new java.util.LinkedHashMap<String, Object>();
		Object objective = dialogueRuntime.currentPlannerObjective();
		if (!Objects.equals(lastObservedObjective, objective)) {
			lastObservedObjective = objective;
			eventBuffer.append(tickCount,"objective.changed",Map.of("objective",objective));
		}
		facts.put("objective", objective);
		facts.put("session", sessionSnapshot.mode());
		facts.put("travelRestrictions", ai.moeru.airicraft.agent.spatial.WorldTravelPolicy.snapshot());
		facts.put("physical", currentPhysicalState());
		facts.put("reflex", survivalReflexRuntime.snapshot());
		facts.put("foodPolicy", foodRuntime.policy());
		var work = workHistory.list();
		var recent = work.stream().filter(value -> value.state().terminal()).toList();
		var currentWork = new java.util.ArrayList<>(work.stream().filter(value -> !value.state().terminal()).toList());
		currentWork.addAll(recent.subList(Math.max(0, recent.size() - 8), recent.size()));
		facts.put("work", currentWork.stream().map(ai.moeru.airicraft.agent.work.WorkSnapshot::payload).toList());
		String worldSession = "out_of_world";
		if (client != null && client.world != null && client.player != null) {
			worldSession = client.world.getRegistryKey().getValue() + ":" + System.identityHashCode(client.world);
			facts.put("dimension", client.world.getRegistryKey().getValue().toString());
			var inventory = new java.util.TreeMap<String, Integer>();
			for (int i = 0; i < client.player.getInventory().size(); i++) {
				var stack = client.player.getInventory().getStack(i);
				if (!stack.isEmpty()) inventory.merge(net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
			}
			facts.put("inventory", inventory);
			int freeStorageSlots = 0;
			for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; slot++)
				if (client.player.getInventory().getStack(slot).isEmpty()) freeStorageSlots++;
			facts.put("inventoryCapacity", Map.of("freeStorageSlots", freeStorageSlots,
				"pickupConstraint", freeStorageSlots == 0
					? "No empty storage slots. Only drops compatible with an existing non-full stack can be picked up. Free space before collecting other items."
					: "Empty storage slots available"));
			facts.put("vitals", Map.of("health", client.player.getHealth(), "maxHealth", client.player.getMaxHealth(),
				"food", client.player.getHungerManager().getFoodLevel(), "air", client.player.getAir(), "maxAir", client.player.getMaxAir()));
		}
		String actuator = survivalReflexRuntime.snapshot().state() == SurvivalReflexState.ACTIVE ? "reflex"
			: survivalReflexRuntime.snapshot().holdsNormalTasks() ? "safety_hold"
			: policyActive() ? "policy" : activeTaskInProgress() ? "work" : "idle";
		return new ai.moeru.airicraft.agent.llm.PlannerDecisionContext(worldSession, tickCount, integratedServerTick(),
			dialogueRuntime.decisionOwner(), actuator, facts, eventBuffer.query(null));
	}

	private void observePhysicalEvents(MinecraftClient client) {
		if (client == null || client.player == null || client.world == null || !client.player.isAlive()) {
			physicalObserver().reset();
			if (slowMiningObserver != null) slowMiningObserver.reset();
			physicalObservationWorld = null;
			return;
		}
		if (physicalObservationWorld != client.world) {
			physicalObserver().reset();
			if (slowMiningObserver != null) slowMiningObserver.reset();
			physicalObservationWorld = client.world;
		}
		var player = client.player;
		var position = player.getPos();
		var velocity = player.getVelocity();
		var input = player.input == null ? net.minecraft.util.PlayerInput.DEFAULT : player.input.playerInput;
		boolean directional = input.forward() || input.backward() || input.left() || input.right() || input.jump() || input.sneak();
		var sample = new PhysicalEventObserver.Sample(
			tickCount, client.world.getRegistryKey().getValue().toString(),
			new PhysicalEventObserver.Position(position.x, position.y, position.z),
			new PhysicalEventObserver.Position(velocity.x, velocity.y, velocity.z),
			player.isOnGround(), player.isTouchingWater(), player.isSubmergedInWater(), player.isClimbing(),
			player.getAbilities().flying || player.isGliding() || player.hasVehicle(), directional, player.isOnFire(),
			player.isSubmergedInWater() && player.getAir() <= config.reflex().lowAirTicks(), player.getAir(), player.getHealth(), physicalTaskContext());
		for (var event : physicalObserver().observe(sample)) eventBuffer.append(tickCount, "player.physical", event.payload());
	}

	private void observeSlowMining(MinecraftClient client) {
		if (slowMiningObserver == null) slowMiningObserver = new ai.moeru.airicraft.agent.events.SlowMiningObserver();
		var job = activeJobRuntime.current();
		if (client == null || client.player == null || client.world == null || client.interactionManager == null
			|| job.jobId() == null || job.status().terminal()) {
			slowMiningObserver.observe(tickCount, null, 0);
			return;
		}
		var breaking = (ai.moeru.airicraft.mixin.client.ClientPlayerInteractionManagerAccessor) client.interactionManager;
		var pos = breaking.airicraft$currentBreakingPos();
		if (!breaking.airicraft$breakingBlock() || pos == null) {
			slowMiningObserver.observe(tickCount, null, 0);
			return;
		}
		var block = client.world.getBlockState(pos);
		var held = client.player.getMainHandStack();
		String heldId = Registries.ITEM.getId(held.getItem()).toString();
		String blockId = Registries.BLOCK.getId(block.getBlock()).toString();
		float delta = block.calcBlockBreakingDelta(client.player, client.world, pos);
		double estimatedTicks = delta > 0 ? Math.ceil(1.0 / delta) : -1;
		String target = job.jobId() + ":" + pos.asLong() + ":" + blockId + ":" + heldId;
		if (!slowMiningObserver.observe(tickCount, target, estimatedTicks)) return;
		int bestSlot = -1;
		double bestScore = -1;
		String bestItem = "minecraft:air";
		for (int slot = 0; slot < 36; slot++) {
			var stack = client.player.getInventory().getStack(slot);
			if (stack.isEmpty()) continue;
			// Rank base tool speed and harvest suitability; the live estimate above includes player conditions.
			double score = stack.getMiningSpeedMultiplier(block) / (block.isToolRequired() && !stack.isSuitableFor(block) ? 100.0 : 30.0);
			if (score > bestScore) {
				bestScore = score;
				bestSlot = slot;
				bestItem = Registries.ITEM.getId(stack.getItem()).toString();
			}
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("reason", "slow_mining");
		payload.put("context", physicalTaskContext());
		payload.put("block", blockId);
		payload.put("position", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
		payload.put("heldItem", heldId);
		payload.put("breakProgress", breaking.airicraft$currentBreakingProgress());
		payload.put("elapsedTicks", slowMiningObserver.elapsedTicks(tickCount));
		payload.put("estimatedBreakTicks", estimatedTicks);
		payload.put("bestCarriedToolByBaseSpeed", Map.of("item", bestItem, "slot", bestSlot));
		payload.put("message", "Slow mining observed: " + blockId + " with " + heldId
			+ "; elapsed " + slowMiningObserver.elapsedTicks(tickCount) + " ticks, estimated total " + estimatedTicks
			+ " ticks (-1 means no progress predicted). Best carried tool by base speed: " + bestItem + " in slot " + bestSlot
			+ ". Review current work and tool/conditions before continuing; this observation is not a task failure.");
		var event = eventBuffer.append(tickCount, "task.notice", payload);
		dialogueRuntime.queueTaskAttention(tickCount, event.seqNo());
	}

	private void tickSurvivalReflex(MinecraftClient client) {
		ActiveJob activeJob = activeJobRuntime.current();
		ActionGraphExecutionSnapshot graph = actionGraphExecutionSnapshot();
		SurvivalReflexRuntime.InterruptedWork interruptedWork = new SurvivalReflexRuntime.InterruptedWork(
			activeJob == null || activeJob.isIdle() || activeJob.status().terminal() ? null : activeJob.jobId(),
			actionGraphCoordinator.hasForeground() ? graph.executionId() : null
		);
		survivalReflexRuntime.tick(
			client,
			interruptedWork,
			tickCount,
			() -> releaseNormalActuatorsForReflex(client),
			combatEating
		);
		processSurvivalReflexEvents();
	}

	private void tickIdleEating(MinecraftClient client) {
		if (client == null || client.player == null || client.world == null || client.interactionManager == null) return;
		var player = client.player;
		boolean idle = sessionSnapshot.companionActuationAllowed() && !sessionSnapshot.requiresRespawn()
			&& !survivalReflexRuntime.snapshot().holdsNormalTasks() && !policyActive()
			&& !activeTaskInProgress() && !actionGraphCoordinator.hasNonterminal()
			&& isIdleForIdleIdeaScheduling(activeJobRuntime.current())
			&& !playerItemUseController.eating() && !player.isUsingItem()
			&& !client.interactionManager.isBreakingBlock()
			&& player.currentScreenHandler == player.playerScreenHandler
			&& player.currentScreenHandler.getCursorStack().isEmpty();
		var decision = foodRuntime.evaluateIdle(idle, player.getHungerManager().getFoodLevel(),
			player.getHealth(), player.getMaxHealth(), foodCandidates(player), tickCount);
		if (decision.missingFood()) {
			var event = eventBuffer.append(tickCount, "food.unavailable", Map.of(
				"goal", foodRuntime.policy().goal().name(), "foodChoice", foodRuntime.policy().foodChoice().name(),
				"hunger", player.getHungerManager().getFoodLevel()));
			dialogueRuntime.queueTaskAttention(tickCount, event.seqNo());
		}
		decision.itemId().ifPresent(itemId -> {
			foodRuntime.recordAttempt(tickCount);
			try {
				playerItemUseController.eat(client, itemId, tickCount);
				eventBuffer.append(tickCount, "food.eat_started", Map.of("itemId", itemId, "source", "idle_policy"));
			}
			catch (RuntimeException exception) {
				eventBuffer.append(tickCount, "food.eat_failed", Map.of("itemId", itemId,
					"reason", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
			}
		});
	}

	private static List<FoodSelector.Candidate> foodCandidates(net.minecraft.client.network.ClientPlayerEntity player) {
		var candidates = new ArrayList<FoodSelector.Candidate>();
		for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; slot++) {
			var stack = player.getInventory().getStack(slot);
			if (stack.isEmpty() || stack.get(net.minecraft.component.DataComponentTypes.CONSUMABLE) == null) continue;
			var food = stack.get(net.minecraft.component.DataComponentTypes.FOOD);
			if (food != null) candidates.add(new FoodSelector.Candidate(
				net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString(), food.nutrition()));
		}
		return candidates;
	}

	private void releaseNormalActuatorsForReflex(MinecraftClient client) {
		worldTaskExecutor.onWorldLeave();
		behaviorTreeRuntime.stop(client);
		followCapability.clear();
		followState = FollowState.idle();
		completePendingCraftToolResult("Tool result for craft_recipe: cancelled reason=survival_reflex");
		cancelPolicy("survival_reflex");
		cancelPendingBlockModificationToolResult(PendingBlockModificationStopReason.SURVIVAL_REFLEX);
		playerItemUseController.reset(client);
	}

	private void pauseNormalWorkForReflex(MinecraftClient client) {
		TaskSnapshot previousTask = taskSnapshot;
		TaskExecutionSnapshot previousExecution = taskExecutionSnapshot;
		activeJobRuntime.pauseForReflex(tickCount);
		actionGraphCoordinator.pauseForegroundForReflex(tickCount);
		taskSnapshot = activeJobRuntime.taskSnapshot();
		// The job stopped sampling when interrupted; retain this tick's actual world observation.
		missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot().withEvidence(missionExecutionSnapshot.evidence());
		Optional<WorldTaskRequest> activeRequest = activeJobRuntime.activeTaskRequest();
		String taskId = activeRequest.map(WorldTaskRequest::taskId).orElse(previousExecution.taskId());
		GoalSnapshot goal = activeRequest.map(WorldTaskRequest::goal).orElse(previousExecution.activeGoal());
		taskExecutionSnapshot = new TaskExecutionSnapshot(
			TaskExecutionState.PAUSED_BY_REFLEX,
			taskId,
			goal,
			previousExecution.processName(),
			"reflex",
			previousExecution.estimatedTicksToGoal(),
			previousExecution.terminationCause()
		);
		recordSemanticTaskTransition(previousTask, taskSnapshot);
		recordTaskStateTransition(previousExecution, taskExecutionSnapshot, hasSemanticTaskContext(previousTask, taskSnapshot));
		behaviorTreeRuntime.reflectSurvivalReflex(client, survivalReflexRuntime.snapshot());
	}

	private void processSurvivalReflexEvents() {
		List<SurvivalReflexEvent> events = survivalReflexRuntime.drainEvents();
		for (SurvivalReflexEvent event : events) {
			var observed = eventBuffer.append(tickCount, event.type(), event.payload());
			if (List.of("reflex.started", "reflex.resolved", "reflex.threat_detected", "reflex.actuator_failed").contains(event.type()))
				dialogueRuntime.queueTaskWakeup(null, tickCount, observed.seqNo());
			if (event.type().equals("reflex.food_retreat_failed") || event.type().equals("reflex.food_unavailable"))
				dialogueRuntime.queueTaskAttention(tickCount, observed.seqNo());
		}
		SurvivalReflexSnapshot reflex = survivalReflexRuntime.snapshot();
		dialogueRuntime.updateSafetyContext(
			reflex.safetyEpoch(),
			reflex.holdId(),
			reflex.state() == SurvivalReflexState.ACTIVE
		);
	}

	private void recordStalePlannerRejections() {
		dialogueRuntime.drainStalePlannerRejections().forEach(rejection -> eventBuffer.append(
			tickCount,
			"planner.stale_response_rejected",
			mapOfNullable(
				"generation", rejection.generation(),
				"requestSafetyEpoch", rejection.requestSafetyEpoch(),
				"currentSafetyEpoch", rejection.currentSafetyEpoch(),
				"requestHoldId", rejection.requestHoldId(),
				"currentHoldId", rejection.currentHoldId(),
				"phase", rejection.phase()
			)
		));
	}

	private void enforcePlayerLifecycle(MinecraftClient client) {
		if (!sessionSnapshot.requiresRespawn()) {
			deathBoundaryApplied = false;
			lastRespawnRequestTick = -1L;
			return;
		}

		if (!deathBoundaryApplied) {
			if (client != null && client.player != null && client.world != null) {
				var pos = client.player.getBlockPos();
				try {
					var place = new ai.moeru.airicraft.agent.memory.PlaceMemory.Place("last_death",
						client.world.getRegistryKey().getValue().toString(), pos.getX(), pos.getY(), pos.getZ(),
						"Automatically recorded at the most recent death. Dropped items may have moved or despawned; this location is not necessarily safe.");
					ai.moeru.airicraft.agent.memory.LocationMemoryBridge.forClient(client).remember(null, place);
					ai.moeru.airicraft.agent.memory.WorldPlacePreservation.reload(client);
					eventBuffer.append(tickCount, "player.death_place_saved", Map.of(
						"name", place.name(), "dimension", place.dimension(), "x", place.x(), "y", place.y(), "z", place.z()));
				}
				catch (java.io.IOException | IllegalArgumentException | IllegalStateException exception) {
					eventBuffer.append(tickCount, "player.death_place_save_failed", Map.of("message", exception.toString()));
				}
			}
			cancelActionsForPlayerDeath(client);
			deathBoundaryApplied = true;
		}

		if (
			client == null
				|| client.player == null
				|| (lastRespawnRequestTick >= 0L && tickCount - lastRespawnRequestTick < RESPAWN_RETRY_TICKS)
		) {
			return;
		}

		lastRespawnRequestTick = tickCount;
		try {
			client.player.requestRespawn();
			eventBuffer.append(tickCount, "player.respawn_requested", Map.of(
				"attemptTick", tickCount
			));
		}
		catch (RuntimeException exception) {
			eventBuffer.append(tickCount, "player.respawn_request_failed", Map.of(
				"attemptTick", tickCount,
				"message", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()
			));
		}
	}

	private void cancelActionsForPlayerDeath(MinecraftClient client) {
		ActionGraphExecutionSnapshot graphSnapshot = actionGraphExecutionSnapshot();
		ActiveJob activeJob = activeJobRuntime.current();
		boolean graphCancelled = actionGraphCoordinator.hasNonterminal();
		boolean jobCancelled = !activeJob.isIdle() && !activeJob.status().terminal();

		survivalReflexRuntime.reset(client);
		worldTaskExecutor.onWorldLeave();
		behaviorTreeRuntime.stop(client);
		followCapability.clear();
		followState = FollowState.idle();
		if (graphCancelled) {
			actionGraphCoordinator.cancelAll("player_died", tickCount);
		}
		pendingActionGraphTerminalEvent = null;
		if (jobCancelled) {
			TaskSnapshot previousTaskSnapshot = taskSnapshot;
			activeJobRuntime.cancel("player_died", tickCount);
			taskSnapshot = activeJobRuntime.taskSnapshot();
			missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
			debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
			recordSemanticTaskTransition(previousTaskSnapshot, taskSnapshot);
		}
		dialogueRuntime.clear();
		dialogueRuntime.updateSafetyContext(survivalReflexRuntime.snapshot().safetyEpoch(), null, false);
		chatService.clear();
		taskExecutionSnapshot = TaskExecutionSnapshot.idle();
		completePendingCraftToolResult("Tool result for craft_recipe: cancelled reason=player_died");
		cancelPolicy("player_died");
		cancelPendingBlockModificationToolResult(PendingBlockModificationStopReason.PLAYER_DIED);
		idleIdeaScheduler.reset();

		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("reason", "player_died");
		payload.put("actionGraphCancelled", graphCancelled);
		payload.put("jobCancelled", jobCancelled);
		if (graphCancelled && graphSnapshot.executionId() != null && !graphSnapshot.executionId().isBlank()) {
			payload.put("executionId", graphSnapshot.executionId());
		}
		if (jobCancelled) {
			payload.put("jobId", activeJob.jobId());
			payload.put("jobType", activeJob.type().name());
		}
		eventBuffer.append(tickCount, "player.actions_cancelled", payload);
	}

	private void openLanIfSingleplayerLocal(MinecraftClient client) {
		if (!autoLanOpenState.shouldAttempt(sessionSnapshot)) {
			return;
		}

		try {
			lanHostingService.openLan(sessionSnapshot);
			if (sessionSnapshotOverrideForTests == null) {
				sessionSnapshot = sessionRuntime.poll(client, tickCount, eventBuffer);
			}
		}
		catch (LanHostingService.LanHostingException exception) {
			if ("minecraft_unavailable".equals(exception.code())) {
				return;
			}
			autoLanOpenState.recordFailure();
			eventBuffer.append(tickCount, "session.lan_open_failed", Map.of(
				"errorCode", exception.code(),
				"message", exception.getMessage()
			));
		}
	}

	public void shutdown() {
		initialized = false;
		tickCount = 0L;
		worldLoadTick = -1L;
		sessionSnapshotOverrideForTests = null;
		blockAcquisitionsOverrideForTests = null;
		autoLanOpenState.clear();
		localDamageTracker.clear();
		physicalObserver().reset();
		if (slowMiningObserver != null) slowMiningObserver.reset();
		physicalObservationWorld = null;
		nearbyPlayerTracker.clear(tickCount, eventBuffer);
		eventPipeline.clearForShutdown();
		primaryInteractionResolver.clear();
		completePendingCraftToolResult("Tool result for craft_recipe: cancelled reason=runtime_shutdown");
		cancelPolicy("runtime_shutdown");
		cancelPendingBlockModificationToolResult(PendingBlockModificationStopReason.RUNTIME_SHUTDOWN);
		dialogueRuntime.shutdown();
		observability.shutdown();
		visionService.shutdown();
		worldTaskExecutor.shutdown();
		blockAcquisitionKnowledgeService.shutdown();
		clearNearbyBlockSnapshot();
		surfaceMemory.clear();
		actionGraphCoordinator.cancelAll("runtime_shutdown", tickCount);
		actionGraphCoordinator.shutdown();
		pendingActionGraphTerminalEvent = null;
		activeJobRuntime.clear();
		followCapability.clear();
		followState = FollowState.idle();
		taskSnapshot = TaskSnapshot.idle();
		taskExecutionSnapshot = TaskExecutionSnapshot.idle();
		missionExecutionSnapshot = MissionExecutionSnapshot.idle();
		behaviorTreeRuntime.stop(MinecraftClient.getInstance());
		chatService.clear();
		proactiveSocialModeOverride = null;
		evaluationPlannerSuppressed = false;
		lastSystemChatTick = -1L;
		lastSystemChatText = null;
		lastKnownPlayerHealth = null;
		deathBoundaryApplied = false;
		lastRespawnRequestTick = -1L;
		survivalReflexRuntime.reset(MinecraftClient.getInstance());
		playerItemUseController.reset(MinecraftClient.getInstance());
		foodRuntime.reset();
		lightingRuntime.reset();
		seenPlayerNames.clear();
		sessionSnapshot = SessionSnapshot.initial();
	}

	public SessionSnapshot sessionSnapshot() {
		return sessionSnapshot.withTickCount(tickCount);
	}

	public long tickCount() {
		return tickCount;
	}

	public AgentRuntimeSnapshot snapshot() {
		return new AgentRuntimeSnapshot(
			initialized,
			tickCount,
			sessionSnapshot(),
			taskSnapshot,
			taskExecutionSnapshot,
			missionExecutionSnapshot,
			survivalReflexRuntime.snapshot()
		);
	}

	public SurvivalReflexSnapshot survivalReflexSnapshot() {
		return survivalReflexRuntime.snapshot();
	}

	public Map<String, Object> survivalReflexDecisionEvidence() {
		return survivalReflexRuntime.decisionEvidence();
	}

	public SurvivalReflexSnapshot resumeSafetyHold(String holdId, String source) {
		SurvivalReflexRuntime.ResumeResult result = survivalReflexRuntime.resume(holdId, tickCount);
		switch (result) {
			case REFLEX_ACTIVE -> throw new BridgeUnavailableException("reflex_active", "The survival reflex is still active");
			case NO_SAFETY_HOLD -> throw new BridgeUnavailableException("no_safety_hold", "There is no resolved survival hold to resume");
			case STALE_SAFETY_HOLD -> throw new BridgeUnavailableException("stale_safety_hold", "The supplied hold id does not match the current survival hold");
			case RESUMED -> {
				activeJobRuntime.resumeAfterReflex(tickCount);
				taskSnapshot = activeJobRuntime.taskSnapshot();
				missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
				processSurvivalReflexEvents();
				eventBuffer.append(tickCount, "reflex.task_resumed", Map.of(
					"source", source == null || source.isBlank() ? "unknown" : source,
					"safetyEpoch", survivalReflexRuntime.snapshot().safetyEpoch()
				));
				drainEventPipeline();
			}
		}
		return survivalReflexRuntime.snapshot();
	}

	private void releaseSafetyHoldForReplacement(String reason) {
		releaseSafetyHold(reason, true);
	}

	private void releaseSafetyHoldForActionGraphStart(String reason) {
		releaseSafetyHold(reason, false);
	}

	private void releaseSafetyHold(String reason, boolean cancelActionGraphs) {
		if (survivalReflexRuntime.snapshot().state() != SurvivalReflexState.AWAITING_PLANNER) {
			return;
		}
		TaskSnapshot previousTask = taskSnapshot;
		TaskExecutionSnapshot previousExecution = taskExecutionSnapshot;
		if (cancelActionGraphs && actionGraphCoordinator.hasNonterminal()) {
			actionGraphCoordinator.cancelAll(reason, tickCount);
			pendingActionGraphTerminalEvent = null;
		}
		ActiveJob interruptedJob = activeJobRuntime.current();
		if (interruptedJob != null
			&& interruptedJob.status() == ActiveJobStatus.BLOCKED
			&& "reflex".equals(interruptedJob.blockedReason())) {
			activeJobRuntime.cancel(reason, tickCount);
			taskSnapshot = activeJobRuntime.taskSnapshot();
			missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
			taskExecutionSnapshot = TaskExecutionSnapshot.idle();
			recordSemanticTaskTransition(previousTask, taskSnapshot);
			recordTaskStateTransition(previousExecution, taskExecutionSnapshot, hasSemanticTaskContext(previousTask, taskSnapshot));
		}
		survivalReflexRuntime.releaseHold(reason, tickCount);
		processSurvivalReflexEvents();
	}

	public Optional<GoalSnapshot> activeGoal() {
		return activeJobRuntime.activeGoal(tickCount);
	}

	public BehaviorTreeSnapshot behaviorTreeSnapshot() {
		return behaviorTreeRuntime.snapshot();
	}

	public ActiveJob activeJob() {
		return activeJobRuntime.current();
	}

	public TaskExecutionSnapshot taskExecutionSnapshot() {
		return taskExecutionSnapshot;
	}

	public TaskSnapshot taskSnapshot() {
		return taskSnapshot;
	}

	public MissionExecutionSnapshot missionExecutionSnapshot() {
		return missionExecutionSnapshot;
	}

	public ActionGraphExecutionSnapshot startActionGoal(ActionGoal goal, String source) {
		ActionGraphStartResult result = startActionGoalDetailed(goal, source);
		return result.execution() == null ? ActionGraphExecutionSnapshot.idle() : result.execution().execution();
	}

	public ActionGraphStartResult startActionGoalDetailed(ActionGoal goal, String source) {
		Objects.requireNonNull(goal, "goal");
		requireLivingPlayerForAction();
		WorldEvidence evidence = currentWorldEvidence(MinecraftClient.getInstance());
		ActionGraphStartResult result = actionGraphCoordinator.submit(
			goal,
			evidence.itemCounts(),
			actionResolverContext(evidence),
			tickCount
		);
		if (result.admission() == ActionGraphAdmission.STARTED) {
			releaseSafetyHoldForActionGraphStart("action_graph_started");
		}
		if (actionGraphCoordinator.hasNonterminal()) {
			dialogueRuntime.invalidateIdleThinkTriggers();
		}
		Map<String, Object> payload = new LinkedHashMap<>(result.toPayload(false));
		payload.put("requestedGoal", goal.normalizedKey());
		payload.put("source", source == null || source.isBlank() ? "bridge_debug" : source);
		eventBuffer.append(tickCount, "action_graph.goal_admission", payload);
		drainActionGraphCoordinatorEvents();
		return result;
	}

	public ActionGraphExecutionSnapshot actionGraphExecutionSnapshot() {
		ActionGraphExecutionView selected = actionGraphCoordinator.inspect(null);
		return selected == null ? ActionGraphExecutionSnapshot.idle() : selected.execution();
	}

	public List<ActionGraphExecutionView> actionGraphExecutions() {
		return actionGraphCoordinator.list();
	}

	public ActionGraphExecutionView actionGraphExecution(String executionId) {
		return actionGraphCoordinator.inspect(executionId);
	}

	public Map<String, Object> actionGraphGoalsPayload(boolean verbose) {
		List<ActionGraphExecutionView> executions = actionGraphCoordinator.list();
		List<ActionGraphWatchSnapshot> watches = actionGraphCoordinator.pendingWatches();
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("foregroundExecutionId", actionGraphCoordinator.foregroundExecutionId());
		payload.put("executionCount", executions.size());
		payload.put("nonterminalCount", actionGraphCoordinator.nonterminalExecutions().size());
		payload.put("suspendedCount", executions.stream().filter(view -> view.residency().name().equals("SUSPENDED")).count());
		payload.put("runnableCount", executions.stream().filter(view -> view.residency().name().equals("RUNNABLE")).count());
		payload.put("executions", executions.stream().map(view -> view.toPayload(verbose)).toList());
		payload.put("watchCount", watches.size());
		payload.put("watches", watches.stream().map(EmbodiedAgentRuntime::actionGraphWatchPayload).toList());
		return payload;
	}

	private static Map<String, Object> actionGraphWatchPayload(ActionGraphWatchSnapshot watch) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("executionId", watch.executionId());
		payload.put("watchId", watch.watchId());
		payload.put("stepId", watch.stepId());
		payload.put("consumedEligibleTicks", watch.consumedEligibleTicks());
		payload.put("progressEligible", watch.progressEligible());
		payload.put("pauseReason", watch.pauseReason());
		if (watch.spec() != null) {
			payload.put("timeoutTicks", watch.spec().timeoutTicks());
			payload.put("progressKind", watch.spec().progressKind().name());
			payload.put("condition", Map.of(
				"fact", watch.spec().condition().factType().id(),
				"keys", watch.spec().condition().queryKeys(),
				"minimums", watch.spec().condition().minimums()
			));
			if (watch.spec().sourceFactIdentity() != null) {
				payload.put("sourceFactIdentity", Map.of(
					"fact", watch.spec().sourceFactIdentity().type().id(),
					"keys", watch.spec().sourceFactIdentity().keys()
				));
			}
			if (watch.spec().anchor() != null) {
				ActionWatchAnchor anchor = watch.spec().anchor();
				payload.put("anchor", Map.of(
					"worldId", anchor.worldId(),
					"dimension", anchor.dimension(),
					"x", anchor.x(),
					"y", anchor.y(),
					"z", anchor.z(),
					"fallback", anchor.fallback()
				));
			}
		}
		return payload;
	}

	public ActionGraphExecutionSnapshot cancelActionGoal(String reason) {
		ActionGraphExecutionSnapshot previous = actionGraphExecutionSnapshot();
		ActionGraphExecutionView cancelled = actionGraphCoordinator.cancelSelected(reason, tickCount);
		return finishActionGraphCancellation(previous, cancelled, reason);
	}

	public ActionGraphExecutionSnapshot cancelActionGoal(String executionId, String reason) {
		ActionGraphExecutionView previousView = actionGraphCoordinator.inspect(executionId);
		ActionGraphExecutionSnapshot previous = previousView == null ? ActionGraphExecutionSnapshot.idle() : previousView.execution();
		ActionGraphExecutionView cancelled = actionGraphCoordinator.cancel(executionId, reason, tickCount);
		return finishActionGraphCancellation(previous, cancelled, reason);
	}

	private ActionGraphExecutionSnapshot finishActionGraphCancellation(
		ActionGraphExecutionSnapshot previous,
		ActionGraphExecutionView cancelled,
		String reason
	) {
		ActionGraphExecutionSnapshot snapshot = cancelled == null ? previous : cancelled.execution();
		if (pendingActionGraphTerminalEvent != null
			&& !previous.activeTaskId().isBlank()
			&& Objects.equals(previous.activeTaskId(), pendingActionGraphTerminalEvent.taskId())) {
			pendingActionGraphTerminalEvent = null;
		}
		if (!previous.activeTaskId().isBlank() && (previous.activeTaskId().startsWith(activeJobRuntime.current().jobId())
			|| taskExecutionSnapshot != null && previous.activeTaskId().equals(taskExecutionSnapshot.taskId()))) {
			cancelActiveJobOnly(reason == null || reason.isBlank() ? "action_graph_cancelled" : reason);
		}
		else if (Objects.equals(
			previous.executionId(),
			survivalReflexRuntime.snapshot().interruptedActionExecutionId()
		)) {
			survivalReflexRuntime.discardHold("action_graph_cancelled", tickCount);
			processSurvivalReflexEvents();
		}
		drainActionGraphCoordinatorEvents();
		return snapshot;
	}

	public Optional<DialogueResponse> lastDialogueResponse() {
		return dialogueRuntime.lastResponse();
	}

	public DialogueSnapshot dialogueSnapshot() {
		return dialogueRuntime.snapshot();
	}

	public boolean llmAvailable() {
		return dialogueRuntime.llmAvailable();
	}

	public boolean codexDriverActive() {
		return codexDriverActive;
	}

	public List<Map<String, Object>> codexDriverTools() {
		requireCodexDriverActive();
		return dialogueRuntime.allAvailableTools();
	}

	public CompletableFuture<ExternalPlannerToolResult> executeCodexDriverTool(String name, JsonObject arguments) {
		requireCodexDriverActive();
		String callId = UUID.randomUUID().toString();
		debugRecorder.recordExternalTool(tickCount, callId, name, "requested", arguments == null ? Map.of() : arguments.deepCopy());
		try {
			return dialogueRuntime.executeExternalTool(name, arguments).whenComplete((result, error) ->
				debugRecorder.recordExternalTool(tickCount, callId, name, error == null ? "completed" : "failed",
					error == null ? result.text() : String.valueOf(error.getMessage())));
		}
		catch (com.google.gson.JsonParseException | IllegalArgumentException exception) {
			debugRecorder.recordExternalTool(tickCount, callId, name, "rejected", String.valueOf(exception.getMessage()));
			throw new BridgeUnavailableException(
				"invalid_request",
				exception.getMessage() == null || exception.getMessage().isBlank() ? "Invalid tool arguments" : exception.getMessage()
			);
		}
		catch (RuntimeException exception) {
			debugRecorder.recordExternalTool(tickCount, callId, name, "failed", String.valueOf(exception.getMessage()));
			throw exception;
		}
	}

	private void requireCodexDriverActive() {
		if (!codexDriverActive) {
			throw new BridgeUnavailableException(
				"codex_driver_inactive",
				"Codex driver tools require launching Airicraft with scripts/codex-driver"
			);
		}
	}

	public boolean visionAvailable() {
		return visionService.isConfigured();
	}

	public boolean isDegraded() {
		return dialogueRuntime.isDegraded();
	}

	public boolean plannerEnabled() {
		return dialogueRuntime.plannerEnabled();
	}

	public void setPlannerEnabled(boolean enabled) {
		eventPipeline.setPlannerEnabled(enabled);
		dialogueRuntime.setPlannerEnabled(enabled);
	}

	public PlannerOrchestratorDebugSnapshot plannerDebugSnapshot() {
		return dialogueRuntime.plannerDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerConversationDebugSnapshot() {
		return dialogueRuntime.plannerConversationDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerProjectedConversationDebugSnapshot() {
		return dialogueRuntime.plannerProjectedConversationDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerChronicleConversationDebugSnapshot() {
		return plannerChronicleConversationDebugSnapshot(true);
	}

	public PlannerConversationDebugSnapshot plannerChronicleConversationDebugSnapshot(boolean verbose) {
		return dialogueRuntime.plannerChronicleConversationDebugSnapshot(verbose);
	}

	public PlannerConversationDebugSnapshot plannerContextConversationDebugSnapshot() {
		return dialogueRuntime.plannerContextConversationDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerCanonicalConversationDebugSnapshot() {
		return dialogueRuntime.plannerCanonicalConversationDebugSnapshot();
	}

	public DialogueDebugSnapshot debugDialogueState() {
		return debugRecorder.dialogueSnapshot();
	}

	public ChatDebugSnapshot debugChatState() {
		return debugRecorder.chatSnapshot();
	}

	public CollectResourceTaskDebugSnapshot debugCollectResourceState() {
		return debugRecorder.collectResourceSnapshot();
	}

	public EventPipelineDebugSnapshot debugEventPipelineState() {
		return debugRecorder.eventPipelineSnapshot();
	}

	public Map<String, Object> debugSystem2() { return dialogueRuntime.system2Snapshot(); }

	public ConversationSourcesDebugSnapshot debugConversationSources() {
		return debugRecorder.conversationSourcesSnapshot();
	}

	public List<PlannerAttemptDebugSnapshot> debugPlannerAttempts() {
		return debugRecorder.plannerAttempts();
	}

	public AgentDebugTimelineQueryResult debugTimeline(Long sinceEntryId) {
		return debugRecorder.queryTimeline(sinceEntryId);
	}

	public LlmFlightRecordQueryResult llmFlightRecords(Long sinceSequenceId) {
		return llmFlightRecorder.query(sinceSequenceId);
	}

	public WorldEvidence currentWorldEvidence() {
		return currentWorldEvidence(MinecraftClient.getInstance());
	}

	public int inventoryItemCount(String itemId) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null || itemId == null || itemId.isBlank()) {
			return 0;
		}
		return inventoryItemCounter.count(client.player.getInventory()).getOrDefault(itemId, 0);
	}

	public String blockIdAt(int x, int y, int z) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null) {
			return null;
		}
		return Registries.BLOCK.getId(client.world.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
	}

	public Map<String, String> blockPropertiesAt(int x, int y, int z) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null) {
			return Map.of();
		}
		BlockState state = client.world.getBlockState(new BlockPos(x, y, z));
		Map<String, String> properties = new LinkedHashMap<>();
		for (Property<?> property : state.getProperties()) {
			properties.put(property.getName(), propertyValue(state, property));
		}
		return Map.copyOf(properties);
	}

	private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
		return property.name(state.get(property));
	}

	public boolean semanticEventContains(String eventType) {
		return eventType != null && eventBuffer.containsType(eventType);
	}

	public void prepareForEvaluation() {
		dialogueRuntime.clear();
		eventPipeline.clearPlannerFeed();
		plannerCallJournal.clear();
		proactiveSocialModeOverride = null;
		// World loading may emit system/idle triggers before the evaluator seeds its goal.
		evaluationPlannerSuppressed = true;
		clearNearbyBlockSnapshot();
		prepareClientForEvaluation();
	}

	public void finishEvaluation() {
		evaluationPlannerSuppressed = true;
		eventPipeline.clearPlannerFeed();
		completePendingCraftToolResult("Tool result for craft_recipe: cancelled reason=evaluation_finished");
		cancelPolicy("evaluation_finished");
		cancelPendingBlockModificationToolResult(PendingBlockModificationStopReason.EVALUATION_FINISHED);
		dialogueRuntime.clear();
		actionGraphCoordinator.cancelAll("runtime_reset", tickCount);
		actionGraphCoordinator.clear();
		pendingActionGraphTerminalEvent = null;
		activeJobRuntime.clear();
		worldTaskExecutor.onWorldLeave();
		idleIdeaScheduler.reset();
		followCapability.clear();
		followState = FollowState.idle();
		taskSnapshot = TaskSnapshot.idle();
		taskExecutionSnapshot = TaskExecutionSnapshot.idle();
		missionExecutionSnapshot = MissionExecutionSnapshot.idle();
		behaviorTreeRuntime.stop(MinecraftClient.getInstance());
	}

	public void startEvaluationGoal(String objective) throws java.io.IOException {
		dialogueRuntime.startEvaluationGoal(objective);
		evaluationPlannerSuppressed = false;
	}

	public Optional<ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore.Goal> plannerGoalSnapshot() {
		return dialogueRuntime.plannerGoalSnapshot();
	}

	public long plannerGameplayDecisionCount() { return dialogueRuntime.gameplayDecisionCount(); }

	public void emitEvaluationChat(String message) {
		emitEvaluationTrigger(PlannerTriggerType.CHAT, "evaluation", message);
	}

	public void emitEvaluationSystem(String message) {
		emitEvaluationTrigger(PlannerTriggerType.SYSTEM, "evaluation", message);
	}

	public List<String> plannerContextExcerpt() {
		return dialogueRuntime.plannerContextExcerpt();
	}

	public List<PlannerShellEvent> plannerShellJournal() {
		return plannerJournal.snapshot();
	}

	public List<PlannerCallRecordV1> plannerCallRecords() {
		return plannerCallJournal.snapshot();
	}

	public void finalizePlannerCallRecordsForEvaluation() {
		plannerCallJournal.finalizeForEvaluation();
	}

	private static long integratedServerTick() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client == null || client.getServer() == null ? -1L : client.getServer().getTicks();
	}

	public int activeEventPolicyRuleCount() {
		return eventPolicyState.activeRuleCount();
	}

	public int recentEventPolicyInterventionCount() {
		return eventPolicyState.recentInterventionCount();
	}

	public EventPolicyDecision lastEventPolicyDecision() {
		return eventPolicyState.lastDecision().orElse(null);
	}

	public List<EventPolicyRule> activeEventPolicyRules() {
		return eventPolicyState.activeRules();
	}

	public List<EventPolicyIntervention> recentEventPolicyInterventions() {
		return eventPolicyState.recentInterventions();
	}

	public void clearEventPolicy() {
		eventPolicyState.clear();
	}

	public long latestEventSeqNo() {
		return eventBuffer.latestSeqNo();
	}

	public boolean startDebugCompaction() {
		return dialogueRuntime.startDebugCompaction();
	}

	public CompactionExecutionResult pollDebugCompaction() {
		return dialogueRuntime.pollDebugCompaction();
	}

	public Optional<PlannerTrigger> fireIdleIdeaTriggerManually() {
		if (!config.llm().isConfigured()) {
			throw new BridgeUnavailableException("planner_unavailable", "Planner LLM is not configured");
		}
		if (!sessionSnapshot.worldLoaded()) {
			throw new BridgeUnavailableException("world_not_loaded", "No Minecraft world is currently loaded");
		}
		if (!sessionSnapshot.companionActuationAllowed()) {
			throw new BridgeUnavailableException(
				"companion_actuation_unavailable",
				"Idle triggers require a LAN-hosted singleplayer or remote multiplayer session"
			);
		}
		Optional<GoalSnapshot> activeGoal = activeGoal();
		Optional<PlannerTrigger> trigger = idleIdeaScheduler.fireNow(tickCount, System.currentTimeMillis());
		trigger.ifPresent(plannerTrigger -> {
			String primaryInteractionPlayer = primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null);
			dialogueRuntime.onPlannerTrigger(
				plannerTrigger,
				sessionSnapshot,
				primaryInteractionPlayer,
				activeGoal,
				taskSnapshot,
				missionExecutionSnapshot,
				plannerEventBuffer
			);
		});
		return trigger;
	}

	public long lastChatTick() {
		return chatService.lastChatTick();
	}

	public String lastChatText() {
		return chatService.lastChatText();
	}

	public void onChatReceived(String senderName, String plainTextMessage) {
		if (isAgentChatEcho(
			senderName,
			plainTextMessage,
			localPlayerName(),
			tickCount,
			chatService
		)) {
			return;
		}
		if (isLocalControllerMessage(senderName, localPlayerName())) {
			eventBuffer.append(tickCount, "social.local_controller_spoke", Map.of(
				"player", senderName,
				"message", plainTextMessage,
				"normalizedMessage", ChatIngestService.normalize(plainTextMessage)
			));

			String plannerSender = DialogueSpeakerLabels.SAME_CLIENT_ADMIN;
			if (dialogueRuntime.handleResetCommand(plannerSender, plainTextMessage, tickCount, eventBuffer)) {
				completePendingCraftToolResult("Tool result for craft_recipe: cancelled reason=planner_reset");
				cancelPolicy("planner_reset");
				cancelPendingBlockModificationToolResult(PendingBlockModificationStopReason.PLANNER_RESET);
				eventPolicyState.clear();
				drainEventPipeline();
				return;
			}
			drainEventPipeline();
			return;
		}

		chatIngestService.ingest(
			senderName,
			plainTextMessage,
			tickCount,
			nearbyPlayerTracker,
			primaryInteractionResolver,
			eventBuffer
		);

		if (dialogueRuntime.handleResetCommand(senderName, plainTextMessage, tickCount, eventBuffer)) {
			completePendingCraftToolResult("Tool result for craft_recipe: cancelled reason=planner_reset");
			cancelPolicy("planner_reset");
			cancelPendingBlockModificationToolResult(PendingBlockModificationStopReason.PLANNER_RESET);
			eventPolicyState.clear();
			drainEventPipeline();
			return;
		}
		drainEventPipeline();
	}

	public void onSystemChatReceived(String plainTextMessage) {
		if (!airicraftConfig.readSystemChatMessages()) {
			return;
		}
		if (isDuplicateSystemChat(plainTextMessage, tickCount)) {
			return;
		}

		chatIngestService.ingestSystemMessage(plainTextMessage, tickCount, eventBuffer);
		drainEventPipeline();
	}

	public void onPlayerCraftedItem(String itemId, int count) {
		if (itemId == null || itemId.isBlank() || count <= 0) {
			return;
		}

		eventBuffer.append(tickCount, "crafting.item_crafted", Map.of(
			"actor", "self",
			"itemId", itemId,
			"count", count
		));
		drainEventPipeline();
	}

	public void onPlayerPickedUpItem(String itemId, int count) {
		if (itemId == null || itemId.isBlank() || count <= 0) {
			return;
		}

		eventBuffer.append(tickCount, "pickup.item_picked_up", Map.of(
			"actor", "self",
			"itemId", itemId,
			"count", count
		));
		drainEventPipeline();
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
		worldTaskExecutor.onPlayerItemPickupObserved(
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
		activeJobRuntime.recordMinedBlock(blockId, new GoalPosition(x, y, z, true), tickCount).ifPresent(event -> handleTerminalTaskEvent(event, false, Optional.empty()));
	}

	public void onPlayerDamageObserved(DamageSource damageSource) {
		localDamageTracker.observeDamageSource(tickCount, damageSource);
	}

	public void onPlayerHealthUpdated(boolean healthInitialized, float healthBefore, float healthAfter) {
		float effectiveHealthBefore = resolveEffectiveHealthBefore(healthBefore, healthAfter);
		Map<String, Object> payload = localDamageTracker.consumeDamage(healthInitialized, tickCount, effectiveHealthBefore, healthAfter);
		lastKnownPlayerHealth = healthAfter;
		if (payload != null) {
			var damagePayload = new LinkedHashMap<String, Object>(payload);
			damagePayload.put("context", physicalTaskContext());
			var client = MinecraftClient.getInstance();
			var player = client == null ? null : client.player;
			if (player != null) {
				damagePayload.put("position", Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ()));
			}
			eventBuffer.append(tickCount, "combat.damage_taken", damagePayload);
			survivalReflexRuntime.observeDamage(new SurvivalReflexRuntime.DamageObservation(
				tickCount,
				stringPayloadValue(payload, "damageTypeId"),
				stringPayloadValue(payload, "attackerUuid"),
				stringPayloadValue(payload, "attackerName"),
				stringPayloadValue(payload, "attackerEntityTypeId"),
				booleanPayloadValue(payload, "attackerLiving"),
				booleanPayloadValue(payload, "attackerPlayer")
			));
			tickSurvivalReflex(MinecraftClient.getInstance());
		}
		boolean fatal = Float.isFinite(healthAfter) && healthAfter <= 0.0F;
		if (fatal) {
			if (sessionSnapshotOverrideForTests != null) {
				sessionSnapshotOverrideForTests = sessionSnapshotOverrideForTests.withPlayerLifecycleState(PlayerLifecycleState.DEAD);
				sessionSnapshot = sessionSnapshotOverrideForTests.withTickCount(tickCount);
				eventBuffer.append(tickCount, "player.died", Map.of(
					"mode", sessionSnapshot.mode().name(),
					"dimensionId", sessionSnapshot.dimensionId()
				));
			}
			else {
				sessionSnapshot = sessionRuntime.onPlayerDied(tickCount, eventBuffer);
			}
			enforcePlayerLifecycle(MinecraftClient.getInstance());
		}
		if (payload == null && !fatal) {
			return;
		}
		drainEventPipeline();
	}

	public void onPlayerRespawned() {
		localDamageTracker.onLifecycleReset(tickCount);
		physicalObserver().reset();
		if (slowMiningObserver != null) slowMiningObserver.reset();
		lastKnownPlayerHealth = null;
		if (sessionSnapshotOverrideForTests != null && sessionSnapshot.requiresRespawn()) {
			sessionSnapshotOverrideForTests = sessionSnapshotOverrideForTests.withPlayerLifecycleState(PlayerLifecycleState.ALIVE);
			sessionSnapshot = sessionSnapshotOverrideForTests.withTickCount(tickCount);
			eventBuffer.append(tickCount, "player.respawned", Map.of(
				"mode", sessionSnapshot.mode().name(),
				"dimensionId", sessionSnapshot.dimensionId()
			));
		}
		else {
			sessionSnapshot = sessionRuntime.onPlayerRespawned(tickCount, eventBuffer);
		}
		deathBoundaryApplied = false;
		lastRespawnRequestTick = -1L;
		drainEventPipeline();
	}

	public void onPlayerJoinedGame(UUID playerUuid, String playerName) {
		if (playerUuid == null || playerName == null || playerName.isBlank()) {
			return;
		}
		if (isLocalPlayer(playerUuid, playerName)) {
			seenPlayerNames.put(playerUuid, playerName);
			return;
		}
		if (seenPlayerNames.putIfAbsent(playerUuid, playerName) != null) {
			return;
		}

		eventBuffer.append(tickCount, "social.player_joined_game", Map.of(
			"player", playerName
		));
		forwardSyntheticPresenceMessage(playerName + " joined the game");
		drainEventPipeline();
	}

	public void onPlayerLeftGame(UUID playerUuid) {
		if (playerUuid == null) {
			return;
		}

		String playerName = seenPlayerNames.remove(playerUuid);
		if (playerName == null || playerName.isBlank() || isLocalPlayer(playerUuid, playerName)) {
			return;
		}

		eventBuffer.append(tickCount, "social.player_left_game", Map.of(
			"player", playerName
		));
		forwardSyntheticPresenceMessage(playerName + " left the game");
		drainEventPipeline();
	}

	public SemanticEventQueryResult recentEvents(Long sinceSeqNo) {
		return eventBuffer.query(sinceSeqNo);
	}

	public Optional<PrimaryInteractionPlayer> primaryInteractionPlayer() {
		return primaryInteractionResolver.current();
	}

	public List<NearbyPlayerSnapshot> nearbyPlayers() {
		return nearbyPlayerTracker.snapshot();
	}

	public Map<String, Object> openLan() {
		return lanHostingService.openLan(sessionSnapshot);
	}

	public void injectMockPlannerResponse(PlannerResponse response) {
		dialogueRuntime.injectMockResponse(response);
	}

	public void injectPlannerTimeout() {
		dialogueRuntime.injectTimeout();
	}

	public TaskSnapshot submitTask(TaskSpec spec, String source) {
		Objects.requireNonNull(spec, "spec");
		requireLivingPlayerForAction();
		releaseSafetyHoldForReplacement("task_replaced");
		activeJobRuntime.submitTask(
			spec,
			currentTaskResourceCount(MinecraftClient.getInstance(), spec),
			source == null || source.isBlank() ? "bridge_debug" : source,
			tickCount
		);
		taskSnapshot = activeJobRuntime.taskSnapshot();
		missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
		eventBuffer.append(tickCount, "task.submitted", Map.of(
			"type", spec.type().name(),
			"resourceKind", spec.resourceKind().name(),
			"quantity", spec.quantity(),
			"source", taskSnapshot.source()
		));
		return taskSnapshot;
	}

	public TaskSnapshot submitMissionLedger(TaskLedger ledger, String source) {
		Objects.requireNonNull(ledger, "ledger");
		requireLivingPlayerForAction();
		releaseSafetyHoldForReplacement("mission_replaced");
		activeJobRuntime.submitMissionLedger(
			ledger,
			currentWorldEvidence(MinecraftClient.getInstance()).inventoryCounts().getOrDefault(TaskResourceKind.WOOD_LOGS, 0),
			source == null || source.isBlank() ? "bridge_debug_mission" : source,
			tickCount
		);
		taskSnapshot = activeJobRuntime.taskSnapshot();
		missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
		eventBuffer.append(tickCount, "mission.submitted", Map.of(
			"missionId", ledger.missionId(),
			"missionType", ledger.missionType().name(),
			"activeStepId", ledger.activeStepId() == null ? "" : ledger.activeStepId(),
			"source", taskSnapshot.source()
		));
		return taskSnapshot;
	}

	public TaskSnapshot submitAttackEntity(EntityInteractionStepArgs entityInteraction, String source) {
		return submitActiveJobProposal(ActiveJobProposal.attackEntity(entityInteraction), source, entityInteractionEventPayload(entityInteraction, "ATTACK_ENTITY", source));
	}

	public TaskSnapshot submitUseEntity(EntityInteractionStepArgs entityInteraction, String source) {
		return submitActiveJobProposal(ActiveJobProposal.useEntity(entityInteraction), source, entityInteractionEventPayload(entityInteraction, "USE_ENTITY", source));
	}

	public TaskSnapshot cancelTask(String reason) {
		cancelPolicy(reason == null ? "task_cancelled" : reason);
		if (actionGraphCoordinator.hasNonterminal()) {
			actionGraphCoordinator.cancelAll(reason == null || reason.isBlank() ? "cancelled" : reason, tickCount);
			pendingActionGraphTerminalEvent = null;
		}
		return cancelActiveJobOnly(reason == null || reason.isBlank() ? "cancelled" : reason);
	}

	private TaskSnapshot cancelActiveJobOnly(String reason) {
		survivalReflexRuntime.discardHold("task_cancelled", tickCount);
		processSurvivalReflexEvents();
		TaskSnapshot previousTaskSnapshot = taskSnapshot;
		activeJobRuntime.cancel(reason == null || reason.isBlank() ? "cancelled" : reason, tickCount);
		taskSnapshot = activeJobRuntime.taskSnapshot();
		missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
		recordSemanticTaskTransition(previousTaskSnapshot, taskSnapshot);
		return taskSnapshot;
	}

	private TaskSnapshot submitActiveJobProposal(ActiveJobProposal proposal, String source, Map<String, Object> submittedPayload) {
		Objects.requireNonNull(proposal, "proposal");
		requireLivingPlayerForAction();
		releaseSafetyHoldForReplacement("task_replaced");
		WorldEvidence worldEvidence = currentWorldEvidence(MinecraftClient.getInstance());
		int currentResourceCount = currentResourceCountForProposal(worldEvidence, proposal);
		activeJobRuntime.applyPlannerResponse(
			new DialogueResponse("", new DialogueIntent(DialogueIntentType.JOB_UPDATE, proposal), tickCount),
			currentResourceCount,
			source == null || source.isBlank() ? "bridge_debug" : source,
			tickCount
		);
		taskSnapshot = activeJobRuntime.taskSnapshot();
		missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
		eventBuffer.append(tickCount, "task.submitted", submittedPayload);
		return taskSnapshot;
	}

	private void tickActionGraph(WorldEvidence worldEvidence, boolean foregroundAllowed) {
		// Eating is admitted only between tasks. Keep the graph from dispatching
		// another primitive until consumption finishes; the furnace keeps ticking.
		if (playerItemUseController.eating()) return;
		if (!actionGraphCoordinator.hasNonterminal() && pendingActionGraphTerminalEvent == null) {
			return;
		}
		TaskTerminalEvent terminalEvent = pendingActionGraphTerminalEvent;
		if (foregroundAllowed) {
			pendingActionGraphTerminalEvent = null;
		}
		else {
			terminalEvent = null;
		}
		ActionResolverContext context = actionResolverContext(worldEvidence);
		MinecraftClient client = MinecraftClient.getInstance();
		ActionGraphAgentPosition agentPosition = client != null && client.player != null
			? new ActionGraphAgentPosition(context.worldId(), context.dimension(), client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ())
			: null;
		List<ActionGraphWatchSnapshot> pendingWatches = actionGraphCoordinator.pendingWatches();
		Map<String, ActionWatchProgressObservation> watchProgress = actionGraphWatchProgress(client, context, agentPosition, pendingWatches);
		ArrayList<ActionFact> observedFacts = new ArrayList<>(FarmBootstrapFactProvider.fromWorldEvidence(context, worldEvidence));
		observedFacts.addAll(observeSmeltingProcessFacts(context));
		boolean refreshPlanningObservations = actionGraphCoordinator.nonterminalExecutions().stream()
			.map(ActionGraphExecutionView::execution)
			.anyMatch(execution -> execution.state() == ai.moeru.airicraft.agent.actions.ActionGraphExecutionState.RESOLVING
				|| execution.state() == ai.moeru.airicraft.agent.actions.ActionGraphExecutionState.REPLANNING);
		List<ActionGraphWatchSnapshot> cropWatches = pendingWatches.stream()
			.filter(watch -> watch.spec() != null && watch.spec().condition().factType() == ActionFactType.WORLD_CROP_GROUP)
			.toList();
		if (refreshPlanningObservations) {
			ai.moeru.airicraft.agent.tasks.CraftingTaskExecutor.nearbyCraftingTablePosition(client).ifPresent(pos ->
				observedFacts.add(new ActionFact(
					ActionFactIdentity.worldSite(context.worldId(), context.dimension(), "crafting-table:" + pos.getX() + "," + pos.getY() + "," + pos.getZ()),
					Map.of("kind", "crafting_table", "availableToActor", context.actorId(), "x", pos.getX(), "y", pos.getY(), "z", pos.getZ()),
					ActionFactProvenance.OBSERVED, context.currentTick(), context.currentTick() + 1)));
		}
		if (refreshPlanningObservations || (!cropWatches.isEmpty() && tickCount % 10L == 0L)) {
			observedFacts.addAll(observeCropGroupFacts(client, context, cropWatches, refreshPlanningObservations));
		}
		actionGraphCoordinator.tick(new ActionGraphExecutionInput(
			context,
			worldEvidence.itemCounts(),
			resourceCountsForGraph(worldEvidence.inventoryCounts()),
			sessionSnapshot.worldLoaded(),
			sessionSnapshot.companionActuationAllowed(),
			terminalEvent,
			worldEvidence.availableCrafts(),
			worldEvidence.knownCrafts(),
			worldEvidence.availableSmelts(),
			worldEvidence.knownSmelts(),
			observedFacts,
			agentPosition,
			watchProgress,
			blockAcquisitions(),
			NearbyBlockAvailability.observed(nearbyHarvestableBlockSnapshot)
		), foregroundAllowed);
		drainActionGraphCoordinatorEvents();
	}

	private static List<ActionFact> observeCropGroupFacts(
		MinecraftClient client,
		ActionResolverContext context,
		List<ActionGraphWatchSnapshot> watches,
		boolean discoverNearby
	) {
		if (client == null || client.world == null || client.player == null || context == null) {
			return List.of();
		}
		BlockPos playerPos = client.player.getBlockPos();
		LinkedHashMap<Long, CropGroupObservation> groups = new LinkedHashMap<>();
		LinkedHashMap<Long, Integer> chunksToScan = new LinkedHashMap<>();
		if (discoverNearby) {
			int playerChunkX = playerPos.getX() >> 4;
			int playerChunkZ = playerPos.getZ() >> 4;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					chunksToScan.put(chunkKey(playerChunkX + dx, playerChunkZ + dz), playerPos.getY());
				}
			}
		}
		if (watches != null) {
			for (ActionGraphWatchSnapshot watch : watches) {
				ActionWatchAnchor anchor = watch.spec() == null ? null : watch.spec().anchor();
				if (anchor != null && Objects.equals(anchor.worldId(), context.worldId()) && Objects.equals(anchor.dimension(), context.dimension())) {
					chunksToScan.put(chunkKey(anchor.chunkX(), anchor.chunkZ()), anchor.y());
				}
			}
		}
		for (Map.Entry<Long, Integer> chunk : chunksToScan.entrySet()) {
			int chunkX = (int) (chunk.getKey() >> 32);
			int chunkZ = (int) (long) chunk.getKey();
			int baseY = chunk.getValue();
			if (!client.world.isChunkLoaded(chunkX, chunkZ)) {
				continue;
			}
			for (int localX = 0; localX < 16; localX++) {
				for (int dy = -6; dy <= 6; dy++) {
					for (int localZ = 0; localZ < 16; localZ++) {
						BlockPos pos = new BlockPos((chunkX << 4) + localX, baseY + dy, (chunkZ << 4) + localZ);
						BlockState state = client.world.getBlockState(pos);
						if (!"minecraft:wheat".equals(Registries.BLOCK.getId(state.getBlock()).toString())) {
							continue;
						}
						groups.computeIfAbsent(chunk.getKey(), ignored -> new CropGroupObservation(pos.toImmutable()))
							.observe(pos, cropAge(state) >= 7);
					}
				}
			}
		}
		ArrayList<ActionFact> facts = new ArrayList<>();
		for (CropGroupObservation group : groups.values()) {
			BlockPos origin = group.origin;
			String siteId = "crop-group:minecraft:wheat:" + (origin.getX() >> 4) + "," + (origin.getZ() >> 4);
			facts.add(new ActionFact(
				ActionFactIdentity.worldCropGroup(context.worldId(), context.dimension(), siteId, "minecraft:wheat"),
				Map.of(
					"matureCount", group.matureCount,
					"totalCount", group.totalCount,
					"origin", Map.of("x", origin.getX(), "y", origin.getY(), "z", origin.getZ())
				),
				ai.moeru.airicraft.agent.actions.ActionFactProvenance.OBSERVED,
				context.currentTick(),
				context.currentTick() + 20L
			));
		}
		return List.copyOf(facts);
	}

	private List<ActionFact> observeSmeltingProcessFacts(ActionResolverContext context) {
		ArrayList<ActionFact> facts = new ArrayList<>();
		for (SmeltingProcessSnapshot process : smeltingProcessManager.processSnapshots()) {
			if (process.stationKey() == null || process.outputItemId() == null || process.outputItemId().isBlank()) {
				continue;
			}
			facts.add(new ActionFact(
				ActionFactIdentity.smeltingProcess(
					context.worldId(),
					context.actorId(),
					process.processId(),
					process.optionId(),
					process.outputItemId()
				),
				Map.of(
					"ready", process.outputReady() ? 1 : 0,
					"expectedOutputCount", process.expectedOutputCount(),
					"origin", Map.of(
						"x", process.stationKey().x(),
						"y", process.stationKey().y(),
						"z", process.stationKey().z()
					)
				),
				ai.moeru.airicraft.agent.actions.ActionFactProvenance.OBSERVED,
				context.currentTick(),
				context.currentTick() + SMELTING_OUTPUT_READY_POLL_INTERVAL_TICKS + 1L
			));
		}
		return List.copyOf(facts);
	}

	private static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
	}

	private static int cropAge(BlockState state) {
		for (Property<?> property : state.getProperties()) {
			if (!"age".equals(property.getName())) {
				continue;
			}
			try {
				return Integer.parseInt(propertyValue(state, property));
			}
			catch (NumberFormatException ignored) {
				return 0;
			}
		}
		return 0;
	}

	private static final class CropGroupObservation {
		private BlockPos origin;
		private int matureCount;
		private int totalCount;

		private CropGroupObservation(BlockPos origin) {
			this.origin = origin;
		}

		private void observe(BlockPos pos, boolean mature) {
			totalCount++;
			if (mature) {
				matureCount++;
			}
			if (pos.getX() < origin.getX()
				|| (pos.getX() == origin.getX() && pos.getZ() < origin.getZ())
				|| (pos.getX() == origin.getX() && pos.getZ() == origin.getZ() && pos.getY() < origin.getY())) {
				origin = pos.toImmutable();
			}
		}
	}

	private Map<String, ActionWatchProgressObservation> actionGraphWatchProgress(
		MinecraftClient client,
		ActionResolverContext context,
		ActionGraphAgentPosition agentPosition,
		List<ActionGraphWatchSnapshot> pendingWatches
	) {
		LinkedHashMap<String, ActionWatchProgressObservation> progress = new LinkedHashMap<>();
		for (ActionGraphWatchSnapshot watch : pendingWatches) {
			if (watch.spec() == null || watch.spec().progressKind() != ActionWatchProgressKind.AREA_TICKING) {
				continue;
			}
			ActionWatchAnchor anchor = watch.spec().anchor();
			if (anchor == null) {
				progress.put(watch.watchId(), ActionWatchProgressObservation.paused("anchor_unavailable"));
				continue;
			}
			if (!Objects.equals(anchor.worldId(), context.worldId()) || !Objects.equals(anchor.dimension(), context.dimension())) {
				progress.put(watch.watchId(), ActionWatchProgressObservation.paused("world_or_dimension_mismatch"));
				continue;
			}
			if (client == null || client.world == null || agentPosition == null) {
				progress.put(watch.watchId(), ActionWatchProgressObservation.paused("world_unavailable"));
				continue;
			}
			if (!client.world.isChunkLoaded(anchor.chunkX(), anchor.chunkZ())) {
				progress.put(watch.watchId(), ActionWatchProgressObservation.paused("anchor_chunk_unloaded"));
				continue;
			}
			int agentChunkX = agentPosition.x() >> 4;
			int agentChunkZ = agentPosition.z() >> 4;
			int chunkDistance = Math.max(Math.abs(anchor.chunkX() - agentChunkX), Math.abs(anchor.chunkZ() - agentChunkZ));
			if (chunkDistance > client.world.getSimulationDistance()) {
				progress.put(watch.watchId(), ActionWatchProgressObservation.paused("outside_simulation_distance"));
				continue;
			}
			progress.put(watch.watchId(), ActionWatchProgressObservation.active());
		}
		return Map.copyOf(progress);
	}

	private void drainActionGraphCoordinatorEvents() {
		for (ActionGraphCoordinatorEvent event : actionGraphCoordinator.drainEvents()) {
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>(event.payload());
			payload.put("executionId", event.executionId());
			eventBuffer.append(tickCount, event.type(), payload);
		}
	}

	private ActionGraphPrimitiveDispatchResult dispatchActionGraphPrimitive(ActionPlanStep step) {
		WorldEvidence evidence = currentWorldEvidence(MinecraftClient.getInstance());
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(step, evidence.availableCrafts(), evidence.availableSmelts());
		if (!dispatch.dispatchable()) {
			return ActionGraphPrimitiveDispatchResult.failed(
				dispatch.failureCode(),
				dispatch.message(),
				dispatch.payload()
			);
		}
		if ((dispatch.proposal().type() == ActiveJobType.MINE_BLOCKS
			|| dispatch.proposal().type() == ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY)
			&& dispatch.proposal().mineSpec() != null) {
			Optional<String> illuminationError = miningIlluminationError(new JsonObject(), dispatch.proposal().mineSpec());
			if (illuminationError.isPresent()) {
				LinkedHashMap<String, Object> payload = new LinkedHashMap<>(dispatch.payload());
				payload.put("failureReason", "insufficient_illumination");
				// This method is unusable under current world conditions. Re-resolving
				// the same mining method cannot supply its own missing illumination.
				return ActionGraphPrimitiveDispatchResult.failed(
					TaskFailureCode.ENVIRONMENT_CHANGED,
					illuminationError.get(),
					payload
				);
			}
		}
		ActionGraphPrimitivePreflight preflight = prepareActionGraphPrimitiveProposal(dispatch.proposal());
		if (preflight == null) {
			return ActionGraphPrimitiveDispatchResult.failed(
				TaskFailureCode.UNKNOWN,
				"Action graph primitive preflight failed",
				dispatch.payload()
			);
		}
		TaskSnapshot submittedTask = submitActiveJobProposal(
			preflight.proposal(),
			"action_graph",
			actionGraphSubmittedPayload(dispatch)
		);
		Optional<WorldTaskRequest> activeTask = activeJobRuntime.activeTaskRequest();
		String taskId = activeTask.map(WorldTaskRequest::taskId).orElse(activeJobRuntime.current().jobId());
		LinkedHashMap<String, Object> resultPayload = new LinkedHashMap<>(dispatch.payload());
		resultPayload.putAll(preflight.payload());
		return ActionGraphPrimitiveDispatchResult.accepted(
			taskId,
			resultPayload,
			actionGraphTaskPayload(submittedTask, activeTask),
			Map.of("taskId", taskId, "state", TaskExecutionState.RUNNING.name())
		);
	}

	private ActionGraphPrimitivePreflight prepareActionGraphPrimitiveProposal(ActiveJobProposal proposal) {
		if (proposal == null) {
			return null;
		}
		if (proposal.type() == ActiveJobType.SMELT_ITEMS && proposal.smeltItems() != null) {
			SmeltingActionResult result = smeltingPlannerService.startSmelting(
				MinecraftClient.getInstance(),
				smeltingProcessManager,
				proposal.smeltItems(),
				tickCount
			);
			return result.accepted() && !result.confirmationRequired()
				? new ActionGraphPrimitivePreflight(proposal, processPayload(result.processId()))
				: null;
		}
		if (proposal.type() == ActiveJobType.COLLECT_SMELTED_ITEMS && proposal.collectSmeltedItems() != null) {
			SmeltingActionResult result = smeltingPlannerService.collectSmelted(
				MinecraftClient.getInstance(),
				smeltingProcessManager,
				proposal.collectSmeltedItems(),
				tickCount
			);
			if (!result.accepted() || result.confirmationRequired()) {
				return null;
			}
			if (proposal.collectSmeltedItems().processId() == null && result.processId() != null) {
				return new ActionGraphPrimitivePreflight(
					ActiveJobProposal.collectSmeltedItems(new CollectSmeltedItemsStepArgs(
						result.processId(),
						proposal.collectSmeltedItems().confirmationToken()
					)),
					processPayload(result.processId())
				);
			}
			return new ActionGraphPrimitivePreflight(proposal, processPayload(result.processId()));
		}
		return new ActionGraphPrimitivePreflight(proposal, Map.of());
	}

	private record ActionGraphPrimitivePreflight(ActiveJobProposal proposal, Map<String, Object> payload) {
	}

	private static Map<String, Object> processPayload(String processId) {
		return processId == null || processId.isBlank() ? Map.of() : Map.of("processId", processId);
	}

	private void captureActionGraphTerminalEvent(TaskTerminalEvent event) {
		ActionGraphExecutionView foreground = actionGraphCoordinator.inspect(actionGraphCoordinator.foregroundExecutionId());
		ActionGraphExecutionSnapshot snapshot = foreground == null ? ActionGraphExecutionSnapshot.idle() : foreground.execution();
		if (event == null || snapshot.activeTaskId().isBlank() || !Objects.equals(snapshot.activeTaskId(), event.taskId())) {
			return;
		}
		pendingActionGraphTerminalEvent = event;
	}

	private ActionResolverContext actionResolverContext(WorldEvidence evidence) {
		String dimension = evidence == null || evidence.dimension() == null || evidence.dimension().isBlank()
			? sessionSnapshot.dimensionId()
			: evidence.dimension();
		return new ActionResolverContext(
			sessionSnapshot.mode().name(),
			"companion",
			dimension == null || dimension.isBlank() ? "unknown" : dimension,
			tickCount
		);
	}

	private static Map<String, Object> actionGraphSubmittedPayload(ActionGraphPrimitiveDispatch dispatch) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>(dispatch.payload());
		ActionPlanStep step = dispatch.selectedStep();
		payload.put("type", dispatch.proposal().type().name());
		payload.put("source", "action_graph");
		if (step != null) {
			payload.put("graphActionId", step.actionId());
			payload.put("graphStepId", step.stepId());
			payload.put("graphPrimitive", step.targetId());
		}
		return payload;
	}

	private static Map<String, Object> actionGraphTaskPayload(TaskSnapshot task, Optional<WorldTaskRequest> activeTask) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		if (task != null) {
			payload.put("state", task.state().name());
			payload.put("source", task.source() == null ? "" : task.source());
		}
		activeTask.ifPresent(request -> {
			payload.put("taskId", request.taskId());
			payload.put("type", request.type().name());
			payload.put("sourceJobId", request.sourceJobId());
		});
		return payload;
	}

	private Map<String, Object> entityInteractionEventPayload(EntityInteractionStepArgs entityInteraction, String type, String source) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("type", type);
		payload.put("source", source == null || source.isBlank() ? "bridge_debug" : source);
		if (entityInteraction != null && entityInteraction.selector() != null) {
			if (entityInteraction.selector().uuid() != null) {
				payload.put("uuid", entityInteraction.selector().uuid());
			}
			if (entityInteraction.selector().name() != null) {
				payload.put("name", entityInteraction.selector().name());
			}
			if (entityInteraction.selector().entityTypeId() != null) {
				payload.put("entityTypeId", entityInteraction.selector().entityTypeId());
			}
		}
		if (entityInteraction != null && entityInteraction.itemId() != null) {
			payload.put("itemId", entityInteraction.itemId());
		}
		if ("ATTACK_ENTITY".equals(type) && entityInteraction != null && entityInteraction.attackMode() != null) {
			payload.put("mode", entityInteraction.attackMode().wireValue());
		}
		return payload;
	}

	void injectDialogueResponseForTests(DialogueResponse response) {
		Optional<GoalSnapshot> previousGoal = activeGoal();
		applyTaskIntent(response, currentWorldEvidence(MinecraftClient.getInstance()));
		recordPlannerOutcome(response, previousGoal, activeGoal());
	}

	void overrideSessionSnapshotForTests(SessionSnapshot sessionSnapshot) {
		sessionSnapshotOverrideForTests = sessionSnapshot;
		this.sessionSnapshot = sessionSnapshot == null ? SessionSnapshot.initial() : sessionSnapshot;
	}

	void overrideBlockAcquisitionsForTests(BlockAcquisitionIndex blockAcquisitions) {
		blockAcquisitionsOverrideForTests = blockAcquisitions;
		activeJobRuntime.updateBlockAcquisitions(blockAcquisitions());
	}

	void injectNearbyPlayerForTests(String playerName, Vec3d pos) {
		nearbyPlayerTracker.injectPlayerNearby(playerName, pos, tickCount, eventBuffer);
	}

	void disconnectNearbyPlayerForTests(String playerName) {
		nearbyPlayerTracker.injectPlayerDisconnect(playerName, tickCount, eventBuffer);
	}

	void injectGoalForTests(GoalSnapshot goalSnapshot) {
		if (goalSnapshot == null) {
			activeJobRuntime.clear();
			taskSnapshot = activeJobRuntime.taskSnapshot();
			missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
			debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
			return;
		}
		activeJobRuntime.applyPlannerResponse(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SET_GOAL,
				goalSnapshot.type(),
				goalSnapshot.targetPlayer(),
				goalSnapshot.position(),
				goalSnapshot.mineSpec()
			),
			goalSnapshot.updatedTick()
		), 0, "test", goalSnapshot.updatedTick());
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
	}

	@Override
	public CompletableFuture<String> execute(PlannerToolCall toolCall) {
		if (!dispatchingPolicyTool && policyRuntime != null && policyRuntime.active() && toolCall != null
			&& !PlannerToolCatalog.isReadTool(toolCall.name())
			&& !List.of("inspect_work", "list_work", "cancel_work", "configure_reflex", "configure_food").contains(toolCall.name())) {
			return CompletableFuture.completedFuture("TOOL_ERROR: policy_active; inspect or cancel workId=" + policyWork.id());
		}
		return plannerActionToolExecutor.execute(toolCall);
	}

	private String startPolicy(PlannerToolCall call) {
		new ai.moeru.airicraft.agent.llm.PolicyToolProvider(this).validateArguments(call.name(), call.arguments());
		requireLivingPlayerForAction();
		if (survivalReflexRuntime.snapshot().holdId() != null) throw new IllegalStateException("work_in_safety_hold");
		if (activeTaskInProgress() || actionGraphCoordinator.hasNonterminal()) throw new IllegalStateException("active_task_in_progress");
		if (MinecraftClient.getInstance() == null || MinecraftClient.getInstance().world == null)
			throw new IllegalStateException("world_not_loaded");
		policyChildren.clear();
		var host = new ToolPolicyHost(this::dispatchPolicyTool, this::describePolicyTool, workHistory::find,
			this::cancelPolicyChildren, () -> new ContainerPolicyHost(MinecraftClient.getInstance()));
		var handle = ai.moeru.airicraft.agent.work.WorkHandle.of(ai.moeru.airicraft.agent.work.WorkHandle.Kind.OPERATION, java.util.UUID.randomUUID().toString());
		String source = call.arguments().get("source").getAsString();
		var input = call.arguments().get("input").deepCopy();
		policyRuntime = new ai.moeru.airicraft.policy.PolicyRuntime(source, input, host, outcome -> {
			var details = new LinkedHashMap<String, Object>();
			details.put("source", source);
			details.put("input", input);
			details.put("reason", outcome.reason());
			details.put("result", outcome.result());
			details.put("effects", outcome.effects());
			details.put("message", "Policy " + outcome.state() + ": " + outcome.reason()
				+ ". Inspect work details for returned value and verified effects; committed effects are not rolled back.");
			recordWork(new ai.moeru.airicraft.agent.work.WorkSnapshot(handle, "",
				ai.moeru.airicraft.agent.work.WorkSnapshot.State.valueOf(outcome.state()), "run_policy", "FINISHED", false, tickCount, details));
		});
		policyWork = handle;
		var work = new ai.moeru.airicraft.agent.work.WorkSnapshot(handle, "", ai.moeru.airicraft.agent.work.WorkSnapshot.State.RUNNING,
			"run_policy", "POLICY", true, tickCount, Map.of("source", source, "input", input));
		recordWork(work);
		dialogueRuntime.observeAcceptedWork(work);
		return "Tool result for run_policy: " + new com.google.gson.Gson().toJson(work.summary());
	}

	private boolean policyActive() { return policyRuntime != null && policyRuntime.active(); }

	private void cancelPolicy(String reason) {
		if (policyRuntime != null) policyRuntime.cancel(reason);
	}

	private void tickPolicy() {
		if (policyRuntime == null || !policyRuntime.active()) return;
		if (!sessionSnapshot.companionActuationAllowed() || sessionSnapshot.requiresRespawn()) cancelPolicy("world_or_player_unavailable");
		else if (survivalReflexRuntime.snapshot().holdId() != null
			|| survivalReflexRuntime.snapshot().state() == SurvivalReflexState.ACTIVE) cancelPolicy("safety_interruption");
		else if (workHistory.list().stream().anyMatch(work -> work.foreground() && !work.state().terminal()
			&& !work.handle().equals(policyWork) && !policyOwns(work.handle()))) cancelPolicy("actuator_ownership_changed");
		else policyRuntime.tick();
	}

	private com.google.gson.JsonElement describePolicyTool(String name) {
		return policyToolDispatcher.allAvailableTools().stream()
			.filter(schema -> ((Map<?, ?>) schema.get("function")).get("name").equals(name))
			.findFirst().map(schema -> new com.google.gson.Gson().toJsonTree(schema.get("function")))
			.orElseThrow(() -> new IllegalArgumentException("tool_unavailable: " + name));
	}

	private CompletableFuture<ai.moeru.airicraft.agent.llm.ExternalPlannerToolResult> dispatchPolicyTool(String name, JsonObject args) {
		// All action admissions happen synchronously on the client thread; later futures only report outcomes.
		refreshWorkHistory();
		var before = workHistory.list().stream().map(ai.moeru.airicraft.agent.work.WorkSnapshot::handle).collect(java.util.stream.Collectors.toSet());
		dispatchingPolicyTool = true;
		try {
			return policyToolDispatcher.executeExternalTool(name, args);
		} finally {
			dispatchingPolicyTool = false;
			refreshWorkHistory();
			for (var child : workHistory.list()) {
				// Furnace processes outlive the insertion job and remain independently inspectable/collectable.
				if (!before.contains(child.handle()) && child.handle().kind() != ai.moeru.airicraft.agent.work.WorkHandle.Kind.SMELTING) {
					policyChildren.add(child.handle());
					if (child.parentWorkId().isBlank()) recordWork(new ai.moeru.airicraft.agent.work.WorkSnapshot(
						child.handle(), policyWork.id(), child.state(), child.label(), child.phase(), child.foreground(), child.updatedTick(), child.details()));
				}
			}
		}
	}

	private boolean policyOwns(ai.moeru.airicraft.agent.work.WorkHandle handle) {
		if (policyChildren.contains(handle)) return true;
		return workHistory.find(handle).filter(work -> !work.parentWorkId().isBlank())
			.map(work -> work.parentWorkId().equals(policyWork.id())
				|| policyChildren.contains(new ai.moeru.airicraft.agent.work.WorkHandle(work.parentWorkId()))).orElse(false);
	}

	private void cancelPolicyChildren() {
		var client = MinecraftClient.getInstance();
		for (var handle : policyChildren) {
			if (handle.kind() == ai.moeru.airicraft.agent.work.WorkHandle.Kind.GRAPH)
				actionGraphCoordinator.cancel(handle.nativeId(), "policy_finished", tickCount);
		}
		var job = activeJobRuntime.current();
		if (job != null && activeTaskInProgress()
			&& policyOwns(ai.moeru.airicraft.agent.work.WorkHandle.of(ai.moeru.airicraft.agent.work.WorkHandle.Kind.JOB, job.jobId()))) {
			activeJobRuntime.cancel("policy_finished", tickCount);
			taskSnapshot = activeJobRuntime.taskSnapshot();
			missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
			worldTaskExecutor.onWorldLeave();
			behaviorTreeRuntime.stop(client);
			completePendingCraftToolResult("TOOL_ERROR: policy_cancelled");
			cancelPendingBlockModificationToolResult(PendingBlockModificationStopReason.POLICY_CANCELLED);
		}
		for (var handle : policyChildren) {
			workHistory.find(handle).filter(work -> !work.state().terminal() && work.phase().equals("EATING"))
				.ifPresent(work -> {
					playerItemUseController.reset(client);
					recordWork(new ai.moeru.airicraft.agent.work.WorkSnapshot(handle, work.parentWorkId(),
						ai.moeru.airicraft.agent.work.WorkSnapshot.State.CANCELLED, work.label(), "CANCELLED", false, tickCount, Map.of("reason", "policy_finished")));
				});
		}
	}

	private void drainInteractionEvidence(MinecraftClient client) {
		int missing = droppedInteractionBatches.getAndSet(0);
		if (missing > 0) eventBuffer.append(tickCount,"interaction.history_gap",Map.of("missingBatches",missing,"recovery","Inspect current inventory/container and read the persistent logbook."));
		ObservedInteractions batch;
		while ((batch = pendingInteractions.poll()) != null) {
			if (client == null || client.getServer() != batch.server()) continue;
			for (var entry : batch.entries()) eventBuffer.append(tickCount,"interaction." + entry.action(),Map.of("observed",entry));
		}
	}

	private void stopWorkOutsideTravelBounds(MinecraftClient client) {
		if (client == null || client.player == null || client.world == null || !activeTaskInProgress()) return;
		var pos = client.player.getBlockPos();
		if (ai.moeru.airicraft.agent.spatial.WorldTravelPolicy.permitsMovement(client.world,pos.getX(),pos.getY(),pos.getZ(),pos.getX(),pos.getY(),pos.getZ(),false)) return;
		var job = activeJobRuntime.current();
		cancelTask("travel_restriction_violated");
		var evidence = new LinkedHashMap<String,Object>();
		evidence.put("failedPredicate", "actor_occupied_cells_inside_travel_bounds");
		evidence.put("scope", "current_work"); evidence.put("workId", "JOB:" + job.jobId());
		evidence.put("actorPosition", Map.of("x",pos.getX(),"y",pos.getY(),"z",pos.getZ()));
		evidence.put("bounds", ai.moeru.airicraft.agent.spatial.WorldTravelPolicy.snapshot());
		evidence.put("cause", "Observed outside restriction; displacement cause is not inferred. No recovery movement started.");
		var event = eventBuffer.append(tickCount,"work.travel_restriction_violated",evidence);
		dialogueRuntime.queueTaskWakeup(null,tickCount,event.seqNo());
	}

	private void refreshWorkHistory() {
		var client = MinecraftClient.getInstance();
		Object world = client == null ? null : client.world;
		if (world != workWorld) { workHistory.clear(); workWorld = world; }
		var reflex = survivalReflexRuntime.snapshot();
		var projected = ai.moeru.airicraft.agent.work.WorkProjection.project(activeJobRuntime.current(), taskExecutionSnapshot,
			actionGraphExecutions(), smeltingProcessManager.processSnapshots(), reflex.holdsNormalTasks(), reflex.holdId(), reflex.interruptedJobId(), reflex.interruptedActionExecutionId(), tickCount);
		for (var work : projected) recordWork(work);
		for (String id : smeltingProcessManager.drainCollectedProcesses()) {
			workHistory.find(ai.moeru.airicraft.agent.work.WorkHandle.of(ai.moeru.airicraft.agent.work.WorkHandle.Kind.SMELTING, id))
				.ifPresent(work -> recordWork(new ai.moeru.airicraft.agent.work.WorkSnapshot(work.handle(), "",
					ai.moeru.airicraft.agent.work.WorkSnapshot.State.SUCCEEDED, work.label(), "COLLECTED", false, tickCount,
					Map.of("physicalEffect", "Output slot emptied by collection interaction."))));
		}
		var present = projected.stream().map(ai.moeru.airicraft.agent.work.WorkSnapshot::handle).collect(java.util.stream.Collectors.toSet());
		for (var work : workHistory.list()) {
			if (work.state().terminal()) continue;
			if (!present.contains(work.handle()) && work.handle().kind() != ai.moeru.airicraft.agent.work.WorkHandle.Kind.OPERATION) {
				recordWork(new ai.moeru.airicraft.agent.work.WorkSnapshot(work.handle(), work.parentWorkId(),
					ai.moeru.airicraft.agent.work.WorkSnapshot.State.CANCELLED, work.label(), "NO_LONGER_TRACKED", false, tickCount,
					Map.of("physicalEffect", "Executor tracking ended or was replaced; this does not prove completion or reverse observed effects.")));
			} else if (work.phase().equals("EATING")) {
				long since = ((Number) work.details().get("afterEventSequence")).longValue();
				eventBuffer.query(since).events().stream().filter(event -> event.type().equals("food.eaten") || event.type().equals("food.eat_failed"))
					.findFirst().ifPresent(event -> recordWork(new ai.moeru.airicraft.agent.work.WorkSnapshot(work.handle(), "",
						event.type().equals("food.eaten") ? ai.moeru.airicraft.agent.work.WorkSnapshot.State.SUCCEEDED : ai.moeru.airicraft.agent.work.WorkSnapshot.State.FAILED,
						work.label(), "FINISHED", false, event.tick(), event.payload())));
			}
		}

		dialogueRuntime.observeWork(workHistory.list());
	}

	private void recordWork(ai.moeru.airicraft.agent.work.WorkSnapshot work) {
		if (workHistory.observe(work)) {
			var event = eventBuffer.append(tickCount, "work.changed", work.payload());
			if (work.state().terminal() || work.state() == ai.moeru.airicraft.agent.work.WorkSnapshot.State.PAUSED || work.phase().equals("CHECK_OUTPUT"))
				dialogueRuntime.queueTaskWakeup(null, tickCount, event.seqNo());
		}
	}

	/** Embedded planner receipts; the external wrapper retains its existing text adapter. */
	CompletableFuture<String> executePlannerAction(PlannerToolCall call) {
		if (call.name().equals("run_policy")) return CompletableFuture.completedFuture("TOOL_UNAVAILABLE: run_policy disabled");
		refreshWorkHistory();
		if (call.name().equals("continue")) {
			var reflex = survivalReflexRuntime.snapshot();
			if (reflex.state() == ai.moeru.airicraft.agent.reflex.SurvivalReflexState.AWAITING_PLANNER) {
				resumeSafetyHold(reflex.holdId(), "planner_continue");
				refreshWorkHistory();
				return CompletableFuture.completedFuture("Plan retained; paused work resumed.");
			}
			return CompletableFuture.completedFuture("Plan retained; queue continues when safety permits.");
		}
		if (call.name().equals("clear_queue")) {
			var results = new ArrayList<String>();
			for (var work : workHistory.list()) {
				if (!work.foreground() || !work.parentWorkId().isBlank() || work.state().terminal()) continue;
				var args = new com.google.gson.JsonObject();
				args.addProperty("workId", work.handle().id());
				args.addProperty("reason", "planner_clear_queue");
				String result = executeWorkTool(new PlannerToolCall("abort-" + call.id(), "cancel_work", args, null, null));
				if (result.startsWith("TOOL_ERROR:")) return CompletableFuture.completedFuture(result);
				results.add(result);
			}
			survivalReflexRuntime.discardHold("planner_clear_queue", tickCount);
			processSurvivalReflexEvents();
			return CompletableFuture.completedFuture("Tool result for clear_queue: " + results);
		}
		if (new ai.moeru.airicraft.agent.work.WorkToolProvider(this).handles(call.name())) return execute(call);
		if (PlannerToolCatalog.isReadTool(call.name())) return execute(call);
		if (survivalReflexRuntime.awaitingTacticalPlan() && workHistory.current().isEmpty()
			&& !List.of("configure_reflex", "configure_food", "configure_pathfind", "configure_lighting", "update_event_policy").contains(call.name())) {
			releaseSafetyHoldForReplacement("planner_tactical_replacement");
		}
		if (survivalReflexRuntime.snapshot().holdId() != null && !List.of("configure_reflex", "configure_food", "configure_pathfind", "configure_lighting", "update_event_policy").contains(call.name())) {
			if (call.name().equals("run_policy")) return CompletableFuture.completedFuture("TOOL_ERROR: run_policy work_in_safety_hold");
			return CompletableFuture.completedFuture("Tool result for " + call.name() + ": " + new com.google.gson.Gson().toJson(Map.of(
				"accepted",false,"reason","work_in_safety_hold","holdId",survivalReflexRuntime.snapshot().holdId(),
				"requiredAction","Use continue to resume the retained plan after the reflex releases control, or clear_queue to abort it before replacing the plan.")));
		}
		var before = workHistory.list().stream().map(ai.moeru.airicraft.agent.work.WorkSnapshot::handle).collect(java.util.stream.Collectors.toSet());
		CompletableFuture<String> result = execute(call);
		refreshWorkHistory();
		var admitted = workHistory.list().stream().filter(work -> !before.contains(work.handle()) && work.parentWorkId().isBlank()
			&& work.handle().kind() != ai.moeru.airicraft.agent.work.WorkHandle.Kind.SMELTING).findFirst();
		return result.thenApply(text -> {
			refreshWorkHistory();
			var receipt = new LinkedHashMap<String, Object>();
			boolean rejected = text.startsWith("TOOL_ERROR:") || text.startsWith("TOOL_UNAVAILABLE:");
			// A rejected policy did not install a waiter. Preserve the error prefix so its
			// endsTurn provider cannot strand the planner waiting for nonexistent work.
			if (rejected && call.name().equals("run_policy")) return text;
			boolean immediate = List.of("equip_item", "configure_reflex", "configure_food", "configure_pathfind", "configure_lighting", "update_event_policy", "close_container", "transfer_container").contains(call.name());
			boolean eating = call.name().equals("eat_food") && playerItemUseController.eating();
			boolean accepted = admitted.isPresent() || !rejected && (immediate || eating);
			receipt.put("accepted", accepted);
			if (admitted.isPresent()) {
				var work = workHistory.find(admitted.get().handle()).orElseThrow();
				receipt.putAll(work.summary());
				dialogueRuntime.observeAcceptedWork(work);
			} else if (accepted) {
				var handle = ai.moeru.airicraft.agent.work.WorkHandle.of(ai.moeru.airicraft.agent.work.WorkHandle.Kind.OPERATION,
					java.util.UUID.randomUUID().toString());
				var work = new ai.moeru.airicraft.agent.work.WorkSnapshot(handle, "", eating
					? ai.moeru.airicraft.agent.work.WorkSnapshot.State.RUNNING : ai.moeru.airicraft.agent.work.WorkSnapshot.State.SUCCEEDED,
					call.name(), eating ? "EATING" : "RETURNED", eating, tickCount,
					Map.of("result", text, "afterEventSequence", eventBuffer.latestSeqNo()));
				recordWork(work);
				receipt.putAll(work.summary());
				dialogueRuntime.observeAcceptedWork(work);
			}
			String prefix = "Tool result for " + call.name() + ": ";
			receipt.put("result", text.startsWith(prefix) ? text.substring(prefix.length()) : text);
			return prefix + new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(receipt);
		});
	}

	private String executeWorkTool(PlannerToolCall call) {
		var provider = new ai.moeru.airicraft.agent.work.WorkToolProvider(this);
		provider.validateArguments(call.name(), call.arguments());
		refreshWorkHistory();
		if (call.name().equals("list_work")) return "Tool result for list_work: "
			+ new com.google.gson.Gson().toJson(workHistory.list().stream().map(ai.moeru.airicraft.agent.work.WorkSnapshot::summary).toList());
		var requested = call.arguments().has("workId")
			? workHistory.find(new ai.moeru.airicraft.agent.work.WorkHandle(call.arguments().get("workId").getAsString())) : workHistory.current();
		if (requested.isEmpty()) return call.arguments().has("workId") ? "TOOL_ERROR: work_not_found"
			: "Tool result for inspect_work: no foreground work; use list_work for background work and retained outcomes.";
		var work = requested.get();
		var handle = work.handle();
		switch (call.name()) {
			case "inspect_work" -> { }
			case "cancel_work" -> {
				if (!work.parentWorkId().isBlank()) return "TOOL_ERROR: control_parent_work workId=" + work.parentWorkId();
				if (!work.state().terminal()) {
					String reason = call.arguments().get("reason").getAsString();
					switch (handle.kind()) {
						case GRAPH -> cancelActionGoal(handle.nativeId(), reason);
						case JOB -> {
							if (!handle.nativeId().equals(activeJobRuntime.current().jobId())) return "TOOL_ERROR: work_no_longer_active";
							cancelActiveJobOnly(reason);
						}
						case SMELTING -> {
							if (!smeltingProcessManager.cancel(handle.nativeId())) return "TOOL_ERROR: process_no_longer_tracked";
							recordWork(new ai.moeru.airicraft.agent.work.WorkSnapshot(handle, "", ai.moeru.airicraft.agent.work.WorkSnapshot.State.CANCELLED,
								work.label(), "TRACKING_CANCELLED", false, tickCount, Map.of("reason", reason, "physicalEffect", "Furnace cooking and items were not changed.")));
						}
						case OPERATION -> {
							if (handle.equals(policyWork) && policyRuntime != null && policyRuntime.active()) {
								policyRuntime.cancel(reason);
								break;
							}
							if (!work.phase().equals("EATING")) return "TOOL_ERROR: operation_not_cancellable";
							playerItemUseController.reset(MinecraftClient.getInstance());
							recordWork(new ai.moeru.airicraft.agent.work.WorkSnapshot(handle, "", ai.moeru.airicraft.agent.work.WorkSnapshot.State.CANCELLED,
								work.label(), "CANCELLED", false, tickCount, Map.of("reason", reason)));
						}
					}
				}
			}
			case "resume_work" -> {
				var reflex = survivalReflexRuntime.snapshot();
				boolean matches = handle.kind() == ai.moeru.airicraft.agent.work.WorkHandle.Kind.JOB && handle.nativeId().equals(reflex.interruptedJobId())
					|| handle.kind() == ai.moeru.airicraft.agent.work.WorkHandle.Kind.GRAPH && handle.nativeId().equals(reflex.interruptedActionExecutionId());
				if (!matches || work.state() != ai.moeru.airicraft.agent.work.WorkSnapshot.State.PAUSED) return "TOOL_ERROR: work_not_in_current_hold";
				resumeSafetyHold(call.arguments().get("holdId").getAsString(), "planner_work_tool");
			}
			default -> throw new IllegalArgumentException("unknown_work_tool");
		}
		refreshWorkHistory();
		var result = new LinkedHashMap<>(call.name().equals("inspect_work") ? workHistory.find(handle).orElseThrow().payload()
			: workHistory.find(handle).orElseThrow().summary());
		if (!call.name().equals("inspect_work")) result.put("accepted", true);
		if (call.name().equals("resume_work")) dialogueRuntime.observeAcceptedWork(workHistory.find(handle).orElseThrow());
		return "Tool result for " + call.name() + ": " + new com.google.gson.Gson().toJson(result);
	}

	private EmbodiedPlannerActionToolExecutor.ExecutionState plannerActionToolExecutionState() {
		ActiveJob activeJob = activeJobRuntime.current();
		return new EmbodiedPlannerActionToolExecutor.ExecutionState(
			survivalReflexRuntime.snapshot().state(),
			sessionSnapshot.requiresRespawn(),
			playerItemUseController.eating(),
			activeTaskInProgress(),
			activeJob == null ? null : activeJob.type(),
			actionGraphCoordinator.hasNonterminal(),
			actionGraphExecutionSnapshot(),
			taskSnapshot,
			taskExecutionSnapshot
		);
	}

	private void requireLivingPlayerForAction() {
		if (sessionSnapshot.requiresRespawn()) {
			throw new BridgeUnavailableException(
				"player_dead",
				"The controlled player is dead; actions are disabled until respawn"
			);
		}
		if (survivalReflexRuntime.snapshot().state() == SurvivalReflexState.ACTIVE) {
			throw new BridgeUnavailableException("reflex_active", "A survival reflex currently owns player actuation");
		}
	}

	private static boolean intentRequiresLivingPlayer(DialogueIntentType type) {
		return switch (type) {
			case SET_GOAL, JOB_UPDATE, MISSION_UPDATE, SUBMIT_TASK -> true;
			case CLEAR_GOAL, CANCEL_TASK, REPLY_ONLY, ASK_CLARIFICATION, ACKNOWLEDGE_FAILURE, NONE -> false;
		};
	}

	private static boolean legacyIntentWouldMutateGraphBoundary(DialogueIntentType type) {
		return switch (type) {
			case SET_GOAL, JOB_UPDATE, MISSION_UPDATE, SUBMIT_TASK, CLEAR_GOAL, CANCEL_TASK -> true;
			case REPLY_ONLY, ASK_CLARIFICATION, ACKNOWLEDGE_FAILURE, NONE -> false;
		};
	}

	private String executePlannerToolCallNow(PlannerToolCall toolCall) {
		if (toolCall == null) {
			return "TOOL_ERROR: missing_tool_call";
		}
		JsonObject args = toolCall.arguments();
		String normalizedToolName = PlannerToolCatalog.normalizeName(toolCall.name());
		return switch (normalizedToolName) {
			case "inspect_work", "list_work", "cancel_work", "resume_work" -> executeWorkTool(toolCall);
			case "run_policy" -> "TOOL_UNAVAILABLE: run_policy disabled";
			case PlannerToolCatalog.RESUME_TASK -> {
				String holdId = stringArg(args, "holdId").orElseThrow(() -> new IllegalArgumentException("holdId is required"));
				SurvivalReflexSnapshot reflex = resumeSafetyHold(holdId, "planner_tool");
				yield "Tool result for resume_task: accepted holdId=" + holdId
					+ " safetyEpoch=" + reflex.safetyEpoch()
					+ " taskState=" + taskSnapshot.state().name();
			}
			case PlannerToolCatalog.START_ACTION_GOAL -> {
				ActionGraphStartResult result = startActionGoalDetailed(parseActionGoalArgs(args), "planner_tool");
				yield actionGraphStartToolResult(result);
			}
			case PlannerToolCatalog.LIST_ACTION_GOALS -> {
				yield actionGraphListToolResult(actionGraphExecutions(), false);
			}
			case PlannerToolCatalog.INSPECT_ACTION_GOAL -> {
				String executionId = stringArg(args, "executionId").orElse(null);
				ActionGraphExecutionView view = actionGraphCoordinator.inspect(executionId);
				yield actionGraphViewToolResult("inspect_action_goal", view, false);
			}
			case PlannerToolCatalog.CANCEL_ACTION_GOAL -> {
				String executionId = stringArg(args, "executionId").orElse(null);
				String reason = stringArg(args, "reason").orElse("planner_tool_cancelled");
				yield actionGraphToolResult("cancel_action_goal", executionId == null
					? cancelActionGoal(reason)
					: cancelActionGoal(executionId, reason), false);
			}
			case PlannerToolCatalog.INSPECT_ACTION_TRACE -> {
				String executionId = stringArg(args, "executionId").orElse(null);
				ActionGraphExecutionView view = actionGraphCoordinator.inspect(executionId);
				yield actionGraphViewToolResult("inspect_action_trace", view, true);
			}
			case PlannerToolCatalog.LIST_ACTION_CAPABILITIES -> {
				yield actionGraphCapabilitiesToolResult();
			}
			case PlannerToolCatalog.FOLLOW_PLAYER -> {
				String targetPlayer = stringArg(args, "targetPlayer").orElseThrow(() -> new IllegalArgumentException("targetPlayer is required"));
				applyPlannerJobTool(ActiveJobProposal.followPlayer(targetPlayer));
				yield queuedActionToolResult("follow_player", "targetPlayer=" + targetPlayer);
			}
			case PlannerToolCatalog.NAVIGATE_TO -> {
				GoalPosition position = new GoalPosition(
					intArg(args, "x").orElseThrow(() -> new IllegalArgumentException("x is required")),
					intArg(args, "y").orElseThrow(() -> new IllegalArgumentException("y is required")),
					intArg(args, "z").orElseThrow(() -> new IllegalArgumentException("z is required")),
					booleanArg(args, "exactY").orElse(false)
				);
				applyPlannerJobTool(ActiveJobProposal.navigateTo(position));
				yield queuedActionToolResult("navigate_to", "x=" + position.x() + " y=" + position.y() + " z=" + position.z() + " exactY=" + position.exactY());
			}
			case PlannerToolCatalog.RETURN_TO_SURFACE -> {
				boolean useTowering = booleanArg(args, "useTowering").orElse(true);
				List<String> fillerBlockIds = args != null && args.has("fillerBlockIds")
					? stringArrayArg(args, "fillerBlockIds")
					: ReturnToSurfaceStepArgs.DEFAULT_FILLER_BLOCK_IDS;
				fillerBlockIds = ReturnToSurfaceStepArgs.normalizeFillerBlockIds(fillerBlockIds);
				Optional<String> validationError = validateFillerBlockIds(fillerBlockIds);
				if (validationError.isPresent()) {
					yield "TOOL_ERROR: return_to_surface " + validationError.get();
				}
				Optional<SurfaceMemory.SurfaceTarget> target = surfaceMemory.bestTarget();
				if (target.isEmpty() && !useTowering) {
					yield "TOOL_ERROR: return_to_surface surface_target_unavailable. No remembered surface is available; retry with useTowering=true if filler blocks are available.";
				}
				ReturnToSurfaceStepArgs returnToSurface = new ReturnToSurfaceStepArgs(
					target.map(SurfaceMemory.SurfaceTarget::position).orElse(null),
					target.map(SurfaceMemory.SurfaceTarget::kind).orElse("none"),
					useTowering,
					fillerBlockIds
				);
				applyPlannerJobTool(ActiveJobProposal.returnToSurface(returnToSurface));
				String targetDetails = target
					.map(surfaceTarget -> surfaceTarget.kind() + "=" + formatPosition(surfaceTarget.position()))
					.orElse("none");
				yield queuedActionToolResult(
					"return_to_surface",
					"target=" + targetDetails + " useTowering=" + useTowering + " fillerBlockIds=" + String.join(",", returnToSurface.fillerBlockIds())
				);
			}
			case PlannerToolCatalog.MINE_BLOCKS -> {
				GoalMineSpec mineSpec = goalMineSpec(
					stringArrayArg(args, "blockIds"),
					intArg(args, "quantity").orElseThrow(() -> new IllegalArgumentException("quantity is required"))
				).withConstraints(acquisitionConstraints(args));
				Optional<String> validationError = validateMineBlockIds(mineSpec.blockIds());
				if (validationError.isPresent()) {
					yield "TOOL_ERROR: mine_blocks " + validationError.get();
				}
				Optional<String> illuminationError = miningIlluminationError(args, mineSpec);
				if (illuminationError.isPresent()) {
					yield "TOOL_ERROR: mine_blocks " + illuminationError.get();
				}
				applyPlannerJobTool(ActiveJobProposal.mineBlocks(mineSpec));
				yield queuedActionToolResult("mine_blocks", "blockIds=" + String.join(",", mineSpec.blockIds())
					+ " quantity=" + mineSpec.quantity()
					+ " allowUnilluminated=" + booleanArg(args, "allowUnilluminated").orElse(false));
			}
			case PlannerToolCatalog.ENSURE_BLOCKS_IN_INVENTORY -> {
				GoalMineSpec mineSpec = goalMineSpec(
					stringArrayArg(args, "blockIds"),
					intArg(args, "quantity").orElseThrow(() -> new IllegalArgumentException("quantity is required"))
				).withConstraints(acquisitionConstraints(args));
				Optional<String> validationError = validateMineBlockIds(mineSpec.blockIds());
				if (validationError.isPresent()) {
					yield "TOOL_ERROR: ensure_blocks_in_inventory " + validationError.get();
				}
				WorldEvidence evidence = currentWorldEvidence(MinecraftClient.getInstance());
				int currentItemCount = mineSpec.matchingItemIds().stream()
					.mapToInt(itemId -> evidence.itemCounts().getOrDefault(itemId, 0))
					.sum();
				if (currentItemCount >= mineSpec.quantity()) {
					yield "Tool result for ensure_blocks_in_inventory: already_satisfied blockIds="
						+ String.join(",", mineSpec.blockIds())
						+ " quantity="
						+ mineSpec.quantity()
						+ " itemCount="
						+ currentItemCount
						+ " matchingItemIds="
						+ mineSpec.matchingItemIds();
				}
				Optional<String> illuminationError = miningIlluminationError(args, mineSpec);
				if (illuminationError.isPresent()) {
					yield "TOOL_ERROR: ensure_blocks_in_inventory " + illuminationError.get();
				}
				applyPlannerJobTool(ActiveJobProposal.ensureBlocksInInventory(mineSpec));
				yield queuedActionToolResult("ensure_blocks_in_inventory", "blockIds=" + String.join(",", mineSpec.blockIds()) + " quantity=" + mineSpec.quantity());
			}
			case PlannerToolCatalog.COLLECT_RESOURCE -> {
				TaskResourceKind resourceKind = resourceKindArg(args, "resourceKind");
				int quantity = intArg(args, "quantity").orElseThrow(() -> new IllegalArgumentException("quantity is required"));
				applyPlannerJobTool(ActiveJobProposal.collectResource(new TaskSpec(TaskType.COLLECT_RESOURCE, resourceKind, quantity, acquisitionConstraints(args))));
				yield "Tool result for collect_resource: accepted resourceKind=" + resourceKind.name() + " quantity=" + quantity;
			}
			case PlannerToolCatalog.CRAFT_RECIPE -> {
				yield "TOOL_ERROR: craft_recipe async_path_required";
			}
			case PlannerToolCatalog.CHECK_SMELTABLES -> {
				yield smeltingPlannerService.checkSmeltables(MinecraftClient.getInstance(), smeltingProcessManager, tickCount);
			}
			case PlannerToolCatalog.INSPECT_SMELTING -> {
				yield smeltingPlannerService.inspectSmelting(MinecraftClient.getInstance(), smeltingProcessManager, tickCount);
			}
			case PlannerToolCatalog.SMELT_ITEMS -> {
				SmeltItemsStepArgs smeltItems = new SmeltItemsStepArgs(
					stringArg(args, "optionId").orElseThrow(() -> new IllegalArgumentException("optionId is required")),
					intArg(args, "inputQuantity").orElseThrow(() -> new IllegalArgumentException("inputQuantity is required")),
					SmeltingFuelMode.fromWireValue(stringArg(args, "fuelMode").orElse(null)),
					stringArg(args, "fuelItemId").orElse(null),
					intArg(args, "fuelQuantity").orElse(0),
					stringArg(args, "confirmationToken").orElse(null)
				);
				SmeltingActionResult smeltingResult = smeltingPlannerService.startSmelting(
					MinecraftClient.getInstance(),
					smeltingProcessManager,
					smeltItems,
					tickCount
				);
				if (smeltingResult.confirmationRequired()) {
					yield "Tool result for smelt_items: confirmationRequired confirmationToken="
						+ smeltingResult.confirmationToken()
						+ " "
						+ smeltingResult.message();
				}
				if (!smeltingResult.accepted()) {
					yield "Tool result for smelt_items: refused error_code="
						+ smeltingResult.errorCode()
						+ " message="
						+ smeltingResult.message();
				}
				applyPlannerJobTool(ActiveJobProposal.smeltItems(smeltItems));
				yield queuedActionToolResult(
					"smelt_items",
					"processId=" + smeltingResult.processId() + " optionId=" + smeltItems.optionId() + " inputQuantity=" + smeltItems.inputQuantity()
				) + " This job loads the furnace; cooking continues in the background. Output is not auto-collected. "
					+ "When smelting is ready, call collect_smelted_items with processId=" + smeltingResult.processId() + ".";
			}
			case PlannerToolCatalog.COLLECT_SMELTED_ITEMS -> {
				CollectSmeltedItemsStepArgs collect = new CollectSmeltedItemsStepArgs(
					stringArg(args, "processId").orElse(null),
					stringArg(args, "confirmationToken").orElse(null)
				);
				SmeltingActionResult collectResult = smeltingPlannerService.collectSmelted(
					MinecraftClient.getInstance(),
					smeltingProcessManager,
					collect,
					tickCount
				);
				if (collectResult.confirmationRequired()) {
					yield "Tool result for collect_smelted_items: confirmationRequired confirmationToken="
						+ collectResult.confirmationToken()
						+ " "
						+ collectResult.message();
				}
				if (!collectResult.accepted()) {
					yield "Tool result for collect_smelted_items: refused error_code="
						+ collectResult.errorCode()
						+ " message="
						+ collectResult.message();
				}
				CollectSmeltedItemsStepArgs queuedCollect = collect.processId() == null && collectResult.processId() != null
					? new CollectSmeltedItemsStepArgs(collectResult.processId(), collect.confirmationToken())
					: collect;
				applyPlannerJobTool(ActiveJobProposal.collectSmeltedItems(queuedCollect));
				yield queuedActionToolResult(
					"collect_smelted_items",
					(queuedCollect.processId() == null ? "processId=untracked" : "processId=" + queuedCollect.processId())
				);
			}
			case PlannerToolCatalog.CANCEL_SMELTING -> {
				String processId = stringArg(args, "processId").orElseThrow(() -> new IllegalArgumentException("processId is required"));
				boolean cancelled = smeltingProcessManager.cancel(processId);
				yield "Tool result for cancel_smelting: accepted processId=" + processId + " tracked=" + cancelled;
			}
			case PlannerToolCatalog.CLOSE_CONTAINER -> ContainerInventoryController.close(MinecraftClient.getInstance());
			case PlannerToolCatalog.INSPECT_CONTAINER -> ContainerInventoryController.inspect(MinecraftClient.getInstance());
			case PlannerToolCatalog.TRANSFER_CONTAINER -> {
				var entries = args.has("items") ? args.getAsJsonArray("items") : new com.google.gson.JsonArray();
				if (!args.has("items")) entries.add(args);
				List<ContainerInventoryController.TransferItem> items = new ArrayList<>();
				for (var entry : entries) {
					var item = entry.getAsJsonObject();
					items.add(new ContainerInventoryController.TransferItem(stringArg(item, "itemId").orElseThrow(), intArg(item, "quantity").orElseThrow()));
				}
				yield ContainerInventoryController.transfer(MinecraftClient.getInstance(),
					intArg(args, "syncId").orElseThrow(), stringArg(args, "direction").orElseThrow(), items);
			}
			case PlannerToolCatalog.EQUIP_ITEM -> {
				String itemId = stringArg(args, "itemId").orElseThrow(() -> new IllegalArgumentException("itemId is required"));
				yield playerItemUseController.equip(MinecraftClient.getInstance(), itemId);
			}
			case PlannerToolCatalog.EAT_FOOD -> {
				String itemId = stringArg(args, "itemId").orElseThrow(() -> new IllegalArgumentException("itemId is required"));
				yield playerItemUseController.eat(MinecraftClient.getInstance(), itemId, tickCount);
			}
			case PlannerToolCatalog.DROP_ITEMS -> {
				DropItemsStepArgs dropItems = new DropItemsStepArgs(
					stringArg(args, "itemId").orElseThrow(() -> new IllegalArgumentException("itemId is required")),
					intArg(args, "quantity").orElseThrow(() -> new IllegalArgumentException("quantity is required")),
					null
				);
				applyPlannerJobTool(ActiveJobProposal.dropItems(dropItems));
				yield queuedActionToolResult("drop_items", "itemId=" + dropItems.itemId() + " quantity=" + dropItems.quantity());
			}
			case PlannerToolCatalog.GIVE_PLAYER -> {
				String targetPlayer = stringArg(args, "targetPlayer").orElseThrow(() -> new IllegalArgumentException("targetPlayer is required"));
				ensureGiveTargetNearby(targetPlayer);
				DropItemsStepArgs dropItems = new DropItemsStepArgs(
					stringArg(args, "itemId").orElseThrow(() -> new IllegalArgumentException("itemId is required")),
					intArg(args, "quantity").orElseThrow(() -> new IllegalArgumentException("quantity is required")),
					targetPlayer
				);
				applyPlannerJobTool(ActiveJobProposal.dropItems(dropItems));
				yield queuedActionToolResult("give_player", "targetPlayer=" + targetPlayer + " itemId=" + dropItems.itemId() + " quantity=" + dropItems.quantity());
			}
			case PlannerToolCatalog.ATTACK_ENTITY -> {
				EntityInteractionStepArgs entityInteraction = new EntityInteractionStepArgs(
					parseEntitySelectorArgs(args),
					null,
					EntityAttackMode.fromWireValue(stringArg(args, "mode").orElse(null))
				);
				applyPlannerJobTool(ActiveJobProposal.attackEntity(entityInteraction));
				yield queuedActionToolResult("attack_entity", describeEntitySelector(entityInteraction.selector()) + " mode=" + entityInteraction.attackMode().wireValue());
			}
			case PlannerToolCatalog.USE_ENTITY -> {
				EntityInteractionStepArgs entityInteraction = new EntityInteractionStepArgs(
					parseEntitySelectorArgs(args),
					stringArg(args, "itemId").orElse(null)
				);
				applyPlannerJobTool(ActiveJobProposal.useEntity(entityInteraction));
				String details = describeEntitySelector(entityInteraction.selector())
					+ (entityInteraction.itemId() == null ? "" : " itemId=" + entityInteraction.itemId());
				yield queuedActionToolResult("use_entity", details);
			}
			case PlannerToolCatalog.PLACE_BLOCK -> {
				BlockPlacementStepArgs blockPlacement = parseBlockPlacementArgs(args);
				for (BlockPlacementStepArgs.Target target : blockPlacement.targets()) {
					BlockPos targetPos = blockPos(target.targetPosition());
					if (!worldReadLedger.isFresh(targetPos)) {
						yield guardedModificationNeedsInspect(PlannerToolCatalog.PLACE_BLOCK, blockPlacement.targets().stream().map(item -> blockPos(item.targetPosition())).toList());
					}
				}
				applyPlannerJobTool(ActiveJobProposal.placeBlock(blockPlacement));
				yield queuedActionToolResult("place_block", "itemId=" + blockPlacement.itemId()
					+ " targets=" + blockPlacement.targets().size()
					+ " firstTargetPos=" + compactPos(blockPos(blockPlacement.targets().getFirst().targetPosition()))
					+ " readFreshnessRemainingToolCalls=" + worldReadLedger.freshnessRemaining(blockPos(blockPlacement.targets().getFirst().targetPosition())));
			}
			case PlannerToolCatalog.USE_BLOCK -> {
				BlockUseStepArgs blockUse = parseBlockUseArgs(args);
				for (BlockUseStepArgs.Target target : blockUse.targets()) {
					BlockPos targetPos = blockPos(target.targetPosition());
					if (!worldReadLedger.isFresh(targetPos)) {
						yield guardedModificationNeedsInspect(PlannerToolCatalog.USE_BLOCK, blockUse.targets().stream().map(item -> blockPos(item.targetPosition())).toList());
					}
				}
				applyPlannerJobTool(ActiveJobProposal.useBlock(blockUse));
				yield queuedActionToolResult("use_block", (blockUse.itemId() == null ? "" : "itemId=" + blockUse.itemId() + " ")
					+ "targets=" + blockUse.targets().size()
					+ " firstTargetPos=" + compactPos(blockPos(blockUse.targets().getFirst().targetPosition()))
					+ " readFreshnessRemainingToolCalls=" + worldReadLedger.freshnessRemaining(blockPos(blockUse.targets().getFirst().targetPosition())));
			}
			case PlannerToolCatalog.BREAK_BLOCKS -> {
				BlockBreakStepArgs blockBreak = parseBlockBreakArgs(args);
				for (BlockBreakStepArgs.Target target : blockBreak.targets()) {
					Optional<String> validationError = validateMineBlockIds(target.expectedBlockIds());
					if (validationError.isPresent()) {
						yield "TOOL_ERROR: break_blocks " + validationError.get();
					}
				}
				for (BlockBreakStepArgs.Target target : blockBreak.targets()) {
					BlockPos targetPos = blockPos(target.position());
					if (!worldReadLedger.isFresh(targetPos)) {
						yield guardedModificationNeedsInspect(PlannerToolCatalog.BREAK_BLOCKS, blockBreak.targets().stream().map(item -> blockPos(item.position())).toList());
					}
				}
				applyPlannerJobTool(ActiveJobProposal.breakBlocks(blockBreak));
				yield queuedActionToolResult(
					"break_blocks",
					"targets=" + blockBreak.targets().size()
						+ " firstTargetPos=" + compactPos(blockPos(blockBreak.targets().getFirst().position()))
				);
			}
			case PlannerToolCatalog.LURE_ENTITIES -> {
				var lure = ai.moeru.airicraft.agent.tasks.LureEntitiesStepArgs.parse(args);
				applyPlannerJobTool(ActiveJobProposal.lureEntities(lure));
				yield queuedActionToolResult("lure_entities", "destination=" + lure);
			}
			case PlannerToolCatalog.TEND_CROPS -> {
				var cropTending = ai.moeru.airicraft.agent.tasks.CropTendingStepArgs.parse(args);
				applyPlannerJobTool(ActiveJobProposal.tendCrops(cropTending));
				yield queuedActionToolResult("tend_crops", "plot=" + cropTending);
			}
			case PlannerToolCatalog.CANCEL_TASK -> {
				String reason = stringArg(args, "reason").orElse("planner_tool_cancelled");
				TaskSnapshot snapshot = cancelTask(reason);
				yield "Tool result for cancel_task: accepted state=" + snapshot.state().name();
			}
			case PlannerToolCatalog.CLEAR_GOAL -> {
				applyPlannerClearGoalTool();
				yield "Tool result for clear_goal: accepted";
			}
			case PlannerToolCatalog.UPDATE_EVENT_POLICY -> {
				EventPolicyChanges changes = parseToolEventPolicyChanges(args);
				applyPlannerEventPolicyChanges(changes);
				yield "Tool result for update_event_policy: applied clearAll=" + changes.clearAll()
					+ " removeRuleIds=" + changes.removeRuleIds().size()
					+ " upserts=" + changes.upserts().size();
			}
			case PlannerToolCatalog.CONFIGURE_PATHFIND -> {
				BaritonePathfindSettings.ApplyResult result = BaritonePathfindSettings.apply(
					args != null && args.has("settings") && args.get("settings").isJsonObject()
						? args.getAsJsonObject("settings")
						: null
				);
				yield result.accepted()
					? "Tool result for configure_pathfind: applied " + String.join(", ", result.changed())
					: "TOOL_ERROR: configure_pathfind " + result.error();
			}
			case PlannerToolCatalog.CONFIGURE_LIGHTING -> {
				LightingPolicy lightingPolicy = lightingRuntime.configure(
					args.get("enabled").getAsBoolean(),
					LightingPolicy.Mode.parse(args.get("mode").getAsString()),
					args.get("maxLightLevel").getAsInt(),
					true,
					args.get("minSpacingBlocks").getAsInt()
				);
				yield "Tool result for configure_lighting: applied enabled=" + lightingPolicy.enabled()
					+ " mode=" + lightingPolicy.mode().wireName()
					+ " maxLightLevel=" + lightingPolicy.maxLightLevel()
					+ " minSpacingBlocks=" + lightingPolicy.minSpacingBlocks()
					+ " policyRevision=" + lightingPolicy.revision();
			}
			case PlannerToolCatalog.CONFIGURE_REFLEX -> {
				var policy = args.isEmpty() ? survivalReflexRuntime.policy() : survivalReflexRuntime.configure(
					new ai.moeru.airicraft.agent.reflex.ReflexPolicy(args.get("combatEnabled").getAsBoolean(),
						args.get("drowningEnabled").getAsBoolean(), args.get("maxThreatDistance").getAsInt(),
						args.get("requireLineOfSight").getAsBoolean()));
				yield "Tool result for configure_reflex: " + (args.isEmpty() ? "current " : "applied ") + policy
					+ "; changes take effect next tick. Observe ownership release, then use continue to resume the plan, or clear_queue to replace it.";
			}
			case PlannerToolCatalog.CONFIGURE_FOOD -> {
				FoodPolicy policy = args.isEmpty() ? foodRuntime.policy() : foodRuntime.configure(
					FoodPolicy.Goal.valueOf(args.get("goal").getAsString().toUpperCase(java.util.Locale.ROOT)),
					FoodPolicy.FoodChoice.valueOf(args.get("foodChoice").getAsString().toUpperCase(java.util.Locale.ROOT)));
				yield "Tool result for configure_food: " + (args.isEmpty() ? "current " : "applied ") + policy;
			}
			default -> "TOOL_ERROR: unknown_tool " + toolCall.name();
		};
	}

	private boolean directPlannerIntentWouldPreemptActiveTask(DialogueIntent intent) {
		return isDirectGoalIntent(intent) && activeTaskInProgress();
	}

	private boolean activeTaskInProgress() {
		ActiveJob activeJob = activeJobRuntime.current();
		boolean activeJobTerminal = activeJob == null
			|| activeJob.isIdle()
			|| activeJob.status() == null
			|| activeJob.status().terminal();
		if (activeJobTerminal && taskSnapshot != null && isTerminalTaskState(taskSnapshot.state())) {
			return false;
		}
		if (isActiveTaskExecutionState(taskExecutionSnapshot == null ? null : taskExecutionSnapshot.state())) {
			return true;
		}
		if (isSemanticTaskSnapshot(taskSnapshot) && isActiveSemanticTaskState(taskSnapshot.state())) {
			return true;
		}
		if (activeJobTerminal) {
			return false;
		}
		return activeJob.status() == ActiveJobStatus.QUEUED
			|| activeJob.status() == ActiveJobStatus.RUNNING
			|| activeJob.status() == ActiveJobStatus.BLOCKED;
	}

	private static String queuedActionToolResult(String toolName, String details) {
		return "Tool result for " + toolName + ": accepted queued " + details
			+ ". Accepted does not mean completed. Wait for its terminal result in a later observation before saying it completed.";
	}

	private static String actionGraphToolResult(String toolName, ActionGraphExecutionSnapshot snapshot, boolean verbose) {
		Map<String, Object> payload = snapshot == null ? ActionGraphExecutionSnapshot.idle().toPayload(verbose) : snapshot.toPayload(verbose);
		return "Tool result for " + toolName
			+ ": state=" + payload.get("state")
			+ " executionPhase=" + payload.get("executionPhase")
			+ " resolved=" + payload.get("resolved")
			+ " accepted=" + payload.get("accepted")
			+ " activePrimitive=" + payload.get("activePrimitive")
			+ " executionId=" + payload.get("executionId")
			+ " activeTaskId=" + payload.get("activeTaskId")
			+ " traceEventCount=" + payload.get("traceEventCount")
			+ " failureCode=" + payload.get("failureCode")
			+ " payload=" + payload;
	}

	private static String actionGraphStartToolResult(ActionGraphStartResult result) {
		Map<String, Object> payload = result == null ? Map.of("admission", "busy") : result.toPayload(false);
		return "Tool result for start_action_goal: state=" + payload.getOrDefault("state", "IDLE")
			+ " admission=" + payload.get("admission")
			+ " executionPhase=" + payload.getOrDefault("executionPhase", "IDLE")
			+ " resolved=" + payload.getOrDefault("resolved", false)
			+ " accepted=" + payload.getOrDefault("accepted", false)
			+ " activePrimitive=" + payload.getOrDefault("activePrimitive", false)
			+ " executionId=" + payload.getOrDefault("executionId", "")
			+ " foregroundExecutionId=" + payload.getOrDefault("foregroundExecutionId", "")
			+ " suspendedCount=" + payload.getOrDefault("suspendedCount", 0)
			+ " runnableCount=" + payload.getOrDefault("runnableCount", 0)
			+ " failureCode=" + payload.getOrDefault("failureCode", "")
			+ " payload=" + payload;
	}

	private static String actionGraphViewToolResult(String toolName, ActionGraphExecutionView view, boolean verbose) {
		if (view == null) {
			return "TOOL_ERROR: " + toolName + " execution_not_found";
		}
		Map<String, Object> payload = view.toPayload(verbose);
		return "Tool result for " + toolName + ": state=" + payload.get("state")
			+ " executionPhase=" + payload.get("executionPhase")
			+ " resolved=" + payload.get("resolved")
			+ " accepted=" + payload.get("accepted")
			+ " activePrimitive=" + payload.get("activePrimitive")
			+ " executionId=" + payload.get("executionId")
			+ " payload=" + payload;
	}

	private static String actionGraphListToolResult(List<ActionGraphExecutionView> executions, boolean verbose) {
		List<Map<String, Object>> goals = executions == null
			? List.of()
			: executions.stream().map(execution -> execution.toPayload(verbose)).toList();
		return "Tool result for list_action_goals: count=" + goals.size() + " goals=" + goals;
	}

	private static String actionGraphCapabilitiesToolResult() {
		Map<String, Object> graph = new ActionGraphDebugService().inspectActionGraph();
		Map<String, Object> goalKinds = Map.of(
			"inventory_item", Map.of("status", "supported", "fields", List.of("itemId", "quantity")),
			"resource_collection", Map.of("status", "supported", "fields", List.of("resourceKind", "quantity"), "supportedResourceKinds", ResourceGatheringCatalog.supportedKindNames()),
			"smelting_output", Map.of("status", "supported", "fields", List.of("itemId", "quantity"), "aliasOf", "inventory_item"),
			"crafting_output", Map.of("status", "supported", "fields", List.of("itemId", "quantity"), "aliasOf", "inventory_item")
		);
		return "Tool result for list_action_capabilities: supportedGoalKinds="
			+ goalKinds
			+ " primitiveCount="
			+ graph.get("primitiveCount")
			+ " domainProviderCount="
			+ graph.get("domainProviderCount")
			+ " graph="
			+ graph;
	}

	private static String formatPosition(GoalPosition position) {
		if (position == null) {
			return "none";
		}
		return "x=" + position.x() + " y=" + position.y() + " z=" + position.z();
	}

	void registerSmeltingOptionsForTests(List<SmeltingOption> options) {
		smeltingProcessManager.registerOptions(options);
	}

	void recordWorldReadForTests(BlockPos pos) {
		worldReadLedger.recordObserved(List.of(pos));
	}

	private CompletableFuture<String> executeCraftRecipePlannerTool(JsonObject args) {
		CraftRecipeStepArgs craftRecipe = new CraftRecipeStepArgs(
			stringArg(args, "recipeId").orElseThrow(() -> new IllegalArgumentException("recipeId is required")),
			intArg(args, "times").orElseThrow(() -> new IllegalArgumentException("times is required"))
		);
		applyPlannerJobTool(ActiveJobProposal.craftRecipe(craftRecipe));
		Optional<WorldTaskRequest> activeTask = activeJobRuntime.activeTaskRequest();
		if (activeTask.isEmpty() || !(activeTask.get().task() instanceof WorldTaskRequest.CraftRecipe)) {
			return CompletableFuture.completedFuture("TOOL_ERROR: craft_recipe task_not_started");
		}

		completePendingCraftToolResult("Tool result for craft_recipe: cancelled reason=superseded");
		CompletableFuture<String> future = new CompletableFuture<>();
		pendingCraftToolResult = new PendingCraftToolResult(
			activeTask.get().taskId(),
			craftRecipe,
			tickCount,
			future
		);
		return future;
	}

	private CompletableFuture<String> executeBlockModificationPlannerTool(PlannerToolCall toolCall) {
		String toolName = PlannerToolCatalog.normalizeName(toolCall.name());
		JsonObject args = toolCall.arguments();
		ActiveJobProposal proposal;
		WorldTaskType expectedTaskType;
		LedgerStepKind expectedStepKind;
		String details;
		switch (toolName) {
			case PlannerToolCatalog.PLACE_BLOCK -> {
				BlockPlacementStepArgs blockPlacement = parseBlockPlacementArgs(args);
				for (BlockPlacementStepArgs.Target target : blockPlacement.targets()) {
					BlockPos targetPos = blockPos(target.targetPosition());
					if (!worldReadLedger.isFresh(targetPos)) {
						return CompletableFuture.completedFuture(guardedModificationNeedsInspect(PlannerToolCatalog.PLACE_BLOCK, blockPlacement.targets().stream().map(item -> blockPos(item.targetPosition())).toList()));
					}
				}
				proposal = ActiveJobProposal.placeBlock(blockPlacement);
				expectedTaskType = WorldTaskType.PLACE_BLOCK;
				expectedStepKind = LedgerStepKind.PLACE_BLOCK;
				details = "itemId=" + blockPlacement.itemId()
					+ " targets=" + blockPlacement.targets().size()
					+ " firstTargetPos=" + compactPos(blockPos(blockPlacement.targets().getFirst().targetPosition()))
					+ " readFreshnessRemainingToolCalls=" + worldReadLedger.freshnessRemaining(blockPos(blockPlacement.targets().getFirst().targetPosition()));
			}
			case PlannerToolCatalog.USE_BLOCK -> {
				BlockUseStepArgs blockUse = parseBlockUseArgs(args);
				for (BlockUseStepArgs.Target target : blockUse.targets()) {
					BlockPos targetPos = blockPos(target.targetPosition());
					if (!worldReadLedger.isFresh(targetPos)) {
						return CompletableFuture.completedFuture(guardedModificationNeedsInspect(PlannerToolCatalog.USE_BLOCK, blockUse.targets().stream().map(item -> blockPos(item.targetPosition())).toList()));
					}
				}
				proposal = ActiveJobProposal.useBlock(blockUse);
				expectedTaskType = WorldTaskType.USE_BLOCK;
				expectedStepKind = LedgerStepKind.USE_BLOCK;
				details = (blockUse.itemId() == null ? "" : "itemId=" + blockUse.itemId() + " ")
					+ "targets=" + blockUse.targets().size()
					+ " firstTargetPos=" + compactPos(blockPos(blockUse.targets().getFirst().targetPosition()))
					+ " readFreshnessRemainingToolCalls=" + worldReadLedger.freshnessRemaining(blockPos(blockUse.targets().getFirst().targetPosition()));
			}
			case PlannerToolCatalog.BREAK_BLOCKS -> {
				BlockBreakStepArgs blockBreak = parseBlockBreakArgs(args);
				for (BlockBreakStepArgs.Target target : blockBreak.targets()) {
					Optional<String> validationError = validateMineBlockIds(target.expectedBlockIds());
					if (validationError.isPresent()) {
						return CompletableFuture.completedFuture("TOOL_ERROR: break_blocks " + validationError.get());
					}
				}
				for (BlockBreakStepArgs.Target target : blockBreak.targets()) {
					BlockPos targetPos = blockPos(target.position());
					if (!worldReadLedger.isFresh(targetPos)) {
						return CompletableFuture.completedFuture(guardedModificationNeedsInspect(PlannerToolCatalog.BREAK_BLOCKS, blockBreak.targets().stream().map(item -> blockPos(item.position())).toList()));
					}
				}
				proposal = ActiveJobProposal.breakBlocks(blockBreak);
				expectedTaskType = WorldTaskType.BREAK_BLOCKS;
				expectedStepKind = LedgerStepKind.BREAK_BLOCKS;
				details = "targets=" + blockBreak.targets().size()
					+ " firstTargetPos=" + compactPos(blockPos(blockBreak.targets().getFirst().position()));
			}
			case PlannerToolCatalog.TEND_CROPS -> {
				var cropTending = ai.moeru.airicraft.agent.tasks.CropTendingStepArgs.parse(args);
				proposal = ActiveJobProposal.tendCrops(cropTending);
				expectedTaskType = WorldTaskType.TEND_CROPS;
				expectedStepKind = LedgerStepKind.USE_BLOCK;
				details = "plot=" + cropTending;
			}
			default -> {
				return CompletableFuture.completedFuture("TOOL_ERROR: unknown_tool " + toolCall.name());
			}
		}

		applyPlannerJobTool(proposal);
		Optional<WorldTaskRequest> activeTask = activeJobRuntime.activeTaskRequest();
		if (activeTask.isEmpty() || activeTask.get().type() != expectedTaskType) {
			return CompletableFuture.completedFuture("TOOL_ERROR: " + toolName + " task_not_started");
		}

		CompletableFuture<String> future = new CompletableFuture<>();
		PendingBlockModificationToolResult replacement = pendingBlockModificationToolResult.getAndSet(new PendingBlockModificationToolResult(
			activeTask.get().taskId(),
			toolName,
			expectedTaskType,
			expectedStepKind,
			details,
			tickCount,
			future
		));
		if (replacement != null) {
			replacement.future().complete(PendingBlockModificationStopReason.SUPERSEDED.result(replacement));
		}
		return future;
	}

	private void emitPlannerToolNarration(PlannerToolCall toolCall) {
		if (toolCall == null || toolCall.narration() == null || toolCall.narration().isBlank()) {
			return;
		}
		chatService.send(MinecraftClient.getInstance(), toolCall.narration(), tickCount);
	}

	private void beforePlannerToolExecution(PlannerToolCall toolCall) {
		worldReadLedger.advanceToolCall();
	}

	private String guardedModificationNeedsInspect(String toolName, List<BlockPos> targets) {
		return inspectMissingModificationTargets(toolName, targets, worldReadLedger,
			args -> guardedWorldQueryService.inspectWorldDetailed(args).join());
	}

	static String inspectMissingModificationTargets(String toolName, List<BlockPos> targets, WorldReadLedger ledger,
		java.util.function.Function<JsonObject, CurrentWorldQueryService.WorldQueryResult> query) {
		StringBuilder feedback = new StringBuilder("Tool result for " + toolName + ": blocked reason=target_not_inspected"
			+ " targets=" + targets.size() + ". Runtime converted this request to inspect_world first.");
		// A batch needs exact target facts, not a repeated neighborhood around each target.
		int radius = targets.size() == 1 ? 1 : 0;
		for (BlockPos targetPos : targets) {
			if (ledger.isFresh(targetPos)) continue;
			JsonObject inspectArgs = new JsonObject();
			inspectArgs.addProperty("mode", "inspect_area");
			inspectArgs.addProperty("scope", "center");
			inspectArgs.addProperty("x", targetPos.getX());
			inspectArgs.addProperty("y", targetPos.getY());
			inspectArgs.addProperty("z", targetPos.getZ());
			inspectArgs.addProperty("horizontalRadius", radius);
			inspectArgs.addProperty("verticalRadius", radius);
			CurrentWorldQueryService.WorldQueryResult result = query.apply(inspectArgs);
			ledger.recordObserved(result.observedPositions());
			feedback.append("\ntargetPos=").append(compactPos(targetPos)).append("\n").append(result.text());
		}
		long unread = targets.stream().filter(pos -> !ledger.isFresh(pos)).count();
		return feedback + "\ninspectedTargets=" + (targets.size() - unread) + " uninspectedTargets=" + unread
			+ "\nReview the observations and any read errors. Call " + toolName + " again if you still want to modify these targets.";
	}

	private static BlockPos blockPos(GoalPosition position) {
		return new BlockPos(position.x(), position.y(), position.z());
	}

	private static String compactPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private void applyPlannerJobTool(ActiveJobProposal proposal) {
		requireLivingPlayerForAction();
		Optional<GoalSnapshot> previousGoal = activeGoal();
		DialogueResponse response = new DialogueResponse(
			"",
			new DialogueIntent(DialogueIntentType.JOB_UPDATE, proposal),
			tickCount
		);
		applyTaskIntent(response, currentWorldEvidence(MinecraftClient.getInstance()), "planner_tool");
		recordPlannerOutcome(response, previousGoal, activeGoal());
		drainEventPipeline();
	}

	private void ensureGiveTargetNearby(String targetPlayer) {
		NearbyPlayerSnapshot target = nearbyPlayerTracker.findByName(targetPlayer)
			.orElseThrow(() -> new IllegalStateException("target_not_nearby"));
		Vec3d selfPos = currentPlayerPosition();
		Vec3d targetPos = new Vec3d(target.x(), target.y(), target.z());
		if (selfPos.squaredDistanceTo(targetPos) > 16.0D) {
			throw new IllegalStateException("target_not_nearby");
		}
	}

	private static EntitySelector parseEntitySelectorArgs(JsonObject object) {
		return new EntitySelector(
			stringArg(object, "uuid").orElse(null),
			stringArg(object, "name").orElse(null),
			stringArg(object, "entityTypeId").orElse(null)
		);
	}

	private static String describeEntitySelector(EntitySelector selector) {
		if (selector == null) {
			return "selector=missing";
		}
		if (selector.uuid() != null) {
			return "uuid=" + NearbyEntityService.plannerUuidToken(selector.uuid());
		}
		if (selector.name() != null) {
			return "name=" + selector.name();
		}
		return "entityTypeId=" + selector.entityTypeId();
	}

	private Vec3d currentPlayerPosition() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.player != null) {
			return new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
		}
		WorldEvidence evidence = currentWorldEvidence(client);
		return new Vec3d(evidence.x(), evidence.y(), evidence.z());
	}

	private void applyPlannerClearGoalTool() {
		if (actionGraphCoordinator.hasNonterminal()) {
			actionGraphCoordinator.cancelAll("planner_tool_cleared", tickCount);
			pendingActionGraphTerminalEvent = null;
		}
		survivalReflexRuntime.discardHold("goal_cleared", tickCount);
		processSurvivalReflexEvents();
		Optional<GoalSnapshot> previousGoal = activeGoal();
		DialogueResponse response = new DialogueResponse(
			"",
			new DialogueIntent(DialogueIntentType.CLEAR_GOAL, null, null),
			tickCount
		);
		applyTaskIntent(response, currentWorldEvidence(MinecraftClient.getInstance()), "planner_tool");
		recordPlannerOutcome(response, previousGoal, activeGoal());
		drainEventPipeline();
	}

	private void applyTaskIntent(DialogueResponse response, WorldEvidence worldEvidence) {
		applyTaskIntent(response, worldEvidence, "planner_response");
	}

	private void applyTaskIntent(DialogueResponse response, WorldEvidence worldEvidence, String source) {
		if (response == null || response.intent() == null || response.intent().type() == null) {
			return;
		}
		if (actionGraphCoordinator.hasNonterminal() && legacyIntentWouldMutateGraphBoundary(response.intent().type())) {
			eventBuffer.append(tickCount, "player.action_rejected", Map.of(
				"reason", "active_action_graph_in_progress",
				"intentType", response.intent().type().name(),
				"source", source == null || source.isBlank() ? "planner_response" : source
			));
			return;
		}
		if (sessionSnapshot.requiresRespawn() && intentRequiresLivingPlayer(response.intent().type())) {
			eventBuffer.append(tickCount, "player.action_rejected", Map.of(
				"reason", "player_dead",
				"intentType", response.intent().type().name(),
				"source", source == null || source.isBlank() ? "planner_response" : source
			));
			return;
		}
		if (survivalReflexRuntime.snapshot().state() == SurvivalReflexState.ACTIVE
			&& intentRequiresLivingPlayer(response.intent().type())) {
			eventBuffer.append(tickCount, "player.action_rejected", Map.of(
				"reason", "reflex_active",
				"intentType", response.intent().type().name(),
				"source", source == null || source.isBlank() ? "planner_response" : source
			));
			return;
		}
		boolean releasedForReplacement = false;
		if (survivalReflexRuntime.snapshot().state() == SurvivalReflexState.AWAITING_PLANNER
			&& intentRequiresLivingPlayer(response.intent().type())) {
			releaseSafetyHoldForReplacement("planner_replaced_task");
			releasedForReplacement = true;
		}
		if (response.intent().type() == DialogueIntentType.CANCEL_TASK || response.intent().type() == DialogueIntentType.CLEAR_GOAL) {
			if (actionGraphCoordinator.hasNonterminal()) {
				actionGraphCoordinator.cancelAll("planner_cancelled", tickCount);
				pendingActionGraphTerminalEvent = null;
			}
			survivalReflexRuntime.discardHold("planner_cancelled", tickCount);
			processSurvivalReflexEvents();
		}
		int currentResourceCount = currentResourceCountForIntent(worldEvidence, response.intent());
		if (!releasedForReplacement && directPlannerIntentWouldPreemptActiveTask(response.intent())) {
			return;
		}
		activeJobRuntime.applyPlannerResponse(response, currentResourceCount, source == null || source.isBlank() ? "planner_response" : source, response.tick());
		TaskSnapshot projectedTaskSnapshot = activeJobRuntime.taskSnapshot();
		if (isSemanticTaskSnapshot(projectedTaskSnapshot)) {
			taskSnapshot = projectedTaskSnapshot;
			missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
		}
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
	}

	private int currentTaskResourceCount(MinecraftClient client) {
		return currentTaskResourceCount(client, taskSnapshot.spec());
	}

	private int currentTaskResourceCount(MinecraftClient client, TaskSpec spec) {
		if (client == null || client.player == null || spec == null) {
			return 0;
		}
		java.util.ArrayList<net.minecraft.item.ItemStack> stacks = new java.util.ArrayList<>();
		for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
			stacks.add(client.player.getInventory().getStack(slot));
		}
		return inventoryResourceCounter.count(stacks, spec.resourceKind());
	}

	private int currentResourceCountForProposal(WorldEvidence worldEvidence, ActiveJobProposal proposal) {
		if (worldEvidence == null || proposal == null || proposal.taskSpec() == null || proposal.taskSpec().type() != TaskType.COLLECT_RESOURCE) {
			return 0;
		}
		return worldEvidence.inventoryCounts().getOrDefault(proposal.taskSpec().resourceKind(), 0);
	}

	private int currentResourceCountForIntent(WorldEvidence worldEvidence, DialogueIntent intent) {
		if (worldEvidence == null) {
			return currentTaskResourceCount(MinecraftClient.getInstance());
		}
		if (intent == null) {
			return 0;
		}
		if (intent.activeJob() != null) {
			return currentResourceCountForProposal(worldEvidence, intent.activeJob());
		}
		if (intent.taskSpec() != null && intent.taskSpec().type() == TaskType.COLLECT_RESOURCE) {
			return worldEvidence.inventoryCounts().getOrDefault(intent.taskSpec().resourceKind(), 0);
		}
		return 0;
	}

	private static Optional<String> stringArg(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) {
			return Optional.empty();
		}
		try {
			String value = object.get(key).getAsString();
			return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static ActionGoal parseActionGoalArgs(JsonObject args) {
		String kind = stringArg(args, "kind").orElseThrow(() -> new IllegalArgumentException("kind is required"));
		if ("inventory_item".equals(kind) || "crafting_output".equals(kind) || "smelting_output".equals(kind)) {
			return ActionGoal.inventoryItem(
				stringArg(args, "itemId").orElseThrow(() -> new IllegalArgumentException("itemId is required")),
				intArg(args, "quantity").orElseThrow(() -> new IllegalArgumentException("quantity is required"))
			);
		}
		if ("resource_collection".equals(kind)) {
			TaskResourceKind resourceKind = resourceKindArg(args, "resourceKind");
			ResourceGatheringCatalog.entry(resourceKind)
				.orElseThrow(() -> new IllegalArgumentException("unsupported_resource_kind " + resourceKind));
			return ActionGoal.resourceCollection(
				resourceKind.name(),
				intArg(args, "quantity").orElseThrow(() -> new IllegalArgumentException("quantity is required"))
			);
		}
		throw new IllegalArgumentException("unsupported_action_goal_kind " + kind + ". Supported executable goal kinds: inventory_item, crafting_output, smelting_output, resource_collection");
	}

	private static Optional<Integer> intArg(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) {
			return Optional.empty();
		}
		try {
			return Optional.of(object.get(key).getAsInt());
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static BlockPlacementStepArgs parseBlockPlacementArgs(JsonObject args) {
		String itemId = stringArg(args, "itemId").orElseThrow(() -> new IllegalArgumentException("itemId is required"));
		String rootFacePreference = stringArg(args, "facePreference").orElse("auto");
		String rootRequiredTargetMaterial = stringArg(args, "requireCurrentTargetMaterial").orElse("air_or_replaceable");
		if (hasTargets(args)) {
			ArrayList<BlockPlacementStepArgs.Target> targets = new ArrayList<>();
			for (JsonElement element : args.getAsJsonArray("targets")) {
				if (!element.isJsonObject()) {
					throw new IllegalArgumentException("targets must contain objects");
				}
				JsonObject target = element.getAsJsonObject();
				targets.add(new BlockPlacementStepArgs.Target(
					parseTargetPosition(target),
					stringArg(target, "facePreference").orElse(rootFacePreference),
					stringArg(target, "requireCurrentTargetMaterial").orElse(rootRequiredTargetMaterial)
				));
			}
			return new BlockPlacementStepArgs(itemId, targets);
		}
		return new BlockPlacementStepArgs(
			itemId,
			parseTargetPosition(args),
			rootFacePreference,
			rootRequiredTargetMaterial
		);
	}

	private static BlockUseStepArgs parseBlockUseArgs(JsonObject args) {
		String rootFacePreference = stringArg(args, "facePreference").orElse("auto");
		List<String> rootExpectedSupportBlockIds = args != null && args.has("expectedSupportBlockIds") && !args.get("expectedSupportBlockIds").isJsonNull()
			? stringArrayArg(args, "expectedSupportBlockIds")
			: List.of();
		String rootExpectedTargetMaterial = stringArg(args, "expectedTargetMaterial").orElse(null);
		if (hasTargets(args)) {
			ArrayList<BlockUseStepArgs.Target> targets = new ArrayList<>();
			for (JsonElement element : args.getAsJsonArray("targets")) {
				if (!element.isJsonObject()) {
					throw new IllegalArgumentException("targets must contain objects");
				}
				JsonObject target = element.getAsJsonObject();
				List<String> expectedSupportBlockIds = target.has("expectedSupportBlockIds") && !target.get("expectedSupportBlockIds").isJsonNull()
					? stringArrayArg(target, "expectedSupportBlockIds")
					: rootExpectedSupportBlockIds;
				targets.add(new BlockUseStepArgs.Target(
					parseTargetPosition(target),
					stringArg(target, "facePreference").orElse(rootFacePreference),
					expectedSupportBlockIds,
					stringArg(target, "expectedTargetMaterial").orElse(rootExpectedTargetMaterial)
				));
			}
			return new BlockUseStepArgs(stringArg(args, "itemId").orElse(null), targets);
		}
		return new BlockUseStepArgs(
			stringArg(args, "itemId").orElse(null),
			parseTargetPosition(args),
			rootFacePreference,
			rootExpectedSupportBlockIds,
			rootExpectedTargetMaterial
		);
	}

	private static BlockBreakStepArgs parseBlockBreakArgs(JsonObject args) {
		if (args == null || !args.has("targets") || !args.get("targets").isJsonArray()) {
			throw new IllegalArgumentException("targets is required");
		}
		ArrayList<BlockBreakStepArgs.Target> targets = new ArrayList<>();
		for (JsonElement element : args.getAsJsonArray("targets")) {
			if (!element.isJsonObject()) {
				throw new IllegalArgumentException("targets must contain objects");
			}
			JsonObject target = element.getAsJsonObject();
			targets.add(new BlockBreakStepArgs.Target(
				new GoalPosition(
					intArg(target, "x").orElseThrow(() -> new IllegalArgumentException("x is required")),
					intArg(target, "y").orElseThrow(() -> new IllegalArgumentException("y is required")),
					intArg(target, "z").orElseThrow(() -> new IllegalArgumentException("z is required")),
					true
				),
				stringArrayArg(target, "expectedBlockIds")
			));
		}
		return new BlockBreakStepArgs(targets);
	}

	private static boolean hasTargets(JsonObject args) {
		return args != null && args.has("targets") && args.get("targets").isJsonArray();
	}

	private static GoalPosition parseTargetPosition(JsonObject object) {
		return new GoalPosition(
			intArg(object, "x").orElseThrow(() -> new IllegalArgumentException("x is required")),
			intArg(object, "y").orElseThrow(() -> new IllegalArgumentException("y is required")),
			intArg(object, "z").orElseThrow(() -> new IllegalArgumentException("z is required")),
			true
		);
	}

	private static Optional<Boolean> booleanArg(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) {
			return Optional.empty();
		}
		try {
			return Optional.of(object.get(key).getAsBoolean());
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static List<String> stringArrayArg(JsonObject object, String key) {
		if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
			throw new IllegalArgumentException(key + " is required");
		}
		JsonArray array = object.getAsJsonArray(key);
		ArrayList<String> values = new ArrayList<>(array.size());
		for (JsonElement element : array) {
			if (!element.isJsonPrimitive()) {
				throw new IllegalArgumentException(key + " must contain strings");
			}
			String value = element.getAsString();
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException(key + " must contain non-empty strings");
			}
			values.add(value);
		}
		if (values.isEmpty()) {
			throw new IllegalArgumentException(key + " must not be empty");
		}
		return List.copyOf(values);
	}

	private static AcquisitionConstraints acquisitionConstraints(JsonObject args) {
		JsonObject value = args.has("constraints") ? args.getAsJsonObject("constraints") : new JsonObject();
		GoalPosition center = null;
		if (value.has("center")) {
			JsonObject pos = value.getAsJsonObject("center");
			center = new GoalPosition(pos.get("x").getAsInt(), pos.get("y").getAsInt(), pos.get("z").getAsInt(), true);
		}
		else {
			var client = MinecraftClient.getInstance();
			var player = client == null ? null : client.player;
			if (player != null) center = new GoalPosition(player.getBlockX(), player.getBlockY(), player.getBlockZ(), true);
		}
		return new AcquisitionConstraints(center,
			intArg(value, "radius").orElse(16), intArg(value, "verticalRadius").orElse(16),
			booleanArg(value, "surfaceOnly").orElse(false), booleanArg(value, "visibleOnly").orElse(false));
	}

	private GoalMineSpec goalMineSpec(List<String> blockIds, int quantity) {
		BlockAcquisitionIndex index = blockAcquisitions();
		List<String> matchingItemIds = index.matchingOutputItemIds(blockIds).stream().sorted().toList();
		return new GoalMineSpec(
			blockIds,
			quantity,
			matchingItemIds.isEmpty() ? blockIds : matchingItemIds,
			List.of()
		);
	}

	private BlockAcquisitionIndex blockAcquisitions() {
		return blockAcquisitionsOverrideForTests == null
			? blockAcquisitionKnowledgeService.snapshot().index()
			: blockAcquisitionsOverrideForTests;
	}

	private static Optional<String> validateMineBlockIds(List<String> blockIds) {
		if (blockIds == null || blockIds.isEmpty()) {
			return Optional.of("missing_block_id");
		}
		for (String blockId : blockIds) {
			Optional<String> error = validateMineBlockId(blockId);
			if (error.isPresent()) {
				return error;
			}
		}
		return Optional.empty();
	}

	private Optional<String> miningIlluminationError(JsonObject args, GoalMineSpec mineSpec) {
		boolean allowUnilluminated = booleanArg(args, "allowUnilluminated").orElse(false);
		int torchCount = inventoryItemCount("minecraft:torch");
		MiningIlluminationPreflight.Result prediction = MiningIlluminationPreflight.inspect(
			MinecraftClient.getInstance(),
			mineSpec,
			7
		);
		MiningIlluminationPreflight.Admission admission = MiningIlluminationPreflight.admit(
			prediction,
			torchCount,
			allowUnilluminated
		);
		if (admission.allowed()) {
			return Optional.empty();
		}
		return Optional.of("insufficient_illumination reason=" + prediction.reason()
			+ " torchCount=" + torchCount
			+ ". Acquire minecraft:torch and retry. Configure lighting policy if automatic placement is desired."
			+ " To deliberately accept unilluminated mining, retry with allowUnilluminated=true.");
	}

	private static Optional<String> validateFillerBlockIds(List<String> blockIds) {
		if (blockIds == null || blockIds.isEmpty()) {
			return Optional.of("missing_filler_block_id");
		}
		for (String blockId : blockIds) {
			Optional<String> error = validateMineBlockId(blockId);
			if (error.isPresent()) {
				return Optional.of(error.get().replace("missing_block_id", "missing_filler_block_id"));
			}
		}
		return Optional.empty();
	}

	private static Optional<String> validateMineBlockId(String blockId) {
		if (blockId == null || blockId.isBlank()) {
			return Optional.of("missing_block_id");
		}
		Identifier identifier;
		try {
			identifier = Identifier.of(blockId);
		}
		catch (RuntimeException exception) {
			return Optional.of("invalid_block_id " + blockId);
		}
		if (KNOWN_NON_BLOCK_MINE_ITEM_IDS.contains(blockId)) {
			return Optional.of("invalid_block_id " + blockId + " is an item id, not a block id. Use mineable block ids such as minecraft:iron_ore; inspect_inventory itemCounts are item ids.");
		}
		if (!minecraftRegistriesAvailableForToolValidation()) {
			return Optional.empty();
		}
		try {
			if (Registries.BLOCK.getOptionalValue(identifier).isPresent()) {
				return Optional.empty();
			}
			if (Registries.ITEM.getOptionalValue(identifier).isPresent()) {
				return Optional.of("invalid_block_id " + blockId + " is an item id, not a block id. Use mineable block ids such as minecraft:iron_ore; inspect_inventory itemCounts are item ids.");
			}
			return Optional.of("invalid_block_id " + blockId + " is not a registered block id.");
		}
		catch (RuntimeException | LinkageError ignored) {
			return Optional.empty();
		}
	}

	private static boolean minecraftRegistriesAvailableForToolValidation() {
		try {
			return MinecraftClient.getInstance() != null;
		}
		catch (RuntimeException | LinkageError ignored) {
			return false;
		}
	}

	private static TaskResourceKind resourceKindArg(JsonObject object, String key) {
		String value = stringArg(object, key).orElseThrow(() -> new IllegalArgumentException(key + " is required"));
		try {
			return TaskResourceKind.valueOf(value.toUpperCase(Locale.ROOT));
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("unsupported resourceKind " + value, exception);
		}
	}

	private static EventPolicyChanges parseToolEventPolicyChanges(JsonObject object) {
		if (object == null) {
			return new EventPolicyChanges(false, List.of(), List.of());
		}
		boolean clearAll = booleanArg(object, "clearAll").orElse(false);
		List<String> removeRuleIds = object.has("removeRuleIds") && object.get("removeRuleIds").isJsonArray()
			? stringArrayAllowEmptyArg(object, "removeRuleIds")
			: List.of();
		ArrayList<EventPolicyRuleUpsert> upserts = new ArrayList<>();
		if (object.has("upserts") && object.get("upserts").isJsonArray()) {
			for (JsonElement element : object.getAsJsonArray("upserts")) {
				if (!element.isJsonObject()) {
					throw new IllegalArgumentException("upserts must contain objects");
				}
				JsonObject upsert = element.getAsJsonObject();
				upserts.add(new EventPolicyRuleUpsert(
					stringArg(upsert, "ruleId").orElse(null),
					stringArg(upsert, "effect").orElse(null),
					parseToolEventPolicyMatch(upsert.has("match") && upsert.get("match").isJsonObject() ? upsert.getAsJsonObject("match") : null),
					stringArg(upsert, "reason").orElse(null)
				));
			}
		}
		return new EventPolicyChanges(clearAll, removeRuleIds, upserts);
	}

	private static List<String> stringArrayAllowEmptyArg(JsonObject object, String key) {
		JsonArray array = object.getAsJsonArray(key);
		ArrayList<String> values = new ArrayList<>(array.size());
		for (JsonElement element : array) {
			if (!element.isJsonPrimitive()) {
				throw new IllegalArgumentException(key + " must contain strings");
			}
			String value = element.getAsString();
			if (value != null && !value.isBlank()) {
				values.add(value);
			}
		}
		return List.copyOf(values);
	}

	private static EventPolicyMatch parseToolEventPolicyMatch(JsonObject object) {
		if (object == null) {
			return null;
		}
		return new EventPolicyMatch(
			stringArg(object, "eventType").orElse(null),
			stringArg(object, "player").orElse(null),
			stringArg(object, "speaker").orElse(null),
			stringArg(object, "actor").orElse(null),
			stringArg(object, "itemId").orElse(null),
			stringArg(object, "damageTypeId").orElse(null),
			stringArg(object, "attackerName").orElse(null)
		);
	}

	private WorldEvidence currentWorldEvidence(MinecraftClient client) {
		if (client == null || client.player == null) {
			return new WorldEvidence(Map.of(), Map.of(), Map.of(), null, 0, 0, 0, null, tickCount);
		}

		java.util.ArrayList<net.minecraft.item.ItemStack> stacks = new java.util.ArrayList<>();
		for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
			stacks.add(client.player.getInventory().getStack(slot));
		}

		java.util.EnumMap<ai.moeru.airicraft.agent.tasks.TaskResourceKind, Integer> resourceCounts =
			new java.util.EnumMap<>(ai.moeru.airicraft.agent.tasks.TaskResourceKind.class);
		for (ai.moeru.airicraft.agent.tasks.TaskResourceKind kind : ai.moeru.airicraft.agent.tasks.TaskResourceKind.values()) {
			resourceCounts.put(kind, inventoryResourceCounter.count(stacks, kind));
		}

		String equippedItemId = Registries.ITEM.getId(client.player.getMainHandStack().getItem()).toString();
		int selectedHotbarSlot = client.player.getInventory().getSelectedSlot();
		BlockPos origin = client.player.getBlockPos();
		Map<String, Integer> itemCounts = inventoryItemCounter.count(client.player.getInventory());
		CraftingOpportunitySnapshot crafting = CraftingOpportunityResolver.inspect(client.player);
		SmeltingOpportunitySnapshot smelting = smeltingPlannerService.inspectOpportunities(client, smeltingProcessManager, tickCount);
		return new WorldEvidence(
			resourceCounts,
			itemCounts,
			collectNearbyBlocks(client, origin),
			crafting.availableCrafts(),
			crafting.knownCrafts(),
			smelting.availableSmelts(),
			smelting.knownSmelts(),
			client.world == null ? null : client.world.getRegistryKey().getValue().toString(),
			origin.getX(),
			origin.getY(),
			origin.getZ(),
			equippedItemId,
			selectedHotbarSlot,
			hotbarItems(client.player.getInventory()),
			tickCount
		);
	}

	private static java.util.List<String> hotbarItems(net.minecraft.entity.player.PlayerInventory inventory) {
		java.util.ArrayList<String> items = new java.util.ArrayList<>();
		if (inventory == null) {
			return java.util.List.of();
		}
		for (int slot = 0; slot < 9; slot++) {
			net.minecraft.item.ItemStack stack = inventory.getStack(slot);
			if (stack == null || stack.isEmpty()) {
				items.add(slot + "=empty");
			}
			else {
				items.add(slot + "=" + Registries.ITEM.getId(stack.getItem()) + "x" + stack.getCount());
			}
		}
		return java.util.List.copyOf(items);
	}

	private static Map<String, Integer> resourceCountsForGraph(Map<TaskResourceKind, Integer> counts) {
		if (counts == null || counts.isEmpty()) {
			return Map.of();
		}
		java.util.LinkedHashMap<String, Integer> copy = new java.util.LinkedHashMap<>();
		for (Map.Entry<TaskResourceKind, Integer> entry : counts.entrySet()) {
			if (entry.getKey() != null && entry.getValue() != null) {
				copy.put(entry.getKey().name(), Math.max(0, entry.getValue()));
			}
		}
		return Map.copyOf(copy);
	}


	private Map<String, Integer> collectNearbyBlocks(MinecraftClient client, BlockPos origin) {
		if (client == null || client.world == null) {
			clearNearbyBlockSnapshot();
			return Map.of();
		}
		if (nearbyHarvestableBlockSnapshot != null && canReuseNearbyBlockSnapshot(client.world, origin)) {
			return nearbyBlockSnapshot;
		}
		java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
		java.util.HashMap<String, Integer> harvestable = new java.util.HashMap<>();
		for (int dx = -NEARBY_BLOCK_HORIZONTAL_RADIUS; dx <= NEARBY_BLOCK_HORIZONTAL_RADIUS; dx++) {
			for (int dy = -NEARBY_BLOCK_VERTICAL_RADIUS; dy <= NEARBY_BLOCK_VERTICAL_RADIUS; dy++) {
				for (int dz = -NEARBY_BLOCK_HORIZONTAL_RADIUS; dz <= NEARBY_BLOCK_HORIZONTAL_RADIUS; dz++) {
					BlockPos pos = origin.add(dx, dy, dz);
					if (!client.world.isChunkLoaded(pos)) {
						continue;
					}
					BlockState blockState = client.world.getBlockState(pos);
					String blockId = Registries.BLOCK.getId(blockState.getBlock()).toString();
					counts.merge(blockId, 1, Integer::sum);
					if (ai.moeru.airicraft.agent.tasks.HarvestableBlocks.ready(blockState)
						&& !ai.moeru.airicraft.agent.memory.WorldPlacePreservation.contains(client.world, pos)) harvestable.merge(blockId, 1, Integer::sum);
				}
			}
		}
		nearbyBlockSnapshotWorld = client.world;
		nearbyBlockSnapshotOrigin = origin.toImmutable();
		nearbyBlockSnapshotTick = tickCount;
		nearbyBlockSnapshot = Map.copyOf(counts);
		nearbyHarvestableBlockSnapshot = Map.copyOf(harvestable);
		return nearbyBlockSnapshot;
	}

	private boolean canReuseNearbyBlockSnapshot(Object world, BlockPos origin) {
		if (nearbyBlockSnapshotWorld != world || nearbyBlockSnapshotOrigin == null || origin == null) {
			return false;
		}
		long age = tickCount - nearbyBlockSnapshotTick;
		if (age < 0L || age >= NEARBY_BLOCK_SCAN_INTERVAL_TICKS) {
			return false;
		}
		long dx = (long) origin.getX() - nearbyBlockSnapshotOrigin.getX();
		long dy = (long) origin.getY() - nearbyBlockSnapshotOrigin.getY();
		long dz = (long) origin.getZ() - nearbyBlockSnapshotOrigin.getZ();
		long movementThresholdSquared = (long) NEARBY_BLOCK_SCAN_MOVEMENT_THRESHOLD * NEARBY_BLOCK_SCAN_MOVEMENT_THRESHOLD;
		return dx * dx + dy * dy + dz * dz <= movementThresholdSquared;
	}

	private void clearNearbyBlockSnapshot() {
		nearbyBlockSnapshotWorld = null;
		nearbyBlockSnapshotOrigin = null;
		nearbyBlockSnapshotTick = Long.MIN_VALUE;
		nearbyBlockSnapshot = Map.of();
		nearbyHarvestableBlockSnapshot = null;
	}

	public VisionDescription describeCapturedView(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) throws LlmBackendException {
		return visionService.describe(screenshot, prompt);
	}

	private boolean proactiveSocialModeEnabled() {
		return proactiveSocialModeOverride != null
			? proactiveSocialModeOverride.booleanValue()
			: airicraftConfig.enableProactiveSocialMode();
	}

	private void setProactiveSocialModeOverride(Boolean enabled) {
		proactiveSocialModeOverride = enabled;
	}

	private boolean playerChatWithinConfiguredDistance(String senderName) {
		if (airicraftConfig.socialChatDistanceUnlimited()) {
			return true;
		}

		Optional<NearbyPlayerSnapshot> nearbyPlayer = nearbyPlayerTracker.findByName(senderName);
		if (nearbyPlayer.isEmpty()) {
			return false;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return false;
		}

		Vec3d selfPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
		Vec3d senderPos = new Vec3d(nearbyPlayer.get().x(), nearbyPlayer.get().y(), nearbyPlayer.get().z());
		double maxDistance = airicraftConfig.socialChatMaxDistanceBlocks();
		return selfPos.squaredDistanceTo(senderPos) <= maxDistance * maxDistance;
	}

	private static double resolveNearbyPlayerTrackingRadius(AiricraftConfig airicraftConfig) {
		if (airicraftConfig.socialChatDistanceUnlimited()) {
			return 32.0D;
		}
		return Math.max(32.0D, airicraftConfig.socialChatMaxDistanceBlocks());
	}

	private String localPlayerName() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return client != null && client.getSession() != null ? client.getSession().getUsername() : null;
		}
		Text playerName = client.player.getName();
		return playerName == null ? null : playerName.getString();
	}

	static boolean isAgentChatEcho(
		String senderName,
		String plainTextMessage,
		String localPlayerName,
		long currentTick,
		ChatService chatService
	) {
		if (chatService == null) {
			return false;
		}
		return isAgentChatEchoSender(senderName, plainTextMessage, localPlayerName)
			&& chatService.isRecentSentChat(plainTextMessage, currentTick, CHAT_ECHO_SUPPRESSION_TICKS);
	}

	static boolean isAgentChatEcho(
		String senderName,
		String plainTextMessage,
		String localPlayerName,
		String lastAgentChatText,
		long currentTick,
		long lastAgentChatTick
	) {
		if (!isAgentChatEchoSender(senderName, plainTextMessage, localPlayerName)) {
			return false;
		}
		if (lastAgentChatText == null || !plainTextMessage.equals(lastAgentChatText)) {
			return false;
		}
		if (lastAgentChatTick < 0L || currentTick < lastAgentChatTick) {
			return false;
		}
		return currentTick - lastAgentChatTick <= CHAT_ECHO_SUPPRESSION_TICKS;
	}

	private static boolean isAgentChatEchoSender(String senderName, String plainTextMessage, String localPlayerName) {
		if (senderName == null || plainTextMessage == null || localPlayerName == null) {
			return false;
		}
		return senderName.equals(localPlayerName);
	}

	static boolean isLocalControllerMessage(String senderName, String localPlayerName) {
		if (senderName == null || localPlayerName == null) {
			return false;
		}
		return senderName.equals(localPlayerName);
	}

	private boolean isDuplicateSystemChat(String plainTextMessage, long currentTick) {
		if (plainTextMessage == null || plainTextMessage.isBlank()) {
			return true;
		}
		boolean duplicate = currentTick == lastSystemChatTick && plainTextMessage.equals(lastSystemChatText);
		lastSystemChatTick = currentTick;
		lastSystemChatText = plainTextMessage;
		return duplicate;
	}

	private void forwardSyntheticPresenceMessage(String plainTextMessage) {
		if (!airicraftConfig.readSystemChatMessages()) {
			return;
		}
		if (isDuplicateSystemChat(plainTextMessage, tickCount)) {
			return;
		}

		chatIngestService.ingestSystemMessage(plainTextMessage, tickCount, eventBuffer);
		drainEventPipeline();
	}

	private boolean isLocalPlayer(UUID playerUuid, String playerName) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return false;
		}
		if (client.player != null && playerUuid.equals(client.player.getUuid())) {
			return true;
		}
		return client.getSession() != null && playerName.equals(client.getSession().getUsername());
	}

	private void recordPlannerOutcome(
		DialogueResponse response,
		Optional<GoalSnapshot> previousGoal,
		Optional<GoalSnapshot> currentGoal
	) {
		if (response == null || response.intent() == null || response.intent().type() == null) {
			return;
		}
		if (sessionSnapshot.requiresRespawn() && intentRequiresLivingPlayer(response.intent().type())) {
			return;
		}

		java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("intentType", response.intent().type().name());
		if (response.intent().targetPlayer() != null && !response.intent().targetPlayer().isBlank()) {
			payload.put("targetPlayer", response.intent().targetPlayer());
		}
		if (response.intent().goalType() != null) {
			payload.put("goalType", response.intent().goalType().name());
		}
		if (response.intent().activeJob() != null) {
			payload.put("activeJobType", response.intent().activeJob().type().name());
		}
		if (response.intent().taskLedger() != null) {
			payload.put("missionId", response.intent().taskLedger().missionId());
			payload.put("missionType", response.intent().taskLedger().missionType().name());
			if (response.intent().taskLedger().activeStepId() != null) {
				payload.put("activeStepId", response.intent().taskLedger().activeStepId());
			}
		}
		if (response.text() != null && !response.text().isBlank()) {
			payload.put("replyText", response.text());
		}
		eventBuffer.append(tickCount, "planner.response_applied", payload);

		if (isDirectGoalIntent(response.intent()) && currentGoal.isPresent()) {
			java.util.LinkedHashMap<String, Object> goalPayload = new java.util.LinkedHashMap<>();
			goalPayload.put("goalType", currentGoal.get().type().name());
			if (currentGoal.get().targetPlayer() != null && !currentGoal.get().targetPlayer().isBlank()) {
				goalPayload.put("targetPlayer", currentGoal.get().targetPlayer());
			}
			goalPayload.put("source", currentGoal.get().source());
			eventBuffer.append(tickCount, "planner.goal_set", goalPayload);
			return;
		}

		if (response.intent().type() == DialogueIntentType.CLEAR_GOAL && currentGoal.isEmpty()) {
			java.util.LinkedHashMap<String, Object> goalPayload = new java.util.LinkedHashMap<>();
			if (previousGoal.isPresent()) {
				goalPayload.put("goalType", previousGoal.get().type().name());
				if (previousGoal.get().targetPlayer() != null && !previousGoal.get().targetPlayer().isBlank()) {
					goalPayload.put("targetPlayer", previousGoal.get().targetPlayer());
				}
				goalPayload.put("source", previousGoal.get().source());
			}
			else {
				goalPayload.put("source", "planner_response");
				goalPayload.put("alreadyClear", true);
			}
			eventBuffer.append(tickCount, "planner.goal_cleared", goalPayload);
		}
	}

	private void drainEventPipeline() {
		List<ai.moeru.airicraft.agent.llm.PlannerTrigger> triggers = eventPipeline.drain(this::createPlannerTrigger);
		if (triggers.isEmpty()) {
			return;
		}
		idleIdeaScheduler.recordActivity();
		String primaryInteractionPlayer = primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null);
		Optional<GoalSnapshot> activeGoal = activeGoal();
		for (ai.moeru.airicraft.agent.llm.PlannerTrigger trigger : triggers) {
			dialogueRuntime.onPlannerTrigger(
				trigger,
				sessionSnapshot,
				primaryInteractionPlayer,
				activeGoal,
				taskSnapshot,
				missionExecutionSnapshot,
				plannerEventBuffer
			);
		}
	}

	private void maybeFireIdleIdeaTrigger(Optional<GoalSnapshot> activeGoal) {
		boolean awaitingSafetyDecision = survivalReflexRuntime.snapshot().state() == SurvivalReflexState.AWAITING_PLANNER;
		if (evaluationPlannerSuppressed
			|| (actionGraphCoordinator.hasNonterminal() && !awaitingSafetyDecision)
			|| !sessionSnapshot.companionActuationAllowed()
			|| !config.llm().isConfigured()) {
			idleIdeaScheduler.reset();
			return;
		}
		boolean jobIdle = isIdleForIdleIdeaScheduling(activeJobRuntime.current());
		if (dialogueRuntime.continuePlannerGoal(tickCount, jobIdle && activeGoal.isEmpty() && !actionGraphCoordinator.hasNonterminal(), sessionSnapshot,
			primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null),
			activeGoal, taskSnapshot, missionExecutionSnapshot, plannerEventBuffer)) {
			idleIdeaScheduler.reset();
			return;
		}
		long nowMs = System.currentTimeMillis();
		idleIdeaScheduler.tick(jobIdle, tickCount, nowMs).ifPresent(trigger -> {
			String primaryInteractionPlayer = primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null);
			dialogueRuntime.onPlannerTrigger(
				trigger,
				sessionSnapshot,
				primaryInteractionPlayer,
				activeGoal,
				taskSnapshot,
				missionExecutionSnapshot,
				plannerEventBuffer
			);
		});
	}

	static boolean isIdleForIdleIdeaScheduling(ActiveJob activeJob) {
		return activeJob == null || activeJob.isIdle() || activeJob.status().terminal();
	}

	private IdleIdeasConfig effectiveIdleIdeasConfig(IdleIdeasConfig idleIdeasConfig) {
		IdleIdeasConfig source = idleIdeasConfig == null ? IdleIdeasConfig.defaults() : idleIdeasConfig;
		AgentConfig.IdleConfig idle = config.idle();
		return new IdleIdeasConfig(
			source.enabled() && idle.automaticEnabled(),
			idle.initialDelaySeconds(),
			idle.cooldownSeconds(),
			source.ideas()
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createPlannerTrigger(SemanticEvent event, EventRoutingProfile profile) {
		String eventType = event.type();
		if (eventType == null) {
			return null;
		}
		if (evaluationPlannerSuppressed && suppressAutonomousPlannerTriggerAfterEvaluation(eventType)) {
			return null;
		}
		return switch (eventType) {
			case "social.player_spoke" -> createPlayerSpokeTrigger(event);
			case "social.player_addressed_agent" -> createAddressedChatTrigger(event);
			case "social.local_controller_spoke" -> createLocalControllerTrigger(event);
			case "social.system_message" -> createSystemTrigger(event);
			case "pickup.item_picked_up" -> createPickupTrigger(event);
			case "crafting.item_crafted" -> createCraftTrigger(event);
			case "combat.damage_taken" -> createDamageTrigger(event);
			case "player.physical" -> createPhysicalTrigger(event);
			case "reflex.resolved" -> createReflexResolvedTrigger(event);
			case "smelting.output_ready" -> createSmeltingOutputReadyTrigger(event);
			case "task.blocked" -> createTaskBlockedTrigger(event);
			case "action_graph.goal_suspended" -> createActionGraphSuspendedTrigger(event);
			case "action_graph.goal_terminal" -> createActionGraphTerminalTrigger(event);
			default -> null;
		};
	}

	private static boolean suppressAutonomousPlannerTriggerAfterEvaluation(String eventType) {
		return switch (eventType) {
			case "social.player_spoke",
				"social.system_message",
				"pickup.item_picked_up",
				"crafting.item_crafted",
				"combat.damage_taken",
				"player.physical",
				"smelting.output_ready",
				"task.blocked",
				"action_graph.goal_suspended",
				"action_graph.goal_terminal" -> true;
			default -> false;
		};
	}

	PlannerTrigger createPlannerTriggerForTests(SemanticEvent event, EventRoutingProfile profile) {
		return createPlannerTrigger(event, profile);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createPlayerSpokeTrigger(SemanticEvent event) {
		String player = stringPayloadValue(event.payload(), "player");
		String message = stringPayloadValue(event.payload(), "message");
		if (player == null || message == null) {
			return null;
		}
		if (ChatIngestService.isAddressedToAgent(message)) {
			return null;
		}
		if (!proactiveSocialModeEnabled() || !playerChatWithinConfiguredDistance(player)) {
			return null;
		}
		return PlannerTrigger.autonomous(PlannerTriggerType.CHAT, player, message, event.tick(), event.timestampMs(), "ambient_player_chat");
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createAddressedChatTrigger(SemanticEvent event) {
		String player = stringPayloadValue(event.payload(), "player");
		String message = stringPayloadValue(event.payload(), "message");
		if (player == null || message == null) {
			return null;
		}
		if (DialogueRuntime.isResetCommand(message) || !playerChatWithinConfiguredDistance(player)) {
			return null;
		}
		return PlannerTrigger.direct(PlannerTriggerType.CHAT, player, message, event.tick(), event.timestampMs());
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createLocalControllerTrigger(SemanticEvent event) {
		String message = stringPayloadValue(event.payload(), "message");
		if (message == null || DialogueRuntime.isResetCommand(message)) {
			return null;
		}
		return PlannerTrigger.direct(
			PlannerTriggerType.CHAT,
			DialogueSpeakerLabels.SAME_CLIENT_ADMIN,
			message,
			event.tick(),
			event.timestampMs()
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createSystemTrigger(SemanticEvent event) {
		String message = stringPayloadValue(event.payload(), "message");
		if (message == null || !proactiveSocialModeEnabled()) {
			return null;
		}
		return PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "server", message, event.tick(), event.timestampMs(), "system_message");
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createActionGraphSuspendedTrigger(SemanticEvent event) {
		String executionId = stringPayloadValue(event.payload(), "executionId");
		String pendingWatch = stringPayloadValue(event.payload(), "pendingWatch");
		if (executionId == null) {
			return null;
		}
		String message = "ACTION GRAPH SUSPENDED: executionId=" + executionId
			+ " pendingWatch=" + (pendingWatch == null ? "" : pendingWatch)
			+ ". The goal released foreground actuation while it waits for a world condition. "
			+ "You may send one short chat message explaining the wait, start at most one useful new high-level goal with start_action_goal, or simply acknowledge without taking action. "
			+ "Do not invent filler work. The suspended goal will resume automatically after its condition is fulfilled and current foreground work finishes.";
		return PlannerTrigger.autonomous(
			PlannerTriggerType.SYSTEM,
			"action_graph",
			message,
			event.tick(),
			event.timestampMs(),
			"action_graph_suspended:" + executionId
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createActionGraphTerminalTrigger(SemanticEvent event) {
		String executionId = stringPayloadValue(event.payload(), "executionId");
		String state = stringPayloadValue(event.payload(), "state");
		if (executionId == null || !"FAILED".equals(state)) {
			return null;
		}
		String goal = stringPayloadValue(event.payload(), "goal");
		String failureCode = stringPayloadValue(event.payload(), "failureCode");
		String failureMessage = stringPayloadValue(event.payload(), "message");
		String failedPrimitive = stringPayloadValue(event.payload(), "failedPrimitive");
		String failedTarget = stringPayloadValue(event.payload(), "failedTarget");
		Object failedArgs = event.payload().get("failedArgs");
		String normalizedCode = failureCode == null ? "failed" : failureCode;
		String message = "ACTION GRAPH FAILED: executionId=" + executionId
			+ " goal=" + (goal == null ? "" : goal)
			+ " failedPrimitive=" + (failedPrimitive == null ? "" : failedPrimitive)
			+ " failedTarget=" + (failedTarget == null ? "" : failedTarget)
			+ " failedArgs=" + (failedArgs == null ? "{}" : failedArgs)
			+ " failureCode=" + normalizedCode
			+ " message=" + (failureMessage == null ? "" : failureMessage)
			+ ". Explain the terminal failure accurately. ";
		if ("unknown_acquisition_method".equals(normalizedCode) || "unsupported_resource_kind".equals(normalizedCode)) {
			message += "Airicraft has no registered acquisition method for this request. This requested capability is unsupported; do not retry it unchanged or invent an acquisition method. Another observed supported action may still serve the objective.";
		}
		else {
			message += "Do not claim completion. Assess the identified failure and choose a supported next attempt toward the objective.";
		}
		return PlannerTrigger.autonomous(
			PlannerTriggerType.SYSTEM,
			"action_graph",
			message,
			event.tick(),
			event.timestampMs(),
			"action_graph_terminal:" + executionId
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createPickupTrigger(SemanticEvent event) {
		if (suppressPlannerTriggersForCollectResourceProgress()) {
			return null;
		}
		String itemId = stringPayloadValue(event.payload(), "itemId");
		Float count = floatPayloadValue(event.payload(), "count");
		if (itemId == null || count == null) {
			return null;
		}
		return PlannerTrigger.autonomous(
			PlannerTriggerType.PICKUP,
			"self",
			"Picked up " + formatDecimal(count) + "x " + itemId + ".",
			event.tick(),
			event.timestampMs(),
			"pickup:" + itemId
		);
	}

	private EventPolicyDecision resolveDefaultEventPolicy(SemanticEvent event, EventRoutingProfile profile) {
		if (event == null || !"pickup.item_picked_up".equals(event.type())) {
			return EventPolicyDecision.allow();
		}
		ActiveJob current = activeJobRuntime.current();
		if (current == null || current.isIdle() || current.status().terminal()) {
			return EventPolicyDecision.allow();
		}
		if (current.type() != ActiveJobType.MINE_BLOCKS && current.type() != ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY) {
			return EventPolicyDecision.allow();
		}
		return new EventPolicyDecision(
			EventPolicyEffect.SEMANTIC_ONLY,
			"default-mining-pickup-semantic-only",
			"pickup progress is owned by the active mining job",
			false
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createCraftTrigger(SemanticEvent event) {
		if (suppressPlannerTriggersForCollectResourceProgress() || suppressPlannerTriggersForPendingCraftToolResult()) {
			return null;
		}
		String itemId = stringPayloadValue(event.payload(), "itemId");
		Float count = floatPayloadValue(event.payload(), "count");
		if (itemId == null || count == null) {
			return null;
		}
		return PlannerTrigger.autonomous(
			PlannerTriggerType.CRAFT,
			"self",
			"I crafted " + formatDecimal(count) + "x " + itemId + ".",
			event.tick(),
			event.timestampMs(),
			"craft:" + itemId
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createDamageTrigger(SemanticEvent event) {
		if (survivalReflexRuntime.snapshot().ownsActuation()) {
			return null;
		}
		Map<String, Object> payload = event.payload();
		String damageTypeId = stringPayloadValue(payload, "damageTypeId");
		String attackerName = stringPayloadValue(payload, "attackerName");
		Float amount = floatPayloadValue(payload, "amount");
		Float resultingHealth = floatPayloadValue(payload, "healthAfter");
		if (amount == null && resultingHealth == null) {
			return null;
		}

		StringBuilder message = new StringBuilder("I took ")
			.append(formatDecimal(amount == null ? 0.0F : amount))
			.append(" damage");
		if (attackerName != null) {
			message.append(" from ").append(attackerName);
		}
		else if (damageTypeId != null) {
			message.append(" from ").append(damageTypeId);
		}
		if (resultingHealth != null) {
			message.append(" and dropped to ").append(formatDecimal(resultingHealth)).append(" health");
		}
		message.append('.');
		return PlannerTrigger.autonomous(
			PlannerTriggerType.DAMAGE,
			"self",
			message.toString(),
			event.tick(),
			event.timestampMs(),
			"damage"
		);
	}

	private PlannerTrigger createPhysicalTrigger(SemanticEvent event) {
		// Reflex observations still reach the semantic feed; its existing resolution event wakes the planner.
		if (survivalReflexRuntime.snapshot().ownsActuation()) return null;
		return PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "self",
			"Physical observation: " + new com.google.gson.Gson().toJson(event.payload())
				+ ". These are observed changes, not proof of an involuntary cause. Use the actual position and task context to decide whether recovery is needed.",
			event.tick(), event.timestampMs(), "physical:" + event.payload().get("kind"));
	}

	private PlannerTrigger createReflexResolvedTrigger(SemanticEvent event) {
		String holdId = stringPayloadValue(event.payload(), "holdId");
		String cause = stringPayloadValue(event.payload(), "cause");
		String reason = stringPayloadValue(event.payload(), "reason");
		String nextState = stringPayloadValue(event.payload(), "nextState");
		String message = "SURVIVAL UPDATE: reflex resolved cause=" + (cause == null ? "unknown" : cause)
			+ " reason=" + (reason == null ? "safe" : reason)
			+ " state=" + (nextState == null ? survivalReflexRuntime.snapshot().state().name() : nextState)
			+ " holdId=" + (holdId == null ? "none" : holdId)
			+ (holdId == null
				? ". Review the consolidated safety episode; no interrupted task requires resumption."
				: ". Review the consolidated safety episode and use continue to retain and resume the plan, or clear_queue to abort and replace it.");
		if ("combat_stalemate".equals(reason)) {
			message += " Combat is still unresolved and made no target-health or closing progress for "
				+ event.payload().get("noProgressTicks") + " ticks. Position=" + event.payload().get("position")
				+ "; remainingThreats=" + event.payload().get("remainingThreats")
				+ ". Inspect local geometry and inventory, then execute a concrete escape or cover plan. Do not resume the same stalled pursuit."
				+ " You have a bounded recovery window: ordinary mob pressure will not preempt your action for 600 ticks after releasing the hold."
				+ " Critical health, an imminent creeper, or drowning can interrupt. Mining steps or towering may be needed to escape a pit.";
		}
		if ("combat_approach_stalled".equals(reason)) {
			message += " Combat is unresolved: pursuit made no closer approach to the distant threats for "
				+ event.payload().get("noProgressTicks") + " ticks. Position=" + event.payload().get("position")
				+ "; remainingThreats=" + event.payload().get("remainingThreats")
				+ ". Choose a tactical next step from fresh geometry and inventory; repeated pursuit has made no progress."
				+ " These distant attackers are deferred while you act; close danger, incoming projectiles, damage or a new attacker reactivate defense.";
		}
		message += " Automatic reflex policy=" + survivalReflexRuntime.policy()
			+ ". Use configure_reflex to read or override it for a deliberate tactic; policy changes do not resume paused work.";
		return PlannerTrigger.autonomous(
			PlannerTriggerType.SYSTEM,
			"survival_runtime",
			message,
			event.tick(),
			event.timestampMs(),
			"survival_reflex_resolved"
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createSmeltingOutputReadyTrigger(SemanticEvent event) {
		Map<String, Object> payload = event.payload();
		String processId = stringPayloadValue(payload, "processId");
		String outputItemId = stringPayloadValue(payload, "outputItemId");
		Float outputCount = floatPayloadValue(payload, "outputCount");
		String station = stringPayloadValue(payload, "station");
		boolean estimated = booleanPayloadValue(payload, "estimated");
		if (processId == null || outputItemId == null || outputCount == null) {
			return null;
		}
		StringBuilder message = new StringBuilder("Smelting output ready: processId=")
			.append(processId)
			.append(" output=")
			.append(outputItemId)
			.append("x")
			.append(formatDecimal(outputCount));
		if (estimated) {
			message.append(" estimated=true");
		}
		if (station != null) {
			message.append(" station=").append(station);
		}
		message.append(". Output still needs collection: call collect_smelted_items with this processId, then verify inventory.");
		return PlannerTrigger.autonomous(
			PlannerTriggerType.SYSTEM,
			"runtime",
			message.toString(),
			event.tick(),
			event.timestampMs(),
			"smelting_output:" + processId
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createTaskBlockedTrigger(SemanticEvent event) {
		Map<String, Object> payload = event.payload();
		String taskType = stringPayloadValue(payload, "taskType");
		String resourceKind = stringPayloadValue(payload, "resourceKind");
		String blockedReason = stringPayloadValue(payload, "blockedReason");
		Float collected = floatPayloadValue(payload, "collected");
		Float remaining = floatPayloadValue(payload, "remaining");
		if (taskType == null || blockedReason == null) {
			return null;
		}

		StringBuilder message = new StringBuilder("Task blocked: taskType=").append(taskType);
		if (resourceKind != null) {
			message.append(" resourceKind=").append(resourceKind);
		}
		message.append(" reason=").append(blockedReason);
		if (collected != null) {
			message.append(" collected=").append(formatDecimal(collected));
		}
		if (remaining != null) {
			message.append(" remaining=").append(formatDecimal(remaining));
		}
		message.append('.');
		return PlannerTrigger.autonomous(
			PlannerTriggerType.SYSTEM,
			"runtime",
			message.toString(),
			event.tick(),
			event.timestampMs(),
			"task_blocked"
		);
	}

	private void recordSmeltingOutputReadyEvents(MinecraftClient client) {
		if (!sessionSnapshot.worldLoaded() || !smeltingProcessManager.hasTrackedProcesses()) {
			return;
		}
		if (
			lastSmeltingOutputReadyPollTick != Long.MIN_VALUE
				&& tickCount - lastSmeltingOutputReadyPollTick < SMELTING_OUTPUT_READY_POLL_INTERVAL_TICKS
		) {
			return;
		}
		lastSmeltingOutputReadyPollTick = tickCount;
		for (SmeltingOutputReadyEvent event : smeltingPlannerService.pollTrackedOutputReady(client, smeltingProcessManager, tickCount)) {
			eventBuffer.append(tickCount, "smelting.output_ready", Map.of(
				"processId", event.processId(),
				"optionId", event.optionId(),
				"station", event.stationKey().compact(),
				"outputItemId", event.outputItemId(),
				"outputCount", event.outputCount(),
				"inputQuantity", event.inputQuantity(),
				"estimated", event.estimated()
			));
		}
	}

	private void applyPlannerEventPolicyChanges(EventPolicyChanges changes) {
		if (changes == null) {
			return;
		}
		long timestampMs = System.currentTimeMillis();
		if (changes.clearAll()) {
			eventPolicyState.clear();
		}
		eventPolicyState.removeRuleIds(changes.removeRuleIds());
		for (EventPolicyRuleUpsert upsert : changes.upserts()) {
			applyPlannerEventPolicyUpsert(upsert, timestampMs);
		}
	}

	private void applyPlannerEventPolicyUpsert(EventPolicyRuleUpsert upsert, long timestampMs) {
		if (upsert == null) {
			return;
		}
		EventPolicyMatch match = upsert.match();
		String eventType = match == null ? null : match.eventType();
		EventPolicyEffect effect = EventPolicyEffect.parse(upsert.effect());
		String ruleId = normalizeRuleId(upsert.ruleId());
		if (ruleId == null) {
			ruleId = "planner-rule-" + timestampMs + "-" + eventPolicyState.activeRuleCount();
		}
		if (match == null || !match.isValid()) {
			recordPolicyRuleRejected(ruleId, eventType, upsert.effect(), "eventType is required");
			return;
		}
		EventRoutingProfile profile = eventRoutingProfiles.get(eventType);
		if (profile != null && profile.policyBypass()) {
			recordPolicyRuleRejected(ruleId, eventType, upsert.effect(), "event type bypasses planner-authored policy");
			return;
		}
		if (effect == null) {
			recordPolicyRuleRejected(ruleId, eventType, upsert.effect(), "effect must be allow, ignore, semantic_only, or trigger_only");
			return;
		}
		eventPolicyState.upsert(new EventPolicyRule(
			ruleId,
			effect,
			match,
			upsert.reason(),
			timestampMs,
			null,
			0L,
			"planner"
		));
	}

	private void recordPolicyRuleRejected(String ruleId, String eventType, String effect, String reason) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		if (ruleId != null) {
			payload.put("ruleId", ruleId);
		}
		if (eventType != null) {
			payload.put("eventType", eventType);
		}
		if (effect != null && !effect.isBlank()) {
			payload.put("effect", effect);
		}
		payload.put("reason", reason == null || reason.isBlank() ? "rule rejected" : reason);
		eventBuffer.append(tickCount, "policy.rule_rejected", payload);
	}

	private static String normalizeRuleId(String ruleId) {
		if (ruleId == null) {
			return null;
		}
		String trimmed = ruleId.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static Map<String, EventRoutingProfile> createEventRoutingProfiles() {
		LinkedHashMap<String, EventRoutingProfile> profiles = new LinkedHashMap<>();
		profiles.put("social.player_spoke", new EventRoutingProfile("social.player_spoke", false, PlannerTriggerType.CHAT, false));
		profiles.put("social.player_addressed_agent", new EventRoutingProfile("social.player_addressed_agent", false, PlannerTriggerType.CHAT, true));
		profiles.put("social.local_controller_spoke", new EventRoutingProfile("social.local_controller_spoke", false, PlannerTriggerType.CHAT, true));
		profiles.put("social.system_message", new EventRoutingProfile("social.system_message", false, PlannerTriggerType.SYSTEM, false));
		profiles.put("pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false));
		profiles.put("crafting.item_crafted", new EventRoutingProfile("crafting.item_crafted", true, PlannerTriggerType.CRAFT, false));
		profiles.put("smelting.output_ready", new EventRoutingProfile("smelting.output_ready", true, PlannerTriggerType.SYSTEM, true));
		profiles.put("combat.damage_taken", new EventRoutingProfile("combat.damage_taken", true, PlannerTriggerType.DAMAGE, false));
		profiles.put("player.physical", new EventRoutingProfile("player.physical", true, PlannerTriggerType.SYSTEM, true));
		profiles.put("reflex.threat_detected", new EventRoutingProfile("reflex.threat_detected", true, null, true));
		profiles.put("reflex.started", new EventRoutingProfile("reflex.started", true, null, true));
		profiles.put("reflex.action_changed", new EventRoutingProfile("reflex.action_changed", true, null, true));
		profiles.put("reflex.resolved", new EventRoutingProfile("reflex.resolved", true, PlannerTriggerType.SYSTEM, true));
		profiles.put("reflex.hold_released", new EventRoutingProfile("reflex.hold_released", true, null, true));
		profiles.put("reflex.actuator_failed", new EventRoutingProfile("reflex.actuator_failed", true, null, true));
		profiles.put("lighting.torch_placed", new EventRoutingProfile("lighting.torch_placed", true, null, true));
		profiles.put("planner.stale_response_rejected", new EventRoutingProfile("planner.stale_response_rejected", true, null, true));
		profiles.put("player.died", new EventRoutingProfile("player.died", true, null, true));
		profiles.put("player.actions_cancelled", new EventRoutingProfile("player.actions_cancelled", true, null, true));
		profiles.put("player.action_rejected", new EventRoutingProfile("player.action_rejected", true, null, true));
		profiles.put("player.respawn_requested", new EventRoutingProfile("player.respawn_requested", true, null, true));
		profiles.put("player.respawn_request_failed", new EventRoutingProfile("player.respawn_request_failed", true, null, true));
		profiles.put("player.respawned", new EventRoutingProfile("player.respawned", true, null, true));
		profiles.put("session.world_loaded", new EventRoutingProfile("session.world_loaded", true, null, false));
		profiles.put("session.world_unloaded", new EventRoutingProfile("session.world_unloaded", true, null, false));
		profiles.put("session.connection_lost", new EventRoutingProfile("session.connection_lost", true, null, false));
		profiles.put("session.lan_opened", new EventRoutingProfile("session.lan_opened", true, null, false));
		profiles.put("social.player_joined_game", new EventRoutingProfile("social.player_joined_game", true, null, false));
		profiles.put("social.player_left_game", new EventRoutingProfile("social.player_left_game", true, null, false));
		profiles.put("social.player_joined_nearby", new EventRoutingProfile("social.player_joined_nearby", true, null, false));
		profiles.put("social.player_left_nearby", new EventRoutingProfile("social.player_left_nearby", true, null, false));
		profiles.put("follow.target_acquired", new EventRoutingProfile("follow.target_acquired", true, null, false));
		profiles.put("follow.target_lost", new EventRoutingProfile("follow.target_lost", true, null, false));
		profiles.put("follow.stuck", new EventRoutingProfile("follow.stuck", true, null, false));
		profiles.put("planner.goal_set", new EventRoutingProfile("planner.goal_set", true, null, false));
		profiles.put("planner.goal_cleared", new EventRoutingProfile("planner.goal_cleared", true, null, false));
		profiles.put("planner.degraded_entered", new EventRoutingProfile("planner.degraded_entered", true, null, false));
		profiles.put("planner.degraded_cleared", new EventRoutingProfile("planner.degraded_cleared", true, null, false));
		profiles.put("planner.reset_requested", new EventRoutingProfile("planner.reset_requested", true, null, true));
		profiles.put("task.blocked", new EventRoutingProfile("task.blocked", true, PlannerTriggerType.SYSTEM, true));
		profiles.put("action_graph.goal_suspended", new EventRoutingProfile("action_graph.goal_suspended", true, PlannerTriggerType.SYSTEM, true));
		profiles.put("action_graph.goal_terminal", new EventRoutingProfile("action_graph.goal_terminal", true, PlannerTriggerType.SYSTEM, true));
		profiles.put("policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened"));
		profiles.put("policy.rule_rejected", EventRoutingProfile.rawOnly("policy.rule_rejected"));
		return Map.copyOf(profiles);
	}

	private boolean suppressPlannerTriggersForCollectResourceProgress() {
		ActiveJob current = activeJobRuntime.current();
		return current.type() == ActiveJobType.COLLECT_RESOURCE && !current.status().terminal();
	}

	private boolean suppressPlannerTriggersForPendingCraftToolResult() {
		PendingCraftToolResult pending = pendingCraftToolResult;
		return pending != null && !pending.future().isDone();
	}

	private void recordTaskStateTransition(TaskExecutionSnapshot previous, TaskExecutionSnapshot current, boolean semanticTaskContext) {
		if (current == null || previous == null || current.state() == previous.state()) {
			return;
		}
		if (semanticTaskContext) {
			return;
		}

		java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
		if (current.activeGoal() != null) {
			payload.put("goalType", current.activeGoal().type().name());
		}
		if (current.processName() != null && !current.processName().isBlank()) {
			payload.put("process", current.processName());
		}

		if (current.state() == TaskExecutionState.RUNNING) {
			eventBuffer.append(tickCount, "task.started", payload);
			return;
		}
		if (current.state() == TaskExecutionState.PAUSED_BY_SESSION_GATE) {
			eventBuffer.append(tickCount, "task.paused_by_session_gate", payload);
		}
		if (current.state() == TaskExecutionState.PAUSED_BY_REFLEX) {
			eventBuffer.append(tickCount, "task.paused_by_reflex", payload);
		}
	}

	private void recordSemanticTaskTransition(TaskSnapshot previous, TaskSnapshot current) {
		if (previous == null || current == null || current.state() == previous.state() || !isSemanticTaskSnapshot(current)) {
			return;
		}

		java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
		if (current.spec() != null) {
			payload.put("taskType", current.spec().type().name());
			payload.put("resourceKind", current.spec().resourceKind().name());
			payload.put("quantity", current.spec().quantity());
		}
		if (current.mission() != null) {
			payload.put("missionId", current.mission().missionId());
			payload.put("missionType", current.mission().missionType().name());
		}
		if (current.activeStepId() != null) {
			payload.put("activeStepId", current.activeStepId());
		}
		if (current.activeStepKind() != null) {
			payload.put("activeStepKind", current.activeStepKind().name());
		}
		payload.put("state", current.state().name());
		if (current.activeStepKind() != LedgerStepKind.ATTACK_ENTITY) payload.put("collected", current.progress().collected());
		payload.put("remaining", current.progress().remaining());
		if (current.state() == TaskState.WAITING_FOR_PICKUP) {
			String blockedReason = activeJobRuntime.current().blockedReason();
			if (blockedReason != null && !blockedReason.isBlank()) {
				payload.put("blockedReason", blockedReason);
			}
		}
		if (current.source() != null && !current.source().isBlank()) {
			payload.put("source", current.source());
		}
		if (current.lastFailure() != null && !current.lastFailure().isBlank()) {
			payload.put("failure", current.lastFailure());
		}

		String terminalDetails = isTerminalTaskState(current.state()) && taskExecutionSnapshot != null
			&& Objects.equals(current.taskId(), taskExecutionSnapshot.taskId())
			? Objects.requireNonNullElse(taskExecutionSnapshot.lastPathEvent(), "") : "";
		if (!terminalDetails.isBlank()) payload.put("message", terminalDetails);
		String eventType = switch (current.state()) {
			case RUNNING -> "task.started";
			case WAITING_FOR_PICKUP -> "task.blocked";
			case PAUSED_BY_SESSION_GATE -> "task.paused_by_session_gate";
			case PAUSED_BY_REFLEX -> "task.paused_by_reflex";
			case COMPLETED -> "task.completed";
			case FAILED -> "task.failed";
			case CANCELLED -> "task.cancelled";
			default -> null;
		};
		if (eventType != null) {
			eventBuffer.append(tickCount, eventType, payload);
		}

		refreshWorkHistory();
		semanticTaskTerminalEvent(current).ifPresent(this::captureActionGraphTerminalEvent);
		if (
			current.state() == TaskState.PAUSED_BY_SESSION_GATE
				|| current.state() == TaskState.PAUSED_BY_REFLEX
				|| current.state() == TaskState.COMPLETED
				|| current.state() == TaskState.FAILED
				|| current.state() == TaskState.CANCELLED
		) {
			dialogueRuntime.queueTaskWakeup(current.mission() == null ? current.taskId() : current.mission().missionId(),
				tickCount, eventBuffer.latestSeqNo());
		}
	}

	private Optional<TaskTerminalEvent> semanticTaskTerminalEvent(TaskSnapshot current) {
		if (current == null || current.source() == null || !"action_graph".equals(current.source())) {
			return Optional.empty();
		}
		TaskExecutionState terminalState = switch (current.state()) {
			case COMPLETED -> TaskExecutionState.COMPLETED;
			case FAILED -> TaskExecutionState.FAILED;
			case CANCELLED -> TaskExecutionState.CANCELLED;
			default -> null;
		};
		if (terminalState == null || current.mission() == null || current.mission().missionId() == null || current.mission().missionId().isBlank()) {
			return Optional.empty();
		}
		String message = current.lastFailure() == null || current.lastFailure().isBlank()
			? current.state().name().toLowerCase(Locale.ROOT)
			: current.lastFailure();
		TaskTerminationCause terminationCause = terminalState == TaskExecutionState.COMPLETED ? TaskTerminationCause.GOAL_REACHED : null;
		String taskId = actionGraphActiveTaskIdFor(current).orElse(current.mission().missionId());
		return Optional.of(new TaskTerminalEvent(
			taskId,
			null,
			terminalState,
			message,
			terminationCause,
			terminalState == TaskExecutionState.FAILED ? TaskFailureCode.UNKNOWN : TaskFailureCode.NONE
		));
	}

	private Optional<String> actionGraphActiveTaskIdFor(TaskSnapshot current) {
		ActionGraphExecutionView foreground = actionGraphCoordinator.inspect(actionGraphCoordinator.foregroundExecutionId());
		ActionGraphExecutionSnapshot snapshot = foreground == null ? ActionGraphExecutionSnapshot.idle() : foreground.execution();
		if (snapshot.activeTaskId().isBlank() || current == null || current.mission() == null) {
			return Optional.empty();
		}
		ActiveJob activeJob = activeJobRuntime.current();
		if (activeJob == null
			|| activeJob.isIdle()
			|| !"action_graph".equals(activeJob.source())
			|| !Objects.equals(activeJob.jobId(), current.mission().missionId())) {
			return Optional.empty();
		}
		return Optional.of(snapshot.activeTaskId());
	}

	private static boolean hasSemanticTaskContext(TaskSnapshot previous, TaskSnapshot current) {
		return (previous != null && isSemanticTaskSnapshot(previous) && isActiveSemanticTaskState(previous.state()))
			|| (current != null && isSemanticTaskSnapshot(current) && isActiveSemanticTaskState(current.state()));
	}

	private static boolean isDirectGoalIntent(DialogueIntent intent) {
		if (intent == null || intent.type() == null) {
			return false;
		}
		if (intent.type() == DialogueIntentType.SET_GOAL) {
			return true;
		}
		if (intent.type() != DialogueIntentType.JOB_UPDATE || intent.activeJob() == null) {
			return false;
		}
		return switch (intent.activeJob().type()) {
			case FOLLOW_PLAYER, NAVIGATE_TO, MINE_BLOCKS, ENSURE_BLOCKS_IN_INVENTORY, RETURN_TO_SURFACE, PLACE_BLOCK, USE_BLOCK, BREAK_BLOCKS, TEND_CROPS, LURE_ENTITIES -> true;
			case IDLE, COLLECT_RESOURCE, CRAFT_RECIPE, DROP_ITEMS, SMELT_ITEMS, COLLECT_SMELTED_ITEMS, ATTACK_ENTITY, USE_ENTITY, ASK_USER -> false;
		};
	}

	private static boolean isSemanticTaskSnapshot(TaskSnapshot snapshot) {
		if (snapshot == null) {
			return false;
		}
		if (snapshot.spec() != null) {
			return true;
		}
		if (Objects.equals(snapshot.activeStepId(), ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY.name().toLowerCase())) {
			return true;
		}
		if (Objects.equals(snapshot.activeStepId(), ActiveJobType.LURE_ENTITIES.name().toLowerCase())
			|| Objects.equals(snapshot.activeStepId(), ActiveJobType.RETURN_TO_SURFACE.name().toLowerCase())) {
			return true;
		}
		return snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.COLLECT_RESOURCE
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.CRAFT_RECIPE
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.DROP_ITEMS
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.SMELT_ITEMS
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.COLLECT_SMELTED_ITEMS
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.ATTACK_ENTITY
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.USE_ENTITY
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.PLACE_BLOCK
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.USE_BLOCK
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.ASK_USER;
	}

	private static boolean isActiveSemanticTaskState(TaskState state) {
		return state == TaskState.QUEUED
			|| state == TaskState.RUNNING
			|| state == TaskState.WAITING_FOR_PICKUP
			|| state == TaskState.PAUSED_BY_SESSION_GATE
			|| state == TaskState.PAUSED_BY_REFLEX;
	}

	private static boolean isActiveTaskExecutionState(TaskExecutionState state) {
		return state == TaskExecutionState.RUNNING
			|| state == TaskExecutionState.PAUSED_BY_SESSION_GATE
			|| state == TaskExecutionState.PAUSED_BY_REFLEX;
	}

	private static boolean isTerminalTaskState(TaskState state) {
		return state == TaskState.COMPLETED
			|| state == TaskState.FAILED
			|| state == TaskState.CANCELLED;
	}

	private void handleTerminalTaskEvent(TaskTerminalEvent event, boolean semanticTaskContext, Optional<WorldTaskRequest> activeTaskRequest) {
		if (event == null || event.goal() == null || event.terminalState() == null) {
			return;
		}
		if (semanticTaskContext) {
			return;
		}

		String eventType = switch (event.terminalState()) {
			case COMPLETED -> "task.completed";
			case FAILED -> "task.failed";
			case CANCELLED -> "task.cancelled";
			default -> null;
		};
		if (eventType != null) {
			java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
			payload.put("taskId", event.taskId());
			payload.put("goalType", event.goal().type().name());
			payload.put("message", event.message() == null ? "" : event.message());
			if (event.goal().position() != null) {
				var target = event.goal().position();
				payload.put("target", Map.of("x",target.x(),"y",target.y(),"z",target.z(),"exactY",target.exactY()));
			}
			payload.putAll(currentPhysicalState());
			if (event.terminationCause() != null) {
				payload.put("terminationCause", event.terminationCause().name());
				if (event.terminationCause() == ai.moeru.airicraft.agent.tasks.TaskTerminationCause.CALCULATION_FAILED) {
					payload.put("failedPredicate", "eligible_path_found");
					payload.put("scope", "target");
					payload.put("bounds", ai.moeru.airicraft.agent.spatial.WorldTravelPolicy.snapshot());
					payload.put("causeKnown", false);
				}
			}
			if (event.goal().targetPlayer() != null && !event.goal().targetPlayer().isBlank()) {
				payload.put("targetPlayer", event.goal().targetPlayer());
			}
			eventBuffer.append(tickCount, eventType, payload);
		}

		dialogueRuntime.queueTaskWakeup(event.taskId(), tickCount, eventBuffer.latestSeqNo());
	}

	private void handleInternalTaskWarning(String warning) {
		if (warning == null || warning.isBlank()) {
			return;
		}
		dialogueRuntime.onInternalTaskUpdate(
			warning,
			tickCount,
			sessionSnapshot,
			activeGoal(),
			taskSnapshot,
			missionExecutionSnapshot,
			eventBuffer
		);
	}

	private void completePendingCraftToolResult(TaskTerminalEvent event) {
		PendingCraftToolResult pending = pendingCraftToolResult;
		if (pending == null || event == null || !Objects.equals(pending.taskId(), event.taskId())) {
			return;
		}
		reconcileTerminalPlannerWork(event);
		completePendingCraftToolResult(formatCraftTerminalToolResult(pending.craftRecipe(), event) + inventorySnapshotForTaskUpdate(WorldTaskType.CRAFT_RECIPE));
	}

	/** Publish the executor's observed outcome before completing its synchronous tool result. */
	private void reconcileTerminalPlannerWork(TaskTerminalEvent event) {
		if (activeJobRuntime.activeTaskRequest().filter(request -> request.taskId().equals(event.taskId())
			&& request.sourceJobId().equals(activeJobRuntime.current().jobId())).isEmpty()) return;
		var observed = new TaskExecutionSnapshot(event.terminalState(), event.taskId(), event.goal(),
			taskExecutionSnapshot.processName(), event.message(), null, event.terminationCause());
		TaskSnapshot previous = taskSnapshot;
		// This reduces an effect already observed; it does not grant new actuation.
		activeJobRuntime.tick(observed, currentWorldEvidence(MinecraftClient.getInstance()), true, true, tickCount);
		taskSnapshot = activeJobRuntime.taskSnapshot();
		missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
		recordSemanticTaskTransition(previous, taskSnapshot);
		refreshWorkHistory();
	}

	private void completePendingCraftToolResultFromTaskSnapshot(TaskSnapshot snapshot) {
		PendingCraftToolResult pending = pendingCraftToolResult;
		if (
			pending == null
				|| snapshot == null
				|| snapshot.taskId() == null
				|| !Objects.equals(pending.taskId(), snapshot.taskId())
				|| snapshot.activeStepKind() != ai.moeru.airicraft.agent.tasks.LedgerStepKind.CRAFT_RECIPE
				|| !isTerminalTaskState(snapshot.state())
		) {
			return;
		}
		completePendingCraftToolResult(formatCraftSnapshotToolResult(pending.craftRecipe(), snapshot) + inventorySnapshotForTaskUpdate(WorldTaskType.CRAFT_RECIPE));
	}

	private void completePendingBlockModificationToolResult(TaskTerminalEvent event, Optional<WorldTaskRequest> activeTaskRequest) {
		PendingBlockModificationToolResult pending = pendingBlockModificationToolResult.get();
		if (pending == null || event == null || !Objects.equals(pending.taskId(), event.taskId())) {
			return;
		}
		reconcileTerminalPlannerWork(event);
		completePendingBlockModificationToolResult(pending,
			formatBlockModificationTerminalToolResult(pending, event)
				+ inventorySnapshotForTaskUpdate(event, activeTaskRequest)
		);
	}

	private void completePendingBlockModificationToolResultFromTaskSnapshot(TaskSnapshot snapshot) {
		PendingBlockModificationToolResult pending = pendingBlockModificationToolResult.get();
		if (
			pending == null
				|| snapshot == null
				|| snapshot.taskId() == null
				|| !Objects.equals(pending.taskId(), snapshot.taskId())
				|| snapshot.activeStepKind() != pending.stepKind()
				|| !isTerminalTaskState(snapshot.state())
		) {
			return;
		}
		completePendingBlockModificationToolResult(pending,
			formatBlockModificationSnapshotToolResult(pending, snapshot)
				+ inventorySnapshotForTaskUpdate(pending.taskType())
		);
	}

	private String inventorySnapshotForTaskUpdate(LedgerStepKind activeStepKind) {
		if (!inventoryMutatingStepKind(activeStepKind)) {
			return "";
		}
		return formatInventorySnapshotForTaskUpdate(currentWorldEvidence(MinecraftClient.getInstance()));
	}

	private String inventorySnapshotForTaskUpdate(TaskTerminalEvent event, Optional<WorldTaskRequest> activeTaskRequest) {
		WorldTaskType taskType = activeTaskRequest == null
			? null
			: activeTaskRequest
				.filter(request -> event != null && Objects.equals(request.taskId(), event.taskId()))
				.map(WorldTaskRequest::type)
				.orElse(null);
		if (taskType == null && event != null && event.goal() != null && event.goal().type() == GoalType.MINE_BLOCKS) {
			taskType = WorldTaskType.MINE;
		}
		return inventorySnapshotForTaskUpdate(taskType);
	}

	private String inventorySnapshotForTaskUpdate(WorldTaskType taskType) {
		if (!inventoryMutatingTaskType(taskType)) {
			return "";
		}
		return formatInventorySnapshotForTaskUpdate(currentWorldEvidence(MinecraftClient.getInstance()));
	}

	private String inventorySnapshotForTaskUpdate(LedgerStepKind stepKind, String activeStepId) {
		if (Objects.equals(activeStepId, ActiveJobType.RETURN_TO_SURFACE.name().toLowerCase())) {
			return formatInventorySnapshotForTaskUpdate(currentWorldEvidence(MinecraftClient.getInstance()));
		}
		return inventorySnapshotForTaskUpdate(stepKind);
	}

	static String formatInventorySnapshotForTaskUpdate(WorldEvidence evidence) {
		if (evidence == null) {
			return "";
		}
		Map<String, Integer> itemCounts = evidence.itemCounts() == null ? Map.of() : new TreeMap<>(evidence.itemCounts());
		List<String> hotbarItems = evidence.hotbarItems() == null ? List.of() : evidence.hotbarItems();
		boolean hasSnapshot = !itemCounts.isEmpty()
			|| !hotbarItems.isEmpty()
			|| evidence.selectedHotbarSlot() >= 0
			|| (evidence.equippedItemId() != null && !evidence.equippedItemId().isBlank());
		if (!hasSnapshot) {
			return "";
		}
		return " Inventory update: " + ai.moeru.airicraft.agent.llm.PlannerStateText.inventory(itemCounts) + " "
			+ ai.moeru.airicraft.agent.llm.PlannerStateText.hotbar(ai.moeru.airicraft.agent.llm.PlannerStateText.hotbarEvidence(hotbarItems), evidence.selectedHotbarSlot()) + " "
			+ ai.moeru.airicraft.agent.llm.PlannerStateText.mainHand(evidence.equippedItemId());
	}

	private static boolean inventoryMutatingStepKind(LedgerStepKind kind) {
		if (kind == null) {
			return false;
		}
		return switch (kind) {
			case COLLECT_RESOURCE, MINE_BLOCKS, CRAFT_RECIPE, TRANSFER_ITEMS, PLACE_BLOCK, USE_BLOCK, DROP_ITEMS, SMELT_ITEMS, COLLECT_SMELTED_ITEMS, BREAK_BLOCKS -> true;
			case NAVIGATE_TO_POSITION, NAVIGATE_TO_BLOCK_KIND, OPEN_CONTAINER, ATTACK_ENTITY, USE_ENTITY, ASK_USER, FINISH -> false;
		};
	}

	private static boolean inventoryMutatingTaskType(WorldTaskType type) {
		if (type == null) {
			return false;
		}
		return switch (type) {
			case MINE, UNDERWATER_HARVEST, CRAFT_RECIPE, DROP_ITEMS, SMELT_ITEMS, COLLECT_SMELTED_ITEMS, RETURN_TO_SURFACE, PLACE_BLOCK, USE_BLOCK, BREAK_BLOCKS, TEND_CROPS -> true;
			case FOLLOW, NAVIGATE, ATTACK_ENTITY, USE_ENTITY, LURE_ENTITIES -> false;
		};
	}

	private void expirePendingCraftToolResultIfTimedOut() {
		PendingCraftToolResult pending = pendingCraftToolResult;
		if (pending == null || pending.future().isDone()) {
			pendingCraftToolResult = null;
			return;
		}
		long waitedTicks = tickCount - pending.startTick();
		if (waitedTicks < CRAFT_TOOL_RESULT_TIMEOUT_TICKS) {
			return;
		}
		completePendingCraftToolResult(
			"Tool result for craft_recipe: pending_timeout"
				+ " recipeId=" + pending.craftRecipe().recipeId()
				+ " times=" + pending.craftRecipe().times()
				+ " waitedTicks=" + waitedTicks
				+ ". Crafting is still running; this can happen on high-latency multiplayer. Wait for its terminal result in a later observation before saying it completed."
		);
	}

	private void expirePendingBlockModificationToolResultIfTimedOut() {
		PendingBlockModificationToolResult pending = pendingBlockModificationToolResult.get();
		if (pending == null) {
			return;
		}
		if (pending.future().isDone()) {
			pendingBlockModificationToolResult.compareAndSet(pending, null);
			return;
		}
		long waitedTicks = tickCount - pending.startTick();
		if (waitedTicks < BLOCK_MODIFICATION_TOOL_RESULT_TIMEOUT_TICKS) {
			return;
		}
		completePendingBlockModificationToolResult(pending,
			"Tool result for " + pending.toolName() + ": pending_timeout "
				+ pending.details()
				+ " waitedTicks=" + waitedTicks
				+ ". The action is still running; wait for its terminal result in a later observation before saying it completed."
		);
	}

	private void completePendingCraftToolResult(String result) {
		PendingCraftToolResult pending = pendingCraftToolResult;
		if (pending == null) {
			return;
		}
		pendingCraftToolResult = null;
		pending.future().complete(result);
	}

	private void completePendingBlockModificationToolResult(
		PendingBlockModificationToolResult pending,
		String result
	) {
		if (pending == null || !pendingBlockModificationToolResult.compareAndSet(pending, null)) {
			return;
		}
		pending.future().complete(result);
	}

	private void cancelPendingBlockModificationToolResult(PendingBlockModificationStopReason reason) {
		PendingBlockModificationToolResult pending = pendingBlockModificationToolResult.getAndSet(null);
		if (pending != null) {
			pending.future().complete(reason.result(pending));
		}
	}

	private static String formatCraftTerminalToolResult(CraftRecipeStepArgs craftRecipe, TaskTerminalEvent event) {
		boolean failed = event.terminalState() == TaskExecutionState.FAILED;
		String status = failed ? "failed" : event.terminalState() == TaskExecutionState.CANCELLED ? "cancelled" : "completed";
		String message = event.message() == null || event.message().isBlank() ? "" : " message=" + event.message();
		return "Tool result for craft_recipe: " + status
			+ " recipeId=" + craftRecipe.recipeId()
			+ " times=" + craftRecipe.times()
			+ " state=" + event.terminalState().name()
			+ message;
	}

	private static String formatCraftSnapshotToolResult(CraftRecipeStepArgs craftRecipe, TaskSnapshot snapshot) {
		String status = snapshot.state() == TaskState.FAILED ? "failed" : snapshot.state() == TaskState.CANCELLED ? "cancelled" : "completed";
		String failure = snapshot.lastFailure() == null || snapshot.lastFailure().isBlank() ? "" : " failure=" + snapshot.lastFailure();
		return "Tool result for craft_recipe: " + status
			+ " recipeId=" + craftRecipe.recipeId()
			+ " times=" + craftRecipe.times()
			+ " state=" + snapshot.state().name()
			+ failure;
	}

	private static String formatBlockModificationTerminalToolResult(PendingBlockModificationToolResult pending, TaskTerminalEvent event) {
		String status = event.terminalState() == TaskExecutionState.FAILED
			? "failed"
			: event.terminalState() == TaskExecutionState.CANCELLED ? "cancelled" : "completed";
		String message = event.message() == null || event.message().isBlank() ? "" : " message=" + event.message();
		return "Tool result for " + pending.toolName() + ": " + status
			+ " " + pending.details()
			+ " state=" + event.terminalState().name()
			+ message;
	}

	private static String formatBlockModificationSnapshotToolResult(PendingBlockModificationToolResult pending, TaskSnapshot snapshot) {
		String status = snapshot.state() == TaskState.FAILED ? "failed" : snapshot.state() == TaskState.CANCELLED ? "cancelled" : "completed";
		String failure = snapshot.lastFailure() == null || snapshot.lastFailure().isBlank() ? "" : " failure=" + snapshot.lastFailure();
		return "Tool result for " + pending.toolName() + ": " + status
			+ " " + pending.details()
			+ " state=" + snapshot.state().name()
			+ failure;
	}

	private static String stringPayloadValue(Map<String, Object> payload, String key) {
		if (payload == null) {
			return null;
		}
		Object value = payload.get(key);
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value);
		return text.isBlank() ? null : text;
	}

	private static Float floatPayloadValue(Map<String, Object> payload, String key) {
		if (payload == null) {
			return null;
		}
		Object value = payload.get(key);
		if (value instanceof Number number) {
			return number.floatValue();
		}
		if (value == null) {
			return null;
		}
		try {
			return Float.parseFloat(String.valueOf(value));
		}
		catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static boolean booleanPayloadValue(Map<String, Object> payload, String key) {
		if (payload == null) {
			return false;
		}
		Object value = payload.get(key);
		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}
		return value != null && Boolean.parseBoolean(String.valueOf(value));
	}

	private static String formatDecimal(float value) {
		if (Math.abs(value - Math.round(value)) < 0.001F) {
			return Integer.toString(Math.round(value));
		}
		String text = String.format(java.util.Locale.ROOT, "%.2f", value);
		int trimIndex = text.length();
		while (trimIndex > 0 && text.charAt(trimIndex - 1) == '0') {
			trimIndex--;
		}
		if (trimIndex > 0 && text.charAt(trimIndex - 1) == '.') {
			trimIndex--;
		}
		return text.substring(0, trimIndex);
	}

	private static Map<String, Object> mapOfNullable(Object... pairs) {
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		for (int index = 0; index + 1 < pairs.length; index += 2) {
			if (pairs[index] != null && pairs[index + 1] != null) {
				map.put(String.valueOf(pairs[index]), pairs[index + 1]);
			}
		}
		return Map.copyOf(map);
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private void prepareClientForEvaluation() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}
		if (client.options != null && client.options.pauseOnLostFocus) {
			client.options.pauseOnLostFocus = false;
			client.options.write();
		}
		if (client.currentScreen != null && "GameMenuScreen".equals(client.currentScreen.getClass().getSimpleName())) {
			client.setScreen(null);
		}
	}

	private void emitEvaluationTrigger(PlannerTriggerType type, String speaker, String message) {
		if (message == null || message.isBlank()) {
			return;
		}
		idleIdeaScheduler.recordActivity();
		String primaryInteractionPlayer = primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null);
		dialogueRuntime.onPlannerTrigger(
			PlannerTrigger.pending(type, speaker, message, tickCount, System.currentTimeMillis()),
			sessionSnapshot,
			primaryInteractionPlayer,
			activeGoal(),
			taskSnapshot,
			missionExecutionSnapshot,
			plannerEventBuffer
		);
	}

	private void joinFirstWorld() {
		List<Map<String, Object>> worlds = singleplayerWorldService.listWorlds();
		if (worlds.isEmpty()) {
			throw new IllegalStateException("No singleplayer worlds are available for session.basic");
		}

		Object worldId = worlds.get(0).get("worldId");
		if (!(worldId instanceof String worldIdValue) || worldIdValue.isBlank()) {
			throw new IllegalStateException("First singleplayer world is missing a valid worldId");
		}

		singleplayerWorldService.joinWorld(worldIdValue);
	}

	private void leaveCurrentWorld() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			throw new IllegalStateException("Minecraft client is not initialized");
		}
		if (client.world == null && client.player == null) {
			return;
		}

		client.disconnect(null, false);
	}

	private GoalPosition findNearbyNavigationTarget() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null || client.player == null) {
			throw new IllegalStateException("Minecraft world is not loaded");
		}

		BlockPos origin = client.player.getBlockPos();
		for (int radius = 1; radius <= 8; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
						continue;
					}
					GoalPosition candidate = findWalkableNavigationTargetInColumn(
						client,
						origin.getX() + dx,
						origin.getZ() + dz,
						origin.getY()
					);
					if (candidate != null) {
						return candidate;
					}
				}
			}
		}

		throw new IllegalStateException("No nearby walkable navigation target was found");
	}

	private GoalPosition findWalkableNavigationTargetInColumn(
		MinecraftClient client,
		int x,
		int z,
		int originY
	) {
		for (int y = originY + 1; y >= originY - 6; y--) {
			BlockPos candidate = new BlockPos(x, y, z);
			if (isWalkableNavigationTarget(client, candidate)) {
				return new GoalPosition(candidate.getX(), candidate.getY(), candidate.getZ(), true);
			}
		}
		return null;
	}

	private boolean isWalkableNavigationTarget(MinecraftClient client, BlockPos target) {
		if (client.world == null || client.player == null) {
			return false;
		}
		if (target.equals(client.player.getBlockPos())) {
			return false;
		}
		BlockPos below = target.down();
		BlockPos above = target.up();
		return client.world.isAir(target)
			&& client.world.isAir(above)
			&& client.world.getBlockState(below).isSideSolidFullSquare(client.world, below, Direction.UP);
	}

	private boolean playerNear(GoalPosition target, double maxDistance) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (target == null || client == null || client.player == null) {
			return false;
		}
		Vec3d center = new Vec3d(target.x() + 0.5D, target.y(), target.z() + 0.5D);
		return client.player.getPos().squaredDistanceTo(center) <= maxDistance * maxDistance;
	}

	private Vec3d playerOffset(double xOffset) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return new Vec3d(xOffset, 64.0D, 0.0D);
		}
		return new Vec3d(
			client.player.getX() + xOffset,
			client.player.getY(),
			client.player.getZ()
		);
	}

	private void setForwardKeyPressed(boolean pressed) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.options == null) {
			throw new IllegalStateException("Minecraft client input is not initialized");
		}
		client.options.forwardKey.setPressed(pressed);
	}

	private boolean isForwardKeyPressed() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client != null
			&& client.options != null
			&& client.options.forwardKey.isPressed();
	}

	private float resolveEffectiveHealthBefore(float observedHealthBefore, float healthAfter) {
		return effectiveHealthBefore(lastKnownPlayerHealth, observedHealthBefore, healthAfter);
	}

	private static Float currentPlayerHealth(MinecraftClient client) {
		if (client == null || client.player == null || !client.isOnThread()) {
			return null;
		}
		return client.player.getHealth();
	}

	static float effectiveHealthBefore(Float lastKnownPlayerHealth, float observedHealthBefore, float healthAfter) {
		if (lastKnownPlayerHealth != null
			&& Float.isFinite(lastKnownPlayerHealth.floatValue())
			&& lastKnownPlayerHealth.floatValue() > healthAfter
		) {
			return lastKnownPlayerHealth.floatValue();
		}
		return observedHealthBefore;
	}

	private record PendingCraftToolResult(
		String taskId,
		CraftRecipeStepArgs craftRecipe,
		long startTick,
		CompletableFuture<String> future
	) {
	}

	private record PendingBlockModificationToolResult(
		String taskId,
		String toolName,
		WorldTaskType taskType,
		LedgerStepKind stepKind,
		String details,
		long startTick,
		CompletableFuture<String> future
	) {
	}

	private enum PendingBlockModificationStopReason {
		POLICY_CANCELLED("policy_cancelled"),
		WORLD_LEFT("world_left"),
		RUNTIME_SHUTDOWN("runtime_shutdown"),
		EVALUATION_FINISHED("evaluation_finished"),
		PLANNER_RESET("planner_reset"),
		SURVIVAL_REFLEX("survival_reflex"),
		PLAYER_DIED("player_died"),
		SUPERSEDED("superseded");

		private final String value;

		PendingBlockModificationStopReason(String value) {
			this.value = value;
		}

		private String result(PendingBlockModificationToolResult pending) {
			return "Tool result for " + pending.toolName() + ": cancelled reason=" + value;
		}
	}

}
