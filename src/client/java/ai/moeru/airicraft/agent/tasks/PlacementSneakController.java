package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

final class PlacementSneakController {
	private boolean ownsSneakKey;

	Preparation prepare(Minecraft minecraft, LocalPlayer player) {
		Preparation preparation = preparation(ownsSneakKey, minecraft.options.keyShift.isDown(), player.isShiftKeyDown());
		if (preparation == Preparation.PRESS_AND_WAIT) {
			minecraft.options.keyShift.setDown(true);
			ownsSneakKey = true;
		}
		return preparation;
	}

	void release(Minecraft minecraft) {
		if (ownsSneakKey && minecraft != null) {
			minecraft.options.keyShift.setDown(false);
		}
		ownsSneakKey = false;
	}

	static Preparation preparation(boolean ownsSneakKey, boolean sneakKeyPressed, boolean playerSneaking) {
		// Navigation release can clear keys after placement first presses them.
		if (ownsSneakKey && !sneakKeyPressed) {
			return Preparation.PRESS_AND_WAIT;
		}
		if (playerSneaking) {
			return Preparation.READY;
		}
		if (ownsSneakKey || sneakKeyPressed) {
			return Preparation.WAITING;
		}
		return Preparation.PRESS_AND_WAIT;
	}

	enum Preparation {
		READY,
		PRESS_AND_WAIT,
		WAITING
	}
}
