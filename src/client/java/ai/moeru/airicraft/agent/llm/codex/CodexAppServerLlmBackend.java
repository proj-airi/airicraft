package ai.moeru.airicraft.agent.llm.codex;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.llm.LlmBackend;
import ai.moeru.airicraft.agent.llm.LlmBackendException;
import ai.moeru.airicraft.agent.llm.LlmCallResult;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerBackendRequest;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import com.google.gson.JsonObject;
import io.opentelemetry.context.Context;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class CodexAppServerLlmBackend implements LlmBackend {
	private static final int THREAD_CLEANUP_TIMEOUT_MILLIS = 2_000;
	private static final String BASE_INSTRUCTIONS = """
		You are an inference-only planner embedded inside Airicraft.
		Do not inspect or modify the working directory and do not call Codex built-in tools.
		Airicraft owns all observation, validation, authorization, tools, and Minecraft effects.
		Return only the structured final response requested by the current turn.
		""";

	@FunctionalInterface
	interface ClientFactory {
		CodexAppServerClient create();
	}

	private final AgentConfig.LlmConfig config;
	private final AgentConfig.CodexAppServerConfig codexConfig;
	private final AgentObservability observability;
	private final CodexPlannerResponseCodec codec;
	private final ClientFactory clientFactory;
	private final Object lifecycleLock = new Object();
	private final Object turnLock = new Object();
	private final Map<Long, CandidateTurn> candidates = new ConcurrentHashMap<>();
	private final Set<Long> discardedGenerations = ConcurrentHashMap.newKeySet();
	private final Set<Long> injectedGenerations = ConcurrentHashMap.newKeySet();
	private final Deque<Object> injectedOutcomes = new ArrayDeque<>();

	private volatile CodexAppServerClient client;
	private volatile String canonicalThreadId;
	/** Observation state the thread already holds: advanced only by a turn that completed. */
	private ai.moeru.airicraft.agent.llm.ObservationPresenter committedPresenter = new ai.moeru.airicraft.agent.llm.ObservationPresenter();
	private volatile String responseModel;

	public CodexAppServerLlmBackend(
		AgentConfig.LlmConfig config,
		AgentObservability observability,
		PlannerToolRegistry toolRegistry
	) {
		this(
			config,
			observability,
			toolRegistry,
			() -> new CodexAppServerClient(config.codexAppServer().executable(), config.codexAppServer().startupTimeoutMillis())
		);
	}

	CodexAppServerLlmBackend(
		AgentConfig.LlmConfig config,
		AgentObservability observability,
		PlannerToolRegistry toolRegistry,
		ClientFactory clientFactory
	) {
		this.config = Objects.requireNonNull(config, "config");
		this.codexConfig = config.codexAppServer();
		this.observability = observability == null ? NoopObservability.INSTANCE : observability;
		this.codec = new CodexPlannerResponseCodec(Objects.requireNonNull(toolRegistry, "toolRegistry"));
		this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
	}

	@Override
	public LlmCallResult<PlannerResponse> generate(LlmConversation conversation) throws LlmBackendException {
		return generate(0L, conversation);
	}

	@Override
	public LlmCallResult<PlannerResponse> generate(PlannerBackendRequest request) throws LlmBackendException {
		return generate(request.generation(), request.conversation());
	}

	private LlmCallResult<PlannerResponse> generate(long generation, LlmConversation conversation) throws LlmBackendException {
		synchronized (turnLock) {
			return generateTurn(generation, conversation);
		}
	}

	private LlmCallResult<PlannerResponse> generateTurn(long generation, LlmConversation conversation) throws LlmBackendException {
		Object injected;
		synchronized (injectedOutcomes) {
			injected = injectedOutcomes.pollFirst();
		}
		if (injected instanceof PlannerResponse response) {
			injectedGenerations.add(generation);
			return LlmCallResult.of(response, LlmUsageSnapshot.unknown(), null, effectiveModel());
		}
		if (injected instanceof TimeoutException timeout) {
			throw new LlmBackendException(LlmFailureType.TIMEOUT, timeout.getMessage(), timeout);
		}

		try {
			if (discardedGenerations.contains(generation)) {
				throw new GenerationSupersededException();
			}
			String threadId = requireThread(conversation);
			if (discardedGenerations.contains(generation)) {
				throw new GenerationSupersededException();
			}
			JsonObject turnParams = new JsonObject();
			turnParams.addProperty("threadId", threadId);
			ai.moeru.airicraft.agent.llm.ObservationPresenter presenter;
			synchronized (lifecycleLock) {
				presenter = committedPresenter.copy();
			}
			turnParams.add("input", codec.turnInput(conversation, presenter));
			turnParams.add("additionalContext", codec.turnAdditionalContext());
			turnParams.add("outputSchema", codec.outputSchema());
			turnParams.addProperty("approvalPolicy", "never");
			if (!codexConfig.model().isBlank()) {
				turnParams.addProperty("model", codexConfig.model());
			}
			if (!codexConfig.reasoningEffort().isBlank()) {
				turnParams.addProperty("effort", codexConfig.reasoningEffort());
			}
			if (!codexConfig.serviceTier().isBlank()) {
				turnParams.addProperty("serviceTier", codexConfig.serviceTier());
			}

			CodexAppServerClient.TurnHandle handle = requireClient().startTurn(
				turnParams,
				codexConfig.startupTimeoutMillis()
			);
			CandidateTurn active = new CandidateTurn(threadId, handle.turnId());
			CandidateTurn previous = candidates.put(generation, active);
			if (previous != null && previous.turnId() != null) {
				requireClient().interrupt(previous.threadId(), previous.turnId());
			}
			if (discardedGenerations.contains(generation)) {
				abandonCandidate(generation);
				throw new GenerationSupersededException();
			}

			CodexAppServerClient.TurnResult turnResult = handle.completion().get(
				codexConfig.turnTimeoutMillis(),
				TimeUnit.MILLISECONDS
			);
			if (!"completed".equals(turnResult.status())) {
				String detail = turnResult.error() == null || turnResult.error().isJsonNull()
					? ""
					: ": " + turnResult.error();
				throw new IOException("Codex app-server turn ended with status " + turnResult.status() + detail);
			}
			if (turnResult.agentMessage() == null || turnResult.agentMessage().isBlank()) {
				throw new IOException("Codex app-server turn completed without an agent message");
			}
			synchronized (lifecycleLock) {
				if (threadId.equals(canonicalThreadId)) committedPresenter = presenter;
			}
			PlannerResponse response = codec.parse(turnResult.agentMessage(), generation);
			LlmCallResult<PlannerResponse> result = LlmCallResult.of(response, LlmUsageSnapshot.unknown(), null, effectiveModel());
			observability.recordLlmResponse(Context.current(), null, effectiveModel(), result.usage(), response);
			return result;
		}
		catch (LlmBackendException exception) {
			abandonCandidate(generation);
			throw exception;
		}
		catch (GenerationSupersededException exception) {
			abandonCandidate(generation);
			throw failure(LlmFailureType.PROVIDER_UNAVAILABLE, exception.getMessage(), exception);
		}
		catch (TimeoutException exception) {
			abandonCandidate(generation);
			throw failure(LlmFailureType.TIMEOUT, "Codex app-server request timed out", exception);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			abandonCandidate(generation);
			throw failure(LlmFailureType.PROVIDER_UNAVAILABLE, "Codex app-server request was interrupted", exception);
		}
		catch (ExecutionException exception) {
			abandonCandidate(generation);
			throw failure(LlmFailureType.PROVIDER_UNAVAILABLE, "Codex app-server turn failed", exception.getCause());
		}
		catch (IOException exception) {
			abandonCandidate(generation);
			throw failure(LlmFailureType.PROVIDER_UNAVAILABLE, exception.getMessage(), exception);
		}
	}

	private String requireThread(LlmConversation conversation)
		throws IOException, TimeoutException, InterruptedException {
		String existing = canonicalThreadId;
		if (existing != null && !existing.isBlank()) {
			return existing;
		}
		synchronized (lifecycleLock) {
			if (canonicalThreadId != null && !canonicalThreadId.isBlank()) {
				return canonicalThreadId;
			}
			JsonObject params = baseThreadParams();
			params.addProperty("developerInstructions", codec.developerInstructions(conversation));
			JsonObject response = requireClient().request("thread/start", params, codexConfig.startupTimeoutMillis());
			JsonObject thread = response.has("thread") && response.get("thread").isJsonObject()
				? response.getAsJsonObject("thread")
				: null;
			String threadId = thread == null || !thread.has("id") ? null : thread.get("id").getAsString();
			if (threadId == null || threadId.isBlank()) {
				throw new IOException("Codex app-server thread/start response is missing thread.id");
			}
			if (response.has("model") && !response.get("model").isJsonNull()) {
				responseModel = response.get("model").getAsString();
			}
			canonicalThreadId = threadId;
			return threadId;
		}
	}

	private JsonObject baseThreadParams() {
		JsonObject params = new JsonObject();
		params.addProperty("approvalPolicy", "never");
		params.addProperty("sandbox", "read-only");
		params.addProperty("cwd", Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toString());
		params.addProperty("baseInstructions", BASE_INSTRUCTIONS);
		if (!codexConfig.serviceTier().isBlank()) {
			params.addProperty("serviceTier", codexConfig.serviceTier());
		}
		if (!codexConfig.model().isBlank()) {
			params.addProperty("model", codexConfig.model());
		}
		return params;
	}

	private CodexAppServerClient requireClient() throws IOException, TimeoutException, InterruptedException {
		CodexAppServerClient existing = client;
		if (existing != null) {
			existing.start();
			return existing;
		}
		synchronized (lifecycleLock) {
			if (client == null) {
				client = clientFactory.create();
			}
			client.start();
			return client;
		}
	}

	@Override
	public void acceptGeneration(long generation) throws LlmBackendException {
		if (discardedGenerations.contains(generation)) {
			throw new LlmBackendException(LlmFailureType.PROVIDER_ERROR, "Codex candidate turn was discarded");
		}
		if (injectedGenerations.remove(generation)) {
			return;
		}
		CandidateTurn accepted = candidates.remove(generation);
		if (accepted == null || accepted.threadId() == null) {
			throw new LlmBackendException(LlmFailureType.PROVIDER_ERROR, "Codex turn is unavailable for acceptance");
		}
		discardedGenerations.remove(generation);
	}

	@Override
	public void discardGeneration(long generation) {
		discardedGenerations.add(generation);
		injectedGenerations.remove(generation);
		abandonCandidate(generation);
	}

	private void abandonCandidate(long generation) {
		CandidateTurn discarded = candidates.remove(generation);
		CodexAppServerClient activeClient = client;
		if (discarded != null && discarded.turnId() != null && activeClient != null) {
			activeClient.interrupt(discarded.threadId(), discarded.turnId());
		}
	}

	@Override
	public synchronized void injectMockResponse(PlannerResponse response) {
		injectedOutcomes.addLast(Objects.requireNonNull(response, "response"));
	}

	@Override
	public synchronized void injectTimeout() {
		injectedOutcomes.addLast(new TimeoutException("Injected LLM timeout"));
	}

	@Override
	public boolean isConfigured() {
		return config.plannerBackend() == AgentConfig.PlannerBackend.CODEX_APP_SERVER && codexConfig.isConfigured();
	}

	@Override
	public boolean managesConversationHistory() {
		return true;
	}

	@Override
	public boolean supportsGenerationCancellation() {
		return true;
	}

	@Override
	public void resetBackend() {
		closeClient();
	}

	@Override
	public void shutdownBackend() {
		closeClient();
	}

	private void closeClient() {
		synchronized (lifecycleLock) {
			CodexAppServerClient activeClient = client;
			String finalCanonicalThreadId = canonicalThreadId;
			CandidateTurn[] abandonedCandidates = candidates.values().toArray(CandidateTurn[]::new);
			client = null;
			canonicalThreadId = null;
			committedPresenter = new ai.moeru.airicraft.agent.llm.ObservationPresenter();
			responseModel = null;
			candidates.clear();
			discardedGenerations.clear();
			injectedGenerations.clear();
			if (activeClient != null) {
				for (CandidateTurn candidate : abandonedCandidates) {
					if (candidate.turnId() != null) {
						activeClient.interrupt(candidate.threadId(), candidate.turnId());
					}
				}
				archiveThreadBeforeClose(activeClient, finalCanonicalThreadId);
				activeClient.close();
			}
		}
	}

	private static void archiveThreadBeforeClose(CodexAppServerClient activeClient, String threadId) {
		if (threadId == null || threadId.isBlank()) {
			return;
		}
		try {
			activeClient.archiveThread(threadId, THREAD_CLEANUP_TIMEOUT_MILLIS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			Airicraft.LOGGER.debug("Interrupted while archiving Codex thread {}", threadId, exception);
		}
		catch (IOException | TimeoutException exception) {
			Airicraft.LOGGER.debug("Failed to archive Codex thread {} during shutdown", threadId, exception);
		}
	}

	private String effectiveModel() {
		String observed = responseModel;
		if (observed != null && !observed.isBlank()) {
			return observed;
		}
		return codexConfig.model().isBlank() ? "codex-local-default" : codexConfig.model();
	}

	private LlmBackendException failure(LlmFailureType type, String message, Throwable cause) {
		String effectiveMessage = message == null || message.isBlank() ? "Codex app-server operation failed" : message;
		observability.recordFailure(Context.current(), type.name(), effectiveMessage, cause);
		return new LlmBackendException(type, effectiveMessage, cause);
	}

	private record CandidateTurn(String threadId, String turnId) {
	}

	private static final class GenerationSupersededException extends Exception {
		private GenerationSupersededException() {
			super("Codex planner generation was superseded");
		}
	}
}
