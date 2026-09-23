package ai.moeru.airicraft.sim.tick;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;

/**
 * Gates {@code MinecraftServer.tick}. Three modes:
 * <ul>
 *   <li>RUN – every tick proceeds (normal 20 TPS).</li>
 *   <li>FREEZE – every tick is cancelled (world fully paused).</li>
 *   <li>SPRINT – all ticks pass; the sprint batch itself is enqueued on the
 *       server thread via {@code server.execute}, so N ticks run back-to-back
 *       inside one queue-drain with no concurrent world mutation.</li>
 * </ul>
 * World state is single-threaded: running the batch on the server thread (not
 * a worker thread) is what keeps the chunk/lighting queues safe.
 */
public final class SimTickGate {
	public enum Mode { FREEZE, RUN, SPRINT }

	private static volatile Mode mode = Mode.RUN;

	private SimTickGate() {}

	public static Mode mode() {
		return mode;
	}

	/** Called at the HEAD of MinecraftServer.tick. */
	public static boolean beginTick() {
		return mode != Mode.FREEZE;
	}

	public static synchronized void freeze() {
		mode = Mode.FREEZE;
	}

	public static synchronized void run() {
		mode = Mode.RUN;
	}

	/**
	 * Executes {@code ticks} server ticks back-to-back on the server thread.
	 * The returned future completes when the batch finishes (mode restored to
	 * RUN). All world access stays on the server thread, so no synchronization
	 * against the chunk/entity queues is needed.
	 */
	public static synchronized CompletableFuture<Integer> sprint(MinecraftServer server, int ticks) {
		if (mode == Mode.SPRINT) {
			return CompletableFuture.failedFuture(new IllegalStateException("sprint already running"));
		}
		mode = Mode.SPRINT;
		CompletableFuture<Integer> future = new CompletableFuture<>();
		server.execute(() -> {
			int done = 0;
			try {
				for (; done < ticks; done++) {
					server.tick(() -> false);
				}
				future.complete(done);
			} catch (Throwable t) {
				future.completeExceptionally(t);
			} finally {
				synchronized (SimTickGate.class) {
					if (mode == Mode.SPRINT) {
						mode = Mode.RUN;
					}
				}
			}
		});
		return future;
	}
}
