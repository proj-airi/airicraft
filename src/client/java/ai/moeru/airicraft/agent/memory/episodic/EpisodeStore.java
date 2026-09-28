package ai.moeru.airicraft.agent.memory.episodic;

import ai.moeru.airicraft.Airicraft;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-world memory files under {@code <world>/airicraft/}: append-only {@code episodes.jsonl} and the
 * derived {@code memory.json}. Jobs write from the memory thread while recall reads on the client thread.
 */
public final class EpisodeStore {
	private static final Gson LINE = new Gson();
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

	private final Path episodesFile;
	private final Path digestFile;
	private final List<Episode> episodes = new ArrayList<>();
	private MemoryDigest digest = MemoryDigest.EMPTY;
	private long loadedEpisodesSize = -1L;
	private long loadedDigestModified = -1L;

	private EpisodeStore(Path worldDirectory) {
		Path directory = worldDirectory.resolve("airicraft");
		episodesFile = directory.resolve("episodes.jsonl");
		digestFile = directory.resolve("memory.json");
	}

	public static EpisodeStore open(Path worldDirectory) {
		EpisodeStore store = new EpisodeStore(Objects.requireNonNull(worldDirectory, "worldDirectory"));
		store.reloadIfChanged();
		return store;
	}

	/** Picks up writes from another runtime, such as the one replaced by a reload, before this one writes. */
	public synchronized void reloadIfChanged() {
		try {
			long size = Files.exists(episodesFile) ? Files.size(episodesFile) : 0L;
			if (size != loadedEpisodesSize) {
				episodes.clear();
				if (size > 0L) {
					for (String line : Files.readAllLines(episodesFile)) {
						if (line.isBlank()) continue;
						try {
							Episode episode = LINE.fromJson(line, Episode.class);
							if (episode != null && !episode.summary().isBlank()) episodes.add(episode);
						}
						catch (JsonParseException malformed) {
							Airicraft.LOGGER.warn("Skipping malformed episode line in {}", episodesFile);
						}
					}
				}
				loadedEpisodesSize = size;
			}
			long modified = Files.exists(digestFile) ? Files.getLastModifiedTime(digestFile).toMillis() : 0L;
			if (modified != loadedDigestModified) {
				MemoryDigest loaded = modified == 0L ? null : PRETTY.fromJson(Files.readString(digestFile), MemoryDigest.class);
				digest = loaded == null ? MemoryDigest.EMPTY : loaded;
				loadedDigestModified = modified;
			}
		}
		catch (IOException | JsonParseException exception) {
			Airicraft.LOGGER.warn("Cannot read episodic memory in {}: {}", episodesFile.getParent(), exception.toString());
		}
	}

	public synchronized List<Episode> episodes() {
		return List.copyOf(episodes);
	}

	public synchronized List<Episode> recent(int limit) {
		return List.copyOf(episodes.subList(Math.max(0, episodes.size() - limit), episodes.size()));
	}

	public synchronized MemoryDigest digest() {
		return digest;
	}

	/** Episodes not yet folded into the digest, oldest first. */
	public synchronized List<Episode> unconsolidated() {
		int from = Math.min(digest.consolidatedEpisodes(), episodes.size());
		return List.copyOf(episodes.subList(from, episodes.size()));
	}

	public synchronized void append(Episode episode) throws IOException {
		Files.createDirectories(episodesFile.getParent());
		Files.writeString(episodesFile, LINE.toJson(episode) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		episodes.add(episode);
		loadedEpisodesSize = Files.size(episodesFile);
	}

	public synchronized void saveDigest(MemoryDigest replacement) throws IOException {
		Files.createDirectories(digestFile.getParent());
		Path temporary = digestFile.resolveSibling("memory.json.tmp");
		Files.writeString(temporary, PRETTY.toJson(replacement) + "\n");
		Files.move(temporary, digestFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		digest = replacement;
		loadedDigestModified = Files.getLastModifiedTime(digestFile).toMillis();
	}
}
