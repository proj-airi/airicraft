package ai.moeru.airicraft.agent.events;

import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventBusTest {
	private static final String TYPE = "task.started";
	private static final String SOURCE = "EmbodiedAgentRuntime";

	@Test
	void strictValidationRejectsBeforeAppendAndCountsEachViolation() {
		var bus = bus(true);
		assertThrows(IllegalArgumentException.class, () -> bus.publish(1, "missing.type", Map.of(), SOURCE, null));
		assertThrows(IllegalArgumentException.class, () -> bus.publish(1, TYPE, Map.of(), "wrong", null));
		assertEquals(0, bus.latestSeqNo());
		assertEquals(1, bus.stats().undeclared());
		assertEquals(1, bus.stats().unknownSource());
	}

	@Test
	void lenientValidationPublishesAndCountsEveryViolation() {
		var bus = bus(false);
		bus.publish(1, "missing.type", Map.of(), SOURCE, null);
		bus.publish(2, "missing.type", Map.of(), SOURCE, null);
		bus.publish(3, TYPE, Map.of(), "wrong", null);
		assertEquals(3, bus.query(null).events().size());
		assertEquals(2, bus.stats().undeclared());
		assertEquals(1, bus.stats().unknownSource());
	}

	@Test
	void prefixTypeValidatesItsDeclaredProducer() {
		var bus = bus(true);
		assertEquals("interaction.chest_opened", bus.from("InteractionLogbookRecorder")
			.publish(7, "interaction.chest_opened", Map.of()).type());
		assertThrows(IllegalArgumentException.class, () -> bus.from(SOURCE).publish(7, "interaction.chest_opened", Map.of()));
	}

	@Test
	void boundPublisherStampsInjectedTimeSourceAndOptionalCause() {
		var bus = bus(true);
		var publisher = bus.from(SOURCE);
		var first = publisher.publish(10, TYPE, Map.of("task", "mine"));
		var second = publisher.publish(11, TYPE, Map.of(), EventCause.event(first.seqNo()));
		assertEquals(1234, first.timestampMs());
		assertEquals(10, first.tick());
		assertEquals(SOURCE, first.source());
		assertEquals(Map.of("task", "mine"), first.payload());
		assertNull(first.cause());
		assertEquals(EventCause.event(1), second.cause());
	}

	@Test
	void nestedPublishesAppendImmediatelyButDispatchAfterAllCurrentSubscribers() {
		var bus = bus(true);
		var seen = new ArrayList<String>();
		bus.subscribe(type -> true, event -> {
			assertTrue(bus.latestSeqNo() >= event.seqNo());
			seen.add("first:" + event.seqNo());
			if (event.seqNo() == 1) {
				assertEquals(2, bus.from(SOURCE).publish(2, TYPE, Map.of()).seqNo());
				assertEquals(2, bus.latestSeqNo());
			}
		});
		bus.subscribe(type -> true, event -> seen.add("second:" + event.seqNo()));
		bus.from(SOURCE).publish(1, TYPE, Map.of());
		assertEquals(List.of("first:1", "second:1", "first:2", "second:2"), seen);
	}

	@Test
	void subscriptionFiltersAndCloseControlDelivery() {
		var bus = bus(true);
		var seen = new ArrayList<Long>();
		var subscription = bus.subscribe(TYPE::equals, event -> seen.add(event.seqNo()));
		bus.from(SOURCE).publish(1, "task.completed", Map.of());
		bus.from(SOURCE).publish(2, TYPE, Map.of());
		subscription.close();
		subscription.close();
		bus.from(SOURCE).publish(3, TYPE, Map.of());
		assertEquals(List.of(2L), seen);
	}

	@Test
	void subscriberAndPredicateFailuresDoNotEscapeOrBlockLaterSubscribers() {
		var recorder = new AgentDebugRecorder();
		var bus = new AgentEventBus(EventCatalog.defaults(), new AgentEventLog(4), () -> 1234, true, recorder);
		var seen = new ArrayList<Long>();
		bus.subscribe(type -> true, event -> { throw new IllegalStateException("subscriber broke"); });
		bus.subscribe(type -> { throw new IllegalArgumentException("filter broke"); }, event -> fail());
		bus.subscribe(type -> true, event -> seen.add(event.seqNo()));
		assertDoesNotThrow(() -> bus.from(SOURCE).publish(9, TYPE, Map.of()));
		assertEquals(List.of(1L), seen);
		assertEquals(2, bus.stats().subscriberFailures());
		var entries = recorder.timelineTail();
		assertEquals(2, entries.size());
		assertEquals("event_bus", entries.getFirst().domain());
		assertEquals("subscriber_failed", entries.getFirst().action());
		assertEquals(9, entries.getFirst().tick());
		assertEquals(1234, entries.getFirst().timestampMs());
		assertEquals(1L, entries.getFirst().correlation().get("eventSeqNo"));
		assertEquals(TYPE, entries.getFirst().payload().get("eventType"));
		assertEquals("subscriber broke", entries.getFirst().payload().get("message"));
	}

	@Test
	void strictOffThreadPublishIsRejectedBeforeAppend() throws InterruptedException {
		var bus = bus(true);
		bus.bindOwnerThread(Thread.currentThread());
		var thrown = publishOnOtherThread(bus);
		assertInstanceOf(IllegalStateException.class, thrown);
		assertEquals(1, bus.stats().offThread());
		assertEquals(0, bus.latestSeqNo());
	}

	@Test
	void lenientOffThreadPublishIsCountedAndRetained() throws InterruptedException {
		var bus = bus(false);
		bus.bindOwnerThread(Thread.currentThread());
		assertNull(publishOnOtherThread(bus));
		assertEquals(1, bus.stats().offThread());
		assertEquals(1, bus.latestSeqNo());
	}

	@Test
	void unboundBusAllowsPublishingFromAnotherThread() throws InterruptedException {
		var bus = bus(true);
		assertNull(publishOnOtherThread(bus));
		assertEquals(0, bus.stats().offThread());
		assertEquals(1, bus.latestSeqNo());
	}

	@Test
	void viewReportsRetentionAndDropsFromTheLog() {
		var bus = new AgentEventBus(EventCatalog.defaults(), new AgentEventLog(1), () -> 1234, true);
		bus.from(SOURCE).publish(1, "task.completed", Map.of());
		bus.from(SOURCE).publish(2, TYPE, Map.of());
		assertTrue(bus.containsType(TYPE));
		assertFalse(bus.containsType("task.completed"));
		assertEquals(1, bus.droppedCount());
		assertEquals(2, bus.query(0L).events().getFirst().seqNo());
		assertTrue(bus.query(0L).truncated());
	}

	@Test
	void nestedDispatchOverflowRejectsBeforeAppendAndDeliversAllAcceptedEvents() {
		var bus = new AgentEventBus(EventCatalog.defaults(), new AgentEventLog(600), () -> 1234, true);
		var seen = new ArrayList<Long>();
		bus.subscribe(type -> true, event -> {
			if (event.seqNo() == 1) {
				for (int i = 0; i < 513; i++) bus.from(SOURCE).publish(i, TYPE, Map.of());
			}
		});
		bus.subscribe(type -> true, event -> seen.add(event.seqNo()));
		assertDoesNotThrow(() -> bus.from(SOURCE).publish(0, TYPE, Map.of()));
		assertEquals(513, bus.latestSeqNo());
		assertEquals(513, seen.size());
		assertEquals(513L, seen.getLast());
		assertEquals(1, bus.stats().subscriberFailures());
		bus.from(SOURCE).publish(600, TYPE, Map.of());
		assertEquals(514L, seen.getLast());
	}

	@Test
	void warningsAreOncePerViolationKeyUntilBoundedHistorySaturates() {
		var messages = new ArrayList<String>();
		var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(AgentEventBus.class);
		var appender = new org.apache.logging.log4j.core.appender.AbstractAppender("bus-test", null, null, false,
			org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
			@Override public void append(org.apache.logging.log4j.core.LogEvent event) {
				messages.add(event.getMessage().getFormattedMessage());
			}
		};
		appender.start();
		logger.addAppender(appender);
		try {
			var bus = bus(false);
			for (int i = 0; i < 256; i++) {
				bus.publish(i, "missing." + i, Map.of(), SOURCE, null);
				bus.publish(i, "missing." + i, Map.of(), SOURCE, null);
			}
			assertEquals(256, messages.size());
			bus.publish(257, "another.type", Map.of(), SOURCE, null);
			assertEquals(257, messages.size());
			bus.publish(258, "missing.0", Map.of(), SOURCE, null);
			bus.publish(259, TYPE, Map.of(), "new source", null);
			assertEquals(257, messages.size());
			assertEquals(514, bus.stats().undeclared());
			assertEquals(1, bus.stats().unknownSource());
			assertEquals(515, bus.latestSeqNo());
		} finally {
			logger.removeAppender(appender);
			appender.stop();
		}
	}

	private static AgentEventBus bus(boolean strict) {
		return new AgentEventBus(EventCatalog.defaults(), new AgentEventLog(8), () -> 1234, strict);
	}

	private static Throwable publishOnOtherThread(AgentEventBus bus) throws InterruptedException {
		var thrown = new AtomicReference<Throwable>();
		Thread thread = new Thread(() -> {
			try {
				bus.from(SOURCE).publish(1, TYPE, Map.of());
			} catch (Throwable failure) {
				thrown.set(failure);
			}
		});
		thread.start();
		thread.join();
		return thrown.get();
	}
}
