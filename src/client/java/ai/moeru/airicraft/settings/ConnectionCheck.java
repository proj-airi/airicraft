package ai.moeru.airicraft.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/** Small user-requested inference probes. Never return provider text or exception messages. */
final class ConnectionCheck {
	private static final String TEST_IMAGE = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAKElEQVR4nO3NsQ0AAAzCMP5/un0CNkuZ41wybXsHAAAAAAAAAAAAxR4yw/wuPL6QkAAAAABJRU5ErkJggg==";
	static Result codex(ai.moeru.airicraft.agent.AgentConfig.CodexAppServerConfig config) { return codex(config, false); }
	static Result codex(ai.moeru.airicraft.agent.AgentConfig.CodexAppServerConfig config, boolean vision) {
		int startupTimeout = Math.min(config.startupTimeoutMillis(), 30_000);
		try (var client = new ai.moeru.airicraft.agent.llm.codex.CodexAppServerClient(config.executable(), startupTimeout)) {
			try { client.start(); }
			catch (IOException exception) { return Result.CODEX_START_FAILED; }
			var account = client.request("account/read", new JsonObject(), startupTimeout);
			if (!account.has("requiresOpenaiAuth")) return Result.CODEX_FAILED;
			if (account.get("requiresOpenaiAuth").getAsBoolean() && (!account.has("account") || account.get("account").isJsonNull())) {
				return Result.CODEX_AUTH_FAILED;
			}
			var threadParams = new JsonObject();
			threadParams.addProperty("ephemeral", true);
			threadParams.addProperty("approvalPolicy", "never");
			threadParams.addProperty("sandbox", "read-only");
			threadParams.addProperty("cwd", System.getProperty("java.io.tmpdir"));
			threadParams.addProperty("baseInstructions", "You are an inference-only connection check. Do not use tools, inspect files or run commands. Reply only with OK.");
			if (!config.model().isBlank()) threadParams.addProperty("model", config.model());
			if (!config.serviceTier().isBlank()) threadParams.addProperty("serviceTier", config.serviceTier());
			var thread = client.request("thread/start", threadParams, startupTimeout);
			var turnParams = new JsonObject();
			turnParams.addProperty("threadId", thread.getAsJsonObject("thread").get("id").getAsString());
			turnParams.add("input", JsonParser.parseString("[{\"type\":\"text\",\"text\":\"Connection test. Reply only with OK.\"}]"));
			if (vision) {
				var image = new JsonObject();
				image.addProperty("type", "image");
				image.addProperty("url", TEST_IMAGE);
				image.addProperty("detail", "low");
				turnParams.getAsJsonArray("input").add(image);
			}
			if (!config.reasoningEffort().isBlank()) turnParams.addProperty("effort", config.reasoningEffort());
			var handle = client.startTurn(turnParams, startupTimeout);
			var result = handle.completion().get(Math.min(config.turnTimeoutMillis(), 60_000), java.util.concurrent.TimeUnit.MILLISECONDS);
			return "completed".equals(result.status()) && result.error() == null
				&& result.agentMessage() != null && !result.agentMessage().isBlank() ? Result.READY : Result.CODEX_FAILED;
		} catch (java.util.concurrent.TimeoutException exception) { return Result.TIMED_OUT; }
		catch (InterruptedException exception) { Thread.currentThread().interrupt(); return Result.CANCELLED; }
		catch (IOException | java.util.concurrent.ExecutionException | RuntimeException exception) { return Result.CODEX_FAILED; }
	}
	enum Result {
		READY("Connected: the model answered the test."),
		NOT_CONFIGURED("Enter a provider URL, API key and model in settings, then retry."),
		INVALID_URL("Use an http(s) provider URL without credentials, query parameters or fragments."),
		AUTH_FAILED("Authentication failed. Check the API key and its permissions."),
		MODEL_FAILED("Model unavailable. Check its name and your account's model access."),
		ENDPOINT_FAILED("Endpoint not found. Check the provider URL; it usually ends in /v1."),
		RATE_LIMITED("Usage limit reached. Check your provider quota or retry later."),
		INVALID_RESPONSE("The service did not return a model answer. Check API compatibility."),
		REQUEST_FAILED("The provider rejected the request. Check model capabilities and settings."),
		UNREACHABLE("Could not connect. Check the address, network and provider status."),
		TIMED_OUT("Connection check timed out. Check the service and retry."),
		CANCELLED("Connection check cancelled."),
		CODEX_START_FAILED("Could not start local Codex. Install it or choose its full executable path."),
		CODEX_AUTH_FAILED("Local Codex is not signed in. Sign in to Codex, then retry."),
		CODEX_FAILED("Local Codex could not answer. Check its login, model and usage limits, then retry.");
		private final String message;
		Result(String message) { this.message = message; }
		String message() { return message; }
	}

	static Result api(String url, String key, String model, boolean vision, int timeoutMillis) {
		if (url.isBlank() || key.isBlank() || model.isBlank()) return Result.NOT_CONFIGURED;
		URI endpoint;
		try {
			var base = URI.create(url.trim());
			if (!("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()))
				|| base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) return Result.INVALID_URL;
			endpoint = URI.create(url.trim().replaceAll("/+$", "") + "/chat/completions");
		} catch (IllegalArgumentException exception) { return Result.INVALID_URL; }
		var payload = new JsonObject();
		payload.addProperty("model", model.trim());
		payload.addProperty("stream", false);
		var message = new JsonObject();
		message.addProperty("role", "user");
		if (vision) {
			var content = new JsonArray();
			var prompt = new JsonObject();
			prompt.addProperty("type", "text");
			prompt.addProperty("text", "Connection test: describe this test image in a few words.");
			content.add(prompt);
			var image = new JsonObject();
			image.addProperty("type", "image_url");
			var imageUrl = new JsonObject();
			imageUrl.addProperty("url", TEST_IMAGE);
			imageUrl.addProperty("detail", "low");
			image.add("image_url", imageUrl);
			content.add(image);
			message.add("content", content);
		} else message.addProperty("content", "Connection test. Reply only with OK.");
		var messages = new JsonArray();
		messages.add(message);
		payload.add("messages", messages);
		var timeout = Duration.ofMillis(Math.clamp(timeoutMillis, 1, 60_000));
		try (var client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()) {
			var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
				.header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
			var response = client.send(request, HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			if (status == 401 || status == 403) return Result.AUTH_FAILED;
			if (status == 429) return Result.RATE_LIMITED;
			if (status >= 400) {
				if (modelError(response.body())) return Result.MODEL_FAILED;
				if (status == 404) return Result.ENDPOINT_FAILED;
				return status >= 500 ? Result.UNREACHABLE : Result.REQUEST_FAILED;
			}
			if (status != 200) return Result.INVALID_RESPONSE;
			try {
				var answer = JsonParser.parseString(response.body()).getAsJsonObject()
					.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
				return answer.isBlank() ? Result.INVALID_RESPONSE : Result.READY;
			} catch (RuntimeException exception) { return Result.INVALID_RESPONSE; }
		} catch (HttpTimeoutException exception) { return Result.TIMED_OUT; }
		catch (InterruptedException exception) { Thread.currentThread().interrupt(); return Result.CANCELLED; }
		catch (IOException exception) { return Result.UNREACHABLE; }
		catch (IllegalArgumentException exception) { return Result.REQUEST_FAILED; }
	}

	private static boolean modelError(String body) {
		try {
			var error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
			return error != null && error.has("code") && java.util.Set.of("model_not_found", "invalid_model", "model_not_available")
				.contains(error.get("code").getAsString());
		} catch (RuntimeException exception) { return false; }
	}
}
