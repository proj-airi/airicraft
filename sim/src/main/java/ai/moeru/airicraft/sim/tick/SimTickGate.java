package ai.moeru.airicraft.sim.tick;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;

/**
 * Gates {@code MinecraftServer.tick}. Three modes:
 * <ul>
 *   <li>RUN – every tick proceeds (normal 20 TPS).</li>
 *   <li>FREEZE – every tick is cancelled (world fully paused).</li>
 *   <li>SPRINT – only the dedicated sprint worker thread may run ticks;
 *       the normal server loop is cancelled meanwhile, so the worker can
 *       execute N ticks back-to-back as fast as the CPU allows.</li>
 * </ul>
 */
public final class SimTickGate {
	public enum Mode { FREEZE, RUN, SPRINT }

	private static volatile Mode mode = Mode.RUN;
	private static volatile Thread sprintThread;
	private static volatile CompletableFuture<Integer> sprintFuture;

	private SimTickGate() {}

	public static Mode mode() {
		return mode;
	}

	/** Called at the HEAD of MinecraftServer.tick. */
	public static boolean beginTick() {
		Mode m = mode;
		if (m == Mode.RUN) {
			return true;
		}
		if (m == Mode.FREEZE) {
			return false;
		}
		return Thread.currentThread() == sprintThread;
	}

	public static synchronized void freeze() {
		cancelSprint();
		mode = Mode.FREEZE;
	}

	public static synchronized void run() {
		cancelSprint();
		mode = Mode.RUN;
	}

	/** Executes {@code ticks} server ticks on a worker thread, as fast as possible. */
	public static synchronized CompletableFuture<Integer> sprint(MinecraftServer server, int ticks) {
		if (mode == Mode.SPRINT) {
			return CompletableFuture.failedFuture(new IllegalStateException("sprint already running"));
		}
		mode = Mode.SPRINT;
		CompletableFuture<Integer> future = new CompletableFuture<>();
		sprintFuture = future;
		Thread worker = new Thread(() -> {
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
					sprintThread = null;
					sprintFuture = null;
					if (mode == Mode.SPRINT) {
						mode = Mode.RUN;
					}
				}
			}
		}, "airicraft-sim-sprint");
		worker.setDaemon(true);
		sprintThread = worker;
		worker.start();
		return future;
	}

	private static void cancelSprint() {
		CompletableFuture<Integer> f = sprintFuture;
		if (f != null) {
			f.completeExceptionally(new IllegalStateException("sprint cancelled"));
			sprintFuture = null;
		}
	}
}
