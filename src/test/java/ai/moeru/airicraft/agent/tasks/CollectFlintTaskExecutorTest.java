package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CollectFlintTaskExecutorTest {
	private static final GoalPosition CELL = new GoalPosition(1, 64, 0, true);
	private static final CollectFlintTaskExecutor.Spot SPOT =
		new CollectFlintTaskExecutor.Spot(CELL, new GoalPosition(1, 63, 0, true));

	// ---- Placement cell ----

	@Test void picksAnOpenCellBesideTheFeetOnSolidGround() {
		Cells cells = Cells.flatGround(64);
		var spot = CollectFlintTaskExecutor.chooseSpot(new Vec3(0.5, 64, 0.5), cells, Set.of()).orElseThrow();
		assertEquals(64, spot.cell().y(), "foot level first");
		assertEquals(1, Math.abs(spot.cell().x()) + Math.abs(spot.cell().z()), "a cardinal neighbour before a diagonal: " + spot.cell());
		assertEquals(new GoalPosition(spot.cell().x(), 63, spot.cell().z(), true), spot.support());
	}

	@Test void neverPicksACellTheBodyOverlaps() {
		Cells cells = Cells.flatGround(64);
		// The body at x=0.8 reaches into x=1, so the nearest cell (+x) would refuse the placement.
		var spot = CollectFlintTaskExecutor.chooseSpot(new Vec3(0.8, 64, 0.5), cells, Set.of()).orElseThrow();
		assertNotEquals(new GoalPosition(1, 64, 0, true), spot.cell(), "skips the overlapped cell");
		assertFalse(CollectFlintTaskExecutor.overlapsBody(new Vec3(0.8, 64, 0.5), spot.cell()));
		assertTrue(CollectFlintTaskExecutor.overlapsBody(new Vec3(0.8, 64, 0.5), new GoalPosition(1, 64, 0, true)));
		assertFalse(CollectFlintTaskExecutor.overlapsBody(new Vec3(0.5, 64, 0.5), new GoalPosition(1, 63, 0, true)),
			"a cell below the feet never overlaps");
	}

	@Test void standingOnSoulSandStillCountsAsTheBlockLevel() {
		Cells cells = Cells.flatGround(64);
		var spot = CollectFlintTaskExecutor.chooseSpot(new Vec3(0.5, 63.875, 0.5), cells, Set.of()).orElseThrow();
		assertEquals(64, spot.cell().y());
	}

	@Test void skipsCellsWithoutSupportBesideLiquidOrRejected() {
		Cells cells = Cells.flatGround(64);
		// Only +z stays usable at foot level: -x has water beside it, +x has no floor, -z was rejected; diagonals are walled.
		cells.liquid.add(new GoalPosition(-2, 64, 0, true));
		cells.support.remove(new GoalPosition(1, 63, 0, true));
		for (int dx : new int[] {-1, 1}) for (int dz : new int[] {-1, 1}) cells.open.remove(new GoalPosition(dx, 64, dz, true));
		var spot = CollectFlintTaskExecutor.chooseSpot(new Vec3(0.5, 64, 0.5), cells, Set.of(new GoalPosition(0, 64, -1, true)));
		assertEquals(new GoalPosition(0, 64, 1, true), spot.orElseThrow().cell());
		cells.liquid.add(new GoalPosition(0, 65, 1, true));
		assertTrue(CollectFlintTaskExecutor.chooseSpot(new Vec3(0.5, 64, 0.5), cells, Set.of(new GoalPosition(0, 64, -1, true))).isEmpty(),
			"water over the cell rules it out too");
	}

	@Test void usesAHoleOneBelowTheFeetOnlyWithOpenAirOverIt() {
		Cells cells = new Cells();
		// Walled in at foot level, with a one-deep hole at +x.
		GoalPosition hole = new GoalPosition(1, 63, 0, true);
		cells.open.add(hole);
		cells.support.add(new GoalPosition(1, 62, 0, true));
		assertTrue(CollectFlintTaskExecutor.chooseSpot(new Vec3(0.5, 64, 0.5), cells, Set.of()).isEmpty(), "covered hole");
		cells.open.add(new GoalPosition(1, 64, 0, true));
		cells.support.remove(new GoalPosition(1, 63, 0, true));
		assertEquals(hole, CollectFlintTaskExecutor.chooseSpot(new Vec3(0.5, 64, 0.5), cells, Set.of()).orElseThrow().cell());
	}

	// ---- Tool choice ----

	@Test void neverBreaksWithSilkTouchAndPrefersFortuneThenSpeed() {
		var silkShovel = new CollectFlintTaskExecutor.ToolOption(0, true, 0, 12.0F, true);
		var hand = new CollectFlintTaskExecutor.ToolOption(1, false, 0, 1.0F, false);
		var ironShovel = new CollectFlintTaskExecutor.ToolOption(2, false, 0, 6.0F, true);
		var fortunePickaxe = new CollectFlintTaskExecutor.ToolOption(20, false, 3, 1.0F, true);
		assertEquals(1, CollectFlintTaskExecutor.chooseToolSlot(0, List.of(silkShovel, hand)));
		assertEquals(2, CollectFlintTaskExecutor.chooseToolSlot(0, List.of(silkShovel, hand, ironShovel)));
		assertEquals(20, CollectFlintTaskExecutor.chooseToolSlot(0, List.of(silkShovel, hand, ironShovel, fortunePickaxe)));
		assertEquals(-1, CollectFlintTaskExecutor.chooseToolSlot(0, List.of(silkShovel)));
	}

	@Test void aTieKeepsTheHeldSlotAndAvoidsWearingATool() {
		var gravel = new CollectFlintTaskExecutor.ToolOption(3, false, 0, 1.0F, false);
		var empty = new CollectFlintTaskExecutor.ToolOption(5, false, 0, 1.0F, false);
		var sword = new CollectFlintTaskExecutor.ToolOption(0, false, 0, 1.0F, true);
		assertEquals(5, CollectFlintTaskExecutor.chooseToolSlot(5, List.of(gravel, empty)));
		assertEquals(3, CollectFlintTaskExecutor.chooseToolSlot(0, List.of(sword, gravel, empty)), "a sword would only wear");
	}

	// ---- Termination and drops ----

	@Test void endsOnlyBetweenCyclesAndTellsNoGravelFromUsedUp() {
		assertEquals(Optional.of(CollectFlintTaskExecutor.Ending.SUCCEEDED),
			CollectFlintTaskExecutor.ending(CollectFlintTaskExecutor.Phase.CHOOSE, 2, 0, 2, 9, 100));
		assertEquals(Optional.empty(), CollectFlintTaskExecutor.ending(CollectFlintTaskExecutor.Phase.COLLECT, 2, 0, 2, 9, 100),
			"a cycle in progress finishes first");
		assertEquals(Optional.of(CollectFlintTaskExecutor.Ending.NO_GRAVEL),
			CollectFlintTaskExecutor.ending(CollectFlintTaskExecutor.Phase.CHOOSE, 0, 0, 1, 0, 1));
		assertEquals(Optional.of(CollectFlintTaskExecutor.Ending.GRAVEL_USED_UP),
			CollectFlintTaskExecutor.ending(CollectFlintTaskExecutor.Phase.CHOOSE, 1, 0, 2, 12, 400));
		assertEquals(Optional.of(CollectFlintTaskExecutor.Ending.TIMEOUT), CollectFlintTaskExecutor.ending(
			CollectFlintTaskExecutor.Phase.BREAK, 0, 5, 1, 50, CollectFlintTaskExecutor.MIN_TIMEOUT_TICKS + 1));
		assertTrue(CollectFlintTaskExecutor.timeoutTicks(8) > CollectFlintTaskExecutor.timeoutTicks(1), "more flint, more time");
	}

	@Test void aDropIsAwaitedThenWalkedToOnlyWhenClose() {
		assertEquals(CollectFlintTaskExecutor.DropStep.DONE, CollectFlintTaskExecutor.dropStep(3, true, null));
		assertEquals(CollectFlintTaskExecutor.DropStep.WAIT, CollectFlintTaskExecutor.dropStep(5, false, null), "not spawned yet");
		assertEquals(CollectFlintTaskExecutor.DropStep.WAIT, CollectFlintTaskExecutor.dropStep(15, false, 1.5));
		assertEquals(CollectFlintTaskExecutor.DropStep.WALK, CollectFlintTaskExecutor.dropStep(21, false, 1.5));
		assertEquals(CollectFlintTaskExecutor.DropStep.LOST, CollectFlintTaskExecutor.dropStep(21, false, 4.0));
		assertEquals(CollectFlintTaskExecutor.DropStep.LOST, CollectFlintTaskExecutor.dropStep(31, false, null));
		assertEquals(CollectFlintTaskExecutor.DropStep.LOST,
			CollectFlintTaskExecutor.dropStep(CollectFlintTaskExecutor.DROP_TIMEOUT_TICKS + 1, false, 1.0));
	}

	@Test void countDefaultsToOneAndStaysWithinEight() {
		assertEquals(1, CollectFlintStepArgs.parse(new JsonObject()).count());
		JsonObject args = new JsonObject();
		args.addProperty("count", 9);
		assertThrows(IllegalArgumentException.class, () -> CollectFlintStepArgs.parse(args));
		args.addProperty("count", 0);
		assertThrows(IllegalArgumentException.class, () -> CollectFlintStepArgs.parse(args));
		args.addProperty("count", 8);
		assertEquals(8, CollectFlintStepArgs.parse(args).count());
	}

	// ---- The cycle ----

	@Test void placesBreaksAndWaitsForThePickupThenSucceeds() {
		Fixture f = new Fixture(1);
		f.tick(3);
		assertEquals(1, f.env.places);
		assertEquals(1, f.env.breaks);
		assertEquals(4, f.env.gravel, "the placed gravel left the inventory");
		f.tick(5);
		assertEquals(List.of(), f.events, "waits for the drop");
		f.env.flint = 1;
		f.tick(2);
		assertEquals(1, f.events.size());
		var end = f.events.getFirst();
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState());
		assertTrue(end.message().startsWith("flint_collected"), end.message());
		assertTrue(end.message().contains(" flint=1 ") && end.message().contains("gravelLeft=4") && end.message().contains("cycles=1"),
			end.message());
		assertTrue(f.nav.goals.isEmpty(), "never walked: the drop came to the feet");
	}

	@Test void gravelThatComesBackIsPlacedAgain() {
		Fixture f = new Fixture(1);
		f.tick(4);
		f.env.gravel = 5;
		f.tick(4);
		assertEquals(2, f.env.places, "a second cycle started");
		assertTrue(f.snapshotText().contains("cycles=2"), f.snapshotText());
		assertEquals(List.of(), f.events);
	}

	@Test void runningOutOfGravelReportsTheFlintMade() {
		Fixture f = new Fixture(2);
		f.env.gravel = 1;
		f.tick(4);
		f.env.flint = 1;
		f.tick(2);
		assertEquals(1, f.events.size());
		var end = f.events.getFirst();
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("gravel_used_up") && end.message().contains(" flint=1 "), end.message());
		assertEquals(TaskFailureCode.MISSING_ITEM, end.failureCode());
	}

	@Test void withoutGravelOrRoomItFailsAtOnce() {
		Fixture noGravel = new Fixture(1);
		noGravel.env.gravel = 0;
		noGravel.tick(1);
		assertTrue(noGravel.events.getFirst().message().startsWith("no_gravel"), noGravel.events.toString());
		assertEquals(0, noGravel.env.places);
		Fixture noSpot = new Fixture(1);
		noSpot.env.spot = Optional.empty();
		noSpot.tick(1);
		assertTrue(noSpot.events.getFirst().message().startsWith("no_place_spot"), noSpot.events.toString());
		Fixture alreadyHeld = new Fixture(1);
		alreadyHeld.env.flint = 3;
		alreadyHeld.tick(1);
		assertEquals(TaskExecutionState.COMPLETED, alreadyHeld.events.getFirst().terminalState());
		assertEquals(0, alreadyHeld.env.places);
	}

	@Test void walksOntoADropThatStayedOutOfReach() {
		Fixture f = new Fixture(1);
		f.tick(3);
		f.env.drop = Optional.of(new Vec3(2.2, 64, 0.5));
		f.tick(CollectFlintTaskExecutor.PICKUP_WAIT_TICKS);
		assertTrue(f.nav.goals.isEmpty(), "waits for the pickup first");
		f.tick(1);
		assertEquals(new GoalPosition(2, 64, 0, true), f.nav.goals.getLast());
		assertEquals(NavigationOptions.WALK_ONLY, f.nav.options, "never edits terrain to reach a drop");
		f.env.flint = 1;
		f.env.drop = Optional.empty();
		f.tick(1);
		assertFalse(f.nav.active, "stops walking once it is picked up");
		f.tick(1);
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
	}

	@Test void dropsLostInARowEndTheTask() {
		Fixture f = new Fixture(1);
		f.tick(400);
		assertEquals(1, f.events.size());
		var end = f.events.getFirst();
		assertTrue(end.message().startsWith("drop_lost"), end.message());
		assertTrue(end.message().contains("dropsLost=" + CollectFlintTaskExecutor.MAX_DROPS_LOST_IN_A_ROW), end.message());
	}

	@Test void aSpotWhereGravelNeverAppearsIsSkipped() {
		Fixture f = new Fixture(1);
		f.env.placeResult = CollectFlintTaskExecutor.PlaceResult.WORKING;
		f.tick(CollectFlintTaskExecutor.PLACE_TIMEOUT_TICKS + 2);
		assertEquals(List.of(), f.events);
		f.tick(1);
		assertTrue(f.env.rejected.contains(CELL), "the next choice skips it");
		assertTrue(f.events.getFirst().message().startsWith("no_place_spot"), f.events.toString());
	}

	@Test void aStuckBreakIsAbandonedAndTheSpotSkipped() {
		Fixture f = new Fixture(1);
		f.env.breakResult = CollectFlintTaskExecutor.BreakResult.WORKING;
		f.tick(2);
		int stoppedBefore = f.env.stopped;
		f.tick(CollectFlintTaskExecutor.BREAK_TIMEOUT_TICKS);
		assertEquals(stoppedBefore, f.env.stopped, "keeps breaking until the step times out");
		f.tick(1);
		assertTrue(f.env.stopped > stoppedBefore, "stops breaking");
		assertTrue(f.snapshotText().contains("break_timeout"), f.snapshotText());
	}

	private static final class Fixture {
		final Environment env = new Environment();
		final Navigation nav = new Navigation();
		final CollectFlintTaskExecutor executor = new CollectFlintTaskExecutor(nav, env);
		final WorldTaskRequest request;
		final List<TaskTerminalEvent> events = new ArrayList<>();
		long ticks;

		Fixture(int count) {
			request = WorldTaskRequest.collectFlint("flint", "job", new CollectFlintStepArgs(count));
		}

		SessionSnapshot session() {
			return new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, ++ticks);
		}

		void tick(int n) {
			for (int i = 0; i < n; i++) executor.tick(session(), Optional.of(request)).ifPresent(events::add);
		}

		String snapshotText() { return executor.snapshot().lastPathEvent(); }
	}

	private static final class Cells implements CollectFlintTaskExecutor.Cells {
		final Set<GoalPosition> open = new HashSet<>(), support = new HashSet<>(), liquid = new HashSet<>();

		/** Open air at and above {@code y} and solid ground under it, around the origin. */
		static Cells flatGround(int y) {
			Cells cells = new Cells();
			for (int x = -3; x <= 3; x++) {
				for (int z = -3; z <= 3; z++) {
					cells.open.add(new GoalPosition(x, y, z, true));
					cells.open.add(new GoalPosition(x, y + 1, z, true));
					cells.support.add(new GoalPosition(x, y - 1, z, true));
				}
			}
			return cells;
		}

		public boolean open(GoalPosition cell) { return open.contains(cell); }
		public boolean support(GoalPosition cell) { return support.contains(cell); }
		public boolean liquid(GoalPosition cell) { return liquid.contains(cell); }
	}

	private static final class Environment implements CollectFlintTaskExecutor.Environment {
		Vec3 feet = new Vec3(0.5, 64, 0.5);
		int gravel = 5, flint, places, breaks, stopped;
		Optional<CollectFlintTaskExecutor.Spot> spot = Optional.of(SPOT);
		CollectFlintTaskExecutor.PlaceResult placeResult = CollectFlintTaskExecutor.PlaceResult.PLACED;
		CollectFlintTaskExecutor.BreakResult breakResult = CollectFlintTaskExecutor.BreakResult.GONE;
		Optional<Vec3> drop = Optional.empty();
		Set<GoalPosition> rejected = Set.of();

		public CollectFlintTaskExecutor.Site observe() { return new CollectFlintTaskExecutor.Site(feet, gravel, flint); }
		public Optional<CollectFlintTaskExecutor.Spot> placeSpot(CollectFlintTaskExecutor.Site site, Set<GoalPosition> rejectedCells) {
			rejected = Set.copyOf(rejectedCells);
			return spot.filter(s -> !rejectedCells.contains(s.cell()));
		}
		public CollectFlintTaskExecutor.PlaceResult place(CollectFlintTaskExecutor.Spot target) {
			places++;
			if (placeResult == CollectFlintTaskExecutor.PlaceResult.PLACED) gravel--;
			return placeResult;
		}
		public CollectFlintTaskExecutor.BreakResult breakGravel(GoalPosition cell) {
			breaks++;
			return breakResult;
		}
		public Optional<Vec3> drop(GoalPosition cell) { return drop; }
		public void stopBreaking() { stopped++; }
		public void release() {}
	}

	private static final class Navigation implements NavigationFacade {
		boolean active;
		List<GoalPosition> goals = new ArrayList<>();
		NavigationOptions options;
		public boolean isLoaded() { return true; }
		public void startFollow(String s) { fail("no following"); }
		public void startNavigate(GoalPosition p, NavigationOptions o) { goals.add(p); active = true; options = o; }
		public void startNavigateNear(GoalPosition p, int r, NavigationOptions o) { goals.add(p); active = true; options = o; }
		public boolean processActive() { return active; }
		public void cancel() { active = false; }
		public Optional<String> activeProcessName() { return Optional.empty(); }
		public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		Queue<String> events = new ArrayDeque<>();
		public Optional<String> pollPathEvent() { return Optional.ofNullable(events.poll()); }
		public boolean navigationGoalReached(GoalPosition p) { return false; }
		public Optional<NavigationProgress> navigationProgress() { return Optional.empty(); }
	}
}
