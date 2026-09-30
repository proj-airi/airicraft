package ai.moeru.airicraft.agent.control;

import ai.moeru.airicraft.control.Channel;
import ai.moeru.airicraft.control.ChannelIntent;
import ai.moeru.airicraft.control.ControlArbiter;
import ai.moeru.airicraft.control.ControlLease;
import ai.moeru.airicraft.control.Priority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Set;

/**
 * Turns an executor's movement calls into locomotion intents on the {@link ControlPlane} and tracks
 * whether the player is stuck. It writes no key bindings and no client options itself: the plane does,
 * and only while this controller holds the locomotion lease.
 *
 * <p>Priority decides contention. A stronger holder (the reflex over a foreground executor) keeps the
 * channel, and this controller's calls do nothing until it lets go. Equal priority takes over, as
 * "last writer wins" did before leases.
 */
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
	private final ControlPlane plane;
	private final String owner;
	private final Priority priority;
	private ControlLease lease;

	public MovementController() {
		this("movement", Priority.FOREGROUND);
	}

	public MovementController(String owner, Priority priority) {
		this(ControlPlane.shared(), owner, priority);
	}

	public MovementController(ControlPlane plane, String owner, Priority priority) {
		this.plane = Objects.requireNonNull(plane, "plane");
		this.owner = Objects.requireNonNull(owner, "owner");
		this.priority = Objects.requireNonNull(priority, "priority");
	}

	public void moveForward(Minecraft minecraft, boolean sprint, boolean jump, long tick) {
		if (minecraft == null) {
			return;
		}

		LocalPlayer player = minecraft.player;
		if (player == null) {
			stop(minecraft);
			return;
		}

		MovementScreenCloser.closeIfMoving(minecraft, true);
		if (!movingForward || movingSinceTick < 0L) {
			movingSinceTick = tick;
			movementStartPos = new Vec3(player.getX(), player.getY(), player.getZ());
			stuck = false;
		}

		boolean effectiveJump = shouldJump(player, jump);
		if (!drive(new ChannelIntent.Locomotion(true, false, false, false, effectiveJump, false, sprint, true))) return;
		movingForward = true;
		sprinting = sprint;
		jumping = effectiveJump;
		descending = false;

		updateStuckState(player, tick);
	}

	/**
	 * Holds jump and/or sneak with nothing else pressed, as towering and placing need. It is not walking, so
	 * it does not feed stuck detection. {@link #stop} lets go.
	 */
	public void hold(Minecraft minecraft, boolean jump, boolean sneak) {
		if (minecraft == null || minecraft.player == null) {
			return;
		}
		drive(new ChannelIntent.Locomotion(false, false, false, false, jump, sneak, false, false));
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

		MovementScreenCloser.closeIfMoving(minecraft, forward || back || left != right || jump || descend);
		if (movingSinceTick < 0L) {
			movingSinceTick = tick;
			movementStartPos = new Vec3(player.getX(), player.getY(), player.getZ());
			stuck = false;
		}

		boolean effectiveForward = forward && !back;
		boolean effectiveSprint = effectiveForward && sprint;
		boolean effectiveDescend = descend && !jump;
		if (!drive(new ChannelIntent.Locomotion(effectiveForward, back, left && !right, right && !left, jump,
			effectiveDescend, effectiveSprint, true))) return;
		movingForward = effectiveForward;
		sprinting = effectiveSprint;
		jumping = jump;
		descending = effectiveDescend;

		updateStuckState(player, tick);
	}

	public void stop(Minecraft minecraft) {
		if (!isControllingMovement()) {
			return;
		}

		forget();
		if (lease != null) {
			ControlLease released = lease;
			lease = null;
			plane.release(minecraft, released);
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
			|| lease != null;
	}

	/** Submits this tick's keys; false when a stronger holder keeps locomotion, so nothing moved. */
	private boolean drive(ChannelIntent.Locomotion intent) {
		if (lease != null && !(plane.status(lease) instanceof ControlArbiter.Status.Held)) {
			// Revoked: acknowledge it, then contend again. An equal holder is taken over, a stronger one refuses.
			plane.release(null, lease);
			lease = null;
		}
		if (lease == null) {
			if (!(plane.acquire(owner, priority, Set.of(Channel.LOCOMOTION)) instanceof ControlArbiter.Acquisition.Granted granted)) {
				forget();
				return false;
			}
			lease = granted.lease();
		}
		return plane.submit(lease, intent);
	}

	private void forget() {
		movingForward = false;
		sprinting = false;
		jumping = false;
		descending = false;
		stuck = false;
		movingSinceTick = -1L;
		movementStartPos = null;
	}
}
