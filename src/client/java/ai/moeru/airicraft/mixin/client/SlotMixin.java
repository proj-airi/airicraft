package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.client.CraftingResultSlotCraftEventBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Slot.class)
public class SlotMixin {
	@Inject(method = "onQuickCraft(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
	private void airicraft$onQuickTransfer(ItemStack stack, ItemStack originalStack, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || !minecraft.isSameThread()) {
			return;
		}
		if (!(((Object) this) instanceof ResultSlot)) {
			return;
		}
		if (!(((Object) this) instanceof CraftingResultSlotCraftEventBridge craftingResultSlotBridge)) {
			return;
		}
		if (stack == null || originalStack == null || originalStack.isEmpty()) {
			return;
		}

		int craftedCount = originalStack.getCount() - stack.getCount();
		if (craftedCount <= 0) {
			return;
		}

		String itemId = BuiltInRegistries.ITEM.getKey(originalStack.getItem()).toString();
		craftingResultSlotBridge.airicraft$markQuickTransferHandled();
		AiricraftClient.runtimeController().onPlayerCraftedItem(itemId, craftedCount);
	}
}
