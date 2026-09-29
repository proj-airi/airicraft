package ai.moeru.airicraft.agent.tasks;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AcquisitionPickupSitesTest {
	@Test void waterDropOffersTheDryLandingThatCollectedTheLiveIron() {
		BlockPos drop = new BlockPos(154, 22, 383);
		BlockPos landing = new BlockPos(154, 23, 384);
		assertEquals(List.of(landing), AcquisitionPickupSites.find(drop, landing::equals));
	}

	@Test void keepsSeparateAlternativesWhenOneApproachCannotBePathed() {
		BlockPos drop = new BlockPos(0, 64, 0);
		Set<BlockPos> supported = Set.of(drop, drop.east(), drop.north().above());
		var sites = AcquisitionPickupSites.find(drop, supported::contains);
		assertEquals(supported, Set.copyOf(sites));
		assertEquals(3, sites.size());
	}

	@Test void doesNotOfferUnsupportedOrDistantLandings() {
		BlockPos drop = new BlockPos(0, 64, 0);
		Set<BlockPos> distant = Set.of(drop.below(), drop.above(2), drop.east(2));
		assertTrue(AcquisitionPickupSites.find(drop, distant::contains).isEmpty());
		assertTrue(AcquisitionPickupSites.find(drop, pos -> false).isEmpty());
	}
}
