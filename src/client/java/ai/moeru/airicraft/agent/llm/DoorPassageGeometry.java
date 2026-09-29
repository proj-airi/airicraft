package ai.moeru.airicraft.agent.llm;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Local standing-player clearance, independent of the door's nominal open flag. */
final class DoorPassageGeometry {
	private DoorPassageGeometry() {}

	static BlockPos lowerPos(BlockPos pos, BlockState state) {
		return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
	}

	static Optional<String> describe(Level level, BlockPos inspected) {
		BlockState inspectedState = level.getBlockState(inspected);
		if (!(inspectedState.getBlock() instanceof DoorBlock)) return Optional.empty();
		BlockPos lower = lowerPos(inspected, inspectedState);
		String prefix = "door=" + pos(lower);
		for (BlockPos cell : BlockPos.betweenClosed(lower.offset(-1, -1, -1), lower.offset(1, 1, 1))) {
			if (!level.hasChunkAt(cell)) return Optional.of(prefix + " passage=unknown reason=neighbor_unloaded");
		}
		BlockState bottom = level.getBlockState(lower);
		BlockState top = level.getBlockState(lower.above());
		if (!(bottom.getBlock() instanceof DoorBlock) || top.getBlock() != bottom.getBlock()
			|| bottom.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || top.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER
			|| bottom.getValue(DoorBlock.OPEN) != top.getValue(DoorBlock.OPEN)
			|| bottom.getValue(DoorBlock.FACING) != top.getValue(DoorBlock.FACING)
			|| bottom.getValue(DoorBlock.HINGE) != top.getValue(DoorBlock.HINGE)) {
			return Optional.of(prefix + " passage=unknown reason=incomplete_or_inconsistent_door");
		}
		List<AABB> current = new ArrayList<>();
		List<AABB> toggled = new ArrayList<>();
		for (BlockPos cell : List.of(lower, lower.above())) {
			BlockState state = level.getBlockState(cell);
			current.addAll(boxes(level, cell, lower, state));
			toggled.addAll(boxes(level, cell, lower, state.setValue(DoorBlock.OPEN, !state.getValue(DoorBlock.OPEN))));
		}
		List<AABB> surroundings = new ArrayList<>();
		for (BlockPos cell : BlockPos.betweenClosed(lower.offset(-1, -1, -1), lower.offset(1, 1, 1))) {
			if (cell.equals(lower) || cell.equals(lower.above())) continue;
			surroundings.addAll(boxes(level, cell, lower, level.getBlockState(cell)));
		}
		Assessment result = assess(current, toggled, surroundings);
		StringBuilder text = new StringBuilder(prefix).append(" stateOpen=").append(bottom.getValue(DoorBlock.OPEN))
			.append(" handToggleAvailable=").append(DoorBlock.isWoodenDoor(bottom))
			.append(" powered=").append(bottom.getValue(DoorBlock.POWERED))
			.append(" inferredPassage=").append(result.inferredPassage())
			.append(" (local level clearance; stateOpen does not determine passage)");
		text.append("\n  east_west: ").append(result.eastWest().description())
			.append("; ").append(approach(level, lower.west(), "west"))
			.append("; ").append(approach(level, lower.east(), "east"));
		text.append("\n  north_south: ").append(result.northSouth().description())
			.append("; ").append(approach(level, lower.north(), "north"))
			.append("; ").append(approach(level, lower.south(), "south"));
		return Optional.of(text.toString());
	}

	private static List<AABB> boxes(Level level, BlockPos cell, BlockPos origin, BlockState state) {
		return state.getCollisionShape(level, cell).toAabbs().stream()
			.map(box -> box.move(cell.getX() - origin.getX(), cell.getY() - origin.getY(), cell.getZ() - origin.getZ()))
			.toList();
	}

	private static String approach(Level level, BlockPos feet, String side) {
		BlockPos floor = feet.below();
		boolean supported = level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
		return side + "Feet=" + pos(feet) + " levelFloor=" + (supported ? "supported" : "not_full_support");
	}

	static Assessment assess(List<AABB> currentDoor, List<AABB> toggledDoor, List<AABB> surroundings) {
		// Sweep a 0.6-wide, 1.8-high player between centers of the neighboring cells.
		Axis eastWest = axis(new AABB(-0.8D, 0D, 0.2D, 1.8D, 1.8D, 0.8D), currentDoor, toggledDoor, surroundings);
		Axis northSouth = axis(new AABB(0.2D, 0D, -0.8D, 0.8D, 1.8D, 1.8D), currentDoor, toggledDoor, surroundings);
		String inferred = eastWest.surroundingsClear() == northSouth.surroundingsClear() ? "ambiguous"
			: eastWest.surroundingsClear() ? "east_west" : "north_south";
		return new Assessment(inferred, eastWest, northSouth);
	}

	private static Axis axis(AABB corridor, List<AABB> current, List<AABB> toggled, List<AABB> surroundings) {
		return new Axis(surroundings.stream().noneMatch(corridor::intersects),
			current.stream().anyMatch(corridor::intersects), toggled.stream().anyMatch(corridor::intersects));
	}

	private static String pos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	record Assessment(String inferredPassage, Axis eastWest, Axis northSouth) {}

	record Axis(boolean surroundingsClear, boolean doorBlocksNow, boolean doorBlocksAfterToggle) {
		String description() {
			String action = !surroundingsClear ? "inspect_surrounding_obstruction"
				: !doorBlocksNow ? "leave_as_is" : !doorBlocksAfterToggle ? "toggle_door" : "no_clear_state";
			return "surroundingsClear=" + surroundingsClear + " doorBlocksNow=" + doorBlocksNow
				+ " doorBlocksAfterToggle=" + doorBlocksAfterToggle + " toClear=" + action;
		}
	}
}
