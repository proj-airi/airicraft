package ai.moeru.airicraft.mixin.client;

import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(WorldSelectionList.class)
public interface WorldListWidgetInvoker {
	@Invoker("reloadWorldList")
	void airicraft$load();
}
