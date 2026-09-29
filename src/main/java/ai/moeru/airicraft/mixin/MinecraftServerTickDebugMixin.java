package ai.moeru.airicraft.mixin;

import ai.moeru.airicraft.debug.ServerTickDebugRuntime;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public class MinecraftServerTickDebugMixin {
	@Inject(method = "tickServer", at = @At("HEAD"), cancellable = true)
	private void airicraft$gateServerTick(BooleanSupplier shouldKeepTicking, CallbackInfo callback) {
		if (!ServerTickDebugRuntime.beginServerTick()) {
			callback.cancel();
		}
	}

	@Inject(method = "tickServer", at = @At("TAIL"))
	private void airicraft$completeServerTick(BooleanSupplier shouldKeepTicking, CallbackInfo callback) {
		ServerTickDebugRuntime.completeServerTick(((MinecraftServer) (Object) this).getTickCount());
	}
}
