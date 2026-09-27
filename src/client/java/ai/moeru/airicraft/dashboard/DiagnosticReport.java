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

	public enum Mode {
		MINIMAL, SUMMARY, DEVELOPER;
		public List<String> categories() {
			return switch (this) {
				case MINIMAL -> List.of("Description, build/model IDs, anonymous session ID and incident tick marker");
				case SUMMARY -> List.of("Description, build/model IDs and incident marker", "Diagnostic events and model-call statistics", "World/session state, dimension and player health");
				case DEVELOPER -> List.of("Description, build/model IDs and incident marker", "Chat and model inputs/outputs", "Logs and full world/session metadata", "Captured screenshots (pixels are not automatically redacted)");
			};
		}
	}

	public record Request(Mode mode, String description) {
		public Request {
			if (mode == null || description == null || description.length() > 2000) throw new IllegalArgumentException("Choose a report type and a description of at most 2000 characters");
		}
	}

	/** Bounded evidence frozen at the marker; preview and save never consult the moving store. */
	public static final class Draft {
		private final String id = UUID.randomUUID().toString();
		private final long markedAtMs = System.currentTimeMillis();
		private final Map<String, Object> status;
		private final List<DashboardObservation> retained;
		private final long clientTick;
		private final JsonObject environment;
		private final List<String> secrets;
		private final int sourceLimitOmitted;

		private Draft(Map<String, Object> status, List<DashboardObservation> retained, long clientTick,
			Map<String, Object> environment, List<String> secrets, int sourceLimitOmitted) {
			this.status = status; this.retained = List.copyOf(retained); this.clientTick = clientTick;
			this.environment = GSON.toJsonTree(environment).getAsJsonObject(); this.secrets = List.copyOf(secrets);
			this.sourceLimitOmitted = sourceLimitOmitted;
		}

		public String id() { return id; }
		public DiagnosticReport prepare(Request request, List<String> currentSecrets) {
			List<String> allSecrets = new ArrayList<>(secrets); allSecrets.addAll(currentSecrets);
			return create(this, request, new DiagnosticRedactor(allSecrets));
		}
	}

	public static Draft mark(DashboardObservationStore store, Map<String, Object> environment, List<String> secrets) {
		Map<String, Object> status;
		List<DashboardObservation> retained;
		long clientTick;
		// Only reference copying holds the game tick's lock; selection, projection and IO happen outside it.
		synchronized (store) {
			status = store.recordingStatus(); retained = store.retainedObservations(); clientTick = store.latestTick();
		}
		boolean serverClock = (boolean) status.get("serverClockAvailable");
		long to = serverClock ? ((Number) status.get("serverTickId")).longValue() : clientTick;
		long from = Math.max(0, to - WINDOW_TICKS);
		List<DashboardObservation> selected = new ArrayList<>();
		long bytes = 0;
		int omitted = 0;
		boolean foundRuntime = false;
		for (int i = retained.size() - 1; i >= 0; i--) {
			var observation = retained.get(i);
			boolean baseline = !foundRuntime && observation.type().equals("runtime_snapshot");
			foundRuntime |= baseline;
			long start = serverClock ? observation.serverTickId() : observation.tick();
			long end = serverClock ? observation.throughServerTickId() : observation.tick();
			if (!baseline && (end < from || start > to)) continue;
			if (bytes + observation.retainedBytes() > 8L * 1024 * 1024) { omitted++; continue; }
			selected.add(observation); bytes += observation.retainedBytes();
		}
		Collections.reverse(selected);
		return new Draft(status, selected, clientTick, environment, secrets, omitted);
	}

	public static DiagnosticReport capture(DashboardObservationStore store, Map<String, Object> environment) {
		return mark(store, environment, List.of()).prepare(new Request(Mode.SUMMARY, ""), List.of());
	}

	private static DiagnosticReport create(Draft draft, Request request, DiagnosticRedactor redactor) {
		var status = draft.status;
		var retained = draft.retained;
		long clientTick = draft.clientTick;

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
		for (int i = request.mode() == Mode.MINIMAL ? -1 : retained.size() - 1; i >= 0; i--) {
			DashboardObservation observation = retained.get(i);
			if (observation.type().equals("runtime_snapshot") && !runtime.get("available").getAsBoolean()) {
				Projection projected = project(observation, redactor);
				runtime.addProperty("available", true);
				runtime.add("observation", projected.value());
				clippedFields += projected.clippedFields();
			}
			long start = serverClock ? observation.serverTickId() : observation.tick();
			long end = serverClock ? observation.throughServerTickId() : observation.tick();
			if (end < from || start > to) continue;
			Projection projected = request.mode() == Mode.DEVELOPER
				? new Projection(observationRecord(observation, redactor.redact(com.google.gson.JsonParser.parseString(observation.payloadJson()))), 0)
				: project(observation, redactor);
			if (projected == null) {
				excluded.merge(observation.type(), 1, Integer::sum);
				continue;
			}
			gap |= observation.type().equals("observation_gap");
			byte[] encoded = line(redactor.redact(projected.value()));
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
		String id = draft.id;
		JsonObject manifest = new JsonObject();
		manifest.addProperty("recordType", "manifest");
		manifest.addProperty("schema", "airicraft.diagnostic-report");
		manifest.addProperty("schemaVersion", 2);
		manifest.addProperty("reportId", id);
		manifest.addProperty("createdAtMs", draft.markedAtMs);
		manifest.addProperty("markedAtMs", draft.markedAtMs);
		manifest.addProperty("description", redactor.redact(request.description()));
		manifest.addProperty("mode", request.mode() == Mode.SUMMARY ? "user_summary" : request.mode().name().toLowerCase(java.util.Locale.ROOT));
		JsonObject correlation = new JsonObject();
		correlation.addProperty("recordingSessionId", (String) status.get("sessionId"));
		correlation.add("hostedSessionId", com.google.gson.JsonNull.INSTANCE);
		manifest.add("correlation", correlation);
		manifest.add("environment", redactor.redact(draft.environment));
		manifest.add("window", GSON.toJsonTree(Map.of(
			"clock", serverClock ? "server_tick" : "client_tick", "fromTick", from, "toTick", to,
			"requestedTicks", WINDOW_TICKS, "throughSequence", status.get("latestSequence"),
			"serverClockAvailable", serverClock, "paused", status.get("paused"))));
		manifest.add("runtimeState", runtime);
		manifest.add("coverage", GSON.toJsonTree(Map.of(
			"truncated", !dropped.isEmpty() || gap || limitOmitted > 0 || clippedFields > 0 || draft.sourceLimitOmitted > 0,
			"droppedByType", dropped, "expiredByType", status.get("expiredByType"),
			"observationGap", gap, "reportLimitOmitted", limitOmitted,
			"clippedFields", clippedFields, "excludedByPolicy", excluded,
			"retainedObservationCount", retained.size(), "includedObservationCount", selected.size(),
			"lossCounterScope", "recording_session")));
		manifest.getAsJsonObject("coverage").addProperty("sourceLimitOmitted", draft.sourceLimitOmitted);
		manifest.add("privacy", GSON.toJsonTree(Map.of("projection", request.mode() == Mode.DEVELOPER ? "redacted_developer_v1" : "allowlisted_summary_v1",
			"includedClasses", request.mode().categories(), "credentialRedaction", true,
			"screenshotsMayContainUnredactedText", request.mode() == Mode.DEVELOPER)));
		int failures = 0;
		for (byte[] record : selected) {
			var value = com.google.gson.JsonParser.parseString(new String(record, StandardCharsets.UTF_8)).getAsJsonObject();
			if (!value.get("payload").isJsonObject()) continue;
			var payload = value.getAsJsonObject("payload");
			String statusText = payload.has("status") && payload.get("status").isJsonPrimitive() ? payload.get("status").getAsString() : "";
			String eventType = payload.has("type") && payload.get("type").isJsonPrimitive() ? payload.get("type").getAsString() : "";
			if (statusText.equals("FAILED") || eventType.matches("(?i).*(failed|failure|error).*")) failures++;
		}
		String summary = "Report " + id + "\nDescription: " + redactor.redact(request.description())
			+ "\nIncident: " + from + "–" + to + " (" + (serverClock ? "server ticks" : "client ticks") + ")"
			+ "\nAttachments: " + String.join("; ", request.mode().categories())
			+ "\nObservations: " + selected.size() + "; failure indicators: " + failures
			+ "\nEvidence truncated: " + manifest.getAsJsonObject("coverage").get("truncated").getAsBoolean()
			+ "\nNo automatic upload. Text credentials are redacted; screenshot pixels are not inspected.";
		manifest.add("summary", GSON.toJsonTree(Map.of("failureIndicatorCount", failures, "observationCount", selected.size(), "text", summary)));
		return new DiagnosticReport(id, redactor.redact(manifest).getAsJsonObject(), selected);

	}

	public String fileName() { return "airicraft-report-" + reportId + ".zip"; }

	public JsonObject preview() { return com.google.gson.JsonParser.parseString(new String(manifest, StandardCharsets.UTF_8)).getAsJsonObject(); }

	/** Exact UTF-8 attachment contents, shared by inspection and ZIP writing. */
	public Map<String, String> attachments() {
		var jsonl = new java.io.ByteArrayOutputStream();
		try { writeTo(jsonl); }
		catch (IOException impossible) { throw new java.io.UncheckedIOException(impossible); }
		JsonObject metadata = preview();
		String summary = metadata.getAsJsonObject("summary").get("text").getAsString()
			+ "\nBuild: " + metadata.getAsJsonObject("environment").get("build")
			+ "\nModels: " + metadata.getAsJsonObject("environment").get("providers")
			+ "\nSession: " + metadata.getAsJsonObject("correlation").get("recordingSessionId").getAsString() + "\n";
		return Map.of("report.jsonl", jsonl.toString(StandardCharsets.UTF_8), "summary.txt", summary);
	}

	public void writeBundleTo(OutputStream output) throws IOException {
		var files = attachments();
		try (var zip = new java.util.zip.ZipOutputStream(output, StandardCharsets.UTF_8)) {
			for (String name : List.of("report.jsonl", "summary.txt")) {
				zip.putNextEntry(new java.util.zip.ZipEntry(name));
				zip.write(files.get(name).getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
	}

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
			try (OutputStream output = Files.newOutputStream(temporary)) { writeBundleTo(output); }
			// Keep prior saves intact when this incident is reviewed again, including without edits.
			Path target = directory.resolve("airicraft-report-" + reportId + "-" + UUID.randomUUID() + ".zip");
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			return target;
		} finally { Files.deleteIfExists(temporary); }
	}

	private static byte[] line(Object value) { return (GSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8); }

	private static Projection project(DashboardObservation observation, DiagnosticRedactor redactor) {
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
			clipped = projectFields(reader, payload, "", java.util.Set.of(fields.split(" ")), redactor);
		} catch (IOException invalidEvidence) { throw new IllegalArgumentException("Invalid recorded evidence", invalidEvidence); }

		return new Projection(observationRecord(observation, payload), clipped);
	}

	private static JsonObject observationRecord(DashboardObservation observation, com.google.gson.JsonElement payload) {
		JsonObject result = new JsonObject();
		result.addProperty("recordType", "observation"); result.addProperty("sequence", observation.sequence());
		result.addProperty("type", observation.type()); result.addProperty("tick", observation.tick());
		result.addProperty("serverTickId", observation.serverTickId()); result.addProperty("throughServerTickId", observation.throughServerTickId());
		result.addProperty("capturedAtMs", observation.capturedAtMs()); result.add("payload", payload); return result;
	}

	/** Skip raw envelopes without materializing their prompts, images or responses. */
	private static int projectFields(JsonReader reader, JsonObject target, String prefix, java.util.Set<String> fields, DiagnosticRedactor redactor) throws IOException {
		int clipped = 0;
		reader.beginObject();
		while (reader.hasNext()) {
			String name = reader.nextName();
			String path = prefix + name;
			JsonToken token = reader.peek();
			if (fields.contains(path) && token == JsonToken.STRING) {
				String value = redactor.redact(reader.nextString());
				if (value.length() > 256) { value = value.substring(0, 256); clipped++; }
				target.addProperty(name, value);
			} else if (fields.contains(path) && token == JsonToken.NUMBER) {
				target.add(name, com.google.gson.JsonParser.parseString(reader.nextString()));
			} else if (fields.contains(path) && token == JsonToken.BOOLEAN) {
				target.addProperty(name, reader.nextBoolean());
			} else if (token == JsonToken.BEGIN_OBJECT && fields.stream().anyMatch(field -> field.startsWith(path + "."))) {
				JsonObject child = new JsonObject();
				clipped += projectFields(reader, child, path + ".", fields, redactor);
				if (!child.isEmpty()) target.add(name, child);
			} else reader.skipValue();
		}
		reader.endObject();
		return clipped;
	}

	private record Projection(JsonObject value, int clippedFields) {}
}
