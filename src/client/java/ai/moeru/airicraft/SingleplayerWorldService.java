package ai.moeru.airicraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.FileUtil;
import net.minecraft.world.level.validation.ContentValidationException;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelStorageException;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.util.DirectoryLock;

import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class SingleplayerWorldService {
	private static final int WORLD_ID_HASH_LENGTH = 8;
	private static final Duration LIST_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(10);

	/** The same world setting as the options screen; never overrides a locked or hardcore world. */
	public Map<String, Object> difficulty(String requestedName) {
		if (ai.moeru.airicraft.debug.ServerTickDebugRuntime.controller().status().paused())
			throw new SingleplayerWorldException("debug_busy", "Continue server ticks before querying or changing difficulty");
		Difficulty requested = requestedName == null ? null : switch (requestedName) {
			case "peaceful" -> Difficulty.PEACEFUL;
			case "easy" -> Difficulty.EASY;
			case "normal" -> Difficulty.NORMAL;
			case "hard" -> Difficulty.HARD;
			default -> throw new SingleplayerWorldException("invalid_request", "Difficulty must be peaceful, easy, normal, or hard");
		};
		Minecraft minecraft = requireClient();
		var server = runOnClientThread(minecraft, () -> {
			if (minecraft.level == null) throw new SingleplayerWorldException("world_not_loaded", "No world is loaded");
			if (minecraft.getSingleplayerServer() == null) throw new SingleplayerWorldException("singleplayer_required", "Difficulty control requires the local singleplayer server");
			return minecraft.getSingleplayerServer();
		});
		try {
			return server.submit(() -> {
				var worldData = server.getWorldData();
				Difficulty before = worldData.getDifficulty();
				if (requested != null && requested != before) {
					if (worldData.isDifficultyLocked() || worldData.isHardcore())
						throw new SingleplayerWorldException("difficulty_locked", "World difficulty is locked");
					server.setDifficulty(requested, false);
				}
				return Map.<String, Object>of("difficulty", worldData.getDifficulty().getKey(),
					"previousDifficulty", before.getKey(), "changed", before != worldData.getDifficulty(),
					"locked", worldData.isDifficultyLocked(), "hardcore", worldData.isHardcore(),
					"timeOfDay", server.overworld().getDayTime());
			}).get(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new SingleplayerWorldException("singleplayer_interrupted", "Difficulty request was interrupted", exception);
		}
		catch (ExecutionException exception) {
			if (exception.getCause() instanceof SingleplayerWorldException cause) throw cause;
			throw new SingleplayerWorldException("difficulty_failed", "Difficulty request failed", exception);
		}
		catch (java.util.concurrent.TimeoutException exception) {
			throw new SingleplayerWorldException("singleplayer_timeout", "Timed out waiting for the server difficulty request", exception);
		}
	}

	/** Uses the native generator and enters the world, as the Create World screen does. */
	public Map<String, Object> createWorld(String name, Long seed) {
		String displayName = validatedDisplayName(name);
		Minecraft client = requireClient();
		return runOnClientThread(client, () -> {
			requireSaveManagementScreen(client);
			LevelStorageSource storage = client.getLevelSource();
			String directory;
			try {
				directory = FileUtil.findAvailableName(storage.getBaseDir(), displayName, "");
				// Reserve atomically: never let the loader open an existing save on a name collision.
				Files.createDirectory(storage.getLevelPath(directory));
			}
			catch (IOException exception) {
				throw new SingleplayerWorldException("world_create_failed", "Could not reserve a new save folder", exception);
			}
			var configuration = WorldDataConfiguration.DEFAULT;
			var info = new LevelSettings(displayName, GameType.SURVIVAL, false,
				Difficulty.NORMAL, false, new GameRules(configuration.enabledFeatures()), configuration);
			var options = seed == null ? WorldOptions.defaultWithRandomSeed()
				: new WorldOptions(seed, true, false);
			client.createWorldOpenFlows().createFreshLevel(directory, info, options,
				lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET)
					.getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(),
				client.screen);
			// The native loader catches failures internally; do not claim it started on that path.
			if (client.getSingleplayerServer() == null) {
				throw new SingleplayerWorldException("world_create_failed", "World creation did not start a server; inspect the client logs and save folder: " + directory);
			}
			return Map.of("started", true, "worldId", worldId(directory), "name", directory,
				"displayName", displayName, "seed", options.seed());
		}, Duration.ofSeconds(120));
	}

	public Map<String, Object> renameWorld(String worldId, String name) {
		String displayName = validatedDisplayName(name);
		LevelSummary summary = requireSummary(worldId);
		Minecraft client = requireClient();
		return runOnClientThread(client, () -> {
			requireSaveManagementScreen(client);
			renameSave(client.getLevelSource(), summary.getLevelId(), displayName);
			return Map.of("renamed", true, "worldId", worldId(summary), "name", summary.getLevelId(), "displayName", displayName);
		}, Duration.ofSeconds(120));
	}

	public Map<String, Object> deleteWorld(String worldId) {
		LevelSummary summary = requireSummary(worldId);
		Minecraft client = requireClient();
		return runOnClientThread(client, () -> {
			requireSaveManagementScreen(client);
			deleteSave(client.getLevelSource(), summary.getLevelId());
			return Map.of("deleted", true, "worldId", worldId(summary), "name", summary.getLevelId());
		}, Duration.ofSeconds(120));
	}

	private LevelSummary requireSummary(String id) {
		LevelSummary summary = findSummaryByWorldId(id);
		if (summary == null) throw new SingleplayerWorldException("world_not_found", "World not found: " + id);
		return summary;
	}

	private static void requireSaveManagementScreen(Minecraft client) {
		if (isInWorld(client) || client.getSingleplayerServer() != null) {
			throw new SingleplayerWorldException("already_in_world", "Leave the current world before managing saves");
		}
		if (!(client.screen instanceof TitleScreen)
			&& !(client.screen instanceof SelectWorldScreen)) {
			throw new SingleplayerWorldException("world_management_busy", "Open the title or world selection screen before managing saves");
		}
	}

	private static String validatedDisplayName(String name) {
		if (name == null || name.isBlank() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl)) {
			throw new SingleplayerWorldException("invalid_request", "Save name must contain 1-255 characters and no control characters");
		}
		return name.trim();
	}

	static void renameSave(LevelStorageSource storage, String directory, String name) {
		String displayName = validatedDisplayName(name);
		mutateSave(storage, directory, session -> session.renameLevel(displayName));
	}

	static void deleteSave(LevelStorageSource storage, String directory) {
		mutateSave(storage, directory, LevelStorageSource.LevelStorageAccess::deleteLevel);
	}

	private static void mutateSave(LevelStorageSource storage, String directory, SaveMutation mutation) {
		Path path = storage.getLevelPath(directory);
		if (!path.normalize().getParent().equals(storage.getBaseDir().normalize())
			|| Files.isSymbolicLink(path)) {
			throw new SingleplayerWorldException("invalid_request", "Save must be a direct, non-symlink child of the saves folder");
		}
		if (!Files.isDirectory(path) || !Files.exists(path.resolve("level.dat"))) {
			throw new SingleplayerWorldException("world_not_found", "Save no longer exists: " + directory);
		}
		try (var session = storage.validateAndCreateAccess(directory)) {
			mutation.apply(session);
		}
		catch (DirectoryLock.LockException | OverlappingFileLockException exception) {
			throw new SingleplayerWorldException("world_locked", "Save is in use: " + directory, exception);
		}
		catch (ContentValidationException exception) {
			throw new SingleplayerWorldException("world_symlink_disallowed", "Save contains disallowed symbolic links: " + directory, exception);
		}
		catch (IOException exception) {
			throw new SingleplayerWorldException("world_save_failed", "Could not modify save: " + directory, exception);
		}
	}

	@FunctionalInterface
	private interface SaveMutation {
		void apply(LevelStorageSource.LevelStorageAccess session) throws IOException;
	}

	public List<Map<String, Object>> listWorlds() {
		Minecraft minecraft = requireClient();
		if (isInWorld(minecraft)) {
			throw new SingleplayerWorldException("already_in_world", "A world is already loaded");
		}

		try {
			List<LevelSummary> summaries = loadSummaries();
			List<Map<String, Object>> worlds = new ArrayList<>(summaries.size());
			for (LevelSummary summary : summaries) {
				worlds.add(worldPayload(summary));
			}
			return worlds;
		}
		catch (LevelStorageException exception) {
			throw new SingleplayerWorldException("singleplayer_list_failed", nonEmpty(exception.getMessage(), "Failed to list worlds"), exception);
		}
	}

	public Map<String, Object> joinWorld(String worldId) {
		Minecraft minecraft = requireClient();
		if (isInWorld(minecraft)) {
			throw new SingleplayerWorldException("already_in_world", "A world is already loaded");
		}

		LevelSummary summary = findSummaryByWorldId(worldId);
		return joinSummary(minecraft, summary, worldId);
	}

	public Map<String, Object> joinWorldDirectory(String directoryName) {
		Minecraft minecraft = requireClient();
		if (isInWorld(minecraft)) {
			throw new SingleplayerWorldException("already_in_world", "A world is already loaded");
		}

		LevelSummary summary = findSummaryByDirectoryName(directoryName);
		return joinSummary(minecraft, summary, directoryName);
	}

	private Map<String, Object> joinSummary(Minecraft minecraft, LevelSummary summary, String requestedId) {
		if (summary == null) {
			throw new SingleplayerWorldException("world_not_found", "World not found: " + requestedId);
		}
		if (!summary.primaryActionActive()) {
			throw new SingleplayerWorldException("world_not_selectable", "World is not selectable: " + summary.getLevelId());
		}

		runOnClientThread(minecraft, () -> {
			minecraft.createWorldOpenFlows().openWorld(summary.getLevelId(), () -> {
			});
			return null;
		});

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("started", true);
		payload.put("worldId", worldId(summary));
		payload.put("name", summary.getLevelId());
		payload.put("displayName", summary.getLevelName());
		return payload;
	}

	private LevelSummary findSummaryByWorldId(String worldId) {
		String normalized = normalizeWorldId(worldId);
		try {
			for (LevelSummary summary : loadSummaries()) {
				if (worldId(summary).equals(normalized)) {
					return summary;
				}
			}
			return null;
		}
		catch (LevelStorageException exception) {
			throw new SingleplayerWorldException("singleplayer_list_failed", nonEmpty(exception.getMessage(), "Failed to read world list"), exception);
		}
	}

	private LevelSummary findSummaryByDirectoryName(String directoryName) {
		String normalized = nonEmpty(directoryName, "").trim();
		if (normalized.isBlank()) {
			throw new SingleplayerWorldException("world_not_found", "World not found: " + directoryName);
		}
		try {
			for (LevelSummary summary : loadSummaries()) {
				if (summary.getLevelId().equals(normalized)) {
					return summary;
				}
			}
			return null;
		}
		catch (LevelStorageException exception) {
			throw new SingleplayerWorldException("singleplayer_list_failed", nonEmpty(exception.getMessage(), "Failed to read world list"), exception);
		}
	}

	private List<LevelSummary> loadSummaries() throws LevelStorageException {
		LevelStorageSource storage = requireClient().getLevelSource();
		CompletableFuture<List<LevelSummary>> future = storage.loadLevelSummaries(storage.findLevelCandidates());
		try {
			return future.get(LIST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new SingleplayerWorldException("singleplayer_interrupted", "Listing worlds was interrupted", exception);
		}
		catch (ExecutionException exception) {
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new SingleplayerWorldException("singleplayer_list_failed", nonEmpty(cause.getMessage(), "Failed to list worlds"), cause);
		}
		catch (java.util.concurrent.TimeoutException exception) {
			throw new SingleplayerWorldException("singleplayer_timeout", "Timed out while listing worlds", exception);
		}
	}

	private static Map<String, Object> worldPayload(LevelSummary summary) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("worldId", worldId(summary));
		payload.put("name", summary.getLevelId());
		payload.put("displayName", summary.getLevelName());
		payload.put("lastPlayed", summary.getLastPlayed());
		payload.put("gameMode", gameModeName(summary.getGameMode()));
		payload.put("selectable", summary.primaryActionActive());
		payload.put("immediatelyLoadable", summary.canUpload());
		payload.put("locked", summary.isLocked());
		payload.put("unavailable", summary.isDisabled());
		payload.put("experimental", summary.isExperimental());
		payload.put("details", summary.getInfo().getString());
		payload.put("version", summary.getWorldVersionName().getString());
		return payload;
	}

	private static String worldId(LevelSummary summary) {
		return worldId(summary.getLevelId());
	}

	private static String worldId(String name) {
		return name + "-" + shortHash(name);
	}

	private static String normalizeWorldId(String worldId) {
		String value = nonEmpty(worldId, "").trim();
		int split = value.lastIndexOf('-');
		if (split <= 0 || split >= value.length() - 1) {
			throw new SingleplayerWorldException("world_not_found", "World not found: " + worldId);
		}

		String name = value.substring(0, split);
		String hash = value.substring(split + 1);
		if (!shortHash(name).equals(hash)) {
			throw new SingleplayerWorldException("world_not_found", "World not found: " + worldId);
		}
		return value;
	}

	private static String shortHash(String value) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(WORLD_ID_HASH_LENGTH);
			for (byte current : hash) {
				if (builder.length() >= WORLD_ID_HASH_LENGTH) {
					break;
				}
				builder.append(Character.forDigit((current >> 4) & 0x0F, 16));
				builder.append(Character.forDigit(current & 0x0F, 16));
			}
			return builder.substring(0, WORLD_ID_HASH_LENGTH);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Missing SHA-256 implementation", exception);
		}
	}

	private static String gameModeName(GameType gameType) {
		return gameType == null ? "unknown" : gameType.getName();
	}

	private static Minecraft requireClient() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			throw new SingleplayerWorldException("minecraft_unavailable", "Minecraft client is not initialized");
		}
		return minecraft;
	}

	private static boolean isInWorld(Minecraft minecraft) {
		return minecraft.level != null || minecraft.player != null;
	}

	private static <T> T runOnClientThread(Minecraft client, java.util.function.Supplier<T> supplier) {
		return runOnClientThread(client, supplier, JOIN_TIMEOUT);
	}

	private static <T> T runOnClientThread(Minecraft minecraft, java.util.function.Supplier<T> supplier, Duration timeout) {
		CompletableFuture<T> future = new CompletableFuture<>();
		minecraft.execute(() -> {
			try {
				future.complete(supplier.get());
			}
			catch (Throwable throwable) {
				future.completeExceptionally(throwable);
			}
		});

		try {
			return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new SingleplayerWorldException("singleplayer_interrupted", "Joining world was interrupted", exception);
		}
		catch (ExecutionException exception) {
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			if (cause instanceof SingleplayerWorldException singleplayerWorldException) {
				throw singleplayerWorldException;
			}
			throw new SingleplayerWorldException("join_failed", nonEmpty(cause.getMessage(), "Failed to join world"), cause);
		}
		catch (java.util.concurrent.TimeoutException exception) {
			throw new SingleplayerWorldException("singleplayer_timeout", "Timed out while joining world", exception);
		}
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	public static final class SingleplayerWorldException extends RuntimeException {
		private final String code;

		SingleplayerWorldException(String code, String message) {
			super(message);
			this.code = code;
		}

		SingleplayerWorldException(String code, String message, Throwable cause) {
			super(message, cause);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}
}
