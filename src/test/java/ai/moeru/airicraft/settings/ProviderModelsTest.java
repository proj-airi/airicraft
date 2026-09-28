package ai.moeru.airicraft.settings;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ProviderModelsTest {
	@Test void fetchesSortedDistinctIdsWithTheDraftCredentials() throws Exception {
		var auth = new AtomicReference<String>();
		var server = server(200, "{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"},{\"id\":\"a-model\"}]}", auth);
		try {
			assertEquals(List.of("a-model", "z-model"), ProviderModels.api(url(server), "draft-secret").models());
			assertEquals("Bearer draft-secret", auth.get());
		} finally { server.stop(0); }
	}
	@Test void unsupportedAuthAndMalformedResponsesAllowManualEntryWithoutEchoingSecrets() throws Exception {
		for (int status : List.of(401, 404, 200)) {
			var server = server(status, "draft-secret", new AtomicReference<>());
			try {
				var result = ProviderModels.api(url(server), "draft-secret");
				assertTrue(result.models().isEmpty());
				assertFalse(result.hint().contains("draft-secret"));
				assertTrue(result.hint().contains("manually"));
			} finally { server.stop(0); }
		}
	}
	private static HttpServer server(int status, String body, AtomicReference<String> auth) throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/models", exchange -> {
			auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		return server;
	}
	private static String url(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
}
