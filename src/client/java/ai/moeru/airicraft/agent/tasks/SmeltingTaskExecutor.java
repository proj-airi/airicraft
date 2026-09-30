package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;

import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class SmeltingTaskExecutor implements WorldTaskExecutor {
	private static final double INTERACTION_RANGE_SQUARED = 20.25D;

	private final Supplier<Minecraft> clientSupplier;
	private final SmeltingProcessManager processManager;
	private final NavigationFacade baritoneFacade;
	private final PlacementSneakController placementSneakController = new PlacementSneakController();

	private WorldTaskRequest appliedTask;
	private boolean terminalEventEmitted;
	private boolean navigationStarted;
	private boolean openedStationForTask;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public SmeltingTaskExecutor() {
		this(Minecraft::getInstance, new SmeltingProcessManager(), null);
	}

	public SmeltingTaskExecutor(SmeltingProcessManager processManager) {
		this(Minecraft::getInstance, processManager, null);
	}

	public SmeltingTaskExecutor(SmeltingProcessManager processManager, NavigationFacade baritoneFacade) {
		this(Minecraft::getInstance, processManager, baritoneFacade);
	}

	SmeltingTaskExecutor(Supplier<Minecraft> clientSupplier, SmeltingProcessManager processManager, NavigationFacade baritoneFacade) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.processManager = Objects.requireNonNull(processManager, "processManager");
		this.baritoneFacade = baritoneFacade;
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || !isSmeltingType(activeTask.get().type())) {
			reset();
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}
		if (sessionSnapshot == null || !sessionSnapshot.companionActuationAllowed()) {
			placementSneakController.release(clientSupplier.get());
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}

		Minecraft minecraft = clientSupplier.get();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft == null || minecraft.level == null || minecraft.gameMode == null || player == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "world_unavailable"));
		}
		return request.type() == WorldTaskType.SMELT_ITEMS
			? tickSmeltItems(request, minecraft, player, sessionSnapshot.tickCount())
			: tickCollectSmeltedItems(request, minecraft, player);
	}

	private Optional<TaskTerminalEvent> tickSmeltItems(WorldTaskRequest request, Minecraft minecraft, LocalPlayer player, long tick) {
		SmeltingOption option = processManager.registeredOption(smeltArgs(request).optionId());
		if (option == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "option_not_found"));
		}
		if (option.stationCandidate().source() == SmeltingStationSource.OPEN_SCREEN
			&& player.containerMenu instanceof AbstractFurnaceMenu menu) {
			return insertSmeltingInputs(request, minecraft, player, menu, option, tick);
		}
		BlockPos stationPos = stationPos(option.stationObservation().key());
		if (stationPos == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "station_unavailable"));
		}
		StationReadiness stationReadiness = ensureStationReady(request, minecraft, player, option, stationPos);
		if (stationReadiness.failure() != null) {
			return fail(request, stationReadiness.failure());
		}
		if (!stationReadiness.ready()) {
			return Optional.empty();
		}
		if (!(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "furnace_screen_not_open"));
		}
		if (!menu.getCarried().isEmpty()) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "cursor_not_empty"));
		}
		option = processManager.registeredOption(smeltArgs(request).optionId());
		if (option == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "option_not_found"));
		}
		return insertSmeltingInputs(request, minecraft, player, menu, option, tick);
	}

	private Optional<TaskTerminalEvent> insertSmeltingInputs(
		WorldTaskRequest request,
		Minecraft minecraft,
		LocalPlayer player,
		AbstractFurnaceMenu menu,
		SmeltingOption option,
		long tick
	) {
		SmeltItemsStepArgs args = smeltArgs(request);
		if (!menu.getCarried().isEmpty()) return fail(request, TaskFailure.of(TaskFailureCode.BUSY, "cursor_not_empty"));
		// A previous batch can block this recipe, or be mistaken for this batch's completed output.
		// The station has already passed startProcess's occupied-station authorization.
		if (!menu.getSlot(2).getItem().isEmpty()) {
			minecraft.gameMode.handleInventoryMouseClick(menu.containerId, 2, 0, ClickType.QUICK_MOVE, player);
			if (!menu.getSlot(2).getItem().isEmpty())
				return fail(request, TaskFailure.of(TaskFailureCode.BUSY, "inventory_full blocking_furnace_output=" + itemId(menu.getSlot(2).getItem())));
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "cleared_previous_output");
			return Optional.empty();
		}
		int reservedInput = remainingItemsToMove(itemId(menu.getSlot(0).getItem()),
			menu.getSlot(0).getItem().getCount(), option.inputItemId(), args.inputQuantity());
		if (reservedInput < 0 || sourceItemCount(menu, option.inputItemId()) < reservedInput) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "insufficient_input"));
		}
		FuelSelection fuel = fuelSelection(minecraft, menu, option, args, reservedInput).orElse(null);
		if (fuel == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "insufficient_fuel"));
		}
		if (fuel.quantity() > 0 && !moveItemsToSlot(minecraft, player, menu, fuel.itemId(), 1, fuel.quantity())) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "insufficient_fuel"));
		}
		if (!moveItemsToSlot(minecraft, player, menu, option.inputItemId(), 0, args.inputQuantity())) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "insufficient_input"));
		}
		processManager.updateProcessFingerprint(
			option.optionId(),
			option.stationObservation().key(),
			screenSlotSnapshot(menu),
			tick
		);
		return complete(request, "smelting_started");
	}

	private Optional<TaskTerminalEvent> tickCollectSmeltedItems(WorldTaskRequest request, Minecraft minecraft, LocalPlayer player) {
		CollectSmeltedItemsStepArgs args = collectArgs(request);
		String processId = args.processId() == null ? processManager.preferredCollectionProcessId() : args.processId();
		SmeltingStationKey key = processId == null
			? processManager.confirmedCollectionStationKey(args.confirmationToken())
			: processManager.processStationKey(processId);
		if (canCollectFromCurrentScreen(key, minecraft.level.dimension().location().toString(),
			player.containerMenu.containerId, player.containerMenu instanceof AbstractFurnaceMenu)) {
			openedStationForTask = true;
		} else {
			BlockPos stationPos = stationPos(key);
			if (stationPos == null) return fail(request, TaskFailure.of(TaskFailureCode.ENVIRONMENT_CHANGED, "confirmed_furnace_screen_unavailable"));
			StationReadiness stationReadiness = ensureExistingStationOpen(request, minecraft, player, stationPos);
			if (stationReadiness.failure() != null) return fail(request, stationReadiness.failure());
			if (!stationReadiness.ready()) return Optional.empty();
		}
		if (!(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "furnace_screen_not_open"));
		}
		SmeltingSlotSnapshot slots = screenSlotSnapshot(menu);
		String expectedOutput = null;
		for (SmeltingProcessSnapshot process : processManager.processSnapshots()) {
			if (process.processId().equals(processId)) expectedOutput = process.outputItemId();
		}
		if (expectedOutput != null && slots.outputCount() > 0 && !expectedOutput.equals(slots.outputItemId())) {
			if (!menu.getCarried().isEmpty()) return fail(request, TaskFailure.of(TaskFailureCode.BUSY, "cursor_not_empty"));
			// Recover an already-loaded batch without claiming its requested output was collected.
			minecraft.gameMode.handleInventoryMouseClick(menu.containerId, 2, 0, ClickType.QUICK_MOVE, player);
			if (!menu.getSlot(2).getItem().isEmpty())
				return fail(request, TaskFailure.of(TaskFailureCode.BUSY, "inventory_full blocking_furnace_output=" + slots.outputItemId()));
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "cleared_blocking_output");
			return Optional.empty();
		}
		if (menu.getSlot(2).getItem().isEmpty()
			|| processId != null && !processManager.processOutputReadyForCollection(processId, slots)) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "waiting_for_output");
			return Optional.empty();
		}
		if (!menu.getCarried().isEmpty()) return fail(request, TaskFailure.of(TaskFailureCode.BUSY, "cursor_not_empty"));
		minecraft.gameMode.handleInventoryMouseClick(menu.containerId, 2, 0, ClickType.QUICK_MOVE, player);
		if (!menu.getSlot(2).getItem().isEmpty())
			return fail(request, TaskFailure.of(TaskFailureCode.BUSY, "inventory_full remainingOutput=" + menu.getSlot(2).getItem().getCount()));
		if (processId != null) {
			processManager.markCollected(processId);
		}
		return complete(request, "smelting_collected");
	}

	private StationReadiness ensureStationReady(WorldTaskRequest request, Minecraft minecraft, LocalPlayer player, SmeltingOption option, BlockPos stationPos) {
		if (option.stationCandidate().source() == SmeltingStationSource.PLACE_FROM_INVENTORY && !isFurnaceBlock(minecraft, stationPos)) {
			if (!canPlaceAt(minecraft, stationPos)) {
				Optional<BlockPos> fallback = chooseFurnacePlacement(minecraft, player);
				if (fallback.isPresent()) {
					BlockPos fallbackPos = fallback.get();
					SmeltingStationKey oldKey = option.stationObservation().key();
					SmeltingStationKey newKey = new SmeltingStationKey(oldKey.dimensionId(), fallbackPos.getX(), fallbackPos.getY(), fallbackPos.getZ());
					processManager.relocatePlacementProcess(option.optionId(), oldKey, newKey, player.distanceToSqr(Vec3.atCenterOf(fallbackPos)));
					stationPos = fallbackPos;
				}
				else {
					return StationReadiness.failed(TaskFailure.of(TaskFailureCode.UNKNOWN, "furnace_placement_blocked"));
				}
			}
			if (!withinInteractionRange(player, stationPos)) {
				return navigateOrFail(request, stationPos);
			}
			PlacementAttempt placement = placeFurnace(minecraft, player, stationPos);
			if (!placement.placed()) {
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "placing_furnace:" + placement.reason());
				return StationReadiness.notReady();
			}
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "waiting_for_furnace");
			return StationReadiness.notReady();
		}
		return ensureExistingStationOpen(request, minecraft, player, stationPos);
	}

	private StationReadiness ensureExistingStationOpen(WorldTaskRequest request, Minecraft minecraft, LocalPlayer player, BlockPos stationPos) {
		if (player.containerMenu instanceof AbstractFurnaceMenu) {
			if (!openedStationForTask) {
				if (player.containerMenu.getCarried().isEmpty()) {
					ScreenCloseSafety.closeHandledScreen(player, "smelting_existing_station_close");
				}
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "closing_existing_furnace_screen");
				return StationReadiness.notReady();
			}
			return StationReadiness.readyState();
		}
		if (!isFurnaceBlock(minecraft, stationPos)) {
			return StationReadiness.failed(TaskFailure.of(TaskFailureCode.UNKNOWN, "station_unavailable"));
		}
		if (!withinInteractionRange(player, stationPos)) {
			return navigateOrFail(request, stationPos);
		}
		BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(stationPos), Direction.UP, stationPos, false);
		InteractionResult result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
		if (result.consumesAction()) {
			player.swing(InteractionHand.MAIN_HAND);
			openedStationForTask = true;
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "opening_furnace");
		return StationReadiness.notReady();
	}

	private StationReadiness navigateOrFail(WorldTaskRequest request, BlockPos stationPos) {
		if (baritoneFacade == null || !baritoneFacade.isLoaded()) {
			return StationReadiness.failed(TaskFailure.of(TaskFailureCode.UNKNOWN, "station_out_of_range"));
		}
		if (!navigationStarted) {
			baritoneFacade.startNavigateNear(new GoalPosition(stationPos.getX(), stationPos.getY(), stationPos.getZ(), false), 3);
			navigationStarted = true;
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "navigating_to_furnace");
		return StationReadiness.notReady();
	}

	private Optional<FuelSelection> fuelSelection(
		Minecraft minecraft,
		AbstractContainerMenu menu,
		SmeltingOption option,
		SmeltItemsStepArgs args,
		int reservedInput
	) {
		int requiredFuelTicks = args.inputQuantity() * option.cookTimeTicks();
		if (args.fuelMode() == SmeltingFuelMode.MANUAL) {
			return manualFuelSelection(minecraft, menu, args, requiredFuelTicks, option.inputItemId(), reservedInput);
		}
		ItemStack existingFuel = menu.getSlot(1).getItem();
		if (existingFuel.isEmpty() && furnaceBurning(menu)) {
			return Optional.of(new FuelSelection(null, 0));
		}
		if (!existingFuel.isEmpty()) {
			if (!minecraft.level.fuelValues().isFuel(existingFuel)) {
				return Optional.empty();
			}
			int needed = fuelItemsNeeded(requiredFuelTicks, minecraft.level.fuelValues().burnDuration(existingFuel));
			String existingFuelItemId = itemId(existingFuel);
			if (existingFuel.getCount() >= needed) {
				return Optional.of(new FuelSelection(null, 0));
			}
			if (existingFuel.getCount() + fuelCountAfterReservingInput(existingFuelItemId,
				sourceItemCount(menu, existingFuelItemId), option.inputItemId(), reservedInput) >= needed) {
				return Optional.of(new FuelSelection(existingFuelItemId, needed));
			}
			return Optional.empty();
		}
		Map<String, Integer> availableFuelCounts = new LinkedHashMap<>();
		Map<String, Integer> fuelTicksByItemId = new LinkedHashMap<>();
		for (int slot = 3; slot < menu.slots.size(); slot++) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (stack.isEmpty() || !minecraft.level.fuelValues().isFuel(stack)) {
				continue;
			}
			String itemId = itemId(stack);
			availableFuelCounts.merge(itemId, stack.getCount(), Integer::sum);
			fuelTicksByItemId.putIfAbsent(itemId, minecraft.level.fuelValues().burnDuration(stack));
		}
		return selectFuel(availableFuelCounts, fuelTicksByItemId, option.inputItemId(), reservedInput, requiredFuelTicks);
	}

	static Optional<FuelSelection> selectFuel(Map<String, Integer> availableFuelCounts,
		Map<String, Integer> fuelTicksByItemId, String inputItemId, int reservedInput, int requiredFuelTicks) {
		FuelSelection best = null;
		for (Map.Entry<String, Integer> entry : new java.util.TreeMap<>(availableFuelCounts).entrySet()) {
			int needed = fuelItemsNeeded(requiredFuelTicks, fuelTicksByItemId.getOrDefault(entry.getKey(), 0));
			if (needed > 0 && fuelCountAfterReservingInput(entry.getKey(), entry.getValue(), inputItemId, reservedInput) >= needed) {
				if (best == null || SmeltingFuelCost.compare(entry.getKey(), needed, fuelTicksByItemId.get(entry.getKey()),
					best.itemId(), best.quantity(), fuelTicksByItemId.get(best.itemId())) < 0) {
					best = new FuelSelection(entry.getKey(), needed);
				}
			}
		}
		return Optional.ofNullable(best);
	}

	private static Optional<FuelSelection> manualFuelSelection(
		Minecraft minecraft,
		AbstractContainerMenu menu,
		SmeltItemsStepArgs args,
		int requiredFuelTicks,
		String inputItemId,
		int reservedInput
	) {
		if (minecraft == null || minecraft.level == null || args.fuelItemId() == null || args.fuelItemId().isBlank()) {
			return Optional.empty();
		}
		String requestedFuelItemId = args.fuelItemId();
		int matchingFuelCount = 0;
		int fuelTicksPerItem = 0;

		ItemStack existingFuel = menu.getSlot(1).getItem();
		if (!existingFuel.isEmpty()) {
			if (!requestedFuelItemId.equals(itemId(existingFuel)) || !minecraft.level.fuelValues().isFuel(existingFuel)) {
				return Optional.empty();
			}
			matchingFuelCount += existingFuel.getCount();
			fuelTicksPerItem = minecraft.level.fuelValues().burnDuration(existingFuel);
		}

		for (int slot = 3; slot < menu.slots.size(); slot++) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (stack.isEmpty() || !requestedFuelItemId.equals(itemId(stack))) {
				continue;
			}
			if (!minecraft.level.fuelValues().isFuel(stack)) {
				return Optional.empty();
			}
			matchingFuelCount += stack.getCount();
			if (fuelTicksPerItem <= 0) {
				fuelTicksPerItem = minecraft.level.fuelValues().burnDuration(stack);
			}
		}

		if (!fuelQuantityCoversCookTime(1, requiredFuelTicks, fuelTicksPerItem, args.fuelQuantity())) {
			return Optional.empty();
		}
		return fuelCountAfterReservingInput(requestedFuelItemId, matchingFuelCount, inputItemId, reservedInput) >= args.fuelQuantity()
			? Optional.of(new FuelSelection(requestedFuelItemId, args.fuelQuantity()))
			: Optional.empty();
	}

	static int fuelCountAfterReservingInput(String fuelItemId, int fuelCount, String inputItemId, int reservedInput) {
		return fuelItemId.equals(inputItemId) ? Math.max(0, fuelCount - reservedInput) : fuelCount;
	}

	static int fuelItemsNeeded(int requiredFuelTicks, int fuelTicksPerItem) {
		if (requiredFuelTicks <= 0) {
			return 0;
		}
		if (fuelTicksPerItem <= 0) {
			return Integer.MAX_VALUE;
		}
		return (requiredFuelTicks + fuelTicksPerItem - 1) / fuelTicksPerItem;
	}

	static boolean fuelQuantityCoversCookTime(int inputQuantity, int cookTimeTicks, int fuelTicksPerItem, int fuelQuantity) {
		if (inputQuantity <= 0 || cookTimeTicks <= 0 || fuelQuantity <= 0) {
			return false;
		}
		return fuelItemsNeeded(inputQuantity * cookTimeTicks, fuelTicksPerItem) <= fuelQuantity;
	}

	private static boolean furnaceBurning(AbstractContainerMenu menu) {
		return menu instanceof AbstractFurnaceMenu furnaceMenu && furnaceMenu.isLit();
	}

	private static boolean moveItemsToSlot(Minecraft minecraft, LocalPlayer player, AbstractContainerMenu menu, String itemId, int targetSlot, int quantity) {
		ItemStack targetStack = menu.getSlot(targetSlot).getItem();
		int remaining = remainingItemsToMove(itemId(targetStack), targetStack.getCount(), itemId, quantity);
		if (remaining < 0) {
			return false;
		}
		while (remaining > 0) {
			int sourceSlot = findSourceSlot(menu, itemId);
			if (sourceSlot < 0) {
				return false;
			}
			ItemStack sourceStack = menu.getSlot(sourceSlot).getItem();
			int moved = moveFromSourceToTarget(minecraft, player, menu, sourceSlot, targetSlot, Math.min(remaining, sourceStack.getCount()));
			if (moved <= 0) {
				return false;
			}
			remaining -= moved;
		}
		return true;
	}

	static int remainingItemsToMove(String currentItemId, int currentCount, String desiredItemId, int desiredQuantity) {
		if (desiredQuantity <= 0) {
			return 0;
		}
		if (currentItemId == null || currentCount <= 0) {
			return desiredQuantity;
		}
		if (!currentItemId.equals(desiredItemId)) {
			return -1;
		}
		return Math.max(0, desiredQuantity - currentCount);
	}

	private static int sourceItemCount(AbstractContainerMenu menu, String itemId) {
		int count = 0;
		for (int slot = 3; slot < menu.slots.size(); slot++) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static int moveFromSourceToTarget(
		Minecraft minecraft,
		LocalPlayer player,
		AbstractContainerMenu menu,
		int sourceSlot,
		int targetSlot,
		int maxQuantity
	) {
		if (!menu.getCarried().isEmpty()) {
			return 0;
		}
		ItemStack targetStack = menu.getSlot(targetSlot).getItem();
		ItemStack sourceStack = menu.getSlot(sourceSlot).getItem();
		int targetCountBefore = targetStack.isEmpty() ? 0 : targetStack.getCount();
		if (sourceStack.isEmpty()) {
			return 0;
		}
		if (!targetStack.isEmpty() && !ItemStack.isSameItemSameComponents(targetStack, sourceStack)) {
			return 0;
		}
		int targetSpace = targetStack.isEmpty()
			? Math.min(sourceStack.getMaxStackSize(), 64)
			: Math.max(0, targetStack.getMaxStackSize() - targetStack.getCount());
		int toMove = Math.min(maxQuantity, targetSpace);
		if (toMove <= 0) {
			return 0;
		}
		minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot, 0, ClickType.PICKUP, player);
		for (int index = 0; index < toMove; index++) {
			minecraft.gameMode.handleInventoryMouseClick(menu.containerId, targetSlot, 1, ClickType.PICKUP, player);
		}
		if (!menu.getCarried().isEmpty()) {
			minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot, 0, ClickType.PICKUP, player);
		}
		ItemStack targetAfter = menu.getSlot(targetSlot).getItem();
		int targetCountAfter = targetAfter.isEmpty() ? 0 : targetAfter.getCount();
		return Math.max(0, targetCountAfter - targetCountBefore);
	}

	private static int findSourceSlot(AbstractContainerMenu menu, String itemId) {
		for (int slot = 3; slot < menu.slots.size(); slot++) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
				return slot;
			}
		}
		return -1;
	}

	private static boolean isFurnaceBlock(Minecraft minecraft, BlockPos pos) {
		if (minecraft == null || minecraft.level == null || pos == null || !minecraft.level.hasChunkAt(pos)) {
			return false;
		}
		BlockState state = minecraft.level.getBlockState(pos);
		return state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER);
	}

	private PlacementAttempt placeFurnace(Minecraft minecraft, LocalPlayer player, BlockPos pos) {
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
			return new PlacementAttempt(false, "inventory_not_ready");
		}
		InteractionHand hand = selectFurnacePlacementHand(minecraft, player);
		if (hand == null) {
			return new PlacementAttempt(false, "furnace_not_selectable");
		}
		PlacementSneakController.Preparation sneakPreparation = placementSneakController.prepare(minecraft, player);
		if (sneakPreparation != PlacementSneakController.Preparation.READY) {
			return new PlacementAttempt(false, switch (sneakPreparation) {
				case PRESS_AND_WAIT -> "preparing_sneak";
				case WAITING -> "waiting_for_sneak";
				case READY -> throw new IllegalStateException("ready placement handled above");
			});
		}
		BlockPos support = pos.below();
		BlockHitResult hitResult = new BlockHitResult(
			new Vec3(support.getX() + 0.5D, support.getY() + 1.0D, support.getZ() + 0.5D),
			Direction.UP,
			support,
			false
		);
		InteractionResult result;
		try {
			result = minecraft.gameMode.useItemOn(player, hand, hitResult);
			if (result.consumesAction()) {
				player.swing(hand);
			}
		}
		finally {
			placementSneakController.release(minecraft);
		}
		return new PlacementAttempt(result.consumesAction(), result.consumesAction() ? "accepted" : "interact_" + result);
	}

	private static InteractionHand selectFurnacePlacementHand(Minecraft minecraft, LocalPlayer player) {
		if (player.getOffhandItem().is(Items.FURNACE)) {
			return InteractionHand.OFF_HAND;
		}
		return selectHotbarItem(minecraft, player, Items.FURNACE) ? InteractionHand.MAIN_HAND : null;
	}

	private static boolean selectHotbarItem(Minecraft minecraft, LocalPlayer player, Item item) {
		AbstractContainerMenu menu = player.containerMenu;
		int sourceSlot = findInventorySlot(menu, item);
		if (sourceSlot < 0) {
			return false;
		}
		int selectedHotbarSlot = player.getInventory().getSelectedSlot();
		if (sourceSlot >= InventoryMenu.USE_ROW_SLOT_START && sourceSlot < InventoryMenu.USE_ROW_SLOT_END) {
			selectAndSyncHotbarSlot(minecraft, player, sourceSlot - InventoryMenu.USE_ROW_SLOT_START);
			return true;
		}
		minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot, selectedHotbarSlot, ClickType.SWAP, player);
		selectAndSyncHotbarSlot(minecraft, player, selectedHotbarSlot);
		ItemStack selected = player.getInventory().getSelectedItem();
		return !selected.isEmpty() && selected.is(item);
	}

	private static void selectAndSyncHotbarSlot(Minecraft minecraft, LocalPlayer player, int hotbarSlot) {
		player.getInventory().setSelectedSlot(hotbarSlot);
		if (minecraft.getConnection() != null) {
			minecraft.getConnection().send(new ServerboundSetCarriedItemPacket(hotbarSlot));
		}
	}

	private static int findInventorySlot(AbstractContainerMenu menu, Item item) {
		if (!(menu instanceof InventoryMenu)) {
			return -1;
		}
		ItemStack offhand = menu.getSlot(InventoryMenu.SHIELD_SLOT).getItem();
		if (!offhand.isEmpty() && offhand.is(item)) {
			return InventoryMenu.SHIELD_SLOT;
		}
		for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_END; slot++) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (!stack.isEmpty() && stack.is(item)) {
				return slot;
			}
		}
		return -1;
	}

	private static Optional<BlockPos> chooseFurnacePlacement(Minecraft minecraft, LocalPlayer player) {
		BlockPos origin = player.blockPosition();
		for (BlockPos candidate : SmeltingPlannerService.furnacePlacementCandidatePositions(origin)) {
			if (canPlaceAt(minecraft, candidate)) {
				return Optional.of(candidate.immutable());
			}
		}
		return Optional.empty();
	}

	private static boolean canPlaceAt(Minecraft minecraft, BlockPos pos) {
		if (minecraft == null || minecraft.level == null || !minecraft.level.hasChunkAt(pos) || !minecraft.level.hasChunkAt(pos.below())) {
			return false;
		}
		if (WorldPlacePreservation.contains(minecraft.level, pos)) return false;
		BlockState target = minecraft.level.getBlockState(pos);
		BlockState support = minecraft.level.getBlockState(pos.below());
		return (target.isAir() || target.canBeReplaced())
			&& support.isFaceSturdy(minecraft.level, pos.below(), Direction.UP)
			&& minecraft.level.isUnobstructed(Blocks.FURNACE.defaultBlockState(), pos, CollisionContext.placementContext(minecraft.player));
	}

	private static boolean withinInteractionRange(LocalPlayer player, BlockPos pos) {
		return player.distanceToSqr(Vec3.atCenterOf(pos)) <= INTERACTION_RANGE_SQUARED;
	}

	static boolean canCollectFromCurrentScreen(SmeltingStationKey key, String dimension, int syncId, boolean furnaceScreen) {
		return key != null && furnaceScreen && key.dimensionId().equals(dimension + "#open_screen") && key.x() == syncId;
	}

	private static BlockPos stationPos(SmeltingStationKey key) {
		if (key == null || key.dimensionId().contains("#open_screen")) {
			return null;
		}
		return new BlockPos(key.x(), key.y(), key.z());
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		placementSneakController.release(clientSupplier.get());
		cancelNavigationIfStarted();
		closeOpenedStationIfSafe();
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, TaskTerminationCause.GOAL_REACHED));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, TaskFailure failure) {
		placementSneakController.release(clientSupplier.get());
		cancelNavigationIfStarted();
		closeOpenedStationIfSafe();
		if (request.type() == WorldTaskType.SMELT_ITEMS) {
			processManager.cancelProcessesForOption(smeltArgs(request).optionId());
		}
		snapshot = snapshot(TaskExecutionState.FAILED, request, failure.detail());
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, failure.detail(), null, failure.code()));
	}

	private void closeOpenedStationIfSafe() {
		if (!openedStationForTask) {
			return;
		}
		Minecraft minecraft = clientSupplier.get();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (player != null
			&& player.containerMenu instanceof AbstractFurnaceMenu
			&& player.containerMenu.getCarried().isEmpty()) {
			ScreenCloseSafety.closeHandledScreen(player, "smelting_station_close");
		}
		openedStationForTask = false;
	}

	private void cancelNavigationIfStarted() {
		if (navigationStarted && baritoneFacade != null && baritoneFacade.isLoaded()) {
			baritoneFacade.cancel();
		}
		navigationStarted = false;
	}

	private static boolean isSmeltingType(WorldTaskType type) {
		return type == WorldTaskType.SMELT_ITEMS || type == WorldTaskType.COLLECT_SMELTED_ITEMS;
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null || left.type() != right.type()) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.task(), right.task());
	}

	private static SmeltItemsStepArgs smeltArgs(WorldTaskRequest request) {
		return ((WorldTaskRequest.SmeltItems) request.task()).args();
	}

	private static CollectSmeltedItemsStepArgs collectArgs(WorldTaskRequest request) {
		return ((WorldTaskRequest.CollectSmeltedItems) request.task()).args();
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "Smelting", event, null, null);
	}

	private static String itemId(ItemStack stack) {
		return stack == null || stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static SmeltingSlotSnapshot screenSlotSnapshot(AbstractFurnaceMenu menu) {
		return new SmeltingSlotSnapshot(
			itemId(menu.getSlot(0).getItem()),
			menu.getSlot(0).getItem().getCount(),
			itemId(menu.getSlot(1).getItem()),
			menu.getSlot(1).getItem().getCount(),
			itemId(menu.getSlot(2).getItem()),
			menu.getSlot(2).getItem().getCount(),
			0,
			200,
			menu.isLit()
		);
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
	}

	private void reset() {
		placementSneakController.release(clientSupplier.get());
		cancelNavigationIfStarted();
		closeOpenedStationIfSafe();
		appliedTask = null;
		terminalEventEmitted = false;
		openedStationForTask = false;
		snapshot = TaskExecutionSnapshot.idle();
	}

	record FuelSelection(String itemId, int quantity) {
	}

	private record PlacementAttempt(boolean placed, String reason) {
	}

	private record StationReadiness(boolean ready, TaskFailure failure) {
		private static StationReadiness readyState() {
			return new StationReadiness(true, null);
		}

		private static StationReadiness notReady() {
			return new StationReadiness(false, null);
		}

		private static StationReadiness failed(TaskFailure failure) {
			return new StationReadiness(false, failure);
		}
	}
}
