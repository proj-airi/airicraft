package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.tasks.MiningToolPreparation;
import baritone.api.BaritoneAPI;
import baritone.api.utils.IPlayerContext;
import baritone.utils.BlockBreakHelper;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Covers path clearance, after Baritone's hotbar-only selection. */
@Mixin(value = BlockBreakHelper.class, remap = false)
public abstract class BaritoneBlockBreakMixin {
	@Shadow @Final private IPlayerContext ctx;
	@Shadow public abstract void stopBreakingBlock();

	@Inject(method = "tick", at = @At(value = "INVOKE",
		target = "Lbaritone/api/utils/IPlayerController;setHittingBlock(Z)V", ordinal = 0), cancellable = true)
	private void airicraft$prepareBreakingTool(boolean leftClick, CallbackInfo ci) {
		if (ctx.player().isCreative()) return;
		// This invocation is reached only for a requested attack with a block hit.
		BlockHitResult hit = (BlockHitResult) ctx.objectMouseOver();
		var state = ctx.world().getBlockState(hit.getBlockPos());
		var result = MiningToolPreparation.ensureSelectedForClearance(ctx.minecraft(), ctx.player(), List.of(state));
		if (!result.ok()) {
			stopBreakingBlock();
			BaritoneAPI.getProvider().getBaritoneForPlayer(ctx.player()).getPathingBehavior().cancelEverything();
			Airicraft.LOGGER.warn("Baritone block breaking stopped: {} target={}", result.message(), hit.getBlockPos());
			ci.cancel();
		}
	}
}
