package ai.moeru.airicraft;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelStorageException;
import net.minecraft.world.level.storage.LevelSummary;

import java.nio.charset.StandardCharsets;
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

	private static <T> T runOnClientThread(Minecraft minecraft, java.util.function.Supplier<T> supplier) {
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
			return future.get(JOIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
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
