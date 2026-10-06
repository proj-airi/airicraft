package ai.moeru.airicraft;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

public class AiricraftClient implements ClientModInitializer {
	private static final ClientRuntimeController RUNTIME_CONTROLLER = new ClientRuntimeController();
	private static final ResourceLocation PLANNER_DEBUG_OVERLAY_ID = ResourceLocation.fromNamespaceAndPath("airicraft", "planner_debug_overlay");
	private static final Set<Screen> SCREEN_OVERLAY_HOOKS = Collections.newSetFromMap(new WeakHashMap<>());

	public static ClientRuntimeController runtimeController() {
		return RUNTIME_CONTROLLER;
	}

	@Override
	public void onInitializeClient() {
		ai.moeru.airicraft.settings.AiricraftSettings.register();
		ai.moeru.airicraft.agent.modded.ClientItemGroups.install();
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(
			server -> RUNTIME_CONTROLLER.automaticPlaytest().onHostedServerTick(server));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
			ClientCommandManager.literal("airicraft")
				.then(ClientCommandManager.literal("noplanner")
					.executes(context -> {
						boolean enabled = RUNTIME_CONTROLLER.togglePlannerEnabled();
						context.getSource().sendFeedback(Component.literal("Airicraft planner: " + (enabled ? "on" : "off")));
						return 1;
					}))
				.then(ClientCommandManager.literal("reload")
					.executes(context -> {
						try {
							context.getSource().sendFeedback(Component.literal(RUNTIME_CONTROLLER.reload().feedbackText()));
							return 1;
						}
						catch (BridgeUnavailableException exception) {
							context.getSource().sendError(Component.literal("Airicraft reload failed: " + exception.getMessage()));
							return 0;
						}
					}))
				.then(ClientCommandManager.literal("debug")
					.then(ClientCommandManager.literal("states")
						.executes(context -> {
							RUNTIME_CONTROLLER.setPlannerDebugOverlayMode(PlannerDebugOverlayMode.STATES);
							context.getSource().sendFeedback(debugOverlayText(RUNTIME_CONTROLLER.plannerDebugOverlayMode()));
							return 1;
						}))
					.then(ClientCommandManager.literal("conversation")
						.executes(context -> {
							RUNTIME_CONTROLLER.setPlannerDebugConversationView(PlannerConversationView.CHRONICLE);
							RUNTIME_CONTROLLER.setPlannerDebugOverlayMode(PlannerDebugOverlayMode.CONVERSATION);
							context.getSource().sendFeedback(debugOverlayText(RUNTIME_CONTROLLER.plannerDebugOverlayMode(), RUNTIME_CONTROLLER.plannerDebugConversationView()));
							return 1;
						}))
					.then(ClientCommandManager.literal("context")
						.executes(context -> {
							RUNTIME_CONTROLLER.setPlannerDebugConversationView(PlannerConversationView.CONTEXT);
							RUNTIME_CONTROLLER.setPlannerDebugOverlayMode(PlannerDebugOverlayMode.CONVERSATION);
							context.getSource().sendFeedback(debugOverlayText(RUNTIME_CONTROLLER.plannerDebugOverlayMode(), RUNTIME_CONTROLLER.plannerDebugConversationView()));
							return 1;
						}))
					.then(ClientCommandManager.literal("verbose")
						.executes(context -> {
							RUNTIME_CONTROLLER.setPlannerDebugConversationVerbose(!RUNTIME_CONTROLLER.plannerDebugConversationVerbose());
							context.getSource().sendFeedback(Component.literal(
								"Airicraft debug verbose: " + (RUNTIME_CONTROLLER.plannerDebugConversationVerbose() ? "on" : "off")));
							return 1;
						}))
					.then(ClientCommandManager.literal("off")
						.executes(context -> {
							RUNTIME_CONTROLLER.setPlannerDebugOverlayMode(PlannerDebugOverlayMode.OFF);
							context.getSource().sendFeedback(debugOverlayText(RUNTIME_CONTROLLER.plannerDebugOverlayMode()));
							return 1;
						}))
					.then(ClientCommandManager.literal("status")
						.executes(context -> {
							context.getSource().sendFeedback(debugOverlayText(RUNTIME_CONTROLLER.plannerDebugOverlayMode()));
							return 1;
						}))
				)
		));
		ClientLifecycleEvents.CLIENT_STARTED.register(RUNTIME_CONTROLLER::onClientStarted);
		ClientTickEvents.END_CLIENT_TICK.register(RUNTIME_CONTROLLER::onClientTick);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(RUNTIME_CONTROLLER::onWorldRender);
		HudElementRegistry.attachElementAfter(VanillaHudElements.SUBTITLES, PLANNER_DEBUG_OVERLAY_ID, RUNTIME_CONTROLLER::onHudRender);
		ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) -> {
			if (!SCREEN_OVERLAY_HOOKS.add(screen)) {
				return;
			}
			ScreenEvents.afterRender(screen).register((screenInstance, guiGraphics, mouseX, mouseY, tickDelta) -> RUNTIME_CONTROLLER.onScreenRender(guiGraphics));
			ScreenMouseEvents.allowMouseScroll(screen).register((screenInstance, mouseX, mouseY, horizontalAmount, verticalAmount) ->
				!RUNTIME_CONTROLLER.onScreenMouseScroll(mouseX, mouseY, verticalAmount)
			);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((listener, minecraft) -> RUNTIME_CONTROLLER.onWorldLeave());
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> RUNTIME_CONTROLLER.shutdown());
	}

	private static Component debugOverlayText(PlannerDebugOverlayMode mode) {
		return Component.literal("Airicraft debug overlay: " + mode.name().toLowerCase(Locale.ROOT));
	}

	private static Component debugOverlayText(PlannerDebugOverlayMode mode, PlannerConversationView view) {
		if (mode != PlannerDebugOverlayMode.CONVERSATION) {
			return debugOverlayText(mode);
		}
		return Component.literal("Airicraft debug overlay: conversation (" + view.name().toLowerCase(Locale.ROOT) + ")");
	}
}
