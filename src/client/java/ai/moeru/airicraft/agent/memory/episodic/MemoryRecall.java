package ai.moeru.airicraft.agent.memory.episodic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders the MEMORY block a new planner context starts with: facts about the players around,
 * the latest chapters, and a few moments picked by recency, salience and who is present.
 */
public final class MemoryRecall {
	public static final String HEADER = "MEMORY (recalled from earlier play; it may be incomplete. Bring it up when it fits, "
		+ "never recite it, and do not treat it as a task list.)";
	static final int MAX_CHARS = 3200;
	static final int MAX_PERSON_CHARS = 280;
	static final int MAX_PEOPLE = 4;
	static final int RECENT_MOMENTS = 3;
	static final int NOTABLE_MOMENTS = 3;
	private static final long DAY_MS = 86_400_000L;

	private MemoryRecall() {
	}

	/** Empty when there is nothing to recall, so contexts without memory stay unchanged. */
	public static String render(List<Episode> episodes, MemoryDigest digest, Set<String> presentPlayers, long nowMs) {
		List<String> people = people(digest, presentPlayers);
		List<String> chapters = digest.chapters().stream().skip(Math.max(0, digest.chapters().size() - 2L))
			.map(chapter -> "- " + days(chapter.fromDay(), chapter.toDay()) + chapter.summary()).toList();
		List<String> moments = moments(episodes, presentPlayers, nowMs).stream().map(MemoryRecall::moment).toList();
		if (people.isEmpty() && chapters.isEmpty() && moments.isEmpty()) return "";
		StringBuilder block = new StringBuilder(HEADER);
		appendSection(block, "People:", people);
		appendSection(block, "Earlier:", chapters);
		appendSection(block, "Moments:", moments);
		return block.toString();
	}

	static List<Episode> moments(List<Episode> episodes, Set<String> presentPlayers, long nowMs) {
		int recentFrom = Math.max(0, episodes.size() - RECENT_MOMENTS);
		List<Episode> older = new ArrayList<>(episodes.subList(0, recentFrom));
		older.sort(Comparator.comparingDouble((Episode episode) -> score(episode, presentPlayers, nowMs)).reversed());
		Set<Episode> picked = new LinkedHashSet<>(older.subList(0, Math.min(NOTABLE_MOMENTS, older.size())));
		picked.addAll(episodes.subList(recentFrom, episodes.size()));
		return episodes.stream().filter(picked::contains).toList();
	}

	private static double score(Episode episode, Set<String> presentPlayers, long nowMs) {
		double ageWeeks = Math.max(0L, nowMs - episode.recordedAtMs()) / (7.0 * DAY_MS);
		boolean withSomeoneHere = episode.participants().stream().anyMatch(presentPlayers::contains);
		return episode.salience() * 2.0 + (withSomeoneHere ? 3.0 : 0.0) - Math.min(3.0, ageWeeks);
	}

	private static List<String> people(MemoryDigest digest, Set<String> presentPlayers) {
		List<Map.Entry<String, MemoryDigest.Person>> ordered = new ArrayList<>(digest.people().entrySet());
		ordered.removeIf(entry -> entry.getValue().facts().isEmpty());
		ordered.sort(Comparator.comparing((Map.Entry<String, MemoryDigest.Person> entry) -> !presentPlayers.contains(entry.getKey()))
			.thenComparing(entry -> -entry.getValue().lastSeenMs()));
		return ordered.stream().limit(MAX_PEOPLE)
			.map(entry -> clip("- " + entry.getKey() + (presentPlayers.contains(entry.getKey()) ? " (here now): " : ": ")
				+ String.join("; ", entry.getValue().facts()), MAX_PERSON_CHARS))
			.toList();
	}

	private static String moment(Episode episode) {
		StringBuilder line = new StringBuilder("- ");
		String dimension = episode.dimension().startsWith("minecraft:") ? episode.dimension().substring(10) : episode.dimension();
		if (episode.worldDay() >= 0L) line.append("Day ").append(episode.worldDay());
		if (!dimension.isBlank()) {
			line.append(episode.worldDay() >= 0L ? ", " : "").append(dimension).append(" near ")
				.append(episode.x()).append(' ').append(episode.y()).append(' ').append(episode.z());
		}
		if (line.length() > 2) line.append(": ");
		line.append(episode.summary());
		for (Episode.Quote quote : episode.quotes()) line.append(' ').append(quote.speaker()).append(": \"").append(quote.text()).append('"');
		return line.toString();
	}

	private static String days(long fromDay, long toDay) {
		if (fromDay < 0L) return "";
		return fromDay == toDay ? "Day " + fromDay + ": " : "Days " + fromDay + "-" + toDay + ": ";
	}

	private static String clip(String line, int maxLength) {
		return line.length() <= maxLength ? line : line.substring(0, maxLength - 1).stripTrailing() + "…";
	}

	private static void appendSection(StringBuilder block, String heading, List<String> lines) {
		int start = block.length();
		block.append('\n').append(heading);
		int added = 0;
		for (String line : lines) {
			if (block.length() + line.length() + 1 > MAX_CHARS) break;
			block.append('\n').append(line);
			added++;
		}
		if (added == 0) block.setLength(start);
	}
}
