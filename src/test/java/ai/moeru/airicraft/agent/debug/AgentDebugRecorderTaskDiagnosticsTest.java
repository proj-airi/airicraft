package ai.moeru.airicraft.agent.debug;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentDebugRecorderTaskDiagnosticsTest {
	@Test
	void taskDiagnosticsAppearOnTheTimelineWithTheirTaskId() {
		AgentDebugRecorder recorder = new AgentDebugRecorder();

		recorder.recordTaskDiagnostics(42L, "nav-1", "NAVIGATE_TO", "COMPLETED",
			Map.of("navigation", Map.of("pathLength", 7.0D)));

		AgentDebugTimelineEntry entry = recorder.timelineTail().getLast();
		assertEquals(42L, entry.tick());
		assertEquals("task", entry.domain());
		assertEquals("terminal_diagnostics", entry.action());
		assertEquals("NAVIGATE_TO COMPLETED", entry.summary());
		assertEquals("nav-1", entry.correlation().get("taskId"));
		assertEquals(Map.of("pathLength", 7.0D), entry.payload().get("navigation"));
	}
}
