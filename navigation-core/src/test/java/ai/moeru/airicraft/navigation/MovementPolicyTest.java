package ai.moeru.airicraft.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementPolicyTest {
	@Test
	void derivedPoliciesLeaveTheOriginalAndOtherFieldsAlone() {
		MovementPolicy base = MovementPolicy.defaults().withPlaceableBlocks(10);

		MovementPolicy noPlace = base.withPlacing(false);
		MovementPolicy shortFall = base.withMaxSafeFall(1);

		assertTrue(base.canPlace());
		assertFalse(noPlace.canPlace(), "blocks in hand do not matter once placing is off");
		assertEquals(10, noPlace.placeableBlocks());
		assertEquals(1, shortFall.maxSafeFall());
		assertEquals(base.maxSafeFall(), MovementPolicy.defaults().maxSafeFall());
		assertEquals(base.allowBreak(), shortFall.allowBreak());
	}

	@Test
	void aNegativeFallLimitIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> MovementPolicy.defaults().withMaxSafeFall(-1));
	}
}
