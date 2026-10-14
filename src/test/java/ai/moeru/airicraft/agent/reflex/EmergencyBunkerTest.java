package ai.moeru.airicraft.agent.reflex;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmergencyBunkerTest {
	private static final BlockPos FEET = new BlockPos(0, 64, 0);

	/** Solid dirt below y=64, air from 64 up. */
	private static final class Ground implements EmergencyBunker.Cells {
		final Set<BlockPos> air = new HashSet<>();
		final Set<BlockPos> water = new HashSet<>();
		final Map<BlockPos, Integer> ticks = new HashMap<>();
		@Override public boolean solid(BlockPos pos) { return pos.getY() < 64 && !air.contains(pos) && !water.contains(pos); }
		@Override public int breakTicks(BlockPos pos) { return ticks.getOrDefault(pos, 10); }
		@Override public boolean fluid(BlockPos pos) { return water.contains(pos); }
		@Override public boolean falling(BlockPos pos) { return false; }
	}

	@Test
	void digsThreeDownWithTheRoofAgainstASideWall() {
		EmergencyBunker.Plan plan = EmergencyBunker.plan(new Ground(), FEET);
		assertNotNull(plan);
		assertEquals(new BlockPos(0, 61, 0), plan.bottom());
		assertEquals(new BlockPos(0, 63, 0), plan.roof());
		assertEquals(63, plan.roofSupport().getY());
	}

	@Test
	void refusesAColumnThatOpensIntoACave() {
		Ground ground = new Ground();
		ground.air.add(new BlockPos(1, 62, 0));
		assertNull(EmergencyBunker.plan(ground, FEET), "a side open at body height lets mobs in");
		Ground hollow = new Ground();
		hollow.air.add(new BlockPos(0, 60, 0));
		assertNull(EmergencyBunker.plan(hollow, FEET), "no floor under the bottom");
	}

	@Test
	void refusesWaterAndSlowRock() {
		Ground wet = new Ground();
		wet.water.add(new BlockPos(0, 61, 1));
		assertNull(EmergencyBunker.plan(wet, FEET));
		Ground rock = new Ground();
		rock.ticks.put(new BlockPos(0, 62, 0), 150);
		assertNull(EmergencyBunker.plan(rock, FEET), "stone by hand takes far too long under attack");
	}

	@Test
	void stepsDigThenRoofThenClose() {
		EmergencyBunker.Plan plan = EmergencyBunker.plan(new Ground(), FEET);
		assertEquals(EmergencyBunker.Step.DIG, EmergencyBunker.step(plan, 64, true, false));
		assertEquals(EmergencyBunker.Step.WAIT, EmergencyBunker.step(plan, 63, false, false));
		assertEquals(EmergencyBunker.Step.DIG, EmergencyBunker.step(plan, 62, true, false));
		assertEquals(EmergencyBunker.Step.ROOF, EmergencyBunker.step(plan, 61, true, false));
		assertEquals(EmergencyBunker.Step.CLOSED, EmergencyBunker.step(plan, 61, true, true));
	}
}
