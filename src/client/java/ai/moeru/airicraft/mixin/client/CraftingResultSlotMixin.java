package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.client.CraftingResultSlotCraftEventBridge;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ResultSlot;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ResultSlot.class)
public class CraftingResultSlotMixin implements CraftingResultSlotCraftEventBridge {
	@Unique
	private boolean airicraft$skipNextTakeItemCraftEvent;

	@Inject(method = "onTake", at = @At("HEAD"))
	private void airicraft$onTakeItem(Player player, ItemStack stack, CallbackInfo ci) {
		if (airicraft$skipNextTakeItemCraftEvent) {
			airicraft$skipNextTakeItemCraftEvent = false;
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread()) {
			return;
		}
		if (player == null || stack == null || stack.isEmpty() || stack.getCount() <= 0) {
			return;
		}

		String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		AiricraftClient.runtimeController().onPlayerCraftedItem(itemId, stack.getCount());
	}

	@Unique
	@Override
	public void airicraft$markQuickTransferHandled() {
		airicraft$skipNextTakeItemCraftEvent = true;
	}
}
