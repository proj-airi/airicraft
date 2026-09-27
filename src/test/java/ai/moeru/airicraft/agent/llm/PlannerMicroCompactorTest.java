package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlannerMicroCompactorTest {
	private static LlmConversation observation(String id, String tool, String raw) {
		return LlmConversation.of(List.of(LlmChatMessage.user("Fix the hole in the wall", LlmMessageKind.TASK),
			LlmChatMessage.assistantToolCall("", new PlannerToolCall(id, tool, new JsonObject(), null)), LlmChatMessage.tool(id, raw)));
	}
	private static String summary(String id) {
		return "{\"findings\":[{\"sourceToolCallId\":\"" + id + "\",\"result\":null,\"memory\":\"West wall intact; east wall not checked\"}]}";
	}
	@Test void keepsRawWhilePendingThenReplacesOnlyMatchingObservationInFutureContexts() {
		var future = new CompletableFuture<String>();
		var prompts = new ArrayList<LlmConversation>();
		var service = new PlannerMicroCompactor(c -> { prompts.add(c); return future; });
		var raw = observation("q", "inspect_world", "RAW_WEST_WALL");
		assertEquals(raw, service.update(raw));
		assertEquals(1, prompts.size());
		assertTrue(prompts.getFirst().messages().getFirst().content().contains("Do not call tools"));
		var later = raw.withAppended(LlmChatMessage.user("Also repair the roof", LlmMessageKind.USER_TURN));
		assertEquals(later, service.update(later));
		future.complete(summary("q"));
		var compacted = service.update(later);
		assertTrue(compacted.messages().getLast().content().contains("roof"));
		assertTrue(compacted.messages().get(2).content().contains("West wall intact"));
		assertFalse(compacted.messages().get(2).content().contains("RAW_"));
		assertEquals("RAW_WEST_WALL", raw.messages().get(2).content(), "In-flight request snapshot stays immutable");
		assertFalse(service.update(raw).messages().get(2).content().contains("RAW_"), "Old planner responses cannot resurrect raw results");
		assertEquals(1, prompts.size());
	}
	@Test void fullCompactionAndResetDiscardLateMicroResultsWithoutRestoringOldHistory() {
		var future = new CompletableFuture<String>() {
			@Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
		};
		var service = new PlannerMicroCompactor(c -> future);
		service.update(observation("q", "query_world", "RAW_QUERY"));
		service.reset(); // Successful full compaction establishes a new context epoch.
		var checkpoint = LlmConversation.of(List.of(LlmChatMessage.user("Full checkpoint", LlmMessageKind.CHECKPOINT)));
		future.complete(summary("q"));
		assertEquals(checkpoint, service.update(checkpoint));
	}
	@Test void failureOrChangedObservationNeverDropsRawEvidence() {
		var future = new CompletableFuture<String>();
		var service = new PlannerMicroCompactor(c -> future);
		var raw = observation("q", "inspect_inventory", "RAW_INVENTORY");
		service.update(raw); future.complete("{\"findings\":[]}");
		assertEquals(raw, service.update(raw));
		assertEquals(raw, service.update(raw));
	}
	@Test void queuedReceiptsAndRetainedFindingsDoNotStartAnotherRequest() {
		var service = new PlannerMicroCompactor(c -> { fail("Not a completed observation"); return null; });
		var queued = observation("q", "inspect_work", "QUEUED: awaiting execution");
		assertEquals(queued, service.update(queued));
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
	void fullCheckpointWinsRegardlessOfMicroCompletionOrder(boolean microFirst) {
		var future = new CompletableFuture<String>();
		var registry = PlannerToolRegistry.empty(); registry.freezeToolPrefix();
		var context = new PlannerContextAggregator(java.time.Clock.systemUTC(), 65536, 128, PlannerVisionMode.EXTERNAL_SUMMARY, registry);
		context.configureMicroCompaction(new PlannerMicroCompactor(c -> future));
		context.retainConversation(observation("q", "inspect_world", "RAW_WALL"));
		var fullCompactionInput = context.buildCompactionConversation();
		if (microFirst) { future.complete(summary("q")); context.refreshMicroCompaction(); }
		context.applyCheckpoint(new CompactionCheckpoint("now", "overworld", "repair", List.of(), List.of("Full checkpoint wall evidence"), List.of(), List.of(), List.of(), List.of()));
		future.complete(summary("q")); context.refreshMicroCompaction();
		assertTrue(context.retainedToolContext().messages().toString().contains("Full checkpoint wall evidence"));
		assertFalse(context.retainedToolContext().messages().toString().contains("RAW_WALL"));
		assertFalse(context.retainedToolContext().messages().toString().contains("West wall intact"));
		assertTrue(fullCompactionInput.messages().toString().contains("RAW_WALL"), "Full-compaction request remains immutable");
	}
	@Test void failedFullCompactionStillAllowsPendingMicroCompactionToFinish() {
		var future = new CompletableFuture<String>();
		var registry = PlannerToolRegistry.empty(); registry.freezeToolPrefix();
		var context = new PlannerContextAggregator(java.time.Clock.systemUTC(), 65536, 128, PlannerVisionMode.EXTERNAL_SUMMARY, registry);
		context.configureMicroCompaction(new PlannerMicroCompactor(c -> future));
		context.retainConversation(observation("q", "inspect_world", "RAW_WALL"));
		context.buildCompactionConversation();
		context.onCompactionFailure();
		assertTrue(context.retainedToolContext().messages().toString().contains("RAW_WALL"));
		future.complete(summary("q")); context.refreshMicroCompaction();
		assertTrue(context.retainedToolContext().messages().toString().contains("West wall intact"));
		assertFalse(context.retainedToolContext().messages().toString().contains("RAW_WALL"));
	}

	@Test void allInspectionFamiliesAreEligibleAndOrdinaryActionsAreNot() {
		for (String name : List.of("inspect_world", "inspect_inventory", "inspect_work", "inspect_smelting", "query_world", "check_craftables", "survey_cave", "take_a_look")) {
			var calls = new ArrayList<LlmConversation>();
			var service = new PlannerMicroCompactor(c -> { calls.add(c); return new CompletableFuture<>(); });
			service.update(observation("q", name, "RAW"));
			assertEquals(1, calls.size(), name);
		}
		var service = new PlannerMicroCompactor(c -> { fail("Action receipt is not an inspection"); return null; });
		service.update(observation("action", "place_block", "Placed stone"));
	}
	@Test void matchingIdWithDifferentEvidenceDoesNotReceiveStaleSummary() {
		var first = new CompletableFuture<String>();
		var second = new CompletableFuture<String>();
		var requests = new java.util.concurrent.atomic.AtomicInteger();
		var service = new PlannerMicroCompactor(c -> requests.getAndIncrement() == 0 ? first : second);
		service.update(observation("q", "query_world", "RAW_OLD"));
		first.complete(summary("q"));
		var fresh = observation("q", "query_world", "RAW_NEW");
		assertEquals(fresh, service.update(fresh));
		assertEquals(2, requests.get());
	}
	@Test void backgroundRequestHasNoToolsAndRetainsTaskSpecificNegativeEvidence() throws Exception {
		var request = new java.util.concurrent.atomic.AtomicReference<JsonObject>();
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", exchange -> {
			request.set(com.google.gson.JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
			var message = new JsonObject(); message.addProperty("content", summary("q"));
			var choice = new JsonObject(); choice.add("message", message);
			var choices = new com.google.gson.JsonArray(); choices.add(choice);
			var response = new JsonObject(); response.add("choices", choices);
			byte[] bytes = response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes); exchange.close();
		});
		server.start();
		var config = new ai.moeru.airicraft.agent.AgentConfig.LlmConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
			"planner-key", "planner-model", "http://127.0.0.1:1", "external-key", "external-model",
			15_000, 10_000, 8, 65_536, "low", true);
		try (var service = new PlannerMicroCompactor(config, ai.moeru.airicraft.agent.observability.NoopObservability.INSTANCE, PlannerToolRegistry.empty())) {
			var raw = observation("q", "inspect_world", "RAW_WALL");
			var current = service.update(raw);
			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
			while (!current.messages().get(2).content().startsWith(PlannerMicroCompactor.RETAINED) && System.nanoTime() < deadline) {
				Thread.sleep(10); current = service.update(current);
			}
			assertTrue(current.messages().get(2).content().contains("West wall intact"));
			assertEquals("RAW_WALL", raw.messages().get(2).content());
			assertEquals("planner-model", request.get().get("model").getAsString());
			assertFalse(request.get().has("tools"));
			assertFalse(request.get().has("tool_choice"));
			assertTrue(request.get().toString().contains("Fix the hole in the wall"));
			assertTrue(request.get().toString().contains("RAW_WALL"));
		} finally { server.stop(0); }
	}

}
