package ai.moeru.airicraft.agent.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AiriSpeechForwarderTest {

	@Test
	void authenticatesThenSendsLiteralSpeechInOrder() throws Exception {
		try (var server = new SpeechServer(true);
			var speech = new AiriSpeechForwarder(new AiriSpeechConfig(true, server.url(), "test-token"))) {
			var firstDelivery = speech.say("Found 16 logs");
			var secondDelivery = speech.say("Coming home");
			firstDelivery.get(5, TimeUnit.SECONDS);
			secondDelivery.get(5, TimeUnit.SECONDS);
			JsonObject authentication = server.auth.poll(2, TimeUnit.SECONDS);
			assertNotNull(authentication);
			assertEquals("test-token", authentication.getAsJsonObject("data").get("token").getAsString());
			JsonObject first = server.speech.poll(2, TimeUnit.SECONDS);
			assertNotNull(first);
			assertEquals("output:speech", first.get("type").getAsString());
			assertEquals("Found 16 logs", first.getAsJsonObject("data").get("text").getAsString());
			assertEquals("Coming home", server.speech.poll(2, TimeUnit.SECONDS).getAsJsonObject("data").get("text").getAsString());
			assertTrue(server.speech.isEmpty());
		}
	}

	@Test
	void disabledForwardingDoesNotConnect() throws Exception {
		try (var server = new SpeechServer(true);
			var speech = new AiriSpeechForwarder(new AiriSpeechConfig(false, server.url(), ""))) {
			speech.say("hello").get(1, TimeUnit.SECONDS);
			assertTrue(server.auth.isEmpty());
			assertTrue(server.speech.isEmpty());
		}
	}

	@Test
	void rejectedAuthenticationDoesNotSendSpeechOrThrowOnTheCaller() throws Exception {
		try (var server = new SpeechServer(false);
			var speech = new AiriSpeechForwarder(new AiriSpeechConfig(true, server.url(), "wrong-token"))) {
			var delivery = assertDoesNotThrow(() -> speech.say("hello"));
			assertThrows(java.util.concurrent.ExecutionException.class, () -> delivery.get(5, TimeUnit.SECONDS));
			assertTrue(server.speech.isEmpty());
		}
	}

	private static final class SpeechServer implements AutoCloseable {
		private final ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
		private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
		final LinkedBlockingQueue<JsonObject> auth = new LinkedBlockingQueue<>();
		final LinkedBlockingQueue<JsonObject> speech = new LinkedBlockingQueue<>();

		SpeechServer(boolean accept) throws java.io.IOException {
			workers.submit(() -> {
				while (!server.isClosed()) {
					Socket socket = server.accept();
					workers.submit(() -> { handle(socket, accept); return null; });
				}
				return null;
			});
		}

		private void handle(Socket socket, boolean accept) throws Exception {
			try (socket) {
				socket.setSoTimeout(4000);
				var input = socket.getInputStream();
				var output = socket.getOutputStream();
				var header = new StringBuilder();
				while (!header.toString().endsWith("\r\n\r\n")) {
					int value = input.read();
					if (value < 0) return;
					header.append((char) value);
				}
				String key = header.toString().lines().filter(line -> line.toLowerCase().startsWith("sec-websocket-key:"))
					.findFirst().orElseThrow().split(":", 2)[1].trim();
				String responseKey = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
					.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
				output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
					+ responseKey + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
				output.flush();
				while (true) {
					int opcode = input.read();
					if (opcode < 0) return;
					int length = input.read() & 127;
					if (length == 126) length = (input.read() << 8) | input.read();
					if (length == 127) throw new IllegalStateException("Test frame is too large");
					byte[] mask = input.readNBytes(4);
					byte[] body = input.readNBytes(length);
					for (int i = 0; i < body.length; i++) body[i] ^= mask[i % 4];
					if ((opcode & 15) == 8) {
						output.write(new byte[] { (byte) 136, 0 });
						output.flush();
						return;
					}
					JsonObject event = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
					if (event.get("type").getAsString().equals("module:authenticate")) {
						auth.add(event);
						byte[] response = ("{\"json\":" + (accept
							? "{\"type\":\"module:authenticated\",\"data\":{\"authenticated\":true}}"
							: "{\"type\":\"error\",\"data\":{\"message\":\"Invalid token\"}}")
							+ "}").getBytes(StandardCharsets.UTF_8);
						output.write(129);
						output.write(response.length);
						output.write(response);
						output.flush();
					} else {
						speech.add(event);
					}
				}
			}
		}

		String url() { return "ws://127.0.0.1:" + server.getLocalPort() + "/ws"; }

		@Override public void close() throws java.io.IOException {
			server.close();
			workers.shutdownNow();
		}
	}
}
