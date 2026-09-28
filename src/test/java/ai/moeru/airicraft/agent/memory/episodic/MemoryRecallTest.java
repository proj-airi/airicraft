package ai.moeru.airicraft.agent.memory.episodic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRecallTest {
	private static final long DAY = 86_400_000L;

	@Test
	void nothingToRecallRendersNothing() {
		assertEquals("", MemoryRecall.render(List.of(), MemoryDigest.EMPTY, Set.of("Rin"), 0L));
	}

	@Test
	void rendersPeopleHereFirstThenChaptersThenMoments() {
		var digest = new MemoryDigest(1, 0, List.of(new MemoryDigest.Chapter(1, 3, 4, 0L, "We built a hut by the lake.")),
			Map.of("Kai", new MemoryDigest.Person(List.of("Plays at night."), 900L),
				"Rin", new MemoryDigest.Person(List.of("Likes birch.", "Laughs when I die."), 100L),
				"Quiet", new MemoryDigest.Person(List.of(), 1_000L)));
		var lava = new Episode("a", 0L, 12L, "minecraft:the_nether", 5, 40, -7, "death", "Lava", "I fell into lava.",
			List.of("Rin"), List.of("funny"), List.of(new Episode.Quote("Rin", "not the diamonds!!")), 5);

		String block = MemoryRecall.render(List.of(lava), digest, Set.of("Rin"), 0L);

		assertTrue(block.startsWith(MemoryRecall.HEADER));
		assertTrue(block.indexOf("- Rin (here now): Likes birch.; Laughs when I die.") < block.indexOf("- Kai: Plays at night."));
		assertFalse(block.contains("Quiet"), "People without facts add nothing");
		assertTrue(block.contains("Earlier:\n- Days 1-3: We built a hut by the lake."));
		assertTrue(block.contains("- Day 12, the_nether near 5 40 -7: I fell into lava. Rin: \"not the diamonds!!\""));
		assertTrue(block.indexOf("People:") < block.indexOf("Earlier:") && block.indexOf("Earlier:") < block.indexOf("Moments:"));
	}

	@Test
	void momentsAreTheLatestFewPlusTheMostMemorableOlderOnesInTimeOrder() {
		var episodes = new ArrayList<Episode>();
		for (int i = 0; i < 10; i++) {
			int salience = i == 2 ? 5 : i == 5 ? 4 : 1;
			episodes.add(new Episode("e" + i, i * DAY, i, "", 0, 0, 0, "reset", "t" + i, "moment " + i,
				i == 7 ? List.of("Rin") : List.of(), List.of(), List.of(), salience));
		}

		var picked = MemoryRecall.moments(episodes, Set.of("Rin"), 10 * DAY).stream().map(Episode::id).toList();

		assertEquals(List.of("e2", "e5", "e6", "e7", "e8", "e9"), picked);
	}

	@Test
	void theBlockStaysWithinItsBudget() {
		var episodes = new ArrayList<Episode>();
		for (int i = 0; i < 20; i++) {
			episodes.add(new Episode("e" + i, 0L, i, "", 0, 0, 0, "reset", "t", "x".repeat(590), List.of(), List.of(), List.of(), 3));
		}
		var people = Map.of("Rin", new MemoryDigest.Person(List.of("y".repeat(150), "z".repeat(150)), 0L));

		String block = MemoryRecall.render(episodes, new MemoryDigest(1, 0, List.of(), people), Set.of(), 0L);

		assertTrue(block.length() <= MemoryRecall.MAX_CHARS, () -> "length " + block.length());
		assertTrue(block.contains("- Rin: "));
		assertTrue(block.lines().filter(line -> line.startsWith("- Rin")).allMatch(line -> line.length() <= MemoryRecall.MAX_PERSON_CHARS));
	}
}
