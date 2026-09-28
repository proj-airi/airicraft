package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.navigation.BodyState;
import ai.moeru.airicraft.navigation.Box;
import ai.moeru.airicraft.navigation.CellInfo;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.MapTerrain;
import ai.moeru.airicraft.navigation.MoveType;
import ai.moeru.airicraft.navigation.MovementPolicy;
import ai.moeru.airicraft.navigation.Moves;
import ai.moeru.airicraft.navigation.Step;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatTraversalTest {
	private static final CellInfo STONE = CellInfo.builder("stone").full().breakTicks(30).build();
	private static final MovementPolicy POLICY = MovementPolicy.defaults().noEdits();

	@Test
	void ascendsWithAJumpAndFinishesStandingOnTheStep() {
		MapTerrain terrain = floor();
		terrain.set(1, 1, 0, STONE);
		Step step = new Moves(terrain, POLICY).stepBetween(new GridPos(0, 1, 0), new GridPos(1, 2, 0));
		assertEquals(MoveType.ASCEND, step.type());
		CombatTraversal traversal = new CombatTraversal();
		traversal.start(step, new CombatPositioning.Cell(1, 2, 0), 0);

		// Facing east, toward the step.
		var control = traversal.tick(body(0.5, 1, 0.5, true, 1), terrain, POLICY, 10.5, 0.5, 1);
		assertTrue(control.jump());
		assertTrue(control.steering().forward());
		assertEquals("ASCEND", traversal.evidence().get("phase"));

		var midair = traversal.tick(body(1.2, 2.3, 0.5, false, 3), terrain, POLICY, 10.5, 0.5, 3);
		assertFalse(midair.jump());
		assertEquals(new CombatPositioning.Cell(1, 2, 0), traversal.destination());

		traversal.tick(body(1.5, 2, 0.5, true, 5), terrain, POLICY, 10.5, 0.5, 5);
		assertNull(traversal.destination());
		assertEquals("SUCCESS", traversal.evidence().get("phase"));
	}

	@Test
	void backsOffADescentWhileFacingTheThreat() {
		MapTerrain terrain = floor();
		for (int z = -2; z <= 2; z++) terrain.set(0, 1, z, STONE).set(-1, 1, z, STONE).set(-2, 1, z, STONE);
		Step step = new Moves(terrain, POLICY).stepBetween(new GridPos(0, 2, 0), new GridPos(1, 1, 0));
		assertEquals(MoveType.DESCEND, step.type());
		CombatTraversal traversal = new CombatTraversal();
		traversal.start(step, new CombatPositioning.Cell(1, 1, 0), 0);

		// The threat is to the west, so stepping east is backing off.
		var control = traversal.tick(body(0.5, 2, 0.5, true, 1), terrain, POLICY, -9.5, 0.5, 1);
		assertTrue(control.steering().back());
		assertFalse(control.jump());
	}

	@Test
	void givesUpAfterSixtyTicks() {
		MapTerrain terrain = floor();
		terrain.set(1, 1, 0, STONE);
		Step step = new Moves(terrain, POLICY).stepBetween(new GridPos(0, 1, 0), new GridPos(1, 2, 0));
		CombatTraversal traversal = new CombatTraversal();
		traversal.start(step, new CombatPositioning.Cell(1, 2, 0), 0);

		traversal.tick(body(0.5, 1, 0.5, true, 61), terrain, POLICY, 10.5, 0.5, 61);

		assertNull(traversal.destination());
		assertTrue(String.valueOf(traversal.evidence().get("phase")).startsWith("failed: age"));
	}

	private static MapTerrain floor() {
		MapTerrain terrain = new MapTerrain(CellInfo.AIR, new Box(-3, 0, -3, 4, 6, 3));
		for (int x = -3; x <= 4; x++) for (int z = -3; z <= 3; z++) terrain.set(x, 0, z, STONE);
		return terrain;
	}

	private static BodyState body(double x, double y, double z, boolean onGround, long tick) {
		return new BodyState(x, y, z, 0, onGround, false, false, false, tick);
	}
}
