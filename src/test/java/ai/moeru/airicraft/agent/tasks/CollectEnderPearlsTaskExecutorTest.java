package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.Arena;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.BuildKind;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.BuildStep;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.Dimension;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.DroppedPearl;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.Enderman;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.Ending;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.SpotPlan;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CollectEnderPearlsTaskExecutorTest {
	private static final GoalPosition STAND = new GoalPosition(0, 64, 0, true);
	private static final GoalPosition PIT = new GoalPosition(0, 63, 0, true);
	private static final GoalPosition ROOF = new GoalPosition(0, 65, 0, true);

	// ---- the pit ----

	@Test void onFlatGroundDigsOneDownAndRoofsOnATwoBlockColumn() {
		Grid grid = new Grid();
		var check = CollectEnderPearlsTaskExecutor.pit(grid, STAND, 3);
		assertNull(check.refusal());
		SpotPlan plan = check.plan();
		assertEquals(PIT, plan.pit());
		assertEquals(ROOF, plan.roof());
		assertFalse(plan.dug());
		assertEquals(List.of(
			new BuildStep(BuildKind.PLACE, new GoalPosition(1, 64, 0, true), new GoalPosition(1, 63, 0, true)),
			new BuildStep(BuildKind.PLACE, new GoalPosition(1, 65, 0, true), new GoalPosition(1, 64, 0, true)),
			new BuildStep(BuildKind.PLACE, ROOF, new GoalPosition(1, 65, 0, true))), plan.placements(),
			"a column beside the pit, then the roof against its top");
		assertEquals("too_few_blocks", CollectEnderPearlsTaskExecutor.pit(grid, STAND, 2).refusal(), "three blocks needed on open ground");
		grid.yields = true;
		assertNull(CollectEnderPearlsTaskExecutor.pit(grid, STAND, 2).refusal(), "the dug stone is the third block");
	}

	@Test void aWallBesideTheHeadHoldsTheRoofWithOneBlock() {
		Grid grid = new Grid();
		grid.solid.add(new GoalPosition(-1, 65, 0, true));
		grid.solid.add(new GoalPosition(-1, 64, 0, true));
		SpotPlan plan = CollectEnderPearlsTaskExecutor.pit(grid, STAND, 1).plan();
		assertNotNull(plan);
		assertEquals(List.of(new BuildStep(BuildKind.PLACE, ROOF, new GoalPosition(-1, 65, 0, true))), plan.placements());
	}

	@Test void refusesPitsAnEndermanCouldStandBesideOrThatFloodOrBurn() {
		Grid grid = new Grid();
		// A two-deep dip next to the pit: an enderman fits in it beside the body.
		grid.air.add(new GoalPosition(1, 63, 1, true));
		grid.air.add(new GoalPosition(1, 62, 1, true));
		assertEquals("open_ring", CollectEnderPearlsTaskExecutor.pit(grid, STAND, 8).refusal());
		assertTrue(CollectEnderPearlsTaskExecutor.endermanStands(grid, new GoalPosition(1, 62, 1, true)));
		assertFalse(CollectEnderPearlsTaskExecutor.ringSafe(grid, PIT));

		Grid water = new Grid();
		water.water.add(new GoalPosition(1, 64, 0, true));
		assertEquals("fluid", CollectEnderPearlsTaskExecutor.pit(water, STAND, 8).refusal());

		Grid lava = new Grid();
		lava.lava.add(new GoalPosition(-1, 63, 1, true));
		assertEquals("lava_or_fire", CollectEnderPearlsTaskExecutor.pit(lava, STAND, 8).refusal());

		Grid bedrock = new Grid();
		bedrock.unbreakable.add(PIT);
		assertEquals("undiggable", CollectEnderPearlsTaskExecutor.pit(bedrock, STAND, 8).refusal());

		Grid hollow = new Grid();
		hollow.air.add(new GoalPosition(0, 62, 0, true));
		assertEquals("no_floor", CollectEnderPearlsTaskExecutor.pit(hollow, STAND, 8).refusal(), "digging would drop into a cave");

		Grid ceiling = new Grid();
		ceiling.solid.add(ROOF);
		assertEquals("no_room", CollectEnderPearlsTaskExecutor.pit(ceiling, STAND, 8).refusal());
	}

	@Test void aLedgeOneLevelUpBesideThePitIsFineButAStepDownIsNot() {
		// On flat ground the endermen stand one level above the pit floor: they overlap the body but cannot see its eyes.
		Grid grid = new Grid();
		assertTrue(CollectEnderPearlsTaskExecutor.ringSafe(grid, PIT));
		// A one-deep hole beside the pit lets one stand level with the body.
		grid.air.add(new GoalPosition(0, 63, 1, true));
		assertTrue(CollectEnderPearlsTaskExecutor.endermanStands(grid, new GoalPosition(0, 63, 1, true)));
		assertFalse(CollectEnderPearlsTaskExecutor.ringSafe(grid, PIT));
	}

	@Test void choosesTheCheapestPitNearTheFeetAndSkipsRejectedOnes() {
		Grid grid = new Grid();
		var plan = CollectEnderPearlsTaskExecutor.planSpot(grid, STAND, 8, Set.of()).orElseThrow();
		assertEquals(STAND, plan.stand(), "its own cell first");
		grid.solid.add(new GoalPosition(3, 65, 0, true));
		grid.solid.add(new GoalPosition(3, 64, 0, true));
		plan = CollectEnderPearlsTaskExecutor.planSpot(grid, STAND, 8, Set.of()).orElseThrow();
		assertEquals(new GoalPosition(2, 64, 0, true), plan.stand(), "two blocks of walking beat two blocks of building");
		plan = CollectEnderPearlsTaskExecutor.planSpot(grid, STAND, 8, Set.of(new GoalPosition(2, 64, 0, true))).orElseThrow();
		assertEquals(new GoalPosition(3, 64, -1, true), plan.stand(), "the next cell beside the wall");
		assertEquals(1, plan.placements().size());
		plan = CollectEnderPearlsTaskExecutor.planSpot(grid, STAND, 1, Set.of(new GoalPosition(2, 64, 0, true),
			new GoalPosition(3, 64, -1, true), new GoalPosition(3, 64, 1, true))).orElseThrow();
		assertEquals(1, plan.placements().size(), "with one block only a pit beside the wall will do");
		assertTrue(CollectEnderPearlsTaskExecutor.planSpot(grid, STAND, 8, Set.of(STAND)).orElseThrow().stand().x() != 0
			|| CollectEnderPearlsTaskExecutor.planSpot(grid, STAND, 8, Set.of(STAND)).orElseThrow().stand().z() != 0);
		Grid stone = new Grid();
		stone.allUnbreakable = true;
		assertTrue(CollectEnderPearlsTaskExecutor.planSpot(stone, STAND, 8, Set.of()).isEmpty());
	}

	@Test void buildsInOrderWalkDigDropPlaceAndOpensAClosedRoofFromOutside() {
		Grid grid = new Grid();
		SpotPlan plan = CollectEnderPearlsTaskExecutor.pit(grid, STAND, 3).plan();
		Vec3 away = new Vec3(5.5, 64, 0.5), onStand = new Vec3(0.5, 64, 0.5), inPit = new Vec3(0.5, 63, 0.5);
		assertEquals(BuildKind.NAVIGATE, CollectEnderPearlsTaskExecutor.buildAction(plan, grid, away, true).kind());
		assertEquals(BuildKind.DIG, CollectEnderPearlsTaskExecutor.buildAction(plan, grid, onStand, true).kind());
		grid.air.add(PIT);
		assertEquals(BuildKind.NUDGE, CollectEnderPearlsTaskExecutor.buildAction(plan, grid, new Vec3(0.95, 64, 0.5), true).kind(),
			"still on the rim");
		assertEquals(BuildKind.WAIT, CollectEnderPearlsTaskExecutor.buildAction(plan, grid, new Vec3(0.5, 63.6, 0.5), false).kind());
		var action = CollectEnderPearlsTaskExecutor.buildAction(plan, grid, inPit, true);
		assertEquals(BuildKind.PLACE, action.kind());
		assertEquals(new GoalPosition(1, 64, 0, true), action.cell());
		grid.solid.add(new GoalPosition(1, 64, 0, true));
		grid.solid.add(new GoalPosition(1, 65, 0, true));
		action = CollectEnderPearlsTaskExecutor.buildAction(plan, grid, inPit, true);
		assertEquals(ROOF, action.cell());
		assertFalse(CollectEnderPearlsTaskExecutor.safeIn(plan, grid, inPit));
		grid.solid.add(ROOF);
		assertEquals(BuildKind.READY, CollectEnderPearlsTaskExecutor.buildAction(plan, grid, inPit, true).kind());
		assertTrue(CollectEnderPearlsTaskExecutor.safeIn(plan, grid, inPit));
		assertEquals(BuildKind.BREAK_ROOF, CollectEnderPearlsTaskExecutor.buildAction(plan, grid, new Vec3(2.5, 64, 0.5), true).kind(),
			"no walking in under a roof");
		grid.solid.remove(ROOF);
		action = CollectEnderPearlsTaskExecutor.buildAction(plan, grid, new Vec3(2.5, 64, 0.5), true);
		assertEquals(BuildKind.NAVIGATE, action.kind());
		assertEquals(PIT, action.cell(), "back into the dug pit");
	}

	// ---- fighting decisions ----

	@Test void staresOnlyAtACalmVisibleEndermanFiveBlocksOrMoreAway() {
		Enderman near = enderman(1, 3.0, true, false, false);
		Enderman hidden = enderman(2, 8.0, false, false, false);
		Enderman angry = enderman(3, 9.0, true, false, true);
		Enderman good = enderman(4, 12.0, true, false, false);
		Enderman farther = enderman(5, 20.0, true, false, false);
		Enderman tooFar = enderman(6, 60.0, true, false, false);
		List<Enderman> all = List.of(near, hidden, angry, good, farther, tooFar);
		assertEquals(good, CollectEnderPearlsTaskExecutor.provokeTarget(all, Map.of(), 10, 1, 3).orElseThrow());
		assertEquals(farther, CollectEnderPearlsTaskExecutor.provokeTarget(all, Map.of(4, 50), 10, 1, 3).orElseThrow(),
			"one stared at in vain is left alone for a while");
		assertTrue(CollectEnderPearlsTaskExecutor.provokeTarget(all, Map.of(), 10, 1, 1).isEmpty(), "one angry at a time in the open");
	}

	@Test void hitsAngryEndermenInReachAndCalmOnesOnlyWhenSafeOrAllowed() {
		Enderman calm = enderman(1, 2.0, true, true, false);
		Enderman angry = enderman(2, 2.5, true, true, true);
		Enderman out = enderman(3, 5.0, true, false, true);
		assertEquals(angry, CollectEnderPearlsTaskExecutor.meleeTarget(List.of(calm, angry, out), false, false));
		assertNull(CollectEnderPearlsTaskExecutor.meleeTarget(List.of(calm, out), false, false));
		assertEquals(calm, CollectEnderPearlsTaskExecutor.meleeTarget(List.of(calm, out), true, false), "from the closed pit");
		assertEquals(calm, CollectEnderPearlsTaskExecutor.meleeTarget(List.of(calm), false, true), "the open fight may provoke");
		assertEquals(angry, CollectEnderPearlsTaskExecutor.meleeTarget(List.of(calm, angry), true, true), "the angry one first");
	}

	@Test void fetchesPearlsOnlyFromSafeGroundWithNoEndermanClose() {
		Vec3 feet = new Vec3(0.5, 63, 0.5);
		DroppedPearl pearl = new DroppedPearl(new Vec3(3.5, 64, 0.5), true);
		DroppedPearl inLava = new DroppedPearl(new Vec3(-3.5, 64, 0.5), false);
		DroppedPearl far = new DroppedPearl(new Vec3(30.5, 64, 0.5), true);
		assertEquals(pearl, CollectEnderPearlsTaskExecutor.pickupTarget(List.of(far, inLava, pearl), List.of(), feet, List.of()).orElseThrow());
		assertTrue(CollectEnderPearlsTaskExecutor.pickupTarget(List.of(far, inLava), List.of(), feet, List.of()).isEmpty());
		assertTrue(CollectEnderPearlsTaskExecutor.pickupTarget(List.of(pearl), List.of(), feet, List.of(pearl.position())).isEmpty(),
			"one given up on");
		Enderman angryFar = new Enderman(1, new Vec3(14.5, 64, 0.5), 14, true, false, true);
		Enderman calmBeside = new Enderman(2, new Vec3(4.5, 64, 1.5), 4, true, false, false);
		Enderman calmAway = new Enderman(3, new Vec3(12.5, 64, 0.5), 12, true, false, false);
		assertFalse(CollectEnderPearlsTaskExecutor.safeToFetch(pearl, List.of(angryFar), feet), "an angry one within 16 blocks");
		assertFalse(CollectEnderPearlsTaskExecutor.safeToFetch(pearl, List.of(calmBeside), feet));
		assertTrue(CollectEnderPearlsTaskExecutor.safeToFetch(pearl, List.of(calmAway), feet));
		assertTrue(CollectEnderPearlsTaskExecutor.autoPickup(new Vec3(1.9, 64, 1.9), feet), "diagonally beside the pit");
		assertFalse(CollectEnderPearlsTaskExecutor.autoPickup(new Vec3(2.5, 64, 0.5), feet));
	}

	@Test void endsOnPearlsThenDimensionThenRainThenTime() {
		assertEquals(Ending.SUCCEEDED, CollectEnderPearlsTaskExecutor.ending(arena(Dimension.OTHER, true, 12), 12, 1).orElseThrow());
		assertEquals(Ending.WRONG_DIMENSION, CollectEnderPearlsTaskExecutor.ending(arena(Dimension.OTHER, false, 0), 12, 1).orElseThrow());
		assertEquals(Ending.RAINING, CollectEnderPearlsTaskExecutor.ending(arena(Dimension.OVERWORLD, true, 0), 12, 1).orElseThrow());
		assertTrue(CollectEnderPearlsTaskExecutor.ending(arena(Dimension.NETHER, false, 3), 12, 100).isEmpty());
		assertTrue(CollectEnderPearlsTaskExecutor.ending(arena(Dimension.END, false, 3), 12, 100).isEmpty());
		assertEquals(Ending.TIMEOUT, CollectEnderPearlsTaskExecutor.ending(arena(Dimension.NETHER, false, 3), 12, 14_401).orElseThrow());
		assertEquals(6_000, CollectEnderPearlsTaskExecutor.timeoutTicks(1));
		assertEquals(14_400, CollectEnderPearlsTaskExecutor.timeoutTicks(12));
	}

	// ---- the hunt ----

	@Test void huntsFromAClosedPitWithoutTakingAHit() {
		World world = new World();
		world.enderman(new Vec3(10.5, 64, 0.5));
		world.enderman(new Vec3(-12.5, 64, 6.5));
		TaskTerminalEvent end = world.run(2, 3_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(end.message().startsWith("ender_pearls_collected pearls=2"), end.message());
		assertEquals(0, world.hitsTaken, "the pit kept the body safe");
		assertTrue(world.dug.contains(PIT));
		assertTrue(world.solid(ROOF), "the roof is on");
		assertEquals(3, world.placements);
		assertTrue(world.meleeHits >= 12, "two endermen of 40 health at 7 a hit: " + world.meleeHits);
		assertFalse(world.stareDistances.isEmpty());
		assertTrue(world.stareDistances.stream().allMatch(d -> d >= CollectEnderPearlsTaskExecutor.MIN_STARE_DISTANCE),
			"never looks a close one in the eyes: " + world.stareDistances);
		assertTrue(world.staredAtAngry == 0, "stops staring once it is angry");
		assertEquals(0, world.roofBreaks, "pearls beside the pit came in by themselves");
	}

	@Test void opensTheRoofForAFarPearlAndClosesItAgain() {
		World world = new World();
		world.enderman(new Vec3(10.5, 64, 0.5));
		world.enderman(new Vec3(-12.5, 64, 0.5));
		world.dropAt = new ArrayDeque<>(List.of(new Vec3(3.5, 64, 0.5)));
		TaskTerminalEvent end = world.run(2, 4_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertEquals(1, world.roofBreaks);
		assertEquals(4, world.placements, "the roof went back on");
		assertTrue(world.navigated.contains(new GoalPosition(3, 64, 0, true)), "walked to the pearl: " + world.navigated);
		assertTrue(world.navigated.contains(PIT), "walked back into the pit: " + world.navigated);
		assertEquals(0, world.hitsTaken);
	}

	@Test void atLowHealthEatsInThePitAndGoesOn() {
		World world = new World();
		world.enderman(new Vec3(10.5, 64, 0.5));
		world.enderman(new Vec3(-12.5, 64, 6.5));
		world.beforeTick = w -> {
			if (w.bodySafe() && !w.hurt) {
				w.hurt = true;
				w.health = 7;
				w.food = 10;
			}
		};
		TaskTerminalEvent end = world.run(2, 6_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(world.eaten >= 1, "ate while healing");
		assertTrue(world.firstStareHealth >= CollectEnderPearlsTaskExecutor.RESUME_HEALTH || world.firstStareHealth == 20,
			"no enderman provoked below 16: " + world.firstStareHealth);
	}

	@Test void lowHealthWithNothingToEatFailsOutOfFood() {
		World world = new World();
		world.enderman(new Vec3(10.5, 64, 0.5));
		world.foodItems = 0;
		world.beforeTick = w -> {
			if (w.bodySafe() && !w.hurt) {
				w.hurt = true;
				w.health = 7;
				w.food = 10;
			}
		};
		TaskTerminalEvent end = world.run(2, 3_000);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("out_of_food pearls=0"), end.message());
	}

	@Test void withoutRoofBlocksFightsInTheOpenBehindTheShield() {
		World world = new World();
		world.blocks = 0;
		world.hasShield = true;
		world.enderman(new Vec3(10.5, 64, 0.5));
		TaskTerminalEvent end = world.run(1, 3_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertEquals(0, world.placements);
		assertTrue(world.dug.isEmpty(), "no pit without a roof block");
		assertTrue(world.guards > 0, "shield up between swings");
		assertTrue(end.message().contains("pit=none (no_pit_site"), end.message());
	}

	@Test void inTheOpenProvokesOnlyWithEnoughHealth() {
		World world = new World();
		world.blocks = 0;
		world.health = 10;
		world.enderman(new Vec3(10.5, 64, 0.5));
		TaskTerminalEvent end = world.run(1, 3_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(world.firstStareHealth >= CollectEnderPearlsTaskExecutor.OPEN_FIGHT_HEALTH, "stared at " + world.firstStareHealth);
	}

	@Test void inTheOpenAtLowHealthWithAnAngryEndermanCloseHandsBack() {
		World world = new World();
		world.blocks = 0;
		world.health = 7;
		world.enderman(new Vec3(5.5, 64, 0.5)).angry = true;
		TaskTerminalEvent end = world.run(1, 200);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("low_health pearls=0"), end.message());
	}

	@Test void undiggableGroundFallsBackToTheOpenFightAndSaysWhy() {
		World world = new World();
		world.allUnbreakable = true;
		world.enderman(new Vec3(10.5, 64, 0.5));
		TaskTerminalEvent end = world.run(1, 3_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(end.message().contains("undiggable="), end.message());
	}

	@Test void noEndermenInSightFailsWithAHint() {
		World world = new World();
		world.daytime = true;
		TaskTerminalEvent end = world.run(1, 1_000);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("no_endermen_found pearls=0"), end.message());
		assertTrue(end.message().contains("it is day"), end.message());
		assertTrue(world.dug.isEmpty(), "nothing dug for no enderman");
	}

	@Test void rainFailsAtOnce() {
		World world = new World();
		world.raining = true;
		world.enderman(new Vec3(10.5, 64, 0.5));
		TaskTerminalEvent end = world.run(1, 10);
		assertTrue(end.message().startsWith("raining pearls=0"), end.message());
		assertEquals(1, world.ticks);
	}

	@Test void anotherDimensionFails() {
		World world = new World();
		world.dimension = Dimension.OTHER;
		TaskTerminalEvent end = world.run(1, 10);
		assertTrue(end.message().startsWith("wrong_dimension"), end.message());
	}

	@Test void endsAtOnceWhenThePearlsAreAlreadyCarried() {
		World world = new World();
		world.pearls = 12;
		TaskTerminalEvent end = world.run(12, 10);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState());
		assertTrue(end.message().startsWith("ender_pearls_collected pearls=12"), end.message());
	}

	@Test void endermenThatNeverAngerTimeOut() {
		World world = new World();
		world.enderman(new Vec3(10.5, 64, 0.5)).provokable = false;
		TaskTerminalEvent end = world.run(1, 7_000);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("timeout pearls=0"), end.message());
		assertTrue(world.ticks <= CollectEnderPearlsTaskExecutor.timeoutTicks(1) + 2);
	}

	@Test void countDefaultsToTwelveAndIsBounded() {
		assertEquals(12, CollectEnderPearlsStepArgs.parse(new JsonObject()).count());
		JsonObject five = new JsonObject();
		five.addProperty("count", 5);
		assertEquals(5, CollectEnderPearlsStepArgs.parse(five).count());
		JsonObject zero = new JsonObject();
		zero.addProperty("count", 0);
		assertThrows(IllegalArgumentException.class, () -> CollectEnderPearlsStepArgs.parse(zero));
		JsonObject many = new JsonObject();
		many.addProperty("count", 17);
		assertThrows(IllegalArgumentException.class, () -> CollectEnderPearlsStepArgs.parse(many));
		JsonObject fraction = new JsonObject();
		fraction.addProperty("count", 2.5);
		assertThrows(IllegalArgumentException.class, () -> CollectEnderPearlsStepArgs.parse(fraction));
		JsonObject unknown = new JsonObject();
		unknown.addProperty("radius", 3);
		assertThrows(IllegalArgumentException.class, () -> CollectEnderPearlsStepArgs.parse(unknown));
	}

	private static Enderman enderman(int id, double distance, boolean visible, boolean inReach, boolean angry) {
		return new Enderman(id, new Vec3(distance, 64, 0), distance, visible, inReach, angry);
	}

	private static Arena arena(Dimension dimension, boolean raining, int pearls) {
		return new Arena(dimension, raining, false, new Vec3(0.5, 64, 0.5), true, 20, 20, pearls, List.of(), List.of(), false, true, 3);
	}

	/** Flat solid ground with its top at y=64, plus edits. */
	static class Grid implements CollectEnderPearlsTaskExecutor.Cells {
		final Set<GoalPosition> solid = new HashSet<>(), air = new HashSet<>(), water = new HashSet<>(), lava = new HashSet<>(),
			unbreakable = new HashSet<>();
		boolean yields, allUnbreakable;

		public boolean solid(GoalPosition cell) { return solid.contains(cell) || cell.y() <= 63 && !air.contains(cell) && !fluid(cell); }
		public boolean passable(GoalPosition cell) { return !solid(cell); }
		public boolean fluid(GoalPosition cell) { return water.contains(cell) || lava.contains(cell); }
		public boolean hazard(GoalPosition cell) { return lava.contains(cell); }
		public boolean diggable(GoalPosition cell) { return solid(cell) && !allUnbreakable && !unbreakable.contains(cell); }
		public boolean yieldsBlock(GoalPosition cell) { return yields; }
	}

	/**
	 * Flat overworld ground at night. Endermen stand calm until looked in the eyes from five blocks or more (or hit), then
	 * walk to the body and stop 1.3 blocks away; they hit for 7 every 20 ticks unless the body is in a roofed pit, which
	 * they cannot see into from within 2.5 blocks, or behind a raised shield. A sword hit does 7 of their 40 health and a
	 * dead one drops a pearl where it stood (or at {@link #dropAt}). The body picks up pearls within reach of its box and
	 * walks a block a tick; digging takes six ticks and the body drops into a dug cell under it.
	 */
	private static final class World extends Grid implements CollectEnderPearlsTaskExecutor.Environment, NavigationFacade {
		Vec3 feet = new Vec3(0.5, 64, 0.5);
		float health = 20.0F;
		int food = 20, foodItems = 4, pearls, blocks = 8;
		Dimension dimension = Dimension.OVERWORLD;
		boolean raining, daytime, hasShield, hurt;
		final Set<GoalPosition> dug = new HashSet<>();
		final List<FakeEnderman> endermen = new ArrayList<>();
		final List<FakePearl> pearlsOnGround = new ArrayList<>();
		Deque<Vec3> dropAt = new ArrayDeque<>();
		final List<GoalPosition> navigated = new ArrayList<>();
		final List<Double> stareDistances = new ArrayList<>();
		int meleeHits, hitsTaken, placements, roofBreaks, guards, eaten, staredAtAngry, ticks;
		float firstStareHealth = -1;
		Consumer<World> beforeTick = w -> {};
		private boolean guardThisTick;
		private int nextId = 1, eatTicks, digTicks;
		private GoalPosition goal, digging;
		private final Queue<String> events = new ArrayDeque<>();
		private final CollectEnderPearlsTaskExecutor executor = new CollectEnderPearlsTaskExecutor(this, this);

		final class FakeEnderman {
			final int id = nextId++;
			Vec3 position;
			float health = 40.0F;
			boolean angry, provokable = true;
			int stared;
			FakeEnderman(Vec3 position) { this.position = position; }
		}

		record FakePearl(Vec3 position, int[] age) {}

		FakeEnderman enderman(Vec3 position) {
			FakeEnderman enderman = new FakeEnderman(position);
			endermen.add(enderman);
			return enderman;
		}

		@Override public boolean solid(GoalPosition cell) { return super.solid(cell) && !dug.contains(cell); }

		boolean bodySafe() {
			GoalPosition at = CollectEnderPearlsTaskExecutor.cell(feet);
			return feet.y < 63.5 && dug.contains(at) && solid(new GoalPosition(at.x(), at.y() + 2, at.z(), true));
		}

		private Vec3 eye() { return feet.add(0, 1.62, 0); }
		private double horizontal(Vec3 a, Vec3 b) { return Math.hypot(a.x - b.x, a.z - b.z); }
		private double distance(FakeEnderman e) { return eye().distanceTo(e.position.add(0, 1.45, 0)); }
		private boolean sees(FakeEnderman e) { return !bodySafe() || horizontal(e.position, feet) > 2.5; }

		TaskTerminalEvent run(int count, int maxTicks) {
			WorldTaskRequest request = WorldTaskRequest.collectEnderPearls("pearls", "job", new CollectEnderPearlsStepArgs(count));
			for (int i = 0; i < maxTicks; i++) {
				ticks++;
				beforeTick.accept(this);
				step();
				// The shield raised on the previous tick is what blocks this tick's hit.
				guardThisTick = false;
				var session = new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, ticks);
				var end = executor.tick(session, Optional.of(request));
				if (end.isPresent()) return end.get();
			}
			fail("no terminal after " + maxTicks + " ticks: " + executor.snapshot().lastPathEvent());
			return null;
		}

		private void step() {
			if (goal != null) {
				Vec3 center = new Vec3(goal.x() + 0.5, goal.y(), goal.z() + 0.5);
				Vec3 flat = new Vec3(center.x - feet.x, 0, center.z - feet.z);
				if (flat.length() <= 1.0 + 1e-6) {
					feet = center;
					goal = null;
					events.add("AT_GOAL");
				} else feet = new Vec3(feet.x, feet.y, feet.z).add(flat.normalize()).with(net.minecraft.core.Direction.Axis.Y,
					Math.max(64, feet.y));
			}
			for (FakeEnderman e : endermen) {
				if (!e.angry) continue;
				Vec3 flat = new Vec3(feet.x - e.position.x, 0, feet.z - e.position.z);
				if (flat.length() > 1.3) e.position = e.position.add(flat.normalize().scale(Math.min(0.4, flat.length() - 1.3)));
				boolean overlaps = feet.y < e.position.y + 2.9 && feet.y + 1.8 > e.position.y;
				if (flat.length() <= 1.8 && overlaps && !bodySafe() && ticks % 20 == 0) {
					if (guardThisTick) continue;
					health -= 7;
					hitsTaken++;
				}
			}
			for (var it = endermen.iterator(); it.hasNext(); ) {
				FakeEnderman e = it.next();
				if (e.health > 0) continue;
				it.remove();
				Vec3 at = dropAt.isEmpty() ? new Vec3(e.position.x, 64, e.position.z) : dropAt.poll();
				pearlsOnGround.add(new FakePearl(at, new int[] {0}));
			}
			for (var it = pearlsOnGround.iterator(); it.hasNext(); ) {
				FakePearl pearl = it.next();
				if (++pearl.age()[0] >= 10 && CollectEnderPearlsTaskExecutor.autoPickup(pearl.position(), feet)) {
					pearls++;
					it.remove();
				}
			}
			if (food >= 18 && health < 20) health = Math.min(20, health + 0.1F);
		}

		// Environment
		public Arena observe() {
			List<Enderman> seen = endermen.stream().map(e -> new Enderman(e.id, e.position, distance(e), sees(e), distance(e) <= 3.0,
				e.angry)).toList();
			List<DroppedPearl> drops = pearlsOnGround.stream().map(p -> new DroppedPearl(p.position(), true)).toList();
			return new Arena(dimension, raining, daytime, feet, goal == null, health, food, pearls, seen, drops, hasShield,
				foodItems > 0, blocks);
		}
		public CollectEnderPearlsTaskExecutor.Cells cells() { return this; }
		public CollectEnderPearlsTaskExecutor.PlaceResult place(GoalPosition cell, GoalPosition support) {
			if (solid(cell)) return CollectEnderPearlsTaskExecutor.PlaceResult.PLACED;
			if (!solid(support) || !passable(cell)) return CollectEnderPearlsTaskExecutor.PlaceResult.BLOCKED;
			GoalPosition at = CollectEnderPearlsTaskExecutor.cell(feet);
			assertNotEquals(at, cell, "never places into the body");
			assertNotEquals(new GoalPosition(at.x(), at.y() + 1, at.z(), true), cell, "never places into the body");
			if (blocks <= 0) return CollectEnderPearlsTaskExecutor.PlaceResult.NO_BLOCK;
			blocks--;
			placements++;
			air.remove(cell);
			solid.add(cell);
			return CollectEnderPearlsTaskExecutor.PlaceResult.WORKING;
		}
		public CollectEnderPearlsTaskExecutor.BreakResult dig(GoalPosition cell) {
			if (passable(cell)) {
				digging = null;
				return CollectEnderPearlsTaskExecutor.BreakResult.GONE;
			}
			if (allUnbreakable || unbreakable.contains(cell)) return CollectEnderPearlsTaskExecutor.BreakResult.UNBREAKABLE;
			if (eye().distanceTo(new Vec3(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5)) > 4.5)
				return CollectEnderPearlsTaskExecutor.BreakResult.OUT_OF_REACH;
			if (!cell.equals(digging)) {
				digging = cell;
				digTicks = 0;
			}
			if (++digTicks < 6) return CollectEnderPearlsTaskExecutor.BreakResult.WORKING;
			if (solid.remove(cell)) {
				roofBreaks++;
				blocks++;
			} else dug.add(cell);
			digging = null;
			GoalPosition at = CollectEnderPearlsTaskExecutor.cell(feet);
			if (cell.x() == at.x() && cell.z() == at.z() && cell.y() == at.y() - 1) feet = new Vec3(feet.x, cell.y(), feet.z);
			return CollectEnderPearlsTaskExecutor.BreakResult.WORKING;
		}
		public void stopDigging() { digging = null; }
		public void nudge(GoalPosition cell) { feet = new Vec3(cell.x() + 0.5, cell.y(), cell.z() + 0.5); }
		public CollectEnderPearlsTaskExecutor.MeleeResult melee(int id, boolean guard) {
			FakeEnderman e = endermen.stream().filter(x -> x.id == id).findFirst().orElse(null);
			if (e == null || distance(e) > 3.0) return CollectEnderPearlsTaskExecutor.MeleeResult.OUT_OF_REACH;
			if (ticks % 12 != 0) {
				if (guard) {
					guards++;
					guardThisTick = true;
				}
				return CollectEnderPearlsTaskExecutor.MeleeResult.COOLDOWN;
			}
			meleeHits++;
			e.health -= 7;
			e.angry = true;
			return CollectEnderPearlsTaskExecutor.MeleeResult.HIT;
		}
		public void stare(int id) {
			FakeEnderman e = endermen.stream().filter(x -> x.id == id).findFirst().orElseThrow();
			if (firstStareHealth < 0) firstStareHealth = health;
			stareDistances.add(distance(e));
			if (e.angry) staredAtAngry++;
			if (sees(e) && distance(e) >= 5 && e.provokable && ++e.stared >= 5) e.angry = true;
		}
		public void watch(int id, boolean guard) {
			if (guard) {
				guards++;
				guardThisTick = true;
			}
		}
		public void lowerShield() {}
		public CollectEnderPearlsTaskExecutor.ConsumeResult consume() {
			if (foodItems <= 0) return CollectEnderPearlsTaskExecutor.ConsumeResult.NOTHING;
			if (++eatTicks < 32) return CollectEnderPearlsTaskExecutor.ConsumeResult.CONSUMING;
			eatTicks = 0;
			foodItems--;
			eaten++;
			food = Math.min(20, food + 8);
			return CollectEnderPearlsTaskExecutor.ConsumeResult.DONE;
		}
		public void stopConsuming() { eatTicks = 0; }
		public void release() {}

		// Navigation: walks a block a tick; no route out of a roofed pit or into a cell without headroom.
		public boolean isLoaded() { return true; }
		public void startFollow(String s) { fail("no following"); }
		public void startNavigate(GoalPosition p, NavigationOptions o) {
			navigated.add(p);
			assertTrue(o.walkOnly(), "walks only");
			boolean noRoom = !passable(p) || !passable(new GoalPosition(p.x(), p.y() + 1, p.z(), true));
			if (bodySafe() || noRoom) {
				goal = null;
				events.add("CALC_FAILED");
			} else goal = p;
		}
		public void startNavigateNear(GoalPosition p, int r, NavigationOptions o) { startNavigate(p, o); }
		public boolean processActive() { return goal != null; }
		public void cancel() { goal = null; }
		public Optional<String> activeProcessName() { return Optional.empty(); }
		public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		public Optional<String> pollPathEvent() { return Optional.ofNullable(events.poll()); }
		public boolean navigationGoalReached(GoalPosition p) { return false; }
	}
}
