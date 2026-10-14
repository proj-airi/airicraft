package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.work.ReadableIds;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public final class SurvivalReflexRuntime {
	private final Actuator actuator = new Actuator("reflex", Priority.REFLEX);
	static final int BREATHABLE_STABLE_TICKS = 12;
	private static final float ATTACK_READY_THRESHOLD = 0.92F;
	private static final double MELEE_ATTACK_DISTANCE = 3.0D;
	static final double MELEE_THREAT_DISTANCE = 6.0D;
	static final double WET_FIGHT_DISTANCE = 4.0D;
	static final long ESCAPE_RETRY_TICKS = 100L;
	private static final int SHELTER_CONFIRM_TICKS = 200;
	private static final int MOB_ROUTE_REFRESH_TICKS = 10;
	private static final long FOOD_RETREAT_LIMIT_TICKS = 100L;
	static final int LAVA_CLEAR_TICKS = 5;
	static final int EXTINGUISH_MAX_ATTEMPTS = 20;
	static final int LAVA_ESCAPE_RADIUS = 5;
	/** Ticks without closing in on the chosen bank before trying another. */
	static final int LAVA_STALL_TICKS = 30;
	/** Ticks of escaping a lit creeper with no distance gained before running straight away. */
	static final int CREEPER_STALL_TICKS = 8;
	static final int CREEPER_RUN_TICKS = 60;
	static final int CREEPER_RUN_BLOCKS = 12;
	static final int NO_CONTACT_RESUME_LIMIT = 3;
	static final long NO_CONTACT_RESUME_WINDOW = 1200L;

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
	/** Set when a hold came from a mob episode with no contact; see {@link #takeNoContactResume}. */
	private boolean noContactHold;
	private final java.util.ArrayDeque<Long> noContactResumes = new java.util.ArrayDeque<>();
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
	/** The planner's turn to pick a tactic; a mob that lands a hit after it opened ends it, so the reflex fights again. */
	record TacticalWindow(long untilTick, long openedTick) {
		TacticalWindow(long untilTick) { this(untilTick, Long.MIN_VALUE); }
		boolean active(long tick, float health) { return active(tick, health, Long.MIN_VALUE); }
		boolean active(long tick, float health, long lastMobDamageTick) {
			return tick < untilTick && health > 4 && lastMobDamageTick <= openedTick;
		}
	}
	private List<ResolvedThreat> progressThreats;
	private MinecraftCombatPositioning combatPositioning;
	private String escapingCreeper;
	/** A straight run from a lit creeper, taken when stepping about gains no distance (see {@link #tickCreeperRun}). */
	private ai.moeru.airicraft.navigation.GridPos creeperRunTarget;
	private long creeperRunTick;
	private double creeperBestDistance = -1;
	private long creeperGainTick;
	private ai.moeru.airicraft.agent.navigation.MinecraftCellClassifier creeperClassifier;
	private String reportedCombatFocus;
	private CombatRecovery combatRecovery;
	private boolean wetFighting;
	private long escapeExhaustedTick = -1L;
	private ReflexPolicy policyOverride;
	/** Mob types a running task fights itself; see {@link #taskOwnsThreatTypes}. */
	private Set<String> taskOwnedThreatTypes = Set.of();
	private long foodRetreatStartedTick = -1L;
	private BlockPos extinguishPour;
	private int extinguishAttempts;
	/** Banks the body could not close in on during this escape. */
	private final Set<BlockPos> lavaBlockedTargets = new HashSet<>();
	private BlockPos lavaTarget;
	private double lavaTargetBestDistance;
	private long lavaTargetProgressTick;
	private boolean foodRetreatAbandoned;
	private boolean foodUnavailableReported;
	private final EmergencyBunker bunker = new EmergencyBunker();
	private final ReflexArchery archery = new ReflexArchery();
	private final UnarmedFlight flight = new UnarmedFlight();

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

	/**
	 * Mob types a running task fights itself, so the reflex leaves them alone: collect_blaze_rods shields, strafes and
	 * hides from blazes, where the reflex's melee pursuit of one blaze let the others burn the playtest body to death.
	 * Threats of those types already observed are dropped. An empty set gives every type back to the reflex.
	 */
	public void taskOwnsThreatTypes(Set<String> entityTypeIds) {
		Set<String> owned = Set.copyOf(entityTypeIds);
		if (owned.equals(taskOwnedThreatTypes)) return;
		taskOwnedThreatTypes = owned;
		observedThreats.values().removeIf(threat -> owned.contains(threat.entityTypeId()));
	}

	private boolean reflexThreat(String entityTypeId) {
		return reflexThreatType(entityTypeId) && !taskOwnedThreatTypes.contains(entityTypeId);
	}

	public Map<String, Object> decisionEvidence() {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("snapshot", snapshot);
		evidence.put("policy", policy());
		evidence.put("taskOwnedThreatTypes", taskOwnedThreatTypes);
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
		if (!observation.attackerLiving() || observation.attackerPlayer() || observation.attackerUuid() == null
			|| !reflexThreat(observation.attackerEntityTypeId())) {
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

		if (tacticalWindow != null && (!tacticalWindow.active(tick, player.getHealth(), lastMobDamageTick) || minecraft.level.getEntitiesOfClass(
			net.minecraft.world.entity.monster.Creeper.class, player.getBoundingBox().inflate(4),
			c -> c.isAlive() && c.getSwellDir() > 0 && c.getSwelling(1) >= .5F).size() > 0)) tacticalWindow = null;
		boolean lavaDanger = player.isInLava();
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
		if (shouldBeginReflex(snapshot.state(), lavaDanger || drowningDanger || mobDanger)) {
			SurvivalReflexCause cause = lavaDanger ? SurvivalReflexCause.LAVA
				: drowningDanger ? SurvivalReflexCause.DROWNING : SurvivalReflexCause.MOB_ATTACK;
			boolean hasInterruptedWork = interruptedWork != null && interruptedWork.hasInterruptedWork();
			SurvivalReflexAction action = lavaDanger ? SurvivalReflexAction.ESCAPE_LAVA
				: drowningDanger
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
		else if (lavaDanger || snapshot.cause() == SurvivalReflexCause.LAVA) {
			if (combatEating != null && combatEating.eating()) combatEating.cancel(minecraft);
			releaseShield(minecraft);
			tickLava(minecraft, player, lavaDanger, threats, tick);
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
		if (tacticalWindow != null) tacticalWindow = new TacticalWindow(tick + 600, tick);
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
		bunker.reset();
		flight.reset(navigationFacade);
		extinguishPour = null;
		extinguishAttempts = 0;
		foodRetreatStartedTick = -1L;
		foodRetreatAbandoned = false;
		foodUnavailableReported = false;
		combatProgress = null;
		combatEpisode = null;
		combatParticipants.clear();
		tacticalWindow = null;
		progressThreats = null;
		combatRecovery = CombatRecovery.READY;
		wetFighting = false;
		escapeExhaustedTick = -1L;
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
		bunker.allowRetry();
		flight.reset(navigationFacade);
		noContactHold = false;
		foodRetreatStartedTick = -1L;
		foodRetreatAbandoned = false;
		foodUnavailableReported = false;
		combatEpisode = null;
		combatParticipants.clear();
		long nextEpoch = snapshot.safetyEpoch() + 1L;
		combatStalemate = null;
		resetSecurityProgress();
		InterruptedWork work = interruptedWork == null ? InterruptedWork.none() : interruptedWork;
		String holdId = work.hasInterruptedWork() ? ReadableIds.next("hold") : null;
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

	/**
	 * Gets the body out of lava: walks and jumps toward the nearest dry cell with solid ground, and ends once the body has
	 * stood outside the lava for a few ticks. Lava outranks drowning and mobs because it kills in seconds.
	 */
	private void tickLava(Minecraft minecraft, LocalPlayer player, boolean inLava, List<ResolvedThreat> threats, long tick) {
		if (snapshot.cause() != SurvivalReflexCause.LAVA) {
			lavaBlockedTargets.clear();
			lavaTarget = null;
			underwaterEscape.reset(minecraft);
			combatRecovery = CombatRecovery.READY;
			changeAction(SurvivalReflexCause.LAVA, SurvivalReflexAction.ESCAPE_LAVA, tick);
		}
		boolean clear = !inLava && player.onGround();
		int clearTicks = clear ? snapshot.breathableTicks() + 1 : 0;
		if (clear && extinguish(minecraft, player, tick)) {
			refreshSnapshot(player, threats, snapshot.lastDangerTick(), Math.min(clearTicks, LAVA_CLEAR_TICKS - 1), null);
			return;
		}
		if (clearTicks >= LAVA_CLEAR_TICKS) {
			if (!mobThreatsResolved(threats.size(), tick, lastMobDamageTick, config.threatCooldownTicks())) {
				changeAction(SurvivalReflexCause.MOB_ATTACK, chooseMobAction(player, threats), tick);
				refreshSnapshot(player, threats, snapshot.lastDangerTick(), 0, null);
				return;
			}
			resolve(minecraft, player, threats, tick, "out_of_lava", false);
			return;
		}
		try {
			if (clear) {
				movementController.stop(minecraft);
			}
			else {
				BlockPos target = lavaEscapeTarget(player.blockPosition(), player.position(), LAVA_ESCAPE_RADIUS,
					cell -> !lavaBlockedTargets.contains(cell) && lavaSafeStand(minecraft.level, cell),
					cell -> !minecraft.level.getBlockState(cell).getCollisionShape(minecraft.level, cell).isEmpty());
				target = trackLavaTarget(target, player.position(), tick);
				if (target == null) {
					movementController.hold(minecraft, true, false);
				}
				else {
					cameraController.lookAt(minecraft, new Vec3(target.getX() + 0.5D, player.getEyeY(), target.getZ() + 0.5D));
					movementController.moveForward(minecraft, false, true, tick);
				}
			}
			refreshSnapshot(player, threats, inLava ? tick : snapshot.lastDangerTick(), clearTicks, null);
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("escape_lava", exception, tick);
			refreshSnapshot(player, threats, inLava ? tick : snapshot.lastDangerTick(), clearTicks, failureText(exception));
		}
	}

	/**
	 * Out of lava the body keeps burning for up to fifteen seconds, which killed it from eight health once. With a
	 * water bucket it pours water at its own feet, which puts the fire out, and scoops the water straight back.
	 * Returns whether this tick acted, so the reflex holds until the fire is out and the bucket is refilled.
	 */
	private boolean extinguish(Minecraft minecraft, LocalPlayer player, long tick) {
		ExtinguishStep step = extinguishStep(player.isOnFire(), player.isInWater(), extinguishPour != null,
			extinguishAttempts, hasItem(player, Items.WATER_BUCKET), hasItem(player, Items.BUCKET));
		if (step == ExtinguishStep.DONE) {
			if (extinguishPour != null) pendingEvents.add(new SurvivalReflexEvent("reflex.extinguished", mapOfNullable(
				"burning", player.isOnFire(), "bucketRefilled", hasItem(player, Items.WATER_BUCKET), "tick", tick)));
			extinguishPour = null;
			extinguishAttempts = 0;
			return false;
		}
		extinguishAttempts++;
		try {
			movementController.stop(minecraft);
			BlockPos feet = player.blockPosition();
			if (step == ExtinguishStep.POUR) {
				if (!selectInventoryItem(minecraft, player, Items.WATER_BUCKET)) return false;
				cameraController.lookAt(minecraft, new Vec3(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D));
				if (actuator.useItem(minecraft, player, InteractionHand.MAIN_HAND).consumesAction()) {
					extinguishPour = feet.immutable();
					extinguishAttempts = 0;
				}
			}
			else {
				if (!selectInventoryItem(minecraft, player, Items.BUCKET)) return false;
				cameraController.lookAt(minecraft, new Vec3(extinguishPour.getX() + 0.5D, extinguishPour.getY() + 0.5D,
					extinguishPour.getZ() + 0.5D));
				actuator.useItem(minecraft, player, InteractionHand.MAIN_HAND);
			}
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("extinguish", exception, tick);
		}
		return true;
	}

	enum ExtinguishStep { POUR, SCOOP, DONE }

	/** Pour while burning and dry; scoop the poured water back once the fire is out; give up after a few tries. */
	static ExtinguishStep extinguishStep(boolean burning, boolean inWater, boolean poured, int attempts,
		boolean hasWaterBucket, boolean hasEmptyBucket) {
		if (attempts >= EXTINGUISH_MAX_ATTEMPTS) return ExtinguishStep.DONE;
		if (!poured) return burning && !inWater && hasWaterBucket ? ExtinguishStep.POUR : ExtinguishStep.DONE;
		if (burning && !inWater) return hasWaterBucket ? ExtinguishStep.POUR : ExtinguishStep.DONE;
		return hasEmptyBucket && !hasWaterBucket ? ExtinguishStep.SCOOP : ExtinguishStep.DONE;
	}

	private static boolean hasItem(LocalPlayer player, net.minecraft.world.item.Item item) {
		for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++) {
			if (player.getInventory().getItem(slot).is(item)) return true;
		}
		return false;
	}

	/** Puts the item in the selected hotbar slot, matching backing inventory indices in case a container menu is open. */
	private boolean selectInventoryItem(Minecraft minecraft, LocalPlayer player, net.minecraft.world.item.Item item) {
		if (player.getInventory().getSelectedItem().is(item)) return true;
		for (int slot = 0; slot < 9; slot++) {
			if (player.getInventory().getItem(slot).is(item)) return actuator.selectHotbar(minecraft, slot);
		}
		if (minecraft.gameMode == null) return false;
		for (int slot = 9; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++) {
			if (!player.getInventory().getItem(slot).is(item)) continue;
			for (var menuSlot : player.containerMenu.slots) {
				if (menuSlot.container == player.getInventory() && menuSlot.getContainerSlot() == slot) {
					minecraft.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, menuSlot.index,
						player.getInventory().getSelectedSlot(), net.minecraft.world.inventory.ClickType.SWAP, player);
					return player.getInventory().getSelectedItem().is(item);
				}
			}
		}
		return false;
	}

	/** Gives up on a bank the body has not closed in on for a while, so the next search picks another. */
	private BlockPos trackLavaTarget(BlockPos target, Vec3 position, long tick) {
		if (target == null) {
			lavaTarget = null;
			return null;
		}
		double distance = Math.hypot(target.getX() + 0.5D - position.x, target.getZ() + 0.5D - position.z);
		if (!target.equals(lavaTarget) || distance < lavaTargetBestDistance - 0.3D) {
			if (!target.equals(lavaTarget)) lavaTarget = target.immutable();
			lavaTargetBestDistance = distance;
			lavaTargetProgressTick = tick;
		}
		else if (tick - lavaTargetProgressTick >= LAVA_STALL_TICKS) {
			lavaBlockedTargets.add(lavaTarget);
			lavaTarget = null;
		}
		return target;
	}

	/**
	 * The closest cell to stand on outside the lava, searched from one below the feet to two above, since a body in a
	 * pool sinks below the bank. Nearer cells win; climbing costs a little more than walking level. A cell counts only if
	 * the straight way there is open at the height the body arrives at, so a bank behind a wall loses to an open one.
	 */
	static BlockPos lavaEscapeTarget(BlockPos feet, Vec3 position, int radius, Predicate<BlockPos> safeStand, Predicate<BlockPos> solid) {
		BlockPos best = null;
		double bestCost = Double.MAX_VALUE;
		for (int dy = -1; dy <= 2; dy++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					BlockPos cell = feet.offset(dx, dy, dz);
					double horizontal = Math.hypot(cell.getX() + 0.5D - position.x, cell.getZ() + 0.5D - position.z);
					double cost = horizontal + 0.5D * Math.max(0, cell.getY() - feet.getY());
					if (cost >= bestCost || !safeStand.test(cell) || !openWay(position, cell, solid)) continue;
					bestCost = cost;
					best = cell;
				}
			}
		}
		return best;
	}

	/** Whether the body's two cells at the bank's height are free along the straight line from here to the bank. */
	static boolean openWay(Vec3 position, BlockPos cell, Predicate<BlockPos> solid) {
		double dx = cell.getX() + 0.5D - position.x, dz = cell.getZ() + 0.5D - position.z;
		int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / 0.25D));
		for (int i = 1; i <= steps; i++) {
			double t = (double) i / steps;
			BlockPos column = BlockPos.containing(position.x + dx * t, cell.getY(), position.z + dz * t);
			if (solid.test(column) || solid.test(column.above())) return false;
		}
		return true;
	}

	private static boolean lavaSafeStand(Level level, BlockPos cell) {
		BlockPos floorPos = cell.below();
		BlockState floor = level.getBlockState(floorPos);
		if (!floor.isFaceSturdy(level, floorPos, Direction.UP) || floor.is(Blocks.MAGMA_BLOCK)
			|| !floor.getFluidState().isEmpty()) {
			return false;
		}
		for (BlockPos body : List.of(cell, cell.above())) {
			BlockState state = level.getBlockState(body);
			if (!state.getCollisionShape(level, body).isEmpty() || state.getFluidState().is(FluidTags.LAVA)
				|| state.is(BlockTags.FIRE)) {
				return false;
			}
		}
		return true;
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
		if (next == CombatRecovery.READY) {
			wetFighting = false;
			escapeExhaustedTick = -1L;
			return false;
		}
		releaseShield(minecraft);
		ResolvedThreat wetThreat = wetMeleeThreat(player, threats);
		if (wetThreat != null) {
			fightWhileWet(minecraft, player, threats, wetThreat, tick);
			return true;
		}
		if (wetFighting) {
			// Knockback during the fight fails escape targets that are fine to retry once it is over.
			wetFighting = false;
			underwaterEscape.reset(minecraft);
		}
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
				if (escapeExhaustedTick < 0) escapeExhaustedTick = tick;
				else if (tick - escapeExhaustedTick >= ESCAPE_RETRY_TICKS) {
					// Floating in place is never the end state: the shore may be reachable from here.
					escapeExhaustedTick = -1L;
					underwaterEscape.reset(minecraft);
				}
			}
			else escapeExhaustedTick = -1L;
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("recover_combat_movement", exception, tick);
			movementController.swimUp(minecraft, false, false, tick);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, failureText(exception));
		}
		return true;
	}

	/** A melee threat close enough to hit back from the water, unless air is too low to stay and fight. */
	private ResolvedThreat wetMeleeThreat(LocalPlayer player, List<ResolvedThreat> threats) {
		boolean lowAir = player.isUnderWater() && player.getAirSupply() < config.lowAirTicks();
		// The combat path that tracks fuses doesn't run during recovery; read them live here.
		updateCreeperEscape(threats);
		if (lowAir || escapingCreeper != null) return null;
		ResolvedThreat threat = threats.isEmpty() ? null : focusedThreat(threats);
		return threat != null && shouldFightWhileWet(threat.distance(), threat.lineOfSight(), isRangedThreat(threat.entity()))
			? threat : null;
	}

	static boolean shouldFightWhileWet(double distance, boolean lineOfSight, boolean ranged) {
		return !ranged && lineOfSight && distance <= WET_FIGHT_DISTANCE;
	}

	/**
	 * Swimming away from a melee mob only trades hits for distance it closes anyway; spiders and
	 * drowned follow into water. Face it, keep afloat, close to reach and strike when ready.
	 */
	private void fightWhileWet(Minecraft minecraft, LocalPlayer player, List<ResolvedThreat> threats,
		ResolvedThreat threat, long tick) {
		if (!wetFighting) {
			wetFighting = true;
			underwaterEscape.reset(minecraft);
			pendingEvents.add(new SurvivalReflexEvent("reflex.wet_fight", mapOfNullable(
				"threatUuid", threat.observed().uuid(), "entityTypeId", threat.observed().entityTypeId(),
				"distance", threat.distance(), "air", player.getAirSupply(), "tick", tick)));
		}
		if (snapshot.action() != SurvivalReflexAction.DEFEND) changeAction(SurvivalReflexCause.MOB_ATTACK, SurvivalReflexAction.DEFEND, tick);
		try {
			equipBestCombatItem(minecraft, player);
			cameraController.lookAt(minecraft, threat.entity().getBoundingBox().getCenter());
			movementController.swimUp(minecraft, threat.distance() > MELEE_ATTACK_DISTANCE - 0.5D, false, tick);
			attemptCloseQuarterAttack(minecraft, player, threats, tick);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("fight_while_wet", exception, tick);
			movementController.swimUp(minecraft, false, false, tick);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, failureText(exception));
		}
	}

	private void tickMobAttack(Minecraft minecraft, LocalPlayer player, List<ResolvedThreat> threats,
		long tick, CombatEating combatEating) {
		if (tickBunker(minecraft, player, threats, tick, combatEating)) {
			// Digging in outranks running; a failed bunker lets the flight start again.
			flight.reset(navigationFacade);
			return;
		}
		if (tickFlight(minecraft, player, threats, tick)) return;
		if (combatProgress != null && combatProgress.stalled()) {
			tacticalWindow = new TacticalWindow(tick + 1200, tick);
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
		if (tickCreeperRun(minecraft, player, threats, tick)) {
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
			return;
		}
		if (tickCombatEating(minecraft, player, threats, tick, combatEating, usePositioning)) return;
		if (threats.stream().noneMatch(threat ->
			policy().acceptsMob(isRangedThreat(threat.entity()), threat.distance(), threat.lineOfSight())
				|| combatPositioning != null && threat.distance() <= Math.min(10, policy().maxThreatDistance()))) {
			resolve(minecraft, player, threats, tick, "no_eligible_threats", false);
			return;
		}
		ResolvedThreat shot = archeryTarget(player, threats);
		if (shot == null) {
			archery.cancel(minecraft, actuator);
			equipBestCombatItem(minecraft, player);
		}
		// A started draw is finished rather than dropped for the shield: the next arrow is the way out.
		if (!archery.drawing() && blockShieldThreat(minecraft, player, threats, tick)) {
			if (usePositioning) reposition(minecraft, threats, tick, true);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
			return;
		}
		releaseShield(minecraft);
		if (shot != null) {
			stopCombatNavigation();
			if (archery.tick(minecraft, player, shot.entity(), tick, actuator, cameraController, movementController,
				item -> selectInventoryItem(minecraft, player, item), pendingEvents)) {
				refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
				return;
			}
			equipBestCombatItem(minecraft, player);
		}
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

	/**
	 * Runs instead of fighting when the body has no real melee weapon and the threats are not trivially beatable (see
	 * {@link UnarmedFlight}). A bow shot at a ranged mob still comes first. Returns whether the flight handled this tick;
	 * false means fight as before, including after a flight is given up.
	 */
	private boolean tickFlight(Minecraft minecraft, LocalPlayer player, List<ResolvedThreat> threats, long tick) {
		if (navigationFacade == null || !navigationFacade.isLoaded()) {
			if (flight.active()) flight.abandon("navigation_unavailable", navigationFacade);
			return false;
		}
		ItemStack weapon = combatWeapon(player);
		double damage = UnarmedFlight.attackDamage(weapon);
		List<UnarmedFlight.Threat> view = threats.stream().map(threat -> new UnarmedFlight.Threat(
			threat.entity().getX(), threat.entity().getZ(), threat.entity().getHealth(), isRangedThreat(threat.entity()),
			threat.entity() instanceof net.minecraft.world.entity.monster.Creeper, threat.distance(), threat.lineOfSight())).toList();
		if (!flight.active()) {
			if (flight.abandoned() || archeryTarget(player, threats) != null) return false;
			String reason = UnarmedFlight.fleeReason(damage, player.getHealth(), player.getMaxHealth(), view);
			if (reason == null) return false;
			flight.start(tick, lastMobDamageTick);
			archery.cancel(minecraft, actuator);
			releaseShield(minecraft);
			stopCombatNavigation();
			combatPositioning = null;
			movementController.stop(minecraft);
			changeAction(SurvivalReflexCause.MOB_ATTACK, SurvivalReflexAction.FLEE, tick);
			pendingEvents.add(new SurvivalReflexEvent("reflex.fled_unarmed", mapOfNullable(
				"reason", reason,
				"weaponItemId", BuiltInRegistries.ITEM.getKey(weapon.getItem()).toString(),
				"weaponDamage", damage,
				"health", player.getHealth(),
				"threatCount", threats.size(),
				"entityTypeIds", threats.stream().map(threat -> threat.observed().entityTypeId()).distinct().toList(),
				"nearestDistance", threats.stream().mapToDouble(ResolvedThreat::distance).min().orElse(-1D),
				"tick", tick)));
		}
		else if (damage >= UnarmedFlight.MIN_WEAPON_DAMAGE) flight.abandon("armed", navigationFacade);
		UnarmedFlight.Outcome outcome = flight.active()
			? flight.tick(minecraft, player, view, tick, lastMobDamageTick, config.threatCooldownTicks(), navigationFacade)
			: UnarmedFlight.Outcome.ABANDONED;
		// Running is neither progress against a target nor a stalled approach.
		combatProgress = null;
		progressThreats = null;
		combatStalemate = null;
		switch (outcome) {
			case FLEEING -> {
				refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
				return true;
			}
			case ESCAPED -> {
				resolve(minecraft, player, threats, tick, "escaped_unarmed", false);
				return true;
			}
			default -> {
				var payload = new LinkedHashMap<String, Object>(flight.summary(tick));
				payload.put("reason", flight.abandonReason());
				payload.put("health", player.getHealth());
				payload.put("threatCount", threats.size());
				payload.put("tick", tick);
				pendingEvents.add(new SurvivalReflexEvent("reflex.flight_abandoned", payload));
				return false;
			}
		}
	}

	/** What the reflex fights with: the weapon it would equip, or whatever is in hand when it carries none. */
	private static ItemStack combatWeapon(LocalPlayer player) {
		int slot = bestCombatInventorySlot(inventoryItemIds(player));
		return slot >= 0 ? player.getInventory().getItem(slot) : player.getMainHandItem();
	}

	private static List<String> inventoryItemIds(LocalPlayer player) {
		List<String> itemIds = new ArrayList<>();
		for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++)
			itemIds.add(BuiltInRegistries.ITEM.getKey(player.getInventory().getItem(slot).getItem()).toString());
		return itemIds;
	}

	/**
	 * The nearest ranged mob worth a bow shot, or null while a melee mob is close enough that the sword comes first.
	 */
	private static ResolvedThreat archeryTarget(LocalPlayer player, List<ResolvedThreat> threats) {
		boolean hasBow = ReflexArchery.hasBow(player), hasArrows = ReflexArchery.hasArrows(player);
		if (!hasBow || !hasArrows) return null;
		ResolvedThreat best = null;
		for (ResolvedThreat threat : threats) {
			boolean ranged = isRangedThreat(threat.entity());
			if (!ranged && threat.distance() <= MELEE_THREAT_DISTANCE) return null;
			if (!ReflexArchery.worthShooting(ranged, threat.lineOfSight(), threat.distance(), true, true)) continue;
			if (best == null || threat.distance() < best.distance()) best = threat;
		}
		return best;
	}

	/**
	 * At low health, or unarmed with no escape route, the fight turns into digging in (see {@link EmergencyBunker}); inside a closed bunker the body
	 * eats and waits for the threats to leave. Returns whether the bunker handled this tick.
	 */
	private boolean tickBunker(Minecraft minecraft, LocalPlayer player, List<ResolvedThreat> threats, long tick,
		CombatEating combatEating) {
		// An unarmed body that could not outrun its attackers digs in at once rather than fight bare-handed.
		boolean cornered = flight.abandoned() && !"armed".equals(flight.abandonReason());
		if (!bunker.active() && (threats.isEmpty() || player.getHealth() > EmergencyBunker.HEALTH_THRESHOLD && !cornered)) return false;
		try {
			if (!bunker.closed(minecraft.level, player)) {
				releaseShield(minecraft);
				stopCombatNavigation();
			}
			if (!bunker.tick(minecraft, player, tick, actuator, cameraController, movementController,
				item -> selectInventoryItem(minecraft, player, item), pendingEvents)) return false;
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("bunker", exception, tick);
			return false;
		}
		if (bunker.closed(minecraft.level, player)) tickCombatEating(minecraft, player, threats, tick, combatEating, true);
		refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
		return true;
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
		boolean sheltered = !immediate && (bunker.closed(minecraft.level, player)
			|| assessMobSecurity(player, threats, tick) == SecurityKind.SEALED);
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

	/**
	 * Runs straight away from a lit creeper once stepping about has stopped gaining distance. A playtest body at
	 * twelve health on a hillside flipped between three nearby cells for sixty ticks with a creeper at its side,
	 * never climbing the slope, and was blown up. A creeper's fuse stops beyond seven blocks and its blast barely
	 * hurts at six, so a sprint along the ground, routed by the navigator, is the way out.
	 */
	private boolean tickCreeperRun(Minecraft minecraft, LocalPlayer player, List<ResolvedThreat> threats, long tick) {
		ResolvedThreat creeper = escapingCreeper == null ? null : threats.stream()
			.filter(t -> t.observed().uuid().equals(escapingCreeper)).findFirst().orElse(null);
		if (creeper == null || navigationFacade == null || !navigationFacade.isLoaded()) {
			endCreeperRun();
			return false;
		}
		if (creeperRunTarget != null) {
			if (navigationFacade.processActive() && tick - creeperRunTick < CREEPER_RUN_TICKS) return true;
			creeperRunTarget = null;
			creeperGainTick = tick;
			creeperBestDistance = creeper.distance();
		}
		if (creeperBestDistance < 0 || creeper.distance() > creeperBestDistance + 0.3D) {
			creeperBestDistance = Math.max(creeperBestDistance, creeper.distance());
			creeperGainTick = tick;
			return false;
		}
		if (tick - creeperGainTick < CREEPER_STALL_TICKS) return false;
		var feet = player.blockPosition();
		if (creeperClassifier == null) creeperClassifier = ai.moeru.airicraft.agent.navigation.MinecraftCellClassifier.forPlayer(null);
		var entity = creeper.entity();
		UnarmedFlight.Leg leg = UnarmedFlight.fleeTarget(
			new ai.moeru.airicraft.agent.navigation.LiveWorldTerrain(minecraft.level, creeperClassifier),
			new ai.moeru.airicraft.navigation.GridPos(feet.getX(), feet.getY(), feet.getZ()),
			List.of(new UnarmedFlight.Threat(entity.getX(), entity.getZ(), entity.getHealth(), false, true, creeper.distance(), creeper.lineOfSight())),
			CREEPER_RUN_BLOCKS);
		creeperGainTick = tick;
		if (leg == null) return false;
		stopCombatNavigation();
		combatPositioning = null;
		movementController.stop(minecraft);
		navigationFacade.startNavigateNear(new GoalPosition(leg.stand().x(), leg.stand().y(), leg.stand().z(), false), 1, UnarmedFlight.OPTIONS);
		creeperRunTarget = leg.stand();
		creeperRunTick = tick;
		pendingEvents.add(new SurvivalReflexEvent("reflex.creeper_run", Map.of(
			"tick", tick, "distance", creeper.distance(), "health", player.getHealth(),
			"target", new GoalPosition(leg.stand().x(), leg.stand().y(), leg.stand().z(), true))));
		return true;
	}

	private void endCreeperRun() {
		if (creeperRunTarget != null && navigationFacade != null) navigationFacade.cancel();
		creeperRunTarget = null;
		creeperBestDistance = -1;
	}

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
		boolean noContact = "no_eligible_threats".equals(reason) && snapshot.cause() == SurvivalReflexCause.MOB_ATTACK
			&& lastMobDamageTick == Long.MIN_VALUE && player.getHealth() >= player.getMaxHealth() * 0.5F;
		foodRetreatStartedTick = -1L;
		foodRetreatAbandoned = false;
		foodUnavailableReported = false;
		wetFighting = false;
		archery.cancel(minecraft, actuator);
		flight.reset(navigationFacade);
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
		noContactHold = noContact && nextState == SurvivalReflexState.AWAITING_PLANNER && !keepSafetyHold;
	}

	/**
	 * Whether to hand the held work straight back. A mob that came within reach and then stayed out of it, without
	 * ever hurting the body, held a playtest's walk, and even its attack on that same spider, for planner turn after
	 * planner turn. Such a hold resumes by itself, at most {@value #NO_CONTACT_RESUME_LIMIT} times per
	 * {@value #NO_CONTACT_RESUME_WINDOW} ticks; past that the planner decides as before.
	 */
	public boolean takeNoContactResume(long tick) {
		if (!noContactHold || snapshot.state() != SurvivalReflexState.AWAITING_PLANNER) return false;
		noContactHold = false;
		return admitNoContactResume(noContactResumes, tick);
	}

	/** Records a resume at {@code tick} unless the window already holds the limit. */
	static boolean admitNoContactResume(java.util.ArrayDeque<Long> history, long tick) {
		while (!history.isEmpty() && tick - history.peekFirst() > NO_CONTACT_RESUME_WINDOW) history.removeFirst();
		if (history.size() >= NO_CONTACT_RESUME_LIMIT) return false;
		history.addLast(tick);
		return true;
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
			? ReadableIds.next("hold")
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
		int bestSlot = bestCombatInventorySlot(inventoryItemIds(player));
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

	public static int combatItemRank(String itemId) {
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
		endCreeperRun();
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
			if (!reflexThreat(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString())
				|| !shouldDetectProactiveThreat(targetsUs || visibleHostile, mob.isAlive())
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

	/**
	 * The Ender Dragon is not a reflex threat: melee pursuit cannot reach a flying boss and would hold the
	 * fight_ender_dragon task, which dodges its breath and hits its head when it perches.
	 */
	static boolean reflexThreatType(String entityTypeId) {
		return !"minecraft:ender_dragon".equals(entityTypeId);
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
