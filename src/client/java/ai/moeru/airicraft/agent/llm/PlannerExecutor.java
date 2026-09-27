package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import com.google.gson.Gson;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.net.URI;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlannerExecutor {
	private static final Gson GSON = new Gson();
	private static final String PLANNER_OFF = "PLANNER OFF";
	private static final URI PLANNER_OFF_ENDPOINT = URI.create("airicraft://planner-off");
	private final LlmBackend llmBackend;
	private final AgentObservability observability;
	private final ExecutorService executorService;
	private final Map<Long, InFlightAttempt> inFlightAttempts = new LinkedHashMap<>();
	private final Set<Long> detachedGenerations = new HashSet<>();

	private long nextSubmissionId = 1L;

	public PlannerExecutor(LlmBackend llmBackend) {
		this(llmBackend, NoopObservability.INSTANCE);
	}

	public PlannerExecutor(LlmBackend llmBackend, AgentObservability observability) {
		this.llmBackend = Objects.requireNonNull(llmBackend, "llmBackend");
		this.observability = Objects.requireNonNull(observability, "observability");
		this.executorService = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "airicraft-planner");
			thread.setDaemon(true);
			return thread;
		});
	}

	public boolean isConfigured() {
		return llmBackend.isConfigured();
	}

	public boolean hasInFlight() {
		return inFlightAttempts.values().stream().anyMatch(attempt -> !detachedGenerations.contains(attempt.generation()));
	}

	/** True while a submitted provider call has not completed yet; completed results wait for {@link #poll()}. */
	public boolean hasRunningCall() {
		return inFlightAttempts.values().stream().anyMatch(attempt -> !attempt.future().isDone());
	}

	public int activeAttemptCount() {
		return (int) inFlightAttempts.values().stream()
			.filter(attempt -> !detachedGenerations.contains(attempt.generation()))
			.count();
	}

	public boolean submit(PlannerRequest request, LlmConversation conversation) {
		return submit(0L, 1, PlannerSessionPhase.PLANNER_REQUEST, request, conversation, Context.current(), AgentObservability.PLANNER_REQUEST_SPAN_NAME);
	}

	public boolean submit(PlannerRequest request, LlmConversation conversation, Context parentContext) {
		return submit(0L, 1, PlannerSessionPhase.PLANNER_REQUEST, request, conversation, parentContext, AgentObservability.PLANNER_REQUEST_SPAN_NAME);
	}

	public boolean submit(PlannerRequest request, LlmConversation conversation, Context parentContext, String spanName) {
		return submit(0L, 1, PlannerSessionPhase.PLANNER_REQUEST, request, conversation, parentContext, spanName);
	}

	public boolean submit(long generation, int attempt, PlannerSessionPhase phase, PlannerRequest request, LlmConversation conversation) {
		return submit(generation, attempt, phase, request, conversation, Context.current(), AgentObservability.PLANNER_REQUEST_SPAN_NAME);
	}

	public boolean submit(long generation, int attempt, PlannerSessionPhase phase, PlannerRequest request, LlmConversation conversation, Context parentContext, String spanName) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(conversation, "conversation");
		Objects.requireNonNull(spanName, "spanName");

		Context executionContext = parentContext == null ? Context.current() : parentContext;
		Context plannerContext = observability.startChildSpan(spanName, executionContext);
		long submissionId = nextSubmissionId++;
		PlannerStreamPreview preview = new PlannerStreamPreview();
		CompletableFuture<LlmCallResult<PlannerResponse>> future = CompletableFuture.supplyAsync(() -> {
			try (Scope scope = plannerContext.makeCurrent()) {
				return llmBackend.generate(new PlannerBackendRequest(generation, attempt, phase, request, conversation), preview::append);
			}
			catch (LlmBackendException exception) {
				throw new CompletionException(exception);
			}
		}, executorService);
		inFlightAttempts.put(submissionId, new InFlightAttempt(submissionId, generation, attempt, phase, request, future, plannerContext, preview));
		return true;
	}

	public PlannerExecutionResult recordDiscarded(
		long generation,
		int attempt,
		PlannerSessionPhase phase,
		PlannerRequest request,
		LlmConversation conversation,
		List<Map<String, Object>> tools,
		Context parentContext,
		String spanName
	) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(conversation, "conversation");
		Objects.requireNonNull(tools, "tools");
		Objects.requireNonNull(spanName, "spanName");
		Context executionContext = parentContext == null ? Context.current() : parentContext;
		Context plannerContext = observability.startChildSpan(spanName, executionContext);
		PlannerExecutionResult result = PlannerExecutionResult.discarded(request, generation, attempt, phase);
		try (Scope scope = plannerContext.makeCurrent()) {
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("model", "planner-off");
			payload.put("tools", tools);
			payload.put("tool_choice", "auto");
			payload.put("messages", OpenAiCompatibleChatClient.canonicalRequestMessages(conversation));
			payload.put("planner", "off");
			String requestBody = GSON.toJson(payload);
			observability.recordLlmRequest(
				Context.current(),
				"airicraft",
				PLANNER_OFF_ENDPOINT,
				"planner-off",
				0L,
				conversation,
				requestBody
			);
			observability.recordRawLlmResponse(
				Context.current(),
				null,
				"planner-off",
				LlmUsageSnapshot.unknown(),
				PLANNER_OFF
			);
			observability.recordLlmResponse(
				Context.current(),
				null,
				"planner-off",
				LlmUsageSnapshot.unknown(),
				result.response()
			);
		}
		finally {
			observability.endSpan(plannerContext);
		}
		return result;
	}

	public PlannerExecutionResult poll() {
		Iterator<Map.Entry<Long, InFlightAttempt>> iterator = inFlightAttempts.entrySet().iterator();
		while (iterator.hasNext()) {
			InFlightAttempt attempt = iterator.next().getValue();
			if (!attempt.future().isDone()) {
				continue;
			}
			iterator.remove();
			clearDetachedGenerationIfDrained(attempt.generation());
			endFlightSpan(attempt.context());
			Context failureContext = attempt.context() == null ? Context.current() : attempt.context();
			try {
				LlmCallResult<PlannerResponse> result = attempt.future().join();
				return new PlannerExecutionResult(
					attempt.request(),
					result.payload(),
					result.usage(),
					null,
					null,
					attempt.generation(),
					attempt.attempt(),
					attempt.phase(),
					false
				);
			}
			catch (CompletionException exception) {
				Throwable cause = exception.getCause();
				if (cause instanceof LlmBackendException backendException) {
					return new PlannerExecutionResult(
						attempt.request(),
						null,
						LlmUsageSnapshot.unknown(),
						backendException.failureType(),
						backendException.getMessage(),
						attempt.generation(),
						attempt.attempt(),
						attempt.phase(),
						false,
						backendException.retryAfterMillis()
					);
				}
				observability.recordFailure(
					failureContext,
					LlmFailureType.PROVIDER_ERROR.name(),
					cause == null ? exception.getMessage() : cause.getMessage(),
					cause instanceof Throwable throwable ? throwable : exception
				);
				return new PlannerExecutionResult(
					attempt.request(),
					null,
					LlmUsageSnapshot.unknown(),
					LlmFailureType.PROVIDER_ERROR,
					cause == null ? exception.getMessage() : cause.getMessage(),
					attempt.generation(),
					attempt.attempt(),
					attempt.phase(),
					false
				);
			}
		}
		return null;
	}

	public java.util.Optional<PlannerConversationDebugMessage> streamPreview(long generation) {
		return inFlightAttempts.values().stream()
			.filter(attempt -> attempt.generation() == generation && !detachedGenerations.contains(generation))
			.reduce((first, last) -> last)
			.filter(attempt -> !attempt.preview().text().isEmpty())
			.map(attempt -> new PlannerConversationDebugMessage("assistant", PlannerConversationDebugKind.ASSISTANT_TURN,
				"[Streaming — incomplete]\n" + attempt.preview().text(), generation, attempt.phase().name(), attempt.attempt(), false));
	}

	public void injectMockResponse(PlannerResponse response) {
		llmBackend.injectMockResponse(response);
	}

	public void injectTimeout() {
		llmBackend.injectTimeout();
	}

	public boolean managesConversationHistory() {
		return llmBackend.managesConversationHistory();
	}

	public void acceptGeneration(long generation) throws LlmBackendException {
		llmBackend.acceptGeneration(generation);
	}

	public void discardGeneration(long generation) {
		discardGeneration(generation, false);
	}

	public void pauseGeneration(long generation) {
		discardGeneration(generation, true);
	}

	private void discardGeneration(long generation, boolean detachIfUncancellable) {
		llmBackend.discardGeneration(generation);
		if (!llmBackend.supportsGenerationCancellation()) {
			if (detachIfUncancellable) {
				detachedGenerations.add(generation);
			}
			return;
		}
		Iterator<Map.Entry<Long, InFlightAttempt>> iterator = inFlightAttempts.entrySet().iterator();
		while (iterator.hasNext()) {
			InFlightAttempt attempt = iterator.next().getValue();
			if (attempt.generation() != generation) {
				continue;
			}
			attempt.future().cancel(true);
			endFlightSpan(attempt.context());
			iterator.remove();
		}
	}

	public void reset() {
		for (InFlightAttempt attempt : inFlightAttempts.values()) {
			attempt.future().cancel(true);
			endFlightSpan(attempt.context());
		}
		inFlightAttempts.clear();
		detachedGenerations.clear();
		llmBackend.resetBackend();
	}

	public void shutdown() {
		for (InFlightAttempt attempt : inFlightAttempts.values()) {
			attempt.future().cancel(true);
			endFlightSpan(attempt.context());
		}
		inFlightAttempts.clear();
		detachedGenerations.clear();
		llmBackend.shutdownBackend();
		executorService.shutdownNow();
	}

	private void endFlightSpan(Context context) {
		if (context != null) {
			observability.endSpan(context);
		}
	}

	private void clearDetachedGenerationIfDrained(long generation) {
		if (inFlightAttempts.values().stream().noneMatch(attempt -> attempt.generation() == generation)) {
			detachedGenerations.remove(generation);
		}
	}

	private record InFlightAttempt(
		long submissionId,
		long generation,
		int attempt,
		PlannerSessionPhase phase,
		PlannerRequest request,
		CompletableFuture<LlmCallResult<PlannerResponse>> future,
		Context context,
		PlannerStreamPreview preview
	) {
	}
}
