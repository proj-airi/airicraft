package ai.moeru.airicraft.agent.evaluation;

import org.junit.jupiter.api.Test;
import ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioEvaluationRunnerTest {
	@Test
	void seedsGoalOnceAndNeverNudgesWaitingPlanner() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("inventory_contains", Map.of(
			"itemId", "minecraft:iron_ingot",
			"count", 1
		))), new EvaluationBudget(4, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);
		assertEquals(List.of("goal:@agent smelt iron"), context.triggers);
		assertEquals(EvaluationStatus.RUNNING, runner.report(context.tick).status());

		context.tick = 5;
		runner.onTick(context);
		assertEquals(List.of("goal:@agent smelt iron"), context.triggers);
		assertEquals(0, runner.report(context.tick).plannerTurns());

		context.inventoryCount = 1;
		context.tick = 6;
		runner.onTick(context);
		assertEquals(EvaluationStatus.PASSED, runner.report(context.tick).status());
	}

	@Test
	void waitsForWorldBeforeEmittingInitialPrompt() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.worldLoaded = false;
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("inventory_contains", Map.of(
			"itemId", "minecraft:iron_ingot",
			"count", 1
		))), new EvaluationBudget(4, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);

		EvaluationReport pendingReport = runner.report(context.tick);
		assertEquals(EvaluationStatus.PENDING_WORLD, pendingReport.status());
		assertEquals("Waiting for evaluation world", pendingReport.message());
		assertTrue(context.triggers.isEmpty());

		context.tick = 3;
		context.worldLoaded = true;
		runner.onTick(context);

		assertEquals(EvaluationStatus.RUNNING, runner.report(context.tick).status());
		assertEquals(List.of("goal:@agent smelt iron"), context.triggers);
	}

	@Test
	void externalDriverRunsWithoutPlannerUntilDeterministicCheckPasses() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.plannerConfigured = false;
		context.externalDriverActive = true;
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("task_execution_state", Map.of(
			"state", "COMPLETED"
		))), new EvaluationBudget(1, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);

		EvaluationReport running = runner.report(context.tick);
		assertEquals(EvaluationStatus.RUNNING, running.status());
		assertEquals("Evaluation running under external driver", running.message());
		assertEquals(0, running.plannerTurns());
		assertTrue(context.triggers.isEmpty());

		context.tick = 6;
		context.taskExecutionState = "COMPLETED";
		runner.onTick(context);

		assertEquals(EvaluationStatus.PASSED, runner.report(context.tick).status());
		assertTrue(context.triggers.isEmpty());
	}

	@Test
	void eventChecksMatchPayloadFieldsAndEventAbsentBlocksAPass() {
		EvaluationScenario scenario = scenario(List.of(
			new EvaluationCheck("event_contains", Map.of("eventType", "perception.block_noticed", "payload", Map.of("blockId", "minecraft:diamond_ore"))),
			new EvaluationCheck("event_absent", Map.of("eventType", "perception.block_noticed", "payload", Map.of("blockId", "minecraft:emerald_ore")))
		), new EvaluationBudget(4, 200, 0, 5));

		ScenarioEvaluationRunner honest = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		honest.start(scenario, 0, 0);
		honest.onTick(context);
		assertEquals(EvaluationStatus.RUNNING, honest.report(context.tick).status(), "the vein has not been noticed yet");
		context.events.add(Map.of("type", "perception.block_noticed", "blockId", "minecraft:diamond_ore"));
		context.tick = 6;
		honest.onTick(context);
		assertEquals(EvaluationStatus.PASSED, honest.report(context.tick).status());

		ScenarioEvaluationRunner xray = new ScenarioEvaluationRunner();
		FakeContext leaked = new FakeContext();
		leaked.events.add(Map.of("type", "perception.block_noticed", "blockId", "minecraft:diamond_ore"));
		leaked.events.add(Map.of("type", "perception.block_noticed", "blockId", "minecraft:emerald_ore"));
		xray.start(scenario, 0, 0);
		xray.onTick(leaked);
		leaked.tick = 6;
		xray.onTick(leaked);
		assertEquals(EvaluationStatus.RUNNING, xray.report(leaked.tick).status(), "a sealed ore that was noticed never passes");
	}

	@Test
	void failsWhenNeitherPlannerNorExternalDriverIsAvailable() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.plannerConfigured = false;
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("task_execution_state", Map.of(
			"state", "COMPLETED"
		))), new EvaluationBudget(4, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);

		EvaluationReport report = runner.report(context.tick);
		assertEquals(EvaluationStatus.FAILED, report.status());
		assertEquals("Planner LLM is not configured", report.message());
		assertTrue(context.triggers.isEmpty());
	}

	@Test
	void failsWhenWorldLoadBudgetIsExhaustedBeforePrompt() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.worldLoaded = false;
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("inventory_contains", Map.of(
			"itemId", "minecraft:iron_ingot",
			"count", 1
		))), new EvaluationBudget(4, 5, 0, 5));

		runner.start(scenario, 0, 0);
		context.tick = 5;
		runner.onTick(context);

		EvaluationReport report = runner.report(context.tick);
		assertEquals(EvaluationStatus.FAILED, report.status());
		assertEquals("Evaluation world did not load before budget was exhausted", report.message());
		assertTrue(report.evidenceReviewRequired());
		assertTrue(context.triggers.isEmpty());
	}

	@Test
	void freezesReportAndStopsTriggersAfterTerminalStatus() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("inventory_contains", Map.of(
			"itemId", "minecraft:iron_ingot",
			"count", 1
		))), new EvaluationBudget(4, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);
		context.tick = 6;
		context.inventoryCount = 1;
		runner.onTick(context);

		EvaluationReport terminalReport = runner.report(context.tick);
		assertEquals(EvaluationStatus.PASSED, terminalReport.status());
		assertEquals(6, terminalReport.elapsedTicks());
		assertTrue(runner.terminal());
		assertEquals(1, context.triggers.size());

		context.tick = 100;
		runner.onTick(context);

		assertEquals(1, context.triggers.size());
		assertEquals(6, runner.report(context.tick).elapsedTicks());
	}

	@Test
	void failsWhenPlannerTurnBudgetIsExhaustedBeforeCheckPasses() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("inventory_contains", Map.of(
			"itemId", "minecraft:iron_ingot",
			"count", 1
		))), new EvaluationBudget(1, 200, 0, 5));

		context.decisions = 50; // Previous scenarios do not consume this budget.
		runner.start(scenario, 0, 0);
		runner.onTick(context);
		context.decisions = 51;
		context.plannerInFlight = true;
		runner.onTick(context);
		assertEquals(EvaluationStatus.RUNNING, runner.report(0).status());
		context.plannerInFlight = false;
		runner.onTick(context);

		EvaluationReport report = runner.report(context.tick);
		assertEquals(EvaluationStatus.FAILED, report.status());
		assertTrue(report.evidenceReviewRequired());
		assertEquals("Evaluation budget exhausted before expected outcome", report.message());
	}

	@Test
	void passesBlockCountWithinSelfRadius() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.playerBlockX = 10;
		context.playerBlockY = 64;
		context.playerBlockZ = -4;
		context.setBlock(9, 64, -4, "minecraft:wheat");
		context.setBlock(10, 64, -4, "minecraft:wheat");
		context.setBlock(11, 64, -4, "minecraft:wheat");
		context.setBlock(20, 64, -4, "minecraft:wheat");
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("block_count", Map.of(
			"blockId", "minecraft:wheat",
			"count", 3,
			"scope", "self",
			"horizontalRadius", 1,
			"verticalRadius", 0
		))), new EvaluationBudget(4, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);

		EvaluationReport report = runner.report(context.tick);
		assertEquals(EvaluationStatus.PASSED, report.status());
		assertTrue(report.checks().getFirst().message().contains("found 3x minecraft:wheat"));
	}

	@Test
	void passesBlockCountWithinBox() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.setBlock(2, 65, 2, "minecraft:wheat");
		context.setBlock(3, 65, 2, "minecraft:wheat");
		context.setBlock(10, 65, 2, "minecraft:wheat");
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("block_count", Map.of(
			"blockId", "minecraft:wheat",
			"count", 2,
			"scope", "box",
			"x1", 3,
			"y1", 65,
			"z1", 2,
			"x2", 2,
			"y2", 65,
			"z2", 2
		))), new EvaluationBudget(4, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);

		assertEquals(EvaluationStatus.PASSED, runner.report(context.tick).status());
	}

	@Test
	void blockStateCanRequireExactStateProperties() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.setBlock(1, 64, 1, "minecraft:water", Map.of("level", "8"));
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("block_state", Map.of(
			"blockId", "minecraft:water",
			"x", 1,
			"y", 64,
			"z", 1,
			"state", Map.of("level", "0")
		))), new EvaluationBudget(1, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);
		context.decisions = 1;
		runner.onTick(context);

		EvaluationReport report = runner.report(context.tick);
		assertEquals(EvaluationStatus.FAILED, report.status());
		assertTrue(report.checks().getFirst().message().contains("state level was 8, expected 0"));
	}

	@Test
	void blockCountCanRequireExactStateProperties() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.setBlock(2, 65, 2, "minecraft:wheat", Map.of("age", "0"));
		context.setBlock(3, 65, 2, "minecraft:wheat", Map.of("age", "7"));
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("block_count", Map.of(
			"blockId", "minecraft:wheat",
			"count", 1,
			"scope", "box",
			"x1", 2,
			"y1", 65,
			"z1", 2,
			"x2", 3,
			"y2", 65,
			"z2", 2,
			"state", Map.of("age", "7")
		))), new EvaluationBudget(4, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);

		EvaluationReport report = runner.report(context.tick);
		assertEquals(EvaluationStatus.PASSED, report.status());
		assertTrue(report.checks().getFirst().message().contains("found 1x minecraft:wheat state={age=7}"));
	}

	@Test
	void marksScenarioForReviewWhenNoDeterministicChecksExist() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("external_judge", Map.of())), new EvaluationBudget(1, 20, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);
		context.decisions = 1;
		runner.onTick(context);
		runner.onTick(context);

		EvaluationReport report = runner.report(context.tick);
		assertEquals(EvaluationStatus.NEEDS_REVIEW, report.status());
		assertTrue(report.evidenceReviewRequired());
	}

	private static EvaluationScenario scenario(List<EvaluationCheck> checks, EvaluationBudget budget) {
		return new EvaluationScenario(
			"smelting-basic",
			"Smelting basic",
			"1.21.8",
			"dev",
			null,
			"world.zip",
			true,
			"@agent smelt iron",
			budget,
			checks,
			List.of(),
			EvaluationEvidenceSettings.defaults()
		);
	}

	@Test
	void goalClaimsNeverSubstituteForPhysicalChecks() {
		for (var status : List.of(PlannerGoalStore.Status.SUCCEEDED, PlannerGoalStore.Status.GIVEN_UP)) {
			var runner = new ScenarioEvaluationRunner();
			var context = new FakeContext();
			runner.start(scenario(List.of(new EvaluationCheck("inventory_contains", Map.of("itemId", "minecraft:iron_ingot", "count", 1))), new EvaluationBudget(4, 200, 0, 5)), 0, 0);
			runner.onTick(context);
			context.goal = Optional.of(new PlannerGoalStore.Goal("goal-1", "Smelt iron", status, "Finished"));
			runner.onTick(context);
			assertEquals(EvaluationStatus.FAILED, runner.report(0).status());
		}
	}

	@Test
	void blockedGoalWaitsWithoutNudgesUntilElapsedBudget() {
		var runner = new ScenarioEvaluationRunner();
		var context = new FakeContext();
		runner.start(scenario(List.of(), new EvaluationBudget(4, 200, 0, 5)), 0, 0);
		runner.onTick(context);
		context.goal = Optional.of(new PlannerGoalStore.Goal("goal-1", "Smelt iron", PlannerGoalStore.Status.BLOCKED, "", "", "",
			new PlannerGoalStore.Blocker("No fuel", "inventory empty", "Fuel arrives", List.of("inventory.changed")), Map.of()));
		for (int tick = 1; tick < 200; tick++) { context.tick = tick; runner.onTick(context); }
		assertEquals(EvaluationStatus.RUNNING, runner.report(199).status());
		assertEquals(1, context.triggers.size());
		context.tick = 200; runner.onTick(context);
		assertEquals(EvaluationStatus.NEEDS_REVIEW, runner.report(200).status());
		assertEquals(PlannerGoalStore.Status.BLOCKED, context.goal.get().status());
	}

	@Test
	void stallsAfterMaxStallTicksWithoutProgress() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		runner.start(scenario(List.of(new EvaluationCheck("inventory_contains", Map.of("itemId", "minecraft:iron_ingot", "count", 1))),
			new EvaluationBudget(40, 100_000, 0, 5, 100)), 0, 0);
		for (int tick = 0; tick < 100; tick++) { context.tick = tick; runner.onTick(context); }
		assertEquals(EvaluationStatus.RUNNING, runner.report(99).status());
		context.tick = 100; runner.onTick(context);
		EvaluationReport report = runner.report(100);
		assertEquals(EvaluationStatus.STALLED, report.status());
		assertTrue(report.message().contains("100 ticks"), report.message());
		assertTrue(runner.terminal());
	}

	@Test
	void plannerCallsTurnsAndEventsResetTheStallClock() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		runner.start(scenario(List.of(new EvaluationCheck("inventory_contains", Map.of("itemId", "minecraft:iron_ingot", "count", 1))),
			new EvaluationBudget(40, 100_000, 0, 5, 100)), 0, 0);
		for (int tick = 0; tick <= 500; tick++) {
			context.tick = tick;
			context.plannerInFlight = tick == 90;
			if (tick == 180) context.latestEventSeqNo++;
			if (tick == 270) context.decisions++;
			runner.onTick(context);
			if (tick < 370) assertEquals(EvaluationStatus.RUNNING, runner.report(tick).status(), "tick " + tick);
		}
		// The last progress was the planner turn at tick 270.
		assertEquals(EvaluationStatus.STALLED, runner.report(370).status());
		assertEquals(370, runner.report(500).elapsedTicks());
	}

	@Test
	void zeroStallBudgetDisablesTheCutoff() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		runner.start(scenario(List.of(new EvaluationCheck("inventory_contains", Map.of("itemId", "minecraft:iron_ingot", "count", 1))),
			new EvaluationBudget(40, 1_000, 0, 5, 0)), 0, 0);
		for (int tick = 0; tick < 1_000; tick++) { context.tick = tick; runner.onTick(context); }
		assertEquals(EvaluationStatus.RUNNING, runner.report(999).status());
		context.tick = 1_000; runner.onTick(context);
		assertEquals(EvaluationStatus.FAILED, runner.report(1_000).status());
	}

	private static final class FakeContext implements ScenarioEvaluationRunner.Context {
		private final List<Map<String, String>> events = new java.util.ArrayList<>();
		private long tick;
		private int inventoryCount;
		private boolean plannerInFlight;
		private boolean plannerConfigured = true;
		private boolean externalDriverActive;
		private boolean worldLoaded = true;
		private int playerBlockX;
		private int playerBlockY;
		private int playerBlockZ;
		private final Map<String, String> blocks = new HashMap<>();
		private final Map<String, Map<String, String>> blockProperties = new HashMap<>();
		private long decisions;
		private long latestEventSeqNo;
		private Optional<PlannerGoalStore.Goal> goal = Optional.empty();
		private String taskExecutionState = "IDLE";
		private final ArrayList<String> triggers = new ArrayList<>();

		private void setBlock(int x, int y, int z, String blockId) {
			blocks.put(x + "," + y + "," + z, blockId);
		}

		private void setBlock(int x, int y, int z, String blockId, Map<String, String> properties) {
			String key = x + "," + y + "," + z;
			blocks.put(key, blockId);
			blockProperties.put(key, Map.copyOf(properties));
		}

		@Override
		public long tick() {
			return tick;
		}

		@Override
		public long nowMs() {
			return tick * 50L;
		}

		@Override
		public boolean worldLoaded() {
			return worldLoaded;
		}

		@Override
		public boolean plannerConfigured() {
			return plannerConfigured;
		}

		@Override
		public boolean externalDriverActive() {
			return externalDriverActive;
		}

		@Override
		public boolean plannerInFlight() {
			return plannerInFlight;
		}

		@Override
		public long gameplayDecisionCount() { return decisions; }

		@Override
		public long latestEventSeqNo() { return latestEventSeqNo; }

		@Override
		public Optional<PlannerGoalStore.Goal> plannerGoal() { return goal; }

		@Override
		public int inventoryCount(String itemId) {
			return inventoryCount;
		}

		@Override
		public String blockIdAt(int x, int y, int z) {
			return blocks.getOrDefault(x + "," + y + "," + z, "minecraft:air");
		}

		@Override
		public Map<String, String> blockPropertiesAt(int x, int y, int z) {
			return blockProperties.getOrDefault(x + "," + y + "," + z, Map.of());
		}

		@Override
		public int playerBlockX() {
			return playerBlockX;
		}

		@Override
		public int playerBlockY() {
			return playerBlockY;
		}

		@Override
		public int playerBlockZ() {
			return playerBlockZ;
		}

		@Override
		public boolean eventContains(String eventType) {
			return events.stream().anyMatch(event -> event.get("type").equals(eventType));
		}

		@Override
		public boolean eventMatches(String eventType, Map<String, String> payload) {
			return events.stream().anyMatch(event -> event.get("type").equals(eventType)
				&& payload.entrySet().stream().allMatch(field -> field.getValue().equals(event.get(field.getKey()))));
		}

		@Override
		public String lastChatText() {
			return "";
		}

		@Override
		public String taskState() {
			return "IDLE";
		}

		@Override
		public String taskExecutionState() {
			return taskExecutionState;
		}

		@Override
		public void startPlannerGoal(String prompt) {
			triggers.add("goal:" + prompt);
		}

	}
}
