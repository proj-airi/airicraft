package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DragonFightTaskExecutorTest {
	private static final GoalPosition SPOT_A = new GoalPosition(20, 64, 0, true);

	@Test void dodgesBreathBeforeAnythingElseAndStopsDrawing() {
		Fixture f = new Fixture();
		f.env.feet = new Vec3(20.5, 64, 0.5); // at the shooting spot
		f.tick(5);
		assertTrue(f.env.drawing, "shooting the crystal");
		f.env.clouds = List.of(new DragonHazards.Cloud(new Vec3(20, 64, 0), 5));
		f.tick(1);
		assertFalse(f.env.drawing);
		assertEquals(f.env.escape, f.nav.goals.getLast());
		assertTrue(f.nav.active);
		assertTrue(f.snapshotText().contains("phase=DODGE"));
	}

	@Test void perchedDragonIsHitOnTheHeadWhenInReachAndApproachedOtherwise() {
		Fixture f = new Fixture();
		f.env.dragon = dragon(true, 180);
		f.env.melee = DragonFightTaskExecutor.MeleeResult.OUT_OF_REACH;
		f.tick(1);
		assertEquals(f.env.perch, f.nav.goals.getLast());
		assertEquals(0, f.nav.radius, "exactly into reach");
		f.env.melee = DragonFightTaskExecutor.MeleeResult.HIT;
		f.tick(3);
		assertFalse(f.nav.active, "standing still while swinging");
		assertTrue(f.snapshotText().contains("headHits=3"));
	}

	@Test void shootsTheNearestOpenCrystalFromItsSpotAndMovesOnWhenItBreaks() {
		Fixture f = new Fixture();
		f.env.crystals = List.of(crystal("far", 80, false), crystal("near", 30, false), crystal("caged", 5, true));
		f.tick(1);
		assertEquals("near", f.env.spotRequests.getLast());
		assertEquals(SPOT_A, f.nav.goals.getLast());
		assertFalse(f.env.drawing, "no drawing while walking");
		f.env.feet = new Vec3(20.5, 64, 0.5);
		f.tick(DragonFightTaskExecutor.FULL_DRAW_TICKS);
		assertEquals(1, f.env.releases);
		f.env.crystals = List.of(crystal("far", 80, false), crystal("caged", 5, true));
		f.tick(f.env.flightTicks + 12);
		assertEquals("far", f.env.spotRequests.getLast(), "the caged crystal is never targeted");
		assertTrue(f.snapshotText().contains("crystalsDestroyed=1"), f.snapshotText());
	}

	@Test void missesRejectTheSpotThenSkipTheCrystal() {
		Fixture f = new Fixture();
		f.env.crystals = List.of(crystal("near", 30, false));
		f.env.feet = new Vec3(20.5, 64, 0.5);
		f.tick(1);
		int perShot = DragonFightTaskExecutor.FULL_DRAW_TICKS + f.env.flightTicks + 10;
		f.tick(perShot * DragonFightTaskExecutor.SHOTS_PER_SPOT);
		assertTrue(f.env.rejected.contains(SPOT_A), "three misses reject the spot");
		f.tick(2);
		assertTrue(f.snapshotText().contains("noShotFromHere=1"), f.snapshotText());
		assertTrue(f.snapshotText().contains("phase=WAIT"), "nothing left to shoot from here: wait for a perch");
		assertEquals(f.env.waiting, f.nav.goals.getLast());
		f.env.spot = Optional.of(new GoalPosition(40, 64, 0, true));
		f.env.feet = new Vec3(-0.5, 64, 0.5);
		f.tick(1);
		assertEquals(List.of("near", "near", "near"), f.env.spotRequests, "retried after moving on");
		assertEquals(new GoalPosition(40, 64, 0, true), f.nav.goals.getLast());
	}

	@Test void aBlockedCrystalIsRetriedAfterAWhileEvenWithoutMoving() {
		Fixture f = new Fixture();
		f.env.spot = Optional.empty();
		f.env.feet = new Vec3(9.5, 64, 0.5); // at the waiting spot
		f.tick(1);
		assertTrue(f.snapshotText().contains("noShotFromHere=1"), f.snapshotText());
		f.tick(DragonFightTaskExecutor.RETRY_BLOCKED_TICKS);
		assertEquals(1, f.env.spotRequests.size());
		f.env.spot = Optional.of(SPOT_A);
		f.tick(2);
		assertEquals(2, f.env.spotRequests.size());
		assertEquals(SPOT_A, f.nav.goals.getLast());
	}

	@Test void aCrystalUnloadedFarAwayIsNotCountedAsDestroyed() {
		Fixture f = new Fixture();
		f.env.bow = false;
		f.env.crystals = List.of(crystal("near", 30, false), crystal("far", 200, false));
		f.tick(1);
		f.env.crystals = List.of(crystal("near", 30, false));
		f.tick(1);
		assertTrue(f.snapshotText().contains("crystalsDestroyed=0"), f.snapshotText());
		f.env.crystals = List.of();
		f.tick(1);
		assertTrue(f.snapshotText().contains("crystalsDestroyed=1"), f.snapshotText());
	}

	@Test void walksTowardThePortalInStepsUntilCloseEnough() {
		Fixture f = new Fixture();
		f.env.bow = false;
		f.env.feet = new Vec3(100.5, 49, 0.5);
		f.env.waiting = new GoalPosition(60, 55, 0, true);
		f.tick(1);
		assertEquals(new GoalPosition(60, 55, 0, true), f.nav.goals.getLast());
		f.env.feet = new Vec3(60.5, 55, 0.5);
		f.env.waiting = new GoalPosition(20, 60, 0, true);
		f.tick(2);
		assertEquals(new GoalPosition(20, 60, 0, true), f.nav.goals.getLast(), "arriving at a step chooses the next one");
	}

	@Test void withoutABowItWaitsNearThePortal() {
		Fixture f = new Fixture();
		f.env.bow = false;
		f.env.crystals = List.of(crystal("near", 30, false));
		f.tick(2);
		assertTrue(f.env.spotRequests.isEmpty());
		assertEquals(f.env.waiting, f.nav.goals.getLast());
		assertEquals(2, f.nav.radius);
	}

	@Test void dyingDragonCompletesTheFight() {
		Fixture f = new Fixture();
		f.tick(2);
		f.env.dragon = new DragonFightTaskExecutor.Dragon(true, true, false, 0, "DYING", Vec3.ZERO, Vec3.ZERO);
		f.tick(2);
		assertEquals(1, f.events.size());
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
		assertTrue(f.events.getFirst().message().startsWith("dragon_defeated"));
		assertFalse(f.nav.active);
		assertFalse(f.env.drawing);
	}

	@Test void handsBackToThePlannerWithAReason() {
		Fixture outside = new Fixture();
		outside.env.inEnd = false;
		outside.tick(1);
		assertTrue(outside.events.getFirst().message().startsWith("not_in_the_end"));

		Fixture hurt = new Fixture();
		hurt.tick(3);
		hurt.env.health = 5;
		hurt.tick(1);
		assertTrue(hurt.events.getFirst().message().startsWith("low_health"));
		assertFalse(hurt.nav.active);

		Fixture empty = new Fixture();
		empty.env.dragon = null;
		empty.env.feet = new Vec3(100.5, 49, 0.5);
		empty.tick(DragonFightTaskExecutor.DRAGON_MISSING_TICKS + 1);
		assertTrue(empty.events.isEmpty(), "far from the portal, a dragon out of range is not missing");
		empty.env.feet = new Vec3(9.5, 64, 0.5);
		empty.tick(DragonFightTaskExecutor.DRAGON_MISSING_TICKS + 1);
		assertTrue(empty.events.getFirst().message().startsWith("dragon_not_found"));

		Fixture stuck = new Fixture();
		stuck.env.bow = false;
		stuck.tick(DragonFightTaskExecutor.NO_PROGRESS_TICKS + 2);
		assertTrue(stuck.events.getFirst().message().startsWith("no_progress"));
	}

	@Test void routesBridgeFromTheArrivalPlatformButOnlyWalkOnTheIsland() {
		Fixture f = new Fixture();
		f.env.bow = false;
		f.env.onEndStone = false;
		f.env.feet = new Vec3(100.5, 49, 0.5);
		f.tick(1);
		assertEquals(NavigationOptions.DEFAULT, f.nav.options, "bridging off the obsidian platform");
		f.env.onEndStone = true;
		f.env.feet = new Vec3(40.5, 62, 0.5);
		f.nav.events.add("CALC_FAILED");
		f.tick(2);
		assertEquals(NavigationOptions.WALK_ONLY, f.nav.options);
		f.env.onEndStone = null; // mid-jump
		f.nav.events.add("CALC_FAILED");
		f.tick(2);
		assertEquals(NavigationOptions.WALK_ONLY, f.nav.options);
	}

	@Test void anIsolatedArrivalPlatformFailsWithAReasonInsteadOfWaiting() {
		Fixture f = new Fixture();
		f.env.bow = false;
		f.env.feet = new Vec3(100.5, 49, 0.5);
		for (int i = 0; i < DragonFightTaskExecutor.MAX_APPROACH_FAILURES * 2 && f.events.isEmpty(); i++) {
			f.tick(1);
			f.nav.events.add("CALC_FAILED");
			f.tick(1);
		}
		assertTrue(f.events.getFirst().message().startsWith("portal_unreachable"), f.events.toString());
		assertEquals(TaskFailureCode.MISSING_ITEM, f.events.getFirst().failureCode());
	}

	@Test void damageToTheDragonCountsAsProgress() {
		Fixture f = new Fixture();
		f.env.bow = false;
		f.tick(DragonFightTaskExecutor.NO_PROGRESS_TICKS - 10);
		f.env.dragon = dragon(false, 150);
		f.tick(100);
		assertTrue(f.events.isEmpty());
	}

	@Test void aStuckRouteIsDroppedBeforeTheDispatcherWatchdogEndsTheFight() {
		Fixture f = new Fixture();
		f.nav.position = f.env.feet;
		var dispatcherWatchdog = new NavigationStallWatchdog();
		boolean dispatcherFired = false;
		for (int i = 0; i < 400; i++) {
			f.tick(1);
			var progress = f.nav.navigationProgress();
			if (progress.isEmpty()) dispatcherWatchdog.clear();
			else dispatcherFired |= dispatcherWatchdog.observe(f.ticks, progress.get());
		}
		assertFalse(dispatcherFired, "the fight never stands still on a route long enough for PATH_STUCK");
		assertTrue(f.env.rejected.contains(SPOT_A), "the stuck spot is rejected");
		assertTrue(f.events.isEmpty());
	}

	@Test void aDodgeFinishesItsStepInsteadOfFlappingAtTheEdgeOfTheMargin() {
		Fixture f = new Fixture();
		f.env.feet = new Vec3(20.5, 64, 0.5);
		f.env.clouds = List.of(new DragonHazards.Cloud(new Vec3(20, 64, 0), 5));
		f.tick(1);
		assertEquals(f.env.escape, f.nav.goals.getLast());
		for (int i = 0; i < 6; i++) {
			f.env.clouds = i % 2 == 0 ? List.of() : List.of(new DragonHazards.Cloud(new Vec3(20, 64, 0), 5));
			f.tick(1);
		}
		assertTrue(f.snapshotText().contains("dodges=1"), f.snapshotText());
		assertTrue(f.snapshotText().contains("phase=DODGE"));
		f.env.clouds = List.of();
		f.env.feet = new Vec3(30.5, 64, 10.5);
		f.tick(1);
		assertFalse(f.snapshotText().contains("phase=DODGE"), "back to work once the escape spot is reached");
	}

	@Test void cancellationReleasesNavigationAndTheBow() {
		Fixture f = new Fixture();
		f.env.feet = new Vec3(20.5, 64, 0.5);
		f.tick(5);
		assertTrue(f.env.drawing);
		f.executor.tick(f.session(), Optional.empty());
		assertFalse(f.env.drawing);
		assertFalse(f.nav.active);
		assertEquals(TaskExecutionState.IDLE, f.executor.snapshot().state());
	}

	private static DragonFightTaskExecutor.Dragon dragon(boolean perched, float health) {
		return new DragonFightTaskExecutor.Dragon(true, false, perched, health, perched ? "SITTING_SCANNING" : "HOLDING_PATTERN",
			new Vec3(0, 68, 0), new Vec3(3, 66, 0));
	}

	private static DragonFightTaskExecutor.Crystal crystal(String id, double x, boolean caged) {
		return new DragonFightTaskExecutor.Crystal(id, new Vec3(x, 90, 0), caged);
	}

	private static final class Fixture {
		final Environment env = new Environment();
		final Navigation nav = new Navigation();
		final DragonFightTaskExecutor executor = new DragonFightTaskExecutor(nav, env);
		final WorldTaskRequest request = WorldTaskRequest.fightEnderDragon("fight", "job");
		final List<TaskTerminalEvent> events = new ArrayList<>();
		long ticks;

		SessionSnapshot session() {
			return new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:the_end", true, 25565, ++ticks);
		}

		void tick(int n) {
			for (int i = 0; i < n; i++) executor.tick(session(), Optional.of(request)).ifPresent(events::add);
		}

		String snapshotText() { return executor.snapshot().lastPathEvent(); }
	}

	private static final class Environment implements DragonFightTaskExecutor.Environment {
		boolean inEnd = true, bow = true, drawing;
		Boolean onEndStone = true;
		float health = 20;
		Vec3 feet = new Vec3(0.5, 64, 20.5);
		DragonFightTaskExecutor.Dragon dragon = dragon(false, 200);
		List<DragonFightTaskExecutor.Crystal> crystals = List.of(crystal("near", 30, false));
		List<DragonHazards.Cloud> clouds = List.of();
		GoalPosition escape = new GoalPosition(30, 64, 10, true);
		GoalPosition perch = new GoalPosition(4, 64, 0, true);
		GoalPosition waiting = new GoalPosition(9, 64, 0, true);
		Optional<GoalPosition> spot = Optional.of(SPOT_A);
		DragonFightTaskExecutor.MeleeResult melee = DragonFightTaskExecutor.MeleeResult.OUT_OF_REACH;
		List<String> spotRequests = new ArrayList<>();
		Set<GoalPosition> rejected = Set.of();
		int releases, flightTicks = 15;

		public DragonFightTaskExecutor.Fight observe() {
			return new DragonFightTaskExecutor.Fight(inEnd, feet, health, dragon, crystals, clouds, List.of(), bow, onEndStone);
		}
		public Optional<GoalPosition> escapePosition(DragonFightTaskExecutor.Fight fight, Vec3 avoid) { return Optional.of(escape); }
		public Optional<GoalPosition> perchApproach(DragonFightTaskExecutor.Fight fight, DragonFightTaskExecutor.Dragon dragon) { return Optional.of(perch); }
		public Optional<GoalPosition> shootingSpot(DragonFightTaskExecutor.Fight fight, DragonFightTaskExecutor.Crystal crystal, Set<GoalPosition> rejected) {
			spotRequests.add(crystal.id());
			this.rejected = Set.copyOf(rejected);
			return spot.filter(s -> !rejected.contains(s));
		}
		public Optional<GoalPosition> waitingSpot(DragonFightTaskExecutor.Fight fight) { return Optional.of(waiting); }
		public DragonFightTaskExecutor.AimResult aimBow(DragonFightTaskExecutor.Crystal crystal) { return DragonFightTaskExecutor.AimResult.ALIGNED; }
		public boolean drawBow() { drawing = true; return true; }
		public int releaseBow() { drawing = false; releases++; return flightTicks; }
		public void cancelBow() { drawing = false; }
		public DragonFightTaskExecutor.MeleeResult meleeHead() { return melee; }
		public void release() { drawing = false; }
	}

	private static final class Navigation implements NavigationFacade {
		boolean active;
		int radius;
		List<GoalPosition> goals = new ArrayList<>();
		public boolean isLoaded() { return true; }
		public void startFollow(String s) { fail("no following"); }
		NavigationOptions options;
		public void startNavigate(GoalPosition p, NavigationOptions o) { goals.add(p); radius = 0; active = true; options = o; }
		public void startNavigateNear(GoalPosition p, int r, NavigationOptions o) { goals.add(p); radius = r; active = true; options = o; }
		public boolean processActive() { return active; }
		public void cancel() { active = false; }
		public Optional<String> activeProcessName() { return Optional.empty(); }
		public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		Queue<String> events = new ArrayDeque<>();
		public Optional<String> pollPathEvent() { return Optional.ofNullable(events.poll()); }
		public boolean navigationGoalReached(GoalPosition p) { return false; }
		Vec3 position;
		public Optional<NavigationProgress> navigationProgress() {
			return active && position != null ? Optional.of(new NavigationProgress(position.x, position.y, position.z, true, null, 0)) : Optional.empty();
		}
	}
}
