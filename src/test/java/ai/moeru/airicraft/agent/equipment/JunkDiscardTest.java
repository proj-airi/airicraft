package ai.moeru.airicraft.agent.equipment;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JunkDiscardTest {
	private static final Set<String> JUNK = Set.of("minecraft:netherrack", "minecraft:cobblestone");

	private static List<JunkDiscard.Stack> inventory(JunkDiscard.Stack... stacks) {
		List<JunkDiscard.Stack> slots = new ArrayList<>();
		for (int slot = 0; slot < 36; slot++) slots.add(new JunkDiscard.Stack(slot, null, 0));
		for (JunkDiscard.Stack stack : stacks) slots.set(stack.inventorySlot(), stack);
		return slots;
	}

	@Test
	void throwsNetherrackBeyondTwoStacks() {
		var discard = JunkDiscard.choose(inventory(
			new JunkDiscard.Stack(1, "minecraft:netherrack", 64),
			new JunkDiscard.Stack(10, "minecraft:netherrack", 64),
			new JunkDiscard.Stack(11, "minecraft:netherrack", 40),
			new JunkDiscard.Stack(12, "minecraft:diamond", 3)), 0, JUNK::contains).orElseThrow();
		assertEquals(11, discard.inventorySlot());
		assertEquals(40, discard.count());
		assertEquals(128, discard.kept());
	}

	@Test
	void keepsTwoStacksAndNeverTouchesOtherItems() {
		assertTrue(JunkDiscard.choose(inventory(
			new JunkDiscard.Stack(1, "minecraft:netherrack", 64),
			new JunkDiscard.Stack(10, "minecraft:netherrack", 64),
			new JunkDiscard.Stack(12, "minecraft:diamond", 64),
			new JunkDiscard.Stack(13, "minecraft:diamond", 64),
			new JunkDiscard.Stack(14, "minecraft:diamond", 64)), 0, JUNK::contains).isEmpty());
	}

	@Test
	void aNearlyFullInventoryKeepsOneStack() {
		List<JunkDiscard.Stack> slots = inventory();
		for (int slot = 0; slot < 34; slot++) slots.set(slot, new JunkDiscard.Stack(slot, "minecraft:diamond", 1));
		slots.set(2, new JunkDiscard.Stack(2, "minecraft:cobblestone", 64));
		slots.set(3, new JunkDiscard.Stack(3, "minecraft:cobblestone", 64));
		var discard = JunkDiscard.choose(slots, 0, JUNK::contains).orElseThrow();
		assertEquals("minecraft:cobblestone", discard.itemId());
		assertEquals(64, discard.kept());
	}

	@Test
	void neverThrowsTheHeldStackOrPartOfAStack() {
		assertTrue(JunkDiscard.choose(inventory(
			new JunkDiscard.Stack(0, "minecraft:netherrack", 64),
			new JunkDiscard.Stack(10, "minecraft:netherrack", 64),
			new JunkDiscard.Stack(11, "minecraft:netherrack", 64)), 0, JUNK::contains).isPresent());
		assertTrue(JunkDiscard.choose(inventory(
			new JunkDiscard.Stack(0, "minecraft:netherrack", 10),
			new JunkDiscard.Stack(10, "minecraft:netherrack", 64),
			new JunkDiscard.Stack(11, "minecraft:netherrack", 64)), 0, JUNK::contains).isEmpty(),
			"a surplus of 10 sits in the held slot and the other stacks are bigger than it");
	}
}
