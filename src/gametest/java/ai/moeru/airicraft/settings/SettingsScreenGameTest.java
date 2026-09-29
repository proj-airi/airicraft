package ai.moeru.airicraft.settings;

import ai.moeru.airicraft.AiricraftClient;
import me.shedaniel.clothconfig2.api.AbstractConfigEntry;
import me.shedaniel.clothconfig2.gui.entries.StringListEntry;
import me.shedaniel.clothconfig2.gui.entries.SubCategoryListEntry;
import me.shedaniel.clothconfig2.gui.entries.TextFieldListEntry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.PauseScreen;
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
			var runtime = context.computeOnClient(minecraft -> AiricraftClient.runtimeController().agentRuntime());
			context.runOnClient(minecraft -> {
				minecraft.options.guiScale().set(1);
				minecraft.resizeDisplay();
			});
			context.setScreen(TitleScreen::new);
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.takeScreenshot("airicraft-settings-connection");
			previewModeControls(context);
			saveReport(context, false);
			inspectScreenshot(context);
			if (!Files.readString(path).equals(original)) throw new AssertionError("Report changed settings");
			context.runOnClient(minecraft -> ((AiricraftSettingsScreen) minecraft.screen).saveAll(true));
			context.waitForScreen(TitleScreen.class);
			context.runOnClient(minecraft -> {
				if (AiricraftClient.runtimeController().agentRuntime() != runtime) throw new AssertionError("Unchanged save reloaded the agent");
			});
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(minecraft -> {
				var screen = (AiricraftSettingsScreen) minecraft.screen;
				var range = (TextFieldListEntry<?>) entry(screen, "Chat range (blocks)");
				range.setValue("-2");
				screen.saveAll(true);
				if (minecraft.screen != screen) throw new AssertionError("Invalid range was accepted");
				if (AiricraftClient.runtimeController().agentRuntime() != runtime) throw new AssertionError("Invalid input reloaded the agent");
				range.setValue("-1");
			});
			context.runOnClient(minecraft -> {
				var screen = (AiricraftSettingsScreen) minecraft.screen;
				field(screen, "API key").setValue("fake-ui-test-key");
			});
			context.takeScreenshot("airicraft-settings-masked-key");
			context.clickScreenButton("gui.cancel");
			context.waitForScreen(TitleScreen.class);
			if (!Files.readString(path).equals(original)) throw new AssertionError("Cancel wrote settings");
			context.runOnClient(minecraft -> {
				if (AiricraftClient.runtimeController().agentRuntime() != runtime) throw new AssertionError("Cancel reloaded the agent");
			});

			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(minecraft -> field((AiricraftSettingsScreen) minecraft.screen, "Model").setValue("settings-smoke-model"));
			context.waitTick();
			context.clickScreenButton("airicraft.settings.save");
			context.waitForScreen(TitleScreen.class);
			if (!Files.readString(path).contains("settings-smoke-model")) throw new AssertionError("Save did not persist the model");
			context.runOnClient(minecraft -> {
				if (AiricraftClient.runtimeController().agentRuntime() == runtime) throw new AssertionError("Save did not reload the agent");
			});
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(minecraft -> {
				if (!field((AiricraftSettingsScreen) minecraft.screen, "Model").getValue().equals("settings-smoke-model")) {
					throw new AssertionError("Reopened screen did not load saved model");
				}
			});
			context.takeScreenshot("airicraft-settings-saved");
			for (int tab = 1; tab < 4; tab++) {
				int selected = tab;
				context.runOnClient(minecraft -> {
					var screen = (AiricraftSettingsScreen) minecraft.screen;
					screen.selectedCategoryIndex = selected;
					minecraft.setScreen(screen);
				});
				context.takeScreenshot("airicraft-settings-tab-" + tab);
			}
			context.clickScreenButton("gui.cancel");
			context.waitForScreen(TitleScreen.class);
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.clickScreenButton("airicraft.settings.profiles");
			context.waitForScreen(SettingsProfilesScreen.class);
			context.runOnClient(minecraft -> minecraft.screen.children().stream()
				.filter(child -> child instanceof net.minecraft.client.gui.components.EditBox)
				.map(child -> (net.minecraft.client.gui.components.EditBox) child)
				.findFirst().orElseThrow().setValue("Second provider"));
			context.clickScreenButton("Duplicate");
			context.takeScreenshot("airicraft-settings-profiles");
			context.clickScreenButton("gui.back");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(minecraft -> field((AiricraftSettingsScreen) minecraft.screen, "Model").setValue("second-provider-model"));
			context.clickScreenButton("airicraft.settings.profiles");
			context.waitForScreen(SettingsProfilesScreen.class);
			context.clickScreenButton("Profile: Second provider");
			context.clickScreenButton("gui.back");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(minecraft -> {
				if (!field((AiricraftSettingsScreen) minecraft.screen, "Model").getValue().equals("settings-smoke-model")) throw new AssertionError("Switch did not restore first profile");
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
			context.runOnClient(minecraft -> {
				if (!field((AiricraftSettingsScreen) minecraft.screen, "Model").getValue().equals("second-provider-model")) throw new AssertionError("Saved second profile lost edits");
			});
			String beforeCancel = Files.readString(path);
			context.clickScreenButton("text.cloth-config.cancel_discard");
			context.waitForScreen(TitleScreen.class);
			if (!beforeCancel.equals(Files.readString(path))) throw new AssertionError("Profile switch persisted on Cancel");
			Files.writeString(path, original);
			if (FabricLoader.getInstance().isModLoaded("modmenu")) {
				context.runOnClient(minecraft -> minecraft.setScreen(ModMenuProbe.open(minecraft.screen)));
				context.waitForScreen(AiricraftSettingsScreen.class);
				context.takeScreenshot("airicraft-settings-modmenu");
				context.clickScreenButton("gui.cancel");
				context.waitForScreen(TitleScreen.class);
			}
			try (var world = context.worldBuilder().create()) {
				context.waitFor(minecraft -> minecraft.screen == null, 20);
				context.getInput().pressKey(net.minecraft.client.KeyMapping.get("key.airicraft.settings"));
				context.waitFor(minecraft -> minecraft.screen instanceof AiricraftSettingsScreen, 10);
				context.takeScreenshot("airicraft-settings-in-world");
				saveReport(context, true);
				context.clickScreenButton("gui.cancel");
				context.waitForScreen(null);
				context.runOnClient(minecraft -> minecraft.player.connection.sendCommand("airicraft config"));
				context.waitForScreen(AiricraftSettingsScreen.class);
				context.clickScreenButton("gui.cancel");
				context.waitForScreen(null);
				context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
				context.waitForScreen(PauseScreen.class);
				context.clickScreenButton("button.airicraft.settings");
				context.waitForScreen(AiricraftSettingsScreen.class);
				context.clickScreenButton("gui.cancel");
				context.waitForScreen(PauseScreen.class);
			}
		} catch (Exception exception) { throw new AssertionError(exception); }
	}

	private static void previewModeControls(ClientGameTestContext context) {
		var pending = new java.util.ArrayList<java.util.concurrent.CompletableFuture<ai.moeru.airicraft.dashboard.DiagnosticReport>>();
		var requests = new java.util.ArrayList<ai.moeru.airicraft.dashboard.DiagnosticReport.Request>();
		var draft = ai.moeru.airicraft.dashboard.DiagnosticReport.mark(
			new ai.moeru.airicraft.dashboard.DashboardObservationStore(1024 * 1024), java.util.Map.of(), java.util.List.of());
		context.runOnClient(minecraft -> minecraft.setScreen(new DiagnosticReportScreen(minecraft.screen, request -> {
			requests.add(request);
			var future = new java.util.concurrent.CompletableFuture<ai.moeru.airicraft.dashboard.DiagnosticReport>();
			pending.add(future);
			return future;
		})));
		context.clickScreenButton("Preview attachments");
		context.runOnClient(minecraft -> {
			var developer = reportButton(minecraft.screen, "Developer");
			if (developer.active) throw new AssertionError("Attachment choices remain enabled during preview preparation");
			developer.mouseClicked(developer.getX() + 2, developer.getY() + 2, 0);
			// A resize reconstructs widgets while the preview is still pending.
			minecraft.screen.resize(minecraft, minecraft.screen.width, minecraft.screen.height);
		});
		context.runOnClient(minecraft -> pending.getFirst().complete(draft.prepare(requests.getFirst(), java.util.List.of())));
		context.waitFor(minecraft -> reportButton(minecraft.screen, "Save these attachments").active, 40);
		context.runOnClient(minecraft -> {
			if (reportButton(minecraft.screen, "Minimal").active
				|| !reportButton(minecraft.screen, "Summary").active || !reportButton(minecraft.screen, "Developer").active) {
				throw new AssertionError("Preview completion did not restore attachment choices or changed the selected mode");
			}
		});
		context.clickScreenButton("Developer");
		context.runOnClient(minecraft -> {
			if (reportButton(minecraft.screen, "Save these attachments").active
				|| reportButton(minecraft.screen, "Inspect evidence").active) throw new AssertionError("Mode change retained stale evidence");
		});
		context.clickScreenButton("Preview attachments");
		context.runOnClient(minecraft -> pending.getLast().completeExceptionally(new IllegalStateException("controlled preparation failure")));
		context.waitFor(minecraft -> reportButton(minecraft.screen, "Preview attachments").active, 40);
		context.runOnClient(minecraft -> {
			if (!reportButton(minecraft.screen, "Minimal").active || !reportButton(minecraft.screen, "Summary").active
				|| reportButton(minecraft.screen, "Developer").active || reportButton(minecraft.screen, "Save these attachments").active) {
				throw new AssertionError("Failed preview did not restore attachment choices safely");
			}
			if (requests.getLast().mode() != ai.moeru.airicraft.dashboard.DiagnosticReport.Mode.DEVELOPER) throw new AssertionError("Selected mode was not applied");
		});
		context.clickScreenButton("Summary");
		context.clickScreenButton("Cancel");
		context.waitForScreen(AiricraftSettingsScreen.class);
	}

	private static net.minecraft.client.gui.components.Button reportButton(net.minecraft.client.gui.screens.Screen screen, String label) {
		return screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.Button)
			.map(child -> (net.minecraft.client.gui.components.Button) child).filter(button -> button.getMessage().getString().equals(label))
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
		context.runOnClient(minecraft -> minecraft.setScreen(new DiagnosticEvidenceScreen(minecraft.screen, report)));
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
		context.waitFor(minecraft -> minecraft.screen != null && minecraft.screen.getTitle().getString().equals("Report this moment"), 40);
		context.runOnClient(minecraft -> minecraft.screen.children().stream()
			.filter(child -> child instanceof net.minecraft.client.gui.components.EditBox)
			.map(child -> (net.minecraft.client.gui.components.EditBox) child).findFirst().orElseThrow().setValue("Stopped moving; Authorization: Bearer pasted-secret"));
		if (worldLoaded) context.clickScreenButton("Summary");
		context.clickScreenButton("Preview attachments");
		context.waitFor(minecraft -> minecraft.screen.children().stream().anyMatch(child -> child instanceof net.minecraft.client.gui.components.Button button
			&& button.getMessage().getString().equals("Save these attachments") && button.active), 200);
		context.runOnClient(minecraft -> {
			var field = minecraft.screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.EditBox)
				.map(child -> (net.minecraft.client.gui.components.EditBox) child).findFirst().orElseThrow();
			field.setValue(field.getValue() + "; still stuck");
			if (minecraft.screen.children().stream().anyMatch(child -> child instanceof net.minecraft.client.gui.components.Button button
				&& button.getMessage().getString().equals("Save these attachments") && button.active)) throw new AssertionError("Edited report reused stale consent preview");
		});
		context.clickScreenButton("Preview attachments");
		context.waitFor(minecraft -> minecraft.screen.children().stream().anyMatch(child -> child instanceof net.minecraft.client.gui.components.Button button
			&& button.getMessage().getString().equals("Save these attachments") && button.active), 200);
		context.waitTicks(2);
		context.takeScreenshot(worldLoaded ? "airicraft-report-preview-world" : "airicraft-report-preview-minimal");
		if (Files.isDirectory(reports)) {
			try (var files = Files.list(reports)) { if (!files.collect(java.util.stream.Collectors.toSet()).equals(before)) throw new AssertionError("Preview wrote a file before consent"); }
		}
		context.clickScreenButton("Inspect evidence");
		context.waitFor(minecraft -> minecraft.screen != null && minecraft.screen.getTitle().getString().equals("Attached evidence"), 40);
		context.takeScreenshot(worldLoaded ? "airicraft-evidence-world" : "airicraft-evidence-minimal");
		context.clickScreenButton("Next");
		context.takeScreenshot("airicraft-evidence-metadata");
		context.clickScreenButton("Back to report");
		context.clickScreenButton("Save these attachments");
		context.waitForScreen(net.minecraft.client.gui.screens.AlertScreen.class);
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
		context.waitFor(minecraft -> minecraft.screen != null && minecraft.screen.getTitle().getString().equals("Report this moment"), 40);
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
		private static net.minecraft.client.gui.screens.Screen open(net.minecraft.client.gui.screens.Screen parent) {
			return com.terraformersmc.modmenu.ModMenu.getConfigScreen("airicraft", parent);
		}
	}
}
