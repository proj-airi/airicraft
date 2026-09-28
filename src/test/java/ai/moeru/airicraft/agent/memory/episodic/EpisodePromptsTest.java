package ai.moeru.airicraft.agent.memory.episodic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EpisodePromptsTest {
	private static final Episode.Ground HERE = new Episode.Ground(12L, "minecraft:overworld", 120, 64, -40);

	@Test
	void episodeTaskListsWhatIsAlreadyRememberedAndForbidsTheFuture() {
		String task = EpisodePrompts.episodeTask(List.of(episode("Lava accident", "I fell into lava.", List.of("Rin"), 4)), "death");

		assertTrue(task.startsWith("EPISODE TASK:"));
		assertTrue(task.contains("- Lava accident: I fell into lava."));
		assertTrue(task.contains("This stretch ended because: death."));
		assertTrue(task.contains("Never record plans, intentions, predictions"));
		assertFalse(task.contains("{{"));
		assertTrue(EpisodePrompts.episodeTask(List.of(), "").contains("(none)"));
	}

	@Test
	void parsesAnEpisodeWithRuntimeGroundAndBoundedFields() {
		JsonObject reply = json("""
			{"episode":{"title":"Lava accident","summary":"I fell into lava while mining diamonds for Rin.  Rin laughed.",
			 "participants":["Rin","Rin","Kai"],"tags":["Funny","danger","death","loss","extra"],
			 "quotes":[{"speaker":"Rin","text":"not the diamonds!!"},{"speaker":"Kai","text":"rip"},{"speaker":"Rin","text":"third"}],
			 "salience":9}}
			""");

		Episode episode = EpisodePrompts.parseEpisode(reply, HERE, "death", "id-1", 5_000L).orElseThrow();

		assertEquals("id-1", episode.id());
		assertEquals(5_000L, episode.recordedAtMs());
		assertEquals(12L, episode.worldDay());
		assertEquals("minecraft:overworld", episode.dimension());
		assertEquals(List.of(120, 64, -40), List.of(episode.x(), episode.y(), episode.z()));
		assertEquals("death", episode.boundary());
		assertEquals("I fell into lava while mining diamonds for Rin. Rin laughed.", episode.summary());
		assertEquals(List.of("Rin", "Kai"), episode.participants());
		assertEquals(List.of("funny", "danger", "death", "loss"), episode.tags());
		assertEquals(2, episode.quotes().size());
		assertEquals(5, episode.salience());
	}

	@Test
	void nullMeansNothingNewAndMalformedRepliesThrow() {
		assertTrue(EpisodePrompts.parseEpisode(json("{\"episode\":null}"), HERE, "reset", "id", 0L).isEmpty());
		assertThrows(JsonParseException.class, () -> EpisodePrompts.parseEpisode(json("{}"), HERE, "reset", "id", 0L));
		assertThrows(JsonParseException.class, () -> EpisodePrompts.parseEpisode(
			json("{\"episode\":{\"title\":\"\",\"summary\":\"x\"}}"), HERE, "reset", "id", 0L));
		assertThrows(JsonParseException.class, () -> EpisodePrompts.parseEpisode(json("{\"episode\":\"text\"}"), HERE, "reset", "id", 0L));
	}

	@Test
	void consolidationMergesPeopleAndAppendsAChapter() {
		var digest = new MemoryDigest(1, 2, List.of(new MemoryDigest.Chapter(1, 3, 2, 10L, "We built a hut.")),
			Map.of("Rin", new MemoryDigest.Person(List.of("Likes birch."), 100L),
				"Kai", new MemoryDigest.Person(List.of("Plays at night."), 200L)));
		var batch = List.of(
			new Episode("a", 1_000L, 10L, "minecraft:overworld", 0, 64, 0, "death", "Lava", "I fell into lava.", List.of("Rin"), List.of(), List.of(), 4),
			new Episode("b", 2_000L, 12L, "minecraft:overworld", 0, 64, 0, "reset", "Gift", "Rin gave me bread.", List.of("Rin"), List.of(), List.of(), 3));
		var reply = json("""
			{"chapter":"I died in lava, and Rin fed me afterwards.","people":[{"name":"Rin","facts":["Likes birch.","Laughs when I die."]},{"name":"  ","facts":["x"]}]}
			""");

		MemoryDigest next = EpisodePrompts.applyConsolidation(digest, batch, reply, 3_000L);

		assertEquals(4, next.consolidatedEpisodes());
		assertEquals(2, next.chapters().size());
		assertEquals(new MemoryDigest.Chapter(10L, 12L, 2, 3_000L, "I died in lava, and Rin fed me afterwards."), next.chapters().getLast());
		assertEquals(new MemoryDigest.Person(List.of("Likes birch.", "Laughs when I die."), 2_000L), next.people().get("Rin"));
		assertEquals(digest.people().get("Kai"), next.people().get("Kai"), "Players missing from the batch keep their facts");
		assertThrows(JsonParseException.class, () -> EpisodePrompts.applyConsolidation(digest, batch, json("{\"people\":[]}"), 0L));
	}

	@Test
	void consolidationInputCarriesExistingFactsAndNewEpisodes() {
		var digest = new MemoryDigest(1, 0, List.of(), Map.of("Rin", new MemoryDigest.Person(List.of("Likes birch."), 0L)));
		var conversation = EpisodePrompts.consolidationConversation(digest, List.of(episode("Lava", "I fell into lava.", List.of("Rin"), 4)));

		assertEquals("system", conversation.messages().getFirst().role());
		String input = conversation.messages().getLast().content();
		assertTrue(input.contains("\"existingFacts\":{\"Rin\":[\"Likes birch.\"]}"));
		assertTrue(input.contains("\"summary\":\"I fell into lava.\""));
	}

	static Episode episode(String title, String summary, List<String> participants, int salience) {
		return new Episode(title, 0L, 3L, "minecraft:overworld", 1, 2, 3, "reset", title, summary, participants, List.of(), List.of(), salience);
	}

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}
}
