package ai.moeru.airicraft.playtest;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.ClientRuntimeController;
import ai.moeru.airicraft.PlannerDebugOverlayMode;
import ai.moeru.airicraft.agent.recording.AutomaticPlaytestRecording;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.WorldSavePath;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.glfw.GLFW;

/** Client-thread owner of one opt-in recording and its report/pause boundary. */
public final class AutomaticPlaytestRuntime {
	public static boolean enabled() { return Boolean.getBoolean("airicraft.automaticPlaytest"); }
	/** Human testers join the companion's LAN world; the planner never pauses it with bug reports. */
	public static boolean hosted() { return enabled() && "hosted".equals(System.getProperty("airicraft.automaticPlaytestMode")); }
	private static final int PARTICIPANT_POLL_TICKS = 20;
	private record PlayerListSnapshot(long serverTick, List<HostedPlaytestParticipants.Participant> players) {}
	private enum State { IDLE, RECORDING, REPORT_PENDING, PAUSING, CAPTURE_READY, FINISHED, FAILED }
	private final ClientRuntimeController controller;
	private final Path root;
	private State state = State.IDLE;
	private AutomaticPlaytestRecording recording;
	private boolean resultCommitted;
	private boolean presentationConfigured;
	private String error = "";
	private String pendingDescription = "";
	private long sessionEpoch;
	private HostedPlaytestParticipants participants;
	private CompletableFuture<PlayerListSnapshot> playerQuery;
	private int participantPollCountdown;
	private final HostedEmptyPause emptyPause = new HostedEmptyPause();
	private volatile String hostingCompanionUuid;
	private volatile boolean emptyHostPaused;
	private volatile boolean hostingStartupFinished;

	/** Runs on the server thread; frozen simulation still processes joins and disconnects. */
	public void onHostedServerTick(net.minecraft.server.MinecraftServer server) {
		String companion = hostingCompanionUuid;
		boolean empty = hosted() && companion != null && server.isRemote()
			&& server.getPlayerManager().getPlayerList().stream().allMatch(p -> p.getUuidAsString().equals(companion));
		boolean startupFinished = hostingStartupFinished;
		switch (emptyPause.update(empty, server.getTickManager().isFrozen(), startupFinished)) {
			case FREEZE -> server.getTickManager().setFrozen(true);
			case RESUME -> server.getTickManager().setFrozen(false);
			case NONE -> { }
		}
		empty = empty && startupFinished;
		if (emptyHostPaused != empty) Airicraft.LOGGER.info("Hosted empty-player pause: {}", empty);
		emptyHostPaused = empty;
	}

	public boolean emptyHostPaused() { return emptyHostPaused; }

	/** Render-loop maintenance must not accumulate a visual backlog while game ticks are gated. */
	public void maintainPausedHost(MinecraftClient client) {
		if (state != State.RECORDING || recording == null) return;
		try {
			recording.recordVisualHistory(controller.liveRecording());
			pollParticipants(client);
		} catch (IOException exception) { fail(exception); }
	}

	public AutomaticPlaytestRuntime(ClientRuntimeController controller, Path root) {
		this.controller = controller;
		this.root = root;
	}

	public void onClientTick(MinecraftClient client) {
		if (!enabled()) return;
		configurePresentation(client);
		if (client.world == null || client.getServer() == null) return;
		// World/player objects exist before DownloadingTerrainScreen finishes ticking.
		// Latch readiness only once the client has actually reached the game screen.
		if (hosted() && client.player != null && client.currentScreen == null) hostingStartupFinished = true;
		try {
			if (state == State.IDLE) {
				if (client.player == null) return;
				Map<String, Object> context = Map.of(
					"clock", ai.moeru.airicraft.debug.ServerTickDebugRuntime.tickAnchor(),
					"worldPath", client.getServer().getSavePath(WorldSavePath.ROOT).toString(),
					"dimension", client.world.getRegistryKey().getValue().toString(),
					"mode", hosted() ? "hosted" : "automatic",
					// Recorder Plays are per connection; this identifies the companion's among testers'.
					"playerUuid", client.player.getUuidAsString(),
					"playerName", client.player.getName().getString());
				String runId = System.getProperty("airicraft.automaticPlaytestId");
				recording = runId == null ? new AutomaticPlaytestRecording(root, context) : new AutomaticPlaytestRecording(root, runId, context);
				if (hosted()) {
					participants = new HostedPlaytestParticipants(client.player.getUuidAsString());
					hostingCompanionUuid = client.player.getUuidAsString();
				}
				state = State.RECORDING;
			}
			if (state == State.REPORT_PENDING && resultCommitted) { pause(client); return; }
			if (state == State.RECORDING) {
				recording.recordTick(controller.agentRuntime(), controller.liveRecording());
				if (participants != null) pollParticipants(client);
			}
		}
		catch (IOException exception) {
			fail(exception);
		}
	}

	private void configurePresentation(MinecraftClient client) {
		if (presentationConfigured) return;
		var window = client.getWindow();
		if (window.isFullscreen()) {
			window.toggleFullscreen();
			client.options.getFullscreen().setValue(false);
			return;
		}
		// Fullscreen changes take effect at the next rendered frame, not at toggleFullscreen().
		if (GLFW.glfwGetWindowMonitor(window.getHandle()) != 0) return;
		GLFW.glfwMaximizeWindow(window.getHandle());
		controller.setPlannerDebugOverlayMode(PlannerDebugOverlayMode.CONVERSATION);
		presentationConfigured = true;
		Airicraft.LOGGER.info("Automatic playtest presentation: maximized window, conversation overlay enabled");
	}

	/** Reads the integrated server's connection list on its own thread; the client never iterates it directly. */
	private void pollParticipants(MinecraftClient client) throws IOException {
		if (playerQuery != null) {
			if (!playerQuery.isDone()) return;
			PlayerListSnapshot snapshot;
			try { snapshot = playerQuery.join(); }
			catch (RuntimeException exception) {
				Airicraft.LOGGER.warn("Hosted playtest could not read connected players", exception);
				snapshot = null;
			}
			playerQuery = null;
			if (snapshot != null)
				recording.recordParticipants(participants.update(snapshot.players(), snapshot.serverTick(), Instant.now().toString()));
		}
		if (--participantPollCountdown > 0) return;
		participantPollCountdown = PARTICIPANT_POLL_TICKS;
		var server = client.getServer();
		playerQuery = server.submit(() -> {
			var players = server.getPlayerManager().getPlayerList().stream()
				.map(player -> new HostedPlaytestParticipants.Participant(player.getUuidAsString(), player.getName().getString()))
				.toList();
			return new PlayerListSnapshot(server.getTicks(), players);
		});
	}

	public String report(String description) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!enabled()) return "TOOL_ERROR: something_wrong: automatic_playtest_disabled";
		if (hosted()) return "TOOL_ERROR: something_wrong: hosted_playtest";
		if (client.world == null || client.getServer() == null)
			return "TOOL_ERROR: something_wrong: local_singleplayer_required";
		if (state == State.IDLE) onClientTick(client);
		if (state == State.CAPTURE_READY) {
			return "Tool result for something_wrong: reportId=" + recording.id()
				+ " state=CAPTURE_READY outputDir=" + recording.pendingDirectory() + ". This playtest was already reported; the launcher will finalize its Recorder Play.";
		}
		if (state == State.FINISHED) return "TOOL_ERROR: something_wrong: playtest_already_finished";
		if (state == State.RECORDING || state == State.FAILED) {
			pendingDescription = description;
			if (recording != null) {
				try { recording.report(description, controller.agentRuntime().tickCount()); }
				catch (IOException exception) { fail(exception); }
			}
			state = State.REPORT_PENDING;
		}
		return "Tool result for something_wrong: reportId=" + (recording == null ? "unrecorded" : recording.id())
			+ " state=" + state + " outputDir=" + (recording == null ? root : recording.incidentDirectory())
			+ ". Report accepted; this playtest will pause. The launcher will keep the client paused for a Codex parent, or close it to finalize the incident when running without one."
			+ (error.isEmpty() ? "" : " Recording error: " + error);

	}

	/** Called only after the tool receipt is recorded; never wait for a frozen planner tick. */
	public void resultCommitted() { resultCommitted = true; }

	private void pause(MinecraftClient client) {
		state = State.PAUSING;
		long reportSessionEpoch = sessionEpoch;
		try {
			var trace = controller.clientTickDebugRuntime().traceStatus();
			if (trace.active()) controller.clientTickDebugRuntime().stopTrace(trace.traceId());
			controller.clientTickDebugRuntime().pause(client, false).whenComplete((capture, failure) -> client.execute(() -> {
				if (sessionEpoch != reportSessionEpoch) return;
				if (failure != null) { fail(failure); return; }
				try {
					if (recording == null) throw new IOException("Recording could not be started: " + error);
					recording.report(pendingDescription, controller.agentRuntime().tickCount());
					recording.finish(controller.agentRuntime(), controller.liveRecording(), Map.of(
						"debugSessionId", capture.debugSessionId(), "pauseEpoch", capture.pauseEpoch(),
						"snapshot", capture.snapshot(), "frameStatus", capture.frame().status()), capture.frame().imageBytes());
					state = State.CAPTURE_READY;
					error = "";
					Airicraft.LOGGER.info("Automatic playtest capture ready for Recorder Play finalization: {}", recording.pendingDirectory());
				}
				catch (IOException | RuntimeException exception) { fail(exception); }
			}));
		}
		catch (RuntimeException exception) { fail(exception); }
	}

	public boolean freezing() { return state == State.PAUSING; }
	public boolean captureReady() { return state == State.CAPTURE_READY; }

	/** Complete the paused world checkpoint before allowing normal disconnect/Recorder Play finalization. */
	public java.util.concurrent.CompletableFuture<Void> prepareShutdown(MinecraftClient client) {
		var server = client.getServer();
		if (!captureReady() || server == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
		var activeRecording = recording;
		return server.submit(() -> {
			try {
				var clock = ai.moeru.airicraft.debug.ServerTickDebugRuntime.controller().status();
				if (!clock.paused()) throw new IllegalStateException("World checkpoint requires the report pause");
				server.getPlayerManager().saveAllPlayerData();
				server.save(false, true, true);
				activeRecording.saveWorldCheckpoint(server.getSavePath(WorldSavePath.ROOT), Map.of(
					"serverTickId", clock.serverTickId(),
					"worldTime", server.getOverworld().getTime(), "timeOfDay", server.getOverworld().getTimeOfDay(),
					"capturedWhilePaused", true));
			}
			catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
		});
	}

	public Map<String, Object> statusPayload() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("enabled", enabled());
		payload.put("mode", hosted() ? "hosted" : "automatic");
		payload.put("state", state.name());
		payload.put("error", error);
		payload.put("outputDir", recording == null ? "" : recording.pendingDirectory().toString());
		if (hosted()) {
			payload.put("connectedTesters", participants == null ? List.of() : participants.connectedTesters());
			payload.put("testersEverJoined", participants == null ? 0 : participants.testersEverJoined());
			payload.put("pausedForNoTesters", emptyHostPaused);
		}
		return payload;
	}

	public void worldLeft(String reason) {
		hostingCompanionUuid = null;
		hostingStartupFinished = false;
		emptyHostPaused = false;
		// This mode has one run per process. Keep its pause through disconnect/server save.
		if (captureReady() || state == State.FINISHED || recording == null) return;
		sessionEpoch++;
		try {
			if (participants != null) {
				playerQuery = null;
				recording.recordParticipants(participants.endSession(
					ai.moeru.airicraft.debug.ServerTickDebugRuntime.tickAnchor().serverTick(), Instant.now().toString()));
			}
			recording.finish(controller.agentRuntime(), controller.liveRecording(), reason);
			state = State.FINISHED;
		}
		catch (IOException | RuntimeException exception) { fail(exception); }
	}

	private void fail(Throwable failure) {
		error = failure.toString();
		state = State.FAILED;
		Airicraft.LOGGER.error("Automatic playtest recording failed; incomplete files remain in .in-progress", failure);
	}
}
