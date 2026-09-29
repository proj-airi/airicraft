package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleException;
import ai.moeru.airicraft.rules.RuleModule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-runs a recorded run's salience steps ({@code salience-steps.jsonl}) through a module, each with its recorded
 * input and prior state, and diffs the percepts and drops. The bundled module must reproduce a bundled recording
 * exactly; an override shows what it would have noticed instead.
 */
public final class SalienceReplay {
	public static final String SCHEMA = "airicraft.salience-replay.v1";
	public static final String INPUT = "salience-steps.jsonl";
	public static final String OUTPUT = "salience-replay.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private SalienceReplay() {
	}

	/** {@code SalienceReplay <run-dir> [--module <salience.js>]} */
	public static void main(String[] args) throws Exception {
		if (args.length != 1 && !(args.length == 3 && "--module".equals(args[1]))) {
			System.err.println("usage: SalienceReplay <run-dir> [--module <salience.js>]");
			System.exit(2);
		}
		Path runDir = Path.of(args[0]);
		RuleModule module = args.length == 3
			? new RuleModule("file:" + args[2], Files.readString(Path.of(args[2]), StandardCharsets.UTF_8), RuleModule.Hook.SALIENCE)
			: RuleModule.bundledSalience();
		Map<String, Object> report = replay(runDir, module, Duration.ofSeconds(120));
		Files.writeString(runDir.resolve(OUTPUT), GSON.toJson(report) + "\n", StandardCharsets.UTF_8);
		System.out.println("salience replay: " + report.get("steps") + " steps, " + report.get("differing") + " differing, report "
			+ runDir.resolve(OUTPUT));
		System.exit(0);
	}

	public static Map<String, Object> replay(Path runDir, RuleModule module, Duration warmTimeout) throws IOException, RuleException {
		RuleEngine engine = RuleEngine.shared(module);
		engine.awaitReady(warmTimeout);
		var differences = new ArrayList<Map<String, Object>>();
		int steps = 0;
		Path input = runDir.resolve(INPUT);
		List<String> lines = Files.exists(input) ? Files.readAllLines(input, StandardCharsets.UTF_8) : List.of();
		for (String line : lines) {
			if (line.isBlank()) continue;
			JsonObject step = JsonParser.parseString(line).getAsJsonObject().getAsJsonObject("step");
			steps++;
			var replayed = engine.step(step.get("input").toString(), step.get("stateBefore").getAsString());
			var recorded = new JsonObject();
			recorded.add("percepts", step.get("percepts"));
			recorded.add("drops", step.get("drops"));
			var now = new JsonObject();
			now.add("percepts", replayed.percepts());
			now.add("drops", replayed.drops());
			if (!recorded.equals(now)) {
				var difference = new LinkedHashMap<String, Object>();
				difference.put("tick", step.get("tick").getAsLong());
				difference.put("recorded", recorded);
				difference.put("replayed", now);
				differences.add(difference);
			}
		}
		var report = new LinkedHashMap<String, Object>();
		report.put("schema", SCHEMA);
		report.put("module", module.origin());
		report.put("steps", steps);
		report.put("differing", differences.size());
		report.put("differences", differences.subList(0, Math.min(differences.size(), 50)));
		return report;
	}
}
