package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LureEntitiesTaskExecutorTest {
	@Test void playerArrivalIsInsufficientAndAnotherLeadPositionDrawsAnimalThroughEntrance() {
		Fixture f = new Fixture();
		f.tick(2);
		f.env.player = point(10);
		f.env.animals = List.of(animal("aaaaaaaa", 8, false, true));
		f.tick(65);
		assertTrue(f.events.isEmpty());
		assertTrue(f.nav.goals.contains(goal(12)), "Try standing deeper instead of counting the animal outside the entrance");
		f.env.animals = List.of(animal("aaaaaaaa", 11, true, true));
		f.tick(4);
		assertEquals(1, f.events.size());
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
		assertFalse(f.nav.active);
		assertFalse(f.env.settingsOwned);
	}

	@Test void stopsForLaggingFollowerThenContinuesAfterCatchup() {
		Fixture f = new Fixture();
		f.tick(2);
		assertTrue(f.nav.active);
		f.env.player = point(8);
		f.tick(1);
		assertFalse(f.nav.active);
		int starts = f.nav.goals.size();
		f.tick(20);
		assertEquals(starts, f.nav.goals.size());
		f.env.animals = List.of(animal("aaaaaaaa", 6, false, true));
		f.tick(2);
		assertTrue(f.nav.active);
		assertEquals(starts + 1, f.nav.goals.size());
	}

	@Test void reacquiresMovingTargetWithoutFeeding() {
		Fixture f = new Fixture();
		f.env.animals = List.of(animal("aaaaaaaa", 8, false, true));
		f.tick(12);
		assertEquals(2, f.nav.radius);
		f.env.animals = List.of(animal("aaaaaaaa", 11, false, true));
		f.tick(1);
		assertEquals(goal(11), f.nav.goals.getLast());
		assertTrue(f.env.held);
	}

	@Test void occludedDistantAnimalRequiresExactApproach() {
		Fixture f = new Fixture();
		f.env.animals = List.of(animal("aaaaaaaa", 8, false, false));
		f.tick(1);
		assertEquals(goal(8), f.nav.goals.getFirst());
		assertEquals(0, f.nav.radius);
		assertTrue(f.events.isEmpty());
	}

	@Test void nearbyOccludedFollowerDoesNotRequireUnnecessaryApproach() {
		Fixture f = new Fixture();
		f.env.animals = List.of(animal("aaaaaaaa", 2, false, false));
		f.tick(2);
		assertEquals(List.of(goal(10)), f.nav.goals);
		assertTrue(f.events.isEmpty());
	}

	@Test void lossOfVisibilityRefreshesApproachEvenWithoutAnimalMovement() {
		Fixture f = new Fixture();
		f.env.animals = List.of(animal("aaaaaaaa", 8, false, true));
		f.tick(12);
		assertEquals(2, f.nav.radius);
		f.env.animals = List.of(animal("aaaaaaaa", 8, false, false));
		f.tick(1);
		assertEquals(0, f.nav.radius);
		assertEquals(2, f.nav.goals.size());
	}

	@Test void everySelectedAnimalMustEnterTheArea() {
		Fixture f = new Fixture("aaaaaaaa", "bbbbbbbb");
		f.env.animals = List.of(animal("aaaaaaaa", 1, true, true), animal("bbbbbbbb", 2, false, true));
		f.tick(2);
		assertTrue(f.events.isEmpty());
		f.env.animals = List.of(animal("aaaaaaaa", 1, true, true), animal("bbbbbbbb", 2, true, true));
		f.tick(1);
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
	}

	@Test void interruptionRestoresSettingsAndResumeReequipsAndReacquiresSameIdentities() {
		Fixture f = new Fixture();
		f.tick(2);
		f.executor.tick(f.session(), Optional.empty());
		assertFalse(f.nav.active);
		assertFalse(f.env.settingsOwned);
		f.env.held = false; // Combat took the hand and moved away from the flock.
		f.env.animals = List.of(animal("aaaaaaaa", 8, false, true));
		f.tick(1);
		assertTrue(f.env.held);
		assertTrue(f.env.settingsOwned);
		assertEquals(1, f.env.initializations, "Resume must retain the resolved identities");
		assertEquals(goal(8), f.nav.goals.getLast());
	}

	@Test void gatedTicksDoNotInitializeActuationOrSpendBudget() {
		Fixture f = new Fixture();
		for (int i = 0; i < 7000; i++) f.executor.tick(SessionSnapshot.initial(), Optional.of(f.request));
		assertEquals(0, f.env.initializations);
		assertFalse(f.env.settingsOwned);
		f.tick(2);
		assertTrue(f.nav.active);
		assertTrue(f.events.isEmpty());
	}

	@Test void approachingOneAnimalMayIncreaseDistanceToAnotherInitiallyNearbyAnimal() {
		Fixture f = new Fixture("aaaaaaaa", "bbbbbbbb");
		f.env.animals = List.of(animal("aaaaaaaa", -25, false, true), animal("bbbbbbbb", 25, false, true));
		f.tick(2);
		f.env.player = point(-24);
		f.tick(20);
		assertTrue(f.events.isEmpty(), "A loaded follower remains valid after the player approaches the other end of the herd");
		assertTrue(f.nav.goals.contains(goal(25)));
		assertTrue(f.env.settingsOwned);
	}

	@Test void missingAnimalFailsInsteadOfSilentlyCompletingSubset() {
		Fixture f = new Fixture();
		f.tick(2);
		f.env.animals = List.of();
		f.tick(1);
		assertEquals("follower_missing_or_dead", f.events.getFirst().message());
		assertFalse(f.nav.active);
		assertFalse(f.env.settingsOwned);
	}

	@Test void failedStandingDestinationTriesAnotherCandidate() {
		Fixture f = new Fixture();
		f.tick(2);
		f.nav.events.add("CALC_FAILED");
		f.tick(2);
		assertEquals(goal(12), f.nav.goals.getLast());
		assertTrue(f.events.isEmpty());
	}

	@Test void impossibleDestinationAndUnresponsiveAnimalTerminateWithReleasedOwnership() {
		Fixture small = new Fixture();
		small.env.leads = List.of();
		small.tick(2);
		assertEquals("destination_too_small_or_unreachable", small.events.getFirst().message());
		Fixture stuck = new Fixture();
		stuck.env.animals = List.of(animal("aaaaaaaa", 8, false, true));
		stuck.tick(1210);
		assertEquals("follower_not_approaching", stuck.events.getFirst().message());
		assertFalse(stuck.nav.active);
		assertFalse(stuck.env.settingsOwned);
	}

	@Test void activeButMotionlessNavigationTimesOutAndReleasesOwnership() {
		Fixture f = new Fixture();
		f.tick(1210);
		assertEquals("lure_navigation_timeout", f.events.getFirst().message());
		assertFalse(f.nav.active);
		assertFalse(f.env.settingsOwned);
	}

	@Test void validatesBoundedAreaAndObservedSelectors() {
		String json = "{\"uuids\":[\"aaaaaaaa\"],\"itemId\":\"minecraft:wheat_seeds\",\"x1\":0,\"y1\":63,\"z1\":0,\"x2\":2,\"y2\":64,\"z2\":3}";
		assertEquals(List.of("aaaaaaaa"), LureEntitiesStepArgs.parse(com.google.gson.JsonParser.parseString(json).getAsJsonObject()).uuids());
		for (String invalid : List.of(json.replace("\"x2\":2", "\"x2\":16"), json.replace("\"y2\":64", "\"y2\":62"),
			json.replace("\"x1\":0", "\"x1\":0.5"), json.replace("\"aaaaaaaa\"", "\"nope\""),
			json.replace("[\"aaaaaaaa\"]", "[\"aaaaaaaa\",\"AAAAAAAA\"]")))
			assertThrows(IllegalArgumentException.class, () -> LureEntitiesStepArgs.parse(com.google.gson.JsonParser.parseString(invalid).getAsJsonObject()));
	}

	private static Vec3d point(int x) { return new Vec3d(x,63,0); }
	private static GoalPosition goal(int x) { return new GoalPosition(x,63,0,true); }
	private static LureEntitiesTaskExecutor.Follower animal(String id, int x, boolean inside, boolean visible) {
		return new LureEntitiesTaskExecutor.Follower(id,point(x),inside,visible);
	}
	private static final class Fixture {
		final Environment env = new Environment();
		final Navigation nav = new Navigation(env);
		final LureEntitiesTaskExecutor executor = new LureEntitiesTaskExecutor(nav,env);
		final WorldTaskRequest request;
		final List<TaskTerminalEvent> events = new ArrayList<>();
		long ticks;
		Fixture(String... ids) {
			request = WorldTaskRequest.lureEntities("lure", "job", new LureEntitiesStepArgs(ids.length == 0 ? List.of("aaaaaaaa") : List.of(ids),
				"minecraft:wheat_seeds",9,63,0,12,64,3));
		}
		SessionSnapshot session() { return new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST,true,true,"minecraft:overworld",true,25565,++ticks); }
		void tick(int n) { for (int i=0;i<n;i++) executor.tick(session(),Optional.of(request)).ifPresent(events::add); }
	}
	private static final class Environment implements LureEntitiesTaskExecutor.Environment {
		Vec3d player = point(0);
		List<LureEntitiesTaskExecutor.Follower> animals = List.of(animal("aaaaaaaa",2,false,true));
		List<GoalPosition> leads = List.of(goal(10),goal(12));
		boolean settingsOwned, held;
		int initializations;
		public String initialize(LureEntitiesStepArgs args) { initializations++; return null; }
		public Vec3d position() { return player; }
		public List<LureEntitiesTaskExecutor.Follower> followers() { return animals; }
		public String holdItem() { held = true; return null; }
		public List<GoalPosition> leadPositions(List<LureEntitiesTaskExecutor.Follower> followers) { return leads; }
		public void beginTravel() { settingsOwned = true; }
		public void release() { settingsOwned = false; }
	}
	private static final class Navigation implements BaritoneFacade {
		final Environment env;
		Navigation(Environment env) { this.env=env; }
		boolean active;
		int radius;
		List<GoalPosition> goals = new ArrayList<>();
		Queue<String> events = new ArrayDeque<>();
		public boolean isLoaded() { return true; }
		public void applySettings() {}
		public double walkOnWaterPenalty() { return 1; }
		public void setWalkOnWaterPenalty(double v) {}
		public void startFollow(String s) { fail("Only bounded navigation is allowed"); }
		public void startNavigate(GoalPosition p) { goals.add(p); radius=0; active=true; }
		public void startNavigateNear(GoalPosition p,int r) { goals.add(p); radius=r; active=true; }
		public boolean processActive() { return active; }
		public boolean cancel() { active=false; return true; }
		public Optional<String> activeProcessName() { return Optional.empty(); }
		public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		public Optional<String> pollPathEvent() { return Optional.ofNullable(events.poll()); }
		public boolean navigationGoalReached(GoalPosition p) { return (int)Math.floor(env.player.x)==p.x(); }
	}
}
