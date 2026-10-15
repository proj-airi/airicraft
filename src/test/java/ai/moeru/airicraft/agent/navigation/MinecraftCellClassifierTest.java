package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.navigation.CellInfo;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftCellClassifierTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void pointedDripstoneIsNoFloor() {
		MinecraftCellClassifier classifier = MinecraftCellClassifier.forPlayer(null);
		assertTrue(classifier.classify(Blocks.POINTED_DRIPSTONE.defaultBlockState()).hazard(CellInfo.HAZARD_FLOOR));
		assertTrue(classifier.classify(Blocks.MAGMA_BLOCK.defaultBlockState()).hazard(CellInfo.HAZARD_FLOOR));
		assertFalse(classifier.classify(Blocks.DRIPSTONE_BLOCK.defaultBlockState()).hazard(CellInfo.HAZARD_FLOOR));
		assertFalse(classifier.classify(Blocks.STONE.defaultBlockState()).hazard(CellInfo.HAZARD_FLOOR));
	}
}
