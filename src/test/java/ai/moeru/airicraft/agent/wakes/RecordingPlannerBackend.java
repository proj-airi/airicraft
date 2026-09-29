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
	private volatile long heldGeneration = -1L;
	private final Object gate = new Object();
	private boolean gateOpen = true;
	public RecordingPlannerBackend(LongSupplier tick) { this.tick = tick; }
	/**
	 * Holds provider calls until {@link #openGate()}. A tick that submits a request and then polls must never see
	 * the response in that same tick: otherwise whether it applies at tick N or N+1 depends on thread scheduling.
	 */
	public void closeGate() { synchronized (gate) { gateOpen = false; } }
	public void openGate() { synchronized (gate) { gateOpen = true; gate.notifyAll(); } }
	public List<Request> requests() { return List.copyOf(requests); }
	public CompletableFuture<PlannerResponse> holdNext() {
		var future = new CompletableFuture<PlannerResponse>();
		scripted.add(future);
		return future;
	}
	public boolean held() {
		var current = held; // read once: the provider thread may clear it between the null check and isDone
		return current != null && !current.isDone();
	}
	public static PlannerResponse yieldResponse() {
		return new PlannerResponse("", new PlannerToolCall("yield", "continue", new JsonObject(), null), null);
	}
	@Override public LlmCallResult<PlannerResponse> generate(LlmConversation conversation) {
		throw new AssertionError("Expected a request with generation and phase");
	}
	@Override public LlmCallResult<PlannerResponse> generate(PlannerBackendRequest request) throws LlmBackendException {
		awaitGate();
		var response = scripted.poll();
		heldGeneration = request.generation();
		held = response;
		synchronized (requests) {
			requests.add(new Request(requests.size() + 1, request, tick.getAsLong()));
			requests.notifyAll();
		}
		try { return LlmCallResult.of(response == null ? yieldResponse() : response.get(2, TimeUnit.SECONDS), null); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new LlmBackendException(LlmFailureType.PROVIDER_ERROR, "interrupted", e); }
		catch (ExecutionException | TimeoutException e) { throw new LlmBackendException(LlmFailureType.TIMEOUT, "scripted timeout", e); }
	}
	private void awaitGate() throws LlmBackendException {
		long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		synchronized (gate) {
			while (!gateOpen) {
				long remaining = end - System.nanoTime();
				if (remaining <= 0) throw new LlmBackendException(LlmFailureType.TIMEOUT, "gate never opened", null);
				try { TimeUnit.NANOSECONDS.timedWait(gate, remaining); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new LlmBackendException(LlmFailureType.PROVIDER_ERROR, "interrupted", e); }
			}
		}
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
	/** Like both production backends: a discarded generation's held call ends at once. */
	@Override public boolean supportsGenerationCancellation() { return true; }
	@Override public void discardGeneration(long generation) {
		var current = held;
		if (current != null && heldGeneration == generation) current.cancel(true);
	}
}
