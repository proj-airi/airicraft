package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ExploreForTaskExecutorTest {
	@Test void walksOutUntilTheForestIsInLoadedChunksThenWalksToIt() {
		World world = new World();
		// A desert with no tree within 200 blocks of the start, whichever way.
		world.feature = World.ring(200);
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 5_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(end.message().startsWith("feature_found featureKind=forest pos="), end.message());
		assertTrue(end.message().contains("reached=true"), end.message());
		assertTrue(world.furthest >= 200 - 3, "walked to the forest itself: " + world.furthest);
		assertTrue(end.message().contains("explored: start=0,0"), end.message());
		assertEquals(Set.of("forest"), world.searchedKinds);
		assertTrue(world.legGoals.size() >= 2, "it had to walk: " + world.legGoals);
		assertTrue(world.legGoals.stream().allMatch(goal -> !goal.exactY()), "columns, not exact cells");
		assertEquals(NavigationOptions.WALK_ONLY.withWaterPenalty(ExploreForTaskExecutor.WATER_PENALTY), world.lastOptions,
			"walking only, no tunnelling, water priced high");
		assertNull(world.goal, "navigation released");
	}

	@Test void aFeatureAlreadyLoadedIsWalkedToAtOnce() {
		World world = new World();
		world.feature = feet -> new int[]{40, 70, -20};
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("water_body", 320), 100);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(end.message().contains("pos=40,70,-20"), end.message());
		assertEquals(List.of(new GoalPosition(40, 70, -20, false)), world.legGoals, "straight to it, no exploring leg");
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
		assertTrue(eastward <= 2L * 7 + 1, "each blocked heading is tried at most twice: " + eastward);
	}

	@Test void headsWhereTheLandLooksGreenRatherThanStraightOut() {
		World world = new World();
		// Sand all round, but grass and a few tree tops show to the north-west, far out; trees only past 300 blocks there.
		world.surface = (cx, cz) -> cx * 16 < -100 && cz * 16 < -100
			? new ExplorationMemory.Surface(64, 4, 50, 0, 0) : new ExplorationMemory.Surface(64, 0, 0, 64, 0);
		world.feature = feet -> feet.x < -200 && feet.z < -200 ? new int[]{(int) feet.x - 20, 64, (int) feet.z - 20} : null;
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 640), 20_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(world.milestones().containsAll(List.of("land_in_sight", "feature_seen", "walked")), world.progress.toString());
		assertTrue(world.progress.getFirst().message().contains("northwest"), world.progress.toString());
		GoalPosition first = world.legGoals.getFirst();
		assertTrue(first.x() < 0 && first.z() < 0, "the first leg already heads north-west: " + first);
		assertTrue(world.walked < 600, "walked nearly straight there: " + world.walked);
	}

	@Test void aSecondCallDoesNotWalkTheSameGroundAgain() {
		World world = new World();
		TaskTerminalEvent first = world.run(new ExploreForStepArgs("lava_pool", 96), 20_000);
		assertTrue(first.message().startsWith("max_distance"), first.message());
		List<GoalPosition> firstLegs = List.copyOf(world.legGoals);
		world.feet = new Vec3(0.5, 64, 0.5);
		world.run(new ExploreForStepArgs("lava_pool", 96), 20_000, "explore-2");
		List<GoalPosition> secondLegs = world.legGoals.subList(firstLegs.size(), world.legGoals.size());
		assertFalse(secondLegs.isEmpty());
		for (GoalPosition leg : secondLegs) {
			for (GoalPosition earlier : firstLegs) {
				assertTrue(Math.hypot(leg.x() - earlier.x(), leg.z() - earlier.z()) >= ExploreForTaskExecutor.MIN_NOVELTY_BLOCKS,
					"leg " + leg + " goes back to " + earlier);
			}
		}
	}

	@Test void swimsToAShoreItCanSeeWhenOnlyWaterIsLeft() {
		World world = new World();
		// A small island; dry land again 70 blocks east, across a strait.
		world.water = (x, z) -> Math.hypot(x, z) > 6 && x < 70;
		world.feature = feet -> feet.x > 80 ? new int[]{110, 64, 0} : null;
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 5_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(end.message().contains("crossings=1"), end.message());
		assertTrue(world.optionsSeen.contains(ExploreForTaskExecutor.CROSSING_OPTIONS), "swam with water at its usual price");
	}

	@Test void anIslandWithNoShoreInSightIsBlocked() {
		World world = new World();
		world.water = (x, z) -> Math.hypot(x, z) > 6;
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 1_000);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("route_blocked"), end.message());
		assertTrue(world.legGoals.isEmpty());
	}

	@Test void withWaitAtNightItDigsInForTheNightAndWalksOnInTheMorning() {
		World world = new World();
		world.roofBlocks = true;
		world.nightAt = 40;
		world.dayAt = 400;
		world.feature = World.ring(200);
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320, 3, true), 5_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertEquals(1, world.sheltersDug);
		assertTrue(world.ticksInShelter >= 300, "stayed in until morning: " + world.ticksInShelter);
		assertTrue(world.optionsSeen.contains(ExploreForTaskExecutor.EXIT_OPTIONS), "climbed out, digging if it had to");
		assertTrue(end.message().contains("nightsSheltered=1"), end.message());
	}

	@Test void handsTheNightBackOnceThePitIsRoofedThenWaitsInItAndWalksOnAtDawn() {
		World world = new World();
		world.roofBlocks = true;
		world.nightAt = 40;
		world.dayAt = 400;
		world.feature = World.ring(200);
		TaskTerminalEvent night = world.run(new ExploreForStepArgs("forest", 320), 5_000);
		assertEquals(TaskExecutionState.COMPLETED, night.terminalState(), night.message());
		assertTrue(night.message().startsWith("sheltered_for_night"), night.message());
		assertTrue(world.ticks < 400, "handed back while it was still night");
		assertEquals(1, world.sheltersDug);
		assertTrue(world.milestones().contains("dusk"), world.progress.toString());
		// Called again that night: it waits in the same pit rather than handing back again, and walks on at dawn.
		TaskTerminalEvent morning = world.run(new ExploreForStepArgs("forest", 320), 5_000, "explore-2");
		assertEquals(TaskExecutionState.COMPLETED, morning.terminalState(), morning.message());
		assertTrue(morning.message().startsWith("feature_found"), morning.message());
		assertEquals(1, world.sheltersDug, "no second pit");
		assertTrue(world.optionsSeen.contains(ExploreForTaskExecutor.EXIT_OPTIONS), "climbed out, digging if it had to");
		assertTrue(world.milestones().contains("morning"), world.progress.toString());
	}

	@Test void aMorningCallFromAStripMineNearThePitClimbsOutThroughIt() {
		World world = new World();
		world.roofBlocks = true;
		world.nightAt = 40;
		world.dayAt = 400;
		world.feature = World.ring(200);
		TaskTerminalEvent night = world.run(new ExploreForStepArgs("forest", 320), 5_000);
		assertTrue(night.message().startsWith("sheltered_for_night"), night.message());
		// The planner mined a tunnel from the pit floor through the night.
		world.feet = world.feet.add(12, -4, 0);
		world.ticks = 500;
		world.optionsSeen.clear();
		TaskTerminalEvent morning = world.run(new ExploreForStepArgs("forest", 320), 5_000, "explore-2");
		assertTrue(morning.message().startsWith("feature_found"), morning.message());
		assertTrue(world.optionsSeen.contains(ExploreForTaskExecutor.EXIT_OPTIONS), "went up through the pit first");
	}

	@Test void withNothingToRoofWithItWalksOnThroughTheNight() {
		World world = new World();
		world.nightAt = 40;
		world.feature = World.ring(200);
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 5_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertEquals(0, world.sheltersDug);
		assertTrue(end.message().contains("walkedAtNight=true"), end.message());
	}

	@Test void eatsWhenHungryOnTheWay() {
		World world = new World();
		world.food = 5;
		world.health = 10;
		world.foodItems = 2;
		world.feature = World.ring(200);
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 5_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertEquals(0, world.foodItems, "ate both");
		assertTrue(end.message().contains("eaten=2"), end.message());
	}

	@Test void huntsAPassingAnimalWhenHungryWithNothingToEat() {
		World world = new World();
		world.food = 6;
		world.animals.add(new ExploreForTaskExecutor.Animal(7, "minecraft:cow", new Vec3(20.5, 64, 6.5)));
		world.feature = World.ring(200);
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 5_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(world.hits >= 1, "hit the cow");
		assertTrue(end.message().contains("animalsKilled=1"), end.message());
		assertTrue(end.message().contains("eaten=1"), "ate what it dropped: " + end.message());
		assertEquals(0, world.foodItems);
	}

	@Test void lowHealthWithNothingToEatStopsTheWalk() {
		World world = new World();
		world.health = 5;
		world.food = 10;
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 10);
		assertTrue(end.message().startsWith("low_health"), end.message());
	}

	@Test void aPauseForAReflexResumesWithoutStartingOver() {
		World world = new World();
		world.feature = World.ring(200);
		world.pauseAt = 30;
		TaskTerminalEvent end = world.run(new ExploreForStepArgs("forest", 320), 5_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(end.message().contains("explored: start=0,0"), "kept its start across the pause: " + end.message());
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
		// Green land to the south (heading 4) outweighs keeping east.
		var lured = ExploreForTaskExecutor.chooseLeg(feet, new Vec3(-48, 64, 0), 320, walked, new int[16], 0, dry,
			heading -> heading == 4 ? 0.6 : 0.0);
		assertEquals(4, lured.leg().heading(), lured.toString());
		// Inside maxDistance everything is walked: nothing new is left.
		var done = ExploreForTaskExecutor.chooseLeg(feet, feet, 20, List.of(feet), new int[16], null, dry);
		assertNull(done.leg());
		assertTrue(done.exhausted());
	}

	@Test void surfaceMemoryLuresTowardTreesAndForgetsOtherWorlds() {
		var memory = new ExplorationMemory();
		memory.enter("a|overworld");
		Vec3 feet = new Vec3(0, 64, 0);
		for (int cx = -12; cx <= 12; cx++) for (int cz = -12; cz <= 12; cz++)
			memory.record(cx, cz, cx > 4 ? new ExplorationMemory.Surface(64, 10, 40, 0, 0) : new ExplorationMemory.Surface(64, 0, 0, 64, 0));
		assertTrue(memory.lure("forest", feet, 0.0) > 0.2, "east is green: " + memory.lure("forest", feet, 0.0));
		assertEquals(0.0, memory.lure("forest", feet, Math.PI), 1e-9, "west is sand");
		assertEquals(0.0, memory.lure("lava_pool", feet, 0.0), 1e-9, "the surface says nothing about lava");
		assertTrue(memory.shows("forest", feet, 160));
		assertFalse(memory.shows("water_body", feet, 160));
		memory.visit(feet);
		memory.enter("a|the_nether");
		assertEquals(0, memory.chunks());
		assertTrue(memory.visited().isEmpty());
	}

	@Test void shelterPlanNeedsSolidWallsAndAFloorAndRoofsAgainstASide() {
		Set<BlockPos> air = new HashSet<>();
		BlockPos top = new BlockPos(0, 64, 0);
		for (int y = 64; y < 70; y++) for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) air.add(new BlockPos(x, y, z));
		Map<BlockPos, Integer> breakTicks = new HashMap<>();
		Set<BlockPos> water = new HashSet<>();
		ExploreShelter.Cells cells = new ExploreShelter.Cells() {
			public boolean solid(BlockPos pos) { return !air.contains(pos) && !water.contains(pos); }
			public int breakTicks(BlockPos pos) { return breakTicks.getOrDefault(pos, 15); }
			public boolean fluid(BlockPos pos) { return water.contains(pos); }
			public boolean falling(BlockPos pos) { return false; }
			public boolean open(BlockPos pos) { return air.contains(pos); }
		};
		var plan = ExploreShelter.plan(cells, top);
		assertNotNull(plan);
		assertEquals(List.of(top.below(), top.below(2), top.below(3)), plan.dig());
		assertEquals(top.below(), plan.roof());
		assertEquals(1, plan.roofSupport().distManhattan(plan.roof()));
		assertEquals(ExploreShelter.Step.DIG, ExploreShelter.step(plan, 64, true, false));
		assertEquals(ExploreShelter.Step.ROOF, ExploreShelter.step(plan, 61, true, false));
		assertEquals(ExploreShelter.Step.CLOSED, ExploreShelter.step(plan, 61, true, true));
		// Unbreakable or slow: no shelter in this column, but the next one over will do.
		breakTicks.put(top.below(2), 400);
		assertNull(ExploreShelter.plan(cells, top));
		assertNotNull(ExploreShelter.near(cells, top));
		breakTicks.clear();
		// Water beside the bottom: it would flood.
		water.add(top.below(3).east());
		assertNull(ExploreShelter.plan(cells, top));
	}

	@Test void headingsAreNamedOnTheCompass() {
		assertEquals("east", ExploreForTaskExecutor.compass(0));
		assertEquals("south", ExploreForTaskExecutor.compass(4));
		assertEquals("northwest", ExploreForTaskExecutor.compass(10));
		assertEquals("north", ExploreForTaskExecutor.compass(12));
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
		assertEquals(new ExploreForStepArgs("forest", ExploreForStepArgs.DEFAULT_MAX_DISTANCE, ExploreForStepArgs.DEFAULT_MIN_TREES),
			ExploreForStepArgs.parse(args));
		JsonObject oneTree = args.deepCopy();
		oneTree.addProperty("minTrees", 1);
		assertEquals(1, ExploreForStepArgs.parse(oneTree).minTrees());
		JsonObject unknownKind = new JsonObject();
		unknownKind.addProperty("featureKind", "village");
		assertThrows(IllegalArgumentException.class, () -> ExploreForStepArgs.parse(unknownKind));
		JsonObject tooFar = args.deepCopy();
		tooFar.addProperty("maxDistance", 5000);
		assertThrows(IllegalArgumentException.class, () -> ExploreForStepArgs.parse(tooFar));
		JsonObject noTrees = args.deepCopy();
		noTrees.addProperty("minTrees", 0);
		assertThrows(IllegalArgumentException.class, () -> ExploreForStepArgs.parse(noTrees));
		JsonObject wait = args.deepCopy();
		wait.addProperty("waitAtNight", true);
		assertTrue(ExploreForStepArgs.parse(wait).waitAtNight());
		JsonObject badWait = args.deepCopy();
		badWait.addProperty("waitAtNight", "yes");
		assertThrows(IllegalArgumentException.class, () -> ExploreForStepArgs.parse(badWait));
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
		int food = 20, foodItems;
		boolean roofBlocks;
		int nightAt = Integer.MAX_VALUE, dayAt = Integer.MAX_VALUE, pauseAt = -1;
		/** Where the feature is once the body stands at the given feet, or null while it is not there. */
		java.util.function.Function<Vec3, int[]> feature = feet -> null;
		BiPredicate<Integer, Integer> water = (x, z) -> false;
		Predicate<GoalPosition> blocked = goal -> false;
		java.util.function.BiFunction<Integer, Integer, ExplorationMemory.Surface> surface;
		final List<ExploreForTaskExecutor.Animal> animals = new ArrayList<>();
		final List<GoalPosition> legGoals = new ArrayList<>();
		final Set<String> searchedKinds = new HashSet<>();
		final Set<NavigationOptions> optionsSeen = new HashSet<>();
		final List<TaskProgress> progress = new ArrayList<>();
		NavigationOptions lastOptions;
		int wetTicks, sheltersDug, ticksInShelter, hits, eatTicks;
		double furthest, walked;
		GoalPosition goal;
		private int goalRadius;
		private ExploreShelter.Plan dug;
		private boolean inShelter;
		private final Queue<String> events = new ArrayDeque<>();
		private final ExploreForTaskExecutor executor = new ExploreForTaskExecutor(this, this);
		long ticks;

		List<String> milestones() { return progress.stream().map(TaskProgress::milestone).toList(); }

		/** Trees everywhere beyond {@code radius} blocks of the start: the nearest is straight out from the feet. */
		static java.util.function.Function<Vec3, int[]> ring(double radius) {
			return feet -> {
				double out = Math.hypot(feet.x - 0.5, feet.z - 0.5);
				if (out < 1) return null;
				return new int[]{(int) Math.floor(0.5 + (feet.x - 0.5) / out * radius), 64, (int) Math.floor(0.5 + (feet.z - 0.5) / out * radius)};
			};
		}

		TaskTerminalEvent run(ExploreForStepArgs args, int maxTicks) { return run(args, maxTicks, "explore"); }

		TaskTerminalEvent run(ExploreForStepArgs args, int maxTicks, String taskId) {
			WorldTaskRequest request = WorldTaskRequest.exploreFor(taskId, "job", args);
			for (int i = 0; i < maxTicks; i++) {
				step();
				var session = new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, ++ticks);
				if (pauseAt >= 0 && ticks >= pauseAt && ticks < pauseAt + 20) {
					// A reflex takes over for a second and walks the body somewhere else.
					if (ticks == pauseAt) {
						executor.onWorldLeave();
						feet = feet.add(5, 0, 5);
					}
					continue;
				}
				var end = executor.tick(session, Optional.of(request));
				progress.addAll(executor.drainProgress());
				if (end.isPresent()) return end.get();
			}
			fail("no terminal after " + maxTicks + " ticks: " + executor.snapshot().lastPathEvent());
			return null;
		}

		private void step() {
			if (inShelter) ticksInShelter++;
			if (goal == null) return;
			Vec3 center = new Vec3(goal.x() + 0.5, 64, goal.z() + 0.5);
			Vec3 delta = center.subtract(feet);
			Vec3 before = feet;
			if (delta.horizontalDistance() <= Math.max(goalRadius, 4)) {
				feet = center;
				goal = null;
				events.add("AT_GOAL");
			} else feet = feet.add(delta.normalize().scale(4));
			if (inShelter) {
				// Climbing out.
				inShelter = false;
				feet = new Vec3(feet.x, 64, feet.z);
			}
			walked += Math.hypot(feet.x - before.x, feet.z - before.z);
			if (water.test((int) Math.floor(feet.x), (int) Math.floor(feet.z))) wetTicks++;
			furthest = Math.max(furthest, Math.hypot(feet.x - 0.5, feet.z - 0.5));
		}

		private boolean wet() { return water.test((int) Math.floor(feet.x), (int) Math.floor(feet.z)); }

		// Environment
		public ExploreForTaskExecutor.Body observe() {
			return new ExploreForTaskExecutor.Body(feet, health, 20.0F, food, ticks >= nightAt && ticks < dayAt, true, wet());
		}
		public String worldKey() { return "test|overworld"; }
		public Optional<ExploreForTaskExecutor.Feature> findFeature(String kind, Vec3 at, int radius, int minTrees) {
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
		public void sampleSurface(Vec3 at, int radius, ExplorationMemory memory) {
			if (surface == null) return;
			int cx0 = (int) Math.floor(at.x) >> 4, cz0 = (int) Math.floor(at.z) >> 4;
			for (int cx = cx0 - 12; cx <= cx0 + 12; cx++) for (int cz = cz0 - 12; cz <= cz0 + 12; cz++)
				if (!memory.sampled(cx, cz)) memory.record(cx, cz, surface.apply(cx, cz));
		}
		public boolean hasFood(boolean starving) { return foodItems > 0; }
		public ExploreForTaskExecutor.ConsumeResult eat(boolean starving) {
			if (foodItems <= 0) return ExploreForTaskExecutor.ConsumeResult.NOTHING;
			assertNull(goal, "stands still to eat");
			if (++eatTicks < 32) return ExploreForTaskExecutor.ConsumeResult.CONSUMING;
			eatTicks = 0;
			foodItems--;
			food = Math.min(20, food + 8);
			return ExploreForTaskExecutor.ConsumeResult.DONE;
		}
		public void stopEating() { eatTicks = 0; }
		public List<ExploreForTaskExecutor.Animal> animals(Vec3 at, double radius) {
			return animals.stream().filter(a -> a.position().distanceTo(at) <= radius).toList();
		}
		public void melee(int id) {
			hits++;
			if (hits >= 3) {
				animals.removeIf(a -> a.id() == id);
				// The drop is picked up where it died.
				foodItems++;
			}
		}
		public boolean hasRoofBlock() { return roofBlocks; }
		public ExploreShelter.Plan shelterNear(Vec3 at) {
			BlockPos top = BlockPos.containing(at);
			return new ExploreShelter.Plan(top, List.of(top.below(), top.below(2), top.below(3)), top.below(), top.below().east());
		}
		public ExploreForTaskExecutor.ShelterTick digShelter(ExploreShelter.Plan plan) {
			assertNull(goal, "digs standing still");
			if (dug == plan) return ExploreForTaskExecutor.ShelterTick.CLOSED;
			dug = plan;
			sheltersDug++;
			inShelter = true;
			feet = new Vec3(plan.top().getX() + 0.5, plan.bottom().getY(), plan.top().getZ() + 0.5);
			return ExploreForTaskExecutor.ShelterTick.WORKING;
		}
		public void stopDigging() {}
		public void stopMoving() {}
		public void release() {}

		// Navigation
		public boolean isLoaded() { return true; }
		public void startFollow(String s) { fail("no following"); }
		public void startNavigate(GoalPosition p, NavigationOptions o) { startNavigateNear(p, 0, o); }
		public void startNavigateNear(GoalPosition p, int r, NavigationOptions o) {
			legGoals.add(p);
			lastOptions = o;
			optionsSeen.add(o);
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
