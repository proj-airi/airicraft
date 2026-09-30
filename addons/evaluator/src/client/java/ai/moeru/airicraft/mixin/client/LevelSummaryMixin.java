package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.agent.evaluation.FrozenWorldLoadService;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelSummary.class)
public class LevelSummaryMixin {
	@Unique
    private static final FrozenWorldLoadService AIRICRAFT_FROZEN_WORLDS = FrozenWorldLoadService.createDefault();

	@ModifyReturnValue(method = "createInfo", at = @At("RETURN"))
	private Component airicraft$appendFixtureStatus(Component original) {
		String directoryName = ((LevelSummary) (Object) this).getLevelId();
		return AIRICRAFT_FROZEN_WORLDS.statusForDirectory(directoryName)
			.menuLabel()
			.<Component>map(label -> Component.empty()
				.append(original)
				.append(Component.literal(", " + label).withStyle(airicraft$fixtureFormatting()))
			)
			.orElse(original);
	}

	@Unique
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
