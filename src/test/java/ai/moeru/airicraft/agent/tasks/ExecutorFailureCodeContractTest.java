package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorFailureCodeContractTest {
	@Test
	void operationalFailureUsesExplicitUnknownFallbackInEveryAffectedExecutor() {
		List<ExecutorCase> cases = List.of(
			new ExecutorCase("BlockBreak", new BlockBreakTaskExecutor(() -> null), breakRequest(), "world_unavailable"),
			new ExecutorCase("BlockInteraction", new BlockInteractionTaskExecutor(() -> null), placeRequest(), "world_unavailable"),
			new ExecutorCase("Crafting", new CraftingTaskExecutor(() -> null, null), craftRequest(), "crafting_busy"),
			new ExecutorCase("DropItems", new DropItemsTaskExecutor(() -> null), dropRequest(), "world_unavailable"),
			new ExecutorCase("EntityInteraction", new EntityInteractionTaskExecutor(() -> null, null), entityRequest(), "world_unavailable"),
			new ExecutorCase("ReturnToSurface", new ReturnToSurfaceTaskExecutor(() -> null, null), surfaceRequest(), "world_unavailable"),
			new ExecutorCase("Smelting", new SmeltingTaskExecutor(() -> null, new SmeltingProcessManager(), null), smeltRequest(), "world_unavailable"),
			new ExecutorCase("UnderwaterHarvest", new UnderwaterHarvestTaskExecutor(() -> null, null, null), underwaterRequest(), "world_unavailable")
		);

		for (ExecutorCase testCase : cases) {
			Optional<TaskTerminalEvent> event = testCase.executor().tick(session(), Optional.of(testCase.request()));

			assertTrue(event.isPresent(), testCase.name());
			assertEquals(TaskExecutionState.FAILED, event.orElseThrow().terminalState(), testCase.name());
			assertEquals(TaskFailureCode.UNKNOWN, event.orElseThrow().failureCode(), testCase.name());
			assertEquals(testCase.detail(), event.orElseThrow().message(), testCase.name());
		}
	}

	@Test
	void detailTextDoesNotClassifyTheTypedFailure() {
		TaskTerminalEvent first = terminal(TaskFailure.of(TaskFailureCode.TRANSIENT, "path timeout"));
		TaskTerminalEvent second = terminal(TaskFailure.of(TaskFailureCode.TRANSIENT, "a changed detail"));

		assertEquals(TaskFailureCode.TRANSIENT, first.failureCode());
		assertEquals(first.failureCode(), second.failureCode());
		assertFalse(first.message().equals(second.message()));
	}

	@Test
	void affectedExecutorsCannotUseDetailTextClassification() throws Exception {
		List<String> executorNames = List.of(
			"BlockBreakTaskExecutor",
			"BlockInteractionTaskExecutor",
			"CraftingTaskExecutor",
			"DropItemsTaskExecutor",
			"EntityInteractionTaskExecutor",
			"ReturnToSurfaceTaskExecutor",
			"SmeltingTaskExecutor",
			"UnderwaterHarvestTaskExecutor"
		);

		for (String executorName : executorNames) {
			Path source = Path.of("src/client/java/ai/moeru/airicraft/agent/tasks", executorName + ".java");
			String text = Files.readString(source);

			assertFalse(text.contains("fromLegacyDetail"), executorName);
			assertTrue(text.contains("failure.code()"), executorName);
		}
	}

	@Test
	void systemOneTasksNameATypedCodeForEveryFailure() throws Exception {
		for (String executorName : List.of("ShelterForNightTaskExecutor", "SleepInBedTaskExecutor", "HuntFoodTaskExecutor",
			"EnterEndPortalTaskExecutor")) {
			String text = Files.readString(Path.of("src/client/java/ai/moeru/airicraft/agent/tasks", executorName + ".java"));
			assertFalse(text.contains("fromLegacyDetail"), executorName);
			String[] failures = text.split("finish\\(false,");
			assertTrue(failures.length > 1, executorName);
			for (int i = 1; i < failures.length; i++) {
				String call = failures[i].substring(0, failures[i].indexOf(");\n"));
				assertTrue(call.contains("TaskFailureCode."), executorName + " fails without a typed code:" + call);
			}
		}
	}

	private static TaskTerminalEvent terminal(TaskFailure failure) {
		return new TaskTerminalEvent("task", null, TaskExecutionState.FAILED, failure.detail(), null, failure.code());
	}

	private static SessionSnapshot session() {
		return new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, 1L);
	}

	private static WorldTaskRequest breakRequest() {
		return WorldTaskRequest.breakBlocks(
			"break-task",
			"job",
			new BlockBreakStepArgs(List.of(new BlockBreakStepArgs.Target(new GoalPosition(1, 64, 2, true), List.of("minecraft:stone"))))
		);
	}

	private static WorldTaskRequest placeRequest() {
		return WorldTaskRequest.placeBlock(
			"place-task",
			"job",
			new BlockPlacementStepArgs("minecraft:dirt", new GoalPosition(1, 64, 2, true), "auto", "air_or_replaceable")
		);
	}

	private static WorldTaskRequest craftRequest() {
		return WorldTaskRequest.craftRecipe("craft-task", "job", new CraftRecipeStepArgs("minecraft:stick", 1));
	}

	private static WorldTaskRequest dropRequest() {
		return WorldTaskRequest.dropItems("drop-task", "job", new DropItemsStepArgs("minecraft:stone", 1, null));
	}

	private static WorldTaskRequest entityRequest() {
		return WorldTaskRequest.attackEntity("entity-task", "job", new EntityInteractionStepArgs(new EntitySelector(null, "Alex", null), null));
	}

	private static WorldTaskRequest surfaceRequest() {
		return WorldTaskRequest.returnToSurface("surface-task", "job", new ReturnToSurfaceStepArgs(null, "unknown", false, null));
	}

	private static WorldTaskRequest smeltRequest() {
		return WorldTaskRequest.smeltItems("smelt-task", "job", new SmeltItemsStepArgs("missing", 1, SmeltingFuelMode.AUTO, null, 0, null));
	}

	private static WorldTaskRequest underwaterRequest() {
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:clay"), 1, List.of("minecraft:clay_ball"), List.of()),
			0L,
			"test"
		);
		return WorldTaskRequest.underwaterHarvest(
			"underwater-task",
			"job",
			goal,
			new UnderwaterHarvestStepArgs(new GoalPosition(1, 64, 2, true))
		);
	}

	private record ExecutorCase(String name, WorldTaskExecutor executor, WorldTaskRequest request, String detail) {
	}
}
