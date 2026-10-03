package ai.moeru.airicraft.airi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiriLinkTest {
	private static final AiriLinkConfig ENABLED = new AiriLinkConfig(true, "ws://127.0.0.1:6121/ws", "secret");

	private final ManualScheduler scheduler = new ManualScheduler();
	private final FakeTransport transport = new FakeTransport();
	private final AtomicInteger eventIds = new AtomicInteger();
	private final AiriLink link = new AiriLink(
		transport,
		scheduler,
		new AiriModuleIdentity("airicraft", "airicraft-1", "airicraft"),
		() -> "event-" + eventIds.incrementAndGet()
	);

	@Test
	void staysDisabledByDefault() {
		link.configure(AiriLinkConfig.defaults());

		assertEquals(AiriLink.State.DISABLED, link.state());
		assertEquals(0, transport.connects.size());
	}

	@Test
	void doesNotConnectWithoutAToken() {
		link.configure(new AiriLinkConfig(true, "ws://127.0.0.1:6121/ws", " "));

		assertEquals(AiriLink.State.MISSING_TOKEN, link.state());
		assertEquals(0, transport.connects.size());
		assertFalse(link.status().lastError().isEmpty());
	}

	@Test
	void authenticatesThenAnnouncesThenBecomesReady() {
		FakeConnection connection = connectAndOpen();

		JsonObject authenticate = connection.sentEvent(0);
		assertEquals("module:authenticate", authenticate.get("type").getAsString());
		assertEquals("secret", authenticate.getAsJsonObject("data").get("token").getAsString());
		assertEquals(AiriLink.State.AUTHENTICATING, link.state());

		connection.receive(serverEvent("module:authenticated", "{\"authenticated\":true}"));

		JsonObject announce = connection.sentEvent(1);
		assertEquals("extension:module:announce", announce.get("type").getAsString());
		JsonObject data = announce.getAsJsonObject("data");
		assertEquals("airicraft", data.get("name").getAsString());
		assertEquals("airicraft-1", data.getAsJsonObject("identity").get("id").getAsString());
		assertEquals("airicraft", data.getAsJsonObject("identity").getAsJsonObject("extension").get("id").getAsString());
		assertEquals(AiriLink.State.ANNOUNCING, link.state());

		connection.receive(serverEvent("extension:module:announced", "{\"name\":\"airicraft\",\"identity\":{\"id\":\"another\"}}"));
		assertEquals(AiriLink.State.ANNOUNCING, link.state(), "Another module's announcement does not finish the handshake");

		connection.receive(serverEvent("extension:module:announced", "{\"name\":\"airicraft\",\"identity\":{\"id\":\"airicraft-1\"}}"));
		assertEquals(AiriLink.State.READY, link.state());
	}

	@Test
	void aRegistrySyncThatListsThisModuleAlsoFinishesTheHandshake() {
		FakeConnection connection = connectAndOpen();
		connection.receive(serverEvent("module:authenticated", "{\"authenticated\":true}"));

		connection.receive(serverEvent("registry:modules:sync",
			"{\"modules\":[{\"name\":\"airicraft\",\"identity\":{\"id\":\"airicraft-1\"}}]}"));

		assertEquals(AiriLink.State.READY, link.state());
	}

	@Test
	void anErrorDuringTheHandshakeWaitsAndReconnectsWithBackoff() {
		FakeConnection first = connectAndOpen();
		first.receive(serverEvent("error", "{\"message\":\"invalid token\"}"));

		assertEquals(AiriLink.State.WAITING_TO_RECONNECT, link.state());
		assertEquals("invalid token", link.status().lastError());
		assertTrue(first.closed);
		assertEquals(Duration.ofSeconds(1), scheduler.lastDelay());

		scheduler.runLast();
		assertEquals(2, transport.connects.size());
		transport.fail(new IllegalStateException("connection refused"));

		assertEquals(AiriLink.State.WAITING_TO_RECONNECT, link.state());
		assertEquals("connection refused", link.status().lastError());
		assertEquals(Duration.ofSeconds(2), scheduler.lastDelay());
	}

	@Test
	void aLostConnectionReconnectsAndReadyResetsTheBackoff() {
		FakeConnection first = ready();

		first.listener.onClosed("closed by server (1001)");
		assertEquals(AiriLink.State.WAITING_TO_RECONNECT, link.state());
		assertEquals(Duration.ofSeconds(1), scheduler.lastDelay());

		scheduler.runLast();
		FakeConnection second = transport.open();
		second.receive(serverEvent("module:authenticated", "{\"authenticated\":true}"));
		second.receive(serverEvent("extension:module:announced", "{\"name\":\"airicraft\",\"identity\":{\"id\":\"airicraft-1\"}}"));
		assertEquals(AiriLink.State.READY, link.state());

		second.listener.onClosed("closed by server (1001)");
		assertEquals(Duration.ofSeconds(1), scheduler.lastDelay());
	}

	@Test
	void answersHeartbeatPingsAndSendsItsOwn() {
		FakeConnection connection = ready();
		int sentBefore = connection.sent.size();

		connection.receive(serverEvent("transport:connection:heartbeat", "{\"kind\":\"ping\",\"message\":\"x\"}"));
		JsonObject pong = connection.sentEvent(sentBefore);
		assertEquals("transport:connection:heartbeat", pong.get("type").getAsString());
		assertEquals("pong", pong.getAsJsonObject("data").get("kind").getAsString());

		connection.receive("ping");
		assertEquals("pong", connection.sent.getLast());

		scheduler.advance(AiriLink.HEARTBEAT_INTERVAL);
		scheduler.runLast();
		JsonObject ping = connection.sentEvent(connection.sent.size() - 1);
		assertEquals("ping", ping.getAsJsonObject("data").get("kind").getAsString());
	}

	@Test
	void dropsTheConnectionWhenAiriStopsAnswering() {
		ready();

		scheduler.advance(AiriLink.READ_TIMEOUT.plusSeconds(1));
		scheduler.runLast();

		assertEquals(AiriLink.State.WAITING_TO_RECONNECT, link.state());
		assertEquals("AIRI stopped answering heartbeats", link.status().lastError());
	}

	@Test
	void deliversEventsAndSendsOnlyWhileReady() {
		List<String> intents = new ArrayList<>();
		link.onEvent("spark:command", event -> intents.add(event.data().get("intent").getAsString()));
		assertFalse(link.send("context:update", new JsonObject()));

		FakeConnection connection = ready();
		connection.receive(serverEvent("spark:command", "{\"intent\":\"action\"}"));
		assertEquals(List.of("action"), intents);

		int sentBefore = connection.sent.size();
		JsonObject data = new JsonObject();
		data.addProperty("text", "hello");
		assertTrue(link.send("context:update", data));
		assertEquals("context:update", connection.sentEvent(sentBefore).get("type").getAsString());
	}

	@Test
	void theSameSettingsKeepTheConnectionAndNewSettingsReplaceIt() {
		FakeConnection connection = ready();

		link.configure(ENABLED);
		assertEquals(1, transport.connects.size());
		assertFalse(connection.closed);

		link.configure(AiriLinkConfig.defaults());
		assertTrue(connection.closed);
		assertEquals(AiriLink.State.DISABLED, link.state());
	}

	@Test
	void closeStopsTheLink() {
		FakeConnection connection = ready();

		link.close();

		assertTrue(connection.closed);
		assertEquals(AiriLink.State.STOPPED, link.state());
	}

	private FakeConnection ready() {
		FakeConnection connection = connectAndOpen();
		connection.receive(serverEvent("module:authenticated", "{\"authenticated\":true}"));
		connection.receive(serverEvent("extension:module:announced", "{\"name\":\"airicraft\",\"identity\":{\"id\":\"airicraft-1\"}}"));
		assertEquals(AiriLink.State.READY, link.state());
		return connection;
	}

	private FakeConnection connectAndOpen() {
		link.configure(ENABLED);
		assertEquals(AiriLink.State.CONNECTING, link.state());
		assertEquals(URI.create(ENABLED.url()), transport.connects.getLast());
		return transport.open();
	}

	private static String serverEvent(String type, String data) {
		return "{\"json\":{\"type\":\"" + type + "\",\"data\":" + data + "}}";
	}

	private static final class ManualScheduler implements AiriLinkScheduler {
		private final List<Runnable> tasks = new ArrayList<>();
		private final List<Duration> delays = new ArrayList<>();
		private long now;

		@Override
		public void execute(Runnable task) {
			task.run();
		}

		@Override
		public Cancellable schedule(Runnable task, Duration delay) {
			tasks.add(task);
			delays.add(delay);
			return () -> {
				int index = tasks.indexOf(task);
				if (index >= 0) {
					tasks.set(index, () -> { });
				}
			};
		}

		@Override
		public long nowMillis() {
			return now;
		}

		void advance(Duration duration) {
			now += duration.toMillis();
		}

		Duration lastDelay() {
			return delays.getLast();
		}

		void runLast() {
			tasks.getLast().run();
		}
	}

	private static final class FakeTransport implements AiriTransport {
		private final List<URI> connects = new ArrayList<>();
		private CompletableFuture<Connection> pending;
		private Listener listener;

		@Override
		public CompletableFuture<Connection> connect(URI uri, Listener listener) {
			connects.add(uri);
			this.listener = listener;
			pending = new CompletableFuture<>();
			return pending;
		}

		FakeConnection open() {
			FakeConnection connection = new FakeConnection(listener);
			pending.complete(connection);
			return connection;
		}

		void fail(Throwable error) {
			pending.completeExceptionally(error);
		}
	}

	private static final class FakeConnection implements AiriTransport.Connection {
		private final AiriTransport.Listener listener;
		private final List<String> sent = new ArrayList<>();
		private boolean closed;

		private FakeConnection(AiriTransport.Listener listener) {
			this.listener = listener;
		}

		@Override
		public void send(String text) {
			sent.add(text);
		}

		@Override
		public void close() {
			closed = true;
		}

		void receive(String text) {
			listener.onText(text);
		}

		JsonObject sentEvent(int index) {
			return JsonParser.parseString(sent.get(index)).getAsJsonObject();
		}
	}
}
