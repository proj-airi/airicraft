package ai.moeru.airicraft.wrapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

final class HttpBridgeTransport implements MinecraftTransport {
	private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration JOIN_REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private static final Duration RELOAD_REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration SCREENSHOT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration VISION_REQUEST_TIMEOUT = Duration.ofSeconds(20);
	private static final Duration DEBUG_COMPACTION_REQUEST_TIMEOUT = Duration.ofSeconds(45);
	private static final Duration AGENT_TOOL_REQUEST_TIMEOUT = Duration.ofSeconds(305);
	private static final Duration CLIENT_TICK_DEBUG_REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(1))
		.build();

	@Override
	public Map<String, Object> request(String method, String path, Object body) {
		BridgeStateFile.BridgeState state = BridgeStateFile.read()
			.orElseThrow(() -> new BridgeUnavailableException("minecraft_unavailable", "Minecraft bridge is not active"));

		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:" + state.port() + path))
			.timeout(requestTimeout(path))
			.header("Authorization", "Bearer " + state.token())
			.header("Accept", "application/json");

		try {
			if (body == null) {
				builder.method(method, HttpRequest.BodyPublishers.noBody());
			}
			else {
				builder.method(method, HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
					.header("Content-Type", "application/json");
			}

			HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			Map<String, Object> payload = response.body() == null || response.body().isBlank()
				? new LinkedHashMap<>()
				: OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);

			if (response.statusCode() >= 400) {
				String code = String.valueOf(payload.getOrDefault("error", "bridge_error"));
				String message = String.valueOf(payload.getOrDefault("message", "Bridge request failed"));
				throw new BridgeUnavailableException(code, message);
			}

			return payload;
		}
		catch (ConnectException exception) {
			BridgeStateFile.deleteIfPresent();
			throw new BridgeUnavailableException("minecraft_unavailable", "Minecraft bridge is not reachable");
		}
		catch (IOException exception) {
			throw new BridgeUnavailableException("bridge_io_error", nonEmpty(exception.getMessage(), "Bridge IO error"));
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new BridgeUnavailableException("bridge_interrupted", "Bridge request interrupted");
		}
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static Duration requestTimeout(String path) {
		return switch (path) {
			case "/v1/worlds/create", "/v1/worlds/rename", "/v1/worlds/delete" -> Duration.ofSeconds(125);
			case "/v1/reload" -> RELOAD_REQUEST_TIMEOUT;
			case "/v1/camera/screenshot", "/v1/map/image" -> SCREENSHOT_REQUEST_TIMEOUT;
			case "/v1/vision/describe" -> VISION_REQUEST_TIMEOUT;
			case "/v1/worlds/join", "/v1/servers/join", "/v1/evaluation/run" -> JOIN_REQUEST_TIMEOUT;
			case "/v1/agent/debug/compact" -> DEBUG_COMPACTION_REQUEST_TIMEOUT;
			case "/v1/agent/tools" -> AGENT_TOOL_REQUEST_TIMEOUT;
			case "/v1/agent/debug/ticks/pause", "/v1/agent/debug/ticks/step", "/v1/agent/debug/trace/records" -> CLIENT_TICK_DEBUG_REQUEST_TIMEOUT;
			default -> DEFAULT_REQUEST_TIMEOUT;
		};
	}
}
