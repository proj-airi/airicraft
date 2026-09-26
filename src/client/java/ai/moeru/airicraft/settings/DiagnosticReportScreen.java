package ai.moeru.airicraft.settings;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.dashboard.DiagnosticReport;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.NoticeScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.EnumMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** The marker is fixed on entry. Only an explicitly reviewed report can be saved. */
final class DiagnosticReportScreen extends Screen {
	private final Screen parent;
	private final Function<DiagnosticReport.Request, CompletableFuture<DiagnosticReport>> prepareReport;
	private final EnumMap<DiagnosticReport.Mode, ButtonWidget> modeButtons = new EnumMap<>(DiagnosticReport.Mode.class);
	private DiagnosticReport.Mode mode = DiagnosticReport.Mode.MINIMAL;
	private String description = "";
	private DiagnosticReport preview;
	private TextFieldWidget descriptionField;
	private ButtonWidget previewButton;
	private ButtonWidget saveButton;
	private ButtonWidget inspectButton;
	private boolean busy;
	private int revision;
	private int scroll;
	private String message = "Choose attachments, then preview. Nothing is saved or uploaded yet.";

	DiagnosticReportScreen(Screen parent, DiagnosticReport.Draft draft) {
		this(parent, request -> AiricraftClient.runtimeController().previewDiagnosticReport(draft, request));
	}

	DiagnosticReportScreen(Screen parent, Function<DiagnosticReport.Request, CompletableFuture<DiagnosticReport>> prepareReport) {
		super(Text.literal("Report this moment"));
		this.parent = parent;
		this.prepareReport = prepareReport;
	}

	@Override protected void init() {
		int w = Math.min(560, width - 24), left = (width - w) / 2;
		descriptionField = addDrawableChild(new TextFieldWidget(textRenderer, left, 46, w, 20, Text.literal("What went wrong?")));
		descriptionField.setMaxLength(2000);
		descriptionField.setText(description);
		descriptionField.setChangedListener(value -> { description = value; invalidate(); });
		String[] names = {"Minimal", "Summary", "Developer"};
		modeButtons.clear();
		for (var option : DiagnosticReport.Mode.values()) {
			var button = addDrawableChild(ButtonWidget.builder(Text.literal(names[option.ordinal()]), ignored -> {
				mode = option; invalidate(); clearAndInit();
			}).dimensions(left + option.ordinal() * (w / 3), 72, w / 3 - 4, 20).build());
			modeButtons.put(option, button);
		}
		previewButton = addDrawableChild(ButtonWidget.builder(Text.literal("Preview attachments"), ignored -> preview())
			.dimensions(left, 98, w / 2 - 2, 20).build());
		inspectButton = addDrawableChild(ButtonWidget.builder(Text.literal("Inspect evidence"), ignored -> {
			if (preview != null && !busy) client.setScreen(new DiagnosticEvidenceScreen(this, preview));
		}).dimensions(left + w / 2 + 2, 98, w / 2 - 2, 20).build());
		saveButton = addDrawableChild(ButtonWidget.builder(Text.literal("Save these attachments"), ignored -> save())
			.dimensions(left, height - 28, w * 2 / 3 - 4, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), ignored -> close())
			.dimensions(left + w * 2 / 3, height - 28, w / 3, 20).build());
		updateControls();
	}

	private void invalidate() {
		revision++; preview = null; scroll = 0;
		if (saveButton != null) saveButton.active = false;
		if (inspectButton != null) inspectButton.active = false;
		message = "Preview required. Saving includes the categories listed above.";
	}

	private void preview() {
		if (busy) return;
		busy = true;
		updateControls();
		int expectedRevision = revision;
		var minecraft = client;
		prepareReport.apply(new DiagnosticReport.Request(mode, description))
			.whenComplete((report, failure) -> minecraft.execute(() -> {
				busy = false;
				if (revision == expectedRevision) {
					preview = report;
					message = failure == null ? report.preview().getAsJsonObject("summary").get("text").getAsString()
						: "Could not prepare this report. Try again.";
				}
				updateControls();
			}));
	}

	private void save() {
		if (busy || preview == null) return;
		busy = true;
		updateControls();
		var minecraft = client;
		AiricraftClient.runtimeController().saveDiagnosticReport(preview).whenComplete((path, failure) -> minecraft.execute(() -> {
			busy = false;
			updateControls();
			Text title = Text.literal(failure == null ? "Bug report saved" : "Could not save bug report");
			Text detail = Text.literal(failure == null ? "Saved to " + path.toAbsolutePath()
				+ "\nReview the ZIP before sharing it. Nothing was uploaded."
				: "Check free disk space and game directory permissions, then try again.");
			if (minecraft.currentScreen != this) { minecraft.inGameHud.setOverlayMessage(title, false); return; }
			minecraft.setScreen(new NoticeScreen(() -> minecraft.setScreen(this), title, detail, Text.translatable("gui.back"), true) {
				@Override protected void init() {
					super.init();
					if (failure == null) addDrawableChild(ButtonWidget.builder(Text.literal("Open report folder"),
						ignored -> net.minecraft.util.Util.getOperatingSystem().open(path.getParent().toFile()))
						.dimensions(width / 2 - 100, height - 30, 200, 20).build());
				}
			});
		}));
	}

	private void updateControls() {
		modeButtons.forEach((option, button) -> button.active = !busy && option != mode);
		saveButton.active = !busy && preview != null;
		inspectButton.active = !busy && preview != null;
		previewButton.active = !busy;
		descriptionField.setEditable(!busy);
	}

	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		scroll = Math.max(0, scroll - (int) (vertical * 20));
		return true;
	}

	@Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		updateControls();
		super.render(context, mouseX, mouseY, delta);
		int w = Math.min(560, width - 24), left = (width - w) / 2;
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal("What went wrong? (optional)"), left, 33, 0xFFFFFFFF);
		String content = "Includes: " + String.join("; ", mode.categories()) + "\n\n" + message;
		var lines = textRenderer.wrapLines(Text.literal(content), w - 4);
		scroll = Math.min(scroll, Math.max(0, lines.size() * 12 - Math.max(0, height - 164)));
		context.enableScissor(left, 126, left + w, Math.max(126, height - 38));
		int y = 126 - scroll;
		for (var line : lines) { context.drawTextWithShadow(textRenderer, line, left, y, 0xFFE0E0E0); y += 12; }
		context.disableScissor();
	}

	@Override public void close() { client.setScreen(parent); }
	@Override public boolean shouldPause() { return false; }
}
