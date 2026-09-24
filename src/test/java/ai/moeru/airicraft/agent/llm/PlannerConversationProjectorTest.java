package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.session.SessionMode;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerConversationProjectorTest {
	@Test
	void appendsToolExchangesInOpenAiReplayOrder() {
		PlannerTurnJournal journal = new PlannerTurnJournal(fixedClock(), 64);
		PlannerConversationProjector projector = new PlannerConversationProjector(48);
		PlannerRequest request = requestAt(10L, 1_000L, "@agent make torches");
		LlmConversation base = LlmConversation.of(List.of(
			LlmChatMessage.system("system"),
			LlmChatMessage.user("[chat][Alice] @agent make torches", LlmMessageKind.USER_TURN)
		));
		PlannerContextSnapshot snapshot = snapshot(request, base);
		PlannerToolCall craftables = toolCall("call_craft", "check_craftables", "prompt", "torch ingredients");
		PlannerToolCall recipes = toolCall("call_recipe", "search_recipes", "query", "torch");

		journal.recordToolExchange(
			1L,
			snapshot,
			null,
			craftables,
			"Tool result for check_craftables: Available 2x2 crafts: torch",
			false
		);
		LlmConversation replay = projector.appendToolExchanges(base, journal.toolExchanges(1L))
			.withAppended(LlmChatMessage.assistantToolCall("", recipes))
			.withAppended(LlmChatMessage.tool(recipes.id(), "Tool result for search_recipes: query=torch"));

		List<LlmChatMessage> tail = replay.messages().subList(replay.messages().size() - 4, replay.messages().size());
		assertEquals("assistant", tail.get(0).role());
		assertEquals("check_craftables", tail.get(0).toolCalls().getFirst().name());
		assertEquals("tool", tail.get(1).role());
		assertTrue(tail.get(1).content().contains("Available 2x2 crafts"));
		assertEquals("assistant", tail.get(2).role());
		assertEquals("search_recipes", tail.get(2).toolCalls().getFirst().name());
		assertEquals("tool", tail.get(3).role());
		assertTrue(tail.get(3).content().contains("query=torch"));
	}

	@Test
	void projectedSnapshotDropsSupersededDebugCards() {
		PlannerTurnJournal journal = new PlannerTurnJournal(fixedClock(), 64);
		PlannerConversationProjector projector = new PlannerConversationProjector(48);
		LlmConversation first = LlmConversation.of(List.of(
			LlmChatMessage.system("system"),
			LlmChatMessage.user("[chat][Alice] first", LlmMessageKind.USER_TURN)
		));
		LlmConversation second = LlmConversation.of(List.of(
			LlmChatMessage.system("system"),
			LlmChatMessage.user("[chat][Alice] second", LlmMessageKind.USER_TURN)
		));
		journal.recordSubmission(1L, 1, PlannerSessionPhase.PLANNER_REQUEST, requestAt(10L, 1_000L, "first"), first);
		journal.recordDebugCard(new PlannerConversationDebugMessage(
			"assistant",
			PlannerConversationDebugKind.TASK,
			"Tool call: take_a_look",
			1L,
			PlannerSessionPhase.PLANNER_REQUEST.name(),
			1,
			false
		));
		journal.markSuperseded(1L);
		journal.recordSubmission(2L, 1, PlannerSessionPhase.PLANNER_REQUEST, requestAt(11L, 1_100L, "second"), second);

		PlannerConversationDebugSnapshot projected = projector.projectedSnapshot(journal);

		assertFalse(projected.messages().stream().anyMatch(message -> message.text().contains("take_a_look")));
		assertTrue(projected.messages().stream().anyMatch(message -> message.text().contains("second")));
	}

	@Test
	void compactionKeepsEarlierMessagesThenCheckpointThenNewTurns() {
		var journal = new PlannerTurnJournal(fixedClock(), 64);
		var projector = new PlannerConversationProjector(48);
		var first = LlmConversation.of(List.of(LlmChatMessage.system("system"),
			LlmChatMessage.user("Gather iron", LlmMessageKind.USER_TURN), LlmChatMessage.assistant("I found ore")));
		journal.recordSubmission(1, 1, PlannerSessionPhase.PLANNER_REQUEST, requestAt(1, 1000, "Gather iron"), first);
		var before = projector.projectedSnapshot(journal).messages();
		var checkpoint = new CompactionCheckpoint("today", "cave", "iron pickaxe", List.of(), List.of("Three raw iron gathered"), List.of(), List.of(), List.of(), List.of());
		journal.recordCompaction(new CompactionExecutionResult(checkpoint, null, null, null));
		var compacted = projector.projectedSnapshot(journal).messages();
		assertEquals(before, compacted.subList(0, before.size()));
		assertEquals(PlannerConversationDebugKind.CHECKPOINT, compacted.getLast().kind());
		assertTrue(compacted.getLast().text().contains("Three raw iron gathered"));
		var next = LlmConversation.of(List.of(LlmChatMessage.system("system"),
			LlmChatMessage.user(checkpoint.renderMessage(), LlmMessageKind.CHECKPOINT),
			LlmChatMessage.user("Smelt iron", LlmMessageKind.USER_TURN)));
		journal.recordSubmission(2, 1, PlannerSessionPhase.PLANNER_REQUEST, requestAt(2, 2000, "Smelt iron"), next);
		var after = projector.projectedSnapshot(journal).messages();
		assertEquals(compacted, after.subList(0, compacted.size()));
		assertEquals("Smelt iron", after.getLast().text());
		assertEquals(1, after.stream().filter(m -> m.kind() == PlannerConversationDebugKind.CHECKPOINT).count());
		assertEquals(List.of(checkpoint.renderMessage()), projector.contextExcerpt(journal), "Delegation excerpt stays limited to submitted context");
		assertFalse(projector.submittedSnapshot(journal).messages().stream().anyMatch(m -> m.text().equals("I found ore")), "Display history must not re-enter the model request");
		journal.recordSubmission(2, 2, PlannerSessionPhase.PLANNER_REQUEST, requestAt(2, 2000, "Smelt iron"), next);
		assertEquals(after.stream().map(PlannerConversationDebugMessage::text).toList(), projector.projectedSnapshot(journal).messages().stream().map(PlannerConversationDebugMessage::text).toList());
	}

	@Test
	void repeatedCompactionIsBoundedAndResetClearsDisplayHistory() {
		var journal = new PlannerTurnJournal(fixedClock(), 32);
		var projector = new PlannerConversationProjector(8);
		for (int i = 1; i <= 20; i++) {
			journal.recordSubmission(i, 1, PlannerSessionPhase.PLANNER_REQUEST, requestAt(i, i, "turn"),
				LlmConversation.of(List.of(LlmChatMessage.user("turn " + i, LlmMessageKind.USER_TURN))));
			journal.recordCompaction(new CompactionExecutionResult(new CompactionCheckpoint("now", "world", "goal " + i,
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), null, null, null));
			assertTrue(projector.projectedSnapshot(journal).messages().size() <= 8);
		}
		var messages = projector.projectedSnapshot(journal).messages();
		assertTrue(messages.getLast().text().contains("goal 20"));
		assertEquals("turn 20", messages.get(messages.size()-2).text());
		journal.clear("world changed");
		assertTrue(projector.projectedSnapshot(journal).isEmpty());
	}

	@Test
	void failedCompactionReportsFailureWithoutReplacingConversation() {
		var journal = new PlannerTurnJournal(fixedClock(), 64);
		var projector = new PlannerConversationProjector(48);
		journal.recordSubmission(1, 1, PlannerSessionPhase.PLANNER_REQUEST, requestAt(1, 1000, "turn"),
			LlmConversation.of(List.of(LlmChatMessage.user("Keep this turn", LlmMessageKind.USER_TURN))));
		journal.recordCompaction(new CompactionExecutionResult(null, null, LlmFailureType.TIMEOUT, "Timed out"));
		var messages = projector.projectedSnapshot(journal).messages();
		assertEquals("Keep this turn", messages.getFirst().text());
		assertEquals(PlannerConversationDebugKind.FAILURE, messages.getLast().kind());
		assertTrue(messages.getLast().text().contains("Timed out"));
	}

	@Test
	void chronicleListsEveryEventInOrderWithoutDuplicates() {
		var journal = new PlannerTurnJournal(fixedClock(), 64);
		var projector = new PlannerConversationProjector(48);
		PlannerRequest request = requestAt(10L, 1_000L, "make torches");
		journal.recordSubmission(1L, 1, PlannerSessionPhase.PLANNER_REQUEST, request,
			LlmConversation.of(List.of(
				LlmChatMessage.system("huge frozen prefix that must not appear"),
				LlmChatMessage.user("[chat][Alice] make torches", LlmMessageKind.USER_TURN)
			)));
		journal.recordToolExchange(1L, snapshot(request, LlmConversation.of(List.of())), null,
			toolCall("call_craft", "check_craftables", "prompt", "torch"), "Tool result: 2x2 crafts", false);
		journal.recordDebugCard(new PlannerConversationDebugMessage(
			"assistant",
			PlannerConversationDebugKind.ASSISTANT_TURN,
			"I'll check your inventory first",
			1L,
			PlannerSessionPhase.PLANNER_REQUEST.name(),
			1,
			false
		));

		var messages = projector.chronicleSnapshot(journal).messages();

		assertEquals(3, messages.size());
		assertEquals(PlannerConversationDebugKind.USER_TURN, messages.get(0).kind());
		assertEquals("make torches", messages.get(0).text());
		assertEquals("Alice", messages.get(0).role());
		assertEquals(PlannerConversationDebugKind.TOOL_RESULT, messages.get(1).kind());
		assertTrue(messages.get(1).text().contains("→ check_craftables"));
		assertTrue(messages.get(1).text().contains("← Tool result: 2x2 crafts"));
		assertEquals(PlannerConversationDebugKind.ASSISTANT_TURN, messages.get(2).kind());
		assertTrue(messages.stream().allMatch(message -> message.timestampMs() == 1_000L),
			"Every chronicle card is stamped with its journal event time");
		assertTrue(messages.stream().noneMatch(message -> message.text().contains("frozen prefix")),
			"The submitted system prefix never appears in the chronicle log");
	}

	@Test
	void chronicleCollapsesToolExchangeToNameUnlessVerbose() {
		var journal = new PlannerTurnJournal(fixedClock(), 64);
		var projector = new PlannerConversationProjector(48);
		journal.recordSubmission(1L, 1, PlannerSessionPhase.PLANNER_REQUEST, requestAt(10L, 1_000L, "first"),
			LlmConversation.of(List.of(LlmChatMessage.user("first", LlmMessageKind.USER_TURN))));
		journal.recordToolExchange(1L, snapshot(requestAt(10L, 1_000L, "first"), LlmConversation.of(List.of())), null,
			toolCall("call_craft", "check_craftables", "prompt", "torch"), "Tool result: 2x2 crafts", false);

		var collapsed = projector.chronicleSnapshot(journal, false).messages();
		var expanded = projector.chronicleSnapshot(journal, true).messages();

		assertEquals("→ check_craftables", collapsed.get(1).text());
		assertEquals(PlannerConversationDebugKind.TOOL_RESULT, collapsed.get(1).kind());
		assertTrue(expanded.get(1).text().contains("prompt"));
		assertTrue(expanded.get(1).text().contains("← Tool result: 2x2 crafts"));
	}

	@Test
	void chronicleKeepsSupersededGenerationsMarked() {
		var journal = new PlannerTurnJournal(fixedClock(), 64);
		var projector = new PlannerConversationProjector(48);
		journal.recordSubmission(1L, 1, PlannerSessionPhase.PLANNER_REQUEST, requestAt(10L, 1_000L, "first"),
			LlmConversation.of(List.of(LlmChatMessage.user("first", LlmMessageKind.USER_TURN))));
		journal.markSuperseded(1L);
		journal.recordSubmission(2L, 1, PlannerSessionPhase.PLANNER_REQUEST, requestAt(11L, 1_100L, "second"),
			LlmConversation.of(List.of(LlmChatMessage.user("second", LlmMessageKind.USER_TURN))));

		var messages = projector.chronicleSnapshot(journal).messages();

		assertEquals(3, messages.size());
		assertEquals("first", messages.get(0).text());
		assertTrue(messages.get(0).superseded(), "Superseded turn stays visible and marked");
		assertEquals(PlannerConversationDebugKind.NOTICE, messages.get(1).kind());
		assertTrue(messages.get(1).text().contains("g1"));
		assertEquals("second", messages.get(2).text());
		assertFalse(messages.get(2).superseded());
	}

	@Test
	void chronicleKeepsResetMarkerAndSkipsAcceptedReplyDuplicates() {
		var journal = new PlannerTurnJournal(fixedClock(), 64);
		var projector = new PlannerConversationProjector(48);
		journal.recordSubmission(1L, 1, PlannerSessionPhase.PLANNER_REQUEST, requestAt(1L, 1_000L, "turn"),
			LlmConversation.of(List.of(LlmChatMessage.user("turn", LlmMessageKind.USER_TURN))));
		journal.clear("world changed");

		var messages = projector.chronicleSnapshot(journal).messages();

		assertEquals(1, messages.size());
		assertEquals(PlannerConversationDebugKind.NOTICE, messages.getFirst().kind());
		assertTrue(messages.getFirst().text().contains("reset"));
		assertTrue(messages.getFirst().text().contains("world changed"));
	}

	private static PlannerContextSnapshot snapshot(PlannerRequest request, LlmConversation conversation) {
		return new PlannerContextSnapshot(
			request,
			PlannerSnapshotMode.TRIGGERED,
			PlannerTriggerBatch.of(List.of()),
			conversation,
			0L,
			0L,
			PlannerAmbientContext.fromRequest(request),
			-1L
		);
	}

	private static PlannerRequest requestAt(long tick, long timestampMs, String message) {
		return new PlannerRequest(tick, timestampMs, SessionMode.OUT_OF_WORLD, "Alice", null, "Alice", message, null);
	}

	private static PlannerToolCall toolCall(String id, String name, String argumentName, String argumentValue) {
		JsonObject arguments = new JsonObject();
		arguments.addProperty(argumentName, argumentValue);
		return new PlannerToolCall(id, name, arguments, null, null);
	}

	private static Clock fixedClock() {
		return Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
	}
}
