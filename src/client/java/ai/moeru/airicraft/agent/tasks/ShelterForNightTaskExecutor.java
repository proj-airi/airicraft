package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.reflex.EmergencyBunker;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Waits out the night on the overworld surface in a dug shelter, as one task. Most random-seed playtest deaths were on the
 * surface at night, and digging a pit, stepping in and roofing it through separate tool calls left the body outside: the
 * placement tool walked it back out of the pit to a stand position before placing the roof.
 *
 * <p>The shelter is the survival reflex's {@link EmergencyBunker}: three blocks straight down from the feet, solid walls
 * around the two cells the body ends in, nothing liquid beside it, and a carried non-falling block over the head. When the
 * column under the feet does not fit, the eight neighbouring columns one and two blocks away are tried, walking there
 * first (walk only). Inside, the body waits; the automatic idle eating still runs (see {@link #waitingInShelter}), and a
 * mob that gets into the shaft is the survival reflex's to fight. Once it is morning (or {@code maxWaitTicks} have passed)
 * and no hostile mob stands within {@link #HOSTILE_CLEAR_RADIUS} blocks of the shaft top, it breaks the roof and climbs
 * out: it pillars up on carried blocks, filling the shaft back in, or digs a staircase into one wall.
 */
public final class ShelterForNightTaskExecutor implements WorldTaskExecutor {
	static final long DAY_LENGTH = 24_000L;
	/**
	 * Morning: the sun rises at 23,000 and zombies and skeletons burn once it is up; a few hundred ticks after 0 the
	 * light is full and hostile mobs no longer spawn on the surface. From here to {@link #DUSK_FROM} the task does not dig.
	 */
	static final long MORNING_FROM = 300L;
	/**
	 * Dusk: sunset starts at 12,000 and hostile mobs spawn on the surface from about 13,000. Digging takes a few seconds,
	 * so from here on (and through the night until {@link #MORNING_FROM}) a call shelters instead of answering already_day.
	 */
	static final long DUSK_FROM = 11_000L;
	/** A hostile mob this close to the shaft top keeps the body inside even in the morning. */
	static final double HOSTILE_CLEAR_RADIUS = 6.0D;
	/** A mob that stays by the shaft top this long after leaving is due no longer holds the body in: the reflex fights it. */
	static final int MOB_LINGER_TICKS = 1_200;
	/** Slowest block the dig accepts with the best carried tool: sand or dirt by hand is 15, stone with a wooden pickaxe 23. */
	static final int MAX_BREAK_TICKS = 100;
	static final int SITE_RADIUS = 2;
	static final int GO_TIMEOUT_TICKS = 300;
	static final int DIG_TIMEOUT_TICKS = 600;
	static final int ROOF_BREAK_TIMEOUT_TICKS = 200;
	/** After the roof breaks, its drop falls onto the body; wait this long for the pickup before counting blocks. */
	static final int ROOF_PICKUP_TICKS = 15;
	/** No height gained and nothing placed or broken for this long: the climb is stuck. */
	static final int EXIT_STUCK_TICKS = 200;
	/** Times the body may be knocked or pulled out of its column and walk back before the task gives up. */
	static final int MAX_REENTRIES = 3;
	/** Gives up on a walk before the dispatcher's 100-tick stall watchdog would end the whole task. */
	static final long NAVIGATION_STALL_TICKS = 70;
	/** Time for walking to a site, digging, roofing and climbing out, on top of the wait. */
	static final int WORK_BUDGET_TICKS = 3_000;

	private final NavigationFacade navigation;
	private final Environment environment;
	private WorldTaskRequest request;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	private Phase phase = Phase.START;
	private int activeTicks, phaseTicks, waitedTicks, dueTicks, stuckTicks, reentries, roofGoneTicks;
	private EmergencyBunker.Plan plan;
	private final Set<BlockPos> rejectedSites = new HashSet<>();
	private Leave leaveReason = Leave.STAY;
	private int bestExitY, exitProgress;
	private boolean pillarTried;
	private BlockPos stairOrigin;
	private Direction stairDirection;
	private String step = "";
	private String lastNote = "";
	private GoalPosition navigationGoal;
	private boolean navigationOwned;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);

	public ShelterForNightTaskExecutor(NavigationFacade navigation, ai.moeru.airicraft.agent.control.CameraController camera) {
		this(navigation, new MinecraftShelterEnvironment(camera));
	}

	ShelterForNightTaskExecutor(NavigationFacade navigation, Environment environment) {
		this.navigation = Objects.requireNonNull(navigation, "navigation");
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	/**
	 * Whether the task is waiting in a closed shelter, with the hands free: the automatic idle eating and {@code eat_food}
	 * may run without stopping it.
	 */
	public static boolean waitingInShelter(TaskExecutionSnapshot execution) {
		return execution != null && execution.state() == TaskExecutionState.RUNNING && PROCESS.equals(execution.processName())
			&& execution.lastPathEvent() != null && execution.lastPathEvent().startsWith("phase=WAIT ");
	}

	private static final String PROCESS = "ShelterForNight";

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.SHELTER_FOR_NIGHT) {
			// A reflex pause hands the same task back afterwards: keep the shelter and the counters, only let go of the body.
			if (request != null) release();
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
			setSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, null);
			return Optional.empty();
		}
		if (!navigation.isLoaded()) return finish(false, "navigation_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED, null);
		Body body = environment.observe();
		if (body == null) return finish(false, "world_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED, null);
		activeTicks++;
		phaseTicks++;
		int maxWait = ((WorldTaskRequest.ShelterForNight) request.task()).args().maxWaitTicks();

		if (phase == Phase.START) {
			if (!body.overworld())
				return finish(false, "wrong_dimension; shelter_for_night waits out the overworld night. In " + body.dimension()
					+ " there is no night to wait out", TaskFailureCode.INVALID_ACTION, body);
			if (!shelterTime(body.dayTime()))
				return finish(true, "already_day dayTime=" + Math.floorMod(body.dayTime(), DAY_LENGTH) + " duskIn="
					+ (DUSK_FROM - Math.floorMod(body.dayTime(), DAY_LENGTH)) + "; nothing dug. Call shelter_for_night again at dusk"
					+ " (day time " + DUSK_FROM + " or later)", null, body);
			enter(Phase.SITE);
		}
		if (activeTicks > maxWait + MOB_LINGER_TICKS + WORK_BUDGET_TICKS)
			return finish(false, "timeout after " + activeTicks + " ticks", TaskFailureCode.TRANSIENT, body);

		Optional<TaskTerminalEvent> ended = switch (phase) {
			case START, SITE -> site(body);
			case GO -> go(body);
			case DIG -> dig(body);
			case WAIT -> waitInside(body, maxWait);
			case EXIT_ROOF -> exitRoof(body);
			case EXIT_PILLAR -> exitPillar(body);
			case EXIT_STAIRS -> exitStairs(body);
		};
		if (ended.isPresent()) return ended;
		setSnapshot(TaskExecutionState.RUNNING, body);
		return Optional.empty();
	}

	// ---- Phases ----

	private Optional<TaskTerminalEvent> site(Body body) {
		EmergencyBunker.Plan enclosed = environment.enclosedShelter(body.block());
		if (enclosed != null && body.onGround()) {
			// Already shut in (a resumed task, or the reflex's bunker): wait here.
			plan = enclosed;
			lastNote = "already_enclosed";
			enter(Phase.WAIT);
			return Optional.empty();
		}
		SiteChoice choice = chooseSite(body.block(), body.buildingBlocks(), environment::siteFacts, rejectedSites);
		if (choice.plan() == null) {
			if (NO_ROOF_BLOCK.equals(choice.failure()))
				return finish(false, "no_roof_block; a shelter needs one block that does not fall to roof it (dirt, cobblestone,"
					+ " stone, planks or logs), and digging here would not yield one. Carry a few such blocks and call"
					+ " shelter_for_night again", TaskFailureCode.MISSING_ITEM, body);
			return finish(false, "no_shelter_site; no column under or within " + SITE_RADIUS + " blocks of you can be dug three"
				+ " deep with solid walls, solid floor, no water, lava, sand or gravel, and blocks the carried tools break fast"
				+ (rejectedSites.isEmpty() ? "" : " (unreachable: " + rejectedSites.size() + ")")
				+ ". Walk onto solid dirt or stone ground and call again", TaskFailureCode.MISSING_FACT, body);
		}
		plan = choice.plan();
		enter(inColumnAtTop(body, plan) ? Phase.DIG : Phase.GO);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> go(Body body) {
		BlockPos top = plan.top();
		if (inColumnAtTop(body, plan) && body.onGround()) {
			stopNavigation();
			enter(Phase.DIG);
			return Optional.empty();
		}
		boolean beside = body.block().getY() == top.getY() && Math.abs(body.block().getX() - top.getX()) <= 1
			&& Math.abs(body.block().getZ() - top.getZ()) <= 1;
		if (phaseTicks > GO_TIMEOUT_TICKS) return rejectSite("walk_timeout");
		if (beside && (!navigationOwned || !navigation.processActive())) {
			// Next to the column (or arrived with momentum one cell over): step onto it.
			stopNavigation();
			environment.approach(top);
			step = "approach";
			return Optional.empty();
		}
		step = "walk";
		if (!navigate(new GoalPosition(top.getX(), top.getY(), top.getZ(), true))) return rejectSite("unreachable");
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> rejectSite(String why) {
		stopNavigation();
		environment.stop();
		rejectedSites.add(plan.top());
		lastNote = "skipped_site=" + plan.top().toShortString() + " " + why;
		plan = null;
		enter(Phase.SITE);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> dig(Body body) {
		if (phaseTicks > DIG_TIMEOUT_TICKS)
			return finish(false, "dig_failed; the shelter at " + plan.top().toShortString() + " was not closed within "
				+ DIG_TIMEOUT_TICKS + " ticks (" + step + ")", TaskFailureCode.TRANSIENT, body);
		DigTick result = environment.dig(plan);
		step = result.name().toLowerCase(Locale.ROOT);
		switch (result) {
			case CLOSED -> {
				environment.stop();
				enter(Phase.WAIT);
			}
			case DIGGING, ROOFING, FALLING, ALIGNING -> {}
			case LEFT_COLUMN -> {
				return reenter(body, "left_column");
			}
			case NO_ROOF_BLOCK -> {
				return finish(false, "no_roof_block; the shaft at " + plan.top().toShortString() + " is dug but no block that does"
					+ " not fall is left to roof it. Carry dirt, cobblestone or planks and call shelter_for_night again",
					TaskFailureCode.MISSING_ITEM, body);
			}
			case OFF_PLAN -> {
				return finish(false, "dig_failed; the body is not over the planned shaft at " + plan.top().toShortString(),
					TaskFailureCode.ENVIRONMENT_CHANGED, body);
			}
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> reenter(Body body, String why) {
		environment.stop();
		reentries++;
		lastNote = why + " reentries=" + reentries;
		if (reentries > MAX_REENTRIES)
			return finish(false, "dig_failed; knocked out of the shelter column at " + plan.top().toShortString() + " "
				+ reentries + " times", TaskFailureCode.ENVIRONMENT_CHANGED, body);
		enter(Phase.GO);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> waitInside(Body body, int maxWait) {
		if (!inShaft(body, plan)) {
			EmergencyBunker.Plan enclosed = environment.enclosedShelter(body.block());
			if (enclosed == null || !body.onGround()) return reenter(body, "left_shelter");
			// Back from a reflex pause shut in somewhere else (the reflex's own bunker): wait there rather than walk out.
			plan = enclosed;
			lastNote = "moved_to_enclosed=" + enclosed.top().toShortString();
		}
		if (!environment.roofClosed(plan)) {
			// The roof went (an enderman takes dirt; a creeper blast): put it back.
			lastNote = "roof_missing";
			enter(Phase.DIG);
			return Optional.empty();
		}
		waitedTicks++;
		step = environment.intruded(plan) ? "intruder_left_to_reflex" : "waiting";
		boolean due = !shelterTime(body.dayTime()) || waitedTicks >= maxWait;
		dueTicks = due ? dueTicks + 1 : 0;
		boolean hostile = due && environment.hostileNear(plan.top(), HOSTILE_CLEAR_RADIUS);
		if (hostile) step = "mob_at_top";
		Leave decision = leave(body.dayTime(), waitedTicks, maxWait, hostile, dueTicks);
		if (decision == Leave.STAY) return Optional.empty();
		leaveReason = decision;
		lastNote = hostile ? "left_with_mob_near" : "";
		enter(Phase.EXIT_ROOF);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> exitRoof(Body body) {
		if (roofGoneTicks > 0) {
			// Let the roof's drop fall and be picked up before counting blocks to pillar with.
			if (++roofGoneTicks <= ROOF_PICKUP_TICKS) return Optional.empty();
			startClimb(body, exitMode(body.buildingBlocks(), plan.top().getY() - body.block().getY()));
			return Optional.empty();
		}
		BreakTick result = environment.breakRoof(plan);
		step = "break_roof_" + result.name().toLowerCase(Locale.ROOT);
		if (result == BreakTick.GONE) {
			environment.stop();
			roofGoneTicks = 1;
			return Optional.empty();
		}
		if (result == BreakTick.FAILED || phaseTicks > ROOF_BREAK_TIMEOUT_TICKS)
			return exitFailed(body, "the roof at " + plan.roof().toShortString() + " did not break");
		return Optional.empty();
	}

	private void startClimb(Body body, ExitMode mode) {
		bestExitY = body.block().getY();
		stuckTicks = 0;
		exitProgress = 0;
		if (mode == ExitMode.PILLAR && !pillarTried) {
			pillarTried = true;
			enter(Phase.EXIT_PILLAR);
			return;
		}
		stairOrigin = body.block();
		stairDirection = environment.stairDirection(stairOrigin, plan.top().getY()).orElse(null);
		enter(Phase.EXIT_STAIRS);
	}

	private Optional<TaskTerminalEvent> exitPillar(Body body) {
		ClimbTick result = environment.pillar(plan);
		step = "pillar_" + result.state().name().toLowerCase(Locale.ROOT);
		if (result.state() == ClimbState.OUT) return succeed(body, "pillar");
		if (result.state() == ClimbState.BLOCKED) {
			// Out of blocks or nowhere to place: dig the rest of the way.
			lastNote = "pillar_stopped " + result.note();
			environment.stop();
			startClimb(body, ExitMode.STAIRS);
			return Optional.empty();
		}
		if (stuck(body, result)) {
			// At the top but never seen on the ground (a live exit ended exit_failed "pillar_stuck falling" at the
			// shaft top): the body is out, whatever it is standing in.
			if (atTop(body.block().getY(), plan.top().getY())) return succeed(body, "pillar");
			lastNote = "pillar_stuck " + result.note();
			environment.stop();
			startClimb(body, ExitMode.STAIRS);
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> exitStairs(Body body) {
		if (stairDirection == null && atTop(body.block().getY(), plan.top().getY())) return succeed(body, "pillar");
		if (stairDirection == null)
			return exitFailed(body, "no wall to dig a staircase into from " + stairOrigin.toShortString()
				+ " (water, lava, falling blocks or unbreakable blocks on every side)" + (lastNote.isEmpty() ? "" : "; " + lastNote));
		ClimbTick result = environment.stairs(stairOrigin, stairDirection, plan.top().getY());
		step = "stairs_" + stairDirection.getSerializedName() + "_" + result.state().name().toLowerCase(Locale.ROOT);
		if (result.state() == ClimbState.OUT)
			return succeed(body, pillarTried && plan.top().getY() - stairOrigin.getY() <= 1 ? "pillar" : "stairs");
		if (result.state() == ClimbState.BLOCKED) return exitFailed(body, "the staircase is blocked: " + result.note());
		if (stuck(body, result)) {
			if (atTop(body.block().getY(), plan.top().getY())) return succeed(body, "stairs");
			return exitFailed(body, "no progress on the staircase for " + EXIT_STUCK_TICKS + " ticks: " + result.note());
		}
		return Optional.empty();
	}

	/** Whether the feet are at or above the shaft top, so the body is out of the shelter. */
	static boolean atTop(int feetY, int topY) {
		return feetY >= topY;
	}

	/** True once the climb has gained no height and placed or broken nothing for {@link #EXIT_STUCK_TICKS}. */
	private boolean stuck(Body body, ClimbTick result) {
		if (body.block().getY() > bestExitY || result.progress() > exitProgress) {
			bestExitY = Math.max(bestExitY, body.block().getY());
			exitProgress = Math.max(exitProgress, result.progress());
			stuckTicks = 0;
			return false;
		}
		return ++stuckTicks > EXIT_STUCK_TICKS;
	}

	private Optional<TaskTerminalEvent> exitFailed(Body body, String why) {
		return finish(false, "exit_failed; " + why + ". Break out by hand: break the block over your head, then pillar up or"
			+ " dig steps (navigate_to the surface next to " + plan.top().toShortString() + ")", TaskFailureCode.ENVIRONMENT_CHANGED, body);
	}

	private Optional<TaskTerminalEvent> succeed(Body body, String exit) {
		String head = leaveReason == Leave.MAX_WAIT ? "sheltered_until_max_wait" : "sheltered_until_morning";
		return finish(true, head + " waited=" + waitedTicks + " slept=false exit=" + exit, null, body);
	}

	private void enter(Phase next) {
		phase = next;
		phaseTicks = 0;
		step = "";
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

	/** Whether {@code dayTime} is dusk or night, when the body should be in a shelter: [11,000, 24,000) and [0, 300). */
	static boolean shelterTime(long dayTime) {
		long time = Math.floorMod(dayTime, DAY_LENGTH);
		return time >= DUSK_FROM || time < MORNING_FROM;
	}

	/**
	 * Whether to leave the shelter now. Leaving is due in the morning, or once {@code maxWait} ticks were spent inside;
	 * a hostile mob by the shaft top holds the body in until it has stayed {@link #MOB_LINGER_TICKS} past that.
	 */
	static Leave leave(long dayTime, int waited, int maxWait, boolean hostileNearTop, int dueTicks) {
		Leave due = !shelterTime(dayTime) ? Leave.MORNING : waited >= maxWait ? Leave.MAX_WAIT : Leave.STAY;
		if (due == Leave.STAY) return Leave.STAY;
		if (hostileNearTop && dueTicks < MOB_LINGER_TICKS) return Leave.STAY;
		return due;
	}

	/**
	 * The columns to try, by where the body would stand to start: the feet first, then the eight directions one block
	 * away and then two (cardinal before diagonal), each at the feet's level, one below and one above.
	 */
	static List<BlockPos> siteCandidates(BlockPos feet) {
		List<BlockPos> candidates = new ArrayList<>();
		candidates.add(feet);
		int[][] directions = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}, {1, -1}, {1, 1}, {-1, 1}, {-1, -1}};
		for (int distance = 1; distance <= SITE_RADIUS; distance++) {
			for (int[] direction : directions) {
				for (int dy : new int[] {0, -1, 1}) candidates.add(feet.offset(direction[0] * distance, dy, direction[1] * distance));
			}
		}
		return candidates;
	}

	/**
	 * The first candidate column a shelter fits in that can be roofed: with a carried roof block any fitting column does,
	 * otherwise only one whose dug blocks yield one. A column other than the feet' own must be standable to walk onto.
	 */
	static SiteChoice chooseSite(BlockPos feet, int carriedRoofBlocks, Function<BlockPos, SiteFacts> facts, Set<BlockPos> rejected) {
		boolean fits = false;
		for (BlockPos top : siteCandidates(feet)) {
			if (rejected.contains(top)) continue;
			SiteFacts site = facts.apply(top);
			if (site == null || site.plan() == null || !top.equals(feet) && !site.standable()) continue;
			fits = true;
			if (carriedRoofBlocks > 0 || site.yieldsRoofBlock()) return new SiteChoice(site.plan(), null);
		}
		return new SiteChoice(null, fits ? NO_ROOF_BLOCK : NO_SHELTER_SITE);
	}

	static final String NO_ROOF_BLOCK = "no_roof_block";
	static final String NO_SHELTER_SITE = "no_shelter_site";

	/**
	 * How to climb {@code rise} blocks out of the shaft: pillar when the carried blocks reach at least to one below the
	 * surface (the last step can be stepped out of), else dig a staircase.
	 */
	static ExitMode exitMode(int buildingBlocks, int rise) {
		return rise > 0 && buildingBlocks >= Math.max(1, rise - 1) ? ExitMode.PILLAR : ExitMode.STAIRS;
	}

	/** One tick of pillaring out of a shaft whose top (surface) level is {@code surfaceY}. */
	static PillarStep pillarStep(int feetY, int surfaceY, boolean onGround, int buildingBlocks) {
		if (feetY >= surfaceY) return onGround ? PillarStep.OUT : PillarStep.FALLING;
		if (buildingBlocks > 0) return PillarStep.TOWER;
		return onGround ? PillarStep.NO_BLOCKS : PillarStep.FALLING;
	}

	/**
	 * One tick of a staircase dug from {@code feet} toward {@code direction} up to {@code surfaceY}: clear the cell over
	 * the head, then the next step's feet and head cells, then jump up onto it.
	 */
	static StairAction stairAction(BlockPos feet, Direction direction, int surfaceY, boolean onGround,
		java.util.function.Predicate<BlockPos> open) {
		if (feet.getY() >= surfaceY) return onGround ? StairAction.done() : StairAction.waiting();
		BlockPos target = feet.relative(direction).above();
		for (BlockPos cell : List.of(feet.above(2), target, target.above())) if (!open.test(cell)) return StairAction.breaking(cell);
		if (!onGround) return StairAction.waiting();
		return StairAction.climb(target);
	}

	/**
	 * The wall to dig a staircase into from {@code origin}: every step needs solid ground under it, and every cell to clear
	 * must break with a carried tool, hold no fluid, have no fluid beside or above it and no falling block over it. The
	 * cheapest such side, or empty.
	 */
	static Optional<Direction> stairDirection(BlockPos origin, int surfaceY, ShelterCells cells) {
		Direction best = null;
		long bestCost = Long.MAX_VALUE;
		int rise = surfaceY - origin.getY();
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			long cost = 0;
			boolean usable = rise > 0;
			for (int k = 1; k <= rise && usable; k++) {
				BlockPos stepFeet = origin.relative(direction, k).above(k);
				if (!cells.solid(stepFeet.below())) usable = false;
				for (BlockPos cell : List.of(origin.relative(direction, k - 1).above(k + 1), stepFeet, stepFeet.above())) {
					if (!usable) break;
					if (cells.fluid(cell) || cells.falling(cell.above()) || cells.fluid(cell.above())) { usable = false; break; }
					for (Direction side : Direction.Plane.HORIZONTAL) if (cells.fluid(cell.relative(side))) usable = false;
					if (cells.open(cell)) continue;
					int ticks = cells.breakTicks(cell);
					if (ticks < 0 || ticks > MAX_BREAK_TICKS) usable = false;
					else cost += ticks;
				}
			}
			if (usable && cost < bestCost) {
				best = direction;
				bestCost = cost;
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * A shelter the body already stands in: solid blocks on all four sides of its feet and head, solid ground under it and a
	 * solid block right over its head. Its top is three above the feet, as if this task had dug it.
	 */
	static EmergencyBunker.Plan enclosedShelter(ShelterCells cells, BlockPos feet) {
		if (!cells.solid(feet.below()) || !cells.solid(feet.above(2)) || !cells.open(feet) || !cells.open(feet.above())) return null;
		for (BlockPos cell : List.of(feet, feet.above())) {
			for (Direction side : Direction.Plane.HORIZONTAL) if (!cells.solid(cell.relative(side))) return null;
		}
		BlockPos roof = feet.above(2);
		BlockPos support = roof.above();
		for (Direction side : Direction.Plane.HORIZONTAL) {
			if (cells.solid(roof.relative(side)) && !cells.falling(roof.relative(side))) {
				support = roof.relative(side);
				break;
			}
		}
		return new EmergencyBunker.Plan(feet.above(3), List.of(roof, feet.above(), feet), roof, support);
	}

	/** Whether {@code top} can be walked onto to start digging: ground under it and room for the body. */
	static boolean standable(ShelterCells cells, BlockPos top) {
		return cells.solid(top.below()) && cells.open(top) && cells.open(top.above());
	}

	static boolean inColumnAtTop(Body body, EmergencyBunker.Plan plan) {
		return body.block().getX() == plan.top().getX() && body.block().getZ() == plan.top().getZ() && body.block().getY() == plan.top().getY();
	}

	/** In the column, below the top: where the body waits. */
	static boolean inShaft(Body body, EmergencyBunker.Plan plan) {
		return body.block().getX() == plan.top().getX() && body.block().getZ() == plan.top().getZ()
			&& body.block().getY() < plan.top().getY() && body.block().getY() >= plan.bottom().getY();
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
		activeTicks = phaseTicks = waitedTicks = dueTicks = stuckTicks = reentries = roofGoneTicks = 0;
		plan = null;
		rejectedSites.clear();
		leaveReason = Leave.STAY;
		bestExitY = exitProgress = 0;
		pillarTried = false;
		stairOrigin = null;
		stairDirection = null;
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

	/** The phase the job message shows: DIG (choosing, walking to and digging the shaft), ROOF, WAIT or EXIT. */
	String phaseLabel() {
		return switch (phase) {
			case START, SITE, GO -> "DIG";
			case DIG -> "roofing".equals(step) ? "ROOF" : "DIG";
			case WAIT -> "WAIT";
			case EXIT_ROOF, EXIT_PILLAR, EXIT_STAIRS -> "EXIT";
		};
	}

	private void setSnapshot(TaskExecutionState state, Body body) {
		StringBuilder detail = new StringBuilder("phase=").append(phaseLabel()).append(" step=")
			.append(phase.name().toLowerCase(Locale.ROOT)).append(step.isEmpty() ? "" : ":" + step);
		if (plan != null) detail.append(" shelter=").append(plan.top().toShortString());
		detail.append(" waited=").append(waitedTicks);
		if (body != null) detail.append(" dayTime=").append(Math.floorMod(body.dayTime(), DAY_LENGTH)).append(summary(body));
		if (!lastNote.isEmpty()) detail.append(' ').append(lastNote);
		snapshot = new TaskExecutionSnapshot(state, request == null ? null : request.taskId(), null, PROCESS, detail.toString(), null, null);
	}

	@Override
	public TaskExecutionSnapshot snapshot() { return snapshot; }

	@Override
	public void onWorldLeave() {
		release();
		request = null;
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override
	public void shutdown() { release(); }

	enum Phase { START, SITE, GO, DIG, WAIT, EXIT_ROOF, EXIT_PILLAR, EXIT_STAIRS }

	enum Leave { STAY, MORNING, MAX_WAIT }

	enum ExitMode { PILLAR, STAIRS }

	enum PillarStep { TOWER, FALLING, NO_BLOCKS, OUT }

	enum DigTick { DIGGING, ROOFING, FALLING, ALIGNING, CLOSED, LEFT_COLUMN, NO_ROOF_BLOCK, OFF_PLAN }

	enum BreakTick { WORKING, GONE, FAILED }

	enum ClimbState { WORKING, OUT, BLOCKED }

	/** {@code progress}: blocks placed and broken so far in this climb, which counts as progress while no height is gained. */
	record ClimbTick(ClimbState state, int progress, String note) {}

	record StairAction(Kind kind, BlockPos cell) {
		enum Kind { BREAK, CLIMB, WAIT, DONE }
		static StairAction breaking(BlockPos cell) { return new StairAction(Kind.BREAK, cell); }
		static StairAction climb(BlockPos cell) { return new StairAction(Kind.CLIMB, cell); }
		static StairAction waiting() { return new StairAction(Kind.WAIT, null); }
		static StairAction done() { return new StairAction(Kind.DONE, null); }
	}

	/**
	 * What the task reads each tick. {@code block}: the feet cell. {@code buildingBlocks}: carried blocks that do not fall
	 * and are cheap to place, for the roof and the pillar.
	 */
	record Body(Vec3 feet, BlockPos block, boolean onGround, float health, int food, long dayTime, boolean overworld,
		String dimension, int buildingBlocks) {}

	/** A column's shelter plan (null when none fits), whether its top can be walked onto, and whether its dig yields a roof block. */
	record SiteFacts(EmergencyBunker.Plan plan, boolean standable, boolean yieldsRoofBlock) {}

	/** The shelter to dig, or why none: {@link #NO_ROOF_BLOCK} or {@link #NO_SHELTER_SITE}. */
	record SiteChoice(EmergencyBunker.Plan plan, String failure) {}

	/** The bunker's cells plus room for the body: no collision and no fluid. */
	interface ShelterCells extends EmergencyBunker.Cells {
		boolean open(BlockPos pos);
	}

	interface Environment {
		/** Null when no world is loaded. */
		Body observe();
		SiteFacts siteFacts(BlockPos top);
		/** The shelter the body already stands shut in, or null. */
		EmergencyBunker.Plan enclosedShelter(BlockPos feet);
		/** Steps toward the middle of {@code top}'s cell from beside it. */
		void approach(BlockPos top);
		/** Digs down or roofs for one tick, with the bunker's own step logic. */
		DigTick dig(EmergencyBunker.Plan plan);
		boolean roofClosed(EmergencyBunker.Plan plan);
		/** A hostile mob inside the shaft with the body. */
		boolean intruded(EmergencyBunker.Plan plan);
		boolean hostileNear(BlockPos top, double radius);
		BreakTick breakRoof(EmergencyBunker.Plan plan);
		/** Jumps and places a carried block under the feet, up to the plan's top level; OUT once standing there. */
		ClimbTick pillar(EmergencyBunker.Plan plan);
		Optional<Direction> stairDirection(BlockPos origin, int surfaceY);
		/** Digs and climbs a staircase from {@code origin}; OUT once standing at {@code surfaceY}. */
		ClimbTick stairs(BlockPos origin, Direction direction, int surfaceY);
		/** Lets go of movement and any block being broken. */
		void stop();
		void release();
	}
}
