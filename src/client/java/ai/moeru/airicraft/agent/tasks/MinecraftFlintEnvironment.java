package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;
import ai.moeru.airicraft.control.Priority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Reads the cells beside the player and places and breaks gravel through the foreground lease. */
final class MinecraftFlintEnvironment implements CollectFlintTaskExecutor.Environment {
	private static final float AIM_TOLERANCE_DEGREES = 1.5F;
	/** Drops pop up to about half a block; look a little wider than the walk radius. */
	private static final double DROP_SEARCH_RADIUS = 3.0D;
	private final Actuator actuator = new Actuator("collect_flint", Priority.FOREGROUND);
	private final CameraController camera;
	private BlockPos breaking;

	MinecraftFlintEnvironment(CameraController camera) {
		this.camera = Objects.requireNonNull(camera, "camera");
	}

	private static Minecraft client() { return Minecraft.getInstance(); }

	@Override
	public CollectFlintTaskExecutor.Site observe() {
		LocalPlayer player = client().player;
		if (player == null || client().level == null) return null;
		return new CollectFlintTaskExecutor.Site(player.position(), count(player, Items.GRAVEL), count(player, Items.FLINT));
	}

	@Override
	public Optional<CollectFlintTaskExecutor.Spot> placeSpot(CollectFlintTaskExecutor.Site site, Set<GoalPosition> rejected) {
		ClientLevel level = client().level;
		if (level == null) return Optional.empty();
		return CollectFlintTaskExecutor.chooseSpot(site.feet(), new CollectFlintTaskExecutor.Cells() {
			@Override public boolean open(GoalPosition cell) { return openCell(level, block(cell)); }
			@Override public boolean support(GoalPosition cell) { return supportBlock(level, block(cell)); }
			@Override public boolean liquid(GoalPosition cell) {
				BlockPos pos = block(cell);
				return level.hasChunkAt(pos) && !level.getFluidState(pos).isEmpty();
			}
		}, rejected);
	}

	/** Air or a replaceable plant or thin snow with no collision, no fluid and no fire, outside preserved places. */
	static boolean openCell(ClientLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos) || WorldPlacePreservation.contains(level, pos)) return false;
		BlockState state = level.getBlockState(pos);
		return (state.isAir() || state.canBeReplaced()) && state.getCollisionShape(level, pos).isEmpty()
			&& level.getFluidState(pos).isEmpty() && !state.is(BlockTags.FIRE) && !state.is(Blocks.NETHER_PORTAL);
	}

	/**
	 * A full block with a sturdy top that holds gravel up: not itself falling, and clicking it with gravel places the gravel
	 * rather than opening a menu or playing a note.
	 */
	static boolean supportBlock(ClientLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos)) return false;
		BlockState state = level.getBlockState(pos);
		return state.isCollisionShapeFullBlock(level, pos) && state.isFaceSturdy(level, pos, Direction.UP)
			&& !(state.getBlock() instanceof FallingBlock) && !state.hasBlockEntity() && state.getMenuProvider(level, pos) == null
			&& !state.is(Blocks.NOTE_BLOCK) && level.getFluidState(pos).isEmpty();
	}

	@Override
	public CollectFlintTaskExecutor.PlaceResult place(CollectFlintTaskExecutor.Spot spot) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || minecraft.gameMode == null) return CollectFlintTaskExecutor.PlaceResult.WORKING;
		BlockPos cell = block(spot.cell());
		BlockPos support = block(spot.support());
		if (level.getBlockState(cell).is(Blocks.GRAVEL)) return CollectFlintTaskExecutor.PlaceResult.PLACED;
		if (!openCell(level, cell) || !supportBlock(level, support) || !player.canInteractWithBlock(support, 0.0D))
			return CollectFlintTaskExecutor.PlaceResult.BLOCKED;
		if (!holdGravel(minecraft, player)) return gravelSlot(player) < 0 && !player.getOffhandItem().is(Items.GRAVEL)
			? CollectFlintTaskExecutor.PlaceResult.NO_GRAVEL : CollectFlintTaskExecutor.PlaceResult.WORKING;
		// The middle of the support's top face: the gravel lands in the cell above it, replacing grass or thin snow there.
		Vec3 aim = new Vec3(cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D);
		camera.lookAt(minecraft, aim);
		if (!camera.isLookingAt(minecraft, aim, AIM_TOLERANCE_DEGREES)) return CollectFlintTaskExecutor.PlaceResult.WORKING;
		InteractionResult result = actuator.useItemOn(minecraft, player, InteractionHand.MAIN_HAND,
			new BlockHitResult(aim, Direction.UP, support, false));
		if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
		return CollectFlintTaskExecutor.PlaceResult.WORKING;
	}

	@Override
	public CollectFlintTaskExecutor.BreakResult breakGravel(GoalPosition cell) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || minecraft.gameMode == null) return CollectFlintTaskExecutor.BreakResult.UNREACHABLE;
		BlockPos pos = block(cell);
		BlockState state = level.getBlockState(pos);
		if (!state.is(Blocks.GRAVEL)) {
			stopBreaking();
			return state.isAir() || state.canBeReplaced() ? CollectFlintTaskExecutor.BreakResult.GONE
				: CollectFlintTaskExecutor.BreakResult.NOT_GRAVEL;
		}
		if (!player.canInteractWithBlock(pos, 0.0D)) return CollectFlintTaskExecutor.BreakResult.UNREACHABLE;
		if (!selectTool(minecraft, player)) return CollectFlintTaskExecutor.BreakResult.WORKING;
		if (camera.lookAtBlock(minecraft, pos).isEmpty()) return CollectFlintTaskExecutor.BreakResult.UNREACHABLE;
		var hit = camera.blockHit(minecraft, pos);
		if (hit.isEmpty()) return CollectFlintTaskExecutor.BreakResult.WORKING;
		// Start once, then continue: isDestroying() reads false whenever the attack key is up, so it cannot gate a restart.
		if (!pos.equals(breaking)) {
			stopBreaking();
			if (!actuator.startDestroy(minecraft, pos, hit.get().getDirection())) return CollectFlintTaskExecutor.BreakResult.WORKING;
			breaking = pos;
		} else actuator.continueDestroy(minecraft, pos, hit.get().getDirection());
		player.swing(InteractionHand.MAIN_HAND);
		return CollectFlintTaskExecutor.BreakResult.WORKING;
	}

	@Override
	public Optional<Vec3> drop(GoalPosition cell) {
		ClientLevel level = client().level;
		if (level == null) return Optional.empty();
		Vec3 center = Vec3.atCenterOf(block(cell));
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(center, center).inflate(DROP_SEARCH_RADIUS),
				item -> item.isAlive() && (item.getItem().is(Items.FLINT) || item.getItem().is(Items.GRAVEL))).stream()
			.min(Comparator.comparingDouble(item -> item.distanceToSqr(center)))
			.map(ItemEntity::position);
	}

	@Override
	public void stopBreaking() {
		if (breaking == null) return;
		breaking = null;
		actuator.stopDestroy(client());
	}

	@Override
	public void release() {
		stopBreaking();
	}

	/** True once the main hand holds gravel; otherwise starts putting it there. */
	private boolean holdGravel(Minecraft minecraft, LocalPlayer player) {
		if (player.getMainHandItem().is(Items.GRAVEL)) return true;
		int slot = gravelSlot(player);
		if (slot >= 0 && slot < Inventory.SELECTION_SIZE) {
			return actuator.selectHotbarAndSync(minecraft, slot) && player.getMainHandItem().is(Items.GRAVEL);
		}
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty() || minecraft.gameMode == null)
			return false;
		// Main inventory indices 9..35 equal the player screen's slot IDs; the offhand is the screen's shield slot.
		int menuSlot = slot >= InventoryMenu.INV_SLOT_START && slot < InventoryMenu.INV_SLOT_END ? slot
			: player.getOffhandItem().is(Items.GRAVEL) ? InventoryMenu.SHIELD_SLOT : -1;
		if (menuSlot < 0) return false;
		int selected = player.getInventory().getSelectedSlot();
		minecraft.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, menuSlot, selected, ClickType.SWAP, player);
		actuator.syncHotbar(minecraft, selected);
		return false;
	}

	/** True once the best gravel-breaking slot is held; never a silk touch tool. */
	private boolean selectTool(Minecraft minecraft, LocalPlayer player) {
		Inventory inventory = player.getInventory();
		BlockState gravel = Blocks.GRAVEL.defaultBlockState();
		List<CollectFlintTaskExecutor.ToolOption> options = new ArrayList<>();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) options.add(toolOption(slot, inventory.getItem(slot), gravel));
		int selected = inventory.getSelectedSlot();
		int best = CollectFlintTaskExecutor.chooseToolSlot(selected, options);
		if (best < 0 || best == selected) return best >= 0;
		if (best < Inventory.SELECTION_SIZE) return actuator.selectHotbarAndSync(minecraft, best);
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty() || minecraft.gameMode == null)
			return false;
		minecraft.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, best, selected, ClickType.SWAP, player);
		actuator.syncHotbar(minecraft, selected);
		return false;
	}

	private static CollectFlintTaskExecutor.ToolOption toolOption(int slot, ItemStack stack, BlockState gravel) {
		boolean silkTouch = false;
		int fortune = 0;
		for (var entry : stack.getEnchantments().entrySet()) {
			if (entry.getKey().is(Enchantments.SILK_TOUCH)) silkTouch = true;
			if (entry.getKey().is(Enchantments.FORTUNE)) fortune = Math.max(fortune, entry.getIntValue());
		}
		float speed = stack.isEmpty() ? 1.0F : stack.getDestroySpeed(gravel);
		return new CollectFlintTaskExecutor.ToolOption(slot, silkTouch, fortune, speed, stack.isDamageableItem());
	}

	/** Every slot, offhand included, so a pickup into the offhand stack still counts. */
	private static int count(LocalPlayer player, Item item) {
		int total = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(item)) total += stack.getCount();
		}
		return total;
	}

	/** The first main-inventory slot (hotbar first) holding gravel, or -1. */
	private static int gravelSlot(LocalPlayer player) {
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++)
			if (player.getInventory().getItem(slot).is(Items.GRAVEL)) return slot;
		return -1;
	}

	private static BlockPos block(GoalPosition position) {
		return new BlockPos(position.x(), position.y(), position.z());
	}
}
