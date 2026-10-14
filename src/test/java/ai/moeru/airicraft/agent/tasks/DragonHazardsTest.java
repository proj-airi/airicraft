package ai.moeru.airicraft.agent.tasks;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DragonHazardsTest {
	private static final DragonHazards.Cloud BREATH = new DragonHazards.Cloud(new Vec3(0, 64, 0), 5);

	@Test void cloudsThreatenTheirRadiusPlusMarginAtTheirHeightOnly() {
		assertTrue(DragonHazards.threatened(new Vec3(6.5, 64, 0), List.of(BREATH), List.of()));
		assertFalse(DragonHazards.threatened(new Vec3(7.5, 64, 0), List.of(BREATH), List.of()));
		assertFalse(DragonHazards.threatened(new Vec3(0, 70, 0), List.of(BREATH), List.of()), "standing well above the disc");
	}

	@Test void fireballsThreatenWhereTheyAreHeadingNotWhereTheyAre() {
		var incoming = new DragonHazards.Fireball(new Vec3(-30, 70, 0), new Vec3(1.5, -0.2, 0));
		var passing = new DragonHazards.Fireball(new Vec3(-30, 70, 20), new Vec3(1.5, 0, 0));
		var away = new DragonHazards.Fireball(new Vec3(-6, 65, 0), new Vec3(-1.5, 0, 0));
		Vec3 feet = new Vec3(0, 64, 0);
		assertTrue(DragonHazards.threatened(feet, List.of(), List.of(incoming)));
		assertFalse(DragonHazards.threatened(feet, List.of(), List.of(passing)));
		assertFalse(DragonHazards.threatened(feet, List.of(), List.of(away)));
	}

	@Test void escapeChoosesTheNearestSafeStandableSpotAwayFromTheHead() {
		Vec3 feet = new Vec3(2, 64, 0);
		List<Vec3> candidates = List.of(new Vec3(4, 64, 0), new Vec3(8, 64, 0), new Vec3(-8, 64, 0), new Vec3(9, 64, 0),
			new Vec3(8.5, 69, 0));
		Vec3 head = new Vec3(-6, 66, 0);
		var escape = DragonHazards.escape(feet, candidates, List.of(BREATH), List.of(), head, c -> c.x != 8);
		assertEquals(new Vec3(9, 64, 0), escape.orElseThrow(), "inside cloud, unstandable and near-head spots lose");
		assertTrue(DragonHazards.escape(feet, List.of(new Vec3(3, 64, 0)), List.of(BREATH), List.of(), null, c -> true).isEmpty());
	}
}
