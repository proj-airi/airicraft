package ai.moeru.airicraft;

import ai.moeru.airicraft.debug.ClientTickDebugController;
import ai.moeru.airicraft.debug.ClientTickTraceRecorder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

final class ClientTickIndicator {
	private static final String PAUSED_LABEL = "PAUSED";
	private static final String HOSTED_PAUSED_LABEL = "PAUSED — WAITING FOR PLAYERS";
	private static final String TRACE_LABEL = "TRACE";
	private static final String PLANNER_OFF_LABEL = "PLANNER OFF";
	private static final int PAUSED_COLOR = 0xD0B52222;
	private static final int TRACE_COLOR = 0xD0205EBA;
	private static final int PLANNER_OFF_COLOR = 0xD0B56A22;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int MARGIN = 6;
	private static final int PADDING = 4;

	void render(
		MinecraftClient client,
		DrawContext drawContext,
		ClientTickDebugController.DebugStatus debugStatus,
		ClientTickTraceRecorder.TraceStatus traceStatus,
		boolean plannerEnabled,
		boolean hostedPaused
	) {
		if ((debugStatus == null || !debugStatus.paused()) && (traceStatus == null || !traceStatus.active()) && plannerEnabled && !hostedPaused) {
			return;
		}
		if (client == null || drawContext == null || client.textRenderer == null) {
			return;
		}

		TextRenderer textRenderer = client.textRenderer;
		for (IndicatorPanel panel : layout(
			debugStatus,
			traceStatus,
			plannerEnabled,
			hostedPaused,
			drawContext.getScaledWindowWidth(),
			textRenderer.getWidth(PAUSED_LABEL),
			textRenderer.getWidth(HOSTED_PAUSED_LABEL),
			textRenderer.getWidth(TRACE_LABEL),
			textRenderer.getWidth(PLANNER_OFF_LABEL),
			textRenderer.fontHeight
		)) {
			IndicatorBounds bounds = panel.bounds();
			drawContext.fill(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), panel.backgroundColor());
			drawContext.drawText(
				textRenderer,
				panel.label(),
				bounds.left() + PADDING,
				bounds.top() + PADDING,
				TEXT_COLOR,
				true
			);
		}
	}

	static List<IndicatorPanel> layout(
		ClientTickDebugController.DebugStatus debugStatus,
		ClientTickTraceRecorder.TraceStatus traceStatus,
		boolean plannerEnabled,
		boolean hostedPaused,
		int scaledWindowWidth,
		int pausedTextWidth,
		int hostedPausedTextWidth,
		int traceTextWidth,
		int plannerOffTextWidth,
		int fontHeight
	) {
		List<IndicatorPanel> panels = new ArrayList<>(3);
		int top = MARGIN;
		if (debugStatus != null && debugStatus.paused()) {
			IndicatorBounds bounds = bounds(scaledWindowWidth, pausedTextWidth, fontHeight, top);
			panels.add(new IndicatorPanel(PAUSED_LABEL, PAUSED_COLOR, bounds));
			top = bounds.bottom();
		}
		if (hostedPaused) {
			IndicatorBounds bounds = bounds(scaledWindowWidth, hostedPausedTextWidth, fontHeight, top);
			panels.add(new IndicatorPanel(HOSTED_PAUSED_LABEL, PAUSED_COLOR, bounds));
			top = bounds.bottom();
		}
		if (traceStatus != null && traceStatus.active()) {
			IndicatorBounds bounds = bounds(scaledWindowWidth, traceTextWidth, fontHeight, top);
			panels.add(new IndicatorPanel(
				TRACE_LABEL,
				TRACE_COLOR,
				bounds
			));
			top = bounds.bottom();
		}
		if (!plannerEnabled) {
			panels.add(new IndicatorPanel(
				PLANNER_OFF_LABEL,
				PLANNER_OFF_COLOR,
				bounds(scaledWindowWidth, plannerOffTextWidth, fontHeight, top)
			));
		}
		return List.copyOf(panels);
	}

	private static IndicatorBounds bounds(int scaledWindowWidth, int textWidth, int fontHeight, int top) {
		int right = Math.max(MARGIN, scaledWindowWidth - MARGIN);
		int left = Math.max(0, right - Math.max(0, textWidth) - (PADDING * 2));
		return new IndicatorBounds(left, top, right, top + Math.max(0, fontHeight) + (PADDING * 2));
	}

	record IndicatorPanel(String label, int backgroundColor, IndicatorBounds bounds) {
	}

	record IndicatorBounds(int left, int top, int right, int bottom) {
	}
}
