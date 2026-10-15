package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EnterEndPortalTaskExecutorTest {
	private static final BlockPos CENTER = new BlockPos(0, 64, 0);

	// ---- Ring layout ----

	@Test void theRingIsTwelveFramesAroundTheOpeningWithoutCorners() {
		List<BlockPos> ring = EnterEndPortalTaskExecutor.ringPositions(CENTER);
		assertEquals(12, ring.size());
		assertEquals(12, new HashSet<>(ring).size());
		for (BlockPos pos : ring) {
			int dx = Math.abs(pos.getX()), dz = Math.abs(pos.getZ());
			assertEquals(2, Math.max(dx, dz), pos.toShortString());
			assertTrue(Math.min(dx, dz) <= 1, "no corner: " + pos.toShortString());
			assertNotNull(EnterEndPortalTaskExecutor.inwardFacing(CENTER, pos));
		}
		assertEquals(List.of(new BlockPos(-1, 64, -2), new BlockPos(0, 64, -2), new BlockPos(1, 64, -2)), ring.subList(0, 3),
			"the north side first, west to east");
		assertEquals(9, EnterEndPortalTaskExecutor.openingCells(CENTER).size());
		assertTrue(EnterEndPortalTaskExecutor.openingCells(CENTER).stream().noneMatch(ring::contains));
	}

	@Test void framesFaceTheOpening() {
		assertEquals(Direction.SOUTH, EnterEndPortalTaskExecutor.inwardFacing(CENTER, new BlockPos(1, 64, -2)), "north side faces south");
		assertEquals(Direction.NORTH, EnterEndPortalTaskExecutor.inwardFacing(CENTER, new BlockPos(0, 64, 2)));
		assertEquals(Direction.EAST, EnterEndPortalTaskExecutor.inwardFacing(CENTER, new BlockPos(-2, 64, 1)));
		assertEquals(Direction.WEST, EnterEndPortalTaskExecutor.inwardFacing(CENTER, new BlockPos(2, 64, -1)));
		assertNull(EnterEndPortalTaskExecutor.inwardFacing(CENTER, new BlockPos(2, 64, 2)), "a corner holds no frame");
		assertNull(EnterEndPortalTaskExecutor.inwardFacing(CENTER, new BlockPos(1, 64, 1)), "the opening holds no frame");
		assertNull(EnterEndPortalTaskExecutor.inwardFacing(CENTER, new BlockPos(0, 65, -2)), "another level");
	}

	@Test void anyFrameOfTheRingFindsItsCentre() {
		World world = new World(CENTER);
		for (BlockPos seed : EnterEndPortalTaskExecutor.ringPositions(CENTER)) {
			var ring = EnterEndPortalTaskExecutor.fitRing(world.frameAt(seed), world::frameAt);
			assertEquals(CENTER, ring.center(), "from " + seed.toShortString());
			assertTrue(ring.complete());
			assertEquals(12, ring.frameCount());
		}
		World far = new World(new BlockPos(-317, 23, 1201));
		BlockPos seed = EnterEndPortalTaskExecutor.ringPositions(far.center).get(7);
		assertEquals(far.center, EnterEndPortalTaskExecutor.fitRing(far.frameAt(seed), far::frameAt).center());
	}

	@Test void aMissingOrBackwardFrameIsNamed() {
		World world = new World(CENTER);
		world.frames.remove(new BlockPos(2, 64, 1));
		world.frames.put(new BlockPos(0, 64, 2), new EnterEndPortalTaskExecutor.Frame(new BlockPos(0, 64, 2), Direction.SOUTH, false));
		var ring = EnterEndPortalTaskExecutor.fitRing(world.frameAt(new BlockPos(-1, 64, -2)), world::frameAt);
		assertEquals(CENTER, ring.center(), "ten frames facing in still place the ring");
		assertFalse(ring.complete());
		assertEquals(List.of(new BlockPos(2, 64, 1)), ring.missing());
		assertEquals(List.of(new BlockPos(0, 64, 2)), ring.misfacing());
		assertEquals(10, ring.frameCount());
		// Seen from the backward frame, the ring is still the one most frames agree on.
		assertEquals(CENTER, EnterEndPortalTaskExecutor.fitRing(world.frameAt(new BlockPos(0, 64, 2)), world::frameAt).center());
	}

	@Test void reachIsMeasuredFromTheEyesToTheFrameTop() {
		BlockPos frame = new BlockPos(0, 64, -2);
		assertEquals(new Vec3(0.5, 64.8125, -1.5), EnterEndPortalTaskExecutor.topCenter(frame));
		assertTrue(EnterEndPortalTaskExecutor.inReach(EnterEndPortalTaskExecutor.eyeAt(new BlockPos(0, 64, -3)), frame));
		assertTrue(EnterEndPortalTaskExecutor.inReach(EnterEndPortalTaskExecutor.eyeAt(new BlockPos(0, 62, -3)), frame), "two below, on the floor");
		assertFalse(EnterEndPortalTaskExecutor.inReach(EnterEndPortalTaskExecutor.eyeAt(new BlockPos(0, 64, 3)), frame), "across the opening");
		assertFalse(EnterEndPortalTaskExecutor.inReach(EnterEndPortalTaskExecutor.eyeAt(new BlockPos(0, 64, -7)), frame));
	}

	@Test void standCellsStayOutsideTheRingAndEntryCellsLeadOverAFrame() {
		var ring = EnterEndPortalTaskExecutor.fitRing(new World(CENTER).frameAt(new BlockPos(0, 64, -2)), new World(CENTER)::frameAt);
		for (BlockPos cell : EnterEndPortalTaskExecutor.standCandidates(ring)) {
			assertFalse(ring.overOpening(cell), cell.toShortString());
			assertFalse(ring.frames().contains(cell.atY(64)), "never a frame's column: " + cell.toShortString());
		}
		assertTrue(EnterEndPortalTaskExecutor.standCandidates(ring).contains(new BlockPos(2, 64, 2)), "corners are outside the ring");
		List<BlockPos> entries = EnterEndPortalTaskExecutor.entryCandidates(ring);
		assertEquals(24, entries.size());
		for (BlockPos entry : entries) {
			BlockPos frame = EnterEndPortalTaskExecutor.frameBehind(ring, entry);
			assertTrue(ring.frames().contains(frame), entry.toShortString() + " -> " + frame.toShortString());
			assertEquals(1, Math.abs(entry.getX() - frame.getX()) + Math.abs(entry.getZ() - frame.getZ()), "right outside its frame");
		}
		assertEquals(new BlockPos(1, 64, -2), EnterEndPortalTaskExecutor.frameBehind(ring, new BlockPos(1, 65, -3)));
		assertEquals(new BlockPos(2, 64, -1), EnterEndPortalTaskExecutor.frameBehind(ring, new BlockPos(3, 64, -1)));
	}

	// ---- Filling and entering ----

	@Test void fillsEveryEmptyFrameFromOutsideTheRingThenEntersTheEnd() {
		Fixture f = new Fixture(new EnterEndPortalStepArgs(null, true));
		f.run(400);
		var end = f.single();
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState());
		assertTrue(end.message().startsWith("entered_end eyesPlaced=12"), end.message());
		assertTrue(end.message().contains("health=20.0 food=18 pos="), end.message());
		assertEquals(12, f.env.placed.size());
		assertEquals(Set.copyOf(EnterEndPortalTaskExecutor.ringPositions(CENTER)), Set.copyOf(f.env.placed));
		assertEquals(new BlockPos(0, 64, -2), f.env.placed.get(0), "the nearest frame first");
		assertEquals(Set.of(new BlockPos(-1, 64, -2), new BlockPos(1, 64, -2)), Set.copyOf(f.env.placed.subList(1, 3)),
			"then the rest of the near side, without walking");
		for (int i = 0; i < f.env.placed.size(); i++)
			assertTrue(EnterEndPortalTaskExecutor.inReach(f.env.placedFrom.get(i), f.env.placed.get(i)), "in reach: " + f.env.placed.get(i));
		assertEquals(0, f.env.eyes);
		assertFalse(f.nav.goals.isEmpty(), "it walked around the ring");
		for (GoalPosition goal : f.nav.goals) {
			BlockPos cell = new BlockPos(goal.x(), goal.y(), goal.z());
			assertTrue(Math.max(Math.abs(cell.getX()), Math.abs(cell.getZ())) >= 3 || cell.getY() != 64, "outside the ring: " + cell);
		}
		assertTrue(f.nav.options.walkOnly());
		assertEquals(3, f.nav.options.maxSafeFall());
		assertTrue(f.env.walks > 0);
	}

	@Test void framesThatAlreadyHoldEyesAreSkipped() {
		Fixture f = new Fixture(new EnterEndPortalStepArgs(null, false));
		List<BlockPos> ring = EnterEndPortalTaskExecutor.ringPositions(CENTER);
		for (int i = 0; i < 10; i++) f.env.world.fill(ring.get(i));
		f.env.eyes = 2;
		f.run(400);
		var end = f.single();
		assertTrue(end.message().startsWith("end_portal_lit eyesPlaced=2 center=0,64,0"), end.message());
		assertEquals(List.of(ring.get(10), ring.get(11)), f.env.placed.stream().sorted(java.util.Comparator.comparingInt(ring::indexOf)).toList());
		assertEquals(0, f.env.walks, "enter=false never walks in");
	}

	@Test void missingEyesAreRefusedUpFrontWithoutUsingAny() {
		Fixture f = new Fixture(new EnterEndPortalStepArgs(null, true));
		f.env.world.fill(new BlockPos(0, 64, 2));
		f.env.eyes = 5;
		f.run(10);
		var end = f.single();
		assertEquals(TaskFailureCode.MISSING_ITEM, end.failureCode());
		assertTrue(end.message().startsWith("not_enough_eyes need=11 have=5"), end.message());
		assertTrue(end.message().contains("no eye was used"), end.message());
		assertEquals(0, f.env.useCalls);
		assertTrue(f.nav.goals.isEmpty());
	}

	@Test void aFrameThatDoesNotTakeTheEyeIsRetriedOnceThenNamed() {
		Fixture f = new Fixture(new EnterEndPortalStepArgs(null, true));
		BlockPos stubborn = new BlockPos(0, 64, -2);
		f.env.refused.add(stubborn);
		f.run(200);
		var end = f.single();
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertTrue(end.message().startsWith("frame_not_filled at 0,64,-2; used an eye on it 2 times"), end.message());
		assertEquals(2, f.env.placed.stream().filter(stubborn::equals).count());
		assertTrue(end.message().contains("health=20.0"), end.message());
	}

	@Test void aPortalThatDoesNotLightIsReported() {
		Fixture f = new Fixture(new EnterEndPortalStepArgs(null, true));
		f.env.lights = false;
		f.run(400);
		var end = f.single();
		assertEquals(TaskFailureCode.ENVIRONMENT_CHANGED, end.failureCode());
		assertTrue(end.message().startsWith("portal_not_lit center=0,64,0"), end.message());
		assertTrue(end.message().contains("though every frame holds an eye"), end.message());
		assertEquals(0, f.env.walks, "never walks into an unlit opening");
	}

	@Test void entersOnceTheDimensionChangesAfterTheEndLoads() {
		Fixture f = new Fixture(new EnterEndPortalStepArgs(null, true));
		f.env.transitTicks = 40;
		f.run(400);
		var end = f.single();
		assertTrue(end.message().startsWith("entered_end eyesPlaced=12"), end.message());
		assertEquals(0, f.env.transitTicks, "waited out the loading End without failing");
	}

	@Test void aReflexPauseResumesTheSameTask() {
		Fixture f = new Fixture(new EnterEndPortalStepArgs(null, true));
		while (f.env.placed.size() < 3 && f.events.isEmpty()) f.tick(1);
		int released = f.env.released;
		for (int i = 0; i < 30; i++) f.executor.tick(f.session(), Optional.empty());
		assertEquals(TaskExecutionState.IDLE, f.executor.snapshot().state());
		assertTrue(f.env.released > released, "the body is let go during the pause");
		assertEquals(3, f.env.placed.size(), "no eye during the pause");
		f.tick(1);
		assertTrue(f.snapshotText().startsWith("phase=CONFIRM"), "still confirming the third eye: " + f.snapshotText());
		assertTrue(f.snapshotText().contains("eyesPlaced=2 frame="), f.snapshotText());
		f.run(400);
		assertTrue(f.single().message().startsWith("entered_end eyesPlaced=12"), f.single().message());
	}

	@Test void pauseForReflexResumesTheSameTask() {
		Fixture f = new Fixture(new EnterEndPortalStepArgs(null, true));
		while (f.env.placed.size() < 3 && f.events.isEmpty()) f.tick(1);
		int released = f.env.released;
		f.executor.pauseForReflex();
		assertEquals(TaskExecutionState.IDLE, f.executor.snapshot().state());
		assertTrue(f.env.released > released, "the body is let go during the pause");
		assertEquals(3, f.env.placed.size(), "no eye during the pause");
		f.tick(1);
		assertTrue(f.snapshotText().startsWith("phase=CONFIRM"), "still confirming the third eye: " + f.snapshotText());
		assertTrue(f.snapshotText().contains("eyesPlaced=2 frame="), f.snapshotText());
		f.run(400);
		assertTrue(f.single().message().startsWith("entered_end eyesPlaced=12"), f.single().message());
	}

	@Test void refusesOutsideTheOverworld() {
		Fixture f = new Fixture(new EnterEndPortalStepArgs(null, true));
		f.env.dimension = "minecraft:the_nether";
		f.run(5);
		var end = f.single();
		assertEquals(TaskFailureCode.INVALID_ACTION, end.failureCode());
		assertTrue(end.message().startsWith("wrong_dimension"), end.message());
		assertEquals(0, f.env.useCalls);
	}

	@Test void needsAFrameInSightOrAtTheGivenPosition() {
		Fixture hidden = new Fixture(new EnterEndPortalStepArgs(null, true));
		hidden.env.visible.clear();
		hidden.run(5);
		assertTrue(hidden.single().message().startsWith("no_portal_frame; no end portal frame within 24 blocks"), hidden.single().message());
		Fixture wrong = new Fixture(new EnterEndPortalStepArgs(new GoalPosition(9, 64, 9, true), true));
		wrong.run(5);
		assertTrue(wrong.single().message().startsWith("no_portal_frame; there is no end portal frame at 9,64,9"), wrong.single().message());
		Fixture given = new Fixture(new EnterEndPortalStepArgs(new GoalPosition(2, 64, 0, true), false));
		given.env.visible.clear();
		given.run(400);
		assertTrue(given.single().message().startsWith("end_portal_lit eyesPlaced=12"), given.single().message());
	}

	@Test void anIncompleteRingIsRefused() {
		Fixture f = new Fixture(new EnterEndPortalStepArgs(null, true));
		f.env.world.frames.remove(new BlockPos(-2, 64, 1));
		f.run(5);
		var end = f.single();
		assertEquals(TaskFailureCode.MISSING_FACT, end.failureCode());
		assertTrue(end.message().startsWith("incomplete_frame center=0,64,0; 11 of 12 frames face the opening, none at -2,64,1"), end.message());
		assertEquals(0, f.env.useCalls);
	}

	@Test void noStandableCellIsUnreachable() {
		Fixture f = new Fixture(new EnterEndPortalStepArgs(null, true));
		f.env.feet = new Vec3(0.5, 64, -9.5);
		f.env.noGround = true;
		f.run(5);
		var end = f.single();
		assertEquals(TaskFailureCode.TRANSIENT, end.failureCode());
		assertTrue(end.message().startsWith("unreachable; no standable cell outside the ring reaches the empty frames"), end.message());
		assertEquals(0, f.env.useCalls);
	}

	@Test void parsesOptionalFrameAndEnter() {
		var none = EnterEndPortalStepArgs.parse(new JsonObject());
		assertNull(none.frame());
		assertTrue(none.enter());
		var given = EnterEndPortalStepArgs.parse(json("{\"x\":1,\"y\":30,\"z\":-3,\"enter\":false}"));
		assertEquals(new GoalPosition(1, 30, -3, true), given.frame());
		assertFalse(given.enter());
		assertEquals("frame=1,30,-3 enter=false", given.describe());
		for (String bad : List.of("{\"x\":1,\"z\":-3}", "{\"x\":1.5,\"y\":3,\"z\":-3}", "{\"enter\":\"yes\"}", "{\"frame\":1}"))
			assertThrows(IllegalArgumentException.class, () -> EnterEndPortalStepArgs.parse(json(bad)), bad);
	}

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	// ---- Fakes ----

	private static final class Fixture {
		final Environment env = new Environment();
		final Navigation nav = new Navigation(env);
		final EnterEndPortalTaskExecutor executor = new EnterEndPortalTaskExecutor(nav, env);
		final WorldTaskRequest request;
		final List<TaskTerminalEvent> events = new ArrayList<>();
		long ticks;

		Fixture(EnterEndPortalStepArgs args) {
			request = WorldTaskRequest.enterEndPortal("portal", "job", args);
		}

		SessionSnapshot session() {
			return new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, ++ticks);
		}

		void tick(int n) {
			for (int i = 0; i < n; i++) executor.tick(session(), Optional.of(request)).ifPresent(events::add);
		}

		void run(int max) {
			for (int i = 0; i < max && events.isEmpty(); i++) tick(1);
		}

		TaskTerminalEvent single() {
			assertEquals(1, events.size(), events.toString() + " " + snapshotText());
			return events.getFirst();
		}

		String snapshotText() { return executor.snapshot().lastPathEvent(); }
	}

	/** A complete ring of empty frames facing the opening. */
	private static final class World {
		final BlockPos center;
		final Map<BlockPos, EnterEndPortalTaskExecutor.Frame> frames = new HashMap<>();

		World(BlockPos center) {
			this.center = center;
			for (BlockPos pos : EnterEndPortalTaskExecutor.ringPositions(center))
				frames.put(pos, new EnterEndPortalTaskExecutor.Frame(pos, EnterEndPortalTaskExecutor.inwardFacing(center, pos), false));
		}

		EnterEndPortalTaskExecutor.Frame frameAt(BlockPos pos) { return frames.get(pos); }

		void fill(BlockPos pos) {
			var frame = frames.get(pos);
			frames.put(pos, new EnterEndPortalTaskExecutor.Frame(pos, frame.facing(), true));
		}

		boolean full() { return frames.values().stream().allMatch(EnterEndPortalTaskExecutor.Frame::eye); }
	}

	/**
	 * The ring around 0,64,0 on flat ground at y=64 that is standable three or more blocks out; the server fills a frame
	 * a few ticks after a use and lights the portal once all 12 hold eyes.
	 */
	private static final class Environment implements EnterEndPortalTaskExecutor.Environment {
		final World world = new World(CENTER);
		Vec3 feet = new Vec3(0.5, 64, -3.5);
		String dimension = EnterEndPortalTaskExecutor.OVERWORLD;
		int eyes = 12, useCalls, walks, released, transitTicks;
		boolean lights = true, noGround;
		final List<BlockPos> visible = new ArrayList<>(List.of(new BlockPos(0, 64, -2)));
		final Set<BlockPos> refused = new HashSet<>();
		final List<BlockPos> placed = new ArrayList<>();
		final List<Vec3> placedFrom = new ArrayList<>();
		private BlockPos pending;
		private int pendingTicks;

		public EnterEndPortalTaskExecutor.Body observe() {
			if (walks >= 3 && dimension.equals(EnterEndPortalTaskExecutor.OVERWORLD)) {
				if (transitTicks > 0) {
					transitTicks--;
					return null;
				}
				dimension = EnterEndPortalTaskExecutor.THE_END;
				feet = new Vec3(100.5, 49, 0.5);
			}
			if (pending != null && ++pendingTicks >= 3) {
				world.fill(pending);
				pending = null;
			}
			return new EnterEndPortalTaskExecutor.Body(feet, feet.add(0, EnterEndPortalTaskExecutor.EYE_HEIGHT, 0), BlockPos.containing(feet),
				true, 20.0F, 18, dimension, eyes);
		}
		public EnterEndPortalTaskExecutor.Frame frameAt(BlockPos pos) { return world.frameAt(pos); }
		public List<BlockPos> visibleFrames(int radius) { return visible; }
		public boolean portalAt(BlockPos pos) {
			return lights && world.full() && Math.abs(pos.getX()) <= 1 && Math.abs(pos.getZ()) <= 1 && pos.getY() == 64;
		}
		public boolean standable(BlockPos cell) {
			return !noGround && cell.getY() == 64 && Math.max(Math.abs(cell.getX()), Math.abs(cell.getZ())) >= 3;
		}
		public boolean open(BlockPos pos) { return true; }
		public EnterEndPortalTaskExecutor.UseResult placeEye(BlockPos frame, boolean force) {
			useCalls++;
			if (eyes <= 0) return EnterEndPortalTaskExecutor.UseResult.NO_EYE;
			placed.add(frame);
			placedFrom.add(feet.add(0, EnterEndPortalTaskExecutor.EYE_HEIGHT, 0));
			if (!refused.contains(frame) && !world.frameAt(frame).eye()) {
				eyes--;
				pending = frame;
				pendingTicks = 0;
			}
			return EnterEndPortalTaskExecutor.UseResult.USED;
		}
		public void walkToward(Vec3 target, long tick) { walks++; }
		public void stop() {}
		public void release() { released++; }
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
