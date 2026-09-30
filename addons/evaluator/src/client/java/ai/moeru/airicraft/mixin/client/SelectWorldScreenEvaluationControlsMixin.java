package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.evaluation.EvaluationWorldFixtureService;
import ai.moeru.airicraft.agent.evaluation.EvaluationWorldListUiState;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenEvaluationControlsMixin extends Screen {
	@Unique
    private static final EvaluationWorldFixtureService AIRICRAFT_FIXTURES = EvaluationWorldFixtureService.createDefault();

	@Shadow
	protected EditBox searchBox;

	@Shadow
	private WorldSelectionList list;

	@Unique
    private Button airicraft$evalCopiesToggleButton;
	@Unique
    private Button airicraft$cleanupEvalCopiesButton;

	protected SelectWorldScreenEvaluationControlsMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void airicraft$addEvaluationWorldControls(CallbackInfo ci) {
		int x = this.width / 2 - 310;
		int y = this.height - 28;
		if (x < 4) {
			x = 4;
			y = this.height - 76;
		}

		airicraft$evalCopiesToggleButton = addRenderableWidget(Button.builder(
				airicraft$toggleMessage(),
				button -> {
					EvaluationWorldListUiState.toggleEvaluationCopies();
					airicraft$updateEvaluationControls();
					airicraft$reloadWorldList();
				}
			)
			.bounds(x, y, 72, 20)
			.tooltip(Tooltip.create(Component.literal("Show or hide Airicraft eval copies")))
			.build());
		airicraft$cleanupEvalCopiesButton = addRenderableWidget(Button.builder(
				Component.literal("Clean Eval"),
				button -> airicraft$cleanupEvalCopies()
			)
			.bounds(x + 76, y, 72, 20)
			.tooltip(Tooltip.create(Component.literal("Delete all Airicraft eval copies")))
			.build());
		airicraft$updateEvaluationControls();
	}

	@Unique
    private void airicraft$cleanupEvalCopies() {
		try {
			EvaluationWorldFixtureService.CleanupResult result = AIRICRAFT_FIXTURES.cleanupDisposableWorlds();
			airicraft$updateEvaluationControls();
			airicraft$reloadWorldList();
			airicraft$showToast("Cleaned eval copies", result.deletedCount() + " deleted");
		}
		catch (EvaluationWorldFixtureService.EvaluationWorldFixtureException exception) {
			Airicraft.LOGGER.error("Failed to clean Airicraft eval copies", exception);
			airicraft$showToast("Clean eval copies failed", exception.getMessage());
		}
	}

	@Unique
    private void airicraft$updateEvaluationControls() {
		if (airicraft$evalCopiesToggleButton != null) {
			airicraft$evalCopiesToggleButton.setMessage(airicraft$toggleMessage());
		}
		if (airicraft$cleanupEvalCopiesButton != null) {
			try {
				airicraft$cleanupEvalCopiesButton.active = AIRICRAFT_FIXTURES.disposableWorldCount() > 0;
			}
			catch (EvaluationWorldFixtureService.EvaluationWorldFixtureException exception) {
				Airicraft.LOGGER.warn("Failed to count Airicraft eval copies", exception);
				airicraft$cleanupEvalCopiesButton.active = false;
			}
		}
	}

	@Unique
    private void airicraft$reloadWorldList() {
		if (list != null) {
			list.reloadWorldList();
		}
		if (searchBox != null && list != null) {
			list.updateFilter(searchBox.getValue());
		}
	}

	@Unique
    private Component airicraft$toggleMessage() {
		return Component.literal(EvaluationWorldListUiState.showEvaluationCopies() ? "Eval: On" : "Eval: Off");
	}

	@Unique
    private void airicraft$showToast(String title, String message) {
		if (minecraft == null) {
			return;
		}
		SystemToast.add(
			minecraft.getToastManager(),
			SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
			Component.literal(title),
			Component.literal(message == null || message.isBlank() ? "" : message)
		);
	}
}
