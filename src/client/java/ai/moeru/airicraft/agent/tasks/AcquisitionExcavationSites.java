package ai.moeru.airicraft.agent.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Work spaces next to a known source; the source itself is left for exact harvesting. */
final class AcquisitionExcavationSites {
	private AcquisitionExcavationSites() {}

	static List<BlockPos> find(BlockPos source, Predicate<BlockPos> clearable, Predicate<BlockPos> supported) {
		List<BlockPos> sites = new ArrayList<>();
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos beside = source.relative(side);
			for (BlockPos feet : List.of(beside, beside.below())) {
				if (clearable.test(feet) && clearable.test(feet.above()) && supported.test(feet.below())) sites.add(feet);
			}
		}
		return List.copyOf(sites);
	}
}
