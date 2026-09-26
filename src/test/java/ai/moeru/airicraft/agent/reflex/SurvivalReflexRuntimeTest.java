package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.tasks.UnderwaterEscapeSearch;
import ai.moeru.airicraft.agent.tasks.UnderwaterEscapeNavigator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalReflexRuntimeTest {
	@Test void combatMealNeedsShelterAndAFullUseWindow() {
		assertTrue(SurvivalReflexRuntime.safeToEatDuringCombat(true, false, false, true, false, 4D));
		assertTrue(SurvivalReflexRuntime.safeToEatDuringCombat(true, false, false, false, false, 12D));
		assertFalse(SurvivalReflexRuntime.safeToEatDuringCombat(true, false, false, false, true, 12D));
		assertFalse(SurvivalReflexRuntime.safeToEatDuringCombat(true, false, true, true, false, 12D));
		assertFalse(SurvivalReflexRuntime.safeToEatDuringCombat(false, false, false, true, false, 12D));
	}

	@Test void resolvedCombatCanHaveNoStallDuration() {
		assertNull(SurvivalReflexRuntime.noProgressTicks(null, null, 7609));
		var progressing = new CombatProgress(7500, java.util.Map.of(), false);
		var approaching = new CombatStalemate(CombatStalemate.Phase.APPROACHING, 7400,
			net.minecraft.util.math.Vec3d.ZERO, java.util.Map.of());
		assertNull(SurvivalReflexRuntime.noProgressTicks(progressing, approaching, 7609));
	}

	@Test void resolvedStalemateReportsItsDurationWithCombatProgressTakingPrecedence() {
		var stalled = new CombatProgress(7400, java.util.Map.of(), true);
		var deferred = new CombatStalemate(CombatStalemate.Phase.DEFERRED, 7000,
			net.minecraft.util.math.Vec3d.ZERO, java.util.Map.of());
		assertEquals(Long.valueOf(209), SurvivalReflexRuntime.noProgressTicks(stalled, deferred, 7609));
		assertEquals(Long.valueOf(609), SurvivalReflexRuntime.noProgressTicks(null, deferred, 7609));
	}

	@Test void awarenessIncludesFlankersOutsideTheEngagementGate() {
		var policy = ReflexPolicy.defaults();
		assertTrue(policy.observesMob(12));
		assertFalse(policy.acceptsMob(false, 12, true));
		assertTrue(policy.observesMob(4));
		assertFalse(policy.acceptsMob(false, 4, false));
		assertFalse(policy.observesMob(17));
		assertFalse(new ReflexPolicy(false, true, 16, true).observesMob(3));
		assertFalse(SurvivalReflexRuntime.shouldReposition(0));
		assertTrue(SurvivalReflexRuntime.shouldReposition(1));
		assertTrue(SurvivalReflexRuntime.shouldReposition(2));
	}

	@Test void resumingTacticalWorkRetainsDeferredThreatIdentityUntilLifecycleReset() throws Exception {
		var runtime = new SurvivalReflexRuntime(null);
		runtime.observeDamage(new SurvivalReflexRuntime.DamageObservation(1, "arrow", "pillager", "Pillager", "minecraft:pillager", true, false));
		var snapshotField = SurvivalReflexRuntime.class.getDeclaredField("snapshot");
		snapshotField.setAccessible(true);
		snapshotField.set(runtime, new SurvivalReflexSnapshot(SurvivalReflexState.AWAITING_PLANNER,
			SurvivalReflexCause.MOB_ATTACK, SurvivalReflexAction.DEFEND, 3, "hold", "job", null,
			List.of(), 20F, 20F, 300, 300, 0, 400, 0, null));
		var deferred = new CombatStalemate(CombatStalemate.Phase.DEFERRED, 0, net.minecraft.util.math.Vec3d.ZERO,
			java.util.Map.of("pillager", new net.minecraft.util.math.Vec3d(13, 0, 0)));
		var stateField = SurvivalReflexRuntime.class.getDeclaredField("combatStalemate");
		stateField.setAccessible(true);
		stateField.set(runtime, deferred);
		var threatsField = SurvivalReflexRuntime.class.getDeclaredField("observedThreats");
		threatsField.setAccessible(true);

		assertEquals(SurvivalReflexRuntime.ResumeResult.RESUMED, runtime.resume("hold", 401));
		assertEquals(deferred, runtime.decisionEvidence().get("combatStalemate"));
		assertTrue(((java.util.Map<?, ?>) threatsField.get(runtime)).containsKey("pillager"),
			"Do not lose deferral during the next asynchronous aggro-query gap");
		runtime.reset(null);
		assertNull(runtime.decisionEvidence().get("combatStalemate"));
		assertTrue(((java.util.Map<?, ?>) threatsField.get(runtime)).isEmpty());
	}

	@Test void blocksAnApproachingCreeperBlastBeforeShieldStartupDelay() {
		assertFalse(SurvivalReflexRuntime.shouldBlockCreeper(3.03, 1, 0.35F, false));
		assertTrue(SurvivalReflexRuntime.shouldBlockCreeper(3.03, 1, 0.7F, false));
		assertTrue(SurvivalReflexRuntime.shouldBlockCreeper(8, 1, 0.8F, true));
		assertFalse(SurvivalReflexRuntime.shouldBlockCreeper(8, 1, 0.8F, false));
		assertFalse(SurvivalReflexRuntime.shouldBlockCreeper(3, -1, 0.35F, false));
		assertFalse(SurvivalReflexRuntime.shouldBlockCreeper(3, 1, 0.1F, false));
	}

	@Test void combatRoutingPreservesConfiguredPathConstraintsOnStartAndReplan() {
		var settingsResets = new java.util.concurrent.atomic.AtomicInteger();
		var routes = new java.util.ArrayList<ai.moeru.airicraft.agent.goals.GoalPosition>();
		var facade = (ai.moeru.airicraft.agent.baritone.BaritoneFacade) java.lang.reflect.Proxy.newProxyInstance(
			getClass().getClassLoader(), new Class<?>[]{ai.moeru.airicraft.agent.baritone.BaritoneFacade.class},
			(proxy, method, args) -> {
				switch (method.getName()) {
					case "applySettings" -> { settingsResets.incrementAndGet(); return null; }
					case "startNavigateNear" -> { routes.add((ai.moeru.airicraft.agent.goals.GoalPosition) args[0]); return null; }
					case "processActive" -> { return true; }
					default -> throw new AssertionError("Unexpected call: " + method.getName());
				}
			});
		var runtime = new SurvivalReflexRuntime(null,
			new ai.moeru.airicraft.agent.control.MovementController(),
			new ai.moeru.airicraft.agent.control.CameraController(), facade);
		var first = new ai.moeru.airicraft.agent.goals.GoalPosition(10, 64, 10, true);
		var second = new ai.moeru.airicraft.agent.goals.GoalPosition(12, 64, 10, true);
		runtime.updateCombatNavigation(first, 100);
		runtime.updateCombatNavigation(first, 110);
		runtime.updateCombatNavigation(second, 120);
		assertEquals(List.of(first, second), routes);
		assertEquals(0, settingsResets.get(), "Combat must not replace runtime constraints with startup defaults");
	}

	@Test void shieldFacesTheShooterAtEyeLevelInsteadOfTrackingArrowPosition() {
		var eye = new net.minecraft.util.math.Vec3d(146, 33, 401);
		var shooter = new net.minecraft.util.math.Vec3d(141, 41, 397);
		var heading = new net.minecraft.util.math.Vec3d(141, 33, 397);
		assertEquals(heading, SurvivalReflexRuntime.shieldFacingPoint(eye, shooter, new net.minecraft.util.math.Vec3d(1, -2, 1)));
		assertEquals(heading, SurvivalReflexRuntime.shieldFacingPoint(eye, shooter, new net.minecraft.util.math.Vec3d(2, -3, 2)));
		assertEquals(new net.minecraft.util.math.Vec3d(138, 33, 401),
			SurvivalReflexRuntime.shieldFacingPoint(eye, null, new net.minecraft.util.math.Vec3d(2, -1, 0)));
		assertNull(SurvivalReflexRuntime.shieldFacingPoint(eye, null, new net.minecraft.util.math.Vec3d(0, -1, 0)));
	}

	@Test void retainsRaisedShieldAcrossTheRecordedBowReleaseGap() {
		var shooter = new net.minecraft.util.math.Vec3d(141, 38, 397);
		var guard = SurvivalReflexRuntime.nextShieldGuard(null, shooter, "skeleton", 14962);
		guard = SurvivalReflexRuntime.nextShieldGuard(guard, null, null, 14963);
		assertNotNull(guard, "Releasing here restarts shield startup before the incoming arrow");
		assertEquals(shooter, guard.facing());
		guard = SurvivalReflexRuntime.nextShieldGuard(guard, shooter, "skeleton", 14966);
		assertNotNull(SurvivalReflexRuntime.nextShieldGuard(guard, null, null, 14972));
		assertNull(SurvivalReflexRuntime.nextShieldGuard(guard, null, null, 14973), "Eventually release to advance and attack");
	}

	@Test void incomingArrowsExcludeRecedingStoppedAndPassingProjectiles() {
		var ahead = new net.minecraft.util.math.Vec3d(0, 0, 12);
		assertEquals(4.0, SurvivalReflexRuntime.incomingProjectileTicks(ahead, new net.minecraft.util.math.Vec3d(0, 0, 3)));
		assertEquals(Double.POSITIVE_INFINITY, SurvivalReflexRuntime.incomingProjectileTicks(ahead, new net.minecraft.util.math.Vec3d(0, 0, -3)));
		assertEquals(Double.POSITIVE_INFINITY, SurvivalReflexRuntime.incomingProjectileTicks(ahead, net.minecraft.util.math.Vec3d.ZERO));
		assertEquals(Double.POSITIVE_INFINITY, SurvivalReflexRuntime.incomingProjectileTicks(new net.minecraft.util.math.Vec3d(3, 0, 12), new net.minecraft.util.math.Vec3d(0, 0, 3)));
		assertEquals(Double.POSITIVE_INFINITY, SurvivalReflexRuntime.incomingProjectileTicks(ahead, new net.minecraft.util.math.Vec3d(0, 0, 1)));
	}

	@Test void combatCanRetrieveSwordFromMainInventory() {
		var inventory = new java.util.ArrayList<>(java.util.Collections.nCopies(36, "minecraft:air"));
		inventory.set(6, "minecraft:iron_pickaxe");
		inventory.set(27, "minecraft:iron_sword");
		assertEquals(27, SurvivalReflexRuntime.bestCombatInventorySlot(inventory));
		inventory.set(2, "minecraft:iron_sword");
		assertEquals(2, SurvivalReflexRuntime.bestCombatInventorySlot(inventory));
		assertEquals(-1, SurvivalReflexRuntime.bestCombatInventorySlot(java.util.List.of("minecraft:dirt")));
	}

	@Test
	void drowningStartsAtLowAirThresholdOrFromDrowningDamage() {
		assertTrue(SurvivalReflexRuntime.shouldStartDrowning(true, 100, 100, false));
		assertFalse(SurvivalReflexRuntime.shouldStartDrowning(true, 101, 100, false));
		assertFalse(SurvivalReflexRuntime.shouldStartDrowning(false, 0, 100, false));
		assertTrue(SurvivalReflexRuntime.shouldStartDrowning(false, 300, 100, true));
	}

	@Test
	void drowningRequiresAirMarginAndTwelveStableTicksToResolve() {
		assertFalse(SurvivalReflexRuntime.airRecoveryMarginReached(false, 279, 300));
		assertTrue(SurvivalReflexRuntime.airRecoveryMarginReached(false, 280, 300));
		assertTrue(SurvivalReflexRuntime.airRecoveryMarginReached(false, 300, 300));
		assertFalse(SurvivalReflexRuntime.airRecoveryMarginReached(true, 300, 300));
		assertFalse(SurvivalReflexRuntime.drowningResolved(11));
		assertTrue(SurvivalReflexRuntime.drowningResolved(12));
	}

	@Test
	void stableBreathingEndsActiveDrowningBeforeSafeLandWork() {
		assertEquals(SurvivalReflexAction.REACH_SAFE_LAND, SurvivalReflexRuntime.drowningAction(false));
		assertEquals(SurvivalReflexAction.SWIM_TO_AIR, SurvivalReflexRuntime.drowningAction(true));
		assertTrue(SurvivalReflexRuntime.stableDrowningRecovery(true));
		assertFalse(SurvivalReflexRuntime.stableDrowningRecovery(false));
		assertTrue(SurvivalReflexRuntime.shouldKeepDrowningSafetyHold(false, false));
		assertFalse(SurvivalReflexRuntime.shouldKeepDrowningSafetyHold(false, true));
		assertTrue(SurvivalReflexRuntime.shouldKeepDrowningSafetyHold(true, true));
		assertEquals(UnderwaterEscapeSearch.SearchMode.BREATHABLE,
			SurvivalReflexRuntime.drowningSearchMode(false, true, 80, 300));
		assertEquals(UnderwaterEscapeSearch.SearchMode.BREATHABLE,
			SurvivalReflexRuntime.drowningSearchMode(true, false, 300, 300));
		assertEquals(UnderwaterEscapeSearch.SearchMode.SAFE_STANDING,
			SurvivalReflexRuntime.drowningSearchMode(false, false, 280, 300));
	}

	@Test
	void terminalSafeLandSearchFallsBackToAfloatHold() {
		assertFalse(SurvivalReflexRuntime.safeLandSearchExhausted(
			UnderwaterEscapeSearch.SearchStatus.SEARCHING,
			UnderwaterEscapeNavigator.Phase.EXHAUSTED
		));
		assertFalse(SurvivalReflexRuntime.safeLandSearchExhausted(
			UnderwaterEscapeSearch.SearchStatus.CELL_BUDGET_EXHAUSTED,
			UnderwaterEscapeNavigator.Phase.WAYPOINT_FALLBACK
		));
		assertTrue(SurvivalReflexRuntime.safeLandSearchExhausted(
			UnderwaterEscapeSearch.SearchStatus.CELL_BUDGET_EXHAUSTED,
			UnderwaterEscapeNavigator.Phase.EXHAUSTED
		));
		assertTrue(SurvivalReflexRuntime.safeLandSearchExhausted(
			UnderwaterEscapeSearch.SearchStatus.COMPLETE,
			UnderwaterEscapeNavigator.Phase.EXHAUSTED
		));
	}

	@Test
	void threatAdmissionRequiresAggroRatherThanProximity() {
		assertFalse(SurvivalReflexRuntime.shouldDetectProactiveThreat(false, true));
		assertTrue(SurvivalReflexRuntime.shouldDetectProactiveThreat(true, true));
		assertFalse(SurvivalReflexRuntime.shouldDetectProactiveThreat(true, false));
	}

	@Test
	void drownedNeedsAHeldTridentToBeARangedThreat() {
		assertFalse(SurvivalReflexRuntime.isRangedThreat("minecraft:drowned", "minecraft:air", true));
		assertFalse(SurvivalReflexRuntime.isRangedThreat("minecraft:drowned", "minecraft:fishing_rod", true));
		assertTrue(SurvivalReflexRuntime.isRangedThreat("minecraft:drowned", "minecraft:trident", true));
		assertTrue(SurvivalReflexRuntime.isRangedThreat("minecraft:skeleton", "minecraft:bow", true));
		assertTrue(SurvivalReflexRuntime.isRangedThreat("minecraft:guardian", "minecraft:air", false));
		assertFalse(ReflexPolicy.defaults().acceptsMob(
			SurvivalReflexRuntime.isRangedThreat("minecraft:drowned", "minecraft:air", true), 33.93D, true));
	}

	@Test
	void distantMeleeMobsDoNotOwnCombatButCloseAndRangedThreatsStillDo() {
		assertTrue(ReflexPolicy.defaults().acceptsMob(false, 3.0D, true));
		assertTrue(ReflexPolicy.defaults().acceptsMob(false, 6.0D, true));
		assertFalse(ReflexPolicy.defaults().acceptsMob(false, 6.01D, true));
		// The three tracked mobs in the ravine reproduction.
		assertFalse(ReflexPolicy.defaults().acceptsMob(false, 16.04D, true));
		assertFalse(ReflexPolicy.defaults().acceptsMob(false, 19.16D, true));
		assertFalse(ReflexPolicy.defaults().acceptsMob(false, 27.97D, true));
		assertTrue(ReflexPolicy.defaults().acceptsMob(true, 16.0D, true));
		assertFalse(ReflexPolicy.defaults().acceptsMob(true, 16.04D, true));
		assertFalse(ReflexPolicy.defaults().acceptsMob(true, 32.0D, true));
	}

	@Test void policyIgnoresRecordedHiddenPillagersAndCanBeOverridden() {
		var runtime = new SurvivalReflexRuntime(ai.moeru.airicraft.agent.AgentConfig.ReflexConfig.defaults());
		assertFalse(runtime.policy().acceptsMob(true, 35.81, false));
		assertFalse(runtime.policy().acceptsMob(true, 12.43, false));
		assertFalse(runtime.policy().acceptsMob(false, 3, false));
		var override = new ReflexPolicy(true, true, 32, false);
		runtime.configure(override);
		assertTrue(runtime.policy().acceptsMob(true, 12.43, false));
		assertFalse(runtime.policy().acceptsMob(true, 35.81, false));
		runtime.reset(null);
		assertEquals(override, runtime.policy(), "Episode/death reset must not silently discard System 2 policy");
		runtime.configure(new ReflexPolicy(false, true, 16, true));
		assertFalse(runtime.policy().acceptsMob(true, 2, true));
		assertTrue(runtime.policy().drowningEnabled());
	}

	@Test
	void threatResolutionHonorsDamageCooldown() {
		assertFalse(SurvivalReflexRuntime.mobThreatsResolved(1, 200, 100, 60));
		assertFalse(SurvivalReflexRuntime.mobThreatsResolved(0, 159, 100, 60));
		assertTrue(SurvivalReflexRuntime.mobThreatsResolved(0, 160, 100, 60));
	}

	@Test
	void closeQuarterAttackIgnoresMovementModeButRequiresRangeSightAndCooldown() {
		assertTrue(SurvivalReflexRuntime.shouldAttackCloseThreat(3.0D, true, 0.92F));
		assertFalse(SurvivalReflexRuntime.shouldAttackCloseThreat(3.01D, true, 1.0F));
		assertFalse(SurvivalReflexRuntime.shouldAttackCloseThreat(2.0D, false, 1.0F));
		assertFalse(SurvivalReflexRuntime.shouldAttackCloseThreat(2.0D, true, 0.91F));
	}

	@Test
	void ranksCombatHotbarItemsAheadOfIncidentalBlocks() {
		assertTrue(SurvivalReflexRuntime.combatItemRank("minecraft:stone_pickaxe")
			< SurvivalReflexRuntime.combatItemRank("minecraft:leaf_litter"));
		assertTrue(SurvivalReflexRuntime.combatItemRank("minecraft:iron_sword")
			< SurvivalReflexRuntime.combatItemRank("minecraft:stone_pickaxe"));
		assertEquals(Integer.MAX_VALUE, SurvivalReflexRuntime.combatItemRank("minecraft:dirt"));
	}

	@Test
	void onlyBlockedOccludedPursuitCountsAsShelter() {
		for (var route : SurvivalReflexRuntime.RouteStatus.values()) {
			for (boolean visible : new boolean[]{false, true}) {
				assertEquals(route == SurvivalReflexRuntime.RouteStatus.BLOCKED && !visible
					? SurvivalReflexRuntime.SecurityKind.SEALED : SurvivalReflexRuntime.SecurityKind.UNSAFE,
					SurvivalReflexRuntime.classifyThreatSecurity(route, visible));
			}
		}
	}

	@Test
	void newDangerPreemptsSafetyHoldButDoesNotRestartActiveReflex() {
		assertTrue(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.IDLE, true));
		assertTrue(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.AWAITING_PLANNER, true));
		assertFalse(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.ACTIVE, true));
		assertFalse(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.AWAITING_PLANNER, false));
	}

	@Test
	void resolvedDrowningHoldTreadsWaterUntilPlannerDecision() {
		assertTrue(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.DROWNING, true));
		assertFalse(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.DROWNING, false));
		assertFalse(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.ACTIVE, SurvivalReflexCause.DROWNING, true));
		assertFalse(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.MOB_ATTACK, true));
	}

	@Test
	void resumeRequiresResolvedMatchingSafetyHold() {
		SurvivalReflexSnapshot active = snapshot(SurvivalReflexState.ACTIVE, "hold-1");
		SurvivalReflexSnapshot awaiting = snapshot(SurvivalReflexState.AWAITING_PLANNER, "hold-1");

		assertEquals(SurvivalReflexRuntime.ResumeResult.REFLEX_ACTIVE,
			SurvivalReflexRuntime.validateResume(active, "hold-1"));
		assertEquals(SurvivalReflexRuntime.ResumeResult.NO_SAFETY_HOLD,
			SurvivalReflexRuntime.validateResume(SurvivalReflexSnapshot.idle(), "hold-1"));
		assertEquals(SurvivalReflexRuntime.ResumeResult.STALE_SAFETY_HOLD,
			SurvivalReflexRuntime.validateResume(awaiting, "old-hold"));
		assertEquals(SurvivalReflexRuntime.ResumeResult.RESUMED,
			SurvivalReflexRuntime.validateResume(awaiting, "hold-1"));
	}

	@Test
	void activeAndDrowningHoldOwnActuationWhileBothHoldNormalWork() {
		SurvivalReflexSnapshot active = snapshot(SurvivalReflexState.ACTIVE, "hold-1");
		SurvivalReflexSnapshot awaiting = snapshot(SurvivalReflexState.AWAITING_PLANNER, "hold-1");

		assertTrue(active.ownsActuation());
		assertTrue(active.holdsNormalTasks());
		assertTrue(awaiting.ownsActuation());
		assertTrue(awaiting.holdsNormalTasks());
	}

	@Test
	void awaitingPlannerCannotExistWithoutAResumableHoldIdentity() {
		assertThrows(IllegalArgumentException.class, () -> snapshot(
			SurvivalReflexState.AWAITING_PLANNER,
			null
		));
		assertNotNull(SurvivalReflexRuntime.safetyHoldId(null, true));
		assertEquals("existing-hold", SurvivalReflexRuntime.safetyHoldId("existing-hold", true));
		assertNull(SurvivalReflexRuntime.safetyHoldId(null, false));
	}

	private static SurvivalReflexSnapshot snapshot(SurvivalReflexState state, String holdId) {
		return new SurvivalReflexSnapshot(
			state, SurvivalReflexCause.DROWNING, SurvivalReflexAction.SWIM_TO_AIR, 2L, holdId,
			"job-1", "action-1", List.of(), 10.0F, 20.0F, 100, 300, 10L, 20L, 0, null
		);
	}
	@Test void creeperRetreatsDuringCooldownOrFuseButReengagesAfterReset() {
		assertTrue(SurvivalReflexRuntime.creeperShouldKite(.2F, 0));
		assertTrue(SurvivalReflexRuntime.creeperShouldKite(1, .3F));
		assertFalse(SurvivalReflexRuntime.creeperShouldKite(1, 0));
	}
	@Test void creeperEscapePersistsAfterFuseReversesUntilPursuerIsWellClear() {
		boolean fleeing = SurvivalReflexRuntime.creeperEscapeActive(false, 2.5, 1, .1F, false);
		assertTrue(fleeing, "Start escaping at ignition, before a late fuse");
		for (double distance : new double[]{3, 4, 5, 6, 7}) {
			fleeing = SurvivalReflexRuntime.creeperEscapeActive(fleeing, distance, -1, 0, false);
			assertTrue(fleeing, "A reset fuse or attack cooldown must not end retreat at " + distance);
		}
		assertFalse(SurvivalReflexRuntime.creeperEscapeActive(fleeing, 8, -1, 0, false));
		assertTrue(SurvivalReflexRuntime.creeperEscapeActive(true, 8, -1, .1F, false));
		assertTrue(SurvivalReflexRuntime.creeperEscapeActive(true, 8, -1, 0, true));
		assertTrue(SurvivalReflexRuntime.creeperEscapeActive(true, 10, 1, .1F, true));
		assertFalse(SurvivalReflexRuntime.creeperEscapeActive(true, 10.01, 1, .1F, true));
		assertFalse(SurvivalReflexRuntime.creeperEscapeActive(true, 14, -1, 0, true));
		assertFalse(SurvivalReflexRuntime.creeperEscapeActive(false, 2, -1, 0, false));
	}

	@Test void bowGuardLeavesEarlyDrawForMovementAndAttacks() {
		assertFalse(SurvivalReflexRuntime.shouldGuardBow(true, 0));
		assertFalse(SurvivalReflexRuntime.shouldGuardBow(true, 13));
		assertTrue(SurvivalReflexRuntime.shouldGuardBow(true, 14));
		assertTrue(SurvivalReflexRuntime.shouldGuardBow(true, 20));
		assertFalse(SurvivalReflexRuntime.shouldGuardBow(false, 20));
	}
	@Test void tacticalWindowIsBoundedAndCriticalHealthInterrupts() {
		var window = new SurvivalReflexRuntime.TacticalWindow(600);
		assertTrue(window.active(599, 20));
		assertFalse(window.active(600, 20));
		assertFalse(window.active(100, 4));
	}
}
