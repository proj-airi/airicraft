package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.debug.LlmFlightRecorder;
import ai.moeru.airicraft.agent.session.SessionMode;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlannerStreamingTest {
	private static final LlmConversation CONVERSATION = LlmConversation.of(List.of(LlmChatMessage.system("test")));

	@Test void providerEnvelopeOverheadDoesNotCountAgainstPayloadCap() {
		var stream = new OpenAiChatStream(ignored -> { });
		String envelope = "x".repeat(400);
		// 20k chunks of one reasoning token each carry ~8 MiB of provider status text but almost no payload.
		for (int i = 0; i < 20_000; i++) {
			stream.onNext("data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"a\"},\"runinfra\":{\"m\":\"" + envelope + "\"}}]}");
			stream.onNext("");
		}
		stream.onNext("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		stream.onNext("");
		assertTrue(stream.response().contains("\"ok\""));
		var big = new OpenAiChatStream(ignored -> { });
		String chunk = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + "y".repeat(1 << 20) + "\"}}]}";
		assertThrows(com.google.gson.JsonParseException.class, () -> { for (int i = 0; i < 5; i++) { big.onNext(chunk); big.onNext(""); } });
	}

	@Test void streamsBeforeCompletionAndAssemblesReasoningToolsAndUsage() throws Exception {
		var firstToken = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var body = new AtomicReference<String>();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/chat/completions", exchange -> {
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try (var out = exchange.getResponseBody()) {
				out.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"看洞穴\",\"content\":null,\"tool_calls\":null}}]}\r\n\r\n".getBytes(StandardCharsets.UTF_8));
				out.flush();
				try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				out.write(("data: {\"model\":\"qwen-test\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"navigate_to\",\"arguments\":\"{\\\"x\\\":\"}}]}}]}\n\n"
					+ "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"1,\\\"y\\\":64,\\\"z\\\":2,\\\"exactY\\\":true}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
					+ "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n"
					+ "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
			}
		});
		server.start();
		try {
			var backend = new OpenAiCompatibleLlmBackend(config(server.getAddress().getPort(), 5000));
			var request = new PlannerBackendRequest(1, 1, PlannerSessionPhase.PLANNER_REQUEST,
				new PlannerRequest(1, 1, SessionMode.OUT_OF_WORLD, null, null, "Tester", "look", null), CONVERSATION);
			var preview = new PlannerStreamPreview();
			var result = CompletableFuture.supplyAsync(() -> {
				try { return backend.generate(request, delta -> { preview.append(delta); if (delta.contains("洞穴")) firstToken.countDown(); }); }
				catch (LlmBackendException e) { throw new RuntimeException(e); }
			});
			assertTrue(firstToken.await(3, TimeUnit.SECONDS));
			assertFalse(result.isDone(), "tokens must arrive while provider response is unfinished");
			assertTrue(preview.text().contains("看洞穴"));
			release.countDown();
			var completed = result.get(3, TimeUnit.SECONDS);
			assertEquals(1, completed.payload().toolCall().arguments().get("x").getAsInt());
			assertEquals("call_1", completed.payload().toolCall().id());
			assertEquals(15, completed.usage().totalTokens());
			assertEquals("qwen-test", completed.responseModel());
			assertTrue(completed.payload().rawAssistantContent().toString().contains("看洞穴"));
			assertTrue(JsonParser.parseString(body.get()).getAsJsonObject().get("stream").getAsBoolean());
		} finally { release.countDown(); server.stop(0); }
	}

	@Test void interruptedStreamCannotBecomeAnExecutableResponse() {
		var stream = new OpenAiChatStream(ignored -> {});
		stream.onNext("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}");
		stream.onNext("");
		assertThrows(com.google.gson.JsonParseException.class, stream::response);
	}

	@Test void bodyStallTimesOutAfterHeaders() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var release = new CountDownLatch(1);
		server.createContext("/chat/completions", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try (var out = exchange.getResponseBody()) {
				out.write(": waiting\n\n".getBytes(StandardCharsets.UTF_8)); out.flush();
				try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			}
		});
		server.start();
		try {
			var backend = new OpenAiCompatibleLlmBackend(config(server.getAddress().getPort(), 200));
			assertEquals(LlmFailureType.TIMEOUT, assertThrows(LlmBackendException.class, () -> backend.generate(CONVERSATION)).failureType());
		} finally { release.countDown(); server.stop(0); }
	}

	@Test void workerDeltasStayWithTheirRequestAndPreviewIsBounded() throws Exception {
		var recorder = new LlmFlightRecorder(8);
		recorder.recordRequest("planner", "one", "test", URI.create("http://localhost"), "test", 1000, CONVERSATION, "{}");
		var listener = recorder.streamListener();
		CompletableFuture.runAsync(() -> listener.accept("a".repeat(20_000))).get();
		var record = recorder.query(null).records().getFirst();
		assertEquals(1, recorder.query(null).records().size());
		assertEquals("STREAMING", record.status());
		assertTrue(record.rawResponseBody().length() < 17_000);
		recorder.recordFailure("TIMEOUT", "test");
		listener.accept("late");
		assertFalse(recorder.query(null).records().getFirst().rawResponseBody().endsWith("late"));
	}

	@Test void detachedOrResetGenerationsCannotKeepShowingLateTokens() throws Exception {
		var started = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var callback = new AtomicReference<java.util.function.Consumer<String>>();
		LlmBackend backend = new LlmBackend() {
			public LlmCallResult<PlannerResponse> generate(LlmConversation conversation) { throw new AssertionError("stream overload required"); }
			public LlmCallResult<PlannerResponse> generate(PlannerBackendRequest request, java.util.function.Consumer<String> preview) {
				callback.set(preview);
				preview.accept("first token");
				started.countDown();
				try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				return LlmCallResult.of(new PlannerResponse("done", List.of(), null), LlmUsageSnapshot.unknown(), 200, "test");
			}
			public void injectMockResponse(PlannerResponse response) { }
			public void injectTimeout() { }
			public boolean isConfigured() { return true; }
		};
		var executor = new PlannerExecutor(backend);
		try {
			var request = new PlannerRequest(1, 1, SessionMode.OUT_OF_WORLD, null, null, "Tester", "look", null);
			executor.submit(1, 1, PlannerSessionPhase.PLANNER_REQUEST, request, CONVERSATION);
			assertTrue(started.await(2, TimeUnit.SECONDS));
			assertTrue(executor.streamPreview(1).orElseThrow().text().contains("first token"));
			assertTrue(executor.streamPreview(2).isEmpty());
			executor.pauseGeneration(1);
			callback.get().accept("late token");
			assertTrue(executor.streamPreview(1).isEmpty());
			executor.reset();
			assertTrue(executor.streamPreview(1).isEmpty());
		} finally { release.countDown(); executor.shutdown(); }
	}

	private static AgentConfig.LlmConfig config(int port, int timeout) {
		return new AgentConfig.LlmConfig("http://127.0.0.1:" + port, "test-key", "qwen-test", "", "", "", timeout, 10000, 8, 65536, "low", false, false);
	}
}
