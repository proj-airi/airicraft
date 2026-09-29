package ai.moeru.airicraft.agent.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftCropTendingEnvironmentTest {
	@Test void scatteredDropsUseTheirOwnLevelExceptOnShortenedFarmland() {
		var drop = new BlockPos(261, 62, 478);
		assertEquals(new ai.moeru.airicraft.agent.goals.GoalPosition(261, 62, 478, true),
			MinecraftCropTendingEnvironment.pickupGoal(drop, false), "Irrigation water is below the crop walking level");
		assertEquals(new ai.moeru.airicraft.agent.goals.GoalPosition(261, 63, 478, true),
			MinecraftCropTendingEnvironment.pickupGoal(drop, true), "Farmland drops occupy the soil block");
	}

	@Test void aimsInsideShortCropInsteadOfAtItsTopBoundary() {
		// A mature beetroot outline is half a block tall.
		var shape = Shapes.box(0, 0, 0, 1, 0.5, 1);
		var pos = new BlockPos(260, 63, 484);
		var eye = new Vec3(262.5, 64.62, 484.5);
		assertNull(shape.clip(eye, Vec3.atCenterOf(pos), pos));
		var hit = shape.clip(eye, MinecraftCropTendingEnvironment.cropAim(pos, shape), pos);
		assertNotNull(hit);
		assertEquals(pos, hit.getBlockPos());
	}
}
