package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.settings.OnboardingScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Minecraft.class)
public abstract class MinecraftClientOnboardingMixin {
	@Unique private boolean airicraft$checkedOnboarding;

	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen airicraft$beforeMainMenu(Screen screen) {
		if (!(screen instanceof TitleScreen) || airicraft$checkedOnboarding) return screen;
		airicraft$checkedOnboarding = true;
		return OnboardingScreen.required() ? OnboardingScreen.create(screen) : screen;
	}
}
