package ai.moeru.airicraft.agent.llm;

public final class LlmBackendException extends Exception {
	private final LlmFailureType failureType;
	private final long retryAfterMillis;
	private final boolean retryable;

	public LlmBackendException(LlmFailureType failureType, String message) {
		this(failureType, message, null, 0L);
	}

	public LlmBackendException(LlmFailureType failureType, String message, Throwable cause) {
		this(failureType, message, cause, 0L);
	}

	public LlmBackendException(LlmFailureType failureType, String message, Throwable cause, long retryAfterMillis) {
		this(failureType, message, cause, retryAfterMillis, true);
	}

	private LlmBackendException(LlmFailureType failureType, String message, Throwable cause, long retryAfterMillis, boolean retryable) {
		super(message, cause);
		this.failureType = failureType;
		this.retryAfterMillis = retryAfterMillis;
		this.retryable = retryable;
	}

	/** A failure that repeats on every attempt, such as a missing provider configuration: the session does not retry it. */
	public static LlmBackendException permanent(LlmFailureType failureType, String message, Throwable cause) {
		return new LlmBackendException(failureType, message, cause, 0L, false);
	}

	public long retryAfterMillis() { return retryAfterMillis; }

	public boolean retryable() { return retryable; }

	public LlmFailureType failureType() {
		return failureType;
	}
}
