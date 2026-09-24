package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DispatchingWorldTaskExecutor implements WorldTaskExecutor {
	private final ExecutorSet executors;
	private final BaritoneFacade sharedBaritone;
	private WorldTaskExecutor activeExecutor;
	private WorldTaskType activeType;
	private NavigationStallWatchdog navigationStall;
	private String navigationTaskId;
	private String stalledTaskId;
	private TaskExecutionSnapshot transitionSnapshot = TaskExecutionSnapshot.idle();

	public DispatchingWorldTaskExecutor(ExecutorSet executors, BaritoneFacade sharedBaritone) {
		this.executors = Objects.requireNonNull(executors, "executors");
		this.sharedBaritone = sharedBaritone;
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty()) {
			deactivate(sessionSnapshot);
			if (navigationStall != null) navigationStall.clear();
			transitionSnapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}

		WorldTaskRequest request = activeTask.get();
		if (request.taskId().equals(stalledTaskId)) return Optional.empty();
		if (navigationStall == null) navigationStall = new NavigationStallWatchdog();
		if (!request.taskId().equals(navigationTaskId)) {
			navigationStall.clear();
			navigationTaskId = request.taskId();
			stalledTaskId = null;
		}
		WorldTaskExecutor requestedExecutor = executors.executorFor(request);
		if (sharedBaritone != null && activeType != request.type()) {
			deactivate(sessionSnapshot);
			if (!BaritoneReleaseBarrier.releaseAndDrain(sharedBaritone)) {
				transitionSnapshot = new TaskExecutionSnapshot(
					TaskExecutionState.RUNNING,
					request.taskId(),
					request.goal(),
					"WorldTaskDispatcher",
					"waiting_for_previous_baritone_release",
					null,
					null
				);
				return Optional.empty();
			}
		}
		else if (activeExecutor != null && activeExecutor != requestedExecutor) {
			deactivate(sessionSnapshot);
		}

		activeExecutor = requestedExecutor;
		activeType = request.type();
		transitionSnapshot = TaskExecutionSnapshot.idle();
		var result = activeExecutor.tick(sessionSnapshot, activeTask);
		var progress = sharedBaritone == null || !sessionSnapshot.companionActuationAllowed()
			? Optional.<BaritoneFacade.NavigationProgress>empty() : sharedBaritone.navigationProgress();
		if (result.isPresent() || progress.isEmpty()) navigationStall.clear();
		else if (navigationStall.observe(sessionSnapshot.tickCount(), progress.orElseThrow())) {
			deactivate(sessionSnapshot);
			sharedBaritone.cancel();
			stalledTaskId = request.taskId();
			transitionSnapshot = new TaskExecutionSnapshot(TaskExecutionState.FAILED, request.taskId(), request.goal(),
				"WorldTaskDispatcher", "PATH_STUCK", null, null);
			return Optional.of(new TaskTerminalEvent(request.taskId(), request.goal(), TaskExecutionState.FAILED,
				"navigation_stuck: no supported displacement or block-breaking progress for 100 active ticks; repeated jumping is not progress",
				null, TaskFailureCode.TRANSIENT));
		}
		return result;
	}

	private void deactivate(SessionSnapshot sessionSnapshot) {
		if (activeExecutor != null) {
			activeExecutor.tick(sessionSnapshot, Optional.empty());
		}
		activeExecutor = null;
		activeType = null;
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
		executors.dropItems().onPlayerItemPickupObserved(
			entityId,
			entityUuid,
			itemId,
			pickupDelta,
			agentAttributedQuantity,
			collectorIdentity,
			observationId
		);
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return activeExecutor == null ? transitionSnapshot : activeExecutor.snapshot();
	}

	@Override
	public void onWorldLeave() {
		navigationTaskId = null;
		stalledTaskId = null;
		if (navigationStall != null) navigationStall.clear();
		activeExecutor = null;
		activeType = null;
		transitionSnapshot = TaskExecutionSnapshot.idle();
		executors.all().forEach(WorldTaskExecutor::onWorldLeave);
	}

	@Override
	public void shutdown() {
		activeExecutor = null;
		activeType = null;
		transitionSnapshot = TaskExecutionSnapshot.idle();
		executors.all().forEach(WorldTaskExecutor::shutdown);
	}

	public record ExecutorSet(
		WorldTaskExecutor baritone,
		WorldTaskExecutor crafting,
		WorldTaskExecutor dropItems,
		WorldTaskExecutor entityInteraction,
		WorldTaskExecutor smelting,
		WorldTaskExecutor returnToSurface,
		WorldTaskExecutor blockInteraction,
		WorldTaskExecutor blockBreak,
		WorldTaskExecutor acquisition,
		WorldTaskExecutor underwaterHarvest,
		WorldTaskExecutor cropTending,
		WorldTaskExecutor lureEntities
	) {
		public ExecutorSet(WorldTaskExecutor baritone, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak, WorldTaskExecutor acquisition,
			WorldTaskExecutor underwaterHarvest, WorldTaskExecutor cropTending) {
			this(baritone, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction,
				blockBreak, acquisition, underwaterHarvest, cropTending, entityInteraction);
		}

		public ExecutorSet(WorldTaskExecutor baritone, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak, WorldTaskExecutor acquisition, WorldTaskExecutor underwaterHarvest) {
			this(baritone, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction, blockBreak, acquisition, underwaterHarvest, blockInteraction);
		}

		public ExecutorSet(WorldTaskExecutor baritone, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak) {
			this(baritone, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction, blockBreak, baritone, baritone);
		}

		public ExecutorSet {
			Objects.requireNonNull(cropTending, "cropTending");
			Objects.requireNonNull(lureEntities, "lureEntities");
			Objects.requireNonNull(acquisition, "acquisition");
			Objects.requireNonNull(underwaterHarvest, "underwaterHarvest");
			Objects.requireNonNull(baritone, "baritone");
			Objects.requireNonNull(crafting, "crafting");
			Objects.requireNonNull(dropItems, "dropItems");
			Objects.requireNonNull(entityInteraction, "entityInteraction");
			Objects.requireNonNull(smelting, "smelting");
			Objects.requireNonNull(returnToSurface, "returnToSurface");
			Objects.requireNonNull(blockInteraction, "blockInteraction");
			Objects.requireNonNull(blockBreak, "blockBreak");
		}

		WorldTaskExecutor executorFor(WorldTaskRequest request) {
			return switch (request.type()) {
				case FOLLOW, NAVIGATE -> baritone;
				case MINE -> acquisition;
				case UNDERWATER_HARVEST -> underwaterHarvest;
				case CRAFT_RECIPE -> crafting;
				case DROP_ITEMS -> dropItems;
				case ATTACK_ENTITY, USE_ENTITY -> entityInteraction;
				case SMELT_ITEMS, COLLECT_SMELTED_ITEMS -> smelting;
				case RETURN_TO_SURFACE -> returnToSurface;
				case PLACE_BLOCK, USE_BLOCK -> blockInteraction;
				case BREAK_BLOCKS -> blockBreak;
				case TEND_CROPS -> cropTending;
				case LURE_ENTITIES -> lureEntities;
			};
		}

		List<WorldTaskExecutor> all() {
			return List.copyOf(new LinkedHashSet<>(List.of(
				baritone,
				crafting,
				dropItems,
				entityInteraction,
				smelting,
				returnToSurface,
				blockInteraction,
				blockBreak, acquisition, underwaterHarvest, cropTending, lureEntities
			)));
		}
	}
}
