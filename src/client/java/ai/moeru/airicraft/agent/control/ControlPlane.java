package ai.moeru.airicraft.agent.control;

import ai.moeru.airicraft.control.Channel;
import ai.moeru.airicraft.control.ChannelIntent;
import ai.moeru.airicraft.control.ControlArbiter;
import ai.moeru.airicraft.control.ControlFrame;
import ai.moeru.airicraft.control.ControlLease;
import ai.moeru.airicraft.control.Priority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Applies leased control to the client. Holders acquire channels from the {@link ControlArbiter},
 * submit intents during their tick, and {@link #tick} writes the merged frame once. Only leased
 * channels are written, so a human's own keys stay theirs.
 *
 * <p>Release is immediate: {@link #release} clears the channels in the calling tick and consumes the
 * release edge, so the frame that follows does not clear them again over another actuator's input.
 * Client thread only.
 */
public final class ControlPlane {
	private final ControlArbiter arbiter = new ControlArbiter();
	private final CameraController camera;

	public ControlPlane(CameraController camera) {
		this.camera = camera;
	}

	public ControlArbiter.Acquisition acquire(String owner, Priority priority, Set<Channel> channels) {
		return arbiter.acquire(owner, priority, channels);
	}

	public ControlArbiter.Status status(ControlLease lease) {
		return arbiter.status(lease);
	}

	public boolean submit(ControlLease lease, ChannelIntent intent) {
		return arbiter.submit(lease, intent);
	}

	public void clear(ControlLease lease, Channel channel) {
		arbiter.clear(lease, channel);
	}

	/** Ends the lease and lets go of its channels now. Safe to call twice or on a revoked lease. */
	public void release(Minecraft minecraft, ControlLease lease) {
		arbiter.release(lease);
		letGo(minecraft, arbiter.takeReleased());
	}

	/** Writes this tick's merged frame. Runs once per client tick, after the actuators have submitted. */
	public void tick(Minecraft minecraft) {
		ControlFrame frame = arbiter.frame();
		letGo(minecraft, frame.released());
		if (frame.held(Channel.LOCOMOTION)) press(minecraft, frame.locomotion());
		ChannelIntent.Look look = frame.look();
		if (look != null) camera.startLookAt(minecraft, new Vec3(look.x(), look.y(), look.z()), look.reason());
		int slot = frame.hotbarSlot();
		if (slot >= 0 && minecraft.player != null && minecraft.player.getInventory().getSelectedSlot() != slot) {
			minecraft.player.getInventory().setSelectedSlot(slot);
		}
	}

	private static void press(Minecraft minecraft, ChannelIntent.Locomotion move) {
		Options options = minecraft.options;
		options.keyUp.setDown(move.forward());
		options.keyDown.setDown(move.back());
		options.keyLeft.setDown(move.left());
		options.keyRight.setDown(move.right());
		options.keyJump.setDown(move.jump());
		options.keyShift.setDown(move.sneak());
		options.keySprint.setDown(move.sprint());
		if (minecraft.player != null) minecraft.player.setSprinting(move.sprint());
	}

	/** Channels whose lease ended. Look and hotbar have nothing to undo: the camera settles and the slot stays. */
	private static void letGo(Minecraft minecraft, Set<Channel> released) {
		if (released.contains(Channel.LOCOMOTION)) press(minecraft, ChannelIntent.Locomotion.NONE);
	}
}
