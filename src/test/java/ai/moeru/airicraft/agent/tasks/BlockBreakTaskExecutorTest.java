package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockBreakTaskExecutorTest {
	@Test void absentTargetRayCannotWaitForeverBeforeMiningStarts() {
		var executor = new BlockBreakTaskExecutor(() -> null);
		for (int tick=0;tick<40;tick++) assertEquals(false, executor.aimWaitExpired(false));
		assertTrue(executor.aimWaitExpired(false));
		// A different task must not inherit the previous task's expired aim budget.
		executor.tick(null, Optional.empty());
		assertEquals(false, executor.aimWaitExpired(false));
	}

	@Test void successfulTargetRayResetsConsecutiveAimWait() {
		var executor = new BlockBreakTaskExecutor(() -> null);
		for (int tick=0;tick<40;tick++) assertEquals(false, executor.aimWaitExpired(false));
		assertEquals(false, executor.aimWaitExpired(true));
		for (int tick=0;tick<40;tick++) assertEquals(false, executor.aimWaitExpired(false));
		assertTrue(executor.aimWaitExpired(false));
	}

	@Test
	void pausesWhenSessionGateBlocksActuation() {
		BlockBreakTaskExecutor executor = new BlockBreakTaskExecutor(() -> null);

		Optional<TaskTerminalEvent> event = executor.tick(
			new SessionSnapshot(SessionMode.SINGLEPLAYER_LOCAL, true, true, "minecraft:overworld", false, 0, 1L),
			Optional.of(request())
		);

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.PAUSED_BY_SESSION_GATE, executor.snapshot().state());
		assertEquals("session_gate", executor.snapshot().lastPathEvent());
	}

	@Test
	void failsWhenWorldUnavailable() {
		BlockBreakTaskExecutor executor = new BlockBreakTaskExecutor(() -> null);

		Optional<TaskTerminalEvent> event = executor.tick(
			new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, 1L),
			Optional.of(request())
		);

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.FAILED, event.orElseThrow().terminalState());
		assertEquals("world_unavailable", event.orElseThrow().message());
		assertEquals(TaskFailureCode.UNKNOWN, event.orElseThrow().failureCode());
	}

	private static WorldTaskRequest request() {
		return WorldTaskRequest.breakBlocks(
			"task-1",
			"job-1",
			new BlockBreakStepArgs(List.of(new BlockBreakStepArgs.Target(
				new GoalPosition(1, 64, 2, true),
				List.of("minecraft:grass_block")
			)))
		);
	}
}
