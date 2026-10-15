package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.navigation.NavigationPolicies;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * The last resort of a losing fight: dig three blocks straight down and put a block over the head, the way a
 * player at two hearts hides from a skeleton. Five of seven deaths in one playtest were fights the reflex kept
 * taking at three or four health, since retreating to eat found no safe window. The hole is only dug where every
 * wall around the body is solid, nothing liquid touches it, there is floor under it, and each block breaks fast
 * with a carried tool; the body then eats and heals inside until the threat leaves. Client thread only.
 */
final class EmergencyBunker {
	/** Health at or below which a fight turns into digging in. */
	static final float HEALTH_THRESHOLD = 8.0F;
	static final int DEPTH = 3;
	/** Slowest block the dig accepts, in ticks with the best carried tool. */
	static final int MAX_BREAK_TICKS = 30;
	/** Give up on a bunker that is not closed within this many ticks of starting. */
	static final int MAX_TICKS = 200;

	/** What the bunker needs to know about the cells around a column. */
	interface Cells {
		boolean solid(BlockPos pos);
		/** Ticks to break with the best carried tool; negative when it cannot be broken. */
		int breakTicks(BlockPos pos);
		boolean fluid(BlockPos pos);
		boolean falling(BlockPos pos);
	}

	/** Where to dig and where the roof goes, for a body standing with its feet in {@code feet}. */
	record Plan(BlockPos top, List<BlockPos> dig, BlockPos roof, BlockPos roofSupport) {
		BlockPos bottom() {
			return dig.getLast();
		}
	}

	private Plan plan;
	private long startedTick;
	private boolean failed;
	private boolean reported;

	void reset() {
		plan = null;
		failed = false;
		reported = false;
	}

	/** A new danger episode may try again where an earlier bunker failed. */
	void allowRetry() {
		failed = false;
	}

	boolean active() {
		return plan != null;
	}

	/** The cells the body digs out, roof cell included, or null with no bunker under way. */
	AABB shaft() {
		return plan == null ? null : shaft(plan);
	}

	static AABB shaft(Plan plan) {
		BlockPos bottom = plan.bottom();
		return new AABB(bottom.getX(), bottom.getY(), bottom.getZ(), bottom.getX() + 1, plan.roof().getY() + 1, bottom.getZ() + 1);
	}

	/**
	 * Whether a mob is inside the shaft with the body. In a random-seed playtest a zombie dropped into the hole before
	 * the roof went on; the roof then closed over both of them and the body stood still while it was hit to death.
	 * A mob standing on the roof does not count: it cannot reach or see the body.
	 */
	static boolean intruded(AABB shaft, List<AABB> mobs) {
		for (AABB mob : mobs) if (mob.intersects(shaft)) return true;
		return false;
	}

	/**
	 * A bunker under {@code feet}, or null when the column is unsafe or too slow to dig. The dug cells become the
	 * body's feet and head cells and the roof cell above them, so the walls around the lower two must be solid,
	 * the roof needs one solid side to be placed against, and nothing next to any of them may be liquid.
	 */
	static Plan plan(Cells cells, BlockPos feet) {
		if (cells.falling(feet.above()) || cells.falling(feet)) return null;
		List<BlockPos> dig = new java.util.ArrayList<>();
		for (int depth = 1; depth <= DEPTH; depth++) dig.add(feet.below(depth));
		if (!cells.solid(feet.below(DEPTH + 1)) || cells.fluid(feet.below(DEPTH + 1))) return null;
		for (BlockPos cell : dig) {
			int ticks = cells.breakTicks(cell);
			if (ticks < 0 || ticks > MAX_BREAK_TICKS || cells.fluid(cell)) return null;
			for (Direction side : Direction.Plane.HORIZONTAL) {
				BlockPos wall = cell.relative(side);
				if (cells.fluid(wall)) return null;
				// The two cells the body ends in must be closed on every side.
				if (!cell.equals(dig.getFirst()) && !cells.solid(wall)) return null;
			}
		}
		BlockPos roof = dig.getFirst();
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos support = roof.relative(side);
			if (cells.solid(support) && !cells.falling(support)) return new Plan(feet, List.copyOf(dig), roof, support);
		}
		return null;
	}

	enum Step { DIG, WAIT, ROOF, CLOSED }

	/** The next thing to do, from the body's feet height and whether the roof is in place. */
	static Step step(Plan plan, int feetY, boolean onGround, boolean roofSolid) {
		if (feetY <= plan.bottom().getY() && roofSolid) return Step.CLOSED;
		if (feetY > plan.bottom().getY()) return onGround ? Step.DIG : Step.WAIT;
		return onGround ? Step.ROOF : Step.WAIT;
	}

	/**
	 * Digs or roofs for one tick. Returns false when there is no bunker to work on (none fits here, it failed, or it
	 * is closed and the body has left it), so the caller fights on as before.
	 */
	boolean tick(Minecraft minecraft, LocalPlayer player, long tick, Actuator actuator, CameraController camera,
		MovementController movement, Predicate<net.minecraft.world.item.Item> select, List<SurvivalReflexEvent> events) {
		Level level = minecraft.level;
		if (failed || level == null) return false;
		if (plan == null) {
			if (!player.onGround() || !hasThrowaway(player) && !dropsDirt(level.getBlockState(player.blockPosition().below()))) return false;
			plan = plan(cells(level, player), player.blockPosition());
			if (plan == null) return false;
			startedTick = tick;
			events.add(new SurvivalReflexEvent("reflex.bunker_started", java.util.Map.of(
				"tick", tick, "health", player.getHealth(), "top", plan.top().toShortString())));
		}
		if (player.blockPosition().getX() != plan.top().getX() || player.blockPosition().getZ() != plan.top().getZ()
			|| player.blockPosition().getY() > plan.top().getY()) {
			// Knocked out of the column, or climbed out after it closed.
			if (!reported) fail(tick, "left_column", events);
			plan = null;
			return false;
		}
		Step step = step(plan, player.blockPosition().getY(), player.onGround(), level.getBlockState(plan.roof()).isSolidRender());
		if (step == Step.CLOSED) {
			movement.stop(minecraft);
			if (!reported) {
				reported = true;
				events.add(new SurvivalReflexEvent("reflex.bunkered", java.util.Map.of(
					"tick", tick, "health", player.getHealth(), "pos", player.blockPosition().toShortString())));
			}
			return true;
		}
		if (tick - startedTick > MAX_TICKS) {
			fail(tick, "too_slow", events);
			actuator.stopDestroy(minecraft);
			plan = null;
			return false;
		}
		movement.stop(minecraft);
		if (step == Step.WAIT) return true;
		if (step == Step.DIG) {
			BlockPos below = player.blockPosition().below();
			BlockState state = level.getBlockState(below);
			if (state.isAir()) return true;
			selectBestTool(player, state, select);
			camera.lookAt(minecraft, Vec3.atCenterOf(below).add(0, 0.5, 0));
			if (minecraft.gameMode != null && minecraft.gameMode.isDestroying()) actuator.continueDestroy(minecraft, below, Direction.UP);
			else actuator.startDestroy(minecraft, below, Direction.UP);
			return true;
		}
		actuator.stopDestroy(minecraft);
		if (!selectThrowaway(player, select)) {
			fail(tick, "no_roof_block", events);
			plan = null;
			return false;
		}
		Direction face = Direction.getApproximateNearest(
			plan.roof().getX() - plan.roofSupport().getX(), 0, plan.roof().getZ() - plan.roofSupport().getZ());
		Vec3 hit = Vec3.atCenterOf(plan.roofSupport()).add(face.getStepX() * 0.5, 0, face.getStepZ() * 0.5);
		camera.lookAt(minecraft, hit);
		actuator.useItemOn(minecraft, player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, plan.roofSupport(), false));
		return true;
	}

	/** Whether the body sits in a closed bunker, so the fight around it can wait. */
	boolean closed(Level level, LocalPlayer player) {
		return plan != null && reported && level != null && player.blockPosition().getY() <= plan.bottom().getY()
			&& level.getBlockState(plan.roof()).isSolidRender();
	}

	private void fail(long tick, String reason, List<SurvivalReflexEvent> events) {
		failed = true;
		events.add(new SurvivalReflexEvent("reflex.bunker_failed", java.util.Map.of("tick", tick, "reason", reason)));
	}

	private static Cells cells(Level level, LocalPlayer player) {
		return new Cells() {
			@Override public boolean solid(BlockPos pos) {
				return level.hasChunkAt(pos) && level.getBlockState(pos).isCollisionShapeFullBlock(level, pos);
			}

			@Override public int breakTicks(BlockPos pos) {
				BlockState state = level.getBlockState(pos);
				float hardness = state.getDestroySpeed(level, pos);
				if (hardness < 0 || state.hasBlockEntity()) return -1;
				if (hardness == 0) return 0;
				float best = 0;
				Inventory inventory = player.getInventory();
				for (int slot = -1; slot < Inventory.INVENTORY_SIZE; slot++) {
					ItemStack tool = slot < 0 ? ItemStack.EMPTY : inventory.getItem(slot);
					float speed = tool.isEmpty() ? 1.0F : tool.getDestroySpeed(state);
					boolean harvests = !state.requiresCorrectToolForDrops() || !tool.isEmpty() && tool.isCorrectToolForDrops(state);
					best = Math.max(best, speed / hardness / (harvests ? 30.0F : 100.0F));
				}
				return best <= 0 ? -1 : (int) Math.ceil(1.0F / best);
			}

			@Override public boolean fluid(BlockPos pos) {
				return !level.getFluidState(pos).isEmpty();
			}

			@Override public boolean falling(BlockPos pos) {
				return level.getBlockState(pos).getBlock() instanceof FallingBlock;
			}
		};
	}

	/**
	 * Whether the first dug block drops dirt, which the body picks up on the way down and roofs with. A body that
	 * respawned with nothing can still dig in where the ground is soil.
	 */
	static boolean dropsDirt(BlockState state) {
		return state.is(BlockTags.DIRT) && !state.is(Blocks.MOSS_BLOCK) && !state.is(Blocks.MUD)
			&& NavigationPolicies.isThrowaway(new ItemStack(Items.DIRT));
	}

	private static boolean hasThrowaway(LocalPlayer player) {
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (NavigationPolicies.isThrowaway(player.getInventory().getItem(slot))) return true;
		}
		return false;
	}

	private static boolean selectThrowaway(LocalPlayer player, Predicate<net.minecraft.world.item.Item> select) {
		if (NavigationPolicies.isThrowaway(player.getInventory().getSelectedItem())) return true;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (NavigationPolicies.isThrowaway(stack)) return select.test(stack.getItem());
		}
		return false;
	}

	private static void selectBestTool(LocalPlayer player, BlockState state, Predicate<net.minecraft.world.item.Item> select) {
		ItemStack best = ItemStack.EMPTY;
		float bestSpeed = 1.0F;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.isEmpty()) continue;
			float speed = stack.getDestroySpeed(state);
			if (speed > bestSpeed) {
				best = stack;
				bestSpeed = speed;
			}
		}
		if (!best.isEmpty()) select.test(best.getItem());
	}
}
