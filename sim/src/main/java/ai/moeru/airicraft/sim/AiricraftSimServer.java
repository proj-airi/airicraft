package ai.moeru.airicraft.sim;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AiricraftSimServer implements DedicatedServerModInitializer {
	public static final String MOD_ID = "airicraft-sim";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			applyWorldRules(server);
			SimRuntime.attach(server);
			SimHttpControl.start(server);
			LOGGER.info("airicraft-sim ready (control port {})", SimHttpControl.port());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			SimHttpControl.stop();
			SimRuntime.detach();
		});
	}

	/**
	 * Fixed combat-lab environment: permanent midnight (undead mobs never
	 * burn), no daylight or weather cycle. Everything else stays vanilla.
	 */
	private static void applyWorldRules(net.minecraft.server.MinecraftServer server) {
		net.minecraft.server.world.ServerWorld overworld = server.getOverworld();
		net.minecraft.world.GameRules rules = overworld.getGameRules();
		rules.get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
		rules.get(net.minecraft.world.GameRules.DO_WEATHER_CYCLE).set(false, server);
		overworld.setTimeOfDay(18000);
	}
}
