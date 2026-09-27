package ai.moeru.airicraft.settings;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationBridge;
import ai.moeru.airicraft.agent.integration.rei.ReiRecipeSearchBridge;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class OnboardingScreen extends Screen {
	private final Screen parent;
	private Settings settings;
	private CheckState checks = new Unchecked();
	private String problem = "";
	private List<Compatibility> compatibility = List.of();
	private ButtonWidget configureButton;
	private ButtonWidget checkButton;
	private ButtonWidget finishButton;
	private int scroll;
	private int contentHeight;

	private OnboardingScreen(Screen parent) {
		super(Text.literal("Welcome to Airicraft"));
		this.parent = parent;
	}
	public static Screen create(Screen parent) { return new OnboardingScreen(parent); }
	private static Path directory() { return FabricLoader.getInstance().getConfigDir().resolve("airicraft"); }
	public static boolean required() {
		try { return OnboardingState.required(SettingsDraft.open(directory())); }
		catch (IOException | RuntimeException exception) { return true; }
	}

	@Override protected void init() {
		refresh();
		int left = Math.max(10, (width - 440) / 2);
		int half = (Math.min(440, width - 20) - 8) / 2;
		configureButton = addDrawableChild(ButtonWidget.builder(Text.literal("Configure providers"), ignored ->
			client.setScreen(AiricraftSettingsScreen.create(this))).dimensions(left, 42, half, 20).build());
		checkButton = addDrawableChild(ButtonWidget.builder(Text.literal("Check connections"), ignored -> check())
			.dimensions(left + half + 8, 42, half, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Set up later"), ignored -> close())
			.dimensions(left, height - 28, half, 20).build());
		finishButton = addDrawableChild(ButtonWidget.builder(Text.literal("Finish setup"), ignored -> finish())
			.dimensions(left + half + 8, height - 28, half, 20).build());
		updateButtons();
	}

	private void refresh() {
		try {
			var next = Settings.read(SettingsDraft.open(directory()));
			if (!next.equals(settings)) { cancelCheck(); settings = next; }
			problem = "";
		} catch (IOException | RuntimeException exception) {
			cancelCheck();
			settings = null;
			problem = "Settings could not be read. Check the YAML format and file permissions, then reopen setup.";
		}
		compatibility = List.of(
			compatibility("JourneyMap", "journeymap", "airicraft-journeymap-compat",
				MapIntegrationBridge.registry().provider("journeymap").isPresent(), "Maps and remembered places"),
			compatibility("REI", "roughlyenoughitems", "airicraft-rei-compat",
				ReiRecipeSearchBridge.backend().available(), "Recipe search"));
	}

	private static Compatibility compatibility(String name, String mod, String adapter, boolean initialized, String feature) {
		var loader = FabricLoader.getInstance();
		var installed = loader.getModContainer(mod);
		if (installed.isEmpty()) return new Compatibility(name, feature + ": optional mod not installed.", false);
		String version = installed.get().getMetadata().getVersion().getFriendlyString();
		if (!loader.isModLoaded(adapter)) return new Compatibility(name, version + " installed; Airicraft compatibility add-on missing. Install the matching add-on and restart.", true);
		return new Compatibility(name, version + (initialized
			? " — integration initialized. World features become available after joining."
			: " — add-on loaded; waiting for integration initialization. Recheck after loading a world."), false);
	}

	private void check() {
		refresh();
		if (settings == null || checks instanceof Running) return;
		Settings input = settings;
		Thread worker = Thread.ofVirtual().unstarted(() -> {
			ConnectionCheck.Result planner = input.codex()
				? ConnectionCheck.codex(input.codexConfig(), input.nativeVision())
				: ConnectionCheck.api(input.url(), input.key(), input.model(), input.nativeVision(), input.timeout());
			ConnectionCheck.Result vision = input.hasVision()
				? ConnectionCheck.api(input.visionUrl(), input.visionKey(), input.visionModel(), true, input.visionTimeout()) : null;
			var result = new Report(planner, vision);
			Thread completed = Thread.currentThread();
			client.execute(() -> {
				if (client.currentScreen == this && checks instanceof Running running && running.worker() == completed) {
					checks = new Checked(result);
					updateButtons();
				}
			});
		});
		checks = new Running(worker);
		updateButtons();
		worker.start();
	}

	boolean checksFinished() { return checks instanceof Checked; }
	private boolean ready() {
		return settings != null && checks instanceof Checked checked && checked.report().ready()
			&& compatibility.stream().noneMatch(Compatibility::blocking);
	}
	private void updateButtons() {
		configureButton.active = !(checks instanceof Running);
		checkButton.active = settings != null && !(checks instanceof Running);
		finishButton.active = ready();
	}
	private void finish() {
		if (!ready()) return;
		try {
			var draft = SettingsDraft.open(directory());
			if (!Settings.read(draft).equals(settings)) {
				refresh();
				problem = "Settings changed. Check connections again before finishing.";
				updateButtons();
				return;
			}
			OnboardingState.complete(draft);
			draft.saveAndReload(() -> AiricraftClient.runtimeController().reload());
			close();
		} catch (IOException | RuntimeException exception) {
			problem = "Could not save setup. Check file permissions and retry; setup is not complete.";
		}
	}

	@Override public void close() {
		client.setScreen(parent instanceof AiricraftSettingsScreen settingsScreen ? settingsScreen.reopen() : parent);
	}
	@Override public void removed() { cancelCheck(); }
	private void cancelCheck() {
		if (checks instanceof Running running) running.worker().interrupt();
		checks = new Unchecked();
	}

	@Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		scroll = Math.clamp(scroll - (int) (vertical * 24), 0, Math.max(0, contentHeight - (height - 112)));
		return true;
	}
	@Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fillGradient(0, 0, width, height, 0xFF17212E, 0xFF0B1019);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal("Connect your companion, then start playing."), width / 2, 27, 0xFFBAC7D6);
		int left = Math.max(10, (width - 440) / 2);
		int wrap = Math.min(440, width - 20);
		context.enableScissor(left, 70, left + wrap, height - 38);
		int y = 76 - scroll;
		if (!problem.isEmpty()) y = paragraph(context, problem, left, y, wrap, 0xFFFFB08A) + 8;
		y = paragraph(context, "1. Choose a provider", left, y, wrap, 0xFF8CD8FF);
		y = paragraph(context, "OpenAI-compatible API uses your provider URL, API key and model. Local Codex uses an installed Codex and its existing sign-in. Choose or manage profiles in Configure providers.", left, y + 3, wrap, 0xFFD6DFEA) + 8;
		y = paragraph(context, "2. Verify connections", left, y, wrap, 0xFF8CD8FF);
		y = paragraph(context, "Checks send a small test request and may use provider credits. Vision sends a built-in test image, never a game screenshot.", left, y + 3, wrap, 0xFFD6DFEA) + 5;
		String planner = checks instanceof Checked checked ? checked.report().planner().message()
			: checks instanceof Running ? "Checking connections…" : "Not checked yet.";
		y = paragraph(context, (settings != null && settings.codex() ? "Local Codex: " : "Planner: ") + planner, left, y, wrap, 0xFFFFFFFF) + 5;
		String vision = settings == null || !settings.hasVision() ? "Optional separate vision: not configured. You can add it in the Vision tab."
			: checks instanceof Checked checked ? "Separate vision: " + checked.report().vision().message() : "Separate vision: " + (checks instanceof Running ? "checking…" : "not checked yet.");
		y = paragraph(context, vision, left, y, wrap, 0xFFD6DFEA) + 8;
		y = paragraph(context, "3. Mod compatibility", left, y, wrap, 0xFF8CD8FF);
		y = paragraph(context, "Minecraft " + FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString()
			+ " — Fabric loaded Airicraft's declared dependencies.", left, y + 3, wrap, 0xFFD6DFEA) + 4;
		for (var item : compatibility) y = paragraph(context, item.name() + ": " + item.detail(), left, y, wrap, item.blocking() ? 0xFFFFB08A : 0xFFD6DFEA) + 4;
		if (ready()) y = paragraph(context, "Ready! Finish setup to continue. Reopen these checks anytime from Airicraft Settings.", left, y + 8, wrap, 0xFF99E7B1);
		contentHeight = y + scroll - 76;
		context.disableScissor();
		if (contentHeight > height - 112) context.drawCenteredTextWithShadow(textRenderer, Text.literal("Scroll for more"), width / 2, height - 38, 0xFFBAC7D6);
		super.render(context, mouseX, mouseY, delta);
	}
	private int paragraph(DrawContext context, String text, int x, int y, int wrap, int color) {
		for (var line : textRenderer.wrapLines(Text.literal(text), wrap)) {
			context.drawTextWithShadow(textRenderer, line, x, y, color);
			y += 12;
		}
		return y;
	}

	private sealed interface CheckState permits Unchecked, Running, Checked {}
	private record Unchecked() implements CheckState {}
	private record Running(Thread worker) implements CheckState {}
	private record Checked(Report report) implements CheckState {}
	private record Report(ConnectionCheck.Result planner, ConnectionCheck.Result vision) {
		boolean ready() { return planner == ConnectionCheck.Result.READY && (vision == null || vision == ConnectionCheck.Result.READY); }
	}
	private record Compatibility(String name, String detail, boolean blocking) {}
	private record Settings(boolean codex, String url, String key, String model, boolean nativeVision, int timeout,
		String visionUrl, String visionKey, String visionModel, int visionTimeout, AgentConfig.CodexAppServerConfig codexConfig) {
		boolean hasVision() { return !visionKey.isBlank() || !visionModel.isBlank(); }
		@Override public String toString() { return "Onboarding settings (credentials hidden)"; }
		static Settings read(SettingsDraft draft) {
			var defaults = AgentConfig.LlmConfig.defaults();
			return new Settings(draft.get("agent.yml", "plannerBackend", "openai-compatible").equals("codex-app-server"),
				draft.get("agent.yml", "providerBaseUrl", defaults.providerBaseUrl()), draft.get("agent.yml", "apiKey", ""), draft.get("agent.yml", "model", ""),
				draft.get("agent.yml", "plannerNativeVisionEnabled", false), draft.get("agent.yml", "requestTimeoutMillis", defaults.requestTimeoutMillis()),
				draft.get("agent.yml", "visionProviderBaseUrl", defaults.visionProviderBaseUrl()), draft.get("agent.yml", "visionApiKey", ""), draft.get("agent.yml", "visionModel", ""),
				draft.get("agent.yml", "visionRequestTimeoutMillis", defaults.visionRequestTimeoutMillis()),
				new AgentConfig.CodexAppServerConfig(draft.get("agent.yml", "codexAppServer.executable", "codex"), draft.get("agent.yml", "codexAppServer.model", ""),
					draft.get("agent.yml", "codexAppServer.reasoningEffort", ""), draft.get("agent.yml", "codexAppServer.serviceTier", ""),
					draft.get("agent.yml", "codexAppServer.startupTimeoutMillis", 10_000), draft.get("agent.yml", "codexAppServer.turnTimeoutMillis", 120_000)));
		}
	}
}
