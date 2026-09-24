package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityInteractionTaskExecutorTest {
	@Test void rejectsServerForbiddenAttackTargets() {
		assertFalse(EntityInteractionTaskExecutor.attackTargetAllowed(net.minecraft.entity.ItemEntity.class, false, true));
		assertFalse(EntityInteractionTaskExecutor.attackTargetAllowed(net.minecraft.entity.ExperienceOrbEntity.class, false, true));
		assertFalse(EntityInteractionTaskExecutor.attackTargetAllowed(net.minecraft.entity.projectile.ArrowEntity.class, false, false));
		assertFalse(EntityInteractionTaskExecutor.attackTargetAllowed(net.minecraft.entity.player.PlayerEntity.class, true, true));
		assertTrue(EntityInteractionTaskExecutor.attackTargetAllowed(net.minecraft.entity.mob.ZombieEntity.class, false, true));
		assertTrue(EntityInteractionTaskExecutor.attackTargetAllowed(net.minecraft.entity.decoration.EndCrystalEntity.class, false, true));
		assertTrue(EntityInteractionTaskExecutor.attackTargetAllowed(net.minecraft.entity.projectile.FireballEntity.class, false, true));
	}

	@Test void collectionReportCountsGainsInsteadOfExistingInventoryOrExpectedLoot() {
		var before = java.util.Map.of("minecraft:beef", 5, "minecraft:leather", 1, "minecraft:dirt", 10);
		var after = java.util.Map.of("minecraft:beef", 7, "minecraft:leather", 1, "minecraft:dirt", 9);
		assertEquals("target_died_nearby_drops_cleared collectedItems={minecraft:beef=2} collectionEvidence=inventory_gain",
			EntityInteractionTaskExecutor.collectionReport("target_died_nearby_drops_cleared", before, after));
		assertEquals("target_died_drops_uncollected_timeout collectedItems={minecraft:beef=2} collectionEvidence=inventory_gain",
			EntityInteractionTaskExecutor.collectionReport("target_died_drops_uncollected_timeout", before, after));
		assertEquals("target_died_nearby_drops_cleared collectedItems={} collectionEvidence=inventory_gain",
			EntityInteractionTaskExecutor.collectionReport("target_died_nearby_drops_cleared", before, before));
	}

	@Test void disappearingTargetMustHaveDeathEvidenceBeforeCollectingDrops() {
		assertTrue(EntityInteractionTaskExecutor.confirmedKill(true, null));
		assertTrue(EntityInteractionTaskExecutor.confirmedKill(false, net.minecraft.entity.Entity.RemovalReason.KILLED));
		assertFalse(EntityInteractionTaskExecutor.confirmedKill(false, null));
		assertFalse(EntityInteractionTaskExecutor.confirmedKill(false, net.minecraft.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK));
		assertFalse(EntityInteractionTaskExecutor.confirmedKill(false, net.minecraft.entity.Entity.RemovalReason.DISCARDED));
	}

	@Test
	void waitsForTransientBusyStateBeforeFailing() {
		assertTrue(EntityInteractionTaskExecutor.shouldWaitForBusyState(0));
		assertTrue(EntityInteractionTaskExecutor.shouldWaitForBusyState(99));
		assertFalse(EntityInteractionTaskExecutor.shouldWaitForBusyState(100));
	}

	@Test
	void refreshesBaritoneChaseGoalWhenTargetMovedEnoughOrRefreshIntervalElapsed() {
		GoalPosition current = new GoalPosition(10, 64, 20, false);

		assertTrue(EntityInteractionTaskExecutor.shouldRefreshChaseGoal(null, current, 0));
		assertFalse(EntityInteractionTaskExecutor.shouldRefreshChaseGoal(current, current, 0));
		assertFalse(EntityInteractionTaskExecutor.shouldRefreshChaseGoal(current, new GoalPosition(11, 64, 20, false), 0));
		assertTrue(EntityInteractionTaskExecutor.shouldRefreshChaseGoal(current, new GoalPosition(12, 64, 20, false), 0));
		assertTrue(EntityInteractionTaskExecutor.shouldRefreshChaseGoal(current, current, 10));
	}

	@Test
	void directChaseRequiresCloseVisibleAndNotStuck() {
		assertTrue(EntityInteractionTaskExecutor.shouldUseDirectChase(9.9D, true, false));

		assertFalse(EntityInteractionTaskExecutor.shouldUseDirectChase(10.1D, true, false));
		assertFalse(EntityInteractionTaskExecutor.shouldUseDirectChase(9.9D, false, false));
		assertFalse(EntityInteractionTaskExecutor.shouldUseDirectChase(9.9D, true, true));
	}

	@Test
	void attackModeDefaultsToKillAndParsesHitOnce() {
		assertEquals(EntityAttackMode.KILL, EntityAttackMode.fromWireValue(null));
		assertEquals(EntityAttackMode.KILL, EntityAttackMode.fromWireValue("kill"));
		assertEquals(EntityAttackMode.HIT_ONCE, EntityAttackMode.fromWireValue("hit_once"));
	}

	@Test
	void killCompletionUsesTypedSelectionStatus() {
		assertTrue(EntityInteractionTaskExecutor.completedAfterLandedAttack(
			WorldTaskType.ATTACK_ENTITY,
			EntityAttackMode.KILL,
			true,
			EntitySelectorResolver.SelectionStatus.TARGET_NOT_FOUND
		));
		assertTrue(EntityInteractionTaskExecutor.completedAfterLandedAttack(
			WorldTaskType.ATTACK_ENTITY,
			EntityAttackMode.KILL,
			true,
			EntitySelectorResolver.SelectionStatus.TARGET_NOT_ALIVE
		));
		assertFalse(EntityInteractionTaskExecutor.completedAfterLandedAttack(
			WorldTaskType.ATTACK_ENTITY,
			EntityAttackMode.KILL,
			true,
			EntitySelectorResolver.SelectionStatus.TARGET_NOT_NEARBY
		));
	}
}
