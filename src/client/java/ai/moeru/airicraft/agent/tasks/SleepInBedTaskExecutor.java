package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Sleeps in a bed, as one task. A raw {@code use_block} on a bed reported success whether or not the body lay down (with
 * monsters near, or by day), and the planner could not tell a skipped night from a refused bed, so it fell back to digging
 * a pit. Sleeping is the safest way through the night and sets the respawn point.
 *
 * <p>Overworld only (a bed explodes in the Nether and the End; nothing is touched there). The task walks (walk only) to a
 * standable cell beside the bed and uses it. By day that only sets the respawn point, which the server confirms in chat
 * ("Respawn point set"). At night it first waits for hostile monsters within vanilla's 8 blocks horizontally and 5
 * vertically of the bed head to go (the survival reflex fights them), uses the bed, confirms the body lies down, retries a
 * refused use, and then waits until the body wakes: in the morning when the night was skipped, or early when it was hurt
 * or woken. The server's answers come from {@link ai.moeru.airicraft.agent.social.GameMessageLog}.
 */
public final class SleepInBedTaskExecutor implements WorldTaskExecutor {
	static final long DAY_LENGTH = 24_000L;
	/** Vanilla lets a player lie down once the sky is dark enough: in clear weather from 12,542 through 23,459. */
	static final long CLEAR_SLEEP_FROM = 12_542L;
	static final long CLEAR_SLEEP_UNTIL = 23_459L;
	/** In rain the sky darkens sooner: 12,010 through 23,991. A thunderstorm allows sleep at any time. */
	static final long RAIN_SLEEP_FROM = 12_010L;
	static final long RAIN_SLEEP_UNTIL = 23_991L;
	/** How far to look for a bed in sight when no position is given. */
	static final int SEARCH_RADIUS = 16;
	/** Vanilla refuses sleep with a monster in a box this far from the bottom centre of the bed head. */
	static final double REST_HORIZONTAL = 8.0D;
	static final double REST_VERTICAL = 5.0D;
	/** Time for the survival reflex (or the monsters themselves) to clear the bed before failing monsters_nearby. */
	static final int MONSTER_WAIT_TICKS = 600;
	/** A use that leaves the body awake this long was refused. */
	static final int CONFIRM_TICKS = 20;
	static final int MAX_USES = 3;
	/** Uses the bed once aimed; past this it uses it anyway. */
	static final int AIM_TICKS = 20;
	/** Past this a use the actuator keeps refusing still counts as an attempt. */
	static final int USE_TIMEOUT_TICKS = 60;
	/** After waking, time for the server's day time to reach the client before an early wake is called. */
	static final int WAKE_SETTLE_TICKS = 40;
	/** The client's day time ran this far ahead of its own ticks: the server skipped the night. */
	static final long NIGHT_SKIP_JUMP = 200L;
	static final int GO_TIMEOUT_TICKS = 400;
	/** Walking, using and sleeping, on top of the time spent waiting for monsters. */
	static final int TIMEOUT_TICKS = 2_400;
	/** Gives up on a walk before the dispatcher's 100-tick stall watchdog would end the whole task. */
	static final long NAVIGATION_STALL_TICKS = 70;
	/** Feet within this of the bottom centre of either bed half can use it: a margin inside vanilla's 3 across and 2 up. */
	static final double REACH_HORIZONTAL = 2.5D;
	static final double REACH_VERTICAL = 1.5D;

	private static final String PROCESS = "SleepInBed";

	private final NavigationFacade navigation;
	private final Environment environment;
	private WorldTaskRequest request;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	private Phase phase = Phase.START;
	private int activeTicks, phaseTicks, monsterTicks, uses, sleptTicks;
	private Bed bed;
	private BlockPos standCell;
	private final Set<BlockPos> rejectedCells = new HashSet<>();
	private long useMark;
	private boolean nightUse, respawnSet, hurt;
	private Reply lastReply = Reply.NONE, useReply = Reply.NONE;
	private String lastMessage = "";
	private List<Monster> lastMonsters = List.of();
	private float sleepHealth;
	private long sleepDayTime, sleepClientTick, clientTick;
	private String step = "";
	private String lastNote = "";
	private GoalPosition navigationGoal;
	private boolean navigationOwned;
	private int navigationTicks;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);

	public SleepInBedTaskExecutor(NavigationFacade navigation, ai.moeru.airicraft.agent.control.CameraController camera) {
		this(navigation, new MinecraftBedEnvironment(camera));
	}

	SleepInBedTaskExecutor(NavigationFacade navigation, Environment environment) {
		this.navigation = Objects.requireNonNull(navigation, "navigation");
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.SLEEP_IN_BED) {
			// A reflex pause hands the same task back afterwards: keep the bed and the counters, only let go of the body.
			// A body already asleep stays in bed; waking it would only make the reflex's fight longer.
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
		clientTick = session.tickCount();
		activeTicks++;
		phaseTicks++;

		if (phase == Phase.START) {
			if (!body.overworld())
				return finish(false, "wrong_dimension; a bed explodes in " + body.dimension() + ". sleep_in_bed works only in the"
					+ " overworld; nothing was used", TaskFailureCode.INVALID_ACTION, body);
			if (body.sleeping()) {
				// Already in bed (a second call, or the body lay down another way): wait for the morning.
				lastNote = "already_asleep";
				startSleep(body);
			}
			else {
				Optional<TaskTerminalEvent> missing = resolveBed(body);
				if (missing.isPresent()) return missing;
				enter(Phase.GO);
			}
		}
		if (activeTicks > TIMEOUT_TICKS + monsterTicks) return timeout(body);

		Optional<TaskTerminalEvent> ended = switch (phase) {
			case START, GO -> go(body);
			case MONSTERS -> monsters(body);
			case USE -> use(body);
			case CONFIRM -> confirm(body);
			case SLEEP -> sleep(body);
			case WOKE -> woke(body);
		};
		if (ended.isPresent()) return ended;
		setSnapshot(TaskExecutionState.RUNNING, body);
		return Optional.empty();
	}

	// ---- Phases ----

	private Optional<TaskTerminalEvent> resolveBed(Body body) {
		GoalPosition wanted = ((WorldTaskRequest.SleepInBed) request.task()).args().bed();
		if (wanted != null) {
			BlockPos pos = new BlockPos(wanted.x(), wanted.y(), wanted.z());
			bed = environment.bedAt(pos);
			if (bed == null)
				return finish(false, "no_bed; there is no bed at " + pos.toShortString() + ". Check the position with"
					+ " inspect_world, or call sleep_in_bed without x,y,z for the nearest bed in sight", TaskFailureCode.MISSING_FACT, body);
			return Optional.empty();
		}
		bed = nearestBed(environment.visibleBeds(SEARCH_RADIUS), body.feet());
		if (bed == null)
			return finish(false, "no_bed; no bed within " + SEARCH_RADIUS + " blocks is in sight. Place one (craft it from"
				+ " three wool of one colour and three planks), or pass the x,y,z of a bed you know", TaskFailureCode.MISSING_FACT, body);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> go(Body body) {
		if (inReach(body.feet(), bed) && body.onGround()) {
			stopNavigation();
			environment.stop();
			return prepare(body);
		}
		if (standCell == null) {
			standCell = chooseStandCell(standCandidates(bed), environment::standable, body.feet(), rejectedCells);
			if (standCell == null)
				return finish(false, "bed_unreachable; no free cell beside the bed at " + bed.describe() + " could be walked to"
					+ (rejectedCells.isEmpty() ? " (every side is blocked)" : " (tried " + rejectedCells.size() + ")")
					+ ". Clear a cell beside it or walk next to it, then call again", TaskFailureCode.TRANSIENT, body);
			phaseTicks = 0;
		}
		if (phaseTicks > GO_TIMEOUT_TICKS) return rejectCell("walk_timeout");
		step = "walk";
		if (!navigate(new GoalPosition(standCell.getX(), standCell.getY(), standCell.getZ(), true))) return rejectCell("unreachable");
		if (navigationOwned && navigationTicks > 10 && !navigation.processActive()) return rejectCell("stopped_short");
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> rejectCell(String why) {
		stopNavigation();
		environment.stop();
		rejectedCells.add(standCell);
		lastNote = "skipped_cell=" + standCell.toShortString() + " " + why;
		standCell = null;
		phaseTicks = 0;
		return Optional.empty();
	}

	/** At the bed: by night, mind the monsters first; then use it. */
	private Optional<TaskTerminalEvent> prepare(Body body) {
		if (sleepWindow(body.dayTime(), body.raining(), body.thundering())) {
			List<Monster> near = environment.monstersNear(bed);
			if (!near.isEmpty()) {
				lastMonsters = near;
				enter(Phase.MONSTERS);
				return Optional.empty();
			}
		}
		enter(Phase.USE);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> monsters(Body body) {
		environment.stop();
		monsterTicks++;
		List<Monster> near = environment.monstersNear(bed);
		if (near.isEmpty() || !sleepWindow(body.dayTime(), body.raining(), body.thundering())) {
			enter(inReach(body.feet(), bed) ? Phase.USE : Phase.GO);
			return Optional.empty();
		}
		lastMonsters = near;
		step = "waiting_for " + describe(near);
		if (monsterTicks >= MONSTER_WAIT_TICKS)
			return finish(false, "monsters_nearby; a bed refuses sleep with a monster within " + (int) REST_HORIZONTAL + " blocks across"
				+ " and " + (int) REST_VERTICAL + " up or down of it, and after " + monsterTicks + " ticks these are still there: "
				+ describe(near) + ". Fight them or wall the bed in, then call sleep_in_bed again", TaskFailureCode.ENVIRONMENT_CHANGED, body);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> use(Body body) {
		if (body.sleeping()) {
			startSleep(body);
			return Optional.empty();
		}
		if (!inReach(body.feet(), bed)) {
			enter(Phase.GO);
			return Optional.empty();
		}
		if (phaseTicks == 1) {
			useMark = environment.messageMark();
			useReply = Reply.NONE;
			nightUse = sleepWindow(body.dayTime(), body.raining(), body.thundering());
		}
		step = "aim";
		if (environment.useBed(bed, phaseTicks > AIM_TICKS) || phaseTicks > USE_TIMEOUT_TICKS) {
			uses++;
			enter(Phase.CONFIRM);
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> confirm(Body body) {
		if (body.sleeping()) {
			startSleep(body);
			return Optional.empty();
		}
		readReplies();
		Reply reply = useReply;
		if (!nightUse && respawnSet)
			return finish(true, "respawn_set bed=" + bed.describe() + "; by day a bed only sets the respawn point. Call"
				+ " sleep_in_bed again at night (day time " + CLEAR_SLEEP_FROM + " or later) to sleep", null, body);
		if (phaseTicks < CONFIRM_TICKS) return Optional.empty();
		step = "refused " + reply.name().toLowerCase(Locale.ROOT);
		if (!nightUse && reply == Reply.NO_SLEEP)
			// Vanilla sets the respawn point before it checks the time, and says nothing when it already is this bed.
			return finish(true, "respawn_set bed=" + bed.describe() + " unchanged=true; the server answered \"" + lastMessage
				+ "\", which comes after the respawn point is set; it was this bed already. Call sleep_in_bed again at night to sleep",
				null, body);
		if (uses >= MAX_USES) {
			String said = lastMessage.isEmpty() ? "no answer from the server" : "last message \"" + lastMessage + "\"";
			if (!nightUse)
				return finish(false, "respawn_unconfirmed bed=" + bed.describe() + "; used it " + uses + " times by day and no"
					+ " \"Respawn point set\" came (" + said + ")", TaskFailureCode.ENVIRONMENT_CHANGED, body);
			return finish(false, "not_sleeping bed=" + bed.describe() + "; used it " + uses + " times and the body did not lie down ("
				+ said + ")" + advice(lastReply), TaskFailureCode.ENVIRONMENT_CHANGED, body);
		}
		if (reply == Reply.TOO_FAR) {
			standCell = null;
			enter(Phase.GO);
			return Optional.empty();
		}
		return prepare(body);
	}

	private Optional<TaskTerminalEvent> sleep(Body body) {
		if (body.sleeping()) {
			sleptTicks++;
			step = "asleep";
			readReplies();
			return Optional.empty();
		}
		hurt = body.health() < sleepHealth - 0.01F;
		enter(Phase.WOKE);
		return woke(body);
	}

	private Optional<TaskTerminalEvent> woke(Body body) {
		if (body.sleeping()) {
			enter(Phase.SLEEP);
			return Optional.empty();
		}
		readReplies();
		boolean skipped = (body.dayTime() - sleepDayTime) - (clientTick - sleepClientTick) > NIGHT_SKIP_JUMP;
		long time = Math.floorMod(body.dayTime(), DAY_LENGTH);
		switch (wakeOutcome(body.dayTime(), body.raining(), body.thundering(), skipped, hurt, phaseTicks)) {
			case MORNING -> {
				return finish(true, "slept_until_morning timeOfDay=" + time + " respawnSet=" + respawnSet + " slept=" + sleptTicks
					+ (bed == null ? "" : " bed=" + bed.describe()), null, body);
			}
			case EARLY -> {
				return finish(false, "woken_early timeOfDay=" + time + " hurt=" + hurt + " slept=" + sleptTicks + "; the night was not"
					+ " skipped" + (hurt ? " (attacked in bed: deal with the attacker, then call sleep_in_bed again)"
					: " (another player, or the bed was broken; call sleep_in_bed again)"), TaskFailureCode.ENVIRONMENT_CHANGED, body);
			}
			case WAIT -> {
				step = "woke_waiting_for_time";
				return Optional.empty();
			}
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> timeout(Body body) {
		if (body.sleeping()) {
			environment.leaveBed();
			return finish(false, "timeout after " + activeTicks + " ticks; still asleep and the night was not skipped (it is only"
				+ " skipped when enough players sleep), so the body got out of bed", TaskFailureCode.TRANSIENT, body);
		}
		return finish(false, "timeout after " + activeTicks + " ticks (" + phase.name().toLowerCase(Locale.ROOT)
			+ (step.isEmpty() ? "" : ": " + step) + ")", TaskFailureCode.TRANSIENT, body);
	}

	private void startSleep(Body body) {
		stopNavigation();
		environment.stop();
		sleepHealth = body.health();
		sleepDayTime = body.dayTime();
		sleepClientTick = clientTick;
		enter(Phase.SLEEP);
	}

	/** Reads the server's answers that came since the last read: the respawn confirmation and the latest refusal. */
	private void readReplies() {
		long mark = environment.messageMark();
		for (Message message : environment.messagesSince(useMark)) {
			Reply reply = classify(message.key(), message.text());
			if (reply == Reply.RESPAWN_SET) respawnSet = true;
			else if (reply != Reply.OTHER || message.overlay()) {
				useReply = reply;
				lastReply = reply;
				lastMessage = message.text();
			}
		}
		useMark = Math.max(useMark, mark);
	}

	private static String advice(Reply reply) {
		return switch (reply) {
			case NOT_SAFE -> ". A monster the body cannot see is near the bed (behind a wall or below): wall the bed in or find it";
			case OCCUPIED -> ". Someone else lies in it: use another bed";
			case OBSTRUCTED -> ". A solid block sits over the bed: break it";
			case TOO_FAR -> ". Walk right next to the bed";
			case NO_SLEEP -> ". It is not night yet: call again later";
			default -> "";
		};
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

	/** Whether vanilla lets a player lie down at {@code dayTime} in this weather (the sky is dark enough). */
	static boolean sleepWindow(long dayTime, boolean raining, boolean thundering) {
		if (thundering) return true;
		long time = Math.floorMod(dayTime, DAY_LENGTH);
		return raining ? time >= RAIN_SLEEP_FROM && time <= RAIN_SLEEP_UNTIL : time >= CLEAR_SLEEP_FROM && time <= CLEAR_SLEEP_UNTIL;
	}

	/** Vanilla's box around the bed head in which a monster keeps a player awake. */
	static AABB restBox(BlockPos head) {
		Vec3 center = Vec3.atBottomCenterOf(head);
		return new AABB(center.x - REST_HORIZONTAL, center.y - REST_VERTICAL, center.z - REST_HORIZONTAL,
			center.x + REST_HORIZONTAL, center.y + REST_VERTICAL, center.z + REST_HORIZONTAL);
	}

	/** Whether a monster with this bounding box keeps a player from sleeping in the bed with this head. */
	static boolean preventsRest(BlockPos head, AABB monster) {
		return restBox(head).intersects(monster);
	}

	/** Whether feet here can use the bed: near the bottom centre of either half. */
	static boolean inReach(Vec3 feet, Bed bed) {
		for (BlockPos part : bed.parts()) {
			Vec3 center = Vec3.atBottomCenterOf(part);
			if (Math.abs(feet.x - center.x) <= REACH_HORIZONTAL && Math.abs(feet.z - center.z) <= REACH_HORIZONTAL
				&& Math.abs(feet.y - center.y) <= REACH_VERTICAL) return true;
		}
		return false;
	}

	/** The bed nearest the feet, or null. */
	static Bed nearestBed(List<Bed> beds, Vec3 feet) {
		return beds.stream().min(Comparator.comparingDouble(bed -> bed.distanceSqr(feet))).orElse(null);
	}

	/** Cells around the bed's two-block footprint, at its level, one above and one below. */
	static List<BlockPos> standCandidates(Bed bed) {
		int minX = Math.min(bed.head().getX(), bed.foot().getX()) - 1, maxX = Math.max(bed.head().getX(), bed.foot().getX()) + 1;
		int minZ = Math.min(bed.head().getZ(), bed.foot().getZ()) - 1, maxZ = Math.max(bed.head().getZ(), bed.foot().getZ()) + 1;
		int y = bed.head().getY();
		List<BlockPos> cells = new ArrayList<>();
		for (int dy : new int[] {0, 1, -1}) {
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					BlockPos cell = new BlockPos(x, y, z);
					if (cell.equals(bed.head()) || cell.equals(bed.foot())) continue;
					cells.add(cell.above(dy));
				}
			}
		}
		return cells;
	}

	/** The standable candidate nearest the feet, at the bed's level before one above or below; null when none is left. */
	static BlockPos chooseStandCell(List<BlockPos> candidates, Predicate<BlockPos> standable, Vec3 feet, Set<BlockPos> rejected) {
		BlockPos best = null;
		double bestCost = Double.MAX_VALUE;
		int level = candidates.isEmpty() ? 0 : candidates.getFirst().getY();
		for (BlockPos cell : candidates) {
			if (rejected.contains(cell) || !standable.test(cell)) continue;
			double cost = Vec3.atBottomCenterOf(cell).distanceToSqr(feet) + (cell.getY() == level ? 0.0D : 4.0D);
			if (cost < bestCost) {
				best = cell;
				bestCost = cost;
			}
		}
		return best;
	}

	/**
	 * How a wake ends: in the morning once the day time is out of the sleep window or jumped ahead (the server skipped the
	 * night); early once hurt, or once the day time still says night {@link #WAKE_SETTLE_TICKS} after waking.
	 */
	static WakeOutcome wakeOutcome(long dayTime, boolean raining, boolean thundering, boolean nightSkipped, boolean hurt, int ticksAwake) {
		if (nightSkipped || !sleepWindow(dayTime, raining, thundering)) return WakeOutcome.MORNING;
		if (hurt || ticksAwake >= WAKE_SETTLE_TICKS) return WakeOutcome.EARLY;
		return WakeOutcome.WAIT;
	}

	/** What a server message says about the bed, by translation key, else by its English text. */
	static Reply classify(String key, String text) {
		switch (key == null ? "" : key) {
			case "block.minecraft.set_spawn" -> { return Reply.RESPAWN_SET; }
			case "block.minecraft.bed.no_sleep" -> { return Reply.NO_SLEEP; }
			case "block.minecraft.bed.not_safe" -> { return Reply.NOT_SAFE; }
			case "block.minecraft.bed.occupied" -> { return Reply.OCCUPIED; }
			case "block.minecraft.bed.obstructed" -> { return Reply.OBSTRUCTED; }
			case "block.minecraft.bed.too_far_away" -> { return Reply.TOO_FAR; }
			default -> {}
		}
		String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
		if (lower.contains("respawn point set")) return Reply.RESPAWN_SET;
		if (lower.contains("monsters nearby")) return Reply.NOT_SAFE;
		if (lower.contains("bed is occupied")) return Reply.OCCUPIED;
		if (lower.contains("bed is obstructed")) return Reply.OBSTRUCTED;
		if (lower.contains("too far away")) return Reply.TOO_FAR;
		if (lower.contains("sleep only at night") || lower.contains("only sleep at night")) return Reply.NO_SLEEP;
		return Reply.OTHER;
	}

	static String describe(List<Monster> monsters) {
		return monsters.stream().sorted(Comparator.comparingDouble(Monster::distance)).limit(6)
			.map(monster -> String.format(Locale.ROOT, "%s %.1f", monster.type(), monster.distance()))
			.collect(Collectors.joining(", "));
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
		activeTicks = phaseTicks = monsterTicks = uses = sleptTicks = 0;
		bed = null;
		standCell = null;
		rejectedCells.clear();
		useMark = 0L;
		nightUse = respawnSet = hurt = false;
		lastReply = useReply = Reply.NONE;
		lastMessage = "";
		lastMonsters = List.of();
		sleepHealth = 0.0F;
		sleepDayTime = sleepClientTick = 0L;
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
		if (bed != null) detail.append(" bed=").append(bed.describe());
		detail.append(" uses=").append(uses);
		if (monsterTicks > 0) detail.append(" monsterWait=").append(monsterTicks);
		if (body != null) detail.append(" dayTime=").append(Math.floorMod(body.dayTime(), DAY_LENGTH)).append(summary(body));
		if (!lastMessage.isEmpty()) detail.append(" lastMessage=\"").append(lastMessage).append('"');
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

	enum Phase { START, GO, MONSTERS, USE, CONFIRM, SLEEP, WOKE }

	enum WakeOutcome { MORNING, EARLY, WAIT }

	/** The server's answer to a bed use. NONE: nothing yet; OTHER: an unrelated message. */
	enum Reply { NONE, RESPAWN_SET, NO_SLEEP, NOT_SAFE, OCCUPIED, OBSTRUCTED, TOO_FAR, OTHER }

	/** The two halves of a bed. */
	record Bed(BlockPos head, BlockPos foot) {
		List<BlockPos> parts() { return List.of(head, foot); }
		String describe() { return head.getX() + "," + head.getY() + "," + head.getZ(); }
		double distanceSqr(Vec3 feet) {
			return Math.min(Vec3.atBottomCenterOf(head).distanceToSqr(feet), Vec3.atBottomCenterOf(foot).distanceToSqr(feet));
		}
	}

	/** A monster keeping the body awake: its type (path of the id) and distance from the bed head. */
	record Monster(String type, double distance) {}

	/** A system message from the server; {@code key} is its translation key, or empty. */
	record Message(String key, String text, boolean overlay) {}

	/** What the task reads each tick. {@code block}: the feet cell. */
	record Body(Vec3 feet, BlockPos block, boolean onGround, float health, int food, long dayTime, boolean raining,
		boolean thundering, boolean overworld, String dimension, boolean sleeping) {}

	interface Environment {
		/** Null when no world is loaded. */
		Body observe();
		/** The bed with a half at {@code pos}, or null when no bed is there. */
		Bed bedAt(BlockPos pos);
		/** Beds within {@code radius} blocks the eyes can see (a ray reaches the bed; no X-ray). */
		List<Bed> visibleBeds(int radius);
		/** Ground under the cell and room for the body, no fluid. */
		boolean standable(BlockPos cell);
		/** Monsters in vanilla's rest box around the bed head ({@link #restBox}). */
		List<Monster> monstersNear(Bed bed);
		/** Turns toward the bed and uses it once aimed, or at once with {@code force}; true once a use was sent. */
		boolean useBed(Bed bed, boolean force);
		/** The latest server message so far, for {@link #messagesSince}. */
		long messageMark();
		List<Message> messagesSince(long mark);
		/** Gets out of bed. */
		void leaveBed();
		/** Lets go of movement. */
		void stop();
		void release();
	}
}
