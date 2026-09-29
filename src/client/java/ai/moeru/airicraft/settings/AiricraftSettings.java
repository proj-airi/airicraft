package ai.moeru.airicraft.settings;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class AiricraftSettings {
	private AiricraftSettings() {}

	public static void register() {
		KeyMapping mapping = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.airicraft.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.airicraft"));
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			while (mapping.consumeClick()) {
				if (minecraft.screen == null) minecraft.setScreen(AiricraftSettingsScreen.create(null));
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
			ClientCommandManager.literal("airicraft").then(ClientCommandManager.literal("config").executes(context -> {
				var minecraft = context.getSource().getClient();
				// Queue after the chat screen finishes closing.
				minecraft.schedule(() -> minecraft.setScreen(AiricraftSettingsScreen.create(null)));
				return 1;
			}))));
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
				Screens.getButtons(screen).add(Button.builder(Component.translatable("button.airicraft.settings"),
					button -> minecraft.setScreen(AiricraftSettingsScreen.create(screen)))
					.bounds(width - 108, 8, 100, 20).build());
			}
		});
	}
}
