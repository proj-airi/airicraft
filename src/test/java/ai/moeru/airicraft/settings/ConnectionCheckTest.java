package ai.moeru.airicraft.settings;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionCheckTest {
	@Test
	void verifiesSelectedModelWithInferenceAndKeepsCredentialsOutOfResults() throws Exception {
		var request = new AtomicReference<String>();
		var authorization = new AtomicReference<String>();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", exchange -> {
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] body = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			var result = ConnectionCheck.api(url(server), "test-secret", "chosen-model", false, 2000);
			assertEquals(ConnectionCheck.Result.READY, result);
			assertEquals("chosen-model", JsonParser.parseString(request.get()).getAsJsonObject().get("model").getAsString());
			assertEquals("Bearer test-secret", authorization.get());
			assertFalse(result.message().contains("test-secret"));
			assertEquals(ConnectionCheck.Result.READY, ConnectionCheck.api(url(server), "test-secret", "vision-model", true, 2000));
			assertTrue(request.get().contains("data:image/png;base64,"));
		} finally { server.stop(0); }
	}

	@Test
	void distinguishesAuthModelEndpointAndMalformedResponsesWithoutEchoingBody() throws Exception {
		for (var fixture : java.util.List.of(
			new Failure(401, "{\"error\":{\"message\":\"test-secret\"}}", ConnectionCheck.Result.AUTH_FAILED),
			new Failure(404, "{\"error\":{\"code\":\"model_not_found\",\"message\":\"test-secret\"}}", ConnectionCheck.Result.MODEL_FAILED),
			new Failure(404, "test-secret", ConnectionCheck.Result.ENDPOINT_FAILED),
			new Failure(429, "test-secret", ConnectionCheck.Result.RATE_LIMITED),
			new Failure(200, "{\"choices\":[]}", ConnectionCheck.Result.INVALID_RESPONSE),
			new Failure(200, "test-secret", ConnectionCheck.Result.INVALID_RESPONSE))) {
			var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/v1/chat/completions", exchange -> {
				byte[] body = fixture.body().getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(fixture.status(), body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			server.start();
			try { assertEquals(fixture.expected(), ConnectionCheck.api(url(server), "test-secret", "model", false, 2000)); }
			finally { server.stop(0); }
		}
	}

	@Test
	void rejectsMissingSettingsAndCredentialBearingUrls() {
		assertEquals(ConnectionCheck.Result.NOT_CONFIGURED, ConnectionCheck.api("https://example.com/v1", "", "", false, 100));
		assertEquals(ConnectionCheck.Result.INVALID_URL, ConnectionCheck.api("https://secret@example.com/v1", "key", "model", false, 100));
	}

	private static String url(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
	private record Failure(int status, String body, ConnectionCheck.Result expected) {}
}
