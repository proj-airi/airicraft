package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.tasks.MiningToolPreparation;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.MotorIntent;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

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
	public String apply(MinecraftClient client, MotorIntent intent) {
		ClientPlayerEntity player = client.player;
		if (player == null) {
			release(client);
			return null;
		}
		holding = true;
		double yaw = Math.toRadians(player.getYaw());
		double forward = intent.moveX() * -Math.sin(yaw) + intent.moveZ() * Math.cos(yaw);
		double left = intent.moveX() * Math.cos(yaw) + intent.moveZ() * Math.sin(yaw);
		boolean forwardKey = forward > KEY_THRESHOLD, backKey = forward < -KEY_THRESHOLD;
		boolean sprint = intent.sprint() && forwardKey && !intent.sneak();
		GameOptions options = client.options;
		options.forwardKey.setPressed(forwardKey);
		options.backKey.setPressed(backKey);
		options.leftKey.setPressed(left > KEY_THRESHOLD);
		options.rightKey.setPressed(left < -KEY_THRESHOLD);
		options.jumpKey.setPressed(intent.jump());
		options.sneakKey.setPressed(intent.sneak());
		options.sprintKey.setPressed(sprint);
		player.setSprinting(sprint);
		if (intent.look() != null) {
			camera.startLookAt(client, new Vec3d(intent.look().x(), intent.look().y(), intent.look().z()), "navigation");
		}

		MotorIntent.Action action = intent.action();
		if (!(action instanceof MotorIntent.Break) && breaking != null) stopBreaking(client);
		if (action == null || client.interactionManager == null || client.world == null) return null;
		return switch (action) {
			case MotorIntent.Break mining -> mine(client, player, pos(mining.pos()));
			case MotorIntent.Place place -> place(client, player, pos(place.pos()), pos(place.against()));
			case MotorIntent.Use use -> use(client, player, pos(use.pos()));
		};
	}

	/** Lets go of every key this motor pressed. Idempotent. */
	public void release(MinecraftClient client) {
		if (!holding) return;
		holding = false;
		stopBreaking(client);
		GameOptions options = client.options;
		options.forwardKey.setPressed(false);
		options.backKey.setPressed(false);
		options.leftKey.setPressed(false);
		options.rightKey.setPressed(false);
		options.jumpKey.setPressed(false);
		options.sneakKey.setPressed(false);
		options.sprintKey.setPressed(false);
		if (client.player != null) client.player.setSprinting(false);
	}

	public BlockPos breaking() {
		return breaking;
	}

	private String mine(MinecraftClient client, ClientPlayerEntity player, BlockPos pos) {
		BlockState state = client.world.getBlockState(pos);
		if (state.isAir()) {
			stopBreaking(client);
			return null;
		}
		if (!pos.equals(breaking)) {
			stopBreaking(client);
			MiningToolPreparation.Result tool = MiningToolPreparation.ensureSelectedForClearance(client, player, List.of(state));
			if (!tool.ok()) return tool.message();
			Direction side = facing(player.getEyePos(), pos);
			breakingForNavigation = true;
			try {
				if (!client.interactionManager.attackBlock(pos, side)) return "break_refused " + pos.toShortString();
			}
			finally {
				breakingForNavigation = false;
			}
			breaking = pos.toImmutable();
			breakingSide = side;
		}
		breakingForNavigation = true;
		try {
			client.interactionManager.updateBlockBreakingProgress(pos, breakingSide);
		}
		finally {
			breakingForNavigation = false;
		}
		player.swingHand(Hand.MAIN_HAND);
		return null;
	}

	/** Whether the current interaction-manager call is navigation clearing its path. Client thread. */
	public static boolean breakingForNavigation() {
		return breakingForNavigation;
	}

	private String place(MinecraftClient client, ClientPlayerEntity player, BlockPos target, BlockPos support) {
		if (!client.world.getBlockState(target).isReplaceable()) return null;
		if (!selectThrowaway(client, player)) return "missing_throwaway_block";
		Direction side = direction(support, target);
		if (side == null) return "placement_not_adjacent " + target.toShortString();
		Vec3d hit = Vec3d.ofCenter(support).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, new BlockHitResult(hit, side, support, false));
		if (result.isAccepted()) player.swingHand(Hand.MAIN_HAND);
		return null;
	}

	private String use(MinecraftClient client, ClientPlayerEntity player, BlockPos pos) {
		Direction side = facing(player.getEyePos(), pos);
		Vec3d hit = Vec3d.ofCenter(pos).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, new BlockHitResult(hit, side, pos, false));
		if (result.isAccepted()) player.swingHand(Hand.MAIN_HAND);
		return null;
	}

	/** Puts a throwaway block in the main hand, from the hotbar or the main inventory. */
	private static boolean selectThrowaway(MinecraftClient client, ClientPlayerEntity player) {
		var inventory = player.getInventory();
		if (NavigationPolicies.isThrowaway(inventory.getSelectedStack())) return true;
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			return false;
		}
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (!NavigationPolicies.isThrowaway(stack)) continue;
			int selected = inventory.getSelectedSlot();
			if (slot < 9) {
				selected = slot;
				inventory.setSelectedSlot(selected);
			}
			else {
				// Main inventory indices 9..35 equal the player screen's slot IDs.
				client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot, selected, SlotActionType.SWAP, player);
			}
			if (client.getNetworkHandler() != null) client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(selected));
			return NavigationPolicies.isThrowaway(inventory.getSelectedStack());
		}
		return false;
	}

	private void stopBreaking(MinecraftClient client) {
		if (breaking != null && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
		breaking = null;
		breakingSide = null;
	}

	/** The block face pointing toward the eye. */
	private static Direction facing(Vec3d eye, BlockPos pos) {
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
