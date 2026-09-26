package ai.moeru.airicraft.dashboard;

import ai.moeru.airicraft.Airicraft;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class DebugDashboardServer {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Duration STREAM_HEARTBEAT = Duration.ofSeconds(15);
	private static final int QUERY_LIMIT = 1000;
	private static final long QUERY_BYTE_LIMIT = 4L * 1024L * 1024L;

	private final DashboardObservationStore store;
	private final Path logPath;
	private final java.util.function.Supplier<Map<String, Object>> reportEnvironment;
	private volatile HttpServer server;
	private volatile ExecutorService requestExecutor;
	private volatile ScheduledExecutorService logExecutor;
	private volatile String token = "";
	private volatile Status status = Status.stopped();

	public DebugDashboardServer(DashboardObservationStore store, java.util.function.Supplier<Map<String, Object>> reportEnvironment) {
		this(store, FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log"), reportEnvironment);
	}

	DebugDashboardServer(DashboardObservationStore store, Path logPath) {
		this(store, logPath, () -> Map.of("availability", "unavailable"));
	}

	DebugDashboardServer(DashboardObservationStore store, Path logPath, java.util.function.Supplier<Map<String, Object>> reportEnvironment) {
		this.store = store;
		this.logPath = logPath;
		this.reportEnvironment = reportEnvironment;
	}

	public synchronized void start(DebugDashboardConfig config) {
		if (server != null || !config.enabled()) {
			if (!config.enabled()) {
				status = Status.disabled();
			}
			return;
		}

		HttpServer candidate = null;
		IOException lastFailure = null;
		for (int port = config.basePort(); port <= config.lastPort(); port++) {
			try {
				candidate = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
				break;
			}
			catch (IOException exception) {
				lastFailure = exception;
			}
		}

		if (candidate == null) {
			String message = lastFailure == null ? "No dashboard port was available" : lastFailure.getMessage();
			status = Status.failed(message);
			Airicraft.LOGGER.error(
				"Failed to start Airicraft debug dashboard on ports {}-{}: {}",
				config.basePort(),
				config.lastPort(),
				message
			);
			return;
		}

		token = generateToken();
		requestExecutor = Executors.newCachedThreadPool(daemonThreadFactory("airicraft-dashboard-http"));
		candidate.setExecutor(requestExecutor);
		candidate.createContext("/", this::handleIndex);
		candidate.createContext("/app.css", exchange -> handleResource(exchange, "/assets/airicraft/dashboard/app.css", "text/css; charset=utf-8"));
		candidate.createContext("/app.js", exchange -> handleResource(exchange, "/assets/airicraft/dashboard/app.js", "text/javascript; charset=utf-8"));
		candidate.createContext("/api/bootstrap", this::handleBootstrap);
		candidate.createContext("/api/observations", this::handleObservations);
		candidate.createContext("/api/stream", this::handleStream);
		candidate.createContext("/api/export", this::handleExport);
		candidate.createContext("/api/report", this::handleReport);
		candidate.createContext("/api/recording", this::handleRecordingSeek);
		candidate.createContext("/api/frame", this::handleFrame);
		candidate.start();

		server = candidate;
		long startedAtMs = System.currentTimeMillis();
		List<String> urls = dashboardUrls(candidate.getAddress().getPort(), token);
		status = new Status(true, true, candidate.getAddress().getPort(), startedAtMs, urls, "");
		DashboardLogTailer logTailer = new DashboardLogTailer(logPath, store);
		logTailer.startAtTail();
		logExecutor = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("airicraft-dashboard-log"));
		logExecutor.scheduleWithFixedDelay(logTailer::poll, 250L, 250L, TimeUnit.MILLISECONDS);

		Airicraft.LOGGER.info("Airicraft debug dashboard: {}", status.primaryUrl());
	}

	public synchronized void reconfigure(DebugDashboardConfig config) {
		store.updateMaxBytes(config.historyByteBudget());
		if (!config.enabled()) {
			stop();
			status = Status.disabled();
			return;
		}
		if (server == null || status.port() < config.basePort() || status.port() > config.lastPort()) {
			stop();
			start(config);
		}
	}

	public synchronized void stop() {
		HttpServer currentServer = server;
		server = null;
		if (currentServer != null) {
			currentServer.stop(0);
		}
		ScheduledExecutorService currentLogExecutor = logExecutor;
		logExecutor = null;
		if (currentLogExecutor != null) {
			currentLogExecutor.shutdownNow();
		}
		ExecutorService currentRequestExecutor = requestExecutor;
		requestExecutor = null;
		if (currentRequestExecutor != null) {
			currentRequestExecutor.shutdownNow();
		}
		token = "";
		status = Status.stopped();
	}

	public Status status() {
		return status;
	}

	public Map<String, Object> statusPayload() {
		Status current = status;
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("enabled", current.enabled());
		payload.put("running", current.running());
		payload.put("port", current.port());
		payload.put("startedAtMs", current.startedAtMs());
		payload.put("urls", current.urls());
		payload.put("url", current.primaryUrl());
		if (!current.error().isBlank()) {
			payload.put("error", current.error());
		}
		return payload;
	}

	private void handleIndex(HttpExchange exchange) throws IOException {
		if (!"/".equals(exchange.getRequestURI().getPath())) {
			writeText(exchange, 404, "text/plain; charset=utf-8", "Not found");
			return;
		}
		handleResource(exchange, "/assets/airicraft/dashboard/index.html", "text/html; charset=utf-8");
	}

	private void handleResource(HttpExchange exchange, String resourcePath, String contentType) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			writeText(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
			return;
		}
		try (InputStream input = DebugDashboardServer.class.getResourceAsStream(resourcePath)) {
			if (input == null) {
				writeText(exchange, 404, "text/plain; charset=utf-8", "Missing dashboard resource");
				return;
			}
			byte[] body = input.readAllBytes();
			exchange.getResponseHeaders().set("Content-Type", contentType);
			exchange.getResponseHeaders().set("Cache-Control", "no-store");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(body);
			}
		}
	}

	private void handleBootstrap(HttpExchange exchange) throws IOException {
		if (!authorizeGet(exchange)) {
			return;
		}
		DashboardObservationStore.Query query = store.queryAfter(Long.MAX_VALUE, 1);
		JsonObject response = queryMetadata(query);
		response.add("dashboard", GSON.toJsonTree(statusPayload()));
		writeJson(exchange, 200, response);
	}

	private void handleRecordingSeek(HttpExchange exchange) throws IOException {
		if (authorizeGet(exchange)) {
			writeJson(exchange, 200, store.seek(longQuery(exchange, "at", store.serverTickId())));
		}
	}

	private void handleFrame(HttpExchange exchange) throws IOException {
		if (!authorizeGet(exchange)) {
			return;
		}
		try {
			JsonObject payload = store.framePayload(longQuery(exchange, "sequence", 0L)).getAsJsonObject("payload");
			byte[] image = java.util.Base64.getDecoder().decode(payload.get("imageBase64").getAsString());
			exchange.getResponseHeaders().set("Content-Type", "image/" + payload.get("format").getAsString());
			exchange.getResponseHeaders().set("Cache-Control", "no-store");
			exchange.sendResponseHeaders(200, image.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(image);
			}
		}
		catch (IllegalArgumentException exception) {
			writeJson(exchange, 404, Map.of("error", "frame_unavailable", "message", exception.getMessage()));
		}
	}

	private void handleObservations(HttpExchange exchange) throws IOException {
		if (!authorizeGet(exchange)) {
			return;
		}
		long since = longQuery(exchange, "since", 0L);
		int limit = (int) Math.min(QUERY_LIMIT, Math.max(1L, longQuery(exchange, "limit", QUERY_LIMIT)));
		DashboardObservationStore.Query query = store.queryAfter(since, limit, QUERY_BYTE_LIMIT);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(200, 0);
		try (Writer writer = new BufferedWriter(new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8))) {
			writeQueryJson(writer, query);
		}
	}

	private void handleStream(HttpExchange exchange) throws IOException {
		if (!authorizeGet(exchange)) {
			return;
		}
		exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.getResponseHeaders().set("Connection", "keep-alive");
		exchange.sendResponseHeaders(200, 0);
		long cursor = longQuery(exchange, "since", 0L);
		try (Writer writer = new BufferedWriter(new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8))) {
			while (server != null) {
				DashboardObservationStore.Query query = store.queryAfter(cursor, 500, QUERY_BYTE_LIMIT);
				if (!query.observations().isEmpty()) {
					writer.write("event: observations\ndata: ");
					writeQueryJson(writer, query);
					writer.write("\n\n");
					writer.flush();
					cursor = query.observations().getLast().sequence();
					continue;
				}
				try {
					store.awaitAfter(cursor, STREAM_HEARTBEAT);
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return;
				}
				writer.write(": heartbeat\n\n");
				writer.flush();
			}
		}
		catch (IOException ignored) {
			// Browser disconnected.
		}
	}

	private void handleReport(HttpExchange exchange) throws IOException {
		if (!authorizeGet(exchange)) return;
		DiagnosticReport report = DiagnosticReport.capture(store, reportEnvironment.get());
		exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
		exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + report.fileName() + "\"");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(200, 0);
		try (OutputStream output = exchange.getResponseBody()) { report.writeTo(output); }
	}

	private void handleExport(HttpExchange exchange) throws IOException {
		if (!authorizeGet(exchange)) {
			return;
		}
		DashboardObservationStore.Query metadata = store.queryAfter(Long.MAX_VALUE, 1);
		List<DashboardObservation> retained = store.retainedObservations();
		String fileName = "airicraft-debug-" + metadata.sessionId() + ".jsonl";
		exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
		exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(200, 0);
		try (Writer writer = new BufferedWriter(new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8))) {
			JsonObject manifest = queryMetadata(metadata);
			manifest.addProperty("recordType", "manifest");
			manifest.addProperty("schemaVersion", 2);
			writeJsonLine(writer, manifest);
			for (DashboardObservation observation : retained) {
				writer.write("{\"recordType\":\"observation\",");
				writeObservationFields(writer, observation, true);
				writer.write("}\n");
			}
		}
	}

	private boolean authorizeGet(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			writeJson(exchange, 405, Map.of("error", "method_not_allowed"));
			return false;
		}
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		if (!authorizationEquals(authorization, "Bearer " + token)) {
			writeJson(exchange, 401, Map.of("error", "unauthorized", "message", "Invalid dashboard token"));
			return false;
		}
		return true;
	}

	private static boolean authorizationEquals(String actual, String expected) {
		if (actual == null || actual.length() != expected.length()) {
			return false;
		}
		int difference = 0;
		for (int index = 0; index < actual.length(); index++) {
			difference |= actual.charAt(index) ^ expected.charAt(index);
		}
		return difference == 0;
	}

	private static JsonObject queryMetadata(DashboardObservationStore.Query query) {
		JsonObject response = new JsonObject();
		response.addProperty("schemaVersion", 2);
		response.addProperty("sessionId", query.sessionId());
		response.addProperty("sessionStartedAtMs", query.sessionStartedAtMs());
		response.addProperty("latestTick", query.latestTick());
		response.addProperty("serverTickId", query.serverTickId());
		response.addProperty("historyWindowTicks", query.historyWindowTicks());
		response.addProperty("paused", query.paused());
		response.addProperty("serverClockAvailable", query.serverClockAvailable());
		response.addProperty("oldestSequence", query.oldestSequence());
		response.addProperty("latestSequence", query.latestSequence());
		response.addProperty("truncated", query.truncated());
		response.addProperty("retainedBytes", query.retainedBytes());
		response.addProperty("maxBytes", query.maxBytes());
		response.add("droppedByType", GSON.toJsonTree(query.droppedByType()));
		return response;
	}

	private static void writeQueryJson(Writer writer, DashboardObservationStore.Query query) throws IOException {
		writer.write('{');
		writer.write("\"schemaVersion\":2,");
		writer.write("\"sessionId\":");
		GSON.toJson(query.sessionId(), writer);
		writer.write(",\"sessionStartedAtMs\":" + query.sessionStartedAtMs());
		writer.write(",\"latestTick\":" + query.latestTick());
		writer.write(",\"serverTickId\":" + query.serverTickId());
		writer.write(",\"historyWindowTicks\":" + query.historyWindowTicks());
		writer.write(",\"paused\":" + query.paused());
		writer.write(",\"serverClockAvailable\":" + query.serverClockAvailable());
		writer.write(",\"oldestSequence\":" + query.oldestSequence());
		writer.write(",\"latestSequence\":" + query.latestSequence());
		writer.write(",\"truncated\":" + query.truncated());
		writer.write(",\"retainedBytes\":" + query.retainedBytes());
		writer.write(",\"maxBytes\":" + query.maxBytes());
		writer.write(",\"droppedByType\":");
		GSON.toJson(query.droppedByType(), writer);
		writer.write(",\"observations\":[");
		boolean first = true;
		for (DashboardObservation observation : query.observations()) {
			if (!first) {
				writer.write(',');
			}
			writer.write('{');
			writeObservationFields(writer, observation, false);
			writer.write('}');
			first = false;
		}
		writer.write("]}");
	}

	private static void writeObservationFields(Writer writer, DashboardObservation observation, boolean includeImages) throws IOException {
		writer.write("\"sequence\":" + observation.sequence());
		writer.write(",\"sessionId\":");
		GSON.toJson(observation.sessionId(), writer);
		writer.write(",\"tick\":" + observation.tick());
		writer.write(",\"serverTickId\":" + observation.serverTickId());
		writer.write(",\"throughServerTickId\":" + observation.throughServerTickId());
		writer.write(",\"capturedAtMs\":" + observation.capturedAtMs());
		writer.write(",\"type\":");
		GSON.toJson(observation.type(), writer);
		writer.write(",\"payload\":");
		if (!includeImages && observation.type().equals("visual_frame")) {
			JsonObject payload = com.google.gson.JsonParser.parseString(observation.payloadJson()).getAsJsonObject();
			payload.remove("imageBase64");
			GSON.toJson(payload, writer);
		}
		else {
			writer.write(observation.payloadJson());
		}
	}

	private static void writeJsonLine(Writer writer, Object json) throws IOException {
		GSON.toJson(json, writer);
		writer.write('\n');
	}

	private static void writeJson(HttpExchange exchange, int status, Object payload) throws IOException {
		writeText(exchange, status, "application/json; charset=utf-8", GSON.toJson(payload));
	}

	private static void writeText(HttpExchange exchange, int status, String contentType, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private static long longQuery(HttpExchange exchange, String key, long fallback) {
		String raw = exchange.getRequestURI().getRawQuery();
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		for (String part : raw.split("&")) {
			String[] pair = part.split("=", 2);
			if (pair.length == 2 && URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(key)) {
				try {
					return Long.parseLong(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
				}
				catch (NumberFormatException ignored) {
					return fallback;
				}
			}
		}
		return fallback;
	}

	private static String generateToken() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		StringBuilder token = new StringBuilder(bytes.length * 2);
		for (byte current : bytes) {
			token.append(String.format("%02x", current));
		}
		return token.toString();
	}

	private static List<String> dashboardUrls(int port, String token) {
		List<InetAddress> addresses = new ArrayList<>();
		try {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces.hasMoreElements()) {
				NetworkInterface networkInterface = interfaces.nextElement();
				if (!networkInterface.isUp() || networkInterface.isLoopback()) {
					continue;
				}
				Enumeration<InetAddress> interfaceAddresses = networkInterface.getInetAddresses();
				while (interfaceAddresses.hasMoreElements()) {
					InetAddress address = interfaceAddresses.nextElement();
					if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
						addresses.add(address);
					}
				}
			}
		}
		catch (IOException ignored) {
		}
		addresses.sort(Comparator
			.comparing((InetAddress address) -> !address.isSiteLocalAddress())
			.thenComparing(InetAddress::getHostAddress));
		List<String> urls = new ArrayList<>();
		for (InetAddress address : addresses) {
			urls.add("http://" + address.getHostAddress() + ':' + port + "/#token=" + token);
		}
		urls.add("http://127.0.0.1:" + port + "/#token=" + token);
		return List.copyOf(urls);
	}

	private static java.util.concurrent.ThreadFactory daemonThreadFactory(String prefix) {
		AtomicInteger sequence = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, prefix + '-' + sequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	public record Status(
		boolean enabled,
		boolean running,
		int port,
		long startedAtMs,
		List<String> urls,
		String error
	) {
		public Status {
			urls = urls == null ? List.of() : List.copyOf(urls);
			error = error == null ? "" : error;
		}

		public String primaryUrl() {
			return urls.isEmpty() ? "" : urls.getFirst();
		}

		private static Status disabled() {
			return new Status(false, false, -1, 0L, List.of(), "");
		}

		private static Status stopped() {
			return new Status(true, false, -1, 0L, List.of(), "");
		}

		private static Status failed(String error) {
			return new Status(true, false, -1, 0L, List.of(), error);
		}
	}
}
