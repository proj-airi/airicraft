package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.navigation.Goal;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.MovementPolicy;
import ai.moeru.airicraft.navigation.PathSearch;
import ai.moeru.airicraft.navigation.SearchBudget;
import ai.moeru.airicraft.navigation.SearchResult;
import ai.moeru.airicraft.navigation.TerrainView;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs searches on one daemon thread. Owners poll the handle on the client thread and drop stale ones. */
public final class NavigationPlanner {
	private static final NavigationPlanner SHARED = new NavigationPlanner();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "airicraft-navigation-planner");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});

	public static NavigationPlanner shared() {
		return SHARED;
	}

	public Pending submit(TerrainView terrain, MovementPolicy policy, GridPos start, Goal goal, SearchBudget budget) {
		AtomicBoolean cancelled = new AtomicBoolean();
		CompletableFuture<SearchResult> future = CompletableFuture.supplyAsync(
			() -> PathSearch.search(terrain, policy, start, goal, budget, cancelled::get), worker);
		return new Pending(future, cancelled, start, goal);
	}

	public record Pending(CompletableFuture<SearchResult> future, AtomicBoolean cancelled, GridPos start, Goal goal) {
		public boolean done() {
			return future.isDone();
		}

		/** The result once done, or null while running or when the search threw. */
		public SearchResult result() {
			try {
				return future.getNow(null);
			}
			catch (CompletionException | CancellationException exception) {
				return null;
			}
		}

		public Throwable failure() {
			return future.isCompletedExceptionally() ? future.handle((ignored, error) -> error).getNow(null) : null;
		}

		public void cancel() {
			cancelled.set(true);
		}
	}
}
