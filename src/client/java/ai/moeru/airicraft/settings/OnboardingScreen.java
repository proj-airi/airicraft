package ai.moeru.airicraft.settings;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationBridge;
import ai.moeru.airicraft.agent.integration.rei.ReiRecipeSearchBridge;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** First-run setup. Editing and probing are isolated from the saved configuration. */
public final class OnboardingScreen extends Screen {
	private final Screen parent;
	private SettingsDraft draft;
	private Provider planner;
	private Provider vision;
	private String executable;
	private String codexModel;
	private boolean codex;
	private boolean visionEnabled;
	private boolean nativeVision;
	private boolean reveal;
	private Tab tab = Tab.PLANNER;
	private String problem = "";
	private List<Compatibility> compatibility = List.of();
	private ButtonWidget finishButton;
	private ButtonWidget checkButton;
	private ButtonWidget modelsButton;
	private final List<ButtonWidget> choices = new ArrayList<>();
	private boolean dropdown;
	private int modelOffset;
	private String modelQuery = "";
	private int left;
	private int span;
	private TextFieldWidget modelField;

	private enum Tab { PLANNER, VISION, MODS }
	private OnboardingScreen(Screen parent) {
		super(Text.literal("Welcome to Airicraft"));
		this.parent = parent;
		try {
			draft = SettingsDraft.open(directory());
			var defaults = AgentConfig.LlmConfig.defaults();
			planner = new Provider(value("providerBaseUrl", defaults.providerBaseUrl()), value("apiKey", ""), value("model", ""));
			vision = new Provider(value("visionProviderBaseUrl", defaults.visionProviderBaseUrl()), value("visionApiKey", ""), value("visionModel", ""));
			codex = value("plannerBackend", "openai-compatible").equals("codex-app-server");
			executable = value("codexAppServer.executable", "codex");
			codexModel = value("codexAppServer.model", "");
			visionEnabled = !vision.key.isBlank() || !vision.model.isBlank();
			nativeVision = value("plannerNativeVisionEnabled", false);
		} catch (IOException | RuntimeException exception) {
			draft = null;
			problem = "Could not read settings. Check the YAML files and reopen setup.";
		}
	}
	public static Screen create(Screen parent) { return new OnboardingScreen(parent); }
	private static Path directory() { return FabricLoader.getInstance().getConfigDir().resolve("airicraft"); }
	public static boolean required() {
		try { return OnboardingState.required(SettingsDraft.open(directory())); }
		catch (IOException | RuntimeException exception) { return true; }
	}
	private <T> T value(String key, T fallback) { return draft.get("agent.yml", key, fallback); }
	private void save(String key, Object value) { draft.set("agent.yml", key, value); }

	@Override protected void init() {
		left = Math.max(10, (width - 420) / 2);
		span = Math.min(420, width - 20);
		dropdown = false;
		choices.clear();
		checkButton = null;
		modelsButton = null;
		for (var section : Tab.values()) {
			String name = switch (section) { case PLANNER -> "Planner"; case VISION -> "Vision"; case MODS -> "Mods"; };
			var button = button(name, left + section.ordinal() * (span + 3) / 3, 30, (span - 6) / 3, () -> {
				tab = section; reveal = false; clearAndInit();
			});
			button.active = tab != section && draft != null;
		}
		compatibility = List.of(
			compatibility("JourneyMap", "journeymap", "airicraft-journeymap-compat", MapIntegrationBridge.registry().provider("journeymap").isPresent()),
			compatibility("REI", "roughlyenoughitems", "airicraft-rei-compat", ReiRecipeSearchBridge.backend().available()));
		if (draft != null && tab != Tab.MODS) providerForm();
		button("Later", left, height - 28, (span - 8) / 2, this::close);
		finishButton = button("Save & finish", left + (span + 8) / 2, height - 28, (span - 8) / 2, this::finish);
		updateButtons();
	}
	private void providerForm() {
		Provider provider = current();
		boolean local = tab == Tab.PLANNER && codex;
		if (tab == Tab.PLANNER) {
			button(local ? "Local Codex" : "API provider", left, 56, (span - 6) / 2, () -> {
				codex = !codex; planner.changed(true); clearAndInit();
			}).setTooltip(Tooltip.of(Text.literal("Switch between an API provider and your local Codex sign-in.")));
			button(nativeVision ? "Image input: on" : "Image input: off", left + (span + 6) / 2, 56, (span - 6) / 2, () -> {
				nativeVision = !nativeVision; planner.changed(false); vision.changed(true); clearAndInit();
			}).setTooltip(Tooltip.of(Text.literal("Enable if this planner model accepts images. The connection test will include a test image.")));
		} else {
			if (nativeVision) return;
			button(visionEnabled ? "Separate vision: on" : "Separate vision: off", left, 56, span, () -> {
				visionEnabled = !visionEnabled; vision.changed(false); clearAndInit();
			});
			if (!visionEnabled) return;
		}
		field(local ? "Executable" : "Provider URL", local ? executable : provider.url, 82, span - 90, false, next -> {
			if (local) executable = next; else provider.url = next;
			provider.changed(true);
		});
		if (!local) {
			field("API key", provider.key, 108, span - 142, true, next -> { provider.key = next; provider.changed(true); });
			button(reveal ? "Hide" : "Show", left + span - 48, 108, 48, () -> { reveal = !reveal; clearAndInit(); });
		}
		modelField = field("Model", local ? codexModel : provider.model, 134, span - (local ? 90 : 116), false, next -> {
			if (local) codexModel = next; else provider.model = next;
			provider.changed(false);
		});
		modelField.setPlaceholder(Text.literal(local ? "Default, or model ID" : "Choose or type model ID"));
		if (!local) modelsButton = button("▼", left + span - 22, 134, 22, () -> {
			dropdown = !dropdown; modelOffset = 0; modelQuery = ""; rebuildChoices();
			if (dropdown) setFocused(modelField);
		});
		checkButton = button("Test connection", left, 163, 112, () -> check(provider, local));
		checkButton.setTooltip(Tooltip.of(Text.literal("Sends a small test request. Provider usage charges may apply.")));
	}
	private TextFieldWidget field(String label, String initial, int y, int fieldWidth, boolean secret, Consumer<String> changed) {
		Text name = Text.literal(label);
		var field = new TextFieldWidget(textRenderer, left + 90, y, fieldWidth, 20, name) {
			@Override protected MutableText getNarrationMessage() {
				return secret && !reveal ? name.copy().append(" — hidden") : super.getNarrationMessage();
			}
		};
		field.setMaxLength(8192);
		field.setText(initial);
		if (secret) field.setRenderTextProvider((text, offset) -> OrderedText.styledForwardsVisitedString(reveal ? text : "•".repeat(text.length()), Style.EMPTY));
		field.setChangedListener(next -> {
			changed.accept(next);
			if (dropdown && label.equals("Model")) { modelQuery = next; modelOffset = 0; }
			else dropdown = false;
			rebuildChoices(); problem = ""; updateButtons();
		});
		return addDrawableChild(field);
	}
	private ButtonWidget button(String label, int x, int y, int size, Runnable action) {
		return addDrawableChild(ButtonWidget.builder(Text.literal(label), ignored -> action.run()).dimensions(x, y, size, 20).build());
	}
	private Provider current() { return tab == Tab.VISION ? vision : planner; }
	private void updateButtons() {
		if (finishButton != null) finishButton.active = ready();
		if (checkButton != null) {
			checkButton.active = !(current().check instanceof Running);
			checkButton.setTooltip(Tooltip.of(Text.literal(current().check instanceof Checked checked ? checked.result().message()
				: "Sends a small test request. Provider usage charges may apply.")));
		}
		if (modelsButton != null) {
			modelsButton.active = !current().models.isEmpty();
			modelsButton.setTooltip(Tooltip.of(Text.literal(current().hint + " Type to filter; scroll to browse.")));
		}
	}
	private boolean ready() {
		return draft != null && planner.ready() && (nativeVision || !visionEnabled || vision.ready()) && compatibility.stream().noneMatch(Compatibility::blocking);
	}
	boolean checksFinished() { return current().check instanceof Checked; }
	@Override public void tick() {
		if (draft == null) return;
		if (!codex) discover(planner);
		if (visionEnabled && !nativeVision) discover(vision);
		updateButtons();
	}
	private void discover(Provider provider) {
		if (provider.fetchAt == 0 || System.nanoTime() < provider.fetchAt || provider.url.isBlank() || provider.key.isBlank()) return;
		provider.fetchAt = 0;
		String url = provider.url;
		String key = provider.key;
		provider.hint = "Loading models…";
		provider.fetch = Thread.ofVirtual().unstarted(() -> {
			var result = ProviderModels.api(url, key);
			Thread completed = Thread.currentThread();
			client.execute(() -> {
				if (client.currentScreen != this || provider.fetch != completed) return;
				provider.fetch = null;
				provider.models = result.models();
				provider.hint = result.hint();
				updateButtons();
			});
		});
		provider.fetch.start();
	}
	private void check(Provider provider, boolean local) {
		if (provider.check instanceof Running) return;
		String url = provider.url, key = provider.key, model = provider.model;
		boolean image = tab == Tab.VISION || nativeVision;
		var config = new AgentConfig.CodexAppServerConfig(executable, codexModel, value("codexAppServer.reasoningEffort", ""), value("codexAppServer.serviceTier", ""),
			value("codexAppServer.startupTimeoutMillis", 10_000), value("codexAppServer.turnTimeoutMillis", 120_000));
		int timeout = value(tab == Tab.VISION ? "visionRequestTimeoutMillis" : "requestTimeoutMillis", 30_000);
		Thread worker = Thread.ofVirtual().unstarted(() -> {
			var result = local ? ConnectionCheck.codex(config, image) : ConnectionCheck.api(url, key, model, image, timeout);
			Thread completed = Thread.currentThread();
			client.execute(() -> {
				if (client.currentScreen == this && provider.check instanceof Running running && running.worker() == completed) {
					provider.check = new Checked(result); updateButtons();
				}
			});
		});
		provider.check = new Running(worker); updateButtons(); worker.start();
	}
	private void rebuildChoices() {
		choices.forEach(this::remove);
		choices.clear();
		if (!dropdown) return;
		var models = current().models.stream().filter(model -> model.toLowerCase(java.util.Locale.ROOT)
			.contains(modelQuery.toLowerCase(java.util.Locale.ROOT))).toList();
		int rows = Math.max(1, Math.min(5, (height - 194) / 20));
		modelOffset = Math.clamp(modelOffset, 0, Math.max(0, models.size() - rows));
		for (int i = modelOffset; i < Math.min(models.size(), modelOffset + rows); i++) {
			String model = models.get(i);
			var choice = button(textRenderer.trimToWidth(model, span - 104), left + 90, 157 + (i - modelOffset) * 20, span - 90, () -> {
				modelField.setText(model); dropdown = false; rebuildChoices(); setFocused(modelField);
			});
			choice.setTooltip(Tooltip.of(Text.literal(model)));
			choices.add(choice);
		}
	}
	@Override public boolean mouseClicked(double x, double y, int button) {
		if (dropdown) {
			for (var choice : List.copyOf(choices)) if (choice.isMouseOver(x, y)) return choice.mouseClicked(x, y, button);
			if (modelField.isMouseOver(x, y)) return super.mouseClicked(x, y, button);
			dropdown = false; rebuildChoices();
			return true;
		}
		return super.mouseClicked(x, y, button);
	}
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		if (dropdown) { modelOffset -= (int) vertical; rebuildChoices(); return true; }
		return super.mouseScrolled(x, y, horizontal, vertical);
	}
	@Override public boolean keyPressed(int key, int scan, int modifiers) {
		if (dropdown) {
			if (key == 256) { dropdown = false; rebuildChoices(); return true; }
			if (key == 264 || key == 265) { modelOffset += key == 264 ? 1 : -1; rebuildChoices(); return true; }
			if (key == 257 && !choices.isEmpty()) { choices.getFirst().onPress(); return true; }
		}
		return super.keyPressed(key, scan, modifiers);
	}
	private void finish() {
		if (!ready()) return;
		try {
			save("plannerBackend", codex ? "codex-app-server" : "openai-compatible");
			save("providerBaseUrl", planner.url); save("apiKey", planner.key); save("model", planner.model);
			save("codexAppServer.executable", executable); save("codexAppServer.model", codexModel);
			save("plannerNativeVisionEnabled", nativeVision);
			save("visionProviderBaseUrl", vision.url); save("visionApiKey", visionEnabled ? vision.key : ""); save("visionModel", visionEnabled ? vision.model : "");
			OnboardingState.complete(draft);
			draft.saveAndReload(() -> AiricraftClient.runtimeController().reload()); close();
		} catch (IOException | RuntimeException exception) { problem = "Could not save. Settings may have changed; reopen setup and retry."; }
	}
	@Override public void close() { client.setScreen(parent instanceof AiricraftSettingsScreen screen ? screen.reopen() : parent); }
	@Override public void removed() {
		if (planner != null) planner.cancel();
		if (vision != null) vision.cancel();
		reveal = false;
	}
	@Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fillGradient(0, 0, width, height, 0xFF17212E, 0xFF0B1019);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFFFF);
		if (draft != null && tab == Tab.MODS) {
			int y = 66;
			for (var item : compatibility) {
				context.drawTextWithShadow(textRenderer, item.name(), left, y, 0xFF8CD8FF);
				y = paragraph(context, item.detail(), left, y + 16, span, item.blocking() ? 0xFFFFB08A : 0xFFD6DFEA) + 18;
			}
		} else if (draft != null && tab == Tab.VISION && nativeVision) {
			paragraph(context, "Your planner handles images. No separate endpoint needed.", left, 76, span, 0xFF99E7B1);
			paragraph(context, "Test image input from the Planner tab.", left, 110, span, 0xFFBAC7D6);
		} else if (draft != null && (tab == Tab.PLANNER || visionEnabled)) {
			boolean local = tab == Tab.PLANNER && codex;
			context.drawTextWithShadow(textRenderer, local ? "Executable" : "Provider URL", left, 88, 0xFFBAC7D6);
			if (!local) context.drawTextWithShadow(textRenderer, "API key", left, 114, 0xFFBAC7D6);
			else context.drawTextWithShadow(textRenderer, "Uses your local Codex sign-in.", left + 90, 114, 0xFFBAC7D6);
			context.drawTextWithShadow(textRenderer, "Model", left, 140, 0xFFBAC7D6);
			var state = current().check;
			String status = state instanceof Running ? "Testing…" : state instanceof Checked checked ? shortResult(checked.result()) : "Not tested";
			paragraph(context, status, left + 120, 164, span - 120, current().ready() ? 0xFF99E7B1 : 0xFFBAC7D6);
			if (!local) context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(current().hint, span), left, 193, 0xFFBAC7D6);
		} else if (draft != null) paragraph(context, "Optional. Add a separate model for image understanding.", left, 94, span, 0xFFBAC7D6);
		if (!problem.isEmpty()) paragraph(context, problem, left, 193, span, 0xFFFFB08A);
		super.render(context, mouseX, mouseY, delta);
		// Overlay must cover both the form and its text. Rendered last, with exclusive pointer handling.
		if (dropdown && !choices.isEmpty()) {
			context.fill(left + 88, 155, left + span + 2, 159 + choices.size() * 20, 0xFF101923);
			for (var choice : choices) choice.render(context, mouseX, mouseY, delta);
		}
	}
	private static String shortResult(ConnectionCheck.Result result) {
		return switch (result) {
			case READY -> "Connected";
			case NOT_CONFIGURED -> "Complete the fields above";
			case INVALID_URL, ENDPOINT_FAILED -> "Check provider URL";
			case AUTH_FAILED -> "Check API key";
			case MODEL_FAILED -> "Check model access";
			case RATE_LIMITED -> "Usage limit reached";
			case TIMED_OUT -> "Timed out · retry";
			case CODEX_START_FAILED -> "Codex not found";
			case CODEX_AUTH_FAILED -> "Sign in to Codex first";
			case CANCELLED -> "Cancelled";
			default -> "Test failed · hover for details";
		};
	}
	private int paragraph(DrawContext context, String text, int x, int y, int wrap, int color) {
		for (var line : textRenderer.wrapLines(Text.literal(text), wrap)) { context.drawTextWithShadow(textRenderer, line, x, y, color); y += 10; }
		return y;
	}
	private static Compatibility compatibility(String name, String mod, String adapter, boolean initialized) {
		var loader = FabricLoader.getInstance();
		var installed = loader.getModContainer(mod);
		if (installed.isEmpty()) return new Compatibility(name, "Not installed · optional", false);
		String version = installed.get().getMetadata().getVersion().getFriendlyString();
		if (!loader.isModLoaded(adapter)) return new Compatibility(name, version + " · matching Airicraft add-on required", true);
		return new Compatibility(name, version + (initialized
			? " · ready after joining a world"
			: " · add-on loaded; initializes in a world"), false);
	}

	private record Compatibility(String name, String detail, boolean blocking) {}
	private sealed interface CheckState permits Unchecked, Running, Checked {}
	private record Unchecked() implements CheckState {}
	private record Running(Thread worker) implements CheckState {}
	private record Checked(ConnectionCheck.Result result) implements CheckState {}
	private static final class Provider {
		String url, key, model;
		CheckState check = new Unchecked();
		List<String> models = List.of();
		String hint = "Enter credentials to load available models.";
		long fetchAt = System.nanoTime() + 500_000_000;
		Thread fetch;
		Provider(String url, String key, String model) { this.url = url; this.key = key; this.model = model; }
		boolean ready() { return check instanceof Checked checked && checked.result() == ConnectionCheck.Result.READY; }
		void changed(boolean credentials) {
			if (check instanceof Running running) running.worker().interrupt();
			check = new Unchecked();
			if (credentials) {
				if (fetch != null) fetch.interrupt();
				fetch = null; models = List.of(); fetchAt = System.nanoTime() + 500_000_000;
				hint = "Enter credentials to load available models.";
			}
		}
		void cancel() { changed(true); fetchAt = 0; }
	}
}
