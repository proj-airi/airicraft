package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.evaluation.EvaluationWorldFixtureService;
import ai.moeru.airicraft.bridge.BridgeExtensionRegistry;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.text.Text;

public final class AiricraftEvaluatorClient implements ClientModInitializer {
	private static final EvaluationAddonRuntime RUNTIME = new EvaluationAddonRuntime();

	@Override
	public void onInitializeClient() {
		registerBridgeRoutes();
		registerCommands();
		ClientTickEvents.END_CLIENT_TICK.register(RUNTIME::onClientTick);
	}

	private static void registerBridgeRoutes() {
		BridgeExtensionRegistry.register("/v1/evaluation/status", RUNTIME::handleStatus);
		BridgeExtensionRegistry.register("/v1/evaluation/scenarios", RUNTIME::handleScenarios);
		BridgeExtensionRegistry.register("/v1/evaluation/config", RUNTIME::handleConfig);
		BridgeExtensionRegistry.register("/v1/evaluation/results", RUNTIME::handleResults);
		BridgeExtensionRegistry.register("/v1/evaluation/evidence", RUNTIME::handleEvidence);
		BridgeExtensionRegistry.register("/v1/evaluation/run", RUNTIME::handleRun);
		BridgeExtensionRegistry.register("/v1/evaluation/client-stop", RUNTIME::handleClientStop);
		BridgeExtensionRegistry.register("/v1/evaluation/survival-fixture", RUNTIME::handleSurvivalFixture);
		BridgeExtensionRegistry.register("/v1/evaluation/navigation-course", RUNTIME::handleNavigationCourse);
	}

	private static void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			CommandNode<FabricClientCommandSource> root = dispatcher.getRoot().getChild("airicraft");
			if (root == null) {
				root = dispatcher.register(ClientCommandManager.literal("airicraft"));
			}
			root.addChild(ClientCommandManager.literal("freeze")
				.executes(context -> {
					try {
						var result = RUNTIME.fixtures().freezeCurrentWorld();
						context.getSource().sendFeedback(Text.literal("Airicraft scenario frozen: " + result.scenarioId()));
						return 1;
					}
					catch (EvaluationWorldFixtureService.EvaluationWorldFixtureException exception) {
						Airicraft.LOGGER.error("Airicraft freeze failed", exception);
						context.getSource().sendError(Text.literal("Airicraft freeze failed: " + exception.getMessage()));
						return 0;
					}
				})
				.build());
			root.addChild(ClientCommandManager.literal("unfreeze")
				.executes(context -> {
					try {
						var result = RUNTIME.fixtures().unfreezeCurrentWorld();
						context.getSource().sendFeedback(Text.literal("Airicraft scenario unfrozen: " + result.scenarioId()));
						return 1;
					}
					catch (EvaluationWorldFixtureService.EvaluationWorldFixtureException exception) {
						Airicraft.LOGGER.error("Airicraft unfreeze failed", exception);
						context.getSource().sendError(Text.literal("Airicraft unfreeze failed: " + exception.getMessage()));
						return 0;
					}
				})
				.build());
			root.addChild(ClientCommandManager.literal("evaluation")
				.then(ClientCommandManager.literal("config")
					.executes(context -> {
						try {
							var path = RUNTIME.fixtures().openCurrentScenarioConfig();
							context.getSource().sendFeedback(Text.literal("Opened Airicraft scenario config: " + path));
							return 1;
						}
						catch (EvaluationWorldFixtureService.EvaluationWorldFixtureException exception) {
							Airicraft.LOGGER.error("Airicraft scenario config failed", exception);
							context.getSource().sendError(Text.literal("Airicraft scenario config failed: " + exception.getMessage()));
							return 0;
						}
					}))
				.build());
		});
	}
}
