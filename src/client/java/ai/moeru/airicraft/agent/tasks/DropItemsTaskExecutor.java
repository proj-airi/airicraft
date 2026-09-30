package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.control.Priority;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class DropItemsTaskExecutor implements WorldTaskExecutor {
	private static final int BUSY_SCREEN_TIMEOUT_TICKS = 100;
	private static final String INVENTORY_BUSY = "inventory_busy";
	private static final String INVENTORY_BUSY_SCREEN = "inventory_busy_screen";
	private static final String INVENTORY_SCREEN_DISMISSED = "inventory_screen_dismissed";

	private final Supplier<Minecraft> clientSupplier;
	private final NavigationFacade navigationFacade;
	private final CameraController cameraController;
	private final MovementController movementController = new MovementController("drop_items", Priority.FOREGROUND);

	private WorldTaskRequest appliedTask;
	private int busyScreenTicks;
	private boolean terminalEventEmitted;
	private UUID targetUuid;
	private Map<PlayerItemDeliveryPolicy.EntityGeneration, Integer> baselineItemEntityCounts = Map.of();
	private final List<PlayerItemDeliveryPolicy.PickupEvidence> pendingPickupEvidence = new ArrayList<>();
	private PlayerItemDeliveryPolicy.State deliveryState;
	private GoalPosition chaseGoal;
	private int chaseGoalRefreshTicks;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public DropItemsTaskExecutor() {
		this(Minecraft::getInstance, null, new CameraController());
	}

	public DropItemsTaskExecutor(NavigationFacade navigationFacade) {
		this(Minecraft::getInstance, navigationFacade, new CameraController());
	}

	public DropItemsTaskExecutor(NavigationFacade navigationFacade, CameraController cameraController) {
		this(Minecraft::getInstance, navigationFacade, cameraController);
	}

	DropItemsTaskExecutor(Supplier<Minecraft> clientSupplier) {
		this(clientSupplier, null, new CameraController());
	}

	DropItemsTaskExecutor(Supplier<Minecraft> clientSupplier, NavigationFacade navigationFacade, CameraController cameraController) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.navigationFacade = navigationFacade;
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.DROP_ITEMS) {
			cancelApproach();
			reset();
			return Optional.empty();
		}

		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			cancelApproach();
			reset();
			appliedTask = request;
			DropItemsStepArgs args = ((WorldTaskRequest.DropItems) request.task()).args();
			deliveryState = args.targetPlayer() == null
				? null
				: PlayerItemDeliveryPolicy.initial(args.itemId(), args.quantity());
		}

		if (!itemDropActuationAllowed(sessionSnapshot)) {
			stopApproach(clientSupplier.get());
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}

		Minecraft minecraft = clientSupplier.get();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft == null || minecraft.gameMode == null || player == null || minecraft.level == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "world_unavailable"));
		}
		if (dismissCurrentScreenIfSafe(minecraft, player)) {
			busyScreenTicks = 0;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, INVENTORY_SCREEN_DISMISSED);
			return Optional.empty();
		}
		Optional<String> readinessFailure = readinessFailure(minecraft, player);
		if (readinessFailure.isPresent()) {
			if (INVENTORY_BUSY_SCREEN.equals(readinessFailure.get()) && shouldWaitForBusyScreen(busyScreenTicks)) {
				busyScreenTicks++;
				snapshot = snapshot(TaskExecutionState.RUNNING, request, INVENTORY_BUSY);
				return Optional.empty();
			}
			return fail(request, TaskFailure.of(TaskFailureCode.BUSY, INVENTORY_BUSY));
		}
		busyScreenTicks = 0;

		DropItemsStepArgs args = ((WorldTaskRequest.DropItems) request.task()).args();
		AbstractContainerMenu menu = player.containerMenu;
		List<DropSlot> matchingSlots = matchingSlots(menu, args.itemId());
		int available = matchingSlots.stream().mapToInt(DropSlot::count).sum();
		if (args.targetPlayer() != null) {
			return tickDelivery(minecraft, player, request, args, available, sessionSnapshot.tickCount());
		}
		if (available < args.quantity()) {
			return fail(request, available == 0
				? TaskFailure.of(TaskFailureCode.MISSING_FACT, "item_not_found")
				: TaskFailure.of(TaskFailureCode.UNKNOWN, "insufficient_items"));
		}

		for (DropClick click : planDropClicks(matchingSlots, args.quantity())) {
			performDropClick(minecraft, player, menu, click);
		}
		return complete(request);
	}

	private Optional<TaskTerminalEvent> tickDelivery(
		Minecraft minecraft,
		LocalPlayer player,
		WorldTaskRequest request,
		DropItemsStepArgs args,
		int available,
		long tick
	) {
		Optional<AbstractClientPlayer> target = findTarget(minecraft, args.targetPlayer());
		Optional<PlayerItemDeliveryPolicy.TargetObservation> targetObservation = target.map(this::observeTarget);
		PlayerItemDeliveryPolicy.Observation observation = new PlayerItemDeliveryPolicy.Observation(
			player.getX(),
			player.getY(),
			player.getZ(),
			available,
			targetObservation,
			observeDroppedItems(minecraft, args),
			List.copyOf(pendingPickupEvidence)
		);
		PlayerItemDeliveryPolicy.Decision decision = PlayerItemDeliveryPolicy.decide(deliveryState, observation);
		pendingPickupEvidence.clear();
		if (decision.command() == PlayerItemDeliveryPolicy.Command.DROP && target.isPresent()) {
			cameraController.lookAt(minecraft, targetAimPoint(target.get()));
			if (!cameraController.isLookingAt(minecraft, targetAimPoint(target.get()), 5.0F)) {
				stopApproach(minecraft);
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "aiming_at_recipient");
				return Optional.empty();
			}
		}
		deliveryState = decision.nextState();
		return switch (decision.command()) {
			case APPROACH -> {
				if (target.isEmpty()) {
					yield fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_not_found"));
				}
				yield approachTarget(minecraft, target.get(), request, decision.reason(), tick);
			}
			case AIM -> {
				stopApproach(minecraft);
				target.ifPresent(value -> cameraController.lookAt(minecraft, targetAimPoint(value)));
				snapshot = snapshot(TaskExecutionState.RUNNING, request, decision.reason());
				yield Optional.empty();
			}
			case DROP -> {
				stopApproach(minecraft);
				target.ifPresent(value -> cameraController.lookAt(minecraft, targetAimPoint(value)));
				baselineItemEntityCounts = itemEntityCounts(minecraft, args.itemId());
				AbstractContainerMenu menu = player.containerMenu;
				for (DropClick click : planDropClicks(matchingSlots(menu, args.itemId()), args.quantity())) {
					performDropClick(minecraft, player, menu, click);
				}
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "dropped_items_waiting_for_delivery");
				yield Optional.empty();
			}
			case WAIT -> {
				target.ifPresent(value -> cameraController.lookAt(minecraft, targetAimPoint(value)));
				snapshot = snapshot(TaskExecutionState.RUNNING, request, decision.reason());
				yield Optional.empty();
			}
			case SUCCEED -> complete(request, decision.reason());
			case FAIL -> fail(request, TaskFailure.of(decision.failureCode(), decision.reason()));
		};
	}

	private Optional<TaskTerminalEvent> approachTarget(
		Minecraft minecraft,
		AbstractClientPlayer target,
		WorldTaskRequest request,
		String reason,
		long tick
	) {
		double distance = minecraft.player.distanceTo(target);
		if (distance <= 10.0D && !movementController.snapshot().stuck()) {
			cameraController.lookAt(minecraft, targetAimPoint(target));
			cancelNavigationChase();
			movementController.moveForward(minecraft, true, false, tick);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, reason + " direct_chase");
			return Optional.empty();
		}
		if (navigationFacade != null && navigationFacade.isLoaded()) {
			movementController.stop(minecraft);
			Optional<String> pathEvent = navigationFacade.pollPathEvent();
			if (pathEvent.map(event -> "CALC_FAILED".equalsIgnoreCase(event.trim())).orElse(false)) {
				return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_unreachable"));
			}
			GoalPosition nextGoal = new GoalPosition(target.blockPosition().getX(), target.blockPosition().getY(), target.blockPosition().getZ(), false);
			if (chaseGoal == null || !chaseGoal.equals(nextGoal) || chaseGoalRefreshTicks >= 10) {
				navigationFacade.startNavigateNear(nextGoal, 2);
				chaseGoal = nextGoal;
				chaseGoalRefreshTicks = 0;
			}
			else {
				chaseGoalRefreshTicks++;
			}
			snapshot = snapshot(TaskExecutionState.RUNNING, request, reason + " navigation_chase");
			return Optional.empty();
		}
		movementController.moveForward(minecraft, true, false, tick);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, reason + " direct_chase");
		return Optional.empty();
	}

	private Optional<AbstractClientPlayer> findTarget(Minecraft minecraft, String targetName) {
		for (AbstractClientPlayer candidate : minecraft.level.players()) {
			if (!candidate.getName().getString().equals(targetName) || !candidate.isAlive()) {
				continue;
			}
			if (targetUuid == null) {
				targetUuid = candidate.getUUID();
			}
			return Optional.of(candidate);
		}
		return Optional.empty();
	}

	private PlayerItemDeliveryPolicy.TargetObservation observeTarget(AbstractClientPlayer target) {
		return new PlayerItemDeliveryPolicy.TargetObservation(
			target.getUUID(),
			target.getName().getString(),
			target.getX(),
			target.getY(),
			target.getZ()
		);
	}

	private List<PlayerItemDeliveryPolicy.DroppedItemEvidence> observeDroppedItems(
		Minecraft minecraft,
		DropItemsStepArgs args
	) {
		if (deliveryState == null || deliveryState.phase() != PlayerItemDeliveryPolicy.Phase.AWAIT_DELIVERY) {
			return List.of();
		}
		AABB area = minecraft.player.getBoundingBox().inflate(64.0D);
		ArrayList<PlayerItemDeliveryPolicy.DroppedItemEvidence> evidence = new ArrayList<>();
		for (ItemEntity itemEntity : minecraft.level.getEntitiesOfClass(ItemEntity.class, area, value -> true)) {
			ItemStack stack = itemEntity.getItem();
			String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			if (!args.itemId().equals(itemId)) {
				continue;
			}
			int droppedCount = agentAttributedQuantity(
				new PlayerItemDeliveryPolicy.EntityGeneration(itemEntity.getId(), itemEntity.getUUID()),
				stack.getCount(),
				baselineItemEntityCounts
			);
			if (droppedCount > 0) {
				evidence.add(new PlayerItemDeliveryPolicy.DroppedItemEvidence(
					new PlayerItemDeliveryPolicy.EntityGeneration(itemEntity.getId(), itemEntity.getUUID()),
					itemId,
					droppedCount,
					itemEntity.getX(),
					itemEntity.getY(),
					itemEntity.getZ()
				));
			}
		}
		return List.copyOf(evidence);
	}

	@Override
	public void onPlayerItemPickupObserved(
		int entityId,
		UUID entityUuid,
		String itemId,
		int pickupDelta,
		int agentAttributedQuantity,
		UUID collectorIdentity,
		UUID observationId
	) {
		if (deliveryState == null || deliveryState.phase() != PlayerItemDeliveryPolicy.Phase.AWAIT_DELIVERY
			|| entityUuid == null || itemId == null || itemId.isBlank() || pickupDelta <= 0
			|| agentAttributedQuantity <= 0 || collectorIdentity == null || observationId == null) {
			return;
		}
		PlayerItemDeliveryPolicy.EntityGeneration generation = new PlayerItemDeliveryPolicy.EntityGeneration(entityId, entityUuid);
		int baselineAttributedQuantity = agentAttributedQuantity(generation, agentAttributedQuantity, baselineItemEntityCounts);
		int observedAttributedQuantity = deliveryState.observedEntityCounts().getOrDefault(generation, 0);
		int attributedQuantity = Math.max(baselineAttributedQuantity, observedAttributedQuantity);
		if (attributedQuantity <= 0) {
			return;
		}
		pendingPickupEvidence.add(new PlayerItemDeliveryPolicy.PickupEvidence(
			generation,
			itemId,
			pickupDelta,
			attributedQuantity,
			collectorIdentity,
			observationId
		));
	}

	static int agentAttributedQuantity(PlayerItemDeliveryPolicy.EntityGeneration generation, int observedEntityStackCount,
		Map<PlayerItemDeliveryPolicy.EntityGeneration, Integer> baselineCounts) {
		if (observedEntityStackCount < 0) {
			throw new IllegalArgumentException("observedEntityStackCount must not be negative");
		}
		int baselineCount = baselineCounts == null ? 0 : baselineCounts.getOrDefault(generation, 0);
		return Math.max(0, observedEntityStackCount - baselineCount);
	}

	static int agentAttributedQuantity(int entityId, int observedEntityStackCount, Map<Integer, Integer> baselineCounts) {
		if (observedEntityStackCount < 0) {
			throw new IllegalArgumentException("observedEntityStackCount must not be negative");
		}
		int baselineCount = baselineCounts == null ? 0 : baselineCounts.getOrDefault(entityId, 0);
		return Math.max(0, observedEntityStackCount - baselineCount);
	}

	private static Map<PlayerItemDeliveryPolicy.EntityGeneration, Integer> itemEntityCounts(Minecraft minecraft, String itemId) {
		Map<PlayerItemDeliveryPolicy.EntityGeneration, Integer> counts = new HashMap<>();
		AABB area = minecraft.player.getBoundingBox().inflate(64.0D);
		for (ItemEntity itemEntity : minecraft.level.getEntitiesOfClass(ItemEntity.class, area, value -> true)) {
			if (itemId.equals(BuiltInRegistries.ITEM.getKey(itemEntity.getItem().getItem()).toString())) {
				counts.put(new PlayerItemDeliveryPolicy.EntityGeneration(itemEntity.getId(), itemEntity.getUUID()), itemEntity.getItem().getCount());
			}
		}
		return Map.copyOf(counts);
	}

	private static Vec3 targetAimPoint(AbstractClientPlayer target) {
		return target.getBoundingBox().getCenter();
	}

	private static Optional<String> readinessFailure(Minecraft minecraft, LocalPlayer player) {
		if (player.containerMenu != player.inventoryMenu) {
			return Optional.of(INVENTORY_BUSY);
		}
		if (minecraft.screen != null && !(minecraft.screen instanceof InventoryScreen)) {
			return Optional.of(INVENTORY_BUSY_SCREEN);
		}
		if (!player.containerMenu.getCarried().isEmpty()) {
			return Optional.of(INVENTORY_BUSY);
		}
		return Optional.empty();
	}

	private static boolean dismissCurrentScreenIfSafe(Minecraft minecraft, LocalPlayer player) {
		if (!shouldDismissBusyScreen(currentScreenName(minecraft))) {
			return false;
		}
		if (player.containerMenu != player.inventoryMenu) {
			return false;
		}
		if (!player.containerMenu.getCarried().isEmpty()) {
			return false;
		}
		ScreenCloseSafety.clearScreen(minecraft, "drop_items_screen_dismiss");
		return true;
	}

	private static String currentScreenName(Minecraft minecraft) {
		return minecraft.screen == null ? null : minecraft.screen.getClass().getSimpleName();
	}

	static boolean shouldDismissBusyScreen(String screenName) {
		return "ChatScreen".equals(screenName) || "PauseScreen".equals(screenName);
	}

	static boolean itemDropActuationAllowed(SessionSnapshot sessionSnapshot) {
		return sessionSnapshot != null && sessionSnapshot.companionActuationAllowed();
	}

	static boolean shouldWaitForBusyScreen(int busyScreenTicks) {
		return busyScreenTicks < BUSY_SCREEN_TIMEOUT_TICKS;
	}

	private static List<DropSlot> matchingSlots(AbstractContainerMenu menu, String itemId) {
		ArrayList<DropSlot> slots = new ArrayList<>();
		for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_END; slot++) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (stack.isEmpty()) {
				continue;
			}
			String stackItemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			if (itemId.equals(stackItemId)) {
				slots.add(new DropSlot(slot, stack.getCount()));
			}
		}
		return List.copyOf(slots);
	}

	static List<DropClick> planDropClicks(List<DropSlot> slots, int quantity) {
		ArrayList<DropClick> clicks = new ArrayList<>();
		int remaining = quantity;
		for (DropSlot slot : slots) {
			if (remaining <= 0) {
				break;
			}
			int dropCount = Math.min(slot.count(), remaining);
			if (dropCount > 0) {
				clicks.add(new DropClick(slot.slotId(), dropCount));
				remaining -= dropCount;
			}
		}
		return List.copyOf(clicks);
	}

	private static void performDropClick(Minecraft minecraft, LocalPlayer player, AbstractContainerMenu menu, DropClick click) {
		int remaining = click.count();
		while (remaining > 0) {
			ItemStack currentStack = menu.getSlot(click.slotId()).getItem();
			if (!currentStack.isEmpty() && remaining >= currentStack.getCount()) {
				int stackCount = currentStack.getCount();
				minecraft.gameMode.handleInventoryMouseClick(menu.containerId, click.slotId(), 1, ClickType.THROW, player);
				remaining -= stackCount;
			}
			else {
				minecraft.gameMode.handleInventoryMouseClick(menu.containerId, click.slotId(), 0, ClickType.THROW, player);
				remaining--;
			}
		}
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request) {
		return complete(request, "dropped_items");
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String event) {
		cancelApproach();
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, event);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		DropItemsStepArgs args = ((WorldTaskRequest.DropItems) request.task()).args();
		String message = args.targetPlayer() == null
			? completionMessage(args)
			: event + " itemId=" + args.itemId()
				+ " quantity=" + args.quantity()
				+ " targetPlayer=" + args.targetPlayer();
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, null));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, TaskFailure failure) {
		cancelApproach();
		snapshot = snapshot(TaskExecutionState.FAILED, request, failure.detail());
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, failure.detail(), null, failure.code()));
	}

	private static String completionMessage(DropItemsStepArgs args) {
		return "dropped_items itemId=" + args.itemId()
			+ " quantity=" + args.quantity()
			+ (args.targetPlayer() == null ? "" : " targetPlayer=" + args.targetPlayer());
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "ItemDrop", event, null, null);
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
		cancelApproach();
		reset();
	}

	@Override
	public void shutdown() {
		cancelApproach();
		reset();
	}

	private void reset() {
		appliedTask = null;
		busyScreenTicks = 0;
		terminalEventEmitted = false;
		targetUuid = null;
		baselineItemEntityCounts = Map.of();
		pendingPickupEvidence.clear();
		deliveryState = null;
		chaseGoal = null;
		chaseGoalRefreshTicks = 0;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private void stopApproach(Minecraft minecraft) {
		movementController.stop(minecraft);
		cancelNavigationChase();
	}

	private void cancelApproach() {
		stopApproach(clientSupplier.get());
	}

	private void cancelNavigationChase() {
		if (chaseGoal != null && navigationFacade != null && navigationFacade.isLoaded()) {
			navigationFacade.cancel();
		}
		chaseGoal = null;
		chaseGoalRefreshTicks = 0;
	}

	record DropSlot(int slotId, int count) {
	}

	record DropClick(int slotId, int count) {
	}
}
