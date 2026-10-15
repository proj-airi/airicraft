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
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class LocateStrongholdTaskExecutorTest {
	private static final Pattern ESTIMATE = Pattern.compile("estimate=(-?\\d+),(-?\\d+)");

	@Test void triangulatesAFarStrongholdAndWalksToIt() {
		World world = new World(1200, -800);
		world.eyes = 10;
		TaskTerminalEvent end = world.run(new LocateStrongholdStepArgs(4), 30_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(end.message().startsWith("stronghold_located"), end.message());
		assertWithin(end.message(), 1200, -800, LocateStrongholdTaskExecutor.ARRIVE_BLOCKS);
		assertTrue(Math.hypot(world.feet.x - 1200, world.feet.z + 800) <= LocateStrongholdTaskExecutor.ARRIVE_BLOCKS,
			"walks to the estimate: " + world.feet);
		assertTrue(world.thrown >= 3 && world.thrown <= 4, "two to cross, one or two more on the way and near it: " + world.thrown);
		assertTrue(end.message().contains("confirmed=true"), end.message());
		assertEquals(10, world.eyes, "every eye survived and was picked back up");
		assertTrue(end.message().contains("bearings=["), end.message());
	}

	@Test void anEyeThatGoesDownMeansTheStrongholdIsHere() {
		World world = new World(6, 4);
		world.eyes = 2;
		TaskTerminalEvent end = world.run(new LocateStrongholdStepArgs(4), 2_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(end.message().contains("eye_went_down=true"), end.message());
		assertWithin(end.message(), 6, 4, 12);
		assertEquals(1, world.thrown);
	}

	@Test void needsEyesAndTheOverworld() {
		World none = new World(1000, 0);
		none.eyes = 0;
		TaskTerminalEvent noEyes = none.run(new LocateStrongholdStepArgs(4), 10);
		assertTrue(noEyes.message().startsWith("no_eyes_of_ender"), noEyes.message());
		assertEquals(TaskFailureCode.MISSING_ITEM, noEyes.failureCode());

		World nether = new World(1000, 0);
		nether.overworld = false;
		TaskTerminalEvent wrong = nether.run(new LocateStrongholdStepArgs(4), 10);
		assertTrue(wrong.message().startsWith("wrong_dimension"), wrong.message());
		assertEquals(0, nether.thrown);
	}

	@Test void oneThrowCannotCrossAndReportsTheBearing() {
		World world = new World(0.5, -2000);
		world.eyes = 5;
		TaskTerminalEvent end = world.run(new LocateStrongholdStepArgs(1), 2_000);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("bearing_inconclusive"), end.message());
		assertTrue(end.message().contains("best_estimate=none"), end.message());
		assertTrue(end.message().contains("0,0@0.0deg"), "the bearing points north: " + end.message());
	}

	@Test void runningOutOfEyesBeforeTwoBearingsFails() {
		World world = new World(1200, -800);
		world.eyes = 1;
		world.shatter = true;
		TaskTerminalEvent end = world.run(new LocateStrongholdStepArgs(4), 2_000);
		assertEquals(TaskExecutionState.FAILED, end.terminalState(), end.message());
		assertTrue(end.message().startsWith("out_of_eyes"), end.message());
		assertTrue(end.message().contains("best_estimate=none"), end.message());
		assertTrue(end.message().contains("eyesLost=1"), end.message());
	}

	@Test void twoBearingsWithoutAnEyeLeftStillWalkToTheEstimateUnconfirmed() {
		World world = new World(1200, -800);
		world.eyes = 2;
		world.shatter = true;
		TaskTerminalEvent end = world.run(new LocateStrongholdStepArgs(4), 20_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertTrue(end.message().contains("confirmed=false"), end.message());
		assertWithin(end.message(), 1200, -800, LocateStrongholdTaskExecutor.ARRIVE_BLOCKS);
		assertTrue(end.message().contains("eyesLost=2"), end.message());
		assertEquals(0, world.eyes);
	}

	@Test void noEyeEverFliesInAWorldWithoutStrongholds() {
		World world = new World(1000, 0);
		world.eyes = 3;
		world.noStronghold = true;
		TaskTerminalEvent end = world.run(new LocateStrongholdStepArgs(4), 1_000);
		assertTrue(end.message().startsWith("no_eye_flight"), end.message());
		assertEquals(LocateStrongholdTaskExecutor.MAX_FAILED_USES, world.uses);
		assertEquals(3, world.eyes, "no eye was spent");
	}

	@Test void aBlockedSideWalksTheOtherWay() {
		World world = new World(1200, -800);
		world.eyes = 10;
		// The first bearing points east-northeast; the walk turns 45 degrees right of it, toward the south, which is impassable.
		world.blocked = goal -> goal.z() > 20;
		TaskTerminalEvent end = world.run(new LocateStrongholdStepArgs(4), 40_000);
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState(), end.message());
		assertWithin(end.message(), 1200, -800, LocateStrongholdTaskExecutor.ARRIVE_BLOCKS);
		assertTrue(world.navigatedGoals.stream().anyMatch(goal -> goal.z() > 20), "tried the south side first");
	}

	@Test void aRouteBlockedEverywhereFailsWithTheBearing() {
		World world = new World(1200, -800);
		world.eyes = 10;
		world.blocked = goal -> true;
		TaskTerminalEvent end = world.run(new LocateStrongholdStepArgs(4), 20_000);
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("route_blocked"), end.message());
		assertTrue(end.message().contains("bearings=["), end.message());
		assertEquals(1, world.thrown);
	}

	@Test void maxEyesDefaultsToFourAndIsBounded() {
		assertEquals(4, LocateStrongholdStepArgs.parse(new JsonObject()).maxEyes());
		JsonObject zero = new JsonObject();
		zero.addProperty("maxEyes", 0);
		assertThrows(IllegalArgumentException.class, () -> LocateStrongholdStepArgs.parse(zero));
		JsonObject unknown = new JsonObject();
		unknown.addProperty("eyes", 3);
		assertThrows(IllegalArgumentException.class, () -> LocateStrongholdStepArgs.parse(unknown));
	}

	private static void assertWithin(String message, double x, double z, double blocks) {
		Matcher matcher = ESTIMATE.matcher(message);
		assertTrue(matcher.find(), message);
		double ex = Integer.parseInt(matcher.group(1)), ez = Integer.parseInt(matcher.group(2));
		assertTrue(Math.hypot(ex - x, ez - z) <= blocks, "estimate " + ex + "," + ez + " vs " + x + "," + z);
	}

	/**
	 * A flat overworld at y=64 with a stronghold at y=0. Eyes fly as in vanilla: toward a point 12 blocks along the
	 * bearing and 8 up, or straight at the stronghold within 12 blocks; they live 80 ticks and drop unless they shatter.
	 * Navigation walks 4 blocks a tick and refuses blocked goals.
	 */
	private static final class World implements LocateStrongholdTaskExecutor.Environment, NavigationFacade {
		final double strongholdX, strongholdZ;
		Vec3 feet = new Vec3(0.5, 64, 0.5);
		int eyes;
		boolean overworld = true, shatter, noStronghold;
		int thrown, uses;
		Predicate<GoalPosition> blocked = goal -> false;
		final List<GoalPosition> navigatedGoals = new ArrayList<>();
		private final Map<Integer, Flight> flights = new HashMap<>();
		private final List<Vec3> drops = new ArrayList<>();
		private int nextId = 100;
		private int pendingSpawn = -1;
		private GoalPosition goal;
		private final Queue<String> events = new ArrayDeque<>();
		private final LocateStrongholdTaskExecutor executor = new LocateStrongholdTaskExecutor(this, this);
		private long ticks;

		World(double strongholdX, double strongholdZ) {
			this.strongholdX = strongholdX;
			this.strongholdZ = strongholdZ;
		}

		TaskTerminalEvent run(LocateStrongholdStepArgs args, int maxTicks) {
			WorldTaskRequest request = WorldTaskRequest.locateStronghold("stronghold", "job", args);
			for (int i = 0; i < maxTicks; i++) {
				step();
				var session = new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, ++ticks);
				var end = executor.tick(session, Optional.of(request));
				if (end.isPresent()) return end.get();
			}
			fail("no terminal after " + maxTicks + " ticks: " + executor.snapshot().lastPathEvent());
			return null;
		}

		private record Flight(Vec3 target, Vec3[] position, int[] life) {}

		private void step() {
			if (goal != null) {
				Vec3 center = new Vec3(goal.x() + 0.5, 64, goal.z() + 0.5);
				Vec3 delta = center.subtract(feet);
				if (delta.horizontalDistance() <= 4) {
					feet = center;
					goal = null;
					events.add("AT_GOAL");
				} else feet = feet.add(delta.normalize().scale(4));
			}
			if (pendingSpawn >= 0 && --pendingSpawn < 0) spawnEye();
			for (var it = flights.entrySet().iterator(); it.hasNext(); ) {
				Flight flight = it.next().getValue();
				Vec3 to = flight.target().subtract(flight.position()[0]);
				flight.position()[0] = to.length() <= 0.5 ? flight.target() : flight.position()[0].add(to.normalize().scale(0.5));
				if (++flight.life()[0] > 80) {
					if (!shatter) drops.add(new Vec3(flight.position()[0].x, 64, flight.position()[0].z));
					it.remove();
				}
			}
			for (var it = drops.iterator(); it.hasNext(); ) {
				Vec3 drop = it.next();
				if (Math.hypot(drop.x - feet.x, drop.z - feet.z) <= 1.5) {
					eyes++;
					it.remove();
				}
			}
		}

		private void spawnEye() {
			Vec3 start = feet.add(0, 0.9, 0);
			double dx = strongholdX - start.x, dz = strongholdZ - start.z, distance = Math.hypot(dx, dz);
			Vec3 target = distance > 12 ? start.add(dx / distance * 12, 8, dz / distance * 12) : new Vec3(strongholdX, 0, strongholdZ);
			flights.put(nextId++, new Flight(target, new Vec3[] {start}, new int[] {0}));
		}

		// Environment
		public LocateStrongholdTaskExecutor.Body observe() { return new LocateStrongholdTaskExecutor.Body(feet, 20, eyes, overworld); }
		public Set<Integer> eyesNear(Vec3 point, double radius) {
			Set<Integer> ids = new HashSet<>();
			flights.forEach((id, flight) -> { if (flight.position()[0].distanceTo(point) <= radius + 1) ids.add(id); });
			return ids;
		}
		public Optional<Vec3> eye(int entityId) {
			Flight flight = flights.get(entityId);
			return flight == null ? Optional.empty() : Optional.of(flight.position()[0]);
		}
		public LocateStrongholdTaskExecutor.ThrowResult throwEye() {
			if (eyes <= 0) return LocateStrongholdTaskExecutor.ThrowResult.NO_EYE;
			assertNull(goal, "stands still to throw");
			uses++;
			if (!noStronghold) {
				eyes--;
				thrown++;
				pendingSpawn = 2;
			}
			return LocateStrongholdTaskExecutor.ThrowResult.USED;
		}
		public Optional<Vec3> droppedEye(Vec3 near, double radius) {
			return drops.stream().filter(drop -> Math.hypot(drop.x - near.x, drop.z - near.z) <= radius).findFirst();
		}
		public void release() {}

		// Navigation
		public boolean isLoaded() { return true; }
		public void startFollow(String s) { fail("no following"); }
		public void startNavigate(GoalPosition p, NavigationOptions o) {
			navigatedGoals.add(p);
			if (blocked.test(p)) {
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
