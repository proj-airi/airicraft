package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.dialogue.DialogueTurn;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

final class PlannerContextReducer {
	private PlannerContextReducer() {
	}

	static PlannerContextState recordObservedEvents(PlannerContextState state, SemanticEventQueryResult queryResult) {
		if (queryResult == null) {
			return state;
		}

		ArrayList<SemanticEvent> pending = new ArrayList<>(state.pendingSemanticEvents());
		for (SemanticEvent event : queryResult.events()) {
			if (event == null || event.seqNo() <= state.lastObservedEventSeqNo()) {
				continue;
			}
			pending.add(event);
		}

		long pendingSemanticGapVersion = state.pendingSemanticGapVersion();
		long nextSemanticGapVersion = state.nextSemanticGapVersion();
		if (queryResult.truncated()) {
			pendingSemanticGapVersion = nextSemanticGapVersion;
			nextSemanticGapVersion++;
		}

		return new PlannerContextState(
			state.acceptedHistoryTape(),
			state.activeCheckpoint(),
			List.copyOf(pending),
			pendingSemanticGapVersion,
			nextSemanticGapVersion,
			Math.max(state.lastObservedEventSeqNo(), queryResult.latestSeqNo()),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeContextAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState enqueueTrigger(PlannerContextState state, PlannerTrigger trigger) {
		ArrayList<PlannerTrigger> queued = new ArrayList<>(state.queuedTriggers());
		if (trigger.origin() == PlannerTriggerOrigin.AUTONOMOUS && trigger.coalescingKey() != null) {
			queued.removeIf(existing -> existing.origin() == PlannerTriggerOrigin.AUTONOMOUS
				&& trigger.coalescingKey().equals(existing.coalescingKey()));
		}
		queued.add(trigger);
		return new PlannerContextState(
			state.acceptedHistoryTape(),
			state.activeCheckpoint(),
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeContextAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			List.copyOf(queued),
			trigger.seqNo() + 1L
		);
	}

	static PlannerContextState invalidateIdleThinkTriggers(PlannerContextState state) {
		List<PlannerTrigger> remaining = state.queuedTriggers().stream()
			.filter(trigger -> trigger.type() != PlannerTriggerType.IDLE_THINK
				|| trigger.origin() != PlannerTriggerOrigin.AUTONOMOUS)
			.toList();
		if (remaining.size() == state.queuedTriggers().size()) {
			return state;
		}
		return new PlannerContextState(
			state.acceptedHistoryTape(),
			state.activeCheckpoint(),
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeContextAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			remaining,
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState recordAcceptedUserTurn(
		PlannerContextState state,
		PlannerTriggerBatch triggerBatch,
		long tick,
		long timestampMs
	) {
		if (triggerBatch == null || triggerBatch.isEmpty()) {
			return state;
		}

		PlannerTrigger structured = triggerBatch.triggers().size() == 1 ? triggerBatch.triggers().getFirst() : null;
		PlannerContextEntry acceptedEntry = new PlannerContextEntry(
			PlannerContextEntryType.USER_TURN,
			triggerBatch.primarySpeaker(),
			structured != null && structured.fields() != null ? structured.text() : triggerBatch.renderPrompt(),
			tick,
			timestampMs,
			null,
			null,
			List.of(),
			structured == null ? null : structured.fields()
		);
		ArrayList<PlannerContextEntry> acceptedHistory = new ArrayList<>(state.acceptedHistoryTape());
		acceptedHistory.add(acceptedEntry);
		return new PlannerContextState(
			List.copyOf(acceptedHistory),
			state.activeCheckpoint(),
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeContextAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState recordAcceptedToolExchange(
		PlannerContextState state,
		JsonElement assistantRawContent,
		String toolResultText,
		long tick,
		long timestampMs
	) {
		if (assistantRawContent == null) {
			return state;
		}

		ArrayList<PlannerContextEntry> acceptedHistory = new ArrayList<>(state.acceptedHistoryTape());
		acceptedHistory.add(PlannerContextEntry.toolRequest(assistantRawContent, tick, timestampMs));
		acceptedHistory.add(PlannerContextEntry.toolResult(toolResultText, tick, timestampMs));
		return new PlannerContextState(
			List.copyOf(acceptedHistory),
			state.activeCheckpoint(),
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeContextAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState recordAcceptedToolExchange(
		PlannerContextState state,
		PlannerToolCall toolCall,
		String toolResultText,
		long tick,
		long timestampMs
	) {
		if (toolCall == null) {
			return state;
		}

		ArrayList<PlannerContextEntry> acceptedHistory = new ArrayList<>(state.acceptedHistoryTape());
		acceptedHistory.add(PlannerContextEntry.toolRequest(toolCall, tick, timestampMs));
		acceptedHistory.add(PlannerContextEntry.toolResult(toolResultText, toolCall, tick, timestampMs));
		return new PlannerContextState(
			List.copyOf(acceptedHistory),
			state.activeCheckpoint(),
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeContextAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
			);
	}

	static PlannerContextState recordAcceptedToolExchange(
		PlannerContextState state,
		List<PlannerToolCall> toolCalls,
		List<String> toolResultTexts,
		long tick,
		long timestampMs
	) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return state;
		}

		ArrayList<PlannerContextEntry> acceptedHistory = new ArrayList<>(state.acceptedHistoryTape());
		acceptedHistory.add(PlannerContextEntry.toolRequest(toolCalls, tick, timestampMs));
		for (int index = 0; index < toolCalls.size(); index++) {
			String toolResultText = toolResultTexts == null || index >= toolResultTexts.size() ? null : toolResultTexts.get(index);
			acceptedHistory.add(PlannerContextEntry.toolResult(toolResultText, toolCalls.get(index), tick, timestampMs));
		}
		return new PlannerContextState(
			List.copyOf(acceptedHistory),
			state.activeCheckpoint(),
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeContextAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState recordAcceptedAssistantTurn(PlannerContextState state, DialogueTurn turn, JsonElement rawAssistantContent) {
		if (turn == null) {
			return state;
		}

		PlannerContextEntry acceptedEntry = new PlannerContextEntry(
			PlannerContextEntryType.ASSISTANT_TURN,
			turn.speaker(),
			turn.text(),
			turn.tick(),
			turn.timestampMs(),
			null,
			rawAssistantContent,
			List.of()
		);
		ArrayList<PlannerContextEntry> acceptedHistory = new ArrayList<>(state.acceptedHistoryTape());
		acceptedHistory.add(acceptedEntry);
		return new PlannerContextState(
			List.copyOf(acceptedHistory),
			state.activeCheckpoint(),
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeContextAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState commitAcceptedSnapshot(PlannerContextState state, PlannerContextSnapshot snapshot) {
		return consumeSnapshot(state, snapshot, true);
	}

	static PlannerContextState discardSnapshot(PlannerContextState state, PlannerContextSnapshot snapshot) {
		return consumeSnapshot(state, snapshot, false);
	}

	private static PlannerContextState consumeSnapshot(
		PlannerContextState state,
		PlannerContextSnapshot snapshot,
		boolean recordUserTurn
	) {
		if (snapshot == null) {
			return state;
		}

		PlannerContextState next = recordUserTurn && snapshot.mode() == PlannerSnapshotMode.TRIGGERED
			? recordAcceptedUserTurn(state, snapshot.triggerBatch(), snapshot.request().tick(), snapshot.request().timestampMs())
			: state;

		ArrayList<SemanticEvent> remainingPending = new ArrayList<>();
		for (SemanticEvent event : next.pendingSemanticEvents()) {
			if (event.seqNo() > snapshot.includedSemanticEventSeqNoUpperBound()) {
				remainingPending.add(event);
			}
		}

		ArrayList<PlannerTrigger> remainingQueued = new ArrayList<>(next.queuedTriggers());
		if (snapshot.mode() == PlannerSnapshotMode.TRIGGERED && snapshot.triggerBatch() != null && !snapshot.triggerBatch().isEmpty()) {
			remainingQueued.clear();
			for (PlannerTrigger queuedTrigger : next.queuedTriggers()) {
				if (queuedTrigger.seqNo() > snapshot.triggerBatch().endSeqNo()) {
					remainingQueued.add(queuedTrigger);
				}
			}
		}

		long pendingSemanticGapVersion = next.pendingSemanticGapVersion();
		if (
			snapshot.includedSemanticGapVersion() != 0L
			&& pendingSemanticGapVersion == snapshot.includedSemanticGapVersion()
		) {
			pendingSemanticGapVersion = 0L;
		}

		return new PlannerContextState(
			next.acceptedHistoryTape(),
			next.activeCheckpoint(),
			List.copyOf(remainingPending),
			pendingSemanticGapVersion,
			next.nextSemanticGapVersion(),
			next.lastObservedEventSeqNo(),
			snapshot.renderedAmbientContext(),
			snapshot.renderedTimeContextAtMs() >= 0L ? snapshot.renderedTimeContextAtMs() : next.lastAcceptedTimeContextAtMs(),
			next.compactionPending(),
			next.lastObservedUsage(),
			List.copyOf(remainingQueued),
			next.nextTriggerSeqNo()
		);
	}

	static PlannerContextState discardPending(PlannerContextState state) {
		return new PlannerContextState(
			state.acceptedHistoryTape(),
			state.activeCheckpoint(),
			List.of(),
			0L,
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeContextAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			List.of(),
			state.nextTriggerSeqNo()
		);
	}

	private static void stripDanglingToolEntries(ArrayList<PlannerContextEntry> retained) {
		while (!retained.isEmpty()) {
			PlannerContextEntryType head = retained.get(0).type();
			if (head == PlannerContextEntryType.TOOL_REQUEST || head == PlannerContextEntryType.TOOL_RESULT) {
				retained.remove(0);
				continue;
			}
			break;
		}
		while (!retained.isEmpty()) {
			PlannerContextEntryType tail = retained.get(retained.size() - 1).type();
			if (tail == PlannerContextEntryType.TOOL_REQUEST) {
				retained.remove(retained.size() - 1);
				continue;
			}
			break;
		}
	}

	static PlannerContextState updateUsage(PlannerContextState state, LlmUsageSnapshot usage, int thresholdTokens) {
		boolean compactionPending = state.compactionPending() || PlannerContextPolicy.shouldCompact(usage, thresholdTokens);
		return updateObservedUsage(state, usage, compactionPending);
	}

	static PlannerContextState updateObservedUsage(PlannerContextState state, LlmUsageSnapshot usage, boolean compactionPending) {
		return new PlannerContextState(
			state.acceptedHistoryTape(),
			state.activeCheckpoint(),
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeContextAtMs(),
			compactionPending,
			usage == null ? state.lastObservedUsage() : usage,
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState clearCompactionPending(PlannerContextState state, CompactionCheckpoint checkpoint, long compactedAtMs) {
		ArrayList<PlannerContextEntry> retained = new ArrayList<>();
		int retainedUserTurns = 0;
		for (int index = state.acceptedHistoryTape().size() - 1; index >= 0; index--) {
			PlannerContextEntry entry = state.acceptedHistoryTape().get(index);
			if (
				entry.type() != PlannerContextEntryType.USER_TURN
				&& entry.type() != PlannerContextEntryType.ASSISTANT_TURN
				&& entry.type() != PlannerContextEntryType.TOOL_REQUEST
				&& entry.type() != PlannerContextEntryType.TOOL_RESULT
			) {
				continue;
			}
			retained.add(0, entry);
			if (entry.type() == PlannerContextEntryType.USER_TURN) {
				retainedUserTurns++;
			}
			if (retainedUserTurns >= PlannerContextPolicy.RETAINED_USER_TURNS || retained.size() >= PlannerContextPolicy.RETAINED_MESSAGE_CAP) {
				break;
			}
		}
		stripDanglingToolEntries(retained);

		return new PlannerContextState(
			List.copyOf(retained),
			checkpoint,
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			compactedAtMs,
			false,
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}
}
