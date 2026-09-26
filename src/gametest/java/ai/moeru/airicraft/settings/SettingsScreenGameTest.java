package ai.moeru.airicraft.settings;

import ai.moeru.airicraft.AiricraftClient;
import me.shedaniel.clothconfig2.api.AbstractConfigEntry;
import me.shedaniel.clothconfig2.gui.entries.StringListEntry;
import me.shedaniel.clothconfig2.gui.entries.SubCategoryListEntry;
import me.shedaniel.clothconfig2.gui.entries.TextFieldListEntry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.stream.Stream;

/** Run explicitly with -Pairicraft.settingsSmoke=true runClientGameTest. */
public final class SettingsScreenGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try {
			var path = FabricLoader.getInstance().getConfigDir().resolve("airicraft/agent.yml");
			String original = Files.readString(path);
			var runtime = context.computeOnClient(client -> AiricraftClient.runtimeController().agentRuntime());
			context.runOnClient(client -> {
				client.options.getGuiScale().setValue(1);
				client.onResolutionChanged();
			});
			context.setScreen(TitleScreen::new);
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.takeScreenshot("airicraft-settings-connection");
			previewModeControls(context);
			saveReport(context, false);
			inspectScreenshot(context);
			if (!Files.readString(path).equals(original)) throw new AssertionError("Report changed settings");
			context.runOnClient(client -> ((AiricraftSettingsScreen) client.currentScreen).saveAll(true));
			context.waitForScreen(TitleScreen.class);
			context.runOnClient(client -> {
				if (AiricraftClient.runtimeController().agentRuntime() != runtime) throw new AssertionError("Unchanged save reloaded the agent");
			});
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(client -> {
				var screen = (AiricraftSettingsScreen) client.currentScreen;
				var range = (TextFieldListEntry<?>) entry(screen, "Chat range (blocks)");
				range.setValue("-2");
				screen.saveAll(true);
				if (client.currentScreen != screen) throw new AssertionError("Invalid range was accepted");
				if (AiricraftClient.runtimeController().agentRuntime() != runtime) throw new AssertionError("Invalid input reloaded the agent");
				range.setValue("-1");
			});
			context.runOnClient(client -> {
				var screen = (AiricraftSettingsScreen) client.currentScreen;
				field(screen, "API key").setValue("fake-ui-test-key");
			});
			context.takeScreenshot("airicraft-settings-masked-key");
			context.clickScreenButton("gui.cancel");
			context.waitForScreen(TitleScreen.class);
			if (!Files.readString(path).equals(original)) throw new AssertionError("Cancel wrote settings");
			context.runOnClient(client -> {
				if (AiricraftClient.runtimeController().agentRuntime() != runtime) throw new AssertionError("Cancel reloaded the agent");
			});

			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(client -> field((AiricraftSettingsScreen) client.currentScreen, "Model").setValue("settings-smoke-model"));
			context.waitTick();
			context.clickScreenButton("airicraft.settings.save");
			context.waitForScreen(TitleScreen.class);
			if (!Files.readString(path).contains("settings-smoke-model")) throw new AssertionError("Save did not persist the model");
			context.runOnClient(client -> {
				if (AiricraftClient.runtimeController().agentRuntime() == runtime) throw new AssertionError("Save did not reload the agent");
			});
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(client -> {
				if (!field((AiricraftSettingsScreen) client.currentScreen, "Model").getValue().equals("settings-smoke-model")) {
					throw new AssertionError("Reopened screen did not load saved model");
				}
			});
			context.takeScreenshot("airicraft-settings-saved");
			for (int tab = 1; tab < 4; tab++) {
				int selected = tab;
				context.runOnClient(client -> {
					var screen = (AiricraftSettingsScreen) client.currentScreen;
					screen.selectedCategoryIndex = selected;
					client.setScreen(screen);
				});
				context.takeScreenshot("airicraft-settings-tab-" + tab);
			}
			context.clickScreenButton("gui.cancel");
			context.waitForScreen(TitleScreen.class);
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.clickScreenButton("airicraft.settings.profiles");
			context.waitForScreen(SettingsProfilesScreen.class);
			context.runOnClient(client -> client.currentScreen.children().stream()
				.filter(child -> child instanceof net.minecraft.client.gui.widget.TextFieldWidget)
				.map(child -> (net.minecraft.client.gui.widget.TextFieldWidget) child)
				.findFirst().orElseThrow().setText("Second provider"));
			context.clickScreenButton("Duplicate");
			context.takeScreenshot("airicraft-settings-profiles");
			context.clickScreenButton("gui.back");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(client -> field((AiricraftSettingsScreen) client.currentScreen, "Model").setValue("second-provider-model"));
			context.clickScreenButton("airicraft.settings.profiles");
			context.waitForScreen(SettingsProfilesScreen.class);
			context.clickScreenButton("Profile: Second provider");
			context.clickScreenButton("gui.back");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(client -> {
				if (!field((AiricraftSettingsScreen) client.currentScreen, "Model").getValue().equals("settings-smoke-model")) throw new AssertionError("Switch did not restore first profile");
			});
			context.waitTick();
			context.clickScreenButton("airicraft.settings.save");
			context.waitForScreen(TitleScreen.class);
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.clickScreenButton("airicraft.settings.profiles");
			context.waitForScreen(SettingsProfilesScreen.class);
			context.clickScreenButton("Profile: Default");
			context.clickScreenButton("gui.back");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(client -> {
				if (!field((AiricraftSettingsScreen) client.currentScreen, "Model").getValue().equals("second-provider-model")) throw new AssertionError("Saved second profile lost edits");
			});
			String beforeCancel = Files.readString(path);
			context.clickScreenButton("text.cloth-config.cancel_discard");
			context.waitForScreen(TitleScreen.class);
			if (!beforeCancel.equals(Files.readString(path))) throw new AssertionError("Profile switch persisted on Cancel");
			Files.writeString(path, original);
			if (FabricLoader.getInstance().isModLoaded("modmenu")) {
				context.runOnClient(client -> client.setScreen(ModMenuProbe.open(client.currentScreen)));
				context.waitForScreen(AiricraftSettingsScreen.class);
				context.takeScreenshot("airicraft-settings-modmenu");
				context.clickScreenButton("gui.cancel");
				context.waitForScreen(TitleScreen.class);
			}
			try (var world = context.worldBuilder().create()) {
				context.waitFor(client -> client.currentScreen == null, 20);
				context.getInput().pressKey(net.minecraft.client.option.KeyBinding.byId("key.airicraft.settings"));
				context.waitFor(client -> client.currentScreen instanceof AiricraftSettingsScreen, 10);
				context.takeScreenshot("airicraft-settings-in-world");
				saveReport(context, true);
				context.clickScreenButton("gui.cancel");
				context.waitForScreen(null);
				context.runOnClient(client -> client.player.networkHandler.sendChatCommand("airicraft config"));
				context.waitForScreen(AiricraftSettingsScreen.class);
				context.clickScreenButton("gui.cancel");
				context.waitForScreen(null);
				context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
				context.waitForScreen(GameMenuScreen.class);
				context.clickScreenButton("button.airicraft.settings");
				context.waitForScreen(AiricraftSettingsScreen.class);
				context.clickScreenButton("gui.cancel");
				context.waitForScreen(GameMenuScreen.class);
			}
		} catch (Exception exception) { throw new AssertionError(exception); }
	}

	private static void previewModeControls(ClientGameTestContext context) {
		var pending = new java.util.ArrayList<java.util.concurrent.CompletableFuture<ai.moeru.airicraft.dashboard.DiagnosticReport>>();
		var requests = new java.util.ArrayList<ai.moeru.airicraft.dashboard.DiagnosticReport.Request>();
		var draft = ai.moeru.airicraft.dashboard.DiagnosticReport.mark(
			new ai.moeru.airicraft.dashboard.DashboardObservationStore(1024 * 1024), java.util.Map.of(), java.util.List.of());
		context.runOnClient(client -> client.setScreen(new DiagnosticReportScreen(client.currentScreen, request -> {
			requests.add(request);
			var future = new java.util.concurrent.CompletableFuture<ai.moeru.airicraft.dashboard.DiagnosticReport>();
			pending.add(future);
			return future;
		})));
		context.clickScreenButton("Preview attachments");
		context.runOnClient(client -> {
			var developer = reportButton(client.currentScreen, "Developer");
			if (developer.active) throw new AssertionError("Attachment choices remain enabled during preview preparation");
			developer.mouseClicked(developer.getX() + 2, developer.getY() + 2, 0);
			// A resize reconstructs widgets while the preview is still pending.
			client.currentScreen.resize(client, client.currentScreen.width, client.currentScreen.height);
		});
		context.runOnClient(client -> pending.getFirst().complete(draft.prepare(requests.getFirst(), java.util.List.of())));
		context.waitFor(client -> reportButton(client.currentScreen, "Save these attachments").active, 40);
		context.runOnClient(client -> {
			if (reportButton(client.currentScreen, "Minimal").active
				|| !reportButton(client.currentScreen, "Summary").active || !reportButton(client.currentScreen, "Developer").active) {
				throw new AssertionError("Preview completion did not restore attachment choices or changed the selected mode");
			}
		});
		context.clickScreenButton("Developer");
		context.runOnClient(client -> {
			if (reportButton(client.currentScreen, "Save these attachments").active
				|| reportButton(client.currentScreen, "Inspect evidence").active) throw new AssertionError("Mode change retained stale evidence");
		});
		context.clickScreenButton("Preview attachments");
		context.runOnClient(client -> pending.getLast().completeExceptionally(new IllegalStateException("controlled preparation failure")));
		context.waitFor(client -> reportButton(client.currentScreen, "Preview attachments").active, 40);
		context.runOnClient(client -> {
			if (!reportButton(client.currentScreen, "Minimal").active || !reportButton(client.currentScreen, "Summary").active
				|| reportButton(client.currentScreen, "Developer").active || reportButton(client.currentScreen, "Save these attachments").active) {
				throw new AssertionError("Failed preview did not restore attachment choices safely");
			}
			if (requests.getLast().mode() != ai.moeru.airicraft.dashboard.DiagnosticReport.Mode.DEVELOPER) throw new AssertionError("Selected mode was not applied");
		});
		context.clickScreenButton("Summary");
		context.clickScreenButton("Cancel");
		context.waitForScreen(AiricraftSettingsScreen.class);
	}

	private static net.minecraft.client.gui.widget.ButtonWidget reportButton(net.minecraft.client.gui.screen.Screen screen, String label) {
		return screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.widget.ButtonWidget)
			.map(child -> (net.minecraft.client.gui.widget.ButtonWidget) child).filter(button -> button.getMessage().getString().equals(label))
			.findFirst().orElseThrow();
	}

	private static void inspectScreenshot(ClientGameTestContext context) throws Exception {
		var store = new ai.moeru.airicraft.dashboard.DashboardObservationStore(1024 * 1024);
		var pixels = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) pixels.setRGB(x, y, x < 32 ? 0x28a8d8 : 0xe8ac32);
		var png = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(pixels, "png", png);
		store.append("visual_frame", 0, 1, java.util.Map.of("format", "png", "imageBase64", java.util.Base64.getEncoder().encodeToString(png.toByteArray())));
		var report = ai.moeru.airicraft.dashboard.DiagnosticReport.mark(store, java.util.Map.of(), java.util.List.of())
			.prepare(new ai.moeru.airicraft.dashboard.DiagnosticReport.Request(ai.moeru.airicraft.dashboard.DiagnosticReport.Mode.DEVELOPER, "Screenshot inspection fixture"), java.util.List.of());
		context.runOnClient(client -> client.setScreen(new DiagnosticEvidenceScreen(client.currentScreen, report)));
		// summary, manifest, observation, then its decoded screenshot
		for (int i = 0; i < 3; i++) context.clickScreenButton("Next");
		context.waitTicks(3);
		context.takeScreenshot("airicraft-evidence-pixels");
		context.clickScreenButton("Previous");
		context.clickScreenButton("Next");
		context.clickScreenButton("Back to report");
		context.waitForScreen(AiricraftSettingsScreen.class);
	}

	private static void saveReport(ClientGameTestContext context, boolean worldLoaded) throws Exception {
		var reports = FabricLoader.getInstance().getGameDir().resolve("airicraft-reports");
		java.util.Set<java.nio.file.Path> before;
		if (Files.isDirectory(reports)) {
			try (var files = Files.list(reports)) { before = files.collect(java.util.stream.Collectors.toSet()); }
		} else before = java.util.Set.of();
		context.clickScreenButton("airicraft.settings.report");
		context.waitFor(client -> client.currentScreen != null && client.currentScreen.getTitle().getString().equals("Report this moment"), 40);
		context.runOnClient(client -> client.currentScreen.children().stream()
			.filter(child -> child instanceof net.minecraft.client.gui.widget.TextFieldWidget)
			.map(child -> (net.minecraft.client.gui.widget.TextFieldWidget) child).findFirst().orElseThrow().setText("Stopped moving; Authorization: Bearer pasted-secret"));
		if (worldLoaded) context.clickScreenButton("Summary");
		context.clickScreenButton("Preview attachments");
		context.waitFor(client -> client.currentScreen.children().stream().anyMatch(child -> child instanceof net.minecraft.client.gui.widget.ButtonWidget button
			&& button.getMessage().getString().equals("Save these attachments") && button.active), 200);
		context.runOnClient(client -> {
			var field = client.currentScreen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.widget.TextFieldWidget)
				.map(child -> (net.minecraft.client.gui.widget.TextFieldWidget) child).findFirst().orElseThrow();
			field.setText(field.getText() + "; still stuck");
			if (client.currentScreen.children().stream().anyMatch(child -> child instanceof net.minecraft.client.gui.widget.ButtonWidget button
				&& button.getMessage().getString().equals("Save these attachments") && button.active)) throw new AssertionError("Edited report reused stale consent preview");
		});
		context.clickScreenButton("Preview attachments");
		context.waitFor(client -> client.currentScreen.children().stream().anyMatch(child -> child instanceof net.minecraft.client.gui.widget.ButtonWidget button
			&& button.getMessage().getString().equals("Save these attachments") && button.active), 200);
		context.waitTicks(2);
		context.takeScreenshot(worldLoaded ? "airicraft-report-preview-world" : "airicraft-report-preview-minimal");
		if (Files.isDirectory(reports)) {
			try (var files = Files.list(reports)) { if (!files.collect(java.util.stream.Collectors.toSet()).equals(before)) throw new AssertionError("Preview wrote a file before consent"); }
		}
		context.clickScreenButton("Inspect evidence");
		context.waitFor(client -> client.currentScreen != null && client.currentScreen.getTitle().getString().equals("Attached evidence"), 40);
		context.takeScreenshot(worldLoaded ? "airicraft-evidence-world" : "airicraft-evidence-minimal");
		context.clickScreenButton("Next");
		context.takeScreenshot("airicraft-evidence-metadata");
		context.clickScreenButton("Back to report");
		context.clickScreenButton("Save these attachments");
		context.waitForScreen(net.minecraft.client.gui.screen.NoticeScreen.class);
		context.takeScreenshot(worldLoaded ? "airicraft-report-in-world" : "airicraft-report-saved");
		try (var files = Files.list(reports)) {
			var created = files.filter(file -> !before.contains(file)).toList();
			if (created.size() != 1) throw new AssertionError("Expected exactly one completed report");
			String contents;
			try (var zip = new java.util.zip.ZipFile(created.getFirst().toFile())) {
				if (zip.getEntry("summary.txt") == null) throw new AssertionError("Missing human summary");
				contents = new String(zip.getInputStream(zip.getEntry("report.jsonl")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			}
			if (contents.contains("pasted-secret")) throw new AssertionError("Report leaked a pasted authorization credential");
			var manifest = com.google.gson.JsonParser.parseString(contents.lines().findFirst().orElseThrow()).getAsJsonObject();
			if (!contents.contains("airicraft.diagnostic-report") || !contents.contains("integrity")
				|| !manifest.getAsJsonObject("environment").getAsJsonObject("build").get("minecraftVersion").getAsString().equals("1.21.8")) {
				throw new AssertionError("Report lacks required metadata");
			}
			if (manifest.getAsJsonObject("runtimeState").get("available").getAsBoolean() != worldLoaded) {
				throw new AssertionError("Report must include retained runtime evidence when in-world");
			}
		}
		context.clickScreenButton("gui.back");
		context.waitFor(client -> client.currentScreen != null && client.currentScreen.getTitle().getString().equals("Report this moment"), 40);
		context.clickScreenButton("Cancel");
		context.waitForScreen(AiricraftSettingsScreen.class);
	}

	private static StringListEntry field(AiricraftSettingsScreen screen, String label) {
		return (StringListEntry) entry(screen, label);
	}

	private static AbstractConfigEntry<?> entry(AiricraftSettingsScreen screen, String label) {
		return screen.getCategorizedEntries().values().stream().flatMap(java.util.Collection::stream)
			.flatMap(SettingsScreenGameTest::flatten).filter(entry -> entry.getFieldName().getString().equals(label))
			.findFirst().orElseThrow();
	}

	private static Stream<AbstractConfigEntry<?>> flatten(AbstractConfigEntry<?> entry) {
		if (entry instanceof SubCategoryListEntry category) return category.getValue().stream().flatMap(SettingsScreenGameTest::flatten);
		return Stream.of(entry);
	}

	private static final class ModMenuProbe {
		private static net.minecraft.client.gui.screen.Screen open(net.minecraft.client.gui.screen.Screen parent) {
			return com.terraformersmc.modmenu.ModMenu.getConfigScreen("airicraft", parent);
		}
	}
}
