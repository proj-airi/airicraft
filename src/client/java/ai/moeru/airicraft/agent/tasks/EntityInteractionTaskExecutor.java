package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ClipContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class EntityInteractionTaskExecutor implements WorldTaskExecutor {
	private static final int TARGET_OUT_OF_RANGE_GRACE_TICKS = 20;
	private static final int BUSY_STATE_TIMEOUT_TICKS = 100;
	private static final int CHASE_GOAL_REFRESH_TICKS = 10;
	private static final int BARITONE_CHASE_RADIUS_BLOCKS = 3;
	private static final double CHASE_GOAL_REFRESH_DISTANCE_BLOCKS = 2.0D;
	private static final double DIRECT_CHASE_DISTANCE_BLOCKS = 10.0D;
	private static final float ATTACK_READY_THRESHOLD = 0.92F;

	private final Supplier<Minecraft> clientSupplier;
	private final BaritoneFacade navigationFacade;
	private final CameraController cameraController;
	private final MovementController movementController = new MovementController();

	private WorldTaskRequest appliedTask;
	private boolean terminalEventEmitted;
	private boolean landedAttack;
	private Entity attackedTarget;
	private Map<String, Integer> inventoryBeforeAttack = Map.of();
	private Vec3 dropCollectionCenter;
	private int dropCollectionTicks;
	private int dropQuietTicks;
	private int outOfRangeTicks;
	private int busyStateTicks;
	private int attackHotbarSlot = -1;
	private GoalPosition chaseGoal;
	private int chaseGoalRefreshTicks;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public EntityInteractionTaskExecutor() {
		this(Minecraft::getInstance, null, new CameraController());
	}

	public EntityInteractionTaskExecutor(BaritoneFacade navigationFacade) {
		this(Minecraft::getInstance, navigationFacade, new CameraController());
	}

	public EntityInteractionTaskExecutor(BaritoneFacade navigationFacade, CameraController cameraController) {
		this(Minecraft::getInstance, navigationFacade, cameraController);
	}

	EntityInteractionTaskExecutor(Supplier<Minecraft> clientSupplier) {
		this(clientSupplier, null, new CameraController());
	}

	EntityInteractionTaskExecutor(Supplier<Minecraft> clientSupplier, BaritoneFacade navigationFacade) {
		this(clientSupplier, navigationFacade, new CameraController());
	}

	EntityInteractionTaskExecutor(Supplier<Minecraft> clientSupplier, BaritoneFacade navigationFacade, CameraController cameraController) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.navigationFacade = navigationFacade;
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || !isEntityInteractionTask(activeTask.get().type())) {
			cancelApproach();
			reset();
			return Optional.empty();
		}

		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			cancelApproach();
			reset();
			appliedTask = request;
		}

		if (!entityInteractionActuationAllowed(sessionSnapshot)) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}

		Minecraft minecraft = clientSupplier.get();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft == null || minecraft.gameMode == null || minecraft.level == null || player == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "world_unavailable"));
		}
		if (dismissCurrentScreenIfSafe(minecraft, player)) {
			busyStateTicks = 0;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "screen_dismissed");
			return Optional.empty();
		}
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
			if (shouldWaitForBusyState(busyStateTicks)) {
				busyStateTicks++;
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "interaction_busy");
				return Optional.empty();
			}
			return fail(request, TaskFailure.of(TaskFailureCode.BUSY, "interaction_busy"));
		}
		busyStateTicks = 0;
		if (request.type() == WorldTaskType.ATTACK_ENTITY && attackHotbarSlot < 0) {
			attackHotbarSlot = player.getInventory().getSelectedSlot();
		}

		if (terminalEventEmitted) return Optional.empty();
		if (dropCollectionCenter != null) return collectKillDrops(minecraft, player, request, sessionSnapshot.tickCount());

		Selection selection = resolveSelection(minecraft, player, interaction(request).selector());
		if (selection.status() != EntitySelectorResolver.SelectionStatus.SELECTED) {
			if (completedAfterLandedAttack(request, selection.status())) {
				return beginDropCollection(request);
			}
			return fail(request, selection.failure());
		}
		Entity target = selection.entity();
		if (target == null) {
			return fail(request, selectionFailure(EntitySelectorResolver.SelectionStatus.TARGET_NOT_FOUND));
		}
		if (!target.isAlive()) {
			if (completedAfterLandedAttack(request, EntitySelectorResolver.SelectionStatus.TARGET_NOT_ALIVE)) {
				return beginDropCollection(request);
			}
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_not_alive"));
		}
		if (request.type() == WorldTaskType.ATTACK_ENTITY
			&& !attackTargetAllowed(target.getClass(), target == player, target.isAttackable())) {
			return fail(request, TaskFailure.of(TaskFailureCode.INVALID_ACTION,
				"target_not_attackable type=" + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType())
					+ "; dropped items and experience are collected by moving within pickup range, not attacking"));
		}
		double distance = player.distanceTo(target);
		boolean hasLineOfSight = hasBlockLineOfSight(minecraft, player, target);
		boolean withinInteractionRange = EntitySelectorResolver.isWithinInteractionRange(
			player.getX(),
			player.getY(),
			player.getZ(),
			target.getX(),
			target.getY(),
			target.getZ()
		);
		if (withinInteractionRange && hasLineOfSight) {
			outOfRangeTicks = 0;
			// Baritone owns steering on indirect approaches; aim only when we own the interaction.
			lookAtTarget(minecraft, target);
			if (!cameraController.isAimingAt(minecraft, target.getBoundingBox())) {
				movementController.stop(minecraft);
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "aiming_at_target");
				return Optional.empty();
			}
		}

		return switch (request.type()) {
			case ATTACK_ENTITY -> {
				if (withinInteractionRange && hasLineOfSight) {
					yield attackEntity(minecraft, player, request, target);
				}
				yield approachTarget(minecraft, request, target, distance, hasLineOfSight, sessionSnapshot.tickCount());
			}
			case USE_ENTITY -> {
				if (withinInteractionRange && hasLineOfSight) {
					yield useEntity(minecraft, player, request, target);
				}
				yield approachTarget(minecraft, request, target, distance, hasLineOfSight, sessionSnapshot.tickCount());
			}
			default -> Optional.empty();
		};
	}

	static boolean entityInteractionActuationAllowed(SessionSnapshot sessionSnapshot) {
		return sessionSnapshot != null && sessionSnapshot.companionActuationAllowed();
	}

	static boolean shouldWaitForBusyState(int busyStateTicks) {
		return busyStateTicks < BUSY_STATE_TIMEOUT_TICKS;
	}

	static boolean shouldRefreshChaseGoal(GoalPosition currentChaseGoal, GoalPosition nextChaseGoal, int ticksSinceRefresh) {
		return currentChaseGoal == null
			|| squaredBlockDistance(currentChaseGoal, nextChaseGoal) >= CHASE_GOAL_REFRESH_DISTANCE_BLOCKS * CHASE_GOAL_REFRESH_DISTANCE_BLOCKS
			|| ticksSinceRefresh >= CHASE_GOAL_REFRESH_TICKS;
	}

	static boolean shouldUseDirectChase(double distance, boolean hasLineOfSight, boolean directMovementStuck) {
		return distance <= DIRECT_CHASE_DISTANCE_BLOCKS && hasLineOfSight && !directMovementStuck;
	}

	/** Mirrors the server's invalid-entity attack rejection before any packet is sent. */
	static boolean attackTargetAllowed(Class<?> targetClass, boolean self, boolean attackable) {
		return !self && !net.minecraft.world.entity.item.ItemEntity.class.isAssignableFrom(targetClass)
			&& !net.minecraft.world.entity.ExperienceOrb.class.isAssignableFrom(targetClass)
			&& !(net.minecraft.world.entity.projectile.AbstractArrow.class.isAssignableFrom(targetClass) && !attackable);
	}

	private Optional<TaskTerminalEvent> attackEntity(
		Minecraft minecraft,
		LocalPlayer player,
		WorldTaskRequest request,
		Entity target
	) {
		movementController.stop(minecraft);
		cancelBaritoneChase();
		// Chase navigation may select tools or building blocks. Restore the hand
		// chosen for this attack before evaluating its cooldown or sending a hit.
		player.getInventory().setSelectedSlot(attackHotbarSlot);
		if (player.getAttackStrengthScale(0.0F) < ATTACK_READY_THRESHOLD) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "attack_cooldown");
			return Optional.empty();
		}

		if (!landedAttack) inventoryBeforeAttack = new InventoryItemCounter().count(player.getInventory());
		attackedTarget = target;
		minecraft.gameMode.attack(player, target);
		player.swing(InteractionHand.MAIN_HAND);
		landedAttack = true;
		if (interaction(request).attackMode() == EntityAttackMode.HIT_ONCE) {
			return complete(request, "attack_landed");
		}
		if (!target.isAlive()) {
			return beginDropCollection(request);
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "attack_landed");
		return Optional.empty();
	}

	static boolean confirmedKill(boolean healthDepleted, Entity.RemovalReason removalReason) {
		return healthDepleted || removalReason == Entity.RemovalReason.KILLED;
	}

	private Optional<TaskTerminalEvent> beginDropCollection(WorldTaskRequest request) {
		// A disappeared or unloaded target alone is not proof that it died.
		if (attackedTarget == null || !confirmedKill(
			attackedTarget instanceof LivingEntity living && living.getHealth() <= 0,
			attackedTarget.getRemovalReason()))
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_lost_kill_unconfirmed"));
		cancelApproach();
		dropCollectionCenter = attackedTarget.position();
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_died_collecting_drops");
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> collectKillDrops(Minecraft minecraft, LocalPlayer player, WorldTaskRequest request, long tick) {
		dropCollectionTicks++;
		var drops = minecraft.level.getEntitiesOfClass(ItemEntity.class,
			new AABB(dropCollectionCenter, dropCollectionCenter).inflate(4), item -> item.isAlive() && !item.getItem().isEmpty());
		if (drops.isEmpty()) {
			cancelApproach();
			// Allow death/drop packets and delayed spawns to settle before reporting completion.
			if (++dropQuietTicks >= 20) return complete(request, "target_died_nearby_drops_cleared");
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "waiting_for_kill_drops");
			return Optional.empty();
		}
		dropQuietTicks = 0;
		if (dropCollectionTicks >= 200)
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_died_drops_uncollected_timeout"));
		var pickup = drops.stream().filter(item -> canAcceptDrop(player, item.getItem()))
			.min(java.util.Comparator.comparingDouble(player::distanceToSqr));
		if (pickup.isEmpty())
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_died_drops_uncollected_inventory_full"));
		ItemEntity target = pickup.get();
		double distance = player.distanceTo(target);
		if (distance < 0.8D) {
			cancelApproach();
		} else if (shouldUseDirectChase(distance, hasBlockLineOfSight(minecraft, player, target), movementController.snapshot().stuck())) {
			cancelBaritoneChase();
			lookAtTarget(minecraft, target);
			movementController.moveForward(minecraft, false, false, tick);
		} else if (navigationFacade != null && navigationFacade.isLoaded()) {
			movementController.stop(minecraft);
			if (isUnreachablePathEvent(navigationFacade.pollPathEvent()))
				return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_died_drops_unreachable"));
			GoalPosition next = new GoalPosition(target.getBlockX(), (int) Math.floor(target.getY() + 0.125D), target.getBlockZ(), true);
			if (shouldRefreshChaseGoal(chaseGoal, next, chaseGoalRefreshTicks++)) {
				navigationFacade.startNavigate(next);
				chaseGoal = next;
				chaseGoalRefreshTicks = 0;
			}
		} else {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_died_drops_unreachable"));
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "collecting_kill_drops");
		return Optional.empty();
	}

	private static boolean canAcceptDrop(LocalPlayer player, ItemStack drop) {
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.isEmpty() || (ItemStack.isSameItemSameComponents(stack, drop) && stack.getCount() < stack.getMaxStackSize())) return true;
		}
		return false;
	}

	private Optional<TaskTerminalEvent> useEntity(
		Minecraft minecraft,
		LocalPlayer player,
		WorldTaskRequest request,
		Entity target
	) {
		movementController.stop(minecraft);
		cancelBaritoneChase();
		InteractionHand hand = resolveInteractionHand(minecraft, player, interaction(request).itemId());
		if (hand == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "required_item_missing"));
		}
		InteractionResult result = minecraft.gameMode.interact(player, target, hand);
		if (!result.consumesAction()) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "interaction_failed"));
		}
		player.swing(hand);
		return complete(request, "interaction_succeeded");
	}

	private Optional<TaskTerminalEvent> approachTarget(
		Minecraft minecraft,
		WorldTaskRequest request,
		Entity target,
		double distance,
		boolean hasLineOfSight,
		long tick
	) {
		outOfRangeTicks++;
		if (shouldUseDirectChase(distance, hasLineOfSight, movementController.snapshot().stuck())) {
			cancelBaritoneChase();
			lookAtTarget(minecraft, target);
			movementController.moveForward(minecraft, true, false, tick);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "direct_chase");
			return Optional.empty();
		}
		if (navigationFacade != null && navigationFacade.isLoaded()) {
			movementController.stop(minecraft);
			Optional<String> pathEvent = navigationFacade.pollPathEvent();
			if (!landedAttack && isUnreachablePathEvent(pathEvent)) {
				return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_unreachable"));
			}
			GoalPosition nextChaseGoal = chaseGoalFor(target);
			if (shouldRefreshChaseGoal(chaseGoal, nextChaseGoal, chaseGoalRefreshTicks)) {
				if (hasLineOfSight) {
					navigationFacade.startNavigateNear(nextChaseGoal, BARITONE_CHASE_RADIUS_BLOCKS);
				}
				else {
					// A nearby goal can already be satisfied on the wrong side of an obstruction.
					// The small feet offset also accounts for shortened support blocks such as farmland.
					navigationFacade.startNavigate(new GoalPosition(nextChaseGoal.x(), (int) Math.floor(target.getY() + 0.125D), nextChaseGoal.z(), true));
				}
				chaseGoal = nextChaseGoal;
				chaseGoalRefreshTicks = 0;
			}
			else {
				chaseGoalRefreshTicks++;
			}
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "baritone_chase");
			return Optional.empty();
		}
		movementController.stop(minecraft);
		if (outOfRangeTicks <= TARGET_OUT_OF_RANGE_GRACE_TICKS) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_out_of_range");
			return Optional.empty();
		}
		return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_out_of_range"));
	}

	private static InteractionHand resolveInteractionHand(Minecraft minecraft, LocalPlayer player, String itemId) {
		if (itemId == null || itemId.isBlank()) {
			return InteractionHand.MAIN_HAND;
		}
		String offHandItemId = BuiltInRegistries.ITEM.getKey(player.getOffhandItem().getItem()).toString();
		if (itemId.equals(offHandItemId) && !player.getOffhandItem().isEmpty()) {
			return InteractionHand.OFF_HAND;
		}

		AbstractContainerMenu menu = player.containerMenu;
		int sourceSlot = findInventorySlot(menu, itemId);
		if (sourceSlot < 0) {
			return null;
		}
		int hotbarIndex = player.getInventory().getSelectedSlot();
		if (sourceSlot >= InventoryMenu.USE_ROW_SLOT_START && sourceSlot < InventoryMenu.USE_ROW_SLOT_END) {
			player.getInventory().setSelectedSlot(sourceSlot - InventoryMenu.USE_ROW_SLOT_START);
			return InteractionHand.MAIN_HAND;
		}
		minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot, hotbarIndex, ClickType.SWAP, player);
		player.getInventory().setSelectedSlot(hotbarIndex);
		ItemStack selected = player.getInventory().getSelectedItem();
		if (selected.isEmpty()) {
			return null;
		}
		String selectedItemId = BuiltInRegistries.ITEM.getKey(selected.getItem()).toString();
		return itemId.equals(selectedItemId) ? InteractionHand.MAIN_HAND : null;
	}

	private static int findInventorySlot(AbstractContainerMenu menu, String itemId) {
		for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_END; slot++) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (stack.isEmpty()) {
				continue;
			}
			if (itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
				return slot;
			}
		}
		return -1;
	}

	private static Selection resolveSelection(Minecraft minecraft, LocalPlayer player, EntitySelector selector) {
		Map<Integer, Entity> entitiesById = new LinkedHashMap<>();
		java.util.ArrayList<EntitySelectorResolver.EntityCandidate> candidates = new java.util.ArrayList<>();
		double radius = EntitySelectorResolver.DEFAULT_NEARBY_RADIUS_BLOCKS;
		for (Entity entity : minecraft.level.getEntities(player, player.getBoundingBox().inflate(radius))) {
			entitiesById.put(entity.getId(), entity);
			candidates.add(NearbyEntityService.toCandidate(entity));
		}
		EntitySelectorResolver.SelectionResult result = EntitySelectorResolver.select(
			selector,
			candidates,
			player.getX(),
			player.getY(),
			player.getZ()
		);
		if (result.status() != EntitySelectorResolver.SelectionStatus.SELECTED || result.selected() == null) {
			return new Selection(null, result.status(), selectionFailure(result.status()));
		}
		Entity selectedEntity = entitiesById.get(result.selected().entityId());
		if (selectedEntity == null) {
			EntitySelectorResolver.SelectionStatus status = EntitySelectorResolver.SelectionStatus.TARGET_NOT_FOUND;
			return new Selection(null, status, selectionFailure(status));
		}
		return new Selection(selectedEntity, result.status(), null);
	}

	private static TaskFailure selectionFailure(EntitySelectorResolver.SelectionStatus status) {
		return switch (status) {
			case TARGET_NOT_FOUND -> TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_not_found");
			case TARGET_NOT_NEARBY -> TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_not_nearby");
			case TARGET_NOT_ALIVE -> TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_not_alive");
			case TARGET_AMBIGUOUS -> TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_ambiguous");
			case SELECTED -> null;
		};
	}

	private static GoalPosition chaseGoalFor(Entity target) {
		var blockPos = target.blockPosition();
		return new GoalPosition(blockPos.getX(), blockPos.getY(), blockPos.getZ(), false);
	}

	private void lookAtTarget(Minecraft minecraft, Entity target) {
		cameraController.lookAt(minecraft, targetAimPoint(target));
	}

	private static boolean hasBlockLineOfSight(Minecraft minecraft, LocalPlayer player, Entity target) {
		if (minecraft == null || minecraft.level == null || player == null || target == null) {
			return false;
		}
		Vec3 start = player.getEyePosition();
		Vec3 end = targetAimPoint(target);
		HitResult hit = minecraft.level.clip(new ClipContext(
			start,
			end,
			ClipContext.Block.COLLIDER,
			ClipContext.Fluid.NONE,
			player
		));
		if (hit == null || hit.getType() == HitResult.Type.MISS) {
			return true;
		}
		return hit.getLocation().distanceToSqr(start) + 0.25D >= end.distanceToSqr(start);
	}

	private static Vec3 targetAimPoint(Entity target) {
		return target.getBoundingBox().getCenter();
	}

	private static double squaredBlockDistance(GoalPosition left, GoalPosition right) {
		if (left == null || right == null) {
			return Double.POSITIVE_INFINITY;
		}
		double dx = left.x() - right.x();
		double dy = left.y() - right.y();
		double dz = left.z() - right.z();
		return (dx * dx) + (dy * dy) + (dz * dz);
	}

	private static boolean isUnreachablePathEvent(Optional<String> pathEvent) {
		return pathEvent
			.map(event -> "CALC_FAILED".equals(event.trim().toUpperCase(java.util.Locale.ROOT)))
			.orElse(false);
	}

	private static boolean dismissCurrentScreenIfSafe(Minecraft minecraft, LocalPlayer player) {
		if (!DropItemsTaskExecutor.shouldDismissBusyScreen(currentScreenName(minecraft))) {
			return false;
		}
		if (player.containerMenu != player.inventoryMenu) {
			return false;
		}
		if (!player.containerMenu.getCarried().isEmpty()) {
			return false;
		}
		ScreenCloseSafety.clearScreen(minecraft, "entity_interaction_screen_dismiss");
		return true;
	}

	private static String currentScreenName(Minecraft minecraft) {
		return minecraft.screen == null ? null : minecraft.screen.getClass().getSimpleName();
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		message = withCollectionReport(message);
		cancelApproach();
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, null));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, TaskFailure failure) {
		String message = withCollectionReport(failure.detail());
		cancelApproach();
		snapshot = snapshot(TaskExecutionState.FAILED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, message, null, failure.code()));
	}

	private String withCollectionReport(String message) {
		if (dropCollectionCenter == null) return message;
		var minecraft = clientSupplier.get();
		if (minecraft == null || minecraft.player == null) return message + " collectedItems=unknown";
		return collectionReport(message, inventoryBeforeAttack, new InventoryItemCounter().count(minecraft.player.getInventory()));
	}

	static String collectionReport(String message, Map<String, Integer> before, Map<String, Integer> after) {
		var collected = new java.util.TreeMap<String, Integer>();
		after.forEach((itemId, count) -> {
			int gained = count - before.getOrDefault(itemId, 0);
			if (gained > 0) collected.put(itemId, gained);
		});
		return message + " collectedItems=" + collected + " collectionEvidence=inventory_gain";
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "EntityInteraction", event, null, null);
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

	private static boolean isEntityInteractionTask(WorldTaskType type) {
		return type == WorldTaskType.ATTACK_ENTITY || type == WorldTaskType.USE_ENTITY;
	}

	private boolean completedAfterLandedAttack(WorldTaskRequest request, EntitySelectorResolver.SelectionStatus status) {
		return request != null
			&& completedAfterLandedAttack(request.type(), interaction(request).attackMode(), landedAttack, status);
	}

	private static EntityInteractionStepArgs interaction(WorldTaskRequest request) {
		return switch (request.task()) {
			case WorldTaskRequest.AttackEntity task -> task.args();
			case WorldTaskRequest.UseEntity task -> task.args();
			default -> throw new IllegalArgumentException("entity interaction task required");
		};
	}

	static boolean completedAfterLandedAttack(
		WorldTaskType type,
		EntityAttackMode attackMode,
		boolean landedAttack,
		EntitySelectorResolver.SelectionStatus status
	) {
		return type == WorldTaskType.ATTACK_ENTITY
			&& attackMode == EntityAttackMode.KILL
			&& landedAttack
			&& (status == EntitySelectorResolver.SelectionStatus.TARGET_NOT_FOUND
				|| status == EntitySelectorResolver.SelectionStatus.TARGET_NOT_ALIVE);
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
		attackHotbarSlot = -1;
		appliedTask = null;
		terminalEventEmitted = false;
		landedAttack = false;
		attackedTarget = null;
		inventoryBeforeAttack = Map.of();
		dropCollectionCenter = null;
		dropCollectionTicks = 0;
		dropQuietTicks = 0;
		outOfRangeTicks = 0;
		busyStateTicks = 0;
		chaseGoal = null;
		chaseGoalRefreshTicks = 0;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private void cancelApproach() {
		movementController.stop(clientSupplier.get());
		cancelBaritoneChase();
	}

	private void cancelBaritoneChase() {
		if (chaseGoal != null && navigationFacade != null && navigationFacade.isLoaded()) {
			navigationFacade.cancel();
		}
		chaseGoal = null;
		chaseGoalRefreshTicks = 0;
	}

	private record Selection(Entity entity, EntitySelectorResolver.SelectionStatus status, TaskFailure failure) {
	}
}
