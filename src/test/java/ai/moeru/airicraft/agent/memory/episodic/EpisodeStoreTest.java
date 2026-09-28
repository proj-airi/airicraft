package ai.moeru.airicraft.agent.memory.episodic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EpisodeStoreTest {
	@TempDir Path world;

	@Test
	void episodesAndDigestSurviveReopening() throws Exception {
		EpisodeStore store = EpisodeStore.open(world);
		Episode lava = EpisodePromptsTest.episode("Lava", "I fell into lava.", List.of("Rin"), 4);
		store.append(lava);
		store.append(EpisodePromptsTest.episode("Bread", "Rin gave me bread.", List.of("Rin"), 3));
		store.saveDigest(new MemoryDigest(1, 1, List.of(new MemoryDigest.Chapter(3, 3, 1, 5L, "A lava day.")),
			Map.of("Rin", new MemoryDigest.Person(List.of("Likes birch."), 5L))));

		EpisodeStore reopened = EpisodeStore.open(world);

		assertEquals(2, reopened.episodes().size());
		assertEquals(lava, reopened.episodes().getFirst());
		assertEquals(List.of("Bread"), reopened.unconsolidated().stream().map(Episode::title).toList());
		assertEquals("A lava day.", reopened.digest().chapters().getFirst().summary());
		assertEquals(List.of("Likes birch."), reopened.digest().people().get("Rin").facts());
		assertTrue(Files.exists(world.resolve("airicraft/episodes.jsonl")));
		assertTrue(Files.exists(world.resolve("airicraft/memory.json")));
	}

	@Test
	void malformedLinesAreSkippedAndOtherWritersArePickedUp() throws Exception {
		EpisodeStore first = EpisodeStore.open(world);
		first.append(EpisodePromptsTest.episode("Lava", "I fell into lava.", List.of(), 4));
		Files.writeString(world.resolve("airicraft/episodes.jsonl"), "{not json\n\n", StandardOpenOption.APPEND);
		EpisodeStore second = EpisodeStore.open(world);
		second.append(EpisodePromptsTest.episode("Cave", "I found a cave.", List.of(), 2));

		first.reloadIfChanged();

		assertEquals(List.of("Lava", "Cave"), first.episodes().stream().map(Episode::title).toList());
		assertEquals(List.of("Cave"), first.recent(1).stream().map(Episode::title).toList());
		assertEquals(2, first.unconsolidated().size());
	}

	@Test
	void anEmptyWorldHasNoMemory() {
		EpisodeStore store = EpisodeStore.open(world);

		assertEquals(List.of(), store.episodes());
		assertEquals(MemoryDigest.EMPTY, store.digest());
		assertFalse(Files.exists(world.resolve("airicraft")), "Opening must not create files");
	}
}
