package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.agent.evaluation.EvaluationWorldListUiState;
import ai.moeru.airicraft.agent.evaluation.FrozenWorldLoadService;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldSelectionList.class)
public class WorldListWidgetEvaluationFilterMixin {
	private static final FrozenWorldLoadService AIRICRAFT_FROZEN_WORLDS = FrozenWorldLoadService.createDefault();

	@Inject(method = "filterAccepts", at = @At("HEAD"), cancellable = true)
	private void airicraft$hideEvaluationCopies(String search, LevelSummary summary, CallbackInfoReturnable<Boolean> cir) {
		if (EvaluationWorldListUiState.showEvaluationCopies()) {
			return;
		}
		FrozenWorldLoadService.WorldFixtureStatus status = AIRICRAFT_FROZEN_WORLDS.statusForDirectory(summary.getLevelId());
		if (status.state() == FrozenWorldLoadService.WorldFixtureState.DISPOSABLE_COPY) {
			cir.setReturnValue(false);
		}
	}
}
