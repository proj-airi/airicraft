package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Fights the Ender Dragon without waiting on the planner, whose turns are slower than the dragon.
 * Every tick, in order: step out of breath clouds and fireball impacts; when the dragon perches on the
 * exit portal, hit its head; otherwise shoot the end crystals that heal it, with a bow from the ground;
 * otherwise wait near the portal. Crystals that a cage or terrain hides from every reachable spot are
 * skipped and reported. The task ends when the dragon dies, or hands back to the planner with a reason.
 */
public final class DragonFightTaskExecutor implements WorldTaskExecutor {
	static final float LOW_HEALTH = 6.0F;
	static final int FIGHT_TIMEOUT_TICKS = 24_000;
	/** No crystal destroyed and no damage dealt for this long: the approach is not working. */
	static final int NO_PROGRESS_TICKS = 6_000;
	static final int DRAGON_MISSING_TICKS = 600;
	static final int FULL_DRAW_TICKS = 22;
	static final int MAX_AIM_TICKS = 60;
	static final int SHOTS_PER_SPOT = 3;
	static final int SPOTS_PER_CRYSTAL = 3;
	static final int NAVIGATION_TIMEOUT_TICKS = 600;
	static final double CRYSTAL_VIEW_DISTANCE = 96.0D;
	static final int MAX_APPROACH_FAILURES = 5;
	static final double RETRY_BLOCKED_DISTANCE = 16.0D;
	/** Waiting by the portal never moves 16 blocks; look for a shot at blocked crystals again after this long. */
	static final int RETRY_BLOCKED_TICKS = 600;
	/** Gives up on a route before the dispatcher's 100-tick stall watchdog would end the whole fight. */
	static final long NAVIGATION_STALL_TICKS = 70;
	/** Once dodging, finish the step before turning back, so hazards at the edge of the margin do not flap. */
	static final int DODGE_SETTLE_TICKS = 20;

	private final NavigationFacade navigation;
	private final Environment environment;
	private WorldTaskRequest request;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	private Phase phase = Phase.WAIT;
	private int activeTicks, lastProgressTick, missingDragonTicks, navigationTicks;
	private GoalPosition navigationGoal;
	private boolean navigationOwned;
	private float lowestDragonHealth = Float.MAX_VALUE;
	/** Crystals seen so far, by id; one that vanishes while its spot is in view was destroyed, not unloaded. */
	private final Map<String, Vec3> knownCrystals = new LinkedHashMap<>();
	private int crystalsDestroyed, headHits, arrowsShot, dodges;

	private String crystalTarget;
	private GoalPosition shootingSpot;
	private final Set<GoalPosition> rejectedSpots = new HashSet<>();
	private final Map<String, Integer> spotsTried = new LinkedHashMap<>();
	private final Map<String, String> skippedCrystals = new LinkedHashMap<>();
	/** Crystals with no clear shot from here; retried once the player has moved on. */
	private final Map<String, Blocked> blockedCrystals = new LinkedHashMap<>();
	private int shotsFromSpot, drawTicks, arrowWaitTicks;
	private boolean waitingSpotReached;
	/** Failed routes toward the portal since the player last got anywhere. */
	private int approachFailures;
	private Vec3 approachFailedFrom;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);
	/** The tick a stalled route was dropped; no route starts in the same tick, so the dispatcher's watchdog sees it end. */
	private int navigationRestTick = -1;
	private int calmTicks;
	/** Last seen standing on the island; routes then walk only, never bridging out into the dragon's reach over the void. */
	private boolean onIsland;

	public DragonFightTaskExecutor(NavigationFacade navigation, ai.moeru.airicraft.agent.control.CameraController camera) {
		this(navigation, new MinecraftDragonFightEnvironment(camera));
	}

	DragonFightTaskExecutor(NavigationFacade navigation, Environment environment) {
		this.navigation = Objects.requireNonNull(navigation, "navigation");
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.FIGHT_ENDER_DRAGON) {
			if (request != null) release();
			request = null;
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest next = activeTask.get();
		if (request == null || !request.taskId().equals(next.taskId())) {
			release();
			request = next;
			resetFight();
		}
		if (terminal != null) return finishRelease();
		if (session == null || !session.companionActuationAllowed()) {
			release();
			setSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, "session_gate", null);
			return Optional.empty();
		}
		if (!navigation.isLoaded()) return finish(false, "navigation_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED);
		Fight fight = environment.observe();
		if (fight == null) return finish(false, "world_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED);
		if (!fight.inEnd()) return finish(false, "not_in_the_end", TaskFailureCode.INVALID_ACTION);
		activeTicks++;
		if (fight.onEndStone() != null) onIsland = fight.onEndStone();

		Dragon dragon = fight.dragon();
		if (dragon == null || !dragon.alive()) {
			if (dragon != null || lowestDragonHealth <= 0.0F) return finish(true, "dragon_defeated", null);
			// The dragon circles far from the portal and drops out of entity range; that is not absence.
			if (!fight.awayFromPortal() && ++missingDragonTicks > DRAGON_MISSING_TICKS)
				return finish(false, "dragon_not_found; no living dragon is loaded nearby, walk toward the exit portal at x=0 z=0 first",
					TaskFailureCode.MISSING_FACT);
		} else missingDragonTicks = 0;
		if (dragon != null && dragon.dying()) return finish(true, "dragon_defeated", null);
		observeProgress(fight);
		if (fight.health() <= LOW_HEALTH)
			return finish(false, "low_health health=" + fight.health() + "; eat or retreat out of reach of the dragon, then call fight_ender_dragon again",
				TaskFailureCode.ENVIRONMENT_CHANGED);
		if (activeTicks > FIGHT_TIMEOUT_TICKS) return finish(false, "fight_timeout", TaskFailureCode.TRANSIENT);
		if (activeTicks - lastProgressTick > NO_PROGRESS_TICKS)
			return finish(false, "no_progress; no crystal destroyed and no damage dealt for " + NO_PROGRESS_TICKS + " ticks",
				TaskFailureCode.ENVIRONMENT_CHANGED);

		boolean threatened = DragonHazards.threatened(fight.feet(), fight.clouds(), fight.fireballs());
		calmTicks = threatened ? 0 : calmTicks + 1;
		if (threatened || settlingDodge(fight)) dodge(fight, dragon);
		else if (dragon != null && dragon.perched()) meleeHead(fight, dragon);
		else if (!shootCrystal(fight)) waitNearPortal(fight);
		if (approachFailures >= MAX_APPROACH_FAILURES)
			return finish(false, "portal_unreachable; no walkable or bridgeable route toward the exit portal at x=0 z=0 from here. "
				+ "Carry building blocks so navigation can bridge from the arrival platform, then call again", TaskFailureCode.MISSING_ITEM);
		if (!navigationOwned) navigationStall.clear();
		setSnapshot(TaskExecutionState.RUNNING, "fighting", fight);
		return Optional.empty();
	}

	/** Whether to keep walking to the escape spot after the threat has passed. */
	private boolean settlingDodge(Fight fight) {
		return phase == Phase.DODGE && calmTicks < DODGE_SETTLE_TICKS && navigationOwned && navigationGoal != null
			&& !atSpot(fight.feet(), navigationGoal) && !navigation.navigationGoalReached(navigationGoal);
	}

	private void observeProgress(Fight fight) {
		boolean progress = false;
		if (fight.dragon() != null && fight.dragon().health() < lowestDragonHealth - 0.5F) {
			progress = lowestDragonHealth != Float.MAX_VALUE;
			lowestDragonHealth = fight.dragon().health();
		}
		Set<String> alive = new HashSet<>();
		for (Crystal crystal : fight.crystals()) {
			alive.add(crystal.id());
			knownCrystals.put(crystal.id(), crystal.position());
		}
		var vanished = knownCrystals.entrySet().iterator();
		while (vanished.hasNext()) {
			var known = vanished.next();
			if (alive.contains(known.getKey())) continue;
			vanished.remove();
			if (horizontalDistance(fight.feet(), known.getValue()) <= CRYSTAL_VIEW_DISTANCE) {
				crystalsDestroyed++;
				progress = true;
			}
		}
		blockedCrystals.values().removeIf(blocked -> horizontalDistance(fight.feet(), blocked.from()) > RETRY_BLOCKED_DISTANCE
			|| activeTicks - blocked.tick() > RETRY_BLOCKED_TICKS);
		if (progress) lastProgressTick = activeTicks;
	}

	private void dodge(Fight fight, Dragon dragon) {
		stopBow();
		if (phase != Phase.DODGE) {
			dodges++;
			stopNavigation();
		}
		phase = Phase.DODGE;
		if (navigationOwned && navigationGoal != null && (navigationTicks < 60 || calmTicks > 0)
			&& !DragonHazards.threatened(center(navigationGoal), fight.clouds(), fight.fireballs())) {
			navigate(navigationGoal, 0);
			return;
		}
		stopNavigation();
		environment.escapePosition(fight, dragon == null ? null : dragon.head()).ifPresent(goal -> navigate(goal, 0));
	}

	private void meleeHead(Fight fight, Dragon dragon) {
		stopBow();
		if (phase != Phase.PERCH) stopNavigation();
		phase = Phase.PERCH;
		MeleeResult result = environment.meleeHead();
		if (result == MeleeResult.HIT) headHits++;
		if (result != MeleeResult.OUT_OF_REACH) {
			stopNavigation();
			return;
		}
		Optional<GoalPosition> approach = environment.perchApproach(fight, dragon);
		if (approach.isEmpty()) return;
		if (navigationOwned && navigationGoal != null && squaredDistance(navigationGoal, approach.get()) > 4) stopNavigation();
		navigate(approach.get(), 0);
	}

	/** Whether a crystal is being worked on; false when none can be shot. */
	private boolean shootCrystal(Fight fight) {
		if (!fight.hasBowAndArrows()) return false;
		Crystal target = fight.crystals().stream().filter(c -> c.id().equals(crystalTarget)).findFirst().orElse(null);
		if (target == null) {
			if (crystalTarget != null) {
				stopBow();
				stopNavigation();
			}
			crystalTarget = null;
			shootingSpot = null;
			target = fight.crystals().stream()
				.filter(c -> !c.caged() && !skippedCrystals.containsKey(c.id()) && !blockedCrystals.containsKey(c.id()))
				.min(java.util.Comparator.comparingDouble(c -> horizontalDistance(fight.feet(), c.position())))
				.orElse(null);
			if (target == null) return false;
			crystalTarget = target.id();
			shotsFromSpot = drawTicks = arrowWaitTicks = 0;
		}
		if (phase != Phase.CRYSTAL) stopNavigation();
		phase = Phase.CRYSTAL;
		if (shootingSpot == null) {
			shootingSpot = environment.shootingSpot(fight, target, rejectedSpots).orElse(null);
			shotsFromSpot = 0;
			if (shootingSpot == null) {
				// Terrain may be unloaded or in the way from here; try again after moving.
				blockedCrystals.put(target.id(), new Blocked(fight.feet(), activeTicks));
				crystalTarget = null;
				return true;
			}
		}
		if (!atSpot(fight.feet(), shootingSpot)) {
			stopBow();
			if (!navigate(shootingSpot, 0) || navigationTicks > NAVIGATION_TIMEOUT_TICKS) rejectSpot(target);
			return true;
		}
		stopNavigation();
		if (arrowWaitTicks > 0) {
			if (--arrowWaitTicks == 0 && ++shotsFromSpot >= SHOTS_PER_SPOT) rejectSpot(target);
			return true;
		}
		AimResult aim = environment.aimBow(target);
		if (aim == AimResult.NO_SOLUTION) {
			stopBow();
			rejectSpot(target);
			return true;
		}
		if (!environment.drawBow()) {
			skip(target, "bow_unavailable");
			return true;
		}
		drawTicks++;
		if (drawTicks >= FULL_DRAW_TICKS && (aim == AimResult.ALIGNED || drawTicks >= MAX_AIM_TICKS)) {
			int flight = environment.releaseBow();
			arrowsShot++;
			drawTicks = 0;
			arrowWaitTicks = Math.max(10, flight + 10);
		}
		return true;
	}

	private void waitNearPortal(Fight fight) {
		stopBow();
		if (phase != Phase.WAIT) {
			stopNavigation();
			waitingSpotReached = false;
		}
		phase = Phase.WAIT;
		if (waitingSpotReached) return;
		// Keep walking to the chosen spot; a step toward the portal is chosen afresh only after it ends.
		Optional<GoalPosition> spot = navigationOwned && navigationGoal != null
			? Optional.of(navigationGoal) : environment.waitingSpot(fight);
		if (spot.isEmpty()) {
			stopNavigation();
			return;
		}
		if (atSpot(fight.feet(), spot.get())) {
			stopNavigation();
			waitingSpotReached = !fight.awayFromPortal();
			return;
		}
		if (!navigate(spot.get(), 2) || navigationTicks > NAVIGATION_TIMEOUT_TICKS) {
			stopNavigation();
			waitingSpotReached = !fight.awayFromPortal();
			if (fight.awayFromPortal()) {
				if (approachFailedFrom == null || approachFailedFrom.distanceTo(fight.feet()) > 4.0D) approachFailures = 0;
				approachFailedFrom = fight.feet();
				approachFailures++;
			}
		}
		else if (navigation.navigationGoalReached(spot.get())) {
			stopNavigation();
			waitingSpotReached = !fight.awayFromPortal();
		}
	}

	private void rejectSpot(Crystal target) {
		stopBow();
		stopNavigation();
		if (shootingSpot != null) rejectedSpots.add(shootingSpot);
		shootingSpot = null;
		if (spotsTried.merge(target.id(), 1, Integer::sum) >= SPOTS_PER_CRYSTAL) skip(target, "missed_from_" + SPOTS_PER_CRYSTAL + "_spots");
	}

	private void skip(Crystal target, String reason) {
		skippedCrystals.put(target.id(), reason);
		crystalTarget = null;
		shootingSpot = null;
		stopBow();
		stopNavigation();
	}

	private boolean navigate(GoalPosition goal, int radius) {
		if (navigationRestTick == activeTicks) return true;
		if (!navigationOwned || !goal.equals(navigationGoal)) {
			stopNavigation();
			NavigationRelease.release(navigation);
			navigation.pollPathEvent();
			NavigationOptions options = onIsland ? NavigationOptions.WALK_ONLY : NavigationOptions.DEFAULT;
			if (radius > 0) navigation.startNavigateNear(goal, radius, options);
			else navigation.startNavigate(goal, options);
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

	private void stopBow() {
		if (drawTicks > 0) environment.cancelBow();
		drawTicks = 0;
	}

	private void release() {
		stopBow();
		stopNavigation();
		environment.release();
	}

	private void resetFight() {
		terminal = null;
		emitted = false;
		phase = Phase.WAIT;
		activeTicks = lastProgressTick = missingDragonTicks = navigationTicks = 0;
		lowestDragonHealth = Float.MAX_VALUE;
		knownCrystals.clear();
		blockedCrystals.clear();
		crystalsDestroyed = headHits = arrowsShot = dodges = 0;
		crystalTarget = null;
		shootingSpot = null;
		rejectedSpots.clear();
		spotsTried.clear();
		skippedCrystals.clear();
		shotsFromSpot = drawTicks = arrowWaitTicks = 0;
		waitingSpotReached = false;
		approachFailures = 0;
		approachFailedFrom = null;
		navigationStall.clear();
		navigationRestTick = -1;
		calmTicks = 0;
		onIsland = false;
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason, TaskFailureCode failureCode) {
		release();
		String summary = reason + " crystalsDestroyed=" + crystalsDestroyed + " headHits=" + headHits + " arrowsShot=" + arrowsShot
			+ " dodges=" + dodges + (skippedCrystals.isEmpty() ? "" : " crystalsSkipped=" + skippedCrystals.values());
		terminal = new TaskTerminalEvent(request.taskId(), null, success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED,
			summary, success ? TaskTerminationCause.GOAL_REACHED : null, success ? null : failureCode);
		return finishRelease();
	}

	private Optional<TaskTerminalEvent> finishRelease() {
		release();
		NavigationRelease.release(navigation);
		snapshot = new TaskExecutionSnapshot(terminal.terminalState(), request.taskId(), null, "FightEnderDragon",
			terminal.message(), null, terminal.terminationCause());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}

	private void setSnapshot(TaskExecutionState state, String reason, Fight fight) {
		String detail = reason + " phase=" + phase;
		if (fight != null) {
			detail += String.format(java.util.Locale.ROOT, " feet=%.1f,%.1f,%.1f health=%.1f", fight.feet().x, fight.feet().y,
				fight.feet().z, fight.health());
			Dragon dragon = fight.dragon();
			detail += " dragonHealth=" + (dragon == null ? "unknown" : dragon.health())
				+ " dragonPhase=" + (dragon == null ? "unknown" : dragon.phase())
				+ (dragon == null || dragon.head() == null ? "" : String.format(java.util.Locale.ROOT, " headDistance=%.1f",
					dragon.head().distanceTo(fight.feet().add(0.0D, 1.62D, 0.0D))))
				+ " crystalsAlive=" + fight.crystals().size()
				+ " caged=" + fight.crystals().stream().filter(Crystal::caged).count();
		}
		detail += " crystalsDestroyed=" + crystalsDestroyed + " skipped=" + skippedCrystals.values()
			+ " noShotFromHere=" + blockedCrystals.size() + " headHits=" + headHits
			+ " arrowsShot=" + arrowsShot + " dodges=" + dodges + " navigationGoal=" + navigationGoal + " activeTicks=" + activeTicks;
		snapshot = new TaskExecutionSnapshot(state, request.taskId(), null, "FightEnderDragon", detail, null, null);
	}

	private static boolean atSpot(Vec3 feet, GoalPosition spot) {
		return horizontalDistance(feet, center(spot)) <= 0.9D && Math.abs(feet.y - spot.y()) <= 1.0D;
	}

	private static Vec3 center(GoalPosition position) {
		return new Vec3(position.x() + 0.5D, position.y(), position.z() + 0.5D);
	}

	private static double horizontalDistance(Vec3 a, Vec3 b) {
		return Math.hypot(a.x - b.x, a.z - b.z);
	}

	private static double squaredDistance(GoalPosition a, GoalPosition b) {
		double x = (double) a.x() - b.x(), y = (double) a.y() - b.y(), z = (double) a.z() - b.z();
		return x * x + y * y + z * z;
	}

	@Override public TaskExecutionSnapshot snapshot() { return snapshot; }
	@Override public void onWorldLeave() {
		release();
		request = null;
		snapshot = TaskExecutionSnapshot.idle();
	}
	@Override public void shutdown() { onWorldLeave(); }

	enum Phase { WAIT, DODGE, PERCH, CRYSTAL }
	private record Blocked(Vec3 from, int tick) {}
	enum AimResult { NO_SOLUTION, AIMING, ALIGNED }
	enum MeleeResult { OUT_OF_REACH, AIMING, COOLDOWN, HIT }

	/** What the dragon is doing. {@code head} is the head part's centre; it takes full melee damage. */
	record Dragon(boolean alive, boolean dying, boolean perched, float health, String phase, Vec3 position, Vec3 head) {}
	/** A live end crystal. {@code position} is its base; a cage of iron bars stops arrows. */
	record Crystal(String id, Vec3 position, boolean caged) {}
	record Fight(boolean inEnd, Vec3 feet, float health, Dragon dragon, List<Crystal> crystals,
		List<DragonHazards.Cloud> clouds, List<DragonHazards.Fireball> fireballs, boolean hasBowAndArrows, Boolean onEndStone) {
		/** Farther from the exit portal than a perch can be reached from. */
		boolean awayFromPortal() { return Math.hypot(feet.x, feet.z) > 24.0D; }
	}

	interface Environment {
		/** Null when no world is loaded. */
		Fight observe();
		Optional<GoalPosition> escapePosition(Fight fight, Vec3 avoid);
		Optional<GoalPosition> perchApproach(Fight fight, Dragon dragon);
		/** A standable spot with a clear arrow path to the crystal, not in {@code rejected}. */
		Optional<GoalPosition> shootingSpot(Fight fight, Crystal crystal, Set<GoalPosition> rejected);
		Optional<GoalPosition> waitingSpot(Fight fight);
		AimResult aimBow(Crystal crystal);
		/** Holds the bow drawn; false when no bow can be held. */
		boolean drawBow();
		/** Lets the arrow fly; returns the expected flight ticks. */
		int releaseBow();
		/** Stops drawing without caring whether an arrow leaves. */
		void cancelBow();
		MeleeResult meleeHead();
		void release();
	}
}
