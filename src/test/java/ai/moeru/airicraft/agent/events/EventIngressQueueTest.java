package ai.moeru.airicraft.agent.events;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventIngressQueueTest {
	@Test
	void fullQueueCountsEachRejectedOfferAndResetDoesNotDiscardItems() {
		var queue = new EventIngressQueue<String>(2);
		assertTrue(queue.offer("first"));
		assertTrue(queue.offer("second"));
		assertFalse(queue.offer("third"));
		assertFalse(queue.offer("fourth"));
		assertEquals(2, queue.takeDroppedCount());
		assertEquals(0, queue.takeDroppedCount());

		var drained = new ArrayList<String>();
		assertEquals(2, queue.drain(drained::add));
		assertEquals(List.of("first", "second"), drained);
		assertEquals(0, queue.drain(drained::add));
		assertTrue(queue.offer("fifth"));
		assertEquals(0, queue.takeDroppedCount());
		assertEquals(1, queue.drain(drained::add));
		assertEquals(List.of("first", "second", "fifth"), drained);
	}

	@Test
	void twoProducersAndDrainerPreserveAcceptedPerProducerOrderAndExactDropAccounting() throws Exception {
		var queue = new EventIngressQueue<String>(4);
		var accepted = new AtomicInteger();
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		var done = new CountDownLatch(2);
		var drained = new ArrayList<String>();
		try (var workers = Executors.newFixedThreadPool(3)) {
			for (int producer = 0; producer < 2; producer++) {
				int id = producer;
				workers.submit(() -> {
					ready.countDown();
					try {
						start.await();
						for (int sequence = 0; sequence < 1_000; sequence++) {
							if (queue.offer(id + ":" + sequence)) accepted.incrementAndGet();
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new RuntimeException(e);
					} finally {
						done.countDown();
					}
				});
			}
			var drainer = workers.submit(() -> {
				int count = 0;
				while (done.getCount() > 0) {
					count += queue.drain(drained::add);
					Thread.onSpinWait();
				}
				return count + queue.drain(drained::add);
			});
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			int drainedCount = drainer.get(10, TimeUnit.SECONDS);
			assertEquals(accepted.get(), drainedCount);
		}

		assertEquals(2_000, accepted.get() + queue.takeDroppedCount());
		assertEquals(0, queue.takeDroppedCount());
		assertEquals(accepted.get(), drained.size());
		assertEquals(0, queue.drain(drained::add));
		int[] previous = {-1, -1};
		for (String item : drained) {
			String[] parts = item.split(":");
			int id = Integer.parseInt(parts[0]);
			int sequence = Integer.parseInt(parts[1]);
			assertTrue(sequence > previous[id], item + " appeared out of order");
			previous[id] = sequence;
		}
	}
}
