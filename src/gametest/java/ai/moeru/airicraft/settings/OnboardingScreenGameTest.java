package ai.moeru.airicraft.settings;

import ai.moeru.airicraft.AiricraftClient;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;

/** Rendered lifecycle checks, using the same isolated game directory as the settings smoke. */
public final class OnboardingScreenGameTest implements FabricClientGameTest {
	@Override public void runTest(ClientGameTestContext context) {
		try {
			var directory = FabricLoader.getInstance().getConfigDir().resolve("airicraft");
			context.waitForScreen(OnboardingScreen.class);
			context.takeScreenshot("airicraft-onboarding-first-run");
			context.runOnClient(client -> client.currentScreen.mouseScrolled(100, 100, 0, -20));
			context.takeScreenshot("airicraft-onboarding-compatibility");
			context.runOnClient(client -> {
				var finish = client.currentScreen.children().stream().filter(child -> child instanceof ButtonWidget)
					.map(child -> (ButtonWidget) child).filter(button -> button.getMessage().getString().equals("Finish setup"))
					.findFirst().orElseThrow();
				if (finish.active) throw new AssertionError("Unverified setup can be completed");
			});
			context.clickScreenButton("Configure providers");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.clickScreenButton("gui.cancel");
			context.waitForScreen(OnboardingScreen.class);
			context.clickScreenButton("Check connections");
			context.waitFor(client -> ((OnboardingScreen) client.currentScreen).checksFinished(), 200);
			context.takeScreenshot("airicraft-onboarding-unconfigured");
			context.clickScreenButton("Set up later");
			context.waitForScreen(TitleScreen.class);
			if (!OnboardingState.required(SettingsDraft.open(directory))) throw new AssertionError("Skip marked setup complete");
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.clickScreenButton("Setup & checks");
			context.waitForScreen(OnboardingScreen.class);
			context.takeScreenshot("airicraft-onboarding-reopened");
			successfulSetup(context, directory);
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.clickScreenButton("gui.cancel");
			context.waitForScreen(TitleScreen.class);
		} catch (Exception exception) { throw new AssertionError(exception); }
	}

	private static void successfulSetup(ClientGameTestContext context, java.nio.file.Path directory) throws Exception {
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		var images = new java.util.concurrent.atomic.AtomicInteger();
		server.createContext("/v1/chat/completions", exchange -> {
			String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			if (request.contains("data:image/png;base64,")) images.incrementAndGet();
			byte[] response = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		var file = directory.resolve("agent.yml");
		String original = java.nio.file.Files.readString(file);
		try {
			context.clickScreenButton("Configure providers");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(client -> {
				var screen = (AiricraftSettingsScreen) client.currentScreen;
				String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
				field(screen, "Provider URL").setValue(url);
				field(screen, "API key").setValue("onboarding-test-secret");
				field(screen, "Model").setValue("test-planner");
				field(screen, "Vision provider URL").setValue(url);
				field(screen, "Vision API key").setValue("onboarding-test-secret");
				field(screen, "Vision model").setValue("test-vision");
			});
			context.waitTick();
			context.clickScreenButton("airicraft.settings.save");
			context.waitForScreen(OnboardingScreen.class);
			context.clickScreenButton("Check connections");
			context.waitFor(client -> ((OnboardingScreen) client.currentScreen).checksFinished(), 200);
			context.runOnClient(client -> client.currentScreen.resize(client, client.currentScreen.width, client.currentScreen.height));
			context.runOnClient(client -> {
				client.options.getGuiScale().setValue(1);
				client.onResolutionChanged();
			});
			context.takeScreenshot("airicraft-onboarding-ready");
			if (images.get() != 1) throw new AssertionError("Separate vision was not checked with a test image");
			context.clickScreenButton("Finish setup");
			context.waitForScreen(AiricraftSettingsScreen.class);
			if (OnboardingState.required(SettingsDraft.open(directory))) throw new AssertionError("Successful setup not persisted");
		} finally {
			server.stop(0);
			java.nio.file.Files.writeString(file, original);
			context.runOnClient(client -> AiricraftClient.runtimeController().reload());
		}
	}

	private static me.shedaniel.clothconfig2.gui.entries.StringListEntry field(AiricraftSettingsScreen screen, String label) {
		return (me.shedaniel.clothconfig2.gui.entries.StringListEntry) screen.getCategorizedEntries().values().stream()
			.flatMap(java.util.Collection::stream).flatMap(OnboardingScreenGameTest::flatten)
			.filter(entry -> entry.getFieldName().getString().equals(label)).findFirst().orElseThrow();
	}
	private static java.util.stream.Stream<me.shedaniel.clothconfig2.api.AbstractConfigEntry<?>> flatten(me.shedaniel.clothconfig2.api.AbstractConfigEntry<?> entry) {
		if (entry instanceof me.shedaniel.clothconfig2.gui.entries.SubCategoryListEntry category) return category.getValue().stream().flatMap(OnboardingScreenGameTest::flatten);
		return java.util.stream.Stream.of(entry);
	}
}
