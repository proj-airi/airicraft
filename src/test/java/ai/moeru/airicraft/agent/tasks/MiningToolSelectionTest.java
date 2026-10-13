package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningToolSelectionTest {
	@Test
	void findsPickaxeOutsideFullHotbarForStoneClearance() {
		var slots = inventory(false, 1);
		slots[32] = new MiningToolSelection.Score(true, 6);
		slots[33] = new MiningToolSelection.Score(true, 2);
		slots[34] = new MiningToolSelection.Score(true, 4);
		assertEquals(32, MiningToolSelection.preferredSlot(2, slot -> slots[slot]));
	}

	@Test
	void prefersEfficientInventoryToolEvenWhenBlockDoesNotRequireOne() {
		var slots = inventory(true, 1);
		slots[20] = new MiningToolSelection.Score(true, 8);
		assertEquals(20, MiningToolSelection.preferredSlot(2, slot -> slots[slot]));
	}

	@Test
	void keepsSelectedToolOnEqualSpeedToAvoidRestartingBreakProgress() {
		var slots = inventory(true, 1);
		slots[2] = slots[32] = new MiningToolSelection.Score(true, 6);
		assertEquals(2, MiningToolSelection.preferredSlot(2, slot -> slots[slot]));
	}

	@Test
	void refusesIneligibleToolsEvenWhenFaster() {
		var slots = inventory(false, 1);
		slots[2] = new MiningToolSelection.Score(false, 10);
		assertEquals(-1, MiningToolSelection.preferredSlot(2, slot -> slots[slot]));
	}

	@Test
	void clearanceAllowsSnowWithoutShovelButHarvestingStillRefuses() {
		var slots = inventory(false, 1);
		assertEquals(2, MiningToolSelection.preferredClearanceSlot(2, slot -> slots[slot]));
		assertEquals(-1, MiningToolSelection.preferredSlot(2, slot -> slots[slot]));
	}

	@Test
	void clearanceStillSelectsInventoryPickaxeInsteadOfBareHands() {
		var slots = inventory(false, 1);
		slots[32] = new MiningToolSelection.Score(true, 6);
		assertEquals(32, MiningToolSelection.preferredClearanceSlot(2, slot -> slots[slot]));
	}

	@Test
	void clearanceAccountsForSlowerBreakingWithoutHarvestCapability() {
		var slots = inventory(false, 1);
		slots[10] = new MiningToolSelection.Score(false, 8);
		slots[32] = new MiningToolSelection.Score(true, 4);
		assertEquals(32, MiningToolSelection.preferredClearanceSlot(2, slot -> slots[slot]));
	}

	@Test
	void doesNotWearAToolThatBreaksNoFasterThanAHand() {
		var slots = inventory(true, 1);
		slots[2] = new MiningToolSelection.Score(true, 1, true);
		assertEquals(0, MiningToolSelection.preferredSlot(2, slot -> slots[slot]));
		assertEquals(0, MiningToolSelection.preferredClearanceSlot(2, slot -> slots[slot]));
	}

	@Test
	void keepsAWearingToolWhenOnlyMainInventoryHasAnUnwornItem() {
		var slots = inventory(true, 1);
		Arrays.fill(slots, 0, 9, new MiningToolSelection.Score(true, 1, true));
		assertEquals(2, MiningToolSelection.preferredSlot(2, slot -> slots[slot]));
	}

	@Test
	void stillWearsAToolThatIsFaster() {
		var slots = inventory(true, 1);
		slots[2] = new MiningToolSelection.Score(true, 6, true);
		assertEquals(2, MiningToolSelection.preferredSlot(2, slot -> slots[slot]));
	}

	private static MiningToolSelection.Score[] inventory(boolean eligible, float speed) {
		var slots = new MiningToolSelection.Score[36];
		Arrays.fill(slots, new MiningToolSelection.Score(eligible, speed));
		return slots;
	}
}
