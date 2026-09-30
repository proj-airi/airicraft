package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.WorldCameraService;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * World-camera occluder fading: blocks in the active fade filter are meshed
 * as air, which both hides them and exposes the faces behind them.
 */
@Mixin(RenderSectionRegion.class)
public abstract class ChunkRendererRegionMixin {
	@ModifyReturnValue(method = "getBlockState", at = @At("RETURN"))
	private BlockState airicraft$fadeOccluders(BlockState original, BlockPos pos) {
		WorldCameraService service = AiricraftClient.runtimeController().worldCameraService();
		if (service.isFaded(pos, original)) {
			return Blocks.AIR.defaultBlockState();
		}
		return original;
	}
}
