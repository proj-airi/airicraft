package ai.moeru.airicraft.agent.chat;

import java.net.URI;

public record AiriSpeechConfig(boolean enabled, String websocketUrl, String token) {
	public static AiriSpeechConfig defaults() {
		return new AiriSpeechConfig(false, "ws://localhost:6121/ws", "");
	}

	public AiriSpeechConfig {
		URI uri = URI.create(websocketUrl);
		if (!("ws".equals(uri.getScheme()) || "wss".equals(uri.getScheme())) || uri.getHost() == null) {
			throw new IllegalArgumentException("airiSpeech.websocketUrl must be a ws:// or wss:// URL");
		}
		token = token == null ? "" : token;
	}

	@Override public String toString() {
		return "AiriSpeechConfig[enabled=" + enabled + ", websocketUrl=" + websocketUrl + ", token=<redacted>]";
	}
}
