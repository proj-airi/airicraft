package ai.moeru.airicraft.agent.reflex;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CombatStalemateTest {
	private static final Vec3 PLAYER = new Vec3(-34.511, 124, -38.573);
	private static final Map<String, Vec3> PILLAGER = Map.of("pillager", new Vec3(-21.740, 123, -41.500));

	@Test void recordedStationaryPillagerEncounterYieldsOnceAfterTwentySeconds() {
		CombatStalemate state = null;
		for (int tick = 0; tick < 400; tick++) {
			state = CombatStalemate.observe(state, tick, PLAYER, PILLAGER, false);
			assertFalse(state.deferred());
		}
		state = CombatStalemate.observe(state, 400, PLAYER, PILLAGER, false);
		assertTrue(state.deferred());
		assertSame(state, CombatStalemate.observe(state, 1400, PLAYER, PILLAGER, false));
		assertSame(state, CombatStalemate.observe(state, 1401, PLAYER.add(-3, 0, 0), PILLAGER, false),
			"Allow the planner's movement without restarting an unchanged distant encounter");
	}

	@Test void meaningfulProgressRestartsObservationButTinyJitterDoesNot() {
		var start = CombatStalemate.observe(null, 0, PLAYER, PILLAGER, false);
		assertEquals(0, CombatStalemate.observe(start, 300, PLAYER.add(0.05, 0, 0), PILLAGER, false).sinceTick());
		var closer = PLAYER.add(PILLAGER.get("pillager").subtract(PLAYER).normalize().scale(1.5));
		var advanced = CombatStalemate.observe(start, 300, closer, PILLAGER, false);
		assertEquals(300, advanced.sinceTick());
		assertFalse(CombatStalemate.observe(advanced, 400, closer, PILLAGER, false).deferred());
	}

	@Test void dangerOrNewThreatEndsDeferral() {
		var deferred = new CombatStalemate(CombatStalemate.Phase.DEFERRED, 0, PLAYER, PILLAGER);
		assertNull(CombatStalemate.observe(deferred, 500, PLAYER, PILLAGER, true));
		assertNull(CombatStalemate.observe(deferred, 500, PLAYER, Map.of(), false));
		assertSame(deferred, CombatStalemate.observe(deferred, 500, PLAYER,
			Map.of("pillager", PILLAGER.get("pillager").add(0, 1.14, 0)), false),
			"A distant pillager jumping behind terrain is not new danger");
		assertFalse(CombatStalemate.observe(deferred, 500, PLAYER,
			Map.of("another_pillager", PILLAGER.get("pillager")), false).deferred());
	}

	@Test void repeatedClimbAndFallDoesNotCountAsCombatProgress() {
		// Reduced from the live ledge cycle: a distant pillager stays below the player.
		var threats = Map.of("pillager", new Vec3(-13.26, 117, -36.30));
		var low = new Vec3(-4.50, 131, -7.57);
		var climb = new Vec3(-4.50, 131, -4.81);
		var high = new Vec3(-5.47, 134.42, -6.36);
		CombatStalemate state = null;
		for (int tick = 0; tick <= 800; tick++) {
			Vec3 position = switch (tick / 100 % 3) {
				case 0 -> low;
				case 1 -> climb;
				default -> high;
			};
			state = CombatStalemate.observe(state, tick, position, threats, false);
			if (tick >= 400) assertTrue(state.deferred(), "A repeated ledge cycle must hand control back");
		}
	}
}
