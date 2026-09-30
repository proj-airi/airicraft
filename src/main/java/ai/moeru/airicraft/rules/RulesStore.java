package ai.moeru.airicraft.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The planner's edits of the rule modules: per hook, the base module (the bundled one or the operator's override) and
 * a bounded history of versions the planner activated. Session-local; {@link #reset} drops a hook's edits.
 *
 * <p>Version numbers only grow. A rollback or an automatic revert is a new version that copies an older one, so the
 * history reads as a log. Every version names the version to fall back to if it fails ({@code fallback}, 0 for the
 * base module), which is how the previous good version is found without walking a chain of reverts.
 */
public final class RulesStore implements RuleRevert {
	public static final int HISTORY = 8;
	public static final int MAX_UPDATES = 6;
	public static final long UPDATE_WINDOW_TICKS = 12_000;
	public static final String ORIGIN_PREFIX = "planner:";

	public enum Kind { UPDATE, ROLLBACK, AUTO_REVERT }

	/**
	 * @param source the module source, or {@code null} when the version activated the base module
	 * @param replay the dry-run summary that accepted it, or {@code null}
	 */
	public record Version(int number, Kind kind, String sha, String source, String reason, long tick, int fallback,
		Map<String, Object> replay) {
		public boolean base() {
			return source == null;
		}
	}

	private static final class Slot {
		RuleModule base;
		int next = 1;
		final ArrayList<Version> history = new ArrayList<>();
		final ArrayDeque<Long> updates = new ArrayDeque<>();
	}

	private final EnumMap<RuleModule.Hook, Slot> slots = new EnumMap<>(RuleModule.Hook.class);

	public RulesStore() {
		for (RuleModule.Hook hook : RuleModule.Hook.values()) {
			Slot slot = new Slot();
			slot.base = RuleModule.bundled(hook);
			slots.put(hook, slot);
		}
	}

	/** Sets the base module and forgets the planner's edits of this hook. */
	public synchronized void reset(RuleModule base) {
		Slot slot = slots.get(base.hook());
		slot.base = Objects.requireNonNull(base, "base");
		slot.history.clear();
		slot.updates.clear();
	}

	public synchronized RuleModule base(RuleModule.Hook hook) {
		return slots.get(hook).base;
	}

	/** The active version, or empty while the base module is active because the planner has not edited. */
	public synchronized Optional<Version> active(RuleModule.Hook hook) {
		List<Version> history = slots.get(hook).history;
		return history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
	}

	/** 0 while the base module is active. */
	public synchronized int activeNumber(RuleModule.Hook hook) {
		return active(hook).filter(version -> !version.base()).map(Version::number).orElse(0);
	}

	public synchronized RuleModule activeModule(RuleModule.Hook hook) {
		Optional<Version> active = active(hook);
		return active.isPresent() && !active.get().base() ? module(hook, active.get()) : slots.get(hook).base;
	}

	public synchronized List<Version> history(RuleModule.Hook hook) {
		return List.copyOf(slots.get(hook).history);
	}

	public synchronized Optional<Version> find(RuleModule.Hook hook, int number) {
		return slots.get(hook).history.stream().filter(version -> version.number() == number).findFirst();
	}

	/** The module a version runs as; its origin names the hook and number, so a failure identifies the version. */
	public static RuleModule module(RuleModule.Hook hook, Version version) {
		return new RuleModule(ORIGIN_PREFIX + hook.name().toLowerCase() + "/v" + version.number(),
			Objects.requireNonNull(version.source(), "base versions have no source"), hook);
	}

	/** The version number a planner module's origin names, or 0 for any other module. */
	public static int versionOf(RuleModule module) {
		String origin = module.origin();
		if (!origin.startsWith(ORIGIN_PREFIX)) return 0;
		int marker = origin.lastIndexOf("/v");
		try {
			return marker < 0 ? 0 : Integer.parseInt(origin.substring(marker + 2));
		}
		catch (NumberFormatException exception) {
			return 0;
		}
	}

	/** The number the next accepted version will get. */
	public synchronized int nextNumber(RuleModule.Hook hook) {
		return slots.get(hook).next;
	}

	/** The tick from which another accepted edit is allowed, or {@code -1} if one is allowed now. */
	public synchronized long retryAtTick(RuleModule.Hook hook, long tick) {
		ArrayDeque<Long> updates = slots.get(hook).updates;
		while (!updates.isEmpty() && tick - updates.peekFirst() >= UPDATE_WINDOW_TICKS) updates.removeFirst();
		return updates.size() < MAX_UPDATES ? -1 : updates.peekFirst() + UPDATE_WINDOW_TICKS;
	}

	/**
	 * Records an accepted edit as the new active version. {@code source} is {@code null} to activate the base module.
	 * Edits by the planner (not automatic reverts) count against the rate limit.
	 */
	public synchronized Version append(RuleModule.Hook hook, Kind kind, String source, String reason, long tick, int fallback,
		Map<String, Object> replay) {
		Slot slot = slots.get(hook);
		String sha = source == null ? slot.base.sha() : new RuleModule("v", source, hook).sha();
		var version = new Version(slot.next++, kind, sha, source, reason, tick, fallback, replay == null ? null : Map.copyOf(replay));
		slot.history.add(version);
		while (slot.history.size() > HISTORY) slot.history.removeFirst();
		if (kind != Kind.AUTO_REVERT) slot.updates.addLast(tick);
		return version;
	}

	/** A failing planner version reverts to the version it replaced, or to the base module. */
	@Override
	public synchronized Result revert(RuleModule failed, long tick) {
		RuleModule.Hook hook = failed.hook();
		int number = versionOf(failed);
		Optional<Version> failedVersion = number == 0 ? Optional.empty() : find(hook, number);
		if (failedVersion.isEmpty()) {
			// Not a planner version (an operator override, or one already superseded): the base failed or is stale.
			RuleModule bundled = RuleModule.bundled(hook);
			if (!failed.bundled() && number == 0) reset(bundled);
			return new Result(number == 0 ? bundled : activeModule(hook), number, number == 0 ? 0 : activeNumber(hook));
		}
		Version from = failedVersion.get();
		Optional<Version> target = from.fallback() == 0 ? Optional.empty() : find(hook, from.fallback()).filter(version -> !version.base());
		String reason = "automatic revert from v" + from.number();
		if (target.isPresent()) {
			Version restored = append(hook, Kind.AUTO_REVERT, target.get().source(), reason, tick, target.get().fallback(), null);
			return new Result(module(hook, restored), from.number(), restored.number());
		}
		append(hook, Kind.AUTO_REVERT, null, reason, tick, 0, null);
		return new Result(slots.get(hook).base, from.number(), 0);
	}
}
