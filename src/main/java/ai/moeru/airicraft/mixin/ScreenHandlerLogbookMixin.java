package ai.moeru.airicraft.mixin;

import ai.moeru.airicraft.memory.InteractionLogbookRecorder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public class ScreenHandlerLogbookMixin {
	@Unique private InteractionLogbookRecorder.Click airicraft$before;
	@Inject(method = "clicked", at = @At("HEAD"))
	private void airicraft$before(int slot, int button, ClickType type, Player player, CallbackInfo ci) {
		if (player instanceof ServerPlayer serverPlayer)
			airicraft$before = InteractionLogbookRecorder.beforeClick(serverPlayer, (AbstractContainerMenu) (Object) this, slot);
	}
	@Inject(method = "clicked", at = @At("RETURN"))
	private void airicraft$after(int slot, int button, ClickType type, Player player, CallbackInfo ci) {
		if (player instanceof ServerPlayer serverPlayer && airicraft$before != null) {
			InteractionLogbookRecorder.afterClick(serverPlayer, (AbstractContainerMenu) (Object) this, airicraft$before);
			airicraft$before = null;
		}
	}
}
