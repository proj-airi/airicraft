package ai.moeru.airicraft.sim.mixin;

import ai.moeru.airicraft.sim.SimRuntime;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reports every living-entity death to the sim runtime so episodes can score kills and player death. */
@Mixin(LivingEntity.class)
public abstract class PlayerDeathEventMixin {
	@Inject(method = "onDeath(Lnet/minecraft/entity/damage/DamageSource;)V", at = @At("HEAD"))
	private void airicraftSim$onDeath(DamageSource source, CallbackInfo ci) {
		SimRuntime.onEntityDeath((LivingEntity) (Object) this, source);
	}
}
