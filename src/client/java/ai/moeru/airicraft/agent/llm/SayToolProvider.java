package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/**
 * The planner's only way to talk while it also calls tools: tool turns carry no visible text.
 * {@code when=now} is sent as soon as the response is accepted and never enters the tool queue;
 * {@code when=queued} waits for its FIFO position, between the actions around it.
 */
public final class SayToolProvider implements PlannerToolProvider {
	public static final String SAY = "say";
	public static final String NOW = "now";
	public static final String QUEUED = "queued";

	private final PlannerChatSink chat;

	public SayToolProvider(PlannerChatSink chat) {
		this.chat = Objects.requireNonNull(chat, "chat");
	}

	public static boolean isSayNow(PlannerToolCall call) {
		return call != null && SAY.equals(call.name()) && NOW.equals(when(call.arguments()));
	}

	public static String text(PlannerToolCall call) {
		return call.arguments().get("text").getAsString();
	}

	@Override public String id() { return SAY; }
	@Override public boolean handles(String toolName) { return SAY.equals(toolName); }
	/** Chat changes nothing in the world, so it needs no work receipt and batches with reads. */
	@Override public boolean isReadTool(String toolName) { return true; }

	@Override
	public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider(SAY,
			"Say one short line in Minecraft chat. when=now (default) sends it immediately while queued work keeps running; "
				+ "when=queued sends it when the queue reaches this call, between the actions around it.",
			propertiesForProvider(
				propForProvider("text", stringForProvider("One plaintext chat line under " + PlannerChatContract.MAX_MESSAGE_LENGTH + " characters.")),
				propForProvider("when", enumStringForProvider("now (default) or queued.", List.of(NOW, QUEUED)))),
			List.of("text")));
	}

	@Override
	public void validateArguments(String toolName, JsonObject arguments) {
		for (String key : arguments.keySet()) {
			if (!List.of("text", "when").contains(key)) throw new JsonParseException("unknown say argument: " + key);
		}
		if (!arguments.has("text") || !arguments.get("text").isJsonPrimitive() || !arguments.getAsJsonPrimitive("text").isString()
			|| arguments.get("text").getAsString().isBlank())
			throw new JsonParseException("say requires nonblank text");
		if (arguments.has("when") && !List.of(NOW, QUEUED).contains(when(arguments)))
			throw new JsonParseException("say when must be now or queued");
	}

	@Override
	public CompletableFuture<String> execute(PlannerToolCall call) {
		validateArguments(call.name(), call.arguments());
		chat.say(text(call));
		return CompletableFuture.completedFuture("Said in chat.");
	}

	private static String when(JsonObject arguments) {
		var value = arguments.get("when");
		return value == null || value.isJsonNull() ? NOW
			: value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
	}
}
