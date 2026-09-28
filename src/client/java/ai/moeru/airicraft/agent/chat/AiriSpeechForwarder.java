package ai.moeru.airicraft.agent.chat;

import ai.moeru.airicraft.Airicraft;
import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Optional copy of planner speech for AIRI's stage. */
public final class AiriSpeechForwarder implements AutoCloseable {
	private static final Gson JSON = new Gson();
	private final AiriSpeechConfig config;
	private final HttpClient client;
	private final ThreadPoolExecutor worker;

	public AiriSpeechForwarder(AiriSpeechConfig config) {
		this.config = config;
		client = config.enabled() ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build() : null;
		worker = config.enabled() ? new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().name("airicraft-airi-speech").factory()) : null;
	}

	public Future<?> say(String text) {
		if (!config.enabled() || text == null || text.isBlank()) return CompletableFuture.completedFuture(null);
		try {
			return worker.submit(() -> {
				try {
					deliver(text);
					return null;
				} catch (Exception exception) {
					if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
					// Do not log remote payloads, URLs or credentials.
					Airicraft.LOGGER.warn("AIRI speech copy failed ({}); Minecraft chat was already sent", exception.getClass().getSimpleName());
					throw exception;
				}
			});
		} catch (RejectedExecutionException exception) {
			Airicraft.LOGGER.warn("AIRI speech copy dropped: sender is busy or closed");
			return CompletableFuture.failedFuture(exception);
		}
	}

	private void deliver(String text) throws Exception {
		var listener = new AuthenticationListener();
		WebSocket socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(2))
			.buildAsync(URI.create(config.websocketUrl()), listener).get(3, TimeUnit.SECONDS);
		try {
			socket.sendText(event("module:authenticate", Map.of("token", config.token())), true).get(2, TimeUnit.SECONDS);
			listener.authenticated.get(2, TimeUnit.SECONDS);
			socket.sendText(event("output:speech", Map.of("text", text)), true).get(2, TimeUnit.SECONDS);
			socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS);
		} finally {
			socket.abort();
		}
	}

	private static String event(String type, Map<String, String> data) {
		return JSON.toJson(Map.of("type", type, "data", data, "metadata", Map.of(
			"source", Map.of("kind", "plugin", "id", "airicraft", "plugin", Map.of("id", "airicraft")),
			"event", Map.of("id", UUID.randomUUID().toString()))));
	}

	@Override public void close() {
		if (worker == null) return;
		for (Runnable pending : worker.shutdownNow()) {
			if (pending instanceof Future<?> future) future.cancel(false);
		}
		client.shutdownNow();
	}

	private static final class AuthenticationListener implements WebSocket.Listener {
		private final CompletableFuture<Void> authenticated = new CompletableFuture<>();
		private final StringBuilder message = new StringBuilder();

		@Override public void onOpen(WebSocket socket) { socket.request(1); }

		@Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
			message.append(text);
			if (last) {
				try {
					var event = JsonParser.parseString(message.toString()).getAsJsonObject();
					// AIRI's server-sdk uses SuperJSON; these replies contain only JSON values.
					if (event.has("json")) event = event.getAsJsonObject("json");
					String type = event.get("type").getAsString();
					if ("module:authenticated".equals(type) && event.getAsJsonObject("data").get("authenticated").getAsBoolean()) {
						authenticated.complete(null);
					} else if ("module:authenticated".equals(type) || "error".equals(type)) {
						authenticated.completeExceptionally(new IllegalStateException("AIRI rejected authentication"));
					}
				} catch (RuntimeException exception) {
					authenticated.completeExceptionally(new IllegalStateException("Invalid AIRI authentication response"));
				}
				message.setLength(0);
			}
			socket.request(1);
			return null;
		}

		@Override public void onError(WebSocket socket, Throwable error) { authenticated.completeExceptionally(error); }
		@Override public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
			authenticated.completeExceptionally(new IllegalStateException("AIRI closed before authentication"));
			return null;
		}
	}
}
