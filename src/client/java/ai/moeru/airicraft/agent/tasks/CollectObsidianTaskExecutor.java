package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Makes and mines obsidian at nearby lava without waiting on the planner, whose hand-driven attempts died in the pool.
 * Every step starts from solid ground with no lava beside it, and the player never steps into the pool. In order:
 * mine an obsidian block nothing can flow into, with solid ground under its drop, then walk over the drop;
 * otherwise pour the water bucket where it turns the most lava into obsidian and scoop the water back before it spreads;
 * otherwise break an obsidian block that only covers lava, giving up its drop so the lava under it can be cast in turn.
 * Ends once the inventory holds the requested count.
 */
public final class CollectObsidianTaskExecutor implements WorldTaskExecutor {
	static final int TIMEOUT_TICKS = 24_000;
	/** No obsidian gained, cast or opened for this long: the site is not working. */
	static final int NO_PROGRESS_TICKS = 3_000;
	static final int NAVIGATION_TIMEOUT_TICKS = 600;
	/** A diamond pickaxe takes 188 ticks on obsidian. */
	static final int BREAK_TIMEOUT_TICKS = 400;
	static final int POUR_TIMEOUT_TICKS = 40;
	/** Water starts flowing 5 ticks after it lands; the scoop has to come first. */
	static final int SCOOP_TIMEOUT_TICKS = 30;
	static final int DROP_SPAWN_TICKS = 10;
	static final int DROP_COLLECT_TICKS = 200;
	static final float LOW_HEALTH = 8.0F;
	/** Gives up on a route before the dispatcher's 100-tick stall watchdog would end the whole task. */
	static final long NAVIGATION_STALL_TICKS = 70;

	private final NavigationFacade navigation;
	private final Environment environment;
	private WorldTaskRequest request;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	private Phase phase = Phase.CHOOSE;
	private int activeTicks, phaseTicks, lastProgressTick;
	private int startObsidian = -1, mostObsidian;
	private int mined, casts, opened, dropsLost;
	private Mine mine;
	private Cast cast;
	private GoalPosition pouredAt;
	private int obsidianBeforeBreak;
	private final Set<GoalPosition> rejectedMines = new HashSet<>();
	private final Set<GoalPosition> rejectedCasts = new HashSet<>();
	private GoalPosition navigationGoal;
	private boolean navigationOwned;
	private boolean navigationArrived;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);
	private String lastNote = "";

	public CollectObsidianTaskExecutor(NavigationFacade navigation, ai.moeru.airicraft.agent.control.CameraController camera) {
		this(navigation, new MinecraftObsidianEnvironment(camera));
	}

	CollectObsidianTaskExecutor(NavigationFacade navigation, Environment environment) {
		this.navigation = Objects.requireNonNull(navigation, "navigation");
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.COLLECT_OBSIDIAN) {
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
		if (!navigation.isLoaded()) return finish(false, "navigation_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED);
		Site site = environment.observe();
		if (site == null) return finish(false, "world_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED);
		activeTicks++;
		phaseTicks++;
		if (startObsidian < 0) startObsidian = mostObsidian = site.obsidian();
		if (site.obsidian() > mostObsidian) {
			mostObsidian = site.obsidian();
			lastProgressTick = activeTicks;
		}
		// The scoop comes before every exit (its timeout bounds it), so finishing never leaves the poured water behind.
		if (phase == Phase.SCOOP) {
			scoop();
			setSnapshot(TaskExecutionState.RUNNING, "working", site);
			return Optional.empty();
		}
		int wanted = ((WorldTaskRequest.CollectObsidian) request.task()).args().count();
		if (site.obsidian() >= wanted) return finish(true, "collected obsidian=" + site.obsidian(), null);
		if (!site.pickaxe())
			return finish(false, "needs_diamond_pickaxe; obsidian drops only for a diamond or netherite pickaxe", TaskFailureCode.MISSING_ITEM);
		if (site.health() <= LOW_HEALTH)
			return finish(false, "low_health health=" + site.health() + "; eat and let health recover, then call collect_obsidian again",
				TaskFailureCode.ENVIRONMENT_CHANGED);
		if (activeTicks > TIMEOUT_TICKS) return finish(false, "timeout", TaskFailureCode.TRANSIENT);
		if (activeTicks - lastProgressTick > NO_PROGRESS_TICKS)
			return finish(false, "no_progress; nothing cast, opened or mined for " + NO_PROGRESS_TICKS + " ticks", TaskFailureCode.ENVIRONMENT_CHANGED);

		Optional<TaskTerminalEvent> ended = switch (phase) {
			case CHOOSE -> choose(site);
			case MINE_APPROACH -> approach(site, mine.stance(), Phase.MINE_BREAK, () -> rejectMine("stance_unreachable"));
			case MINE_BREAK -> breakTarget(site);
			case COLLECT_DROP -> collectDrop(site);
			case CAST_APPROACH -> approach(site, cast.stance(), Phase.POUR, () -> rejectCast("stance_unreachable"));
			case POUR -> pour();
			case SCOOP -> Optional.empty();
		};
		if (ended.isPresent()) return ended;
		setSnapshot(TaskExecutionState.RUNNING, "working", site);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> choose(Site site) {
		Optional<Mine> nextMine = environment.mineTarget(site, rejectedMines, false);
		if (nextMine.isPresent()) {
			mine = nextMine.get();
			enter(Phase.MINE_APPROACH);
			return Optional.empty();
		}
		if (!site.waterBucket()) {
			String detail = site.emptyBucket()
				? "no_water_bucket; fill the empty bucket at water (use_block on a water source) and call collect_obsidian again"
				: "no_water_bucket; casting obsidian needs a water bucket (3 iron ingots make a bucket; fill it at water)";
			return finish(false, detail + summary(), TaskFailureCode.MISSING_ITEM);
		}
		Optional<Cast> nextCast = environment.castTarget(site, rejectedCasts);
		if (nextCast.isPresent()) {
			cast = nextCast.get();
			enter(Phase.CAST_APPROACH);
			return Optional.empty();
		}
		// Only obsidian lying on lava is left: break one and lose its drop, so the lava under it can be cast.
		Optional<Mine> opening = environment.mineTarget(site, rejectedMines, true);
		if (opening.isPresent()) {
			mine = opening.get();
			enter(Phase.MINE_APPROACH);
			return Optional.empty();
		}
		boolean worked = mined + casts + opened > 0;
		return finish(false, (worked ? "no_more_safe_lava; no lava left here that can be cast from solid ground"
			: "no_lava_nearby; no lava source within " + Environment.SEARCH_RADIUS + " blocks can be reached from solid ground. Lava is"
				+ " mostly underground: pools in caves, and lava lakes near y=-55. Get within about 8 blocks of one, then call again")
			+ summary(), TaskFailureCode.MISSING_FACT);
	}

	private Optional<TaskTerminalEvent> approach(Site site, GoalPosition stance, Phase arrived, Runnable reject) {
		if (atStance(site.feet(), stance) || navigationArrived && navigationOwned) {
			stopNavigation();
			enter(arrived);
			return Optional.empty();
		}
		if (!navigate(stance) || phaseTicks > NAVIGATION_TIMEOUT_TICKS) {
			stopNavigation();
			reject.run();
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> breakTarget(Site site) {
		if (!environment.stillMineable(mine)) {
			environment.stopBreaking();
			rejectMine("changed");
			return Optional.empty();
		}
		if (phaseTicks == 1) obsidianBeforeBreak = site.obsidian();
		switch (environment.breakBlock(mine.block())) {
			case GONE -> {
				environment.stopBreaking();
				if (mine.coversLava()) {
					opened++;
					lastProgressTick = activeTicks;
					enter(Phase.CHOOSE);
				} else {
					mined++;
					lastProgressTick = activeTicks;
					enter(Phase.COLLECT_DROP);
				}
			}
			case NO_TOOL -> {
				return finish(false, "needs_diamond_pickaxe; obsidian drops only for a diamond or netherite pickaxe", TaskFailureCode.MISSING_ITEM);
			}
			case UNREACHABLE -> rejectMine("out_of_reach");
			case WORKING -> {
				if (phaseTicks > BREAK_TIMEOUT_TICKS) {
					environment.stopBreaking();
					rejectMine("break_timeout");
				}
			}
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> collectDrop(Site site) {
		if (site.obsidian() > obsidianBeforeBreak) {
			stopNavigation();
			enter(Phase.CHOOSE);
			return Optional.empty();
		}
		Optional<GoalPosition> drop = environment.obsidianDrop(mine.block());
		if (drop.isEmpty()) {
			if (phaseTicks > DROP_SPAWN_TICKS) {
				dropsLost++;
				stopNavigation();
				enter(Phase.CHOOSE);
			}
			return Optional.empty();
		}
		if (!navigate(drop.get()) || phaseTicks > DROP_COLLECT_TICKS) {
			dropsLost++;
			stopNavigation();
			enter(Phase.CHOOSE);
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> pour() {
		switch (environment.pour(cast)) {
			case POURED -> {
				casts++;
				lastProgressTick = activeTicks;
				pouredAt = cast.pour();
				enter(Phase.SCOOP);
			}
			case NOT_LAVA -> enter(Phase.CHOOSE);
			case NO_WATER_BUCKET -> enter(Phase.CHOOSE);
			case NO_SHOT -> rejectCast("no_line_of_sight");
			case WORKING -> {
				if (phaseTicks > POUR_TIMEOUT_TICKS) rejectCast("pour_timeout");
			}
		}
		return Optional.empty();
	}

	private void scoop() {
		switch (environment.scoop(pouredAt)) {
			case SCOOPED -> enter(Phase.CHOOSE);
			case NO_WATER, NO_BUCKET -> {
				lastNote = "water_left_at=" + pouredAt.x() + "," + pouredAt.y() + "," + pouredAt.z();
				enter(Phase.CHOOSE);
			}
			case NO_SHOT, WORKING -> {
				if (phaseTicks > SCOOP_TIMEOUT_TICKS) {
					lastNote = "water_left_at=" + pouredAt.x() + "," + pouredAt.y() + "," + pouredAt.z();
					enter(Phase.CHOOSE);
				}
			}
		}
	}

	private void rejectMine(String why) {
		rejectedMines.add(mine.block());
		lastNote = "skipped_obsidian=" + mine.block().x() + "," + mine.block().y() + "," + mine.block().z() + " " + why;
		enter(Phase.CHOOSE);
	}

	private void rejectCast(String why) {
		rejectedCasts.add(cast.pour());
		lastNote = "skipped_lava=" + cast.pour().x() + "," + cast.pour().y() + "," + cast.pour().z() + " " + why;
		enter(Phase.CHOOSE);
	}

	private void enter(Phase next) {
		phase = next;
		phaseTicks = 0;
	}

	private boolean navigate(GoalPosition goal) {
		if (!navigationOwned || !goal.equals(navigationGoal)) {
			stopNavigation();
			NavigationRelease.release(navigation);
			navigation.pollPathEvent();
			navigation.startNavigate(goal, NavigationOptions.DEFAULT);
			navigationOwned = true;
			navigationGoal = goal;
			navigationArrived = false;
			navigationStall.clear();
		}
		String event = navigation.pollPathEvent().orElse("");
		if (event.equals("AT_GOAL")) navigationArrived = true;
		var progress = navigation.navigationProgress();
		if (progress.isEmpty()) navigationStall.clear();
		else if (navigationStall.observe(activeTicks, progress.get())) return false;
		return !event.contains("CALC_FAILED") && !event.equals("CANCELED");
	}

	private void stopNavigation() {
		if (navigationOwned) navigation.cancel();
		navigationOwned = false;
		navigationArrived = false;
		navigationGoal = null;
		navigationStall.clear();
	}

	static boolean atStance(Vec3 feet, GoalPosition stance) {
		return Math.floor(feet.x) == stance.x() && Math.floor(feet.z) == stance.z() && Math.abs(feet.y - stance.y()) < 0.6D;
	}

	private void release() {
		stopNavigation();
		environment.stopBreaking();
		environment.release();
	}

	private void reset() {
		terminal = null;
		emitted = false;
		phase = Phase.CHOOSE;
		activeTicks = phaseTicks = lastProgressTick = 0;
		startObsidian = -1;
		mostObsidian = 0;
		mined = casts = opened = dropsLost = 0;
		mine = null;
		cast = null;
		pouredAt = null;
		rejectedMines.clear();
		rejectedCasts.clear();
		lastNote = "";
	}

	private String summary() {
		return " mined=" + mined + " casts=" + casts + " opened=" + opened + " dropsLost=" + dropsLost;
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason, TaskFailureCode failureCode) {
		release();
		terminal = new TaskTerminalEvent(request.taskId(), null, success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED,
			reason + (reason.contains(" mined=") ? "" : summary()), success ? TaskTerminationCause.GOAL_REACHED : null, success ? null : failureCode);
		return finishRelease();
	}

	private Optional<TaskTerminalEvent> finishRelease() {
		release();
		NavigationRelease.release(navigation);
		snapshot = new TaskExecutionSnapshot(terminal.terminalState(), request.taskId(), null, "CollectObsidian",
			terminal.message(), null, terminal.terminationCause());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}

	private void setSnapshot(TaskExecutionState state, String reason, Site site) {
		String detail = reason + " phase=" + phase;
		if (site != null) detail += String.format(Locale.ROOT, " feet=%.1f,%.1f,%.1f health=%.1f obsidian=%d", site.feet().x,
			site.feet().y, site.feet().z, site.health(), site.obsidian());
		if (phase == Phase.MINE_APPROACH || phase == Phase.MINE_BREAK || phase == Phase.COLLECT_DROP)
			detail += " target=" + mine.block().x() + "," + mine.block().y() + "," + mine.block().z() + (mine.coversLava() ? " opening" : "");
		if (phase == Phase.CAST_APPROACH || phase == Phase.POUR || phase == Phase.SCOOP)
			detail += " pour=" + cast.pour().x() + "," + cast.pour().y() + "," + cast.pour().z() + " expected=" + cast.lavaTurned();
		detail += summary() + (lastNote.isEmpty() ? "" : " " + lastNote);
		snapshot = new TaskExecutionSnapshot(state, request == null ? null : request.taskId(), null, "CollectObsidian", detail, null, null);
	}

	@Override
	public TaskExecutionSnapshot snapshot() { return snapshot; }

	/** The reflex hands the same task back: keep the target, rejected targets and the counters; let go of breaking and route. */
	@Override
	public void pauseForReflex() {
		if (request == null) return;
		release();
		// The body may have been moved off its stance: walk back to it before breaking or pouring, instead of rejecting a good target.
		if (phase == Phase.MINE_BREAK) enter(Phase.MINE_APPROACH);
		else if (phase == Phase.POUR) enter(Phase.CAST_APPROACH);
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

	enum Phase { CHOOSE, MINE_APPROACH, MINE_BREAK, COLLECT_DROP, CAST_APPROACH, POUR, SCOOP }

	enum BreakResult { WORKING, GONE, NO_TOOL, UNREACHABLE }

	enum PourResult { WORKING, POURED, NOT_LAVA, NO_WATER_BUCKET, NO_SHOT }

	enum ScoopResult { WORKING, SCOOPED, NO_WATER, NO_BUCKET, NO_SHOT }

	/** What the task needs from the world each tick. */
	record Site(Vec3 feet, float health, int obsidian, boolean waterBucket, boolean emptyBucket, boolean pickaxe) {}

	/**
	 * An obsidian block and the solid ground to mine it from. {@code coversLava}: it lies on lava, so its drop is lost and
	 * breaking it only opens that lava to be cast.
	 */
	record Mine(GoalPosition block, GoalPosition stance, boolean coversLava) {}

	/**
	 * Where to pour water ({@code pour}: a lava source or open cell beside or above lava), the solid face to click so the
	 * bucket lands there, and where to stand. {@code lavaTurned} lava sources around it become obsidian.
	 */
	record Cast(GoalPosition pour, GoalPosition support, String supportFace, GoalPosition stance, int lavaTurned) {}

	interface Environment {
		int SEARCH_RADIUS = 12;

		/** Null when no world is loaded. */
		Site observe();
		/**
		 * The nearest obsidian to mine from safe ground. Without {@code opening}: nothing can flow into its cell and its drop
		 * lands on solid ground. With it: obsidian whose only lava is the source under it.
		 */
		Optional<Mine> mineTarget(Site site, Set<GoalPosition> rejected, boolean opening);
		/** Whether the target is still obsidian and still as safe as when it was chosen. */
		boolean stillMineable(Mine mine);
		Optional<Cast> castTarget(Site site, Set<GoalPosition> rejected);
		BreakResult breakBlock(GoalPosition block);
		PourResult pour(Cast cast);
		ScoopResult scoop(GoalPosition water);
		/** Where a dropped obsidian item near the mined block lies. */
		Optional<GoalPosition> obsidianDrop(GoalPosition near);
		void stopBreaking();
		void release();
	}
}
