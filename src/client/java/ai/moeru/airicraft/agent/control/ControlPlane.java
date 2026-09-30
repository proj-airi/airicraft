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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Applies leased control to the client. Holders acquire channels from the {@link ControlArbiter},
 * submit intents during their tick, and {@link #tick} writes the merged frame once. Only leased
 * channels are written, so a human's own keys stay theirs.
 *
 * <p>Release is immediate: {@link #release} clears the channels in the calling tick and consumes the
 * release edge, so the frame that follows does not clear them again over another actuator's input.
 *
 * <p>The plane also owns the client's auto-jump option while a holder wants the assist: it saves the
 * player's setting once and restores it when locomotion is no longer leased with the assist on.
 * Client thread only.
 */
public final class ControlPlane {
	private static final ControlPlane SHARED = new ControlPlane();

	private final ControlArbiter arbiter = new ControlArbiter();
	/** Leases that end with the current tick unless kept: one-shot actions do not have to remember to release. */
	private final List<ControlLease> tickLeases = new ArrayList<>();
	private CameraController camera;
	private Boolean savedAutoJump;

	public ControlPlane() {
		this(null);
	}

	public ControlPlane(CameraController camera) {
		this.camera = camera;
	}

	/**
	 * The plane of this client. There is one player, so there is one actuation boundary; holders
	 * that are not handed a plane use this one. {@link #bind} attaches the camera it aims with.
	 */
	public static ControlPlane shared() {
		return SHARED;
	}

	/** Sets the camera that look intents aim through. */
	public ControlPlane bind(CameraController camera) {
		this.camera = camera;
		return this;
	}

	public ControlArbiter.Acquisition acquire(String owner, Priority priority, Set<Channel> channels) {
		return arbiter.acquire(owner, priority, channels);
	}

	/**
	 * A lease that ends when the current tick's frame is applied, for actions that finish within the tick.
	 *
	 * @return the lease, or null when a stronger holder has one of the channels
	 */
	public ControlLease acquireForTick(String owner, Priority priority, Set<Channel> channels) {
		if (!(arbiter.acquire(owner, priority, channels) instanceof ControlArbiter.Acquisition.Granted granted)) return null;
		tickLeases.add(granted.lease());
		return granted.lease();
	}

	/** Makes a tick-scoped lease last until it is released, as a held key needs. */
	public void keep(ControlLease lease) {
		tickLeases.remove(lease);
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

	/**
	 * Ends the lease and lets go of its channels now. Safe to call twice or on a revoked lease, which
	 * clears nothing: the channels already belong to whoever revoked it. Without a client the release
	 * edge is kept for the next frame.
	 */
	public void release(Minecraft minecraft, ControlLease lease) {
		tickLeases.remove(lease);
		arbiter.release(lease);
		if (minecraft != null) letGo(minecraft, arbiter.takeReleased());
	}

	/** Writes this tick's merged frame. Runs once per client tick, after the actuators have submitted. */
	public void tick(Minecraft minecraft) {
		ControlFrame frame = arbiter.frame();
		letGo(minecraft, frame.released());
		if (frame.held(Channel.LOCOMOTION)) press(minecraft, frame.locomotion());
		ChannelIntent.Look look = frame.look();
		// Navigation aims lowest: a reflex, executor or tool that aimed this tick keeps the camera.
		if (look != null && camera != null && !camera.aimedThisTick()) camera.startLookAt(minecraft, new Vec3(look.x(), look.y(), look.z()), look.reason());
		int slot = frame.hotbarSlot();
		if (slot >= 0 && minecraft.player != null && minecraft.player.getInventory().getSelectedSlot() != slot) {
			minecraft.player.getInventory().setSelectedSlot(slot);
		}
		if (frame.useHeld()) minecraft.options.keyUse.setDown(true);
		// One-shot actions are over: drop their leases without side effects.
		for (ControlLease lease : tickLeases) arbiter.release(lease);
		tickLeases.clear();
		arbiter.takeReleased();
	}

	private void press(Minecraft minecraft, ChannelIntent.Locomotion move) {
		Options options = minecraft.options;
		autoJump(options, move.autoJump());
		options.keyUp.setDown(move.forward());
		options.keyDown.setDown(move.back());
		options.keyLeft.setDown(move.left());
		options.keyRight.setDown(move.right());
		options.keyJump.setDown(move.jump());
		options.keyShift.setDown(move.sneak());
		options.keySprint.setDown(move.sprint());
		if (minecraft.player != null) {
			minecraft.player.setSprinting(move.sprint());
			minecraft.player.setShiftKeyDown(move.sneak());
		}
	}

	private void autoJump(Options options, boolean wanted) {
		if (wanted) {
			if (savedAutoJump == null) savedAutoJump = options.autoJump().get();
			options.autoJump().set(true);
		}
		else if (savedAutoJump != null) {
			options.autoJump().set(savedAutoJump);
			savedAutoJump = null;
		}
	}

	/** Channels whose lease ended. Look and hotbar have nothing to undo: the camera settles and the slot stays. */
	private void letGo(Minecraft minecraft, Set<Channel> released) {
		if (released.contains(Channel.LOCOMOTION)) press(minecraft, ChannelIntent.Locomotion.NONE);
		if (released.contains(Channel.SECONDARY)) minecraft.options.keyUse.setDown(false);
	}
}
