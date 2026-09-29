package ai.moeru.airicraft.agent.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.ArrayDeque;

public final class ChatService {
	public static final int MAX_CHAT_MESSAGE_LENGTH = 220;
	private static final int RECENT_SENT_CHAT_LIMIT = 8;

	private long lastChatTick = -1L;
	private String lastChatText;
	private final ArrayDeque<SentChat> recentSentChats = new ArrayDeque<>();

	public boolean send(Minecraft minecraft, String text, long tick) {
		if (minecraft == null || text == null || text.isBlank()) {
			return false;
		}

		String sanitizedText = sanitizeForChat(text);
		if (sanitizedText.isBlank()) {
			return false;
		}

		ClientPacketListener packetListener = minecraft.getConnection();
		if (packetListener == null) {
			return false;
		}

		packetListener.sendChat(sanitizedText);
		lastChatTick = tick;
		lastChatText = sanitizedText;
		rememberSentChat(sanitizedText, tick);
		return true;
	}

	public boolean isRecentSentChat(String text, long currentTick, long maxAgeTicks) {
		if (text == null || text.isBlank()) {
			return false;
		}
		for (SentChat sentChat : recentSentChats) {
			if (sentChat.matches(text, currentTick, maxAgeTicks)) {
				return true;
			}
		}
		return false;
	}

	public static String sanitizeForChat(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}

		StringBuilder builder = new StringBuilder(text.length());
		boolean previousWhitespace = false;
		for (int i = 0; i < text.length(); i++) {
			char current = text.charAt(i);
			if (current == '\r' || current == '\n' || current == '\t') {
				current = ' ';
			}
			if (Character.isISOControl(current) || current == '§') {
				continue;
			}
			if (Character.isWhitespace(current)) {
				if (!previousWhitespace) {
					builder.append(' ');
					previousWhitespace = true;
				}
				continue;
			}

			builder.append(current);
			previousWhitespace = false;
		}

		String sanitized = builder.toString().strip();
		while (sanitized.startsWith("/")) {
			sanitized = sanitized.substring(1).stripLeading();
		}
		if (sanitized.length() > MAX_CHAT_MESSAGE_LENGTH) {
			sanitized = sanitized.substring(0, MAX_CHAT_MESSAGE_LENGTH).stripTrailing();
		}
		return sanitized;
	}

	public long lastChatTick() {
		return lastChatTick;
	}

	public String lastChatText() {
		return lastChatText;
	}

	public void clear() {
		lastChatTick = -1L;
		lastChatText = null;
		recentSentChats.clear();
	}

	void rememberSentChat(String text, long tick) {
		if (text == null || text.isBlank()) {
			return;
		}
		recentSentChats.addLast(new SentChat(text, tick));
		while (recentSentChats.size() > RECENT_SENT_CHAT_LIMIT) {
			recentSentChats.removeFirst();
		}
	}

	private record SentChat(String text, long tick) {
		boolean matches(String candidate, long currentTick, long maxAgeTicks) {
			if (tick < 0L || currentTick < tick) {
				return false;
			}
			return currentTick - tick <= maxAgeTicks && text.equals(candidate);
		}
	}
}
