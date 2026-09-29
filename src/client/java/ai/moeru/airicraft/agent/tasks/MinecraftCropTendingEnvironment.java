package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import static ai.moeru.airicraft.agent.tasks.CropTendingTaskExecutor.Cell;

final class MinecraftCropTendingEnvironment implements CropTendingTaskExecutor.Environment {
	private final CameraController camera;
	MinecraftCropTendingEnvironment(CameraController camera) { this.camera = camera; }
	private Minecraft client() { return Minecraft.getInstance(); }
	private static BlockPos block(GoalPosition pos) { return new BlockPos(pos.x(), pos.y(), pos.z()); }
	private static GoalPosition position(BlockPos pos) { return new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), true); }

	@Override public String validate(CropTendingStepArgs args) {
		var minecraft = client();
		if (minecraft.level == null || minecraft.player == null) return "world_unavailable";
		var item = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(args.seedItemId()));
		if (!(item instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof CropBlock)) return "unsupported_crop_planting_item";
		for (int x : new int[]{args.x1(), args.x2()}) for (int z : new int[]{args.z1(), args.z2()}) {
			if (minecraft.player.distanceToSqr(Vec3.atCenterOf(new BlockPos(x, args.y(), z))) > 64 * 64) return "crop_plot_too_far";
		}
		return null;
	}

	@Override public Cell state(GoalPosition position, CropTendingStepArgs args) {
		var level = client().level;
		BlockPos pos = block(position);
		if (level == null || !level.hasChunkAt(pos)) return Cell.UNLOADED;
		var state = level.getBlockState(pos);
		var item = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(args.seedItemId()));
		if (item instanceof BlockItem seed && state.is(seed.getBlock()) && state.getBlock() instanceof CropBlock crop)
			return crop.isMaxAge(state) ? Cell.MATURE : Cell.GROWING;
		return state.isAir() && level.getBlockState(pos.below()).is(Blocks.FARMLAND) ? Cell.EMPTY_FARMLAND : Cell.OTHER;
	}

	@Override public GoalPosition position() { return position(client().player.blockPosition()); }

	@Override public GoalPosition workPosition(GoalPosition crop) {
		if (canHarvest(crop)) return position();
		var level = client().level;
		BlockPos target = block(crop);
		return StreamSupport.stream(BlockPos.betweenClosed(target.offset(-3, -1, -3), target.offset(3, 1, 3)).spliterator(), false)
			.filter(pos -> level.hasChunkAt(pos)
				&& level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
				&& level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
				&& level.getFluidState(pos).isEmpty() && level.getFluidState(pos.above()).isEmpty()
				&& (level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
					|| level.getBlockState(pos.below()).is(Blocks.FARMLAND))
				&& visible(Vec3.atBottomCenterOf(pos).add(0, 1.62, 0), target))
			.map(BlockPos::immutable).min(Comparator.comparingDouble(pos -> pos.distToCenterSqr(client().player.position())))
			.map(MinecraftCropTendingEnvironment::position).orElse(null);
	}

	private boolean visible(Vec3 eye, BlockPos target) {
		Vec3 aim = cropAim(target);
		if (aim == null || eye.distanceToSqr(aim) > 20.25) return false;
		var hit = client().level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE,
			ClipContext.Fluid.NONE, client().player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
	}

	private Vec3 cropAim(BlockPos pos) {
		var level = client().level;
		var shape = level.getBlockState(pos).getShape(level, pos);
		return cropAim(pos, shape);
	}

	static Vec3 cropAim(BlockPos pos, net.minecraft.world.phys.shapes.VoxelShape shape) {
		return shape.isEmpty() ? null : shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
	}

	@Override public boolean canHarvest(GoalPosition crop) {
		return client().player.onGround() && visible(client().player.getEyePosition(), block(crop));
	}

	@Override public boolean harvest(GoalPosition crop, CropTendingStepArgs args) {
		var minecraft = client();
		if (state(crop, args) != Cell.MATURE || !canHarvest(crop) || minecraft.gameMode == null
			|| minecraft.player.containerMenu != minecraft.player.inventoryMenu
			|| !minecraft.player.containerMenu.getCarried().isEmpty()) return false;
		BlockPos pos = block(crop);
		camera.lookAt(minecraft, cropAim(pos));
		var cursorHit = camera.blockHit(minecraft, pos);
		if (cursorHit.isEmpty()) return false;
		minecraft.gameMode.startDestroyBlock(pos, cursorHit.get().getDirection());
		minecraft.player.swing(InteractionHand.MAIN_HAND);
		return state(crop, args) == Cell.EMPTY_FARMLAND;
	}

	@Override public int seedCount(String seedItemId) {
		int count = 0;
		var inventory = client().player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			var stack = inventory.getItem(i);
			if (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(seedItemId)) count += stack.getCount();
		}
		return count;
	}

	@Override public Optional<GoalPosition> pickupPosition(GoalPosition crop, CropTendingStepArgs args) {
		Set<String> drops = switch (args.seedItemId()) {
			case "minecraft:wheat_seeds" -> Set.of(args.seedItemId(), "minecraft:wheat");
			case "minecraft:beetroot_seeds" -> Set.of(args.seedItemId(), "minecraft:beetroot");
			case "minecraft:potato" -> Set.of(args.seedItemId(), "minecraft:poisonous_potato");
			default -> Set.of(args.seedItemId());
		};
		return client().level.getEntitiesOfClass(ItemEntity.class, new AABB(block(crop)).inflate(3),
			item -> item.isAlive() && drops.contains(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString()))
			.stream().min(Comparator.comparingDouble(item -> item.distanceToSqr(client().player)))
			.map(item -> pickupGoal(item.blockPosition(), client().level.getBlockState(item.blockPosition()).is(Blocks.FARMLAND)));
	}

	static GoalPosition pickupGoal(BlockPos drop, boolean onFarmland) {
		// Farmland's shortened collision box puts resting drops in the soil block.
		// Elsewhere (including irrigation water), follow the drop's actual level.
		return new GoalPosition(drop.getX(), drop.getY() + (onFarmland ? 1 : 0), drop.getZ(), true);
	}
}
