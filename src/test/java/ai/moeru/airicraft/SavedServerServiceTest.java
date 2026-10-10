package ai.moeru.airicraft;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SavedServerServiceTest {
	@BeforeAll
	static void initGameVersion() {
		SharedConstants.tryDetectVersion();
	}

	@Test
	void startLoginUsesNormalLoginInsteadOfTransfer() {
		ServerData serverData = new ServerData("LAN world", "127.0.0.1:25565", ServerData.Type.LAN);
		List<Object[]> calls = new ArrayList<>();

		SavedServerService.startLogin(
			(parent, minecraft, address, data, quickPlay, transferState) ->
				calls.add(new Object[] {parent, minecraft, address, data, quickPlay, transferState}),
			null,
			null,
			serverData
		);

		assertEquals(1, calls.size());
		Object[] call = calls.getFirst();
		ServerAddress address = (ServerAddress) call[2];
		assertEquals("127.0.0.1", address.getHost());
		assertEquals(25565, address.getPort());
		assertSame(serverData, call[3]);
		assertFalse((Boolean) call[4]);
		// A non-null TransferState makes Minecraft send the TRANSFER handshake intent,
		// which LAN-opened integrated servers reject as transfers_disabled.
		assertNull(call[5]);
	}
}
