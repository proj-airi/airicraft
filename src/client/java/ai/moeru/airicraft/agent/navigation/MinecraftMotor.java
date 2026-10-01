package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.ControlPlane;
import ai.moeru.airicraft.agent.control.MovementScreenCloser;
import ai.moeru.airicraft.agent.tasks.MiningToolPreparation;
import ai.moeru.airicraft.control.Channel;
import ai.moeru.airicraft.control.ChannelIntent;
import ai.moeru.airicraft.control.ControlArbiter;
import ai.moeru.airicraft.control.ControlLease;
import ai.moeru.airicraft.control.Priority;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.MotorIntent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Applies motor intents to the client: movement and look go through a foreground lease on the
 * control plane, block breaking, placing and use through the interaction manager.
 */
public final class MinecraftMotor {
	/** Key threshold for eight-way movement: a component beyond cos(67.5 degrees) presses its key. */
	private static final double KEY_THRESHOLD = 0.38;
	private static final Set<Channel> CHANNELS = Set.of(Channel.LOCOMOTION, Channel.LOOK);
	private final ControlPlane plane;
	private final Actuator actuator;
	private ControlLease lease;
	private BlockPos breaking;
	private Direction breakingSide;

	public MinecraftMotor(ControlPlane plane) {
		this.plane = plane;
		this.actuator = new Actuator(plane, "navigation", Priority.FOREGROUND, true);
	}

	/** Applies one tick of intent; returns why an action could not be done, or null. */
	public String apply(Minecraft minecraft, MotorIntent intent) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			release(minecraft);
			return null;
		}
		if (!holdControl()) return "control_preempted";
		double yaw = Math.toRadians(player.getYRot());
		double forward = intent.moveX() * -Math.sin(yaw) + intent.moveZ() * Math.cos(yaw);
		double left = intent.moveX() * Math.cos(yaw) + intent.moveZ() * Math.sin(yaw);
		MovementScreenCloser.closeIfMoving(minecraft, Math.abs(forward) > KEY_THRESHOLD || Math.abs(left) > KEY_THRESHOLD
		|| intent.swim() || intent.jump() || intent.sneak() && player.isInWater());
		if (intent.swim()) {
			// Sprint-swimming goes where the camera points, pitch included: forward is the only key.
			plane.submit(lease, new ChannelIntent.Locomotion(true, false, false, false, false, false, true));
		}
		else {
			plane.submit(lease, new ChannelIntent.Locomotion(forward > KEY_THRESHOLD, forward < -KEY_THRESHOLD,
				left > KEY_THRESHOLD, left < -KEY_THRESHOLD, intent.jump(), intent.sneak(), intent.sprint()));
		}
		if (intent.look() != null) {
			plane.submit(lease, new ChannelIntent.Look(intent.look().x(), intent.look().y(), intent.look().z(), "navigation"));
		}
		else {
			plane.clear(lease, Channel.LOOK);
		}

		MotorIntent.Action action = intent.action();
		if (!(action instanceof MotorIntent.Break) && breaking != null) stopBreaking(minecraft);
		if (action == null || minecraft.gameMode == null || minecraft.level == null) return null;
		return switch (action) {
			case MotorIntent.Break mining -> mine(minecraft, player, pos(mining.pos()));
			case MotorIntent.Place place -> place(minecraft, player, pos(place.pos()), pos(place.against()));
			case MotorIntent.Use use -> use(minecraft, player, pos(use.pos()));
		};
	}

	/** Lets go of every key this motor pressed, in this call. Idempotent. */
	public void release(Minecraft minecraft) {
		if (lease == null) return;
		ControlLease released = lease;
		lease = null;
		stopBreaking(minecraft);
		plane.release(minecraft, released);
	}

	/**
	 * Takes the lease on first use and again after a revocation, once whoever revoked it has let go.
	 * False while a holder of higher or equal priority has the channels: the route pauses, it does not fail.
	 */
	public boolean holdControl() {
		if (lease != null) {
			ControlArbiter.Status status = plane.status(lease);
			if (status instanceof ControlArbiter.Status.Held) return true;
			if (status instanceof ControlArbiter.Status.Revoked revoked
				&& plane.status(revoked.by()) instanceof ControlArbiter.Status.Held) return false;
			plane.release(null, lease);
			lease = null;
		}
		if (!(plane.acquire("navigation", Priority.FOREGROUND, CHANNELS) instanceof ControlArbiter.Acquisition.Granted granted)) {
			return false;
		}
		lease = granted.lease();
		return true;
	}

	public BlockPos breaking() {
		return breaking;
	}

	private String mine(Minecraft minecraft, LocalPlayer player, BlockPos pos) {
		BlockState state = minecraft.level.getBlockState(pos);
		if (state.isAir()) {
			stopBreaking(minecraft);
			return null;
		}
		if (!pos.equals(breaking)) {
			stopBreaking(minecraft);
			MiningToolPreparation.Result tool = MiningToolPreparation.ensureSelectedForClearance(minecraft, actuator, player, List.of(state),
				PathfindSettings.current().allowInventoryToolSwap());
			if (!tool.ok()) return tool.message();
			Direction side = facing(player.getEyePosition(), pos);
			if (!actuator.startDestroy(minecraft, pos, side)) return "break_refused " + pos.toShortString();
			breaking = pos.immutable();
			breakingSide = side;
		}
		actuator.continueDestroy(minecraft, pos, breakingSide);
		player.swing(InteractionHand.MAIN_HAND);
		return null;
	}

	private String place(Minecraft minecraft, LocalPlayer player, BlockPos target, BlockPos support) {
		if (!minecraft.level.getBlockState(target).canBeReplaced()) return null;
		if (!selectThrowaway(minecraft, player)) return "missing_throwaway_block";
		Direction side = direction(support, target);
		if (side == null) return "placement_not_adjacent " + target.toShortString();
		Vec3 hit = Vec3.atCenterOf(support).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
		InteractionResult result = actuator.useItemOn(minecraft, player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, side, support, false));
		if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
		return null;
	}

	private String use(Minecraft minecraft, LocalPlayer player, BlockPos pos) {
		Direction side = facing(player.getEyePosition(), pos);
		Vec3 hit = Vec3.atCenterOf(pos).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
		InteractionResult result = actuator.useItemOn(minecraft, player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, side, pos, false));
		if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
		return null;
	}

	/** Puts a throwaway block in the main hand, from the hotbar or the main inventory. */
	private boolean selectThrowaway(Minecraft minecraft, LocalPlayer player) {
		var inventory = player.getInventory();
		if (NavigationPolicies.isThrowaway(inventory.getSelectedItem())) return true;
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
			return false;
		}
		boolean allowInventory = PathfindSettings.current().allowInventoryToolSwap();
		for (int slot = 0; slot < (allowInventory ? 36 : 9); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!NavigationPolicies.isThrowaway(stack)) continue;
			int selected = inventory.getSelectedSlot();
			if (slot < 9) {
				selected = slot;
				if (!actuator.selectHotbarAndSync(minecraft, selected)) return false;
			}
			else {
				// Main inventory indices 9..35 equal the player screen's slot IDs.
				minecraft.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, selected, ClickType.SWAP, player);
				if (!actuator.syncHotbar(minecraft, selected)) return false;
			}
			return NavigationPolicies.isThrowaway(inventory.getSelectedItem());
		}
		return false;
	}

	private void stopBreaking(Minecraft minecraft) {
		if (breaking != null) actuator.stopDestroy(minecraft);
		breaking = null;
		breakingSide = null;
	}

	/** The block face pointing toward the eye. */
	private static Direction facing(Vec3 eye, BlockPos pos) {
		double dx = eye.x - (pos.getX() + 0.5), dy = eye.y - (pos.getY() + 0.5), dz = eye.z - (pos.getZ() + 0.5);
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
		if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	private static Direction direction(BlockPos from, BlockPos to) {
		int dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
		if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) return null;
		if (dy == 1) return Direction.UP;
		if (dy == -1) return Direction.DOWN;
		if (dx == 1) return Direction.EAST;
		if (dx == -1) return Direction.WEST;
		return dz == 1 ? Direction.SOUTH : Direction.NORTH;
	}

	private static BlockPos pos(GridPos cell) {
		return new BlockPos(cell.x(), cell.y(), cell.z());
	}
}
