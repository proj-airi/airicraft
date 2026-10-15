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

class CollectObsidianTaskExecutorTest {
	private static final GoalPosition OBSIDIAN = new GoalPosition(3, 27, 0, true);
	private static final GoalPosition MINE_STANCE = new GoalPosition(1, 28, 0, true);
	private static final GoalPosition LAVA = new GoalPosition(5, 27, 0, true);
	private static final GoalPosition CAST_STANCE = new GoalPosition(2, 28, 2, true);

	@Test void minesSafeObsidianFromItsStanceAndWalksOverTheDrop() {
		Fixture f = new Fixture(1);
		f.env.mine = Optional.of(new CollectObsidianTaskExecutor.Mine(OBSIDIAN, MINE_STANCE, false));
		f.tick(2);
		assertEquals(MINE_STANCE, f.nav.goals.getLast());
		f.env.feet = new Vec3(1.5, 28, 0.5);
		f.tick(3);
		assertTrue(f.env.breaking.contains(OBSIDIAN));
		f.env.mine = Optional.empty();
		f.env.broken = true;
		f.tick(2);
		assertEquals(OBSIDIAN, f.nav.goals.getLast(), "walks over the drop");
		f.env.obsidian = 1;
		f.tick(1);
		assertEquals(1, f.events.size());
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
		assertTrue(f.events.getFirst().message().contains("mined=1"), f.events.getFirst().message());
	}

	@Test void pauseForReflexMidBreakWalksBackToTheStanceInsteadOfRejectingTheTarget() {
		Fixture f = new Fixture(1);
		f.env.mine = Optional.of(new CollectObsidianTaskExecutor.Mine(OBSIDIAN, MINE_STANCE, false));
		f.tick(2);
		f.env.feet = new Vec3(1.5, 28, 0.5);
		f.tick(3);
		assertTrue(f.env.breaking.contains(OBSIDIAN));
		int routes = f.nav.goals.size();
		f.executor.pauseForReflex();
		assertFalse(f.nav.active);
		f.env.feet = new Vec3(-3.5, 28, 0.5); // the reflex moved the body off its stance
		f.env.breaking.clear();
		f.tick(2);
		assertEquals(routes + 1, f.nav.goals.size(), "walks back to the stance");
		assertEquals(MINE_STANCE, f.nav.goals.getLast());
		assertTrue(f.env.breaking.isEmpty(), "does not swing at the block from out of reach");
		f.env.feet = new Vec3(1.5, 28, 0.5);
		f.tick(2);
		assertTrue(f.env.breaking.contains(OBSIDIAN), "breaks the same block again");
		assertTrue(f.env.rejectedMines.isEmpty(), "the target is not rejected: " + f.env.rejectedMines);
	}

	@Test void castsWhenNothingIsSafeToMineAndScoopsTheWaterBack() {
		Fixture f = new Fixture(10);
		f.env.cast = Optional.of(new CollectObsidianTaskExecutor.Cast(LAVA, new GoalPosition(5, 26, 0, true), "up", CAST_STANCE, 3));
		f.tick(2);
		assertEquals(CAST_STANCE, f.nav.goals.getLast());
		f.env.feet = new Vec3(2.5, 28, 2.5);
		f.tick(1);
		assertFalse(f.nav.active, "stands still to pour");
		f.env.pourResult = CollectObsidianTaskExecutor.PourResult.POURED;
		f.tick(1);
		assertEquals(List.of(), f.env.scoops);
		f.tick(1);
		assertEquals(List.of(LAVA), f.env.scoops, "scoops the poured cell right away");
		f.env.scoopResult = CollectObsidianTaskExecutor.ScoopResult.SCOOPED;
		f.env.cast = Optional.empty();
		f.env.mine = Optional.of(new CollectObsidianTaskExecutor.Mine(OBSIDIAN, MINE_STANCE, false));
		f.tick(2);
		assertTrue(f.snapshotText().contains("casts=1"), f.snapshotText());
		assertTrue(f.snapshotText().contains("phase=MINE_APPROACH"), f.snapshotText());
	}

	@Test void reachingTheCountWhileScoopingStillScoopsBeforeFinishing() {
		Fixture f = new Fixture(1);
		f.env.cast = Optional.of(new CollectObsidianTaskExecutor.Cast(LAVA, new GoalPosition(5, 26, 0, true), "up", CAST_STANCE, 3));
		f.env.feet = new Vec3(2.5, 28, 2.5);
		f.env.pourResult = CollectObsidianTaskExecutor.PourResult.POURED;
		f.tick(3);
		f.env.obsidian = 3;
		f.tick(1);
		assertEquals(List.of(), f.events, "the scoop runs first");
		assertEquals(List.of(LAVA), f.env.scoops);
		f.env.scoopResult = CollectObsidianTaskExecutor.ScoopResult.SCOOPED;
		f.tick(2);
		assertEquals(1, f.events.size());
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
		assertEquals(2, f.env.scoops.size());
	}

	@Test void opensObsidianOverDeeperLavaOnlyWhenNothingElseIsLeft() {
		Fixture f = new Fixture(10);
		f.env.opening = Optional.of(new CollectObsidianTaskExecutor.Mine(OBSIDIAN, MINE_STANCE, true));
		f.env.feet = new Vec3(1.5, 28, 0.5);
		f.tick(3);
		assertTrue(f.env.breaking.contains(OBSIDIAN));
		f.env.opening = Optional.empty();
		f.env.broken = true;
		f.env.cast = Optional.of(new CollectObsidianTaskExecutor.Cast(new GoalPosition(3, 26, 0, true), OBSIDIAN, "north", CAST_STANCE, 1));
		f.tick(2);
		assertTrue(f.snapshotText().contains("opened=1"), f.snapshotText());
		assertTrue(f.snapshotText().contains("phase=CAST_APPROACH"), "casts the lava it opened, without chasing a drop");
	}

	@Test void unreachableStancesAreSkippedAndAnEmptySiteFailsWithAReason() {
		Fixture f = new Fixture(10);
		f.env.mine = Optional.of(new CollectObsidianTaskExecutor.Mine(OBSIDIAN, MINE_STANCE, false));
		f.tick(2);
		f.nav.events.add("CALC_FAILED");
		f.tick(2);
		assertTrue(f.env.rejectedMines.contains(OBSIDIAN), "the next choice skips it");
		assertEquals(1, f.events.size());
		assertTrue(f.events.getFirst().message().startsWith("no_lava_nearby"), f.events.getFirst().message());
		assertEquals(TaskFailureCode.MISSING_FACT, f.events.getFirst().failureCode());
	}

	@Test void needsAWaterBucketToCastAndADiamondPickaxeAtAll() {
		Fixture noWater = new Fixture(10);
		noWater.env.waterBucket = false;
		noWater.env.cast = Optional.of(new CollectObsidianTaskExecutor.Cast(LAVA, new GoalPosition(5, 26, 0, true), "up", CAST_STANCE, 3));
		noWater.tick(1);
		assertTrue(noWater.events.getFirst().message().startsWith("no_water_bucket"), noWater.events.toString());
		Fixture noPickaxe = new Fixture(10);
		noPickaxe.env.pickaxe = false;
		noPickaxe.tick(1);
		assertTrue(noPickaxe.events.getFirst().message().startsWith("needs_diamond_pickaxe"));
	}

	@Test void countDefaultsToAPortalFrame() {
		assertEquals(10, CollectObsidianStepArgs.parse(new JsonObject()).count());
		JsonObject args = new JsonObject();
		args.addProperty("count", 0);
		assertThrows(IllegalArgumentException.class, () -> CollectObsidianStepArgs.parse(args));
	}

	private static final class Fixture {
		final Environment env = new Environment();
		final Navigation nav = new Navigation();
		final CollectObsidianTaskExecutor executor = new CollectObsidianTaskExecutor(nav, env);
		final WorldTaskRequest request;
		final List<TaskTerminalEvent> events = new ArrayList<>();
		long ticks;

		Fixture(int count) {
			request = WorldTaskRequest.collectObsidian("obsidian", "job", new CollectObsidianStepArgs(count));
		}

		SessionSnapshot session() {
			return new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, ++ticks);
		}

		void tick(int n) {
			for (int i = 0; i < n; i++) executor.tick(session(), Optional.of(request)).ifPresent(events::add);
		}

		String snapshotText() { return executor.snapshot().lastPathEvent(); }
	}

	private static final class Environment implements CollectObsidianTaskExecutor.Environment {
		Vec3 feet = new Vec3(0.5, 28, 0.5);
		int obsidian;
		boolean waterBucket = true, pickaxe = true, broken;
		Optional<CollectObsidianTaskExecutor.Mine> mine = Optional.empty(), opening = Optional.empty();
		Optional<CollectObsidianTaskExecutor.Cast> cast = Optional.empty();
		CollectObsidianTaskExecutor.PourResult pourResult = CollectObsidianTaskExecutor.PourResult.WORKING;
		CollectObsidianTaskExecutor.ScoopResult scoopResult = CollectObsidianTaskExecutor.ScoopResult.WORKING;
		Set<GoalPosition> breaking = new HashSet<>(), rejectedMines = Set.of();
		List<GoalPosition> scoops = new ArrayList<>();

		public CollectObsidianTaskExecutor.Site observe() {
			return new CollectObsidianTaskExecutor.Site(feet, 20, obsidian, waterBucket, !waterBucket, pickaxe);
		}
		public Optional<CollectObsidianTaskExecutor.Mine> mineTarget(CollectObsidianTaskExecutor.Site site, Set<GoalPosition> rejected,
			boolean wantOpening) {
			rejectedMines = Set.copyOf(rejected);
			return (wantOpening ? opening : mine).filter(m -> !rejected.contains(m.block()));
		}
		public boolean stillMineable(CollectObsidianTaskExecutor.Mine mine) { return true; }
		public Optional<CollectObsidianTaskExecutor.Cast> castTarget(CollectObsidianTaskExecutor.Site site, Set<GoalPosition> rejected) {
			return cast.filter(c -> !rejected.contains(c.pour()));
		}
		public CollectObsidianTaskExecutor.BreakResult breakBlock(GoalPosition block) {
			breaking.add(block);
			return broken ? CollectObsidianTaskExecutor.BreakResult.GONE : CollectObsidianTaskExecutor.BreakResult.WORKING;
		}
		public CollectObsidianTaskExecutor.PourResult pour(CollectObsidianTaskExecutor.Cast cast) { return pourResult; }
		public CollectObsidianTaskExecutor.ScoopResult scoop(GoalPosition water) {
			scoops.add(water);
			return scoopResult;
		}
		public Optional<GoalPosition> obsidianDrop(GoalPosition near) { return broken ? Optional.of(near) : Optional.empty(); }
		public void stopBreaking() {}
		public void release() {}
	}

	private static final class Navigation implements NavigationFacade {
		boolean active;
		List<GoalPosition> goals = new ArrayList<>();
		public boolean isLoaded() { return true; }
		public void startFollow(String s) { fail("no following"); }
		NavigationOptions options;
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
