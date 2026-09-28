package ai.moeru.airicraft.rules;

/** A rule module failed to load or to complete a step. {@code code} is stable; the message is for people. */
public final class RuleException extends Exception {
	private final String code;

	public RuleException(String code, String message) {
		super(message);
		this.code = code;
	}

	public RuleException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
