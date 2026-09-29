package ai.moeru.airicraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class SavedServerService {
	private static final int SERVER_ID_HASH_LENGTH = 8;
	private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(10);

	public List<Map<String, Object>> listServers() {
		Minecraft minecraft = requireClient();
		ServerList serverList = loadServerList(minecraft);
		List<Map<String, Object>> servers = new ArrayList<>(serverList.size());
		for (int index = 0; index < serverList.size(); index++) {
			ServerData serverData = serverList.get(index);
			servers.add(serverPayload(serverData, index));
		}
		return servers;
	}

	public Map<String, Object> joinServer(String serverId) {
		Minecraft minecraft = requireClient();
		if (isInWorld(minecraft)) {
			throw new SavedServerServiceException("already_in_world", "A world is already loaded");
		}

		ServerEntry entry = findServer(normalizeServerId(serverId));
		if (entry == null) {
			throw new SavedServerServiceException("server_not_found", "Server not found: " + serverId);
		}

		runOnClientThread(minecraft, () -> {
			ConnectScreen.startConnecting(
				minecraft.screen != null ? minecraft.screen : new TitleScreen(),
				minecraft,
				ServerAddress.parseString(entry.serverInfo.ip),
				entry.serverInfo,
				false,
				new TransferState(Map.of())
			);
			return null;
		});

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("started", true);
		payload.put("serverId", serverId(entry.serverInfo));
		payload.put("name", entry.serverInfo.name);
		payload.put("address", entry.serverInfo.ip);
		payload.put("serverType", serverTypeName(entry.serverInfo));
		return payload;
	}

	private ServerEntry findServer(String serverId) {
		ServerList serverList = loadServerList(requireClient());
		for (int index = 0; index < serverList.size(); index++) {
			ServerData serverData = serverList.get(index);
			if (serverId(serverData).equals(serverId)) {
				return new ServerEntry(index, serverData);
			}
		}
		return null;
	}

	private static ServerList loadServerList(Minecraft minecraft) {
		ServerList serverList = new ServerList(minecraft);
		serverList.load();
		return serverList;
	}

	private static Map<String, Object> serverPayload(ServerData serverData, int index) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("serverId", serverId(serverData));
		payload.put("name", nonEmpty(serverData.name, "Unnamed Server"));
		payload.put("address", nonEmpty(serverData.ip, ""));
		payload.put("index", index);
		payload.put("serverType", serverTypeName(serverData));
		payload.put("local", serverData.isLan());
		payload.put("realm", serverData.isRealm());
		payload.put("resourcePackPolicy", resourcePackPolicy(serverData));
		return payload;
	}

	private static String serverId(ServerData serverData) {
		String key = normalizeAddress(serverData.ip) + "|" + serverTypeName(serverData);
		return shortHash(key);
	}

	private static String normalizeServerId(String serverId) {
		String value = nonEmpty(serverId, "").trim();
		if (value.isEmpty()) {
			throw new SavedServerServiceException("server_not_found", "Server not found: " + serverId);
		}
		return value;
	}

	private static String serverTypeName(ServerData serverData) {
		return String.valueOf(serverData.type()).toLowerCase(Locale.ROOT);
	}

	private static String resourcePackPolicy(ServerData serverData) {
		return String.valueOf(serverData.getResourcePackStatus()).toLowerCase(Locale.ROOT);
	}

	private static String normalizeAddress(String address) {
		return nonEmpty(address, "").trim().toLowerCase(Locale.ROOT);
	}

	private static String shortHash(String value) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(SERVER_ID_HASH_LENGTH);
			for (byte current : hash) {
				if (builder.length() >= SERVER_ID_HASH_LENGTH) {
					break;
				}
				builder.append(Character.forDigit((current >> 4) & 0x0F, 16));
				builder.append(Character.forDigit(current & 0x0F, 16));
			}
			return builder.substring(0, SERVER_ID_HASH_LENGTH);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Missing SHA-256 implementation", exception);
		}
	}

	private static Minecraft requireClient() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			throw new SavedServerServiceException("minecraft_unavailable", "Minecraft client is not initialized");
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
			throw new SavedServerServiceException("saved_server_interrupted", "Joining server was interrupted", exception);
		}
		catch (ExecutionException exception) {
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			if (cause instanceof SavedServerServiceException savedServerServiceException) {
				throw savedServerServiceException;
			}
			throw new SavedServerServiceException("join_failed", nonEmpty(cause.getMessage(), "Failed to join server"), cause);
		}
		catch (java.util.concurrent.TimeoutException exception) {
			throw new SavedServerServiceException("saved_server_timeout", "Timed out while joining server", exception);
		}
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private record ServerEntry(int index, ServerData serverInfo) {
	}

	public static final class SavedServerServiceException extends RuntimeException {
		private final String code;

		SavedServerServiceException(String code, String message) {
			super(message);
			this.code = code;
		}

		SavedServerServiceException(String code, String message, Throwable cause) {
			super(message, cause);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}
}
