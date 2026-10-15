package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Fills the End portal in a stronghold's portal room with eyes of ender and walks into it, as one task. By hand this was up
 * to twelve {@code use_block} calls on frames hanging over a lava pool, with a silverfish spawner on the stairs.
 *
 * <p>Overworld only. The task finds a frame (the one given, else the nearest one the eyes can see), reads the 5x5 ring of 12
 * frames around it from the frames' facings, refuses up front when the inventory lacks an eye for every empty frame, then for
 * each empty frame walks (walk only, falls of at most 3) to a standable cell outside the ring from which the frame's top is in
 * reach, never into the opening or the lava under it, and uses an eye on the frame's top. It confirms each frame took its eye,
 * then that end portal blocks fill the 3x3 opening. With {@code enter} it walks from a cell just outside a side of the ring
 * over a frame into the opening and succeeds once the body is in the End, standing still where it landed.
 */
public final class EnterEndPortalTaskExecutor implements WorldTaskExecutor {
	/** How far to look for a frame in sight when no position is given. */
	static final int SEARCH_RADIUS = 24;
	/** Eye to the centre of the frame's top: a margin inside vanilla's 4.5-block interaction range. */
	static final double REACH = 4.0D;
	/** An end portal frame is 13/16 of a block tall. */
	static final double FRAME_TOP = 0.8125D;
	static final double EYE_HEIGHT = 1.62D;
	/** A frame that still has no eye this long after a use did not take it. */
	static final int CONFIRM_TICKS = 20;
	/** Uses per frame: the first and one retry. */
	static final int MAX_USES = 2;
	/** Uses the eye once aimed; past this it uses it anyway. */
	static final int AIM_TICKS = 20;
	/** Past this a use the actuator keeps refusing still counts as an attempt. */
	static final int USE_TIMEOUT_TICKS = 60;
	/** Time for the server's portal blocks to reach the client after the last eye. */
	static final int LIGHT_TICKS = 40;
	static final int GO_TIMEOUT_TICKS = 400;
	/** A walk into the lit portal that has not changed the dimension in this long is tried from another cell. */
	static final int PLUNGE_TICKS = 100;
	static final int MAX_PLUNGES = 3;
	/** The End loading after the body touched the portal. */
	static final int TRANSIT_TICKS = 600;
	static final int TIMEOUT_TICKS = 3_600;
	static final int MAX_SAFE_FALL = 3;
	/** Gives up on a walk before the dispatcher's 100-tick stall watchdog would end the whole task. */
	static final long NAVIGATION_STALL_TICKS = 70;
	static final NavigationOptions OPTIONS = NavigationOptions.WALK_ONLY.withMaxSafeFall(MAX_SAFE_FALL);
	static final String OVERWORLD = "minecraft:overworld";
	static final String THE_END = "minecraft:the_end";

	private static final String PROCESS = "EnterEndPortal";

	private final NavigationFacade navigation;
	private final Environment environment;
	private WorldTaskRequest request;
	private EnterEndPortalStepArgs args;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	private Phase phase = Phase.START;
	private int activeTicks, phaseTicks, uses, eyesPlaced, plunges, transitTicks;
	private Ring ring;
	private BlockPos target, standCell, entryCell;
	private final Set<BlockPos> rejectedCells = new HashSet<>();
	private final Set<BlockPos> rejectedEntries = new HashSet<>();
	private String step = "";
	private String lastNote = "";
	private GoalPosition navigationGoal;
	private boolean navigationOwned;
	private int navigationTicks;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);

	public EnterEndPortalTaskExecutor(NavigationFacade navigation, ai.moeru.airicraft.agent.control.CameraController camera) {
		this(navigation, new MinecraftEndPortalEnvironment(camera));
	}

	EnterEndPortalTaskExecutor(NavigationFacade navigation, Environment environment) {
		this.navigation = Objects.requireNonNull(navigation, "navigation");
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.ENTER_END_PORTAL) {
			// A reflex pause hands the same task back afterwards: keep the ring and the counters, only let go of the body.
			if (request != null) release();
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest next = activeTask.get();
		if (request == null || !request.taskId().equals(next.taskId())) {
			release();
			request = next;
			args = ((WorldTaskRequest.EnterEndPortal) next.task()).args();
			reset();
		}
		if (terminal != null) return finishRelease();
		if (session == null || !session.companionActuationAllowed()) {
			release();
			setSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, null);
			return Optional.empty();
		}
		if (phase == Phase.PLUNGE) {
			// Touching the portal swaps the level; while the End loads there is no body and no navigation.
			Body body = navigation.isLoaded() ? environment.observe() : null;
			if (body == null || !body.overworld() && !body.end()) {
				step = "transit";
				if (++transitTicks > TRANSIT_TICKS)
					return finish(false, "timeout after " + transitTicks + " ticks waiting for the End to load", TaskFailureCode.TRANSIENT, body);
				setSnapshot(TaskExecutionState.RUNNING, body);
				return Optional.empty();
			}
		}
		if (!navigation.isLoaded()) return finish(false, "navigation_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED, null);
		Body body = environment.observe();
		if (body == null) return finish(false, "world_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED, null);
		activeTicks++;
		phaseTicks++;

		if (phase == Phase.START) {
			Optional<TaskTerminalEvent> ended = start(body);
			if (ended.isPresent()) return ended;
		}
		if (phase == Phase.PLUNGE && body.end())
			return finish(true, "entered_end eyesPlaced=" + eyesPlaced + "; standing where the End portal put you (the obsidian"
				+ " platform). fight_ender_dragon takes it from here", null, body);
		if (activeTicks > TIMEOUT_TICKS)
			return finish(false, "timeout after " + activeTicks + " ticks (" + phase.name().toLowerCase(Locale.ROOT)
				+ (step.isEmpty() ? "" : ": " + step) + ")" + progress(), TaskFailureCode.TRANSIENT, body);

		Optional<TaskTerminalEvent> ended = switch (phase) {
			case START, PICK -> pick(body);
			case GO -> go(body);
			case USE -> use(body);
			case CONFIRM -> confirm(body);
			case LIGHT -> light(body);
			case ENTER_GO -> enterGo(body);
			case PLUNGE -> plunge(body);
		};
		if (ended.isPresent()) return ended;
		setSnapshot(TaskExecutionState.RUNNING, body);
		return Optional.empty();
	}

	// ---- Phases ----

	private Optional<TaskTerminalEvent> start(Body body) {
		if (!body.overworld())
			return finish(false, "wrong_dimension; End portals are in overworld strongholds and this is " + body.dimension()
				+ ". Nothing was used", TaskFailureCode.INVALID_ACTION, body);
		Frame seed;
		if (args.frame() != null) {
			BlockPos pos = new BlockPos(args.frame().x(), args.frame().y(), args.frame().z());
			seed = environment.frameAt(pos);
			if (seed == null)
				return finish(false, "no_portal_frame; there is no end portal frame at " + describe(pos) + ". Check it with"
					+ " find_world_features featureKind=end_portal, or call enter_end_portal without x,y,z for the nearest frame in"
					+ " sight", TaskFailureCode.MISSING_FACT, body);
		}
		else {
			BlockPos nearest = environment.visibleFrames(SEARCH_RADIUS).stream()
				.min(Comparator.comparingDouble(pos -> topCenter(pos).distanceToSqr(body.eye()))).orElse(null);
			seed = nearest == null ? null : environment.frameAt(nearest);
			if (seed == null)
				return finish(false, "no_portal_frame; no end portal frame within " + SEARCH_RADIUS + " blocks is in sight. Walk"
					+ " into the portal room (find_world_features featureKind=end_portal), or pass the x,y,z of a frame",
					TaskFailureCode.MISSING_FACT, body);
		}
		ring = fitRing(seed, environment::frameAt);
		if (!ring.complete())
			return finish(false, "incomplete_frame center=" + describe(ring.center()) + "; " + ring.frameCount() + " of 12 frames"
				+ " face the opening" + (ring.missing().isEmpty() ? "" : ", none at " + describeAll(ring.missing()))
				+ (ring.misfacing().isEmpty() ? "" : ", facing away from the opening at " + describeAll(ring.misfacing()))
				+ ". A portal needs all 12 frames of the 5x5 ring facing inward", TaskFailureCode.MISSING_FACT, body);
		List<BlockPos> empty = emptyFrames();
		if (body.eyes() < empty.size())
			return finish(false, "not_enough_eyes need=" + empty.size() + " have=" + body.eyes() + "; " + (12 - empty.size())
				+ " of the 12 frames already hold an eye. Craft eyes of ender (an ender pearl and blaze powder each) and call"
				+ " again; no eye was used", TaskFailureCode.MISSING_ITEM, body);
		lastNote = "empty_at_start=" + empty.size();
		enter(Phase.PICK);
		return Optional.empty();
	}

	/** Picks the next empty frame and where to stand for it; to the light check once every frame holds an eye. */
	private Optional<TaskTerminalEvent> pick(Body body) {
		stopNavigation();
		List<BlockPos> empty = emptyFrames();
		if (empty.isEmpty()) {
			environment.stop();
			enter(Phase.LIGHT);
			return light(body);
		}
		if (body.eyes() < empty.size())
			return finish(false, "not_enough_eyes need=" + empty.size() + " have=" + body.eyes() + "; eyes ran out after placing "
				+ eyesPlaced + ". Craft more and call again", TaskFailureCode.MISSING_ITEM, body);
		uses = 0;
		standCell = null;
		if (body.onGround() && !ring.overOpening(body.block())) {
			BlockPos reachable = empty.stream().filter(frame -> inReach(body.eye(), frame))
				.min(Comparator.comparingDouble(frame -> topCenter(frame).distanceToSqr(body.eye()))).orElse(null);
			if (reachable != null) {
				target = reachable;
				environment.stop();
				enter(Phase.USE);
				return Optional.empty();
			}
		}
		Choice choice = chooseTarget(empty, ring, environment::standable, body.feet(), rejectedCells);
		if (choice == null)
			return finish(false, "unreachable; no standable cell outside the ring reaches the empty frames at " + describeAll(empty)
				+ (rejectedCells.isEmpty() ? "" : " (gave up on " + rejectedCells.size() + " cells)") + ". Clear ground beside the"
				+ " ring or build a step up to it, then call again" + progress(), TaskFailureCode.TRANSIENT, body);
		target = choice.frame();
		standCell = choice.cell();
		enter(Phase.GO);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> go(Body body) {
		if (environment.frameAt(target) != null && environment.frameAt(target).eye()) {
			enter(Phase.PICK);
			return Optional.empty();
		}
		if (body.onGround() && inReach(body.eye(), target) && !ring.overOpening(body.block())
			&& (body.block().equals(standCell) || navigationOwned && navigationTicks > 10 && !navigation.processActive())) {
			stopNavigation();
			environment.stop();
			enter(Phase.USE);
			return Optional.empty();
		}
		if (phaseTicks > GO_TIMEOUT_TICKS) return rejectCell("walk_timeout");
		step = "walk to " + describe(standCell) + " for " + describe(target);
		if (!navigate(standCell)) return rejectCell("unreachable");
		if (navigationOwned && navigationTicks > 10 && !navigation.processActive()) return rejectCell("stopped_short");
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> rejectCell(String why) {
		stopNavigation();
		environment.stop();
		rejectedCells.add(standCell);
		lastNote = "skipped_cell=" + describe(standCell) + " " + why;
		standCell = null;
		enter(Phase.PICK);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> use(Body body) {
		Frame frame = environment.frameAt(target);
		if (frame == null)
			return finish(false, "frame_not_filled at " + describe(target) + "; the frame is gone" + progress(),
				TaskFailureCode.ENVIRONMENT_CHANGED, body);
		if (frame.eye()) {
			enter(Phase.PICK);
			return Optional.empty();
		}
		if (!inReach(body.eye(), target)) {
			enter(Phase.PICK);
			return Optional.empty();
		}
		step = "aim at " + describe(target);
		UseResult result = environment.placeEye(target, phaseTicks > AIM_TICKS);
		if (result == UseResult.NO_EYE)
			return finish(false, "not_enough_eyes need=" + emptyFrames().size() + " have=0; eyes ran out after placing " + eyesPlaced
				+ ". Craft more and call again", TaskFailureCode.MISSING_ITEM, body);
		if (result == UseResult.USED || phaseTicks > USE_TIMEOUT_TICKS) {
			uses++;
			enter(Phase.CONFIRM);
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> confirm(Body body) {
		environment.stop();
		Frame frame = environment.frameAt(target);
		if (frame != null && frame.eye()) {
			eyesPlaced++;
			lastNote = "filled=" + describe(target);
			enter(Phase.PICK);
			return pick(body);
		}
		step = "confirm " + describe(target) + " use=" + uses;
		if (phaseTicks < CONFIRM_TICKS) return Optional.empty();
		if (frame != null && uses < MAX_USES) {
			enter(Phase.USE);
			return Optional.empty();
		}
		return finish(false, "frame_not_filled at " + describe(target) + "; used an eye on it " + uses + " times and it still"
			+ " shows no eye" + progress() + ". Look at the frame (inspect_world) and call again", TaskFailureCode.ENVIRONMENT_CHANGED, body);
	}

	private Optional<TaskTerminalEvent> light(Body body) {
		if (portalLit()) {
			if (!args.enter())
				return finish(true, "end_portal_lit eyesPlaced=" + eyesPlaced + " center=" + describe(ring.center()) + "; call"
					+ " enter_end_portal again (enter defaults to true) when ready for the dragon", null, body);
			enter(Phase.ENTER_GO);
			return Optional.empty();
		}
		step = "waiting_for_portal";
		if (phaseTicks < LIGHT_TICKS) return Optional.empty();
		List<BlockPos> empty = emptyFrames();
		return finish(false, "portal_not_lit center=" + describe(ring.center()) + "; no end portal blocks fill the opening "
			+ phaseTicks + " ticks after the last eye" + (empty.isEmpty() ? " though every frame holds an eye"
			: "; frames still empty at " + describeAll(empty)) + progress(), TaskFailureCode.ENVIRONMENT_CHANGED, body);
	}

	private Optional<TaskTerminalEvent> enterGo(Body body) {
		if (entryCell == null) {
			entryCell = chooseEntry(entryCandidates(ring), cell -> environment.standable(cell)
				&& environment.open(frameBehind(ring, cell).above()) && environment.open(frameBehind(ring, cell).above(2)),
				body.feet(), rejectedEntries);
			if (entryCell == null)
				return finish(false, "unreachable; the portal is lit at " + describe(ring.center()) + " but no standable cell just"
					+ " outside a side of the ring with room over its frame leads into it" + (rejectedEntries.isEmpty() ? ""
					: " (tried " + rejectedEntries.size() + ")") + ". Walk onto a frame and into the opening, or clear a cell beside the"
					+ " ring", TaskFailureCode.TRANSIENT, body);
			phaseTicks = 1;
		}
		if (body.block().equals(entryCell) && body.onGround()) {
			stopNavigation();
			environment.stop();
			plunges++;
			enter(Phase.PLUNGE);
			return Optional.empty();
		}
		if (phaseTicks > GO_TIMEOUT_TICKS) return rejectEntry("walk_timeout");
		step = "walk to " + describe(entryCell);
		if (!navigate(entryCell)) return rejectEntry("unreachable");
		if (navigationOwned && navigationTicks > 10 && !navigation.processActive()) return rejectEntry("stopped_short");
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> rejectEntry(String why) {
		stopNavigation();
		environment.stop();
		rejectedEntries.add(entryCell);
		lastNote = "skipped_entry=" + describe(entryCell) + " " + why;
		entryCell = null;
		enter(Phase.ENTER_GO);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> plunge(Body body) {
		step = "walk_in";
		if (!portalLit()) {
			environment.stop();
			return finish(false, "portal_not_lit center=" + describe(ring.center()) + "; the end portal blocks went away before"
				+ " the body reached them" + progress(), TaskFailureCode.ENVIRONMENT_CHANGED, body);
		}
		if (phaseTicks > PLUNGE_TICKS) {
			environment.stop();
			if (plunges >= MAX_PLUNGES)
				return finish(false, "unreachable; walked toward the lit portal " + plunges + " times and the dimension did not"
					+ " change. Walk onto a frame and into the opening", TaskFailureCode.TRANSIENT, body);
			return rejectEntry("did_not_enter");
		}
		environment.walkToward(Vec3.atCenterOf(ring.center()), activeTicks);
		return Optional.empty();
	}

	// ---- Helpers ----

	private List<BlockPos> emptyFrames() {
		List<BlockPos> empty = new ArrayList<>();
		for (BlockPos pos : ring.frames()) {
			Frame frame = environment.frameAt(pos);
			if (frame == null || !frame.eye()) empty.add(pos);
		}
		return empty;
	}

	private boolean portalLit() {
		for (BlockPos cell : ring.opening()) if (!environment.portalAt(cell)) return false;
		return true;
	}

	private String progress() {
		return " eyesPlaced=" + eyesPlaced;
	}

	private void enter(Phase next) {
		phase = next;
		phaseTicks = 0;
		step = "";
	}

	/** False once the walk cannot go on. */
	private boolean navigate(BlockPos cell) {
		GoalPosition goal = new GoalPosition(cell.getX(), cell.getY(), cell.getZ(), true);
		if (!navigationOwned || !goal.equals(navigationGoal)) {
			stopNavigation();
			NavigationRelease.release(navigation);
			navigation.pollPathEvent();
			navigation.startNavigate(goal, OPTIONS);
			navigationOwned = true;
			navigationGoal = goal;
			navigationTicks = 0;
			navigationStall.clear();
		}
		navigationTicks++;
		String event = navigation.pollPathEvent().orElse("");
		var progress = navigation.navigationProgress();
		if (progress.isEmpty()) navigationStall.clear();
		else if (navigationStall.observe(activeTicks, progress.get())) return false;
		return !event.contains("CALC_FAILED") && !event.equals("CANCELED");
	}

	private void stopNavigation() {
		if (navigationOwned) navigation.cancel();
		navigationOwned = false;
		navigationGoal = null;
		navigationTicks = 0;
		navigationStall.clear();
	}

	// ---- Pure decisions ----

	/** The 12 frame cells of the 5x5 ring around {@code center}: north side west to east, then east, south and west sides. */
	static List<BlockPos> ringPositions(BlockPos center) {
		List<BlockPos> cells = new ArrayList<>(12);
		for (Direction side : new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
			Direction along = side.getClockWise();
			for (int l = -1; l <= 1; l++) cells.add(center.relative(side, 2).relative(along, l));
		}
		return cells;
	}

	/** The 3x3 opening around {@code center}, at the frames' level. */
	static List<BlockPos> openingCells(BlockPos center) {
		List<BlockPos> cells = new ArrayList<>(9);
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) cells.add(center.offset(dx, 0, dz));
		return cells;
	}

	/** Which way a frame of the ring around {@code center} at {@code pos} faces when it faces the opening; null off the ring. */
	static Direction inwardFacing(BlockPos center, BlockPos pos) {
		int dx = pos.getX() - center.getX(), dz = pos.getZ() - center.getZ();
		if (pos.getY() != center.getY()) return null;
		if (dz == -2 && Math.abs(dx) <= 1) return Direction.SOUTH;
		if (dz == 2 && Math.abs(dx) <= 1) return Direction.NORTH;
		if (dx == -2 && Math.abs(dz) <= 1) return Direction.EAST;
		if (dx == 2 && Math.abs(dz) <= 1) return Direction.WEST;
		return null;
	}

	/**
	 * The ring a frame belongs to. A frame faces the opening (vanilla places it facing the player, who stands inside the ring),
	 * so the centre is two blocks ahead of it and up to one block to either side; the six candidates (and, for a frame placed
	 * the wrong way round, the six behind it) are scored by how many of their 12 cells hold a frame facing their opening.
	 */
	static Ring fitRing(Frame seed, Function<BlockPos, Frame> frames) {
		Direction facing = seed.facing();
		Direction along = facing.getClockWise();
		Ring best = null;
		for (int sign : new int[] {1, -1}) {
			for (int l : new int[] {0, -1, 1}) {
				BlockPos center = seed.pos().relative(facing, 2 * sign).relative(along, l);
				if (inwardFacing(center, seed.pos()) == null) continue;
				Ring ring = evaluate(center, frames);
				if (best == null || ring.score() > best.score()) best = ring;
			}
		}
		return best;
	}

	static Ring evaluate(BlockPos center, Function<BlockPos, Frame> frames) {
		List<BlockPos> positions = ringPositions(center);
		List<BlockPos> missing = new ArrayList<>();
		List<BlockPos> misfacing = new ArrayList<>();
		for (BlockPos pos : positions) {
			Frame frame = frames.apply(pos);
			if (frame == null) missing.add(pos);
			else if (frame.facing() != inwardFacing(center, pos)) misfacing.add(pos);
		}
		return new Ring(center.immutable(), List.copyOf(positions), List.copyOf(missing), List.copyOf(misfacing));
	}

	/** The centre of a frame's top face, where an eye goes. */
	static Vec3 topCenter(BlockPos frame) {
		return new Vec3(frame.getX() + 0.5D, frame.getY() + FRAME_TOP, frame.getZ() + 0.5D);
	}

	/** Eyes of a body standing in this cell. */
	static Vec3 eyeAt(BlockPos cell) {
		return new Vec3(cell.getX() + 0.5D, cell.getY() + EYE_HEIGHT, cell.getZ() + 0.5D);
	}

	static boolean inReach(Vec3 eye, BlockPos frame) {
		return eye.distanceToSqr(topCenter(frame)) <= REACH * REACH;
	}

	/**
	 * Cells outside the ring to stand in while placing eyes: the columns up to two blocks beyond the ring (its corners too),
	 * never a frame's column or the opening's, from three below the frames to one above.
	 */
	static List<BlockPos> standCandidates(Ring ring) {
		BlockPos c = ring.center();
		List<BlockPos> cells = new ArrayList<>();
		for (int dy : new int[] {0, 1, -1, -2, -3}) {
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					if (Math.abs(dx) <= 2 && Math.abs(dz) <= 2 && !(Math.abs(dx) == 2 && Math.abs(dz) == 2)) continue;
					cells.add(c.offset(dx, dy, dz));
				}
			}
		}
		return cells;
	}

	/**
	 * The empty frame to fill next and the cell to fill it from: of every standable cell that reaches an empty frame, the one
	 * nearest the feet; null when no empty frame can be reached.
	 */
	static Choice chooseTarget(List<BlockPos> empty, Ring ring, Predicate<BlockPos> standable, Vec3 feet, Set<BlockPos> rejected) {
		Choice best = null;
		double bestCost = Double.MAX_VALUE;
		List<BlockPos> candidates = standCandidates(ring);
		for (BlockPos cell : candidates) {
			if (rejected.contains(cell)) continue;
			Vec3 eye = eyeAt(cell);
			BlockPos frame = empty.stream().filter(pos -> inReach(eye, pos))
				.min(Comparator.comparingDouble(pos -> topCenter(pos).distanceToSqr(eye))).orElse(null);
			if (frame == null) continue;
			double cost = Vec3.atBottomCenterOf(cell).distanceToSqr(feet);
			if (cost >= bestCost || !standable.test(cell)) continue;
			best = new Choice(frame, cell);
			bestCost = cost;
		}
		return best;
	}

	/**
	 * Cells to walk into the portal from: just outside each frame, level with it or one above, so a walk toward the centre
	 * steps onto the frame and drops into the opening.
	 */
	static List<BlockPos> entryCandidates(Ring ring) {
		List<BlockPos> cells = new ArrayList<>();
		for (Direction side : new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
			Direction along = side.getClockWise();
			for (int l : new int[] {0, -1, 1}) {
				BlockPos outside = ring.center().relative(side, 3).relative(along, l);
				cells.add(outside);
				cells.add(outside.above());
			}
		}
		return cells;
	}

	/** The frame a walk from this entry cell toward the centre steps over. */
	static BlockPos frameBehind(Ring ring, BlockPos entry) {
		int dx = Integer.signum(entry.getX() - ring.center().getX()) * (Math.abs(entry.getX() - ring.center().getX()) == 3 ? 1 : 0);
		int dz = Integer.signum(entry.getZ() - ring.center().getZ()) * (Math.abs(entry.getZ() - ring.center().getZ()) == 3 ? 1 : 0);
		return new BlockPos(entry.getX() - dx, ring.center().getY(), entry.getZ() - dz);
	}

	static BlockPos chooseEntry(List<BlockPos> candidates, Predicate<BlockPos> usable, Vec3 feet, Set<BlockPos> rejected) {
		return candidates.stream().filter(cell -> !rejected.contains(cell))
			.sorted(Comparator.comparingDouble(cell -> Vec3.atBottomCenterOf(cell).distanceToSqr(feet)))
			.filter(usable).findFirst().orElse(null);
	}

	static String describe(BlockPos pos) {
		return pos == null ? "none" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	static String describeAll(List<BlockPos> positions) {
		return positions.stream().map(EnterEndPortalTaskExecutor::describe).collect(Collectors.joining(" "));
	}

	// ---- Lifecycle ----

	private void release() {
		stopNavigation();
		environment.release();
	}

	private void reset() {
		terminal = null;
		emitted = false;
		phase = Phase.START;
		activeTicks = phaseTicks = uses = eyesPlaced = plunges = transitTicks = 0;
		ring = null;
		target = standCell = entryCell = null;
		rejectedCells.clear();
		rejectedEntries.clear();
		step = "";
		lastNote = "";
	}

	private static String summary(Body body) {
		if (body == null) return "";
		return String.format(Locale.ROOT, " health=%.1f food=%d pos=%d,%d,%d", body.health(), body.food(),
			body.block().getX(), body.block().getY(), body.block().getZ());
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason, TaskFailureCode failureCode, Body body) {
		release();
		terminal = new TaskTerminalEvent(request.taskId(), null, success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED,
			reason + summary(body), success ? TaskTerminationCause.GOAL_REACHED : null, success ? null : failureCode);
		return finishRelease();
	}

	private Optional<TaskTerminalEvent> finishRelease() {
		release();
		NavigationRelease.release(navigation);
		snapshot = new TaskExecutionSnapshot(terminal.terminalState(), request.taskId(), null, PROCESS,
			terminal.message(), null, terminal.terminationCause());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}

	private void setSnapshot(TaskExecutionState state, Body body) {
		StringBuilder detail = new StringBuilder("phase=").append(phase.name()).append(step.isEmpty() ? "" : " step=" + step);
		if (ring != null) detail.append(" center=").append(describe(ring.center()));
		detail.append(" eyesPlaced=").append(eyesPlaced);
		if (target != null && phase.ordinal() <= Phase.CONFIRM.ordinal()) detail.append(" frame=").append(describe(target));
		if (body != null) detail.append(" eyes=").append(body.eyes()).append(summary(body));
		if (!lastNote.isEmpty()) detail.append(' ').append(lastNote);
		snapshot = new TaskExecutionSnapshot(state, request == null ? null : request.taskId(), null, PROCESS, detail.toString(), null, null);
	}

	@Override
	public TaskExecutionSnapshot snapshot() { return snapshot; }

	/** The reflex hands the same task back: keep the ring and the counters, only let go of the body. */
	@Override
	public void pauseForReflex() {
		if (request != null) release();
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override
	public void onWorldLeave() {
		release();
		request = null;
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override
	public void shutdown() { release(); }

	enum Phase { START, PICK, GO, USE, CONFIRM, LIGHT, ENTER_GO, PLUNGE }

	enum UseResult { USED, WORKING, NO_EYE }

	/** An end portal frame block: where it is, which way it faces and whether it holds an eye. */
	record Frame(BlockPos pos, Direction facing, boolean eye) {}

	/**
	 * A 5x5 ring of frames around the opening's centre cell: its 12 frame cells, those with no frame and those whose frame
	 * faces away from the opening.
	 */
	record Ring(BlockPos center, List<BlockPos> frames, List<BlockPos> missing, List<BlockPos> misfacing) {
		boolean complete() { return missing.isEmpty() && misfacing.isEmpty(); }
		int frameCount() { return frames.size() - missing.size() - misfacing.size(); }
		/** Frames facing the opening count most; a frame the wrong way round still says the ring is here. */
		int score() { return 2 * frameCount() + misfacing.size(); }
		List<BlockPos> opening() { return openingCells(center); }
		/** Whether a body in this cell stands in the opening's columns, over the lava or in the portal. */
		boolean overOpening(BlockPos cell) {
			return Math.abs(cell.getX() - center.getX()) <= 1 && Math.abs(cell.getZ() - center.getZ()) <= 1;
		}
	}

	/** The frame to fill next and the cell to fill it from. */
	record Choice(BlockPos frame, BlockPos cell) {}

	/** What the task reads each tick. {@code block}: the feet cell; {@code eyes}: eyes of ender carried. */
	record Body(Vec3 feet, Vec3 eye, BlockPos block, boolean onGround, float health, int food, String dimension, int eyes) {
		boolean overworld() { return OVERWORLD.equals(dimension); }
		boolean end() { return THE_END.equals(dimension); }
	}

	interface Environment {
		/** Null when no world is loaded. */
		Body observe();
		/** The end portal frame at {@code pos}, or null when there is none (or its chunk is not loaded). */
		Frame frameAt(BlockPos pos);
		/** Frames within {@code radius} blocks the eyes can see (a ray reaches a face of the frame; no X-ray). */
		List<BlockPos> visibleFrames(int radius);
		/** Whether an end portal block is at {@code pos}. */
		boolean portalAt(BlockPos pos);
		/** Solid ground under the cell, room for the body, no fluid, and no lava beside the feet or the ground under them. */
		boolean standable(BlockPos cell);
		/** Nothing to collide with and no fluid. */
		boolean open(BlockPos pos);
		/** Holds an eye of ender and uses it on the frame's top once aimed, or at once with {@code force}. */
		UseResult placeEye(BlockPos frame, boolean force);
		/** Turns toward the point and walks forward once facing it, jumping onto a frame in the way. */
		void walkToward(Vec3 target, long tick);
		/** Lets go of movement. */
		void stop();
		void release();
	}
}
