package ai.moeru.airicraft.agent.llm;

import java.util.Objects;

/**
 * A compaction to run in the background: the conversation to summarize and the {@link Cut} that says which part of the
 * history that summary replaces. Anything recorded after the cut was taken stays as it is when the summary lands.
 */
public record CompactionRequest(LlmConversation conversation, Cut cut) {
	public CompactionRequest {
		conversation = Objects.requireNonNull(conversation, "conversation");
		cut = Objects.requireNonNull(cut, "cut");
	}

	/**
	 * The history the summary covers, fixed when the compaction starts.
	 *
	 * @param historyEntries accepted-history entries covered (client-rebuilt history)
	 * @param anchor         last retained wire message covered, or null when history is rebuilt from the accepted tape
	 */
	public record Cut(int historyEntries, LlmChatMessage anchor) {
	}
}
