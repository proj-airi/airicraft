package ai.moeru.airicraft.agent.memory.episodic;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.llm.LlmChatMessage;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmMessageKind;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Autobiographical memory beside the controller planner. It reads the planner's own context when a
 * stretch of play ends and writes what happened, never what comes next. Nothing it writes enters the
 * live context; a new context (session start or after compaction) begins with a recalled MEMORY block.
 * Client-thread methods: {@link #tick}, {@link #onEvent}, {@link #contextEnding}, {@link #recall}.
 */
public final class EpisodicMemory implements AutoCloseable {
	/** Story boundaries: the stretch around them becomes an episode once the planner has taken it in. */
	public static final Map<String, String> BOUNDARY_EVENTS = Map.of(
		"player.died", "death",
		"planner.goal_set", "new goal",
		"planner.goal_cleared", "goal ended",
		"social.player_joined_game", "player joined",
		"social.player_left_game", "player left");
	static final long SETTLE_MS = 30_000L;
	static final long MIN_STORY_GAP_MS = 3 * 60_000L;
	static final long ELAPSED_MS = 20 * 60_000L;
	static final int CONSOLIDATE_AFTER = 6;
	static final int MAX_CONSOLIDATION_BATCH = 12;
	static final int REMEMBERED_IN_TASK = 5;

	/** One JSON-object completion outside the planner loop. */
	@FunctionalInterface
	public interface Model {
		JsonObject complete(LlmConversation conversation) throws Exception;
	}

	private final Model model;
	private final Supplier<LlmConversation> context;
	private final Supplier<Path> worldDirectory;
	private final Supplier<Episode.Ground> ground;
	private final Supplier<Set<String>> presentPlayers;
	private final LongSupplier clock;
	private final ExecutorService executor;
	private boolean enabled;
	private Path boundWorld;
	private EpisodeStore store;
	private String pendingBoundary;
	private long pendingDueMs;
	private long lastCutMs;
	private String lastFingerprint;

	public EpisodicMemory(Model model, Supplier<LlmConversation> context, Supplier<Path> worldDirectory,
		Supplier<Episode.Ground> ground, Supplier<Set<String>> presentPlayers, LongSupplier clock) {
		this(model, context, worldDirectory, ground, presentPlayers, clock, Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "airicraft-memory");
			thread.setDaemon(true);
			return thread;
		}));
	}

	EpisodicMemory(Model model, Supplier<LlmConversation> context, Supplier<Path> worldDirectory,
		Supplier<Episode.Ground> ground, Supplier<Set<String>> presentPlayers, LongSupplier clock, ExecutorService executor) {
		this.model = Objects.requireNonNull(model, "model");
		this.context = Objects.requireNonNull(context, "context");
		this.worldDirectory = Objects.requireNonNull(worldDirectory, "worldDirectory");
		this.ground = Objects.requireNonNull(ground, "ground");
		this.presentPlayers = Objects.requireNonNull(presentPlayers, "presentPlayers");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.executor = executor;
		this.enabled = executor != null;
	}

	/** For planner backends that keep history on the provider side, where there is no local context to read. */
	public static EpisodicMemory disabled() {
		return new EpisodicMemory(conversation -> null, () -> null, () -> null, () -> Episode.Ground.UNKNOWN, Set::of, () -> 0L, null);
	}

	/** Evaluations measure tasks from a known context; memory stays out of them for the rest of the session. */
	public void disable() {
		enabled = false;
		pendingBoundary = null;
	}

	public void tick() {
		refreshWorld();
		if (!enabled || store == null) return;
		long now = clock.getAsLong();
		if (pendingBoundary != null) {
			if (now >= pendingDueMs && now - lastCutMs >= MIN_STORY_GAP_MS) cut(pendingBoundary, false);
		}
		else if (now - lastCutMs >= ELAPSED_MS) cut("time passed", false);
	}

	public void onEvent(String type) {
		String boundary = BOUNDARY_EVENTS.get(type);
		if (!enabled || boundary == null || pendingBoundary != null) return;
		pendingBoundary = boundary;
		pendingDueMs = clock.getAsLong() + SETTLE_MS;
	}

	/** The planner is about to compact, reset or shut down: whatever it has not remembered yet is written now. */
	public void contextEnding(String reason) {
		if (enabled && store != null) cut(reason, false);
	}

	/** Leaving the world closes a session, so unconsolidated episodes also become a chapter. */
	public void worldLeaving() {
		if (enabled && store != null) cut("left the world", true);
	}

	/** The MEMORY block for a new context: empty without memories, null while no world is bound yet. */
	public String recall() {
		if (!enabled) return "";
		refreshWorld();
		EpisodeStore current = store;
		if (current == null) return null;
		return MemoryRecall.render(current.episodes(), current.digest(), safePresentPlayers(), clock.getAsLong());
	}

	@Override
	public void close() {
		if (executor == null) return;
		executor.shutdown();
		try {
			// Let the last episode of a session finish writing without holding up the client for long.
			executor.awaitTermination(3, TimeUnit.SECONDS);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private void refreshWorld() {
		if (!enabled) return;
		Path world;
		try {
			world = worldDirectory.get();
		}
		catch (RuntimeException unavailable) {
			world = null;
		}
		if (Objects.equals(world, boundWorld)) return;
		boundWorld = world;
		store = world == null ? null : EpisodeStore.open(world);
		pendingBoundary = null;
		lastFingerprint = null;
		lastCutMs = clock.getAsLong();
	}

	private void cut(String boundary, boolean consolidateSession) {
		EpisodeStore target = store;
		pendingBoundary = null;
		lastCutMs = clock.getAsLong();
		LlmConversation base;
		try {
			base = context.get();
		}
		catch (RuntimeException failure) {
			Airicraft.LOGGER.warn("Cannot read planner context for episodic memory: {}", failure.toString());
			return;
		}
		boolean fresh = base != null && storyMessages(base) >= 2 && !fingerprint(base).equals(lastFingerprint);
		if (fresh) lastFingerprint = fingerprint(base);
		if (!fresh && !consolidateSession) return;
		Episode.Ground where = safeGround();
		long now = clock.getAsLong();
		try {
			executor.execute(() -> {
				target.reloadIfChanged();
				if (fresh) writeEpisode(target, base, boundary, where, now);
				consolidate(target, consolidateSession ? 1 : CONSOLIDATE_AFTER);
			});
		}
		catch (RejectedExecutionException closed) {
			Airicraft.LOGGER.debug("Episodic memory is closed; dropping the {} stretch", boundary);
		}
	}

	private void writeEpisode(EpisodeStore target, LlmConversation base, String boundary, Episode.Ground where, long now) {
		List<LlmChatMessage> messages = new ArrayList<>(base.messages());
		messages.add(LlmChatMessage.user(EpisodePrompts.episodeTask(target.recent(REMEMBERED_IN_TASK), boundary), LlmMessageKind.TASK));
		try {
			var episode = EpisodePrompts.parseEpisode(model.complete(LlmConversation.of(messages)), where, boundary,
				UUID.randomUUID().toString(), now);
			if (episode.isPresent()) {
				target.append(episode.get());
				Airicraft.LOGGER.info("Remembered episode: {}", episode.get().title());
			}
		}
		catch (Exception failure) {
			Airicraft.LOGGER.warn("Episodic memory skipped a stretch ({}): {}", boundary, failure.toString());
		}
	}

	private void consolidate(EpisodeStore target, int threshold) {
		List<Episode> pending = target.unconsolidated();
		if (pending.isEmpty() || pending.size() < threshold) return;
		List<Episode> batch = pending.subList(0, Math.min(MAX_CONSOLIDATION_BATCH, pending.size()));
		try {
			MemoryDigest digest = target.digest();
			JsonObject reply = model.complete(EpisodePrompts.consolidationConversation(digest, batch));
			target.saveDigest(EpisodePrompts.applyConsolidation(digest, batch, reply, clock.getAsLong()));
			Airicraft.LOGGER.info("Consolidated {} episodes into a memory chapter", batch.size());
		}
		catch (Exception failure) {
			Airicraft.LOGGER.warn("Episodic memory consolidation failed; episodes stay unconsolidated: {}", failure.toString());
		}
	}

	/** Messages that can hold new play: not the system prompt, checkpoint or recalled memory. */
	private static int storyMessages(LlmConversation conversation) {
		int count = 0;
		for (LlmChatMessage message : conversation.messages()) {
			if (message.kind() != LlmMessageKind.SYSTEM && message.kind() != LlmMessageKind.CHECKPOINT) count++;
		}
		return count;
	}

	private static String fingerprint(LlmConversation conversation) {
		List<LlmChatMessage> messages = conversation.messages();
		LlmChatMessage last = messages.getLast();
		return messages.size() + ":" + last.role() + ":" + Objects.hashCode(last.content()) + ":" + last.toolCalls().size();
	}

	private Episode.Ground safeGround() {
		try {
			Episode.Ground observed = ground.get();
			return observed == null ? Episode.Ground.UNKNOWN : observed;
		}
		catch (RuntimeException unavailable) {
			return Episode.Ground.UNKNOWN;
		}
	}

	private Set<String> safePresentPlayers() {
		try {
			Set<String> players = presentPlayers.get();
			return players == null ? Set.of() : players;
		}
		catch (RuntimeException unavailable) {
			return Set.of();
		}
	}
}
