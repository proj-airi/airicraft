package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DispatchingWorldTaskExecutor implements WorldTaskExecutor {
	private final ExecutorSet executors;
	private final NavigationFacade navigation;
	private WorldTaskExecutor activeExecutor;
	private WorldTaskType activeType;
	private NavigationStallWatchdog navigationStall;
	private String navigationTaskId;
	private String stalledTaskId;
	private TaskExecutionSnapshot transitionSnapshot = TaskExecutionSnapshot.idle();

	public DispatchingWorldTaskExecutor(ExecutorSet executors, NavigationFacade navigation) {
		this.executors = Objects.requireNonNull(executors, "executors");
		this.navigation = navigation;
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
		if (navigation != null && activeType != request.type()) {
			deactivate(sessionSnapshot);
			NavigationRelease.release(navigation);
		}
		else if (activeExecutor != null && activeExecutor != requestedExecutor) {
			deactivate(sessionSnapshot);
		}

		activeExecutor = requestedExecutor;
		activeType = request.type();
		transitionSnapshot = TaskExecutionSnapshot.idle();
		var result = activeExecutor.tick(sessionSnapshot, activeTask);
		var progress = navigation == null || !sessionSnapshot.companionActuationAllowed()
			? Optional.<NavigationFacade.NavigationProgress>empty() : navigation.navigationProgress();
		if (result.isPresent() || progress.isEmpty()) navigationStall.clear();
		else if (navigationStall.observe(sessionSnapshot.tickCount(), progress.orElseThrow())) {
			String detail = stuckDetail(progress.orElseThrow(), navigation.destination());
			deactivate(sessionSnapshot);
			navigation.cancel();
			stalledTaskId = request.taskId();
			transitionSnapshot = new TaskExecutionSnapshot(TaskExecutionState.FAILED, request.taskId(), request.goal(),
				"WorldTaskDispatcher", "PATH_STUCK " + detail, null, null);
			return Optional.of(new TaskTerminalEvent(request.taskId(), request.goal(), TaskExecutionState.FAILED,
				"navigation_stuck: no supported displacement or block-breaking progress for 100 active ticks; repeated jumping is not progress",
				null, TaskFailureCode.TRANSIENT));
		}
		return result;
	}

	/** Where the body stood and where its route was headed, so a bare PATH_STUCK is not all the caller learns. */
	static String stuckDetail(NavigationFacade.NavigationProgress progress, Optional<NavigationFacade.Destination> destination) {
		var detail = new StringBuilder("at=").append((int) Math.floor(progress.x())).append(',').append((int) Math.floor(progress.y()))
			.append(',').append((int) Math.floor(progress.z()));
		destination.ifPresent(target -> detail.append(" toward=").append(target.key()));
		if (progress.breakingTarget() != null) detail.append(" breaking=").append(progress.breakingTarget());
		return detail.append(" no movement or block-breaking progress for 100 ticks").toString();
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
	public List<TaskProgress> drainProgress() {
		// Every executor, not only the active one: a milestone reported on the tick a task ended still counts.
		List<TaskProgress> progress = new java.util.ArrayList<>();
		for (WorldTaskExecutor executor : executors.all()) progress.addAll(executor.drainProgress());
		return progress;
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
		WorldTaskExecutor navigation,
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
		WorldTaskExecutor lureEntities,
		WorldTaskExecutor dragonFight,
		WorldTaskExecutor collectObsidian,
		WorldTaskExecutor locateStronghold,
		WorldTaskExecutor exploreFor,
		WorldTaskExecutor collectBlazeRods,
		WorldTaskExecutor collectFlint,
		WorldTaskExecutor shelterForNight,
		WorldTaskExecutor collectEnderPearls
	) {
		public ExecutorSet(WorldTaskExecutor navigation, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak, WorldTaskExecutor acquisition,
			WorldTaskExecutor underwaterHarvest, WorldTaskExecutor cropTending, WorldTaskExecutor lureEntities,
			WorldTaskExecutor dragonFight, WorldTaskExecutor collectObsidian, WorldTaskExecutor locateStronghold,
			WorldTaskExecutor exploreFor, WorldTaskExecutor collectBlazeRods, WorldTaskExecutor collectFlint,
			WorldTaskExecutor shelterForNight) {
			this(navigation, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction,
				blockBreak, acquisition, underwaterHarvest, cropTending, lureEntities, dragonFight, collectObsidian, locateStronghold,
				exploreFor, collectBlazeRods, collectFlint, shelterForNight, entityInteraction);
		}

		public ExecutorSet(WorldTaskExecutor navigation, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak, WorldTaskExecutor acquisition,
			WorldTaskExecutor underwaterHarvest, WorldTaskExecutor cropTending, WorldTaskExecutor lureEntities,
			WorldTaskExecutor dragonFight, WorldTaskExecutor collectObsidian, WorldTaskExecutor locateStronghold,
			WorldTaskExecutor exploreFor, WorldTaskExecutor collectBlazeRods, WorldTaskExecutor collectFlint) {
			this(navigation, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction,
				blockBreak, acquisition, underwaterHarvest, cropTending, lureEntities, dragonFight, collectObsidian, locateStronghold,
				exploreFor, collectBlazeRods, collectFlint, entityInteraction);
		}

		public ExecutorSet(WorldTaskExecutor navigation, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak, WorldTaskExecutor acquisition,
			WorldTaskExecutor underwaterHarvest, WorldTaskExecutor cropTending, WorldTaskExecutor lureEntities,
			WorldTaskExecutor dragonFight, WorldTaskExecutor collectObsidian, WorldTaskExecutor locateStronghold,
			WorldTaskExecutor exploreFor, WorldTaskExecutor collectBlazeRods) {
			this(navigation, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction,
				blockBreak, acquisition, underwaterHarvest, cropTending, lureEntities, dragonFight, collectObsidian, locateStronghold,
				exploreFor, collectBlazeRods, entityInteraction);
		}

		public ExecutorSet(WorldTaskExecutor navigation, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak, WorldTaskExecutor acquisition,
			WorldTaskExecutor underwaterHarvest, WorldTaskExecutor cropTending, WorldTaskExecutor lureEntities,
			WorldTaskExecutor dragonFight, WorldTaskExecutor collectObsidian, WorldTaskExecutor locateStronghold,
			WorldTaskExecutor exploreFor) {
			this(navigation, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction,
				blockBreak, acquisition, underwaterHarvest, cropTending, lureEntities, dragonFight, collectObsidian, locateStronghold,
				exploreFor, entityInteraction);
		}

		public ExecutorSet(WorldTaskExecutor navigation, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak, WorldTaskExecutor acquisition,
			WorldTaskExecutor underwaterHarvest, WorldTaskExecutor cropTending, WorldTaskExecutor lureEntities,
			WorldTaskExecutor dragonFight, WorldTaskExecutor collectObsidian, WorldTaskExecutor locateStronghold) {
			this(navigation, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction,
				blockBreak, acquisition, underwaterHarvest, cropTending, lureEntities, dragonFight, collectObsidian, locateStronghold,
				entityInteraction);
		}

		public ExecutorSet(WorldTaskExecutor navigation, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak, WorldTaskExecutor acquisition,
			WorldTaskExecutor underwaterHarvest, WorldTaskExecutor cropTending, WorldTaskExecutor lureEntities,
			WorldTaskExecutor dragonFight, WorldTaskExecutor collectObsidian) {
			this(navigation, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction,
				blockBreak, acquisition, underwaterHarvest, cropTending, lureEntities, dragonFight, collectObsidian, entityInteraction);
		}

		public ExecutorSet(WorldTaskExecutor navigation, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak, WorldTaskExecutor acquisition,
			WorldTaskExecutor underwaterHarvest, WorldTaskExecutor cropTending) {
			this(navigation, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction,
				blockBreak, acquisition, underwaterHarvest, cropTending, entityInteraction);
		}

		public ExecutorSet(WorldTaskExecutor navigation, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak, WorldTaskExecutor acquisition,
			WorldTaskExecutor underwaterHarvest, WorldTaskExecutor cropTending, WorldTaskExecutor lureEntities) {
			this(navigation, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction,
				blockBreak, acquisition, underwaterHarvest, cropTending, lureEntities, entityInteraction);
		}

		public ExecutorSet(WorldTaskExecutor navigation, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak, WorldTaskExecutor acquisition,
			WorldTaskExecutor underwaterHarvest, WorldTaskExecutor cropTending, WorldTaskExecutor lureEntities,
			WorldTaskExecutor dragonFight) {
			this(navigation, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction,
				blockBreak, acquisition, underwaterHarvest, cropTending, lureEntities, dragonFight, entityInteraction);
		}

		public ExecutorSet(WorldTaskExecutor navigation, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak, WorldTaskExecutor acquisition, WorldTaskExecutor underwaterHarvest) {
			this(navigation, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction, blockBreak, acquisition, underwaterHarvest, blockInteraction);
		}

		public ExecutorSet(WorldTaskExecutor navigation, WorldTaskExecutor crafting, WorldTaskExecutor dropItems,
			WorldTaskExecutor entityInteraction, WorldTaskExecutor smelting, WorldTaskExecutor returnToSurface,
			WorldTaskExecutor blockInteraction, WorldTaskExecutor blockBreak) {
			this(navigation, crafting, dropItems, entityInteraction, smelting, returnToSurface, blockInteraction, blockBreak, navigation, navigation);
		}

		public ExecutorSet {
			Objects.requireNonNull(cropTending, "cropTending");
			Objects.requireNonNull(lureEntities, "lureEntities");
			Objects.requireNonNull(dragonFight, "dragonFight");
			Objects.requireNonNull(collectObsidian, "collectObsidian");
			Objects.requireNonNull(locateStronghold, "locateStronghold");
			Objects.requireNonNull(exploreFor, "exploreFor");
			Objects.requireNonNull(collectBlazeRods, "collectBlazeRods");
			Objects.requireNonNull(collectFlint, "collectFlint");
			Objects.requireNonNull(shelterForNight, "shelterForNight");
			Objects.requireNonNull(collectEnderPearls, "collectEnderPearls");
			Objects.requireNonNull(acquisition, "acquisition");
			Objects.requireNonNull(underwaterHarvest, "underwaterHarvest");
			Objects.requireNonNull(navigation, "navigation");
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
				case FOLLOW, NAVIGATE -> navigation;
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
				case FIGHT_ENDER_DRAGON -> dragonFight;
				case COLLECT_OBSIDIAN -> collectObsidian;
				case LOCATE_STRONGHOLD -> locateStronghold;
				case EXPLORE_FOR -> exploreFor;
				case COLLECT_BLAZE_RODS -> collectBlazeRods;
				case COLLECT_FLINT -> collectFlint;
				case SHELTER_FOR_NIGHT -> shelterForNight;
				case COLLECT_ENDER_PEARLS -> collectEnderPearls;
			};
		}

		List<WorldTaskExecutor> all() {
			return List.copyOf(new LinkedHashSet<>(List.of(
				navigation,
				crafting,
				dropItems,
				entityInteraction,
				smelting,
				returnToSurface,
				blockInteraction,
				blockBreak, acquisition, underwaterHarvest, cropTending, lureEntities, dragonFight, collectObsidian, locateStronghold, exploreFor, collectBlazeRods, collectFlint, shelterForNight, collectEnderPearls
			)));
		}
	}
}
