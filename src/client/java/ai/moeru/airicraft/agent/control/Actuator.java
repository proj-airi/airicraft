package ai.moeru.airicraft.agent.control;

import ai.moeru.airicraft.control.Channel;
import ai.moeru.airicraft.control.ChannelIntent;
import ai.moeru.airicraft.control.ControlArbiter;
import ai.moeru.airicraft.control.ControlLease;
import ai.moeru.airicraft.control.Priority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Objects;
import java.util.Set;

/**
 * One owner's hands: hotbar selection, attacking, breaking, and using items, blocks and entities.
 * Every call takes the owner's lease on the hotbar, primary and secondary channels for the current
 * tick, so a stronger holder that acted first this tick keeps them and this call does nothing.
 * Denied calls answer as a refused action ({@code false} or {@link InteractionResult#FAIL}); callers
 * retry on their next tick like any other refusal. This is the only place outside the control plane
 * that calls the interaction manager or writes the selected slot.
 *
 * <p>{@link #holdUse} promotes the lease to a sticky one that holds the use key until
 * {@link #releaseUse}. Client thread only.
 */
public final class Actuator {
	private static final Set<Channel> CHANNELS = Set.of(Channel.HOTBAR, Channel.PRIMARY, Channel.SECONDARY);
	/** True while the interaction manager is called to break for navigation, so edit vetoes can tell path breaking apart. */
	private static boolean breakingForNavigation;

	private final ControlPlane plane;
	private final String owner;
	private final Priority priority;
	private final boolean navigation;
	private ControlLease lease;

	public Actuator(String owner, Priority priority) {
		this(ControlPlane.shared(), owner, priority, false);
	}

	public Actuator(ControlPlane plane, String owner, Priority priority, boolean navigation) {
		this.plane = Objects.requireNonNull(plane, "plane");
		this.owner = Objects.requireNonNull(owner, "owner");
		this.priority = Objects.requireNonNull(priority, "priority");
		this.navigation = navigation;
	}

	/** Whether the current interaction-manager call is navigation clearing its path. Client thread. */
	public static boolean breakingForNavigation() {
		return breakingForNavigation;
	}

	/** Selects a hotbar slot locally; the server learns of it with the next attack, break or use. */
	public boolean selectHotbar(Minecraft minecraft, int slot) {
		if (minecraft == null || minecraft.player == null || !holding()) return false;
		minecraft.player.getInventory().setSelectedSlot(slot);
		return true;
	}

	/** Selects a hotbar slot and tells the server at once. */
	public boolean selectHotbarAndSync(Minecraft minecraft, int slot) {
		if (!selectHotbar(minecraft, slot)) return false;
		if (minecraft.getConnection() != null) minecraft.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
		return true;
	}

	/** Tells the server which slot is selected, after an inventory swap changed what it holds. */
	public boolean syncHotbar(Minecraft minecraft, int slot) {
		if (minecraft == null || !holding()) return false;
		if (minecraft.getConnection() != null) minecraft.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
		return true;
	}

	public boolean startDestroy(Minecraft minecraft, BlockPos pos, Direction side) {
		if (minecraft == null || minecraft.gameMode == null || !holding()) return false;
		return destroy(() -> minecraft.gameMode.startDestroyBlock(pos, side));
	}

	public boolean continueDestroy(Minecraft minecraft, BlockPos pos, Direction side) {
		if (minecraft == null || minecraft.gameMode == null || !holding()) return false;
		return destroy(() -> minecraft.gameMode.continueDestroyBlock(pos, side));
	}

	/** Stops breaking. Releasing is never refused. */
	public void stopDestroy(Minecraft minecraft) {
		if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.stopDestroyBlock();
	}

	public InteractionResult useItemOn(Minecraft minecraft, LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
		if (minecraft == null || minecraft.gameMode == null || !holding()) return InteractionResult.FAIL;
		return minecraft.gameMode.useItemOn(player, hand, hit);
	}

	public InteractionResult useItem(Minecraft minecraft, LocalPlayer player, InteractionHand hand) {
		if (minecraft == null || minecraft.gameMode == null || !holding()) return InteractionResult.FAIL;
		return minecraft.gameMode.useItem(player, hand);
	}

	public boolean attack(Minecraft minecraft, LocalPlayer player, Entity target) {
		if (minecraft == null || minecraft.gameMode == null || !holding()) return false;
		minecraft.gameMode.attack(player, target);
		return true;
	}

	public InteractionResult interact(Minecraft minecraft, LocalPlayer player, Entity target, InteractionHand hand) {
		if (minecraft == null || minecraft.gameMode == null || !holding()) return InteractionResult.FAIL;
		return minecraft.gameMode.interact(player, target, hand);
	}

	/** Holds the use key until {@link #releaseUse}; false when a stronger holder keeps the channel. */
	public boolean holdUse() {
		if (!holding()) return false;
		plane.keep(lease);
		return plane.submit(lease, new ChannelIntent.HoldUse());
	}

	/** Lets go of the use key and this owner's lease. Safe to call without holding. */
	public void releaseUse(Minecraft minecraft) {
		if (lease == null) return;
		ControlLease released = lease;
		lease = null;
		plane.release(minecraft, released);
	}

	private boolean destroy(java.util.function.BooleanSupplier call) {
		boolean previous = breakingForNavigation;
		breakingForNavigation = navigation;
		try {
			return call.getAsBoolean();
		}
		finally {
			breakingForNavigation = previous;
		}
	}

	/** Takes the owner's channels for this tick, or reuses the lease already held; false when refused. */
	private boolean holding() {
		if (lease != null && plane.status(lease) instanceof ControlArbiter.Status.Held) return true;
		if (lease != null) {
			plane.release(null, lease);
			lease = null;
		}
		lease = plane.acquireForTick(owner, priority, CHANNELS);
		return lease != null;
	}
}
