package ai.moeru.airicraft.wrapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiricraftCliMainTest {
	@Test
	void statusRendersDeterministicText() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/status").failure = new BridgeUnavailableException(
			"minecraft_unavailable",
			"Minecraft bridge is not active"
		);

		CliResult result = execute(transport, "status");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().startsWith(
			"status: ok\n" +
				"command: status\n" +
				"available: false\n"
		));
		assertTrue(result.output().contains("state: minecraft_unavailable\n"));
	}

	@Test
	void statusPrintsClickableDebugDashboardUrl() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/status").payload = linkedMap(
			"available", true,
			"bridgeAvailable", true,
			"worldLoaded", false,
			"sessionState", "out_of_world",
			"state", "world_not_loaded",
			"debugDashboard", linkedMap(
				"enabled", true,
				"running", true,
				"port", 8765,
				"url", "http://192.168.1.20:8765/#token=test-token"
			)
		);

		CliResult result = execute(transport, "status");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("[debugDashboard]\n"));
		assertTrue(result.output().contains("url: http://192.168.1.20:8765/#token=test-token\n"));
	}

	@Test
	void statusExposesAutomaticPlaytestOutcomeAndEvidenceDirectory() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/status").payload = linkedMap(
			"available", true,
			"automaticPlaytest", linkedMap("enabled", true, "state", "REPORTED", "outputDir", "/tmp/playtests/report-1", "error", "")
		);
		CliResult result = execute(transport, "status");
		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("[automaticPlaytest]\n"));
		assertTrue(result.output().contains("state: REPORTED\n"));
		assertTrue(result.output().contains("outputDir: /tmp/playtests/report-1\n"));
	}

	@Test
	void reloadRendersDeterministicText() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/reload").payload = linkedMap(
			"available", true,
			"reloaded", true,
			"agentStateReset", true,
			"sessionMode", "SINGLEPLAYER_LOCAL",
			"worldLoaded", true,
			"plannerVisionMode", "native_tool_image",
			"llmConfigured", true,
			"visionConfigured", true,
			"observabilityEnabled", false
		);

		CliResult result = execute(transport, "reload");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().startsWith(
			"status: ok\n" +
				"command: reload\n"
		));
		assertTrue(result.output().contains("reloaded: true\n"));
		assertTrue(result.output().contains("agentStateReset: true\n"));
		assertTrue(result.output().contains("plannerVisionMode: native_tool_image\n"));
		assertTrue(transport.requested("POST", "/v1/reload"));
	}

	@Test
	void agentContextRendersCompactionState() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/context").payload = linkedMap(
			"available", true,
			"planner", linkedMap(
				"configured", true,
				"plannerVisionMode", "native_tool_image",
				"inFlight", false,
				"plannerInFlight", false,
				"compactionInFlight", false,
				"captureInFlight", false,
				"toolInFlight", false,
				"toolUsed", false,
				"coalescePending", true,
				"coalesceReadyAtMs", 123456999L,
				"coalesceWindowMs", 20L,
				"context", linkedMap(
					"compactionTriggerTokens", 65536,
					"compactionPending", true,
					"acceptedTurnCount", 8,
					"pendingSemanticEventCount", 3,
					"projectedPendingNoticeCount", 2,
					"frozenPlannerMessageCount", 0,
					"queuedTriggerCount", 3,
					"lastObservedEventSeqNo", 42,
					"lastAcceptedTimeContextAtMs", 123456789L,
					"pendingSemanticGap", false,
					"overflowFlushPending", true
				)
			),
			"conversationSources", linkedMap(
				"canonicalMessageCount", 11,
				"projectedMessageCount", 14,
				"canonicalUserTurnCount", 4,
				"projectedUserTurnCount", 4,
				"hiddenKinds", List.of("SYSTEM")
			)
		);

		CliResult result = execute(transport, "agent", "context");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent context\n"));
		assertTrue(result.output().contains("plannerVisionMode: native_tool_image\n"));
		assertTrue(result.output().contains("compactionPending: true\n"));
		assertTrue(result.output().contains("coalescePending: true\n"));
		assertTrue(result.output().contains("coalesceWindowMs: 20\n"));
		assertTrue(result.output().contains("queuedTriggerCount: 3\n"));
		assertTrue(result.output().contains("acceptedTurnCount: 8\n"));
		assertTrue(result.output().contains("projectedPendingNoticeCount: 2\n"));
		assertTrue(result.output().contains("overflowFlushPending: true\n"));
		assertTrue(result.output().contains("canonicalMessageCount: 11\n"));
		assertTrue(result.output().contains("projectedMessageCount: 14\n"));
	}

	@Test
	void agentContextVerboseIncludesContextExcerpt() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/context").payload = linkedMap(
			"available", true,
			"planner", linkedMap(
				"configured", true,
				"context", linkedMap(
					"acceptedTurnCount", 1
				)
			),
			"contextExcerpt", List.of(
				"Context update: You took 4 damage from minecraft:fall and dropped to 16 health just now."
			),
			"conversation", linkedMap("messageCount", 1),
			"canonicalConversation", linkedMap("messageCount", 1),
			"projectedConversation", linkedMap("messageCount", 2),
			"conversationSources", linkedMap(
				"canonicalMessageCount", 1,
				"projectedMessageCount", 2
			),
			"plannerJournal", linkedMap("eventCount", 3)
		);

		CliResult result = execute(transport, "agent", "context", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("contextExcerptLineCount: 1\n"));
		assertTrue(result.output().contains("projectedMessageCount: 2\n"));
		assertTrue(result.output().contains("[canonicalConversation]\n"));
		assertTrue(result.output().contains("[plannerJournal]\n"));
		assertTrue(result.output().contains("value: Context update: You took 4 damage from minecraft:fall and dropped to 16 health just now.\n"));
	}

	@Test
	void agentDialogueVerboseIncludesConversationAliases() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/dialogue").payload = linkedMap(
			"available", true,
			"dialogue", linkedMap(
				"pendingReply", false,
				"recentTurns", List.of()
			),
			"conversation", linkedMap("messageCount", 1),
			"canonicalConversation", linkedMap("messageCount", 1),
			"projectedConversation", linkedMap("messageCount", 2),
			"conversationSources", linkedMap(
				"canonicalMessageCount", 1,
				"projectedMessageCount", 2,
				"canonicalUserTurnCount", 1,
				"projectedUserTurnCount", 1
			),
			"plannerJournal", linkedMap("eventCount", 4),
			"lastChatTick", 42L,
			"lastChatText", "done"
		);

		CliResult result = execute(transport, "agent", "dialogue", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent dialogue\n"));
		assertTrue(result.output().contains("canonicalMessageCount: 1\n"));
		assertTrue(result.output().contains("projectedMessageCount: 2\n"));
		assertTrue(result.output().contains("[canonicalConversation]\n"));
		assertTrue(result.output().contains("[plannerJournal]\n"));
	}

	@Test
	void agentSessionOpenLanRendersDeterministicText() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/session/open-lan").payload = linkedMap(
			"opened", true,
			"port", 25565
		);

		CliResult result = execute(transport, "agent", "session", "open-lan");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().startsWith(
			"status: ok\n" +
				"command: agent session open-lan\n"
		));
		assertTrue(result.output().contains("opened: true\n"));
		assertTrue(result.output().contains("port: 25565\n"));
	}

	@Test
	void agentDebugChatPassesMessagePayload() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/debug/chat").payload = linkedMap(
			"available", true,
			"accepted", true,
			"senderName", "Player688",
			"message", "@agent get me 4 wood logs"
		);

		CliResult result = execute(
			transport,
			"agent", "debug", "chat",
			"--message", "@agent get me 4 wood logs"
		);

		assertEquals(0, result.exitCode());
		assertEquals("@agent get me 4 wood logs", transport.body("POST", "/v1/agent/debug/chat").get("message"));
		assertTrue(result.output().contains("command: agent debug chat\n"));
		assertTrue(result.output().contains("accepted: true\n"));
	}

	@Test
	void agentDebugNavigationPlanPostsTheGoal() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/debug/navigation/plan").payload = linkedMap(
			"outcome", "found",
			"steps", java.util.List.of("TRAVERSE -> 1,64,0")
		);

		CliResult result = execute(transport, "agent", "debug", "navigation", "plan",
			"--x", "1", "--y", "64", "--z", "0", "--no-exact-y", "--highlight-seconds", "5");

		assertEquals(0, result.exitCode());
		Map<String, Object> body = transport.body("POST", "/v1/agent/debug/navigation/plan");
		assertEquals(1, ((Number) body.get("x")).intValue());
		assertEquals(64, ((Number) body.get("y")).intValue());
		assertEquals(false, body.get("exactY"));
		assertEquals(5, ((Number) body.get("highlightSeconds")).intValue());
		assertTrue(result.output().contains("command: agent debug navigation plan\n"));
		assertTrue(result.output().contains("outcome: found\n"));
	}

	@Test
	void agentDebugIdleTriggerFiresManualTrigger() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/debug/idle-trigger").payload = linkedMap(
			"available", true,
			"accepted", true,
			"triggerType", "idle_think",
			"speaker", "self",
			"tick", 42L,
			"sessionMode", "SINGLEPLAYER_LAN_HOST"
		);

		CliResult result = execute(transport, "agent", "debug", "idle-trigger");

		assertEquals(0, result.exitCode());
		assertTrue(transport.requested("POST", "/v1/agent/debug/idle-trigger"));
		assertTrue(result.output().contains("command: agent debug idle-trigger\n"));
		assertTrue(result.output().contains("accepted: true\n"));
		assertTrue(result.output().contains("triggerType: idle_think\n"));
	}

	@Test
	void agentDebugStateShowsActionDispatchState() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/debug/state").payload = linkedMap(
			"available", true,
			"planner", linkedMap("configured", true),
			"dialogueState", linkedMap("pendingReply", false),
			"conversationSources", linkedMap("canonicalMessageCount", 1, "projectedMessageCount", 1),
			"taskProgressProbe", linkedMap("active", false),
			"chatProbe", linkedMap("lastSendSucceeded", true),
			"eventPipeline", linkedMap("lastEventType", "task.failed"),
			"plannerAttempts", List.of(),
			"timelineTail", List.of(),
			"activeJob", linkedMap(
				"type", "NAVIGATE_TO",
				"status", "FAILED",
				"lastError", "CALC_FAILED"
			),
			"taskExecution", linkedMap("state", "IDLE")
		);

		CliResult result = execute(transport, "agent", "debug", "state");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("[activeJob]\n"));
		assertTrue(result.output().contains("type: NAVIGATE_TO\n"));
		assertTrue(result.output().contains("lastError: CALC_FAILED\n"));
		assertTrue(result.output().contains("[taskExecution]\n"));
		assertTrue(result.output().contains("state: IDLE\n"));
	}

	@Test
	void agentDebugTimelinePassesSinceAndRendersEntries() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/debug/timeline").payload = linkedMap(
			"available", true,
			"oldestEntryId", 3,
			"latestEntryId", 7,
			"truncated", false,
			"entries", List.of(
				linkedMap(
					"entryId", 7,
					"tick", 120,
					"timestampMs", 123456L,
					"domain", "planner",
					"action", "failure",
					"summary", "TIMEOUT: LLM request timed out"
				)
			)
		);

		CliResult result = execute(transport, "agent", "debug", "timeline", "--since", "4");

		assertEquals(0, result.exitCode());
		assertEquals("/v1/agent/debug/timeline?since=4", transport.lastRequest("GET", "/v1/agent/debug/timeline").path());
		assertTrue(result.output().contains("command: agent debug timeline\n"));
		assertTrue(result.output().contains("entryCount: 1\n"));
		assertTrue(result.output().contains("summary: TIMEOUT: LLM request timed out\n"));
	}

	@Test
	void recordingQueryUsesExplicitServerRangeAndNoImagePayloads() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/debug/recording").payload = linkedMap("observations", List.of(), "nextCursor", 9);
		CliResult result = execute(transport, "agent", "debug", "recording", "query", "--from-server-tick", "20",
			"--to-server-tick", "40", "--since", "5", "--type", "decision_state", "--limit", "10");
		assertEquals(0, result.exitCode());
		assertEquals("/v1/agent/debug/recording?mode=query&since=5&limit=10&from=20&to=40&types=decision_state",
			transport.lastRequest("GET", "/v1/agent/debug/recording").path());
		assertTrue(result.output().contains("nextCursor: 9"));
	}

	@Test
	void recordingFrameWritesOnlyTheRequestedImage(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/debug/recording").payload = linkedMap("sequence", 7L,
			"payload", linkedMap("format", "jpeg", "imageBase64", "AQID"));
		Path output = tempDir.resolve("frame.jpg");
		CliResult result = execute(transport, "agent", "debug", "recording", "frame", "--sequence", "7", "--output", output.toString());
		assertEquals(0, result.exitCode());
		assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(output));
		assertFalse(result.output().contains("AQID"));
	}

	@Test
	void clientTickPauseSavesFrameAndRendersSnapshotIdentity(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/debug/ticks/pause").payload = linkedMap(
			"available", true,
			"debugSessionId", "debug-1",
			"pauseEpoch", 1L,
			"captureId", "capture-1",
			"snapshotId", "snapshot-1",
			"clientTickId", 44L,
			"snapshot", linkedMap(
				"capturedAtMs", 100L,
				"dimensionId", "minecraft:overworld",
				"worldTime", 200L,
				"timeOfDay", 200L,
				"player", linkedMap("blockX", 1, "blockY", 64, "blockZ", 2),
				"playerActions", linkedMap(
					"actions", List.of(linkedMap("action", "attack", "pressed", true, "started", true)),
					"breakProgress", linkedMap("position", linkedMap("x", 1, "y", 64, "z", 2), "progress", 0.6, "stage", 6, "started", true)
				),
				"plannerGeneration", 7L,
				"plannerPhase", "IDLE"
			),
			"frame", linkedMap("status", "CAPTURED", "format", "png", "width", 854, "height", 480),
			"imageBase64", java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})
		);
		Path output = tempDir.resolve("pause.png");

		CliResult result = execute(
			transport,
			"agent", "debug", "ticks", "pause", "--player-actions", "--output-image", output.toString()
		);

		assertEquals(0, result.exitCode());
		assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(output));
		assertTrue(result.output().contains("snapshotId: snapshot-1\n"));
		assertTrue(result.output().contains("clientTickId: 44\n"));
		assertTrue(result.output().contains("action: attack\n"));
		assertTrue(result.output().contains("stage: 6\n"));
		assertTrue(result.output().contains("imageOutputPath: " + output + "\n"));
		assertEquals(true, transport.body("POST", "/v1/agent/debug/ticks/pause").get("playerActions"));
	}

	@Test
	void clientTickStepPassesExactSessionAndPauseEpoch() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/debug/ticks/step").payload = linkedMap(
			"available", true,
			"debugSessionId", "debug-1",
			"pauseEpoch", 3L,
			"snapshotId", "snapshot-3",
			"clientTickId", 46L,
			"snapshot", Map.of(),
			"frame", linkedMap("status", "CAPTURED")
		);

		CliResult result = execute(
			transport,
			"agent", "debug", "ticks", "step",
			"--debug-session-id", "debug-1",
			"--pause-epoch", "2"
		);

		assertEquals(0, result.exitCode());
		assertEquals("debug-1", transport.body("POST", "/v1/agent/debug/ticks/step").get("debugSessionId"));
		assertEquals(2L, transport.body("POST", "/v1/agent/debug/ticks/step").get("pauseEpoch"));
		assertFalse(result.output().contains("imageBase64"));
	}

	@Test
	void clientTickTraceStartBuildsSelectedQueriesAndWindow(@TempDir Path tempDir) {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/debug/trace/start").payload = linkedMap(
			"active", true,
			"traceId", "trace-1",
			"startedClientTickId", 40L,
			"windowTicks", 100
		);
		transport.when("POST", "/v1/agent/debug/trace/records").payload = linkedMap(
			"active", false,
			"complete", true,
			"records", List.of()
		);

		CliResult result = execute(
			transport,
			"agent", "debug", "trace", "start",
			"--info", "metadata,player-state,player-actions,entities,frame",
			"--window-ticks", "100",
			"--output", tempDir.resolve("trace.jsonl").toString(),
			"--entity-query", "{\"radius\":16,\"entityTypeIds\":[\"minecraft:zombie\"],\"limit\":12}"
		);

		assertEquals(0, result.exitCode());
		assertEquals(
			List.of("metadata", "player_state", "player_actions", "entities", "frame"),
			transport.body("POST", "/v1/agent/debug/trace/start").get("infos")
		);
		assertEquals(100, transport.body("POST", "/v1/agent/debug/trace/start").get("windowTicks"));
		assertEquals(false, transport.body("POST", "/v1/agent/debug/trace/start").get("once"));
		Map<String, Object> entityQuery = castMap(transport.body("POST", "/v1/agent/debug/trace/start").get("entityQuery"));
		assertEquals(16, entityQuery.get("radius"));
		assertEquals(List.of("minecraft:zombie"), entityQuery.get("entityTypeIds"));
		assertEquals(12, entityQuery.get("limit"));
	}

	@Test
	void clientTickTraceStartRequiresBlockQueryForBlockRecords() {
		TestTransport transport = new TestTransport();

		CliResult result = execute(
			transport,
			"agent", "debug", "trace", "start",
			"--info", "blocks",
			"--window-ticks", "20",
			"--output", "trace.jsonl"
		);

		assertEquals(2, result.exitCode());
		assertTrue(result.output().contains("error_code: invalid_arguments\n"));
		assertTrue(transport.body("POST", "/v1/agent/debug/trace/start") == null);
	}

	@Test
	void clientTickTraceStartRejectsLargeFrameWindow() {
		TestTransport transport = new TestTransport();

		CliResult result = execute(
			transport,
			"agent", "debug", "trace", "start",
			"--info", "frame",
			"--window-ticks", "201",
			"--output", "trace.jsonl"
		);

		assertEquals(2, result.exitCode());
		assertTrue(result.output().contains("window-ticks must not exceed 200 with frame tracing"));
	}

	@Test
	void clientTickTraceStartStreamsJsonLinesAndFrames(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/debug/trace/start").payload = linkedMap(
			"active", true,
			"traceId", "trace-1",
			"startedClientTickId", 40L,
			"windowTicks", 2,
			"once", true
		);
		transport.when("POST", "/v1/agent/debug/trace/records").payload = linkedMap(
			"traceId", "trace-1",
			"active", false,
			"complete", true,
			"records", List.of(linkedMap(
				"clientTickId", 42L,
				"frame", linkedMap(
					"status", "CAPTURED",
					"format", "png",
					"imageBase64", java.util.Base64.getEncoder().encodeToString(new byte[]{7, 8, 9})
				)
			))
		);
		Path output = tempDir.resolve("trace.jsonl");

		CliResult result = execute(
			transport,
			"agent", "debug", "trace", "start",
			"--info", "frame",
			"--window-ticks", "2",
			"--once",
			"--output", output.toString()
		);

		List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
		assertEquals(0, result.exitCode());
		assertEquals(3, lines.size());
		assertTrue(lines.getFirst().contains("\"event\":\"trace_start\""));
		assertTrue(lines.get(1).contains("\"event\":\"trace_record\""));
		assertTrue(lines.get(1).contains("\"imageBase64\":\"BwgJ\""));
		assertTrue(lines.getLast().contains("\"event\":\"trace_end\""));
		Map<String, Object> recordsRequest = transport.body("POST", "/v1/agent/debug/trace/records");
		assertEquals("trace-1", recordsRequest.get("traceId"));
		assertEquals(40L, recordsRequest.get("sinceClientTickId"));
		assertEquals(256, recordsRequest.get("limit"));
		assertEquals(true, recordsRequest.get("includeImageBytes"));
		assertEquals(true, transport.body("POST", "/v1/agent/debug/trace/start").get("once"));
		assertFalse(result.output().contains("imageBase64"));
	}

	@Test
	void clientTickTraceDoesNotExposeARecordsCommand() {
		TestTransport transport = new TestTransport();

		CliResult result = execute(
			transport,
			"agent", "debug", "trace", "records",
			"--trace-id", "trace-1"
		);

		assertEquals(2, result.exitCode());
		assertFalse(transport.requested("POST", "/v1/agent/debug/trace/records"));
	}

	@Test
	void clientTickWorldScanBoxBuildsBoundedRegionRequest() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/debug/world/query").payload = linkedMap("snapshotId", "snapshot-3", "blocks", List.of());

		CliResult result = execute(
			transport,
			"agent", "debug", "world", "scan-box",
			"--snapshot-id", "snapshot-3",
			"--min-x", "-10", "--min-y", "60", "--min-z", "20",
			"--max-x", "10", "--max-y", "70", "--max-z", "40",
			"--cursor", "100", "--limit", "512"
		);

		assertEquals(0, result.exitCode());
		assertEquals("snapshot-3", transport.body("POST", "/v1/agent/debug/world/query").get("snapshotId"));
		assertEquals("scan_box", transport.body("POST", "/v1/agent/debug/world/query").get("operation"));
		assertEquals(-10, transport.body("POST", "/v1/agent/debug/world/query").get("minX"));
		assertEquals(40, transport.body("POST", "/v1/agent/debug/world/query").get("maxZ"));
		assertEquals(100L, transport.body("POST", "/v1/agent/debug/world/query").get("cursor"));
		assertEquals(512, transport.body("POST", "/v1/agent/debug/world/query").get("limit"));
	}

	@Test
	void clientTickWorldPlayerStateBuildsSnapshotRequest() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/debug/world/query").payload = linkedMap("snapshotId", "snapshot-3", "player", Map.of());

		CliResult result = execute(
			transport,
			"agent", "debug", "world", "player-state",
			"--snapshot-id", "snapshot-3"
		);

		assertEquals(0, result.exitCode());
		assertEquals("snapshot-3", transport.body("POST", "/v1/agent/debug/world/query").get("snapshotId"));
		assertEquals("player_state", transport.body("POST", "/v1/agent/debug/world/query").get("operation"));
	}

	@Test
	void clientTickWorldEntitiesBuildsRadiusAndFilterRequest() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/debug/world/query").payload = linkedMap("snapshotId", "snapshot-3", "entities", List.of());

		CliResult result = execute(
			transport,
			"agent", "debug", "world", "entities",
			"--snapshot-id", "snapshot-3",
			"--radius", "16",
			"--name", "Sheep",
			"--entity-type-id", "minecraft:sheep,minecraft:cow",
			"--alive=false",
			"--living-only",
			"--include-self",
			"--cursor", "4",
			"--limit", "5"
		);

		assertEquals(0, result.exitCode());
		assertEquals("entities", transport.body("POST", "/v1/agent/debug/world/query").get("operation"));
		assertEquals(16.0D, transport.body("POST", "/v1/agent/debug/world/query").get("radius"));
		assertFalse(transport.body("POST", "/v1/agent/debug/world/query").containsKey("centerX"));
		assertEquals("Sheep", transport.body("POST", "/v1/agent/debug/world/query").get("name"));
		assertEquals(List.of("minecraft:sheep", "minecraft:cow"), transport.body("POST", "/v1/agent/debug/world/query").get("entityTypeIds"));
		assertEquals(false, transport.body("POST", "/v1/agent/debug/world/query").get("alive"));
		assertEquals(true, transport.body("POST", "/v1/agent/debug/world/query").get("livingOnly"));
		assertEquals(true, transport.body("POST", "/v1/agent/debug/world/query").get("includeSelf"));
		assertEquals(4L, transport.body("POST", "/v1/agent/debug/world/query").get("cursor"));
		assertEquals(5, transport.body("POST", "/v1/agent/debug/world/query").get("limit"));
	}

	@Test
	void clientTickWorldEntitiesBuildsRegionAndIdentityRequest() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/debug/world/query").payload = linkedMap("snapshotId", "snapshot-3", "entities", List.of());

		CliResult result = execute(
			transport,
			"agent", "debug", "world", "entities",
			"--snapshot-id", "snapshot-3",
			"--min-x", "-2", "--min-y", "60", "--min-z", "-3",
			"--max-x", "2", "--max-y", "70", "--max-z", "3",
			"--entity-id", "42",
			"--uuid", "entity-42"
		);

		assertEquals(0, result.exitCode());
		assertEquals(-2, transport.body("POST", "/v1/agent/debug/world/query").get("minX"));
		assertEquals(3, transport.body("POST", "/v1/agent/debug/world/query").get("maxZ"));
		assertEquals(42, transport.body("POST", "/v1/agent/debug/world/query").get("entityId"));
		assertEquals("entity-42", transport.body("POST", "/v1/agent/debug/world/query").get("uuid"));
	}

	@Test
	void clientTickWorldEntitiesRejectsPartialRegion() {
		TestTransport transport = new TestTransport();

		CliResult result = execute(
			transport,
			"agent", "debug", "world", "entities",
			"--snapshot-id", "snapshot-3",
			"--min-x", "0"
		);

		assertEquals(2, result.exitCode());
		assertTrue(result.output().contains("error_code: invalid_arguments\n"));
		assertTrue(transport.body("POST", "/v1/agent/debug/world/query") == null);
	}

	@Test
	void clientTickWorldFindBlocksUsesMatchItemLabel() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/debug/world/query").payload = linkedMap(
			"matches", List.of(linkedMap("id", "minecraft:stone"))
		);

		CliResult result = execute(
			transport,
			"agent", "debug", "world", "find-blocks",
			"--snapshot-id", "snapshot-3",
			"--min-x", "0", "--min-y", "0", "--min-z", "0",
			"--max-x", "0", "--max-y", "0", "--max-z", "0",
			"--block-id", "minecraft:stone"
		);

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("[match 1]\n"));
		assertFalse(result.output().contains("[matche 1]\n"));
	}

	@Test
	void agentGoalsShowsActiveDirectJobFailure() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/goals").payload = linkedMap(
			"available", true,
			"activeJob", linkedMap(
				"type", "NAVIGATE_TO",
				"status", "FAILED",
				"lastError", "CALC_FAILED"
			),
			"taskExecution", linkedMap("state", "IDLE")
		);

		CliResult result = execute(transport, "agent", "goals", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("[activeJob]\n"));
		assertTrue(result.output().contains("type: NAVIGATE_TO\n"));
		assertTrue(result.output().contains("status: FAILED\n"));
		assertTrue(result.output().contains("lastError: CALC_FAILED\n"));
	}

	@Test
	void agentStatusIncludesPlannerVisionMode() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/status").payload = linkedMap(
			"available", true,
			"initialized", true,
			"tickCount", 42,
			"codexDriverActive", true,
			"llmAvailable", true,
			"visionAvailable", false,
			"plannerVisionMode", "native_tool_image",
			"reflex", linkedMap("state", "AWAITING_PLANNER", "holdId", "hold-42"),
			"degraded", false
		);

		CliResult result = execute(transport, "agent", "status");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent status\n"));
		assertTrue(result.output().contains("codexDriverActive: true\n"));
		assertTrue(result.output().contains("plannerVisionMode: native_tool_image\n"));
		assertTrue(result.output().contains("state: AWAITING_PLANNER\n"));
		assertTrue(result.output().contains("holdId: hold-42\n"));
	}

	@Test
	void agentToolsListRendersCompleteFunctionSchemas() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/tools").payload = linkedMap(
			"available", true,
			"codexDriverActive", true,
			"toolCount", 1,
			"tools", List.of(linkedMap(
				"type", "function",
				"function", linkedMap(
					"name", "navigate_to",
					"description", "Navigate to a block position.",
					"parameters", linkedMap("type", "object", "required", List.of("x", "y", "z"))
				)
			))
		);

		CliResult result = execute(transport, "agent", "tools", "list", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent tools list\n"));
		assertTrue(result.output().contains("codexDriverActive: true\n"));
		assertTrue(result.output().contains("name: navigate_to\n"));
		assertTrue(result.output().contains("[parameters]\n"));
	}

	@Test
	void agentToolsCallPassesJsonAndWritesReturnedImage(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/tools").payload = linkedMap(
			"available", true,
			"codexDriverActive", true,
			"toolName", "take_a_look",
			"result", "current view attached",
			"imageAttached", true,
			"imageMimeType", "image/png",
			"imageDetail", "high",
			"imageBase64", java.util.Base64.getEncoder().encodeToString(new byte[]{4, 5, 6})
		);
		Path output = tempDir.resolve("look.png");

		CliResult result = execute(
			transport,
			"agent", "tools", "call",
			"--name", "take_a_look",
			"--arguments", "{\"direction\":\"north\"}",
			"--timeout-seconds", "30",
			"--output-image", output.toString()
		);

		assertEquals(0, result.exitCode());
		Map<String, Object> toolRequest = transport.body("POST", "/v1/agent/tools");
		assertEquals("take_a_look", toolRequest.get("name"));
		assertEquals("north", castMap(toolRequest.get("arguments")).get("direction"));
		assertEquals(30_000, toolRequest.get("timeoutMs"));
		assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(output));
		assertTrue(result.output().contains("imageOutputPath: " + output.toAbsolutePath().normalize() + "\n"));
		assertFalse(result.output().contains("imageBase64"));
	}

	@Test
	void agentEventPolicyShowsRuleSummary() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/event-policy").payload = linkedMap(
			"available", true,
			"activeRuleCount", 2,
			"recentInterventionCount", 1,
			"lastDecision", linkedMap(
				"matchedRuleId", "mute-system",
				"effect", "IGNORE",
				"reason", "suppress noisy system spam",
				"bypassed", false
			),
			"activeRules", List.of(
				linkedMap("ruleId", "mute-system", "effect", "IGNORE")
			),
			"recentInterventions", List.of(
				linkedMap("matchedRuleId", "mute-system", "effect", "IGNORE")
			)
		);

		CliResult result = execute(transport, "agent", "event-policy");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent event-policy\n"));
		assertTrue(result.output().contains("activeRuleCount: 2\n"));
		assertTrue(result.output().contains("matchedRuleId: mute-system\n"));
	}

	@Test
	void playerAttackEntityPassesSelectorPayload() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/player/attack-entity").payload = linkedMap("accepted", true, "task", linkedMap("state", "QUEUED"));

		CliResult result = execute(transport, "player", "attack-entity", "--entity-type-id", "minecraft:sheep", "--mode", "hit_once");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: player attack-entity\n"));
		assertEquals("minecraft:sheep", transport.body("POST", "/v1/player/attack-entity").get("entityTypeId"));
		assertEquals("hit_once", transport.body("POST", "/v1/player/attack-entity").get("mode"));
	}

	@Test
	void playerNearbyEntitiesCallsTransport() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/player/nearby-entities").payload = linkedMap(
			"entityCount", 1,
			"entities", List.of(linkedMap("entityTypeId", "minecraft:sheep", "name", "Sheep", "distance", 3.0))
		);

		CliResult result = execute(transport, "player", "nearby-entities");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: player nearby-entities\n"));
		assertTrue(result.output().contains("entityCount: 1\n"));
		assertTrue(transport.requested("GET", "/v1/player/nearby-entities"));
	}

	@Test
	void playerUseEntityPassesSelectorAndItemPayload() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/player/use-entity").payload = linkedMap("accepted", true, "task", linkedMap("state", "QUEUED"));

		CliResult result = execute(
			transport,
			"player", "use-entity",
			"--name", "Dinner",
			"--item-id", "minecraft:shears"
		);

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: player use-entity\n"));
		assertEquals("Dinner", transport.body("POST", "/v1/player/use-entity").get("name"));
		assertEquals("minecraft:shears", transport.body("POST", "/v1/player/use-entity").get("itemId"));
	}

	@Test
	void playerLookAtPassesDurationOverride() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/player/look-at").payload = linkedMap("available", true, "durationTicks", 12, "scheduled", true);

		CliResult result = execute(
			transport,
			"player", "look-at",
			"--x", "1.5",
			"--y", "64",
			"--z", "-2.25",
			"--duration-ticks", "12"
		);

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: player look-at\n"));
		assertEquals(1.5D, transport.body("POST", "/v1/player/look-at").get("x"));
		assertEquals(64.0D, transport.body("POST", "/v1/player/look-at").get("y"));
		assertEquals(-2.25D, transport.body("POST", "/v1/player/look-at").get("z"));
		assertEquals(12, transport.body("POST", "/v1/player/look-at").get("durationTicks"));
		assertTrue(result.output().contains("durationTicks: 12\n"));
		assertTrue(result.output().contains("scheduled: true\n"));
	}

	@Test
	void agentEventPolicyClearCallsTransport() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/event-policy/clear").payload = linkedMap(
			"available", true,
			"activeRuleCount", 0,
			"recentInterventionCount", 0,
			"activeRules", List.of(),
			"recentInterventions", List.of()
		);

		CliResult result = execute(transport, "agent", "event-policy", "clear");

		assertEquals(0, result.exitCode());
		assertTrue(transport.requested("POST", "/v1/agent/event-policy/clear"));
		assertTrue(result.output().contains("command: agent event-policy clear\n"));
		assertTrue(result.output().contains("activeRuleCount: 0\n"));
	}

	@Test
	void agentTasksShowsCurrentTaskSnapshot() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/tasks").payload = linkedMap(
			"available", true,
			"reflex", linkedMap("state", "IDLE", "health", 20.0D),
			"taskExecution", linkedMap("state", "RUNNING", "taskId", "task-1", "fullPath", List.of("large", "payload")),
			"missionExecution", linkedMap("evidence", linkedMap("knownCrafts", List.of("must-not-render"))),
			"task", linkedMap(
				"state", "RUNNING",
				"spec", linkedMap(
					"type", "COLLECT_RESOURCE",
					"resourceKind", "WOOD_LOGS",
					"quantity", 4
				)
			)
		);

		CliResult result = execute(transport, "agent", "tasks");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent tasks\n"));
		assertTrue(result.output().contains("state: RUNNING\n"));
		assertTrue(result.output().contains("resourceKind: WOOD_LOGS\n"));
		assertTrue(result.output().contains("taskId: task-1\n"));
		assertFalse(result.output().contains("fullPath"));
		assertFalse(result.output().contains("knownCrafts"));
	}

	@Test
	void agentTasksSubmitPassesNormalizedTaskPayload() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/tasks").payload = linkedMap(
			"available", true,
			"task", linkedMap("state", "QUEUED")
		);

		CliResult result = execute(
			transport,
			"agent", "tasks", "submit",
			"--type", "collect-resource",
			"--resource", "wood-logs",
			"--quantity", "4"
		);

		assertEquals(0, result.exitCode());
		assertEquals("COLLECT_RESOURCE", transport.body("POST", "/v1/agent/tasks").get("type"));
		assertEquals("WOOD_LOGS", transport.body("POST", "/v1/agent/tasks").get("resourceKind"));
		assertEquals(4, transport.body("POST", "/v1/agent/tasks").get("quantity"));
		assertTrue(result.output().contains("command: agent tasks submit\n"));
	}

	@Test
	void agentTasksResumePassesSafetyHoldId() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/tasks/resume").payload = linkedMap(
			"available", true,
			"resumed", true,
			"holdId", "hold-42",
			"reflex", linkedMap("state", "IDLE")
		);

		CliResult result = execute(transport, "agent", "tasks", "resume", "--hold-id", "hold-42");

		assertEquals(0, result.exitCode());
		assertEquals("hold-42", transport.body("POST", "/v1/agent/tasks/resume").get("holdId"));
		assertTrue(result.output().contains("command: agent tasks resume\n"));
		assertTrue(result.output().contains("resumed: true\n"));
		assertTrue(result.output().contains("holdId: hold-42\n"));
	}

	@Test
	void agentLedgerRendersCurrentMissionLedger() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/ledger").payload = linkedMap(
			"available", true,
			"ledger", linkedMap(
				"missionId", "mission-wood-1",
				"activeStepId", "collect_logs"
			)
		);

		CliResult result = execute(transport, "agent", "ledger");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent ledger\n"));
		assertTrue(result.output().contains("missionId: mission-wood-1\n"));
		assertTrue(result.output().contains("activeStepId: collect_logs\n"));
	}

	@Test
	void agentEvidenceRendersWorldEvidenceSummary() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/evidence").payload = linkedMap(
			"available", true,
			"evidence", linkedMap(
				"dimension", "minecraft:overworld",
				"inventoryCounts", linkedMap(
					"WOOD_LOGS", 4
				)
			)
		);

		CliResult result = execute(transport, "agent", "evidence");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent evidence\n"));
		assertTrue(result.output().contains("dimension: minecraft:overworld\n"));
		assertTrue(result.output().contains("WOOD_LOGS: 4\n"));
	}

	@Test
	void agentStepExecutionRendersLatestStepResult() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/step-execution").payload = linkedMap(
			"available", true,
			"stepExecution", linkedMap(
				"stepId", "craft_sticks",
				"status", "RUNNING"
			)
		);

		CliResult result = execute(transport, "agent", "step-execution");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent step-execution\n"));
		assertTrue(result.output().contains("stepId: craft_sticks\n"));
		assertTrue(result.output().contains("status: RUNNING\n"));
	}

	@Test
	void agentActionsInspectRendersActionGraphSummary() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/action-graph/inspect").payload = linkedMap(
			"available", true,
			"worldLoaded", false,
			"sessionState", "title_screen",
			"primitiveCount", 2,
			"domainProviderCount", 4,
			"primitives", List.of(linkedMap(
				"id", "craft_item",
				"version", 1,
				"summary", "Craft an item",
				"foregroundActuation", true,
				"executorBinding", "WorldTaskRequest.CRAFT_RECIPE"
			)),
			"domainProviders", List.of(linkedMap(
				"id", "recipe_provider",
				"summary", "Plans craft routes",
				"producedGoal", "inventory.item"
			))
		);

		CliResult result = execute(transport, "agent", "actions", "inspect");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent actions inspect\n"));
		assertTrue(result.output().contains("primitiveCount: 2\n"));
		assertTrue(result.output().contains("id: craft_item\n"));
		assertTrue(result.output().contains("id: recipe_provider\n"));
	}

	@Test
	void agentActionsGoalStartSubmitsInventoryGoal() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/action-goals").payload = actionGoalPayload("action-graph-1", "RESOLVING");

		CliResult result = execute(
			transport,
			"agent", "actions", "goal", "start",
			"--item-id", "minecraft:bread",
			"--quantity", "2"
		);

		assertEquals(0, result.exitCode());
		assertEquals("inventory_item", transport.body("POST", "/v1/agent/action-goals").get("kind"));
		assertEquals("minecraft:bread", transport.body("POST", "/v1/agent/action-goals").get("itemId"));
		assertEquals(2, transport.body("POST", "/v1/agent/action-goals").get("quantity"));
		assertTrue(result.output().contains("command: agent actions goal start\n"));
		assertTrue(result.output().contains("executionId: action-graph-1\n"));
		assertTrue(result.output().contains("state: RESOLVING\n"));
		assertTrue(result.output().contains("executionPhase: PLANNING\n"));
		assertTrue(result.output().contains("activePrimitive: false\n"));
	}

	@Test
	void agentActionsGoalStartSubmitsResourceGoal() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/action-goals").payload = actionGoalPayload("action-graph-raw-iron", "RESOLVING");

		CliResult result = execute(
			transport,
			"agent", "actions", "goal", "start",
			"--kind", "resource_collection",
			"--resource-kind", "RAW_IRON",
			"--quantity", "3"
		);

		assertEquals(0, result.exitCode());
		assertEquals("resource_collection", transport.body("POST", "/v1/agent/action-goals").get("kind"));
		assertEquals("RAW_IRON", transport.body("POST", "/v1/agent/action-goals").get("resourceKind"));
		assertEquals(3, transport.body("POST", "/v1/agent/action-goals").get("quantity"));
		assertTrue(result.output().contains("executionId: action-graph-raw-iron\n"));
	}

	@Test
	void agentActionsGoalInspectAndCancelRenderSnapshot() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/action-goals").payload = actionGoalPayload("action-graph-2", "WAITING_PRIMITIVE");
		transport.when("DELETE", "/v1/agent/action-goals").payload = actionGoalPayload("action-graph-2", "CANCELLED");

		CliResult inspect = execute(transport, "agent", "actions", "goal", "inspect");
		CliResult cancel = execute(transport, "agent", "actions", "goal", "cancel");

		assertEquals(0, inspect.exitCode());
		assertEquals(0, cancel.exitCode());
		assertTrue(inspect.output().contains("command: agent actions goal inspect\n"));
		assertTrue(inspect.output().contains("state: WAITING_PRIMITIVE\n"));
		assertTrue(cancel.output().contains("command: agent actions goal cancel\n"));
		assertTrue(cancel.output().contains("state: CANCELLED\n"));
	}

	@Test
	void agentActionsGoalListAndIdSpecificOperationsUseSchedulerSurface() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/action-goals?list=true").payload = linkedMap(
			"available", true,
			"foregroundExecutionId", "graph-foreground",
			"executionCount", 2,
			"nonterminalCount", 2,
			"suspendedCount", 1,
			"runnableCount", 0,
			"watchCount", 1,
			"executions", List.of(
				linkedMap("executionId", "graph-suspended", "state", "WATCHING", "residency", "SUSPENDED", "watchCount", 1, "updatedTick", 10),
				linkedMap("executionId", "graph-foreground", "state", "WAITING_PRIMITIVE", "residency", "FOREGROUND", "watchCount", 0, "updatedTick", 11)
			),
			"watches", List.of(linkedMap("executionId", "graph-suspended", "watchId", "watch-1", "stepId", "wait", "progressKind", "AREA_TICKING", "consumedEligibleTicks", 4, "timeoutTicks", 20, "progressEligible", true, "pauseReason", ""))
		);
		transport.when("GET", "/v1/agent/action-goals").payload = actionGoalPayload("graph-suspended", "WATCHING");
		transport.when("DELETE", "/v1/agent/action-goals").payload = actionGoalPayload("graph-suspended", "CANCELLED");

		CliResult list = execute(transport, "agent", "actions", "goal", "list");
		CliResult inspect = execute(transport, "agent", "actions", "goal", "inspect", "--execution-id", "graph-suspended");
		CliResult cancel = execute(transport, "agent", "actions", "goal", "cancel", "--execution-id", "graph-suspended");

		assertEquals(0, list.exitCode());
		assertTrue(list.output().contains("executionCount: 2\n"));
		assertTrue(list.output().contains("residency: SUSPENDED\n"));
		assertTrue(transport.lastRequest("GET", "/v1/agent/action-goals").path().contains("execution-id=graph-suspended"));
		assertTrue(transport.lastRequest("DELETE", "/v1/agent/action-goals").path().contains("execution-id=graph-suspended"));
		assertEquals(0, inspect.exitCode());
		assertEquals(0, cancel.exitCode());
	}

	@Test
	void agentActionsRejectsRemovedFactsCommand() {
		CliResult result = execute(new TestTransport(), "agent", "actions", "facts", "list");

		assertEquals(2, result.exitCode());
		assertTrue(result.output().contains("error_code: invalid_arguments\n"));
	}

	@Test
	void agentActionsWatchesListRendersActiveWatchSummary() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/action-goals?list=true").payload = linkedMap(
			"available", true,
			"foregroundExecutionId", "",
			"executionCount", 1,
			"nonterminalCount", 1,
			"suspendedCount", 1,
			"runnableCount", 0,
			"watchCount", 1,
			"watches", List.of(linkedMap(
				"executionId", "action-graph-watch",
				"watchId", "action-graph-watch:wait_for_bread",
				"stepId", "wait_for_bread",
				"progressKind", "AREA_TICKING",
				"consumedEligibleTicks", 3,
				"timeoutTicks", 20,
				"progressEligible", true,
				"pauseReason", ""
			))
		);

		CliResult result = execute(transport, "agent", "actions", "watches", "list");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent actions watches list\n"));
		assertTrue(result.output().contains("watchCount: 1\n"));
		assertTrue(result.output().contains("watchId: action-graph-watch:wait_for_bread\n"));
	}

	@Test
	void agentMissionSubmitPassesNormalizedMissionPayload() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/tasks").payload = linkedMap(
			"available", true,
			"task", linkedMap("state", "QUEUED")
		);

		CliResult result = execute(
			transport,
			"agent", "mission", "submit",
			"--type", "collect-resource",
			"--resource", "wood-logs",
			"--quantity", "4"
		);

		assertEquals(0, result.exitCode());
		assertEquals("COLLECT_RESOURCE", transport.body("POST", "/v1/agent/tasks").get("type"));
		assertEquals("WOOD_LOGS", transport.body("POST", "/v1/agent/tasks").get("resourceKind"));
		assertEquals(4, transport.body("POST", "/v1/agent/tasks").get("quantity"));
		assertTrue(result.output().contains("command: agent mission submit\n"));
	}

	@Test
	void agentMissionSubmitReadsLedgerPayloadFromFile(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/tasks").payload = linkedMap(
			"available", true,
			"task", linkedMap("state", "QUEUED")
		);
		Path ledgerFile = tempDir.resolve("ledger.json");
		Files.writeString(
			ledgerFile,
			"""
				{
				  "missionId": "mission-craft-1",
				  "missionType": "CRAFT_TOOL",
				  "goalText": "Turn wood into sticks",
				  "steps": [],
				  "activeStepId": null,
				  "completionCriteria": [],
				  "replanReason": "debug",
				  "plannerNotes": "manual"
				}
				""",
			StandardCharsets.UTF_8
		);

		CliResult result = execute(
			transport,
			"agent", "mission", "submit",
			"--ledger-file", ledgerFile.toString()
		);

		assertEquals(0, result.exitCode());
		assertEquals("mission-craft-1", transport.body("POST", "/v1/agent/tasks").get("missionId"));
		assertEquals("CRAFT_TOOL", transport.body("POST", "/v1/agent/tasks").get("missionType"));
		assertTrue(result.output().contains("command: agent mission submit\n"));
	}

	@Test
	void agentCompactPassesWaitAndTimeout() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/agent/debug/compact").payload = linkedMap(
			"available", true,
			"started", true,
			"completed", false,
			"timeoutMs", 7000,
			"planner", linkedMap(
				"configured", true,
				"plannerVisionMode", "external_summary",
				"inFlight", true,
				"plannerInFlight", false,
				"compactionInFlight", true,
				"captureInFlight", false,
				"toolInFlight", false,
				"context", linkedMap(
					"compactionPending", false,
					"acceptedTurnCount", 5
				)
			)
		);

		CliResult result = execute(transport, "agent", "compact", "--no-wait", "--timeout-seconds", "7");

		assertEquals(0, result.exitCode());
		assertEquals(false, transport.body("POST", "/v1/agent/debug/compact").get("wait"));
		assertEquals(7000, transport.body("POST", "/v1/agent/debug/compact").get("timeoutMs"));
		assertTrue(result.output().contains("command: agent compact\n"));
		assertTrue(result.output().contains("completed: false\n"));
	}

	@Test
	void evaluationStatusShowsCapabilities() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/evaluation/status").payload = linkedMap(
			"available", true,
			"sessionMode", "SINGLEPLAYER_LOCAL",
			"worldLoaded", true,
			"scenarioRoot", "scenarios",
			"capabilities", List.of("scenario_list", "planner_loop"),
			"report", linkedMap(
				"status", "IDLE",
				"plannerTurns", 0,
				"elapsedTicks", 0,
				"evidenceReviewRequired", false
			)
		);

		CliResult result = execute(transport, "evaluation", "status", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: evaluation status\n"));
		assertTrue(result.output().contains("capabilityCount: 2\n"));
		assertTrue(result.output().contains("scenarioRoot: scenarios\n"));
		assertTrue(result.output().contains("[capability 1]\n"));
		assertTrue(result.output().contains("value: scenario_list\n"));
	}

	@Test
	void evaluationScenariosRendersTrackedScenarioSummary() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/evaluation/scenarios").payload = linkedMap(
			"available", true,
			"scenarioRoot", "scenarios",
			"scenarios", List.of(linkedMap(
				"id", "smelting-basic",
				"name", "Smelting basic",
				"frozen", true,
				"promptConfigured", true,
				"checkCount", 1,
				"maxPlannerTurns", 8,
				"maxElapsedTicks", 1200
			))
		);

		CliResult result = execute(transport, "evaluation", "scenarios");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: evaluation scenarios\n"));
		assertTrue(result.output().contains("scenarioCount: 1\n"));
		assertTrue(result.output().contains("id: smelting-basic\n"));
		assertTrue(result.output().contains("promptConfigured: true\n"));
	}

	@Test
	void evaluationConfigShowsCurrentWorldScenarioConfig() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/evaluation/config").payload = linkedMap(
			"available", true,
			"scenarioId", "smelting-basic",
			"configPath", "/repo/scenarios/smelting-basic/scenario.yml",
			"worldArchivePath", "/repo/scenarios/smelting-basic/world.zip",
			"scenarioRoot", "/repo/scenarios",
			"scenario", linkedMap(
				"id", "smelting-basic",
				"name", "Smelting basic",
				"frozen", true,
				"promptConfigured", true,
				"checkCount", 1,
				"worldArchive", "world.zip"
			)
		);

		CliResult result = execute(transport, "evaluation", "config");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: evaluation config\n"));
		assertTrue(result.output().contains("scenarioId: smelting-basic\n"));
		assertTrue(result.output().contains("configPath: /repo/scenarios/smelting-basic/scenario.yml\n"));
		assertTrue(result.output().contains("frozen: true\n"));
	}

	@Test
	void evaluationRunPassesScenarioNameAndOutputDir() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/evaluation/run").payload = linkedMap(
			"accepted", true,
			"scenario", "smelting-basic",
			"worldName", "airicraft_eval_smelting-basic_1",
			"report", linkedMap(
				"status", "PENDING_WORLD",
				"scenarioId", "smelting-basic",
				"message", "Waiting for evaluation world",
				"plannerTurns", 0,
				"elapsedTicks", 0,
				"evidenceReviewRequired", false
			)
		);

		CliResult result = execute(
			transport,
			"evaluation", "run",
			"--scenario", "smelting-basic",
			"--output-dir", "/tmp/eval-output/run-1"
		);

		assertEquals(0, result.exitCode());
		assertEquals("smelting-basic", transport.body("POST", "/v1/evaluation/run").get("scenario"));
		assertEquals("/tmp/eval-output/run-1", transport.body("POST", "/v1/evaluation/run").get("outputDir"));
		assertTrue(result.output().contains("scenario: smelting-basic\n"));
		assertTrue(result.output().contains("reportStatus: PENDING_WORLD\n"));
	}

	@Test
	void evaluationResultsVerboseShowsDiagnostics() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/evaluation/results").payload = linkedMap(
			"available", true,
			"report", linkedMap(
				"status", "FAILED",
				"scenarioId", "smelting-basic",
				"message", "Evaluation budget exhausted before expected outcome",
				"plannerTurns", 8,
				"elapsedTicks", 1200,
				"checks", List.of(linkedMap(
					"type", "inventory_contains",
					"passed", false,
					"message", "inventory contains 0x minecraft:iron_ingot, expected at least 1"
				)),
				"evidenceReviewRequired", true,
				"diagnostics", linkedMap(
					"maxPlannerTurns", 8
				)
			)
		);

		CliResult result = execute(transport, "evaluation", "results", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("reportStatus: FAILED\n"));
		assertTrue(result.output().contains("reportScenarioId: smelting-basic\n"));
		assertTrue(result.output().contains("[diagnostics]\n"));
		assertTrue(result.output().contains("maxPlannerTurns: 8\n"));
	}

	@Test
	void evaluationEvidenceVerboseShowsEvidenceBundle() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/evaluation/evidence").payload = linkedMap(
			"available", true,
			"evidence", linkedMap(
				"report", linkedMap(
					"status", "NEEDS_REVIEW",
					"scenarioId", "smelting-basic",
					"plannerTurns", 8,
					"elapsedTicks", 1200,
					"evidenceReviewRequired", true
				),
				"plannerJournal", List.of(linkedMap("kind", "planner_succeeded"))
			)
		);

		CliResult result = execute(transport, "evaluation", "evidence", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: evaluation evidence\n"));
		assertTrue(result.output().contains("reportStatus: NEEDS_REVIEW\n"));
		assertTrue(result.output().contains("[evidence]\n"));
		assertTrue(result.output().contains("[plannerJournal 1]\n"));
	}

	@Test
	void agentEventsRecentPassesSinceFilter() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/agent/events/recent").payload = linkedMap(
			"available", true,
			"oldestSeqNo", 10,
			"latestSeqNo", 12,
			"truncated", false,
			"events", List.of(linkedMap(
				"seqNo", 12,
				"tick", 300,
				"timestampMs", 123456789L,
				"type", "planner.goal_set"
			))
		);

		CliResult result = execute(transport, "agent", "events", "recent", "--since", "11");

		assertEquals(0, result.exitCode());
		assertEquals("/v1/agent/events/recent?since=11", transport.lastRequest("GET", "/v1/agent/events/recent").path());
		assertTrue(result.output().contains("eventCount: 1\n"));
	}

	@Test
	void worldsListOmitsVerboseFieldsByDefault() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/worlds").payload = linkedMap(
			"available", true,
			"sessionState", "out_of_world",
			"worlds", List.of(linkedMap(
				"worldId", "survival-12345678",
				"name", "survival",
				"displayName", "Survival",
				"lastPlayed", 123L,
				"gameMode", "survival",
				"selectable", true,
				"immediatelyLoadable", true,
				"locked", false,
				"unavailable", false,
				"experimental", false,
				"details", "A long details string",
				"version", "1.21.8"
			))
		);

		CliResult compact = execute(transport, "worlds", "list");
		CliResult verbose = execute(transport, "worlds", "list", "--verbose");

		assertEquals(0, compact.exitCode());
		assertTrue(compact.output().contains("worldCount: 1\n"));
		assertFalse(compact.output().contains("details:"));
		assertTrue(verbose.output().contains("details: A long details string\n"));
		assertTrue(verbose.output().contains("version: 1.21.8\n"));
	}

	@Test
	void worldDifficultyReadsAndSetsWithoutChangingItOnInspection() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/worlds/difficulty").payload = Map.of("difficulty", "peaceful", "locked", false);
		transport.when("POST", "/v1/worlds/difficulty").payload = Map.of("difficulty", "easy", "changed", true);
		CliResult read = execute(transport, "world", "difficulty");
		assertEquals(0, read.exitCode());
		assertTrue(read.output().contains("difficulty: peaceful"));
		assertFalse(transport.requested("POST", "/v1/worlds/difficulty"));
		CliResult changed = execute(transport, "world", "difficulty", "--set", "easy");
		assertEquals(0, changed.exitCode());
		assertEquals(Map.of("difficulty", "easy"), transport.lastRequest("POST", "/v1/worlds/difficulty").body());
		assertTrue(changed.output().contains("changed: true"));
	}

	@Test
	void worldDifficultyRejectsInvalidValuesAndReportsLockedWorlds() {
		TestTransport transport = new TestTransport();
		assertEquals(2, execute(transport, "world", "difficulty", "--set", "impossible").exitCode());
		assertFalse(transport.requested("POST", "/v1/worlds/difficulty"));
		transport.when("POST", "/v1/worlds/difficulty").failure = new BridgeUnavailableException("difficulty_locked", "World difficulty is locked");
		CliResult locked = execute(transport, "world", "difficulty", "--set", "easy");
		assertEquals(4, locked.exitCode());
		assertTrue(locked.output().contains("error_code: difficulty_locked"));
	}

	@Test
	void missingRequiredArgumentReturnsUsageError() {
		CliResult result = execute(new TestTransport(), "worlds", "join");

		assertEquals(2, result.exitCode());
		assertTrue(result.output().contains("status: error\n"));
		assertTrue(result.output().contains("command: worlds join\n"));
		assertTrue(result.output().contains("error_code: invalid_arguments\n"));
		assertTrue(result.output().contains("Missing required option: '--world-id"));
	}

	@Test
	void bridgeErrorsMapToExitCodes() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/worlds/join").failure = new BridgeUnavailableException("already_in_world", "A world is already loaded");
		transport.when("GET", "/v1/servers").failure = new BridgeUnavailableException("minecraft_unavailable", "Minecraft bridge is not active");

		CliResult domainFailure = execute(transport, "worlds", "join", "--world-id", "survival-12345678");
		CliResult transportFailure = execute(transport, "servers", "list");

		assertEquals(4, domainFailure.exitCode());
		assertTrue(domainFailure.output().contains("error_code: already_in_world\n"));
		assertEquals(3, transportFailure.exitCode());
		assertTrue(transportFailure.output().contains("error_code: minecraft_unavailable\n"));
	}

	@Test
	void helpCommandShowsScopedUsage() {
		CliResult result = execute(new TestTransport(), "help", "highlights", "block");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("Usage: airicraft highlights block"));
		assertTrue(result.output().contains("--x"));
		assertTrue(result.output().contains("--duration-seconds"));
	}

	@Test
	void cameraScreenshotWritesFileAndPrintsMetadata(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/camera/screenshot").payload = linkedMap(
			"imageBase64", java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}),
			"format", "png",
			"width", 854,
			"height", 480,
			"sourceWidth", 1920,
			"sourceHeight", 1080,
			"capturedAtMs", 123456789L
		);
		Path output = tempDir.resolve("captures/view.png");

		CliResult result = execute(transport, "camera", "screenshot", "--output", output.toString());

		assertEquals(0, result.exitCode());
		assertTrue(Files.exists(output));
		assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(output));
		assertTrue(result.output().contains("status: ok\n"));
		assertTrue(result.output().contains("command: camera screenshot\n"));
		assertTrue(result.output().contains("outputPath: " + output.toAbsolutePath().normalize() + "\n"));
		assertTrue(result.output().contains("format: png\n"));
		assertTrue(result.output().contains("width: 854\n"));
		assertTrue(result.output().contains("height: 480\n"));
		assertTrue(result.output().contains("capturedAtMs: 123456789\n"));
	}

	@Test
	void mapStatusPrintsProviderSummary() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/map/status").payload = linkedMap(
			"available", true,
			"preferredProvider", "journeymap",
			"providers", List.of(linkedMap("id", "journeymap", "available", true))
		);

		CliResult result = execute(transport, "map", "status");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("status: ok\n"));
		assertTrue(result.output().contains("command: map status\n"));
		assertTrue(result.output().contains("available: true\n"));
		assertTrue(result.output().contains("preferredProvider: journeymap\n"));
	}

	@Test
	void mapWaypointsListPrintsWaypoints() {
		TestTransport transport = new TestTransport();
		transport.when("GET", "/v1/map/waypoints").payload = linkedMap(
			"waypoints", List.of(linkedMap(
				"id", "guid-1",
				"name", "Home",
				"dimension", "minecraft:overworld",
				"x", 1,
				"y", 64,
				"z", 2
			))
		);

		CliResult result = execute(transport, "map", "waypoints", "list");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: map waypoints list\n"));
		assertTrue(result.output().contains("id: guid-1\n"));
		assertTrue(result.output().contains("name: Home\n"));
		assertTrue(result.output().contains("dimension: minecraft:overworld\n"));
	}

	@Test
	void mapImageWritesFileAndPrintsMetadata(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/map/image").payload = linkedMap(
			"imageBase64", java.util.Base64.getEncoder().encodeToString(new byte[]{9, 8, 7}),
			"format", "png",
			"width", 512,
			"height", 512,
			"capturedAtMs", 123456789L
		);
		Path output = tempDir.resolve("captures/map.png");

		CliResult result = execute(transport, "map", "image", "--kind", "worldmap", "--output", output.toString());

		assertEquals(0, result.exitCode());
		assertArrayEquals(new byte[] {9, 8, 7}, Files.readAllBytes(output));
		assertTrue(result.output().contains("command: map image\n"));
		assertTrue(result.output().contains("outputPath: " + output.toAbsolutePath().normalize() + "\n"));
		assertTrue(result.output().contains("format: png\n"));
		assertTrue(result.output().contains("width: 512\n"));
		assertTrue(result.output().contains("height: 512\n"));
	}

	@Test
	void mapImagePassesOriginCoordinates(@TempDir Path tempDir) {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/map/image").payload = linkedMap(
			"imageBase64", java.util.Base64.getEncoder().encodeToString(new byte[]{1}),
			"format", "png",
			"width", 512,
			"height", 512,
			"capturedAtMs", 1L
		);
		Path output = tempDir.resolve("map.png");

		CliResult result = execute(
			transport,
			"map",
			"image",
			"--origin-x",
			"128",
			"--origin-z",
			"-64",
			"--output",
			output.toString()
		);

		assertEquals(0, result.exitCode());
		assertEquals(128, transport.body("POST", "/v1/map/image").get("originX"));
		assertEquals(-64, transport.body("POST", "/v1/map/image").get("originZ"));
	}

	@Test
	void cameraScreenshotRequiresOutputPath() {
		CliResult result = execute(new TestTransport(), "camera", "screenshot");

		assertEquals(2, result.exitCode());
		assertTrue(result.output().contains("status: error\n"));
		assertTrue(result.output().contains("command: camera screenshot\n"));
		assertTrue(result.output().contains("error_code: invalid_arguments\n"));
		assertTrue(result.output().contains("Missing required option: '--output"));
	}

	@Test
	void cameraScreenshotBridgeErrorsUseTransportExitCodes() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/camera/screenshot").failure = new BridgeUnavailableException("capture_timeout", "Screenshot capture timed out");

		CliResult result = execute(transport, "camera", "screenshot", "--output", "capture.png");

		assertEquals(4, result.exitCode());
		assertTrue(result.output().contains("command: camera screenshot\n"));
		assertTrue(result.output().contains("error_code: capture_timeout\n"));
	}

	@Test
	void visionDescribePrintsDeterministicText() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/vision/describe").payload = linkedMap(
			"format", "text",
			"capturedAtMs", 987654321L,
			"model", "gpt-4.1-mini",
			"description", "A birch forest hill with open sky and no visible structures."
		);

		CliResult result = execute(transport, "vision", "describe");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("status: ok\n"));
		assertTrue(result.output().contains("command: vision describe\n"));
		assertTrue(result.output().contains("format: text\n"));
		assertTrue(result.output().contains("capturedAtMs: 987654321\n"));
		assertTrue(result.output().contains("model: gpt-4.1-mini\n"));
		assertTrue(result.output().contains("description: A birch forest hill with open sky and no visible structures.\n"));
	}

	@Test
	void visionDescribePassesPromptOverride() {
		TestTransport transport = new TestTransport();
		transport.when("POST", "/v1/vision/describe").payload = linkedMap(
			"format", "text",
			"capturedAtMs", 1L,
			"model", "test-model",
			"description", "No hazards."
		);

		CliResult result = execute(transport, "vision", "describe", "--prompt", "Describe hazards only.");

		assertEquals(0, result.exitCode());
		assertEquals("Describe hazards only.", transport.body("POST", "/v1/vision/describe").get("prompt"));
	}

	private static CliResult execute(MinecraftTransport transport, String... args) {
		StringWriter writer = new StringWriter();
		CommandLine commandLine = AiricraftCliMain.createCommandLine(transport, new PrintWriter(writer, true));
		commandLine.setColorScheme(new CommandLine.Help.ColorScheme.Builder().ansi(CommandLine.Help.Ansi.OFF).build());
		int exitCode = commandLine.execute(args);
		return new CliResult(exitCode, writer.toString().replace("\r\n", "\n"));
	}

	private static LinkedHashMap<String, Object> linkedMap(Object... values) {
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		for (int index = 0; index < values.length; index += 2) {
			map.put(String.valueOf(values[index]), values[index + 1]);
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Object value) {
		return (Map<String, Object>) value;
	}

	private static Map<String, Object> actionGoalPayload(String executionId, String state) {
		return linkedMap(
			"available", true,
			"executionId", executionId,
			"state", state,
			"executionPhase", "RESOLVING".equals(state) ? "PLANNING" : "TERMINAL",
			"resolved", false,
			"accepted", false,
			"activePrimitive", false,
			"cursor", 0,
			"traceEventCount", 1,
			"goal", linkedMap("fact", "inventory.item", "itemId", "minecraft:bread", "countAtLeast", 1),
			"route", linkedMap("cost", 0, "steps", List.of())
		);
	}

	private record CliResult(int exitCode, String output) {
	}

	private static final class TestTransport implements MinecraftTransport {
		private final Map<String, Stub> stubs = new HashMap<>();
		private final List<Request> requests = new ArrayList<>();

		Stub when(String method, String path) {
			return stubs.computeIfAbsent(route(method, path), ignored -> new Stub());
		}

		Request lastRequest(String method, String pathPrefix) {
			for (int index = requests.size() - 1; index >= 0; index--) {
				Request request = requests.get(index);
				if (request.method().equals(method) && request.path().startsWith(pathPrefix)) {
					return request;
				}
			}
			return null;
		}

		boolean requested(String method, String pathPrefix) {
			return lastRequest(method, pathPrefix) != null;
		}

		Map<String, Object> body(String method, String pathPrefix) {
			Request request = lastRequest(method, pathPrefix);
			return request == null ? null : castMap(request.body());
		}

		@Override
		public Map<String, Object> request(String method, String path, Object body) {
			Request request = new Request(method, path, body);
			requests.add(request);
			String route = matchingRoute(stubs, method, path);
			if (route == null) {
				return Map.of();
			}
			Stub stub = stubs.get(route);
			if (stub.failure != null) {
				throw stub.failure;
			}
			return stub.payload;
		}

		private static String matchingRoute(Map<String, ?> routes, String method, String path) {
			String exact = route(method, path);
			if (routes.containsKey(exact)) {
				return exact;
			}
			String prefix = method + " ";
			return routes.keySet().stream()
				.filter(candidate -> candidate.startsWith(prefix) && path.startsWith(candidate.substring(prefix.length())))
				.findFirst()
				.orElse(null);
		}

		private static String route(String method, String path) {
			return method + " " + path;
		}
	}

	private static final class Stub {
		private Map<String, Object> payload = Map.of();
		private RuntimeException failure;
	}

	private record Request(String method, String path, Object body) {
	}
}
