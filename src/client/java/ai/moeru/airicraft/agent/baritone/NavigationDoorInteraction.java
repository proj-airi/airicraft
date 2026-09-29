package ai.moeru.airicraft.agent.baritone;

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

import java.util.WeakHashMap;

/** Uses Baritone's ordinary look/use inputs, including for a door in the source cell. */
public final class NavigationDoorInteraction {
	private static final WeakHashMap<IPlayerContext, NavigationDoorInteraction> CONTROLLERS = new WeakHashMap<>();
	private Level level;
	private PendingDoor pending;
	private record PendingDoor(BlockPos pos, BlockState original, AABB panel, Vec3 travel) {}

	public static boolean update(IPlayerContext ctx, BlockPos src, BlockPos dest, MovementState state) {
		return CONTROLLERS.computeIfAbsent(ctx, ignored -> new NavigationDoorInteraction()).tick(ctx, src, dest, state);
	}

	private boolean tick(IPlayerContext ctx, BlockPos src, BlockPos dest, MovementState state) {
		if (level != ctx.world()) {
			level = ctx.world();
			pending = null;
		}
		AABB body = ctx.player().getBoundingBox();

		Vec3 travel = new Vec3(dest.getX() - src.getX(), 0, dest.getZ() - src.getZ());
		if (travel.lengthSqr() == 0) return false;
		// Cover both endpoint panels, including the far edge of the destination cell.
		Vec3 sweep = travel.normalize().scale(travel.length() + 0.8);
		for (BlockPos candidate : new BlockPos[]{src, src.above(), dest, dest.above()}) {
			BlockState door = level.getBlockState(candidate);
			if (!(door.getBlock() instanceof DoorBlock block) || !block.type().canOpenByHand()) continue;
			BlockPos pos = door.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? candidate.below() : candidate;
			door = level.getBlockState(pos);
			if (!(door.getBlock() instanceof DoorBlock)) continue;
			AABB panel = door.getCollisionShape(level, pos).bounds().move(pos).expandTowards(0, 1, 0);
			if (!DoorPassage.blocks(body, sweep, panel)) continue;
			// A panel running alongside travel is not a door we can open out of the way.
			AABB toggled = door.cycle(DoorBlock.OPEN).getCollisionShape(level, pos).bounds().move(pos).expandTowards(0, 1, 0);
			if (DoorPassage.blocks(body, sweep, toggled)) continue;
			if (use(ctx, pos, state)) {
				if (pending == null) pending = new PendingDoor(pos.immutable(), door, panel, travel);
				return true;
			}
		}
		return false;
	}

	/** Arrival may end movement before the player's trailing edge clears the panel. */
	public static void restorePassedDoor(IPlayerContext ctx) {
		NavigationDoorInteraction controller = CONTROLLERS.get(ctx);
		if (controller == null || controller.pending == null) return;
		PendingDoor door = controller.pending;
		if (controller.level != ctx.world() || ctx.player() == null
			|| !ctx.world().getBlockState(door.pos()).equals(door.original().cycle(DoorBlock.OPEN))) {
			controller.pending = null;
			return;
		}
		if (!DoorPassage.cleared(ctx.player().getBoundingBox(), door.travel(), door.panel())) return;
		if (ctx.player().isShiftKeyDown() || ctx.player().isUsingItem()) return;
		double reach = ctx.playerController().getBlockReachDistance();
		var rotation = RotationUtils.reachable(ctx, door.pos(), reach);
		if (rotation.isPresent()) {
			var hit = baritone.api.utils.RayTraceUtils.rayTraceTowards(ctx.player(), rotation.get(), reach);
			if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
				&& (blockHit.getBlockPos().equals(door.pos()) || blockHit.getBlockPos().equals(door.pos().above()))) {
				ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), net.minecraft.world.InteractionHand.MAIN_HAND, blockHit);
			}
		}
		controller.pending = null;
	}

	private static boolean use(IPlayerContext ctx, BlockPos pos, MovementState state) {
		var rotation = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
		if (rotation.isEmpty()) return false;
		state.getInputStates().clear();
		state.setInput(Input.SNEAK, false);
		state.setTarget(new MovementState.MovementTarget(rotation.get(), true));
		// Wait until the look target is selected, so another block is never clicked.
		if (ctx.isLookingAt(pos) || ctx.isLookingAt(pos.above())) state.setInput(Input.CLICK_RIGHT, true);
		return true;
	}
}
