package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Collects ender pearls by fighting endermen without waiting on the planner. An enderman is almost three blocks tall and
 * hits hard (7 on normal), so the body fights from a pit it can see out of but the enderman cannot reach into: it digs one
 * block down where the eight blocks around that cell are solid, and puts one block over its head (on a two-block support
 * column beside the pit when nothing else holds it). An enderman cannot stand in the two-high pit or next to it at the
 * body's level, and from the ground beside it its eyes cannot see the body's eyes under the roof, which vanilla melee
 * needs; the body still sees its legs through the one-block gap and hits them. From the pit it provokes one enderman at a
 * time by looking at its eyes (never at one within five blocks, which makes it teleport away), then watches its legs
 * while it comes and hits it in reach with the best melee weapon. Pearls dropped beside the pit are picked up from inside;
 * one farther away is fetched (walk only, from safe ground, with no angry enderman near) after breaking the roof, which
 * goes back on when the body is in again. At 8 health it stops provoking and eats in the pit until 16. It never shoots
 * an enderman (a projectile makes it teleport) and does not fight in overworld rain, which makes them teleport too.
 * Without carried roof blocks or ground to dig, it fights in the open: it provokes only at 14 health or more, one
 * enderman at a time, with the shield up between swings when one is carried. Ends once the inventory holds the
 * requested pearls.
 */
public final class CollectEnderPearlsTaskExecutor implements WorldTaskExecutor {
	/** The whole task gets at least this long; each pearl wanted adds {@link #TIMEOUT_TICKS_PER_PEARL}. */
	static final int MIN_TIMEOUT_TICKS = 6_000;
	/** About two kills a pearl (a drop is 50% without looting) at about 25 seconds a kill. */
	static final int TIMEOUT_TICKS_PER_PEARL = 1_200;
	/** No enderman in sight at all this long after the start: there are none here. */
	static final int FIRST_SEARCH_TICKS = 600;
	/** Nothing to provoke, fight or pick up for this long after having seen some: they are gone. */
	static final int NO_ENDERMAN_TICKS = 2_400;
	static final double SEARCH_RANGE = 48.0D;
	/** Looking at the eyes of an enderman this close makes it teleport away rather than come. */
	static final double MIN_STARE_DISTANCE = 5.0D;
	/** Staring this long without making it angry (no clear view of its eyes): try another for a while. */
	static final int STARE_TICKS = 100;
	static final int STARE_COOLDOWN_TICKS = 600;
	static final float RETREAT_HEALTH = 8.0F;
	static final float RESUME_HEALTH = 16.0F;
	/** Out in the open an enderman is provoked only with this much health: two or three hits from it. */
	static final float OPEN_FIGHT_HEALTH = 14.0F;
	/** Outside the pit this low with an angry enderman close, one more hit kills: hand back to the survival reflex. */
	static final float CRITICAL_HEALTH = 4.0F;
	static final double THREAT_RANGE = 8.0D;
	/** Natural regeneration needs this much hunger. */
	static final int REGEN_FOOD = 18;
	static final int FULL_FOOD = 20;
	static final int HEAL_TIMEOUT_TICKS = 2_400;
	static final int MAX_ANGRY_OPEN = 1;
	static final int MAX_ANGRY_SPOT = 3;
	/** In the open the shield goes up toward an angry enderman this close. */
	static final double GUARD_RANGE = 6.0D;
	static final double PICKUP_RADIUS = 16.0D;
	/**
	 * A pearl this close along both horizontal axes is picked up by the body in the pit without leaving it: the body's
	 * half width, the one-block pickup reach and the item's half width.
	 */
	static final double AUTO_PICKUP_REACH = 1.4D;
	/** Wait this long for a pearl beside the pit to be picked up before going out for it. */
	static final int AUTO_PICKUP_TICKS = 20;
	/** No angry enderman this close to the body or the pearl while going out for it. */
	static final double PICKUP_ANGRY_CLEARANCE = 16.0D;
	/** No calm enderman this close to the pearl: walking up to one risks looking it in the eyes. */
	static final double PICKUP_CALM_CLEARANCE = 4.0D;
	static final int PICKUP_TIMEOUT_TICKS = 300;
	static final int SPOT_RADIUS = 4;
	static final int SPOT_ATTEMPTS = 3;
	/** One dig, placement or walk of the pit's building that takes longer than this has failed. */
	static final int STEP_TIMEOUT_TICKS = 200;
	static final int NAVIGATION_TIMEOUT_TICKS = 600;
	/** Gives up on a route before the dispatcher's 100-tick stall watchdog would end the whole task. */
	static final long NAVIGATION_STALL_TICKS = 70;
	static final int MAX_SAFE_FALL = 3;
	static final NavigationOptions OPTIONS = NavigationOptions.WALK_ONLY.withMaxSafeFall(MAX_SAFE_FALL);
	/** A known enderman that vanishes this close was killed, not lost from view. */
	static final double KILL_DISTANCE = 6.0D;

	private final NavigationFacade navigation;
	private final Environment environment;
	private WorldTaskRequest request;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	private Mode mode = Mode.SEARCH;
	private int activeTicks, modeTicks, idleTicks;
	private int count, pearls, startPearls = -1;
	private boolean everSeen;
	private SpotPlan spot;
	private final Set<GoalPosition> rejectedSpots = new HashSet<>();
	private int spotFailures;
	private boolean openFight;
	private String openReason = "";
	private String actionKey;
	private int actionTicks;
	private int stareTarget = -1, stareTicks;
	private final Map<Integer, Integer> stareCooldown = new HashMap<>();
	private final Map<Integer, Vec3> knownEndermen = new LinkedHashMap<>();
	private final Map<Integer, Boolean> angryEndermen = new HashMap<>();
	private Vec3 pickupTarget;
	private int pickupTicks;
	private final List<Vec3> skippedPearls = new ArrayList<>();
	private int nearDropTicks;
	private int kills, hits, provoked, exits, eats;
	private boolean consuming;
	private boolean guarding;
	private boolean digging;
	private GoalPosition navigationGoal;
	private boolean navigationOwned;
	private int navigationTicks;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);
	/** The tick a stalled route was dropped; no route starts in the same tick, so the dispatcher's watchdog sees it end. */
	private int navigationRestTick = -1;
	private String lastAction = "none";

	public CollectEnderPearlsTaskExecutor(NavigationFacade navigation, ai.moeru.airicraft.agent.control.CameraController camera) {
		this(navigation, new MinecraftEnderPearlEnvironment(camera));
	}

	CollectEnderPearlsTaskExecutor(NavigationFacade navigation, Environment environment) {
		this.navigation = Objects.requireNonNull(navigation, "navigation");
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.COLLECT_ENDER_PEARLS) {
			if (request != null) release();
			request = null;
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest next = activeTask.get();
		if (request == null || !request.taskId().equals(next.taskId())) {
			release();
			request = next;
			reset(((WorldTaskRequest.CollectEnderPearls) next.task()).args().count());
		}
		if (terminal != null) return finishRelease();
		if (session == null || !session.companionActuationAllowed()) {
			release();
			setSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, "session_gate", null);
			return Optional.empty();
		}
		if (!navigation.isLoaded()) return finish(false, "navigation_unavailable", "", TaskFailureCode.ENVIRONMENT_CHANGED);
		Arena arena = environment.observe();
		if (arena == null) return finish(false, "world_unavailable", "", TaskFailureCode.ENVIRONMENT_CHANGED);
		activeTicks++;
		modeTicks++;
		pearls = arena.pearls();
		if (startPearls < 0) startPearls = pearls;
		Optional<Ending> ending = ending(arena, count, activeTicks);
		if (ending.isPresent()) return switch (ending.get()) {
			case SUCCEEDED -> finish(true, "ender_pearls_collected", "", null);
			case WRONG_DIMENSION -> finish(false, "wrong_dimension", "endermen are fought in the overworld, the Nether (warped forests "
				+ "are full of them) or the End", TaskFailureCode.INVALID_ACTION);
			case RAINING -> finish(false, "raining", "rain makes endermen teleport away in the overworld; wait for it to stop "
				+ "(or go to a warped forest in the Nether), then call again", TaskFailureCode.ENVIRONMENT_CHANGED);
			case TIMEOUT -> finish(false, "timeout", "the task stops after " + timeoutTicks(count) + " ticks; call it again to go on",
				TaskFailureCode.TRANSIENT);
		};
		observeEndermen(arena);
		boolean busy = arena.endermen().stream().anyMatch(e -> e.visible() || e.angry())
			|| pickupTarget(arena.drops(), arena.endermen(), arena.feet(), skippedPearls).isPresent();
		idleTicks = busy ? 0 : idleTicks + 1;
		if (!everSeen && activeTicks > FIRST_SEARCH_TICKS)
			return finish(false, "no_endermen_found", "no enderman in sight within " + (int) SEARCH_RANGE + " blocks" + searchHint(arena),
				TaskFailureCode.MISSING_FACT);
		if (everSeen && idleTicks > NO_ENDERMAN_TICKS)
			return finish(false, "no_endermen_found", "no enderman in sight for " + NO_ENDERMAN_TICKS + " ticks" + searchHint(arena),
				TaskFailureCode.ENVIRONMENT_CHANGED);
		if (mode != Mode.HEAL && arena.health() <= RETREAT_HEALTH) enter(Mode.HEAL);

		Cells cells = environment.cells();
		boolean safe = spot != null && cells != null && safeIn(spot, cells, arena.feet());
		int angry = (int) arena.endermen().stream().filter(Enderman::angry).count();
		boolean provokeAllowed = openFight && mode != Mode.HEAL && arena.health() >= OPEN_FIGHT_HEALTH && angry < MAX_ANGRY_OPEN;
		Enderman reach = meleeTarget(arena.endermen(), safe, provokeAllowed);
		if (reach != null) {
			stopConsuming();
			stopNavigation();
			stopDigging();
			stare(null);
			lastAction = "melee";
			if (environment.melee(reach.id(), !safe && arena.hasShield()) == MeleeResult.HIT) hits++;
			guarding = !safe && arena.hasShield();
			return running(arena);
		}
		Optional<TaskTerminalEvent> end = mode == Mode.HEAL ? heal(arena, cells, safe) : work(arena, cells, safe, angry);
		if (end.isPresent()) return end;
		return running(arena);
	}

	private Optional<TaskTerminalEvent> running(Arena arena) {
		if (!navigationOwned) navigationStall.clear();
		setSnapshot(TaskExecutionState.RUNNING, "collecting", arena);
		return Optional.empty();
	}

	// ---- modes ----

	private Optional<TaskTerminalEvent> work(Arena arena, Cells cells, boolean safe, int angry) {
		if (spot == null && !openFight) {
			if (!everSeen) {
				stopNavigation();
				lastAction = "look_for_endermen";
				return Optional.empty();
			}
			chooseSpot(arena, cells);
			if (spot == null && !openFight) return Optional.empty();
		}
		if (spot == null) return open(arena, angry);
		if (mode == Mode.EXIT) return exit(arena, cells);
		if (mode == Mode.PICKUP) {
			if (pickup(arena)) return Optional.empty();
			enter(Mode.BUILD);
		}
		if (!safe) {
			if (mode != Mode.BUILD) enter(Mode.BUILD);
			return build(arena, cells);
		}
		if (mode != Mode.WAIT) enter(Mode.WAIT);
		return waitInPit(arena, angry);
	}

	private void chooseSpot(Arena arena, Cells cells) {
		if (cells == null) return;
		GoalPosition feet = cell(arena.feet());
		Optional<SpotPlan> plan = planSpot(cells, feet, arena.buildingBlocks(), rejectedSpots);
		if (plan.isPresent()) {
			spot = plan.get();
			enter(Mode.BUILD);
			return;
		}
		openFight = true;
		openReason = spotFailures > 0 ? spotFailures + "_pits_failed" : "no_pit_site " + pitRefusals(cells, feet, arena.buildingBlocks());
		enter(Mode.OPEN);
	}

	private Optional<TaskTerminalEvent> build(Arena arena, Cells cells) {
		BuildAction action = buildAction(spot, cells, arena.feet(), arena.onGround());
		String key = action.kind() + "@" + (action.cell() == null ? "" : compact(action.cell()));
		if (!key.equals(actionKey)) {
			actionKey = key;
			actionTicks = 0;
			if (action.kind() != BuildKind.DIG) stopDigging();
		}
		actionTicks++;
		if (actionTicks > STEP_TIMEOUT_TICKS) return rejectSpot(action.kind().name().toLowerCase(Locale.ROOT) + "_timeout");
		stare(null);
		switch (action.kind()) {
			case READY -> {
				stopNavigation();
				enter(Mode.WAIT);
				lastAction = "pit_ready";
			}
			case WAIT -> {
				stopNavigation();
				lastAction = "falling";
			}
			case NAVIGATE -> {
				lastAction = "walk_to_pit";
				if (!navigate(action.cell()) || navigationTicks > NAVIGATION_TIMEOUT_TICKS) return rejectSpot("unreachable");
			}
			case NUDGE -> {
				stopNavigation();
				lastAction = "step_into_pit";
				environment.nudge(action.cell());
			}
			case DIG, BREAK_ROOF -> {
				stopNavigation();
				lastAction = action.kind() == BuildKind.DIG ? "dig_pit" : "open_roof";
				digging = true;
				BreakResult result = environment.dig(action.cell());
				if (result == BreakResult.UNBREAKABLE || result == BreakResult.OUT_OF_REACH)
					return rejectSpot(result.name().toLowerCase(Locale.ROOT));
			}
			case PLACE -> {
				stopNavigation();
				stopDigging();
				lastAction = "place " + compact(action.cell());
				PlaceResult result = environment.place(action.cell(), action.support());
				if (result == PlaceResult.BLOCKED) return rejectSpot("placement_blocked");
				if (result == PlaceResult.NO_BLOCK) {
					spot = null;
					openFight = true;
					openReason = "out_of_roof_blocks";
					enter(Mode.OPEN);
				}
			}
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> rejectSpot(String why) {
		stopNavigation();
		stopDigging();
		if (spot != null) rejectedSpots.add(spot.stand());
		lastAction = "pit_failed " + why;
		spot = null;
		spotFailures++;
		if (spotFailures >= SPOT_ATTEMPTS) {
			openFight = true;
			openReason = spotFailures + "_pits_failed last=" + why;
			enter(Mode.OPEN);
		} else enter(Mode.SEARCH);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> waitInPit(Arena arena, int angry) {
		stopNavigation();
		stopDigging();
		Vec3 feet = arena.feet();
		Optional<DroppedPearl> drop = pickupTarget(arena.drops(), arena.endermen(), feet, skippedPearls);
		// A pearl right beside the pit comes to the body by itself; give it a moment before going out for any.
		boolean dropNear = arena.drops().stream().anyMatch(d -> autoPickup(d.position(), feet));
		nearDropTicks = dropNear ? nearDropTicks + 1 : 0;
		if (drop.isPresent() && (!dropNear || nearDropTicks > AUTO_PICKUP_TICKS)) {
			stare(null);
			exits++;
			enter(Mode.EXIT);
			lastAction = "open_roof_for_pearl";
			return Optional.empty();
		}
		Enderman coming = nearest(arena.endermen(), Enderman::angry);
		if (coming != null) {
			stare(null);
			lastAction = "watch_angry";
			environment.watch(coming.id(), false);
			return Optional.empty();
		}
		return provoke(arena, angry, MAX_ANGRY_SPOT);
	}

	private Optional<TaskTerminalEvent> exit(Arena arena, Cells cells) {
		stopNavigation();
		stare(null);
		GoalPosition roof = spot.roof();
		if (cells != null && cells.passable(roof)) {
			stopDigging();
			enter(Mode.PICKUP);
			lastAction = "roof_open";
			if (!pickup(arena)) enter(Mode.BUILD);
			return Optional.empty();
		}
		if (modeTicks > STEP_TIMEOUT_TICKS) {
			stopDigging();
			arena.drops().forEach(drop -> skippedPearls.add(drop.position()));
			enter(Mode.WAIT);
			lastAction = "roof_stuck";
			return Optional.empty();
		}
		lastAction = "open_roof";
		digging = true;
		BreakResult result = environment.dig(roof);
		if (result == BreakResult.UNBREAKABLE || result == BreakResult.OUT_OF_REACH) {
			stopDigging();
			arena.drops().forEach(drop -> skippedPearls.add(drop.position()));
			enter(Mode.WAIT);
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> open(Arena arena, int angry) {
		if (mode != Mode.OPEN && mode != Mode.PICKUP) enter(Mode.OPEN);
		if (pickup(arena)) return Optional.empty();
		if (mode == Mode.PICKUP) enter(Mode.OPEN);
		stopNavigation();
		Enderman coming = nearest(arena.endermen(), Enderman::angry);
		if (coming != null) {
			stare(null);
			boolean guard = arena.hasShield() && coming.distance() <= GUARD_RANGE;
			guarding = guard;
			lastAction = guard ? "guard" : "watch_angry";
			environment.watch(coming.id(), guard);
			return Optional.empty();
		}
		lowerGuard();
		if (arena.health() < OPEN_FIGHT_HEALTH) {
			stare(null);
			if (arena.food() < FULL_FOOD && arena.hasFood()) eat();
			else lastAction = "regenerate_before_provoking";
			return Optional.empty();
		}
		stopConsuming();
		return provoke(arena, angry, MAX_ANGRY_OPEN);
	}

	private Optional<TaskTerminalEvent> provoke(Arena arena, int angry, int maxAngry) {
		Optional<Enderman> target = provokeTarget(arena.endermen(), stareCooldown, activeTicks, angry, maxAngry);
		if (target.isEmpty()) {
			stare(null);
			lastAction = "wait_for_endermen";
			return Optional.empty();
		}
		Enderman enderman = target.get();
		stare(enderman);
		if (++stareTicks > STARE_TICKS) {
			stareCooldown.put(enderman.id(), activeTicks + STARE_COOLDOWN_TICKS);
			stare(null);
			lastAction = "stare_gave_up";
			return Optional.empty();
		}
		lastAction = "stare";
		environment.stare(enderman.id());
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> heal(Arena arena, Cells cells, boolean safe) {
		stare(null);
		if (arena.health() >= RESUME_HEALTH) {
			stopConsuming();
			enter(spot != null ? Mode.BUILD : openFight ? Mode.OPEN : Mode.SEARCH);
			return Optional.empty();
		}
		if (modeTicks > HEAL_TIMEOUT_TICKS)
			return finish(false, "low_health", String.format(Locale.ROOT, "health=%.1f did not recover in %d ticks; eat, then call "
				+ "again", arena.health(), HEAL_TIMEOUT_TICKS), TaskFailureCode.ENVIRONMENT_CHANGED);
		if (!safe) {
			Enderman threat = nearest(arena.endermen(), e -> e.angry() && e.distance() <= THREAT_RANGE);
			boolean dug = spot != null && cells != null && cells.passable(spot.pit());
			if (threat != null && (!dug || arena.health() <= CRITICAL_HEALTH))
				return finish(false, "low_health", String.format(Locale.ROOT, "health=%.1f with an angry enderman %.1f blocks away and "
					+ "no closed pit to stand in; the survival reflex takes over. Eat, then call again", arena.health(), threat.distance()),
					TaskFailureCode.ENVIRONMENT_CHANGED);
			if (dug && cells != null) {
				// Back into the pit and close the roof before eating.
				stopConsuming();
				return build(arena, cells);
			}
		}
		stopNavigation();
		stopDigging();
		if (arena.food() < FULL_FOOD && arena.hasFood()) {
			eat();
			return Optional.empty();
		}
		stopConsuming();
		if (!arena.hasFood() && arena.food() < REGEN_FOOD)
			return finish(false, "out_of_food", String.format(Locale.ROOT, "health=%.1f food=%d and nothing to eat; health does not "
				+ "come back below %d food. Bring cooked food, then call again", arena.health(), arena.food(), REGEN_FOOD),
				TaskFailureCode.MISSING_ITEM);
		lastAction = "regenerate";
		return Optional.empty();
	}

	/** Walks over a dropped pearl when that is safe; false when there is none to fetch. */
	private boolean pickup(Arena arena) {
		if (pickupTarget != null) {
			Vec3 target = pickupTarget;
			DroppedPearl drop = arena.drops().stream().filter(d -> d.position().distanceTo(target) <= 1.5D).findFirst().orElse(null);
			if (drop == null) pickupTarget = null;
			else if (!safeToFetch(drop, arena.endermen(), arena.feet())) {
				// An enderman turned angry or came close: back off now and fetch it later.
				pickupTarget = null;
				stopNavigation();
				return false;
			} else if (++pickupTicks > PICKUP_TIMEOUT_TICKS) {
				skippedPearls.add(target);
				pickupTarget = null;
				stopNavigation();
			}
		}
		if (pickupTarget == null) {
			pickupTarget = pickupTarget(arena.drops(), arena.endermen(), arena.feet(), skippedPearls).map(DroppedPearl::position).orElse(null);
			pickupTicks = 0;
			if (pickupTarget == null) return false;
		}
		if (mode != Mode.PICKUP) enter(Mode.PICKUP);
		stare(null);
		lowerGuard();
		stopConsuming();
		lastAction = "pickup";
		GoalPosition goal = new GoalPosition((int) Math.floor(pickupTarget.x), (int) Math.floor(pickupTarget.y + 0.25D),
			(int) Math.floor(pickupTarget.z), true);
		if (!navigate(goal) || navigationTicks > NAVIGATION_TIMEOUT_TICKS) {
			skippedPearls.add(pickupTarget);
			pickupTarget = null;
			stopNavigation();
		}
		return true;
	}

	private void eat() {
		lowerGuard();
		lastAction = "eat";
		ConsumeResult result = environment.consume();
		if (result == ConsumeResult.DONE) {
			consuming = false;
			eats++;
		} else consuming = result == ConsumeResult.CONSUMING;
	}

	private void stare(Enderman target) {
		int id = target == null ? -1 : target.id();
		if (id != stareTarget) {
			stareTarget = id;
			stareTicks = 0;
		}
	}

	private void observeEndermen(Arena arena) {
		Set<Integer> present = new HashSet<>();
		for (Enderman enderman : arena.endermen()) {
			present.add(enderman.id());
			knownEndermen.put(enderman.id(), enderman.position());
			if (enderman.angry() && angryEndermen.put(enderman.id(), true) == null) provoked++;
			if (enderman.visible() && enderman.distance() <= SEARCH_RANGE) everSeen = true;
		}
		var it = knownEndermen.entrySet().iterator();
		while (it.hasNext()) {
			var known = it.next();
			if (present.contains(known.getKey())) continue;
			it.remove();
			stareCooldown.remove(known.getKey());
			boolean wasAngry = angryEndermen.remove(known.getKey()) != null;
			if (wasAngry && known.getValue().distanceTo(arena.feet()) <= KILL_DISTANCE) kills++;
		}
		stareCooldown.values().removeIf(until -> until < activeTicks);
	}

	private String searchHint(Arena arena) {
		return switch (arena.dimension()) {
			case OVERWORLD -> arena.daytime()
				? "; it is day: endermen spawn in the dark, so call again at night on open flat ground (deserts and plains), or "
					+ "go to a warped forest in the Nether"
				: "; walk to open flat ground with a wide view (plains, desert) or go to a warped forest in the Nether, then call again";
			case NETHER -> "; endermen are common in warped forests (blue-green nylium) and soul sand valleys: explore_for one, then "
				+ "call again";
			case END -> "; walk out onto the open end stone away from the pillars, then call again";
			case OTHER -> "";
		};
	}

	private void enter(Mode next) {
		if (mode != next) {
			stopNavigation();
			if (mode == Mode.PICKUP) pickupTarget = null;
			actionKey = null;
			actionTicks = 0;
		}
		mode = next;
		modeTicks = 0;
	}

	// ---- pure decisions, tested directly ----

	static int timeoutTicks(int count) {
		return Math.max(MIN_TIMEOUT_TICKS, count * TIMEOUT_TICKS_PER_PEARL);
	}

	/** Whether the task ends this tick, before anything else is looked at. */
	static Optional<Ending> ending(Arena arena, int count, int activeTicks) {
		if (arena.pearls() >= count) return Optional.of(Ending.SUCCEEDED);
		if (arena.dimension() == Dimension.OTHER) return Optional.of(Ending.WRONG_DIMENSION);
		if (arena.raining()) return Optional.of(Ending.RAINING);
		if (activeTicks > timeoutTicks(count)) return Optional.of(Ending.TIMEOUT);
		return Optional.empty();
	}

	/**
	 * The enderman to hit this tick: one in reach that is angry, or a calm one when hitting it is safe (from the closed pit)
	 * or the open fight may provoke another. Angry ones first, then the nearest.
	 */
	static Enderman meleeTarget(List<Enderman> endermen, boolean safe, boolean provokeAllowed) {
		return endermen.stream().filter(Enderman::inReach).filter(e -> e.angry() || safe || provokeAllowed)
			.min(Comparator.comparing((Enderman e) -> !e.angry()).thenComparingDouble(Enderman::distance)).orElse(null);
	}

	/**
	 * The calm enderman to look in the eyes: the nearest whose eyes the body's eyes see, at least {@link #MIN_STARE_DISTANCE}
	 * and at most {@link #SEARCH_RANGE} away, not given up on lately, while fewer than {@code maxAngry} are angry.
	 */
	static Optional<Enderman> provokeTarget(List<Enderman> endermen, Map<Integer, Integer> cooldown, int tick, int angry, int maxAngry) {
		if (angry >= maxAngry) return Optional.empty();
		return endermen.stream()
			.filter(e -> !e.angry() && e.visible() && e.distance() >= MIN_STARE_DISTANCE && e.distance() <= SEARCH_RANGE)
			.filter(e -> cooldown.getOrDefault(e.id(), Integer.MIN_VALUE) < tick)
			.min(Comparator.comparingDouble(Enderman::distance).thenComparingInt(Enderman::id));
	}

	/** The nearest pearl worth walking to: on safe ground, in range, not given up on, and safe to fetch. */
	static Optional<DroppedPearl> pickupTarget(List<DroppedPearl> drops, List<Enderman> endermen, Vec3 feet, List<Vec3> skipped) {
		return drops.stream()
			.filter(drop -> skipped.stream().noneMatch(s -> s.distanceTo(drop.position()) <= 1.5D))
			.filter(drop -> drop.position().distanceTo(feet) <= PICKUP_RADIUS)
			.filter(drop -> safeToFetch(drop, endermen, feet))
			.min(Comparator.comparingDouble(drop -> drop.position().distanceToSqr(feet)));
	}

	/** A pearl on safe ground with no angry enderman near it or the body, and no calm one right beside it. */
	static boolean safeToFetch(DroppedPearl drop, List<Enderman> endermen, Vec3 feet) {
		if (!drop.safe()) return false;
		for (Enderman e : endermen) {
			if (e.angry() && (e.position().distanceTo(drop.position()) <= PICKUP_ANGRY_CLEARANCE
				|| e.position().distanceTo(feet) <= PICKUP_ANGRY_CLEARANCE)) return false;
			if (!e.angry() && e.position().distanceTo(drop.position()) <= PICKUP_CALM_CLEARANCE) return false;
		}
		return true;
	}

	/** Whether a pearl lies where the body standing at {@code feet} picks it up without moving. */
	static boolean autoPickup(Vec3 drop, Vec3 feet) {
		return Math.abs(drop.x - feet.x) <= AUTO_PICKUP_REACH && Math.abs(drop.z - feet.z) <= AUTO_PICKUP_REACH
			&& drop.y >= feet.y - 0.5D && drop.y <= feet.y + 2.3D;
	}

	/** Whether an enderman (2.9 tall, standing on any block with collision) fits with its feet in {@code cell}. */
	static boolean endermanStands(Cells cells, GoalPosition cell) {
		return !cells.passable(offset(cell, 0, -1, 0)) && cells.passable(cell) && cells.passable(offset(cell, 0, 1, 0))
			&& cells.passable(offset(cell, 0, 2, 0));
	}

	/**
	 * Whether no enderman can stand beside {@code pit} low enough to overlap the body standing in it: none of the eight
	 * neighbouring columns has room for one with its feet at the pit's level or up to two below. One a level higher (on the
	 * ground beside a dug pit) does overlap the body, but its eyes cannot see the body's eyes under the roof, and vanilla
	 * melee needs that line of sight; one two levels higher is clear of the body altogether.
	 */
	static boolean ringSafe(Cells cells, GoalPosition pit) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				for (int dy = -2; dy <= 0; dy++) if (endermanStands(cells, offset(pit, dx, dy, dz))) return false;
			}
		}
		return true;
	}

	/**
	 * The pit for a body standing with its feet in {@code stand}: dig the block under it (unless already dug), then place a
	 * roof over the head in the pit, against a solid neighbour of the roof cell or on a two-block column built beside it.
	 * Every placement happens from inside the pit. Null with the reason when the cell does not fit.
	 */
	static PitCheck pit(Cells cells, GoalPosition stand, int blocks) {
		GoalPosition pit = offset(stand, 0, -1, 0);
		GoalPosition roof = offset(stand, 0, 1, 0);
		boolean dug = cells.passable(pit);
		if (dug) {
			if (cells.fluid(pit)) return PitCheck.refused("fluid");
		} else {
			if (!cells.solid(pit)) return PitCheck.refused("no_ground");
			if (!cells.diggable(pit)) return PitCheck.refused("undiggable");
			if (!room(cells, stand)) return PitCheck.refused("no_room");
		}
		if (!cells.passable(stand) || cells.fluid(stand)) return PitCheck.refused("no_room");
		GoalPosition floor = offset(pit, 0, -1, 0);
		if (!cells.solid(floor) || cells.fluid(floor)) return PitCheck.refused("no_floor");
		if (!cells.passable(roof) || cells.fluid(roof)) return PitCheck.refused("no_headroom");
		for (int[] side : HORIZONTAL) {
			for (GoalPosition cell : List.of(pit, stand, roof))
				if (cells.fluid(offset(cell, side[0], 0, side[1]))) return PitCheck.refused("fluid");
		}
		if (cells.fluid(offset(roof, 0, 1, 0))) return PitCheck.refused("fluid");
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				for (int y = floor.y(); y <= roof.y(); y++)
					if (cells.hazard(new GoalPosition(pit.x() + dx, y, pit.z() + dz, true))) return PitCheck.refused("lava_or_fire");
		if (!ringSafe(cells, pit)) return PitCheck.refused("open_ring");
		List<BuildStep> placements = new ArrayList<>();
		GoalPosition support = null;
		for (int[] side : HORIZONTAL) {
			GoalPosition next = offset(roof, side[0], 0, side[1]);
			if (cells.solid(next)) {
				support = next;
				break;
			}
		}
		if (support == null && cells.solid(offset(roof, 0, 1, 0))) support = offset(roof, 0, 1, 0);
		if (support == null) {
			for (int[] side : HORIZONTAL) {
				GoalPosition top = offset(roof, side[0], 0, side[1]);
				GoalPosition low = offset(stand, side[0], 0, side[1]);
				GoalPosition base = offset(pit, side[0], 0, side[1]);
				if (!cells.passable(top) || cells.fluid(top)) continue;
				if (cells.solid(low)) {
					placements.add(new BuildStep(BuildKind.PLACE, top, low));
				} else if (cells.passable(low) && !cells.fluid(low) && cells.solid(base)) {
					placements.add(new BuildStep(BuildKind.PLACE, low, base));
					placements.add(new BuildStep(BuildKind.PLACE, top, low));
				} else continue;
				support = top;
				break;
			}
		}
		if (support == null) return PitCheck.refused("no_roof_support");
		placements.add(new BuildStep(BuildKind.PLACE, roof, support));
		int available = blocks + (!dug && cells.yieldsBlock(pit) ? 1 : 0);
		if (available < placements.size()) return PitCheck.refused("too_few_blocks");
		return new PitCheck(new SpotPlan(stand, pit, roof, List.copyOf(placements), dug), null);
	}

	/**
	 * The nearest pit site within {@link #SPOT_RADIUS} blocks of {@code feet}, cheapest first: the body's own cell before
	 * one it walks to, and fewer blocks to place.
	 */
	static Optional<SpotPlan> planSpot(Cells cells, GoalPosition feet, int blocks, Set<GoalPosition> rejected) {
		SpotPlan best = null;
		double bestCost = Double.MAX_VALUE;
		for (int dy : new int[] {0, -1, 1, -2, 2}) {
			for (int dx = -SPOT_RADIUS; dx <= SPOT_RADIUS; dx++) {
				for (int dz = -SPOT_RADIUS; dz <= SPOT_RADIUS; dz++) {
					double horizontal = Math.hypot(dx, dz);
					if (horizontal > SPOT_RADIUS + 0.5D) continue;
					GoalPosition stand = offset(feet, dx, dy, dz);
					if (rejected.contains(stand)) continue;
					PitCheck check = pit(cells, stand, blocks);
					if (check.plan() == null) continue;
					double cost = horizontal + Math.abs(dy) + check.plan().placements().size() * 2.0D;
					if (cost < bestCost) {
						bestCost = cost;
						best = check.plan();
					}
				}
			}
		}
		return Optional.ofNullable(best);
	}

	/** What to do next for the pit: walk, dig, step in, place, or nothing (it is closed with the body inside). */
	static BuildAction buildAction(SpotPlan spot, Cells cells, Vec3 feet, boolean onGround) {
		GoalPosition pit = spot.pit();
		if (!cells.passable(pit)) {
			if (!inCell(feet, spot.stand())) return new BuildAction(BuildKind.NAVIGATE, spot.stand(), null);
			return new BuildAction(BuildKind.DIG, pit, null);
		}
		if (!inCell(feet, pit)) {
			boolean overPit = (int) Math.floor(feet.x) == pit.x() && (int) Math.floor(feet.z) == pit.z() && feet.y > pit.y() + 0.5D
				&& feet.y < spot.stand().y() + 0.5D;
			if (overPit) return onGround ? new BuildAction(BuildKind.NUDGE, pit, null) : new BuildAction(BuildKind.WAIT, pit, null);
			// Walking in under a placed roof is impossible: open it from outside first.
			if (cells.solid(spot.roof())) return new BuildAction(BuildKind.BREAK_ROOF, spot.roof(), null);
			return new BuildAction(BuildKind.NAVIGATE, pit, null);
		}
		for (BuildStep step : spot.placements()) if (!cells.solid(step.cell())) return new BuildAction(BuildKind.PLACE, step.cell(), step.support());
		return new BuildAction(BuildKind.READY, null, null);
	}

	/** Whether the body stands in the pit with the roof over it. */
	static boolean safeIn(SpotPlan spot, Cells cells, Vec3 feet) {
		return inCell(feet, spot.pit()) && cells.solid(spot.roof());
	}

	static boolean inCell(Vec3 feet, GoalPosition cell) {
		return (int) Math.floor(feet.x) == cell.x() && (int) Math.floor(feet.z) == cell.z() && Math.abs(feet.y - cell.y()) < 0.5D;
	}

	private static boolean room(Cells cells, GoalPosition stand) {
		GoalPosition head = offset(stand, 0, 1, 0);
		return cells.passable(stand) && cells.passable(head) && !cells.fluid(stand) && !cells.fluid(head);
	}

	private static String pitRefusals(Cells cells, GoalPosition feet, int blocks) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		int candidates = 0;
		for (int dy = -2; dy <= 2; dy++) {
			for (int dx = -SPOT_RADIUS; dx <= SPOT_RADIUS; dx++) {
				for (int dz = -SPOT_RADIUS; dz <= SPOT_RADIUS; dz++) {
					if (Math.hypot(dx, dz) > SPOT_RADIUS + 0.5D) continue;
					GoalPosition stand = offset(feet, dx, dy, dz);
					// Only cells a body could stand in are worth a reason.
					if (!room(cells, stand) || cells.passable(offset(stand, 0, -1, 0))) continue;
					candidates++;
					String reason = pit(cells, stand, blocks).refusal();
					if (reason != null) counts.merge(reason, 1, Integer::sum);
				}
			}
		}
		StringBuilder text = new StringBuilder("candidates=" + candidates + " blocks=" + blocks);
		counts.forEach((reason, n) -> text.append(' ').append(reason).append('=').append(n));
		return text.toString();
	}

	private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private static GoalPosition offset(GoalPosition position, int dx, int dy, int dz) {
		return new GoalPosition(position.x() + dx, position.y() + dy, position.z() + dz, true);
	}

	static GoalPosition cell(Vec3 feet) {
		return new GoalPosition((int) Math.floor(feet.x), (int) Math.floor(feet.y + 0.2D), (int) Math.floor(feet.z), true);
	}

	// ---- navigation and actuators ----

	private boolean navigate(GoalPosition goal) {
		if (navigationRestTick == activeTicks) return true;
		if (!navigationOwned || !goal.equals(navigationGoal)) {
			stopNavigation();
			NavigationRelease.release(navigation);
			navigation.pollPathEvent();
			navigation.startNavigate(goal, OPTIONS);
			navigationOwned = true;
			navigationGoal = goal;
			navigationTicks = 0;
		}
		navigationTicks++;
		String event = navigation.pollPathEvent().orElse("");
		var progress = navigation.navigationProgress();
		if (progress.isEmpty()) navigationStall.clear();
		else if (navigationStall.observe(activeTicks, progress.get())) {
			stopNavigation();
			navigationStall.clear();
			navigationRestTick = activeTicks;
			return false;
		}
		return !event.contains("CALC_FAILED") && !event.equals("CANCELED");
	}

	private void stopNavigation() {
		if (navigationOwned) navigation.cancel();
		navigationOwned = false;
		navigationGoal = null;
		navigationTicks = 0;
	}

	private void stopDigging() {
		if (digging) environment.stopDigging();
		digging = false;
	}

	private void stopConsuming() {
		if (consuming) environment.stopConsuming();
		consuming = false;
	}

	private void lowerGuard() {
		if (guarding) environment.lowerShield();
		guarding = false;
	}

	private void release() {
		stopConsuming();
		stopDigging();
		lowerGuard();
		stopNavigation();
		environment.release();
	}

	private void reset(int requested) {
		terminal = null;
		emitted = false;
		mode = Mode.SEARCH;
		activeTicks = modeTicks = idleTicks = 0;
		count = requested;
		pearls = 0;
		startPearls = -1;
		everSeen = false;
		spot = null;
		rejectedSpots.clear();
		spotFailures = 0;
		openFight = false;
		openReason = "";
		actionKey = null;
		actionTicks = 0;
		stareTarget = -1;
		stareTicks = 0;
		stareCooldown.clear();
		knownEndermen.clear();
		angryEndermen.clear();
		pickupTarget = null;
		pickupTicks = 0;
		skippedPearls.clear();
		nearDropTicks = 0;
		kills = hits = provoked = exits = eats = 0;
		consuming = guarding = digging = false;
		navigationStall.clear();
		navigationRestTick = -1;
		lastAction = "none";
	}

	private String where() {
		return spot != null ? " pit=" + compact(spot.pit()) : openFight ? " pit=none (" + openReason + ")" : "";
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason, String detail, TaskFailureCode failureCode) {
		release();
		String summary = reason + " pearls=" + pearls + (detail.isEmpty() ? "" : "; " + detail)
			+ " requested=" + count + " gained=" + Math.max(0, pearls - Math.max(0, startPearls)) + " kills=" + kills
			+ " hits=" + hits + " angered=" + provoked + " pitExits=" + exits + " eaten=" + eats + " pearlsLeft=" + skippedPearls.size()
			+ where();
		terminal = new TaskTerminalEvent(request.taskId(), null, success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED,
			summary, success ? TaskTerminationCause.GOAL_REACHED : null, success ? null : failureCode);
		return finishRelease();
	}

	private Optional<TaskTerminalEvent> finishRelease() {
		release();
		NavigationRelease.release(navigation);
		snapshot = new TaskExecutionSnapshot(terminal.terminalState(), request.taskId(), null, "CollectEnderPearls",
			terminal.message(), null, terminal.terminationCause());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}

	private void setSnapshot(TaskExecutionState state, String reason, Arena arena) {
		String detail = reason + " mode=" + mode + " action=" + lastAction + " pearls=" + pearls + "/" + count;
		if (arena != null) {
			detail += String.format(Locale.ROOT, " feet=%.1f,%.1f,%.1f health=%.1f food=%d", arena.feet().x, arena.feet().y,
				arena.feet().z, arena.health(), arena.food())
				+ " endermenInSight=" + arena.endermen().stream().filter(Enderman::visible).count()
				+ " angry=" + arena.endermen().stream().filter(Enderman::angry).count() + " droppedPearls=" + arena.drops().size();
		}
		detail += " kills=" + kills + " hits=" + hits + " angered=" + provoked + where() + " activeTicks=" + activeTicks;
		snapshot = new TaskExecutionSnapshot(state, request.taskId(), null, "CollectEnderPearls", detail, null, null);
	}

	private static Enderman nearest(List<Enderman> endermen, java.util.function.Predicate<Enderman> filter) {
		return endermen.stream().filter(filter).min(Comparator.comparingDouble(Enderman::distance)).orElse(null);
	}

	private static String compact(GoalPosition position) {
		return position.x() + "," + position.y() + "," + position.z();
	}

	@Override public TaskExecutionSnapshot snapshot() { return snapshot; }
	/** The reflex hands the same task back: keep the pit, rejected spots and the counters; let go of digging, shield, meal and
	 * route, which the next tick takes up again. */
	@Override
	public void pauseForReflex() {
		if (request == null) return;
		release();
		navigationStall.clear();
		navigationRestTick = -1;
		// The body may have moved: the next build step and stare start their timers afresh.
		actionKey = null;
		actionTicks = 0;
		stare(null);
		knownEndermen.clear();
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override public void onWorldLeave() {
		release();
		request = null;
		snapshot = TaskExecutionSnapshot.idle();
	}
	@Override public void shutdown() { onWorldLeave(); }

	enum Mode { SEARCH, BUILD, WAIT, EXIT, PICKUP, OPEN, HEAL }
	enum Ending { SUCCEEDED, WRONG_DIMENSION, RAINING, TIMEOUT }
	enum Dimension { OVERWORLD, NETHER, END, OTHER }
	enum BuildKind { NAVIGATE, DIG, WAIT, NUDGE, BREAK_ROOF, PLACE, READY }
	enum MeleeResult { OUT_OF_REACH, AIMING, COOLDOWN, HIT }
	enum PlaceResult { WORKING, PLACED, BLOCKED, NO_BLOCK }
	enum BreakResult { WORKING, GONE, UNBREAKABLE, OUT_OF_REACH }
	enum ConsumeResult { NOTHING, CONSUMING, DONE }

	/**
	 * A live enderman. {@code position} is its feet; {@code distance} is from the body's eyes to its middle; {@code visible}
	 * is a clear line between the eyes (what both staring and its own melee need); {@code inReach} means its legs can be hit
	 * from here; {@code angry} is its client-side "creepy" flag, set while it has a target.
	 */
	record Enderman(int id, Vec3 position, double distance, boolean visible, boolean inReach, boolean angry) {}
	/** A dropped ender pearl; {@code safe} when it rests on solid ground with no lava or fire beside it and no drop under it. */
	record DroppedPearl(Vec3 position, boolean safe) {}
	/** One tick of the hunt as the client sees it. {@code buildingBlocks}: carried blocks an enderman cannot pick up. */
	record Arena(Dimension dimension, boolean raining, boolean daytime, Vec3 feet, boolean onGround, float health, int food,
		int pearls, List<Enderman> endermen, List<DroppedPearl> drops, boolean hasShield, boolean hasFood, int buildingBlocks) {}
	record BuildStep(BuildKind kind, GoalPosition cell, GoalPosition support) {}
	record BuildAction(BuildKind kind, GoalPosition cell, GoalPosition support) {}
	/**
	 * A pit: the body digs {@code pit} out from {@code stand} (unless {@code dug}), drops into it, then places
	 * {@code placements} in order, the roof last.
	 */
	record SpotPlan(GoalPosition stand, GoalPosition pit, GoalPosition roof, List<BuildStep> placements, boolean dug) {}
	record PitCheck(SpotPlan plan, String refusal) {
		static PitCheck refused(String reason) { return new PitCheck(null, reason); }
	}

	/** The block facts the pit choice reads. */
	interface Cells {
		/** A full block with collision. */
		boolean solid(GoalPosition cell);
		/** No collision at all (air, plants, fluids); false where the chunk is not loaded. */
		boolean passable(GoalPosition cell);
		boolean fluid(GoalPosition cell);
		/** Lava, fire or magma. */
		boolean hazard(GoalPosition cell);
		/** Breaks quickly with a carried tool, does not fall, holds no block entity and is not in a preserved place. */
		boolean diggable(GoalPosition cell);
		/** Breaking it puts a block in the inventory that an enderman cannot pick up (cobblestone, netherrack, end stone). */
		boolean yieldsBlock(GoalPosition cell);
	}

	interface Environment {
		/** Null when no world is loaded. */
		Arena observe();
		/** The live block facts, or null when no world is loaded. */
		Cells cells();
		/** Holds a carried block an enderman cannot pick up and places it in {@code cell} against {@code support}. */
		PlaceResult place(GoalPosition cell, GoalPosition support);
		/** Breaks {@code cell} with the best carried tool; GONE once it has no collision. */
		BreakResult dig(GoalPosition cell);
		void stopDigging();
		/** Steps toward the middle of {@code cell} for one tick. */
		void nudge(GoalPosition cell);
		/** Hits the enderman's legs with the best melee weapon; with {@code guard}, holds the shield up while the swing recharges. */
		MeleeResult melee(int endermanId, boolean guard);
		/** Looks the enderman in the eyes, which makes it angry. */
		void stare(int endermanId);
		/** Looks at the enderman's legs, never its eyes; with {@code guard}, holds the shield up toward it. */
		void watch(int endermanId, boolean guard);
		void lowerShield();
		/** Eats one tick of the best carried food; DONE when an item was used up. */
		ConsumeResult consume();
		void stopConsuming();
		void release();
	}
}
