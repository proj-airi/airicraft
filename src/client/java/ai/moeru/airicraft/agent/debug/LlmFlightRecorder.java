package ai.moeru.airicraft.agent.debug;

import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerObservation;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LlmFlightRecorder {
	private static final int DEFAULT_CAPACITY = 2048;
	private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

	private final int capacity;
	private final long maxBytes;
	private long retainedBytes;
	private final ArrayDeque<MutableRecord> records = new ArrayDeque<>();
	private final Map<Long, MutableRecord> pendingByThread = new HashMap<>();
	private long nextSequenceId = 1L;
	private java.util.function.LongSupplier tickSource = () -> -1L;
	private java.util.function.LongSupplier serverTickSource = () -> -1L;

	public synchronized void configureClock(java.util.function.LongSupplier tick, java.util.function.LongSupplier serverTick) {
		tickSource = java.util.Objects.requireNonNull(tick);
		serverTickSource = java.util.Objects.requireNonNull(serverTick);
	}

	public LlmFlightRecorder() {
		this(DEFAULT_CAPACITY);
	}

	public LlmFlightRecorder(int capacity) {
		this(capacity, 64L * 1024 * 1024);
	}

	public LlmFlightRecorder(int capacity, long maxBytes) {
		this.capacity = Math.max(1, capacity);
		this.maxBytes = Math.max(1L, maxBytes);
	}

	public synchronized void recordRequest(
		String requestKind,
		String threadId,
		String providerName,
		URI endpoint,
		String model,
		long timeoutMillis,
		LlmConversation conversation,
		String requestBody
	) {
		long javaThreadId = Thread.currentThread().threadId();
		MutableRecord record = new MutableRecord(nextSequenceId++, System.currentTimeMillis(), javaThreadId);
		record.status = "REQUESTED";
		record.requestKind = requestKind;
		record.threadId = threadId;
		record.providerName = providerName;
		record.endpoint = endpoint == null ? "" : endpoint.toString();
		record.model = model;
		record.timeoutMillis = timeoutMillis;
		record.messageCount = conversation == null ? 0 : conversation.messages().size();
		record.imageAttached = conversation != null && conversation.messages().stream().anyMatch(message -> message.imageAttachment() != null);
		record.requestBody = requestBody;
		record.dispatchTick = tickSource.getAsLong();
		record.dispatchServerTick = serverTickSource.getAsLong();
		if (conversation != null) {
			PlannerObservation.latestPayload(conversation.messages()).ifPresent(context -> {
				var metadata = new java.util.LinkedHashMap<String, Object>();
				for (String key : List.of("worldSessionId", "tick", "serverTick", "decisionOwner", "actuatorOwner", "afterEventSequence", "throughEventSequence", "missingEventRange")) {
					if (context.has(key)) metadata.put(key, context.get(key).deepCopy());
				}
				var current = context.getAsJsonObject("current");
				if (current != null && current.has("job")) metadata.put("work", current.get("job").deepCopy());
				if (current != null && current.has("work")) metadata.put("work", current.get("work").deepCopy());
				record.decisionContext = Map.copyOf(metadata);
			});
		}
		records.addLast(record);
		pendingByThread.put(javaThreadId, record);
		updateRetention(record);
	}

	public synchronized java.util.function.Consumer<String> streamListener() {
		MutableRecord record = pendingOrSynthetic();
		var preview = new ai.moeru.airicraft.agent.llm.PlannerStreamPreview();
		return delta -> {
			synchronized (LlmFlightRecorder.this) {
				if (!record.retained) return;
				if (!record.status.equals("REQUESTED") && !record.status.equals("STREAMING")) return;
				preview.append(delta);
				record.status = "STREAMING";
				record.rawResponseBody = preview.text();
				updateRetention(record);
			}
		};
	}

	public synchronized void recordRawResponse(Integer statusCode, String responseModel, LlmUsageSnapshot usage, String rawResponseBody) {
		MutableRecord record = pendingOrSynthetic();
		record.status = "RAW_RESPONSE";
		record.completedAtMs = System.currentTimeMillis();
		record.statusCode = statusCode;
		record.responseModel = responseModel;
		record.usage = usage;
		if (record.retained) record.rawResponseBody = rawResponseBody;
		updateRetention(record);
	}

	public synchronized void recordParsedResponse(String parsedResponseKind, Integer statusCode, String responseModel, LlmUsageSnapshot usage, Object parsedResponse) {
		MutableRecord record = pendingOrSynthetic();
		record.status = "COMPLETED";
		record.completedAtMs = System.currentTimeMillis();
		record.statusCode = statusCode;
		record.responseModel = responseModel;
		record.usage = usage;
		record.parsedResponseKind = parsedResponseKind;
		if (record.retained) {
			record.parsedResponse = parsedResponse;
			record.parsedBytes = jsonBytes(parsedResponse);
		}
		updateRetention(record);
		pendingByThread.remove(record.javaThreadId);
	}

	public synchronized void recordFailure(String failureType, String failureMessage) {
		MutableRecord record = pendingOrSynthetic();
		record.status = "FAILED";
		record.completedAtMs = System.currentTimeMillis();
		record.failureType = failureType;
		if (record.retained) record.failureMessage = failureMessage;
		updateRetention(record);
		pendingByThread.remove(record.javaThreadId);
	}

	public synchronized LlmFlightRecordQueryResult query(Long sinceSequenceId) {
		long oldestSequenceId = records.isEmpty() ? nextSequenceId : records.peekFirst().sequenceId;
		long latestSequenceId = nextSequenceId - 1L;
		long effectiveSince = sinceSequenceId == null ? 0L : sinceSequenceId.longValue();
		ArrayList<LlmFlightRecord> matches = new ArrayList<>();
		for (MutableRecord record : records) {
			if (record.sequenceId > effectiveSince) {
				matches.add(record.snapshot());
			}
		}
		boolean truncated = oldestSequenceId > 1L && (sinceSequenceId == null || sinceSequenceId < oldestSequenceId - 1L);
		return new LlmFlightRecordQueryResult(oldestSequenceId, latestSequenceId, truncated, matches);
	}

	private MutableRecord pendingOrSynthetic() {
		long javaThreadId = Thread.currentThread().threadId();
		MutableRecord record = pendingByThread.get(javaThreadId);
		if (record != null) {
			return record;
		}
		MutableRecord synthetic = new MutableRecord(nextSequenceId++, System.currentTimeMillis(), javaThreadId);
		synthetic.status = "UNMATCHED";
		records.addLast(synthetic);
		pendingByThread.put(javaThreadId, synthetic);
		updateRetention(synthetic);
		return synthetic;
	}

	private void updateRetention(MutableRecord record) {
		if (!record.retained) return;
		retainedBytes -= record.retainedBytes;
		record.retainedBytes = 1024L + record.parsedBytes + jsonBytes(record.decisionContext);
		for (String value : new String[] {record.requestBody, record.rawResponseBody, record.failureMessage,
			record.requestKind, record.threadId, record.providerName, record.endpoint, record.model,
			record.responseModel, record.parsedResponseKind, record.failureType}) {
			if (value != null) record.retainedBytes += 2L * value.length();
		}
		retainedBytes += record.retainedBytes;
		while (records.size() > capacity || retainedBytes > maxBytes) {
			MutableRecord removed = records.removeFirst();
			retainedBytes -= removed.retainedBytes;
			removed.retained = false;
			// In-flight callbacks may still hold this object. Keep its identity until completion,
			// but release its payload and never resurrect an evicted record as a synthetic call.
			removed.requestBody = "";
			removed.rawResponseBody = "";
			removed.parsedResponse = null;
			removed.failureMessage = "";
			removed.decisionContext = Map.of();
		}
	}

	private static long jsonBytes(Object value) {
		// Estimate parsed/context payloads without allocating another serialized body.
		var counter = new java.io.Writer() {
			long characters;
			@Override public void write(char[] chars, int offset, int length) { characters += length; }
			@Override public void write(String text, int offset, int length) { characters += length; }
			@Override public void flush() {}
			@Override public void close() {}
		};
		GSON.toJson(value, counter);
		return 2L * counter.characters;
	}

	private static final class MutableRecord {
		private boolean retained = true;
		private long retainedBytes;
		private long parsedBytes;
		private final long sequenceId;
		private final long requestedAtMs;
		private final long javaThreadId;
		private long completedAtMs;
		private String status = "REQUESTED";
		private String requestKind = "";
		private String threadId = "";
		private String providerName = "";
		private String endpoint = "";
		private String model = "";
		private long timeoutMillis;
		private int messageCount;
		private boolean imageAttached;
		private String requestBody = "";
		private Integer statusCode;
		private String responseModel = "";
		private LlmUsageSnapshot usage = LlmUsageSnapshot.unknown();
		private String rawResponseBody = "";
		private String parsedResponseKind = "";
		private Object parsedResponse;
		private String failureType = "";
		private String failureMessage = "";
		private long dispatchTick = -1L;
		private long dispatchServerTick = -1L;
		private Map<String, Object> decisionContext = Map.of();

		private MutableRecord(long sequenceId, long requestedAtMs, long javaThreadId) {
			this.sequenceId = sequenceId;
			this.requestedAtMs = requestedAtMs;
			this.javaThreadId = javaThreadId;
		}

		private LlmFlightRecord snapshot() {
			return new LlmFlightRecord(
				sequenceId,
				requestedAtMs,
				completedAtMs,
				status,
				requestKind,
				threadId,
				javaThreadId,
				providerName,
				endpoint,
				model,
				timeoutMillis,
				messageCount,
				imageAttached,
				requestBody,
				statusCode,
				responseModel,
				usage,
				rawResponseBody,
				parsedResponseKind,
				parsedResponse,
				failureType,
				failureMessage,
				dispatchTick,
				dispatchServerTick,
				decisionContext
			);
		}
	}
}
