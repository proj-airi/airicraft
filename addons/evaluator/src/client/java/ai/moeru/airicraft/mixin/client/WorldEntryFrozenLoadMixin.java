package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.evaluation.EvaluationWorldFixtureService;
import ai.moeru.airicraft.agent.evaluation.FrozenWorldLoadService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldSelectionList.WorldListEntry.class)
public class WorldEntryFrozenLoadMixin {
	private static final FrozenWorldLoadService AIRICRAFT_FROZEN_WORLDS = FrozenWorldLoadService.createDefault();

	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	@Final
	LevelSummary summary;

	@Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
	private void airicraft$loadFrozenWorldCopy(CallbackInfo ci) {
		if (!summary.primaryActionActive() || summary instanceof LevelSummary.SymlinkLevelSummary) {
			return;
		}

		FrozenWorldLoadService.WorldFixtureStatus status = AIRICRAFT_FROZEN_WORLDS.statusForDirectory(summary.getLevelId());
		if (!status.frozenLoadMustDetour()) {
			return;
		}

		ci.cancel();
		try {
			EvaluationWorldFixtureService.RestoredWorld restoredWorld = AIRICRAFT_FROZEN_WORLDS
				.restoreFrozenDisposableCopy(summary.getLevelId())
				.orElseThrow(() -> new EvaluationWorldFixtureService.EvaluationWorldFixtureException(
					"scenario_not_frozen",
					"Scenario is not frozen: " + summary.getLevelId()
				));
			minecraft.createWorldOpenFlows().openWorld(restoredWorld.worldName(), () -> {
			});
		}
		catch (RuntimeException exception) {
			Airicraft.LOGGER.error("Failed to restore frozen scenario world {}", summary.getLevelId(), exception);
			showRestoreFailureToast(exception);
		}
	}

	private void showRestoreFailureToast(RuntimeException exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) {
			message = "Failed to restore disposable world copy";
		}
		SystemToast.add(
			minecraft.getToastManager(),
			SystemToast.SystemToastId.WORLD_ACCESS_FAILURE,
			Component.literal("Airicraft frozen world"),
			Component.literal(message)
		);
	}
}
