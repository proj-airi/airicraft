package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeferredWorkReceiptsTest {
	private final PlannerToolCall call = new PlannerToolCall("call-1", "mine_blocks", new JsonObject(), null);
	private final String accepted = "Tool result for mine_blocks: {\"accepted\":true,\"workId\":\"JOB:one\",\"state\":\"RUNNING\"}";

	@Test void failureReplacesUndeliveredAcceptanceButPreservesToolPair() {
		var receipts = new DeferredWorkReceipts();
		assertTrue(receipts.defer(call, accepted));
		var history = LlmConversation.of(List.of(LlmChatMessage.assistantToolCall("", call), LlmChatMessage.tool(call.id(), accepted)));
		var delivered = receipts.deliver(history, Map.of("work", List.of(Map.of("workId", "JOB:one", "state", "FAILED", "details", Map.of("failure", "unreachable")))));
		assertEquals(history.messages().getFirst(), delivered.messages().getFirst());
		assertEquals(call.id(), delivered.messages().getLast().toolCallId());
		assertTrue(delivered.messages().getLast().content().contains("unreachable"));
		assertFalse(delivered.messages().getLast().content().contains("accepted"));
	}

	@Test void ongoingReceiptIsDeliveredOnceAndNotRewrittenLater() {
		var receipts = new DeferredWorkReceipts();
		assertTrue(receipts.defer(call, accepted));
		var history = LlmConversation.of(List.of(LlmChatMessage.tool(call.id(), accepted)));
		assertEquals(history, receipts.deliver(history, Map.of()));
		assertEquals(history, receipts.deliver(history, Map.of("work", List.of(Map.of("workId", "JOB:one", "state", "FAILED")))));
	}

	@Test void errorsReadsAndImmediateResultsDoNotYield() {
		var receipts = new DeferredWorkReceipts();
		assertFalse(receipts.defer(call, "TOOL_ERROR: unreachable"));
		assertFalse(receipts.defer(call, accepted.replace("true", "false")));
		assertFalse(receipts.defer(call, accepted.replace("RUNNING", "SUCCEEDED")));
		assertFalse(receipts.defer(call, "Tool result for mine_blocks: {}"));
	}
}
