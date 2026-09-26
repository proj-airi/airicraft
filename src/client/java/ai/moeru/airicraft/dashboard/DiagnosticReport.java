package ai.moeru.airicraft.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A bounded product summary of existing evidence, never a second recorder. */
public final class DiagnosticReport {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
	private static final long WINDOW_TICKS = 1200;
	private static final int MAX_OBSERVATIONS = 2000;
	private static final long MAX_OBSERVATION_BYTES = 2L * 1024 * 1024;
	private final String reportId;
	private final byte[] manifest;
	private final List<byte[]> observations;

	private DiagnosticReport(String reportId, JsonObject manifest, List<byte[]> observations) {
		this.reportId = reportId;
		this.manifest = line(manifest);
		this.observations = List.copyOf(observations);
	}

	public static DiagnosticReport capture(DashboardObservationStore store, Map<String, Object> environment) {
		Map<String, Object> status;
		List<DashboardObservation> retained;
		long clientTick;
		// Freeze references and metadata together; JSON projection and IO never hold the tick's lock.
		synchronized (store) {
			status = store.recordingStatus();
			retained = store.retainedObservations();
			clientTick = store.latestTick();
		}
		boolean serverClock = (boolean) status.get("serverClockAvailable");
		long to = serverClock ? ((Number) status.get("serverTickId")).longValue() : clientTick;
		long from = Math.max(0, to - WINDOW_TICKS);
		long bytes = 0;
		int limitOmitted = 0;
		int clippedFields = 0;
		boolean gap = false;
		Map<String, Integer> excluded = new LinkedHashMap<>();
		List<byte[]> selected = new ArrayList<>();
		JsonObject runtime = new JsonObject();
		runtime.addProperty("available", false);
		for (int i = retained.size() - 1; i >= 0; i--) {
			DashboardObservation observation = retained.get(i);
			if (observation.type().equals("runtime_snapshot") && !runtime.get("available").getAsBoolean()) {
				Projection projected = project(observation);
				runtime.addProperty("available", true);
				runtime.add("observation", projected.value());
				clippedFields += projected.clippedFields();
			}
			long start = serverClock ? observation.serverTickId() : observation.tick();
			long end = serverClock ? observation.throughServerTickId() : observation.tick();
			if (end < from || start > to) continue;
			Projection projected = project(observation);
			if (projected == null) {
				excluded.merge(observation.type(), 1, Integer::sum);
				continue;
			}
			gap |= observation.type().equals("observation_gap");
			byte[] encoded = line(projected.value());
			if (selected.size() >= MAX_OBSERVATIONS || bytes + encoded.length > MAX_OBSERVATION_BYTES) {
				limitOmitted++;
				continue;
			}
			selected.add(encoded);
			bytes += encoded.length;
			clippedFields += projected.clippedFields();
		}
		Collections.reverse(selected);
		Map<?, ?> dropped = (Map<?, ?>) status.get("droppedByType");
		String id = UUID.randomUUID().toString();
		JsonObject manifest = new JsonObject();
		manifest.addProperty("recordType", "manifest");
		manifest.addProperty("schema", "airicraft.diagnostic-report");
		manifest.addProperty("schemaVersion", 1);
		manifest.addProperty("reportId", id);
		manifest.addProperty("createdAtMs", System.currentTimeMillis());
		manifest.addProperty("mode", "user_summary");
		JsonObject correlation = new JsonObject();
		correlation.addProperty("recordingSessionId", (String) status.get("sessionId"));
		correlation.add("hostedSessionId", com.google.gson.JsonNull.INSTANCE);
		manifest.add("correlation", correlation);
		manifest.add("environment", GSON.toJsonTree(environment));
		manifest.add("window", GSON.toJsonTree(Map.of(
			"clock", serverClock ? "server_tick" : "client_tick", "fromTick", from, "toTick", to,
			"requestedTicks", WINDOW_TICKS, "throughSequence", status.get("latestSequence"),
			"serverClockAvailable", serverClock, "paused", status.get("paused"))));
		manifest.add("runtimeState", runtime);
		manifest.add("coverage", GSON.toJsonTree(Map.of(
			"truncated", !dropped.isEmpty() || gap || limitOmitted > 0 || clippedFields > 0,
			"droppedByType", dropped, "expiredByType", status.get("expiredByType"),
			"observationGap", gap, "reportLimitOmitted", limitOmitted,
			"clippedFields", clippedFields, "excludedByPolicy", excluded,
			"retainedObservationCount", retained.size(), "includedObservationCount", selected.size(),
			"lossCounterScope", "recording_session")));
		manifest.add("privacy", GSON.toJsonTree(Map.of("projection", "allowlisted_summary_v1",
			"excluded", List.of("chat", "prompts", "model_responses", "logs", "images", "credentials", "endpoint_urls", "player_names"))));
		return new DiagnosticReport(id, manifest, selected);
	}

	public String fileName() { return "airicraft-report-" + reportId + ".jsonl"; }

	/** Only the final footer certifies a complete file; its hash covers every preceding UTF-8 byte. */
	public void writeTo(OutputStream output) throws IOException {
		MessageDigest digest;
		try { digest = MessageDigest.getInstance("SHA-256"); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
		DigestOutputStream checked = new DigestOutputStream(output, digest);
		checked.write(manifest);
		long bytes = manifest.length;
		for (byte[] observation : observations) {
			checked.write(observation);
			bytes += observation.length;
		}
		checked.flush();
		output.write(line(Map.of("recordType", "integrity", "complete", true,
			"algorithm", "SHA-256", "sha256", HexFormat.of().formatHex(digest.digest()),
			"bytes", bytes, "observationCount", observations.size())));
	}

	public Path save(Path directory) throws IOException {
		Files.createDirectories(directory);
		Path temporary = Files.createTempFile(directory, ".airicraft-report-", ".partial");
		try {
			try (OutputStream output = Files.newOutputStream(temporary)) { writeTo(output); }
			Path target = directory.resolve(fileName());
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			return target;
		} finally { Files.deleteIfExists(temporary); }
	}

	private static byte[] line(Object value) { return (GSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8); }

	private static Projection project(DashboardObservation observation) {
		String fields = switch (observation.type()) {
			case "runtime_snapshot" -> "plannerEnabled degraded llmAvailable visionAvailable "
				+ "agent.initialized agent.tickCount agent.session.mode agent.session.worldLoaded agent.session.playerLifecycleState "
				+ "planner.currentPhase planner.inFlight planner.retryPending planner.activeAttemptCount "
				+ "task.state task.activeStepId task.activeStepKind task.updatedTick task.progress.collected task.progress.remaining task.lastStepResult.status "
				+ "taskExecution.state taskExecution.taskId taskExecution.processName taskExecution.lastPathEvent taskExecution.terminationCause "
				+ "world.loaded world.dimension world.screen world.player.health world.player.maxHealth world.player.food "
				+ "reflex.state reflex.cause reflex.action reflex.safetyEpoch";
			case "decision_state" -> "task.state task.taskId task.processName task.lastPathEvent task.terminationCause reflex.snapshot.state reflex.snapshot.cause reflex.snapshot.action reflex.snapshot.safetyEpoch";
			case "llm_call" -> "sequenceId requestedAtMs completedAtMs status requestKind providerName model responseModel statusCode "
				+ "timeoutMillis messageCount imageAttached failureType parsedResponseKind dispatchTick dispatchServerTick "
				+ "usage.promptTokens usage.completionTokens usage.totalTokens";
			case "semantic_event" -> "seqNo tick timestampMs type payload.failureCode payload.code payload.reasonCode payload.state payload.taskId payload.jobId";
			case "debug_timeline" -> "entryId tick timestampMs domain action correlation.jobId correlation.taskId correlation.callId payload.failureCode payload.state";
			case "observation_gap" -> "reason originalType estimatedBytes stream requestedAfter oldestAvailable";
			case "session_started" -> "reason";
			default -> null;
		};
		if (fields == null) return null;
		JsonObject payload = new JsonObject();
		int clipped;
		try (JsonReader reader = new JsonReader(new java.io.StringReader(observation.payloadJson()))) {
			clipped = projectFields(reader, payload, "", java.util.Set.of(fields.split(" ")));
		} catch (IOException invalidEvidence) { throw new IllegalArgumentException("Invalid recorded evidence", invalidEvidence); }

		JsonObject result = new JsonObject();
		result.addProperty("recordType", "observation");
		result.addProperty("sequence", observation.sequence());
		result.addProperty("type", observation.type());
		result.addProperty("tick", observation.tick());
		result.addProperty("serverTickId", observation.serverTickId());
		result.addProperty("throughServerTickId", observation.throughServerTickId());
		result.addProperty("capturedAtMs", observation.capturedAtMs());
		result.add("payload", payload);
		return new Projection(result, clipped);
	}

	/** Skip raw envelopes without materializing their prompts, images or responses. */
	private static int projectFields(JsonReader reader, JsonObject target, String prefix, java.util.Set<String> fields) throws IOException {
		int clipped = 0;
		reader.beginObject();
		while (reader.hasNext()) {
			String name = reader.nextName();
			String path = prefix + name;
			JsonToken token = reader.peek();
			if (fields.contains(path) && token == JsonToken.STRING) {
				String value = reader.nextString();
				if (value.length() > 256) { value = value.substring(0, 256); clipped++; }
				target.addProperty(name, value);
			} else if (fields.contains(path) && token == JsonToken.NUMBER) {
				target.add(name, com.google.gson.JsonParser.parseString(reader.nextString()));
			} else if (fields.contains(path) && token == JsonToken.BOOLEAN) {
				target.addProperty(name, reader.nextBoolean());
			} else if (token == JsonToken.BEGIN_OBJECT && fields.stream().anyMatch(field -> field.startsWith(path + "."))) {
				JsonObject child = new JsonObject();
				clipped += projectFields(reader, child, path + ".", fields);
				if (!child.isEmpty()) target.add(name, child);
			} else reader.skipValue();
		}
		reader.endObject();
		return clipped;
	}

	private record Projection(JsonObject value, int clippedFields) {}
}
