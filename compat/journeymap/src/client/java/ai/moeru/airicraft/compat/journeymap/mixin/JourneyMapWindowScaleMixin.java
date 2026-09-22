package ai.moeru.airicraft.compat.journeymap.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** JourneyMap assumes a window always intersects a monitor, including during screen transitions. */
@Pseudo
@Mixin(targets = "journeymap.client.ui.component.screens.JmUI", remap = false)
public abstract class JourneyMapWindowScaleMixin {
	@Inject(method = "getQuarterMaxScale", at = @At("HEAD"), cancellable = true, remap = false)
	private static void airicraft$defaultScaleWithoutMonitor(CallbackInfoReturnable<Float> callback) {
		if (MinecraftClient.getInstance().getWindow().getMonitor() == null) {
			// This is JourneyMap's ordinary scale when no HiDPI adjustment is needed.
			callback.setReturnValue(1.0F);
		}
	}
}
