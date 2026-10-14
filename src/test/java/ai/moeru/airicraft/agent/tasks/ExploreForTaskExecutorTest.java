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
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ExploreForTaskExecutorTest {
	@Test void walksOutUntilTheForestIsInLoadedChunks() {
		World world = new World();
		// A desert with no tree within 200 blocks of the start, whichever way.
		world.feature = World.ring(200);
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 5_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(end.message().startsWith("feature_found featureKind=forest pos="), end.message());
		assertTrue(world.furthest >= 200 - World.LOADED, "walked out until it loaded: " + world.furthest);
		assertTrue(end.message().contains("explored: start=0,0"), end.message());
		assertEquals(Set.of("forest"), world.searchedKinds);
		assertTrue(world.legGoals.size() >= 2, "it had to walk: " + world.legGoals);
		assertTrue(world.legGoals.stream().allMatch(goal -> !goal.exactY()), "columns, not exact cells");
		assertEquals(NavigationOptions.WALK_ONLY.withWaterPenalty(ExploreForTaskExecutor.WATER_PENALTY), world.lastOptions,
			"walking only, no tunnelling, water priced high");
		assertNull(world.goal, "navigation released");
	}

	@Test void aFeatureAlreadyLoadedEndsAtOnce() {
		World world = new World();
		world.feature = feet -> new int[]{40, 70, -20};
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("water_body", 320), 10);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(end.message().contains("pos=40,70,-20"), end.message());
		assertTrue(world.legGoals.isEmpty());
	}

	@Test void goesAroundALakeOnDryLand() {
		World world = new World();
		// A lake east of the start.
		world.water = (x, z) -> x >= 20 && x <= 140 && Math.abs(z) <= 30;
		world.feature = World.ring(160);
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 5_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertEquals(0, world.wetTicks, "never walked into the lake");
		assertTrue(world.legGoals.stream().noneMatch(goal -> world.water.test(goal.x(), goal.z())), "every leg ends on dry land");
	}

	@Test void aHeadingWhoseRoutesFailIsDropped() {
		World world = new World();
		world.blocked = goal -> goal.x() > 10;
		world.feature = World.ring(160);
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 5_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		long eastward = world.legGoals.stream().filter(goal -> goal.x() > 10).count();
		assertTrue(eastward <= 2L * 7, "each blocked heading is tried at most twice: " + eastward);
	}

	@Test void stopsAtNightSoTheCallerCanShelter() {
		World world = new World();
		world.nightAt = 40;
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 1_000);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("night_fell"), end.message());
		assertTrue(end.message().contains("explored: start=0,0 furthest="), end.message());
		assertNull(world.goal, "stops walking");
		assertEquals(TaskFailureCode.ENVIRONMENT_CHANGED, end.failureCode());
	}

	@Test void lowHealthStopsTheWalk() {
		World world = new World();
		world.health = 5;
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 10);
		assertTrue(end.message().startsWith("low_health"), end.message());
	}

	@Test void nothingWithinMaxDistanceFailsWithTheExtent() {
		World world = new World();
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("lava_pool", 96), 20_000);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("max_distance"), end.message());
		assertTrue(world.furthest <= 96 + ExploreForTaskExecutor.ARRIVE_BLOCKS, "stays inside maxDistance: " + world.furthest);
		assertTrue(end.message().contains("walked="), end.message());
		assertEquals(TaskFailureCode.MISSING_FACT, end.failureCode());
	}

	@Test void anIslandWithWaterOnEveryHeadingIsBlocked() {
		World world = new World();
		world.water = (x, z) -> Math.hypot(x, z) > 6;
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 1_000);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("route_blocked"), end.message());
		assertTrue(world.legGoals.isEmpty());
	}

	@Test void legChoiceAvoidsWalkedGroundAndWaterOnTheWay() {
		Vec3 feet = new Vec3(0.5, 64, 0.5);
		ExploreForTaskExecutor.GroundView dry = (x, z, y) -> new ExploreForTaskExecutor.Ground(ExploreForTaskExecutor.Ground.Kind.DRY, 64);
		// Walked in from the west: the next leg keeps going east, away from it.
		var walked = List.of(new Vec3(-48, 64, 0), new Vec3(-24, 64, 0), feet);
		var east = ExploreForTaskExecutor.chooseLeg(feet, new Vec3(-48, 64, 0), 320, walked, new int[16], 0, dry);
		assertEquals(0, east.leg().heading(), east.toString());
		// A strip of water across the east: a heading that stays dry wins.
		ExploreForTaskExecutor.GroundView strip = (x, z, y) -> new ExploreForTaskExecutor.Ground(
			x >= 10 && x <= 20 && Math.abs(z) < 20 ? ExploreForTaskExecutor.Ground.Kind.WATER : ExploreForTaskExecutor.Ground.Kind.DRY, 64);
		var around = ExploreForTaskExecutor.chooseLeg(feet, new Vec3(-48, 64, 0), 320, walked, new int[16], 0, strip);
		assertNotEquals(0, around.leg().heading(), around.toString());
		assertTrue(Math.abs(around.leg().z()) >= 20 || around.leg().x() < 10, around.toString());
		// Inside maxDistance everything is walked: nothing new is left.
		var done = ExploreForTaskExecutor.chooseLeg(feet, feet, 20, List.of(feet), new int[16], null, dry);
		assertNull(done.leg());
		assertTrue(done.exhausted());
	}

	@Test void nightRunsFromDuskToDawn() {
		assertFalse(ExploreForTaskExecutor.night(6_000));
		assertTrue(ExploreForTaskExecutor.night(12_600));
		assertTrue(ExploreForTaskExecutor.night(18_000 + 24_000 * 3));
		assertFalse(ExploreForTaskExecutor.night(23_500));
	}

	@Test void argumentsAreCheckedAndDefaulted() {
		JsonObject args = new JsonObject();
		args.addProperty("featureKind", "forest");
		assertEquals(new ExploreForStepArgs("forest", ExploreForStepArgs.DEFAULT_MAX_DISTANCE), ExploreForStepArgs.parse(args));
		JsonObject unknownKind = new JsonObject();
		unknownKind.addProperty("featureKind", "village");
		assertThrows(IllegalArgumentException.class, () -> ExploreForStepArgs.parse(unknownKind));
		JsonObject tooFar = args.deepCopy();
		tooFar.addProperty("maxDistance", 5000);
		assertThrows(IllegalArgumentException.class, () -> ExploreForStepArgs.parse(tooFar));
		JsonObject extra = args.deepCopy();
		extra.addProperty("direction", "north");
		assertThrows(IllegalArgumentException.class, () -> ExploreForStepArgs.parse(extra));
		assertThrows(IllegalArgumentException.class, () -> ExploreForStepArgs.parse(new JsonObject()));
	}

	/**
	 * Flat dry ground at y=64 except where {@link #water} says, loaded within 96 blocks of the body. Navigation walks
	 * 4 blocks a tick in a straight line and refuses blocked goals. The feature shows up once it is in loaded chunks.
	 */
	private static final class World implements ExploreForTaskExecutor.Environment, NavigationFacade {
		static final double LOADED = 96;
		Vec3 feet = new Vec3(0.5, 64, 0.5);
		float health = 20;
		int nightAt = Integer.MAX_VALUE;
		/** Where the feature is once the body stands at the given feet, or null while it is not there. */
		java.util.function.Function<Vec3, int[]> feature = feet -> null;
		BiPredicate<Integer, Integer> water = (x, z) -> false;
		Predicate<GoalPosition> blocked = goal -> false;
		final List<GoalPosition> legGoals = new ArrayList<>();
		final Set<String> searchedKinds = new HashSet<>();
		NavigationOptions lastOptions;
		int wetTicks;
		double furthest;
		GoalPosition goal;
		private int goalRadius;
		private final Queue<String> events = new ArrayDeque<>();
		private final ExploreForTaskExecutor executor = new ExploreForTaskExecutor(this, this);
		private long ticks;

		/** Trees everywhere beyond {@code radius} blocks of the start: the nearest is straight out from the feet. */
		static java.util.function.Function<Vec3, int[]> ring(double radius) {
			return feet -> {
				double out = Math.hypot(feet.x - 0.5, feet.z - 0.5);
				if (out < 1) return null;
				return new int[]{(int) Math.floor(0.5 + (feet.x - 0.5) / out * radius), 64, (int) Math.floor(0.5 + (feet.z - 0.5) / out * radius)};
			};
		}

		TaskTerminalEvent run(ExploreForStepArgs args, int maxTicks) {
			WorldTaskRequest request = WorldTaskRequest.exploreFor("explore", "job", args);
			for (int i = 0; i < maxTicks; i++) {
				step();
				var session = new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, ++ticks);
				var end = executor.tick(session, Optional.of(request));
				if (end.isPresent()) return end.get();
			}
			fail("no terminal after " + maxTicks + " ticks: " + executor.snapshot().lastPathEvent());
			return null;
		}

		private void step() {
			if (goal == null) return;
			Vec3 center = new Vec3(goal.x() + 0.5, 64, goal.z() + 0.5);
			Vec3 delta = center.subtract(feet);
			if (delta.horizontalDistance() <= Math.max(goalRadius, 4)) {
				feet = center;
				goal = null;
				events.add("AT_GOAL");
			} else feet = feet.add(delta.normalize().scale(4));
			if (water.test((int) Math.floor(feet.x), (int) Math.floor(feet.z))) wetTicks++;
			furthest = Math.max(furthest, Math.hypot(feet.x - 0.5, feet.z - 0.5));
		}

		// Environment
		public ExploreForTaskExecutor.Body observe() { return new ExploreForTaskExecutor.Body(feet, health, ticks >= nightAt); }
		public Optional<ExploreForTaskExecutor.Feature> findFeature(String kind, Vec3 at, int radius) {
			searchedKinds.add(kind);
			int[] found = feature.apply(at);
			if (found == null || Math.hypot(found[0] + 0.5 - at.x, found[2] + 0.5 - at.z) > Math.min(radius, LOADED))
				return Optional.empty();
			return Optional.of(new ExploreForTaskExecutor.Feature(found[0], found[1], found[2], "{kind=" + kind + "}"));
		}
		public ExploreForTaskExecutor.Ground ground(int x, int z, int nearY) {
			if (Math.hypot(x + 0.5 - feet.x, z + 0.5 - feet.z) > LOADED) return ExploreForTaskExecutor.Ground.UNLOADED;
			return new ExploreForTaskExecutor.Ground(water.test(x, z) ? ExploreForTaskExecutor.Ground.Kind.WATER
				: ExploreForTaskExecutor.Ground.Kind.DRY, 64);
		}
		public void release() {}

		// Navigation
		public boolean isLoaded() { return true; }
		public void startFollow(String s) { fail("no following"); }
		public void startNavigate(GoalPosition p, NavigationOptions o) { startNavigateNear(p, 0, o); }
		public void startNavigateNear(GoalPosition p, int r, NavigationOptions o) {
			legGoals.add(p);
			lastOptions = o;
			goalRadius = r;
			if (blocked.test(p)) {
				goal = null;
				events.add("CALC_FAILED");
			} else goal = p;
		}
		public boolean processActive() { return goal != null; }
		public void cancel() { goal = null; }
		public Optional<String> activeProcessName() { return Optional.empty(); }
		public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		public Optional<String> pollPathEvent() { return Optional.ofNullable(events.poll()); }
		public boolean navigationGoalReached(GoalPosition p) { return false; }
	}
}
