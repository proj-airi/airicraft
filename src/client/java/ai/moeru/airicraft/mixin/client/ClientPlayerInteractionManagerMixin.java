package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;
import ai.moeru.airicraft.debug.ClientTickPlayerActionEvents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {
	@Unique
	private String airicraft$breakingBlockId;

	@Unique
	private BlockPos airicraft$breakingBlockPos;

	@Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void airicraft$captureAttackStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (WorldPlacePreservation.blocksPathBreaking(pos) || ai.moeru.airicraft.agent.spatial.WorldTravelPolicy.blocksEdit(pos)) {
			cir.setReturnValue(false);
			return;
		}
		ClientTickPlayerActionEvents.recordStart("attack");
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void airicraft$captureBlockBreakStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (WorldPlacePreservation.blocksPathBreaking(pos) || ai.moeru.airicraft.agent.spatial.WorldTravelPolicy.blocksEdit(pos)) {
			cir.setReturnValue(false);
			return;
		}
		ClientPlayerInteractionManagerAccessor accessor = (ClientPlayerInteractionManagerAccessor) (Object) this;
		if (!accessor.airicraft$breakingBlock()) {
			ClientTickPlayerActionEvents.recordStart("attack");
		}
	}

	@Inject(method = "continueDestroyBlock", at = @At("RETURN"))
	private void airicraft$captureBlockBreakProgress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (!Boolean.TRUE.equals(cir.getReturnValue()) || pos == null) {
			return;
		}
		ClientPlayerInteractionManagerAccessor accessor = (ClientPlayerInteractionManagerAccessor) (Object) this;
		ClientTickPlayerActionEvents.recordBreakProgress(
			pos.getX(),
			pos.getY(),
			pos.getZ(),
			accessor.airicraft$currentBreakingProgress()
		);
	}

	@Inject(method = "attack", at = @At("HEAD"))
	private void airicraft$captureEntityAttackStart(Player player, Entity target, CallbackInfo ci) {
		ClientTickPlayerActionEvents.recordStart("attack");
	}

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void airicraft$captureBlockUseStart(
		LocalPlayer player,
		InteractionHand hand,
		BlockHitResult hitResult,
		CallbackInfoReturnable<InteractionResult> cir
	) {
		BlockPos target = hitResult.getBlockPos();
		if (ai.moeru.airicraft.agent.spatial.WorldTravelPolicy.blocksEdit(target)
			|| player.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem
				&& ai.moeru.airicraft.agent.spatial.WorldTravelPolicy.blocksEdit(target.relative(hitResult.getDirection()))) {
			cir.setReturnValue(InteractionResult.FAIL);
			return;
		}

		ClientTickPlayerActionEvents.recordStart("use");
	}

	@Inject(method = "useItem", at = @At("HEAD"))
	private void airicraft$captureItemUseStart(
		Player player,
		InteractionHand hand,
		CallbackInfoReturnable<InteractionResult> cir
	) {
		ClientTickPlayerActionEvents.recordStart("use");
	}

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void airicraft$captureBrokenBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		airicraft$breakingBlockId = null;
		airicraft$breakingBlockPos = null;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || !minecraft.isSameThread() || minecraft.level == null || pos == null) {
			return;
		}

		BlockState state = minecraft.level.getBlockState(pos);
		if (state == null || state.isAir()) {
			return;
		}
		airicraft$breakingBlockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		airicraft$breakingBlockPos = pos.immutable();
	}

	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void airicraft$reportBrokenBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (!Boolean.TRUE.equals(cir.getReturnValue()) || airicraft$breakingBlockId == null || airicraft$breakingBlockPos == null) {
			return;
		}
		AiricraftClient.runtimeController().onPlayerMinedBlock(
			airicraft$breakingBlockId,
			airicraft$breakingBlockPos.getX(),
			airicraft$breakingBlockPos.getY(),
			airicraft$breakingBlockPos.getZ()
		);
	}
}
