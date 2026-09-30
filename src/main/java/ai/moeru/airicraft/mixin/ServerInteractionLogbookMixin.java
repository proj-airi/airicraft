package ai.moeru.airicraft.mixin;

import ai.moeru.airicraft.memory.InteractionLogbookRecorder;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public class ServerInteractionLogbookMixin {
	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void airicraft$before(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir, @Share("previousMenu") LocalRef<AbstractContainerMenu> previousMenu) {
		previousMenu.set(player.containerMenu);
	}

	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void airicraft$after(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir, @Share("previousMenu") LocalRef<AbstractContainerMenu> previousMenu) {
		if (player.containerMenu != previousMenu.get())
			InteractionLogbookRecorder.opened(player, hit.getBlockPos(), player.containerMenu);
	}
}
