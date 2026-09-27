package ai.moeru.airicraft.settings;

import ai.moeru.airicraft.AiricraftClient;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

/** Exercises the rendered form against a real local provider, without touching user credentials. */
public final class OnboardingScreenGameTest implements FabricClientGameTest {
	@Override public void runTest(ClientGameTestContext context) {
		try {
			var directory = FabricLoader.getInstance().getConfigDir().resolve("airicraft");
			context.waitForScreen(OnboardingScreen.class);
			context.takeScreenshot("airicraft-onboarding-first-run");
			assertFinish(context, false);
			context.clickScreenButton("Mods");
			context.takeScreenshot("airicraft-onboarding-compatibility");
			context.clickScreenButton("Later");
			context.waitForScreen(TitleScreen.class);
			if (!OnboardingState.required(SettingsDraft.open(directory))) throw new AssertionError("Skip marked setup complete");
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.clickScreenButton("Setup & checks");
			context.waitForScreen(OnboardingScreen.class);
			successfulSetup(context, directory);
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.clickScreenButton("gui.cancel");
			context.waitForScreen(TitleScreen.class);
		} catch (Exception exception) { throw new AssertionError(exception); }
	}

	private static void successfulSetup(ClientGameTestContext context, java.nio.file.Path directory) throws Exception {
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		var images = new java.util.concurrent.atomic.AtomicInteger();
		var lists = new java.util.concurrent.atomic.AtomicInteger();
		server.createContext("/v1/models", exchange -> {
			lists.incrementAndGet();
			byte[] response = "{\"data\":[{\"id\":\"test-planner\"},{\"id\":\"test-vision\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
		});
		server.createContext("/v1/chat/completions", exchange -> {
			String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			if (request.contains("data:image/png;base64,")) images.incrementAndGet();
			byte[] response = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
		});
		server.start();
		var file = directory.resolve("agent.yml");
		String original = java.nio.file.Files.readString(file);
		try {
			context.clickScreenButton("API provider");
			context.takeScreenshot("airicraft-onboarding-codex");
			context.clickScreenButton("Local Codex");
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
			context.runOnClient(client -> {
				field(client.currentScreen, "Provider URL").setText(url);
				field(client.currentScreen, "API key").setText("onboarding-test-secret");
			});
			context.waitFor(client -> button(client.currentScreen, "▼").active, 200);
			if (lists.get() != 1) throw new AssertionError("Model discovery was not debounced");
			context.clickScreenButton("▼");
			context.takeScreenshot("airicraft-onboarding-model-dropdown");
			context.runOnClient(client -> field(client.currentScreen, "Model").setText("planner"));
			context.clickScreenButton("test-planner");
			context.runOnClient(client -> {
				if (!field(client.currentScreen, "Model").getText().equals("test-planner")) throw new AssertionError("Model selection did not fill field");
			});
			testConnection(context);
			assertFinish(context, true);
			// Manual model entry remains available, and any edit invalidates inference proof.
			context.runOnClient(client -> field(client.currentScreen, "Model").setText("manual-model"));
			assertFinish(context, false);
			testConnection(context);
			context.runOnClient(client -> field(client.currentScreen, "API key").setText("updated-test-secret"));
			assertFinish(context, false);
			testConnection(context);
			context.clickScreenButton("Vision");
			context.clickScreenButton("Separate vision: off");
			assertFinish(context, false);
			// Native image input supersedes even an enabled, unconfigured separate provider.
			context.clickScreenButton("Planner");
			context.clickScreenButton("Image input: off");
			assertFinish(context, false);
			testConnection(context);
			assertFinish(context, true);
			if (images.get() != 1) throw new AssertionError("Native vision did not test image input");
			context.clickScreenButton("Vision");
			context.runOnClient(client -> {
				if (client.currentScreen.children().stream().anyMatch(child -> child instanceof TextFieldWidget))
					throw new AssertionError("Native vision still asks for a dedicated endpoint");
			});
			context.takeScreenshot("airicraft-onboarding-native-vision");
			context.clickScreenButton("Planner");
			context.clickScreenButton("Image input: on");
			testConnection(context);
			assertFinish(context, false);
			context.clickScreenButton("Vision");
			context.runOnClient(client -> {
				// Listing is unsupported at this URL; inference still works with a manual ID.
				field(client.currentScreen, "Provider URL").setText(url + "/manual");
				field(client.currentScreen, "API key").setText("onboarding-test-secret");
				field(client.currentScreen, "Model").setText("test-vision");
			});
			server.createContext("/v1/manual/chat/completions", exchange -> {
				String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				if (request.contains("data:image/png;base64,")) images.incrementAndGet();
				byte[] response = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
			});
			testConnection(context);
			assertFinish(context, true);
			if (!java.nio.file.Files.readString(file).equals(original)) throw new AssertionError("Editing or testing wrote settings");
			context.runOnClient(client -> client.currentScreen.resize(client, client.currentScreen.width, client.currentScreen.height));
			assertFinish(context, true);
			context.takeScreenshot("airicraft-onboarding-vision-ready");
			context.clickScreenButton("Planner");
			context.takeScreenshot("airicraft-onboarding-ready");
			if (images.get() != 2) throw new AssertionError("Separate vision was not checked with a test image");
			context.clickScreenButton("Save & finish");
			context.waitForScreen(AiricraftSettingsScreen.class);
			var saved = SettingsDraft.open(directory);
			if (OnboardingState.required(saved)) throw new AssertionError("Successful setup not persisted");
			if (!saved.get("agent.yml", "model", "").equals("manual-model")) throw new AssertionError("Manual model not saved");
		} finally {
			server.stop(0); java.nio.file.Files.writeString(file, original);
			context.runOnClient(client -> AiricraftClient.runtimeController().reload());
		}
	}
	private static void testConnection(ClientGameTestContext context) {
		context.clickScreenButton("Test connection");
		context.waitFor(client -> ((OnboardingScreen) client.currentScreen).checksFinished(), 200);
	}
	private static void assertFinish(ClientGameTestContext context, boolean expected) {
		context.runOnClient(client -> { if (button(client.currentScreen, "Save & finish").active != expected) throw new AssertionError("Wrong completion state"); });
	}
	private static ButtonWidget button(Screen screen, String label) {
		return screen.children().stream().filter(child -> child instanceof ButtonWidget).map(child -> (ButtonWidget) child)
			.filter(button -> button.getMessage().getString().equals(label)).findFirst().orElseThrow();
	}
	private static TextFieldWidget field(Screen screen, String label) {
		return screen.children().stream().filter(child -> child instanceof TextFieldWidget).map(child -> (TextFieldWidget) child)
			.filter(field -> field.getMessage().getString().equals(label)).findFirst().orElseThrow();
	}
}
