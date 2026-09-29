package ai.moeru.airicraft;

import ai.moeru.airicraft.agent.control.CameraController;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.gui.screens.TitleScreen;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModBridgeServerTest {
	private static final ModBridgeServer BRIDGE = new ModBridgeServer(
		() -> null,
		() -> null,
		() -> null,
		() -> null,
		() -> null,
		new CameraController(),
		new BridgeDiscoveryFile(Path.of("unused-bridge-state.json"))
	);

	@Test
	void currentScreenNameNormalizesTitleScreen() {
		assertEquals("TitleScreen", ModBridgeServer.currentScreenNameForStatus(new TitleScreen(), false));
	}

	@Test
	void currentScreenNameKeepsStableNullStates() {
		assertEquals("none", ModBridgeServer.currentScreenNameForStatus(null, false));
		assertEquals("in_game", ModBridgeServer.currentScreenNameForStatus(null, true));
	}

	@Test
	void commonGetContractChecksAuthorizationAndMethodBeforeHandling() throws Exception {
		assertError(request("GET", "", false, exchange -> BRIDGE.handleJson(exchange, () -> Map.of())),
			401, "unauthorized", "Invalid bridge token");
		assertError(request("POST", "", true, exchange -> BRIDGE.handleJson(exchange, () -> Map.of())),
			405, "method_not_allowed", null);

		HttpResponse<String> response = request(
			"GET",
			"",
			true,
			exchange -> BRIDGE.handleJson(exchange, () -> Map.of("available", true))
		);
		assertEquals(200, response.statusCode());
		assertEquals(true, json(response).get("available").getAsBoolean());
	}

	@Test
	void commonGetContractMapsDomainAndInternalFailures() throws Exception {
		assertError(request("GET", "", true, exchange -> BRIDGE.handleJson(exchange, () -> {
			throw new BridgeUnavailableException("world_not_loaded", "No world is currently loaded");
		})), 503, "world_not_loaded", "No world is currently loaded");
		assertError(request("GET", "", true, exchange -> BRIDGE.handleJson(exchange, () -> {
			throw new IllegalStateException("broken");
		})), 500, "internal_error", "broken");
		assertError(request("GET", "", true, exchange -> BRIDGE.handleJson(exchange, () -> {
			throw new JsonSyntaxException("internal JSON failure");
		})), 500, "internal_error", "internal JSON failure");
		assertError(request("GET", "", true, exchange -> BRIDGE.handleJson(exchange, () -> {
			throw new RuntimeException();
		})), 500, "internal_error", "null");
	}

	@Test
	void commonBodyContractParsesJsonAndRejectsMalformedInput() throws Exception {
		HttpResponse<String> response = request("POST", "{\"name\":\"Alex\"}", true, exchange ->
			BRIDGE.handleJsonBody(exchange, "POST", NameRequest.class, request -> Map.of("name", request.name()))
		);
		assertEquals(200, response.statusCode());
		assertEquals("Alex", json(response).get("name").getAsString());
		assertError(request("POST", "{", true, exchange ->
			BRIDGE.handleJsonBody(exchange, "POST", NameRequest.class, request -> Map.of())
		), 400, "invalid_json", "Malformed request payload");
	}

	private static HttpResponse<String> request(
		String method,
		String body,
		boolean authorized,
		ExchangeHandler handler
	) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", handler::handle);
		server.start();
		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
				.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
			if (authorized) {
				request.header("Authorization", "Bearer null");
			}
			return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
		finally {
			server.stop(0);
		}
	}

	private static void assertError(HttpResponse<String> response, int status, String code, String message) {
		assertEquals(status, response.statusCode());
		assertEquals(code, json(response).get("error").getAsString());
		if (message != null) {
			assertEquals(message, json(response).get("message").getAsString());
		}
	}

	private static com.google.gson.JsonObject json(HttpResponse<String> response) {
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	@FunctionalInterface
	private interface ExchangeHandler {
		void handle(HttpExchange exchange) throws IOException;
	}

	private record NameRequest(String name) {
	}
}
