package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.control.Priority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;

final class PlacementSneakController {
	private final MovementController movement = new MovementController("placement_sneak", Priority.FOREGROUND);
	private boolean ownsSneakKey;

	Preparation prepare(Minecraft minecraft, LocalPlayer player) {
		boolean ordinaryCrouchMode = !player.getAbilities().flying && !player.isSwimming()
			&& !player.isFallFlying() && !player.isAutoSpinAttack() && !player.isSleeping();
		// Minecraft keeps SWIMMING for a forced crawl when even crouching would collide.
		boolean crouchFits = !ordinaryCrouchMode || player.getPose() != Pose.SWIMMING
			|| player.level().noCollision(player,
				player.getDimensions(Pose.CROUCHING).makeBoundingBox(player.position()).deflate(1.0E-7D));
		Preparation preparation = preparation(ownsSneakKey, minecraft.options.keyShift.isDown(),
			player.isShiftKeyDown(), poseReady(player.getPose(), ordinaryCrouchMode, crouchFits));
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

	static Preparation preparation(boolean ownsSneakKey, boolean sneakKeyPressed, boolean playerSneaking, boolean poseReady) {
		// Another locomotion owner can take the channel after placement first holds sneak.
		if (ownsSneakKey && !sneakKeyPressed) {
			return Preparation.PRESS_AND_WAIT;
		}
		// The control plane can set the shift flag before Minecraft updates the pose/eye height.
		if (sneakKeyPressed && playerSneaking && poseReady) {
			return Preparation.READY;
		}
		if (ownsSneakKey || sneakKeyPressed) {
			return Preparation.WAITING;
		}
		return Preparation.PRESS_AND_WAIT;
	}

	static boolean poseReady(Pose currentPose, boolean ordinaryCrouchMode, boolean crouchFits) {
		return currentPose == Pose.CROUCHING || !ordinaryCrouchMode
			|| (currentPose == Pose.SWIMMING && !crouchFits);
	}

	enum Preparation {
		READY,
		PRESS_AND_WAIT,
		WAITING
	}
}
