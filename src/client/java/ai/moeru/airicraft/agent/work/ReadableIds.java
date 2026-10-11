package ai.moeru.airicraft.agent.work;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Short ids the planner can read and copy back, such as {@code craft-recipe-3} or {@code graph-12}.
 * Counters are per prefix and process-wide, so an id stays unique across reloads and world changes.
 * Ids that outlive the process (saved goals, saved blueprints) continue after the highest saved number.
 */
public final class ReadableIds {
	private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

	private ReadableIds() { }

	public static String next(String prefix) {
		return prefix + "-" + counter(prefix).incrementAndGet();
	}

	/** The next id for {@code prefix} that is above every number already used in {@code taken}. */
	public static String nextAfter(String prefix, Collection<String> taken) {
		long highest = 0L;
		for (String id : taken) highest = Math.max(highest, number(prefix, id));
		AtomicLong counter = counter(prefix);
		long finalHighest = highest;
		return prefix + "-" + counter.updateAndGet(current -> Math.max(current, finalHighest) + 1L);
	}

	/** Lower-case, hyphenated form of an enum constant: {@code CRAFT_RECIPE} becomes {@code craft-recipe}. */
	public static String kind(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	private static AtomicLong counter(String prefix) {
		return COUNTERS.computeIfAbsent(prefix, ignored -> new AtomicLong());
	}

	private static long number(String prefix, String id) {
		if (id == null || !id.startsWith(prefix + "-")) return 0L;
		try {
			return Long.parseLong(id.substring(prefix.length() + 1));
		} catch (NumberFormatException ignored) {
			return 0L;
		}
	}
}
