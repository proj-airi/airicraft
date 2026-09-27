package ai.moeru.airicraft.settings;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.TreeSet;

/** Model discovery is optional; providers without it still accept manual model IDs. */
final class ProviderModels {
	record Result(List<String> models, String hint) {}
	static Result api(String url, String key) {
		var fallback = new Result(List.of(), "Models unavailable. Enter a model manually.");
		try {
			var uri = URI.create(url.strip());
			if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
				|| uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || key.isBlank()) return fallback;
			var timeout = Duration.ofSeconds(10);
			try (var client = HttpClient.newBuilder().connectTimeout(timeout).build()) {
				var request = HttpRequest.newBuilder(URI.create(url.strip().replaceAll("/+$", "") + "/models"))
					.timeout(timeout).header("Authorization", "Bearer " + key).GET().build();
				var response = client.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() != 200) return fallback;
				var models = new TreeSet<String>();
				for (var element : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("data")) {
					var id = element.getAsJsonObject().get("id");
					if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString() && !id.getAsString().isBlank()) models.add(id.getAsString());
				}
				return models.isEmpty() ? fallback : new Result(List.copyOf(models), "Choose a model, or type its ID.");
			}
		} catch (InterruptedException exception) { Thread.currentThread().interrupt(); return fallback; }
		catch (java.io.IOException | RuntimeException exception) { return fallback; }
	}
}
