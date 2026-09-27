package ai.moeru.airicraft.agent.events;

public interface EventView {
	SemanticEventQueryResult query(Long sinceSeqNo);
	long latestSeqNo();
	boolean containsType(String type);
	long droppedCount();
}
