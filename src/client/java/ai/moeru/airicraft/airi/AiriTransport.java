package ai.moeru.airicraft.airi;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/** The WebSocket boundary of the AIRI link. Tests replace it with a fake. */
public interface AiriTransport {
	CompletableFuture<Connection> connect(URI uri, Listener listener);

	interface Connection {
		void send(String text);

		void close();
	}

	interface Listener {
		void onText(String text);

		void onClosed(String reason);

		void onError(Throwable error);
	}
}
