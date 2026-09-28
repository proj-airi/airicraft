package ai.moeru.airicraft.agent.memory.episodic;

import java.util.List;

/**
 * One autobiographical memory: a neutral, past-tense record of a stretch of play. The model writes
 * the story fields; the runtime supplies where, when and why the stretch ended.
 */
public record Episode(
	String id,
	long recordedAtMs,
	long worldDay,
	String dimension,
	int x,
	int y,
	int z,
	String boundary,
	String title,
	String summary,
	List<String> participants,
	List<String> tags,
	List<Quote> quotes,
	int salience
) {
	public record Quote(String speaker, String text) { }

	public Episode {
		id = id == null ? "" : id;
		dimension = dimension == null ? "" : dimension;
		boundary = boundary == null ? "" : boundary;
		title = title == null ? "" : title;
		summary = summary == null ? "" : summary;
		participants = participants == null ? List.of() : List.copyOf(participants);
		tags = tags == null ? List.of() : List.copyOf(tags);
		quotes = quotes == null ? List.of() : List.copyOf(quotes);
		salience = Math.max(1, Math.min(5, salience));
	}

	/** Where and when a stretch of play ended, observed by the runtime rather than written by the model. */
	public record Ground(long worldDay, String dimension, int x, int y, int z) {
		public static final Ground UNKNOWN = new Ground(-1L, "", 0, 0, 0);
	}
}
