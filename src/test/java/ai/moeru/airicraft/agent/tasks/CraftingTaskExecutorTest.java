package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.inventory.InventoryMenu;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static ai.moeru.airicraft.agent.tasks.CraftingTaskExecutor.TableNavigationOutcome.FALLBACK;
import static ai.moeru.airicraft.agent.tasks.CraftingTaskExecutor.TableNavigationOutcome.OPEN_TABLE;
import static ai.moeru.airicraft.agent.tasks.CraftingTaskExecutor.TableNavigationOutcome.WAIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingTaskExecutorTest {
	@Test
	void portableTableFromInventoryChoosesPlacementFlow() {
		assertEquals(
			CraftingTaskExecutor.WorkbenchSetupAction.PLACE_PORTABLE_TABLE,
			CraftingTaskExecutor.initialWorkbenchSetupAction(false, true)
		);
	}

	@Test
	void portableTablePlacementDelegatesOneExactTargetToBlockExecutor() {
		GoalPosition target = new GoalPosition(2, 59, -5, true);
		WorldTaskRequest parent = WorldTaskRequest.craftRecipe(
			"craft-1",
			"graph-1",
			new CraftRecipeStepArgs("minecraft:iron_pickaxe", 1)
		);

		WorldTaskRequest placement = CraftingTaskExecutor.portableTablePlacementRequest(parent, 2, target);
		BlockPlacementStepArgs args = ((WorldTaskRequest.PlaceBlock) placement.task()).args();

		assertEquals(WorldTaskType.PLACE_BLOCK, placement.type());
		assertEquals("craft-1:portable-table:2", placement.taskId());
		assertEquals("graph-1", placement.sourceJobId());
		assertEquals("minecraft:crafting_table", args.itemId());
		assertEquals(target, args.targetPosition());
		assertEquals("auto", args.facePreference());
		assertEquals("air_or_replaceable", args.requiredTargetMaterial());
	}

	@Test
	void placementFailureKeepsStableCodeAndDetailedEvidence() {
		assertEquals(
			"crafting_table_place_failed stage=placement_exhausted attempted=4 lastTarget=2,59,-5 lastFailure=safe_stand_position_not_found",
			CraftingTaskExecutor.portableTableFailure(
				"placement_exhausted",
				4,
				new GoalPosition(2, 59, -5, true),
				"safe_stand_position_not_found"
			)
		);
	}

	@Test
	void craftingLifecycleCancelsOwnedPlacementExecutor() {
		RecordingPlacementExecutor placementExecutor = new RecordingPlacementExecutor();
		CraftingTaskExecutor executor = new CraftingTaskExecutor(
			() -> null,
			null,
			new CameraController(),
			placementExecutor
		);

		executor.onWorldLeave();
		assertEquals(1, placementExecutor.worldLeaveCount);

		executor.shutdown();
		assertEquals(2, placementExecutor.worldLeaveCount);
		assertEquals(1, placementExecutor.shutdownCount);
	}

	@Test
	void nearbyTableReuseWinsOverPortableTableSetup() {
		assertEquals(
			CraftingTaskExecutor.WorkbenchSetupAction.REUSE_NEARBY_TABLE,
			CraftingTaskExecutor.initialWorkbenchSetupAction(true, true)
		);
	}

	@Test
	void nearbyTableSearchCanReturnFromAShallowMine() {
		assertTrue(CraftingTaskExecutor.craftingTableWithinSearchBounds(-8, 5, 5));
		assertFalse(CraftingTaskExecutor.craftingTableWithinSearchBounds(-17, 0, 0));
		assertFalse(CraftingTaskExecutor.craftingTableWithinSearchBounds(0, 9, 0));
	}

	@Test
	void portableCraftingTableSourceSlotsIncludeOffhand() {
		assertTrue(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(InventoryMenu.INV_SLOT_START));
		assertTrue(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(InventoryMenu.USE_ROW_SLOT_START));
		assertTrue(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(InventoryMenu.SHIELD_SLOT));

		assertFalse(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(InventoryMenu.CRAFT_SLOT_START));
		assertFalse(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(InventoryMenu.ARMOR_SLOT_START));
	}

	@Test
	void stalledTableNavigationFallsBackAfterTimeout() {
		assertEquals(
			WAIT,
			CraftingTaskExecutor.tableNavigationOutcome(
				Optional.empty(),
				false,
				false,
				CraftingTaskExecutor.TABLE_NAVIGATION_TIMEOUT_TICKS
			)
		);
		assertEquals(
			FALLBACK,
			CraftingTaskExecutor.tableNavigationOutcome(
				Optional.empty(),
				false,
				false,
				CraftingTaskExecutor.TABLE_NAVIGATION_TIMEOUT_TICKS + 1
			)
		);
	}

	@Test
	void terminalNavigationEventsDoNotLoopForever() {
		assertEquals(
			OPEN_TABLE,
			CraftingTaskExecutor.tableNavigationOutcome(Optional.of("AT_GOAL"), false, false, 0)
		);
		assertEquals(
			FALLBACK,
			CraftingTaskExecutor.tableNavigationOutcome(Optional.of("CANCELED"), false, false, 0)
		);
		assertEquals(
			FALLBACK,
			CraftingTaskExecutor.tableNavigationOutcome(Optional.of("CALC_FAILED"), false, false, 0)
		);
	}

	@Test
	void visibleScreensDoNotDetermineCraftingReadiness() {
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting(null));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("InventoryScreen"));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("ChatScreen"));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("PauseScreen"));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("ContainerScreen"));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("AbstractContainerScreen"));
	}

	@Test
	void staleContainerWithEmptyCursorIsClosedBeforeCrafting() {
		assertEquals(
			CraftingTaskExecutor.CraftingScreenDisposition.CLOSE_OPEN_SCREEN,
			CraftingTaskExecutor.craftingScreenDisposition(false, false, true, true)
		);
		assertEquals(
			CraftingTaskExecutor.CraftingScreenDisposition.CLOSE_OPEN_SCREEN,
			CraftingTaskExecutor.craftingScreenDisposition(false, true, false, true)
		);
	}

	@Test
	void craftingKeepsOnlyTheHandlerRequiredByItsGrid() {
		assertEquals(
			CraftingTaskExecutor.CraftingScreenDisposition.READY,
			CraftingTaskExecutor.craftingScreenDisposition(true, false, false, true)
		);
		assertEquals(
			CraftingTaskExecutor.CraftingScreenDisposition.READY,
			CraftingTaskExecutor.craftingScreenDisposition(false, true, true, true)
		);
	}

	@Test
	void staleContainerWithCarriedCursorItemFailsSafely() {
		assertEquals(
			CraftingTaskExecutor.CraftingScreenDisposition.FAIL,
			CraftingTaskExecutor.craftingScreenDisposition(false, false, true, false)
		);
	}

	@Test
	void craftPlanCarriesResolvedNetworkRecipeId() {
		RecipeDisplayId recipeDisplayId = new RecipeDisplayId(42);
		CraftingOpportunityResolver.CraftingRecipeResolution resolution = new CraftingOpportunityResolver.CraftingRecipeResolution(
			recipeDisplayId,
			null,
			4,
			2,
			CraftingGridKind.PLAYER_2X2,
			java.util.List.of(),
			null
		);

		CraftingTaskExecutor.CraftingPlan plan = CraftingTaskExecutor.toCraftingPlanForTests(resolution);

		assertEquals(recipeDisplayId, plan.networkRecipeId());
		assertEquals(8, plan.targetOutputCount());
	}

	@Test
	void recipeFillRequestUsesHandlerSyncId() {
		RecipeDisplayId recipeDisplayId = new RecipeDisplayId(7);

		CraftingTaskExecutor.RecipeFillRequest request = CraftingTaskExecutor.recipeFillRequestForTests(123, recipeDisplayId);

		assertEquals(123, request.syncId());
		assertEquals(recipeDisplayId, request.networkRecipeId());
		assertFalse(request.craftAll());
	}

	private static final class RecordingPlacementExecutor implements WorldTaskExecutor {
		private int worldLeaveCount;
		private int shutdownCount;

		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			return Optional.empty();
		}

		@Override
		public TaskExecutionSnapshot snapshot() {
			return TaskExecutionSnapshot.idle();
		}

		@Override
		public void onWorldLeave() {
			worldLeaveCount++;
		}

		@Override
		public void shutdown() {
			shutdownCount++;
		}
	}
}
