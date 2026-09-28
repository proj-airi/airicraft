package ai.moeru.airicraft.agent.attention;

import java.util.List;
import java.util.Objects;

/**
 * Runs the self-wake generators (Cortico's {@code onIdle}) in priority order: goal continuation, then idle
 * think. A generator that handles the tick, by waking or by deliberately holding, resets every generator after
 * it, so lower-priority self wakes restart their own cadence. When the agent is not eligible for self wakes at
 * all, every generator resets.
 */
public final class IdleHook {
	/** One self-wake source. {@code poll} returns whether it handled this tick. */
	public interface Generator {
		boolean poll(long tick);

		default void reset() {
		}
	}

	public record Named(String id, Generator generator) {
		public Named {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(generator, "generator");
		}
	}

	private IdleHook() {
	}

	public static void run(long tick, boolean eligible, List<Named> generators) {
		if (!eligible) {
			generators.forEach(named -> named.generator().reset());
			return;
		}
		for (int index = 0; index < generators.size(); index++) {
			if (generators.get(index).generator().poll(tick)) {
				for (var lower : generators.subList(index + 1, generators.size())) lower.generator().reset();
				return;
			}
		}
	}
}
