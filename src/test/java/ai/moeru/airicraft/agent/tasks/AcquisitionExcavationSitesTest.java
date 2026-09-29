package ai.moeru.airicraft.agent.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AcquisitionExcavationSitesTest {
	@Test void fullyBuriedSourceHasWorkSpacesWithoutClearingTheOre() {
		BlockPos ore = new BlockPos(206,98,455);
		var sites = AcquisitionExcavationSites.find(ore, pos -> !pos.equals(ore), pos -> true);
		assertEquals(8, sites.size());
		for (var feet : sites) {
			assertNotEquals(ore, feet);
			assertNotEquals(ore, feet.above());
			assertTrue(Vec3.atBottomCenterOf(feet).add(0,1.62,0)
				.distanceToSqr(Vec3.atCenterOf(ore)) < 20.25);
		}
	}

	@Test void requiresBothHeadroomAndSafeSupportInsideTheAllowedSpace() {
		BlockPos ore = new BlockPos(0,64,0);
		var allowed = Set.of(ore.east(), ore.east().above());
		assertEquals(java.util.List.of(ore.east()), AcquisitionExcavationSites.find(ore, allowed::contains, pos -> true));
		assertTrue(AcquisitionExcavationSites.find(ore, allowed::contains, pos -> false).isEmpty());
		assertTrue(AcquisitionExcavationSites.find(ore, pos -> pos.equals(ore.east()), pos -> true).isEmpty());
	}
}
