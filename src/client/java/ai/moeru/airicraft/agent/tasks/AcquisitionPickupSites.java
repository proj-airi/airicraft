package ai.moeru.airicraft.agent.tasks;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Nearby landings can collect a drop during approach without standing in its cell. */
final class AcquisitionPickupSites {
	private AcquisitionPickupSites() {}

	static List<BlockPos> find(BlockPos drop, Predicate<BlockPos> standable) {
		List<BlockPos> sites = new ArrayList<>();
		for (BlockPos feet : BlockPos.betweenClosed(drop.offset(-1, 0, -1), drop.offset(1, 1, 1))) {
			if (standable.test(feet)) sites.add(feet.immutable());
		}
		return List.copyOf(sites);
	}
}
