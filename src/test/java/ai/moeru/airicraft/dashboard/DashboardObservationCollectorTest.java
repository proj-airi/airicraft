package ai.moeru.airicraft.dashboard;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.List;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import com.google.gson.Gson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardObservationCollectorTest {
	@Test
	void requestMetadataSeparatesContextDispatchAndCollectionClocks() {
		var events = new ai.moeru.airicraft.agent.events.SemanticEventBuffer(4);
		events.append(10, "task.failed", Map.of("workId", "wood"));
		var context = new ai.moeru.airicraft.agent.llm.PlannerDecisionContext("world-A", 10, 8,
			"controller", "idle", Map.of("job", Map.of("id", "wood")), events.query(null));
		var recorder = new ai.moeru.airicraft.agent.debug.LlmFlightRecorder(4);
		var dispatch = new java.util.concurrent.atomic.AtomicLong(12);
		recorder.configureClock(dispatch::get, () -> dispatch.get() - 2);
		recorder.recordRequest("planner", "one", "test", java.net.URI.create("http://localhost"), "qwen", 1000,
			ai.moeru.airicraft.agent.llm.LlmConversation.of(ai.moeru.airicraft.agent.llm.PlannerObservation.exchange(context.observation(0))), "request");
		dispatch.set(30);
		var store = new DashboardObservationStore(1024L * 1024L);
		store.advanceClock(28, false, true);
		DashboardObservationCollector.llmPayload(store, recorder.query(null).records().getFirst(), 30, 100);
		var request = com.google.gson.JsonParser.parseString(store.retainedObservations().getFirst().payloadJson()).getAsJsonObject();
		assertEquals(12, request.get("dispatchTick").getAsLong());
		assertEquals(10, request.get("dispatchServerTick").getAsLong());
		assertEquals(10, request.getAsJsonObject("decisionContext").get("tick").getAsLong());
		assertEquals(1, request.getAsJsonObject("decisionContext").get("throughEventSequence").getAsLong());
		assertEquals(30, store.retainedObservations().getFirst().tick());
	}

	@Test
	void plannerBaseRequestDoesNotDuplicateRecipeCatalogInEveryRuntimeSnapshot() {
		var recipes = java.util.stream.IntStream.range(0, 2000)
			.mapToObj(i -> new CraftingOpportunity("recipe-" + i, "minecraft:bread", 1, List.of("minecraft:wheat")))
			.toList();
		var evidence = new WorldEvidence(Map.of(), Map.of("minecraft:wheat", 3), Map.of(), List.of(), recipes,
			List.of(), List.of(), "minecraft:overworld", 1, 64, 2, "minecraft:air", 0, List.of(), 10);
		var mission = new MissionExecutionSnapshot(null, null, null, evidence, null, null);
		var request = new ai.moeru.airicraft.agent.llm.PlannerRequest(10, 100,
			ai.moeru.airicraft.agent.session.SessionMode.SINGLEPLAYER_LAN_HOST, "Alice", null, null, mission,
			ai.moeru.airicraft.agent.llm.PlannerTriggerBatch.of(List.of()), "tool-result", 7, "hold-7");
		var planner = new ai.moeru.airicraft.agent.llm.PlannerOrchestratorDebugSnapshot(true, "external_summary",
			true, true, false, false, false, false, request, null, null, 2, "TOOL_FOLLOW_UP", 1, 0, 0,
			false, 0, false, 0, 0);
		var store = new DashboardObservationStore(1024L * 1024L);
		store.advanceClock(10, false, true);
		var first = DashboardObservationCollector.plannerPayload(store, planner, 10, 100);
		store.append("runtime_snapshot", 10, 100, Map.of("planner", first));
		store.advanceClock(30, false, true);
		// Current world knowledge can differ from the request captured before an action.
		var current = new WorldEvidence(Map.of(), Map.of(), Map.of(), List.of(), recipes.subList(0, 1),
			List.of(), List.of(), "minecraft:overworld", 1, 64, 2, "minecraft:air", 0, List.of(), 30);
		var currentMission = new MissionExecutionSnapshot(null, null, null, current, null, null);
		DashboardObservationCollector.missionPayload(store, currentMission, 30, 200);
		var second = DashboardObservationCollector.plannerPayload(store, planner, 30, 200);
		store.append("runtime_snapshot", 30, 200, Map.of("planner", second));
		assertTrue(second.toString().length() < 5000, "base request must reference the catalog, not copy it");
		DashboardObservationCollector.missionPayload(store, currentMission, 30, 200);
		assertEquals(2, store.retainedObservations().stream().filter(o -> o.type().equals("recipe_catalog")).count());
		var base = second.getAsJsonObject("baseRequest");
		assertEquals("tool-result", base.get("toolResult").getAsString());
		assertEquals(7, base.get("safetyEpoch").getAsLong());
		var facts = base.getAsJsonObject("missionExecution").getAsJsonObject("evidence");
		assertEquals(3, facts.getAsJsonObject("itemCounts").get("minecraft:wheat").getAsInt());
		assertEquals(2000, facts.get("knownCraftCount").getAsInt());
		assertEquals(recipes, request.missionExecution().evidence().knownCrafts());
		assertEquals(first, second);
		// Timeline seek must retain the recipes needed to reconstruct the request.
		assertTrue(store.seek(30)
			.get("observations").toString().contains("recipe-1999"));
	}

	@Test
	void streamingUpdatesReferenceOneRequestEnvelope() {
		var store = new DashboardObservationStore(1024L * 1024L);
		var recorder = new ai.moeru.airicraft.agent.debug.LlmFlightRecorder(8);
		recorder.recordRequest("planner", "one", "test", java.net.URI.create("http://localhost"), "qwen", 1000,
			null, "large prompt ".repeat(1000));
		var stream = recorder.streamListener();
		store.advanceClock(10, false, true);
		stream.accept("first token");
		var first = DashboardObservationCollector.llmPayload(store, recorder.query(null).records().getFirst(), 10, 100);
		store.advanceClock(20, false, true);
		stream.accept(" second token");
		var second = DashboardObservationCollector.llmPayload(store, recorder.query(null).records().getFirst(), 20, 200);
		assertEquals(1, store.retainedObservations().size());
		assertEquals(20, store.retainedObservations().getFirst().throughServerTickId());
		assertEquals(first.get("request"), second.get("request"));
		assertTrue(!second.has("requestBody"));
		assertTrue(second.toString().length() < 1000);
		assertTrue(second.get("rawResponseBody").getAsString().endsWith("second token"));
	}

	@Test
	void missionSnapshotsReferenceOneCatalogAndKeepCurrentEvidence() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		var recipe = new CraftingOpportunity("bread", "minecraft:bread", 1, List.of("minecraft:wheat", "minecraft:wheat", "minecraft:wheat"));
		var evidence = new WorldEvidence(Map.of(), Map.of("minecraft:wheat", 3), Map.of(),
			List.of(recipe), List.of(recipe), List.of(), List.of(), "minecraft:overworld", 1, 64, 2,
			"minecraft:air", 0, List.of(), 10);
		var mission = new MissionExecutionSnapshot(null, null, null, evidence, null, null);
		store.advanceClock(10, false, true);
		var first = DashboardObservationCollector.missionPayload(store, mission, 10, 100);
		store.advanceClock(30, false, true);
		var second = DashboardObservationCollector.missionPayload(store, mission, 30, 200);
		assertEquals(1, store.retainedObservations().size());
		assertEquals(30, store.retainedObservations().getFirst().throughServerTickId());
		assertEquals(first, second);
		var facts = (Map<?, ?>) second.get("evidence");
		assertEquals(Map.of("minecraft:wheat", 3), facts.get("itemCounts"));
		assertEquals(List.of(recipe), facts.get("availableCrafts"));
		assertEquals(store.retainedObservations().getFirst().sequence(), facts.get("recipeCatalogSequence"));
		assertTrue(!new Gson().toJson(second).contains("knownCrafts"));
		assertEquals(List.of(recipe), evidence.knownCrafts());
	}

	@Test
	void aPostDisconnectClientTickPreservesTheLastExportableWindow() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		store.advanceClock(500L, false, true);
		store.append("runtime_snapshot", 900L, 100L, Map.of("state", "failed"));
		DashboardObservationCollector collector = new DashboardObservationCollector(store);
		try {
			collector.worldLeft();
			collector.capture(null, null);
			assertEquals(500L, store.serverTickId());
			assertTrue(store.snapshot().paused());
			assertTrue(store.recordingPage(0L, store.serverTickId(), 0L, Long.MAX_VALUE, 10, Set.of(), false)
				.get("observations").toString().contains("failed"));
		}
		finally {
			collector.close();
		}
	}
}
