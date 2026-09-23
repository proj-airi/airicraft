package ai.moeru.airicraft.agent.llm.codex;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.llm.LlmBackendException;
import ai.moeru.airicraft.agent.llm.LlmCallResult;
import ai.moeru.airicraft.agent.llm.LlmChatMessage;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmMessageKind;
import ai.moeru.airicraft.agent.llm.PlannerChatMessage;
import ai.moeru.airicraft.agent.llm.PlannerBackendRequest;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerSessionPhase;
import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.session.SessionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAppServerLlmBackendTest {
	@TempDir
	Path tempDir;

	@Test
	void acceptsInjectedDebugResponseWithoutStartingAppServer() throws Exception {
		Path log = tempDir.resolve("injected.log");
		CodexAppServerLlmBackend backend = backend(log);
		try {
			backend.injectMockResponse(new PlannerResponse(
				List.of(new PlannerChatMessage("injected", 0)),
				null,
				null
			));
			assertEquals("injected", backend.generate(request(42L, "ignored")).payload().replyText());
			backend.acceptGeneration(42L);
			assertTrue(Files.notExists(log));
		}
		finally {
			backend.shutdownBackend();
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "AIRICRAFT_CODEX_LIVE", matches = "1")
	void completesStructuredTurnThroughInstalledCodexAppServer() throws Exception {
		AgentConfig.LlmConfig liveConfig = withCodexCommand(
			System.getenv().getOrDefault("AIRICRAFT_CODEX_EXECUTABLE", "codex"),
			120_000
		);
		CodexAppServerLlmBackend backend = new CodexAppServerLlmBackend(
			liveConfig,
			NoopObservability.INSTANCE,
			PlannerToolRegistry.empty()
		);
		try {
			LlmCallResult<PlannerResponse> result = backend.generate(request(99L, "Reply with exactly: ready"));
			assertTrue(result.payload().toolCalls().isEmpty());
			assertTrue(result.payload().replyText().equalsIgnoreCase("ready"));
			backend.acceptGeneration(99L);
		}
		finally {
			backend.shutdownBackend();
		}
	}

	@Test
	void reusesSingleThreadAcrossAcceptedDiscardedAndToolFollowUpTurns() throws Exception {
		Path log = tempDir.resolve("single-thread.log");
		CodexAppServerLlmBackend backend = backend(log);
		try {
			LlmCallResult<PlannerResponse> first = backend.generate(request(1L, "first"));
			assertEquals("from:root", first.payload().replyText());
			backend.acceptGeneration(1L);

			LlmCallResult<PlannerResponse> unacceptedSibling = backend.generate(request(2L, "sibling"));
			assertEquals("from:root", unacceptedSibling.payload().replyText());
			backend.discardGeneration(2L);

			LlmCallResult<PlannerResponse> toolProposal = backend.generate(request(1L, "REQUEST_TOOL"));
			assertEquals(PlannerToolCatalog.CLEAR_GOAL, toolProposal.payload().toolCall().name());
			backend.acceptGeneration(1L);

			LlmCallResult<PlannerResponse> followUp = backend.generate(request(1L, "Tool result: goal cleared"));
			assertEquals("from:root", followUp.payload().replyText());
			backend.acceptGeneration(1L);
		}
		finally {
			backend.shutdownBackend();
		}

		String wireLog = Files.readString(log);
		assertEquals(1L, wireLog.lines().filter(line -> line.equals("thread/start")).count());
		assertEquals(4L, wireLog.lines().filter(line -> line.equals("turn/start")).count());
		assertEquals(4L, wireLog.lines().filter(line -> line.equals("turn/start effort high")).count());
		assertTrue(wireLog.contains("thread/archive root"));
		assertFalse(wireLog.contains("thread/fork"));
	}

	@Test
	void sendsTheFullToolCatalogInAdditionalContextOnEveryTurn() throws Exception {
		Path log = tempDir.resolve("tool-schema-refresh.log");
		PlannerToolRegistry registry = PlannerToolRegistry.empty();
		CodexAppServerLlmBackend backend = backend(log, registry);
		try {
			backend.generate(request(1L, "first"));
			backend.acceptGeneration(1L);
			backend.generate(request(2L, "second"));
			backend.acceptGeneration(2L);
		}
		finally {
			backend.shutdownBackend();
		}

		List<String> schemaContexts = Files.readAllLines(log).stream()
			.filter(line -> line.startsWith("turn/start additionalContext "))
			.toList();
		assertEquals(2, schemaContexts.size());
		for (String context : schemaContexts) {
			assertTrue(context.contains("inspect_world"));
			assertTrue(context.contains("inspect_area"));
			assertTrue(context.contains("find_placement_sites"));
		}
	}

	@Test
	void invalidArgumentsIdentifyTheRejectedToolForSchemaRepair() {
		PlannerToolRegistry registry = PlannerToolRegistry.empty();
		CodexPlannerResponseCodec codec = new CodexPlannerResponseCodec(registry);

		LlmBackendException exception = assertThrows(LlmBackendException.class, () -> codec.parse("""
			{"chatMessages":[],"toolCalls":[{"name":"inspect_world","argumentsJson":"{\\"mode\\":\\"block\\",\\"scope\\":\\"self\\"}"}]}
			""", 7L));

		assertEquals(ai.moeru.airicraft.agent.llm.LlmFailureType.PARSE_ERROR, exception.failureType());
		assertTrue(exception.getMessage().contains("Invalid inspect_world tool arguments"));
		assertTrue(exception.getMessage().contains("Unsupported inspect_world mode: block"));
	}

	@Test
	void discardInterruptsActiveTurnAndLeavesCanonicalThreadUnchanged() throws Exception {
		Path log = tempDir.resolve("supersede.log");
		CodexAppServerLlmBackend backend = backend(log);
		try {
			CompletableFuture<LlmCallResult<PlannerResponse>> waiting = CompletableFuture.supplyAsync(() -> {
				try {
					return backend.generate(request(7L, "WAIT"));
				}
				catch (LlmBackendException exception) {
					throw new CompletionException(exception);
				}
			});
			awaitLog(log, "turn/start");

			backend.discardGeneration(7L);
			assertThrows(CompletionException.class, waiting::join);
			awaitLog(log, "turn/interrupt");

			LlmCallResult<PlannerResponse> replacement = backend.generate(request(8L, "replacement"));
			assertEquals("from:root", replacement.payload().replyText());
		}
		finally {
			backend.shutdownBackend();
		}

		String wireLog = Files.readString(log);
		assertTrue(wireLog.contains("turn/interrupt"));
		assertEquals(1L, wireLog.lines().filter(line -> line.equals("thread/start")).count());
		assertEquals(2L, wireLog.lines().filter(line -> line.equals("turn/start")).count());
		assertTrue(wireLog.contains("thread/archive root"));
		assertFalse(wireLog.contains("thread/fork"));
	}

	private CodexAppServerLlmBackend backend(Path log) {
		return backend(log, PlannerToolRegistry.empty());
	}

	@Test
	void forwardsConfiguredServiceTierAndOmitsInheritedDefault() throws Exception {
		for (String tier : List.of("", "fast", "priority")) {
			Path log = tempDir.resolve("tier-" + tier + ".log");
			var backend = new CodexAppServerLlmBackend(
				withCodexCommand("fake", 5_000, tier), NoopObservability.INSTANCE,
				PlannerToolRegistry.empty(), () -> CodexAppServerClientTest.fakeClient(log));
			try {
				backend.generate(request(1L, "first"));
				backend.acceptGeneration(1L);
				backend.generate(request(2L, "follow-up"));
				backend.acceptGeneration(2L);
			}
			finally {
				backend.shutdownBackend();
			}
			String wireLog = Files.readString(log);
			if (tier.isEmpty()) {
				assertFalse(wireLog.contains("serviceTier"));
			}
			else {
				assertEquals(1L, wireLog.lines().filter(line -> line.equals("thread/start serviceTier " + tier)).count());
				assertEquals(2L, wireLog.lines().filter(line -> line.equals("turn/start serviceTier " + tier)).count());
			}
		}
	}

	private CodexAppServerLlmBackend backend(Path log, PlannerToolRegistry registry) {
		return new CodexAppServerLlmBackend(
			codexConfig(),
			NoopObservability.INSTANCE,
			registry,
			() -> CodexAppServerClientTest.fakeClient(log)
		);
	}

	private static PlannerBackendRequest request(long generation, String text) {
		PlannerRequest plannerRequest = new PlannerRequest(
			1L,
			1_000L,
			SessionMode.OUT_OF_WORLD,
			null,
			null,
			null,
			null,
			"Tester",
			text,
			null
		);
		return new PlannerBackendRequest(
			generation,
			1,
			PlannerSessionPhase.PLANNER_REQUEST,
			plannerRequest,
			LlmConversation.of(List.of(
				LlmChatMessage.system("You are a test planner."),
				LlmChatMessage.user(text, LlmMessageKind.USER_TURN)
			))
		);
	}

	private static AgentConfig.LlmConfig codexConfig() {
		return withCodexCommand("fake", 5_000);
	}

	private static AgentConfig.LlmConfig withCodexCommand(String executable, int turnTimeoutMillis) {
		return withCodexCommand(executable, turnTimeoutMillis, "");
	}

	private static AgentConfig.LlmConfig withCodexCommand(String executable, int turnTimeoutMillis, String serviceTier) {
		AgentConfig.LlmConfig defaults = AgentConfig.LlmConfig.defaults();
		return new AgentConfig.LlmConfig(
			defaults.providerBaseUrl(),
			"",
			"",
			defaults.visionProviderBaseUrl(),
			defaults.visionApiKey(),
			defaults.visionModel(),
			defaults.requestTimeoutMillis(),
			defaults.visionRequestTimeoutMillis(),
			defaults.maxRecentConversationTurns(),
			defaults.plannerCompactionTriggerTokens(),
			defaults.plannerPendingSemanticEventCap(),
			defaults.plannerSessionMaxConcurrentAttempts(),
			defaults.plannerSessionCoalesceStepMillis(),
			defaults.plannerSessionCoalesceMinMillis(),
			defaults.plannerSessionCoalesceMaxMillis(),
			defaults.visionImageDetail(),
			true,
			defaults.plannerUseJsonObjectResponseFormat(),
			AgentConfig.PlannerBackend.CODEX_APP_SERVER,
			new AgentConfig.CodexAppServerConfig(executable, "", "high", serviceTier, 10_000, turnTimeoutMillis)
		);
	}

	private static void awaitLog(Path log, String fragment) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (System.nanoTime() < deadline) {
			if (Files.exists(log) && Files.readString(log).contains(fragment)) {
				return;
			}
			Thread.sleep(10L);
		}
		throw new AssertionError("Timed out waiting for fake app-server log: " + fragment);
	}
}
