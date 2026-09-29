package ai.moeru.airicraft.agent.perception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoticedMemoryTest {
	@Test void noticesOnceUntilTheTtlExpires() {
		var memory = new NoticedMemory();
		memory.scope("world:overworld");
		assertTrue(memory.notice("block:a", 0, 100));
		assertFalse(memory.notice("block:a", 99, 100));
		assertTrue(memory.noticed("block:a", 50));
		assertTrue(memory.notice("block:a", 100, 100), "an expired notice can be noticed again");
	}

	@Test void evictsTheLeastRecentlyUsedKeyAtCapacity() {
		var memory = new NoticedMemory(2);
		memory.notice("a", 0, 1_000);
		memory.notice("b", 0, 1_000);
		memory.noticed("a", 1);
		memory.notice("c", 1, 1_000);
		assertTrue(memory.noticed("a", 2));
		assertFalse(memory.noticed("b", 2), "b was least recently used");
		assertEquals(2, memory.size());
	}

	@Test void scopesAreSeparateAndSurviveASwitchBack() {
		var memory = new NoticedMemory();
		memory.scope("world:overworld");
		memory.notice("block:a", 0, 1_000);
		memory.scope("world:the_nether");
		assertFalse(memory.noticed("block:a", 1));
		assertTrue(memory.notice("block:a", 1, 1_000));
		memory.scope("world:overworld");
		assertTrue(memory.noticed("block:a", 2), "returning to a dimension keeps what was noticed there");
	}

	@Test void forgetAndClear() {
		var memory = new NoticedMemory();
		memory.notice("a", 0, Long.MAX_VALUE);
		assertTrue(memory.noticed("a", Long.MAX_VALUE - 1));
		memory.forget("a");
		assertFalse(memory.noticed("a", 1));
		memory.notice("a", 0, 10);
		memory.clear();
		assertEquals(0, memory.size());
	}
}
