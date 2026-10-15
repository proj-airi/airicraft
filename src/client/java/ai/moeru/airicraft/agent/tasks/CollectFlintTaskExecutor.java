package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns carried gravel into flint the vanilla way, without a planner turn per block: an external driver spent about twenty
 * minutes doing it by hand through separate tool calls. Standing still, it places one gravel on solid ground right beside
 * the feet, breaks it with the best carried tool (never silk touch, which always drops gravel), waits for the drop to be
 * picked up (walking onto it only when it lands out of reach), counts the flint and repeats. A break drops flint about one
 * time in ten, otherwise the gravel comes back. Ends once the inventory holds the requested flint.
 */
public final class CollectFlintTaskExecutor implements WorldTaskExecutor {
	/** The whole task gets at least this long; each flint wanted adds {@link #TIMEOUT_TICKS_PER_FLINT}. */
	static final int MIN_TIMEOUT_TICKS = 2_400;
	/** About forty cycles of 30 ticks: four times the ten cycles one flint takes on average. */
	static final int TIMEOUT_TICKS_PER_FLINT = 1_200;
	static final int PLACE_TIMEOUT_TICKS = 40;
	/** A bare hand breaks gravel in 18 ticks. */
	static final int BREAK_TIMEOUT_TICKS = 120;
	/** A dropped item cannot be picked up for 10 ticks; one at the feet is taken right after. */
	static final int PICKUP_WAIT_TICKS = 20;
	/** No drop in sight and nothing gained for this long after the break: it is gone. */
	static final int DROP_VANISHED_TICKS = 30;
	static final int DROP_TIMEOUT_TICKS = 140;
	/** A drop that did not come to the feet is walked to only when it lies this close, horizontally. */
	static final double DROP_WALK_RADIUS = 2.5D;
	static final int MAX_PLACE_FAILURES = 4;
	static final int MAX_DROPS_LOST_IN_A_ROW = 3;
	/** Gives up on a walk before the dispatcher's 100-tick stall watchdog would end the whole task. */
	static final long NAVIGATION_STALL_TICKS = 70;
	/** Standing on soul sand, farmland, a path or a slab still counts as the block level the feet are in. */
	static final double FEET_Y_SLACK = 0.2D;
	static final double BODY_HALF_WIDTH = 0.31D;
	static final double BODY_HEIGHT = 1.8D;
	private static final int[][] HORIZONTAL = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

	private final NavigationFacade navigation;
	private final Environment environment;
	private WorldTaskRequest request;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	private Phase phase = Phase.CHOOSE;
	private int activeTicks, phaseTicks;
	private int startFlint = -1;
	private int cycles, flintDrops, dropsLost, dropsLostInARow, placeFailures;
	private int itemsAtBreak, flintAtBreak;
	private Spot spot;
	private final Set<GoalPosition> rejectedSpots = new HashSet<>();
	private GoalPosition navigationGoal;
	private boolean navigationOwned;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);
	private String lastNote = "";

	public CollectFlintTaskExecutor(NavigationFacade navigation, ai.moeru.airicraft.agent.control.CameraController camera) {
		this(navigation, new MinecraftFlintEnvironment(camera));
	}

	CollectFlintTaskExecutor(NavigationFacade navigation, Environment environment) {
		this.navigation = Objects.requireNonNull(navigation, "navigation");
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.COLLECT_FLINT) {
			if (request != null) release();
			request = null;
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest next = activeTask.get();
		if (request == null || !request.taskId().equals(next.taskId())) {
			release();
			request = next;
			reset();
		}
		if (terminal != null) return finishRelease();
		if (session == null || !session.companionActuationAllowed()) {
			release();
			setSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, "session_gate", null);
			return Optional.empty();
		}
		if (!navigation.isLoaded()) return finish(false, "navigation_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED, null);
		Site site = environment.observe();
		if (site == null) return finish(false, "world_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED, null);
		activeTicks++;
		phaseTicks++;
		if (startFlint < 0) startFlint = site.flint();
		int wanted = ((WorldTaskRequest.CollectFlint) request.task()).args().count();

		Optional<Ending> ending = ending(phase, site.flint(), site.gravel(), wanted, cycles, activeTicks);
		if (ending.isPresent()) return switch (ending.get()) {
			case SUCCEEDED -> finish(true, "flint_collected", null, site);
			case NO_GRAVEL -> finish(false, "no_gravel; collect_flint turns carried gravel into flint. Mine gravel first (a shovel is"
				+ " fastest; gravel lies on riverbeds, beaches and in caves)", TaskFailureCode.MISSING_ITEM, site);
			case GRAVEL_USED_UP -> finish(false, "gravel_used_up; every gravel became flint or was lost before reaching " + wanted
				+ " flint. Mine more gravel and call collect_flint again", TaskFailureCode.MISSING_ITEM, site);
			case TIMEOUT -> finish(false, "timeout after " + activeTicks + " ticks", TaskFailureCode.TRANSIENT, site);
		};

		Optional<TaskTerminalEvent> ended = switch (phase) {
			case CHOOSE -> choose(site);
			case PLACE -> place();
			case BREAK -> breakGravel(site);
			case COLLECT -> collect(site);
		};
		if (ended.isPresent()) return ended;
		setSnapshot(TaskExecutionState.RUNNING, "working", site);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> choose(Site site) {
		Optional<Spot> next = environment.placeSpot(site, rejectedSpots);
		if (next.isEmpty()) {
			return finish(false, "no_place_spot; no open cell right beside your feet (at foot level or one below) with solid,"
				+ " non-falling ground under it and no water or lava around it. Step onto flat open ground and call collect_flint again",
				TaskFailureCode.MISSING_FACT, site);
		}
		spot = next.get();
		enter(Phase.PLACE);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> place() {
		switch (environment.place(spot)) {
			case PLACED -> enter(Phase.BREAK);
			case NO_GRAVEL -> enter(Phase.CHOOSE);
			case BLOCKED -> rejectSpot("blocked");
			case WORKING -> {
				if (phaseTicks > PLACE_TIMEOUT_TICKS) {
					placeFailures++;
					if (placeFailures >= MAX_PLACE_FAILURES)
						return finish(false, "placement_failed; gravel did not appear at " + MAX_PLACE_FAILURES + " spots beside you."
							+ " Move to flat open ground with nothing standing next to you and call collect_flint again",
							TaskFailureCode.ENVIRONMENT_CHANGED, null);
					rejectSpot("place_timeout");
				}
			}
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> breakGravel(Site site) {
		switch (environment.breakGravel(spot.cell())) {
			case GONE -> {
				environment.stopBreaking();
				cycles++;
				itemsAtBreak = site.gravel() + site.flint();
				flintAtBreak = site.flint();
				enter(Phase.COLLECT);
			}
			case NOT_GRAVEL -> {
				environment.stopBreaking();
				rejectSpot("not_gravel");
			}
			case UNREACHABLE -> {
				environment.stopBreaking();
				rejectSpot("out_of_reach");
			}
			case WORKING -> {
				if (phaseTicks > BREAK_TIMEOUT_TICKS) {
					environment.stopBreaking();
					rejectSpot("break_timeout");
				}
			}
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> collect(Site site) {
		boolean pickedUp = site.gravel() + site.flint() > itemsAtBreak;
		Optional<Vec3> drop = pickedUp ? Optional.empty() : environment.drop(spot.cell());
		Double distance = drop.map(position -> horizontalDistance(site.feet(), position)).orElse(null);
		switch (dropStep(phaseTicks, pickedUp, distance)) {
			case DONE -> {
				if (site.flint() > flintAtBreak) flintDrops++;
				dropsLostInARow = 0;
				stopNavigation();
				enter(Phase.CHOOSE);
			}
			case WALK -> {
				GoalPosition goal = new GoalPosition((int) Math.floor(drop.get().x), (int) Math.floor(drop.get().y + FEET_Y_SLACK),
					(int) Math.floor(drop.get().z), true);
				if (!navigate(goal)) return loseDrop(site, "walk_failed");
			}
			case WAIT -> {}
			case LOST -> {
				return loseDrop(site, distance == null ? "vanished" : "out_of_reach");
			}
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> loseDrop(Site site, String why) {
		stopNavigation();
		dropsLost++;
		dropsLostInARow++;
		lastNote = "drop_lost=" + why;
		if (dropsLostInARow >= MAX_DROPS_LOST_IN_A_ROW)
			return finish(false, "drop_lost; " + MAX_DROPS_LOST_IN_A_ROW + " drops in a row were not picked up (" + why + ")."
				+ " Make room in the inventory and stand on flat ground, then call collect_flint again", TaskFailureCode.ENVIRONMENT_CHANGED, site);
		enter(Phase.CHOOSE);
		return Optional.empty();
	}

	private void rejectSpot(String why) {
		rejectedSpots.add(spot.cell());
		lastNote = "skipped_spot=" + spot.cell().x() + "," + spot.cell().y() + "," + spot.cell().z() + " " + why;
		enter(Phase.CHOOSE);
	}

	private void enter(Phase next) {
		phase = next;
		phaseTicks = 0;
	}

	/** False once the walk cannot go on. */
	private boolean navigate(GoalPosition goal) {
		if (!navigationOwned || !goal.equals(navigationGoal)) {
			stopNavigation();
			NavigationRelease.release(navigation);
			navigation.pollPathEvent();
			navigation.startNavigate(goal, NavigationOptions.WALK_ONLY);
			navigationOwned = true;
			navigationGoal = goal;
			navigationStall.clear();
		}
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
		navigationStall.clear();
	}

	// ---- Pure decisions ----

	/** Whether the task ends this tick. Success and running out of gravel are judged only between cycles. */
	static Optional<Ending> ending(Phase phase, int flint, int gravel, int wanted, int cycles, int activeTicks) {
		if (phase == Phase.CHOOSE && flint >= wanted) return Optional.of(Ending.SUCCEEDED);
		if (activeTicks > timeoutTicks(wanted)) return Optional.of(Ending.TIMEOUT);
		if (phase == Phase.CHOOSE && gravel <= 0) return Optional.of(cycles == 0 ? Ending.NO_GRAVEL : Ending.GRAVEL_USED_UP);
		return Optional.empty();
	}

	static int timeoutTicks(int wanted) {
		return Math.max(MIN_TIMEOUT_TICKS, wanted * TIMEOUT_TICKS_PER_FLINT);
	}

	/**
	 * What to do about the drop {@code phaseTicks} after the break. {@code dropDistance}: how far the nearest flint or gravel
	 * item near the broken cell lies from the feet, horizontally, or null when none is in sight.
	 */
	static DropStep dropStep(int phaseTicks, boolean pickedUp, Double dropDistance) {
		if (pickedUp) return DropStep.DONE;
		if (phaseTicks > DROP_TIMEOUT_TICKS) return DropStep.LOST;
		if (dropDistance == null) return phaseTicks > DROP_VANISHED_TICKS ? DropStep.LOST : DropStep.WAIT;
		if (phaseTicks <= PICKUP_WAIT_TICKS) return DropStep.WAIT;
		return dropDistance <= DROP_WALK_RADIUS ? DropStep.WALK : DropStep.LOST;
	}

	/**
	 * The cell to place gravel in: open, horizontally beside the body (cardinal before diagonal, nearest first) at foot level,
	 * else one below foot level with open air over it, with a solid non-falling block under it, no water or lava beside or
	 * above it, and clear of the body itself, so the body never has to move.
	 */
	static Optional<Spot> chooseSpot(Vec3 feet, Cells cells, Set<GoalPosition> rejected) {
		int fx = (int) Math.floor(feet.x);
		int fy = (int) Math.floor(feet.y + FEET_Y_SLACK);
		int fz = (int) Math.floor(feet.z);
		record Candidate(GoalPosition cell, int level, double distance) {}
		List<Candidate> candidates = new ArrayList<>();
		for (int level = 0; level < 2; level++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					double cx = fx + dx + 0.5D - feet.x;
					double cz = fz + dz + 0.5D - feet.z;
					candidates.add(new Candidate(new GoalPosition(fx + dx, fy - level, fz + dz, true), level, cx * cx + cz * cz));
				}
			}
		}
		candidates.sort(Comparator.comparingInt(Candidate::level).thenComparingDouble(Candidate::distance));
		for (Candidate candidate : candidates) {
			if (usable(feet, candidate.cell(), candidate.level() > 0, cells, rejected))
				return Optional.of(new Spot(candidate.cell(), offset(candidate.cell(), 0, -1, 0)));
		}
		return Optional.empty();
	}

	static boolean usable(Vec3 feet, GoalPosition cell, boolean sunken, Cells cells, Set<GoalPosition> rejected) {
		if (rejected.contains(cell) || overlapsBody(feet, cell) || !cells.open(cell) || !cells.support(offset(cell, 0, -1, 0))) return false;
		GoalPosition above = offset(cell, 0, 1, 0);
		if (sunken && !cells.open(above)) return false;
		if (cells.liquid(above)) return false;
		for (int[] side : HORIZONTAL) if (cells.liquid(offset(cell, side[0], 0, side[1]))) return false;
		return true;
	}

	/** Whether a block in {@code cell} would intersect the body standing at {@code feet}, which refuses the placement. */
	static boolean overlapsBody(Vec3 feet, GoalPosition cell) {
		return feet.x + BODY_HALF_WIDTH > cell.x() && feet.x - BODY_HALF_WIDTH < cell.x() + 1
			&& feet.z + BODY_HALF_WIDTH > cell.z() && feet.z - BODY_HALF_WIDTH < cell.z() + 1
			&& feet.y < cell.y() + 1 && feet.y + BODY_HEIGHT > cell.y();
	}

	/**
	 * The slot to break gravel with, or -1: never silk touch (gravel would always drop itself); then the highest fortune
	 * (more flint), the fastest, one that does not wear, the slot already held, the hotbar.
	 */
	static int chooseToolSlot(int selectedSlot, List<ToolOption> options) {
		Comparator<ToolOption> better = Comparator.comparingInt(ToolOption::fortune)
			.thenComparingDouble(ToolOption::speed)
			.thenComparing((ToolOption option) -> !option.wears())
			.thenComparing((ToolOption option) -> option.slot() == selectedSlot)
			.thenComparing((ToolOption option) -> option.slot() < 9)
			.thenComparingInt((ToolOption option) -> -option.slot());
		return options.stream().filter(option -> !option.silkTouch()).max(better).map(ToolOption::slot).orElse(-1);
	}

	static double horizontalDistance(Vec3 feet, Vec3 position) {
		double dx = position.x - feet.x, dz = position.z - feet.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static GoalPosition offset(GoalPosition position, int dx, int dy, int dz) {
		return new GoalPosition(position.x() + dx, position.y() + dy, position.z() + dz, true);
	}

	// ---- Lifecycle ----

	private void release() {
		stopNavigation();
		environment.stopBreaking();
		environment.release();
	}

	private void reset() {
		terminal = null;
		emitted = false;
		phase = Phase.CHOOSE;
		activeTicks = phaseTicks = 0;
		startFlint = -1;
		cycles = flintDrops = dropsLost = dropsLostInARow = placeFailures = 0;
		itemsAtBreak = flintAtBreak = 0;
		spot = null;
		rejectedSpots.clear();
		lastNote = "";
	}

	private String summary(Site site) {
		String counts = site == null ? "" : " flint=" + site.flint() + " made=" + Math.max(0, site.flint() - Math.max(0, startFlint))
			+ " gravelLeft=" + site.gravel();
		return counts + " cycles=" + cycles + " flintDrops=" + flintDrops + " dropsLost=" + dropsLost;
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason, TaskFailureCode failureCode, Site site) {
		release();
		terminal = new TaskTerminalEvent(request.taskId(), null, success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED,
			reason + summary(site), success ? TaskTerminationCause.GOAL_REACHED : null, success ? null : failureCode);
		return finishRelease();
	}

	private Optional<TaskTerminalEvent> finishRelease() {
		release();
		NavigationRelease.release(navigation);
		snapshot = new TaskExecutionSnapshot(terminal.terminalState(), request.taskId(), null, "CollectFlint",
			terminal.message(), null, terminal.terminationCause());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}

	private void setSnapshot(TaskExecutionState state, String reason, Site site) {
		String detail = reason + " phase=" + phase;
		if (site != null) detail += String.format(Locale.ROOT, " feet=%.1f,%.1f,%.1f", site.feet().x, site.feet().y, site.feet().z);
		if (spot != null && phase != Phase.CHOOSE) detail += " spot=" + spot.cell().x() + "," + spot.cell().y() + "," + spot.cell().z();
		detail += summary(site) + (lastNote.isEmpty() ? "" : " " + lastNote);
		snapshot = new TaskExecutionSnapshot(state, request == null ? null : request.taskId(), null, "CollectFlint", detail, null, null);
	}

	@Override
	public TaskExecutionSnapshot snapshot() { return snapshot; }

	/** The reflex hands the same task back: keep the cycles and counters; let go of breaking and route. */
	@Override
	public void pauseForReflex() {
		if (request == null) return;
		release();
		// The spot was chosen beside the feet; the body may have moved, so choose again before placing.
		if (phase == Phase.PLACE) enter(Phase.CHOOSE);
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

	enum Phase { CHOOSE, PLACE, BREAK, COLLECT }

	enum Ending { SUCCEEDED, NO_GRAVEL, GRAVEL_USED_UP, TIMEOUT }

	enum DropStep { WAIT, WALK, DONE, LOST }

	enum PlaceResult { WORKING, PLACED, BLOCKED, NO_GRAVEL }

	enum BreakResult { WORKING, GONE, NOT_GRAVEL, UNREACHABLE }

	/** What the task needs from the world each tick: where the feet are, and the gravel and flint carried (offhand included). */
	record Site(Vec3 feet, int gravel, int flint) {}

	/** The cell the gravel goes in, and the solid block under it whose top face is clicked. */
	record Spot(GoalPosition cell, GoalPosition support) {}

	/** One inventory slot as a gravel-breaking tool. {@code wears}: a damageable item. */
	record ToolOption(int slot, boolean silkTouch, int fortune, float speed, boolean wears) {}

	/** The block facts {@link #chooseSpot} reads. */
	interface Cells {
		/** Air or a replaceable plant or thin snow: no collision, no fluid, not fire, not in a preserved place. */
		boolean open(GoalPosition cell);
		/** A full block with a sturdy top that does not fall and does not open or toggle anything when clicked. */
		boolean support(GoalPosition cell);
		/** Holds water or lava. */
		boolean liquid(GoalPosition cell);
	}

	interface Environment {
		/** Null when no world is loaded. */
		Site observe();
		Optional<Spot> placeSpot(Site site, Set<GoalPosition> rejected);
		/** Holds gravel, looks at the support's top face and places; PLACED once the cell holds gravel. */
		PlaceResult place(Spot spot);
		/** Breaks the gravel in {@code cell} with the best non-silk-touch tool; GONE once the cell is empty. */
		BreakResult breakGravel(GoalPosition cell);
		/** Where the nearest dropped flint or gravel item near {@code cell} lies. */
		Optional<Vec3> drop(GoalPosition cell);
		void stopBreaking();
		void release();
	}
}
