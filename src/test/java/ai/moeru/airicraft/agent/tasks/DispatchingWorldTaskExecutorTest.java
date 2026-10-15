package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchingWorldTaskExecutorTest {
	@Test void navigationStallAppliesToFollowAndCraftingApproach() {
		for (var request : List.of(
			WorldTaskRequest.direct("follow", new GoalSnapshot(GoalType.FOLLOW_PLAYER,"Alex",null,null,1L,"test")),
			WorldTaskRequest.craftRecipe("craft", "job", new CraftRecipeStepArgs("minecraft:stick",1)))) {
			var facade = new RecordingNavigation();
			var dispatcher = new Fixture().dispatcher(facade);
			dispatcher.tick(snapshot(), Optional.of(request));
			facade.progress = new NavigationFacade.NavigationProgress(0,64,0,true,null,0);
			assertTrue(dispatcher.tick(snapshot().withTickCount(10), Optional.of(request)).isEmpty());
			for (int tick=11; tick<110; tick++) {
				facade.progress = new NavigationFacade.NavigationProgress(0, tick%2==0?64:65.2,0,tick%2==0,null,0);
				assertTrue(dispatcher.tick(snapshot().withTickCount(tick), Optional.of(request)).isEmpty());
			}
			assertEquals(TaskExecutionState.FAILED, dispatcher.tick(snapshot().withTickCount(110), Optional.of(request)).orElseThrow().terminalState());
			assertTrue(dispatcher.tick(snapshot().withTickCount(111), Optional.of(request)).isEmpty());
		}
	}

	@Test void pathStuckSaysWhereTheBodyStoodAndWhereItWasGoing() {
		assertEquals("at=33,91,-8 toward=33,91,-7~3 breaking=33,90,-7 no movement or block-breaking progress for 100 ticks",
			DispatchingWorldTaskExecutor.stuckDetail(new NavigationFacade.NavigationProgress(33.4, 91, -7.2, true, "33,90,-7", 0.4f),
				Optional.of(new NavigationFacade.Destination(33, 91, -7, true, 3))));
		assertEquals("at=0,64,0 no movement or block-breaking progress for 100 ticks",
			DispatchingWorldTaskExecutor.stuckDetail(new NavigationFacade.NavigationProgress(0, 64, 0, true, null, 0), Optional.empty()));
	}

	@Test void miningHasItsOwnExecutorAndDoesNotReachTheNavigationMiningProcess() {
		var navigation = new RecordingExecutor();
		var acquisition = new RecordingExecutor();
		var underwater = new RecordingExecutor();
		var dispatcher = new DispatchingWorldTaskExecutor(new DispatchingWorldTaskExecutor.ExecutorSet(
			navigation, navigation, navigation, navigation, navigation, navigation, navigation, navigation, acquisition, underwater), null);
		dispatcher.tick(snapshot(), Optional.of(WorldTaskRequest.collectMine("mine", "job", miningGoal())));
		assertEquals(1, acquisition.calls.size());
		assertTrue(navigation.calls.isEmpty());
		assertTrue(underwater.calls.isEmpty());
	}

	@Test
	void requestEnvelopeNormalizesIdentityAndDerivesVariantMetadata() {
		GoalSnapshot goal = miningGoal();
		WorldTaskRequest request = new WorldTaskRequest(" mine ", " job ", new WorldTaskRequest.Mine(goal, List.of(), false));

		assertEquals("mine", request.taskId());
		assertEquals("job", request.sourceJobId());
		assertEquals(WorldTaskType.MINE, request.type());
		assertSame(goal, request.goal());
		assertThrows(IllegalArgumentException.class, () -> new WorldTaskRequest.Mine(navigationGoal(), List.of(), false));
	}

	@Test
	void routesEachTaskVariantToItsExecutor() {
		GoalSnapshot navigation = navigationGoal();
		GoalSnapshot mining = miningGoal();
		List<RouteCase> cases = List.of(
			new RouteCase(WorldTaskRequest.direct("follow", new GoalSnapshot(GoalType.FOLLOW_PLAYER, "Alex", null, null, 1L, "test")), Route.MINING),
			new RouteCase(WorldTaskRequest.direct("navigate", navigation), Route.MINING),
			new RouteCase(WorldTaskRequest.collectMine("mine", "job", mining), Route.MINING),
			new RouteCase(WorldTaskRequest.underwaterHarvest("harvest", "job", mining, new UnderwaterHarvestStepArgs(new GoalPosition(2, 52, -4, true))), Route.MINING),
			new RouteCase(WorldTaskRequest.craftRecipe("craft", "job", new CraftRecipeStepArgs("minecraft:stick", 1)), Route.CRAFTING),
			new RouteCase(WorldTaskRequest.dropItems("drop", "job", new DropItemsStepArgs("minecraft:stone", 1, null)), Route.DROP_ITEMS),
			new RouteCase(WorldTaskRequest.attackEntity("attack", "job", interaction()), Route.ENTITY),
			new RouteCase(WorldTaskRequest.useEntity("use-entity", "job", interaction()), Route.ENTITY),
			new RouteCase(WorldTaskRequest.smeltItems("smelt", "job", new SmeltItemsStepArgs("option", 1, SmeltingFuelMode.AUTO, null, 0, null)), Route.SMELTING),
			new RouteCase(WorldTaskRequest.collectSmeltedItems("collect", "job", new CollectSmeltedItemsStepArgs("process", "confirm")), Route.SMELTING),
			new RouteCase(WorldTaskRequest.returnToSurface("surface", "job", new ReturnToSurfaceStepArgs(null, "nearest_surface", false, List.of())), Route.SURFACE),
			new RouteCase(WorldTaskRequest.placeBlock("place", "job", new BlockPlacementStepArgs("minecraft:dirt", new GoalPosition(1, 64, 2, true), "down", "air")), Route.BLOCK_INTERACTION),
			new RouteCase(WorldTaskRequest.useBlock("use-block", "job", new BlockUseStepArgs("minecraft:stick", new GoalPosition(1, 64, 2, true), "down", List.of(), "air")), Route.BLOCK_INTERACTION),
			new RouteCase(WorldTaskRequest.breakBlocks("break", "job", new BlockBreakStepArgs(List.of(new BlockBreakStepArgs.Target(new GoalPosition(1, 64, 2, true), List.of("minecraft:stone"))))), Route.BLOCK_BREAK)
		);

		for (RouteCase routeCase : cases) {
			Fixture fixture = new Fixture();
			fixture.dispatcher(null).tick(snapshot(), Optional.of(routeCase.request()));

			assertEquals(routeCase.request(), fixture.executor(routeCase.route()).lastTask.orElseThrow());
			assertEquals(1, fixture.all().stream().mapToInt(executor -> executor.calls.size()).sum());
		}
	}

	@Test
	void pickupEvidenceRoutesToDropItemsExecutor() {
		Fixture fixture = new Fixture();
		UUID entityUuid = UUID.randomUUID();
		UUID collectorIdentity = UUID.randomUUID();
		UUID observationId = UUID.randomUUID();
		fixture.dispatcher(null).onPlayerItemPickupObserved(
			42, entityUuid, "minecraft:oak_log", 1, 2, collectorIdentity, observationId
		);

		assertEquals(42, fixture.dropItems.pickupEntityId);
		assertEquals(entityUuid, fixture.dropItems.pickupEntityUuid);
		assertEquals("minecraft:oak_log", fixture.dropItems.pickupItemId);
		assertEquals(1, fixture.dropItems.pickupDelta);
		assertEquals(2, fixture.dropItems.agentAttributedQuantity);
		assertEquals(collectorIdentity, fixture.dropItems.pickupCollectorIdentity);
		assertEquals(observationId, fixture.dropItems.pickupObservationId);
	}

	@Test
	void lifecycleBroadcastDeduplicatesExecutorInstancesSharedAcrossRoles() {
		RecordingExecutor shared = new RecordingExecutor();
		DispatchingWorldTaskExecutor dispatcher = new DispatchingWorldTaskExecutor(
			new DispatchingWorldTaskExecutor.ExecutorSet(shared, shared, shared, shared, shared, shared, shared, shared),
			null
		);

		dispatcher.onWorldLeave();
		dispatcher.shutdown();

		assertEquals(1, shared.worldLeaveCalls);
		assertEquals(1, shared.shutdownCalls);
	}

	@Test
	void aReflexPauseKeepsTheActiveTaskAndItsExecutorsState() {
		Fixture fixture = new Fixture();
		ResumableExecutor resumable = new ResumableExecutor();
		RecordingNavigation navigation = new RecordingNavigation();
		DispatchingWorldTaskExecutor dispatcher = fixture.dispatcher(navigation, resumable);
		WorldTaskRequest craft = WorldTaskRequest.craftRecipe("craft", "job", new CraftRecipeStepArgs("minecraft:stick", 1));
		for (int i = 0; i < 3; i++) dispatcher.tick(snapshot(), Optional.of(craft));
		assertEquals(3, resumable.progress);

		dispatcher.pauseForReflex();
		assertEquals(1, resumable.pauses, "the executor lets go of the body");
		assertEquals(0, resumable.resets);
		assertEquals(1, fixture.mining.worldLeaveCalls, "an executor that cannot resume still resets on the pause");
		navigation.active = true; // the reflex's own route, still set when the hold ends

		dispatcher.tick(snapshot(), Optional.of(craft));
		assertEquals(4, resumable.progress, "the same request goes on where it left off");
		assertEquals(0, resumable.resets, "no deactivating tick between the pause and the resume");
		assertEquals(1, navigation.cancelCalls, "what the reflex left running is ended before the task goes on");
		assertFalse(resumable.calls.contains(Optional.empty()));

		dispatcher.tick(snapshot(), Optional.of(craft));
		assertEquals(5, resumable.progress);
		assertEquals(1, navigation.cancelCalls);
	}

	@Test
	void aWorldLeaveStillResetsTheTask() {
		Fixture fixture = new Fixture();
		ResumableExecutor resumable = new ResumableExecutor();
		DispatchingWorldTaskExecutor dispatcher = fixture.dispatcher(null, resumable);
		WorldTaskRequest craft = WorldTaskRequest.craftRecipe("craft", "job", new CraftRecipeStepArgs("minecraft:stick", 1));
		for (int i = 0; i < 3; i++) dispatcher.tick(snapshot(), Optional.of(craft));

		dispatcher.onWorldLeave();
		dispatcher.tick(snapshot(), Optional.of(craft));
		assertEquals(1, resumable.resets);
		assertEquals(1, resumable.progress, "after leaving the world the same request starts over");
	}

	@Test
	void anotherRequestAfterAReflexPauseStillDeactivatesThePausedExecutor() {
		Fixture fixture = new Fixture();
		ResumableExecutor resumable = new ResumableExecutor();
		DispatchingWorldTaskExecutor dispatcher = fixture.dispatcher(null, resumable);
		WorldTaskRequest craft = WorldTaskRequest.craftRecipe("craft", "job", new CraftRecipeStepArgs("minecraft:stick", 1));
		dispatcher.tick(snapshot(), Optional.of(craft));
		dispatcher.tick(snapshot(), Optional.of(craft));

		dispatcher.pauseForReflex();
		dispatcher.tick(snapshot(), Optional.empty()); // the job was cancelled during the hold
		assertEquals(Optional.empty(), resumable.calls.getLast());
		assertEquals(1, resumable.resets);
		dispatcher.tick(snapshot(), Optional.of(WorldTaskRequest.craftRecipe("craft2", "job2", new CraftRecipeStepArgs("minecraft:stick", 1))));
		assertEquals(1, resumable.progress);
	}

	@Test
	void onlyActiveExecutorReceivesOneInactiveTransition() {
		Fixture fixture = new Fixture();
		DispatchingWorldTaskExecutor dispatcher = fixture.dispatcher(null);
		WorldTaskRequest attack = WorldTaskRequest.attackEntity("attack", "job", interaction());

		dispatcher.tick(snapshot(), Optional.empty());
		dispatcher.tick(snapshot(), Optional.of(WorldTaskRequest.direct("nav", navigationGoal())));
		dispatcher.tick(snapshot(), Optional.of(attack));
		dispatcher.tick(snapshot(), Optional.of(attack));
		dispatcher.tick(snapshot(), Optional.empty());
		dispatcher.tick(snapshot(), Optional.empty());

		assertEquals(List.of("NAVIGATE", "inactive"), types(fixture.mining.calls));
		assertEquals(List.of("ATTACK_ENTITY", "ATTACK_ENTITY", "inactive"), types(fixture.entity.calls));
		assertTrue(fixture.all().stream()
			.filter(executor -> executor != fixture.mining && executor != fixture.entity)
			.allMatch(executor -> executor.calls.isEmpty()));
	}

	@Test
	void typeChangeReleasesNavigationInTheSameTickEvenWhenVariantsShareExecutor() {
		Fixture fixture = new Fixture();
		RecordingNavigation navigation = new RecordingNavigation();
		DispatchingWorldTaskExecutor dispatcher = fixture.dispatcher(navigation);
		WorldTaskRequest mine = WorldTaskRequest.collectMine("mine", "job", miningGoal());

		dispatcher.tick(snapshot(), Optional.of(WorldTaskRequest.direct("nav", navigationGoal())));
		navigation.active = true;
		dispatcher.tick(snapshot(), Optional.of(mine));

		assertEquals(WorldTaskType.MINE, fixture.mining.lastTask.orElseThrow().type());
		assertEquals(1, navigation.cancelCalls);
		assertFalse(navigation.active, "no wait: the previous owner is released before the next one runs");
	}

	@Test
	void snapshotAndTerminalEventRemainOwnedByActiveExecutor() {
		Fixture fixture = new Fixture();
		DispatchingWorldTaskExecutor dispatcher = fixture.dispatcher(null);
		WorldTaskRequest craft = WorldTaskRequest.craftRecipe("craft", "job", new CraftRecipeStepArgs("minecraft:stick", 1));
		TaskTerminalEvent terminal = new TaskTerminalEvent("craft", null, TaskExecutionState.COMPLETED, "done", TaskTerminationCause.GOAL_REACHED);
		fixture.crafting.currentSnapshot = new TaskExecutionSnapshot(TaskExecutionState.RUNNING, "craft", null, "Crafting", "working", null, null);
		fixture.crafting.terminal = Optional.of(terminal);

		assertEquals(Optional.of(terminal), dispatcher.tick(snapshot(), Optional.of(craft)));
		assertSame(fixture.crafting.currentSnapshot, dispatcher.snapshot());
	}

	private static List<String> types(List<Optional<WorldTaskRequest>> calls) {
		return calls.stream().map(call -> call.map(request -> request.type().name()).orElse("inactive")).toList();
	}

	private static EntityInteractionStepArgs interaction() {
		return new EntityInteractionStepArgs(new EntitySelector(null, "Alex", "minecraft:sheep"), null);
	}

	private static GoalSnapshot navigationGoal() {
		return new GoalSnapshot(GoalType.NAVIGATE_TO, null, new GoalPosition(10, 64, 20, true), null, 1L, "test");
	}

	private static GoalSnapshot miningGoal() {
		return new GoalSnapshot(GoalType.MINE_BLOCKS, null, null, new GoalMineSpec(List.of("minecraft:stone"), 1), 1L, "test");
	}

	private static SessionSnapshot snapshot() {
		return new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, 0L);
	}

	private enum Route { MINING, CRAFTING, DROP_ITEMS, ENTITY, SMELTING, SURFACE, BLOCK_INTERACTION, BLOCK_BREAK }

	private record RouteCase(WorldTaskRequest request, Route route) { }

	private static final class Fixture {
		private final RecordingExecutor mining = new RecordingExecutor();
		private final RecordingExecutor crafting = new RecordingExecutor();
		private final RecordingExecutor dropItems = new RecordingExecutor();
		private final RecordingExecutor entity = new RecordingExecutor();
		private final RecordingExecutor smelting = new RecordingExecutor();
		private final RecordingExecutor surface = new RecordingExecutor();
		private final RecordingExecutor blockInteraction = new RecordingExecutor();
		private final RecordingExecutor blockBreak = new RecordingExecutor();

		private DispatchingWorldTaskExecutor dispatcher(NavigationFacade navigationFacade) {
			return dispatcher(navigationFacade, crafting);
		}

		private DispatchingWorldTaskExecutor dispatcher(NavigationFacade navigationFacade, WorldTaskExecutor craftingExecutor) {
			return new DispatchingWorldTaskExecutor(new DispatchingWorldTaskExecutor.ExecutorSet(
				mining, craftingExecutor, dropItems, entity, smelting, surface, blockInteraction, blockBreak
			), navigationFacade);
		}

		private RecordingExecutor executor(Route route) {
			return switch (route) {
				case MINING -> mining;
				case CRAFTING -> crafting;
				case DROP_ITEMS -> dropItems;
				case ENTITY -> entity;
				case SMELTING -> smelting;
				case SURFACE -> surface;
				case BLOCK_INTERACTION -> blockInteraction;
				case BLOCK_BREAK -> blockBreak;
			};
		}

		private List<RecordingExecutor> all() {
			return List.of(mining, crafting, dropItems, entity, smelting, surface, blockInteraction, blockBreak);
		}
	}

	private static final class RecordingExecutor implements WorldTaskExecutor {
		private Optional<WorldTaskRequest> lastTask = Optional.empty();
		private final List<Optional<WorldTaskRequest>> calls = new ArrayList<>();
		private Optional<TaskTerminalEvent> terminal = Optional.empty();
		private TaskExecutionSnapshot currentSnapshot = TaskExecutionSnapshot.idle();
		private int pickupEntityId;
		private UUID pickupEntityUuid;
		private String pickupItemId;
		private int pickupDelta;
		private int agentAttributedQuantity;
		private UUID pickupCollectorIdentity;
		private UUID pickupObservationId;
		private int worldLeaveCalls;
		private int shutdownCalls;

		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			lastTask = activeTask;
			calls.add(activeTask);
			return activeTask.isEmpty() ? Optional.empty() : terminal;
		}

		@Override
		public void onPlayerItemPickupObserved(int entityId, UUID entityUuid, String itemId, int pickupDelta, int agentAttributedQuantity, UUID collectorIdentity, UUID observationId) {
			pickupEntityId = entityId;
			pickupEntityUuid = entityUuid;
			pickupItemId = itemId;
			this.pickupDelta = pickupDelta;
			this.agentAttributedQuantity = agentAttributedQuantity;
			pickupCollectorIdentity = collectorIdentity;
			pickupObservationId = observationId;
		}

		@Override public TaskExecutionSnapshot snapshot() { return currentSnapshot; }
		@Override public void onWorldLeave() { worldLeaveCalls++; }
		@Override public void shutdown() { shutdownCalls++; }
	}

	/** Counts the ticks of one task; a new task id, an inactive tick or leaving the world starts it over. */
	private static final class ResumableExecutor implements WorldTaskExecutor {
		private final List<Optional<WorldTaskRequest>> calls = new ArrayList<>();
		private String taskId;
		private int progress, resets, pauses;

		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			calls.add(activeTask);
			if (activeTask.isEmpty()) {
				reset();
				return Optional.empty();
			}
			if (!activeTask.get().taskId().equals(taskId)) {
				if (taskId != null) reset();
				taskId = activeTask.get().taskId();
			}
			progress++;
			return Optional.empty();
		}

		private void reset() {
			resets++;
			taskId = null;
			progress = 0;
		}

		@Override public TaskExecutionSnapshot snapshot() { return TaskExecutionSnapshot.idle(); }
		@Override public void onWorldLeave() { reset(); }
		@Override public void pauseForReflex() { pauses++; }
		@Override public void shutdown() { }
	}

	private static final class RecordingNavigation implements NavigationFacade {
		private NavigationFacade.NavigationProgress progress;
		@Override public Optional<NavigationFacade.NavigationProgress> navigationProgress() { return Optional.ofNullable(progress); }
		private boolean active;
		private int cancelCalls;

		@Override public boolean isLoaded() { return true; }
		@Override public void startFollow(String playerName) { }
		@Override public void startNavigate(GoalPosition position, NavigationOptions options) { }
		@Override public void startNavigateNear(GoalPosition position, int radiusBlocks, NavigationOptions options) { }
		@Override public boolean processActive() { return active; }
		@Override public void cancel() {
			if (active) {
				cancelCalls++;
				active = false;
			}
		}
		@Override public Optional<String> activeProcessName() { return Optional.empty(); }
		@Override public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		@Override public Optional<String> pollPathEvent() { return Optional.empty(); }
		@Override public boolean navigationGoalReached(GoalPosition position) { return false; }
	}
}
