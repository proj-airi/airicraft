package ai.moeru.airicraft.agent.work;

import ai.moeru.airicraft.agent.job.ActiveJobType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReadableIdsTest {
	@Test
	void countsPerPrefix() {
		String first = ReadableIds.next("readable-ids-test-a");
		String second = ReadableIds.next("readable-ids-test-a");
		assertEquals("readable-ids-test-a-1", first);
		assertEquals("readable-ids-test-a-2", second);
		assertEquals("readable-ids-test-b-1", ReadableIds.next("readable-ids-test-b"));
	}

	@Test
	void continuesAfterSavedIds() {
		assertEquals("readable-ids-test-c-8",
			ReadableIds.nextAfter("readable-ids-test-c", List.of("readable-ids-test-c-7", "0c2f9a1e-uuid", "other-40")));
		assertEquals("readable-ids-test-c-9", ReadableIds.nextAfter("readable-ids-test-c", List.of("readable-ids-test-c-2")));
		assertEquals("readable-ids-test-c-10", ReadableIds.next("readable-ids-test-c"));
	}

	@Test
	void kindIsHyphenatedLowerCase() {
		assertEquals("collect-smelted-items", ReadableIds.kind(ActiveJobType.COLLECT_SMELTED_ITEMS));
	}
}
