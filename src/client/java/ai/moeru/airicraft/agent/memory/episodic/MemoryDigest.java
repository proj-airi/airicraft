package ai.moeru.airicraft.agent.memory.episodic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Long-term memory rebuilt from episodes: one chapter per consolidated batch and a short fact list
 * per player. Derived and rewritable; {@code episodes.jsonl} stays the source of truth.
 */
public record MemoryDigest(int version, int consolidatedEpisodes, List<Chapter> chapters, Map<String, Person> people) {
	public static final MemoryDigest EMPTY = new MemoryDigest(1, 0, List.of(), Map.of());

	public record Chapter(long fromDay, long toDay, int episodes, long recordedAtMs, String summary) {
		public Chapter {
			summary = summary == null ? "" : summary;
		}
	}

	public record Person(List<String> facts, long lastSeenMs) {
		public Person {
			facts = facts == null ? List.of() : List.copyOf(facts);
		}
	}

	public MemoryDigest {
		consolidatedEpisodes = Math.max(0, consolidatedEpisodes);
		chapters = chapters == null ? List.of() : List.copyOf(chapters);
		people = people == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(people));
	}
}
