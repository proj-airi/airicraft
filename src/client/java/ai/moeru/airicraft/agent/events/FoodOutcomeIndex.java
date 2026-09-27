package ai.moeru.airicraft.agent.events;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.function.Consumer;

/** Food outcomes by sequence, independent of event-log eviction. */
public final class FoodOutcomeIndex implements Consumer<SemanticEvent> {
	private final int capacity;
	private final ArrayDeque<SemanticEvent> outcomes = new ArrayDeque<>();

	public FoodOutcomeIndex(int capacity) {
		if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
		this.capacity = capacity;
	}

	@Override
	public void accept(SemanticEvent event) {
		if (!event.type().equals("food.eaten") && !event.type().equals("food.eat_failed")) return;
		if (outcomes.size() == capacity) outcomes.removeFirst();
		outcomes.addLast(event);
	}

	public Optional<SemanticEvent> firstAfter(long seqNo) {
		for (var event : outcomes) {
			if (event.seqNo() > seqNo) return Optional.of(event);
		}
		return Optional.empty();
	}

	public void clear() {
		outcomes.clear();
	}
}
