package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.Airicraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

final class ScreenCloseSafety {
	private ScreenCloseSafety() {
	}

	static void closeHandledScreen(LocalPlayer player, String context) {
		try {
			player.closeContainer();
		}
		catch (RuntimeException exception) {
			Airicraft.LOGGER.warn("Screen close hook failed during {}; continuing task cleanup", context, exception);
		}
	}

	/**
	 * Closes a container left open by earlier work (a playtest's furnace screen stayed open after smelting and
	 * every later break, tower and mine failed interaction_busy for twenty minutes), so a task can use the
	 * player's own inventory. Returns true when it closed one; never with a stack on the cursor, which closing
	 * would drop.
	 */
	static boolean closeLeftoverContainer(LocalPlayer player, String context) {
		if (player.containerMenu == player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) return false;
		closeHandledScreen(player, context);
		return true;
	}

	static void clearScreen(Minecraft minecraft, String context) {
		try {
			minecraft.setScreen(null);
		}
		catch (RuntimeException exception) {
			Airicraft.LOGGER.warn("Screen clear hook failed during {}; continuing task cleanup", context, exception);
		}
	}
}
