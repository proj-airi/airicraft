package ai.moeru.airicraft.agent.recording;

import ai.moeru.airicraft.agent.debug.AgentDebugTimelineQueryResult;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.function.Function;

/**
 * Experimental: an append-only, per-world record of semantic events and debug-timeline entries,
 * kept across sessions so an offline episoder ({@code scripts/episoder.py}) can build episodic
 * memory from it. Unlike the rolling flight recorder it survives the 12,000-tick window; unlike a
 * playtest recording it carries no frames, snapshots or LLM envelopes. Opt in with
 * {@code -Dairicraft.episodeStream=true}.
 */
public final class EpisodeStream {
	public static final String ENABLE_PROPERTY = "airicraft.episodeStream";
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private final Path file;
	private Long latestEventSeqNo;
	private Long latestTimelineEntryId;

	public EpisodeStream(Path worldRoot) throws IOException {
		Path directory = worldRoot.resolve("airicraft").resolve("episodes");
		Files.createDirectories(directory);
		file = directory.resolve("stream.jsonl");
	}

	public static boolean enabled() {
		return Boolean.getBoolean(ENABLE_PROPERTY);
	}

	public Path file() { return file; }

	/** Appends what is new since the last call; a restarted source sequence is read from its start. */
	public void drain(Function<Long, SemanticEventQueryResult> events, Function<Long, AgentDebugTimelineQueryResult> timeline,
		String collectedAt) throws IOException {
		var eventResult = events.apply(latestEventSeqNo);
		if (latestEventSeqNo != null && eventResult.latestSeqNo() < latestEventSeqNo) {
			eventResult = events.apply(null);
		}
		var timelineResult = timeline.apply(latestTimelineEntryId);
		if (latestTimelineEntryId != null && timelineResult.latestEntryId() < latestTimelineEntryId) {
			timelineResult = timeline.apply(null);
		}
		StringBuilder lines = new StringBuilder();
		for (var event : eventResult.events()) {
			append(lines, "event", collectedAt, event);
		}
		for (var entry : timelineResult.entries()) {
			append(lines, "timeline", collectedAt, entry);
		}
		latestEventSeqNo = eventResult.latestSeqNo();
		latestTimelineEntryId = timelineResult.latestEntryId();
		if (lines.isEmpty()) return;
		Files.writeString(file, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private static void append(StringBuilder lines, String kind, String collectedAt, Object value) {
		var record = new LinkedHashMap<String, Object>();
		record.put("collectedAt", collectedAt);
		record.put(kind, value);
		lines.append(GSON.toJson(record)).append('\n');
	}
}
