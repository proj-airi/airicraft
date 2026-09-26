package ai.moeru.airicraft.agent.llm;

import java.util.ArrayList;
import java.util.List;

public record PlannerTriggerBatch(
	List<PlannerTrigger> triggers
) {
	public PlannerTriggerBatch {
		triggers = triggers == null ? List.of() : List.copyOf(triggers);
	}

	public static PlannerTriggerBatch of(List<PlannerTrigger> triggers) {
		return new PlannerTriggerBatch(triggers);
	}

	public boolean isEmpty() {
		return triggers.isEmpty();
	}

	public int size() {
		return triggers.size();
	}

	public boolean maySupersedeLaunchedTurn() {
		return triggers.stream().anyMatch(PlannerTrigger::maySupersedeLaunchedTurn);
	}

	public long endSeqNo() {
		return triggers.isEmpty() ? 0L : triggers.get(triggers.size() - 1).seqNo();
	}

	public long tick() {
		return triggers.isEmpty() ? -1L : triggers.get(triggers.size() - 1).tick();
	}

	public long timestampMs() {
		return triggers.isEmpty() ? -1L : triggers.get(triggers.size() - 1).timestampMs();
	}

	public String primarySpeaker() {
		if (triggers.isEmpty()) {
			return null;
		}
		if (triggers.size() == 1) {
			return triggers.get(0).speaker();
		}
		return "combined_updates";
	}

	public String primaryMessage() {
		if (triggers.isEmpty()) {
			return "";
		}
		if (triggers.size() == 1) {
			PlannerTrigger trigger = triggers.get(0);
			if (usesGenericWakePrompt(trigger.type())) {
				return genericWakePrompt();
			}
			return trigger.text();
		}
		return renderPrompt();
	}

	public String renderPrompt() {
		if (triggers.isEmpty()) {
			return genericWakePrompt();
		}

		StringBuilder builder = new StringBuilder("Recent updates requiring one combined response:\n");
		int renderedCount = 0;
		for (PlannerTrigger trigger : triggers) {
			if (usesGenericWakePrompt(trigger.type())) {
				continue;
			}
			renderedCount++;
			builder
				.append("- [")
				.append(trigger.type().promptLabel())
				.append("][")
				.append(trigger.speaker())
				.append("] ")
				.append(trigger.text())
				.append('\n');
		}
		if (renderedCount == 0) {
			return genericWakePrompt();
		}
		builder.append('\n').append("Respond once to the combined latest context above.");
		return builder.toString();
	}

	public LlmChatMessage toTerminalMessage() {
		return LlmChatMessage.user(renderPrompt(), LlmMessageKind.USER_TURN,
			triggers.size() == 1 ? triggers.getFirst().fields() : null);
	}

	/**
	 * Messages preceding an observation: only player chat stays a user turn. Runtime wakeups become notices that the
	 * observation carries; generic wakeups add nothing because their evidence is already in the observed events.
	 */
	public List<LlmChatMessage> toObservedMessages() {
		var messages = new ArrayList<LlmChatMessage>();
		var chat = new StringBuilder();
		for (PlannerTrigger trigger : triggers) {
			if (trigger.type() == PlannerTriggerType.CHAT) {
				if (!chat.isEmpty()) chat.append('\n');
				chat.append(trigger.speaker() == null || trigger.speaker().isBlank() ? "Someone" : trigger.speaker())
					.append(": ").append(trigger.text());
			}
			else if (!usesGenericWakePrompt(trigger.type()))
				messages.add(LlmChatMessage.user(trigger.text(), LlmMessageKind.NOTICE, trigger.fields()));
		}
		if (!chat.isEmpty()) messages.addFirst(LlmChatMessage.user(chat.toString(), LlmMessageKind.USER_TURN));
		return List.copyOf(messages);
	}

	private static String genericWakePrompt() {
		return "Recent context updates require one combined response.";
	}

	private static boolean usesGenericWakePrompt(PlannerTriggerType triggerType) {
		return triggerType == PlannerTriggerType.CRAFT
			|| triggerType == PlannerTriggerType.PICKUP
			|| triggerType == PlannerTriggerType.DAMAGE;
	}
}
