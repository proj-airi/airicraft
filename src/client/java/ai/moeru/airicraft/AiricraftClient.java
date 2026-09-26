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
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

public class AiricraftClient implements ClientModInitializer {
	private static final ClientRuntimeController RUNTIME_CONTROLLER = new ClientRuntimeController();
	private static final Identifier PLANNER_DEBUG_OVERLAY_ID = Identifier.of("airicraft", "planner_debug_overlay");
	private static final Set<Screen> SCREEN_OVERLAY_HOOKS = Collections.newSetFromMap(new WeakHashMap<>());

	public static ClientRuntimeController runtimeController() {
		return RUNTIME_CONTROLLER;
	}

	@Override
	public void onInitializeClient() {
		ai.moeru.airicraft.settings.AiricraftSettings.register();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommandManager.literal("airicraft")
				.then(ClientCommandManager.literal("noplanner")
					.executes(context -> {
						boolean enabled = RUNTIME_CONTROLLER.togglePlannerEnabled();
						context.getSource().sendFeedback(Text.literal("Airicraft planner: " + (enabled ? "on" : "off")));
						return 1;
					}))
				.then(ClientCommandManager.literal("reload")
					.executes(context -> {
						try {
							context.getSource().sendFeedback(Text.literal(RUNTIME_CONTROLLER.reload().feedbackText()));
							return 1;
						}
						catch (BridgeUnavailableException exception) {
							context.getSource().sendError(Text.literal("Airicraft reload failed: " + exception.getMessage()));
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
							context.getSource().sendFeedback(Text.literal(
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
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!SCREEN_OVERLAY_HOOKS.add(screen)) {
				return;
			}
			ScreenEvents.afterRender(screen).register((screenInstance, drawContext, mouseX, mouseY, tickDelta) -> RUNTIME_CONTROLLER.onScreenRender(drawContext));
			ScreenMouseEvents.allowMouseScroll(screen).register((screenInstance, mouseX, mouseY, horizontalAmount, verticalAmount) ->
				!RUNTIME_CONTROLLER.onScreenMouseScroll(mouseX, mouseY, verticalAmount)
			);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RUNTIME_CONTROLLER.onWorldLeave());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RUNTIME_CONTROLLER.shutdown());
	}

	private static Text debugOverlayText(PlannerDebugOverlayMode mode) {
		return Text.literal("Airicraft debug overlay: " + mode.name().toLowerCase(Locale.ROOT));
	}

	private static Text debugOverlayText(PlannerDebugOverlayMode mode, PlannerConversationView view) {
		if (mode != PlannerDebugOverlayMode.CONVERSATION) {
			return debugOverlayText(mode);
		}
		return Text.literal("Airicraft debug overlay: conversation (" + view.name().toLowerCase(Locale.ROOT) + ")");
	}
}
