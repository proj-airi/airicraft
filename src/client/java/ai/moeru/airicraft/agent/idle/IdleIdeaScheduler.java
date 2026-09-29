package ai.moeru.airicraft.agent.idle;

import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;

import java.util.List;
import java.util.Optional;

/**
 * Idle-think cadence. Idle time is measured in agent ticks (20 per second), so a tick-debug pause or any other
 * stretch of wall time without ticks never counts as idle time (D7). {@code nowMs} only stamps the trigger.
 */
public final class IdleIdeaScheduler {
	private static final long TICKS_PER_SECOND = 20L;

	private volatile IdleIdeasConfig config;
	private final List<String> interests;
	private long idleStartTick = -1L;
	private long lastFireTick = -1L;

	public IdleIdeaScheduler(IdleIdeasConfig config) {
		this(config, List.of());
	}

	/** Interests come from the character card and make idle turns the character's free time. */
	public IdleIdeaScheduler(IdleIdeasConfig config, List<String> interests) {
		this.config = config == null ? IdleIdeasConfig.defaults() : config;
		this.interests = interests == null ? List.of() : List.copyOf(interests);
	}

	public synchronized void updateConfig(IdleIdeasConfig nextConfig) {
		this.config = nextConfig == null ? IdleIdeasConfig.defaults() : nextConfig;
	}

	public synchronized void reset() {
		idleStartTick = -1L;
		lastFireTick = -1L;
	}

	public synchronized void recordActivity() {
		idleStartTick = -1L;
	}

	public synchronized Optional<PlannerTrigger> tick(boolean activeJobIdle, long tickCount, long nowMs) {
		IdleIdeasConfig current = config;
		if (!current.enabled() || current.initialDelaySeconds() <= 0 || current.cooldownSeconds() <= 0 || nothingToSuggest(current)) {
			idleStartTick = -1L;
			return Optional.empty();
		}
		if (!activeJobIdle) {
			idleStartTick = -1L;
			return Optional.empty();
		}
		if (idleStartTick < 0L) {
			idleStartTick = tickCount;
		}
		long idleTicks = Math.max(0L, tickCount - idleStartTick);
		long ticksSinceLastFire = lastFireTick < 0L ? Long.MAX_VALUE : Math.max(0L, tickCount - lastFireTick);
		if (idleTicks < current.initialDelaySeconds() * TICKS_PER_SECOND) {
			return Optional.empty();
		}
		if (ticksSinceLastFire < current.cooldownSeconds() * TICKS_PER_SECOND) {
			return Optional.empty();
		}
		lastFireTick = tickCount;
		return Optional.of(buildTrigger(current.ideas(), interests, tickCount, nowMs));
	}

	public synchronized Optional<PlannerTrigger> fireNow(long tickCount, long nowMs) {
		IdleIdeasConfig current = config;
		if (nothingToSuggest(current)) {
			return Optional.empty();
		}
		if (idleStartTick < 0L) {
			idleStartTick = tickCount;
		}
		lastFireTick = tickCount;
		return Optional.of(buildTrigger(current.ideas(), interests, tickCount, nowMs));
	}

	private boolean nothingToSuggest(IdleIdeasConfig current) {
		return current.ideas().isEmpty() && interests.isEmpty();
	}

	private static PlannerTrigger buildTrigger(List<String> ideas, List<String> interests, long tickCount, long nowMs) {
		StringBuilder builder = new StringBuilder(768);
		builder.append("IDLE THINK: Nothing is running and nobody has asked you for anything lately. This is your free time. ")
			.append("Do one small thing you would genuinely enjoy or care about right now, in character: start it with an action tool, ")
			.append("or say something to a nearby player if you have something worth saying. ")
			.append("Ask the player a question only when you really want their input, and never in back-to-back idle turns.\n");
		if (!interests.isEmpty()) {
			builder.append("Things you enjoy (pick what fits the moment; this is not a checklist):\n");
			for (String interest : interests) {
				builder.append("- ").append(interest).append('\n');
			}
		}
		if (!ideas.isEmpty()) {
			builder.append("Useful survival progress you can also choose (only when its preconditions are met now):\n");
			for (String idea : ideas) {
				builder.append("- ").append(idea).append('\n');
			}
			builder.append("If torches would improve mining readiness and none are available, consider smelting a log into minecraft:charcoal, then crafting minecraft:torch from charcoal and sticks.\n");
		}
		builder.append("Do not read these lists back to the player.\n");
		// With a decision context the prose is not sent; the lists become the idle_think hint.
		var fields = new com.google.gson.JsonObject();
		var interestList = new com.google.gson.JsonArray();
		interests.forEach(interestList::add);
		var ideaList = new com.google.gson.JsonArray();
		ideas.forEach(ideaList::add);
		fields.add("interests", interestList);
		fields.add("ideas", ideaList);
		return new PlannerTrigger(0L, PlannerTriggerType.IDLE_THINK, "self", builder.toString(), tickCount, nowMs, null, null, fields);
	}
}
