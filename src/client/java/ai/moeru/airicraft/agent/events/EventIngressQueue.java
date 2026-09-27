package ai.moeru.airicraft.agent.events;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Bounded handoff from event producers to a single owning consumer. */
public final class EventIngressQueue<T> {
	private final ArrayBlockingQueue<T> pending;
	private final AtomicInteger dropped = new AtomicInteger();

	public EventIngressQueue(int capacity) {
		pending = new ArrayBlockingQueue<>(capacity);
	}

	public boolean offer(T item) {
		if (pending.offer(item)) return true;
		dropped.incrementAndGet();
		return false;
	}

	public int drain(Consumer<T> onItem) {
		int count = 0;
		T item;
		while ((item = pending.poll()) != null) {
			onItem.accept(item);
			count++;
		}
		return count;
	}

	public int takeDroppedCount() {
		return dropped.getAndSet(0);
	}
}
