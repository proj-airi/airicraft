package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.control.Priority;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.tasks.MinecraftUnderwaterEscapeController;
import ai.moeru.airicraft.agent.tasks.UnderwaterEscapeNavigator;
import ai.moeru.airicraft.agent.tasks.UnderwaterEscapeSearch;
import ai.moeru.airicraft.agent.tasks.UnderwaterHarvestPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SurvivalReflexRuntime {
	private final Actuator actuator = new Actuator("reflex", Priority.REFLEX);
	static final int BREATHABLE_STABLE_TICKS = 12;
	private static final float ATTACK_READY_THRESHOLD = 0.92F;
	private static final double MELEE_ATTACK_DISTANCE = 3.0D;
	static final double MELEE_THREAT_DISTANCE = 6.0D;
	private static final int SHELTER_CONFIRM_TICKS = 200;
	private static final int MOB_ROUTE_REFRESH_TICKS = 10;
	private static final long FOOD_RETREAT_LIMIT_TICKS = 100L;

	private final AgentConfig.ReflexConfig config;
	private final MovementController movementController;
	private final CameraController cameraController;
	private final NavigationFacade navigationFacade;
	private final MinecraftUnderwaterEscapeController underwaterEscape;
	private final Map<String, ObservedThreat> observedThreats = new LinkedHashMap<>();
	private final List<SurvivalReflexEvent> pendingEvents = new ArrayList<>();

	private SurvivalReflexSnapshot snapshot = SurvivalReflexSnapshot.idle();
	private boolean drowningDamageObserved;
	private long lastMobDamageTick = Long.MIN_VALUE;
	private boolean safetyHoldActuating;
	private int secureEscapeTicks;
	private long mobRoutesTick = Long.MIN_VALUE;
	private Map<String, MobRoute> mobRoutes = Map.of();
	private CompletableFuture<Set<String>> aggroQuery;
	private GoalPosition combatTarget;
	private long combatRouteTick;
	private boolean shieldUseOwned;
	private ShieldGuard shieldGuard;
	private CombatStalemate combatStalemate;
	private CombatProgress combatProgress;
	private CombatEpisode combatEpisode;
	private final Map<String, ResolvedThreat> combatParticipants = new LinkedHashMap<>();
	private TacticalWindow tacticalWindow;
	record TacticalWindow(long untilTick) {
		boolean active(long tick, float health) { return tick < untilTick && health > 4; }
	}
	private List<ResolvedThreat> progressThreats;
	private MinecraftCombatPositioning combatPositioning;
	private String escapingCreeper;
	private String reportedCombatFocus;
	private CombatRecovery combatRecovery;
	private ReflexPolicy policyOverride;
	private long foodRetreatStartedTick = -1L;
	private boolean foodRetreatAbandoned;
	private boolean foodUnavailableReported;

	public interface CombatEating {
		boolean needed(LocalPlayer player);
		boolean hasEligibleFood(LocalPlayer player);
		java.util.Optional<String> candidate(LocalPlayer player);
		boolean ready(long tick);
		boolean eating();
		void start(Minecraft minecraft, String itemId, long tick);
		void cancel(Minecraft minecraft);
	}

	public SurvivalReflexRuntime(AgentConfig.ReflexConfig config) {
		this(config, new MovementController("reflex", Priority.REFLEX), new CameraController(), null);
	}

	public SurvivalReflexRuntime(AgentConfig.ReflexConfig config, NavigationFacade navigationFacade) {
		this(config, new MovementController("reflex", Priority.REFLEX), new CameraController(), navigationFacade);
	}

	public SurvivalReflexRuntime(AgentConfig.ReflexConfig config, NavigationFacade navigationFacade, CameraController cameraController) {
		this(config, new MovementController("reflex", Priority.REFLEX), cameraController, navigationFacade);
	}

	SurvivalReflexRuntime(
		AgentConfig.ReflexConfig config,
		MovementController movementController,
		CameraController cameraController
	) {
		this(config, movementController, cameraController, null);
	}

	SurvivalReflexRuntime(
		AgentConfig.ReflexConfig config,
		MovementController movementController,
		CameraController cameraController,
		NavigationFacade navigationFacade
	) {
		this.config = Objects.requireNonNullElseGet(config, AgentConfig.ReflexConfig::defaults);
		this.movementController = Objects.requireNonNull(movementController, "movementController");
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
		this.navigationFacade = navigationFacade;
		this.underwaterEscape = new MinecraftUnderwaterEscapeController(
			navigationFacade,
			this.movementController,
			this.cameraController
		);
	}

	public SurvivalReflexSnapshot snapshot() {
		return snapshot;
	}

	public ReflexPolicy policy() {
		return policyOverride == null ? ReflexPolicy.defaults() : policyOverride;
	}

	public ReflexPolicy configure(ReflexPolicy policy) {
		policyOverride = Objects.requireNonNull(policy);
		pendingEvents.add(new SurvivalReflexEvent("reflex.policy_changed", Map.of("policy", policy)));
		return policy;
	}

	public Map<String, Object> decisionEvidence() {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("snapshot", snapshot);
		evidence.put("policy", policy());
		evidence.put("combatTarget", combatTarget);
		evidence.put("combatStalemate", combatStalemate);
		evidence.put("combatProgress", combatProgress);
		evidence.put("tacticalWindow", tacticalWindow);
		evidence.put("escapingCreeper", escapingCreeper);
		evidence.put("combatPositioning", combatPositioning == null ? null : combatPositioning.evidence());
		evidence.put("shieldUseOwned", shieldUseOwned);
		evidence.put("combatRecovery", combatRecovery == null ? CombatRecovery.READY : combatRecovery);
		if (combatRecovery == CombatRecovery.REACH_DRY_GROUND) evidence.put("movementRecovery", underwaterEscape.snapshot());
		evidence.put("shieldGuard", shieldGuard);
		evidence.put("secureEscapeTicks", secureEscapeTicks);
		evidence.put("mobRoutesTick", mobRoutesTick);
		evidence.put("mobRoutes", Map.copyOf(mobRoutes));
		return evidence;
	}

	public void observeDamage(DamageObservation observation) {
		if (observation == null) {
			return;
		}
		if (isDrowningDamage(observation.damageTypeId())) {
			drowningDamageObserved = true;
			return;
		}
		if (!observation.attackerLiving() || observation.attackerPlayer() || observation.attackerUuid() == null) {
			return;
		}
		observedThreats.put(observation.attackerUuid(), new ObservedThreat(
			observation.attackerUuid(),
			observation.attackerName(),
			observation.attackerEntityTypeId(),
			observation.tick()
		));
		lastMobDamageTick = observation.tick();
	}

	public SurvivalReflexSnapshot tick(
		Minecraft minecraft,
		InterruptedWork interruptedWork,
		long tick,
		Runnable releaseNormalActuators
	) {
		return tick(minecraft, interruptedWork, tick, releaseNormalActuators, null);
	}

	public SurvivalReflexSnapshot tick(
		Minecraft minecraft,
		InterruptedWork interruptedWork,
		long tick,
		Runnable releaseNormalActuators,
		CombatEating combatEating
	) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (!config.enabled() || minecraft == null || minecraft.level == null || player == null || player.isDeadOrDying()) {
			reset(minecraft);
			return snapshot;
		}

		if (tacticalWindow != null && (!tacticalWindow.active(tick, player.getHealth()) || minecraft.level.getEntitiesOfClass(
			net.minecraft.world.entity.monster.Creeper.class, player.getBoundingBox().inflate(4),
			c -> c.isAlive() && c.getSwellDir() > 0 && c.getSwelling(1) >= .5F).size() > 0)) tacticalWindow = null;
		boolean drowningDanger = policy().drowningEnabled() && drowningDanger(player, config.lowAirTicks(), drowningDamageObserved);
		detectProactiveThreats(minecraft, player, tick);
		List<ResolvedThreat> threats = resolveThreats(minecraft, player);
		if (snapshot.state() == SurvivalReflexState.ACTIVE && snapshot.cause() == SurvivalReflexCause.MOB_ATTACK) {
			var targets = new LinkedHashMap<String, CombatProgress.Target>();
			for (var threat : threats) targets.put(threat.observed().uuid(),
				new CombatProgress.Target(threat.distance(), threat.entity().getHealth()));
			boolean wasStalled = combatProgress != null && combatProgress.stalled();
			boolean defeated = progressThreats != null && progressThreats.stream().anyMatch(t -> t.entity().isDeadOrDying());
			combatProgress = CombatProgress.observe(combatProgress, tick, targets, defeated);
			progressThreats = threats;
			if (combatProgress != null && combatProgress.stalled() != wasStalled) {
				pendingEvents.add(new SurvivalReflexEvent("reflex.combat_progress", Map.of(
					"stalled", combatProgress.stalled(), "noProgressTicks", tick - combatProgress.lastProgressTick(),
					"reason", combatProgress.stalled() ? "no_target_health_or_closing_progress" : "progress_resumed",
					"underPressure", immediateCombatDanger(minecraft, player, threats, tick),
					"targets", targets, "tick", tick)));
			}
		}
		else { combatProgress = null; progressThreats = null; }
		if (combatStalemate != null || snapshot.state() == SurvivalReflexState.ACTIVE && snapshot.cause() == SurvivalReflexCause.MOB_ATTACK) {
			Map<String, Vec3> positions = new LinkedHashMap<>();
			for (ResolvedThreat threat : threats) positions.put(threat.observed().uuid(), threat.entity().position());
			combatStalemate = CombatStalemate.observe(combatStalemate, tick, player.position(), positions,
				immediateCombatDanger(minecraft, player, threats, tick));
		}
		boolean mobDanger = !combatDeferred() && threats.stream().anyMatch(threat ->
			policy().acceptsMob(isRangedThreat(threat.entity()), threat.distance(), threat.lineOfSight()));
		if (shouldBeginReflex(snapshot.state(), drowningDanger || mobDanger)) {
			SurvivalReflexCause cause = drowningDanger ? SurvivalReflexCause.DROWNING : SurvivalReflexCause.MOB_ATTACK;
			boolean hasInterruptedWork = interruptedWork != null && interruptedWork.hasInterruptedWork();
			SurvivalReflexAction action = drowningDanger
				? drowningAction(hasInterruptedWork)
				: chooseMobAction(player, threats);
			begin(cause, action, interruptedWork, player, threats, tick, releaseNormalActuators);
		}

		if (snapshot.state() != SurvivalReflexState.ACTIVE) {
			if (tacticalWindow != null && snapshot.state() == SurvivalReflexState.AWAITING_PLANNER) {
				if (!blockShieldThreat(minecraft, player, threats, tick)) releaseShield(minecraft);
			} else if (tacticalWindow != null) releaseShield(minecraft);
			maintainDrowningSafetyHold(minecraft, player, tick);
			refreshSnapshot(player, threats, snapshot.lastDangerTick(), snapshot.breathableTicks(), snapshot.lastActuatorFailure());
			return snapshot;
		}

		observeCombatEpisode(minecraft, player, threats, tick);

		if (snapshot.cause() == SurvivalReflexCause.DROWNING && !policy().drowningEnabled()
			|| snapshot.cause() == SurvivalReflexCause.MOB_ATTACK && !policy().combatEnabled()) {
			resolve(minecraft, player, threats, tick, "reflex_policy_disabled", false);
		}
		else if (recoverCombatMovement(minecraft, player, threats, tick)) {
			// The recovery navigator owns all movement until dry supported ground.
		}
		else if (drowningDanger || snapshot.cause() == SurvivalReflexCause.DROWNING) {
			if (combatEating != null && combatEating.eating()) combatEating.cancel(minecraft);
			releaseShield(minecraft);
			tickDrowning(minecraft, player, drowningDanger, threats.stream().filter(threat ->
				policy().acceptsMob(isRangedThreat(threat.entity()), threat.distance(), threat.lineOfSight())).toList(), tick);
		}
		else {
			tickMobAttack(minecraft, player, threats, tick, combatEating);
		}
		drowningDamageObserved = false;
		return snapshot;
	}

	public boolean awaitingTacticalPlan() {
		return tacticalWindow != null && snapshot.state() == SurvivalReflexState.AWAITING_PLANNER;
	}

	public ResumeResult resume(String holdId, long tick) {
		ResumeResult validation = validateResume(snapshot, holdId);
		if (validation != ResumeResult.RESUMED) {
			return validation;
		}
		releaseHold("resumed", tick);
		return ResumeResult.RESUMED;
	}

	public boolean releaseHold(String reason, long tick) {
		if (snapshot.state() != SurvivalReflexState.AWAITING_PLANNER) {
			return false;
		}
		if (tacticalWindow != null) tacticalWindow = new TacticalWindow(tick + 600);
		pendingEvents.add(new SurvivalReflexEvent("reflex.hold_released", mapOfNullable(
			"holdId", snapshot.holdId(),
			"reason", reason == null || reason.isBlank() ? "released" : reason,
			"safetyEpoch", snapshot.safetyEpoch()
		)));
		snapshot = new SurvivalReflexSnapshot(
			SurvivalReflexState.IDLE, null, null, snapshot.safetyEpoch(), null, null, null, List.of(),
			snapshot.health(), snapshot.maxHealth(), snapshot.air(), snapshot.maxAir(), -1L, tick, 0, null
		);
		if (!combatDeferred()) observedThreats.clear();
		return true;
	}

	public boolean discardHold(String reason, long tick) {
		if (snapshot.holdId() == null) {
			return false;
		}
		if (snapshot.state() == SurvivalReflexState.AWAITING_PLANNER) {
			return releaseHold(reason, tick);
		}
		if (snapshot.state() != SurvivalReflexState.ACTIVE) {
			return false;
		}
		pendingEvents.add(new SurvivalReflexEvent("reflex.hold_released", mapOfNullable(
			"holdId", snapshot.holdId(),
			"reason", reason == null || reason.isBlank() ? "cancelled" : reason,
			"safetyEpoch", snapshot.safetyEpoch()
		)));
		snapshot = new SurvivalReflexSnapshot(
			snapshot.state(), snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), null, null, null,
			snapshot.threats(), snapshot.health(), snapshot.maxHealth(), snapshot.air(), snapshot.maxAir(),
			snapshot.startedTick(), snapshot.lastDangerTick(), snapshot.breathableTicks(), snapshot.lastActuatorFailure()
		);
		return true;
	}

	public void reset(Minecraft minecraft) {
		foodRetreatStartedTick = -1L;
		foodRetreatAbandoned = false;
		foodUnavailableReported = false;
		combatProgress = null;
		combatEpisode = null;
		combatParticipants.clear();
		tacticalWindow = null;
		progressThreats = null;
		combatRecovery = CombatRecovery.READY;
		combatStalemate = null;
		releaseShield(minecraft);
		movementController.stop(minecraft);
		observedThreats.clear();
		drowningDamageObserved = false;
		lastMobDamageTick = Long.MIN_VALUE;
		safetyHoldActuating = false;
		stopCombatNavigation();
		aggroQuery = null;
		resetSecurityProgress();
		underwaterEscape.reset(minecraft);
		long epoch = snapshot.safetyEpoch();
		snapshot = new SurvivalReflexSnapshot(
			SurvivalReflexState.IDLE, null, null, epoch, null, null, null, List.of(),
			null, null, null, null, -1L, -1L, 0, null
		);
	}

	void enqueueEventForTests(SurvivalReflexEvent event) {
		pendingEvents.add(event);
	}

	public List<SurvivalReflexEvent> drainEvents() {
		if (pendingEvents.isEmpty()) {
			return List.of();
		}
		List<SurvivalReflexEvent> events = List.copyOf(pendingEvents);
		pendingEvents.clear();
		return events;
	}

	private void begin(
		SurvivalReflexCause cause,
		SurvivalReflexAction action,
		InterruptedWork interruptedWork,
		LocalPlayer player,
		List<ResolvedThreat> threats,
		long tick,
		Runnable releaseNormalActuators
	) {
		foodRetreatStartedTick = -1L;
		foodRetreatAbandoned = false;
		foodUnavailableReported = false;
		combatEpisode = null;
		combatParticipants.clear();
		long nextEpoch = snapshot.safetyEpoch() + 1L;
		combatStalemate = null;
		resetSecurityProgress();
		InterruptedWork work = interruptedWork == null ? InterruptedWork.none() : interruptedWork;
		String holdId = work.hasInterruptedWork() ? UUID.randomUUID().toString() : null;
		snapshot = new SurvivalReflexSnapshot(
			SurvivalReflexState.ACTIVE, cause, action, nextEpoch, holdId, work.jobId(), work.actionExecutionId(),
			threatSnapshots(threats), player.getHealth(), player.getMaxHealth(), player.getAirSupply(), player.getMaxAirSupply(),
			tick, tick, 0, null
		);
		pendingEvents.add(new SurvivalReflexEvent("reflex.started", mapOfNullable(
			"safetyEpoch", nextEpoch,
			"holdId", holdId,
			"cause", cause.name(),
			"action", action.name(),
			"interruptedJobId", work.jobId(),
			"interruptedActionExecutionId", work.actionExecutionId(),
			"health", player.getHealth(),
			"air", player.getAirSupply()
		)));
		try {
			if (releaseNormalActuators != null) {
				releaseNormalActuators.run();
			}
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("release_normal_actuators", exception, tick);
			snapshot = new SurvivalReflexSnapshot(
				snapshot.state(), snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), snapshot.holdId(),
				snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), snapshot.threats(),
				snapshot.health(), snapshot.maxHealth(), snapshot.air(), snapshot.maxAir(), snapshot.startedTick(),
				snapshot.lastDangerTick(), snapshot.breathableTicks(), failureText(exception)
			);
		}
	}

	private void tickDrowning(
		Minecraft minecraft,
		LocalPlayer player,
		boolean danger,
		List<ResolvedThreat> threats,
		long tick
	) {
		boolean hasInterruptedWork = snapshot.holdId() != null;
		SurvivalReflexAction desiredAction = drowningAction(hasInterruptedWork);
		if (snapshot.cause() != SurvivalReflexCause.DROWNING || snapshot.action() != desiredAction) {
			changeAction(SurvivalReflexCause.DROWNING, desiredAction, tick);
		}
		boolean airRecovered = airRecoveryMarginReached(
			player.isUnderWater(),
			player.getAirSupply(),
			player.getMaxAirSupply()
		);
		boolean safeLand = player.onGround()
			&& MinecraftUnderwaterEscapeController.isSafeStandingPosition(minecraft, player.blockPosition());
		boolean stable = stableDrowningRecovery(airRecovered);
		int stableTicks = stable ? snapshot.breathableTicks() + 1 : 0;
		if (drowningResolved(stableTicks)) {
			if (!mobThreatsResolved(threats.size(), tick, lastMobDamageTick, config.threatCooldownTicks())) {
				underwaterEscape.reset(minecraft);
				changeAction(SurvivalReflexCause.MOB_ATTACK, chooseMobAction(player, threats), tick);
				refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
				return;
			}
			boolean keepSafetyHold = shouldKeepDrowningSafetyHold(
				hasInterruptedWork,
				safeLand
			);
			resolve(
				minecraft,
				player,
				threats,
				tick,
				safeLand ? "safe_land_reached" : "breathing_restored",
				keepSafetyHold
			);
			return;
		}

		try {
			if (stable) {
				underwaterEscape.reset(minecraft);
				if (player.isInWater()) {
					movementController.swimUp(minecraft, false, false, tick);
				}
				else {
					movementController.stop(minecraft);
				}
			}
			else {
				UnderwaterEscapeSearch.SearchMode mode = drowningSearchMode(
					hasInterruptedWork,
					player.isUnderWater(),
					player.getAirSupply(),
					player.getMaxAirSupply()
				);
				underwaterEscape.tick(
					minecraft,
					mode,
					player.getAirSupply(),
					tick,
					mode == UnderwaterEscapeSearch.SearchMode.BREATHABLE
						? !player.isUnderWater()
						: safeLand
				);
			}
			refreshSnapshot(player, threats, danger ? tick : snapshot.lastDangerTick(), stableTicks, null);
		}
		catch (RuntimeException exception) {
			String operation = hasInterruptedWork ? "swim_to_air" : "reach_safe_land";
			recordActuatorFailure(operation, exception, tick);
			refreshSnapshot(player, threats, danger ? tick : snapshot.lastDangerTick(), stableTicks, failureText(exception));
		}
	}

	private boolean combatDeferred() {
		return tacticalWindow != null || combatStalemate != null && combatStalemate.deferred();
	}

	private boolean immediateCombatDanger(Minecraft minecraft, LocalPlayer player, List<ResolvedThreat> threats, long tick) {
		if (recentlyDamagedByMob(tick, lastMobDamageTick, config.threatCooldownTicks())
			|| threats.stream().anyMatch(threat -> threat.distance() <= MELEE_THREAT_DISTANCE || threat.entity().isUsingItem())) return true;
		for (var projectile : minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class,
			player.getBoundingBox().inflate(24), Entity::isAlive)) {
			if (projectile.getOwner() != player && Double.isFinite(incomingProjectileTicks(
				player.getBoundingBox().getCenter().subtract(projectile.position()), projectile.getDeltaMovement()))) return true;
		}
		return false;
	}

	static boolean safeToEatDuringCombat(boolean grounded, boolean wet, boolean immediateDanger,
		boolean sealed, boolean visibleThreat, double nearestDistance) {
		return grounded && !wet && !immediateDanger
			&& (sealed || !visibleThreat && nearestDistance >= 10D);
	}

	private boolean recoverCombatMovement(Minecraft minecraft, LocalPlayer player, List<ResolvedThreat> threats, long tick) {
		CombatRecovery current = combatRecovery == null ? CombatRecovery.READY : combatRecovery;
		if (current == CombatRecovery.READY && snapshot.cause() != SurvivalReflexCause.MOB_ATTACK) return false;
		boolean safeLand = !player.isInWater() && player.onGround()
			&& MinecraftUnderwaterEscapeController.isSafeStandingPosition(minecraft, player.blockPosition());
		CombatRecovery next = current.next(player.isInWater(), player.onGround(), safeLand);
		if (next != current) {
			combatRecovery = next;
			combatStalemate = null;
			stopCombatNavigation();
			combatPositioning = null;
			underwaterEscape.reset(minecraft);
			pendingEvents.add(new SurvivalReflexEvent("reflex.movement_recovery", Map.of("phase", next.name(), "tick", tick)));
		}
		if (next == CombatRecovery.READY) return false;
		releaseShield(minecraft);
		try {
			// Low air gets a breathable waypoint first; breathing alone does not hand control back.
			var mode = player.isUnderWater() && player.getAirSupply() < config.lowAirTicks()
				? UnderwaterEscapeSearch.SearchMode.BREATHABLE : UnderwaterEscapeSearch.SearchMode.SAFE_STANDING;
			var recovery = underwaterEscape.tick(minecraft, mode, player.getAirSupply(), tick,
				mode == UnderwaterEscapeSearch.SearchMode.BREATHABLE ? !player.isUnderWater() : safeLand);
			boolean exhausted = safeLandSearchExhausted(recovery.searchStatus(), recovery.navigation().phase());
			var action = exhausted ? SurvivalReflexAction.STAY_AFLOAT : SurvivalReflexAction.REACH_SAFE_LAND;
			if (snapshot.action() != action) changeAction(SurvivalReflexCause.MOB_ATTACK, action, tick);
			if (exhausted) {
				movementController.swimUp(minecraft, false, false, tick);
			}
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("recover_combat_movement", exception, tick);
			movementController.swimUp(minecraft, false, false, tick);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, failureText(exception));
		}
		return true;
	}

	private void tickMobAttack(Minecraft minecraft, LocalPlayer player, List<ResolvedThreat> threats,
		long tick, CombatEating combatEating) {
		if (combatProgress != null && combatProgress.stalled()) {
			tacticalWindow = new TacticalWindow(tick + 1200);
			resolve(minecraft, player, threats, tick, "combat_stalemate", true);
			return;
		}
		if (combatDeferred()) {
			resolve(minecraft, player, threats, tick, "combat_approach_stalled", true);
			return;
		}
		if (snapshot.action() != SurvivalReflexAction.DEFEND) {
			changeAction(SurvivalReflexCause.MOB_ATTACK, SurvivalReflexAction.DEFEND, tick);
		}
		boolean usePositioning = shouldReposition(threats.size()) && navigationFacade != null && navigationFacade.isLoaded();
		updateCreeperEscape(threats);
		if (tickCombatEating(minecraft, player, threats, tick, combatEating, usePositioning)) return;
		if (threats.stream().noneMatch(threat ->
			policy().acceptsMob(isRangedThreat(threat.entity()), threat.distance(), threat.lineOfSight())
				|| combatPositioning != null && threat.distance() <= Math.min(10, policy().maxThreatDistance()))) {
			resolve(minecraft, player, threats, tick, "no_eligible_threats", false);
			return;
		}
		equipBestCombatItem(minecraft, player);
		if (blockShieldThreat(minecraft, player, threats, tick)) {
			if (usePositioning) reposition(minecraft, threats, tick, true);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
			return;
		}
		releaseShield(minecraft);
		attemptCloseQuarterAttack(minecraft, player, threats, tick);
		SecurityKind security = assessMobSecurity(player, threats, tick);
		if (security == SecurityKind.SEALED) {
			secureEscapeTicks++;
			stopCombatNavigation();
			movementController.stop(minecraft);
			if (secureEscapeTicks >= SHELTER_CONFIRM_TICKS) {
				resolve(minecraft, player, threats, tick, "sealed_shelter", false);
				return;
			}
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
			return;
		}
		secureEscapeTicks = 0;
		try {
			if (usePositioning) {
				reposition(minecraft, threats, tick, false);
			}
			else if (!threats.isEmpty()) {
				defend(minecraft, player, focusedThreat(threats), tick);
			}
			else {
				movementController.stop(minecraft);
			}
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("defend", exception, tick);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, failureText(exception));
		}
	}

	private boolean tickCombatEating(Minecraft minecraft, LocalPlayer player, List<ResolvedThreat> threats,
		long tick, CombatEating eating, boolean usePositioning) {
		if (eating == null) return false;
		var candidate = eating.candidate(player);
		if (!eating.eating() && candidate.isEmpty()) {
			if (eating.needed(player) && !eating.hasEligibleFood(player)
				&& !foodUnavailableReported && !foodRetreatAbandoned) {
				foodUnavailableReported = true;
				pendingEvents.add(new SurvivalReflexEvent("reflex.food_unavailable", Map.of(
					"tick", tick, "health", player.getHealth(),
					"hunger", player.getFoodData().getFoodLevel())));
			}
			foodRetreatStartedTick = -1L;
			return false;
		}
		if (foodRetreatAbandoned) return false;
		foodUnavailableReported = false;
		boolean immediate = immediateCombatDanger(minecraft, player, threats, tick);
		boolean visible = threats.stream().anyMatch(ResolvedThreat::lineOfSight);
		double nearest = threats.stream().mapToDouble(ResolvedThreat::distance).min().orElse(Double.POSITIVE_INFINITY);
		boolean sheltered = !immediate && assessMobSecurity(player, threats, tick) == SecurityKind.SEALED;
		boolean safe = safeToEatDuringCombat(player.onGround(), player.isInWater(), immediate,
			sheltered, visible, nearest);
		if (eating.eating()) {
			if (!safe) {
				eating.cancel(minecraft);
				pendingEvents.add(new SurvivalReflexEvent("reflex.food_eat_interrupted", Map.of("tick", tick, "reason", "threat_returned")));
				return false;
			}
			stopCombatNavigation();
			movementController.stop(minecraft);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
			return true;
		}
		if (foodRetreatStartedTick < 0) foodRetreatStartedTick = tick;
		if (safe) {
			releaseShield(minecraft);
			stopCombatNavigation();
			movementController.stop(minecraft);
			if (!eating.ready(tick)) {
				refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
				return true;
			}
			try {
				eating.start(minecraft, candidate.orElseThrow(), tick);
				foodRetreatStartedTick = -1L;
				pendingEvents.add(new SurvivalReflexEvent("reflex.food_eat_started", Map.of("itemId", candidate.orElseThrow(), "tick", tick)));
				refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
				return true;
			}
			catch (RuntimeException exception) {
				pendingEvents.add(new SurvivalReflexEvent("reflex.food_eat_failed", Map.of("tick", tick,
					"reason", failureText(exception))));
				return false;
			}
		}
		if (tick - foodRetreatStartedTick >= FOOD_RETREAT_LIMIT_TICKS || !usePositioning) {
			foodRetreatAbandoned = true;
			pendingEvents.add(new SurvivalReflexEvent("reflex.food_retreat_failed", Map.of(
				"tick", tick, "reason", usePositioning ? "no_safe_window" : "no_safe_route")));
			return false;
		}
		boolean shielding = blockShieldThreat(minecraft, player, threats, tick);
		if (!shielding) releaseShield(minecraft);
		reposition(minecraft, threats, tick, shielding, true);
		refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
		return true;
	}

	private boolean blockShieldThreat(Minecraft minecraft, LocalPlayer player, List<ResolvedThreat> threats, long tick) {
		if (minecraft.gameMode == null) return false;
		// Resolve the backing inventory index: combat may interrupt an open container.
		if (!player.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)) {
			for (var slot : player.containerMenu.slots) {
				if (slot.container == player.getInventory() && slot.getContainerSlot() < 36
					&& slot.getItem().is(net.minecraft.world.item.Items.SHIELD)) {
					minecraft.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, slot.index, 40,
						net.minecraft.world.inventory.ClickType.SWAP, player);
					break;
				}
			}
		}
		if (!player.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)
			|| player.getCooldowns().isOnCooldown(player.getOffhandItem())) return false;
		if (shieldGuard != null) for (var t : threats) {
			if (t.observed().uuid().equals(shieldGuard.source()) && t.entity() instanceof net.minecraft.world.entity.monster.Creeper c
				&& !shouldBlockCreeper(t.distance(), c.getSwellDir(), c.getSwelling(1), c.isPowered())) shieldGuard = null;
			if (shieldGuard == null) break;
		}
		net.minecraft.world.phys.Vec3 aim = null;
		String source = null;
		double earliest = Double.POSITIVE_INFINITY;
		Float fuseProgress = null;
		for (ResolvedThreat threat : threats) {
			if (threat.entity() instanceof net.minecraft.world.entity.monster.Creeper creeper
				&& threat.lineOfSight() && shouldBlockCreeper(threat.distance(), creeper.getSwellDir(), creeper.getSwelling(1), creeper.isPowered())) {
				aim = shieldFacingPoint(player.getEyePosition(), creeper.position(), Vec3.ZERO);
				source = creeper.getStringUUID();
				fuseProgress = creeper.getSwelling(1);
				break;
			}
		}
		if (aim == null) for (var projectile : minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class,
			player.getBoundingBox().inflate(24), Entity::isAlive)) {
			if (projectile.getOwner() == player) continue;
			var relative = player.getBoundingBox().getCenter().subtract(projectile.position());
			var velocity = projectile.getDeltaMovement();
			double arrival = incomingProjectileTicks(relative, velocity);
			if (arrival < earliest) {
				Entity shooter = projectile.getOwner();
				Vec3 facing = shieldFacingPoint(player.getEyePosition(), shooter == null ? null : shooter.position(), velocity);
				if (facing == null) continue;
				earliest = arrival;
				aim = facing;
				source = shooter == null ? null : shooter.getStringUUID();
			}
		}
		if (aim == null) {
			boolean reloadingCrossbow = false;
			boolean otherRangedThreat = false;
			for (ResolvedThreat threat : threats) {
				var entity = threat.entity();
				if (!threat.lineOfSight()) continue;
				boolean drawingBow = entity.isUsingItem() && entity.getUseItem().is(net.minecraft.world.item.Items.BOW);
				// Crossbows fire after item use ends. Guard the loaded weapon, leaving reload time for counterattacks.
				var mainHand = entity.getMainHandItem();
				var offHand = entity.getOffhandItem();
				boolean loadedCrossbow = (mainHand.is(net.minecraft.world.item.Items.CROSSBOW) && net.minecraft.world.item.CrossbowItem.isCharged(mainHand))
					|| (offHand.is(net.minecraft.world.item.Items.CROSSBOW) && net.minecraft.world.item.CrossbowItem.isCharged(offHand));
				if (shouldGuardBow(drawingBow, entity.getTicksUsingItem()) || loadedCrossbow) {
					aim = shieldFacingPoint(player.getEyePosition(), entity.position(), Vec3.ZERO);
					source = entity.getStringUUID();
					break;
				}
				if (mainHand.is(net.minecraft.world.item.Items.CROSSBOW) || offHand.is(net.minecraft.world.item.Items.CROSSBOW)) {
					boolean reloading = entity.isUsingItem() && entity.getUseItem().is(net.minecraft.world.item.Items.CROSSBOW);
					reloadingCrossbow |= reloading;
					otherRangedThreat |= !reloading;
				}
				otherRangedThreat |= mainHand.is(net.minecraft.world.item.Items.BOW) || offHand.is(net.minecraft.world.item.Items.BOW);
			}
			// A confirmed reload cannot fire. Incoming arrows and other ready weapons still take priority above.
			if (aim == null && reloadingCrossbow && !otherRangedThreat) shieldGuard = null;
		}
		shieldGuard = nextShieldGuard(shieldGuard, aim, source, tick);
		if (shieldGuard == null) return false;
		movementController.stop(minecraft);
		ResolvedThreat approach = threats.isEmpty() ? null : closestVisibleThreat(threats);
		if (!shouldReposition(threats.size())) {
			if (fuseProgress == null && approach != null && approach.distance() > MELEE_ATTACK_DISTANCE
				&& navigationFacade != null && navigationFacade.isLoaded()) {
				updateCombatNavigation(goal(approach.entity().blockPosition()), tick);
			}
			else {
				stopCombatNavigation();
			}
		}
		cameraController.lookAt(minecraft, new Vec3(shieldGuard.facing().x, player.getEyeY(), shieldGuard.facing().z));
		actuator.holdUse();
		if (!shieldUseOwned) pendingEvents.add(new SurvivalReflexEvent("reflex.shield_raised", mapOfNullable(
			"sourceUuid", shieldGuard.source(), "incomingProjectile", Double.isFinite(earliest),
			"creeperFuseProgress", fuseProgress,
			"shieldDamage", player.getOffhandItem().getDamageValue(), "health", player.getHealth(), "tick", tick)));
		shieldUseOwned = true;
		if (!player.isUsingItem() || player.getUsedItemHand() != InteractionHand.OFF_HAND)
			actuator.useItem(minecraft, player, InteractionHand.OFF_HAND);
		return true;
	}

	static boolean shouldGuardBow(boolean drawing, int useTicks) {
		// Skeleton draw lasts 20 ticks; allow five ticks for shield startup plus one margin.
		return drawing && useTicks >= 14;
	}

	static boolean creeperShouldKite(float cooldown, float fuseProgress) {
		return cooldown < .92F || fuseProgress >= .2F;
	}

	static boolean creeperEscapeActive(boolean previous, double distance, int fuseSpeed, float fuseProgress, boolean charged) {
		if (distance > 10) return false;
		return fuseSpeed > 0 || fuseProgress >= .2F
			|| previous && (fuseProgress > 0 || distance < (charged ? 10 : 8));
	}

	static boolean shouldBlockCreeper(double distance, int fuseSpeed, float fuseProgress, boolean charged) {
		// Last resort: preserve time for shield activation, but let early fuse movement escape.
		return fuseSpeed > 0 && fuseProgress >= 0.7F && distance <= (charged ? 12 : 6);
	}

	record ShieldGuard(Vec3 facing, String source, long throughTick) {}

	static ShieldGuard nextShieldGuard(ShieldGuard previous, Vec3 facing, String source, long tick) {
		if (facing != null) return new ShieldGuard(facing, source, tick + 6);
		return previous != null && tick <= previous.throughTick() ? previous : null;
	}

	/** Shield coverage needs a heading toward the shooter, not an aim lock on an arrow. */
	static Vec3 shieldFacingPoint(Vec3 eye, Vec3 shooter, Vec3 projectileVelocity) {
		if (shooter != null) return new Vec3(shooter.x, eye.y, shooter.z);
		Vec3 incomingDirection = new Vec3(-projectileVelocity.x, 0, -projectileVelocity.z);
		return incomingDirection.lengthSqr() < 0.0001 ? null : eye.add(incomingDirection.normalize().scale(8));
	}

	/** Closest approach within the next eight ticks; ignore stopped, receding and passing arrows. */
	static double incomingProjectileTicks(net.minecraft.world.phys.Vec3 relative, net.minecraft.world.phys.Vec3 velocity) {
		double speedSquared = velocity.lengthSqr();
		if (speedSquared < 0.01D) return Double.POSITIVE_INFINITY;
		double ticks = relative.dot(velocity) / speedSquared;
		if (ticks < 0 || ticks > 8) return Double.POSITIVE_INFINITY;
		return relative.subtract(velocity.scale(ticks)).lengthSqr() <= 2.25D
			? ticks : Double.POSITIVE_INFINITY;
	}

	private void releaseShield(Minecraft minecraft) {
		shieldGuard = null;
		if (!shieldUseOwned) return;
		shieldUseOwned = false;
		if (minecraft == null) return;
		actuator.releaseUse(minecraft);
		if (minecraft.player != null) pendingEvents.add(new SurvivalReflexEvent("reflex.shield_lowered", mapOfNullable(
			"shieldDamage", minecraft.player.getOffhandItem().getDamageValue(), "health", minecraft.player.getHealth(),
			"wasBlocking", minecraft.player.isBlocking())));
		if (minecraft.player != null && minecraft.gameMode != null && minecraft.player.isUsingItem()
			&& minecraft.player.getUseItem().is(net.minecraft.world.item.Items.SHIELD))
			minecraft.gameMode.releaseUsingItem(minecraft.player);
	}

	private void defend(Minecraft minecraft, LocalPlayer player, ResolvedThreat threat, long tick) {
		if (threat.distance() <= 3.0D && threat.lineOfSight()) {
			cameraController.lookAt(minecraft, threat.entity().getBoundingBox().getCenter());
			stopCombatNavigation();
			movementController.stop(minecraft);
			return;
		}
		if (navigationFacade != null && navigationFacade.isLoaded()) {
			movementController.stop(minecraft);
			updateCombatNavigation(goal(threat.entity().blockPosition()), tick);
		}
		else if (threat.lineOfSight()) {
			cameraController.lookAt(minecraft, threat.entity().getBoundingBox().getCenter());
			movementController.moveDirectional(minecraft, true, false, false, false, true, false, tick);
		}
		else {
			movementController.stop(minecraft);
		}
	}

	static boolean shouldReposition(int threatCount) { return threatCount >= 1; }

	private void updateCreeperEscape(List<ResolvedThreat> threats) {
		// Keep tracking the live pursuer, not the position where it first ignited.
		escapingCreeper = threats.stream().filter(t -> t.entity() instanceof net.minecraft.world.entity.monster.Creeper c
			&& creeperEscapeActive(t.observed().uuid().equals(escapingCreeper), t.distance(), c.getSwellDir(), c.getSwelling(1), c.isPowered()))
			.min(java.util.Comparator.comparingDouble(ResolvedThreat::distance))
			.map(t -> t.observed().uuid()).orElse(null);
	}

	private void reposition(Minecraft minecraft, List<ResolvedThreat> threats, long tick, boolean shielding) {
		reposition(minecraft, threats, tick, shielding, false);
	}

	private void reposition(Minecraft minecraft, List<ResolvedThreat> threats, long tick,
		boolean shielding, boolean retreatForFood) {
		if (navigationFacade == null || !navigationFacade.isLoaded()) {
			stopCombatNavigation();
			movementController.stop(minecraft);
			return;
		}
		if (combatPositioning == null) {
			stopCombatNavigation();
			combatPositioning = new MinecraftCombatPositioning();
		}
		var focus = threats.stream().filter(t -> t.entity() instanceof net.minecraft.world.entity.monster.Creeper c
			&& t.observed().uuid().equals(escapingCreeper)).min(java.util.Comparator.comparingDouble(ResolvedThreat::distance))
			.orElseGet(() -> focusedThreat(threats));
		boolean escaping = focus.observed().uuid().equals(escapingCreeper);
		boolean kiting = focus.entity() instanceof net.minecraft.world.entity.monster.Creeper c
			&& creeperShouldKite(minecraft.player.getAttackStrengthScale(0), c.getSwelling(1));
		double desiredDistance = escaping ? (((net.minecraft.world.entity.monster.Creeper) focus.entity()).isPowered() ? 14 : 8)
			: kiting ? 5 : 2.6;
		if (retreatForFood) desiredDistance = Math.max(10D, desiredDistance);
		var decision = combatPositioning.plan(minecraft, threats.stream().map(ResolvedThreat::entity).toList(), focus.entity(), tick, shielding, desiredDistance);
		var step = decision.nextStep();
		if (step == null) step = new CombatPositioning.Cell(minecraft.player.getBlockX(), minecraft.player.getBlockY(), minecraft.player.getBlockZ());
		if (!combatPositioning.canStepTo(step)) {
			stopCombatNavigation();
			movementController.stop(minecraft);
			combatPositioning.invalidate();
			return;
		}
		GoalPosition target = new GoalPosition(step.x(), step.y(), step.z(), true);
		// Always steer using the actual spring angle, including while turning into a sprint.
		Vec3 actualFacing = minecraft.player.position().add(Vec3.directionFromRotation(0.0F, minecraft.player.getYRot()));
		var control = combatPositioning.control(minecraft, step, actualFacing, focus.entity().position(), tick);
		var steering = control.steering();
		boolean sprintEscape = escaping && !shielding && !control.jump() && !control.sneak()
			&& minecraft.player.onGround() && !minecraft.player.isInWater()
			&& minecraft.player.getFoodData().getFoodLevel() > 6;
		Vec3 facing = sprintEscape
			? new Vec3(step.x() + .5, minecraft.player.getEyeY(), step.z() + .5)
			: focus.entity().getBoundingBox().getCenter();
		// Walking, traversal and guarding retain the threat heading. Only sprint escape turns away.
		if (shielding && shieldGuard != null) facing = shieldGuard.facing();
		cameraController.lookAt(minecraft, facing);
		boolean sprint = !shielding && (!kiting && !escaping || sprintEscape)
			&& steering.forward() && !steering.back() && focus.entity() instanceof net.minecraft.world.entity.monster.Creeper
			&& !control.sneak() && minecraft.player.getFoodData().getFoodLevel() > 6;

		if (!shielding && !control.jump() && !control.sneak() && focus.entity() instanceof net.minecraft.world.entity.monster.Witch) {
			var strafe = combatPositioning.witchSprint(minecraft, step, focus.entity().position());
			if (strafe != null) {
				facing = strafe.facing();
				cameraController.lookAt(minecraft, facing);
				// Sprint keys assume the orbit heading; use current-heading steering while turning.
				if (cameraController.isLookingAt(minecraft, facing, .5F)) {
					steering = strafe.steering();
					sprint = true;
				}
			}
		}
		movementController.moveDirectional(minecraft, steering.forward(), steering.back(), steering.left(), steering.right(), sprint,
			control.jump(), control.sneak(), tick);
		if (!target.equals(combatTarget)) pendingEvents.add(new SurvivalReflexEvent("reflex.combat_reposition", Map.of(
			"target", target, "threatCount", threats.size(), "risk", decision.risk(), "standingRisk", decision.standingRisk(),
			"route", decision.route(), "shielding", shielding, "facing", facing, "steering", steering, "escapingCreeper", escaping, "tick", tick)));
		combatTarget = target;
		combatRouteTick = tick;
	}

	void updateCombatNavigation(GoalPosition target, long tick) {
		if (combatTarget == null || tick - combatRouteTick >= 20L
			&& (!target.equals(combatTarget) || !navigationFacade.processActive())) {
			navigationFacade.startNavigateNear(target, 2);
			combatTarget = target;
			combatRouteTick = tick;
		}
	}

	private void stopCombatNavigation() {
		if (combatTarget != null && navigationFacade != null) {
			navigationFacade.cancel();
		}
		combatTarget = null;
	}

	private void observeCombatEpisode(Minecraft minecraft, LocalPlayer player, List<ResolvedThreat> threats, long tick) {
		if (combatEpisode == null) {
			if (snapshot.cause() != SurvivalReflexCause.MOB_ATTACK) return;
			combatEpisode = new CombatEpisode(tick, player.getHealth());
		}
		for (ResolvedThreat threat : threats) combatParticipants.put(threat.observed().uuid(), threat);
		List<CombatEpisode.Target> observations = new ArrayList<>();
		for (ResolvedThreat threat : combatParticipants.values()) {
			LivingEntity entity = threat.entity();
			// Removal/unloading alone is not death. Retain the entity reference to observe the death state after filtering.
			boolean dead = entity.isDeadOrDying() || entity.getRemovalReason() == Entity.RemovalReason.KILLED;
			boolean present = !entity.isRemoved() && minecraft.level.getEntity(entity.getId()) == entity;
			if (!dead && !present) continue;
			observations.add(new CombatEpisode.Target(entity.getStringUUID(), entity.getName().getString(),
				BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), dead ? CombatEpisode.Outcome.CONFIRMED_DEAD : CombatEpisode.Outcome.ALIVE,
				entity.getHealth(), player.distanceTo(entity)));
		}
		combatEpisode.observe(player.getHealth(), observations);
	}

	private void resolve(
		Minecraft minecraft,
		LocalPlayer player,
		List<ResolvedThreat> threats,
		long tick,
		String reason,
		boolean keepSafetyHold
	) {
		foodRetreatStartedTick = -1L;
		foodRetreatAbandoned = false;
		foodUnavailableReported = false;
		releaseShield(minecraft);
		underwaterEscape.reset(minecraft);
		stopCombatNavigation();
		movementController.stop(minecraft);
		SurvivalReflexState nextState = keepSafetyHold || snapshot.holdId() != null
			? SurvivalReflexState.AWAITING_PLANNER
			: SurvivalReflexState.IDLE;
		String nextHoldId = safetyHoldId(snapshot.holdId(), nextState == SurvivalReflexState.AWAITING_PLANNER);
		pendingEvents.add(new SurvivalReflexEvent("reflex.resolved", mapOfNullable(
			"safetyEpoch", snapshot.safetyEpoch(),
			"holdId", nextHoldId,
			"cause", snapshot.cause() == null ? null : snapshot.cause().name(),
			"action", snapshot.action() == null ? null : snapshot.action().name(),
			"reason", reason,
			"position", goal(player.blockPosition()),
			"remainingThreats", threatSnapshots(threats),
			"combatSummary", combatEpisode == null ? null : combatEpisode.summary(tick, reason),
			"noProgressTicks", noProgressTicks(combatProgress, combatStalemate, tick),
			"nextState", nextState.name()
		)));
		combatEpisode = null;
		combatParticipants.clear();
		snapshot = new SurvivalReflexSnapshot(
			nextState, snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), nextHoldId,
			snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), threatSnapshots(threats),
			player.getHealth(), player.getMaxHealth(), player.getAirSupply(), player.getMaxAirSupply(), snapshot.startedTick(),
			tick, snapshot.breathableTicks(), null
		);
		if (!"combat_approach_stalled".equals(reason) && !"combat_stalemate".equals(reason)) {
			combatStalemate = null;
			observedThreats.clear();
		}
		lastMobDamageTick = Long.MIN_VALUE;
		resetSecurityProgress();
	}

	static Long noProgressTicks(CombatProgress progress, CombatStalemate approach, long tick) {
		if (progress != null && progress.stalled()) return tick - progress.lastProgressTick();
		if (approach != null && approach.deferred()) return tick - approach.sinceTick();
		return null;
	}

	static String safetyHoldId(String existingHoldId, boolean holdRequired) {
		if (!holdRequired) {
			return null;
		}
		return existingHoldId == null || existingHoldId.isBlank()
			? UUID.randomUUID().toString()
			: existingHoldId;
	}

	private void attemptCloseQuarterAttack(
		Minecraft minecraft,
		LocalPlayer player,
		List<ResolvedThreat> threats,
		long tick
	) {
		if (escapingCreeper != null || minecraft.gameMode == null || threats == null || threats.isEmpty()) {
			return;
		}
		ResolvedThreat threat = focusedThreat(threats);
		float cooldown = player.getAttackStrengthScale(0.0F);
		if (!shouldAttackCloseThreat(threat.distance(), threat.lineOfSight(), cooldown)) {
			return;
		}
		// Early-fuse strikes can interrupt the approach; late fuse belongs to escape/blocking.
		if (threat.entity() instanceof net.minecraft.world.entity.monster.Creeper c && c.getSwelling(1) >= .2F) return;
		cameraController.lookAt(minecraft, threat.entity().getBoundingBox().getCenter());
		if (!cameraController.isAimingAt(minecraft, threat.entity().getBoundingBox())) return;
		// Send the current hit-facing before attack so server knockback uses it too.
		player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
			player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision));
		boolean sprintHit = player.isSprinting();
		actuator.attack(minecraft, player, threat.entity());
		player.swing(InteractionHand.MAIN_HAND);
		pendingEvents.add(new SurvivalReflexEvent("reflex.close_quarter_attack", mapOfNullable(
			"threatUuid", threat.observed().uuid(),
			"sprinting", sprintHit,
			"weaponItemId", BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString(),
			"entityTypeId", threat.observed().entityTypeId(),
			"distance", threat.distance(),
			"action", snapshot.action() == null ? null : snapshot.action().name(),
			"tick", tick
		)));
	}

	private void equipBestCombatItem(Minecraft minecraft, LocalPlayer player) {
		List<String> itemIds = new ArrayList<>();
		for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++)
			itemIds.add(BuiltInRegistries.ITEM.getKey(player.getInventory().getItem(slot).getItem()).toString());
		int bestSlot = bestCombatInventorySlot(itemIds);
		if (bestSlot < 0) return;
		if (bestSlot < 9) {
			actuator.selectHotbar(minecraft, bestSlot);
			return;
		}
		if (minecraft.gameMode == null) return;
		// Match backing inventory indices, since an interrupted task may have a container open.
		for (var slot : player.containerMenu.slots) {
			if (slot.container == player.getInventory() && slot.getContainerSlot() == bestSlot) {
				minecraft.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, slot.index,
					player.getInventory().getSelectedSlot(), net.minecraft.world.inventory.ClickType.SWAP, player);
				return;
			}
		}
	}

	static int bestCombatInventorySlot(List<String> itemIds) {
		int bestSlot = -1;
		int bestRank = Integer.MAX_VALUE;
		for (int slot = 0; slot < Math.min(net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE, itemIds.size()); slot++) {
			int rank = combatItemRank(itemIds.get(slot));
			if (rank < bestRank) {
				bestRank = rank;
				bestSlot = slot;
			}
		}
		return bestSlot;
	}

	static int combatItemRank(String itemId) {
		return switch (itemId == null ? "" : itemId) {
			case "minecraft:netherite_sword" -> 0;
			case "minecraft:diamond_sword" -> 1;
			case "minecraft:iron_sword" -> 2;
			case "minecraft:stone_sword" -> 3;
			case "minecraft:golden_sword" -> 4;
			case "minecraft:wooden_sword" -> 5;
			case "minecraft:netherite_axe" -> 6;
			case "minecraft:diamond_axe" -> 7;
			case "minecraft:iron_axe" -> 8;
			case "minecraft:stone_axe" -> 9;
			case "minecraft:golden_axe" -> 10;
			case "minecraft:wooden_axe" -> 11;
			case "minecraft:netherite_pickaxe" -> 12;
			case "minecraft:diamond_pickaxe" -> 13;
			case "minecraft:iron_pickaxe" -> 14;
			case "minecraft:stone_pickaxe" -> 15;
			case "minecraft:golden_pickaxe" -> 16;
			case "minecraft:wooden_pickaxe" -> 17;
			default -> Integer.MAX_VALUE;
		};
	}

	private void changeAction(SurvivalReflexCause cause, SurvivalReflexAction action, long tick) {
		if (action != SurvivalReflexAction.DEFEND) {
			stopCombatNavigation();
		}
		pendingEvents.add(new SurvivalReflexEvent("reflex.action_changed", mapOfNullable(
			"safetyEpoch", snapshot.safetyEpoch(),
			"holdId", snapshot.holdId(),
			"previousCause", snapshot.cause() == null ? null : snapshot.cause().name(),
			"previousAction", snapshot.action() == null ? null : snapshot.action().name(),
			"cause", cause.name(),
			"action", action.name()
		)));
		snapshot = new SurvivalReflexSnapshot(
			snapshot.state(), cause, action, snapshot.safetyEpoch(), snapshot.holdId(),
			snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), snapshot.threats(),
			snapshot.health(), snapshot.maxHealth(), snapshot.air(), snapshot.maxAir(), snapshot.startedTick(),
			tick, snapshot.breathableTicks(), snapshot.lastActuatorFailure()
		);
	}

	private void refreshSnapshot(
		LocalPlayer player,
		List<ResolvedThreat> threats,
		long lastDangerTick,
		int breathableTicks,
		String actuatorFailure
	) {
		snapshot = new SurvivalReflexSnapshot(
			snapshot.state(), snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), snapshot.holdId(),
			snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), threatSnapshots(threats),
			player.getHealth(), player.getMaxHealth(), player.getAirSupply(), player.getMaxAirSupply(), snapshot.startedTick(),
			lastDangerTick, breathableTicks, actuatorFailure
		);
	}

	private void recordActuatorFailure(String operation, RuntimeException exception, long tick) {
		pendingEvents.add(new SurvivalReflexEvent("reflex.actuator_failed", mapOfNullable(
			"safetyEpoch", snapshot.safetyEpoch(),
			"holdId", snapshot.holdId(),
			"operation", operation,
			"message", failureText(exception),
			"tick", tick
		)));
	}

	static boolean drowningDanger(LocalPlayer player, int lowAirTicks, boolean drowningDamageObserved) {
		return shouldStartDrowning(
			player != null && player.isUnderWater(),
			player == null ? Integer.MAX_VALUE : player.getAirSupply(),
			lowAirTicks,
			drowningDamageObserved
		);
	}

	static boolean shouldStartDrowning(boolean submerged, int air, int lowAirTicks, boolean drowningDamageObserved) {
		return drowningDamageObserved || (submerged && air <= Math.max(0, lowAirTicks));
	}

	static boolean airRecoveryMarginReached(boolean submerged, int air, int maxAir) {
		return UnderwaterHarvestPolicy.mayResumeHarvest(submerged, air, maxAir);
	}

	static boolean drowningResolved(int breathableTicks) {
		return breathableTicks >= BREATHABLE_STABLE_TICKS;
	}

	static SurvivalReflexAction drowningAction(boolean hasInterruptedWork) {
		return hasInterruptedWork ? SurvivalReflexAction.SWIM_TO_AIR : SurvivalReflexAction.REACH_SAFE_LAND;
	}

	static boolean stableDrowningRecovery(boolean airRecovered) {
		return airRecovered;
	}

	static boolean shouldKeepDrowningSafetyHold(boolean hasInterruptedWork, boolean safeLand) {
		return hasInterruptedWork || !safeLand;
	}

	static boolean safeLandSearchExhausted(
		UnderwaterEscapeSearch.SearchStatus searchStatus,
		UnderwaterEscapeNavigator.Phase navigationPhase
	) {
		return searchStatus != UnderwaterEscapeSearch.SearchStatus.SEARCHING
			&& navigationPhase == UnderwaterEscapeNavigator.Phase.EXHAUSTED;
	}

	static UnderwaterEscapeSearch.SearchMode drowningSearchMode(
		boolean hasInterruptedWork,
		boolean submerged,
		int air,
		int maxAir
	) {
		if (hasInterruptedWork || submerged || air < Math.max(0, maxAir - UnderwaterHarvestPolicy.AIR_RESUME_MARGIN_TICKS)) {
			return UnderwaterEscapeSearch.SearchMode.BREATHABLE;
		}
		return UnderwaterEscapeSearch.SearchMode.SAFE_STANDING;
	}

	static boolean shouldBeginReflex(SurvivalReflexState state, boolean dangerPresent) {
		return dangerPresent && state != SurvivalReflexState.ACTIVE;
	}

	static boolean shouldMaintainDrowningSafetyHold(
		SurvivalReflexState state,
		SurvivalReflexCause cause,
		boolean touchingWater
	) {
		return state == SurvivalReflexState.AWAITING_PLANNER
			&& cause == SurvivalReflexCause.DROWNING
			&& touchingWater;
	}

	static boolean mobThreatsResolved(int relevantThreatCount, long tick, long lastDamageTick, int cooldownTicks) {
		return relevantThreatCount == 0 && !recentlyDamagedByMob(tick, lastDamageTick, cooldownTicks);
	}

	static ResumeResult validateResume(SurvivalReflexSnapshot snapshot, String holdId) {
		SurvivalReflexSnapshot current = snapshot == null ? SurvivalReflexSnapshot.idle() : snapshot;
		if (current.state() == SurvivalReflexState.ACTIVE) {
			return ResumeResult.REFLEX_ACTIVE;
		}
		if (current.state() != SurvivalReflexState.AWAITING_PLANNER || current.holdId() == null) {
			return ResumeResult.NO_SAFETY_HOLD;
		}
		return current.holdId().equals(holdId) ? ResumeResult.RESUMED : ResumeResult.STALE_SAFETY_HOLD;
	}

	static boolean shouldDetectProactiveThreat(boolean targetingPlayer, boolean alive) {
		return targetingPlayer && alive;
	}

	public static boolean recentlyDamagedByMob(long tick, long lastDamageTick, int cooldownTicks) {
		return lastDamageTick != Long.MIN_VALUE && tick - lastDamageTick < Math.max(0, cooldownTicks);
	}

	static boolean shouldAttackCloseThreat(double distance, boolean lineOfSight, float attackCooldown) {
		return distance <= MELEE_ATTACK_DISTANCE
			&& lineOfSight
			&& attackCooldown >= ATTACK_READY_THRESHOLD;
	}

	static SecurityKind classifyThreatSecurity(
		RouteStatus routeStatus,
		boolean lineOfSight
	) {
		// A long or incomplete route is not shelter: pursuit can resume as soon as we stop.
		return routeStatus == RouteStatus.BLOCKED && !lineOfSight
			? SecurityKind.SEALED : SecurityKind.UNSAFE;
	}

	private SecurityKind assessMobSecurity(LocalPlayer player, List<ResolvedThreat> threats, long tick) {
		if (player == null || threats == null || threats.isEmpty()) {
			return SecurityKind.UNSAFE;
		}
		Set<String> threatIds = threats.stream().map(threat -> threat.observed().uuid()).collect(java.util.stream.Collectors.toSet());
		if (mobRoutesTick == Long.MIN_VALUE
			|| tick - mobRoutesTick >= MOB_ROUTE_REFRESH_TICKS
			|| !mobRoutes.keySet().equals(threatIds)) {
			LinkedHashMap<String, MobRoute> refreshed = new LinkedHashMap<>();
			for (ResolvedThreat threat : threats) {
				refreshed.put(threat.observed().uuid(), computeMobRoute(player, threat.entity()));
			}
			mobRoutes = Map.copyOf(refreshed);
			mobRoutesTick = tick;
		}
		SecurityKind combined = SecurityKind.SEALED;
		for (ResolvedThreat threat : threats) {
			MobRoute route = mobRoutes.getOrDefault(threat.observed().uuid(), MobRoute.unknown());
			SecurityKind threatSecurity = classifyThreatSecurity(
				route.status(),
				threat.lineOfSight()
			);
			if (threatSecurity == SecurityKind.UNSAFE) {
				combined = SecurityKind.UNSAFE;
			}

		}
		return combined;
	}

	private static MobRoute computeMobRoute(LocalPlayer player, LivingEntity threat) {
		if (!(threat instanceof Mob mob)) {
			return MobRoute.unknown();
		}
		try {
			Path path = mob.getNavigation().createPath(player.blockPosition(), 0);
			if (path == null) {
				return new MobRoute(RouteStatus.BLOCKED, -1);
			}
			if (!path.canReach()) {
				return new MobRoute(RouteStatus.PARTIAL, path.getNodeCount());
			}
			return new MobRoute(RouteStatus.REACHABLE, path.getNodeCount());
		}
		catch (RuntimeException exception) {
			return MobRoute.unknown();
		}
	}

	private SurvivalReflexAction chooseMobAction(LocalPlayer player, List<ResolvedThreat> threats) {
		// DEFEND includes terrain-aware repositioning against multiple attackers.
		return SurvivalReflexAction.DEFEND;
	}

	private static boolean isDrowningDamage(String damageTypeId) {
		return damageTypeId != null && (damageTypeId.equals("drown") || damageTypeId.endsWith(":drown"));
	}

	private ResolvedThreat focusedThreat(List<ResolvedThreat> threats) {
		var player = Minecraft.getInstance().player;
		var candidates = threats.stream().map(t -> {
			var entity = t.entity();
			Vec3 towardPlayer = player.position().subtract(entity.position()).normalize();
			double closingSpeed = entity.getDeltaMovement().subtract(player.getDeltaMovement()).dot(towardPlayer);
			boolean preparingAttack = entity.isUsingItem() && entity.getUseItem().is(net.minecraft.world.item.Items.BOW)
				|| net.minecraft.world.item.CrossbowItem.isCharged(entity.getMainHandItem())
				|| net.minecraft.world.item.CrossbowItem.isCharged(entity.getOffhandItem())
				|| entity instanceof net.minecraft.world.entity.monster.Creeper creeper && creeper.getSwellDir() > 0;
			return new CombatFocus.Candidate(t.observed().uuid(), t.distance(), t.lineOfSight(),
				closingSpeed, isRangedThreat(entity), preparingAttack, t.observed().entityTypeId(), entity.isBaby());
		}).toList();
		String id = CombatFocus.select(candidates);
		if (!java.util.Objects.equals(id, reportedCombatFocus)) {
			reportedCombatFocus = id;
			pendingEvents.add(new SurvivalReflexEvent("reflex.combat_focus", Map.of("threatUuid", id,
				"candidates", candidates.stream().map(c -> Map.of("threatUuid", c.id(), "distance", c.distance(),
					"visible", c.visible(), "closingSpeed", c.closingSpeed(), "preparingAttack", c.preparingAttack(), "priority", c.priority(),
					"entityTypeId", c.entityTypeId(), "baby", c.baby(), "rank", CombatFocus.rank(c, CombatFocus.meleePressure(candidates)))).toList())));
		}
		return threats.stream().filter(t -> t.observed().uuid().equals(id)).findFirst().orElseThrow();
	}

	private static ResolvedThreat closestVisibleThreat(List<ResolvedThreat> threats) {
		return threats.stream()
			.min((left, right) -> {
				if (left.lineOfSight() != right.lineOfSight()) {
					return left.lineOfSight() ? -1 : 1;
				}
				return Double.compare(left.distance(), right.distance());
			})
			.orElseThrow();
	}

	private void resetSecurityProgress() {
		escapingCreeper = null;
		reportedCombatFocus = null;
		combatPositioning = null;
		secureEscapeTicks = 0;
		mobRoutesTick = Long.MIN_VALUE;
		mobRoutes = Map.of();
	}

	private static GoalPosition goal(BlockPos pos) {
		return new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), true);
	}

	private void detectProactiveThreats(Minecraft minecraft, LocalPlayer player, long tick) {
		if (minecraft == null || minecraft.level == null || player == null) {
			return;
		}
		Set<String> targetingPlayer = Set.of();
		if (minecraft.getSingleplayerServer() != null) {
			if (aggroQuery != null && aggroQuery.isDone()) {
				targetingPlayer = aggroQuery.join();
				aggroQuery = null;
			}
			if (aggroQuery == null) {
				var server = minecraft.getSingleplayerServer();
				var dimension = minecraft.level.dimension();
				var bounds = player.getBoundingBox().inflate(32.0D);
				UUID playerId = player.getUUID();
				aggroQuery = server.submit(() -> {
					var level = server.getLevel(dimension);
					if (level == null) {
						return Set.<String>of();
					}
					return level.getEntitiesOfClass(Mob.class, bounds, Entity::isAlive).stream()
						.filter(mob -> mob.getTarget() != null && playerId.equals(mob.getTarget().getUUID()))
						.map(Entity::getStringUUID).collect(java.util.stream.Collectors.toUnmodifiableSet());
				});
			}
		}
		for (Mob mob : minecraft.level.getEntitiesOfClass(Mob.class,
			player.getBoundingBox().inflate(32.0D), Entity::isAlive)) {
			// Remote clients may not receive AI targets; damage observations remain authoritative there.
			boolean targetsUs = targetingPlayer.contains(mob.getStringUUID())
				|| mob.getTarget() != null && player.getUUID().equals(mob.getTarget().getUUID());
			boolean visibleHostile = mob.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER
				&& !(mob instanceof net.minecraft.world.entity.NeutralMob) && player.hasLineOfSight(mob);
			if (!shouldDetectProactiveThreat(targetsUs || visibleHostile, mob.isAlive())
				|| !policy().observesMob(player.distanceTo(mob))) {
				continue;
			}
			String uuid = mob.getStringUUID();
			if (observedThreats.containsKey(uuid)) {
				continue;
			}
			ObservedThreat observed = new ObservedThreat(uuid, mob.getName().getString(),
				BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString(), tick);
			observedThreats.put(uuid, observed);
			pendingEvents.add(new SurvivalReflexEvent("reflex.threat_detected", mapOfNullable(
				"source", targetsUs ? "aggro_target" : "visible_hostile", "uuid", uuid, "name", observed.name(),
				"entityTypeId", observed.entityTypeId(), "distance", player.distanceTo(mob),
				"lineOfSight", player.hasLineOfSight(mob), "tick", tick)));
		}
	}

	private void maintainDrowningSafetyHold(Minecraft minecraft, LocalPlayer player, long tick) {
		if (!policy().drowningEnabled()) {
			if (safetyHoldActuating) {
				underwaterEscape.reset(minecraft);
				movementController.stop(minecraft);
				safetyHoldActuating = false;
			}
			return;
		}
		boolean reachingSafeLand = snapshot.state() == SurvivalReflexState.AWAITING_PLANNER
			&& snapshot.cause() == SurvivalReflexCause.DROWNING
			&& snapshot.action() == SurvivalReflexAction.REACH_SAFE_LAND;
		boolean safeLand = reachingSafeLand
			&& player.onGround()
			&& MinecraftUnderwaterEscapeController.isSafeStandingPosition(minecraft, player.blockPosition());
		if (safeLand) {
			underwaterEscape.reset(minecraft);
			movementController.stop(minecraft);
			safetyHoldActuating = false;
			releaseHold("safe_land_reached", tick);
			return;
		}
		boolean shouldActuate = shouldMaintainDrowningSafetyHold(
			snapshot.state(), snapshot.cause(), player.isInWater()
		);
		if (!shouldActuate) {
			if (safetyHoldActuating) {
				underwaterEscape.reset(minecraft);
				safetyHoldActuating = false;
			}
			return;
		}
		try {
			if (snapshot.action() == SurvivalReflexAction.REACH_SAFE_LAND) {
				MinecraftUnderwaterEscapeController.Snapshot escape = underwaterEscape.tick(
					minecraft,
					UnderwaterEscapeSearch.SearchMode.SAFE_STANDING,
					player.getAirSupply(),
					tick,
					false
				);
				if (safeLandSearchExhausted(escape.searchStatus(), escape.navigation().phase())) {
					underwaterEscape.reset(minecraft);
					changeAction(SurvivalReflexCause.DROWNING, SurvivalReflexAction.STAY_AFLOAT, tick);
					movementController.swimUp(minecraft, false, false, tick);
				}
			}
			else {
				movementController.swimUp(minecraft, false, false, tick);
			}
			safetyHoldActuating = true;
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("maintain_drowning_safety_hold", exception, tick);
		}
	}

	static boolean isRangedThreat(LivingEntity entity) {
		return isRangedThreat(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
			BuiltInRegistries.ITEM.getKey(entity.getMainHandItem().getItem()).toString(),
			entity instanceof RangedAttackMob || entity instanceof CrossbowAttackMob);
	}

	static boolean isRangedThreat(String entityTypeId, String mainHandItemId, boolean rangedInterface) {
		// Drowned implement RangedAttackMob even when their trident attack goal cannot start.
		if (entityTypeId.equals("minecraft:drowned")) return mainHandItemId.equals("minecraft:trident");
		return rangedInterface || switch (entityTypeId) {
				case "minecraft:blaze", "minecraft:breeze", "minecraft:ghast", "minecraft:guardian",
					"minecraft:elder_guardian", "minecraft:shulker", "minecraft:evoker",
					"minecraft:warden", "minecraft:ender_dragon" -> true;
				default -> false;
			};
	}

	private List<ResolvedThreat> resolveThreats(Minecraft minecraft, LocalPlayer player) {
		if (minecraft == null || minecraft.level == null || player == null || observedThreats.isEmpty()) {
			return List.of();
		}
		ArrayList<ResolvedThreat> resolved = new ArrayList<>();
		for (Entity entity : minecraft.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || entity instanceof Player || !entity.isAlive()) {
				continue;
			}
			ObservedThreat observed = observedThreats.get(entity.getStringUUID());
			if (observed == null) {
				continue;
			}
			double distance = player.distanceTo(entity);
			boolean lineOfSight = player.hasLineOfSight(entity);
			if (!policy().observesMob(distance)) {
				continue;
			}
			resolved.add(new ResolvedThreat(observed, living, distance, lineOfSight));
		}
		return List.copyOf(resolved);
	}

	private static List<SurvivalReflexSnapshot.ThreatSnapshot> threatSnapshots(List<ResolvedThreat> threats) {
		if (threats == null || threats.isEmpty()) {
			return List.of();
		}
		return threats.stream().map(threat -> new SurvivalReflexSnapshot.ThreatSnapshot(
			threat.observed().uuid(), threat.observed().name(), threat.observed().entityTypeId(), threat.distance(),
			threat.entity().isAlive(), threat.lineOfSight()
		)).toList();
	}

	private static String failureText(RuntimeException exception) {
		return exception == null || exception.getMessage() == null
			? exception == null ? "unknown" : exception.getClass().getSimpleName()
			: exception.getMessage();
	}

	private static Map<String, Object> mapOfNullable(Object... pairs) {
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		for (int index = 0; index + 1 < pairs.length; index += 2) {
			if (pairs[index] != null && pairs[index + 1] != null) {
				map.put(String.valueOf(pairs[index]), pairs[index + 1]);
			}
		}
		return Map.copyOf(map);
	}

	public record DamageObservation(
		long tick,
		String damageTypeId,
		String attackerUuid,
		String attackerName,
		String attackerEntityTypeId,
		boolean attackerLiving,
		boolean attackerPlayer
	) {
	}

	public record InterruptedWork(String jobId, String actionExecutionId) {
		public static InterruptedWork none() {
			return new InterruptedWork(null, null);
		}

		public boolean hasInterruptedWork() {
			return (jobId != null && !jobId.isBlank()) || (actionExecutionId != null && !actionExecutionId.isBlank());
		}
	}

	public enum ResumeResult {
		RESUMED,
		NO_SAFETY_HOLD,
		REFLEX_ACTIVE,
		STALE_SAFETY_HOLD
	}

	record ObservedThreat(String uuid, String name, String entityTypeId, long lastDamageTick) {
	}

	record ResolvedThreat(ObservedThreat observed, LivingEntity entity, double distance, boolean lineOfSight) {
	}


	enum RouteStatus {
		REACHABLE,
		BLOCKED,
		PARTIAL,
		UNKNOWN
	}

	enum SecurityKind {
		UNSAFE,
		SEALED
	}

	private record MobRoute(RouteStatus status, int pathLength) {
		private static MobRoute unknown() {
			return new MobRoute(RouteStatus.UNKNOWN, -1);
		}
	}

}
