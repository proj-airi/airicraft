package ai.moeru.airicraft.airi;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** The clock and thread boundary of the AIRI link. Every link state change runs on this scheduler. */
public interface AiriLinkScheduler {
	void execute(Runnable task);

	Cancellable schedule(Runnable task, Duration delay);

	long nowMillis();

	/** Stops the scheduler after the tasks that are already queued. Later tasks are ignored. */
	default void close() {}

	interface Cancellable {
		void cancel();
	}

	static AiriLinkScheduler singleThread() {
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "airicraft-airi-link");
			thread.setDaemon(true);
			return thread;
		});
		return new AiriLinkScheduler() {
			@Override
			public void execute(Runnable task) {
				try {
					executor.execute(task);
				}
				catch (RejectedExecutionException closed) {
					// The link is closed. Late transport callbacks have nothing to do.
				}
			}

			@Override
			public Cancellable schedule(Runnable task, Duration delay) {
				try {
					ScheduledFuture<?> future = executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
					return () -> future.cancel(false);
				}
				catch (RejectedExecutionException closed) {
					return () -> {};
				}
			}

			@Override
			public long nowMillis() {
				return System.currentTimeMillis();
			}

			@Override
			public void close() {
				executor.shutdown();
			}
		};
	}
}
