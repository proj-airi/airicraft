package ai.moeru.airicraft.agent.chat;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServiceTest {
	@Test
	void sanitizeForChatCollapsesWhitespaceAndStripsLeadingSlash() {
		String sanitized = ChatService.sanitizeForChat("  /test\n\nhello\tworld  ");

		assertEquals("test hello world", sanitized);
	}

	@Test
	void sanitizeForChatStripsFormattingCodeAndTruncatesLongMessages() {
		String longText = "§a" + "a".repeat(400);

		String sanitized = ChatService.sanitizeForChat(longText);

		assertFalse(sanitized.contains("§"));
		assertEquals(ChatService.MAX_CHAT_MESSAGE_LENGTH, sanitized.length());
	}

	@Test
	void recentSentChatKeepsMultipleLinesFromSameTick() {
		ChatService chatService = new ChatService();

		chatService.rememberSentChat("Checking my inventory...", 100L);
		chatService.rememberSentChat("Let me see what I can craft...", 100L);

		assertTrue(chatService.isRecentSentChat("Checking my inventory...", 101L, 20L));
		assertTrue(chatService.isRecentSentChat("Let me see what I can craft...", 101L, 20L));
		assertFalse(chatService.isRecentSentChat("Checking my inventory...", 121L, 20L));
	}

	@Test
	void linesSentAfterReturnsEveryNewLineOldestFirst() {
		ChatService chatService = new ChatService();
		chatService.rememberSentLine("one");
		long seen = chatService.sentLineCount();

		chatService.rememberSentLine("two");
		chatService.rememberSentLine("three");

		assertEquals(3L, chatService.sentLineCount());
		assertEquals(List.of("two", "three"), chatService.linesSentAfter(seen));
		assertEquals(List.of(), chatService.linesSentAfter(chatService.sentLineCount()));
	}

	@Test
	void linesSentAfterKeepsOnlyTheLastLines() {
		ChatService chatService = new ChatService();
		for (int index = 0; index < 20; index++) {
			chatService.rememberSentLine("line " + index);
		}

		List<String> lines = chatService.linesSentAfter(0L);

		assertEquals(16, lines.size());
		assertEquals("line 4", lines.get(0));
		assertEquals("line 19", lines.get(15));
	}
}
