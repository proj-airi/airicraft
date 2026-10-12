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
		assertEquals(1, f.nav.radius);
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
		assertTrue(f.snapshotText().contains("skipped=1"), f.snapshotText());
		assertTrue(f.snapshotText().contains("phase=WAIT"), "nothing left to shoot: wait for a perch");
		assertEquals(f.env.waiting, f.nav.goals.getLast());
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
		empty.tick(DragonFightTaskExecutor.DRAGON_MISSING_TICKS + 1);
		assertTrue(empty.events.getFirst().message().startsWith("dragon_not_found"));

		Fixture stuck = new Fixture();
		stuck.env.bow = false;
		stuck.tick(DragonFightTaskExecutor.NO_PROGRESS_TICKS + 2);
		assertTrue(stuck.events.getFirst().message().startsWith("no_progress"));
	}

	@Test void damageToTheDragonCountsAsProgress() {
		Fixture f = new Fixture();
		f.env.bow = false;
		f.tick(DragonFightTaskExecutor.NO_PROGRESS_TICKS - 10);
		f.env.dragon = dragon(false, 150);
		f.tick(100);
		assertTrue(f.events.isEmpty());
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
		float health = 20;
		Vec3 feet = new Vec3(0.5, 64, 30.5);
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
			return new DragonFightTaskExecutor.Fight(inEnd, feet, health, dragon, crystals, clouds, List.of(), bow);
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
		public void startNavigate(GoalPosition p, NavigationOptions o) { goals.add(p); radius = 0; active = true; }
		public void startNavigateNear(GoalPosition p, int r, NavigationOptions o) { goals.add(p); radius = r; active = true; }
		public boolean processActive() { return active; }
		public void cancel() { active = false; }
		public Optional<String> activeProcessName() { return Optional.empty(); }
		public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		public Optional<String> pollPathEvent() { return Optional.empty(); }
		public boolean navigationGoalReached(GoalPosition p) { return false; }
	}
}
