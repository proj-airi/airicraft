package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.tasks.MiningToolPreparation;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.MotorIntent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Options;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Applies motor intents to the client: movement keys relative to the current yaw, the shared
 * camera, and block breaking, placing and use through the interaction manager.
 */
public final class MinecraftMotor {
	/** Key threshold for eight-way movement: a component beyond cos(67.5 degrees) presses its key. */
	private static final double KEY_THRESHOLD = 0.38;
	/** True while this motor calls the interaction manager to break, so edit vetoes can tell path breaking apart. */
	private static boolean breakingForNavigation;
	private final CameraController camera;
	private boolean holding;
	private BlockPos breaking;
	private Direction breakingSide;

	public MinecraftMotor(CameraController camera) {
		this.camera = camera;
	}

	/** Applies one tick of intent; returns why an action could not be done, or null. */
	public String apply(Minecraft minecraft, MotorIntent intent) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			release(minecraft);
			return null;
		}
		holding = true;
		double yaw = Math.toRadians(player.getYRot());
		double forward = intent.moveX() * -Math.sin(yaw) + intent.moveZ() * Math.cos(yaw);
		double left = intent.moveX() * Math.cos(yaw) + intent.moveZ() * Math.sin(yaw);
		boolean forwardKey = forward > KEY_THRESHOLD, backKey = forward < -KEY_THRESHOLD;
		boolean sprint = intent.sprint() && forwardKey && !intent.sneak();
		Options options = minecraft.options;
		options.keyUp.setDown(forwardKey);
		options.keyDown.setDown(backKey);
		options.keyLeft.setDown(left > KEY_THRESHOLD);
		options.keyRight.setDown(left < -KEY_THRESHOLD);
		options.keyJump.setDown(intent.jump());
		options.keyShift.setDown(intent.sneak());
		options.keySprint.setDown(sprint);
		player.setSprinting(sprint);
		if (intent.look() != null) {
			camera.startLookAt(minecraft, new Vec3(intent.look().x(), intent.look().y(), intent.look().z()), "navigation");
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

	/** Lets go of every key this motor pressed. Idempotent. */
	public void release(Minecraft minecraft) {
		if (!holding) return;
		holding = false;
		stopBreaking(minecraft);
		Options options = minecraft.options;
		options.keyUp.setDown(false);
		options.keyDown.setDown(false);
		options.keyLeft.setDown(false);
		options.keyRight.setDown(false);
		options.keyJump.setDown(false);
		options.keyShift.setDown(false);
		options.keySprint.setDown(false);
		if (minecraft.player != null) minecraft.player.setSprinting(false);
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
			MiningToolPreparation.Result tool = MiningToolPreparation.ensureSelectedForClearance(minecraft, player, List.of(state));
			if (!tool.ok()) return tool.message();
			Direction side = facing(player.getEyePosition(), pos);
			breakingForNavigation = true;
			try {
				if (!minecraft.gameMode.startDestroyBlock(pos, side)) return "break_refused " + pos.toShortString();
			}
			finally {
				breakingForNavigation = false;
			}
			breaking = pos.immutable();
			breakingSide = side;
		}
		breakingForNavigation = true;
		try {
			minecraft.gameMode.continueDestroyBlock(pos, breakingSide);
		}
		finally {
			breakingForNavigation = false;
		}
		player.swing(InteractionHand.MAIN_HAND);
		return null;
	}

	/** Whether the current interaction-manager call is navigation clearing its path. Client thread. */
	public static boolean breakingForNavigation() {
		return breakingForNavigation;
	}

	private String place(Minecraft minecraft, LocalPlayer player, BlockPos target, BlockPos support) {
		if (!minecraft.level.getBlockState(target).canBeReplaced()) return null;
		if (!selectThrowaway(minecraft, player)) return "missing_throwaway_block";
		Direction side = direction(support, target);
		if (side == null) return "placement_not_adjacent " + target.toShortString();
		Vec3 hit = Vec3.atCenterOf(support).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
		InteractionResult result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, side, support, false));
		if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
		return null;
	}

	private String use(Minecraft minecraft, LocalPlayer player, BlockPos pos) {
		Direction side = facing(player.getEyePosition(), pos);
		Vec3 hit = Vec3.atCenterOf(pos).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
		InteractionResult result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, side, pos, false));
		if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
		return null;
	}

	/** Puts a throwaway block in the main hand, from the hotbar or the main inventory. */
	private static boolean selectThrowaway(Minecraft minecraft, LocalPlayer player) {
		var inventory = player.getInventory();
		if (NavigationPolicies.isThrowaway(inventory.getSelectedItem())) return true;
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
			return false;
		}
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!NavigationPolicies.isThrowaway(stack)) continue;
			int selected = inventory.getSelectedSlot();
			if (slot < 9) {
				selected = slot;
				inventory.setSelectedSlot(selected);
			}
			else {
				// Main inventory indices 9..35 equal the player screen's slot IDs.
				minecraft.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, selected, ClickType.SWAP, player);
			}
			if (minecraft.getConnection() != null) minecraft.getConnection().send(new ServerboundSetCarriedItemPacket(selected));
			return NavigationPolicies.isThrowaway(inventory.getSelectedItem());
		}
		return false;
	}

	private void stopBreaking(Minecraft minecraft) {
		if (breaking != null && minecraft.gameMode != null) minecraft.gameMode.stopDestroyBlock();
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
