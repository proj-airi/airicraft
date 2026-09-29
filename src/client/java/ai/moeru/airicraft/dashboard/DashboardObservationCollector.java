package ai.moeru.airicraft.dashboard;

import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import ai.moeru.airicraft.agent.debug.LlmFlightRecord;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.multiplayer.ClientLevel;
import ai.moeru.airicraft.debug.ServerTickDebugRuntime;
import com.google.gson.Gson;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class DashboardObservationCollector {
	private static final int SNAPSHOT_INTERVAL_TICKS = 20;
	private static final int LLM_POLL_INTERVAL_TICKS = 5;
	private final DashboardObservationStore store;
	private final DashboardFrameCapture frameCapture;
	private static final Gson GSON = new Gson();
	private ClientLevel observedLevel;
	private String lastDecisionJson;
	private long lastPausedTick = Long.MIN_VALUE;
	private final Supplier<DebugDashboardConfig> configSupplier;
	private final Map<Long, String> llmVersions = new HashMap<>();
	private Long eventCursor;
	private Long timelineCursor;
	private long lastSnapshotTick = Long.MIN_VALUE;
	private long lastLlmPollTick = Long.MIN_VALUE;
	private long highestLlmSequence;

	public DashboardObservationCollector(DashboardObservationStore store) {
		this(store, DebugDashboardConfig::defaults);
	}

	public DashboardObservationCollector(
		DashboardObservationStore store,
		Supplier<DebugDashboardConfig> configSupplier
	) {
		this.store = store;
		this.frameCapture = new DashboardFrameCapture(store);
		this.configSupplier = configSupplier;
	}

	public void startSession(String reason, EmbodiedAgentRuntime runtime) {
		long tick = runtime == null ? 0L : runtime.tickCount();
		store.startSession(reason, tick, System.currentTimeMillis());
		eventCursor = null;
		timelineCursor = null;
		llmVersions.clear();
		lastSnapshotTick = Long.MIN_VALUE;
		lastLlmPollTick = Long.MIN_VALUE;
		highestLlmSequence = 0L;
		lastDecisionJson = null;
		lastPausedTick = Long.MIN_VALUE;
	}

	public void capture(Minecraft minecraft, EmbodiedAgentRuntime runtime) {
		var clock = ServerTickDebugRuntime.controller().status();
		boolean worldLoaded = minecraft != null && minecraft.level != null && minecraft.player != null;
		boolean available = worldLoaded && minecraft.getSingleplayerServer() != null;
		if (!worldLoaded) {
			store.advanceClock(store.serverTickId(), true, false);
			return;
		}
		store.advanceClock(available ? clock.serverTickId() : -1L, clock.paused(), available);
		if (observedLevel != minecraft.level) {
			observedLevel = minecraft.level;
			startSession("world_joined", runtime);
		}
		if (clock.paused() && lastPausedTick == clock.serverTickId()) {
			return;
		}
		captureEventHistory(runtime);
		captureDebugTimeline(runtime);
		// Remote servers do not expose this clock; preserve their dashboard without inventing server ticks.
		long tick = available ? clock.serverTickId() : runtime.tickCount();
		if (clock.paused() || lastLlmPollTick == Long.MIN_VALUE || tick - lastLlmPollTick >= LLM_POLL_INTERVAL_TICKS) {
			captureLlmHistory(runtime, runtime.tickCount());
			lastLlmPollTick = tick;
		}
		// Keep the actual task/reflex decision state at each client decision boundary, including short-lived failures.
		Map<String, Object> decision = new LinkedHashMap<>();
		decision.put("task", runtime.taskExecutionSnapshot());
		decision.put("reflex", runtime.survivalReflexDecisionEvidence());
		decision.put("eventPipeline", runtime.debugEventPipelineState());
		decision.put("eventBus", runtime.debugEventBusState());
		String decisionJson = GSON.toJson(decision);
		if (!decisionJson.equals(lastDecisionJson)) {
			store.appendJson("decision_state", runtime.tickCount(), System.currentTimeMillis(), decisionJson);
			lastDecisionJson = decisionJson;
		}
		if (clock.paused() || lastSnapshotTick == Long.MIN_VALUE || tick - lastSnapshotTick >= SNAPSHOT_INTERVAL_TICKS) {
			Map<String, Object> snapshot = runtimeSnapshot(minecraft, runtime);
			snapshot.put("serverClock", clock);
			snapshot.put("serverClockAvailable", available);
			snapshot.put("frameCapture", frameCapture.status());
			store.append("runtime_snapshot", runtime.tickCount(), System.currentTimeMillis(), snapshot);
			lastSnapshotTick = tick;
		}
		if (clock.paused()) {
			lastPausedTick = tick;
		}
	}

	public void onRenderedFrame(Minecraft minecraft, EmbodiedAgentRuntime runtime) {
		var clock = ServerTickDebugRuntime.controller().status();
		// A server pause can finish between client ticks. Retain the final boundary even when client ticks stop.
		if (clock.paused()) {
			capture(minecraft, runtime);
		}
		frameCapture.onRenderedFrame(minecraft, runtime.tickCount(), clock.serverTickId(), clock.paused(), configSupplier.get());
	}

	public void worldLeft() {
		observedLevel = null;
		store.advanceClock(store.serverTickId(), true, false);
	}

	public void close() {
		frameCapture.close();
	}

	private void captureEventHistory(EmbodiedAgentRuntime runtime) {
		var result = runtime.recentEvents(eventCursor);
		if (result.truncated()) {
			appendGap(runtime.tickCount(), "semantic_event", eventCursor, result.oldestSeqNo());
		}
		for (var event : result.events()) {
			store.append("semantic_event", event.tick(), event.timestampMs(), event);
		}
		if (result.latestSeqNo() > 0L) {
			eventCursor = result.latestSeqNo();
		}
	}

	private void captureDebugTimeline(EmbodiedAgentRuntime runtime) {
		var result = runtime.debugTimeline(timelineCursor);
		if (result.truncated()) {
			appendGap(runtime.tickCount(), "debug_timeline", timelineCursor, result.oldestEntryId());
		}
		for (var entry : result.entries()) {
			store.append("debug_timeline", entry.tick(), entry.timestampMs(), entry);
		}
		if (result.latestEntryId() > 0L) {
			timelineCursor = result.latestEntryId();
		}
	}

	private void captureLlmHistory(EmbodiedAgentRuntime runtime, long tick) {
		long revisitAfter = Math.max(0L, highestLlmSequence - 64L);
		var result = runtime.llmFlightRecords(revisitAfter);
		if (result.truncated()) {
			appendGap(tick, "llm_call", revisitAfter, result.oldestSequenceId());
		}
		for (LlmFlightRecord record : result.records()) {
			highestLlmSequence = Math.max(highestLlmSequence, record.sequenceId());
			String version = record.status() + ':' + record.completedAtMs() + ':' + record.rawResponseBody().hashCode()
				+ ':' + record.failureMessage().length();
			if (!version.equals(llmVersions.put(record.sequenceId(), version))) {
				long capturedAt = record.status().equals("STREAMING") ? System.currentTimeMillis()
					: record.completedAtMs() > 0L ? record.completedAtMs() : record.requestedAtMs();
				store.append("llm_call", tick, capturedAt, llmPayload(store, record, tick, capturedAt));
			}
		}
		if (llmVersions.size() > 4096) {
			long keepFrom = Math.max(result.oldestSequenceId(), highestLlmSequence - 512L);
			llmVersions.keySet().removeIf(sequence -> sequence < keepFrom);
		}
	}

	private void appendGap(long tick, String stream, Long requestedAfter, long oldestAvailable) {
		store.append("observation_gap", tick, System.currentTimeMillis(), Map.of(
			"stream", stream,
			"requestedAfter", requestedAfter == null ? 0L : requestedAfter,
			"oldestAvailable", oldestAvailable
		));
	}

	private Map<String, Object> runtimeSnapshot(Minecraft minecraft, EmbodiedAgentRuntime runtime) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("schemaVersion", 4);
		payload.put("system2", runtime.debugSystem2());
		var agent = runtime.snapshot();
		payload.put("agent", Map.of("initialized", agent.initialized(), "tickCount", agent.tickCount(), "session", agent.session()));
		payload.put("planner", plannerPayload(store, runtime.plannerDebugSnapshot(), runtime.tickCount(), System.currentTimeMillis()));
		payload.put("dialogue", Map.of("observationSequence", store.appendContext("dialogue_history", runtime.tickCount(),
			System.currentTimeMillis(), runtime.dialogueSnapshot()).sequence()));
		payload.put("dialogueState", runtime.debugDialogueState());
		payload.put("conversationSources", Map.of("observationSequence", store.appendContext("conversation_sources",
			runtime.tickCount(), System.currentTimeMillis(), runtime.debugConversationSources()).sequence()));
		payload.put("activeGoal", runtime.activeGoal().orElse(null));
		payload.put("activeJob", runtime.activeJob());
		payload.put("task", runtime.taskSnapshot());
		payload.put("taskExecution", runtime.taskExecutionSnapshot());
		payload.put("missionExecution", missionPayload(store, runtime.missionExecutionSnapshot(), runtime.tickCount(), System.currentTimeMillis()));
		payload.put("reflex", runtime.survivalReflexSnapshot());
		payload.put("reflexDecision", runtime.survivalReflexDecisionEvidence());
		Map<String, Object> actionGraph = new LinkedHashMap<>(runtime.actionGraphGoalsPayload(false));
		actionGraph.put("executions", runtime.actionGraphExecutions().stream().map(view -> {
			var execution = view.execution();
			var context = store.appendContext("action_graph_execution", execution.executionId(), runtime.tickCount(),
				System.currentTimeMillis(), view.toPayload(true));
			var detail = view.toPayload(false);
			Map<String, Object> summary = new LinkedHashMap<>();
			for (String key : java.util.List.of("executionId", "state", "goal", "residency", "failureCode", "message", "activeTaskId")) {
				var value = detail.get(key);
				if (value != null) summary.put(key, value);
			}
			summary.put("observationSequence", context.sequence());
			return summary;
		}).toList());
		payload.put("actionGraph", actionGraph);
		payload.put("behaviorTree", runtime.behaviorTreeSnapshot());
		payload.put("eventPipeline", runtime.debugEventPipelineState());
		payload.put("eventBus", runtime.debugEventBusState());
		payload.put("attention", runtime.debugAttentionState());
		payload.put("perception", runtime.debugPerceptionState());
		payload.put("taskProgressProbe", runtime.debugCollectResourceState());
		payload.put("chatProbe", runtime.debugChatState());
		payload.put("observability", runtime.observabilityDebugSnapshot());
		payload.put("plannerEnabled", runtime.plannerEnabled());
		payload.put("degraded", runtime.isDegraded());
		payload.put("llmAvailable", runtime.llmAvailable());
		payload.put("visionAvailable", runtime.visionAvailable());
		payload.put("world", worldSnapshot(minecraft));
		payload.put("placePreservation", ai.moeru.airicraft.agent.memory.WorldPlacePreservation.debugSnapshot());
		return payload;
	}

	static com.google.gson.JsonObject plannerPayload(DashboardObservationStore store,
		ai.moeru.airicraft.agent.llm.PlannerOrchestratorDebugSnapshot planner, long tick, long capturedAtMs) {
		var payload = GSON.toJsonTree(planner).getAsJsonObject();
		if (planner.baseRequest() != null && planner.baseRequest().missionExecution() != null) {
			payload.getAsJsonObject("baseRequest").add("missionExecution", GSON.toJsonTree(
				missionPayload(store, planner.baseRequest().missionExecution(), tick, capturedAtMs, "planner_base_request")));
		}
		return payload;
	}

	static com.google.gson.JsonObject llmPayload(DashboardObservationStore store, LlmFlightRecord record, long tick, long capturedAtMs) {
		var payload = GSON.toJsonTree(record).getAsJsonObject();
		if (!record.requestBody().isEmpty()) {
			var request = store.appendContext("llm_request", Long.toString(record.sequenceId()), tick, capturedAtMs,
				Map.of("requestBody", record.requestBody(), "requestedAtMs", record.requestedAtMs(),
					"dispatchTick", record.dispatchTick(), "dispatchServerTick", record.dispatchServerTick(),
					"decisionContext", record.decisionContext()));
			payload.remove("requestBody");
			payload.add("request", GSON.toJsonTree(Map.of("observationSequence", request.sequence())));
		}
		return payload;
	}

	static Map<String, Object> missionPayload(DashboardObservationStore store, MissionExecutionSnapshot mission, long tick, long capturedAtMs) {
		return missionPayload(store, mission, tick, capturedAtMs, "recipe_catalog");
	}

	private static Map<String, Object> missionPayload(DashboardObservationStore store, MissionExecutionSnapshot mission,
		long tick, long capturedAtMs, String catalogKey) {
		if (mission == null) return Map.of();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("mission", mission.mission());
		result.put("ledger", mission.ledger());
		result.put("activeStep", mission.activeStep());
		result.put("lastStepResult", mission.lastStepResult());
		result.put("primitiveExecution", mission.primitiveExecution());
		var evidence = mission.evidence();
		if (evidence != null) {
			var catalog = store.appendContext("recipe_catalog", catalogKey, tick, capturedAtMs,
				Map.of("knownCrafts", evidence.knownCrafts(), "knownSmelts", evidence.knownSmelts()));
			Map<String, Object> facts = new LinkedHashMap<>();
			facts.put("recipeCatalogSequence", catalog.sequence());
			facts.put("knownCraftCount", evidence.knownCrafts().size());
			facts.put("knownSmeltCount", evidence.knownSmelts().size());
			facts.put("inventoryCounts", evidence.inventoryCounts());
			facts.put("itemCounts", evidence.itemCounts());
			facts.put("nearbyBlocks", evidence.nearbyBlocks());
			facts.put("availableCrafts", evidence.availableCrafts());
			facts.put("availableSmelts", evidence.availableSmelts());
			facts.put("dimension", evidence.dimension());
			facts.put("x", evidence.x());
			facts.put("y", evidence.y());
			facts.put("z", evidence.z());
			facts.put("equippedItemId", evidence.equippedItemId());
			facts.put("selectedHotbarSlot", evidence.selectedHotbarSlot());
			facts.put("hotbarItems", evidence.hotbarItems());
			facts.put("tick", evidence.tick());
			result.put("evidence", facts);
		}
		return result;
	}

	private static Map<String, Object> worldSnapshot(Minecraft minecraft) {
		Map<String, Object> world = new LinkedHashMap<>();
		world.put("loaded", minecraft != null && minecraft.level != null && minecraft.player != null);
		world.put("screen", minecraft == null || minecraft.screen == null
			? "none"
			: minecraft.screen.getClass().getSimpleName());
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			return world;
		}
		var player = minecraft.player;
		world.put("dimension", minecraft.level.dimension().location().toString());
		world.put("time", minecraft.level.getGameTime());
		world.put("timeOfDay", minecraft.level.getDayTime());
		world.put("raining", minecraft.level.isRaining());
		world.put("thundering", minecraft.level.isThundering());
		Map<String, Object> playerSnapshot = new LinkedHashMap<>();
		playerSnapshot.put("name", player.getName().getString());
		playerSnapshot.put("x", player.getX());
		playerSnapshot.put("y", player.getY());
		playerSnapshot.put("z", player.getZ());
		playerSnapshot.put("yaw", player.getYRot());
		playerSnapshot.put("pitch", player.getXRot());
		playerSnapshot.put("health", player.getHealth());
		playerSnapshot.put("maxHealth", player.getMaxHealth());
		playerSnapshot.put("food", player.getFoodData().getFoodLevel());
		playerSnapshot.put("air", player.getAirSupply());
		playerSnapshot.put("onGround", player.onGround());
		playerSnapshot.put("submerged", player.isUnderWater());
		world.put("player", playerSnapshot);
		Map<String, Integer> inventory = new LinkedHashMap<>();
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			var stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty()) {
				inventory.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
			}
		}
		world.put("inventory", inventory);
		world.put("selectedHotbarSlot", player.getInventory().getSelectedSlot());
		world.put("equippedItem", BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString());
		return world;
	}
}
