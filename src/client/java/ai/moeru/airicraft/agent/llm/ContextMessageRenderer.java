package ai.moeru.airicraft.agent.llm;

public final class ContextMessageRenderer {
	private static final String NOTICE_PREFIX = "Context update: ";

	private ContextMessageRenderer() {
	}

	public static String noticeText(String content) {
		return content.startsWith(NOTICE_PREFIX) ? content.substring(NOTICE_PREFIX.length()) : content;
	}

	public static LlmChatMessage renderEntry(PlannerContextEntry entry, long anchorTimeMs) {
		String relativeTime = RelativeTimeFormatter.format(entry.timestampMs(), anchorTimeMs);
		return switch (entry.type()) {
			case USER_TURN -> LlmChatMessage.user(
				(entry.speaker() == null || entry.speaker().isBlank() ? "Someone" : entry.speaker()) + " said " + relativeTime + ": " + entry.text(),
				LlmMessageKind.USER_TURN
			);
			case ASSISTANT_TURN -> LlmChatMessage.assistant("Agent replied " + relativeTime + ": " + entry.text());
			case TOOL_REQUEST -> LlmChatMessage.assistant("Agent requested tool " + relativeTime + ".", entry.rawAssistantContent());
			case TOOL_RESULT -> LlmChatMessage.user(entry.text(), LlmMessageKind.TOOL_RESULT);
			case NOTICE -> LlmChatMessage.user(NOTICE_PREFIX + entry.text(), LlmMessageKind.NOTICE);
		};
	}
}
