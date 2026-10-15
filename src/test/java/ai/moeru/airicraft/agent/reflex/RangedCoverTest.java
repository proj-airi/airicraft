package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.navigation.GridPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangedCoverTest {
	private static UnarmedFlight.Threat skeleton(int x, int z, boolean sight) {
		return new UnarmedFlight.Threat(x + 0.5, z + 0.5, 20F, true, false, Math.hypot(x, z), sight);
	}

	private static UnarmedFlight.Threat zombie(int x, int z) {
		return new UnarmedFlight.Threat(x + 0.5, z + 0.5, 20F, false, false, Math.hypot(x, z), true);
	}

	private static UnarmedFlight.Threat creeper(int x, int z) {
		return new UnarmedFlight.Threat(x + 0.5, z + 0.5, 20F, false, true, Math.hypot(x, z), true);
	}

	private static RangedCover.Candidate cell(int x, int seenBy, int cover, double walk) {
		return new RangedCover.Candidate(new GridPos(x, 64, 0), false, false, seenBy, cover, walk, 10.0D);
	}

	@Test
	void hidesFromShootersOnlyWhenItWouldOtherwisePunchThem() {
		var two = List.of(skeleton(10, 0, true), skeleton(8, 6, true));
		assertNull(RangedCover.blocker(1.0D, false, two), "the death: bare hands against two skeletons");
		assertNull(RangedCover.blocker(3.0D, false, two), "a stone pickaxe is no sword");
		assertEquals("armed", RangedCover.blocker(UnarmedFlight.MIN_WEAPON_DAMAGE, false, two));
		assertEquals("bow_ready", RangedCover.blocker(1.0D, true, two), "a bow shot comes first");
		assertEquals("melee_close", RangedCover.blocker(1.0D, false, List.of(skeleton(10, 0, true), zombie(3, 0))));
		assertNull(RangedCover.blocker(1.0D, false, List.of(skeleton(10, 0, true), zombie(9, 0))), "a far zombie does not");
		assertEquals("creeper", RangedCover.blocker(1.0D, false, List.of(skeleton(10, 0, true), creeper(7, 0))));
		assertEquals("no_ranged_threat", RangedCover.blocker(1.0D, false, List.of(zombie(9, 0))));
		assertNull(RangedCover.blocker(1.0D, false, List.of()), "waiting out threats that left is still cover");
	}

	@Test
	void coverIsTheFirstAnswerToShootersAloneAndTheFallbackForAMixedGroup() {
		assertEquals(RangedCover.RANGED_UNARMED, RangedCover.startReason(1.0D, false, List.of(skeleton(10, 0, true)), false));
		assertNull(RangedCover.startReason(1.0D, false, List.of(skeleton(10, 0, false)), false), "no shooter sees the body");
		var mixed = List.of(skeleton(10, 0, true), zombie(9, 0));
		assertNull(RangedCover.startReason(1.0D, false, mixed, false), "run from a mixed group first");
		assertEquals(RangedCover.FLIGHT_ABANDONED, RangedCover.startReason(1.0D, false, mixed, true));
		assertNull(RangedCover.startReason(1.0D, false, List.of(), true));
		assertNull(RangedCover.startReason(1.0D, false, List.of(skeleton(10, 0, true), zombie(2, 0)), true), "a zombie in reach");
		assertNull(RangedCover.startReason(6.0D, false, List.of(skeleton(10, 0, true)), false), "a sword fights");
	}

	@Test
	void prefersUnseenCellsThenCoverThenAShortWalk() {
		var seen = cell(1, 1, 6, 1.0D);
		var open = cell(2, 0, 0, 2.0D);
		var wall = cell(3, 0, 1, 2.0D);
		var roofed = cell(4, 0, 3, 6.0D);
		var far = cell(5, 0, 1, 8.0D);
		assertEquals(wall, RangedCover.choose(List.of(seen, open, wall, far), 1, Set.of()).orElseThrow(),
			"a wall behind the head over open ground or a longer walk");
		assertEquals(roofed, RangedCover.choose(List.of(seen, open, wall, roofed), 1, Set.of()).orElseThrow(),
			"a roof and walls are worth a few more steps");
		assertTrue(RangedCover.choose(List.of(seen), 1, Set.of()).isEmpty(), "one shooter must not see the cell at all");
		assertEquals(seen, RangedCover.choose(List.of(seen), 2, Set.of()).orElseThrow(), "hidden from one of two still helps");
		assertEquals(open, RangedCover.choose(List.of(open, wall), 1, Set.of(wall.cell())).orElseThrow(), "not a cell the walk failed to reach");
	}

	@Test
	void prefersCellsAwayFromTheThreatsAtEqualCoverAndWalk() {
		var near = new RangedCover.Candidate(new GridPos(1, 64, 0), false, false, 0, 1, 3.0D, 5.0D);
		var away = new RangedCover.Candidate(new GridPos(-1, 64, 0), false, false, 0, 1, 3.0D, 11.0D);
		assertEquals(away, RangedCover.choose(List.of(near, away), 1, Set.of()).orElseThrow());
	}

	@Test
	void neverHidesBesideLavaAnEdgeOrAThreatOrOutOfReach() {
		var lava = new RangedCover.Candidate(new GridPos(1, 64, 0), true, false, 0, 6, 1.0D, 10.0D);
		var edge = new RangedCover.Candidate(new GridPos(2, 64, 0), false, true, 0, 6, 1.0D, 10.0D);
		var beside = new RangedCover.Candidate(new GridPos(3, 64, 0), false, false, 0, 6, 1.0D, 2.0D);
		var distant = new RangedCover.Candidate(new GridPos(4, 64, 0), false, false, 0, 6, 12.0D, 10.0D);
		assertTrue(RangedCover.choose(List.of(lava, edge, beside, distant), 1, Set.of()).isEmpty());
	}

	@Test
	void standsInItsCellOnceTheFeetAreThere() {
		var cell = new GridPos(4, 64, -3);
		assertTrue(RangedCover.atCell(cell, 4.5, 64.0, -2.5));
		assertTrue(RangedCover.atCell(cell, 4.05, 64.0, -2.95), "anywhere in the block");
		assertFalse(RangedCover.atCell(cell, 5.6, 64.0, -2.5), "one block over");
		assertFalse(RangedCover.atCell(cell, 4.5, 66.0, -2.5), "on the roof");
	}

	@Test
	void givingUpPausesLongerThanSteppingAside() {
		RangedCover cover = new RangedCover();
		assertTrue(cover.mayStart(0));
		cover.start(0);
		assertTrue(cover.active());
		assertFalse(cover.mayStart(1));
		cover.abandon("melee_close", 10, false, null);
		assertEquals("melee_close", cover.abandonReason());
		assertFalse(cover.mayStart(10 + RangedCover.PAUSE_TICKS - 1));
		assertTrue(cover.mayStart(10 + RangedCover.PAUSE_TICKS));
		cover.start(60);
		cover.abandon("no_cover", 70, true, null);
		assertFalse(cover.mayStart(70 + RangedCover.PAUSE_TICKS));
		assertTrue(cover.mayStart(70 + RangedCover.RETRY_TICKS));
		cover.reset(null);
		assertTrue(cover.mayStart(71), "a new episode starts fresh");
	}
}
