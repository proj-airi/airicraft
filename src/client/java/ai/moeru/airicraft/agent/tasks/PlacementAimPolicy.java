package ai.moeru.airicraft.agent.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;

/** Placement-only readiness: preserve precise aim unless the item has no hit/rotation-dependent state. */
final class PlacementAimPolicy {
	// Deliberately separate from inert sneak supports: deepslate, for example, has an axis.
	private static final Set<String> ORDINARY_ITEMS = Set.of(
		"minecraft:stone", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt",
		"minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:jungle_planks",
		"minecraft:acacia_planks", "minecraft:dark_oak_planks", "minecraft:mangrove_planks", "minecraft:cherry_planks",
		"minecraft:pale_oak_planks", "minecraft:bamboo_planks", "minecraft:crimson_planks", "minecraft:warped_planks"
	);

	private PlacementAimPolicy() {}

	static boolean usesCurrentViewRay(WorldTaskType type, String itemId, Item item) {
		if (type != WorldTaskType.PLACE_BLOCK || itemId == null || !ORDINARY_ITEMS.contains(itemId)
			|| item == null || item.getClass() != BlockItem.class) return false;
		Block block = ((BlockItem) item).getBlock();
		return block.getClass() == Block.class
			&& block.defaultBlockState().getProperties().isEmpty()
			&& block.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}

	static Optional<Vec3> currentViewRayEnd(Vec3 eye, Vec3 view, double range) {
		if (!finite(eye) || !finite(view) || !Double.isFinite(range) || range <= 0D) return Optional.empty();
		double lengthSquared = view.lengthSqr();
		if (!Double.isFinite(lengthSquared) || lengthSquared <= 0D) return Optional.empty();
		// Vanilla's float/Mth lookup-table direction is only approximately unit length.
		Vec3 end = eye.add(view.scale(range / Math.sqrt(lengthSquared)));
		return finite(end) ? Optional.of(end) : Optional.empty();
	}

	static Optional<BlockHitResult> selectHit(boolean useCurrentView, boolean preciselyAligned,
		BlockPos target, BlockHitResult planned, BlockHitResult current, Vec3 feet, double maxFeetDistanceSquared) {
		if (!useCurrentView) return preciselyAligned ? Optional.ofNullable(planned) : Optional.empty();
		if (target == null || planned == null || current == null || current.getType() != HitResult.Type.BLOCK
			|| current.isInside() || !current.getBlockPos().equals(planned.getBlockPos())
			|| current.getDirection() != planned.getDirection()
			|| !current.getBlockPos().relative(current.getDirection()).equals(target)
			|| !finite(planned.getLocation()) || !finite(current.getLocation()) || !finite(feet)
			|| !Double.isFinite(maxFeetDistanceSquared) || maxFeetDistanceSquared < 0D
			|| feet.distanceToSqr(current.getLocation()) > maxFeetDistanceSquared) {
			// This is an early path, not a stricter replacement for existing precisely aligned interaction.
			// In particular, the old feet reach can admit low faces just beyond the eye ray's reach.
			return preciselyAligned ? Optional.ofNullable(planned) : Optional.empty();
		}
		return Optional.of(current);
	}

	private static boolean finite(Vec3 point) {
		return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
	}
}
