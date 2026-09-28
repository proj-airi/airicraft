package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.PlayerLifecycleState;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneTaskExecutorTest {
	@Test
	void waterStallTemporarilyRaisesPenaltyReplansAndRestoresAfterProgress() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		AtomicReference<Optional<WaterStallRecovery.Sample>> waterSample = new AtomicReference<>(Optional.of(
			new WaterStallRecovery.Sample(true, 4.0D, 62.0D, -8.0D)
		));
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade, waterSample::get);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(40, 62, -8, true),
			null,
			0L,
			"action_graph"
		);
		WorldTaskRequest request = request("river-task", goal);

		executor.tick(multiplayerAt(0L), Optional.of(request));
		executor.tick(multiplayerAt(WaterStallRecovery.STALL_TICKS - 1L), Optional.of(request));
		assertTrue(facade.waterPenaltyChanges.isEmpty());

		executor.tick(multiplayerAt(WaterStallRecovery.STALL_TICKS), Optional.of(request));

		assertEquals(List.of(12.0D), facade.waterPenaltyChanges);
		assertEquals(1, facade.cancelCalls);
		assertEquals(2, facade.navigateCalls.size());
		assertEquals("WATER_STALL_REPLAN", executor.snapshot().lastPathEvent());

		waterSample.set(Optional.of(new WaterStallRecovery.Sample(true, 6.0D, 62.0D, -8.0D)));
		executor.tick(multiplayerAt(WaterStallRecovery.STALL_TICKS + 1L), Optional.of(request));

		assertEquals(List.of(12.0D, 3.0D), facade.waterPenaltyChanges);
		assertEquals("WATER_STALL_RECOVERED", executor.snapshot().lastPathEvent());
	}

	@Test
	void sessionGateDuringWaterReplanPreservesTheDeferredGoal() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		facade.activateOnStart = true;
		AtomicReference<Optional<WaterStallRecovery.Sample>> waterSample = new AtomicReference<>(Optional.of(
			new WaterStallRecovery.Sample(true, 4.0D, 62.0D, -8.0D)
		));
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade, waterSample::get);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(40, 62, -8, true),
			null,
			0L,
			"action_graph"
		);
		WorldTaskRequest request = request("river-task", goal);

		executor.tick(multiplayerAt(0L), Optional.of(request));
		executor.tick(multiplayerAt(WaterStallRecovery.STALL_TICKS), Optional.of(request));
		assertEquals("WATER_STALL_RELEASING", executor.snapshot().lastPathEvent());
		assertEquals(1, facade.navigateCalls.size());

		executor.tick(singleplayerLocal(), Optional.of(request));
		facade.processActive = false;
		executor.tick(multiplayerAt(WaterStallRecovery.STALL_TICKS + 1L), Optional.of(request));

		assertEquals("WATER_STALL_REPLAN", executor.snapshot().lastPathEvent());
		assertEquals(2, facade.navigateCalls.size());
	}

	@Test
	void taskRemovalRestoresTemporaryWaterPenaltyWithoutWaitingForMovement() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		AtomicReference<Optional<WaterStallRecovery.Sample>> waterSample = new AtomicReference<>(Optional.of(
			new WaterStallRecovery.Sample(true, 4.0D, 62.0D, -8.0D)
		));
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade, waterSample::get);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 40, 20, true),
			null,
			0L,
			"planner_response"
		);
		WorldTaskRequest request = request("nav-task", goal);

		executor.tick(multiplayerAt(0L), Optional.of(request));
		executor.tick(multiplayerAt(WaterStallRecovery.STALL_TICKS), Optional.of(request));
		executor.tick(multiplayerAt(WaterStallRecovery.STALL_TICKS + 1L), Optional.empty());

		assertEquals(List.of(12.0D, 3.0D), facade.waterPenaltyChanges);
	}


	@Test
	void navigateGoalStartsOnceAndReportsRunning() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertEquals(1, facade.applySettingsCalls);
		assertEquals(1, facade.navigateCalls.size());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
	}

	@Test
	void navigationStallFailsAndCancelsOnceAfterFiveSecondsDespiteJitter() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		java.util.concurrent.atomic.AtomicReference<WaterStallRecovery.Sample> sample =
			new java.util.concurrent.atomic.AtomicReference<>(new WaterStallRecovery.Sample(false, -50.5, 66, -102.5));
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade, () -> Optional.of(sample.get()));
		WorldTaskRequest task = request("stuck-door", new GoalSnapshot(GoalType.NAVIGATE_TO, null,
			new GoalPosition(-51, 66, -104, true), null, 0, "planner_tool"));
		assertTrue(executor.tick(multiplayerAt(0), Optional.of(task)).isEmpty());
		sample.set(new WaterStallRecovery.Sample(false, -50.51, 66, -102.51));
		assertTrue(executor.tick(multiplayerAt(99), Optional.of(task)).isEmpty());
		TaskTerminalEvent event = executor.tick(multiplayerAt(100), Optional.of(task)).orElseThrow();
		assertEquals(TaskExecutionState.FAILED, event.terminalState());
		assertTrue(event.message().contains("navigation_stuck"));
		assertEquals(true, navigationDiagnostics(event).get("stalled"));
		assertEquals(3L, navigationDiagnostics(event).get("activeTicks"));
		assertEquals(1, facade.cancelCalls);
		assertTrue(executor.tick(multiplayerAt(101), Optional.of(task)).isEmpty());
		assertEquals(1, facade.cancelCalls);
	}

	@Test
	void navigationArrivalReportsTravelDiagnostics() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		AtomicReference<WaterStallRecovery.Sample> sample =
			new AtomicReference<>(new WaterStallRecovery.Sample(false, 0.5D, 64.0D, 0.5D));
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade, () -> Optional.of(sample.get()));
		WorldTaskRequest task = request("walk", new GoalSnapshot(GoalType.NAVIGATE_TO, null,
			new GoalPosition(3, 64, 4, true), null, 0, "planner_tool"));

		executor.tick(multiplayerAt(10), Optional.of(task));
		sample.set(new WaterStallRecovery.Sample(false, 3.5D, 64.0D, 0.5D));
		executor.tick(multiplayerAt(11), Optional.of(task));
		sample.set(new WaterStallRecovery.Sample(false, 3.5D, 64.0D, 4.5D));
		facade.navigationGoalReached = true;
		facade.pathEvents.add("AT_GOAL");
		TaskTerminalEvent event = executor.tick(multiplayerAt(14), Optional.of(task)).orElseThrow();

		assertEquals(TaskExecutionState.COMPLETED, event.terminalState());
		Map<String, Object> navigation = navigationDiagnostics(event);
		assertEquals(4L, navigation.get("elapsedTicks"));
		assertEquals(3L, navigation.get("activeTicks"));
		assertEquals(0, navigation.get("replans"));
		assertEquals(false, navigation.get("stalled"));
		assertEquals(7.0D, navigation.get("pathLength"));
		assertEquals(5.0D, navigation.get("startDistance"));
		assertEquals(0.0D, navigation.get("endDistance"));
	}

	@Test
	void navigationMovementAndSessionPauseRestartStallWindow() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		java.util.concurrent.atomic.AtomicReference<WaterStallRecovery.Sample> sample =
			new java.util.concurrent.atomic.AtomicReference<>(new WaterStallRecovery.Sample(false, 0, 64, 0));
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade, () -> Optional.of(sample.get()));
		WorldTaskRequest task = request("moving", new GoalSnapshot(GoalType.NAVIGATE_TO, null,
			new GoalPosition(20, 64, 20, true), null, 0, "planner_tool"));
		executor.tick(multiplayerAt(0), Optional.of(task));
		sample.set(new WaterStallRecovery.Sample(false, 1, 64, 0));
		assertTrue(executor.tick(multiplayerAt(90), Optional.of(task)).isEmpty());
		assertTrue(executor.tick(multiplayerAt(189), Optional.of(task)).isEmpty());
		executor.tick(singleplayerLocal(), Optional.of(task));
		assertTrue(executor.tick(multiplayerAt(500), Optional.of(task)).isEmpty());
		assertTrue(executor.tick(multiplayerAt(599), Optional.of(task)).isEmpty());
		assertEquals(TaskExecutionState.FAILED,
			executor.tick(multiplayerAt(600), Optional.of(task)).orElseThrow().terminalState());
	}

	@Test
	void replacementNavigationGetsFreshBudgetAndArrivalWinsOverStall() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade,
			() -> Optional.of(new WaterStallRecovery.Sample(false, 0, 64, 0)));
		GoalSnapshot goal = new GoalSnapshot(GoalType.NAVIGATE_TO, null,
			new GoalPosition(20, 64, 20, true), null, 0, "planner_tool");
		executor.tick(multiplayerAt(0), Optional.of(request("first", goal)));
		executor.tick(multiplayerAt(99), Optional.of(request("second", goal)));
		assertTrue(executor.tick(multiplayerAt(100), Optional.of(request("second", goal))).isEmpty());
		facade.navigationGoalReached = true;
		facade.pathEvents.add("AT_GOAL");
		assertEquals(TaskExecutionState.COMPLETED,
			executor.tick(multiplayerAt(199), Optional.of(request("second", goal))).orElseThrow().terminalState());
	}

	@Test
	void deathGateCancelsActiveBaritoneProcess() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);
		WorldTaskRequest request = request("nav-task", goal);

		executor.tick(multiplayer(), Optional.of(request));
		executor.tick(deadMultiplayer(), Optional.of(request));

		assertEquals(1, facade.cancelCalls);
		assertEquals(TaskExecutionState.PAUSED_BY_SESSION_GATE, executor.snapshot().state());
	}

	@Test
	void terminalPathEventBecomesCompletedTaskEvent() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		facade.navigationGoalReached = true;
		facade.pathEvents.add("AT_GOAL");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.COMPLETED, event.orElseThrow().terminalState());
		assertEquals(TaskTerminationCause.GOAL_REACHED, event.orElseThrow().terminationCause());
	}

	@Test
	void navigationCalculationFailureRemainsTerminal() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);
		WorldTaskRequest request = request("nav-task", goal);

		executor.tick(multiplayer(), Optional.of(request));
		facade.pathEvents.add("CALC_FAILED");

		Optional<TaskTerminalEvent> calculationFailure = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(calculationFailure.isPresent());
		assertEquals(TaskExecutionState.FAILED, calculationFailure.orElseThrow().terminalState());
		assertEquals(TaskTerminationCause.CALCULATION_FAILED, calculationFailure.orElseThrow().terminationCause());
	}

	@Test
	void cancelledPathEventVariantIsRecognizedAndSnapshotStaysTerminal() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(1, 64, 1, true),
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		facade.pathEvents.add("cancelled");

		assertTrue(executor.tick(multiplayerAt(20), Optional.of(request("nav-task", goal))).isEmpty());
		Optional<TaskTerminalEvent> event = executor.tick(multiplayerAt(30), Optional.of(request("nav-task", goal)));

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.CANCELLED, event.orElseThrow().terminalState());
		assertEquals(TaskTerminationCause.BARITONE_CANCELLED, event.orElseThrow().terminationCause());
		assertEquals(TaskExecutionState.CANCELLED, executor.snapshot().state());

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		assertEquals(TaskExecutionState.CANCELLED, executor.snapshot().state());
	}

	@Test
	void pathGoalEventCannotClaimArrivalWithoutPhysicalConfirmation() {
		var facade = new FakeBaritoneFacade();
		var executor = new BaritoneTaskExecutor(facade);
		var goal = new GoalSnapshot(GoalType.NAVIGATE_TO, null, new GoalPosition(318,-10,280,true),null,20L,"planner_tool");
		executor.tick(multiplayer(),Optional.of(request("falling",goal)));
		facade.pathEvents.add("AT_GOAL");
		assertTrue(executor.tick(multiplayerAt(20),Optional.of(request("falling",goal))).isEmpty());
		var event=executor.tick(multiplayerAt(30),Optional.of(request("falling",goal))).orElseThrow();
		assertEquals(TaskExecutionState.FAILED,event.terminalState());
		assertEquals("navigation_arrival_unconfirmed",event.message());
	}

	@Test
	void normalStepCanLandAfterPathEndWithoutReissuingMovement() {
		var facade = new FakeBaritoneFacade();
		var executor = new BaritoneTaskExecutor(facade);
		var goal = new GoalSnapshot(GoalType.NAVIGATE_TO,null,new GoalPosition(319,-9,281,true),null,20L,"planner_tool");
		var request = request("step-up",goal);
		executor.tick(multiplayerAt(20),Optional.of(request));
		facade.pathEvents.add("CANCELED");
		assertTrue(executor.tick(multiplayerAt(21),Optional.of(request)).isEmpty());
		facade.navigationGoalReached = true;
		assertEquals(TaskExecutionState.COMPLETED,executor.tick(multiplayerAt(23),Optional.of(request)).orElseThrow().terminalState());
		assertEquals(1, facade.navigateCalls.size());
	}

	@Test
	void cancelledNavigateAtReachedGoalCountsAsCompleted() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		facade.navigationGoalReached = true;
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(-7, 68, -4, true),
			null,
			20L,
			"verification"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		facade.pathEvents.add("CANCELED");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.COMPLETED, event.orElseThrow().terminalState());
		assertEquals(TaskExecutionState.COMPLETED, executor.snapshot().state());
	}

	@Test
	void failedCalculationAtReachedNavigateGoalCountsAsCompleted() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		facade.navigationGoalReached = true;
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(-7, 68, -4, true),
			null,
			20L,
			"verification"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		facade.pathEvents.add("CALC_FAILED");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.COMPLETED, event.orElseThrow().terminalState());
		assertEquals(TaskTerminationCause.GOAL_REACHED, event.orElseThrow().terminationCause());
	}

	@Test
	void repeatedTerminalPathEventsOnlyEmitOneTerminalCallbackPerGoal() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		facade.pathEvents.add("CANCELED");
		facade.pathEvents.add("CANCELED");

		assertTrue(executor.tick(multiplayerAt(20), Optional.of(request("nav-task", goal))).isEmpty());
		Optional<TaskTerminalEvent> first = executor.tick(multiplayerAt(30), Optional.of(request("nav-task", goal)));
		Optional<TaskTerminalEvent> second = executor.tick(multiplayerAt(31), Optional.of(request("nav-task", goal)));

		assertTrue(first.isPresent());
		assertTrue(second.isEmpty());
		assertEquals(TaskExecutionState.CANCELLED, executor.snapshot().state());
	}

	@Test
	void followRemainsRunningAfterReachingTarget() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"LanAlice",
			null,
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));
		facade.pathEvents.add("AT_GOAL");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("AT_GOAL", executor.snapshot().lastPathEvent());
		assertEquals(List.of("LanAlice"), facade.followCalls);
	}

	@Test
	void cancelledFollowRearmsWithoutCompletingTask() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"LanAlice",
			null,
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));
		facade.pathEvents.add("CANCELED");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("FOLLOW_REACQUIRING", executor.snapshot().lastPathEvent());
		assertEquals(List.of("LanAlice", "LanAlice"), facade.followCalls);
	}

	@Test
	void failedFollowCalculationRearmsWithoutCompletingTask() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"LanAlice",
			null,
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));
		facade.pathEvents.add("CALC_FAILED");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("FOLLOW_REACQUIRING", executor.snapshot().lastPathEvent());
		assertEquals(List.of("LanAlice", "LanAlice"), facade.followCalls);
	}

	@Test
	void clearingFollowExplicitlyCancelsWithoutRearming() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"LanAlice",
			null,
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));
		executor.tick(multiplayer(), Optional.empty());

		assertEquals(TaskExecutionState.IDLE, executor.snapshot().state());
		assertEquals(1, facade.cancelCalls);
		assertEquals(List.of("LanAlice"), facade.followCalls);
	}

	@Test
	void sameGoalTargetWithDifferentTickDoesNotRestartPathing() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot first = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);
		GoalSnapshot second = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			21L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", first)));
		executor.tick(multiplayer(), Optional.of(request("nav-task", second)));

		assertEquals(1, facade.navigateCalls.size());
	}

	@Test
	void sessionGatePausesWithoutCancellingGoalState() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"Alice",
			null,
			null,
			20L,
			"planner_response"
		);

		executor.tick(singleplayerLocal(), Optional.of(request("follow-task", goal)));

		assertEquals(TaskExecutionState.PAUSED_BY_SESSION_GATE, executor.snapshot().state());
		assertEquals("follow-task", executor.snapshot().taskId());
		assertEquals(goal, executor.snapshot().activeGoal());
		assertEquals(0, facade.followCalls.size());
	}

	@Test
	void noGoalStaysIdleEvenWhenActuationIsBlocked() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);

		executor.tick(singleplayerLocal(), Optional.empty());

		assertEquals(TaskExecutionState.IDLE, executor.snapshot().state());
	}

	@Test
	void replacingNavigationTaskDoesNotSurfaceInternalCancelledEvent() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot first = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"task_runtime"
		);
		GoalSnapshot second = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(14, 64, 20, true),
			null,
			21L,
			"task_runtime"
		);

		executor.tick(multiplayer(), Optional.of(request("job-1:nav:1", first)));
		facade.pathEvents.add("CANCELED");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("job-1:nav:2", second)));

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("job-1:nav:2", executor.snapshot().taskId());
		assertEquals(2, facade.navigateCalls.size());
		assertEquals(1, facade.cancelCalls);
	}

	@Test
	void replacingNavigationTaskAfterCompletionResetsSnapshotToRunning() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		facade.navigationGoalReached = true;
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot first = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"task_runtime"
		);
		GoalSnapshot second = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(14, 64, 20, true),
			null,
			21L,
			"task_runtime"
		);

		executor.tick(multiplayer(), Optional.of(request("job-1:nav:1", first)));
		facade.pathEvents.add("AT_GOAL");
		Optional<TaskTerminalEvent> completed = executor.tick(multiplayer(), Optional.of(request("job-1:nav:1", first)));
		Optional<TaskTerminalEvent> replacement = executor.tick(multiplayer(), Optional.of(request("job-1:nav:2", second)));

		assertTrue(completed.isPresent());
		assertTrue(replacement.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("job-1:nav:2", executor.snapshot().taskId());
		assertEquals(2, facade.navigateCalls.size());
	}

	@Test
	void facadeStartFailureBecomesFailedTaskEvent() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		facade.startNavigateFailure = new IllegalArgumentException("Invalid navigation goal");
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.FAILED, event.orElseThrow().terminalState());
		assertEquals("Invalid navigation goal", event.orElseThrow().message());
		assertEquals(TaskExecutionState.FAILED, executor.snapshot().state());
		assertEquals("Invalid navigation goal", executor.snapshot().lastPathEvent());
	}

	@Test
	void unavailableFacadeBecomesFailedTaskEvent() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		facade.loaded = false;
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);

		Optional<TaskTerminalEvent> first = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		Optional<TaskTerminalEvent> second = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertTrue(first.isPresent());
		assertTrue(second.isEmpty());
		assertEquals(TaskExecutionState.FAILED, first.orElseThrow().terminalState());
		assertEquals("baritone_unavailable", first.orElseThrow().message());
		assertEquals(TaskExecutionState.FAILED, executor.snapshot().state());
		assertEquals("baritone_unavailable", executor.snapshot().lastPathEvent());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> navigationDiagnostics(TaskTerminalEvent event) {
		return (Map<String, Object>) event.diagnostics().get("navigation");
	}

	private static WorldTaskRequest request(String taskId, GoalSnapshot goal) {
		return WorldTaskRequest.direct(taskId, goal);
	}

	private static SessionSnapshot multiplayer() {
		return multiplayerAt(30L);
	}

	private static SessionSnapshot multiplayerAt(long tick) {
		return new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, tick);
	}

	private static SessionSnapshot singleplayerLocal() {
		return new SessionSnapshot(SessionMode.SINGLEPLAYER_LOCAL, true, true, "minecraft:overworld", false, 0, 30L);
	}

	private static SessionSnapshot deadMultiplayer() {
		return new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			30L,
			PlayerLifecycleState.DEAD
		);
	}

	private static final class FakeBaritoneFacade implements BaritoneFacade {
		private int applySettingsCalls;
		private final List<GoalPosition> navigateCalls = new ArrayList<>();
		private final List<String> followCalls = new ArrayList<>();
		private final ArrayDeque<String> pathEvents = new ArrayDeque<>();
		private boolean navigationGoalReached;
		private boolean processActive;
		private boolean activateOnStart;
		private boolean cancellationRequested;
		private int cancelCalls;
		private RuntimeException startNavigateFailure;
		private boolean loaded = true;
		private double waterPenalty = 3.0D;
		private final List<Double> waterPenaltyChanges = new ArrayList<>();

		@Override
		public boolean isLoaded() {
			return loaded;
		}

		@Override
		public void applySettings() {
			applySettingsCalls++;
		}

		@Override
		public double walkOnWaterPenalty() {
			return waterPenalty;
		}

		@Override
		public void setWalkOnWaterPenalty(double value) {
			waterPenalty = value;
			waterPenaltyChanges.add(value);
		}

		@Override
		public void startFollow(String playerName) {
			cancellationRequested = false;
			followCalls.add(playerName);
			processActive = activateOnStart;
		}

		@Override
		public void startNavigate(GoalPosition position) {
			if (startNavigateFailure != null) {
				throw startNavigateFailure;
			}
			cancellationRequested = false;
			navigateCalls.add(position);
			processActive = activateOnStart;
		}

		@Override
		public void startNavigateNear(GoalPosition position, int radiusBlocks) {
			cancellationRequested = false;
			navigateCalls.add(position);
			processActive = activateOnStart;
		}

		@Override
		public boolean processActive() {
			return processActive;
		}

		@Override
		public boolean cancel() {
			if (!cancellationRequested) {
				cancelCalls++;
				cancellationRequested = true;
			}
			return false;
		}

		@Override
		public Optional<String> activeProcessName() {
			return Optional.of("fake");
		}

		@Override
		public Optional<Double> estimatedTicksToGoal() {
			return Optional.of(42.0D);
		}

		@Override
		public Optional<String> pollPathEvent() {
			return Optional.ofNullable(pathEvents.pollFirst());
		}

		@Override
		public boolean navigationGoalReached(GoalPosition position) {
			return navigationGoalReached;
		}
	}
}
