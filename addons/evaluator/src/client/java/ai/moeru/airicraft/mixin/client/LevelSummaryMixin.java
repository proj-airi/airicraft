package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.agent.evaluation.FrozenWorldLoadService;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelSummary.class)
public class LevelSummaryMixin {
	private static final FrozenWorldLoadService AIRICRAFT_FROZEN_WORLDS = FrozenWorldLoadService.createDefault();

	@Inject(method = "createInfo", at = @At("RETURN"), cancellable = true)
	private void airicraft$appendFixtureStatus(CallbackInfoReturnable<Component> cir) {
		String directoryName = ((LevelSummary) (Object) this).getLevelId();
		AIRICRAFT_FROZEN_WORLDS.statusForDirectory(directoryName)
			.menuLabel()
			.ifPresent(label -> cir.setReturnValue(
				Component.empty()
					.append(cir.getReturnValue())
					.append(Component.literal(", " + label).withStyle(airicraft$fixtureFormatting()))
			));
	}

	private ChatFormatting airicraft$fixtureFormatting() {
		String directoryName = ((LevelSummary) (Object) this).getLevelId();
		return switch (AIRICRAFT_FROZEN_WORLDS.statusForDirectory(directoryName).state()) {
			case FROZEN -> ChatFormatting.GOLD;
			case FROZEN_MISSING_ARCHIVE -> ChatFormatting.RED;
			case DISPOSABLE_COPY -> ChatFormatting.AQUA;
			case UNFROZEN -> ChatFormatting.GREEN;
			case NONE -> ChatFormatting.GRAY;
		};
	}
}
