package ai.moeru.airicraft.dashboard;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Redacts text, including credentials pasted into otherwise allowlisted fields. Pixels are not inspected. */
public final class DiagnosticRedactor {
	private static final String MASK = "[REDACTED]";
	private static final Pattern AUTH = Pattern.compile("(?i)\\b(?:bearer|basic)\\s+(?:\\[REDACTED\\]|[a-z0-9+/._~=-]+)");
	private static final Pattern ASSIGNMENT = Pattern.compile("(?i)([\\\"']?(?:authorization|proxy-authorization|x-api-key|api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|client[_-]?secret|signature|cookie)[\\\"']?\\s*[:=]\\s*)(?:\\[REDACTED\\]|\\\"[^\\\"]*\\\"|'[^']*'|(?:\\[REDACTED\\]|[^\\s,;&}\\]#\\[])+)");
	private static final Pattern USERINFO = Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]{0,31}://)[^\\s/@]+@");
	private static final Set<String> SENSITIVE_KEYS = Set.of("authorization", "proxyauthorization", "cookie", "setcookie",
		"apikey", "password", "passwd", "secret", "clientsecret", "token", "credentials", "credential", "otlpheaders");
	private final List<String> secrets;

	public DiagnosticRedactor(Collection<String> knownSecrets) {
		var variants = new LinkedHashSet<String>();
		Gson gson = new Gson();
		for (String secret : knownSecrets) {
			if (secret == null || secret.isBlank()) continue;
			variants.add(secret);
			String json = gson.toJson(secret);
			variants.add(json.substring(1, json.length() - 1));
			variants.add(URLEncoder.encode(secret, StandardCharsets.UTF_8));
			if (secret.matches("(?is)^(Bearer|Basic) .+")) variants.add(secret.substring(secret.indexOf(' ') + 1));
		}
		secrets = variants.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
	}

	public String redact(String value) {
		String result = value;
		for (String secret : secrets) result = result.replace(secret, MASK);
		result = AUTH.matcher(result).replaceAll(MASK);
		result = ASSIGNMENT.matcher(result).replaceAll("$1" + MASK);
		result = USERINFO.matcher(result).replaceAll("$1" + MASK + "@");
		return result;
	}

	public JsonElement redact(JsonElement value) {
		if (value.isJsonObject()) {
			JsonObject result = new JsonObject();
			value.getAsJsonObject().entrySet().forEach(entry -> {
				String key = entry.getKey().replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
				boolean sensitive = SENSITIVE_KEYS.contains(key) || key.endsWith("apikey") || key.endsWith("token") || key.endsWith("secret");
				result.add(redact(entry.getKey()), sensitive ? new JsonPrimitive(MASK) : redact(entry.getValue()));
			});
			return result;
		}
		if (value.isJsonArray()) {
			JsonArray result = new JsonArray(); value.getAsJsonArray().forEach(item -> result.add(redact(item))); return result;
		}
		return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? new JsonPrimitive(redact(value.getAsString())) : value.deepCopy();
	}
}
