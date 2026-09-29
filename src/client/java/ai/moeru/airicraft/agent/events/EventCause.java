package ai.moeru.airicraft.agent.events;

public record EventCause(Kind kind, String ref) {
	public enum Kind { EVENT, TOOL_CALL, WORK, GENERATION }

	public static EventCause event(long seqNo) {
		return new EventCause(Kind.EVENT, Long.toString(seqNo));
	}

	public static EventCause toolCall(String id) {
		return new EventCause(Kind.TOOL_CALL, id);
	}

	public static EventCause work(String workId) {
		return new EventCause(Kind.WORK, workId);
	}

	public static EventCause generation(long generation) {
		return new EventCause(Kind.GENERATION, Long.toString(generation));
	}
}
