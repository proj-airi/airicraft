package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.control.Priority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

final class PlacementSneakController {
	private final MovementController movement = new MovementController("placement_sneak", Priority.FOREGROUND);
	private boolean ownsSneakKey;

	Preparation prepare(Minecraft minecraft, LocalPlayer player) {
		Preparation preparation = preparation(ownsSneakKey, minecraft.options.keyShift.isDown(), player.isShiftKeyDown());
		if (preparation == Preparation.PRESS_AND_WAIT) {
			movement.hold(minecraft, false, true);
			ownsSneakKey = true;
		}
		return preparation;
	}

	void release(Minecraft minecraft) {
		if (ownsSneakKey) {
			movement.stop(minecraft);
		}
		ownsSneakKey = false;
	}

	static Preparation preparation(boolean ownsSneakKey, boolean sneakKeyPressed, boolean playerSneaking) {
		// Another locomotion owner can take the channel after placement first holds sneak.
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
