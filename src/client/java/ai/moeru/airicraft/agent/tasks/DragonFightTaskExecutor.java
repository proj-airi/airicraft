package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
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
	static final int DRAGON_MISSING_TICKS = 100;
	static final int FULL_DRAW_TICKS = 22;
	static final int MAX_AIM_TICKS = 60;
	static final int SHOTS_PER_SPOT = 3;
	static final int SPOTS_PER_CRYSTAL = 3;
	static final int NAVIGATION_TIMEOUT_TICKS = 600;

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
	private int fewestCrystals = Integer.MAX_VALUE;
	private int crystalsDestroyed, headHits, arrowsShot, dodges;

	private String crystalTarget;
	private GoalPosition shootingSpot;
	private final Set<GoalPosition> rejectedSpots = new HashSet<>();
	private final Map<String, Integer> spotsTried = new LinkedHashMap<>();
	private final Set<String> skippedCrystals = new HashSet<>();
	private int shotsFromSpot, drawTicks, arrowWaitTicks;
	private boolean waitingSpotReached;

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

		Dragon dragon = fight.dragon();
		if (dragon == null || !dragon.alive()) {
			if (dragon != null || lowestDragonHealth <= 0.0F) return finish(true, "dragon_defeated", null);
			if (++missingDragonTicks > DRAGON_MISSING_TICKS)
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

		if (DragonHazards.threatened(fight.feet(), fight.clouds(), fight.fireballs())) dodge(fight, dragon);
		else if (dragon != null && dragon.perched()) meleeHead(fight, dragon);
		else if (!shootCrystal(fight)) waitNearPortal(fight);
		setSnapshot(TaskExecutionState.RUNNING, "fighting", fight);
		return Optional.empty();
	}

	private void observeProgress(Fight fight) {
		boolean progress = false;
		if (fight.dragon() != null && fight.dragon().health() < lowestDragonHealth - 0.5F) {
			progress = lowestDragonHealth != Float.MAX_VALUE;
			lowestDragonHealth = fight.dragon().health();
		}
		int alive = (int) fight.crystals().stream().count();
		if (alive < fewestCrystals) {
			if (fewestCrystals != Integer.MAX_VALUE) {
				crystalsDestroyed += fewestCrystals - alive;
				progress = true;
			}
			fewestCrystals = alive;
		}
		if (progress) lastProgressTick = activeTicks;
	}

	private void dodge(Fight fight, Dragon dragon) {
		stopBow();
		if (phase != Phase.DODGE) {
			dodges++;
			stopNavigation();
		}
		phase = Phase.DODGE;
		if (navigationOwned && navigationGoal != null && navigationTicks < 60
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
		navigate(approach.get(), 1);
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
				.filter(c -> !c.caged() && !skippedCrystals.contains(c.id()))
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
				skip(target, "no_clear_shot");
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
		Optional<GoalPosition> spot = environment.waitingSpot(fight);
		if (spot.isEmpty() || atSpot(fight.feet(), spot.get())) {
			stopNavigation();
			waitingSpotReached = true;
			return;
		}
		if (!navigate(spot.get(), 2) || navigationTicks > NAVIGATION_TIMEOUT_TICKS) {
			stopNavigation();
			waitingSpotReached = true;
		}
		else if (navigation.navigationGoalReached(spot.get())) {
			stopNavigation();
			waitingSpotReached = true;
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
		skippedCrystals.add(target.id());
		crystalTarget = null;
		shootingSpot = null;
		stopBow();
		stopNavigation();
	}

	private boolean navigate(GoalPosition goal, int radius) {
		if (!navigationOwned || !goal.equals(navigationGoal)) {
			stopNavigation();
			NavigationRelease.release(navigation);
			navigation.pollPathEvent();
			if (radius > 0) navigation.startNavigateNear(goal, radius);
			else navigation.startNavigate(goal);
			navigationOwned = true;
			navigationGoal = goal;
			navigationTicks = 0;
		}
		navigationTicks++;
		String event = navigation.pollPathEvent().orElse("");
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
		fewestCrystals = Integer.MAX_VALUE;
		crystalsDestroyed = headHits = arrowsShot = dodges = 0;
		crystalTarget = null;
		shootingSpot = null;
		rejectedSpots.clear();
		spotsTried.clear();
		skippedCrystals.clear();
		shotsFromSpot = drawTicks = arrowWaitTicks = 0;
		waitingSpotReached = false;
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason, TaskFailureCode failureCode) {
		release();
		String summary = reason + " crystalsDestroyed=" + crystalsDestroyed + " headHits=" + headHits + " arrowsShot=" + arrowsShot
			+ " dodges=" + dodges + (skippedCrystals.isEmpty() ? "" : " crystalsSkipped=" + skippedCrystals.size());
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
			Dragon dragon = fight.dragon();
			detail += " dragonHealth=" + (dragon == null ? "unknown" : dragon.health())
				+ " dragonPhase=" + (dragon == null ? "unknown" : dragon.phase())
				+ " crystalsAlive=" + fight.crystals().size()
				+ " caged=" + fight.crystals().stream().filter(Crystal::caged).count();
		}
		detail += " crystalsDestroyed=" + crystalsDestroyed + " skipped=" + skippedCrystals.size() + " headHits=" + headHits
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
	enum AimResult { NO_SOLUTION, AIMING, ALIGNED }
	enum MeleeResult { OUT_OF_REACH, AIMING, COOLDOWN, HIT }

	/** What the dragon is doing. {@code head} is the head part's centre; it takes full melee damage. */
	record Dragon(boolean alive, boolean dying, boolean perched, float health, String phase, Vec3 position, Vec3 head) {}
	/** A live end crystal. {@code position} is its base; a cage of iron bars stops arrows. */
	record Crystal(String id, Vec3 position, boolean caged) {}
	record Fight(boolean inEnd, Vec3 feet, float health, Dragon dragon, List<Crystal> crystals,
		List<DragonHazards.Cloud> clouds, List<DragonHazards.Fireball> fireballs, boolean hasBowAndArrows) {}

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
