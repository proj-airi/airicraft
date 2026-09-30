package ai.moeru.airicraft.agent.navigation;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoorPassageTest {
	@Test
	void sidewaysOpenDoorBlocksNorthwardExitFromInsideItsOwnCell() {
		AABB player = new AABB(-50.801, 66, -102.8125, -50.201, 67.8, -102.2125);
		AABB openPanel = new AABB(-51, 66, -103, -50, 68, -102.8125);
		AABB closedPanel = new AABB(-51, 66, -103, -50.8125, 68, -102);
		Vec3 north = new Vec3(0, 0, -0.8);
		assertTrue(DoorPassage.blocks(player, north, openPanel));
		assertFalse(DoorPassage.blocks(player, north, closedPanel));
		assertFalse(DoorPassage.cleared(player, north, openPanel));
		assertTrue(DoorPassage.cleared(player.move(0, 0, -1), north, openPanel));
	}

	@Test
	void ordinaryClosedDoorBlocksEntryAndRestoresOnlyAfterWholeBodyPasses() {
		AABB panel = new AABB(0, 0, 0, 0.1875, 2, 1);
		Vec3 east = new Vec3(0.8, 0, 0);
		AABB approaching = new AABB(-0.6, 0, 0.2, 0, 1.8, 0.8);
		assertTrue(DoorPassage.blocks(approaching, east, panel));
		assertFalse(DoorPassage.cleared(approaching.move(0.7, 0, 0), east, panel));
		assertTrue(DoorPassage.cleared(approaching.move(0.9, 0, 0), east, panel));
		assertFalse(DoorPassage.blocks(approaching.move(0.9, 0, 0), east, panel));
	}

	@Test
	void entrySweepIncludesFarEdgeDoorBeforeVanillaCanOpenItUntracked() {
		AABB body = new AABB(-0.8, 0, 0.2, -0.2, 1.8, 0.8);
		AABB farPanel = new AABB(0.8125, 0, 0, 1, 2, 1);
		assertTrue(DoorPassage.blocks(body, new Vec3(1.8, 0, 0), farPanel));
	}

	@Test
	void panelsBesideTheRouteAndBeyondTheNextStepAreUntouched() {
		AABB body = new AABB(0.2, 0, 0.2, 0.8, 1.8, 0.8);
		Vec3 north = new Vec3(0, 0, -0.8);
		assertFalse(DoorPassage.blocks(body, north, new AABB(0, 0, 0, 0.1875, 2, 1)));
		assertFalse(DoorPassage.blocks(body, north, new AABB(0, 0, -2, 1, 2, -1.8125)));
	}
}
