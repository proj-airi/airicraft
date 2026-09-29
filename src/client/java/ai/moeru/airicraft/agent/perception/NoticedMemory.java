package ai.moeru.airicraft.agent.perception;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What has already been noticed, so each thing becomes a candidate once (spec section 5). Keys are kept per world
 * and dimension scope, in a bounded LRU with a TTL: something seen again long after it was forgotten can be
 * noticed again, while walking back and forth does not repeat it.
 */
public final class NoticedMemory {
	public static final int DEFAULT_CAPACITY = 4096;
	private static final int MAX_SCOPES = 4;

	private final int capacity;
	private final LinkedHashMap<String, LinkedHashMap<String, Long>> scopes = new LinkedHashMap<>(8, .75f, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<String, LinkedHashMap<String, Long>> eldest) {
			return size() > MAX_SCOPES;
		}
	};
	private String scope;

	public NoticedMemory() {
		this(DEFAULT_CAPACITY);
	}

	public NoticedMemory(int capacity) {
		if (capacity < 1) throw new IllegalArgumentException("capacity");
		this.capacity = capacity;
	}

	/** Switches to a world and dimension; returning to an earlier scope keeps what was noticed there. */
	public void scope(String worldAndDimension) {
		scope = worldAndDimension;
	}

	/** Records {@code key} and returns true when it was not noticed in this scope, or its notice expired. */
	public boolean notice(String key, long tick, long ttlTicks) {
		var entries = entries();
		Long expiresAt = entries.get(key);
		if (expiresAt != null && tick < expiresAt) return false;
		entries.put(key, ttlTicks == Long.MAX_VALUE ? Long.MAX_VALUE : tick + ttlTicks);
		return true;
	}

	public boolean noticed(String key, long tick) {
		Long expiresAt = entries().get(key);
		return expiresAt != null && tick < expiresAt;
	}

	/** Forget one key, for example when salience could not decide on it and it should be offered again. */
	public void forget(String key) {
		entries().remove(key);
	}

	public int size() {
		return entries().size();
	}

	public void clear() {
		scopes.clear();
		scope = null;
	}

	private LinkedHashMap<String, Long> entries() {
		String key = scope == null ? "" : scope;
		return scopes.computeIfAbsent(key, ignored -> new LinkedHashMap<>(64, .75f, true) {
			@Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
				return size() > capacity;
			}
		});
	}
}
