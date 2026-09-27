package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.WorldCameraService;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.client.render.RenderLayers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * World-camera leaf translucency: when the fade filter's {@code fadeLeaves}
 * flag is on, leaves are moved to the translucent render layer so the
 * alpha-scaling vertex consumer in {@link SectionBuilderMixin} can make
 * them ~20% opaque instead of hiding them outright.
 */
@Mixin(RenderLayers.class)
public abstract class RenderLayersMixin {
	@ModifyReturnValue(method = "getBlockLayer", at = @At("RETURN"))
	private static BlockRenderLayer airicraft$translucentLeaves(BlockRenderLayer original, BlockState state) {
		if (!(state.getBlock() instanceof LeavesBlock)) {
			return original;
		}
		WorldCameraService service = AiricraftClient.runtimeController().worldCameraService();
		if (service != null && service.fadeLeavesActive()) {
			return BlockRenderLayer.TRANSLUCENT;
		}
		return original;
	}
}
