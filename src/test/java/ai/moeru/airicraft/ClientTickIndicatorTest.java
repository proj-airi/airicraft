package ai.moeru.airicraft;

import ai.moeru.airicraft.debug.ClientTickDebugController;
import ai.moeru.airicraft.debug.ClientTickTraceRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientTickIndicatorTest {
	@Test
	void showsOnlyActiveTickStates() {
		for (ClientTickDebugController.Phase phase : ClientTickDebugController.Phase.values()) {
			List<ClientTickIndicator.IndicatorPanel> panels = layout(debugStatus(phase), traceStatus(false), true);
			assertEquals(phase == ClientTickDebugController.Phase.PAUSED ? List.of("PAUSED") : List.of(), labels(panels));
		}

		assertEquals(List.of("TRACE"), labels(layout(null, traceStatus(true), true)));
		assertEquals(List.of("PLANNER OFF"), labels(layout(null, traceStatus(false), false)));
		assertEquals(List.of(), layout(null, traceStatus(false), true));
	}

	@Test
	void showsHostedPauseAndKeepsDebugPauseDistinct() {
		var hosted = ClientTickIndicator.layout(null, traceStatus(false), true, true, 320, 36, 180, 30, 61, 9);
		assertEquals(List.of("PAUSED — WAITING FOR PLAYERS"), labels(hosted));
		assertEquals(180 + 8, hosted.getFirst().bounds().right() - hosted.getFirst().bounds().left());
		var both = ClientTickIndicator.layout(debugStatus(ClientTickDebugController.Phase.PAUSED), traceStatus(false), true, true, 320, 36, 180, 30, 61, 9);
		assertEquals(List.of("PAUSED", "PAUSED — WAITING FOR PLAYERS"), labels(both));
		assertEquals(both.getFirst().bounds().bottom(), both.get(1).bounds().top());
	}

	@Test
	void keepsSingleIndicatorsAtTheTopRight() {
		assertEquals(
			new ClientTickIndicator.IndicatorPanel(
				"PAUSED",
				0xD0B52222,
				new ClientTickIndicator.IndicatorBounds(270, 6, 314, 23)
			),
			layout(debugStatus(ClientTickDebugController.Phase.PAUSED), null, true).getFirst()
		);
		assertEquals(
			new ClientTickIndicator.IndicatorPanel(
				"TRACE",
				0xD0205EBA,
				new ClientTickIndicator.IndicatorBounds(276, 6, 314, 23)
			),
			layout(null, traceStatus(true), true).getFirst()
		);
	}

	@Test
	void stacksTraceBelowPauseWhenBothAreVisible() {
		List<ClientTickIndicator.IndicatorPanel> panels = layout(
			debugStatus(ClientTickDebugController.Phase.PAUSED),
			traceStatus(true),
			false
		);

		assertEquals(List.of("PAUSED", "TRACE", "PLANNER OFF"), labels(panels));
		assertEquals(23, panels.get(0).bounds().bottom());
		assertEquals(23, panels.get(1).bounds().top());
		assertEquals(40, panels.get(1).bounds().bottom());
		assertEquals(40, panels.get(2).bounds().top());
		assertEquals(57, panels.get(2).bounds().bottom());
	}

	private static List<ClientTickIndicator.IndicatorPanel> layout(
		ClientTickDebugController.DebugStatus debugStatus,
		ClientTickTraceRecorder.TraceStatus traceStatus,
		boolean plannerEnabled
	) {
		return ClientTickIndicator.layout(debugStatus, traceStatus, plannerEnabled, false, 320, 36, 180, 30, 61, 9);
	}

	private static List<String> labels(List<ClientTickIndicator.IndicatorPanel> panels) {
		return panels.stream().map(ClientTickIndicator.IndicatorPanel::label).toList();
	}

	private static ClientTickDebugController.DebugStatus debugStatus(ClientTickDebugController.Phase phase) {
		return new ClientTickDebugController.DebugStatus(phase, "debug-session", 1L, 2L, "snapshot", "CAPTURED");
	}

	private static ClientTickTraceRecorder.TraceStatus traceStatus(boolean active) {
		return new ClientTickTraceRecorder.TraceStatus(
			active,
			"trace",
			Set.of(ClientTickTraceRecorder.TraceInfo.METADATA),
			100,
			false,
			1L,
			null,
			null,
			0
		);
	}
}
