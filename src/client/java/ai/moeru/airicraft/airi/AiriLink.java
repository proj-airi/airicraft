package ai.moeru.airicraft.airi;

import ai.moeru.airicraft.Airicraft;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The mod's client for AIRI's channel server. It joins AIRI as the module {@value #MODULE_NAME}: it authenticates,
 * announces itself, keeps the connection alive with heartbeats and reconnects with backoff.
 *
 * <p>All state changes run on the {@link AiriLinkScheduler}. Event listeners also run there, so a listener that touches
 * the game must move its work to the client thread.
 */
public final class AiriLink implements AutoCloseable {
	public static final String MODULE_NAME = "airicraft";
	static final List<String> POSSIBLE_EVENTS = List.of("module:configure", "spark:command");
	static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);
	static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(20);
	static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
	static final Duration FIRST_RECONNECT_DELAY = Duration.ofSeconds(1);
	static final Duration MAX_RECONNECT_DELAY = Duration.ofSeconds(30);
	private static final String HEARTBEAT_PING = "🩵";
	private static final String HEARTBEAT_PONG = "💛";

	public enum State {
		DISABLED,
		MISSING_TOKEN,
		CONNECTING,
		AUTHENTICATING,
		ANNOUNCING,
		READY,
		WAITING_TO_RECONNECT,
		STOPPED;

		public String wireValue() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private final AiriTransport transport;
	private final AiriLinkScheduler scheduler;
	private final AiriModuleIdentity identity;
	private final Supplier<String> eventIds;
	private final Map<String, List<Consumer<AiriEvent>>> listeners = new ConcurrentHashMap<>();

	private volatile State state = State.DISABLED;
	private volatile AiriLinkConfig config = AiriLinkConfig.defaults();
	private volatile String lastError = "";
	private AiriTransport.Connection connection;
	private int generation;
	private int reconnectAttempts;
	private long lastReceivedAtMillis;
	private AiriLinkScheduler.Cancellable handshakeTimeout;
	private AiriLinkScheduler.Cancellable heartbeat;
	private AiriLinkScheduler.Cancellable reconnect;

	public AiriLink(AiriTransport transport, AiriLinkScheduler scheduler, AiriModuleIdentity identity, Supplier<String> eventIds) {
		this.transport = transport;
		this.scheduler = scheduler;
		this.identity = identity;
		this.eventIds = eventIds;
	}

	public static AiriLink create() {
		String instanceId = MODULE_NAME + "-" + UUID.randomUUID();
		return new AiriLink(
			new JdkAiriTransport(),
			AiriLinkScheduler.singleThread(),
			new AiriModuleIdentity(MODULE_NAME, instanceId, MODULE_NAME),
			() -> UUID.randomUUID().toString()
		);
	}

	/** Applies new settings. Settings equal to the current ones keep the current connection. */
	public void configure(AiriLinkConfig nextConfig) {
		scheduler.execute(() -> apply(nextConfig));
	}

	/** Registers a listener for one event type. It receives events only while the link is ready. */
	public void onEvent(String type, Consumer<AiriEvent> listener) {
		listeners.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>()).add(listener);
	}

	/** Queues one event for AIRI. Returns false and drops the event when the link is not ready. */
	public boolean send(String type, JsonObject data) {
		if (state != State.READY) {
			return false;
		}
		scheduler.execute(() -> {
			if (state == State.READY) {
				sendNow(type, data);
			}
		});
		return true;
	}

	public State state() {
		return state;
	}

	public AiriLinkStatus status() {
		return new AiriLinkStatus(state, config.url(), lastError);
	}

	@Override
	public void close() {
		scheduler.execute(() -> {
			disconnect();
			state = State.STOPPED;
		});
		scheduler.close();
	}

	private void apply(AiriLinkConfig nextConfig) {
		boolean unchanged = nextConfig.equals(config) && state != State.DISABLED && state != State.STOPPED;
		if (unchanged) {
			return;
		}
		disconnect();
		config = nextConfig;
		lastError = "";
		reconnectAttempts = 0;
		if (!nextConfig.enabled()) {
			state = State.DISABLED;
			return;
		}
		if (!nextConfig.hasToken()) {
			state = State.MISSING_TOKEN;
			lastError = "set airi.token to AIRI's channel server token";
			Airicraft.LOGGER.warn("AIRI link is enabled without a token, so it does not connect");
			return;
		}
		connect();
	}

	private void connect() {
		int connectGeneration = ++generation;
		state = State.CONNECTING;
		AiriTransport.Listener listener = new AiriTransport.Listener() {
			@Override
			public void onText(String text) {
				scheduler.execute(() -> handleText(connectGeneration, text));
			}

			@Override
			public void onClosed(String reason) {
				scheduler.execute(() -> fail(connectGeneration, reason));
			}

			@Override
			public void onError(Throwable error) {
				scheduler.execute(() -> fail(connectGeneration, describe(error)));
			}
		};
		CompletableFuture<AiriTransport.Connection> opening;
		try {
			opening = transport.connect(URI.create(config.url()), listener);
		}
		catch (RuntimeException exception) {
			fail(connectGeneration, describe(exception));
			return;
		}
		opening.whenComplete((opened, error) -> scheduler.execute(() -> {
			if (error != null) {
				fail(connectGeneration, describe(error));
				return;
			}
			if (connectGeneration != generation || state != State.CONNECTING) {
				opened.close();
				return;
			}
			connection = opened;
			lastReceivedAtMillis = scheduler.nowMillis();
			handshakeTimeout = scheduler.schedule(() -> {
				if (connectGeneration == generation && state != State.READY) {
					fail(connectGeneration, "AIRI did not finish the handshake");
				}
			}, HANDSHAKE_TIMEOUT);
			state = State.AUTHENTICATING;
			JsonObject data = new JsonObject();
			data.addProperty("token", config.token());
			sendNow("module:authenticate", data);
		}));
	}

	private void handleText(int textGeneration, String text) {
		if (textGeneration != generation || connection == null) {
			return;
		}
		lastReceivedAtMillis = scheduler.nowMillis();
		if ("ping".equals(text)) {
			connection.send("pong");
			return;
		}
		if ("pong".equals(text)) {
			return;
		}
		AiriEvent event;
		try {
			event = AiriWireCodec.decode(text);
		}
		catch (IllegalArgumentException exception) {
			Airicraft.LOGGER.debug("Ignoring an AIRI message that is not an event: {}", exception.getMessage());
			return;
		}
		switch (event.type()) {
			case "error" -> {
				String message = stringField(event.data(), "message", "AIRI reported an error");
				if (state == State.READY) {
					lastError = message;
					Airicraft.LOGGER.warn("AIRI reported an error: {}", message);
				}
				else {
					fail(textGeneration, message);
				}
			}
			case "transport:connection:heartbeat" -> {
				if ("ping".equals(stringField(event.data(), "kind", ""))) {
					sendHeartbeat("pong", HEARTBEAT_PONG);
				}
			}
			case "module:authenticated" -> {
				if (state != State.AUTHENTICATING) {
					return;
				}
				if (event.data().has("authenticated") && event.data().get("authenticated").getAsBoolean()) {
					announce();
				}
				else {
					fail(textGeneration, "AIRI rejected the token");
				}
			}
			case "extension:module:announced" -> {
				if (state == State.ANNOUNCING && isSelf(event.data())) {
					becomeReady();
				}
			}
			case "registry:modules:sync" -> {
				if (state == State.ANNOUNCING && registryHasSelf(event.data())) {
					becomeReady();
				}
			}
			default -> {
			}
		}
		if (state == State.READY) {
			dispatch(event);
		}
	}

	private void announce() {
		state = State.ANNOUNCING;
		JsonArray possibleEvents = new JsonArray();
		POSSIBLE_EVENTS.forEach(possibleEvents::add);
		JsonObject data = new JsonObject();
		data.addProperty("name", identity.name());
		data.add("identity", identity.toJson());
		data.add("possibleEvents", possibleEvents);
		data.add("dependencies", new JsonArray());
		sendNow("extension:module:announce", data);
	}

	private void becomeReady() {
		cancel(handshakeTimeout);
		handshakeTimeout = null;
		state = State.READY;
		lastError = "";
		reconnectAttempts = 0;
		Airicraft.LOGGER.info("AIRI link is ready at {}", config.url());
		int readyGeneration = generation;
		heartbeat = scheduler.schedule(() -> heartbeatTick(readyGeneration), HEARTBEAT_INTERVAL);
	}

	private void heartbeatTick(int heartbeatGeneration) {
		if (heartbeatGeneration != generation || state != State.READY) {
			return;
		}
		if (scheduler.nowMillis() - lastReceivedAtMillis > READ_TIMEOUT.toMillis()) {
			fail(heartbeatGeneration, "AIRI stopped answering heartbeats");
			return;
		}
		sendHeartbeat("ping", HEARTBEAT_PING);
		heartbeat = scheduler.schedule(() -> heartbeatTick(heartbeatGeneration), HEARTBEAT_INTERVAL);
	}

	private void sendHeartbeat(String kind, String message) {
		JsonObject data = new JsonObject();
		data.addProperty("kind", kind);
		data.addProperty("message", message);
		data.addProperty("at", scheduler.nowMillis());
		sendNow("transport:connection:heartbeat", data);
	}

	private void dispatch(AiriEvent event) {
		for (Consumer<AiriEvent> listener : listeners.getOrDefault(event.type(), List.of())) {
			try {
				listener.accept(event);
			}
			catch (RuntimeException exception) {
				Airicraft.LOGGER.warn("AIRI event listener failed for {}", event.type(), exception);
			}
		}
	}

	private void fail(int failedGeneration, String reason) {
		if (failedGeneration != generation || state == State.DISABLED || state == State.STOPPED
			|| state == State.MISSING_TOKEN || state == State.WAITING_TO_RECONNECT) {
			return;
		}
		lastError = reason;
		if (state == State.READY) {
			Airicraft.LOGGER.warn("AIRI link lost: {}", reason);
		}
		else {
			Airicraft.LOGGER.debug("AIRI link attempt failed: {}", reason);
		}
		disconnect();
		state = State.WAITING_TO_RECONNECT;
		long delayMillis = Math.min(
			MAX_RECONNECT_DELAY.toMillis(),
			FIRST_RECONNECT_DELAY.toMillis() << Math.min(reconnectAttempts, 5)
		);
		reconnectAttempts++;
		int reconnectGeneration = generation;
		reconnect = scheduler.schedule(() -> {
			if (reconnectGeneration == generation && state == State.WAITING_TO_RECONNECT) {
				connect();
			}
		}, Duration.ofMillis(delayMillis));
	}

	private void disconnect() {
		generation++;
		cancel(handshakeTimeout);
		cancel(heartbeat);
		cancel(reconnect);
		handshakeTimeout = null;
		heartbeat = null;
		reconnect = null;
		if (connection != null) {
			connection.close();
			connection = null;
		}
	}

	private void sendNow(String type, JsonObject data) {
		if (connection != null) {
			connection.send(AiriWireCodec.encode(type, data, identity, eventIds.get()));
		}
	}

	private boolean isSelf(JsonObject data) {
		return identity.name().equals(stringField(data, "name", "")) && identity.matches(objectField(data, "identity"));
	}

	private boolean registryHasSelf(JsonObject data) {
		if (!data.has("modules") || !data.get("modules").isJsonArray()) {
			return false;
		}
		for (JsonElement module : data.getAsJsonArray("modules")) {
			if (module.isJsonObject() && isSelf(module.getAsJsonObject())) {
				return true;
			}
		}
		return false;
	}

	private static JsonObject objectField(JsonObject data, String name) {
		return data.has(name) && data.get(name).isJsonObject() ? data.getAsJsonObject(name) : null;
	}

	private static String stringField(JsonObject data, String name, String fallback) {
		if (!data.has(name) || !data.get(name).isJsonPrimitive()) {
			return fallback;
		}
		return data.get(name).getAsString();
	}

	private static void cancel(AiriLinkScheduler.Cancellable cancellable) {
		if (cancellable != null) {
			cancellable.cancel();
		}
	}

	private static String describe(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}
}
