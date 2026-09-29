package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropItemsTaskExecutorTest {
	@Test
	void plansExactDropCountAcrossMatchingSlots() {
		List<DropItemsTaskExecutor.DropSlot> slots = List.of(
			new DropItemsTaskExecutor.DropSlot(10, 1),
			new DropItemsTaskExecutor.DropSlot(11, 64),
			new DropItemsTaskExecutor.DropSlot(12, 8)
		);

		List<DropItemsTaskExecutor.DropClick> clicks = DropItemsTaskExecutor.planDropClicks(slots, 66);

		assertEquals(List.of(
			new DropItemsTaskExecutor.DropClick(10, 1),
			new DropItemsTaskExecutor.DropClick(11, 64),
			new DropItemsTaskExecutor.DropClick(12, 1)
		), clicks);
	}

	@Test
	void waitsForTransientBusyScreenBeforeFailingInventoryBusy() {
		assertTrue(DropItemsTaskExecutor.shouldWaitForBusyScreen(0));
		assertTrue(DropItemsTaskExecutor.shouldWaitForBusyScreen(99));
		assertFalse(DropItemsTaskExecutor.shouldWaitForBusyScreen(100));
	}

	@Test
	void dismissesOnlySafeTransientBusyScreens() {
		assertTrue(DropItemsTaskExecutor.shouldDismissBusyScreen("ChatScreen"));
		assertTrue(DropItemsTaskExecutor.shouldDismissBusyScreen("PauseScreen"));

		assertFalse(DropItemsTaskExecutor.shouldDismissBusyScreen(null));
		assertFalse(DropItemsTaskExecutor.shouldDismissBusyScreen("InventoryScreen"));
		assertFalse(DropItemsTaskExecutor.shouldDismissBusyScreen("ContainerScreen"));
		assertFalse(DropItemsTaskExecutor.shouldDismissBusyScreen("AbstractContainerScreen"));
	}

	@Test
	void permitsDropActuationOnlyWhenTheCompanionSessionCanAct() {
		assertFalse(DropItemsTaskExecutor.itemDropActuationAllowed(snapshot(SessionMode.SINGLEPLAYER_LOCAL, true)));
		assertTrue(DropItemsTaskExecutor.itemDropActuationAllowed(snapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true)));
		assertTrue(DropItemsTaskExecutor.itemDropActuationAllowed(snapshot(SessionMode.REMOTE_MULTIPLAYER, true)));

		assertFalse(DropItemsTaskExecutor.itemDropActuationAllowed(snapshot(SessionMode.OUT_OF_WORLD, false)));
		assertFalse(DropItemsTaskExecutor.itemDropActuationAllowed(snapshot(SessionMode.SINGLEPLAYER_LOCAL, false)));
	}

	@Test
	void attributesOnlyTheIncreaseWhenADroppedEntityMergesWithAnExistingStack() {
		assertEquals(2, DropItemsTaskExecutor.agentAttributedQuantity(10, 4, Map.of(10, 2)));
		assertEquals(0, DropItemsTaskExecutor.agentAttributedQuantity(10, 2, Map.of(10, 2)));
		assertEquals(3, DropItemsTaskExecutor.agentAttributedQuantity(11, 3, Map.of()));
	}

	@Test
	void doesNotReuseABaselineWhenTheIntegerIdGetsANewUuid() {
		PlayerItemDeliveryPolicy.EntityGeneration oldGeneration = new PlayerItemDeliveryPolicy.EntityGeneration(10, UUID.fromString("00000000-0000-0000-0000-000000000001"));
		PlayerItemDeliveryPolicy.EntityGeneration newGeneration = new PlayerItemDeliveryPolicy.EntityGeneration(10, UUID.fromString("00000000-0000-0000-0000-000000000002"));

		assertEquals(0, DropItemsTaskExecutor.agentAttributedQuantity(oldGeneration, 2, Map.of(oldGeneration, 2)));
		assertEquals(3, DropItemsTaskExecutor.agentAttributedQuantity(newGeneration, 3, Map.of(oldGeneration, 2)));
	}

	private static SessionSnapshot snapshot(SessionMode mode, boolean worldLoaded) {
		return new SessionSnapshot(mode, true, worldLoaded, "minecraft:overworld", false, 0, 0L);
	}
}
