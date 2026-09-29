package ai.moeru.airicraft.agent.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

public final class MovementController {
	private static final long STUCK_TICKS = 20L;
	private static final double STUCK_DISTANCE_EPSILON = 0.15D;

	private boolean movingForward;
	private boolean sprinting;
	private boolean jumping;
	private boolean descending;
	private boolean stuck;
	private long movingSinceTick = -1L;
	private Vec3 movementStartPos;
	private Boolean previousAutoJumpValue;

	public void moveForward(Minecraft minecraft, boolean sprint, boolean jump, long tick) {
		if (minecraft == null) {
			return;
		}

		LocalPlayer player = minecraft.player;
		if (player == null) {
			stop(minecraft);
			return;
		}

		if (!movingForward || movingSinceTick < 0L) {
			movingSinceTick = tick;
			movementStartPos = new Vec3(player.getX(), player.getY(), player.getZ());
			stuck = false;
		}

		movingForward = true;
		sprinting = sprint;
		boolean effectiveJump = shouldJump(player, jump);
		jumping = effectiveJump;
		descending = false;
		enableAutoJump(minecraft);

		minecraft.options.keyUp.setDown(true);
		minecraft.options.keyDown.setDown(false);
		minecraft.options.keyLeft.setDown(false);
		minecraft.options.keyRight.setDown(false);
		minecraft.options.keySprint.setDown(sprint);
		minecraft.options.keyJump.setDown(effectiveJump);
		minecraft.options.keyShift.setDown(false);
		player.setSprinting(sprint);
		player.setShiftKeyDown(false);

		updateStuckState(player, tick);
	}

	public void swimUp(Minecraft minecraft, boolean forward, boolean sprint, long tick) {
		swimUp(minecraft, forward, sprint, false, false, false, tick);
	}

	public void swimUp(Minecraft minecraft, boolean forward, boolean sprint, boolean left, boolean right, boolean back, long tick) {
		moveDirectional(minecraft, forward, back, left, right, sprint, true, tick);
	}

	public void moveDirectional(
		Minecraft minecraft,
		boolean forward,
		boolean back,
		boolean left,
		boolean right,
		boolean sprint,
		boolean jump,
		long tick
	) {
		moveDirectional(minecraft, forward, back, left, right, sprint, jump, false, tick);
	}

	public void moveDirectional(
		Minecraft minecraft,
		boolean forward,
		boolean back,
		boolean left,
		boolean right,
		boolean sprint,
		boolean jump,
		boolean descend,
		long tick
	) {
		if (minecraft == null) {
			return;
		}

		LocalPlayer player = minecraft.player;
		if (player == null) {
			stop(minecraft);
			return;
		}

		if (movingSinceTick < 0L) {
			movingSinceTick = tick;
			movementStartPos = new Vec3(player.getX(), player.getY(), player.getZ());
			stuck = false;
		}

		boolean effectiveForward = forward && !back;
		boolean effectiveSprint = effectiveForward && sprint;
		movingForward = effectiveForward;
		sprinting = effectiveSprint;
		jumping = jump;
		descending = descend && !jump;
		enableAutoJump(minecraft);

		minecraft.options.keyUp.setDown(effectiveForward);
		minecraft.options.keyDown.setDown(back);
		minecraft.options.keyLeft.setDown(left && !right);
		minecraft.options.keyRight.setDown(right && !left);
		minecraft.options.keySprint.setDown(effectiveSprint);
		minecraft.options.keyJump.setDown(jump);
		minecraft.options.keyShift.setDown(descending);
		player.setSprinting(effectiveSprint);
		player.setShiftKeyDown(descending);

		updateStuckState(player, tick);
	}

	public void stop(Minecraft minecraft) {
		if (!isControllingMovement()) {
			return;
		}

		movingForward = false;
		sprinting = false;
		jumping = false;
		descending = false;
		stuck = false;
		movingSinceTick = -1L;
		movementStartPos = null;

		if (minecraft == null) {
			return;
		}

		minecraft.options.keyUp.setDown(false);
		minecraft.options.keyDown.setDown(false);
		minecraft.options.keyLeft.setDown(false);
		minecraft.options.keyRight.setDown(false);
		minecraft.options.keyJump.setDown(false);
		minecraft.options.keyShift.setDown(false);
		minecraft.options.keySprint.setDown(false);
		restoreAutoJump(minecraft);
		if (minecraft.player != null) {
			minecraft.player.setSprinting(false);
			minecraft.player.setShiftKeyDown(false);
		}
	}

	public MovementStateSnapshot snapshot() {
		return new MovementStateSnapshot(movingForward, sprinting, jumping, stuck, movingSinceTick);
	}

	private void updateStuckState(LocalPlayer player, long tick) {
		if (movementStartPos == null || movingSinceTick < 0L) {
			stuck = false;
			return;
		}
		if (tick - movingSinceTick < STUCK_TICKS) {
			stuck = false;
			return;
		}

		Vec3 currentPos = new Vec3(player.getX(), player.getY(), player.getZ());
		double movedDistance = currentPos.distanceTo(movementStartPos);
		stuck = movedDistance < STUCK_DISTANCE_EPSILON;
		if (!stuck) {
			movingSinceTick = tick;
			movementStartPos = currentPos;
		}
	}

	private static boolean shouldJump(LocalPlayer player, boolean requestedJump) {
		if (requestedJump) {
			return true;
		}

		if (player.isInWater() || player.isUnderWater()) {
			return true;
		}

		return player.horizontalCollision && player.onGround();
	}

	private boolean isControllingMovement() {
		return movingForward
			|| sprinting
			|| jumping
			|| descending
			|| movingSinceTick >= 0L
			|| movementStartPos != null
			|| previousAutoJumpValue != null;
	}

	private void enableAutoJump(Minecraft minecraft) {
		if (minecraft == null || minecraft.options == null) {
			return;
		}
		if (previousAutoJumpValue == null) {
			previousAutoJumpValue = minecraft.options.autoJump().get();
		}
		minecraft.options.autoJump().set(true);
	}

	private void restoreAutoJump(Minecraft minecraft) {
		if (minecraft == null || minecraft.options == null || previousAutoJumpValue == null) {
			return;
		}
		minecraft.options.autoJump().set(previousAutoJumpValue);
		previousAutoJumpValue = null;
	}
}
