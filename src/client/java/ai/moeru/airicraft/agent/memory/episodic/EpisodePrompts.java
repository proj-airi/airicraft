package ai.moeru.airicraft.agent.memory.episodic;

import ai.moeru.airicraft.agent.llm.LlmChatMessage;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmMessageKind;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Episode and consolidation prompts, and tolerant parsing of what the model returns. */
public final class EpisodePrompts {
	private static final Gson GSON = new Gson();
	private static final String EPISODE_TEMPLATE = read("/prompts/memory-episode.md");
	private static final String CONSOLIDATION_INSTRUCTION = read("/prompts/memory-consolidation.md");
	static final int MAX_FACTS = 8;

	private EpisodePrompts() {
	}

	public static String episodeTask(List<Episode> alreadyRemembered, String boundary) {
		StringBuilder recorded = new StringBuilder();
		for (Episode episode : alreadyRemembered) {
			recorded.append("- ").append(episode.title()).append(": ").append(episode.summary()).append('\n');
		}
		return EPISODE_TEMPLATE
			.replace("{{boundary}}", boundary == null || boundary.isBlank() ? "unknown" : boundary)
			.replace("{{recorded}}", recorded.isEmpty() ? "(none)\n" : recorded.toString());
	}

	/** Empty when the model found nothing new; throws when the reply is not a usable episode. */
	public static Optional<Episode> parseEpisode(JsonObject response, Episode.Ground ground, String boundary, String id, long nowMs) {
		if (response == null || !response.has("episode")) throw new JsonParseException("Missing episode");
		JsonElement value = response.get("episode");
		if (value.isJsonNull()) return Optional.empty();
		if (!value.isJsonObject()) throw new JsonParseException("episode must be an object or null");
		JsonObject episode = value.getAsJsonObject();
		String title = clip(string(episode, "title"), 80);
		String summary = clip(string(episode, "summary"), 600);
		if (title.isBlank() || summary.isBlank()) throw new JsonParseException("episode needs a title and summary");
		List<Episode.Quote> quotes = new ArrayList<>();
		if (episode.get("quotes") instanceof JsonArray array) {
			for (JsonElement quote : array) {
				if (quotes.size() == 2 || !quote.isJsonObject()) continue;
				String speaker = clip(string(quote.getAsJsonObject(), "speaker"), 40);
				String text = clip(string(quote.getAsJsonObject(), "text"), 160);
				if (!speaker.isBlank() && !text.isBlank()) quotes.add(new Episode.Quote(speaker, text));
			}
		}
		int salience = episode.has("salience") && episode.get("salience").isJsonPrimitive()
			&& episode.getAsJsonPrimitive("salience").isNumber() ? episode.get("salience").getAsInt() : 2;
		Episode.Ground where = ground == null ? Episode.Ground.UNKNOWN : ground;
		return Optional.of(new Episode(id, nowMs, where.worldDay(), where.dimension(), where.x(), where.y(), where.z(),
			boundary, title, summary, strings(episode, "participants", 8, 40, false), strings(episode, "tags", 4, 24, true),
			quotes, salience));
	}

	public static LlmConversation consolidationConversation(MemoryDigest digest, List<Episode> newEpisodes) {
		Map<String, Object> input = new LinkedHashMap<>();
		Map<String, List<String>> people = new LinkedHashMap<>();
		digest.people().forEach((name, person) -> people.put(name, person.facts()));
		input.put("existingFacts", people);
		List<String> chapters = digest.chapters().stream().skip(Math.max(0, digest.chapters().size() - 2L))
			.map(MemoryDigest.Chapter::summary).toList();
		input.put("latestChapters", chapters);
		List<Map<String, Object>> episodes = new ArrayList<>();
		for (Episode episode : newEpisodes) {
			Map<String, Object> entry = new LinkedHashMap<>();
			if (episode.worldDay() >= 0L) entry.put("day", episode.worldDay());
			entry.put("title", episode.title());
			entry.put("summary", episode.summary());
			entry.put("participants", episode.participants());
			entry.put("tags", episode.tags());
			if (!episode.quotes().isEmpty()) entry.put("quotes", episode.quotes());
			episodes.add(entry);
		}
		input.put("newEpisodes", episodes);
		return LlmConversation.of(List.of(
			LlmChatMessage.system(CONSOLIDATION_INSTRUCTION),
			LlmChatMessage.user(GSON.toJson(input), LlmMessageKind.TASK)));
	}

	/** Folds a consolidation reply into the digest; throws when the reply lacks a chapter. */
	public static MemoryDigest applyConsolidation(MemoryDigest digest, List<Episode> newEpisodes, JsonObject response, long nowMs) {
		String summary = response == null ? "" : clip(string(response, "chapter"), 600);
		if (summary.isBlank()) throw new JsonParseException("Consolidation needs a chapter");
		Map<String, MemoryDigest.Person> people = new LinkedHashMap<>(digest.people());
		if (response.get("people") instanceof JsonArray updates) {
			for (JsonElement update : updates) {
				if (!update.isJsonObject()) continue;
				String name = clip(string(update.getAsJsonObject(), "name"), 40);
				if (name.isBlank()) continue;
				MemoryDigest.Person previous = people.get(name);
				people.put(name, new MemoryDigest.Person(strings(update.getAsJsonObject(), "facts", MAX_FACTS, 160, false),
					previous == null ? 0L : previous.lastSeenMs()));
			}
		}
		long fromDay = -1L;
		long toDay = -1L;
		for (Episode episode : newEpisodes) {
			for (String participant : episode.participants()) {
				MemoryDigest.Person person = people.get(participant);
				if (person != null) people.put(participant, new MemoryDigest.Person(person.facts(), episode.recordedAtMs()));
			}
			if (episode.worldDay() < 0L) continue;
			fromDay = fromDay < 0L ? episode.worldDay() : Math.min(fromDay, episode.worldDay());
			toDay = Math.max(toDay, episode.worldDay());
		}
		List<MemoryDigest.Chapter> chapters = new ArrayList<>(digest.chapters());
		chapters.add(new MemoryDigest.Chapter(fromDay, toDay, newEpisodes.size(), nowMs, summary));
		return new MemoryDigest(1, digest.consolidatedEpisodes() + newEpisodes.size(), chapters, people);
	}

	private static List<String> strings(JsonObject object, String field, int limit, int maxLength, boolean lowerCase) {
		List<String> values = new ArrayList<>();
		if (!(object.get(field) instanceof JsonArray array)) return values;
		for (JsonElement element : array) {
			if (values.size() == limit) break;
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) continue;
			String value = clip(element.getAsString(), maxLength);
			if (lowerCase) value = value.toLowerCase(Locale.ROOT);
			if (!value.isBlank() && !values.contains(value)) values.add(value);
		}
		return values;
	}

	private static String string(JsonObject object, String field) {
		JsonElement value = object.get(field);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
	}

	private static String clip(String value, int maxLength) {
		String single = value == null ? "" : value.replaceAll("\\s+", " ").strip();
		return single.length() <= maxLength ? single : single.substring(0, maxLength - 1).stripTrailing() + "…";
	}

	private static String read(String resourcePath) {
		try (InputStream stream = EpisodePrompts.class.getResourceAsStream(resourcePath)) {
			if (stream == null) throw new IllegalStateException("Missing embedded memory prompt: " + resourcePath);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new UncheckedIOException("Failed to read memory prompt: " + resourcePath, exception);
		}
	}
}
