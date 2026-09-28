package ai.moeru.airicraft.agent.recording;

import ai.moeru.airicraft.agent.debug.LlmFlightRecorder;
import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeFlightRecorderTest {
	@TempDir Path root;

	@Test void persistsCompletionUnderTheOriginalCallIdWithoutRepeatingItEveryTick() throws Exception {
		var source = new LlmFlightRecorder();
		var writer = new RuntimeFlightRecorder(root);
		source.recordRequest("planner", "thread", "test", null, "model", 1000, null, "request");
		writer.drainLlmCalls(source::query, "start");
		source.streamListener().accept("partial");
		writer.drainLlmCalls(source::query, "stream");
		source.recordRawResponse(200, "model", LlmUsageSnapshot.unknown(), "complete response");
		writer.drainLlmCalls(source::query, "raw");
		source.recordParsedResponse("tool_call", 200, "model", LlmUsageSnapshot.unknown(), "something_wrong");
		writer.drainLlmCalls(source::query, "complete");
		writer.drainLlmCalls(source::query, "unchanged");
		var records = records();
		assertEquals(List.of("REQUESTED", "COMPLETED"), records.stream().map(r -> r.get("status").getAsString()).toList());
		assertEquals(1, records.getLast().get("sequenceId").getAsLong());
		assertEquals("complete response", records.getLast().get("rawResponseBody").getAsString());
		assertEquals("something_wrong", records.getLast().get("parsedResponse").getAsString());
	}

	@Test void laterCallsCanFinishFirstWithoutLosingEarlierFailures() throws Exception {
		var source = new LlmFlightRecorder();
		var writer = new RuntimeFlightRecorder(root);
		var requested = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var first = CompletableFuture.runAsync(() -> {
			source.recordRequest("planner", "first", "test", null, "model", 1000, null, "first request");
			requested.countDown();
			try { release.await(); }
			catch (InterruptedException exception) { throw new RuntimeException(exception); }
			source.recordFailure("timeout", "first call timed out");
		});
		try {
			assertTrue(requested.await(2, java.util.concurrent.TimeUnit.SECONDS));
			writer.drainLlmCalls(source::query, "first");
			source.recordRequest("planner", "second", "test", null, "model", 1000, null, "second request");
			source.recordParsedResponse("reply", 200, "model", LlmUsageSnapshot.unknown(), "second reply");
			writer.drainLlmCalls(source::query, "second");
		}
		finally { release.countDown(); }
		first.get(2, java.util.concurrent.TimeUnit.SECONDS);
		writer.drainLlmCalls(source::query, "late first");
		writer.drainLlmCalls(source::query, "unchanged");
		var records = records();
		assertEquals(List.of(1L, 2L, 1L), records.stream().map(r -> r.get("sequenceId").getAsLong()).toList());
		assertEquals("FAILED", records.getLast().get("status").getAsString());
		assertEquals("first call timed out", records.getLast().get("failureMessage").getAsString());
	}

	@Test void appendsEachAttentionDecisionOnceWithItsInputs() throws Exception {
		var log = new ai.moeru.airicraft.agent.attention.AttentionDecisionLog();
		var writer = new RuntimeFlightRecorder(root);
		var inputs = new ai.moeru.airicraft.agent.attention.AttentionDecision.Inputs(
			ai.moeru.airicraft.agent.attention.AttentionState.idle(), ai.moeru.airicraft.agent.attention.AttentionEvidence.NONE, true,
			ai.moeru.airicraft.agent.events.EventRoutingProfile.rawOnly("task.notice"), List.of());
		for (long seq = 1; seq <= 2; seq++) {
			log.record(new ai.moeru.airicraft.agent.attention.AttentionDecision(seq, seq, "task.notice", false,
				ai.moeru.airicraft.agent.attention.Delivery.NONE, ai.moeru.airicraft.agent.attention.Urgency.LOW,
				ai.moeru.airicraft.agent.attention.AttentionStage.RULES, "catalog.semantic", "", false, inputs));
			writer.drainAttentionDecisions(log, "tick " + seq);
			writer.drainAttentionDecisions(log, "unchanged");
		}
		var lines = Files.readAllLines(root.resolve("attention-decisions.jsonl")).stream()
			.map(line -> JsonParser.parseString(line).getAsJsonObject().getAsJsonObject("decision")).toList();
		assertEquals(List.of(1L, 2L), lines.stream().map(d -> d.get("seqNo").getAsLong()).toList());
		assertTrue(lines.getFirst().getAsJsonObject("inputs").getAsJsonObject("state").get("activeJobIdle").getAsBoolean());
		assertEquals(2L, writer.statusPayload().get("attentionDecisionsLatestSeqNo"));
		assertEquals(false, writer.statusPayload().get("attentionDecisionsTruncated"));
	}

	private List<com.google.gson.JsonObject> records() throws Exception {
		return Files.readAllLines(root.resolve("llm-calls.jsonl")).stream()
			.map(line -> JsonParser.parseString(line).getAsJsonObject().getAsJsonObject("record")).toList();
	}
}
