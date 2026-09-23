package ai.moeru.airicraft.sim.mixin;

import ai.moeru.airicraft.sim.SimRuntime;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records the last DamageSource per entity so episodes can attribute health diffs. */
@Mixin(LivingEntity.class)
public abstract class MobDamageSourceMixin {
	@Inject(method = "damage(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/damage/DamageSource;F)Z", at = @At("HEAD"))
	private void airicraftSim$recordDamageSource(ServerWorld world, DamageSource source, float amount,
			CallbackInfoReturnable<Boolean> cir) {
		SimRuntime.recordDamageSource((LivingEntity) (Object) this, source);
	}
}
