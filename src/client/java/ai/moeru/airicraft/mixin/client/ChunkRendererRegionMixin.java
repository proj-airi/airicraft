package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.WorldCameraService;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.chunk.ChunkRendererRegion;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * World-camera occluder fading: blocks in the active fade filter are meshed
 * as air, which both hides them and exposes the faces behind them.
 */
@Mixin(ChunkRendererRegion.class)
public abstract class ChunkRendererRegionMixin {
	@Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
	private void airicraft$fadeOccluders(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		WorldCameraService service = AiricraftClient.runtimeController().worldCameraService();
		if (service != null && service.isFaded(pos, cir.getReturnValue())) {
			cir.setReturnValue(Blocks.AIR.getDefaultState());
		}
	}
}
