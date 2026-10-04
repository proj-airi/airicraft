package ai.moeru.airicraft.airi;

/**
 * Settings for the link to AIRI's channel server. The link stays off unless it is enabled and has a token.
 */
public record AiriLinkConfig(boolean enabled, String url, String token) {
	public static final String DEFAULT_URL = "ws://127.0.0.1:6121/ws";

	public AiriLinkConfig {
		url = url == null || url.isBlank() ? DEFAULT_URL : url.trim();
		token = token == null ? "" : token.trim();
	}

	public static AiriLinkConfig defaults() {
		return new AiriLinkConfig(false, DEFAULT_URL, "");
	}

	public boolean hasToken() {
		return !token.isEmpty();
	}

	public static boolean isWebSocketUrl(String url) {
		return url != null && (url.startsWith("ws://") || url.startsWith("wss://"));
	}
}
