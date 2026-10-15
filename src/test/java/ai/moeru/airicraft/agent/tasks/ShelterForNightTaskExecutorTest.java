package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.reflex.EmergencyBunker;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ShelterForNightTaskExecutorTest {
	private static final BlockPos FEET = new BlockPos(0, 64, 0);

	// ---- Night and leaving ----

	@Test void sheltersFromDuskUntilJustAfterSunrise() {
		assertFalse(ShelterForNightTaskExecutor.shelterTime(6_000), "noon");
		assertFalse(ShelterForNightTaskExecutor.shelterTime(10_999));
		assertTrue(ShelterForNightTaskExecutor.shelterTime(11_000), "dusk: dig before the mobs spawn at about 13,000");
		assertTrue(ShelterForNightTaskExecutor.shelterTime(18_000), "midnight");
		assertTrue(ShelterForNightTaskExecutor.shelterTime(23_600), "sunrise is still night for leaving");
		assertTrue(ShelterForNightTaskExecutor.shelterTime(0));
		assertTrue(ShelterForNightTaskExecutor.shelterTime(299));
		assertFalse(ShelterForNightTaskExecutor.shelterTime(300), "morning");
		assertTrue(ShelterForNightTaskExecutor.shelterTime(24_000L * 5 + 13_000), "the day count does not matter");
	}

	@Test void leavesInTheMorningOrAfterTheCapButNotPastAMobAtTheTop() {
		var stay = ShelterForNightTaskExecutor.Leave.STAY;
		assertEquals(stay, ShelterForNightTaskExecutor.leave(18_000, 5_000, 14_000, false, 0));
		assertEquals(ShelterForNightTaskExecutor.Leave.MORNING, ShelterForNightTaskExecutor.leave(400, 13_000, 14_000, false, 1));
		assertEquals(stay, ShelterForNightTaskExecutor.leave(400, 13_000, 14_000, true, 10), "a zombie by the hole");
		assertEquals(ShelterForNightTaskExecutor.Leave.MORNING, ShelterForNightTaskExecutor.leave(400, 14_000, 14_000, true,
			ShelterForNightTaskExecutor.MOB_LINGER_TICKS), "a mob that never leaves is the reflex's to fight");
		assertEquals(ShelterForNightTaskExecutor.Leave.MAX_WAIT, ShelterForNightTaskExecutor.leave(18_000, 600, 600, false, 1));
		assertEquals(stay, ShelterForNightTaskExecutor.leave(18_000, 600, 600, true, 1));
	}

	// ---- Site ----

	@Test void triesTheFeetFirstThenNeighboursOneAndTwoBlocksAway() {
		List<BlockPos> candidates = ShelterForNightTaskExecutor.siteCandidates(FEET);
		assertEquals(FEET, candidates.getFirst());
		assertEquals(1 + 2 * 8 * 3, candidates.size());
		assertEquals(new BlockPos(0, 64, -1), candidates.get(1), "a cardinal neighbour at foot level next");
		assertTrue(candidates.subList(1, 25).stream().allMatch(p -> Math.max(Math.abs(p.getX()), Math.abs(p.getZ())) == 1),
			"every column one away before any two away");
		assertTrue(candidates.stream().allMatch(p -> Math.abs(p.getX()) <= 2 && Math.abs(p.getZ()) <= 2 && Math.abs(p.getY() - 64) <= 1));
		assertEquals(candidates.size(), new HashSet<>(candidates).size());
	}

	@Test void digsUnderTheFeetWhenTheColumnFits() {
		Ground ground = new Ground();
		var choice = ShelterForNightTaskExecutor.chooseSite(FEET, 4, top -> ground.facts(top), Set.of());
		assertNull(choice.failure());
		assertEquals(FEET, choice.plan().top());
		assertEquals(new BlockPos(0, 61, 0), choice.plan().bottom());
	}

	@Test void movesToANeighbouringColumnWhenTheFeetSitByWater() {
		Ground ground = new Ground();
		ground.water.add(new BlockPos(-1, 62, 0));
		var choice = ShelterForNightTaskExecutor.chooseSite(FEET, 4, top -> ground.facts(top), Set.of());
		assertNotNull(choice.plan());
		assertNotEquals(FEET, choice.plan().top());
		for (BlockPos cell : choice.plan().dig())
			for (Direction side : Direction.Plane.HORIZONTAL) assertFalse(ground.water.contains(cell.relative(side)), "no water beside the shaft");
		var skipped = ShelterForNightTaskExecutor.chooseSite(FEET, 4, top -> ground.facts(top), Set.of(choice.plan().top()));
		assertNotEquals(choice.plan().top(), skipped.plan().top(), "an unreachable column is not tried twice");
	}

	@Test void aNeighbourMustBeStandableToWalkOnto() {
		Ground ground = new Ground();
		ground.water.add(new BlockPos(0, 62, 1));
		ground.blocked.add(new BlockPos(0, 64, -1));
		var choice = ShelterForNightTaskExecutor.chooseSite(FEET, 4, top -> ground.facts(top), Set.of());
		assertNotEquals(new BlockPos(0, 64, -1), choice.plan().top(), "a block in the way of the feet");
	}

	@Test void tellsNoSiteFromNoRoofBlock() {
		Ground flooded = new Ground();
		for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) flooded.water.add(new BlockPos(x, 61, z));
		assertEquals(ShelterForNightTaskExecutor.NO_SHELTER_SITE,
			ShelterForNightTaskExecutor.chooseSite(FEET, 4, top -> flooded.facts(top), Set.of()).failure());
		Ground sandstone = new Ground();
		sandstone.yieldsRoof = false;
		assertEquals(ShelterForNightTaskExecutor.NO_ROOF_BLOCK,
			ShelterForNightTaskExecutor.chooseSite(FEET, 0, top -> sandstone.facts(top), Set.of()).failure());
		assertNotNull(ShelterForNightTaskExecutor.chooseSite(FEET, 1, top -> sandstone.facts(top), Set.of()).plan(), "a carried block roofs it");
		Ground soil = new Ground();
		assertNotNull(ShelterForNightTaskExecutor.chooseSite(FEET, 0, top -> soil.facts(top), Set.of()).plan(), "the dug dirt roofs it");
	}

	@Test void recognisesAShelterItAlreadyStandsIn() {
		Ground ground = new Ground();
		BlockPos bottom = new BlockPos(0, 61, 0);
		ground.air.addAll(List.of(bottom, bottom.above()));
		var plan = ShelterForNightTaskExecutor.enclosedShelter(ground, bottom);
		assertNotNull(plan);
		assertEquals(FEET, plan.top());
		assertEquals(new BlockPos(0, 63, 0), plan.roof());
		assertEquals(bottom, plan.bottom());
		assertEquals(63, plan.roofSupport().getY());
		ground.air.add(new BlockPos(1, 62, 0));
		assertNull(ShelterForNightTaskExecutor.enclosedShelter(ground, bottom), "an open side is no shelter");
		assertNull(ShelterForNightTaskExecutor.enclosedShelter(ground, FEET), "open sky over the head");
	}

	// ---- Exit ----

	@Test void pillarsWithEnoughBlocksOtherwiseDigsStairs() {
		assertEquals(ShelterForNightTaskExecutor.ExitMode.PILLAR, ShelterForNightTaskExecutor.exitMode(2, 3), "two blocks, then step out");
		assertEquals(ShelterForNightTaskExecutor.ExitMode.PILLAR, ShelterForNightTaskExecutor.exitMode(64, 3));
		assertEquals(ShelterForNightTaskExecutor.ExitMode.STAIRS, ShelterForNightTaskExecutor.exitMode(1, 3));
		assertEquals(ShelterForNightTaskExecutor.ExitMode.STAIRS, ShelterForNightTaskExecutor.exitMode(0, 1));
		assertEquals(ShelterForNightTaskExecutor.ExitMode.PILLAR, ShelterForNightTaskExecutor.exitMode(1, 1));
	}

	@Test void aPillarTowersUntilTheFeetReachTheTop() {
		assertEquals(ShelterForNightTaskExecutor.PillarStep.TOWER, ShelterForNightTaskExecutor.pillarStep(61, 64, true, 3));
		assertEquals(ShelterForNightTaskExecutor.PillarStep.TOWER, ShelterForNightTaskExecutor.pillarStep(62, 64, false, 2), "places mid-jump");
		assertEquals(ShelterForNightTaskExecutor.PillarStep.FALLING, ShelterForNightTaskExecutor.pillarStep(64, 64, false, 0));
		assertEquals(ShelterForNightTaskExecutor.PillarStep.OUT, ShelterForNightTaskExecutor.pillarStep(64, 64, true, 0));
		assertTrue(ShelterForNightTaskExecutor.atTop(64, 64), "a stuck climb at the shaft top is out");
		assertFalse(ShelterForNightTaskExecutor.atTop(63, 64));
		assertEquals(ShelterForNightTaskExecutor.PillarStep.NO_BLOCKS, ShelterForNightTaskExecutor.pillarStep(63, 64, true, 0));
	}

	@Test void aStaircaseClearsHeadroomAndTheStepThenClimbs() {
		Ground ground = new Ground();
		BlockPos bottom = new BlockPos(0, 61, 0);
		ground.air.addAll(List.of(bottom, bottom.above(), bottom.above(2)));
		var first = ShelterForNightTaskExecutor.stairAction(bottom, Direction.EAST, 64, true, ground::open);
		assertEquals(ShelterForNightTaskExecutor.StairAction.Kind.BREAK, first.kind());
		assertEquals(new BlockPos(1, 62, 0), first.cell(), "the next step's feet cell");
		ground.air.add(first.cell());
		assertEquals(new BlockPos(1, 63, 0), ShelterForNightTaskExecutor.stairAction(bottom, Direction.EAST, 64, true, ground::open).cell());
		ground.air.add(new BlockPos(1, 63, 0));
		var climb = ShelterForNightTaskExecutor.stairAction(bottom, Direction.EAST, 64, true, ground::open);
		assertEquals(ShelterForNightTaskExecutor.StairAction.Kind.CLIMB, climb.kind());
		assertEquals(new BlockPos(1, 62, 0), climb.cell());
		assertEquals(ShelterForNightTaskExecutor.StairAction.Kind.WAIT,
			ShelterForNightTaskExecutor.stairAction(bottom, Direction.EAST, 64, false, ground::open).kind(), "mid-jump");
		// On the step: the cell over the head comes first.
		var next = ShelterForNightTaskExecutor.stairAction(new BlockPos(1, 62, 0), Direction.EAST, 64, true, ground::open);
		assertEquals(ShelterForNightTaskExecutor.StairAction.breaking(new BlockPos(2, 63, 0)), next, "open sky over the head; the next step");
		assertEquals(ShelterForNightTaskExecutor.StairAction.Kind.DONE,
			ShelterForNightTaskExecutor.stairAction(new BlockPos(3, 64, 0), Direction.EAST, 64, true, ground::open).kind());
	}

	@Test void theStaircaseAvoidsWaterAndPicksTheCheapestWall() {
		Ground ground = new Ground();
		BlockPos bottom = new BlockPos(0, 61, 0);
		ground.air.addAll(List.of(bottom, bottom.above(), bottom.above(2)));
		ground.ticks.put(new BlockPos(0, 62, -1), 90);
		ground.ticks.put(new BlockPos(1, 62, 0), 60);
		ground.ticks.put(new BlockPos(-1, 62, 0), 60);
		ground.water.add(new BlockPos(-1, 63, 1));
		assertEquals(Optional.of(Direction.EAST), ShelterForNightTaskExecutor.stairDirection(bottom, 64, ground));
		ground.water.add(new BlockPos(2, 63, 1));
		assertEquals(Optional.of(Direction.NORTH), ShelterForNightTaskExecutor.stairDirection(bottom, 64, ground),
			"south and west touch water, east now too; north is dearer but dry");
		ground.unbreakable = true;
		assertEquals(Optional.empty(), ShelterForNightTaskExecutor.stairDirection(bottom, 64, ground));
	}

	@Test void waitingInTheShelterLeavesTheHandsFree() {
		var waiting = new TaskExecutionSnapshot(TaskExecutionState.RUNNING, "t", null, "ShelterForNight", "phase=WAIT step=wait", null, null);
		var digging = new TaskExecutionSnapshot(TaskExecutionState.RUNNING, "t", null, "ShelterForNight", "phase=DIG step=dig", null, null);
		assertTrue(ShelterForNightTaskExecutor.waitingInShelter(waiting));
		assertFalse(ShelterForNightTaskExecutor.waitingInShelter(digging));
		assertFalse(ShelterForNightTaskExecutor.waitingInShelter(TaskExecutionSnapshot.idle()));
	}

	@Test void maxWaitDefaultsToTheCapAndStaysWithinIt() {
		assertEquals(ShelterForNightStepArgs.MAX_WAIT_TICKS, ShelterForNightStepArgs.parse(new JsonObject()).maxWaitTicks());
		JsonObject args = new JsonObject();
		args.addProperty("maxWaitTicks", 14_001);
		assertThrows(IllegalArgumentException.class, () -> ShelterForNightStepArgs.parse(args));
		args.addProperty("maxWaitTicks", 2_000);
		assertEquals(2_000, ShelterForNightStepArgs.parse(args).maxWaitTicks());
		JsonObject unknown = new JsonObject();
		unknown.addProperty("untilTick", 5);
		assertThrows(IllegalArgumentException.class, () -> ShelterForNightStepArgs.parse(unknown));
	}

	// ---- The task ----

	@Test void digsInWaitsTheNightAndPillarsOutInTheMorning() {
		Fixture f = new Fixture(ShelterForNightStepArgs.MAX_WAIT_TICKS);
		f.tick(4);
		assertEquals(3, f.env.digCalls, "three blocks dug");
		f.tick(2);
		assertTrue(f.env.roof, "roofed");
		assertTrue(ShelterForNightTaskExecutor.waitingInShelter(f.executor.snapshot()), f.snapshotText());
		f.tick(100);
		assertEquals(List.of(), f.events, "night: stays");
		assertTrue(f.snapshotText().startsWith("phase=WAIT "), f.snapshotText());
		f.env.dayTime = 400;
		f.tick(1);
		assertTrue(f.snapshotText().startsWith("phase=EXIT "), f.snapshotText());
		f.tick(80);
		assertEquals(1, f.events.size(), f.snapshotText());
		var end = f.events.getFirst();
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState());
		assertTrue(end.message().startsWith("sheltered_until_morning waited="), end.message());
		assertTrue(end.message().contains("slept=false exit=pillar"), end.message());
		assertEquals(64, f.env.block.getY(), "out on the surface");
		assertTrue(f.nav.goals.isEmpty(), "dug where it stood");
	}

	@Test void byDayItDoesNotDig() {
		Fixture f = new Fixture(ShelterForNightStepArgs.MAX_WAIT_TICKS);
		f.env.dayTime = 24_000L * 3 + 5_000;
		f.tick(1);
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
		assertTrue(f.events.getFirst().message().startsWith("already_day"), f.events.getFirst().message());
		assertEquals(0, f.env.digCalls);
	}

	@Test void onlyInTheOverworld() {
		Fixture f = new Fixture(ShelterForNightStepArgs.MAX_WAIT_TICKS);
		f.env.overworld = false;
		f.tick(1);
		assertEquals(TaskExecutionState.FAILED, f.events.getFirst().terminalState());
		assertTrue(f.events.getFirst().message().startsWith("wrong_dimension"), f.events.getFirst().message());
		assertTrue(f.events.getFirst().message().contains("health=") && f.events.getFirst().message().contains("pos=0,64,0"));
	}

	@Test void refusesWithoutASiteOrARoofBlock() {
		Fixture noSite = new Fixture(ShelterForNightStepArgs.MAX_WAIT_TICKS);
		for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) noSite.env.ground.water.add(new BlockPos(x, 61, z));
		noSite.tick(1);
		assertTrue(noSite.events.getFirst().message().startsWith("no_shelter_site"), noSite.events.toString());
		Fixture noRoof = new Fixture(ShelterForNightStepArgs.MAX_WAIT_TICKS);
		noRoof.env.blocks = 0;
		noRoof.env.ground.yieldsRoof = false;
		noRoof.tick(1);
		assertTrue(noRoof.events.getFirst().message().startsWith("no_roof_block"), noRoof.events.toString());
		assertEquals(TaskFailureCode.MISSING_ITEM, noRoof.events.getFirst().failureCode());
		assertEquals(0, noRoof.env.digCalls, "refused before digging");
	}

	@Test void stepsOntoTheNextColumnWhenItsOwnDoesNotFit() {
		Fixture f = new Fixture(ShelterForNightStepArgs.MAX_WAIT_TICKS);
		f.env.ground.water.add(new BlockPos(0, 62, 1));
		f.tick(2);
		assertTrue(f.nav.goals.isEmpty(), "one step over needs no route");
		assertEquals(new BlockPos(0, 64, -1), f.env.block);
		f.tick(4);
		assertEquals(3, f.env.digCalls, "digs there");
		assertEquals(new BlockPos(0, 61, -1), f.env.block);
	}

	@Test void walksToAColumnTwoBlocksAway() {
		Fixture f = new Fixture(ShelterForNightStepArgs.MAX_WAIT_TICKS);
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) f.env.ground.water.add(new BlockPos(x, 62, z));
		f.tick(2);
		assertEquals(List.of(new GoalPosition(2, 64, -2, true)), f.nav.goals);
		assertEquals(NavigationOptions.WALK_ONLY, f.nav.options, "walks there without edits");
		f.tick(4);
		assertTrue(f.env.digCalls > 0, "digs once there");
		assertEquals(2, f.env.block.getX());
		assertEquals(-2, f.env.block.getZ());
	}

	@Test void aMobByTheTopKeepsTheBodyInUntilItLingersTooLong() {
		Fixture f = new Fixture(ShelterForNightStepArgs.MAX_WAIT_TICKS);
		f.tick(8);
		f.env.hostile = true;
		f.env.dayTime = 400;
		f.tick(ShelterForNightTaskExecutor.MOB_LINGER_TICKS - 5);
		assertTrue(f.snapshotText().startsWith("phase=WAIT ") && f.snapshotText().contains("mob_at_top"), f.snapshotText());
		f.tick(10);
		assertTrue(f.snapshotText().startsWith("phase=EXIT "), f.snapshotText());
	}

	@Test void theWaitCapEndsTheNightEarly() {
		Fixture f = new Fixture(200);
		f.tick(8);
		f.tick(200);
		f.tick(80);
		assertEquals(1, f.events.size(), f.snapshotText());
		assertTrue(f.events.getFirst().message().startsWith("sheltered_until_max_wait waited=200 "), f.events.getFirst().message());
	}

	@Test void aBlockedPillarFallsBackToStairs() {
		Fixture f = new Fixture(200);
		f.env.pillarBlocked = true;
		f.tick(8 + 200 + 80);
		assertEquals(1, f.events.size(), f.snapshotText());
		assertTrue(f.events.getFirst().message().contains("exit=stairs"), f.events.getFirst().message());
		assertTrue(f.env.stairCalls > 0);
	}

	@Test void aStuckClimbFailsWithExitFailed() {
		Fixture f = new Fixture(200);
		f.env.pillarBlocked = true;
		f.env.stairsStuck = true;
		f.tick(8 + 200 + 20);
		assertEquals(List.of(), f.events);
		f.tick(ShelterForNightTaskExecutor.EXIT_STUCK_TICKS + 10);
		assertEquals(1, f.events.size());
		var end = f.events.getFirst();
		assertTrue(end.message().startsWith("exit_failed"), end.message());
		assertTrue(end.message().contains("food=") && end.message().contains("pos=0,61,0"), end.message());
	}

	@Test void aReflexPauseResumesTheSameShelter() {
		Fixture f = new Fixture(ShelterForNightStepArgs.MAX_WAIT_TICKS);
		f.tick(50);
		int digs = f.env.digCalls;
		String before = f.snapshotText();
		for (int i = 0; i < 20; i++) f.executor.tick(f.session(), Optional.empty());
		assertEquals(TaskExecutionState.IDLE, f.executor.snapshot().state());
		f.tick(1);
		assertEquals(digs, f.env.digCalls, "nothing dug again");
		assertTrue(f.snapshotText().startsWith("phase=WAIT "), f.snapshotText());
		assertTrue(f.snapshotText().contains("waited="), before);
	}

	@Test void pauseForReflexInTheWaitKeepsTheShelterAndTheWait() {
		Fixture f = new Fixture(ShelterForNightStepArgs.MAX_WAIT_TICKS);
		f.tick(50);
		assertTrue(f.snapshotText().startsWith("phase=WAIT "), f.snapshotText());
		int digs = f.env.digCalls;
		int waited = waited(f.snapshotText());
		BlockPos shelter = f.env.block;
		int releases = f.env.releases;
		f.executor.pauseForReflex();
		assertTrue(f.env.releases > releases, "the body is let go");
		assertEquals(TaskExecutionState.IDLE, f.executor.snapshot().state());
		f.tick(5);
		assertEquals(digs, f.env.digCalls, "no new shaft from the bottom of the old one");
		assertEquals(shelter, f.env.block);
		assertTrue(f.snapshotText().startsWith("phase=WAIT "), f.snapshotText());
		assertEquals(waited + 5, waited(f.snapshotText()), "the wait goes on counting from where it was");
		assertTrue(f.events.isEmpty());
	}

	@Test void pauseForReflexInTheWalkStartsTheRouteAgain() {
		Fixture f = new Fixture(ShelterForNightStepArgs.MAX_WAIT_TICKS);
		f.nav.arrive = false;
		// No shaft fits here or a step over: walk to a column two blocks away.
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) f.env.ground.water.add(new BlockPos(x, 62, z));
		f.tick(3);
		assertTrue(f.snapshotText().contains("step=go:walk"), f.snapshotText());
		int routes = f.nav.goals.size();
		assertEquals(1, routes);
		f.executor.pauseForReflex();
		assertFalse(f.nav.active, "the route is cancelled for the reflex");
		f.tick(1);
		assertTrue(f.snapshotText().contains("step=go:walk"), f.snapshotText());
		assertEquals(routes + 1, f.nav.goals.size(), "the route is started again, not assumed to survive the reflex");
		assertEquals(f.nav.goals.getFirst(), f.nav.goals.getLast(), "to the same site");
		assertTrue(f.nav.active);
	}

	@Test void leavingTheWorldStillForgetsTheShelter() {
		Fixture f = new Fixture(ShelterForNightStepArgs.MAX_WAIT_TICKS);
		f.tick(50);
		int digs = f.env.digCalls;
		f.executor.onWorldLeave();
		f.tick(5);
		assertTrue(f.env.digCalls > digs, "a fresh task plans a new shaft: " + f.snapshotText());
	}

	private static int waited(String text) {
		var matcher = java.util.regex.Pattern.compile(" waited=(\\d+)").matcher(text);
		assertTrue(matcher.find(), text);
		return Integer.parseInt(matcher.group(1));
	}

	@Test void aLostRoofIsPutBack() {
		Fixture f = new Fixture(ShelterForNightStepArgs.MAX_WAIT_TICKS);
		f.tick(10);
		f.env.roof = false;
		f.tick(1);
		assertTrue(f.snapshotText().startsWith("phase=DIG "), f.snapshotText());
		f.tick(2);
		assertTrue(f.env.roof);
		assertTrue(f.snapshotText().startsWith("phase=WAIT "), f.snapshotText());
	}

	// ---- Driven by the survival reflex in its own night bunker ----

	@Test void theReflexsClosedNightBunkerIsWaitedInUntilMorningAndClimbedOutOf() {
		ReflexFixture f = new ReflexFixture(true);
		f.tick(100);
		assertEquals(List.of(), f.events, f.snapshotText());
		assertTrue(ShelterForNightTaskExecutor.waitingInShelter(f.executor.snapshot()), f.snapshotText());
		assertEquals(0, f.env.digCalls, "the reflex dug it; nothing is dug again");
		assertTrue(f.nav.goals.isEmpty());
		f.env.dayTime = 400;
		f.tick(80);
		assertEquals(1, f.events.size(), f.snapshotText());
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
		assertTrue(f.events.getFirst().message().startsWith("sheltered_until_morning waited="), f.events.getFirst().message());
		assertEquals(64, f.env.block.getY(), "out on the surface");
	}

	@Test void aReflexBunkerClosedInTheMorningIsClimbedOutOfNotLeftAsAlreadyDay() {
		ReflexFixture f = new ReflexFixture(true);
		f.env.dayTime = 24_000L + 350;
		f.tick(80);
		assertEquals(1, f.events.size(), f.snapshotText());
		assertTrue(f.events.getFirst().message().startsWith("sheltered_until_morning"), f.events.getFirst().message());
		assertEquals(64, f.env.block.getY());
	}

	@Test void aReflexShelterThatIsNotClosedFailsWithoutDiggingAnother() {
		ReflexFixture f = new ReflexFixture(false);
		f.tick(ShelterForNightTaskExecutor.REFLEX_SETTLE_TICKS);
		assertEquals(List.of(), f.events, "a moment for a bounce to land");
		f.tick(5);
		assertEquals(1, f.events.size(), f.snapshotText());
		assertEquals(TaskExecutionState.FAILED, f.events.getFirst().terminalState());
		assertTrue(f.events.getFirst().message().startsWith("not_enclosed"), f.events.getFirst().message());
		assertEquals(0, f.env.digCalls);
		assertTrue(f.nav.goals.isEmpty());
	}

	// ---- Fakes ----

	/** A body at the bottom of the reflex's closed bunker (or, when not {@code enclosed}, on the surface). */
	private static final class ReflexFixture {
		final Environment env = new Environment();
		final Navigation nav = new Navigation(env);
		final ShelterForNightTaskExecutor executor = ShelterForNightTaskExecutor.forReflex(nav, env);
		final WorldTaskRequest request = WorldTaskRequest.shelterForNight("night-bunker-1", "night-bunker-1",
			new ShelterForNightStepArgs(ShelterForNightStepArgs.MAX_WAIT_TICKS));
		final List<TaskTerminalEvent> events = new ArrayList<>();

		ReflexFixture(boolean enclosed) {
			if (!enclosed) return;
			BlockPos bottom = new BlockPos(0, 61, 0);
			env.ground.air.addAll(List.of(bottom, bottom.above()));
			env.enclosed = ShelterForNightTaskExecutor.enclosedShelter(env.ground, bottom);
			env.block = bottom;
			env.roof = true;
		}

		void tick(int n) {
			for (int i = 0; i < n; i++) executor.tickForReflex(request).ifPresent(events::add);
		}

		String snapshotText() { return executor.snapshot().lastPathEvent(); }
	}

	private static final class Fixture {
		final Environment env = new Environment();
		final Navigation nav = new Navigation(env);
		final ShelterForNightTaskExecutor executor = new ShelterForNightTaskExecutor(nav, env);
		final WorldTaskRequest request;
		final List<TaskTerminalEvent> events = new ArrayList<>();
		long ticks;

		Fixture(int maxWait) {
			request = WorldTaskRequest.shelterForNight("shelter", "job", new ShelterForNightStepArgs(maxWait));
		}

		SessionSnapshot session() {
			return new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, ++ticks);
		}

		void tick(int n) {
			for (int i = 0; i < n; i++) executor.tick(session(), Optional.of(request)).ifPresent(events::add);
		}

		String snapshotText() { return executor.snapshot().lastPathEvent(); }
	}

	/** Solid ground below y=64 (dirt by default), open air from 64 up. */
	private static final class Ground implements ShelterForNightTaskExecutor.ShelterCells {
		final Set<BlockPos> air = new HashSet<>(), water = new HashSet<>(), blocked = new HashSet<>();
		final Map<BlockPos, Integer> ticks = new HashMap<>();
		boolean yieldsRoof = true, unbreakable;

		public boolean solid(BlockPos pos) { return blocked.contains(pos) || pos.getY() < 64 && !air.contains(pos) && !water.contains(pos); }
		public int breakTicks(BlockPos pos) { return unbreakable && solid(pos) ? -1 : ticks.getOrDefault(pos, 10); }
		public boolean fluid(BlockPos pos) { return water.contains(pos); }
		public boolean falling(BlockPos pos) { return false; }
		public boolean open(BlockPos pos) { return !solid(pos) && !water.contains(pos); }

		ShelterForNightTaskExecutor.SiteFacts facts(BlockPos top) {
			var plan = EmergencyBunker.plan(this, top, ShelterForNightTaskExecutor.MAX_BREAK_TICKS);
			return new ShelterForNightTaskExecutor.SiteFacts(plan, ShelterForNightTaskExecutor.standable(this, top), yieldsRoof);
		}
	}

	/** A body that digs one block per call, roofs in one, and climbs one block per call. */
	private static final class Environment implements ShelterForNightTaskExecutor.Environment {
		final Ground ground = new Ground();
		BlockPos block = FEET;
		long dayTime = 13_000;
		boolean overworld = true, roof, hostile, intruder, pillarBlocked, stairsStuck;
		EmergencyBunker.Plan enclosed;
		int blocks = 4, digCalls, stairCalls, progress;

		public ShelterForNightTaskExecutor.Body observe() {
			return new ShelterForNightTaskExecutor.Body(Vec3.atBottomCenterOf(block), block, true, 20.0F, 18, dayTime++, overworld,
				overworld ? "minecraft:overworld" : "minecraft:the_nether", blocks);
		}
		public ShelterForNightTaskExecutor.SiteFacts siteFacts(BlockPos top) { return ground.facts(top); }
		public EmergencyBunker.Plan enclosedShelter(BlockPos feet) {
			return enclosed != null && feet.equals(enclosed.bottom()) ? enclosed : null;
		}
		public void approach(BlockPos top) { block = top; }
		public ShelterForNightTaskExecutor.DigTick dig(EmergencyBunker.Plan plan) {
			if (block.getY() > plan.bottom().getY()) {
				digCalls++;
				block = block.below();
				return ShelterForNightTaskExecutor.DigTick.DIGGING;
			}
			if (!roof) {
				roof = true;
				blocks--;
				return ShelterForNightTaskExecutor.DigTick.ROOFING;
			}
			return ShelterForNightTaskExecutor.DigTick.CLOSED;
		}
		public boolean roofClosed(EmergencyBunker.Plan plan) { return roof; }
		public boolean intruded(EmergencyBunker.Plan plan) { return intruder; }
		public boolean hostileNear(BlockPos top, double radius) { return hostile; }
		public ShelterForNightTaskExecutor.BreakTick breakRoof(EmergencyBunker.Plan plan) {
			if (!roof) return ShelterForNightTaskExecutor.BreakTick.GONE;
			roof = false;
			blocks++;
			return ShelterForNightTaskExecutor.BreakTick.WORKING;
		}
		public ShelterForNightTaskExecutor.ClimbTick pillar(EmergencyBunker.Plan plan) {
			if (block.getY() >= plan.top().getY()) return new ShelterForNightTaskExecutor.ClimbTick(ShelterForNightTaskExecutor.ClimbState.OUT, progress, "out");
			if (pillarBlocked || blocks == 0)
				return new ShelterForNightTaskExecutor.ClimbTick(ShelterForNightTaskExecutor.ClimbState.BLOCKED, progress, "no_blocks");
			block = block.above();
			blocks--;
			return new ShelterForNightTaskExecutor.ClimbTick(ShelterForNightTaskExecutor.ClimbState.WORKING, ++progress, "tower");
		}
		public Optional<Direction> stairDirection(BlockPos origin, int surfaceY) { return Optional.of(Direction.EAST); }
		public ShelterForNightTaskExecutor.ClimbTick stairs(BlockPos origin, Direction direction, int surfaceY) {
			stairCalls++;
			if (block.getY() >= surfaceY) return new ShelterForNightTaskExecutor.ClimbTick(ShelterForNightTaskExecutor.ClimbState.OUT, progress, "out");
			if (stairsStuck) return new ShelterForNightTaskExecutor.ClimbTick(ShelterForNightTaskExecutor.ClimbState.WORKING, progress, "stuck");
			block = block.relative(direction).above();
			return new ShelterForNightTaskExecutor.ClimbTick(ShelterForNightTaskExecutor.ClimbState.WORKING, ++progress, "climb");
		}
		int releases;
		public void stop() {}
		public void release() { releases++; }
	}

	/** Arrives at once, unless {@code arrive} is off. */
	private static final class Navigation implements NavigationFacade {
		final Environment env;
		boolean active, arrive = true;
		List<GoalPosition> goals = new ArrayList<>();
		NavigationOptions options;
		Navigation(Environment env) { this.env = env; }
		public boolean isLoaded() { return true; }
		public void startFollow(String s) { fail("no following"); }
		public void startNavigate(GoalPosition p, NavigationOptions o) {
			goals.add(p);
			options = o;
			active = true;
			if (arrive) env.block = new BlockPos(p.x(), p.y(), p.z());
		}
		public void startNavigateNear(GoalPosition p, int r, NavigationOptions o) { startNavigate(p, o); }
		public boolean processActive() { return active; }
		public void cancel() { active = false; }
		public Optional<String> activeProcessName() { return Optional.empty(); }
		public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		public Optional<String> pollPathEvent() { return Optional.empty(); }
		public boolean navigationGoalReached(GoalPosition p) { return false; }
	}
}
