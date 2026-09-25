package ai.moeru.airicraft.settings;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.AiricraftConfig;
import ai.moeru.airicraft.agent.AgentConfig;
import me.shedaniel.clothconfig2.api.*;
import me.shedaniel.clothconfig2.gui.ClothConfigScreen;
import me.shedaniel.clothconfig2.gui.entries.BooleanListEntry;
import me.shedaniel.clothconfig2.gui.entries.SelectionListEntry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.NoticeScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AiricraftSettingsScreen extends ClothConfigScreen {
	private static final String MOD = "airicraft.yml";
	private static final String AGENT = "agent.yml";
	private final SettingsDraft draft;
	private final SettingsProfiles profiles;
	private ButtonWidget saveButton;
	private ButtonWidget profilesButton;

	private AiricraftSettingsScreen(Screen parent, Form form) {
		this(parent, form, new SettingsProfiles(form.draft));
	}

	private AiricraftSettingsScreen(Screen parent, Form form, SettingsProfiles profiles) {
		super(parent, text("title", "Airicraft Settings"), form.categories, form.builder.getDefaultBackgroundTexture());
		draft = form.draft;
		this.profiles = profiles;
		setAlwaysShowTabs(true);
		setConfirmSave(false);
	}

	public static Screen create(Screen parent) {
		try {
			return new AiricraftSettingsScreen(parent, new Form(SettingsDraft.open(
				FabricLoader.getInstance().getConfigDir().resolve("airicraft"))));
		} catch (IOException | RuntimeException exception) {
			return new NoticeScreen(() -> MinecraftClient.getInstance().setScreen(parent),
				text("open_failed", "Could not open Airicraft settings"),
				text("open_failed.detail", "The settings files could not be read. Check their format and file permissions; no changes were made."));
		}
	}

	@Override
	protected void init() {
		super.init();
		var reportButton = addDrawableChild(ButtonWidget.builder(text("report", "Report a problem"), ignored -> saveReport())
			.dimensions(4, 4, 104, 20).build());
		reportButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(text("report.detail",
			"Mark this moment, choose attachments and preview before saving locally.")));
		profilesButton = addDrawableChild(ButtonWidget.builder(text("profiles", "Profiles…"), ignored -> {
			if (hasErrors()) return;
			super.saveAll(false);
			profiles.stage();
			client.setScreen(new SettingsProfilesScreen(profiles, () ->
				client.setScreen(new AiricraftSettingsScreen(parent, new Form(draft), profiles))));
		}).dimensions(width - 104, 4, 100, 20).build());
		profilesButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Active profile: " + profiles.active())));
		// Cloth recreates the bottom-right save button with a dynamic label on each init.
		for (var child : java.util.List.copyOf(children())) {
			if (child instanceof ButtonWidget button && button.getY() == height - 26 && button.getX() > width / 2) {
				remove(button);
				saveButton = addDrawableChild(ButtonWidget.builder(text("save", "Save & reload agent"), ignored -> saveAll(true))
					.dimensions(button.getX(), button.getY(), button.getWidth(), button.getHeight()).build());
			}
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		boolean valid = !hasErrors();
		saveButton.active = isEdited() && valid;
		profilesButton.active = valid;
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public boolean isEdited() { return draft.isDirty() || super.isEdited(); }

	private boolean hasErrors() {
		return getCategorizedEntries().values().stream().flatMap(java.util.Collection::stream)
			.anyMatch(entry -> entry.getConfigError().isPresent());
	}

	@Override
	public void saveAll(boolean closeAfterSave) {
		if (hasErrors()) return;
		try {
			// Cloth's callbacks update only the draft; no persistence happens while editing.
			super.saveAll(false);
			profiles.stage();
			boolean saved = draft.saveAndReload(() -> AiricraftClient.runtimeController().reload());
			if (saved) client.inGameHud.setOverlayMessage(text("saved", "Airicraft settings saved. Agent reloaded."), false);
			client.setScreen(parent);
		} catch (IOException | RuntimeException exception) {
			// Parser exceptions can contain API keys. Never display or log their raw text.
			client.setScreen(new NoticeScreen(() -> client.setScreen(this),
				text("save_failed", "Could not apply Airicraft settings"),
				text("save_failed.detail", "Your edits are still in this menu. Check the values and file permissions. If settings were edited elsewhere, close and reopen this menu."),
				Text.translatable("gui.back"), true));
		}
	}

	private void saveReport() {
		client.setScreen(new DiagnosticReportScreen(this, AiricraftClient.runtimeController().markDiagnosticReport()));
	}

	private static Text text(String key, String fallback) {
		return Text.translatableWithFallback("airicraft.settings." + key, fallback);
	}

	private static final class Form {
		private final SettingsDraft draft;
		private final ConfigBuilder builder = ConfigBuilder.create();
		private final ConfigEntryBuilder entries = builder.entryBuilder();
		private final Map<String, ConfigCategory> categories = new LinkedHashMap<>();
		private final BooleanListEntry reveal = entries.startBooleanToggle(text("reveal", "Show API keys"), false).build();

		private Form(SettingsDraft draft) {
			this.draft = draft;
			AgentConfig agent = AgentConfig.defaults();
			var llm = agent.llm();
			AiricraftConfig mod = AiricraftConfig.defaults();
			ConfigCategory connection = category("connection", "Connection");
			SelectionListEntry<String> backend = entries.startSelector(text("plannerBackend", "Planner backend"),
				new String[]{"openai-compatible", "codex-app-server"}, draft.get(AGENT, "plannerBackend", llm.plannerBackend().wireValue()))
				.setNameProvider(value -> value.equals("codex-app-server") ? text("backend.codex", "Local Codex") : text("backend.api", "OpenAI-compatible API"))
				.setDefaultValue(llm.plannerBackend().wireValue()).setSaveConsumer(value -> draft.set(AGENT, "plannerBackend", value)).build();
			connection.addEntry(backend);
			connection.addEntry(reveal);
			var api = entries.startSubCategory(text("api", "API connection")).setExpanded(true)
				.setDisplayRequirement(() -> backend.getValue().equals("openai-compatible"));
			api.add(string(AGENT, "providerBaseUrl", "Provider URL", llm.providerBaseUrl(), "Base URL of your OpenAI-compatible service, usually ending in /v1."));
			api.add(secret("apiKey", "API key"));
			api.add(string(AGENT, "model", "Model", llm.model(), "Model name supplied by your provider."));
			api.add(string(AGENT, "plannerReasoningEffort", "Reasoning effort", llm.reasoningEffort(), "Leave empty for the provider default. Supported values depend on the model."));
			connection.addEntry(api.build());
			var codex = entries.startSubCategory(text("codex", "Local Codex")).setExpanded(true)
				.setDisplayRequirement(() -> backend.getValue().equals("codex-app-server"));
			codex.add(string(AGENT, "codexAppServer.executable", "Codex executable", llm.codexAppServer().executable(), "Uses your existing local Codex login. Usually just codex."));
			codex.add(string(AGENT, "codexAppServer.model", "Codex model", llm.codexAppServer().model(), "Leave empty to use your local Codex default."));
			codex.add(string(AGENT, "codexAppServer.reasoningEffort", "Codex reasoning effort", llm.codexAppServer().reasoningEffort(), "Leave empty for the local default. Accepted values depend on the model."));
			codex.add(string(AGENT, "codexAppServer.serviceTier", "Codex service tier", llm.codexAppServer().serviceTier(), "Leave empty for the local default. Fast mode may consume more credits."));
			connection.addEntry(codex.build());

			ConfigCategory behaviour = category("behaviour", "Behaviour");
			behaviour.addEntry(number(MOD, "socialChatMaxDistanceBlocks", "Chat range (blocks)", mod.socialChatMaxDistanceBlocks(), -1, Integer.MAX_VALUE, "Use -1 to hear chat at any distance."));
			behaviour.addEntry(bool(MOD, "readSystemChatMessages", "Read system messages", mod.readSystemChatMessages(), "Let the agent read death and advancement messages."));
			behaviour.addEntry(bool(MOD, "enableProactiveSocialMode", "Proactive conversation", mod.enableProactiveSocialMode(), "Let the agent respond to nearby chat without an @agent mention."));
			behaviour.addEntry(bool(MOD, "suppressAutoPauseOnFocusLost", "Keep running when unfocused", mod.suppressAutoPauseOnFocusLost(), "Do not open the pause menu when you switch to another window."));
			behaviour.addEntry(number(AGENT, "idleInitialDelaySeconds", "First idle thought (seconds)", agent.idle().initialDelaySeconds(), 0, Integer.MAX_VALUE, "Wait this long after becoming idle. Set either idle timer to 0 to disable idle thoughts."));
			behaviour.addEntry(number(AGENT, "idleCooldownSeconds", "Idle thought interval (seconds)", agent.idle().cooldownSeconds(), 0, Integer.MAX_VALUE, "Minimum time between automatic idle thoughts. 0 disables them."));
			behaviour.addEntry(bool(AGENT, "reflex.enabled", "Emergency reflexes", agent.reflex().enabled(), "Allow automatic reactions to immediate danger."));
			behaviour.addEntry(number(MOD, "cameraLerpDefaultTicks", "Camera smoothing (ticks)", mod.cameraLerpDefaultTicks(), 0, Integer.MAX_VALUE, "Approximate turn settling time. 0 uses the default spring; 20 ticks is about one second."));
			behaviour.addEntry(number(MOD, "blockInteractionDelayTicks", "Interaction delay (ticks)", mod.blockInteractionDelayTicks(), 0, Integer.MAX_VALUE, "Delay between batched block interactions. 0 disables pacing."));

			ConfigCategory vision = category("vision", "Vision");
			vision.addEntry(bool(AGENT, "plannerNativeVisionEnabled", "Send images to the planner", llm.plannerNativeVisionEnabled(), "Use a vision-capable planner model. Otherwise a separate vision model describes screenshots."));
			vision.addEntry(string(AGENT, "visionProviderBaseUrl", "Vision provider URL", llm.visionProviderBaseUrl(), "OpenAI-compatible service for the separate vision model."));
			vision.addEntry(secret("visionApiKey", "Vision API key"));
			vision.addEntry(string(AGENT, "visionModel", "Vision model", llm.visionModel(), "Name of the model used to describe screenshots."));
			vision.addEntry(entries.startSelector(text("visionImageDetail", "Image detail"), new String[]{"low", "high", "auto"}, draft.get(AGENT, "visionImageDetail", llm.visionImageDetail()))
				.setDefaultValue(llm.visionImageDetail()).setSaveConsumer(value -> draft.set(AGENT, "visionImageDetail", value)).build());
			vision.addEntry(number(AGENT, "plannerMaxImages", "Images retained in context", llm.plannerMaxImages(), 1, Integer.MAX_VALUE, "At the limit, new images are described separately until context is compacted."));

			ConfigCategory advanced = category("advanced", "Advanced");
			advanced.addEntry(number(AGENT, "requestTimeoutMillis", "Planner timeout (ms)", llm.requestTimeoutMillis(), 1, Integer.MAX_VALUE, "Maximum wait for a planner request."));
			advanced.addEntry(number(AGENT, "visionRequestTimeoutMillis", "Vision timeout (ms)", llm.visionRequestTimeoutMillis(), 1, Integer.MAX_VALUE, "Maximum wait for a vision request."));
			advanced.addEntry(number(AGENT, "codexAppServer.startupTimeoutMillis", "Codex startup timeout (ms)", llm.codexAppServer().startupTimeoutMillis(), 1, Integer.MAX_VALUE, "Maximum wait for the local Codex process to start."));
			advanced.addEntry(number(AGENT, "codexAppServer.turnTimeoutMillis", "Codex turn timeout (ms)", llm.codexAppServer().turnTimeoutMillis(), 1, Integer.MAX_VALUE, "Maximum wait for a local Codex turn."));
			advanced.addEntry(number(AGENT, "maxRecentConversationTurns", "Recent conversation turns", llm.maxRecentConversationTurns(), 1, Integer.MAX_VALUE, "Recent dialogue retained in context."));
			advanced.addEntry(number(AGENT, "plannerCompactionTriggerTokens", "Compact context at (tokens)", llm.plannerCompactionTriggerTokens(), 1, Integer.MAX_VALUE, "Trigger context compaction when the input reaches this size."));
			advanced.addEntry(number(AGENT, "plannerPendingSemanticEventCap", "Pending event limit", llm.plannerPendingSemanticEventCap(), 1, Integer.MAX_VALUE, "Flush pending observations when this limit is reached."));
			advanced.addEntry(number(AGENT, "plannerSessionMaxConcurrentAttempts", "Concurrent planner attempts", llm.plannerSessionMaxConcurrentAttempts(), 1, Integer.MAX_VALUE, "Limit superseded requests still in flight."));
			advanced.addEntry(number(AGENT, "plannerSessionCoalesceStepMillis", "Event batching step (ms)", llm.plannerSessionCoalesceStepMillis(), 0, Integer.MAX_VALUE, "Additional delay for each queued event trigger."));
			advanced.addEntry(number(AGENT, "plannerSessionCoalesceMinMillis", "Minimum batching delay (ms)", llm.plannerSessionCoalesceMinMillis(), 0, Integer.MAX_VALUE, "Minimum delay when merging multiple triggers."));
			advanced.addEntry(number(AGENT, "plannerSessionCoalesceMaxMillis", "Maximum batching delay (ms)", llm.plannerSessionCoalesceMaxMillis(), 0, Integer.MAX_VALUE, "Upper bound on batching delay; must be at least the minimum."));
			advanced.addEntry(number(AGENT, "reflex.lowAirTicks", "Low air threshold (ticks)", agent.reflex().lowAirTicks(), 0, Integer.MAX_VALUE, "Air remaining before an emergency surfacing response."));
			advanced.addEntry(number(AGENT, "reflex.threatCooldownTicks", "Threat cooldown (ticks)", agent.reflex().threatCooldownTicks(), 0, Integer.MAX_VALUE, "Time before considering an immediate threat cleared."));
			var thinking = entries.startSubCategory(text("thinking", "Thinking planner")).setDisplayRequirement(() -> backend.getValue().equals("openai-compatible"));
			thinking.add(bool(AGENT, "thinkingPlanner.enabled", "Enable thinking planner", llm.thinkingPlanner().enabled(), "Use an additional planning session for delegated work."));
			thinking.add(string(AGENT, "thinkingPlanner.model", "Thinking model", llm.thinkingPlanner().model(), "Leave empty to use the controller model."));
			thinking.add(string(AGENT, "thinkingPlanner.reasoningEffort", "Thinking effort", llm.thinkingPlanner().reasoningEffort(), "Reasoning effort for delegated planning."));
			advanced.addEntry(thinking.build());
			boolean summaries = draft.get(AGENT, "plannerSummarizeToolResults", llm.plannerSummarizeToolResults() && !backend.getValue().equals("codex-app-server"));
			advanced.addEntry(entries.startBooleanToggle(text("plannerSummarizeToolResults", "Summarize inspection results"), summaries)
				.setDefaultValue(llm.plannerSummarizeToolResults()).setRequirement(() -> backend.getValue().equals("openai-compatible"))
				.setTooltip(text("plannerSummarizeToolResults.help", "Experimental. Only available with the API backend; disabled when saving Local Codex settings."))
				.setSaveConsumer(value -> draft.set(AGENT, "plannerSummarizeToolResults", value && backend.getValue().equals("openai-compatible"))).build());
			var dashboard = entries.startSubCategory(text("dashboard", "Debug dashboard"));
			dashboard.add(bool(MOD, "debugDashboard.enabled", "Enable dashboard", mod.debugDashboard().enabled(), "Provide a read-only dashboard on your local network."));
			dashboard.add(number(MOD, "debugDashboard.basePort", "Dashboard port", mod.debugDashboard().basePort(), 1, 65535, "First network port to try."));
			dashboard.add(number(MOD, "debugDashboard.portScanLimit", "Ports to try", mod.debugDashboard().portScanLimit(), 1, 1000, "Try subsequent ports if the first is already in use."));
			dashboard.add(number(MOD, "debugDashboard.historyMegabytes", "History memory (MiB)", (int) (mod.debugDashboard().historyByteBudget() / (1024 * 1024)), 1, Integer.MAX_VALUE, "Memory budget for the rolling observation history."));
			dashboard.add(bool(MOD, "debugDashboard.visualCaptureEnabled", "Capture dashboard images", mod.debugDashboard().visualCaptureEnabled(), "Include occasional game screenshots in the dashboard."));
			dashboard.add(number(MOD, "debugDashboard.visualCaptureIntervalTicks", "Image interval (ticks)", mod.debugDashboard().visualCaptureIntervalTicks(), 1, Integer.MAX_VALUE, "20 ticks is about one second."));
			advanced.addEntry(dashboard.build());
			var observability = entries.startSubCategory(text("observability", "Tracing"));
			observability.add(bool(AGENT, "observability.enabled", "Enable tracing", agent.observability().enabled(), "Export model-call traces to an OpenTelemetry collector."));
			observability.add(string(AGENT, "observability.otlpEndpoint", "Trace endpoint", agent.observability().otlpEndpoint(), "OTLP HTTP traces endpoint."));
			observability.add(bool(AGENT, "observability.captureInputs", "Trace model inputs", agent.observability().captureInputs(), "Include summarized model inputs in exported traces."));
			observability.add(bool(AGENT, "observability.captureOutputs", "Trace model outputs", agent.observability().captureOutputs(), "Include summarized model outputs in exported traces."));
			observability.add(bool(AGENT, "observability.captureImages", "Trace images", agent.observability().captureImages(), "Include screenshot payloads in exported traces."));
			advanced.addEntry(observability.build());
		}

		private ConfigCategory category(String key, String label) {
			ConfigCategory category = builder.getOrCreateCategory(text("category." + key, label));
			category.addEntry(entries.startTextDescription(text("reload_notice", "Saving reloads the agent and ends its current work. Cancel leaves settings unchanged.")).build());
			categories.put(category.getCategoryKey().getString(), category);
			return category;
		}

		private AbstractConfigListEntry<?> string(String file, String key, String label, String fallback, String help) {
			return entries.startStrField(text(key, label), draft.get(file, key, fallback)).setDefaultValue(fallback)
				.setTooltip(text(key + ".help", help)).setSaveConsumer(value -> draft.set(file, key, value)).build();
		}

		private AbstractConfigListEntry<?> secret(String key, String label) {
			return new SecretEntry(text(key, label), draft.get(AGENT, key, ""), reveal::getValue, value -> draft.set(AGENT, key, value));
		}

		private AbstractConfigListEntry<?> bool(String file, String key, String label, boolean fallback, String help) {
			return entries.startBooleanToggle(text(key, label), draft.get(file, key, fallback)).setDefaultValue(fallback)
				.setTooltip(text(key + ".help", help)).setSaveConsumer(value -> draft.set(file, key, value)).build();
		}

		private AbstractConfigListEntry<?> number(String file, String key, String label, int fallback, int minimum, int maximum, String help) {
			Number value = draft.get(file, key, (Number) fallback);
			return entries.startIntField(text(key, label), value.intValue()).setDefaultValue(fallback).setMin(minimum).setMax(maximum)
				.setTooltip(text(key + ".help", help)).setSaveConsumer(next -> draft.set(file, key, next)).build();
		}
	}
}
