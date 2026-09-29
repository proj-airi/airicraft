package ai.moeru.airicraft.agent.session;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LanHostingService {
	public Map<String, Object> openLan(SessionSnapshot sessionSnapshot) {
		if (sessionSnapshot.mode() != SessionMode.SINGLEPLAYER_LOCAL) {
			throw new LanHostingException("invalid_session_mode", "LAN hosting requires SINGLEPLAYER_LOCAL");
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.player == null || minecraft.getSingleplayerServer() == null) {
			throw new LanHostingException("minecraft_unavailable", "Minecraft integrated server is not available");
		}

		GameType gameType = minecraft.gameMode != null
			? minecraft.gameMode.getPlayerMode()
			: GameType.SURVIVAL;
		int port;
		try {
			port = LanPortScan.openFirstAvailable(candidate -> minecraft.getSingleplayerServer().publishServer(gameType, false, candidate));
		}
		catch (LanPortScan.LanPortUnavailableException exception) {
			throw new LanHostingException("lan_open_failed", exception.getMessage());
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("opened", true);
		payload.put("port", port);
		return payload;
	}

	public static final class LanHostingException extends RuntimeException {
		private final String code;

		LanHostingException(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}
}
