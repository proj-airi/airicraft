package ai.moeru.airicraft;

import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * Holds a singleplayer game while the planner is degraded by provider failures, so the world does not move on
 * without the companion. The integrated server's tick rate is frozen and client game ticks are gated, as for an
 * empty hosted playtest; the planner's wall-clock cooldown still runs, and the game resumes when it ends (or on
 * '@agent reset'). A world with other players is never held.
 */
public final class ProviderOutagePause {
	private volatile boolean paused;
	private volatile boolean frozeServer;

	public boolean paused() { return paused; }

	/** Called after each normal client tick. */
	public void onClientTick(Minecraft minecraft, EmbodiedAgentRuntime runtime) {
		if (!paused && shouldHold(minecraft, runtime)) pause(minecraft, runtime);
	}

	/** Called instead of a client game tick while held; returns whether the game stays held. */
	public boolean maintain(Minecraft minecraft, EmbodiedAgentRuntime runtime) {
		if (!paused) return false;
		runtime.pollProviderRecovery();
		if (!shouldHold(minecraft, runtime)) resume(minecraft, runtime.isDegraded() ? "no_longer_eligible" : "degraded_cleared");
		return paused;
	}

	static boolean eligible(boolean singleplayer, int playerCount, boolean degraded, boolean plannerEnabled, boolean externalDriver) {
		return singleplayer && playerCount <= 1 && degraded && plannerEnabled && !externalDriver;
	}

	private static boolean shouldHold(Minecraft minecraft, EmbodiedAgentRuntime runtime) {
		MinecraftServer server = minecraft.getSingleplayerServer();
		return minecraft.level != null && minecraft.player != null && eligible(server != null,
			server == null ? 0 : server.getPlayerCount(), runtime.isDegraded(), runtime.plannerEnabled(), runtime.codexDriverActive());
	}

	private void pause(Minecraft minecraft, EmbodiedAgentRuntime runtime) {
		MinecraftServer server = minecraft.getSingleplayerServer();
		paused = true;
		server.execute(() -> {
			// Leave a freeze someone else set (the /tick command, an empty hosted playtest) to its owner.
			if (paused && !server.tickRateManager().isFrozen()) {
				server.tickRateManager().setFrozen(true);
				frozeServer = true;
			}
		});
		Airicraft.LOGGER.info("Provider outage pause: holding the game while the planner is degraded (outage {} ms)",
			runtime.providerOutageMillis());
	}

	private void resume(Minecraft minecraft, String reason) {
		paused = false;
		MinecraftServer server = minecraft.getSingleplayerServer();
		if (server != null) server.execute(() -> {
			if (frozeServer && !paused) server.tickRateManager().setFrozen(false);
			frozeServer = false;
		});
		else frozeServer = false;
		Airicraft.LOGGER.info("Provider outage pause: resuming the game ({})", reason);
	}
}
