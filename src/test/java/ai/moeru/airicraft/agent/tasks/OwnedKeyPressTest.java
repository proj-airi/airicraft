package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.KeyMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedKeyPressTest {
	@Test
	void inactiveReleasePreservesUserHeldKey() {
		KeyMapping jumpKey = new KeyMapping("key.airicraft.test.inactive_jump", 32, KeyMapping.CATEGORY_MOVEMENT);
		OwnedKeyPress control = new OwnedKeyPress();
		jumpKey.setDown(true);

		control.release(jumpKey);

		assertTrue(jumpKey.isDown());
	}

	@Test
	void repeatedReleasePreservesNewUserPressAfterOwnedRelease() {
		KeyMapping jumpKey = new KeyMapping("key.airicraft.test.owned_jump", 32, KeyMapping.CATEGORY_MOVEMENT);
		OwnedKeyPress control = new OwnedKeyPress();
		control.press(jumpKey);

		control.release(jumpKey);
		assertFalse(jumpKey.isDown());

		jumpKey.setDown(true);
		control.release(jumpKey);

		assertTrue(jumpKey.isDown());
	}
}
