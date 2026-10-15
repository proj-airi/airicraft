package ai.moeru.airicraft.agent.social;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The last few system messages the server sent this client, chat and action bar ("overlay") alike, with their translation
 * key when the message is a translatable component. Tasks read it to learn what the server answered to an action that has
 * no other client-side trace: a bed says "Respawn point set" in chat and "You may not rest now; there are monsters
 * nearby" over the hotbar. A task takes {@link #mark()} before acting and reads {@link #since} afterwards.
 */
public final class GameMessageLog {
	private static final int CAPACITY = 64;
	private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
	private static long sequence;

	private GameMessageLog() {
	}

	/** {@code key}: the translation key, or empty for a literal message. */
	public record Entry(long sequence, String key, String text, boolean overlay) {}

	public static synchronized void record(String key, String text, boolean overlay) {
		if ((key == null || key.isEmpty()) && (text == null || text.isBlank())) return;
		ENTRIES.addLast(new Entry(++sequence, key == null ? "" : key, text == null ? "" : text, overlay));
		while (ENTRIES.size() > CAPACITY) ENTRIES.removeFirst();
	}

	/** The sequence number of the latest message; messages recorded later have a larger one. */
	public static synchronized long mark() {
		return sequence;
	}

	/** Messages recorded after {@code mark}, oldest first. */
	public static synchronized List<Entry> since(long mark) {
		List<Entry> result = new ArrayList<>();
		for (Entry entry : ENTRIES) if (entry.sequence() > mark) result.add(entry);
		return result;
	}
}
