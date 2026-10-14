package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.CollectBlazeRodsTaskExecutor.Arena;
import ai.moeru.airicraft.agent.tasks.CollectBlazeRodsTaskExecutor.Blaze;
import ai.moeru.airicraft.agent.tasks.CollectBlazeRodsTaskExecutor.DroppedRod;
import ai.moeru.airicraft.agent.tasks.CollectBlazeRodsTaskExecutor.Fireball;
import ai.moeru.airicraft.agent.tasks.CollectBlazeRodsTaskExecutor.Side;
import ai.moeru.airicraft.agent.tasks.CollectBlazeRodsTaskExecutor.Spot;
import ai.moeru.airicraft.agent.tasks.CollectBlazeRodsTaskExecutor.Weapon;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CollectBlazeRodsTaskExecutorTest {
	private static final GoalPosition SPAWNER = new GoalPosition(0, 64, 0, true);
	private static final GoalPosition SPOT = new GoalPosition(8, 64, 0, true);
	private static final GoalPosition COVER = new GoalPosition(8, 64, 6, true);

	// ---- choosing a fighting spot ----

	@Test void refusesSpotsByLavaAtEdgesWithoutSightOrOutOfRange() {
		Set<GoalPosition> none = Set.of();
		assertNull(CollectBlazeRodsTaskExecutor.refusal(spot(8, 0, 8.0, 0), none));
		assertEquals("lava_or_fire", CollectBlazeRodsTaskExecutor.refusal(
			new Spot(new GoalPosition(8, 64, 0, true), true, true, false, true, 0, 0, 8.0), none));
		assertEquals("edge_drop", CollectBlazeRodsTaskExecutor.refusal(
			new Spot(new GoalPosition(8, 64, 0, true), true, false, true, true, 0, 0, 8.0), none));
		assertEquals("no_sight", CollectBlazeRodsTaskExecutor.refusal(
			new Spot(new GoalPosition(8, 64, 0, true), true, false, false, false, 0, 0, 8.0), none));
		assertEquals("too_close", CollectBlazeRodsTaskExecutor.refusal(spot(2, 0, 2.0, 0), none));
		assertEquals("too_far", CollectBlazeRodsTaskExecutor.refusal(spot(15, 0, 15.0, 0), none));
		assertEquals("unreachable", CollectBlazeRodsTaskExecutor.refusal(spot(8, 0, 8.0, 0), Set.of(new GoalPosition(8, 64, 0, true))));
	}

	@Test void prefersASafeSpotWithCoverAndNeverAnUnsafeOne() {
		Vec3 feet = new Vec3(20.5, 64, 0.5);
		Spot open = spot(8, 0, 8.0, 0);
		Spot walled = spot(0, 8, 8.0, 3);
		Spot lavaButCloser = new Spot(new GoalPosition(10, 64, 0, true), true, true, false, true, 0, 4, 10.0);
		Spot edge = new Spot(new GoalPosition(9, 64, 1, true), true, false, true, true, 0, 4, 9.0);
		var chosen = CollectBlazeRodsTaskExecutor.chooseFightingSpot(List.of(lavaButCloser, edge, open, walled), feet, Set.of());
		assertEquals(walled.position(), chosen.orElseThrow().position(), "a wall beside the head beats a few steps of walking");
		assertTrue(CollectBlazeRodsTaskExecutor.chooseFightingSpot(List.of(lavaButCloser, edge), feet, Set.of()).isEmpty());
	}

	@Test void coverIsTheNearestSafeSpotNoBlazeSees() {
		Vec3 feet = new Vec3(8.5, 64, 0.5);
		Spot seen = new Spot(new GoalPosition(8, 64, 2, true), true, false, false, false, 1, 0, 0);
		Spot hidden = new Spot(new GoalPosition(8, 64, 5, true), true, false, false, false, 0, 2, 0);
		Spot hiddenInLava = new Spot(new GoalPosition(9, 64, 1, true), true, true, false, false, 0, 2, 0);
		Spot farHidden = new Spot(new GoalPosition(8, 64, 30, true), true, false, false, false, 0, 2, 0);
		assertEquals(hidden.position(), CollectBlazeRodsTaskExecutor.chooseCover(List.of(seen, hiddenInLava, farHidden, hidden), feet)
			.orElseThrow().position());
	}

	// ---- weapons and fireballs ----

	@Test void meleeInReachBowFartherAwayNothingWithoutABow() {
		Blaze close = new Blaze(1, new Vec3(0, 66, 0), 2.5, true, true);
		Blaze far = new Blaze(2, new Vec3(0, 66, 10), 10, true, false);
		Blaze tooFar = new Blaze(3, new Vec3(0, 66, 40), 40, true, false);
		assertEquals(Weapon.MELEE, CollectBlazeRodsTaskExecutor.weapon(close, true));
		assertEquals(Weapon.BOW, CollectBlazeRodsTaskExecutor.weapon(far, true));
		assertEquals(Weapon.NONE, CollectBlazeRodsTaskExecutor.weapon(far, false));
		assertEquals(Weapon.NONE, CollectBlazeRodsTaskExecutor.weapon(tooFar, true));
	}

	@Test void aFireballFlyingAtTheBodyIsIncomingAndOnePassingWideIsNot() {
		Vec3 feet = new Vec3(0.5, 64, 0.5);
		assertTrue(CollectBlazeRodsTaskExecutor.incoming(feet, new Fireball(new Vec3(0.5, 65.4, 10.5), new Vec3(0, 0, -1), null)));
		assertFalse(CollectBlazeRodsTaskExecutor.incoming(feet, new Fireball(new Vec3(3.5, 65.4, 10.5), new Vec3(0, 0, -1), null)),
			"three blocks wide misses");
		assertFalse(CollectBlazeRodsTaskExecutor.incoming(feet, new Fireball(new Vec3(0.5, 65.4, -3.5), new Vec3(0, 0, -1), null)),
			"already past");
	}

	@Test void strafesAwayFromTheFireballLineOntoSafeGroundOnly() {
		Vec3 feet = new Vec3(0.5, 64, 0.5);
		// Flying north (-z) toward the body from the south, a little to the east of it: facing south, east is the left.
		Fireball fireball = new Fireball(new Vec3(0.9, 65.4, 10.5), new Vec3(0, 0, -1), null);
		Vec3 face = CollectBlazeRodsTaskExecutor.facing(feet, fireball);
		assertEquals(1.0, face.z, 1e-9, "faces back along the flight");
		// The body is west of the line, which is the right when facing south: step further right.
		assertEquals(Side.RIGHT, CollectBlazeRodsTaskExecutor.strafeSide(feet, fireball, true, true));
		assertEquals(Side.LEFT, CollectBlazeRodsTaskExecutor.strafeSide(feet, fireball, true, false), "the other side when lava is there");
		assertEquals(Side.NONE, CollectBlazeRodsTaskExecutor.strafeSide(feet, fireball, false, false));
	}

	// ---- the fight ----

	@Test void withoutABowHidesSoBlazesComeIntoReachAndCollectsTheRods() {
		World world = new World();
		world.spawning = true;
		TaskTerminalEvent end = world.run(2, 4_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(end.message().startsWith("blaze_rods_collected rods=2"), end.message());
		assertEquals(2, world.rods);
		assertTrue(world.meleeHits >= 8, "two blazes of 20 health at 6 a hit: " + world.meleeHits);
		assertEquals(0, world.arrowsShot);
		assertTrue(world.visited.contains(COVER), "hid at the cover spot so blazes flew in");
		assertTrue(world.navigated.stream().allMatch(goal -> goal.equals(SPOT) || goal.equals(COVER) || goal.y() == 64),
			"never routes below the floor: " + world.navigated);
		assertTrue(world.options.stream().allMatch(o -> Integer.valueOf(CollectBlazeRodsTaskExecutor.MAX_SAFE_FALL).equals(o.maxSafeFall())),
			"every route caps falls at three blocks");
	}

	@Test void shootsAFarBlazeWithABowFromTheSpot() {
		World world = new World();
		world.hasBow = true;
		world.blaze(new Vec3(0.5, 66, 0.5));
		world.hideAtCover = false;
		TaskTerminalEvent end = world.run(1, 2_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(world.arrowsShot >= 3, "three arrows for 20 health: " + world.arrowsShot);
		assertEquals(0, world.meleeHits);
		assertFalse(world.visited.contains(COVER), "a bow needs no ambush");
	}

	@Test void raisesTheShieldAgainstAnIncomingFireball() {
		World world = new World();
		world.hasShield = true;
		world.coverSpots.clear();
		world.blaze(new Vec3(0.5, 66, 0.5));
		world.beforeTick = w -> {
			if (w.atSpot() && w.ticks % 40 == 0) w.fire(w.blazes.getFirst());
		};
		world.runTicks(1, 300);
		assertTrue(world.shieldRaises > 0, "shield raised");
		assertTrue(world.strafeSides.isEmpty(), "no need to strafe behind a shield: " + world.strafeSides);
	}

	@Test void withoutAShieldStrafesOffTheLineTowardSafeGround() {
		World world = new World();
		world.blaze(new Vec3(0.5, 66, 0.5));
		world.coverSpots.clear();
		world.rightSafe = false;
		world.beforeTick = w -> {
			if (w.atSpot() && w.fireballs.isEmpty() && w.strafeSides.isEmpty()) w.fire(w.blazes.getFirst());
		};
		world.runTicks(1, 200);
		assertEquals(0, world.shieldRaises);
		assertFalse(world.strafeSides.isEmpty(), "strafed");
		assertTrue(world.strafeSides.stream().allMatch(side -> side == Side.LEFT), "only onto the safe side: " + world.strafeSides);
	}

	@Test void atLowHealthHidesEatsAndComesBack() {
		World world = new World();
		world.blaze(new Vec3(0.5, 66, 0.5));
		world.feet = new Vec3(8.5, 64, 0.5);
		world.health = 9.0F;
		world.food = 12;
		world.foodItems = 3;
		world.hideAtCover = true;
		world.blazesApproachHiddenBody = false;
		boolean[] resumed = {false};
		world.beforeTick = w -> {
			if (w.health >= CollectBlazeRodsTaskExecutor.RESUME_HEALTH && w.snapshot().contains("mode=APPROACH")) resumed[0] = true;
		};
		world.runTicks(1, 1_500);
		assertTrue(world.visited.contains(COVER), "went out of sight");
		assertTrue(world.eaten >= 1, "ate behind cover");
		assertTrue(resumed[0], "went back to the fight at " + CollectBlazeRodsTaskExecutor.RESUME_HEALTH + " health");
	}

	@Test void drinksFireResistanceWhenSetAlight() {
		World world = new World();
		world.blaze(new Vec3(0.5, 66, 0.5));
		world.feet = new Vec3(8.5, 64, 0.5);
		world.onFire = true;
		world.potions = 1;
		world.runTicks(1, 100);
		assertEquals(0, world.potions, "drank it");
		assertTrue(world.fireResistant);
		assertFalse(world.onFire);
	}

	@Test void lowHealthWithNothingToEatFailsOutOfFood() {
		World world = new World();
		world.blaze(new Vec3(0.5, 66, 0.5));
		world.feet = new Vec3(8.5, 64, 0.5);
		world.health = 9.0F;
		world.food = 10;
		world.foodItems = 0;
		world.blazesApproachHiddenBody = false;
		TaskTerminalEvent end = world.run(1, 1_000);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("out_of_food rods=0"), end.message());
		assertEquals(TaskFailureCode.MISSING_ITEM, end.failureCode());
		assertTrue(world.visited.contains(COVER), "hid before giving up");
	}

	@Test void criticalHealthInSightOfBlazesHandsBack() {
		World world = new World();
		world.blaze(new Vec3(0.5, 66, 0.5));
		world.health = 4.0F;
		world.coverSpots.clear();
		TaskTerminalEvent end = world.run(1, 200);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("low_health rods=0"), end.message());
	}

	@Test void tooManyBlazesInSightSendsItBehindCover() {
		World world = new World();
		for (int i = 0; i < 5; i++) world.blaze(new Vec3(-2.5 + i, 66, 0.5));
		world.blazesApproachHiddenBody = false;
		Set<String> actions = new LinkedHashSet<>();
		world.beforeTick = w -> actions.add(w.snapshot().replaceAll(".*action=(\\S+).*", "$1"));
		world.runTicks(1, 300);
		assertTrue(actions.contains("cover_thin_out"), "took cover from five blazes: " + actions);
		assertTrue(world.visited.contains(COVER));
	}

	@Test void picksUpRodsOnlyFromSafeGroundWithNoBlazeClose() {
		World world = new World();
		world.rodsOnGround.add(new DroppedRod(new Vec3(6.5, 64, 2.5), false));
		world.rodsOnGround.add(new DroppedRod(new Vec3(9.5, 64, -2.5), true));
		world.runTicks(3, 200);
		assertEquals(1, world.rods, "fetched the safe rod");
		assertEquals(1, world.rodsOnGround.size());
		assertFalse(world.rodsOnGround.getFirst().safe(), "left the one by lava");

		World guarded = new World();
		guarded.rodsOnGround.add(new DroppedRod(new Vec3(3.5, 64, 0.5), true));
		guarded.blaze(new Vec3(1.5, 66, 0.5));
		guarded.blazeHealthPerHit = 0;
		guarded.blazesApproachHiddenBody = false;
		guarded.hideAtCover = false;
		guarded.runTicks(3, 200);
		assertEquals(0, guarded.rods, "no walking under a blaze for a rod");
		assertFalse(CollectBlazeRodsTaskExecutor.safeToFetch(new DroppedRod(new Vec3(3.5, 64, 0.5), true),
			List.of(new Blaze(1, new Vec3(1.5, 66, 0.5), 7, true, false)), new Vec3(8.5, 64, 0.5)));
		assertTrue(CollectBlazeRodsTaskExecutor.safeToFetch(new DroppedRod(new Vec3(3.5, 64, 0.5), true),
			List.of(new Blaze(1, new Vec3(1.5, 66, 0.5), 7, false, false)), new Vec3(8.5, 64, 0.5)), "a blaze that cannot see is no bar");
	}

	// ---- endings ----

	@Test void endsAtOnceWhenTheRodsAreAlreadyCarried() {
		World world = new World();
		world.rods = 5;
		TaskTerminalEvent end = world.run(4, 5);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState());
		assertTrue(end.message().startsWith("blaze_rods_collected rods=5"), end.message());
	}

	@Test void noSpawnerAndNoBlazeInSightFailsWithTheFortressHint() {
		World world = new World();
		world.spawner = null;
		world.fortress = new GoalPosition(40, 70, 40, true);
		TaskTerminalEvent end = world.run(7, 200);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("no_blazes_found rods=0"), end.message());
		assertTrue(end.message().contains("nether bricks at 40,70,40"), end.message());
		assertEquals(TaskFailureCode.MISSING_FACT, end.failureCode());
	}

	@Test void aSpawnerThatNeverSpawnsFailsAfterTheIdleDeadline() {
		World world = new World();
		TaskTerminalEvent end = world.run(7, CollectBlazeRodsTaskExecutor.NO_BLAZE_TICKS + 500);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("no_blazes_found rods=0; no blaze in sight for"), end.message());
		assertTrue(end.message().contains("spawner at 0,64,0"), end.message());
	}

	@Test void noSafeSpotFailsAndSaysWhy() {
		World world = new World();
		world.blaze(new Vec3(0.5, 66, 0.5));
		world.fightingSpots.clear();
		world.fightingSpots.add(new Spot(new GoalPosition(8, 64, 0, true), true, true, false, true, 1, 0, 8));
		world.fightingSpots.add(new Spot(new GoalPosition(0, 64, 8, true), true, false, true, true, 1, 0, 8));
		TaskTerminalEvent end = world.run(7, 50);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("no_safe_position rods=0"), end.message());
		assertTrue(end.message().contains("lava_or_fire=1 edge_drop=1"), end.message());
		assertTrue(world.navigated.isEmpty(), "never walked toward an unsafe spot");
	}

	@Test void anUnreachableSpotIsDroppedAndTheNextTried() {
		World world = new World();
		world.blaze(new Vec3(0.5, 66, 0.5));
		world.hasBow = true;
		world.hideAtCover = false;
		world.unreachable.add(new GoalPosition(0, 64, 8, true));
		world.fightingSpots.add(new Spot(new GoalPosition(0, 64, 8, true), true, false, false, true, 1, 4, 8));
		TaskTerminalEvent end = world.run(1, 2_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertEquals(new GoalPosition(0, 64, 8, true), world.navigated.getFirst(), "tried the walled spot first");
		assertTrue(world.visited.contains(SPOT));
	}

	@Test void outsideTheNetherFailsWrongDimension() {
		World world = new World();
		world.nether = false;
		TaskTerminalEvent end = world.run(7, 5);
		assertTrue(end.message().startsWith("wrong_dimension rods=0"), end.message());
		assertEquals(TaskFailureCode.INVALID_ACTION, end.failureCode());
	}

	@Test void aFightThatNeverEndsTimesOut() {
		World world = new World();
		world.blaze(new Vec3(0.5, 66, 0.5));
		world.blazeHealthPerHit = 0;
		world.blazesApproachHiddenBody = false;
		world.coverSpots.clear();
		TaskTerminalEvent end = world.run(7, CollectBlazeRodsTaskExecutor.TIMEOUT_TICKS + 10);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("timeout rods=0"), end.message());
		assertEquals(TaskFailureCode.TRANSIENT, end.failureCode());
	}

	@Test void countDefaultsToSevenAndIsBounded() {
		assertEquals(7, CollectBlazeRodsStepArgs.parse(new JsonObject()).count());
		JsonObject twelve = new JsonObject();
		twelve.addProperty("count", 12);
		assertEquals(12, CollectBlazeRodsStepArgs.parse(twelve).count());
		JsonObject zero = new JsonObject();
		zero.addProperty("count", 0);
		assertThrows(IllegalArgumentException.class, () -> CollectBlazeRodsStepArgs.parse(zero));
		JsonObject many = new JsonObject();
		many.addProperty("count", 17);
		assertThrows(IllegalArgumentException.class, () -> CollectBlazeRodsStepArgs.parse(many));
		JsonObject fraction = new JsonObject();
		fraction.addProperty("count", 2.5);
		assertThrows(IllegalArgumentException.class, () -> CollectBlazeRodsStepArgs.parse(fraction));
		JsonObject unknown = new JsonObject();
		unknown.addProperty("rods", 3);
		assertThrows(IllegalArgumentException.class, () -> CollectBlazeRodsStepArgs.parse(unknown));
	}

	private static Spot spot(int x, int z, double anchorDistance, int cover) {
		return new Spot(new GoalPosition(x, 64, z, true), true, false, false, true, 1, cover, anchorDistance);
	}

	/**
	 * A flat nether floor at y=64 with a spawner at the origin, a safe fighting spot 8 blocks east and a cover spot behind a
	 * wall 6 blocks south of it. Blazes hover; out of the body's sight they fly toward it, as vanilla blazes do. A hit does
	 * {@link #blazeHealthPerHit}; a dead blaze drops a rod on safe ground. Navigation walks a block a tick.
	 */
	private static final class World implements CollectBlazeRodsTaskExecutor.Environment, NavigationFacade {
		Vec3 feet = new Vec3(20.5, 64, 0.5);
		float health = 20.0F;
		int food = 20, foodItems = 4, rods;
		boolean nether = true, hasBow, hasShield, spawning, hideAtCover = true, blazesApproachHiddenBody = true;
		boolean leftSafe = true, rightSafe = true, onFire, fireResistant;
		int potions;
		float blazeHealthPerHit = 6.0F;
		GoalPosition spawner = SPAWNER, fortress = new GoalPosition(10, 70, 10, true);
		final List<Spot> fightingSpots = new ArrayList<>(List.of(new Spot(SPOT, true, false, false, true, 1, 1, 8.0)));
		final List<Spot> coverSpots = new ArrayList<>(List.of(new Spot(COVER, true, false, false, false, 0, 3, 0)));
		final List<FakeBlaze> blazes = new ArrayList<>();
		final List<Fireball> fireballs = new ArrayList<>();
		final List<DroppedRod> rodsOnGround = new ArrayList<>();
		final Set<GoalPosition> unreachable = new HashSet<>();
		final List<GoalPosition> navigated = new ArrayList<>();
		final List<NavigationOptions> options = new ArrayList<>();
		final Set<GoalPosition> visited = new HashSet<>();
		final List<Side> strafeSides = new ArrayList<>();
		int meleeHits, arrowsShot, shieldRaises, eaten, ticks;
		Consumer<World> beforeTick = w -> {};
		private int nextId = 1, spawnCooldown, eatTicks, aimed = -1;
		private GoalPosition goal;
		private final Queue<String> events = new ArrayDeque<>();
		private final CollectBlazeRodsTaskExecutor executor = new CollectBlazeRodsTaskExecutor(this, this);

		final class FakeBlaze {
			final int id = nextId++;
			Vec3 position;
			float health = 20.0F;
			FakeBlaze(Vec3 position) { this.position = position; }
		}

		FakeBlaze blaze(Vec3 position) {
			FakeBlaze blaze = new FakeBlaze(position);
			blazes.add(blaze);
			return blaze;
		}

		void fire(FakeBlaze from) {
			Vec3 toward = feet.add(0, 0.9, 0).subtract(from.position).normalize();
			fireballs.add(new Fireball(from.position, toward.scale(1.2), from.position));
		}

		boolean atSpot() { return Math.hypot(feet.x - (SPOT.x() + 0.5), feet.z - (SPOT.z() + 0.5)) < 0.5; }

		String snapshot() { return executor.snapshot().lastPathEvent() == null ? "" : executor.snapshot().lastPathEvent(); }

		TaskTerminalEvent run(int count, int maxTicks) {
			TaskTerminalEvent end = runTicks(count, maxTicks);
			if (end == null) fail("no terminal after " + maxTicks + " ticks: " + snapshot());
			return end;
		}

		TaskTerminalEvent runTicks(int count, int maxTicks) {
			WorldTaskRequest request = WorldTaskRequest.collectBlazeRods("blaze", "job", new CollectBlazeRodsStepArgs(count));
			for (int i = 0; i < maxTicks; i++) {
				ticks++;
				beforeTick.accept(this);
				step();
				var session = new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:the_nether", true, 25565, ticks);
				var end = executor.tick(session, Optional.of(request));
				if (end.isPresent()) return end.get();
			}
			return null;
		}

		private boolean hidden() {
			return hideAtCover && Math.hypot(feet.x - (COVER.x() + 0.5), feet.z - (COVER.z() + 0.5)) < 1.0;
		}

		private boolean sees(FakeBlaze blaze) {
			return !hidden() || blaze.position.distanceTo(eye()) <= 4.0;
		}

		private Vec3 eye() { return feet.add(0, 1.62, 0); }

		private void step() {
			if (goal != null) {
				Vec3 center = new Vec3(goal.x() + 0.5, 64, goal.z() + 0.5);
				Vec3 delta = center.subtract(feet);
				if (delta.horizontalDistance() <= 1.0 + 1e-6) {
					feet = center;
					visited.add(goal);
					goal = null;
					events.add("AT_GOAL");
				} else {
					feet = feet.add(delta.normalize());
					if (center.subtract(feet).horizontalDistance() < 0.5) visited.add(goal);
				}
			}
			for (FakeBlaze blaze : blazes) {
				// A blaze that loses sight of its target flies to it, into reach.
				Vec3 to = eye().subtract(blaze.position);
				if (blazesApproachHiddenBody && hidden() && to.length() > 2.5) blaze.position = blaze.position.add(to.normalize().scale(0.3));
			}
			for (var it = blazes.iterator(); it.hasNext(); ) {
				FakeBlaze blaze = it.next();
				if (blaze.health > 0) continue;
				it.remove();
				rodsOnGround.add(new DroppedRod(new Vec3(blaze.position.x, 64, blaze.position.z), true));
			}
			if (spawning && blazes.isEmpty() && --spawnCooldown <= 0) {
				blaze(new Vec3(0.5, 65.5, 0.5));
				spawnCooldown = 40;
			}
			// Fireballs fly one step and vanish after passing the body or hitting it.
			for (var it = fireballs.listIterator(); it.hasNext(); ) {
				Fireball fireball = it.next();
				Vec3 next = fireball.position().add(fireball.velocity());
				if (next.distanceTo(feet.add(0, 0.9, 0)) < 0.8) {
					health -= 5;
					it.remove();
				} else if (next.distanceTo(feet) > 30 || fireball.position().distanceTo(fireball.origin()) > 20) it.remove();
				else it.set(new Fireball(next, fireball.velocity(), fireball.origin()));
			}
			for (var it = rodsOnGround.iterator(); it.hasNext(); ) {
				DroppedRod rod = it.next();
				if (Math.hypot(rod.position().x - feet.x, rod.position().z - feet.z) <= 1.0) {
					rods++;
					it.remove();
				}
			}
			if (food >= 18 && health < 20) health = Math.min(20, health + 0.1F);
		}

		// Environment
		public Arena observe() {
			List<Blaze> seen = blazes.stream().map(b -> new Blaze(b.id, b.position, b.position.distanceTo(eye()), sees(b),
				b.position.distanceTo(eye()) <= 3.0)).toList();
			return new Arena(nether, feet, health, food, onFire, fireResistant, rods, seen, List.copyOf(fireballs), List.copyOf(rodsOnGround),
				hasBow, hasShield, foodItems > 0, potions > 0);
		}
		public Optional<GoalPosition> blazeSpawner(Vec3 feet) { return Optional.ofNullable(spawner); }
		public Optional<GoalPosition> fortress(Vec3 feet) { return Optional.ofNullable(fortress); }
		public List<Spot> fightingSpots(Arena arena, CollectBlazeRodsTaskExecutor.Anchor anchor) { return List.copyOf(fightingSpots); }
		public List<Spot> coverSpots(Arena arena) { return List.copyOf(coverSpots); }
		public CollectBlazeRodsTaskExecutor.StrafeOptions strafeOptions(Vec3 feet, Vec3 facing) {
			return new CollectBlazeRodsTaskExecutor.StrafeOptions(leftSafe, rightSafe);
		}
		public boolean raiseShield(Vec3 face) {
			if (!hasShield) return false;
			shieldRaises++;
			fireballs.clear(); // blocked
			return true;
		}
		public void lowerShield() {}
		public void strafe(Side side, Vec3 face) {
			assertNull(goal, "navigation stops while strafing");
			strafeSides.add(side);
			Vec3 f = new Vec3(face.x - feet.x, 0, face.z - feet.z).normalize();
			Vec3 left = new Vec3(f.z, 0, -f.x);
			feet = feet.add((side == Side.LEFT ? left : left.reverse()).scale(0.25));
		}
		public void stopMoving() {}
		public CollectBlazeRodsTaskExecutor.MeleeResult melee(int blazeId) {
			FakeBlaze blaze = blazes.stream().filter(b -> b.id == blazeId).findFirst().orElse(null);
			if (blaze == null || blaze.position.distanceTo(eye()) > 3.0) return CollectBlazeRodsTaskExecutor.MeleeResult.OUT_OF_REACH;
			if (ticks % 12 != 0) return CollectBlazeRodsTaskExecutor.MeleeResult.COOLDOWN;
			meleeHits++;
			blaze.health -= blazeHealthPerHit;
			return CollectBlazeRodsTaskExecutor.MeleeResult.HIT;
		}
		public CollectBlazeRodsTaskExecutor.AimResult aimBow(int blazeId) {
			assertTrue(hasBow, "no bow, no aiming");
			aimed = blazeId;
			return CollectBlazeRodsTaskExecutor.AimResult.ALIGNED;
		}
		public boolean drawBow() { return hasBow; }
		public void releaseBow() {
			arrowsShot++;
			blazes.stream().filter(b -> b.id == aimed).findFirst().ifPresent(b -> b.health -= 9);
		}
		public void cancelBow() {}
		public CollectBlazeRodsTaskExecutor.ConsumeResult consume(CollectBlazeRodsTaskExecutor.Consumable kind) {
			if (kind == CollectBlazeRodsTaskExecutor.Consumable.FIRE_RESISTANCE) {
				if (potions <= 0) return CollectBlazeRodsTaskExecutor.ConsumeResult.NOTHING;
				if (++eatTicks < 32) return CollectBlazeRodsTaskExecutor.ConsumeResult.CONSUMING;
				eatTicks = 0;
				potions--;
				fireResistant = true;
				onFire = false;
				return CollectBlazeRodsTaskExecutor.ConsumeResult.DONE;
			}
			if (foodItems <= 0) return CollectBlazeRodsTaskExecutor.ConsumeResult.NOTHING;
			if (++eatTicks < 32) return CollectBlazeRodsTaskExecutor.ConsumeResult.CONSUMING;
			eatTicks = 0;
			foodItems--;
			eaten++;
			food = Math.min(20, food + 8);
			return CollectBlazeRodsTaskExecutor.ConsumeResult.DONE;
		}
		public void stopConsuming() { eatTicks = 0; }
		public void release() {}

		// Navigation
		public boolean isLoaded() { return true; }
		public void startFollow(String s) { fail("no following"); }
		public void startNavigate(GoalPosition p, NavigationOptions o) {
			navigated.add(p);
			options.add(o);
			if (unreachable.contains(p)) {
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
