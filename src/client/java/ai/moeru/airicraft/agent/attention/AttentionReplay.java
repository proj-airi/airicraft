package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.agent.events.EventPolicyRule;
import ai.moeru.airicraft.agent.events.EventPolicyState;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleException;
import ai.moeru.airicraft.rules.RuleModule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Re-decides a recorded run. For every recorded attention decision that carries its inputs, the event is decided
 * again by the Java reference and by a rule module (the bundled one unless another is given), in recording order
 * so stateful rules see the same history. Writes {@value #OUTPUT} into the run directory; summarize it with
 * {@code python3 scripts/wake_ledger.py replay-summary <run>}.
 */
public final class AttentionReplay {
	public static final String SCHEMA = "airicraft.attention-replay.v1";
	public static final String OUTPUT = "attention-replay.json";
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting().create();
	private static final TypeToken<Map<String, Object>> PAYLOAD = new TypeToken<>() {
	};

	private AttentionReplay() {
	}

	/** {@code AttentionReplay <run-dir> [--module <attention.js>]} */
	public static void main(String[] args) throws Exception {
		if (args.length != 1 && !(args.length == 3 && "--module".equals(args[1]))) {
			System.err.println("usage: AttentionReplay <run-dir> [--module <attention.js>]");
			System.exit(2);
		}
		Path runDir = Path.of(args[0]);
		RuleModule module = args.length == 3
			? new RuleModule("file:" + Path.of(args[2]).getFileName(), Files.readString(Path.of(args[2]), StandardCharsets.UTF_8))
			: RuleModule.bundledAttention();
		Map<String, Object> report = replay(runDir, module, Duration.ofSeconds(60));
		Path output = runDir.resolve(OUTPUT);
		Files.writeString(output, GSON.toJson(report) + "\n", StandardCharsets.UTF_8);
		System.out.printf("replayed %s of %s decisions; recorded!=reference %s, reference!=rules %s -> %s%n",
			report.get("replayed"), report.get("decisions"), report.get("recordedVsReference"), report.get("referenceVsRules"), output);
		System.exit(0);
	}

	public static Map<String, Object> replay(Path runDir, RuleModule module, Duration warmTimeout) throws IOException, RuleException {
		Objects.requireNonNull(module, "module");
		Map<Long, SemanticEvent> events = readEvents(runDir.resolve("events.jsonl"));
		List<AttentionDecision> decisions = readDecisions(runDir.resolve("attention-decisions.jsonl"));
		RuleEngine.shared(module).awaitReady(warmTimeout);

		var current = new AttentionInputs[1];
		var diagnostics = new SemanticEventBuffer(256);
		var rules = new RuleAttentionPolicy(() -> current[0].state(), event -> current[0].evidence(), diagnostics, module);
		var entries = new ArrayList<Map<String, Object>>();
		int skipped = 0;
		int missingEvents = 0;
		int recordedVsReference = 0;
		int referenceVsRules = 0;
		for (AttentionDecision recorded : decisions) {
			AttentionDecision.Inputs inputs = recorded.inputs();
			if (inputs == null || inputs.state() == null || inputs.evidence() == null || inputs.profile() == null) {
				skipped++;
				continue;
			}
			SemanticEvent event = events.get(recorded.seqNo());
			if (event == null) {
				missingEvents++;
				continue;
			}
			var store = new EventPolicyState();
			for (EventPolicyRule rule : inputs.plannerRules()) store.upsert(rule);
			current[0] = new AttentionInputs(inputs.state(), inputs.evidence());
			AttentionOutcome reference = ReferenceAttentionPolicy.decide(inputs.state(), inputs.evidence(), event, inputs.profile(),
				store, inputs.plannerEnabled());
			AttentionOutcome ruled = rules.decide(event, inputs.profile(), store, inputs.plannerEnabled());
			Map<String, Object> recordedView = view(recorded.emitSemantic(), recorded.delivery(), recorded.urgency(), recorded.stage(), recorded.ruleId());
			Map<String, Object> referenceView = view(reference);
			Map<String, Object> rulesView = view(ruled);
			boolean recordedDiffers = !sameDecision(recordedView, referenceView);
			boolean rulesDiffer = !sameDecision(referenceView, rulesView);
			if (recordedDiffers) recordedVsReference++;
			if (rulesDiffer) referenceVsRules++;
			var entry = new LinkedHashMap<String, Object>();
			entry.put("seqNo", recorded.seqNo());
			entry.put("tick", recorded.tick());
			entry.put("type", recorded.type());
			entry.put("recorded", recordedView);
			entry.put("reference", referenceView);
			entry.put("rules", rulesView);
			entry.put("recordedDiffersFromReference", recordedDiffers);
			entry.put("rulesDifferFromReference", rulesDiffer);
			entries.add(entry);
		}

		var report = new LinkedHashMap<String, Object>();
		report.put("schema", SCHEMA);
		report.put("runDir", runDir.toAbsolutePath().normalize().toString());
		report.put("module", module.origin());
		report.put("moduleSha", module.sha());
		report.put("decisions", decisions.size());
		report.put("replayed", entries.size());
		report.put("skippedWithoutInputs", skipped);
		report.put("missingEvents", missingEvents);
		report.put("recordedVsReference", recordedVsReference);
		report.put("referenceVsRules", referenceVsRules);
		report.put("ruleEngine", rules.debugState());
		report.put("entries", entries);
		return report;
	}

	/** Delivery, urgency, semantic feed and rule id decide the outcome; the stage says only who decided it. */
	private static boolean sameDecision(Map<String, Object> left, Map<String, Object> right) {
		return left.get("emitSemantic").equals(right.get("emitSemantic")) && left.get("delivery").equals(right.get("delivery"))
			&& left.get("urgency").equals(right.get("urgency")) && Objects.equals(left.get("ruleId"), right.get("ruleId"));
	}

	private static Map<String, Object> view(AttentionOutcome outcome) {
		WakeDecision wake = outcome.wake();
		return view(outcome.emitSemantic(), wake.delivery(), wake.urgency(), wake.stage(), wake.ruleId());
	}

	private static Map<String, Object> view(boolean emitSemantic, Delivery delivery, Urgency urgency, AttentionStage stage, String ruleId) {
		var view = new LinkedHashMap<String, Object>();
		view.put("emitSemantic", emitSemantic);
		view.put("delivery", delivery.name());
		view.put("urgency", urgency.name());
		view.put("stage", stage.name());
		view.put("ruleId", ruleId);
		return view;
	}

	private static Map<Long, SemanticEvent> readEvents(Path path) throws IOException {
		var events = new HashMap<Long, SemanticEvent>();
		if (Files.notExists(path)) return events;
		for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			if (line.isBlank()) continue;
			JsonObject event = JsonParser.parseString(line).getAsJsonObject().getAsJsonObject("event");
			Map<String, Object> payload = event.has("payload") && event.get("payload").isJsonObject()
				? GSON.fromJson(event.get("payload"), PAYLOAD.getType()) : Map.of();
			long seqNo = event.get("seqNo").getAsLong();
			events.put(seqNo, new SemanticEvent(seqNo, event.get("tick").getAsLong(),
				event.has("timestampMs") ? event.get("timestampMs").getAsLong() : 0L, event.get("type").getAsString(), payload));
		}
		return events;
	}

	private static List<AttentionDecision> readDecisions(Path path) throws IOException {
		var decisions = new ArrayList<AttentionDecision>();
		if (Files.notExists(path)) return decisions;
		for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			if (line.isBlank()) continue;
			decisions.add(GSON.fromJson(JsonParser.parseString(line).getAsJsonObject().get("decision"), AttentionDecision.class));
		}
		return decisions;
	}
}
