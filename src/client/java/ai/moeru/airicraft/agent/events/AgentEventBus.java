package ai.moeru.airicraft.agent.events;

import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates and appends events before synchronous, sequence-ordered subscriber delivery. Delivery happens
 * outside the bus lock, so a slow subscriber never blocks readers such as the dashboard or bridge.
 */
public final class AgentEventBus implements EventStream {
	private static final Logger LOGGER = LoggerFactory.getLogger(AgentEventBus.class);
	// After saturation all validation warnings are suppressed; violation counters still advance.
	private static final int MAX_WARNING_KEYS = 256;
	// Reject before append, so every accepted event retains its subscriber delivery.
	private static final int MAX_PENDING_EVENTS = 512;
	static final String TEST_SOURCE = "test";

	private final EventCatalog catalog;
	private final AgentEventLog log;
	private final LongSupplier wallClockMs;
	private final boolean strict;
	private final AgentDebugRecorder recorder;
	private final Set<WarningKey> warningKeys = new HashSet<>();
	private final ArrayDeque<SemanticEvent> pending = new ArrayDeque<>();
	private final List<Subscriber> subscribers = new ArrayList<>();
	private Thread ownerThread;
	private boolean dispatching;
	private boolean warningsSaturated;
	private long nextSubscriberId;
	private long undeclared;
	private long unknownSource;
	private long offThread;
	private long subscriberFailures;

	public AgentEventBus(EventCatalog catalog, AgentEventLog log, LongSupplier wallClockMs, boolean strict) {
		this(catalog, log, wallClockMs, strict, new AgentDebugRecorder());
	}

	public AgentEventBus(EventCatalog catalog, AgentEventLog log, LongSupplier wallClockMs, boolean strict, AgentDebugRecorder recorder) {
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.log = Objects.requireNonNull(log, "log");
		this.wallClockMs = Objects.requireNonNull(wallClockMs, "wallClockMs");
		this.strict = strict;
		this.recorder = Objects.requireNonNull(recorder, "recorder");
	}

	/**
	 * Validates, appends and queues under the bus lock, then delivers outside it. Subscribers run on the
	 * publishing thread, or on the thread already delivering, and never hold the lock that readers take.
	 */
	@Override
	public SemanticEvent publish(long tick, String type, Map<String, Object> payload, String source, EventCause cause) {
		return publish(tick, wallClockMs.getAsLong(), type, payload, source, cause);
	}

	/** Derived events retain their source's observation time through the same validated delivery path. */
	SemanticEvent publish(long tick, long timestampMs, String type, Map<String, Object> payload, String source, EventCause cause) {
		SemanticEvent event;
		synchronized (this) {
			Objects.requireNonNull(type, "type");
			if (ownerThread != null && ownerThread != Thread.currentThread()) {
				offThread++;
				if (strict) throw new IllegalStateException("Event published outside the owner thread: " + type);
			}
			EventTypeSpec spec = catalog.find(type);
			if (spec == null) {
				undeclared++;
				validationFailure(new WarningKey(type, null), "Undeclared event type: " + type);
			} else if (source == null || !acceptsProducer(spec, source)) {
				unknownSource++;
				validationFailure(new WarningKey(type, source), "Undeclared producer for " + type + ": " + source);
			}
			if (pending.size() == MAX_PENDING_EVENTS) {
				throw new IllegalStateException("Nested event delivery queue is full");
			}
			event = log.append(tick, timestampMs, type, payload, source, cause);
			pending.addLast(event);
			// The thread already delivering, including this one when nested, delivers this event in order.
			if (dispatching) return event;
			dispatching = true;
		}
		dispatchPending();
		return event;
	}

	/**
	 * Test injection ({@code appendEventForTests}) may publish any declared type as {@value #TEST_SOURCE}.
	 * Only strict mode accepts it; strict mode is enabled solely by the Gradle test task, so a production
	 * publish claiming the test source is counted and warned as an unknown producer.
	 */
	private boolean acceptsProducer(EventTypeSpec spec, String source) {
		return spec.producers().contains(source) || strict && TEST_SOURCE.equals(source);
	}

	private void validationFailure(WarningKey key, String message) {
		if (strict) throw new IllegalArgumentException(message);
		if (warningsSaturated || warningKeys.contains(key)) return;
		if (warningKeys.size() == MAX_WARNING_KEYS) {
			warningsSaturated = true;
			LOGGER.warn("Event validation warning history is full; suppressing further validation warnings");
			return;
		}
		warningKeys.add(key);
		LOGGER.warn(message);
	}

	/** Runs without the bus lock; the lock guards only the queue, the subscriber snapshot and counters. */
	private void dispatchPending() {
		boolean drained = false;
		try {
			while (true) {
				SemanticEvent event;
				List<Subscriber> snapshot;
				synchronized (this) {
					event = pending.pollFirst();
					if (event == null) {
						// Cleared together with the empty check, so a concurrent publish either sees
						// dispatching and is delivered here, or starts its own delivery.
						dispatching = false;
						drained = true;
						return;
					}
					// Subscription changes take effect on the next event, including queued nested events.
					snapshot = List.copyOf(subscribers);
				}
				for (Subscriber subscriber : snapshot) {
					try {
						if (subscriber.types().test(event.type())) subscriber.consumer().accept(event);
					} catch (RuntimeException failure) {
						synchronized (this) {
							subscriberFailures++;
						}
						recorder.recordEventBusSubscriberFailure(event, subscriber.id(), failure);
					}
				}
			}
		} finally {
			if (!drained) {
				synchronized (this) {
					dispatching = false;
				}
			}
		}
	}

	/** Delivery uses a subscription snapshot per event; closing is idempotent. */
	public synchronized Subscription subscribe(Predicate<String> types, Consumer<SemanticEvent> subscriber) {
		var entry = new Subscriber(++nextSubscriberId, Objects.requireNonNull(types, "types"), Objects.requireNonNull(subscriber, "subscriber"));
		subscribers.add(entry);
		return () -> {
			synchronized (AgentEventBus.this) {
				subscribers.remove(entry);
			}
		};
	}

	public synchronized void bindOwnerThread(Thread thread) {
		ownerThread = Objects.requireNonNull(thread, "thread");
	}

	public synchronized AgentEventBusStats stats() {
		return new AgentEventBusStats(undeclared, unknownSource, offThread, subscriberFailures);
	}

	@Override
	public synchronized SemanticEventQueryResult query(Long sinceSeqNo) {
		return log.query(sinceSeqNo);
	}

	@Override
	public synchronized long latestSeqNo() {
		return log.latestSeqNo();
	}

	@Override
	public synchronized boolean containsType(String type) {
		return log.containsType(type);
	}

	@Override
	public synchronized long droppedCount() {
		return log.droppedCount();
	}

	@FunctionalInterface
	public interface Subscription extends AutoCloseable {
		@Override void close();
	}

	public record AgentEventBusStats(long undeclared, long unknownSource, long offThread, long subscriberFailures) {}

	private record WarningKey(String type, String source) {}
	private record Subscriber(long id, Predicate<String> types, Consumer<SemanticEvent> consumer) {}
}
