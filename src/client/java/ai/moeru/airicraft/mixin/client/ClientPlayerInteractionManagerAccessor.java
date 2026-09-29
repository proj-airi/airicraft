package ai.moeru.airicraft.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultiPlayerGameMode.class)
public interface ClientPlayerInteractionManagerAccessor {
	@Accessor("destroyBlockPos")
	BlockPos airicraft$currentBreakingPos();

	@Accessor("destroyProgress")
	float airicraft$currentBreakingProgress();

	@Accessor("isDestroying")
	boolean airicraft$breakingBlock();
}
