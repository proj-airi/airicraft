package ai.moeru.airicraft.agent.tasks;

import com.google.gson.JsonObject;

/** The longest the body waits in its night shelter before climbing out, in ticks; morning ends the wait sooner. */
public record ShelterForNightStepArgs(int maxWaitTicks) {
	/** Dusk (11,000) to just past sunrise (24,300) is 13,300 ticks; the cap leaves a little over that. */
	public static final int MAX_WAIT_TICKS = 14_000;
	public static final int MIN_WAIT_TICKS = 20;

	public ShelterForNightStepArgs {
		if (maxWaitTicks < MIN_WAIT_TICKS || maxWaitTicks > MAX_WAIT_TICKS)
			throw new IllegalArgumentException("maxWaitTicks must be " + MIN_WAIT_TICKS + ".." + MAX_WAIT_TICKS);
	}

	public static ShelterForNightStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) if (!key.equals("maxWaitTicks")) throw new IllegalArgumentException("unknown argument: " + key);
		var value = args.get("maxWaitTicks");
		if (value == null || value.isJsonNull()) return new ShelterForNightStepArgs(MAX_WAIT_TICKS);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("maxWaitTicks must be an integer");
		try { return new ShelterForNightStepArgs(value.getAsBigDecimal().intValueExact()); }
		catch (ArithmeticException ex) { throw new IllegalArgumentException("maxWaitTicks must be an integer"); }
	}
}
