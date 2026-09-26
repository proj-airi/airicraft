package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.tasks.MinecraftUnderwaterEscapeController;
import ai.moeru.airicraft.agent.tasks.UnderwaterEscapeNavigator;
import ai.moeru.airicraft.agent.tasks.UnderwaterEscapeSearch;
import ai.moeru.airicraft.agent.tasks.UnderwaterHarvestPolicy;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.CrossbowUser;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SurvivalReflexRuntime {
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
	private final BaritoneFacade baritone;
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
		boolean needed(ClientPlayerEntity player);
		boolean hasEligibleFood(ClientPlayerEntity player);
		java.util.Optional<String> candidate(ClientPlayerEntity player);
		boolean ready(long tick);
		boolean eating();
		void start(MinecraftClient client, String itemId, long tick);
		void cancel(MinecraftClient client);
	}

	public SurvivalReflexRuntime(AgentConfig.ReflexConfig config) {
		this(config, new MovementController(), new CameraController(), null);
	}

	public SurvivalReflexRuntime(AgentConfig.ReflexConfig config, BaritoneFacade baritone) {
		this(config, new MovementController(), new CameraController(), baritone);
	}

	public SurvivalReflexRuntime(AgentConfig.ReflexConfig config, BaritoneFacade baritone, CameraController cameraController) {
		this(config, new MovementController(), cameraController, baritone);
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
		BaritoneFacade baritone
	) {
		this.config = Objects.requireNonNullElseGet(config, AgentConfig.ReflexConfig::defaults);
		this.movementController = Objects.requireNonNull(movementController, "movementController");
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
		this.baritone = baritone;
		this.underwaterEscape = new MinecraftUnderwaterEscapeController(
			baritone,
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
		MinecraftClient client,
		InterruptedWork interruptedWork,
		long tick,
		Runnable releaseNormalActuators
	) {
		return tick(client, interruptedWork, tick, releaseNormalActuators, null);
	}

	public SurvivalReflexSnapshot tick(
		MinecraftClient client,
		InterruptedWork interruptedWork,
		long tick,
		Runnable releaseNormalActuators,
		CombatEating combatEating
	) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (!config.enabled() || client == null || client.world == null || player == null || player.isDead()) {
			reset(client);
			return snapshot;
		}

		if (tacticalWindow != null && (!tacticalWindow.active(tick, player.getHealth()) || client.world.getEntitiesByClass(
			net.minecraft.entity.mob.CreeperEntity.class, player.getBoundingBox().expand(4),
			c -> c.isAlive() && c.getFuseSpeed() > 0 && c.getLerpedFuseTime(1) >= .5F).size() > 0)) tacticalWindow = null;
		boolean drowningDanger = policy().drowningEnabled() && drowningDanger(player, config.lowAirTicks(), drowningDamageObserved);
		detectProactiveThreats(client, player, tick);
		List<ResolvedThreat> threats = resolveThreats(client, player);
		if (snapshot.state() == SurvivalReflexState.ACTIVE && snapshot.cause() == SurvivalReflexCause.MOB_ATTACK) {
			var targets = new LinkedHashMap<String, CombatProgress.Target>();
			for (var threat : threats) targets.put(threat.observed().uuid(),
				new CombatProgress.Target(threat.distance(), threat.entity().getHealth()));
			boolean wasStalled = combatProgress != null && combatProgress.stalled();
			boolean defeated = progressThreats != null && progressThreats.stream().anyMatch(t -> t.entity().isDead());
			combatProgress = CombatProgress.observe(combatProgress, tick, targets, defeated);
			progressThreats = threats;
			if (combatProgress != null && combatProgress.stalled() != wasStalled) {
				pendingEvents.add(new SurvivalReflexEvent("reflex.combat_progress", Map.of(
					"stalled", combatProgress.stalled(), "noProgressTicks", tick - combatProgress.lastProgressTick(),
					"reason", combatProgress.stalled() ? "no_target_health_or_closing_progress" : "progress_resumed",
					"underPressure", immediateCombatDanger(client, player, threats, tick),
					"targets", targets, "tick", tick)));
			}
		}
		else { combatProgress = null; progressThreats = null; }
		if (combatStalemate != null || snapshot.state() == SurvivalReflexState.ACTIVE && snapshot.cause() == SurvivalReflexCause.MOB_ATTACK) {
			Map<String, Vec3d> positions = new LinkedHashMap<>();
			for (ResolvedThreat threat : threats) positions.put(threat.observed().uuid(), threat.entity().getPos());
			combatStalemate = CombatStalemate.observe(combatStalemate, tick, player.getPos(), positions,
				immediateCombatDanger(client, player, threats, tick));
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
				if (!blockShieldThreat(client, player, threats, tick)) releaseShield(client);
			} else if (tacticalWindow != null) releaseShield(client);
			maintainDrowningSafetyHold(client, player, tick);
			refreshSnapshot(player, threats, snapshot.lastDangerTick(), snapshot.breathableTicks(), snapshot.lastActuatorFailure());
			return snapshot;
		}

		if (snapshot.cause() == SurvivalReflexCause.DROWNING && !policy().drowningEnabled()
			|| snapshot.cause() == SurvivalReflexCause.MOB_ATTACK && !policy().combatEnabled()) {
			resolve(client, player, threats, tick, "reflex_policy_disabled", false);
		}
		else if (recoverCombatMovement(client, player, threats, tick)) {
			// The recovery navigator owns all movement until dry supported ground.
		}
		else if (drowningDanger || snapshot.cause() == SurvivalReflexCause.DROWNING) {
			if (combatEating != null && combatEating.eating()) combatEating.cancel(client);
			releaseShield(client);
			tickDrowning(client, player, drowningDanger, threats.stream().filter(threat ->
				policy().acceptsMob(isRangedThreat(threat.entity()), threat.distance(), threat.lineOfSight())).toList(), tick);
		}
		else {
			tickMobAttack(client, player, threats, tick, combatEating);
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

	public void reset(MinecraftClient client) {
		foodRetreatStartedTick = -1L;
		foodRetreatAbandoned = false;
		foodUnavailableReported = false;
		combatProgress = null;
		tacticalWindow = null;
		progressThreats = null;
		combatRecovery = CombatRecovery.READY;
		combatStalemate = null;
		releaseShield(client);
		movementController.stop(client);
		observedThreats.clear();
		drowningDamageObserved = false;
		lastMobDamageTick = Long.MIN_VALUE;
		safetyHoldActuating = false;
		stopCombatNavigation();
		aggroQuery = null;
		resetSecurityProgress();
		underwaterEscape.reset(client);
		long epoch = snapshot.safetyEpoch();
		snapshot = new SurvivalReflexSnapshot(
			SurvivalReflexState.IDLE, null, null, epoch, null, null, null, List.of(),
			null, null, null, null, -1L, -1L, 0, null
		);
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
		ClientPlayerEntity player,
		List<ResolvedThreat> threats,
		long tick,
		Runnable releaseNormalActuators
	) {
		foodRetreatStartedTick = -1L;
		foodRetreatAbandoned = false;
		foodUnavailableReported = false;
		long nextEpoch = snapshot.safetyEpoch() + 1L;
		combatStalemate = null;
		resetSecurityProgress();
		InterruptedWork work = interruptedWork == null ? InterruptedWork.none() : interruptedWork;
		String holdId = work.hasInterruptedWork() ? UUID.randomUUID().toString() : null;
		snapshot = new SurvivalReflexSnapshot(
			SurvivalReflexState.ACTIVE, cause, action, nextEpoch, holdId, work.jobId(), work.actionExecutionId(),
			threatSnapshots(threats), player.getHealth(), player.getMaxHealth(), player.getAir(), player.getMaxAir(),
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
			"air", player.getAir()
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
		MinecraftClient client,
		ClientPlayerEntity player,
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
			player.isSubmergedInWater(),
			player.getAir(),
			player.getMaxAir()
		);
		boolean safeLand = player.isOnGround()
			&& MinecraftUnderwaterEscapeController.isSafeStandingPosition(client, player.getBlockPos());
		boolean stable = stableDrowningRecovery(airRecovered);
		int stableTicks = stable ? snapshot.breathableTicks() + 1 : 0;
		if (drowningResolved(stableTicks)) {
			if (!mobThreatsResolved(threats.size(), tick, lastMobDamageTick, config.threatCooldownTicks())) {
				underwaterEscape.reset(client);
				changeAction(SurvivalReflexCause.MOB_ATTACK, chooseMobAction(player, threats), tick);
				refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
				return;
			}
			boolean keepSafetyHold = shouldKeepDrowningSafetyHold(
				hasInterruptedWork,
				safeLand
			);
			resolve(
				client,
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
				underwaterEscape.reset(client);
				if (player.isTouchingWater()) {
					movementController.swimUp(client, false, false, tick);
				}
				else {
					movementController.stop(client);
				}
			}
			else {
				UnderwaterEscapeSearch.SearchMode mode = drowningSearchMode(
					hasInterruptedWork,
					player.isSubmergedInWater(),
					player.getAir(),
					player.getMaxAir()
				);
				underwaterEscape.tick(
					client,
					mode,
					player.getAir(),
					tick,
					mode == UnderwaterEscapeSearch.SearchMode.BREATHABLE
						? !player.isSubmergedInWater()
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

	private boolean immediateCombatDanger(MinecraftClient client, ClientPlayerEntity player, List<ResolvedThreat> threats, long tick) {
		if (recentlyDamagedByMob(tick, lastMobDamageTick, config.threatCooldownTicks())
			|| threats.stream().anyMatch(threat -> threat.distance() <= MELEE_THREAT_DISTANCE || threat.entity().isUsingItem())) return true;
		for (var projectile : client.world.getEntitiesByClass(net.minecraft.entity.projectile.PersistentProjectileEntity.class,
			player.getBoundingBox().expand(24), Entity::isAlive)) {
			if (projectile.getOwner() != player && Double.isFinite(incomingProjectileTicks(
				player.getBoundingBox().getCenter().subtract(projectile.getPos()), projectile.getVelocity()))) return true;
		}
		return false;
	}

	static boolean safeToEatDuringCombat(boolean grounded, boolean wet, boolean immediateDanger,
		boolean sealed, boolean visibleThreat, double nearestDistance) {
		return grounded && !wet && !immediateDanger
			&& (sealed || !visibleThreat && nearestDistance >= 10D);
	}

	private boolean recoverCombatMovement(MinecraftClient client, ClientPlayerEntity player, List<ResolvedThreat> threats, long tick) {
		CombatRecovery current = combatRecovery == null ? CombatRecovery.READY : combatRecovery;
		if (current == CombatRecovery.READY && snapshot.cause() != SurvivalReflexCause.MOB_ATTACK) return false;
		boolean safeLand = !player.isTouchingWater() && player.isOnGround()
			&& MinecraftUnderwaterEscapeController.isSafeStandingPosition(client, player.getBlockPos());
		CombatRecovery next = current.next(player.isTouchingWater(), player.isOnGround(), safeLand);
		if (next != current) {
			combatRecovery = next;
			combatStalemate = null;
			stopCombatNavigation();
			combatPositioning = null;
			underwaterEscape.reset(client);
			pendingEvents.add(new SurvivalReflexEvent("reflex.movement_recovery", Map.of("phase", next.name(), "tick", tick)));
		}
		if (next == CombatRecovery.READY) return false;
		releaseShield(client);
		try {
			// Low air gets a breathable waypoint first; breathing alone does not hand control back.
			var mode = player.isSubmergedInWater() && player.getAir() < config.lowAirTicks()
				? UnderwaterEscapeSearch.SearchMode.BREATHABLE : UnderwaterEscapeSearch.SearchMode.SAFE_STANDING;
			var recovery = underwaterEscape.tick(client, mode, player.getAir(), tick,
				mode == UnderwaterEscapeSearch.SearchMode.BREATHABLE ? !player.isSubmergedInWater() : safeLand);
			boolean exhausted = safeLandSearchExhausted(recovery.searchStatus(), recovery.navigation().phase());
			var action = exhausted ? SurvivalReflexAction.STAY_AFLOAT : SurvivalReflexAction.REACH_SAFE_LAND;
			if (snapshot.action() != action) changeAction(SurvivalReflexCause.MOB_ATTACK, action, tick);
			if (exhausted) {
				movementController.swimUp(client, false, false, tick);
			}
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("recover_combat_movement", exception, tick);
			movementController.swimUp(client, false, false, tick);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, failureText(exception));
		}
		return true;
	}

	private void tickMobAttack(MinecraftClient client, ClientPlayerEntity player, List<ResolvedThreat> threats,
		long tick, CombatEating combatEating) {
		if (combatProgress != null && combatProgress.stalled()) {
			tacticalWindow = new TacticalWindow(tick + 1200);
			resolve(client, player, threats, tick, "combat_stalemate", true);
			return;
		}
		if (combatDeferred()) {
			resolve(client, player, threats, tick, "combat_approach_stalled", true);
			return;
		}
		if (snapshot.action() != SurvivalReflexAction.DEFEND) {
			changeAction(SurvivalReflexCause.MOB_ATTACK, SurvivalReflexAction.DEFEND, tick);
		}
		boolean usePositioning = shouldReposition(threats.size()) && baritone != null && baritone.isLoaded();
		updateCreeperEscape(threats);
		if (tickCombatEating(client, player, threats, tick, combatEating, usePositioning)) return;
		if (threats.stream().noneMatch(threat ->
			policy().acceptsMob(isRangedThreat(threat.entity()), threat.distance(), threat.lineOfSight())
				|| combatPositioning != null && threat.distance() <= Math.min(10, policy().maxThreatDistance()))) {
			resolve(client, player, threats, tick, "no_eligible_threats", false);
			return;
		}
		equipBestCombatItem(client, player);
		if (blockShieldThreat(client, player, threats, tick)) {
			if (usePositioning) reposition(client, threats, tick, true);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
			return;
		}
		releaseShield(client);
		attemptCloseQuarterAttack(client, player, threats, tick);
		SecurityKind security = assessMobSecurity(player, threats, tick);
		if (security == SecurityKind.SEALED) {
			secureEscapeTicks++;
			stopCombatNavigation();
			movementController.stop(client);
			if (secureEscapeTicks >= SHELTER_CONFIRM_TICKS) {
				resolve(client, player, threats, tick, "sealed_shelter", false);
				return;
			}
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
			return;
		}
		secureEscapeTicks = 0;
		try {
			if (usePositioning) {
				reposition(client, threats, tick, false);
			}
			else if (!threats.isEmpty()) {
				defend(client, player, focusedThreat(threats), tick);
			}
			else {
				movementController.stop(client);
			}
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("defend", exception, tick);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, failureText(exception));
		}
	}

	private boolean tickCombatEating(MinecraftClient client, ClientPlayerEntity player, List<ResolvedThreat> threats,
		long tick, CombatEating eating, boolean usePositioning) {
		if (eating == null) return false;
		var candidate = eating.candidate(player);
		if (!eating.eating() && candidate.isEmpty()) {
			if (eating.needed(player) && !eating.hasEligibleFood(player)
				&& !foodUnavailableReported && !foodRetreatAbandoned) {
				foodUnavailableReported = true;
				pendingEvents.add(new SurvivalReflexEvent("reflex.food_unavailable", Map.of(
					"tick", tick, "health", player.getHealth(),
					"hunger", player.getHungerManager().getFoodLevel())));
			}
			foodRetreatStartedTick = -1L;
			return false;
		}
		if (foodRetreatAbandoned) return false;
		foodUnavailableReported = false;
		boolean immediate = immediateCombatDanger(client, player, threats, tick);
		boolean visible = threats.stream().anyMatch(ResolvedThreat::lineOfSight);
		double nearest = threats.stream().mapToDouble(ResolvedThreat::distance).min().orElse(Double.POSITIVE_INFINITY);
		boolean sheltered = !immediate && assessMobSecurity(player, threats, tick) == SecurityKind.SEALED;
		boolean safe = safeToEatDuringCombat(player.isOnGround(), player.isTouchingWater(), immediate,
			sheltered, visible, nearest);
		if (eating.eating()) {
			if (!safe) {
				eating.cancel(client);
				pendingEvents.add(new SurvivalReflexEvent("reflex.food_eat_interrupted", Map.of("tick", tick, "reason", "threat_returned")));
				return false;
			}
			stopCombatNavigation();
			movementController.stop(client);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
			return true;
		}
		if (foodRetreatStartedTick < 0) foodRetreatStartedTick = tick;
		if (safe) {
			releaseShield(client);
			stopCombatNavigation();
			movementController.stop(client);
			if (!eating.ready(tick)) {
				refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
				return true;
			}
			try {
				eating.start(client, candidate.orElseThrow(), tick);
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
		boolean shielding = blockShieldThreat(client, player, threats, tick);
		if (!shielding) releaseShield(client);
		reposition(client, threats, tick, shielding, true);
		refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
		return true;
	}

	private boolean blockShieldThreat(MinecraftClient client, ClientPlayerEntity player, List<ResolvedThreat> threats, long tick) {
		if (client.interactionManager == null) return false;
		// Resolve the backing inventory index: combat may interrupt an open container.
		if (!player.getOffHandStack().isOf(net.minecraft.item.Items.SHIELD)) {
			for (var slot : player.currentScreenHandler.slots) {
				if (slot.inventory == player.getInventory() && slot.getIndex() < 36
					&& slot.getStack().isOf(net.minecraft.item.Items.SHIELD)) {
					client.interactionManager.clickSlot(player.currentScreenHandler.syncId, slot.id, 40,
						net.minecraft.screen.slot.SlotActionType.SWAP, player);
					break;
				}
			}
		}
		if (!player.getOffHandStack().isOf(net.minecraft.item.Items.SHIELD)
			|| player.getItemCooldownManager().isCoolingDown(player.getOffHandStack())) return false;
		if (shieldGuard != null) for (var t : threats) {
			if (t.observed().uuid().equals(shieldGuard.source()) && t.entity() instanceof net.minecraft.entity.mob.CreeperEntity c
				&& !shouldBlockCreeper(t.distance(), c.getFuseSpeed(), c.getLerpedFuseTime(1), c.isCharged())) shieldGuard = null;
			if (shieldGuard == null) break;
		}
		net.minecraft.util.math.Vec3d aim = null;
		String source = null;
		double earliest = Double.POSITIVE_INFINITY;
		Float fuseProgress = null;
		for (ResolvedThreat threat : threats) {
			if (threat.entity() instanceof net.minecraft.entity.mob.CreeperEntity creeper
				&& threat.lineOfSight() && shouldBlockCreeper(threat.distance(), creeper.getFuseSpeed(), creeper.getLerpedFuseTime(1), creeper.isCharged())) {
				aim = shieldFacingPoint(player.getEyePos(), creeper.getPos(), Vec3d.ZERO);
				source = creeper.getUuidAsString();
				fuseProgress = creeper.getLerpedFuseTime(1);
				break;
			}
		}
		if (aim == null) for (var projectile : client.world.getEntitiesByClass(net.minecraft.entity.projectile.PersistentProjectileEntity.class,
			player.getBoundingBox().expand(24), Entity::isAlive)) {
			if (projectile.getOwner() == player) continue;
			var relative = player.getBoundingBox().getCenter().subtract(projectile.getPos());
			var velocity = projectile.getVelocity();
			double arrival = incomingProjectileTicks(relative, velocity);
			if (arrival < earliest) {
				Entity shooter = projectile.getOwner();
				Vec3d facing = shieldFacingPoint(player.getEyePos(), shooter == null ? null : shooter.getPos(), velocity);
				if (facing == null) continue;
				earliest = arrival;
				aim = facing;
				source = shooter == null ? null : shooter.getUuidAsString();
			}
		}
		if (aim == null) {
			boolean reloadingCrossbow = false;
			boolean otherRangedThreat = false;
			for (ResolvedThreat threat : threats) {
				var entity = threat.entity();
				if (!threat.lineOfSight()) continue;
				boolean drawingBow = entity.isUsingItem() && entity.getActiveItem().isOf(net.minecraft.item.Items.BOW);
				// Crossbows fire after item use ends. Guard the loaded weapon, leaving reload time for counterattacks.
				var mainHand = entity.getMainHandStack();
				var offHand = entity.getOffHandStack();
				boolean loadedCrossbow = (mainHand.isOf(net.minecraft.item.Items.CROSSBOW) && net.minecraft.item.CrossbowItem.isCharged(mainHand))
					|| (offHand.isOf(net.minecraft.item.Items.CROSSBOW) && net.minecraft.item.CrossbowItem.isCharged(offHand));
				if (shouldGuardBow(drawingBow, entity.getItemUseTime()) || loadedCrossbow) {
					aim = shieldFacingPoint(player.getEyePos(), entity.getPos(), Vec3d.ZERO);
					source = entity.getUuidAsString();
					break;
				}
				if (mainHand.isOf(net.minecraft.item.Items.CROSSBOW) || offHand.isOf(net.minecraft.item.Items.CROSSBOW)) {
					boolean reloading = entity.isUsingItem() && entity.getActiveItem().isOf(net.minecraft.item.Items.CROSSBOW);
					reloadingCrossbow |= reloading;
					otherRangedThreat |= !reloading;
				}
				otherRangedThreat |= mainHand.isOf(net.minecraft.item.Items.BOW) || offHand.isOf(net.minecraft.item.Items.BOW);
			}
			// A confirmed reload cannot fire. Incoming arrows and other ready weapons still take priority above.
			if (aim == null && reloadingCrossbow && !otherRangedThreat) shieldGuard = null;
		}
		shieldGuard = nextShieldGuard(shieldGuard, aim, source, tick);
		if (shieldGuard == null) return false;
		movementController.stop(client);
		ResolvedThreat approach = threats.isEmpty() ? null : closestVisibleThreat(threats);
		if (!shouldReposition(threats.size())) {
			if (fuseProgress == null && approach != null && approach.distance() > MELEE_ATTACK_DISTANCE
				&& baritone != null && baritone.isLoaded()) {
				updateCombatNavigation(goal(approach.entity().getBlockPos()), tick);
			}
			else {
				stopCombatNavigation();
			}
		}
		cameraController.lookAt(client, new Vec3d(shieldGuard.facing().x, player.getEyeY(), shieldGuard.facing().z));
		client.options.useKey.setPressed(true);
		if (!shieldUseOwned) pendingEvents.add(new SurvivalReflexEvent("reflex.shield_raised", mapOfNullable(
			"sourceUuid", shieldGuard.source(), "incomingProjectile", Double.isFinite(earliest),
			"creeperFuseProgress", fuseProgress,
			"shieldDamage", player.getOffHandStack().getDamage(), "health", player.getHealth(), "tick", tick)));
		shieldUseOwned = true;
		if (!player.isUsingItem() || player.getActiveHand() != Hand.OFF_HAND)
			client.interactionManager.interactItem(player, Hand.OFF_HAND);
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

	record ShieldGuard(Vec3d facing, String source, long throughTick) {}

	static ShieldGuard nextShieldGuard(ShieldGuard previous, Vec3d facing, String source, long tick) {
		if (facing != null) return new ShieldGuard(facing, source, tick + 6);
		return previous != null && tick <= previous.throughTick() ? previous : null;
	}

	/** Shield coverage needs a heading toward the shooter, not an aim lock on an arrow. */
	static Vec3d shieldFacingPoint(Vec3d eye, Vec3d shooter, Vec3d projectileVelocity) {
		if (shooter != null) return new Vec3d(shooter.x, eye.y, shooter.z);
		Vec3d incomingDirection = new Vec3d(-projectileVelocity.x, 0, -projectileVelocity.z);
		return incomingDirection.lengthSquared() < 0.0001 ? null : eye.add(incomingDirection.normalize().multiply(8));
	}

	/** Closest approach within the next eight ticks; ignore stopped, receding and passing arrows. */
	static double incomingProjectileTicks(net.minecraft.util.math.Vec3d relative, net.minecraft.util.math.Vec3d velocity) {
		double speedSquared = velocity.lengthSquared();
		if (speedSquared < 0.01D) return Double.POSITIVE_INFINITY;
		double ticks = relative.dotProduct(velocity) / speedSquared;
		if (ticks < 0 || ticks > 8) return Double.POSITIVE_INFINITY;
		return relative.subtract(velocity.multiply(ticks)).lengthSquared() <= 2.25D
			? ticks : Double.POSITIVE_INFINITY;
	}

	private void releaseShield(MinecraftClient client) {
		shieldGuard = null;
		if (!shieldUseOwned) return;
		shieldUseOwned = false;
		if (client == null) return;
		client.options.useKey.setPressed(false);
		if (client.player != null) pendingEvents.add(new SurvivalReflexEvent("reflex.shield_lowered", mapOfNullable(
			"shieldDamage", client.player.getOffHandStack().getDamage(), "health", client.player.getHealth(),
			"wasBlocking", client.player.isBlocking())));
		if (client.player != null && client.interactionManager != null && client.player.isUsingItem()
			&& client.player.getActiveItem().isOf(net.minecraft.item.Items.SHIELD))
			client.interactionManager.stopUsingItem(client.player);
	}

	private void defend(MinecraftClient client, ClientPlayerEntity player, ResolvedThreat threat, long tick) {
		if (threat.distance() <= 3.0D && threat.lineOfSight()) {
			cameraController.lookAt(client, threat.entity().getBoundingBox().getCenter());
			stopCombatNavigation();
			movementController.stop(client);
			return;
		}
		if (baritone != null && baritone.isLoaded()) {
			movementController.stop(client);
			updateCombatNavigation(goal(threat.entity().getBlockPos()), tick);
		}
		else if (threat.lineOfSight()) {
			cameraController.lookAt(client, threat.entity().getBoundingBox().getCenter());
			movementController.moveDirectional(client, true, false, false, false, true, false, tick);
		}
		else {
			movementController.stop(client);
		}
	}

	static boolean shouldReposition(int threatCount) { return threatCount >= 1; }

	private void updateCreeperEscape(List<ResolvedThreat> threats) {
		// Keep tracking the live pursuer, not the position where it first ignited.
		escapingCreeper = threats.stream().filter(t -> t.entity() instanceof net.minecraft.entity.mob.CreeperEntity c
			&& creeperEscapeActive(t.observed().uuid().equals(escapingCreeper), t.distance(), c.getFuseSpeed(), c.getLerpedFuseTime(1), c.isCharged()))
			.min(java.util.Comparator.comparingDouble(ResolvedThreat::distance))
			.map(t -> t.observed().uuid()).orElse(null);
	}

	private void reposition(MinecraftClient client, List<ResolvedThreat> threats, long tick, boolean shielding) {
		reposition(client, threats, tick, shielding, false);
	}

	private void reposition(MinecraftClient client, List<ResolvedThreat> threats, long tick,
		boolean shielding, boolean retreatForFood) {
		if (baritone == null || !baritone.isLoaded()) {
			stopCombatNavigation();
			movementController.stop(client);
			return;
		}
		if (combatPositioning == null) {
			stopCombatNavigation();
			combatPositioning = new MinecraftCombatPositioning();
		}
		var focus = threats.stream().filter(t -> t.entity() instanceof net.minecraft.entity.mob.CreeperEntity c
			&& t.observed().uuid().equals(escapingCreeper)).min(java.util.Comparator.comparingDouble(ResolvedThreat::distance))
			.orElseGet(() -> focusedThreat(threats));
		boolean escaping = focus.observed().uuid().equals(escapingCreeper);
		boolean kiting = focus.entity() instanceof net.minecraft.entity.mob.CreeperEntity c
			&& creeperShouldKite(client.player.getAttackCooldownProgress(0), c.getLerpedFuseTime(1));
		double desiredDistance = escaping ? (((net.minecraft.entity.mob.CreeperEntity) focus.entity()).isCharged() ? 14 : 8)
			: kiting ? 5 : 2.6;
		if (retreatForFood) desiredDistance = Math.max(10D, desiredDistance);
		var decision = combatPositioning.plan(client, threats.stream().map(ResolvedThreat::entity).toList(), focus.entity(), tick, shielding, desiredDistance);
		var step = decision.nextStep();
		if (step == null) step = new CombatPositioning.Cell(client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ());
		if (!combatPositioning.canStepTo(step)) {
			stopCombatNavigation();
			movementController.stop(client);
			combatPositioning.invalidate();
			return;
		}
		GoalPosition target = new GoalPosition(step.x(), step.y(), step.z(), true);
		// Always steer using the actual spring angle, including while turning into a sprint.
		Vec3d actualFacing = client.player.getPos().add(Vec3d.fromPolar(0.0F, client.player.getYaw()));
		var control = combatPositioning.control(client, step, actualFacing, focus.entity().getPos(), tick);
		var steering = control.steering();
		boolean sprintEscape = escaping && !shielding && !control.jump() && !control.sneak()
			&& client.player.isOnGround() && !client.player.isTouchingWater()
			&& client.player.getHungerManager().getFoodLevel() > 6;
		Vec3d facing = sprintEscape
			? new Vec3d(step.x() + .5, client.player.getEyeY(), step.z() + .5)
			: focus.entity().getBoundingBox().getCenter();
		// Walking, traversal and guarding retain the threat heading. Only sprint escape turns away.
		if (shielding && shieldGuard != null) facing = shieldGuard.facing();
		cameraController.lookAt(client, facing);
		boolean sprint = !shielding && (!kiting && !escaping || sprintEscape)
			&& steering.forward() && !steering.back() && focus.entity() instanceof net.minecraft.entity.mob.CreeperEntity
			&& !control.sneak() && client.player.getHungerManager().getFoodLevel() > 6;

		if (!shielding && !control.jump() && !control.sneak() && focus.entity() instanceof net.minecraft.entity.mob.WitchEntity) {
			var strafe = combatPositioning.witchSprint(client, step, focus.entity().getPos());
			if (strafe != null) {
				facing = strafe.facing();
				cameraController.lookAt(client, facing);
				// Sprint keys assume the orbit heading; use current-heading steering while turning.
				if (cameraController.isLookingAt(client, facing, .5F)) {
					steering = strafe.steering();
					sprint = true;
				}
			}
		}
		movementController.moveDirectional(client, steering.forward(), steering.back(), steering.left(), steering.right(), sprint,
			control.jump(), control.sneak(), tick);
		if (!target.equals(combatTarget)) pendingEvents.add(new SurvivalReflexEvent("reflex.combat_reposition", Map.of(
			"target", target, "threatCount", threats.size(), "risk", decision.risk(), "standingRisk", decision.standingRisk(),
			"route", decision.route(), "shielding", shielding, "facing", facing, "steering", steering, "escapingCreeper", escaping, "tick", tick)));
		combatTarget = target;
		combatRouteTick = tick;
	}

	void updateCombatNavigation(GoalPosition target, long tick) {
		if (combatTarget == null || tick - combatRouteTick >= 20L
			&& (!target.equals(combatTarget) || !baritone.processActive())) {
			baritone.startNavigateNear(target, 2);
			combatTarget = target;
			combatRouteTick = tick;
		}
	}

	private void stopCombatNavigation() {
		if (combatTarget != null && baritone != null) {
			baritone.cancel();
		}
		combatTarget = null;
	}

	private void resolve(
		MinecraftClient client,
		ClientPlayerEntity player,
		List<ResolvedThreat> threats,
		long tick,
		String reason,
		boolean keepSafetyHold
	) {
		foodRetreatStartedTick = -1L;
		foodRetreatAbandoned = false;
		foodUnavailableReported = false;
		releaseShield(client);
		underwaterEscape.reset(client);
		stopCombatNavigation();
		movementController.stop(client);
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
			"position", goal(player.getBlockPos()),
			"remainingThreats", threatSnapshots(threats),
			"noProgressTicks", noProgressTicks(combatProgress, combatStalemate, tick),
			"nextState", nextState.name()
		)));
		snapshot = new SurvivalReflexSnapshot(
			nextState, snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), nextHoldId,
			snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), threatSnapshots(threats),
			player.getHealth(), player.getMaxHealth(), player.getAir(), player.getMaxAir(), snapshot.startedTick(),
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
		MinecraftClient client,
		ClientPlayerEntity player,
		List<ResolvedThreat> threats,
		long tick
	) {
		if (escapingCreeper != null || client.interactionManager == null || threats == null || threats.isEmpty()) {
			return;
		}
		ResolvedThreat threat = focusedThreat(threats);
		float cooldown = player.getAttackCooldownProgress(0.0F);
		if (!shouldAttackCloseThreat(threat.distance(), threat.lineOfSight(), cooldown)) {
			return;
		}
		// Early-fuse strikes can interrupt the approach; late fuse belongs to escape/blocking.
		if (threat.entity() instanceof net.minecraft.entity.mob.CreeperEntity c && c.getLerpedFuseTime(1) >= .2F) return;
		cameraController.lookAt(client, threat.entity().getBoundingBox().getCenter());
		if (!cameraController.isAimingAt(client, threat.entity().getBoundingBox())) return;
		// Send the current hit-facing before attack so server knockback uses it too.
		player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(
			player.getYaw(), player.getPitch(), player.isOnGround(), player.horizontalCollision));
		boolean sprintHit = player.isSprinting();
		client.interactionManager.attackEntity(player, threat.entity());
		player.swingHand(Hand.MAIN_HAND);
		pendingEvents.add(new SurvivalReflexEvent("reflex.close_quarter_attack", mapOfNullable(
			"threatUuid", threat.observed().uuid(),
			"sprinting", sprintHit,
			"weaponItemId", Registries.ITEM.getId(player.getMainHandStack().getItem()).toString(),
			"entityTypeId", threat.observed().entityTypeId(),
			"distance", threat.distance(),
			"action", snapshot.action() == null ? null : snapshot.action().name(),
			"tick", tick
		)));
	}

	private static void equipBestCombatItem(MinecraftClient client, ClientPlayerEntity player) {
		List<String> itemIds = new ArrayList<>();
		for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; slot++)
			itemIds.add(Registries.ITEM.getId(player.getInventory().getStack(slot).getItem()).toString());
		int bestSlot = bestCombatInventorySlot(itemIds);
		if (bestSlot < 0) return;
		if (bestSlot < 9) {
			player.getInventory().setSelectedSlot(bestSlot);
			return;
		}
		if (client.interactionManager == null) return;
		// Match backing inventory indices, since an interrupted task may have a container open.
		for (var slot : player.currentScreenHandler.slots) {
			if (slot.inventory == player.getInventory() && slot.getIndex() == bestSlot) {
				client.interactionManager.clickSlot(player.currentScreenHandler.syncId, slot.id,
					player.getInventory().getSelectedSlot(), net.minecraft.screen.slot.SlotActionType.SWAP, player);
				return;
			}
		}
	}

	static int bestCombatInventorySlot(List<String> itemIds) {
		int bestSlot = -1;
		int bestRank = Integer.MAX_VALUE;
		for (int slot = 0; slot < Math.min(net.minecraft.entity.player.PlayerInventory.MAIN_SIZE, itemIds.size()); slot++) {
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
		ClientPlayerEntity player,
		List<ResolvedThreat> threats,
		long lastDangerTick,
		int breathableTicks,
		String actuatorFailure
	) {
		snapshot = new SurvivalReflexSnapshot(
			snapshot.state(), snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), snapshot.holdId(),
			snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), threatSnapshots(threats),
			player.getHealth(), player.getMaxHealth(), player.getAir(), player.getMaxAir(), snapshot.startedTick(),
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

	static boolean drowningDanger(ClientPlayerEntity player, int lowAirTicks, boolean drowningDamageObserved) {
		return shouldStartDrowning(
			player != null && player.isSubmergedInWater(),
			player == null ? Integer.MAX_VALUE : player.getAir(),
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

	private SecurityKind assessMobSecurity(ClientPlayerEntity player, List<ResolvedThreat> threats, long tick) {
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

	private static MobRoute computeMobRoute(ClientPlayerEntity player, LivingEntity threat) {
		if (!(threat instanceof MobEntity mob)) {
			return MobRoute.unknown();
		}
		try {
			Path path = mob.getNavigation().findPathTo(player.getBlockPos(), 0);
			if (path == null) {
				return new MobRoute(RouteStatus.BLOCKED, -1);
			}
			if (!path.reachesTarget()) {
				return new MobRoute(RouteStatus.PARTIAL, path.getLength());
			}
			return new MobRoute(RouteStatus.REACHABLE, path.getLength());
		}
		catch (RuntimeException exception) {
			return MobRoute.unknown();
		}
	}

	private SurvivalReflexAction chooseMobAction(ClientPlayerEntity player, List<ResolvedThreat> threats) {
		// DEFEND includes terrain-aware repositioning against multiple attackers.
		return SurvivalReflexAction.DEFEND;
	}

	private static boolean isDrowningDamage(String damageTypeId) {
		return damageTypeId != null && (damageTypeId.equals("drown") || damageTypeId.endsWith(":drown"));
	}

	private ResolvedThreat focusedThreat(List<ResolvedThreat> threats) {
		var player = MinecraftClient.getInstance().player;
		var candidates = threats.stream().map(t -> {
			var entity = t.entity();
			Vec3d towardPlayer = player.getPos().subtract(entity.getPos()).normalize();
			double closingSpeed = entity.getVelocity().subtract(player.getVelocity()).dotProduct(towardPlayer);
			boolean preparingAttack = entity.isUsingItem() && entity.getActiveItem().isOf(net.minecraft.item.Items.BOW)
				|| net.minecraft.item.CrossbowItem.isCharged(entity.getMainHandStack())
				|| net.minecraft.item.CrossbowItem.isCharged(entity.getOffHandStack())
				|| entity instanceof net.minecraft.entity.mob.CreeperEntity creeper && creeper.getFuseSpeed() > 0;
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

	private void detectProactiveThreats(MinecraftClient client, ClientPlayerEntity player, long tick) {
		if (client == null || client.world == null || player == null) {
			return;
		}
		Set<String> targetingPlayer = Set.of();
		if (client.getServer() != null) {
			if (aggroQuery != null && aggroQuery.isDone()) {
				targetingPlayer = aggroQuery.join();
				aggroQuery = null;
			}
			if (aggroQuery == null) {
				var server = client.getServer();
				var dimension = client.world.getRegistryKey();
				var bounds = player.getBoundingBox().expand(32.0D);
				UUID playerId = player.getUuid();
				aggroQuery = server.submit(() -> {
					var world = server.getWorld(dimension);
					if (world == null) {
						return Set.<String>of();
					}
					return world.getEntitiesByClass(MobEntity.class, bounds, Entity::isAlive).stream()
						.filter(mob -> mob.getTarget() != null && playerId.equals(mob.getTarget().getUuid()))
						.map(Entity::getUuidAsString).collect(java.util.stream.Collectors.toUnmodifiableSet());
				});
			}
		}
		for (MobEntity mob : client.world.getEntitiesByClass(MobEntity.class,
			player.getBoundingBox().expand(32.0D), Entity::isAlive)) {
			// Remote clients may not receive AI targets; damage observations remain authoritative there.
			boolean targetsUs = targetingPlayer.contains(mob.getUuidAsString())
				|| mob.getTarget() != null && player.getUuid().equals(mob.getTarget().getUuid());
			boolean visibleHostile = mob.getType().getSpawnGroup() == net.minecraft.entity.SpawnGroup.MONSTER
				&& !(mob instanceof net.minecraft.entity.mob.Angerable) && player.canSee(mob);
			if (!shouldDetectProactiveThreat(targetsUs || visibleHostile, mob.isAlive())
				|| !policy().observesMob(player.distanceTo(mob))) {
				continue;
			}
			String uuid = mob.getUuidAsString();
			if (observedThreats.containsKey(uuid)) {
				continue;
			}
			ObservedThreat observed = new ObservedThreat(uuid, mob.getName().getString(),
				Registries.ENTITY_TYPE.getId(mob.getType()).toString(), tick);
			observedThreats.put(uuid, observed);
			pendingEvents.add(new SurvivalReflexEvent("reflex.threat_detected", mapOfNullable(
				"source", targetsUs ? "aggro_target" : "visible_hostile", "uuid", uuid, "name", observed.name(),
				"entityTypeId", observed.entityTypeId(), "distance", player.distanceTo(mob),
				"lineOfSight", player.canSee(mob), "tick", tick)));
		}
	}

	private void maintainDrowningSafetyHold(MinecraftClient client, ClientPlayerEntity player, long tick) {
		if (!policy().drowningEnabled()) {
			if (safetyHoldActuating) {
				underwaterEscape.reset(client);
				movementController.stop(client);
				safetyHoldActuating = false;
			}
			return;
		}
		boolean reachingSafeLand = snapshot.state() == SurvivalReflexState.AWAITING_PLANNER
			&& snapshot.cause() == SurvivalReflexCause.DROWNING
			&& snapshot.action() == SurvivalReflexAction.REACH_SAFE_LAND;
		boolean safeLand = reachingSafeLand
			&& player.isOnGround()
			&& MinecraftUnderwaterEscapeController.isSafeStandingPosition(client, player.getBlockPos());
		if (safeLand) {
			underwaterEscape.reset(client);
			movementController.stop(client);
			safetyHoldActuating = false;
			releaseHold("safe_land_reached", tick);
			return;
		}
		boolean shouldActuate = shouldMaintainDrowningSafetyHold(
			snapshot.state(), snapshot.cause(), player.isTouchingWater()
		);
		if (!shouldActuate) {
			if (safetyHoldActuating) {
				underwaterEscape.reset(client);
				safetyHoldActuating = false;
			}
			return;
		}
		try {
			if (snapshot.action() == SurvivalReflexAction.REACH_SAFE_LAND) {
				MinecraftUnderwaterEscapeController.Snapshot escape = underwaterEscape.tick(
					client,
					UnderwaterEscapeSearch.SearchMode.SAFE_STANDING,
					player.getAir(),
					tick,
					false
				);
				if (safeLandSearchExhausted(escape.searchStatus(), escape.navigation().phase())) {
					underwaterEscape.reset(client);
					changeAction(SurvivalReflexCause.DROWNING, SurvivalReflexAction.STAY_AFLOAT, tick);
					movementController.swimUp(client, false, false, tick);
				}
			}
			else {
				movementController.swimUp(client, false, false, tick);
			}
			safetyHoldActuating = true;
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("maintain_drowning_safety_hold", exception, tick);
		}
	}

	static boolean isRangedThreat(LivingEntity entity) {
		return isRangedThreat(Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
			Registries.ITEM.getId(entity.getMainHandStack().getItem()).toString(),
			entity instanceof RangedAttackMob || entity instanceof CrossbowUser);
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

	private List<ResolvedThreat> resolveThreats(MinecraftClient client, ClientPlayerEntity player) {
		if (client == null || client.world == null || player == null || observedThreats.isEmpty()) {
			return List.of();
		}
		ArrayList<ResolvedThreat> resolved = new ArrayList<>();
		for (Entity entity : client.world.getEntities()) {
			if (!(entity instanceof LivingEntity living) || entity instanceof PlayerEntity || !entity.isAlive()) {
				continue;
			}
			ObservedThreat observed = observedThreats.get(entity.getUuidAsString());
			if (observed == null) {
				continue;
			}
			double distance = player.distanceTo(entity);
			boolean lineOfSight = player.canSee(entity);
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
