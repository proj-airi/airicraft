package ai.moeru.airicraft.agent.llm;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlannerLoopWatchdogTest {
	private static final Object STILL = List.of("minecraft:overworld", Map.of("minecraft:cobblestone", 4), 10L, 64L, 10L);

	@Test void repeatedTurnsThatChangeNothingGetANoticeThenAHoldThatDoubles() {
		var watchdog = new PlannerLoopWatchdog();
		assertNull(watchdog.onTurn(0, false, List.of("inspect_world", "inspect_work"), STILL));
		assertNull(watchdog.onTurn(10, false, List.of("inspect_work", "inspect_world"), STILL));
		var notice = watchdog.onTurn(20, false, List.of("inspect_world", "inspect_work"), STILL);
		assertEquals(PlannerLoopWatchdog.NO_PROGRESS, notice.kind());
		assertEquals(List.of("inspect_work", "inspect_world"), notice.tools());
		assertFalse(notice.hold(), "the first notice is advice");
		assertNull(watchdog.onTurn(30, false, List.of("inspect_world"), STILL));
		assertNull(watchdog.onTurn(40, false, List.of("inspect_world"), STILL));
		var hold = watchdog.onTurn(50, false, List.of("inspect_world"), STILL);
		assertEquals(PlannerLoopWatchdog.HOLD_TICKS, hold.holdTicks());
		assertTrue(watchdog.holding(50 + PlannerLoopWatchdog.HOLD_TICKS - 1));
		assertNull(watchdog.release(50 + PlannerLoopWatchdog.HOLD_TICKS - 1));
		assertTrue(watchdog.release(50 + PlannerLoopWatchdog.HOLD_TICKS).released());
		assertFalse(watchdog.holding(50 + PlannerLoopWatchdog.HOLD_TICKS));
		assertNull(watchdog.release(50 + PlannerLoopWatchdog.HOLD_TICKS), "a hold ends once");

		long tick = 2_000;
		assertNull(watchdog.onTurn(tick, false, List.of("continue"), STILL));
		assertNull(watchdog.onTurn(tick + 1, false, List.of("continue"), STILL));
		assertEquals(2 * PlannerLoopWatchdog.HOLD_TICKS, watchdog.onTurn(tick + 2, false, List.of("continue"), STILL).holdTicks(),
			"the same loop after a hold holds again, twice as long");
	}

	@Test void anyTurnsWithoutChangeAreNoticedOnceTheStreakIsLong() {
		var watchdog = new PlannerLoopWatchdog();
		PlannerLoopWatchdog.Notice notice = null;
		for (int turn = 0; turn < PlannerLoopWatchdog.STREAK_TURNS; turn++) {
			assertNull(notice, "varied research turns are not a loop yet");
			notice = watchdog.onTurn(turn, false, List.of("tool_" + turn), STILL);
		}
		assertNotNull(notice);
		assertEquals(PlannerLoopWatchdog.STREAK_TURNS, notice.count());
	}

	@Test void progressAndThePlayersGuidanceClearTheLoopAndItsHold() {
		var watchdog = new PlannerLoopWatchdog();
		for (int turn = 0; turn < 6; turn++) watchdog.onTurn(turn, false, List.of("report_to_me"), STILL);
		assertTrue(watchdog.holding(6));
		watchdog.onTurn(7, false, List.of("report_to_me"), List.of("moved"));
		assertFalse(watchdog.holding(8), "a changed world ends the hold");
		assertTrue(watchdog.release(8).released(), "and says so");
		assertNull(watchdog.onTurn(9, false, List.of("report_to_me"), List.of("moved")));
		assertFalse(watchdog.onTurn(10, false, List.of("report_to_me"), List.of("moved")).hold(), "the streak restarted with a notice, not a hold");

		for (int turn = 11; turn < 20; turn++) watchdog.onTurn(turn, false, List.of("report_to_me"), List.of("moved"));
		assertTrue(watchdog.holding(20));
		assertNull(watchdog.onTurn(21, true, List.of("say"), List.of("moved")));
		assertFalse(watchdog.holding(21), "guidance ends the hold");
		assertNull(watchdog.onTurn(22, false, List.of("report_to_me"), null), "unknown state is ignored");
		for (int turn = 23; turn < 40; turn++) assertNull(watchdog.onTurn(turn, false, List.of(), List.of("moved")), "so is waiting");
	}

	@Test void aToolFailingTheSameWayIsNoticedEveryThirdTimeWhatEverChangedBetween() {
		var watchdog = new PlannerLoopWatchdog();
		String shortfall = "Tool result for construct_blueprint: {\"accepted\":false,\"workId\":\"build-%d\",\"updatedTick\":%d,"
			+ "\"failure\":\"construction_material_missing\",\"shortfall\":{\"minecraft:cobblestone\":18,\"minecraft:stick\":2}}";
		assertNull(watchdog.onResult("construct_blueprint", shortfall.formatted(1, 100)));
		assertNull(watchdog.onResult("mine_blocks", "Work outcome: {\"workId\":\"mine-1\",\"state\":\"SUCCEEDED\"}"));
		assertNull(watchdog.onResult("construct_blueprint", shortfall.formatted(2, 900)));
		var notice = watchdog.onResult("construct_blueprint", shortfall.formatted(3, 1700));
		assertEquals(PlannerLoopWatchdog.REPEATED_FAILURE, notice.kind());
		assertEquals(3, notice.count());
		assertTrue(notice.result().contains("construction_material_missing"));
		assertFalse(notice.hold(), "a repeated failure is only ever advice");

		assertNull(watchdog.onResult("construct_blueprint", shortfall.formatted(4, 2500).replace("18", "17")),
			"a different shortfall is a different failure");
		assertNull(watchdog.onResult("construct_blueprint", "Tool result for construct_blueprint: {\"accepted\":true,\"state\":\"RUNNING\"}"));
		assertNull(watchdog.onResult("construct_blueprint", shortfall.formatted(5, 3300)), "success clears the tool's history");
	}

	@Test void cancellingOwnRunningWorkIsNoticedEveryThirdTimeUntilItSucceeds() {
		var watchdog = new PlannerLoopWatchdog();
		assertNull(watchdog.onAbandoned("construct_blueprint"));
		assertNull(watchdog.onResult("construct_blueprint", "Cancelled by clear_queue; completed effects are not undone."));
		assertNull(watchdog.onAbandoned("construct_blueprint"));
		var notice = watchdog.onAbandoned("construct_blueprint");
		assertEquals(PlannerLoopWatchdog.ABANDONED_WORK, notice.kind());
		assertEquals(List.of("construct_blueprint"), notice.tools());
		assertFalse(notice.hold());
		assertNull(watchdog.onResult("construct_blueprint", "Work outcome: {\"workId\":\"build-4\",\"state\":\"SUCCEEDED\"}"));
		assertNull(watchdog.onAbandoned("construct_blueprint"));
		assertNull(watchdog.onAbandoned("construct_blueprint"), "a success started the count over");
	}

	@Test void failuresAreReadFromErrorsReceiptsAndWorkOutcomes() {
		assertTrue(PlannerLoopWatchdog.isFailure("TOOL_ERROR: no path"));
		assertTrue(PlannerLoopWatchdog.isFailure("Work outcome: {\"workId\":\"craft-2\",\"state\":\"FAILED\",\"failure\":\"missing_ingredients\"}"));
		assertTrue(PlannerLoopWatchdog.isFailure("Tool result for craft_item: {\"accepted\":false,\"reason\":\"no_table\"}"));
		assertFalse(PlannerLoopWatchdog.isFailure("Work outcome: {\"workId\":\"craft-2\",\"state\":\"SUCCEEDED\"}"));
		assertFalse(PlannerLoopWatchdog.isFailure("Report checkpoint reached; execution continues."));
		assertEquals(PlannerLoopWatchdog.normalize("TOOL_ERROR: stalled at tick 123456"), PlannerLoopWatchdog.normalize("TOOL_ERROR: stalled at tick 654321"));
	}
}
