package ai.moeru.airicraft.agent.memory.episodic;

import ai.moeru.airicraft.agent.llm.LlmChatMessage;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmMessageKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EpisodicMemoryTest {
	private static final Episode.Ground HERE = new Episode.Ground(7L, "minecraft:overworld", 10, 64, 20);
	private static final String LAVA = """
		{"episode":{"title":"Lava accident","summary":"I fell into lava while Rin watched.","participants":["Rin"],
		 "tags":["funny"],"quotes":[],"salience":4}}
		""";

	@TempDir Path world;
	private final AtomicLong now = new AtomicLong(0L);
	private final AtomicReference<Path> boundWorld = new AtomicReference<>();
	private final List<String> context = new ArrayList<>(List.of("Rin: dig here", "I dug and fell into lava."));
	private final FakeModel model = new FakeModel();
	private final EpisodicMemory memory = new EpisodicMemory(model, this::conversation, boundWorld::get, () -> HERE,
		() -> Set.of("Rin"), now::get, new DirectExecutor());

	@Test
	void anEndingContextBecomesAnEpisodeReadFromThePlannersOwnMessages() {
		enterWorld();
		model.replies.add(LAVA);

		memory.contextEnding("compaction");

		assertEquals(1, model.calls.size());
		var sent = model.calls.getFirst().messages();
		assertEquals(conversation().messages(), sent.subList(0, sent.size() - 1), "The episode reads the same context the planner has");
		assertTrue(sent.getLast().content().startsWith("EPISODE TASK:"));
		assertTrue(sent.getLast().content().contains("This stretch ended because: compaction."));
		Episode episode = EpisodeStore.open(world).episodes().getFirst();
		assertEquals("I fell into lava while Rin watched.", episode.summary());
		assertEquals("compaction", episode.boundary());
		assertEquals(List.of(7L, 10L, 64L, 20L), List.of(episode.worldDay(), (long) episode.x(), (long) episode.y(), (long) episode.z()));

		memory.contextEnding("reset");
		assertEquals(1, model.calls.size(), "Nothing new since the last episode, so no call");

		context.add("Rin: you ok?");
		model.replies.add("{\"episode\":null}");
		memory.contextEnding("reset");
		assertEquals(2, model.calls.size());
		assertTrue(model.calls.getLast().messages().getLast().content().contains("- Lava accident: I fell into lava while Rin watched."),
			"The next task lists what is already remembered");
		assertEquals(1, EpisodeStore.open(world).episodes().size());
	}

	@Test
	void storyBoundariesWaitForThePlannerAndForSpacing() {
		enterWorld();
		model.replies.add(LAVA);

		memory.onEvent("food.eaten");
		memory.onEvent("player.died");
		now.set(40_000L);
		memory.tick();
		assertEquals(0, model.calls.size(), "Episodes stay a few minutes apart");

		now.set(EpisodicMemory.MIN_STORY_GAP_MS);
		memory.tick();
		assertEquals(1, model.calls.size());
		assertTrue(model.calls.getFirst().messages().getLast().content().contains("ended because: death."));
	}

	@Test
	void quietPlayStillBecomesAnEpisode() {
		enterWorld();
		model.replies.add(LAVA);

		now.set(EpisodicMemory.ELAPSED_MS - 1);
		memory.tick();
		assertEquals(0, model.calls.size());
		now.set(EpisodicMemory.ELAPSED_MS);
		memory.tick();

		assertEquals(1, model.calls.size());
		assertTrue(model.calls.getFirst().messages().getLast().content().contains("ended because: time passed."));
	}

	@Test
	void leavingTheWorldTurnsTheSessionIntoAChapter() {
		enterWorld();
		model.replies.add(LAVA);
		model.replies.add("{\"chapter\":\"I fell into lava while Rin watched.\",\"people\":[{\"name\":\"Rin\",\"facts\":[\"Watches me dig.\"]}]}");

		memory.worldLeaving();

		assertEquals(2, model.calls.size());
		assertEquals("system", model.calls.getLast().messages().getFirst().role());
		MemoryDigest digest = EpisodeStore.open(world).digest();
		assertEquals(1, digest.consolidatedEpisodes());
		assertEquals("I fell into lava while Rin watched.", digest.chapters().getFirst().summary());
		assertEquals(List.of("Watches me dig."), digest.people().get("Rin").facts());
	}

	@Test
	void recallFollowsTheWorldAndItsMemories() {
		assertNull(memory.recall(), "No world yet: recall again later");
		enterWorld();
		assertEquals("", memory.recall());

		model.replies.add(LAVA);
		memory.contextEnding("reset");

		String block = memory.recall();
		assertTrue(block.startsWith(MemoryRecall.HEADER));
		assertTrue(block.contains("I fell into lava while Rin watched."));
	}

	@Test
	void failuresAndDisabledMemoryNeverReachThePlanner() {
		enterWorld();
		model.failure = new IllegalStateException("provider down");
		assertDoesNotThrow(() -> memory.contextEnding("compaction"));
		assertEquals(List.of(), EpisodeStore.open(world).episodes());

		context.add("more play");
		memory.disable();
		memory.contextEnding("reset");
		memory.onEvent("player.died");
		now.set(EpisodicMemory.ELAPSED_MS * 2);
		memory.tick();
		assertEquals(1, model.calls.size());
		assertEquals("", memory.recall());
		assertEquals("", EpisodicMemory.disabled().recall());
	}

	@Test
	void aContextWithoutNewPlayIsNotWorthACall() {
		enterWorld();
		context.clear();
		context.add("only one line");

		memory.contextEnding("reset");

		assertEquals(0, model.calls.size());
	}

	private void enterWorld() {
		boundWorld.set(world);
		memory.tick();
	}

	private LlmConversation conversation() {
		var messages = new ArrayList<LlmChatMessage>();
		messages.add(LlmChatMessage.system("You are a Minecraft companion."));
		messages.add(LlmChatMessage.user("MEMORY\nMoments:\n- older", LlmMessageKind.CHECKPOINT));
		for (String line : context) messages.add(LlmChatMessage.user(line, LlmMessageKind.USER_TURN));
		return LlmConversation.of(messages);
	}

	private static final class FakeModel implements EpisodicMemory.Model {
		final ArrayDeque<String> replies = new ArrayDeque<>();
		final List<LlmConversation> calls = new ArrayList<>();
		RuntimeException failure;

		@Override
		public JsonObject complete(LlmConversation conversation) {
			calls.add(conversation);
			if (failure != null) throw failure;
			return JsonParser.parseString(replies.remove()).getAsJsonObject();
		}
	}

	/** Runs memory jobs inline so tests read their results immediately. */
	private static final class DirectExecutor extends AbstractExecutorService {
		private boolean shutdown;

		@Override public void execute(Runnable command) { command.run(); }
		@Override public void shutdown() { shutdown = true; }
		@Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
		@Override public boolean isShutdown() { return shutdown; }
		@Override public boolean isTerminated() { return shutdown; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
	}
}
