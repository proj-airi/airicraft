package ai.moeru.airicraft.airi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** {@link AiriTransport} on the JDK WebSocket client. */
public final class JdkAiriTransport implements AiriTransport {
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

	@Override
	public CompletableFuture<Connection> connect(URI uri, Listener listener) {
		return httpClient.newWebSocketBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.buildAsync(uri, new TextListener(listener))
			.thenApply(JdkConnection::new);
	}

	private static final class JdkConnection implements Connection {
		private final WebSocket webSocket;
		private CompletableFuture<WebSocket> sending;

		private JdkConnection(WebSocket webSocket) {
			this.webSocket = webSocket;
			this.sending = CompletableFuture.completedFuture(webSocket);
		}

		@Override
		public synchronized void send(String text) {
			// The JDK client rejects a send while the previous one is still in flight.
			sending = sending.handle((ignored, error) -> null).thenCompose(ignored -> webSocket.sendText(text, true));
		}

		@Override
		public synchronized void close() {
			sending.handle((ignored, error) -> null)
				.thenCompose(ignored -> webSocket.sendClose(WebSocket.NORMAL_CLOSURE, ""))
				.orTimeout(2, TimeUnit.SECONDS)
				.whenComplete((ignored, error) -> webSocket.abort());
		}
	}

	private static final class TextListener implements WebSocket.Listener {
		private final Listener listener;
		private final StringBuilder partial = new StringBuilder();

		private TextListener(Listener listener) {
			this.listener = listener;
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			partial.append(data);
			if (last) {
				String text = partial.toString();
				partial.setLength(0);
				listener.onText(text);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			listener.onClosed("closed by server (" + statusCode + (reason == null || reason.isBlank() ? "" : ": " + reason) + ")");
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			listener.onError(error);
		}
	}
}
