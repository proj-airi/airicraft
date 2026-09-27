package ai.moeru.airicraft.settings;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class AiricraftSettings {
	private AiricraftSettings() {}

	public static void register() {
		KeyBinding binding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.airicraft.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.airicraft"));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (binding.wasPressed()) {
				if (client.currentScreen == null) client.setScreen(AiricraftSettingsScreen.create(null));
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommandManager.literal("airicraft").then(ClientCommandManager.literal("config").executes(context -> {
				var client = context.getSource().getClient();
				// Queue after the chat screen finishes closing.
				client.send(() -> client.setScreen(AiricraftSettingsScreen.create(null)));
				return 1;
			}))));
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof TitleScreen || screen instanceof GameMenuScreen) {
				Screens.getButtons(screen).add(ButtonWidget.builder(Text.translatable("button.airicraft.settings"),
					button -> client.setScreen(AiricraftSettingsScreen.create(screen)))
					.dimensions(width - 108, 8, 100, 20).build());
			}
		});
	}
}
