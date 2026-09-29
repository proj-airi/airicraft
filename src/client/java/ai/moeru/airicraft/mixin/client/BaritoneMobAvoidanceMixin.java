package ai.moeru.airicraft.mixin.client;

import baritone.api.utils.IPlayerContext;
import baritone.utils.pathing.Avoidance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.stream.Stream;

/** Passive animals must not multiply navigation costs around farms and shelters. */
@Mixin(value = Avoidance.class, remap = false)
public abstract class BaritoneMobAvoidanceMixin {
	@Redirect(method = "create", at = @At(value = "INVOKE",
		target = "Lbaritone/api/utils/IPlayerContext;entitiesStream()Ljava/util/stream/Stream;"))
	private static Stream<Entity> airicraft$onlyMonsterAvoidance(IPlayerContext context) {
		// Keep Baritone's subsequent enderman, zombified piglin and daylight-spider filters.
		// Monster also covers slimes and ghasts, which do not extend HostileEntity.
		return context.entitiesStream().filter(entity -> entity instanceof Enemy);
	}
}
