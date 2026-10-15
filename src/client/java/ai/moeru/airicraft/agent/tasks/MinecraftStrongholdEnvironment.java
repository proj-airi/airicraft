package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.control.Priority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads eyes of ender from the client world and throws them through the foreground lease. The server spawns a thrown eye
 * at the player and moves it toward the stronghold; the client sees its position through ordinary entity tracking.
 */
final class MinecraftStrongholdEnvironment implements LocateStrongholdTaskExecutor.Environment {
	/** Wide enough that a slow camera still counts as aimed; the eye's flight does not depend on the aim. */
	private static final float AIM_TOLERANCE_DEGREES = 5.0F;
	private final Actuator actuator = new Actuator("locate_stronghold", Priority.FOREGROUND);
	private final CameraController camera;
	/** Where the camera looks for this throw: up into the sky, so the use never lands on an end portal frame. */
	private Vec3 aim;

	MinecraftStrongholdEnvironment(CameraController camera) {
		this.camera = java.util.Objects.requireNonNull(camera, "camera");
	}

	private static Minecraft client() { return Minecraft.getInstance(); }

	@Override
	public LocateStrongholdTaskExecutor.Body observe() {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return null;
		return new LocateStrongholdTaskExecutor.Body(player.position(), player.getHealth(), eyes(player), level.dimension() == Level.OVERWORLD);
	}

	@Override
	public Set<Integer> eyesNear(Vec3 point, double radius) {
		ClientLevel level = client().level;
		if (level == null || point == null) return Set.of();
		return level.getEntitiesOfClass(EyeOfEnder.class, new AABB(point, point).inflate(radius), Entity::isAlive).stream()
			.map(Entity::getId).collect(Collectors.toUnmodifiableSet());
	}

	@Override
	public Optional<Vec3> eye(int entityId) {
		ClientLevel level = client().level;
		if (level == null) return Optional.empty();
		return level.getEntity(entityId) instanceof EyeOfEnder eye && eye.isAlive() && !eye.isRemoved()
			? Optional.of(eye.position()) : Optional.empty();
	}

	@Override
	public LocateStrongholdTaskExecutor.ThrowResult throwEye() {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) return LocateStrongholdTaskExecutor.ThrowResult.WORKING;
		if (slot(player) < 0) return LocateStrongholdTaskExecutor.ThrowResult.NO_EYE;
		if (!hold(minecraft, player)) return LocateStrongholdTaskExecutor.ThrowResult.WORKING;
		if (aim == null) {
			Vec3 view = player.getViewVector(1.0F);
			Vec3 flat = new Vec3(view.x, 0.0D, view.z);
			flat = flat.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : flat.normalize();
			aim = player.getEyePosition().add(flat.scale(3.0D)).add(0.0D, 8.0D, 0.0D);
		}
		camera.lookAt(minecraft, aim);
		if (!camera.isLookingAt(minecraft, aim, AIM_TOLERANCE_DEGREES)) return LocateStrongholdTaskExecutor.ThrowResult.WORKING;
		InteractionResult result = actuator.useItem(minecraft, player, InteractionHand.MAIN_HAND);
		if (result == InteractionResult.FAIL) return LocateStrongholdTaskExecutor.ThrowResult.WORKING;
		aim = null;
		return LocateStrongholdTaskExecutor.ThrowResult.USED;
	}

	@Override
	public Optional<Vec3> droppedEye(Vec3 near, double radius) {
		ClientLevel level = client().level;
		if (level == null || near == null) return Optional.empty();
		// The eye drops where it ended, often several blocks up, and falls: search tall.
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(near, near).inflate(radius, 16.0D, radius),
				item -> item.isAlive() && item.getItem().is(Items.ENDER_EYE)).stream()
			.min(Comparator.comparingDouble(item -> item.distanceToSqr(near)))
			.map(Entity::position);
	}

	@Override
	public void release() {
		aim = null;
	}

	private static int eyes(LocalPlayer player) {
		int total = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(Items.ENDER_EYE)) total += stack.getCount();
		}
		return total;
	}

	private static int slot(LocalPlayer player) {
		for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++)
			if (player.getInventory().getItem(slot).is(Items.ENDER_EYE)) return slot;
		return -1;
	}

	/** True once an eye is in the main hand; otherwise starts putting one there. */
	private boolean hold(Minecraft minecraft, LocalPlayer player) {
		if (player.getMainHandItem().is(Items.ENDER_EYE)) return true;
		int inventorySlot = slot(player);
		if (inventorySlot < 0) return false;
		if (inventorySlot < 9) {
			actuator.selectHotbar(minecraft, inventorySlot);
			return false;
		}
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty() || minecraft.gameMode == null)
			return false;
		if (inventorySlot < InventoryMenu.INV_SLOT_START || inventorySlot >= InventoryMenu.INV_SLOT_END) return false;
		minecraft.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, inventorySlot,
			player.getInventory().getSelectedSlot(), ClickType.SWAP, player);
		return false;
	}
}
