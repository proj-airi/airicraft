package ai.moeru.airicraft.agent.llm;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

public final class PlannerContextPolicy {
	/** Background compaction starts at this share of the token budget so its summary lands before the budget does. */
	static final double EARLY_COMPACTION_RATIO = 0.75;
	private static final long TIME_BEACON_WINDOW_MILLIS = 30L * 60L * 1000L;
	private static final DateTimeFormatter TIME_BEACON_FORMATTER = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
		.withLocale(Locale.ENGLISH);

	private PlannerContextPolicy() {
	}

	public static boolean shouldInjectTimeBeacon(long lastTimeBeaconAtMs, long nowMs) {
		return lastTimeBeaconAtMs < 0L || nowMs - lastTimeBeaconAtMs >= TIME_BEACON_WINDOW_MILLIS;
	}

	public static int earlyCompactionTokens(int budgetTokens) {
		return Math.max(1, (int) (budgetTokens * EARLY_COMPACTION_RATIO));
	}

	public static boolean shouldCompact(LlmUsageSnapshot usage, int thresholdTokens) {
		return usage != null
			&& usage.hasPromptTokens()
			&& usage.promptTokens() != null
			&& usage.promptTokens().intValue() >= thresholdTokens;
	}

	public static String timeBeaconText(long nowMs, ZoneId zoneId) {
		String formatted = TIME_BEACON_FORMATTER.format(Instant.ofEpochMilli(nowMs).atZone(zoneId));
		return "It is now around " + formatted + " local time (" + zoneId + ").";
	}
}
