package ai.moeru.airicraft.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;

public final class DashboardObservationStore {
	public static final long HISTORY_WINDOW_TICKS = 10L * 60L * 20L;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Map<String, Integer> TYPE_CAPS = Map.of(
		"runtime_snapshot", 4096,
		"semantic_event", 12000,
		"debug_timeline", 12000,
		"decision_state", 12001,
		"llm_call", 2048,
        "blueprint_designer", 4096,
		"log", 8192,
		"visual_frame", 601,
		"session_started", 128,
		"observation_gap", 512
	);

	private final ArrayDeque<DashboardObservation> observations = new ArrayDeque<>();
	private final Map<String, Integer> typeCounts = new LinkedHashMap<>();
	private final Map<String, Long> droppedByType = new LinkedHashMap<>();
	private final Map<String, Long> expiredByType = new LinkedHashMap<>();
	private long maxBytes;
	private long retainedBytes;
	private long nextSequence = 1L;
	private long latestTick;
	private long serverTickId;
	private boolean paused;
	private boolean serverClockAvailable;
	private long visualBytes;
	private String sessionId = UUID.randomUUID().toString();
	private long sessionStartedAtMs;
	private Map<String, Long> contextSequences;

	public DashboardObservationStore(long maxBytes) {
		this.maxBytes = Math.max(1024L * 1024L, maxBytes);
	}

	public synchronized String startSession(String reason, long tick, long capturedAtMs) {
		observations.clear();
		typeCounts.clear();
		droppedByType.clear();
		expiredByType.clear();
		if (contextSequences != null) contextSequences.clear();
		retainedBytes = 0L;
		visualBytes = 0L;
		sessionId = UUID.randomUUID().toString();
		sessionStartedAtMs = capturedAtMs;
		latestTick = tick;
		appendJson("session_started", tick, capturedAtMs, GSON.toJson(Map.of(
			"reason", reason == null || reason.isBlank() ? "runtime_started" : reason
		)));
		return sessionId;
	}

	/** The caller supplies completed logical-server ticks, never wall time or agent ticks. */
	public synchronized void advanceClock(long completedServerTick, boolean nextPaused, boolean available) {
		boolean advanced = serverTickId != completedServerTick;
		serverTickId = completedServerTick;
		paused = nextPaused;
		serverClockAvailable = available;
		if (advanced) {
			trimHistory();
		}
	}

	public synchronized boolean acceptsLogs() {
		return !paused;
	}

	public synchronized void appendLog(String message, long capturedAtMs) {
		if (acceptsLogs()) {
			append("log", latestTick, capturedAtMs, Map.of("message", message));
		}
	}

	public synchronized String sessionId() {
		return sessionId;
	}

	public synchronized long serverTickId() {
		return serverTickId;
	}

	public synchronized Map<String, Object> recordingStatus() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("schemaVersion", 2);
		result.put("sessionId", sessionId);
		result.put("serverClockAvailable", serverClockAvailable);
		result.put("paused", paused);
		result.put("serverTickId", serverTickId);
		result.put("historyWindowTicks", HISTORY_WINDOW_TICKS);
		result.put("windowStartServerTickId", Math.max(0L, serverTickId - HISTORY_WINDOW_TICKS));
		result.put("oldestSequence", observations.isEmpty() ? nextSequence : observations.getFirst().sequence());
		result.put("latestSequence", latestSequence());
		result.put("retainedBytes", retainedBytes);
		result.put("maxBytes", maxBytes);
		result.put("visualBytes", visualBytes);
		result.put("droppedByType", Map.copyOf(droppedByType));
		result.put("expiredByType", Map.copyOf(expiredByType));
		result.put("countsByType", Map.copyOf(typeCounts));
		return result;
	}

	/** A bounded, structured page. Images are metadata-only unless explicitly requested for export. */
	public Map<String, Object> recordingPage(long fromTick, long toTick, long since, long throughSequence,
		int limit, Set<String> types, boolean includeImages) {
		if (fromTick < 0L || toTick < fromTick || since < 0L || limit < 1 || limit > 1000) {
			throw new IllegalArgumentException("Expected nonnegative ticks/cursor, from <= to, and limit between 1 and 1000");
		}
		List<DashboardObservation> retained;
		Map<String, Object> result;
		synchronized (this) {
			retained = List.copyOf(observations);
			result = recordingStatus();
		}
		long endSequence = Math.min(throughSequence, ((Number) result.get("latestSequence")).longValue());
		List<JsonObject> records = new ArrayList<>();
		long bytes = 0L;
		long cursor = since;
		boolean hasMore = false;
		for (DashboardObservation observation : retained) {
			if (observation.sequence() <= since || observation.sequence() > endSequence) {
				continue;
			}
			if (observation.throughServerTickId() < fromTick || observation.serverTickId() > toTick
				|| (!types.isEmpty() && !types.contains(observation.type()))) {
				cursor = observation.sequence();
				continue;
			}
			if (!records.isEmpty() && (records.size() >= limit || bytes + observation.retainedBytes() > 1024L * 1024L)) {
				hasMore = true;
				break;
			}
			records.add(observationPayload(observation, includeImages));
			bytes += observation.retainedBytes();
			cursor = observation.sequence();
		}
		result.put("fromServerTickId", fromTick);
		result.put("toServerTickId", toTick);
		result.put("throughSequence", endSequence);
		result.put("nextCursor", hasMore ? cursor : endSequence);
		result.put("hasMore", hasMore);
		result.put("truncated", (since > 0L && !retained.isEmpty() && since < retained.getFirst().sequence() - 1L)
			|| fromTick < ((Number) result.get("windowStartServerTickId")).longValue() || !((Map<?, ?>) result.get("droppedByType")).isEmpty());
		result.put("observations", records);
		return result;
	}

	public JsonObject framePayload(long sequence) {
		DashboardObservation frame;
		synchronized (this) {
			frame = observations.stream().filter(observation -> observation.sequence() == sequence && observation.type().equals("visual_frame"))
				.findFirst().orElseThrow(() -> new IllegalArgumentException("Frame is unavailable or has left the rolling window"));
		}
		return observationPayload(frame, true);
	}

	public Map<String, Object> seek(long tick) {
		List<DashboardObservation> retained;
		Map<String, Object> result;
		synchronized (this) {
			retained = List.copyOf(observations);
			result = recordingStatus();
		}
		Map<String, DashboardObservation> baseline = new LinkedHashMap<>();
		ArrayDeque<DashboardObservation> events = new ArrayDeque<>();
		for (DashboardObservation observation : retained) {
			if (observation.serverTickId() > tick) {
				continue;
			}
			if (Set.of("runtime_snapshot", "decision_state", "visual_frame", "llm_call").contains(observation.type())) {
				baseline.put(observation.type(), observation);
			}
			else if (observation.serverTickId() >= tick - 100L) {
				events.addLast(observation);
				if (events.size() > 50) {
					events.removeFirst();
				}
			}
		}
		List<DashboardObservation> selected = new ArrayList<>(events);
        for(var observation:retained)if(observation.type().equals("blueprint_designer")&&observation.serverTickId()<=tick&&!selected.contains(observation))selected.add(observation);
		selected.addAll(baseline.values());
		Set<Long> references = new java.util.HashSet<>();
		for (var observation : selected) collectContextReferences(JsonParser.parseString(observation.payloadJson()), references);
		for (var observation : retained) {
			if (references.contains(observation.sequence()) && !selected.contains(observation)) selected.add(observation);
		}
		selected.sort(java.util.Comparator.comparingLong(DashboardObservation::sequence));
		result.put("selectedServerTickId", tick);
		result.put("observations", selected.stream().map(observation -> observationPayload(observation, false)).toList());
		return result;
	}

	private static void collectContextReferences(com.google.gson.JsonElement value, Set<Long> references) {
		if (value.isJsonArray()) value.getAsJsonArray().forEach(element -> collectContextReferences(element, references));
		else if (value.isJsonObject()) {
			value.getAsJsonObject().entrySet().forEach(entry -> {
				if ((entry.getKey().equals("observationSequence") || entry.getKey().equals("recipeCatalogSequence"))
					&& entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
					references.add(entry.getValue().getAsLong());
				} else collectContextReferences(entry.getValue(), references);
			});
		}
	}

	private static JsonObject observationPayload(DashboardObservation observation, boolean includeImages) {
		JsonObject result = new JsonObject();
		result.addProperty("recordType", "observation");
		result.addProperty("sequence", observation.sequence());
		result.addProperty("sessionId", observation.sessionId());
		result.addProperty("tick", observation.tick());
		result.addProperty("serverTickId", observation.serverTickId());
		result.addProperty("throughServerTickId", observation.throughServerTickId());
		result.addProperty("capturedAtMs", observation.capturedAtMs());
		result.addProperty("type", observation.type());
		var payload = JsonParser.parseString(observation.payloadJson());
		if (observation.type().equals("visual_frame") && !includeImages) {
			payload.getAsJsonObject().remove("imageBase64");
		}
		result.add("payload", payload);
		return result;
	}

	public DashboardObservation append(String type, long tick, long capturedAtMs, Object payload) {
		return appendJson(type, tick, capturedAtMs, GSON.toJson(payload));
	}

	public synchronized DashboardObservation appendJson(
		String type,
		long tick,
		long capturedAtMs,
		String payloadJson
	) {
		return appendAt(type, tick, serverTickId, capturedAtMs, payloadJson);
	}

	/** Reuse unchanged context while it is still retained; its validity follows its latest use. */
	public synchronized DashboardObservation appendContext(String type, long tick, long capturedAtMs, Object payload) {
		return appendContext(type, type, tick, capturedAtMs, payload);
	}

	public synchronized DashboardObservation appendContext(String type, String key, long tick, long capturedAtMs, Object payload) {
		String json = GSON.toJson(payload);
		if (contextSequences == null) contextSequences = new LinkedHashMap<>();
		String contextKey = type + ':' + key;
		Long previousSequence = contextSequences.get(contextKey);
		DashboardObservation previous = null;
		for (var iterator = observations.descendingIterator(); iterator.hasNext();) {
			DashboardObservation candidate = iterator.next();
			if (previousSequence != null && candidate.sequence() == previousSequence) {
				previous = candidate;
				break;
			}
		}
		if (previous != null && previous.payloadJson().equals(json)) {
			extendObservation(sessionId, previous.sequence(), type, serverTickId);
			return previous;
		}
		DashboardObservation added = appendAt(type, tick, serverTickId, capturedAtMs, json);
		contextSequences.put(contextKey, added.sequence());
		return added;
	}

	private DashboardObservation appendAt(String type, long tick, long observedServerTick, long capturedAtMs, String payloadJson) {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(payloadJson, "payloadJson");
		latestTick = Math.max(latestTick, tick);

		DashboardObservation observation = new DashboardObservation(
			nextSequence++,
			sessionId,
			tick,
			observedServerTick,
			observedServerTick,
			capturedAtMs,
			type,
			payloadJson
		);
		if (observation.retainedBytes() > maxBytes) {
			incrementDropped(type);
			observation = new DashboardObservation(
				observation.sequence(),
				sessionId,
				tick,
				observedServerTick,
				observedServerTick,
				capturedAtMs,
				"observation_gap",
				GSON.toJson(Map.of(
					"reason", "observation_exceeds_history_budget",
					"originalType", type,
					"estimatedBytes", observation.retainedBytes()
				))
			);
		}

		observations.addLast(observation);
		retainedBytes += observation.retainedBytes();
		if (observation.type().equals("visual_frame")) {
			visualBytes += observation.retainedBytes();
		}
		typeCounts.merge(observation.type(), 1, Integer::sum);
		trimType(observation.type());
		trimBudget();
		trimHistory();
		notifyAll();
		return observation;
	}

	public synchronized DashboardObservation appendFrame(String expectedSession, long tick, long frameServerTick, long capturedAtMs, Object payload) {
		if (!sessionId.equals(expectedSession) || frameServerTick < serverTickId - HISTORY_WINDOW_TICKS) {
			return null;
		}
		return appendAt("visual_frame", tick, frameServerTick, capturedAtMs, GSON.toJson(payload));
	}

	/** Extend an identical frame's validity without retaining or publishing another image. */
	public synchronized boolean extendFrame(String expectedSession, long sequence, long throughTick) {
		return extendObservation(expectedSession, sequence, "visual_frame", throughTick);
	}

	private boolean extendObservation(String expectedSession, long sequence, String type, long throughTick) {
		if (!sessionId.equals(expectedSession)) {
			return false;
		}
		ArrayDeque<DashboardObservation> updated = new ArrayDeque<>();
		boolean found = false;
		for (DashboardObservation observation : observations) {
			if (observation.sequence() == sequence && observation.type().equals(type)) {
				observation = new DashboardObservation(observation.sequence(), observation.sessionId(), observation.tick(),
					observation.serverTickId(), throughTick, observation.capturedAtMs(), observation.type(), observation.payloadJson());
				found = true;
			}
			updated.addLast(observation);
		}
		if (found) {
			observations.clear();
			observations.addAll(updated);
		}
		return found;
	}

	public synchronized void updateMaxBytes(long nextMaxBytes) {
		maxBytes = Math.max(1024L * 1024L, nextMaxBytes);
		trimBudget();
	}

	public synchronized Query queryAfter(long sinceSequence, int limit) {
		return queryAfter(sinceSequence, limit, Long.MAX_VALUE);
	}

	public synchronized Query queryAfter(long sinceSequence, int limit, long byteLimit) {
		int safeLimit = Math.max(1, Math.min(5000, limit));
		long safeByteLimit = Math.max(1L, byteLimit);
		long oldestSequence = observations.isEmpty() ? nextSequence : observations.peekFirst().sequence();
		long latestSequence = observations.isEmpty() ? nextSequence - 1L : observations.peekLast().sequence();
		ArrayList<DashboardObservation> matches = new ArrayList<>();
		long matchedBytes = 0L;
		for (DashboardObservation observation : observations) {
			if (observation.sequence() > sinceSequence) {
				long observationBytes = observation.retainedBytes();
				if (!matches.isEmpty() && observationBytes > safeByteLimit - matchedBytes) {
					break;
				}
				matches.add(observation);
				matchedBytes += observationBytes;
				if (matches.size() >= safeLimit) {
					break;
				}
			}
		}
		boolean truncated = !observations.isEmpty() && sinceSequence < oldestSequence - 1L;
		return new Query(
			sessionId,
			sessionStartedAtMs,
			latestTick,
			oldestSequence,
			latestSequence,
			truncated,
			retainedBytes,
			maxBytes,
			serverTickId,
			HISTORY_WINDOW_TICKS,
			paused,
			serverClockAvailable,
			Map.copyOf(droppedByType),
			List.copyOf(matches)
		);
	}

	public synchronized Query snapshot() {
		return queryAfter(Long.MIN_VALUE, 5000);
	}

	public synchronized boolean awaitAfter(long sinceSequence, Duration timeout) throws InterruptedException {
		long timeoutMillis = Math.max(1L, timeout.toMillis());
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (latestSequence() <= sinceSequence) {
			long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0L) {
				return false;
			}
			wait(remaining);
		}
		return true;
	}

	public synchronized List<DashboardObservation> retainedObservations() {
		return List.copyOf(observations);
	}

	public synchronized long latestTick() {
		return latestTick;
	}

	private long latestSequence() {
		return observations.isEmpty() ? nextSequence - 1L : observations.peekLast().sequence();
	}

	private void trimType(String type) {
		int cap = TYPE_CAPS.getOrDefault(type, 4096);
		while (typeCounts.getOrDefault(type, 0) > cap) {
			Iterator<DashboardObservation> iterator = observations.iterator();
			while (iterator.hasNext()) {
				DashboardObservation candidate = iterator.next();
				if (candidate.type().equals(type)) {
					iterator.remove();
					removed(candidate);
					break;
				}
			}
		}
	}

	private void trimBudget() {
		// RGB cannot consume the entire decision-evidence budget.
		Iterator<DashboardObservation> frames = observations.iterator();
		while (visualBytes > maxBytes / 2L && frames.hasNext()) {
			DashboardObservation observation = frames.next();
			if (observation.type().equals("visual_frame")) {
				frames.remove();
				removed(observation);
			}
		}
		while (retainedBytes > maxBytes && !observations.isEmpty()) {
			// Reused context can start long ago and still serve current snapshots.
			// Evict by the end of validity, rather than its original insertion time.
			DashboardObservation oldest = observations.stream()
				.min(java.util.Comparator.comparingLong(DashboardObservation::throughServerTickId)).orElseThrow();
			observations.remove(oldest);
			removed(oldest);
		}
	}

	private void trimHistory() {
		long cutoff = serverTickId - HISTORY_WINDOW_TICKS;
		Iterator<DashboardObservation> iterator = observations.iterator();
		while (iterator.hasNext()) {
			DashboardObservation observation = iterator.next();
			if (observation.throughServerTickId() < cutoff) {
				iterator.remove();
				removed(observation, true);
			}
		}
	}

	private void removed(DashboardObservation observation) {
		removed(observation, false);
	}

	private void removed(DashboardObservation observation, boolean expired) {
		if (contextSequences != null) contextSequences.values().removeIf(sequence -> sequence == observation.sequence());
		retainedBytes -= observation.retainedBytes();
		if (observation.type().equals("visual_frame")) {
			visualBytes -= observation.retainedBytes();
		}
		typeCounts.computeIfPresent(observation.type(), (ignored, count) -> count <= 1 ? null : count - 1);
		if (expired) {
			expiredByType.merge(observation.type(), 1L, Long::sum);
		}
		else {
			incrementDropped(observation.type());
		}
	}

	private void incrementDropped(String type) {
		droppedByType.merge(type, 1L, Long::sum);
	}

	public record Query(
		String sessionId,
		long sessionStartedAtMs,
		long latestTick,
		long oldestSequence,
		long latestSequence,
		boolean truncated,
		long retainedBytes,
		long maxBytes,
		long serverTickId,
		long historyWindowTicks,
		boolean paused,
		boolean serverClockAvailable,
		Map<String, Long> droppedByType,
		List<DashboardObservation> observations
	) {
	}
}
