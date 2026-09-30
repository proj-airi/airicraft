package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public class GameRendererPauseMixin {
	@WrapWithCondition(
		method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"
		)
	)
	private boolean airicraft$suppressAutoPauseOnFocusLost(Minecraft minecraft, boolean pauseOnly) {
		return !AiricraftClient.runtimeController().config().suppressAutoPauseOnFocusLost();
	}
}
