package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.client.server.IntegratedServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class ServerRecipeDisplayCatalog {
	private static final Object CACHE_LOCK = new Object();
	private static final Snapshot EMPTY = new Snapshot(List.of());
	private static IntegratedServer cachedServer;
	private static RecipeManager cachedRecipeManager;
	private static Snapshot cachedSnapshot = EMPTY;

	private ServerRecipeDisplayCatalog() {
	}

	static Snapshot current() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || !minecraft.hasSingleplayerServer()) {
			clear();
			return EMPTY;
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			clear();
			return EMPTY;
		}
		RecipeManager recipeManager = server.getRecipeManager();
		synchronized (CACHE_LOCK) {
			if (server == cachedServer && recipeManager == cachedRecipeManager) {
				return cachedSnapshot;
			}
		}

		CompletableFuture<List<RecipeDisplayEntry>> future = new CompletableFuture<>();
		server.executeIfPossible(() -> {
			try {
				List<RecipeHolder<?>> recipes = List.copyOf(recipeManager.getRecipes());
				List<RecipeDisplayEntry> entries = new ArrayList<>();
				for (RecipeHolder<?> recipe : recipes) {
					recipeManager.listDisplaysForRecipe(recipe.id(), entries::add);
				}
				future.complete(List.copyOf(entries));
			}
			catch (Throwable throwable) {
				future.completeExceptionally(throwable);
			}
		});
		try {
			Snapshot snapshot = new Snapshot(future.get(2L, TimeUnit.SECONDS));
			synchronized (CACHE_LOCK) {
				cachedServer = server;
				cachedRecipeManager = recipeManager;
				cachedSnapshot = snapshot;
			}
			return snapshot;
		}
		catch (Exception exception) {
			return EMPTY;
		}
	}

	private static void clear() {
		synchronized (CACHE_LOCK) {
			cachedServer = null;
			cachedRecipeManager = null;
			cachedSnapshot = EMPTY;
		}
	}

	record Snapshot(List<RecipeDisplayEntry> entries) {
		Snapshot {
			entries = entries == null ? List.of() : List.copyOf(entries);
		}
	}
}
