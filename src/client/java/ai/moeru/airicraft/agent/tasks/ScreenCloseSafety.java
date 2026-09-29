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

	static void clearScreen(Minecraft minecraft, String context) {
		try {
			minecraft.setScreen(null);
		}
		catch (RuntimeException exception) {
			Airicraft.LOGGER.warn("Screen clear hook failed during {}; continuing task cleanup", context, exception);
		}
	}
}
