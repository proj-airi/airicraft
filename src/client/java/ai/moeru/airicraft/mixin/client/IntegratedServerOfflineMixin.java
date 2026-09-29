package ai.moeru.airicraft.mixin.client;

import net.minecraft.server.MinecraftServer;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public class IntegratedServerOfflineMixin {
	@Inject(method = "publishServer", at = @At("HEAD"))
	private void airicraft$disableOnlineVerification(CallbackInfoReturnable<Boolean> cir) {
		((MinecraftServer) (Object) this).setUsesAuthentication(false);
	}
}
