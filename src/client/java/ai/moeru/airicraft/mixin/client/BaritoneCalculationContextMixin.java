package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Let A* route around remembered structures instead of discovering protection by failing to dig. */
@Mixin(value = CalculationContext.class, remap = false)
public abstract class BaritoneCalculationContextMixin {
	@Shadow @Final public Level world;

	@Inject(method = "isPossiblyProtected", at = @At("HEAD"), cancellable = true)
	private void airicraft$preservePlaces(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
		if (WorldPlacePreservation.contains(world, x, y, z) || !ai.moeru.airicraft.agent.spatial.WorldTravelPolicy.allows(world,x,y,z)) cir.setReturnValue(true);
	}
}
