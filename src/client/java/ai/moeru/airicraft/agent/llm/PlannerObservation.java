package ai.moeru.airicraft.agent.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * World observations enter planner history as an {@code observe} tool exchange: a runtime-issued call followed by its
 * JSON result. Environment evidence then arrives on the tool-result channel instead of posing as a user turn.
 * The result JSON is canonical; request presentation may render it differently.
 */
public final class PlannerObservation {
	public static final String TOOL_NAME = PlannerToolCatalog.OBSERVE;
	private static final String RUNTIME_CALL_ID_PREFIX = "call_observe_";
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final AtomicLong NEXT_CALL = new AtomicLong();

	private PlannerObservation() {
	}

	public static List<LlmChatMessage> exchange(Map<String, Object> payload) {
		String callId = RUNTIME_CALL_ID_PREFIX + NEXT_CALL.incrementAndGet();
		PlannerToolCall call = new PlannerToolCall(callId, TOOL_NAME, new JsonObject(), null, null);
		return List.of(LlmChatMessage.assistantToolCall("", call), LlmChatMessage.tool(callId, render(payload)));
	}

	public static String render(Map<String, Object> payload) {
		return GSON.toJson(payload);
	}

	/** True for an assistant turn that only issues {@code observe}, whether the runtime or the model issued it. */
	public static boolean isCallOnly(LlmChatMessage message) {
		return message != null && "assistant".equals(message.role()) && message.hasToolCalls()
			&& (message.content() == null || message.content().isBlank())
			&& message.toolCalls().stream().allMatch(call -> TOOL_NAME.equals(call.name()));
	}

	/** Tool-call ids of every {@code observe} call in the history. */
	public static Set<String> callIds(List<LlmChatMessage> messages) {
		var ids = new HashSet<String>();
		for (LlmChatMessage message : messages) {
			if (!"assistant".equals(message.role())) continue;
			for (PlannerToolCall call : message.toolCalls()) if (TOOL_NAME.equals(call.name()) && call.id() != null) ids.add(call.id());
		}
		return ids;
	}

	public static Optional<JsonObject> latestPayload(List<LlmChatMessage> messages) {
		Set<String> ids = callIds(messages);
		for (int index = messages.size() - 1; index >= 0; index--) {
			LlmChatMessage message = messages.get(index);
			if (!"tool".equals(message.role()) || !ids.contains(message.toolCallId())) continue;
			try {
				return Optional.of(JsonParser.parseString(message.content()).getAsJsonObject());
			}
			catch (JsonParseException | IllegalStateException exception) {
				return Optional.empty();
			}
		}
		return Optional.empty();
	}
}
