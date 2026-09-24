package ai.moeru.airicraft;

import ai.moeru.airicraft.agent.AgentRuntimeSnapshot;
import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import ai.moeru.airicraft.agent.llm.CompactionCheckpoint;
import ai.moeru.airicraft.agent.llm.CompactionExecutionResult;
import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerAmbientContext;
import ai.moeru.airicraft.agent.llm.PlannerContextDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugKind;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugMessage;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerOrchestratorDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.llm.PlannerTriggerBatch;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.job.ActiveJob;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PlannerDebugOverlay {
	private static final int PANEL_MARGIN = 6;
	private static final int PANEL_PADDING = 4;
	private static final int PANEL_BACKGROUND_COLOR = 0x88000000;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int SECTION_COLOR = 0xFFE7D9A7;
	private static final int STATE_MAX_LINE_LENGTH = 76;
	private static final int MAX_MESSAGE_PREVIEW_LENGTH = 96;

	private static final int CONVERSATION_WIDTH_MIN = 280;
	private static final int CONVERSATION_HEIGHT_MIN = 200;
	private static final double CONVERSATION_WIDTH_RATIO = 0.38D;
	private static final double CONVERSATION_HEIGHT_RATIO = 0.68D;
	private static final int CONVERSATION_TITLE_HEIGHT = 18;
	private static final int CONVERSATION_CARD_PADDING = 6;
	private static final int CONVERSATION_CARD_GAP = 6;
	private static final int CONVERSATION_SCROLL_STEP_PX = 24;
	private static final int CONVERSATION_FOOTER_HEIGHT = 16;
	static final int CONVERSATION_KEY_SCROLL_STEP_PX = 48;
	private static final DateTimeFormatter CHRONICLE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
	private static final int STATIC_BODY_CHAR_CAP = 480;
	private static final int BODY_CHAR_CAP = 1600;
	private static final int CONVERSATION_BACKGROUND_COLOR = 0x9A0E1117;
	private static final int CONVERSATION_INNER_COLOR = 0xCC121821;
	private static final int CONVERSATION_TITLE_COLOR = 0xFFF5E7B8;
	private static final int CONVERSATION_META_COLOR = 0xFFB8C5D6;
	private static final int CONVERSATION_IMAGE_MARKER_COLOR = 0xFF98D8AA;
	private static final int CONVERSATION_FOOTER_COLOR = 0xFFD3DCE9;
	private static final int SYSTEM_BORDER_COLOR = 0xFFC7A85B;
	private static final int USER_BORDER_COLOR = 0xFF4FA7D8;
	private static final int ASSISTANT_BORDER_COLOR = 0xFF8AC9A6;
	private static final int FAILURE_BORDER_COLOR = 0xFFE06A6A;
	private static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};

	private PlannerDebugOverlayMode mode = PlannerDebugOverlayMode.OFF;
	private PlannerConversationView conversationView = PlannerConversationView.CHRONICLE;
	private boolean conversationVerbose;
	private ConversationPaneLayout lastConversationLayout;
	private int conversationScrollTop;
	private boolean conversationPinnedToBottom = true;

	PlannerConversationView conversationView() {
		return conversationView;
	}

	boolean conversationVerbose() {
		return conversationVerbose;
	}

	void setConversationVerbose(boolean verbose) {
		conversationVerbose = verbose;
	}

	void setConversationView(PlannerConversationView view) {
		PlannerConversationView next = view == null ? PlannerConversationView.CHRONICLE : view;
		if (conversationView == next) {
			return;
		}
		conversationView = next;
		conversationScrollTop = 0;
		conversationPinnedToBottom = true;
	}

	PlannerDebugOverlayMode mode() {
		return mode;
	}

	boolean enabled() {
		return mode != PlannerDebugOverlayMode.OFF;
	}

	void setMode(PlannerDebugOverlayMode value) {
		PlannerDebugOverlayMode nextMode = value == null ? PlannerDebugOverlayMode.OFF : value;
		if (mode == nextMode) {
			return;
		}
		mode = nextMode;
		conversationScrollTop = 0;
		conversationPinnedToBottom = true;
		lastConversationLayout = null;
	}

	void scrollConversationBy(int deltaPx) {
		if (mode != PlannerDebugOverlayMode.CONVERSATION || lastConversationLayout == null) {
			return;
		}
		conversationScrollTop = clamp(conversationScrollTop + deltaPx, 0, lastConversationLayout.maxScroll());
		conversationPinnedToBottom = conversationScrollTop >= lastConversationLayout.maxScroll();
	}

	void scrollConversationToStart() {
		if (mode != PlannerDebugOverlayMode.CONVERSATION || lastConversationLayout == null) {
			return;
		}
		conversationScrollTop = 0;
		conversationPinnedToBottom = lastConversationLayout.maxScroll() <= 0;
	}

	void scrollConversationToEnd() {
		if (mode != PlannerDebugOverlayMode.CONVERSATION || lastConversationLayout == null) {
			return;
		}
		conversationScrollTop = lastConversationLayout.maxScroll();
		conversationPinnedToBottom = true;
	}

	boolean onMouseScroll(double mouseX, double mouseY, double verticalAmount) {
		if (mode != PlannerDebugOverlayMode.CONVERSATION || lastConversationLayout == null) {
			return false;
		}
		ConversationScrollUpdate update = scrollConversation(lastConversationLayout, conversationScrollTop, mouseX, mouseY, verticalAmount);
		if (!update.consumed()) {
			return false;
		}
		conversationScrollTop = update.scrollTop();
		conversationPinnedToBottom = update.pinnedToBottom();
		return true;
	}

	void render(MinecraftClient client, DrawContext drawContext, EmbodiedAgentRuntime agentRuntime, long nowMs) {
		if (!enabled() || client == null || drawContext == null || agentRuntime == null || client.options.hudHidden) {
			return;
		}

		TextRenderer textRenderer = client.textRenderer;
		if (textRenderer == null) {
			return;
		}

		switch (mode) {
			case OFF -> {
				return;
			}
			case STATES -> renderStates(drawContext, textRenderer, agentRuntime, nowMs);
			case CONVERSATION -> renderConversation(drawContext, textRenderer, agentRuntime, nowMs);
		}
	}

	private void renderStates(DrawContext drawContext, TextRenderer textRenderer, EmbodiedAgentRuntime agentRuntime, long nowMs) {
		List<String> lines = formatStateLines(
			true,
			agentRuntime.snapshot(),
			agentRuntime.isDegraded(),
			agentRuntime.llmAvailable(),
			agentRuntime.visionAvailable(),
			agentRuntime.plannerDebugSnapshot(),
			agentRuntime.activeJob(),
			nowMs
		);
		if (lines.isEmpty()) {
			return;
		}

		int panelWidth = 0;
		for (String line : lines) {
			panelWidth = Math.max(panelWidth, textRenderer.getWidth(line));
		}
		int lineHeight = textRenderer.fontHeight + 1;
		int panelRight = drawContext.getScaledWindowWidth() - PANEL_MARGIN;
		int panelLeft = panelRight - panelWidth - (PANEL_PADDING * 2);
		int panelTop = PANEL_MARGIN;
		int panelBottom = panelTop + (PANEL_PADDING * 2) + (lineHeight * lines.size());

		drawContext.fill(panelLeft, panelTop, panelRight, panelBottom, PANEL_BACKGROUND_COLOR);
		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index);
			int textX = panelRight - PANEL_PADDING - textRenderer.getWidth(line);
			int textY = panelTop + PANEL_PADDING + (index * lineHeight);
			drawContext.drawText(textRenderer, line, textX, textY, colorForStateLine(line), true);
		}
	}

	private void renderConversation(DrawContext drawContext, TextRenderer textRenderer, EmbodiedAgentRuntime agentRuntime, long nowMs) {
		PlannerOrchestratorDebugSnapshot plannerSnapshot = agentRuntime.plannerDebugSnapshot();
		PlannerConversationDebugSnapshot snapshot = conversationView == PlannerConversationView.CONTEXT
			? agentRuntime.plannerContextConversationDebugSnapshot()
			: agentRuntime.plannerChronicleConversationDebugSnapshot(conversationVerbose);
		if (snapshot == null || snapshot.isEmpty()) {
			snapshot = placeholderConversationSnapshot(plannerSnapshot);
		}
		String footerLine = formatConversationFooter(plannerSnapshot, agentRuntime.snapshot(), agentRuntime.activeJob(), nowMs);

		ConversationPaneLayout layout = layoutConversationPane(
			conversationView,
			snapshot,
			drawContext.getScaledWindowWidth(),
			drawContext.getScaledWindowHeight(),
			textRenderer::getWidth,
			textRenderer.fontHeight + 1,
			conversationScrollTop,
			conversationPinnedToBottom,
			footerLine
		);
		lastConversationLayout = layout;
		conversationScrollTop = layout.scrollTop();
		conversationPinnedToBottom = layout.atBottom();

		Rect pane = layout.paneBounds();
		Rect viewport = layout.viewportBounds();
		drawContext.fill(pane.left(), pane.top(), pane.right(), pane.bottom(), CONVERSATION_BACKGROUND_COLOR);
		drawContext.fill(pane.left(), pane.top(), pane.right(), pane.top() + CONVERSATION_TITLE_HEIGHT, 0xD018202B);
		drawContext.drawText(textRenderer, layout.title(), pane.left() + PANEL_PADDING, pane.top() + PANEL_PADDING, CONVERSATION_TITLE_COLOR, true);

		drawContext.enableScissor(viewport.left(), viewport.top(), viewport.right(), viewport.bottom());
		for (ConversationCardLayout card : layout.cards()) {
			int renderTop = viewport.top() + card.contentTop() - layout.scrollTop();
			int renderBottom = renderTop + card.height();
			if (renderBottom < viewport.top() || renderTop > viewport.bottom()) {
				continue;
			}
			int renderLeft = viewport.left();
			int renderRight = viewport.right();
			drawContext.fill(renderLeft, renderTop, renderRight, renderBottom, card.borderColor());
			drawContext.fill(
				renderLeft + 1,
				renderTop + 1,
				renderRight - 1,
				renderBottom - 1,
				CONVERSATION_INNER_COLOR
			);
			int textX = renderLeft + CONVERSATION_CARD_PADDING;
			int textY = renderTop + CONVERSATION_CARD_PADDING;
			for (String headerLine : card.headerLines()) {
				drawContext.drawText(textRenderer, headerLine, textX, textY, card.borderColor(), true);
				textY += layout.lineHeight();
			}
			for (String bodyLine : card.bodyLines()) {
				drawContext.drawText(textRenderer, bodyLine, textX, textY, TEXT_COLOR, false);
				textY += layout.lineHeight();
			}
			if (card.imageMarkerLine() != null) {
				drawContext.drawText(textRenderer, card.imageMarkerLine(), textX, textY, CONVERSATION_IMAGE_MARKER_COLOR, false);
			}
		}
		drawContext.disableScissor();
		if (layout.footerLine() != null) {
			int footerY = pane.bottom() - PANEL_PADDING - textRenderer.fontHeight;
			drawContext.drawText(textRenderer, layout.footerLine(), pane.left() + PANEL_PADDING, footerY, CONVERSATION_FOOTER_COLOR, false);
		}
	}

	static List<String> formatStateLines(
		boolean overlayEnabled,
		AgentRuntimeSnapshot runtimeSnapshot,
		boolean degraded,
		boolean llmAvailable,
		boolean visionAvailable,
		PlannerOrchestratorDebugSnapshot plannerSnapshot,
		long nowMs
	) {
		return formatStateLines(overlayEnabled, runtimeSnapshot, degraded, llmAvailable, visionAvailable, plannerSnapshot, null, nowMs);
	}

	static List<String> formatStateLines(
		boolean overlayEnabled,
		AgentRuntimeSnapshot runtimeSnapshot,
		boolean degraded,
		boolean llmAvailable,
		boolean visionAvailable,
		PlannerOrchestratorDebugSnapshot plannerSnapshot,
		ActiveJob activeJob,
		long nowMs
	) {
		ArrayList<String> lines = new ArrayList<>();
		SessionSnapshot session = runtimeSnapshot == null || runtimeSnapshot.session() == null
			? SessionSnapshot.initial()
			: runtimeSnapshot.session();
		TaskSnapshot task = runtimeSnapshot == null ? null : runtimeSnapshot.task();
		TaskExecutionSnapshot taskExecution = runtimeSnapshot == null ? null : runtimeSnapshot.taskExecution();
		PlannerContextDebugSnapshot context = plannerSnapshot == null ? null : plannerSnapshot.context();
		long coalesceRemainingMs = plannerSnapshot != null && plannerSnapshot.coalescePending()
			? Math.max(0L, plannerSnapshot.coalesceReadyAtMs() - nowMs)
			: 0L;

		addStateSection(lines, "runtime/session");
		addStateLine(lines, "enabled: " + overlayEnabled);
		addStateLine(lines, "sessionState: " + sessionState(session.mode()));
		addStateLine(lines, "degraded: " + degraded);
		addStateLine(lines, "llmAvailable: " + llmAvailable);
		addStateLine(lines, "visionAvailable: " + visionAvailable);
		addStateLine(lines, "plannerVisionMode: " + plannerValue(plannerSnapshot == null ? null : plannerSnapshot.plannerVisionMode()));

		addStateSection(lines, "action dispatch");
		addStateLine(lines, summarizeActiveJob(activeJob));
		addStateLine(lines, summarizeGoal(activeJob == null ? null : activeJob.directGoal()));
		addStateLine(lines, summarizeTaskExecution(taskExecution));
		addStateLine(lines, summarizeTask(task));

		addStateSection(lines, "planner execution");
		addStateLine(lines, "configured: " + plannerBool(plannerSnapshot, PlannerOrchestratorDebugSnapshot::configured));
		addStateLine(lines, "inFlight: " + plannerBool(plannerSnapshot, PlannerOrchestratorDebugSnapshot::inFlight));
		addStateLine(lines, "plannerInFlight: " + plannerBool(plannerSnapshot, PlannerOrchestratorDebugSnapshot::plannerInFlight));
		addStateLine(lines, "activeGeneration: " + plannerLong(plannerSnapshot, PlannerOrchestratorDebugSnapshot::activeGeneration));
		addStateLine(lines, "currentPhase: " + plannerValue(plannerSnapshot == null ? null : plannerSnapshot.currentPhase()));
		addStateLine(lines, "activeAttemptCount: " + plannerInt(plannerSnapshot, PlannerOrchestratorDebugSnapshot::activeAttemptCount));
		addStateLine(lines, "pendingNewestGeneration: " + plannerLong(plannerSnapshot, PlannerOrchestratorDebugSnapshot::pendingNewestGeneration));
		addStateLine(lines, "supersededCount: " + plannerLong(plannerSnapshot, PlannerOrchestratorDebugSnapshot::supersededCount));

		addStateSection(lines, "retry/coalesce");
		addStateLine(lines, "retryPending: " + plannerBool(plannerSnapshot, PlannerOrchestratorDebugSnapshot::retryPending));
		addStateLine(lines, "retryReadyAtMs: " + plannerLong(plannerSnapshot, PlannerOrchestratorDebugSnapshot::retryReadyAtMs));
		addStateLine(lines, "coalescePending: " + plannerBool(plannerSnapshot, PlannerOrchestratorDebugSnapshot::coalescePending));
		addStateLine(lines, "coalesceWindowMs: " + plannerLong(plannerSnapshot, PlannerOrchestratorDebugSnapshot::coalesceWindowMs));
		addStateLine(lines, "coalesceReadyAtMs: " + plannerLong(plannerSnapshot, PlannerOrchestratorDebugSnapshot::coalesceReadyAtMs));
		addStateLine(lines, "coalesceRemainingMs: " + coalesceRemainingMs);

		addStateSection(lines, "tool/compaction");
		addStateLine(lines, "toolInFlight: " + plannerBool(plannerSnapshot, PlannerOrchestratorDebugSnapshot::toolInFlight));
		addStateLine(lines, "toolUsed: " + plannerBool(plannerSnapshot, PlannerOrchestratorDebugSnapshot::toolUsed));
		addStateLine(lines, "captureInFlight: " + plannerBool(plannerSnapshot, PlannerOrchestratorDebugSnapshot::captureInFlight));
		addStateLine(lines, "compactionInFlight: " + plannerBool(plannerSnapshot, PlannerOrchestratorDebugSnapshot::compactionInFlight));
		addStateLine(lines, "compactionPending: " + contextBool(context, PlannerContextDebugSnapshot::compactionPending));

		addStateSection(lines, "context counters");
		addStateLine(lines, "queuedTriggerCount: " + contextInt(context, PlannerContextDebugSnapshot::queuedTriggerCount));
		addStateLine(lines, "pendingSemanticEventCount: " + contextInt(context, PlannerContextDebugSnapshot::pendingSemanticEventCount));
		addStateLine(lines, "projectedPendingNoticeCount: " + contextInt(context, PlannerContextDebugSnapshot::projectedPendingNoticeCount));
		addStateLine(lines, "acceptedTurnCount: " + contextInt(context, PlannerContextDebugSnapshot::acceptedTurnCount));
		addStateLine(lines, "frozenPlannerMessageCount: " + contextInt(context, PlannerContextDebugSnapshot::frozenPlannerMessageCount));
		addStateLine(lines, "lastObservedEventSeqNo: " + contextLong(context, PlannerContextDebugSnapshot::lastObservedEventSeqNo));
		addStateLine(lines, "lastAcceptedTimeContextAtMs: " + contextLong(context, PlannerContextDebugSnapshot::lastAcceptedTimeContextAtMs));
		addStateLine(lines, "pendingSemanticGap: " + contextBool(context, PlannerContextDebugSnapshot::pendingSemanticGap));
		addStateLine(lines, "overflowFlushPending: " + contextBool(context, PlannerContextDebugSnapshot::overflowFlushPending));

		addStateSection(lines, "summaries");
		addStateLine(lines, summarizeBaseRequest(plannerSnapshot == null ? null : plannerSnapshot.baseRequest()));
		addStateLine(lines, summarizeBaseRequestMessage(plannerSnapshot == null ? null : plannerSnapshot.baseRequest()));
		addStateLine(lines, summarizeCompactionResult(plannerSnapshot == null ? null : plannerSnapshot.lastCompactionResult()));
		addStateLine(lines, summarizeUsage(context == null ? null : context.lastObservedUsage()));
		addStateLine(lines, summarizeCheckpoint(context == null ? null : context.activeCheckpoint()));
		addStateLine(lines, summarizeAmbientContext(context == null ? null : context.acceptedAmbientContext()));
		return List.copyOf(lines);
	}

	static ConversationPaneLayout layoutConversationPane(
		PlannerConversationView view,
		PlannerConversationDebugSnapshot snapshot,
		int windowWidth,
		int windowHeight,
		TextWidthMeasurer textWidthMeasurer,
		int lineHeight,
		int requestedScrollTop,
		boolean pinnedToBottom,
		String footerLine
	) {
		int paneWidth = clamp((int) Math.round(windowWidth * CONVERSATION_WIDTH_RATIO), CONVERSATION_WIDTH_MIN, Math.max(CONVERSATION_WIDTH_MIN, windowWidth - (PANEL_MARGIN * 2)));
		int paneHeight = clamp((int) Math.round(windowHeight * CONVERSATION_HEIGHT_RATIO), CONVERSATION_HEIGHT_MIN, Math.max(CONVERSATION_HEIGHT_MIN, windowHeight - (PANEL_MARGIN * 2)));
		Rect paneBounds = new Rect(
			windowWidth - PANEL_MARGIN - paneWidth,
			PANEL_MARGIN,
			windowWidth - PANEL_MARGIN,
			PANEL_MARGIN + paneHeight
		);
		Rect viewportBounds = new Rect(
			paneBounds.left() + PANEL_PADDING,
			paneBounds.top() + CONVERSATION_TITLE_HEIGHT + PANEL_PADDING,
			paneBounds.right() - PANEL_PADDING,
			paneBounds.bottom() - PANEL_PADDING - (footerLine == null ? 0 : CONVERSATION_FOOTER_HEIGHT)
		);

		int contentWidth = Math.max(48, viewportBounds.width() - (CONVERSATION_CARD_PADDING * 2));
		ArrayList<ConversationCardLayout> cards = new ArrayList<>();
		int contentTop = 0;
		for (PlannerConversationDebugMessage message : snapshot.messages()) {
			List<String> headerLines = wrapText(formatConversationHeader(view, message), contentWidth, textWidthMeasurer);
			List<String> bodyLines = wrapText(conversationBodyText(message), contentWidth, textWidthMeasurer);
			String imageMarkerLine = message.hasImageAttachment() ? "image attached" : null;
			int lineCount = headerLines.size() + bodyLines.size() + (imageMarkerLine == null ? 0 : 1);
			int cardHeight = (CONVERSATION_CARD_PADDING * 2)
				+ (Math.max(1, lineCount) * lineHeight)
				+ (bodyLines.isEmpty() ? 0 : 2)
				+ (imageMarkerLine == null ? 0 : 2);
			cards.add(new ConversationCardLayout(
				message,
				headerLines,
				bodyLines,
				imageMarkerLine,
				contentTop,
				cardHeight,
				borderColorFor(message.kind())
			));
			contentTop += cardHeight + CONVERSATION_CARD_GAP;
		}

		int contentHeight = Math.max(0, contentTop - (cards.isEmpty() ? 0 : CONVERSATION_CARD_GAP));
		int maxScroll = Math.max(0, contentHeight - viewportBounds.height());
		int scrollTop = pinnedToBottom ? maxScroll : clamp(requestedScrollTop, 0, maxScroll);
		return new ConversationPaneLayout(
			paneBounds,
			viewportBounds,
			contentHeight,
			scrollTop,
			maxScroll,
			lineHeight,
			formatConversationTitle(view, snapshot),
			List.copyOf(cards),
			footerLine
		);
	}

	static String formatConversationFooter(PlannerOrchestratorDebugSnapshot plannerSnapshot, long nowMs) {
		return formatConversationFooter(plannerSnapshot, null, null, nowMs);
	}

	static String formatConversationFooter(
		PlannerOrchestratorDebugSnapshot plannerSnapshot,
		AgentRuntimeSnapshot runtimeSnapshot,
		ActiveJob activeJob,
		long nowMs
	) {
		String actionStatus = summarizeActionFooter(runtimeSnapshot, activeJob);
		if (plannerSnapshot == null || !plannerSnapshot.inFlight()) {
			return actionStatus;
		}
		String spinner = SPINNER_FRAMES[(int) ((Math.max(0L, nowMs) / 200L) % SPINNER_FRAMES.length)];
		PlannerContextDebugSnapshot context = plannerSnapshot.context();
		String status = plannerSnapshot.compactionInFlight()
			? "compacting context"
			: plannerSnapshot.captureInFlight()
				? "capturing view"
				: plannerSnapshot.toolInFlight()
					? "waiting for tool follow-up"
					: context != null && context.overflowFlushPending()
						? "flushing pending semantic context"
					: plannerSnapshot.coalescePending()
						? "coalescing updates"
						: plannerSnapshot.plannerInFlight()
							? "waiting for planner"
							: "working";
		String plannerStatus = spinner + " " + status;
		return actionStatus == null ? plannerStatus : plannerStatus + " | " + actionStatus;
	}

	static List<String> wrapText(String text, int maxWidth, TextWidthMeasurer textWidthMeasurer) {
		ArrayList<String> lines = new ArrayList<>();
		String normalized = text == null ? "" : text.replace("\r", "");
		String[] paragraphs = normalized.split("\n", -1);
		for (String paragraph : paragraphs) {
			wrapParagraph(lines, paragraph, Math.max(1, maxWidth), textWidthMeasurer);
		}
		if (lines.isEmpty()) {
			lines.add("");
		}
		return List.copyOf(lines);
	}

	static ConversationScrollUpdate scrollConversation(
		ConversationPaneLayout layout,
		int currentScrollTop,
		double mouseX,
		double mouseY,
		double verticalAmount
	) {
		if (layout == null || !layout.viewportBounds().contains(mouseX, mouseY) || layout.maxScroll() <= 0) {
			return new ConversationScrollUpdate(currentScrollTop, currentScrollTop >= (layout == null ? 0 : layout.maxScroll()), false);
		}
		int updatedScrollTop = clamp(
			(int) Math.round(currentScrollTop - (verticalAmount * CONVERSATION_SCROLL_STEP_PX)),
			0,
			layout.maxScroll()
		);
		return new ConversationScrollUpdate(updatedScrollTop, updatedScrollTop >= layout.maxScroll(), true);
	}

	private static void wrapParagraph(List<String> lines, String paragraph, int maxWidth, TextWidthMeasurer textWidthMeasurer) {
		if (paragraph == null || paragraph.isEmpty()) {
			lines.add("");
			return;
		}

		String current = "";
		for (String word : paragraph.split("\\s+")) {
			if (word.isEmpty()) {
				continue;
			}
			String candidate = current.isEmpty() ? word : current + " " + word;
			if (textWidthMeasurer.width(candidate) <= maxWidth) {
				current = candidate;
				continue;
			}
			if (!current.isEmpty()) {
				lines.add(current);
				current = "";
			}
			if (textWidthMeasurer.width(word) <= maxWidth) {
				current = word;
				continue;
			}
			lines.addAll(breakWord(word, maxWidth, textWidthMeasurer));
		}
		if (!current.isEmpty()) {
			lines.add(current);
		}
	}

	private static List<String> breakWord(String word, int maxWidth, TextWidthMeasurer textWidthMeasurer) {
		ArrayList<String> chunks = new ArrayList<>();
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < word.length(); index++) {
			char next = word.charAt(index);
			String candidate = builder.toString() + next;
			if (builder.length() > 0 && textWidthMeasurer.width(candidate) > maxWidth) {
				chunks.add(builder.toString());
				builder.setLength(0);
			}
			builder.append(next);
		}
		if (builder.length() > 0) {
			chunks.add(builder.toString());
		}
		return chunks;
	}

	private static void addStateSection(List<String> lines, String label) {
		lines.add("[" + trim(label, STATE_MAX_LINE_LENGTH - 2) + "]");
	}

	private static void addStateLine(List<String> lines, String line) {
		lines.add(trim(normalizeWhitespace(line), STATE_MAX_LINE_LENGTH));
	}

	private static int colorForStateLine(String line) {
		return line.startsWith("[") ? SECTION_COLOR : TEXT_COLOR;
	}

	private static String summarizeBaseRequest(PlannerRequest request) {
		if (request == null) {
			return "baseRequest: none";
		}
		PlannerTriggerBatch triggerBatch = request.triggerBatch();
		int triggerCount = triggerBatch == null ? 0 : triggerBatch.size();
		return "baseRequest: tick=" + request.tick()
			+ " session=" + sessionState(request.sessionMode())
			+ " player=" + plannerValue(request.primaryInteractionPlayer())
			+ " triggers=" + triggerCount
			+ " sender=" + plannerValue(request.senderName())
			+ " toolResult=" + hasText(request.toolResult());
	}

	private static String summarizeBaseRequestMessage(PlannerRequest request) {
		if (request == null) {
			return "baseRequestMessage: none";
		}
		return "baseRequestMessage: " + trim(normalizeWhitespace(request.message()), MAX_MESSAGE_PREVIEW_LENGTH);
	}

	private static String summarizeCompactionResult(CompactionExecutionResult result) {
		if (result == null) {
			return "lastCompactionResult: none";
		}
		if (!result.succeeded()) {
			return "lastCompactionResult: failure type=" + plannerValue(result.failureType() == null ? null : result.failureType().name())
				+ " message=" + trim(normalizeWhitespace(result.failureMessage()), MAX_MESSAGE_PREVIEW_LENGTH);
		}
		CompactionCheckpoint checkpoint = result.checkpoint();
		String goal = checkpoint == null ? "none" : plannerValue(checkpoint.activeGoal());
		int openLoops = checkpoint == null || checkpoint.openLoops() == null ? 0 : checkpoint.openLoops().size();
		int timelineCount = checkpoint == null || checkpoint.recentTimeline() == null ? 0 : checkpoint.recentTimeline().size();
		return "lastCompactionResult: success goal=" + goal + " openLoops=" + openLoops + " timeline=" + timelineCount;
	}

	private static String summarizeUsage(LlmUsageSnapshot usage) {
		if (usage == null || (usage.promptTokens() == null && usage.completionTokens() == null && usage.totalTokens() == null)) {
			return "lastObservedUsage: unknown";
		}
		return "lastObservedUsage: prompt=" + plannerValue(usage.promptTokens())
			+ " completion=" + plannerValue(usage.completionTokens())
			+ " total=" + plannerValue(usage.totalTokens());
	}

	private static String summarizeCheckpoint(CompactionCheckpoint checkpoint) {
		if (checkpoint == null) {
			return "activeCheckpoint: none";
		}
		int openLoops = checkpoint.openLoops() == null ? 0 : checkpoint.openLoops().size();
		int timelineCount = checkpoint.recentTimeline() == null ? 0 : checkpoint.recentTimeline().size();
		return "activeCheckpoint: goal=" + plannerValue(checkpoint.activeGoal())
			+ " openLoops=" + openLoops
			+ " timeline=" + timelineCount;
	}

	private static String summarizeAmbientContext(PlannerAmbientContext ambientContext) {
		if (ambientContext == null) {
			return "ambientContext: none";
		}
		return "ambientContext: session=" + sessionState(ambientContext.sessionMode())
			+ " player=" + plannerValue(ambientContext.primaryInteractionPlayer())
			+ " goal=" + plannerValue(ambientContext.activeGoalDescription());
	}

	private static String summarizeActiveJob(ActiveJob activeJob) {
		if (activeJob == null || activeJob.isIdle()) {
			return "activeJob: idle";
		}
		return "activeJob: type=" + activeJob.type().name()
			+ " status=" + activeJob.status().name()
			+ " error=" + plannerValue(activeJob.lastError())
			+ " blocked=" + plannerValue(activeJob.blockedReason())
			+ " source=" + plannerValue(activeJob.source());
	}

	private static String summarizeGoal(GoalSnapshot goal) {
		if (goal == null || goal.type() == null) {
			return "activeGoal: none";
		}
		String target = goal.targetPlayer() == null || goal.targetPlayer().isBlank()
			? ""
			: " target=" + goal.targetPlayer();
		String position = goal.position() == null
			? ""
			: " x=" + goal.position().x()
				+ " y=" + goal.position().y()
				+ " z=" + goal.position().z()
				+ " exactY=" + goal.position().exactY();
		String mine = goal.mineSpec() == null
			? ""
			: " blocks=" + goal.mineSpec().blockIds().size()
				+ " quantity=" + goal.mineSpec().quantity();
		return "activeGoal: " + goal.type().name() + target + position + mine;
	}

	private static String summarizeTaskExecution(TaskExecutionSnapshot taskExecution) {
		if (taskExecution == null || taskExecution.state() == null) {
			return "taskExecution: state=-";
		}
		return "taskExecution: state=" + taskExecution.state().name()
			+ " cause=" + plannerValue(taskExecution.terminationCause() == null ? null : taskExecution.terminationCause().name())
			+ " pathEvent=" + plannerValue(taskExecution.lastPathEvent())
			+ " taskId=" + plannerValue(taskExecution.taskId())
			+ " process=" + plannerValue(taskExecution.processName())
			+ " eta=" + plannerValue(taskExecution.estimatedTicksToGoal());
	}

	private static String summarizeTask(TaskSnapshot task) {
		if (task == null || task.state() == null) {
			return "semanticTask: none";
		}
		return "semanticTask: state=" + task.state().name()
			+ " step=" + plannerValue(task.activeStepKind() == null ? null : task.activeStepKind().name())
			+ " failure=" + plannerValue(task.lastFailure());
	}

	private static String summarizeActionFooter(AgentRuntimeSnapshot runtimeSnapshot, ActiveJob activeJob) {
		if (activeJob != null && !activeJob.isIdle()) {
			StringBuilder builder = new StringBuilder("job ")
				.append(activeJob.type().name())
				.append(' ')
				.append(activeJob.status().name());
			if (activeJob.lastError() != null && !activeJob.lastError().isBlank()) {
				builder.append(" error=").append(activeJob.lastError());
			}
			else if (activeJob.blockedReason() != null && !activeJob.blockedReason().isBlank()) {
				builder.append(" blocked=").append(activeJob.blockedReason());
			}
			return trim(builder.toString(), 96);
		}
		TaskExecutionSnapshot execution = runtimeSnapshot == null ? null : runtimeSnapshot.taskExecution();
		if (execution == null || execution.state() == null || execution.state() == TaskExecutionState.IDLE) {
			return null;
		}
		StringBuilder builder = new StringBuilder("exec ").append(execution.state().name());
		if (execution.activeGoal() != null && execution.activeGoal().type() != null) {
			builder.append(' ').append(execution.activeGoal().type().name());
		}
		if (execution.lastPathEvent() != null && !execution.lastPathEvent().isBlank()) {
			builder.append(" event=").append(execution.lastPathEvent());
		}
		return trim(builder.toString(), 96);
	}

	private static PlannerConversationDebugSnapshot placeholderConversationSnapshot(PlannerOrchestratorDebugSnapshot plannerSnapshot) {
		return new PlannerConversationDebugSnapshot(
			plannerSnapshot == null ? 0L : plannerSnapshot.activeGeneration(),
			plannerSnapshot == null || plannerSnapshot.currentPhase() == null ? "IDLE" : plannerSnapshot.currentPhase(),
			plannerSnapshot == null ? 0 : plannerSnapshot.activeAttemptCount(),
			List.of(new PlannerConversationDebugMessage(
				"system",
				PlannerConversationDebugKind.NOTICE,
				"No LLM conversation has been submitted yet.",
				plannerSnapshot == null ? 0L : plannerSnapshot.activeGeneration(),
				plannerSnapshot == null || plannerSnapshot.currentPhase() == null ? "IDLE" : plannerSnapshot.currentPhase(),
				plannerSnapshot == null ? 0 : plannerSnapshot.activeAttemptCount(),
				false
			))
		);
	}

	private static String formatConversationTitle(PlannerConversationView view, PlannerConversationDebugSnapshot snapshot) {
		String viewLabel = view == PlannerConversationView.CONTEXT ? "Context" : "Chronicle";
		if (snapshot == null || snapshot.isEmpty()) {
			return viewLabel + " | empty";
		}
		return trim(
			viewLabel
				+ " | g" + snapshot.generation()
				+ " | " + normalizePhase(snapshot.phase())
				+ " | a" + snapshot.attempt()
				+ " | " + snapshot.messages().size() + " msg",
			60
		);
	}

	private static String formatConversationHeader(PlannerConversationView view, PlannerConversationDebugMessage message) {
		StringBuilder header = new StringBuilder();
		if (view == PlannerConversationView.CHRONICLE && message.timestampMs() > 0L) {
			header.append(CHRONICLE_TIME_FORMAT.format(Instant.ofEpochMilli(message.timestampMs()))).append(" · ");
		}
		header.append(trim(conversationLabel(message), 30));
		if (view == PlannerConversationView.CHRONICLE) {
			if (message.generation() > 0L) {
				header.append(" · g").append(message.generation());
			}
			if (message.attempt() > 1) {
				header.append(" a").append(message.attempt());
			}
			if (message.superseded()) {
				header.append(" · superseded");
			}
		}
		return trim(header.toString(), 72);
	}

	private static String conversationLabel(PlannerConversationDebugMessage message) {
		return switch (message.kind()) {
			case USER_TURN -> message.role();
			case ASSISTANT_TURN -> "assistant";
			case TOOL_RESULT -> "tool";
			case TASK -> "assistant · action";
			case FAILURE -> "failure";
			case CHECKPOINT -> "checkpoint";
			case NOTICE -> "notice";
			case SYSTEM -> "system";
		};
	}

	private static String conversationBodyText(PlannerConversationDebugMessage message) {
		String text = message == null ? "" : message.text().replace('\t', ' ');
		if (text.isBlank()) {
			return "-";
		}
		int cap = switch (message.kind()) {
			case SYSTEM, CHECKPOINT, NOTICE -> STATIC_BODY_CHAR_CAP;
			default -> BODY_CHAR_CAP;
		};
		if (text.length() > cap) {
			return text.substring(0, cap) + " … [+" + (text.length() - cap) + "]";
		}
		return text;
	}

	private static int borderColorFor(PlannerConversationDebugKind kind) {
		if (kind == null) {
			return SYSTEM_BORDER_COLOR;
		}
		return switch (kind) {
			case FAILURE -> FAILURE_BORDER_COLOR;
			case ASSISTANT_TURN -> ASSISTANT_BORDER_COLOR;
			case USER_TURN, TOOL_RESULT -> USER_BORDER_COLOR;
			case SYSTEM, CHECKPOINT, NOTICE, TASK -> SYSTEM_BORDER_COLOR;
		};
	}

	private static String normalizePhase(String value) {
		if (value == null || value.isBlank()) {
			return "unknown";
		}
		return value.toLowerCase(Locale.ROOT);
	}

	private static boolean plannerBool(PlannerOrchestratorDebugSnapshot snapshot, PlannerBooleanExtractor extractor) {
		return snapshot != null && extractor.extract(snapshot);
	}

	private static long plannerLong(PlannerOrchestratorDebugSnapshot snapshot, PlannerLongExtractor extractor) {
		return snapshot == null ? 0L : extractor.extract(snapshot);
	}

	private static int plannerInt(PlannerOrchestratorDebugSnapshot snapshot, PlannerIntExtractor extractor) {
		return snapshot == null ? 0 : extractor.extract(snapshot);
	}

	private static boolean contextBool(PlannerContextDebugSnapshot snapshot, ContextBooleanExtractor extractor) {
		return snapshot != null && extractor.extract(snapshot);
	}

	private static long contextLong(PlannerContextDebugSnapshot snapshot, ContextLongExtractor extractor) {
		return snapshot == null ? 0L : extractor.extract(snapshot);
	}

	private static int contextInt(PlannerContextDebugSnapshot snapshot, ContextIntExtractor extractor) {
		return snapshot == null ? 0 : extractor.extract(snapshot);
	}

	private static String plannerValue(Object value) {
		if (value == null) {
			return "-";
		}
		if (value instanceof String text) {
			String normalized = normalizeWhitespace(text);
			return normalized.isEmpty() ? "-" : normalized;
		}
		return String.valueOf(value);
	}

	private static String normalizeWhitespace(String value) {
		if (value == null) {
			return "";
		}
		return value.replace('\n', ' ').replace('\r', ' ').trim();
	}

	private static String trim(String value, int maxLength) {
		String normalized = value == null ? "" : value;
		if (normalized.length() <= maxLength) {
			return normalized;
		}
		return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
	}

	private static String sessionState(SessionMode sessionMode) {
		if (sessionMode == null) {
			return "-";
		}
		return sessionMode.name().toLowerCase(Locale.ROOT);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	@FunctionalInterface
	interface TextWidthMeasurer {
		int width(String text);
	}

	@FunctionalInterface
	private interface PlannerBooleanExtractor {
		boolean extract(PlannerOrchestratorDebugSnapshot snapshot);
	}

	@FunctionalInterface
	private interface PlannerLongExtractor {
		long extract(PlannerOrchestratorDebugSnapshot snapshot);
	}

	@FunctionalInterface
	private interface PlannerIntExtractor {
		int extract(PlannerOrchestratorDebugSnapshot snapshot);
	}

	@FunctionalInterface
	private interface ContextBooleanExtractor {
		boolean extract(PlannerContextDebugSnapshot snapshot);
	}

	@FunctionalInterface
	private interface ContextLongExtractor {
		long extract(PlannerContextDebugSnapshot snapshot);
	}

	@FunctionalInterface
	private interface ContextIntExtractor {
		int extract(PlannerContextDebugSnapshot snapshot);
	}

	record Rect(int left, int top, int right, int bottom) {
		int width() {
			return Math.max(0, right - left);
		}

		int height() {
			return Math.max(0, bottom - top);
		}

		boolean contains(double x, double y) {
			return x >= left && x <= right && y >= top && y <= bottom;
		}
	}

	record ConversationCardLayout(
		PlannerConversationDebugMessage message,
		List<String> headerLines,
		List<String> bodyLines,
		String imageMarkerLine,
		int contentTop,
		int height,
		int borderColor
	) {
	}

	record ConversationPaneLayout(
		Rect paneBounds,
		Rect viewportBounds,
		int contentHeight,
		int scrollTop,
		int maxScroll,
		int lineHeight,
		String title,
		List<ConversationCardLayout> cards,
		String footerLine
	) {
		boolean atBottom() {
			return scrollTop >= maxScroll;
		}
	}

	record ConversationScrollUpdate(int scrollTop, boolean pinnedToBottom, boolean consumed) {
	}
}
