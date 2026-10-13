package ai.moeru.airicraft.agent.durability;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Tool wear as the planner sees it: coarse durability in {@code current}, an event when the last tool of a kind runs
 * low or a tool breaks, and a forecast when mining work asks for more blocks than the eligible tools can still break.
 * Pure; the Minecraft adapter supplies {@link ToolStack}s.
 */
public final class ToolWear {
	/** Low means at most a tenth of the maximum or this many uses, whichever is more. */
	static final int LOW_MIN_USES = 8;
	/** A low stack that vanishes with at most this many uses left broke; a larger one was moved or dropped. */
	static final int BREAK_WINDOW = 4;

	private ToolWear() {}

	public static boolean low(int remaining, int maximum) {
		return remaining <= Math.max(LOW_MIN_USES, (maximum + 9) / 10);
	}

	/** Durability in 10% steps so a mining job does not change the observation on every block; exact once low. */
	public static String describe(int remaining, int maximum) {
		if (low(remaining, maximum)) return "low, " + remaining + "/" + maximum + " uses left";
		return (remaining * 10 / Math.max(1, maximum)) * 10 + "%";
	}

	/** The {@code current.durability} entries: damaged items only, inventory order. */
	public static List<Map<String, Object>> present(List<ToolStack> stacks) {
		var entries = new ArrayList<Map<String, Object>>();
		for (ToolStack stack : stacks) {
			if (stack.remaining() >= stack.maximum()) continue;
			var entry = new LinkedHashMap<String, Object>();
			entry.put("itemId", stack.itemId());
			entry.put("slot", stack.slot());
			entry.put("durability", describe(stack.remaining(), stack.maximum()));
			entries.add(entry);
		}
		return entries;
	}

	/**
	 * A warning when {@code blocksNeeded} exceeds the uses left on {@code eligible}, the damageable tools that can
	 * harvest the targets; empty when they suffice, when an unbreakable tool is eligible ({@code unbreakableEligible}),
	 * or when nothing is eligible (the job reports that itself).
	 */
	public static Optional<String> shortfall(List<ToolStack> eligible, boolean unbreakableEligible, int blocksNeeded) {
		if (unbreakableEligible || eligible.isEmpty() || blocksNeeded <= 0) return Optional.empty();
		int uses = eligible.stream().mapToInt(ToolStack::remaining).sum();
		if (uses >= blocksNeeded) return Optional.empty();
		String tools = eligible.stream().map(stack -> stack.itemId() + " " + stack.remaining() + "/" + stack.maximum())
			.collect(Collectors.joining(", "));
		return Optional.of("the tools that can harvest these blocks have about " + uses + " uses left (" + tools + ") for "
			+ blocksNeeded + " blocks; when they break the job fails with missing_suitable_tool. Craft a spare tool first or lower the quantity.");
	}

	/** One wear event: {@code tool.durability_low} or {@code tool.broken}, with its payload. */
	public record Event(String type, Map<String, Object> payload) {}

	/** Compares successive inventory readings. */
	public static final class Tracker {
		private Map<Integer, ToolStack> previous = Map.of();
		private boolean primed;

		public void reset() {
			previous = Map.of();
			primed = false;
		}

		public List<Event> update(List<ToolStack> stacks) {
			var bySlot = new HashMap<Integer, ToolStack>();
			for (ToolStack stack : stacks) bySlot.put(stack.slot(), stack);
			if (!primed) {
				primed = true;
				previous = bySlot;
				return List.of();
			}
			var events = new ArrayList<Event>();
			for (ToolStack stack : stacks) {
				if (!stack.tool() || !low(stack.remaining(), stack.maximum())) continue;
				ToolStack before = previous.get(stack.slot());
				// Only a stack that wore down in place crossed the line; one picked up or moved here was already low.
				if (before == null || !before.itemId().equals(stack.itemId()) || low(before.remaining(), before.maximum())
					|| before.remaining() <= stack.remaining()) continue;
				long spares = spares(stacks, stack.kind(), stack.slot(), true);
				if (spares > 0) continue;
				events.add(new Event("tool.durability_low", payload(stack, spares)));
			}
			for (ToolStack before : previous.values()) {
				if (!before.tool() || before.remaining() > BREAK_WINDOW) continue;
				ToolStack now = bySlot.get(before.slot());
				if (now != null && now.itemId().equals(before.itemId())) continue;
				if (count(stacks, before.itemId()) >= count(previous.values(), before.itemId())) continue;
				var payload = payload(before, spares(stacks, before.kind(), -1, false));
				payload.remove("remaining");
				events.add(new Event("tool.broken", payload));
			}
			previous = bySlot;
			return events;
		}

		private static Map<String, Object> payload(ToolStack stack, long spares) {
			var payload = new LinkedHashMap<String, Object>();
			payload.put("itemId", stack.itemId());
			payload.put("toolKind", stack.kind());
			payload.put("slot", stack.slot());
			payload.put("remaining", stack.remaining());
			payload.put("maximum", stack.maximum());
			payload.put("sparesOfKind", spares);
			return payload;
		}

		/** Other tools of this kind; {@code healthyOnly} leaves out ones that are low themselves. */
		private static long spares(List<ToolStack> stacks, String kind, int exceptSlot, boolean healthyOnly) {
			return stacks.stream().filter(stack -> stack.kind().equals(kind) && stack.slot() != exceptSlot
				&& !(healthyOnly && low(stack.remaining(), stack.maximum()))).count();
		}

		private static long count(java.util.Collection<ToolStack> stacks, String itemId) {
			return stacks.stream().filter(stack -> stack.itemId().equals(itemId)).count();
		}
	}
}
