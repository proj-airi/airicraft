package ai.moeru.airicraft.agent.llm;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldReadLedgerTest {
	@Test
	void observedPositionExpiresAfterConfiguredToolCalls() {
		WorldReadLedger ledger = new WorldReadLedger(10);
		BlockPos target = new BlockPos(1, 64, 2);

		ledger.recordObserved(List.of(target));

		assertTrue(ledger.isFresh(target));
		assertEquals(10, ledger.freshnessRemaining(target));

		for (int index = 0; index < 10; index++) {
			ledger.advanceToolCall();
		}

		assertTrue(ledger.isFresh(target));
		assertEquals(0, ledger.freshnessRemaining(target));

		ledger.advanceToolCall();

		assertFalse(ledger.isFresh(target));
		assertEquals(-1, ledger.freshnessRemaining(target));
	}
}
