package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
public class GameRendererPauseMixin {
	@Redirect(
		method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"
		)
	)
	private void airicraft$suppressAutoPauseOnFocusLost(Minecraft minecraft, boolean pauseOnly) {
		if (AiricraftClient.runtimeController().config().suppressAutoPauseOnFocusLost()) {
			return;
		}
		minecraft.pauseGame(pauseOnly);
	}
}
