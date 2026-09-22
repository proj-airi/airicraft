package ai.moeru.airicraft.sim.mixin;

import ai.moeru.airicraft.sim.SimRuntime;
import ai.moeru.airicraft.sim.tick.SimTickGate;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTickGateMixin {
	@Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"), cancellable = true)
	private void airicraftSim$gateTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		if (!SimTickGate.beginTick()) {
			ci.cancel();
			return;
		}
		SimRuntime.beforeTick((MinecraftServer) (Object) this);
	}

	@Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("TAIL"))
	private void airicraftSim$afterTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		SimRuntime.afterTick((MinecraftServer) (Object) this);
	}
}
