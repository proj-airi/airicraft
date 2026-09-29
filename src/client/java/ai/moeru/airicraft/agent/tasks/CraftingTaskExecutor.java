package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class CraftingTaskExecutor implements WorldTaskExecutor {
	private static final int WAIT_TIMEOUT_TICKS = 20;
	static final int TABLE_NAVIGATION_TIMEOUT_TICKS = 200;
	private static final int TABLE_SEARCH_RADIUS = 16;
	private static final int TABLE_SEARCH_VERTICAL_RADIUS = 8;
	private static final double TABLE_INTERACTION_RANGE_SQUARED = 20.25D;

	private final Supplier<Minecraft> clientSupplier;
	private final BaritoneFacade baritoneFacade;
	private final CameraController cameraController;
	private final WorldTaskExecutor portableTablePlacementExecutor;

	private WorldTaskRequest appliedTask;
	private CraftingPlan plan;
	private CraftingPlan craftingTablePlan;
	private CraftPhase phase = CraftPhase.IDLE;
	private final CraftingProgressTracker progressTracker = new CraftingProgressTracker();
	private final CraftingProgressTracker craftingTableProgressTracker = new CraftingProgressTracker();
	private int waitTicks;
	private boolean terminalEventEmitted;
	private TableTarget tableTarget;
	private BlockPos placedTablePos;
	private boolean navigationStarted;
	private boolean openedWorkbenchForTask;
	private PortableTablePlacementPolicy.AttemptState portableTablePlacementState;
	private WorldTaskRequest portableTablePlacementTask;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public CraftingTaskExecutor() {
		this(Minecraft::getInstance, null, new CameraController());
	}

	public CraftingTaskExecutor(BaritoneFacade baritoneFacade) {
		this(Minecraft::getInstance, baritoneFacade, new CameraController());
	}

	public CraftingTaskExecutor(BaritoneFacade baritoneFacade, CameraController cameraController) {
		this(Minecraft::getInstance, baritoneFacade, cameraController);
	}

	CraftingTaskExecutor(Supplier<Minecraft> clientSupplier) {
		this(clientSupplier, null, new CameraController());
	}

	CraftingTaskExecutor(Supplier<Minecraft> clientSupplier, BaritoneFacade baritoneFacade) {
		this(clientSupplier, baritoneFacade, new CameraController());
	}

	CraftingTaskExecutor(Supplier<Minecraft> clientSupplier, BaritoneFacade baritoneFacade, CameraController cameraController) {
		this(
			clientSupplier,
			baritoneFacade,
			cameraController,
			new BlockInteractionTaskExecutor(clientSupplier, cameraController, 0, baritoneFacade)
		);
	}

	CraftingTaskExecutor(
		Supplier<Minecraft> clientSupplier,
		BaritoneFacade baritoneFacade,
		CameraController cameraController,
		WorldTaskExecutor portableTablePlacementExecutor
	) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.baritoneFacade = baritoneFacade;
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
		this.portableTablePlacementExecutor = Objects.requireNonNull(portableTablePlacementExecutor, "portableTablePlacementExecutor");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.CRAFT_RECIPE) {
			reset();
			return Optional.empty();
		}

		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}

		if (!sessionSnapshot.companionActuationAllowed()) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}

		Minecraft minecraft = clientSupplier.get();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft == null || minecraft.gameMode == null || player == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "crafting_busy"));
		}
		if (plan == null) {
			plan = resolvePlan(player, ((WorldTaskRequest.CraftRecipe) request.task()).args());
			if (plan.failure() != null) {
				return fail(request, plan.failure());
			}
			progressTracker.reset();
		}
		CraftingScreenDisposition screenDisposition = craftingScreenDisposition(
			player.containerMenu == player.inventoryMenu,
			player.containerMenu instanceof CraftingMenu,
			plan.gridKind() == CraftingGridKind.WORKBENCH_3X3,
			player.containerMenu.getCarried().isEmpty()
		);
		if (screenDisposition == CraftingScreenDisposition.CLOSE_OPEN_SCREEN) {
			ScreenCloseSafety.closeHandledScreen(player, "crafting_blocking_screen_close");
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "closing_blocking_screen");
			return Optional.empty();
		}
		if (screenDisposition == CraftingScreenDisposition.FAIL) {
			return fail(request, TaskFailure.of(TaskFailureCode.BUSY, "crafting_busy"));
		}

		if (progressTracker.targetReached(plan.targetOutputCount())) {
			return complete(request);
		}

		if (plan.gridKind() == CraftingGridKind.WORKBENCH_3X3) {
			WorkbenchReadiness readiness = ensureWorkbenchReady(request, sessionSnapshot, minecraft, player);
			if (readiness.failure().isPresent()) {
				return fail(request, readiness.failure().get());
			}
			if (!readiness.ready()) {
				return Optional.empty();
			}
		}

		CraftAdvanceResult advanced = advanceCrafting(
			request,
			minecraft,
			player,
			plan,
			CraftingGridSpec.forKind(plan.gridKind()),
			progressTracker,
			CraftPhase.PLACING_INPUTS,
			CraftPhase.WAITING_FOR_RESULT,
			CraftPhase.WAITING_FOR_TAKE
		);
		if (advanced.status() == CraftAdvanceStatus.FAILED) {
			return fail(request, advanced.failure());
		}
		if (advanced.status() == CraftAdvanceStatus.COMPLETED) {
			return complete(request);
		}

		snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting");
		return Optional.empty();
	}

	private CraftingPlan resolvePlan(LocalPlayer player, CraftRecipeStepArgs request) {
		CraftingOpportunityResolver.CraftingRecipeResolution resolved = CraftingOpportunityResolver.resolve(player, request);
		return toCraftingPlan(resolved);
	}

	private static CraftingPlan toCraftingPlan(CraftingOpportunityResolver.CraftingRecipeResolution resolved) {
		if (resolved.failureReason() != null) {
			return CraftingPlan.failure(resolved.failureReason(), TaskFailureCode.MISSING_FACT);
		}
		return new CraftingPlan(
			resolved.networkRecipeId(),
			resolved.outputItem(),
			resolved.outputCount(),
			resolved.requestedTimes(),
			resolved.outputCount() * resolved.requestedTimes(),
			resolved.gridKind(),
			resolved.placements(),
			null
		);
	}

	static CraftingPlan toCraftingPlanForTests(CraftingOpportunityResolver.CraftingRecipeResolution resolved) {
		return toCraftingPlan(resolved);
	}

	private WorkbenchReadiness ensureWorkbenchReady(
		WorldTaskRequest request,
		SessionSnapshot sessionSnapshot,
		Minecraft minecraft,
		LocalPlayer player
	) {
		if (player.containerMenu instanceof CraftingMenu) {
			if (phase != CraftPhase.IDLE
				&& phase != CraftPhase.PLACING_INPUTS
				&& phase != CraftPhase.WAITING_FOR_RESULT
				&& phase != CraftPhase.WAITING_FOR_TAKE) {
				phase = CraftPhase.PLACING_INPUTS;
				waitTicks = 0;
			}
			return WorkbenchReadiness.readyState();
		}
		if (player.containerMenu != player.inventoryMenu) {
			return WorkbenchReadiness.failed(TaskFailure.of(TaskFailureCode.BUSY, "crafting_busy"));
		}
		if (phase == CraftPhase.IDLE) {
			tableTarget = findNearbyCraftingTable(minecraft, player).orElse(null);
			boolean tableSubstantiallyBelowPlayer = tableTarget != null
				&& tableTarget.tablePos().getY() < player.getBlockY() - 1;
			phase = switch (initialWorkbenchSetupAction(
				tableTarget != null,
				tableSubstantiallyBelowPlayer,
				hasCraftingTableItem(player.containerMenu)
			)) {
				case REUSE_NEARBY_TABLE -> CraftPhase.NAVIGATING_TO_TABLE;
				case PLACE_PORTABLE_TABLE -> CraftPhase.PLACING_TABLE;
				case CRAFT_PORTABLE_TABLE -> CraftPhase.CRAFTING_TABLE_INPUTS;
			};
			waitTicks = 0;
		}

		if (phase == CraftPhase.CRAFTING_TABLE_INPUTS
			|| phase == CraftPhase.CRAFTING_TABLE_WAITING_FOR_RESULT
			|| phase == CraftPhase.CRAFTING_TABLE_WAITING_FOR_TAKE) {
			if (craftingTablePlan == null) {
				CraftingOpportunityResolver.CraftingRecipeResolution resolved = CraftingOpportunityResolver.resolveCraftingTable(player);
				if (resolved.failureReason() != null) {
					return WorkbenchReadiness.failed(TaskFailure.of(TaskFailureCode.MISSING_FACT, resolved.failureReason()));
				}
				craftingTablePlan = new CraftingPlan(
					resolved.networkRecipeId(),
					resolved.outputItem(),
					resolved.outputCount(),
					1,
					resolved.outputCount(),
					resolved.gridKind(),
					resolved.placements(),
					null
				);
				craftingTableProgressTracker.reset();
			}
			CraftAdvanceResult advanced = advanceCrafting(
				request,
				minecraft,
				player,
				craftingTablePlan,
				CraftingGridSpec.PLAYER,
				craftingTableProgressTracker,
				CraftPhase.CRAFTING_TABLE_INPUTS,
				CraftPhase.CRAFTING_TABLE_WAITING_FOR_RESULT,
				CraftPhase.CRAFTING_TABLE_WAITING_FOR_TAKE
			);
			if (advanced.status() == CraftAdvanceStatus.FAILED) {
				return WorkbenchReadiness.failed(TaskFailure.of(TaskFailureCode.MISSING_FACT, "crafting_table_missing_materials"));
			}
			if (advanced.status() == CraftAdvanceStatus.COMPLETED) {
				phase = CraftPhase.PLACING_TABLE;
				waitTicks = 0;
			}
			return WorkbenchReadiness.notReadyState();
		}

		if (phase == CraftPhase.PLACING_TABLE) {
			if (closeInventoryScreenIfOpen(minecraft, player)) {
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "inventory_screen_dismissed");
				return WorkbenchReadiness.notReadyState();
			}
			return tickPortableTablePlacement(request, sessionSnapshot, minecraft, player);
		}

		if (phase == CraftPhase.NAVIGATING_TO_TABLE) {
			if (closeInventoryScreenIfOpen(minecraft, player)) {
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "inventory_screen_dismissed");
				return WorkbenchReadiness.notReadyState();
			}
			if (tableTarget == null) {
				return WorkbenchReadiness.failed(TaskFailure.of(TaskFailureCode.MISSING_FACT, "crafting_table_not_found"));
			}
			if (withinInteractionRange(player, tableTarget.tablePos())) {
				phase = CraftPhase.OPENING_TABLE;
				waitTicks = 0;
				return WorkbenchReadiness.notReadyState();
			}
			if (tableTarget.standPosition() == null || baritoneFacade == null || !baritoneFacade.isLoaded()) {
				return fallBackToPortableCraftingTable(request, player);
			}
			if (!navigationStarted) {
				baritoneFacade.startNavigate(tableTarget.standPosition());
				navigationStarted = true;
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_navigation_started");
				return WorkbenchReadiness.notReadyState();
			}
			Optional<String> pathEvent = baritoneFacade.pollPathEvent();
			int elapsedNavigationTicks = waitTicks + 1;
			TableNavigationOutcome navigationOutcome = tableNavigationOutcome(
				pathEvent,
				baritoneFacade.navigationGoalReached(tableTarget.standPosition()),
				withinInteractionRange(player, tableTarget.tablePos()),
				elapsedNavigationTicks
			);
			if (navigationOutcome == TableNavigationOutcome.FALLBACK) {
				return fallBackToPortableCraftingTable(request, player);
			}
			if (navigationOutcome == TableNavigationOutcome.OPEN_TABLE) {
				phase = CraftPhase.OPENING_TABLE;
				waitTicks = 0;
			}
			else {
				waitTicks = elapsedNavigationTicks;
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_navigating");
			}
			return WorkbenchReadiness.notReadyState();
		}

		if (phase == CraftPhase.OPENING_TABLE) {
			if (closeInventoryScreenIfOpen(minecraft, player)) {
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "inventory_screen_dismissed");
				return WorkbenchReadiness.notReadyState();
			}
			if (tableTarget == null || tableTarget.tablePos() == null) {
				return WorkbenchReadiness.failed(TaskFailure.of(TaskFailureCode.MISSING_FACT, "crafting_table_not_found"));
			}
			Vec3 aim = Vec3.atCenterOf(tableTarget.tablePos());
			cameraController.lookAt(minecraft, aim);
			if (!cameraController.isLookingAt(minecraft, aim)) {
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "aiming_at_crafting_table");
				return WorkbenchReadiness.notReadyState();
			}
			if (!openCraftingTable(minecraft, player, tableTarget.tablePos())) {
				return fallBackToPortableCraftingTable(request, player);
			}
			phase = CraftPhase.WAITING_FOR_TABLE_SCREEN;
			waitTicks = 0;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_opened");
			return WorkbenchReadiness.notReadyState();
		}

		if (phase == CraftPhase.WAITING_FOR_TABLE_SCREEN) {
			if (player.containerMenu instanceof CraftingMenu) {
				phase = CraftPhase.PLACING_INPUTS;
				waitTicks = 0;
				return WorkbenchReadiness.readyState();
			}
			return waitForWorkbench(request, player, "crafting_table_open_failed");
		}

		return WorkbenchReadiness.notReadyState();
	}

	private WorkbenchReadiness waitForWorkbench(WorldTaskRequest request, LocalPlayer player, String reason) {
		waitTicks++;
		if (waitTicks > WAIT_TIMEOUT_TICKS) {
			if ("crafting_table_open_failed".equals(reason)) {
				return fallBackToPortableCraftingTable(request, player);
			}
			return WorkbenchReadiness.failed(TaskFailure.of(TaskFailureCode.UNKNOWN, reason));
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_wait");
		return WorkbenchReadiness.notReadyState();
	}

	private WorkbenchReadiness fallBackToPortableCraftingTable(WorldTaskRequest request, LocalPlayer player) {
		cancelNavigationIfStarted();
		cancelPortableTablePlacement();
		tableTarget = null;
		placedTablePos = null;
		waitTicks = 0;
		if (hasCraftingTableItem(player.containerMenu)) {
			phase = CraftPhase.PLACING_TABLE;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_fallback_to_inventory");
			return WorkbenchReadiness.notReadyState();
		}
		phase = CraftPhase.CRAFTING_TABLE_INPUTS;
		craftingTablePlan = null;
		craftingTableProgressTracker.reset();
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_fallback_to_planks");
		return WorkbenchReadiness.notReadyState();
	}

	static TableNavigationOutcome tableNavigationOutcome(Optional<String> pathEvent, boolean goalReached, boolean withinInteractionRange, int elapsedTicks) {
		if (pathEvent.isPresent()) {
			String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
			if ("AT_GOAL".equals(normalized)) {
				return TableNavigationOutcome.OPEN_TABLE;
			}
			if ("CALC_FAILED".equals(normalized) || "CANCELED".equals(normalized) || "CANCELLED".equals(normalized)) {
				return TableNavigationOutcome.FALLBACK;
			}
		}
		if (goalReached || withinInteractionRange) {
			return TableNavigationOutcome.OPEN_TABLE;
		}
		if (elapsedTicks > TABLE_NAVIGATION_TIMEOUT_TICKS) {
			return TableNavigationOutcome.FALLBACK;
		}
		return TableNavigationOutcome.WAIT;
	}

	private CraftAdvanceResult advanceCrafting(
		WorldTaskRequest request,
		Minecraft minecraft,
		LocalPlayer player,
		CraftingPlan plan,
		CraftingGridSpec gridSpec,
		CraftingProgressTracker tracker,
		CraftPhase placingPhase,
		CraftPhase waitingForResultPhase,
		CraftPhase waitingForTakePhase
	) {
		boolean requireEmptyGrid = phase == CraftPhase.IDLE || phase == placingPhase;
		Optional<TaskFailure> readinessFailure = readinessFailure(player, gridSpec, requireEmptyGrid);
		if (readinessFailure.isPresent()) {
			return CraftAdvanceResult.failed(readinessFailure.get());
		}

		AbstractContainerMenu menu = player.containerMenu;
		if (phase == CraftPhase.IDLE || phase == placingPhase) {
			if (!placeRecipeInputs(minecraft, menu, gridSpec, plan)) {
				return CraftAdvanceResult.failed(TaskFailure.of(TaskFailureCode.MISSING_FACT, "recipe_not_found"));
			}
			phase = waitingForResultPhase;
			waitTicks = 0;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_recipe_requested");
			return CraftAdvanceResult.running();
		}

		if (phase == waitingForResultPhase) {
			ItemStack resultStack = menu.getSlot(gridSpec.resultSlot()).getItem();
			if (!resultStack.isEmpty() && resultStack.is(plan.outputItem())) {
				tracker.beginTake(inventoryCount(player, plan.outputItem()), resultStack.getCount());
				minecraft.gameMode.handleInventoryMouseClick(menu.containerId, gridSpec.resultSlot(), 0, ClickType.QUICK_MOVE, player);
				phase = waitingForTakePhase;
				waitTicks = 0;
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_result_taken");
				return CraftAdvanceResult.running();
			}
			return waitCraftAdvance(request, TaskFailure.of(TaskFailureCode.BUSY, "crafting_busy"));
		}

		if (phase == waitingForTakePhase) {
			if (tracker.finishTakeIfInventoryIncreased(inventoryCount(player, plan.outputItem()))) {
				phase = placingPhase;
				waitTicks = 0;
				if (tracker.targetReached(plan.targetOutputCount())) {
					return CraftAdvanceResult.completed();
				}
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_next_batch");
				return CraftAdvanceResult.running();
			}
			return waitCraftAdvance(request, TaskFailure.of(TaskFailureCode.BUSY, "crafting_busy"));
		}

		return CraftAdvanceResult.running();
	}

	private CraftAdvanceResult waitCraftAdvance(WorldTaskRequest request, TaskFailure failure) {
		waitTicks++;
		if (waitTicks > WAIT_TIMEOUT_TICKS) {
			return CraftAdvanceResult.failed(failure);
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_wait");
		return CraftAdvanceResult.running();
	}

	private static boolean requestRecipeFill(Minecraft minecraft, AbstractContainerMenu menu, CraftingPlan plan) {
		if (minecraft.gameMode == null || plan.networkRecipeId() == null) {
			return false;
		}
		RecipeFillRequest request = recipeFillRequest(menu.containerId, plan.networkRecipeId());
		minecraft.gameMode.handlePlaceRecipe(request.syncId(), request.networkRecipeId(), request.craftAll());
		return true;
	}

	private static boolean placeRecipeInputs(Minecraft minecraft, AbstractContainerMenu menu, CraftingGridSpec gridSpec, CraftingPlan plan) {
		if (plan.networkRecipeId() != null) {
			return requestRecipeFill(minecraft, menu, plan);
		}
		if (minecraft.gameMode == null || plan.placements().isEmpty()) {
			return false;
		}
		for (CraftingOpportunityResolver.CraftingIngredientPlacement placement : plan.placements()) {
			int targetSlot = gridSpec.firstInputSlot() + placement.gridIndex();
			if (targetSlot < gridSpec.firstInputSlot() || targetSlot >= gridSpec.firstInputSlot() + gridSpec.inputSlotCount()) {
				return false;
			}
			ItemStack targetStack = menu.getSlot(targetSlot).getItem();
			if (!targetStack.isEmpty()) {
				if (targetStack.is(placement.item())) {
					continue;
				}
				return false;
			}
			int sourceSlot = findInventorySlot(menu, gridSpec, placement.item());
			if (sourceSlot < 0) {
				return false;
			}
			minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot, 0, ClickType.PICKUP, minecraft.player);
			minecraft.gameMode.handleInventoryMouseClick(menu.containerId, targetSlot, 1, ClickType.PICKUP, minecraft.player);
			minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot, 0, ClickType.PICKUP, minecraft.player);
		}
		return true;
	}

	private static RecipeFillRequest recipeFillRequest(int handlerSyncId, RecipeDisplayId recipeDisplayId) {
		return new RecipeFillRequest(handlerSyncId, Objects.requireNonNull(recipeDisplayId, "networkRecipeId"), false);
	}

	static RecipeFillRequest recipeFillRequestForTests(int handlerSyncId, RecipeDisplayId recipeDisplayId) {
		return recipeFillRequest(handlerSyncId, recipeDisplayId);
	}

	private static int findInventorySlot(AbstractContainerMenu menu, CraftingGridSpec gridSpec, Item item) {
		for (int slot = gridSpec.inventoryStart(); slot < gridSpec.hotbarEnd(); slot++) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (!stack.isEmpty() && stack.is(item)) {
				return slot;
			}
		}
		return -1;
	}

	private static Optional<TaskFailure> readinessFailure(LocalPlayer player, CraftingGridSpec gridSpec, boolean requireEmptyGrid) {
		if (!gridSpec.matches(player.containerMenu)) {
			return Optional.of(TaskFailure.of(TaskFailureCode.BUSY, "crafting_busy"));
		}
		AbstractContainerMenu menu = player.containerMenu;
		if (!menu.getCarried().isEmpty()) {
			return Optional.of(TaskFailure.of(TaskFailureCode.UNKNOWN, "crafting_grid_occupied"));
		}
		if (!requireEmptyGrid) {
			return Optional.empty();
		}
		for (int index = gridSpec.firstInputSlot(); index < gridSpec.firstInputSlot() + gridSpec.inputSlotCount(); index++) {
			if (!menu.getSlot(index).getItem().isEmpty()) {
				return Optional.of(TaskFailure.of(TaskFailureCode.UNKNOWN, "crafting_grid_occupied"));
			}
		}
		return Optional.empty();
	}

	static boolean isVisibleScreenBlockingCrafting(String screenName) {
		return false;
	}

	static CraftingScreenDisposition craftingScreenDisposition(
		boolean playerScreenHandler,
		boolean craftingScreenHandler,
		boolean workbenchRequired,
		boolean cursorEmpty
	) {
		if (playerScreenHandler || workbenchRequired && craftingScreenHandler) {
			return CraftingScreenDisposition.READY;
		}
		return cursorEmpty ? CraftingScreenDisposition.CLOSE_OPEN_SCREEN : CraftingScreenDisposition.FAIL;
	}

	/** Observe the same usable workbench candidates that execution can open or approach. */
	public static Optional<BlockPos> nearbyCraftingTablePosition(Minecraft minecraft) {
		if (minecraft == null || minecraft.player == null) return Optional.empty();
		return findNearbyCraftingTable(minecraft, minecraft.player).map(TableTarget::tablePos);
	}

	private static Optional<TableTarget> findNearbyCraftingTable(Minecraft minecraft, LocalPlayer player) {
		if (minecraft.level == null) {
			return Optional.empty();
		}
		BlockPos origin = player.blockPosition();
		TableTarget best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -TABLE_SEARCH_RADIUS; dx <= TABLE_SEARCH_RADIUS; dx++) {
			for (int dy = -TABLE_SEARCH_VERTICAL_RADIUS; dy <= TABLE_SEARCH_VERTICAL_RADIUS; dy++) {
				for (int dz = -TABLE_SEARCH_RADIUS; dz <= TABLE_SEARCH_RADIUS; dz++) {
					BlockPos pos = origin.offset(dx, dy, dz);
					double distance = origin.distSqr(pos);
					if (!craftingTableWithinSearchBounds(dx, dy, dz) || distance >= bestDistance || !minecraft.level.hasChunkAt(pos)) {
						continue;
					}
					if (!minecraft.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
						continue;
					}
					GoalPosition standPosition = standPositionForTable(minecraft, player, pos).orElse(null);
					if (standPosition == null && !withinInteractionRange(player, pos)) {
						continue;
					}
					best = new TableTarget(pos.immutable(), standPosition);
					bestDistance = distance;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	static boolean craftingTableWithinSearchBounds(int dx, int dy, int dz) {
		long distanceSquared = (long) dx * dx + (long) dy * dy + (long) dz * dz;
		return Math.abs(dy) <= TABLE_SEARCH_VERTICAL_RADIUS
			&& distanceSquared <= (long) TABLE_SEARCH_RADIUS * TABLE_SEARCH_RADIUS;
	}

	private static Optional<GoalPosition> standPositionForTable(Minecraft minecraft, LocalPlayer player, BlockPos tablePos) {
		if (minecraft.level == null) {
			return Optional.empty();
		}
		BlockPos current = player.blockPosition();
		GoalPosition best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos candidate = tablePos.relative(direction);
			if (!isStandable(minecraft, candidate)) {
				continue;
			}
			double distance = current.distSqr(candidate);
			if (distance < bestDistance) {
				best = new GoalPosition(candidate.getX(), candidate.getY(), candidate.getZ(), true);
				bestDistance = distance;
			}
		}
		return Optional.ofNullable(best);
	}

	private static boolean isStandable(Minecraft minecraft, BlockPos pos) {
		if (minecraft.level == null || !minecraft.level.hasChunkAt(pos) || !minecraft.level.hasChunkAt(pos.above())) {
			return false;
		}
		BlockState feet = minecraft.level.getBlockState(pos);
		BlockState head = minecraft.level.getBlockState(pos.above());
		BlockState floor = minecraft.level.getBlockState(pos.below());
		return (feet.isAir() || feet.canBeReplaced())
			&& (head.isAir() || head.canBeReplaced())
			&& floor.isFaceSturdy(minecraft.level, pos.below(), Direction.UP);
	}

	private static boolean withinInteractionRange(LocalPlayer player, BlockPos pos) {
		return player.distanceToSqr(Vec3.atCenterOf(pos)) <= TABLE_INTERACTION_RANGE_SQUARED;
	}

	private static GoalPosition goalPosition(BlockPos pos) {
		return new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), true);
	}

	private static BlockPos blockPos(GoalPosition position) {
		return new BlockPos(position.x(), position.y(), position.z());
	}

	private static String compactPos(BlockPos pos) {
		return pos == null ? "none" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static String compactGoal(GoalPosition position) {
		return position == null ? "none" : position.x() + "," + position.y() + "," + position.z();
	}

	private static boolean closeInventoryScreenIfOpen(Minecraft minecraft, LocalPlayer player) {
		if (minecraft.screen instanceof InventoryScreen
			&& player.containerMenu == player.inventoryMenu
			&& player.containerMenu.getCarried().isEmpty()) {
			ScreenCloseSafety.clearScreen(minecraft, "crafting_inventory_close");
			return true;
		}
		return false;
	}

	private WorkbenchReadiness tickPortableTablePlacement(
		WorldTaskRequest request,
		SessionSnapshot sessionSnapshot,
		Minecraft minecraft,
		LocalPlayer player
	) {
		if (minecraft.level == null
			|| player.containerMenu != player.inventoryMenu
			|| !player.containerMenu.getCarried().isEmpty()) {
			return WorkbenchReadiness.failed(TaskFailure.of(
				TaskFailureCode.BUSY,
				portableTableFailure("placement_unavailable", 0, null, "crafting_busy")
			));
		}
		if (portableTablePlacementState == null) {
			GoalPosition origin = goalPosition(player.blockPosition());
			List<PortableTablePlacementPolicy.SiteObservation> observations = PortableTablePlacementPolicy
				.candidatePositions(origin)
				.stream()
				.map(candidate -> observePortableTableSite(minecraft, candidate, origin))
				.toList();
			List<GoalPosition> candidates = PortableTablePlacementPolicy.rankFeasibleSites(
				origin,
				observations,
				PortableTablePlacementPolicy.MAX_ATTEMPTS
			);
			portableTablePlacementState = new PortableTablePlacementPolicy.AttemptState(
				candidates,
				sessionSnapshot.tickCount()
			);
			if (candidates.isEmpty()) {
				return WorkbenchReadiness.failed(TaskFailure.of(
					TaskFailureCode.MISSING_FACT,
					portableTableFailure("site_not_found", 0, null, "no_static_feasible_site")
				));
			}
		}

		PortableTablePlacementPolicy.AttemptState placementState = portableTablePlacementState;
		if (placementState.timedOut(sessionSnapshot.tickCount())) {
			return WorkbenchReadiness.failed(TaskFailure.of(
				TaskFailureCode.TRANSIENT,
				portableTableFailure(
					"placement_timeout",
					placementState.attemptNumber(),
					placementState.activeTarget(),
					placementState.lastFailure()
				)
			));
		}
		if (placementState.exhausted()) {
			return WorkbenchReadiness.failed(TaskFailure.of(
				TaskFailureCode.UNKNOWN,
				portableTableFailure(
					"placement_exhausted",
					placementState.candidates().size(),
					null,
					placementState.lastFailure()
				)
			));
		}

		GoalPosition target = placementState.activeTarget();
		if (WorldPlacePreservation.contains(minecraft.level, blockPos(target))) {
			resetPortableTablePlacementAttempt(sessionSnapshot);
			portableTablePlacementState = placementState.advance("preserved_place");
			return WorkbenchReadiness.notReadyState();
		}
		if (portableTablePlacementTask == null) {
			portableTablePlacementTask = portableTablePlacementRequest(request, placementState.attemptNumber(), target);
		}
		Optional<TaskTerminalEvent> terminal = portableTablePlacementExecutor.tick(
			sessionSnapshot,
			Optional.of(portableTablePlacementTask)
		);
		TaskExecutionSnapshot childSnapshot = portableTablePlacementExecutor.snapshot();
		if (terminal.isEmpty()) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, portableTableProgressEvent(placementState, target, childSnapshot.lastPathEvent()));
			return WorkbenchReadiness.notReadyState();
		}

		TaskTerminalEvent childTerminal = terminal.get();
		BlockPos targetPos = blockPos(target);
		boolean exactTablePlaced = childTerminal.terminalState() == TaskExecutionState.COMPLETED
			&& minecraft.level.hasChunkAt(targetPos)
			&& minecraft.level.getBlockState(targetPos).is(Blocks.CRAFTING_TABLE);
		resetPortableTablePlacementAttempt(sessionSnapshot);
		if (exactTablePlaced) {
			placedTablePos = targetPos;
			tableTarget = new TableTarget(targetPos, standPositionForTable(minecraft, player, targetPos).orElse(null));
			clearPortableTablePlacementState();
			phase = CraftPhase.OPENING_TABLE;
			waitTicks = 0;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_placed targetPos=" + compactPos(targetPos));
			return WorkbenchReadiness.notReadyState();
		}

		String failure = childTerminal.terminalState() == TaskExecutionState.COMPLETED
			? "placed_block_not_crafting_table"
			: childTerminal.message();
		PortableTablePlacementPolicy.FailureDecision failureDecision = PortableTablePlacementPolicy.decideFailure(
			placementState,
			childTerminal.terminalState(),
			childTerminal.failureCode(),
			childTerminal.terminationCause(),
			failure
		);
		if (failureDecision.disposition() == PortableTablePlacementPolicy.FailureDisposition.TERMINATE) {
			return WorkbenchReadiness.failed(TaskFailure.of(
				childTerminal.terminalState() == TaskExecutionState.FAILED
					? childTerminal.failureCode()
					: TaskFailureCode.UNKNOWN,
				portableTableFailure(
					"placement_failed",
					placementState.attemptNumber(),
					target,
					failure
				)
			));
		}
		portableTablePlacementState = failureDecision.nextState();
		portableTablePlacementTask = null;
		if (portableTablePlacementState.exhausted()) {
			return WorkbenchReadiness.failed(TaskFailure.of(
				TaskFailureCode.UNKNOWN,
				portableTableFailure(
					"placement_exhausted",
					portableTablePlacementState.candidates().size(),
					target,
					failure
				)
			));
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request,
			"crafting_table_placement_retry"
				+ " completedAttempt=" + placementState.attemptNumber()
				+ " failedTarget=" + compactGoal(target)
				+ " failure=" + failure);
		return WorkbenchReadiness.notReadyState();
	}

	private static PortableTablePlacementPolicy.SiteObservation observePortableTableSite(
		Minecraft minecraft,
		GoalPosition candidate,
		GoalPosition origin
	) {
		BlockPos target = blockPos(candidate);
		boolean targetLoaded = minecraft.level != null && minecraft.level.hasChunkAt(target);
		BlockState targetState = targetLoaded ? minecraft.level.getBlockState(target) : null;
		boolean adjacentSupportAvailable = minecraft.level != null && Arrays.stream(Direction.values())
			.map(target::relative)
			.filter(minecraft.level::hasChunkAt)
			.map(minecraft.level::getBlockState)
			.anyMatch(state -> !state.isAir() && !state.canBeReplaced());
		return new PortableTablePlacementPolicy.SiteObservation(
			candidate,
			targetLoaded,
			targetState != null && (targetState.isAir() || targetState.canBeReplaced()),
			adjacentSupportAvailable,
			occupiesPlayerSpace(candidate, origin),
			WorldPlacePreservation.contains(minecraft.level, target)
		);
	}

	private static boolean occupiesPlayerSpace(GoalPosition candidate, GoalPosition origin) {
		return candidate.x() == origin.x()
			&& candidate.z() == origin.z()
			&& candidate.y() >= origin.y()
			&& candidate.y() <= origin.y() + 1;
	}

	static WorldTaskRequest portableTablePlacementRequest(
		WorldTaskRequest parent,
		int attemptNumber,
		GoalPosition target
	) {
		return WorldTaskRequest.placeBlock(
			parent.taskId() + ":portable-table:" + attemptNumber,
			parent.sourceJobId(),
			new BlockPlacementStepArgs("minecraft:crafting_table", target, "auto", "air_or_replaceable")
		);
	}

	private static String portableTableProgressEvent(
		PortableTablePlacementPolicy.AttemptState state,
		GoalPosition target,
		String childEvent
	) {
		return "crafting_table_placing"
			+ " attempt=" + state.attemptNumber() + "/" + state.candidates().size()
			+ " targetPos=" + compactGoal(target)
			+ " childEvent=" + (childEvent == null ? "starting" : childEvent);
	}

	static String portableTableFailure(String stage, int attempted, GoalPosition target, String lastFailure) {
		return "crafting_table_place_failed"
			+ " stage=" + stage
			+ " attempted=" + attempted
			+ " lastTarget=" + compactGoal(target)
			+ " lastFailure=" + (lastFailure == null || lastFailure.isBlank() ? "none" : lastFailure);
	}

	private void resetPortableTablePlacementAttempt(SessionSnapshot sessionSnapshot) {
		portableTablePlacementExecutor.tick(sessionSnapshot, Optional.empty());
		portableTablePlacementTask = null;
	}

	private void clearPortableTablePlacementState() {
		portableTablePlacementState = null;
		portableTablePlacementTask = null;
	}

	private static boolean hasCraftingTableItem(AbstractContainerMenu menu) {
		return findPortableCraftingTableSlot(menu) >= 0;
	}

	private static int findPortableCraftingTableSlot(AbstractContainerMenu menu) {
		if (!(menu instanceof InventoryMenu)) {
			return -1;
		}
		for (int slot = 0; slot < menu.slots.size(); slot++) {
			if (!isPortableCraftingTableSourceSlot(slot)) {
				continue;
			}
			ItemStack stack = menu.getSlot(slot).getItem();
			if (!stack.isEmpty() && stack.is(Items.CRAFTING_TABLE)) {
				return slot;
			}
		}
		return -1;
	}

	static boolean isPortableCraftingTableSourceSlot(int slot) {
		return (slot >= InventoryMenu.INV_SLOT_START && slot < InventoryMenu.USE_ROW_SLOT_END)
			|| slot == InventoryMenu.SHIELD_SLOT;
	}

	private boolean openCraftingTable(Minecraft minecraft, LocalPlayer player, BlockPos pos) {
		if (minecraft.level == null || !minecraft.level.hasChunkAt(pos) || !minecraft.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
			return false;
		}
		if (!withinInteractionRange(player, pos)) {
			return false;
		}
		Vec3 hitVec = Vec3.atCenterOf(pos);
		cameraController.lookAt(minecraft, hitVec);
		BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, pos, false);
		InteractionResult result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
		if (result.consumesAction()) {
			player.swing(InteractionHand.MAIN_HAND);
			openedWorkbenchForTask = true;
		}
		return result.consumesAction();
	}

	private static int inventoryCount(LocalPlayer player, Item item) {
		int count = 0;
		for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
			ItemStack stack = player.getInventory().getItem(index);
			if (stack.is(item)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request) {
		cancelNavigationIfStarted();
		cancelPortableTablePlacement();
		closeOwnedWorkbenchIfSafe();
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, "crafted");
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, "crafted", null));
	}

	private void closeOwnedWorkbenchIfSafe() {
		if (!openedWorkbenchForTask) {
			return;
		}
		Minecraft minecraft = clientSupplier.get();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (player != null
			&& player.containerMenu instanceof CraftingMenu
			&& player.containerMenu.getCarried().isEmpty()) {
			ScreenCloseSafety.closeHandledScreen(player, "crafting_workbench_close");
		}
		openedWorkbenchForTask = false;
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, TaskFailure failure) {
		cancelNavigationIfStarted();
		cancelPortableTablePlacement();
		closeOwnedWorkbenchIfSafe();
		snapshot = snapshot(TaskExecutionState.FAILED, request, failure.detail());
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, failure.detail(), null, failure.code()));
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "Crafting", event, null, null);
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.task(), right.task());
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		reset();
	}

	@Override
	public void shutdown() {
		reset();
		portableTablePlacementExecutor.shutdown();
	}

	private void reset() {
		cancelNavigationIfStarted();
		cancelPortableTablePlacement();
		closeOwnedWorkbenchIfSafe();
		appliedTask = null;
		plan = null;
		craftingTablePlan = null;
		phase = CraftPhase.IDLE;
		progressTracker.reset();
		craftingTableProgressTracker.reset();
		waitTicks = 0;
		terminalEventEmitted = false;
		tableTarget = null;
		placedTablePos = null;
		navigationStarted = false;
		openedWorkbenchForTask = false;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private void cancelPortableTablePlacement() {
		portableTablePlacementExecutor.onWorldLeave();
		clearPortableTablePlacementState();
	}

	private void cancelNavigationIfStarted() {
		if (navigationStarted && baritoneFacade != null && baritoneFacade.isLoaded()) {
			baritoneFacade.cancel();
		}
		navigationStarted = false;
	}

	private enum CraftPhase {
		IDLE,
		CRAFTING_TABLE_INPUTS,
		CRAFTING_TABLE_WAITING_FOR_RESULT,
		CRAFTING_TABLE_WAITING_FOR_TAKE,
		NAVIGATING_TO_TABLE,
		PLACING_TABLE,
		OPENING_TABLE,
		WAITING_FOR_TABLE_SCREEN,
		PLACING_INPUTS,
		WAITING_FOR_RESULT,
		WAITING_FOR_TAKE
	}

	enum CraftingScreenDisposition {
		READY,
		CLOSE_OPEN_SCREEN,
		FAIL
	}

	enum TableNavigationOutcome {
		WAIT,
		OPEN_TABLE,
		FALLBACK
	}

	static WorkbenchSetupAction initialWorkbenchSetupAction(boolean nearbyTableAvailable, boolean portableTableAvailable) {
		return initialWorkbenchSetupAction(nearbyTableAvailable, false, portableTableAvailable);
	}

	static WorkbenchSetupAction initialWorkbenchSetupAction(
		boolean nearbyTableAvailable,
		boolean nearbyTableSubstantiallyBelowPlayer,
		boolean portableTableAvailable
	) {
		if (portableTableAvailable && nearbyTableSubstantiallyBelowPlayer) {
			return WorkbenchSetupAction.PLACE_PORTABLE_TABLE;
		}
		if (nearbyTableAvailable) {
			return WorkbenchSetupAction.REUSE_NEARBY_TABLE;
		}
		return portableTableAvailable
			? WorkbenchSetupAction.PLACE_PORTABLE_TABLE
			: WorkbenchSetupAction.CRAFT_PORTABLE_TABLE;
	}

	enum WorkbenchSetupAction {
		REUSE_NEARBY_TABLE,
		PLACE_PORTABLE_TABLE,
		CRAFT_PORTABLE_TABLE
	}

	static record CraftingPlan(
		RecipeDisplayId networkRecipeId,
		Item outputItem,
		int outputCount,
		int requestedTimes,
		int targetOutputCount,
		CraftingGridKind gridKind,
		java.util.List<CraftingOpportunityResolver.CraftingIngredientPlacement> placements,
		TaskFailure failure
	) {
		private static CraftingPlan failure(String reason, TaskFailureCode code) {
			return new CraftingPlan(null, null, 0, 0, 0, null, java.util.List.of(), TaskFailure.of(code, reason));
		}
	}

	static record RecipeFillRequest(int syncId, RecipeDisplayId networkRecipeId, boolean craftAll) {
	}

	private record WorkbenchReadiness(boolean ready, Optional<TaskFailure> failure) {
		private static WorkbenchReadiness readyState() {
			return new WorkbenchReadiness(true, Optional.empty());
		}

		private static WorkbenchReadiness notReadyState() {
			return new WorkbenchReadiness(false, Optional.empty());
		}

		private static WorkbenchReadiness failed(TaskFailure failure) {
			return new WorkbenchReadiness(false, Optional.of(failure));
		}
	}

	private enum CraftAdvanceStatus {
		RUNNING,
		COMPLETED,
		FAILED
	}

	private record CraftAdvanceResult(CraftAdvanceStatus status, TaskFailure failure) {
		private static CraftAdvanceResult running() {
			return new CraftAdvanceResult(CraftAdvanceStatus.RUNNING, null);
		}

		private static CraftAdvanceResult completed() {
			return new CraftAdvanceResult(CraftAdvanceStatus.COMPLETED, null);
		}

		private static CraftAdvanceResult failed(TaskFailure failure) {
			return new CraftAdvanceResult(CraftAdvanceStatus.FAILED, failure);
		}
	}

	private record TableTarget(BlockPos tablePos, GoalPosition standPosition) {
	}

	private record CraftingGridSpec(
		CraftingGridKind kind,
		int resultSlot,
		int firstInputSlot,
		int inputSlotCount,
		int inventoryStart,
		int hotbarStart,
		int hotbarEnd
	) {
		private static final CraftingGridSpec PLAYER = new CraftingGridSpec(
			CraftingGridKind.PLAYER_2X2,
			InventoryMenu.RESULT_SLOT,
			InventoryMenu.CRAFT_SLOT_START,
			InventoryMenu.CRAFT_SLOT_COUNT,
			InventoryMenu.INV_SLOT_START,
			InventoryMenu.USE_ROW_SLOT_START,
			InventoryMenu.USE_ROW_SLOT_END
		);
		private static final CraftingGridSpec WORKBENCH = new CraftingGridSpec(
			CraftingGridKind.WORKBENCH_3X3,
			CraftingMenu.RESULT_SLOT,
			1,
			9,
			10,
			37,
			46
		);

		private static CraftingGridSpec forKind(CraftingGridKind kind) {
			return kind == CraftingGridKind.WORKBENCH_3X3 ? WORKBENCH : PLAYER;
		}

		private boolean matches(AbstractContainerMenu menu) {
			if (kind == CraftingGridKind.WORKBENCH_3X3) {
				return menu instanceof CraftingMenu;
			}
			return menu instanceof InventoryMenu;
		}
	}
}
