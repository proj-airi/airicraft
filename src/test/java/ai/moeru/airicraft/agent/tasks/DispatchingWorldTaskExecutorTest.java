package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchingWorldTaskExecutorTest {
	@Test void navigationStallAppliesToFollowAndCraftingApproach() {
		for (var request : List.of(
			WorldTaskRequest.direct("follow", new GoalSnapshot(GoalType.FOLLOW_PLAYER,"Alex",null,null,1L,"test")),
			WorldTaskRequest.craftRecipe("craft", "job", new CraftRecipeStepArgs("minecraft:stick",1)))) {
			var facade = new RecordingBaritone();
			var dispatcher = new Fixture().dispatcher(facade);
			dispatcher.tick(snapshot(), Optional.of(request));
			facade.progress = new BaritoneFacade.NavigationProgress(0,64,0,true,null,0);
			assertTrue(dispatcher.tick(snapshot().withTickCount(10), Optional.of(request)).isEmpty());
			for (int tick=11; tick<110; tick++) {
				facade.progress = new BaritoneFacade.NavigationProgress(0, tick%2==0?64:65.2,0,tick%2==0,null,0);
				assertTrue(dispatcher.tick(snapshot().withTickCount(tick), Optional.of(request)).isEmpty());
			}
			assertEquals(TaskExecutionState.FAILED, dispatcher.tick(snapshot().withTickCount(110), Optional.of(request)).orElseThrow().terminalState());
			assertTrue(dispatcher.tick(snapshot().withTickCount(111), Optional.of(request)).isEmpty());
		}
	}

	@Test void miningHasItsOwnExecutorAndDoesNotReachTheBaritoneMiningProcess() {
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
	void typeChangeUsesReleaseBarrierEvenWhenVariantsShareExecutor() {
		Fixture fixture = new Fixture();
		RecordingBaritone baritone = new RecordingBaritone();
		DispatchingWorldTaskExecutor dispatcher = fixture.dispatcher(baritone);
		WorldTaskRequest mine = WorldTaskRequest.collectMine("mine", "job", miningGoal());

		dispatcher.tick(snapshot(), Optional.of(WorldTaskRequest.direct("nav", navigationGoal())));
		baritone.active = true;
		assertTrue(dispatcher.tick(snapshot(), Optional.of(mine)).isEmpty());
		assertEquals("waiting_for_previous_baritone_release", dispatcher.snapshot().lastPathEvent());
		assertEquals(List.of("NAVIGATE", "inactive"), types(fixture.mining.calls));

		baritone.cancellationPending = false;
		dispatcher.tick(snapshot(), Optional.of(mine));

		assertEquals(WorldTaskType.MINE, fixture.mining.lastTask.orElseThrow().type());
		assertEquals(1, baritone.cancelCalls);
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

		private DispatchingWorldTaskExecutor dispatcher(BaritoneFacade baritone) {
			return new DispatchingWorldTaskExecutor(new DispatchingWorldTaskExecutor.ExecutorSet(
				mining, crafting, dropItems, entity, smelting, surface, blockInteraction, blockBreak
			), baritone);
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

	private static final class RecordingBaritone implements BaritoneFacade {
		private BaritoneFacade.NavigationProgress progress;
		@Override public Optional<BaritoneFacade.NavigationProgress> navigationProgress() { return Optional.ofNullable(progress); }
		private boolean active;
		private boolean cancellationPending;
		private int cancelCalls;

		@Override public boolean isLoaded() { return true; }
		@Override public void applySettings() { }
		@Override public double walkOnWaterPenalty() { return 0.0D; }
		@Override public void setWalkOnWaterPenalty(double value) { }
		@Override public void startFollow(String playerName) { }
		@Override public void startNavigate(GoalPosition position) { }
		@Override public void startNavigateNear(GoalPosition position, int radiusBlocks) { }
		@Override public void startMine(GoalMineSpec spec) { }
		@Override public boolean mineProcessActive() { return active; }
		@Override public boolean processActive() { return active; }
		@Override public boolean cancel() {
			if (active && !cancellationPending) {
				cancelCalls++;
				active = false;
				cancellationPending = true;
			}
			return cancellationPending;
		}
		@Override public boolean cancellationPending() { return cancellationPending; }
		@Override public Optional<String> activeProcessName() { return Optional.empty(); }
		@Override public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		@Override public Optional<String> pollPathEvent() { return Optional.empty(); }
		@Override public boolean navigationGoalReached(GoalPosition position) { return false; }
	}
}
