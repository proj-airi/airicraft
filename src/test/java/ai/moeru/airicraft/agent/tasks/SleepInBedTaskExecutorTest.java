package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SleepInBedTaskExecutorTest {
	private static final SleepInBedTaskExecutor.Bed BED = new SleepInBedTaskExecutor.Bed(new BlockPos(2, 64, 0), new BlockPos(3, 64, 0));
	private static final long MIDNIGHT = 18_000L;
	private static final long NOON = 6_000L;

	// ---- Day and night ----

	@Test void sleepsOnlyWhenTheSkyIsDarkEnough() {
		assertFalse(SleepInBedTaskExecutor.sleepWindow(NOON, false, false));
		assertFalse(SleepInBedTaskExecutor.sleepWindow(12_541, false, false), "sunset, still too light");
		assertTrue(SleepInBedTaskExecutor.sleepWindow(12_542, false, false));
		assertTrue(SleepInBedTaskExecutor.sleepWindow(MIDNIGHT, false, false));
		assertTrue(SleepInBedTaskExecutor.sleepWindow(23_459, false, false));
		assertFalse(SleepInBedTaskExecutor.sleepWindow(23_460, false, false), "sunrise");
		assertFalse(SleepInBedTaskExecutor.sleepWindow(0, false, false), "the morning after a skipped night");
		assertTrue(SleepInBedTaskExecutor.sleepWindow(24_000L * 7 + MIDNIGHT, false, false), "the day count does not matter");
		assertTrue(SleepInBedTaskExecutor.sleepWindow(12_010, true, false), "rain darkens the sky sooner");
		assertTrue(SleepInBedTaskExecutor.sleepWindow(23_991, true, false));
		assertFalse(SleepInBedTaskExecutor.sleepWindow(12_009, true, false));
		assertTrue(SleepInBedTaskExecutor.sleepWindow(NOON, true, true), "a thunderstorm allows sleep at noon");
	}

	// ---- Monsters ----

	@Test void aMonsterInVanillasBoxAroundTheHeadKeepsTheBodyAwake() {
		BlockPos head = BED.head();
		assertTrue(SleepInBedTaskExecutor.preventsRest(head, zombieAt(head, 7.0, 0, 0)));
		assertTrue(SleepInBedTaskExecutor.preventsRest(head, zombieAt(head, 8.2, 0, 0)), "its body reaches into the box");
		assertFalse(SleepInBedTaskExecutor.preventsRest(head, zombieAt(head, 8.5, 0, 0)));
		assertTrue(SleepInBedTaskExecutor.preventsRest(head, zombieAt(head, 6.0, 0, -6.0)), "diagonals count up to 8 on each axis");
		assertTrue(SleepInBedTaskExecutor.preventsRest(head, zombieAt(head, 0, 4.9, 0)));
		assertFalse(SleepInBedTaskExecutor.preventsRest(head, zombieAt(head, 0, 5.5, 0)), "more than 5 above");
		assertTrue(SleepInBedTaskExecutor.preventsRest(head, zombieAt(head, 0, -6.5, 0)), "a zombie standing 6.5 below reaches up into it");
		assertFalse(SleepInBedTaskExecutor.preventsRest(head, zombieAt(head, 0, -7.0, 0)));
	}

	/** A zombie-sized box standing at an offset from the bottom centre of the head. */
	private static AABB zombieAt(BlockPos head, double dx, double dy, double dz) {
		Vec3 at = Vec3.atBottomCenterOf(head).add(dx, dy, dz);
		return new AABB(at.x - 0.3, at.y, at.z - 0.3, at.x + 0.3, at.y + 1.95, at.z + 0.3);
	}

	@Test void waitsForMonstersToClearThenSleeps() {
		Fixture f = new Fixture(null);
		f.env.monsters.add(new SleepInBedTaskExecutor.Monster("zombie", 5.2));
		f.env.onUse = f.env::lieDown;
		f.tick(50);
		assertEquals(0, f.env.useCalls, "no use while a monster is near");
		assertTrue(f.snapshotText().startsWith("phase=MONSTERS step=waiting_for zombie 5.2"), f.snapshotText());
		f.env.monsters.clear();
		f.tick(3);
		assertEquals(1, f.env.useCalls);
		assertTrue(f.env.sleeping);
		f.tick(100);
		assertTrue(f.events.isEmpty(), "asleep until woken");
		f.env.wakeInTheMorning();
		f.tick(1);
		var end = f.single();
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState());
		assertTrue(end.message().startsWith("slept_until_morning timeOfDay="), end.message());
		assertTrue(end.message().contains("respawnSet=true"), end.message());
		assertTrue(end.message().contains("health=20.0 food=18 pos="), end.message());
	}

	@Test void failsMonstersNearbyNamingThemAfterTheWait() {
		Fixture f = new Fixture(null);
		f.env.monsters.add(new SleepInBedTaskExecutor.Monster("creeper", 7.04));
		f.env.monsters.add(new SleepInBedTaskExecutor.Monster("skeleton", 4.5));
		f.tick(SleepInBedTaskExecutor.MONSTER_WAIT_TICKS - 5);
		assertTrue(f.events.isEmpty());
		f.tick(10);
		var end = f.single();
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("monsters_nearby"), end.message());
		assertEquals(TaskFailureCode.ENVIRONMENT_CHANGED, end.failureCode());
		assertTrue(end.message().contains("skeleton 4.5, creeper 7.0"), "nearest first with distances: " + end.message());
		assertEquals(0, f.env.useCalls, "never used the bed with monsters near");
	}

	@Test void theMonsterWaitDoesNotCountAgainstTheTimeout() {
		Fixture f = new Fixture(null);
		f.env.monsters.add(new SleepInBedTaskExecutor.Monster("zombie", 3.0));
		f.tick(SleepInBedTaskExecutor.MONSTER_WAIT_TICKS - 10);
		f.env.monsters.clear();
		f.env.onUse = f.env::lieDown;
		f.tick(SleepInBedTaskExecutor.TIMEOUT_TICKS - 100);
		assertTrue(f.events.isEmpty(), f.events.toString());
		assertTrue(f.env.sleeping);
	}

	// ---- Uses and retries ----

	@Test void retriesARefusedUseThreeTimesThenFailsWithTheServersMessage() {
		Fixture f = new Fixture(null);
		f.env.onUse = () -> f.env.say("block.minecraft.bed.occupied", "This bed is occupied", true);
		f.tick(200);
		var end = f.single();
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertEquals(SleepInBedTaskExecutor.MAX_USES, f.env.useCalls);
		assertTrue(end.message().startsWith("not_sleeping"), end.message());
		assertEquals(TaskFailureCode.ENVIRONMENT_CHANGED, end.failureCode());
		assertTrue(end.message().contains("last message \"This bed is occupied\""), end.message());
	}

	@Test void aSecondTryThatLiesDownSleeps() {
		Fixture f = new Fixture(null);
		f.env.onUse = () -> {
			if (f.env.useCalls == 1) f.env.say("block.minecraft.bed.not_safe", "You may not rest now; there are monsters nearby", true);
			else f.env.lieDown();
		};
		f.tick(60);
		assertEquals(2, f.env.useCalls);
		assertTrue(f.env.sleeping);
		assertTrue(f.events.isEmpty());
	}

	@Test void silentUsesFailNotSleeping() {
		Fixture f = new Fixture(null);
		f.tick(200);
		var end = f.single();
		assertTrue(end.message().startsWith("not_sleeping"), end.message());
		assertTrue(end.message().contains("no answer from the server"), end.message());
	}

	// ---- By day ----

	@Test void byDayItSetsTheRespawnPoint() {
		Fixture f = new Fixture(null);
		f.env.dayTime = NOON;
		f.env.onUse = () -> {
			f.env.say("block.minecraft.set_spawn", "Respawn point set", false);
			f.env.say("block.minecraft.bed.no_sleep", "You can sleep only at night or during thunderstorms", true);
		};
		f.tick(5);
		var end = f.single();
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState());
		assertTrue(end.message().startsWith("respawn_set bed=2,64,0"), end.message());
		assertEquals(1, f.env.useCalls);
	}

	@Test void byDayTheNightMessageAloneMeansTheSpawnWasThisBedAlready() {
		Fixture f = new Fixture(null);
		f.env.dayTime = NOON;
		f.env.onUse = () -> f.env.say("block.minecraft.bed.no_sleep", "You can sleep only at night or during thunderstorms", true);
		f.tick(40);
		var end = f.single();
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState());
		assertTrue(end.message().startsWith("respawn_set bed=2,64,0 unchanged=true"), end.message());
	}

	@Test void byDayWithoutConfirmationItIsUnconfirmed() {
		Fixture f = new Fixture(null);
		f.env.dayTime = NOON;
		f.env.monsters.add(new SleepInBedTaskExecutor.Monster("zombie", 2.0));
		f.env.onUse = () -> f.env.say("block.minecraft.bed.obstructed", "This bed is obstructed", true);
		f.tick(200);
		var end = f.single();
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("respawn_unconfirmed"), end.message());
		assertTrue(end.message().contains("This bed is obstructed"), end.message());
		assertEquals(SleepInBedTaskExecutor.MAX_USES, f.env.useCalls, "by day monsters do not hold the use back");
	}

	// ---- Waking ----

	@Test void wakeOutcomes() {
		assertEquals(SleepInBedTaskExecutor.WakeOutcome.MORNING, SleepInBedTaskExecutor.wakeOutcome(24_000L * 3, false, false, false, false, 0));
		assertEquals(SleepInBedTaskExecutor.WakeOutcome.MORNING, SleepInBedTaskExecutor.wakeOutcome(MIDNIGHT, false, false, true, false, 0),
			"the client's day time jumped ahead before it caught up");
		assertEquals(SleepInBedTaskExecutor.WakeOutcome.WAIT, SleepInBedTaskExecutor.wakeOutcome(MIDNIGHT, false, false, false, false, 5),
			"the new day time may still be on its way");
		assertEquals(SleepInBedTaskExecutor.WakeOutcome.EARLY, SleepInBedTaskExecutor.wakeOutcome(MIDNIGHT, false, false, false, true, 0));
		assertEquals(SleepInBedTaskExecutor.WakeOutcome.EARLY, SleepInBedTaskExecutor.wakeOutcome(MIDNIGHT, false, false, false, false,
			SleepInBedTaskExecutor.WAKE_SETTLE_TICKS));
	}

	@Test void attackedInBedWakesEarly() {
		Fixture f = new Fixture(null);
		f.env.onUse = f.env::lieDown;
		f.tick(30);
		f.env.sleeping = false;
		f.env.health = 15.0F;
		f.tick(1);
		var end = f.single();
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("woken_early timeOfDay="), end.message());
		assertTrue(end.message().contains("hurt=true"), end.message());
		assertTrue(end.message().contains("health=15.0"), end.message());
	}

	@Test void wokenAtNightWithoutHarmWaitsForTheDayTimeBeforeCallingItEarly() {
		Fixture f = new Fixture(null);
		f.env.onUse = f.env::lieDown;
		f.tick(30);
		f.env.sleeping = false;
		f.tick(SleepInBedTaskExecutor.WAKE_SETTLE_TICKS - 5);
		assertTrue(f.events.isEmpty(), "the day time may still arrive");
		f.tick(10);
		var end = f.single();
		assertTrue(end.message().startsWith("woken_early"), end.message());
		assertTrue(end.message().contains("hurt=false"), end.message());
	}

	@Test void aLateDayTimeUpdateStillCountsAsMorning() {
		Fixture f = new Fixture(null);
		f.env.onUse = f.env::lieDown;
		f.tick(30);
		f.env.sleeping = false;
		f.tick(10);
		assertTrue(f.events.isEmpty());
		f.env.dayTime = 24_000L;
		f.tick(1);
		assertTrue(f.single().message().startsWith("slept_until_morning"), f.single().message());
	}

	@Test void staysInBedUntilTheTimeoutThenGetsOut() {
		Fixture f = new Fixture(null);
		f.env.onUse = f.env::lieDown;
		f.tick(SleepInBedTaskExecutor.TIMEOUT_TICKS + 5);
		var end = f.single();
		assertTrue(end.message().startsWith("timeout"), end.message());
		assertEquals(TaskFailureCode.TRANSIENT, end.failureCode());
		assertTrue(f.env.leftBed, "got out of bed");
	}

	// ---- Where ----

	@Test void onlyInTheOverworldAndTheBedIsNeverTouched() {
		Fixture f = new Fixture(null);
		f.env.overworld = false;
		f.tick(1);
		var end = f.single();
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertEquals(TaskFailureCode.INVALID_ACTION, end.failureCode());
		assertTrue(end.message().startsWith("wrong_dimension"), end.message());
		assertTrue(end.message().contains("pos=0,64,0"), end.message());
		assertEquals(0, f.env.useCalls);
		assertTrue(f.nav.goals.isEmpty());
	}

	@Test void noBedInSightOrAtThePosition() {
		Fixture none = new Fixture(null);
		none.env.visible.clear();
		none.tick(1);
		assertTrue(none.single().message().startsWith("no_bed; no bed within 16 blocks"), none.single().message());
		assertEquals(TaskFailureCode.MISSING_FACT, none.single().failureCode());
		Fixture wrong = new Fixture(new GoalPosition(9, 64, 9, true));
		wrong.tick(1);
		assertTrue(wrong.single().message().startsWith("no_bed; there is no bed at 9, 64, 9"), wrong.single().message());
	}

	@Test void aGivenPositionPicksThatBed() {
		Fixture f = new Fixture(new GoalPosition(3, 64, 0, true));
		f.env.visible.clear();
		f.env.onUse = f.env::lieDown;
		f.tick(5);
		assertTrue(f.env.sleeping);
		assertEquals(List.of(BED), f.env.used);
	}

	@Test void walksBesideAFarBedFirst() {
		Fixture f = new Fixture(null);
		f.env.feet = new Vec3(12.5, 64, 0.5);
		f.env.onUse = f.env::lieDown;
		f.tick(5);
		assertEquals(1, f.nav.goals.size());
		assertSame(NavigationOptions.WALK_ONLY, f.nav.options);
		GoalPosition goal = f.nav.goals.getFirst();
		assertEquals(new GoalPosition(4, 64, 0, true), goal, "the free cell nearest the body, at the bed's level");
		assertTrue(f.env.sleeping);
	}

	@Test void picksTheNearestVisibleBed() {
		var far = new SleepInBedTaskExecutor.Bed(new BlockPos(10, 64, 10), new BlockPos(10, 64, 11));
		assertEquals(BED, SleepInBedTaskExecutor.nearestBed(List.of(far, BED), new Vec3(0.5, 64, 0.5)));
		assertNull(SleepInBedTaskExecutor.nearestBed(List.of(), Vec3.ZERO));
	}

	@Test void standCellsRingTheBedAndSkipBlockedOrRejectedOnes() {
		List<BlockPos> cells = SleepInBedTaskExecutor.standCandidates(BED);
		assertEquals(3 * (4 * 3 - 2), cells.size());
		assertFalse(cells.contains(BED.head()) || cells.contains(BED.foot()));
		Vec3 feet = new Vec3(0.5, 64, 0.5);
		assertEquals(new BlockPos(1, 64, 0), SleepInBedTaskExecutor.chooseStandCell(cells, cell -> cell.getY() == 64, feet, Set.of()));
		assertEquals(new BlockPos(1, 64, 1), SleepInBedTaskExecutor.chooseStandCell(cells, cell -> cell.getY() == 64, feet,
			Set.of(new BlockPos(1, 64, 0), new BlockPos(1, 64, -1))));
		assertEquals(new BlockPos(1, 65, 0), SleepInBedTaskExecutor.chooseStandCell(cells, cell -> cell.getY() == 65, feet, Set.of()),
			"one up when the ground beside the bed is higher");
		assertNull(SleepInBedTaskExecutor.chooseStandCell(cells, cell -> false, feet, Set.of()));
	}

	@Test void reachIsMeasuredToEitherHalf() {
		assertTrue(SleepInBedTaskExecutor.inReach(new Vec3(0.5, 64, 0.5), BED));
		assertTrue(SleepInBedTaskExecutor.inReach(new Vec3(5.5, 64, 2.5), BED), "beside the foot");
		assertFalse(SleepInBedTaskExecutor.inReach(new Vec3(6.5, 64, 0.5), BED));
		assertFalse(SleepInBedTaskExecutor.inReach(new Vec3(2.5, 66, 0.5), BED), "two up");
	}

	// ---- Messages ----

	@Test void classifiesTheServersAnswersByKeyThenText() {
		assertEquals(SleepInBedTaskExecutor.Reply.RESPAWN_SET, SleepInBedTaskExecutor.classify("block.minecraft.set_spawn", "Punto de reaparición"));
		assertEquals(SleepInBedTaskExecutor.Reply.NOT_SAFE, SleepInBedTaskExecutor.classify("block.minecraft.bed.not_safe", ""));
		assertEquals(SleepInBedTaskExecutor.Reply.OCCUPIED, SleepInBedTaskExecutor.classify("block.minecraft.bed.occupied", ""));
		assertEquals(SleepInBedTaskExecutor.Reply.OBSTRUCTED, SleepInBedTaskExecutor.classify("block.minecraft.bed.obstructed", ""));
		assertEquals(SleepInBedTaskExecutor.Reply.TOO_FAR, SleepInBedTaskExecutor.classify("block.minecraft.bed.too_far_away", ""));
		assertEquals(SleepInBedTaskExecutor.Reply.NO_SLEEP, SleepInBedTaskExecutor.classify("block.minecraft.bed.no_sleep", ""));
		assertEquals(SleepInBedTaskExecutor.Reply.RESPAWN_SET, SleepInBedTaskExecutor.classify("", "Respawn point set"));
		assertEquals(SleepInBedTaskExecutor.Reply.NOT_SAFE, SleepInBedTaskExecutor.classify("", "You may not rest now, there are monsters nearby"));
		assertEquals(SleepInBedTaskExecutor.Reply.NO_SLEEP, SleepInBedTaskExecutor.classify("", "You can sleep only at night"));
		assertEquals(SleepInBedTaskExecutor.Reply.OTHER, SleepInBedTaskExecutor.classify("chat.type.text", "hello"));
	}

	// ---- Pauses and arguments ----

	@Test void aReflexPauseResumesTheSameSleep() {
		Fixture f = new Fixture(null);
		f.env.onUse = f.env::lieDown;
		f.tick(10);
		assertTrue(f.env.sleeping);
		for (int i = 0; i < 30; i++) f.executor.tick(f.session(), Optional.empty());
		assertEquals(TaskExecutionState.IDLE, f.executor.snapshot().state());
		assertTrue(f.env.sleeping, "a pause does not get the body out of bed");
		f.tick(1);
		assertEquals(1, f.env.useCalls, "the bed is not used again");
		assertTrue(f.snapshotText().startsWith("phase=SLEEP"), f.snapshotText());
	}

	@Test void parsesAnOptionalBedPosition() {
		assertNull(SleepInBedStepArgs.parse(new JsonObject()).bed());
		assertEquals(new GoalPosition(1, 70, -3, true), SleepInBedStepArgs.parse(json("{\"x\":1,\"y\":70,\"z\":-3}")).bed());
		assertEquals("bed=1,70,-3", SleepInBedStepArgs.parse(json("{\"x\":1,\"y\":70,\"z\":-3}")).describe());
		assertThrows(IllegalArgumentException.class, () -> SleepInBedStepArgs.parse(json("{\"x\":1,\"z\":-3}")));
		assertThrows(IllegalArgumentException.class, () -> SleepInBedStepArgs.parse(json("{\"x\":1.5,\"y\":70,\"z\":-3}")));
		assertThrows(IllegalArgumentException.class, () -> SleepInBedStepArgs.parse(json("{\"bed\":true}")));
	}

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	// ---- Fakes ----

	private static final class Fixture {
		final Environment env = new Environment();
		final Navigation nav = new Navigation(env);
		final SleepInBedTaskExecutor executor = new SleepInBedTaskExecutor(nav, env);
		final WorldTaskRequest request;
		final List<TaskTerminalEvent> events = new ArrayList<>();
		long ticks;

		Fixture(GoalPosition bed) {
			request = WorldTaskRequest.sleepInBed("sleep", "job", new SleepInBedStepArgs(bed));
		}

		SessionSnapshot session() {
			return new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, ++ticks);
		}

		void tick(int n) {
			for (int i = 0; i < n; i++) executor.tick(session(), Optional.of(request)).ifPresent(events::add);
		}

		TaskTerminalEvent single() {
			assertEquals(1, events.size(), events.toString() + " " + snapshotText());
			return events.getFirst();
		}

		String snapshotText() { return executor.snapshot().lastPathEvent(); }
	}

	/** A bed at 2..3,64,0 on flat ground (solid below y=64); the server answers each use through {@link #onUse}. */
	private static final class Environment implements SleepInBedTaskExecutor.Environment {
		Vec3 feet = new Vec3(0.5, 64, 0.5);
		long dayTime = MIDNIGHT;
		boolean overworld = true, sleeping, raining, thundering, leftBed;
		float health = 20.0F;
		final List<SleepInBedTaskExecutor.Bed> visible = new ArrayList<>(List.of(BED));
		final List<SleepInBedTaskExecutor.Monster> monsters = new ArrayList<>();
		final List<SleepInBedTaskExecutor.Bed> used = new ArrayList<>();
		final List<SleepInBedTaskExecutor.Message> messages = new ArrayList<>();
		final Set<BlockPos> blocked = new HashSet<>();
		Runnable onUse = () -> {};
		int useCalls;

		void lieDown() {
			if (!sleeping) say("block.minecraft.set_spawn", "Respawn point set", false);
			sleeping = true;
		}

		void wakeInTheMorning() {
			sleeping = false;
			dayTime = 24_000L * ((dayTime / 24_000L) + 1);
		}

		void say(String key, String text, boolean overlay) {
			messages.add(new SleepInBedTaskExecutor.Message(key, text, overlay));
		}

		public SleepInBedTaskExecutor.Body observe() {
			return new SleepInBedTaskExecutor.Body(feet, BlockPos.containing(feet), true, health, 18, dayTime++, raining, thundering,
				overworld, overworld ? "minecraft:overworld" : "minecraft:the_nether", sleeping);
		}
		public SleepInBedTaskExecutor.Bed bedAt(BlockPos pos) {
			return pos.equals(BED.head()) || pos.equals(BED.foot()) ? BED : null;
		}
		public List<SleepInBedTaskExecutor.Bed> visibleBeds(int radius) { return visible; }
		public boolean standable(BlockPos cell) { return cell.getY() == 64 && !blocked.contains(cell); }
		public List<SleepInBedTaskExecutor.Monster> monstersNear(SleepInBedTaskExecutor.Bed bed) { return List.copyOf(monsters); }
		public boolean useBed(SleepInBedTaskExecutor.Bed bed, boolean force) {
			useCalls++;
			used.add(bed);
			onUse.run();
			return true;
		}
		public long messageMark() { return messages.size(); }
		public List<SleepInBedTaskExecutor.Message> messagesSince(long mark) {
			return List.copyOf(messages.subList((int) Math.min(mark, messages.size()), messages.size()));
		}
		public void leaveBed() {
			sleeping = false;
			leftBed = true;
		}
		public void stop() {}
		public void release() {}
	}

	/** Arrives at once. */
	private static final class Navigation implements NavigationFacade {
		final Environment env;
		final List<GoalPosition> goals = new ArrayList<>();
		NavigationOptions options;
		Navigation(Environment env) { this.env = env; }
		public boolean isLoaded() { return true; }
		public void startFollow(String s) { fail("no following"); }
		public void startNavigate(GoalPosition p, NavigationOptions o) {
			goals.add(p);
			options = o;
			env.feet = Vec3.atBottomCenterOf(new BlockPos(p.x(), p.y(), p.z()));
		}
		public void startNavigateNear(GoalPosition p, int r, NavigationOptions o) { startNavigate(p, o); }
		public boolean processActive() { return false; }
		public void cancel() {}
		public Optional<String> activeProcessName() { return Optional.empty(); }
		public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		public Optional<String> pollPathEvent() { return Optional.empty(); }
		public boolean navigationGoalReached(GoalPosition p) { return false; }
	}
}
