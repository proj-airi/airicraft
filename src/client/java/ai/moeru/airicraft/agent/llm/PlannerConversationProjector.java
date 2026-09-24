package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PlannerConversationProjector {
	private final int messageLimit;

	public PlannerConversationProjector(int messageLimit) {
		this.messageLimit = Math.max(1, messageLimit);
	}

	public PlannerConversationDebugSnapshot submittedSnapshot(PlannerTurnJournal journal) {
		PlannerTurnEvent submission = latestSubmission(journal);
		if (submission == null) {
			return PlannerConversationDebugSnapshot.empty();
		}
		return PlannerConversationDebugSnapshot.fromConversation(
			submission.generation(),
			phase(submission.phase()),
			submission.attempt(),
			submission.conversation()
		);
	}

	/**
	 * Append-only log of every journal event: triggers, replies, tool exchanges,
	 * compaction, supersede and reset markers. Nothing is rewritten — superseded
	 * generations stay visible with a flag so the log never lies about what happened.
	 * When verbose is false, tool exchanges collapse to the tool name alone so the
	 * log reads as a sequence of actions rather than a raw protocol dump.
	 */
	public PlannerConversationDebugSnapshot chronicleSnapshot(PlannerTurnJournal journal) {
		return chronicleSnapshot(journal, true);
	}

	public PlannerConversationDebugSnapshot chronicleSnapshot(PlannerTurnJournal journal, boolean verbose) {
		Objects.requireNonNull(journal, "journal");
		ArrayList<PlannerConversationDebugMessage> messages = new ArrayList<>();
		PlannerTurnEvent latest = null;
		for (PlannerTurnEvent event : journal.snapshot()) {
			if (event.kind() == PlannerTurnEvent.Kind.SUBMISSION) {
				latest = event;
			}
			switch (event.kind()) {
				case SUBMISSION -> messages.add(triggerCard(event, journal.isSuperseded(event.generation())));
				case DEBUG_CARD -> {
					if (event.debugMessage() != null) {
						messages.add(event.debugMessage().stamped(event.timestampMs(), journal.isSuperseded(event.generation())));
					}
				}
				case TOOL_EXCHANGE -> messages.add(toolExchangeCard(event, journal.isSuperseded(event.generation()), verbose));
				case COMPACTION -> {
					if (event.debugMessage() != null) {
						messages.add(event.debugMessage().stamped(event.timestampMs(), false));
					}
				}
				case SUPERSEDED -> messages.add(markerCard(event, "generation g" + event.generation() + " superseded"));
				case RESET -> messages.add(markerCard(event, "reset: " + (event.toolResultText().isBlank() ? "unknown" : event.toolResultText())));
				case ACCEPTED_REPLY -> {
					// Reply text is already shown by the assistant DEBUG_CARD.
				}
			}
		}
		return new PlannerConversationDebugSnapshot(
			latest == null ? 0L : latest.generation(),
			latest == null ? "IDLE" : latest.phase(),
			latest == null ? 0 : latest.attempt(),
			trim(messages)
		);
	}

	private static PlannerConversationDebugMessage triggerCard(PlannerTurnEvent event, boolean superseded) {
		PlannerRequest request = event.request();
		String speaker = request == null ? null : request.senderName();
		String text = request == null ? "" : request.message();
		if (text.isBlank()) {
			text = "(trigger without message)";
		}
		return new PlannerConversationDebugMessage(
			speaker == null || speaker.isBlank() ? "user" : speaker,
			PlannerConversationDebugKind.USER_TURN,
			text,
			event.generation(),
			event.phase(),
			event.attempt(),
			false,
			event.timestampMs(),
			superseded
		);
	}

	private static PlannerConversationDebugMessage toolExchangeCard(PlannerTurnEvent event, boolean superseded, boolean verbose) {
		PlannerToolCall toolCall = event.toolCall();
		StringBuilder text = new StringBuilder("→ ").append(toolCall == null ? "tool" : toolCall.name());
		if (verbose) {
			if (toolCall != null && toolCall.arguments() != null && !toolCall.arguments().entrySet().isEmpty()) {
				text.append(' ').append(toolCall.arguments());
			}
			text.append('\n').append("← ").append(toolResultContent(event.toolResultText()));
		}
		return new PlannerConversationDebugMessage(
			"tool",
			PlannerConversationDebugKind.TOOL_RESULT,
			text.toString(),
			event.generation(),
			event.phase(),
			event.attempt(),
			event.imageAttached(),
			event.timestampMs(),
			superseded
		);
	}

	private static PlannerConversationDebugMessage markerCard(PlannerTurnEvent event, String text) {
		return new PlannerConversationDebugMessage(
			"system",
			PlannerConversationDebugKind.NOTICE,
			text,
			event.generation(),
			event.phase(),
			event.attempt(),
			false,
			event.timestampMs(),
			false
		);
	}

	public PlannerConversationDebugSnapshot projectedSnapshot(PlannerTurnJournal journal) {
		Objects.requireNonNull(journal, "journal");
		List<PlannerTurnEvent> events = journal.snapshot();
		ArrayList<PlannerConversationDebugMessage> history = new ArrayList<>();
		int segmentStart = 0;
		for (int index = 0; index < events.size(); index++) {
			PlannerTurnEvent event = events.get(index);
			if (event.kind() != PlannerTurnEvent.Kind.COMPACTION || !event.toolResultText().equals("checkpoint_updated")) continue;
			history.addAll(projectSegment(journal, events.subList(segmentStart, index), segmentStart > 0).messages());
			if (event.debugMessage() != null) history.add(event.debugMessage());
			segmentStart = index + 1;
		}
		var current = projectSegment(journal, events.subList(segmentStart, events.size()), segmentStart > 0);
		history.addAll(current.messages());
		PlannerTurnEvent latest = latestSubmission(events);
		return new PlannerConversationDebugSnapshot(latest == null ? 0 : latest.generation(),
			latest == null ? "COMPACTION" : latest.phase(), latest == null ? 0 : latest.attempt(), trim(history));
	}

	private PlannerConversationDebugSnapshot projectSegment(PlannerTurnJournal journal, List<PlannerTurnEvent> events, boolean afterCompaction) {
		PlannerTurnEvent latestSubmission = latestSubmission(events);
		if (latestSubmission == null) {
			return PlannerConversationDebugSnapshot.empty();
		}

		PlannerConversationDebugSnapshot submitted = PlannerConversationDebugSnapshot.fromConversation(
			latestSubmission.generation(),
			phase(latestSubmission.phase()),
			latestSubmission.attempt(),
			latestSubmission.conversation()
		);
		ArrayList<PlannerConversationDebugMessage> messages = new ArrayList<>();
		for (PlannerTurnEvent event : events) {
			if (event.id() >= latestSubmission.id()) {
				break;
			}
			if (!isVisiblePersistentCard(event.debugMessage()) || journal.isSuperseded(event.generation())) {
				continue;
			}
			messages.add(event.debugMessage());
		}
		// The checkpoint already has its chronological compaction card. Do not repeat the
		// frozen system prefix or checkpoint every time a post-compaction request arrives.
		for (var message : submitted.messages()) {
			if (afterCompaction && (message.kind() == PlannerConversationDebugKind.SYSTEM
				|| message.kind() == PlannerConversationDebugKind.CHECKPOINT)) continue;
			messages.add(message);
		}
		for (PlannerTurnEvent event : events) {
			if (event.id() <= latestSubmission.id()) continue;
			if (event.kind() == PlannerTurnEvent.Kind.COMPACTION && event.debugMessage() != null) {
				messages.add(event.debugMessage());
				continue;
			}
			if (event.generation() != latestSubmission.generation()) {
				continue;
			}
			if (!Objects.equals(event.phase(), latestSubmission.phase()) || event.attempt() != latestSubmission.attempt()) {
				continue;
			}
			if (event.debugMessage() != null && !journal.isSuperseded(event.generation())) {
				messages.add(event.debugMessage());
			}
		}
		return new PlannerConversationDebugSnapshot(
			latestSubmission.generation(),
			latestSubmission.phase(),
			latestSubmission.attempt(),
			List.copyOf(messages)
		);
	}

	public List<String> contextExcerpt(PlannerTurnJournal journal) {
		// This excerpt can feed delegation; archived display history is not model context.
		PlannerConversationDebugSnapshot snapshot = submittedSnapshot(journal);
		if (snapshot.isEmpty()) {
			return List.of();
		}
		ArrayList<String> excerpt = new ArrayList<>();
		for (PlannerConversationDebugMessage message : snapshot.messages()) {
			if (message.kind() != PlannerConversationDebugKind.NOTICE && message.kind() != PlannerConversationDebugKind.CHECKPOINT) {
				continue;
			}
			if (message.text() != null && !message.text().isBlank()) {
				excerpt.add(message.text());
			}
		}
		return List.copyOf(excerpt);
	}

	public LlmConversation appendToolExchanges(LlmConversation conversation, List<PlannerTurnEvent> exchanges) {
		LlmConversation updated = conversation;
		for (PlannerTurnEvent exchange : exchanges == null ? List.<PlannerTurnEvent>of() : exchanges) {
			if (exchange.toolCall() != null) {
				updated = updated
					.withAppended(LlmChatMessage.assistantToolCall("", exchange.toolCall(), exchange.assistantRawContent()))
					.withAppended(LlmChatMessage.tool(exchange.toolCall().id(), toolResultContent(exchange.toolResultText())));
			}
			else if (exchange.assistantRawContent() != null) {
				updated = updated
					.withAppended(LlmChatMessage.assistant(
						OpenAiCompatibleMessageContent.extractVisibleText(exchange.assistantRawContent()),
						exchange.assistantRawContent()
					))
					.withAppended(LlmChatMessage.user(
						"Tool result: " + toolResultContent(exchange.toolResultText()),
						LlmMessageKind.TOOL_RESULT
					));
			}
		}
		return updated;
	}

	private PlannerTurnEvent latestSubmission(PlannerTurnJournal journal) {
		return latestSubmission(journal == null ? List.of() : journal.snapshot());
	}

	private static PlannerTurnEvent latestSubmission(List<PlannerTurnEvent> events) {
		for (int index = events.size() - 1; index >= 0; index--) {
			PlannerTurnEvent event = events.get(index);
			if (event.kind() == PlannerTurnEvent.Kind.SUBMISSION) {
				return event;
			}
		}
		return null;
	}

	private static boolean isVisiblePersistentCard(PlannerConversationDebugMessage message) {
		if (message == null) {
			return false;
		}
		return switch (message.kind()) {
			case ASSISTANT_TURN, TOOL_RESULT, TASK, FAILURE -> true;
			case SYSTEM, CHECKPOINT, NOTICE, USER_TURN -> false;
		};
	}

	private List<PlannerConversationDebugMessage> trim(List<PlannerConversationDebugMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return List.of();
		}
		ArrayList<PlannerConversationDebugMessage> trimmed = new ArrayList<>(messages);
		while (trimmed.size() > messageLimit) {
			int noise = -1;
			for (int index = 0; index < trimmed.size(); index++) {
				var kind = trimmed.get(index).kind();
				if (kind == PlannerConversationDebugKind.SYSTEM || kind == PlannerConversationDebugKind.NOTICE) {
					noise = index;
					break;
				}
			}
			trimmed.remove(noise >= 0 ? noise : 0);
		}
		return List.copyOf(trimmed);
	}

	private static PlannerSessionPhase phase(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return PlannerSessionPhase.valueOf(value);
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static String toolResultContent(String toolResultText) {
		if (toolResultText == null || toolResultText.isBlank()) {
			return "Tool result: none";
		}
		return toolResultText;
	}
}
