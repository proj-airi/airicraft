package ai.moeru.airicraft.settings;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.dashboard.DiagnosticReport;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** The marker is fixed on entry. Only an explicitly reviewed report can be saved. */
final class DiagnosticReportScreen extends Screen {
	private final Screen parent;
	private final Function<DiagnosticReport.Request, CompletableFuture<DiagnosticReport>> prepareReport;
	private final EnumMap<DiagnosticReport.Mode, Button> modeButtons = new EnumMap<>(DiagnosticReport.Mode.class);
	private DiagnosticReport.Mode mode = DiagnosticReport.Mode.MINIMAL;
	private String description = "";
	private DiagnosticReport preview;
	private EditBox descriptionField;
	private Button previewButton;
	private Button saveButton;
	private Button inspectButton;
	private boolean busy;
	private int revision;
	private int scroll;
	private String message = "Choose attachments, then preview. Nothing is saved or uploaded yet.";

	DiagnosticReportScreen(Screen parent, DiagnosticReport.Draft draft) {
		this(parent, request -> AiricraftClient.runtimeController().previewDiagnosticReport(draft, request));
	}

	DiagnosticReportScreen(Screen parent, Function<DiagnosticReport.Request, CompletableFuture<DiagnosticReport>> prepareReport) {
		super(Component.literal("Report this moment"));
		this.parent = parent;
		this.prepareReport = prepareReport;
	}

	@Override protected void init() {
		int w = Math.min(560, width - 24), left = (width - w) / 2;
		descriptionField = addRenderableWidget(new EditBox(font, left, 46, w, 20, Component.literal("What went wrong?")));
		descriptionField.setMaxLength(2000);
		descriptionField.setValue(description);
		descriptionField.setResponder(value -> { description = value; invalidate(); });
		String[] names = {"Minimal", "Summary", "Developer"};
		modeButtons.clear();
		for (var option : DiagnosticReport.Mode.values()) {
			var button = addRenderableWidget(Button.builder(Component.literal(names[option.ordinal()]), ignored -> {
				mode = option; invalidate(); rebuildWidgets();
			}).bounds(left + option.ordinal() * (w / 3), 72, w / 3 - 4, 20).build());
			modeButtons.put(option, button);
		}
		previewButton = addRenderableWidget(Button.builder(Component.literal("Preview attachments"), ignored -> preview())
			.bounds(left, 98, w / 2 - 2, 20).build());
		inspectButton = addRenderableWidget(Button.builder(Component.literal("Inspect evidence"), ignored -> {
			if (preview != null && !busy) minecraft.setScreen(new DiagnosticEvidenceScreen(this, preview));
		}).bounds(left + w / 2 + 2, 98, w / 2 - 2, 20).build());
		saveButton = addRenderableWidget(Button.builder(Component.literal("Save these attachments"), ignored -> save())
			.bounds(left, height - 28, w * 2 / 3 - 4, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), ignored -> onClose())
			.bounds(left + w * 2 / 3, height - 28, w / 3, 20).build());
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
		var minecraft = this.minecraft;
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
		var minecraft = this.minecraft;
		AiricraftClient.runtimeController().saveDiagnosticReport(preview).whenComplete((path, failure) -> minecraft.execute(() -> {
			busy = false;
			updateControls();
			Component title = Component.literal(failure == null ? "Bug report saved" : "Could not save bug report");
			Component detail = Component.literal(failure == null ? "Saved to " + path.toAbsolutePath()
				+ "\nReview the ZIP before sharing it. Nothing was uploaded."
				: "Check free disk space and game directory permissions, then try again.");
			if (minecraft.screen != this) { minecraft.gui.setOverlayMessage(title, false); return; }
			minecraft.setScreen(new AlertScreen(() -> minecraft.setScreen(this), title, detail, Component.translatable("gui.back"), true) {
				@Override protected void init() {
					super.init();
					if (failure == null) addRenderableWidget(Button.builder(Component.literal("Open report folder"),
						ignored -> net.minecraft.Util.getPlatform().openFile(path.getParent().toFile()))
						.bounds(width / 2 - 100, height - 30, 200, 20).build());
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

	@Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		updateControls();
		super.render(graphics, mouseX, mouseY, delta);
		int w = Math.min(560, width - 24), left = (width - w) / 2;
		graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFFFF);
		graphics.drawString(font, Component.literal("What went wrong? (optional)"), left, 33, 0xFFFFFFFF);
		String content = "Includes: " + String.join("; ", mode.categories()) + "\n\n" + message;
		var lines = font.split(Component.literal(content), w - 4);
		scroll = Math.min(scroll, Math.max(0, lines.size() * 12 - Math.max(0, height - 164)));
		graphics.enableScissor(left, 126, left + w, Math.max(126, height - 38));
		int y = 126 - scroll;
		for (var line : lines) { graphics.drawString(font, line, left, y, 0xFFE0E0E0); y += 12; }
		graphics.disableScissor();
	}

	@Override public void onClose() { minecraft.setScreen(parent); }
	@Override public boolean isPauseScreen() { return false; }
}
