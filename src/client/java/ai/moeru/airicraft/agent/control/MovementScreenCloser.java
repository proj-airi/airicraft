package ai.moeru.airicraft.agent.control;

import ai.moeru.airicraft.AiricraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractFurnaceMenu;

/** Closes container UI at the movement boundary, never merely because a task is running. */
public final class MovementScreenCloser {
	private MovementScreenCloser() {
	}

	public static void closeIfMoving(Minecraft client, boolean movementRequested) {
		if (!movementRequested || client == null || client.player == null || client.level == null
			|| !(client.screen instanceof AbstractContainerScreen<?>)) return;
		var player = client.player;
		boolean screenRequired = player.containerMenu instanceof AbstractFurnaceMenu
			&& AiricraftClient.runtimeController().agentRuntime().requiresOpenSmeltingScreen(
				client.level.dimension().location().toString(), player.containerMenu.containerId);
		closeIfMoving(movementRequested, !screenRequired,
			player.containerMenu.getCarried().isEmpty(), player::closeContainer);
	}

	static void closeIfMoving(boolean movementRequested, boolean screenMayClose, boolean cursorEmpty, Runnable closeScreen) {
		// Closing during slot transfers can drop the cursor stack. Retry on the next movement tick.
		if (movementRequested && screenMayClose && cursorEmpty) closeScreen.run();
	}
}
