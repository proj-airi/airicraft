package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.control.Priority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads end portal frames from the client world, places eyes of ender and walks into the portal through the foreground leases. */
final class MinecraftEndPortalEnvironment implements EnterEndPortalTaskExecutor.Environment {
	private static final String OWNER = "enter_end_portal";
	private static final float AIM_DEGREES = 10.0F;
	/** Searching the full height of the radius cube is wasted work: a portal room is a few blocks tall. */
	private static final int SEARCH_HEIGHT = 12;
	private final Actuator actuator = new Actuator(OWNER, Priority.FOREGROUND);
	private final MovementController movement = new MovementController(OWNER, Priority.FOREGROUND);
	private final CameraController camera;

	MinecraftEndPortalEnvironment(CameraController camera) {
		this.camera = Objects.requireNonNull(camera, "camera");
	}

	private static Minecraft client() { return Minecraft.getInstance(); }

	@Override
	public EnterEndPortalTaskExecutor.Body observe() {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return null;
		return new EnterEndPortalTaskExecutor.Body(player.position(), player.getEyePosition(), player.blockPosition(), player.onGround(),
			player.getHealth(), player.getFoodData().getFoodLevel(), level.dimension().location().toString(), eyes(player));
	}

	@Override
	public EnterEndPortalTaskExecutor.Frame frameAt(BlockPos pos) {
		ClientLevel level = client().level;
		if (level == null || !level.hasChunkAt(pos)) return null;
		return frame(level.getBlockState(pos), pos);
	}

	private static EnterEndPortalTaskExecutor.Frame frame(BlockState state, BlockPos pos) {
		if (!state.is(Blocks.END_PORTAL_FRAME)) return null;
		return new EnterEndPortalTaskExecutor.Frame(pos.immutable(), state.getValue(EndPortalFrameBlock.FACING),
			state.getValue(EndPortalFrameBlock.HAS_EYE));
	}

	@Override
	public List<BlockPos> visibleFrames(int radius) {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return List.of();
		BlockPos origin = player.blockPosition();
		Vec3 eye = player.getEyePosition();
		List<BlockPos> frames = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int height = Math.min(radius, SEARCH_HEIGHT);
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -height; dy <= height; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (!level.hasChunkAt(cursor) || !level.getBlockState(cursor).is(Blocks.END_PORTAL_FRAME)) continue;
					BlockPos pos = cursor.immutable();
					if (visible(level, player, eye, pos)) frames.add(pos);
				}
			}
		}
		return frames;
	}

	/** Whether a ray from the eyes reaches the frame's top or one of its sides: what the player could see, no X-ray. */
	private static boolean visible(ClientLevel level, LocalPlayer player, Vec3 eye, BlockPos pos) {
		double top = EnterEndPortalTaskExecutor.FRAME_TOP;
		Vec3[] aims = {
			new Vec3(pos.getX() + 0.5D, pos.getY() + top - 0.01D, pos.getZ() + 0.5D),
			new Vec3(pos.getX() + 0.01D, pos.getY() + top / 2, pos.getZ() + 0.5D),
			new Vec3(pos.getX() + 0.99D, pos.getY() + top / 2, pos.getZ() + 0.5D),
			new Vec3(pos.getX() + 0.5D, pos.getY() + top / 2, pos.getZ() + 0.01D),
			new Vec3(pos.getX() + 0.5D, pos.getY() + top / 2, pos.getZ() + 0.99D)
		};
		for (Vec3 aim : aims) {
			BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
			if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return true;
		}
		return false;
	}

	@Override
	public boolean portalAt(BlockPos pos) {
		ClientLevel level = client().level;
		return level != null && level.hasChunkAt(pos) && level.getBlockState(pos).is(Blocks.END_PORTAL);
	}

	@Override
	public boolean standable(BlockPos cell) {
		ClientLevel level = client().level;
		if (level == null || !level.hasChunkAt(cell)) return false;
		BlockPos below = cell.below();
		if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) || !level.getFluidState(below).isEmpty()) return false;
		if (!open(level, cell) || !open(level, cell.above())) return false;
		// Never beside the lava pool: a step off the cell would end in it.
		for (Direction side : Direction.Plane.HORIZONTAL) {
			if (level.getFluidState(cell.relative(side)).is(FluidTags.LAVA) || level.getFluidState(below.relative(side)).is(FluidTags.LAVA))
				return false;
		}
		return true;
	}

	@Override
	public boolean open(BlockPos pos) {
		ClientLevel level = client().level;
		return level != null && level.hasChunkAt(pos) && open(level, pos);
	}

	private static boolean open(ClientLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty();
	}

	@Override
	public EnterEndPortalTaskExecutor.UseResult placeEye(BlockPos frame, boolean force) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) return EnterEndPortalTaskExecutor.UseResult.WORKING;
		if (slot(player) < 0) return EnterEndPortalTaskExecutor.UseResult.NO_EYE;
		movement.stop(minecraft);
		if (!hold(minecraft, player)) return EnterEndPortalTaskExecutor.UseResult.WORKING;
		Vec3 hit = EnterEndPortalTaskExecutor.topCenter(frame);
		camera.lookAt(minecraft, hit);
		if (!force && !camera.isLookingAt(minecraft, hit, AIM_DEGREES)) return EnterEndPortalTaskExecutor.UseResult.WORKING;
		// A use on the frame's top: the eye goes into the frame. Never a plain use, which would throw the eye.
		InteractionResult result = actuator.useItemOn(minecraft, player, InteractionHand.MAIN_HAND,
			new BlockHitResult(hit, Direction.UP, frame, false));
		if (result == InteractionResult.FAIL) return EnterEndPortalTaskExecutor.UseResult.WORKING;
		if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
		return EnterEndPortalTaskExecutor.UseResult.USED;
	}

	@Override
	public void walkToward(Vec3 target, long tick) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null) return;
		// Level with the eyes: the walk needs only the heading, and looking down would hide nothing.
		Vec3 aim = new Vec3(target.x, player.getEyeY(), target.z);
		camera.lookAt(minecraft, aim);
		if (!camera.isLookingAt(minecraft, aim, AIM_DEGREES)) {
			movement.stop(minecraft);
			return;
		}
		// The movement controller jumps on a horizontal collision, which climbs the frame from a cell level with it.
		movement.moveForward(minecraft, false, false, tick);
	}

	@Override
	public void stop() {
		movement.stop(client());
	}

	@Override
	public void release() {
		stop();
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
