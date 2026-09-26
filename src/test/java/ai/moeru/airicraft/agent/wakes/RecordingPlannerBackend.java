package ai.moeru.airicraft.agent.wakes;

import ai.moeru.airicraft.agent.llm.*;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

public final class RecordingPlannerBackend implements LlmBackend {
	public record Request(int index, PlannerBackendRequest request, long observedAtTick) { }
	private final CopyOnWriteArrayList<Request> requests = new CopyOnWriteArrayList<>();
	private final LinkedBlockingQueue<CompletableFuture<PlannerResponse>> scripted = new LinkedBlockingQueue<>();
	private final LongSupplier tick;
	private volatile CompletableFuture<PlannerResponse> held;
	public RecordingPlannerBackend(LongSupplier tick) { this.tick = tick; }
	public List<Request> requests() { return List.copyOf(requests); }
	public CompletableFuture<PlannerResponse> holdNext() {
		var future = new CompletableFuture<PlannerResponse>();
		scripted.add(future);
		return future;
	}
	public boolean held() { return held != null && !held.isDone(); }
	public static PlannerResponse yieldResponse() {
		return new PlannerResponse("", new PlannerToolCall("yield", "continue", new JsonObject(), null), null);
	}
	@Override public LlmCallResult<PlannerResponse> generate(LlmConversation conversation) {
		throw new AssertionError("Expected a request with generation and phase");
	}
	@Override public LlmCallResult<PlannerResponse> generate(PlannerBackendRequest request) throws LlmBackendException {
		var response = scripted.poll();
		held = response;
		synchronized (requests) {
			requests.add(new Request(requests.size() + 1, request, tick.getAsLong()));
			requests.notifyAll();
		}
		try { return LlmCallResult.of(response == null ? yieldResponse() : response.get(2, TimeUnit.SECONDS), null); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new LlmBackendException(LlmFailureType.PROVIDER_ERROR, "interrupted", e); }
		catch (ExecutionException | TimeoutException e) { throw new LlmBackendException(LlmFailureType.TIMEOUT, "scripted timeout", e); }
	}
	public void awaitRequests(int count, Duration timeout) {
		long end = System.nanoTime() + timeout.toNanos();
		synchronized (requests) {
			while (requests.size() < count) {
				long remaining = end - System.nanoTime();
				if (remaining <= 0) throw new AssertionError("Expected " + count + " requests, got " + requests.size());
				try { TimeUnit.NANOSECONDS.timedWait(requests, remaining); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
			}
		}
	}
	@Override public void injectMockResponse(PlannerResponse response) { scripted.add(CompletableFuture.completedFuture(response)); }
	@Override public void injectTimeout() { var future = holdNext(); future.completeExceptionally(new TimeoutException("injected")); }
	@Override public boolean isConfigured() { return true; }
}
