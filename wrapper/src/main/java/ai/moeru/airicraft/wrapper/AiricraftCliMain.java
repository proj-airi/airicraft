package ai.moeru.airicraft.wrapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

public final class AiricraftCliMain {
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private AiricraftCliMain() {
	}

	public static void main(String[] args) {
		PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
		CommandLine commandLine = createCommandLine(new HttpBridgeTransport(), out);
		int exitCode = commandLine.execute(args);
		System.exit(exitCode);
	}

	static CommandLine createCommandLine(MinecraftTransport transport, PrintWriter out) {
		CliContext context = new CliContext(transport, out);
		CommandLine root = new CommandLine(new UsageCommand(out, "airicraft", "Airicraft agent CLI"));
		root.setCommandName("airicraft");
		root.setExecutionExceptionHandler(new CliExecutionExceptionHandler(context));
		root.setParameterExceptionHandler(new CliParameterExceptionHandler(context));
		root.setUsageHelpWidth(100);

		root.addSubcommand(new StatusCommand(context));
		root.addSubcommand(new ReloadCommand(context));

		root.addSubcommand("agent", new UsageCommand(out, "airicraft agent", "Agent observability and debug commands"));
		CommandLine agent = root.getSubcommands().get("agent");
		agent.addSubcommand(new AgentStatusCommand(context));
		agent.addSubcommand("tools", new UsageCommand(out, "airicraft agent tools", "External Codex driver tool commands"));
		CommandLine agentTools = agent.getSubcommands().get("tools");
		agentTools.addSubcommand(new AgentToolsListCommand(context));
		agentTools.addSubcommand(new AgentToolsCallCommand(context));
		agent.addSubcommand(new AgentSessionCommand(context));
		CommandLine agentSession = agent.getSubcommands().get("session");
		agentSession.addSubcommand(new AgentSessionOpenLanCommand(context));
		agent.addSubcommand(new AgentGoalsCommand(context));
		agent.addSubcommand(new AgentTasksCommand(context));
		agent.addSubcommand(new AgentLedgerCommand(context));
		agent.addSubcommand(new AgentEvidenceCommand(context));
		agent.addSubcommand(new AgentStepExecutionCommand(context));
		agent.addSubcommand("actions", new UsageCommand(out, "airicraft agent actions", "Composable action graph debug commands"));
		CommandLine agentActions = agent.getSubcommands().get("actions");
		agentActions.addSubcommand(new AgentActionsInspectCommand(context));
		agentActions.addSubcommand("goal", new UsageCommand(out, "airicraft agent actions goal", "Action graph goal commands"));
		CommandLine agentActionsGoal = agentActions.getSubcommands().get("goal");
		agentActionsGoal.addSubcommand(new AgentActionsGoalListCommand(context));
		agentActionsGoal.addSubcommand(new AgentActionsGoalInspectCommand(context));
		agentActionsGoal.addSubcommand(new AgentActionsGoalStartCommand(context));
		agentActionsGoal.addSubcommand(new AgentActionsGoalCancelCommand(context));
		agentActions.addSubcommand("watches", new UsageCommand(out, "airicraft agent actions watches", "Action graph watch commands"));
		CommandLine agentActionsWatches = agentActions.getSubcommands().get("watches");
		agentActionsWatches.addSubcommand(new AgentActionsWatchesListCommand(context));
		agent.addSubcommand("mission", new UsageCommand(out, "airicraft agent mission", "Mission-level agent commands"));
		CommandLine agentMission = agent.getSubcommands().get("mission");
		agentMission.addSubcommand(new AgentMissionSubmitCommand(context));
		CommandLine agentTasks = agent.getSubcommands().get("tasks");
		agentTasks.addSubcommand(new AgentTasksSubmitCommand(context));
		agentTasks.addSubcommand(new AgentTasksCancelCommand(context));
		agentTasks.addSubcommand(new AgentTasksResumeCommand(context));
		agent.addSubcommand(new AgentTreeCommand(context));
		agent.addSubcommand(new AgentDialogueCommand(context));
		agent.addSubcommand("debug", new UsageCommand(out, "airicraft agent debug", "Agent debug commands"));
		CommandLine agentDebug = agent.getSubcommands().get("debug");
		agentDebug.addSubcommand(new AgentDebugChatCommand(context));
		agentDebug.addSubcommand(new AgentDebugIdleTriggerCommand(context));
		agentDebug.addSubcommand(new AgentDebugStateCommand(context));
		agentDebug.addSubcommand(new AgentDebugTimelineCommand(context));
		agentDebug.addSubcommand("recording", new UsageCommand(out, "airicraft agent debug recording", "Rolling live-playtest evidence"));
		CommandLine recording = agentDebug.getSubcommands().get("recording");
		recording.addSubcommand(new AgentDebugRecordingStatusCommand(context));
		recording.addSubcommand(new AgentDebugRecordingQueryCommand(context));
		recording.addSubcommand(new AgentDebugRecordingExportCommand(context));
		recording.addSubcommand(new AgentDebugRecordingFrameCommand(context));
		agentDebug.addSubcommand("ticks", new UsageCommand(out, "airicraft agent debug ticks", "Client tick debug commands"));
		CommandLine agentDebugTicks = agentDebug.getSubcommands().get("ticks");
		agentDebugTicks.addSubcommand(new AgentDebugTicksStateCommand(context));
		agentDebugTicks.addSubcommand(new AgentDebugTicksPauseCommand(context));
		agentDebugTicks.addSubcommand(new AgentDebugTicksStepCommand(context));
		agentDebugTicks.addSubcommand(new AgentDebugTicksContinueCommand(context));
		agentDebug.addSubcommand("trace", new UsageCommand(out, "airicraft agent debug trace", "Continuous client tick trace commands"));
		CommandLine agentDebugTrace = agentDebug.getSubcommands().get("trace");
		agentDebugTrace.addSubcommand(new AgentDebugTraceStatusCommand(context));
		agentDebugTrace.addSubcommand(new AgentDebugTraceStartCommand(context));
		agentDebugTrace.addSubcommand(new AgentDebugTraceStopCommand(context));
		agentDebug.addSubcommand("world", new UsageCommand(out, "airicraft agent debug world", "Paused world snapshot queries"));
		CommandLine agentDebugWorld = agentDebug.getSubcommands().get("world");
		agentDebugWorld.addSubcommand(new AgentDebugWorldMetadataCommand(context));
		agentDebugWorld.addSubcommand(new AgentDebugWorldPlayerStateCommand(context));
		agentDebugWorld.addSubcommand(new AgentDebugWorldEntitiesCommand(context));
		agentDebugWorld.addSubcommand(new AgentDebugWorldGetBlockCommand(context));
		agentDebugWorld.addSubcommand(new AgentDebugWorldScanBoxCommand(context));
		agentDebugWorld.addSubcommand(new AgentDebugWorldFindBlocksCommand(context));
		agentDebugWorld.addSubcommand(new AgentDebugWorldRegionStatsCommand(context));
		agent.addSubcommand(new AgentContextCommand(context));
		agent.addSubcommand(new AgentCompactCommand(context));
		agent.addSubcommand(new AgentEventPolicyCommand(context));
		CommandLine agentEventPolicy = agent.getSubcommands().get("event-policy");
		agentEventPolicy.addSubcommand(new AgentEventPolicyShowCommand(context));
		agentEventPolicy.addSubcommand(new AgentEventPolicyClearCommand(context));
		agent.addSubcommand("events", new UsageCommand(out, "airicraft agent events", "Agent event stream commands"));
		CommandLine agentEvents = agent.getSubcommands().get("events");
		agentEvents.addSubcommand(new AgentEventsRecentCommand(context));

		root.addSubcommand("evaluation", new UsageCommand(out, "airicraft evaluation", "Scenario evaluation commands"));
		CommandLine evaluation = root.getSubcommands().get("evaluation");
		evaluation.addSubcommand(new EvaluationStatusCommand(context));
		evaluation.addSubcommand(new EvaluationScenariosCommand(context));
		evaluation.addSubcommand(new EvaluationConfigCommand(context));
		evaluation.addSubcommand(new EvaluationRunCommand(context));
		evaluation.addSubcommand(new EvaluationResultsCommand(context));
		evaluation.addSubcommand(new EvaluationEvidenceCommand(context));

		root.addSubcommand("worlds", new UsageCommand(out, "airicraft worlds", "Saved singleplayer worlds"));
		CommandLine worlds = root.getSubcommands().get("worlds");
		worlds.addSubcommand(new WorldsListCommand(context));
		worlds.addSubcommand(new WorldsJoinCommand(context));

		root.addSubcommand("servers", new UsageCommand(out, "airicraft servers", "Saved multiplayer servers"));
		CommandLine servers = root.getSubcommands().get("servers");
		servers.addSubcommand(new ServersListCommand(context));
		servers.addSubcommand(new ServersJoinCommand(context));

		root.addSubcommand("player", new UsageCommand(out, "airicraft player", "Player-oriented commands"));
		CommandLine player = root.getSubcommands().get("player");
		player.addSubcommand(new PlayerFocusCommand(context));
		player.addSubcommand(new PlayerNearbyEntitiesCommand(context));
		player.addSubcommand(new PlayerLookAtCommand(context));
		player.addSubcommand(new PlayerAttackEntityCommand(context));
		player.addSubcommand(new PlayerUseEntityCommand(context));

		root.addSubcommand("camera", new UsageCommand(out, "airicraft camera", "Camera capture commands"));
		CommandLine camera = root.getSubcommands().get("camera");
		camera.addSubcommand(new CameraScreenshotCommand(context));

		root.addSubcommand("vision", new UsageCommand(out, "airicraft vision", "Vision analysis commands"));
		CommandLine vision = root.getSubcommands().get("vision");
		vision.addSubcommand(new VisionDescribeCommand(context));

		root.addSubcommand("map", new UsageCommand(out, "airicraft map", "Map integration commands"));
		CommandLine map = root.getSubcommands().get("map");
		map.addSubcommand(new MapStatusCommand(context));
		map.addSubcommand("waypoints", new UsageCommand(out, "airicraft map waypoints", "Map waypoint commands"));
		CommandLine mapWaypoints = map.getSubcommands().get("waypoints");
		mapWaypoints.addSubcommand(new MapWaypointsListCommand(context));
		mapWaypoints.addSubcommand(new MapWaypointsSetCommand(context));
		mapWaypoints.addSubcommand(new MapWaypointsDeleteCommand(context));
		map.addSubcommand(new MapImageCommand(context));

		root.addSubcommand("world", new UsageCommand(out, "airicraft world", "World inspection and singleplayer settings"));
		CommandLine world = root.getSubcommands().get("world");
		world.addSubcommand(new WorldSnapshotCommand(context));
		world.addSubcommand(new WorldDifficultyCommand(context));

		root.addSubcommand("highlights", new UsageCommand(out, "airicraft highlights", "Highlight commands"));
		CommandLine highlights = root.getSubcommands().get("highlights");
		highlights.addSubcommand(new HighlightBlockCommand(context));
		highlights.addSubcommand(new HighlightRegionCommand(context));
		highlights.addSubcommand(new HighlightsListCommand(context));
		highlights.addSubcommand(new HighlightsClearCommand(context));
		highlights.addSubcommand(new HighlightsClearAllCommand(context));

		HelpCommand helpCommand = new HelpCommand(context);
		root.addSubcommand(helpCommand);
		context.setRootCommandLine(root);
		return root;
	}

	private static final class CliContext {
		private final MinecraftTransport transport;
		private final TextPrinter printer;
		private CommandLine rootCommandLine;

		private CliContext(MinecraftTransport transport, PrintWriter out) {
			this.transport = transport;
			this.printer = new TextPrinter(out);
		}

		private void setRootCommandLine(CommandLine rootCommandLine) {
			this.rootCommandLine = rootCommandLine;
		}
	}

	@Command(mixinStandardHelpOptions = true)
	private static final class UsageCommand implements Runnable {
		@Spec
		private CommandSpec spec;

		private final PrintWriter out;
		private final String commandName;
		private final String description;

		private UsageCommand(PrintWriter out, String commandName, String description) {
			this.out = out;
			this.commandName = commandName;
			this.description = description;
		}

		@Override
		public void run() {
			out.println(commandName);
			out.println(description);
			out.println();
			spec.commandLine().usage(out);
		}
	}

	private abstract static class BaseCommand implements Callable<Integer> {
		private final CliContext context;
		private final String commandPath;

		@Option(names = "--verbose", description = "Include additional detail where supported.")
		private boolean verbose;

		private BaseCommand(CliContext context, String commandPath) {
			this.context = context;
			this.commandPath = commandPath;
		}

		@Override
		public final Integer call() {
			Map<String, Object> payload = runCommand();
			context.printer.printSuccess(commandPath, payload);
			return 0;
		}

		abstract Map<String, Object> runCommand();

		final MinecraftTransport transport() {
			return context.transport;
		}

		final boolean verbose() {
			return verbose;
		}

		final String commandPath() {
			return commandPath;
		}
	}

	@Command(name = "status", mixinStandardHelpOptions = true, description = "Inspect bridge and session status.")
	private static final class StatusCommand extends BaseCommand {
		private StatusCommand(CliContext context) {
			super(context, "status");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.status(statusPayload(transport()), verbose());
		}
	}

	@Command(name = "reload", mixinStandardHelpOptions = true, description = "Reload runtime config without restarting Minecraft.")
	private static final class ReloadCommand extends BaseCommand {
		private ReloadCommand(CliContext context) {
			super(context, "reload");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.reload(transport().post("/v1/reload", null), verbose());
		}
	}

	@Command(name = "status", mixinStandardHelpOptions = true, description = "Inspect agent runtime status.")
	private static final class AgentStatusCommand extends BaseCommand {
		private AgentStatusCommand(CliContext context) {
			super(context, "agent status");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentStatus(transport().get("/v1/agent/status"), verbose());
		}
	}

	@Command(name = "list", mixinStandardHelpOptions = true, description = "List every planner tool available to an external Codex driver.")
	private static final class AgentToolsListCommand extends BaseCommand {
		private AgentToolsListCommand(CliContext context) {
			super(context, "agent tools list");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentTools(transport().get("/v1/agent/tools"), verbose());
		}
	}

	@Command(name = "call", mixinStandardHelpOptions = true, description = "Call any planner tool through the external Codex driver.")
	private static final class AgentToolsCallCommand extends BaseCommand {
		@Option(names = "--name", required = true, description = "Planner tool name.")
		private String name;

		@Option(names = "--arguments", defaultValue = "{}", description = "Tool arguments as a JSON object.")
		private String argumentsJson;

		@Option(names = "--timeout-seconds", defaultValue = "120", description = "Maximum seconds to wait for a tool result (1-300).")
		private int timeoutSeconds;

		@Option(names = "--output-image", description = "Write an image-producing tool result to this path.")
		private Path outputImage;

		private AgentToolsCallCommand(CliContext context) {
			super(context, "agent tools call");
		}

		@Override
		Map<String, Object> runCommand() {
			if (timeoutSeconds < 1 || timeoutSeconds > 300) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "timeout-seconds must be between 1 and 300");
			}
			Map<String, Object> arguments = parseJsonObject(argumentsJson, commandPath());
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("name", name);
			body.put("arguments", arguments);
			body.put("timeoutMs", timeoutSeconds * 1000);
			Map<String, Object> payload = new LinkedHashMap<>(transport().post("/v1/agent/tools", body));
			Object encodedImage = payload.remove("imageBase64");
			if (encodedImage instanceof String imageBase64 && !imageBase64.isBlank() && outputImage != null) {
				try {
					Path outputPath = outputImage.toAbsolutePath().normalize();
					writeCapture(outputPath, java.util.Base64.getDecoder().decode(imageBase64));
					payload.put("imageOutputPath", outputPath.toString());
				}
				catch (IllegalArgumentException exception) {
					throw new CliUsageException(commandPath(), "bridge_io_error", "Bridge returned invalid image data");
				}
			}
			return PayloadViews.agentToolResult(payload, verbose());
		}
	}

	@Command(name = "session", mixinStandardHelpOptions = true, description = "Inspect agent session state.")
	private static final class AgentSessionCommand extends BaseCommand {
		private AgentSessionCommand(CliContext context) {
			super(context, "agent session");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentSession(transport().get("/v1/agent/session"), verbose());
		}
	}

	@Command(name = "open-lan", mixinStandardHelpOptions = true, description = "Open the current singleplayer session to LAN.")
	private static final class AgentSessionOpenLanCommand extends BaseCommand {
		private AgentSessionOpenLanCommand(CliContext context) {
			super(context, "agent session open-lan");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().post("/v1/agent/session/open-lan", null);
		}
	}

	@Command(name = "goals", mixinStandardHelpOptions = true, description = "Inspect active agent goals.")
	private static final class AgentGoalsCommand extends BaseCommand {
		private AgentGoalsCommand(CliContext context) {
			super(context, "agent goals");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentGoals(transport().get("/v1/agent/goals"), verbose());
		}
	}

	@Command(name = "tasks", mixinStandardHelpOptions = true, description = "Inspect active agent task state.")
	private static final class AgentTasksCommand extends BaseCommand {
		private AgentTasksCommand(CliContext context) {
			super(context, "agent tasks");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentTasks(transport().get("/v1/agent/tasks"), verbose());
		}
	}

	@Command(name = "ledger", mixinStandardHelpOptions = true, description = "Inspect the active planner-owned mission ledger.")
	private static final class AgentLedgerCommand extends BaseCommand {
		private AgentLedgerCommand(CliContext context) {
			super(context, "agent ledger");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentLedger(transport().get("/v1/agent/ledger"), verbose());
		}
	}

	@Command(name = "evidence", mixinStandardHelpOptions = true, description = "Inspect the latest runtime-owned world evidence.")
	private static final class AgentEvidenceCommand extends BaseCommand {
		private AgentEvidenceCommand(CliContext context) {
			super(context, "agent evidence");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentEvidence(transport().get("/v1/agent/evidence"), verbose());
		}
	}

	@Command(name = "step-execution", mixinStandardHelpOptions = true, description = "Inspect the latest semantic step execution result and primitive execution state.")
	private static final class AgentStepExecutionCommand extends BaseCommand {
		private AgentStepExecutionCommand(CliContext context) {
			super(context, "agent step-execution");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentStepExecution(transport().get("/v1/agent/step-execution"), verbose());
		}
	}

	@Command(name = "inspect", mixinStandardHelpOptions = true, description = "Inspect the composable action graph surface.")
	private static final class AgentActionsInspectCommand extends BaseCommand {
		private AgentActionsInspectCommand(CliContext context) {
			super(context, "agent actions inspect");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentActionInspect(transport().get("/v1/agent/action-graph/inspect"), verbose());
		}
	}

	@Command(name = "inspect", mixinStandardHelpOptions = true, description = "Inspect the active action graph goal.")
	private static final class AgentActionsGoalInspectCommand extends BaseCommand {
		@Option(names = "--execution-id", description = "Specific action graph execution id.")
		private String executionId;

		private AgentActionsGoalInspectCommand(CliContext context) {
			super(context, "agent actions goal inspect");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentActionGoal(transport().get(actionGoalPath(executionId, false)), verbose());
		}
	}

	@Command(name = "list", mixinStandardHelpOptions = true, description = "List action graph goals in all scheduler residencies.")
	private static final class AgentActionsGoalListCommand extends BaseCommand {
		private AgentActionsGoalListCommand(CliContext context) {
			super(context, "agent actions goal list");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentActionGoals(transport().get(actionGoalPath(null, true)), verbose());
		}
	}

	@Command(name = "start", mixinStandardHelpOptions = true, description = "Start an action graph goal.")
	private static final class AgentActionsGoalStartCommand extends BaseCommand {
		@Option(names = "--kind", description = "Goal kind. v1 supports inventory_item and resource_collection.")
		private String kind = "inventory_item";

		@Option(names = "--item-id", description = "Inventory item id to obtain.")
		private String itemId;

		@Option(names = "--resource-kind", description = "Catalog resource kind to gather for resource_collection goals.")
		private String resourceKind;

		@Option(names = "--quantity", description = "Minimum item count.")
		private int quantity = 1;

		private AgentActionsGoalStartCommand(CliContext context) {
			super(context, "agent actions goal start");
		}

		@Override
		Map<String, Object> runCommand() {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("kind", kind);
			if (itemId != null && !itemId.isBlank()) {
				payload.put("itemId", itemId);
			}
			if (resourceKind != null && !resourceKind.isBlank()) {
				payload.put("resourceKind", resourceKind);
			}
			payload.put("quantity", quantity);
			return PayloadViews.agentActionGoal(transport().post("/v1/agent/action-goals", payload), verbose());
		}
	}

	@Command(name = "cancel", mixinStandardHelpOptions = true, description = "Cancel the active action graph goal.")
	private static final class AgentActionsGoalCancelCommand extends BaseCommand {
		@Option(names = "--execution-id", description = "Specific action graph execution id.")
		private String executionId;

		private AgentActionsGoalCancelCommand(CliContext context) {
			super(context, "agent actions goal cancel");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentActionGoal(transport().delete(actionGoalPath(executionId, false), Map.of()), verbose());
		}
	}

	@Command(name = "list", mixinStandardHelpOptions = true, description = "List active action graph watches.")
	private static final class AgentActionsWatchesListCommand extends BaseCommand {
		private AgentActionsWatchesListCommand(CliContext context) {
			super(context, "agent actions watches list");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentActionWatches(transport().get(actionGoalPath(null, true)), verbose());
		}
	}

	@Command(name = "submit", mixinStandardHelpOptions = true, description = "Submit a high-level agent task.")
	private static final class AgentTasksSubmitCommand extends BaseCommand {
		@Option(names = "--type", required = true, description = "Task type, for example collect-resource.")
		private String type;

		@Option(names = "--resource", required = true, description = "Task resource kind, for example wood-logs.")
		private String resource;

		@Option(names = "--quantity", required = true, description = "Requested quantity.")
		private int quantity;

		private AgentTasksSubmitCommand(CliContext context) {
			super(context, "agent tasks submit");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentTasks(transport().post("/v1/agent/tasks", Map.of(
				"type", PayloadViews.normalizeTaskValue(type),
				"resourceKind", PayloadViews.normalizeTaskValue(resource),
				"quantity", quantity
			)), verbose());
		}
	}

	@Command(name = "submit", mixinStandardHelpOptions = true, description = "Submit a mission-level request and let the runtime seed a ledger.")
	private static final class AgentMissionSubmitCommand extends BaseCommand {
		@Option(names = "--type", description = "Mission type, for example collect-resource.")
		private String type;

		@Option(names = "--resource", description = "Mission resource kind, for example wood-logs.")
		private String resource;

		@Option(names = "--quantity", description = "Requested quantity.")
		private int quantity;

		@Option(names = "--ledger-file", description = "Path to a JSON file containing a full TaskLedger payload.")
		private Path ledgerFile;

		private AgentMissionSubmitCommand(CliContext context) {
			super(context, "agent mission submit");
		}

		@Override
		Map<String, Object> runCommand() {
			if (ledgerFile != null) {
				if (type != null || resource != null || quantity > 0) {
					throw new CliUsageException(commandPath(), "invalid_arguments", "--ledger-file cannot be combined with --type/--resource/--quantity");
				}
				return PayloadViews.agentTasks(transport().post("/v1/agent/tasks", readLedgerPayload(ledgerFile)), verbose());
			}
			if (type == null || resource == null || quantity <= 0) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "Either provide --ledger-file or all of --type, --resource, and --quantity");
			}
			return PayloadViews.agentTasks(transport().post("/v1/agent/tasks", Map.of(
				"type", PayloadViews.normalizeTaskValue(type),
				"resourceKind", PayloadViews.normalizeTaskValue(resource),
				"quantity", quantity
			)), verbose());
		}

		private Map<String, Object> readLedgerPayload(Path ledgerFile) {
			try {
				return OBJECT_MAPPER.readValue(Files.newBufferedReader(ledgerFile, StandardCharsets.UTF_8), MAP_TYPE);
			}
			catch (java.io.IOException exception) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "Failed to read ledger file: " + ledgerFile);
			}
		}
	}

	@Command(name = "cancel", mixinStandardHelpOptions = true, description = "Cancel the active high-level task.")
	private static final class AgentTasksCancelCommand extends BaseCommand {
		private AgentTasksCancelCommand(CliContext context) {
			super(context, "agent tasks cancel");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentTasks(transport().delete("/v1/agent/tasks", null), verbose());
		}
	}

	@Command(name = "resume", mixinStandardHelpOptions = true, description = "Resume the exact task paused by a resolved survival reflex.")
	private static final class AgentTasksResumeCommand extends BaseCommand {
		@Option(names = "--hold-id", required = true, description = "Exact safety hold id from agent status.")
		private String holdId;

		private AgentTasksResumeCommand(CliContext context) {
			super(context, "agent tasks resume");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentTasks(transport().post("/v1/agent/tasks/resume", Map.of("holdId", holdId)), verbose());
		}
	}

	@Command(name = "tree", mixinStandardHelpOptions = true, description = "Inspect the behavior tree snapshot.")
	private static final class AgentTreeCommand extends BaseCommand {
		private AgentTreeCommand(CliContext context) {
			super(context, "agent tree");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().get("/v1/agent/tree");
		}
	}

	@Command(name = "dialogue", mixinStandardHelpOptions = true, description = "Inspect dialogue state and recent turns.")
	private static final class AgentDialogueCommand extends BaseCommand {
		private AgentDialogueCommand(CliContext context) {
			super(context, "agent dialogue");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentDialogue(transport().get("/v1/agent/dialogue"), verbose());
		}
	}

	@Command(name = "chat", mixinStandardHelpOptions = true, description = "Inject a local-controller chat message into the agent runtime.")
	private static final class AgentDebugChatCommand extends BaseCommand {
		@Option(names = "--message", required = true, description = "Chat message to inject, for example '@agent get me 4 wood logs'.")
		private String message;

		private AgentDebugChatCommand(CliContext context) {
			super(context, "agent debug chat");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().post("/v1/agent/debug/chat", Map.of("message", message));
		}
	}

	@Command(name = "idle-trigger", mixinStandardHelpOptions = true, description = "Manually fire the idle-think planner trigger.")
	private static final class AgentDebugIdleTriggerCommand extends BaseCommand {
		private AgentDebugIdleTriggerCommand(CliContext context) {
			super(context, "agent debug idle-trigger");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentDebugIdleTrigger(transport().post("/v1/agent/debug/idle-trigger", null), verbose());
		}
	}

	@Command(name = "state", mixinStandardHelpOptions = true, description = "Inspect correlated agent debug state across planner, dialogue, chat, and task progress.")
	private static final class AgentDebugStateCommand extends BaseCommand {
		private AgentDebugStateCommand(CliContext context) {
			super(context, "agent debug state");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentDebugState(transport().get("/v1/agent/debug/state"), verbose());
		}
	}

	@Command(name = "timeline", mixinStandardHelpOptions = true, description = "Inspect the recent agent debug timeline.")
	private static final class AgentDebugTimelineCommand extends BaseCommand {
		@Option(names = "--since", description = "Only return timeline entries with an entryId greater than this value.")
		private Long since;

		private AgentDebugTimelineCommand(CliContext context) {
			super(context, "agent debug timeline");
		}

		@Override
		Map<String, Object> runCommand() {
			String path = since == null ? "/v1/agent/debug/timeline" : "/v1/agent/debug/timeline?since=" + since;
			return PayloadViews.agentDebugTimeline(transport().get(path), verbose());
		}
	}

	@Command(name = "status", mixinStandardHelpOptions = true, description = "Inspect the rolling recording window, clock and evidence loss.")
	private static final class AgentDebugRecordingStatusCommand extends BaseCommand {
		private AgentDebugRecordingStatusCommand(CliContext context) { super(context, "agent debug recording status"); }
		@Override Map<String, Object> runCommand() { return transport().get("/v1/agent/debug/recording"); }
	}

	@Command(name = "query", mixinStandardHelpOptions = true, description = "Read a bounded page of structured evidence without image bytes.")
	private static class AgentDebugRecordingQueryCommand extends BaseCommand {
		@Option(names = "--from-server-tick", description = "First completed server tick, inclusive. Defaults to the window start.")
		Long fromTick;
		@Option(names = "--to-server-tick", description = "Last completed server tick, inclusive. Defaults to the latest tick.")
		Long toTick;
		@Option(names = "--since", description = "Observation sequence cursor returned by the previous query.")
		long since;
		@Option(names = "--limit", description = "Maximum observations per page (1-1000).")
		int limit = 100;
		@Option(names = "--type", split = ",", description = "Observation types, comma separated or repeated.")
		List<String> types = new ArrayList<>();

		private AgentDebugRecordingQueryCommand(CliContext context) { this(context, "agent debug recording query"); }
		AgentDebugRecordingQueryCommand(CliContext context, String command) { super(context, command); }
		String queryPath() {
			if (since < 0 || limit < 1 || limit > 1000 || (fromTick != null && fromTick < 0) || (toTick != null && toTick < 0)
				|| (fromTick != null && toTick != null && fromTick > toTick)) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "Use nonnegative ticks/cursor, from <= to, and limit 1-1000");
			}
			return "/v1/agent/debug/recording?mode=query&since=" + since + "&limit=" + limit
				+ (fromTick == null ? "" : "&from=" + fromTick) + (toTick == null ? "" : "&to=" + toTick)
				+ (types.isEmpty() ? "" : "&types=" + URLEncoder.encode(String.join(",", types), StandardCharsets.UTF_8));
		}
		@Override Map<String, Object> runCommand() { return transport().get(queryPath()); }
	}

	@Command(name = "export", mixinStandardHelpOptions = true, description = "Save the retained interval as replayable JSONL. Pause server ticks first for a stable export.")
	private static final class AgentDebugRecordingExportCommand extends AgentDebugRecordingQueryCommand {
		@Option(names = "--output", required = true, description = "JSONL file to create.")
		Path output;
		@Option(names = "--frames", description = "Include sparse RGB images in the export.")
		boolean frames;
		private AgentDebugRecordingExportCommand(CliContext context) { super(context, "agent debug recording export"); }
		@Override Map<String, Object> runCommand() {
			try {
				return new LiveRecordingExporter(transport(), OBJECT_MAPPER).export(output, queryPath(), frames);
			}
			catch (java.io.IOException exception) { throw new UncheckedIOException(exception); }
		}
	}

	@Command(name = "frame", mixinStandardHelpOptions = true, description = "Fetch one retained RGB frame by observation sequence.")
	private static final class AgentDebugRecordingFrameCommand extends BaseCommand {
		@Option(names = "--sequence", required = true, description = "visual_frame observation sequence from query.")
		long sequence;
		@Option(names = "--output", required = true, description = "Path to write the JPEG frame.")
		Path output;
		private AgentDebugRecordingFrameCommand(CliContext context) { super(context, "agent debug recording frame"); }
		@Override Map<String, Object> runCommand() {
			Map<String, Object> observation = transport().get("/v1/agent/debug/recording?frame=" + sequence);
			Map<String, Object> payload = OBJECT_MAPPER.convertValue(observation.get("payload"), MAP_TYPE);
			String encoded = String.valueOf(payload.remove("imageBase64"));
			Path path = output.toAbsolutePath().normalize();
			writeCapture(path, java.util.Base64.getDecoder().decode(encoded));
			observation.put("payload", payload);
			observation.put("outputPath", path.toString());
			return observation;
		}
	}

	@Command(name = "state", mixinStandardHelpOptions = true, description = "Inspect the client tick debug state.")
	private static final class AgentDebugTicksStateCommand extends BaseCommand {
		private AgentDebugTicksStateCommand(CliContext context) {
			super(context, "agent debug ticks state");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.clientTickDebugStatus(transport().get("/v1/agent/debug/ticks/state"));
		}
	}

	@Command(name = "pause", mixinStandardHelpOptions = true, description = "Pause client ticks and capture the current client frame.")
	private static final class AgentDebugTicksPauseCommand extends BaseCommand {
		@Option(names = "--output-image", description = "Write the captured client frame to this path.")
		private Path outputImage;

		@Option(names = "--player-actions", description = "Capture player actions and block break progress in this pause session.")
		private boolean playerActions;

		private AgentDebugTicksPauseCommand(CliContext context) {
			super(context, "agent debug ticks pause");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.clientTickDebugCapture(
				prepareClientTickCapture(transport().post("/v1/agent/debug/ticks/pause", Map.of("playerActions", playerActions)), outputImage, commandPath()),
				verbose()
			);
		}
	}

	@Command(name = "step", mixinStandardHelpOptions = true, description = "Run one client tick and capture its first rendered frame.")
	private static final class AgentDebugTicksStepCommand extends BaseCommand {
		@Option(names = "--debug-session-id", required = true, description = "Debug session ID from the last pause or step response.")
		private String debugSessionId;

		@Option(names = "--pause-epoch", required = true, description = "Pause epoch from the last pause or step response.")
		private long pauseEpoch;

		@Option(names = "--output-image", description = "Write the captured client frame to this path.")
		private Path outputImage;

		private AgentDebugTicksStepCommand(CliContext context) {
			super(context, "agent debug ticks step");
		}

		@Override
		Map<String, Object> runCommand() {
			requirePositivePauseEpoch(pauseEpoch, commandPath());
			return PayloadViews.clientTickDebugCapture(
				prepareClientTickCapture(
					transport().post("/v1/agent/debug/ticks/step", Map.of(
						"debugSessionId", debugSessionId,
						"pauseEpoch", pauseEpoch
					)),
					outputImage,
					commandPath()
				),
				verbose()
			);
		}
	}

	@Command(name = "continue", mixinStandardHelpOptions = true, description = "Continue normal client ticks.")
	private static final class AgentDebugTicksContinueCommand extends BaseCommand {
		@Option(names = "--debug-session-id", required = true, description = "Debug session ID from the last pause or step response.")
		private String debugSessionId;

		@Option(names = "--pause-epoch", required = true, description = "Pause epoch from the last pause or step response.")
		private long pauseEpoch;

		private AgentDebugTicksContinueCommand(CliContext context) {
			super(context, "agent debug ticks continue");
		}

		@Override
		Map<String, Object> runCommand() {
			requirePositivePauseEpoch(pauseEpoch, commandPath());
			return PayloadViews.clientTickDebugStatus(
				transport().post("/v1/agent/debug/ticks/continue", Map.of(
					"debugSessionId", debugSessionId,
					"pauseEpoch", pauseEpoch
				))
			);
		}
	}

	@Command(name = "status", mixinStandardHelpOptions = true, description = "Inspect the continuous client tick trace state.")
	private static final class AgentDebugTraceStatusCommand extends BaseCommand {
		private AgentDebugTraceStatusCommand(CliContext context) {
			super(context, "agent debug trace status");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().get("/v1/agent/debug/trace");
		}
	}

	@Command(name = "start", mixinStandardHelpOptions = true, description = "Start a client tick trace and stream it to a JSON Lines file.")
	private static final class AgentDebugTraceStartCommand implements Callable<Integer> {
		private static final List<String> SUPPORTED_INFOS = List.of(
			"metadata", "player_state", "player_actions", "entities", "blocks", "frame"
		);
		private static final String COMMAND_PATH = "agent debug trace start";

		private final CliContext context;

		@Option(
			names = "--info",
			required = true,
			split = ",",
			description = "Trace info names: metadata, player-state, player-actions, entities, blocks, or frame. Repeat or use commas."
		)
		private List<String> infos;

		@Option(names = "--window-ticks", required = true, description = "Trace span from 1 to 1200 client ticks. Frame traces allow at most 200 ticks.")
		private int windowTicks;

		@Option(names = "--once", description = "Stop automatically after window-ticks records.")
		private boolean once;

		@Option(names = "--output", required = true, description = "Path to write the JSON Lines trace file.")
		private Path output;

		@Option(names = "--verbose", description = "Include additional detail where supported.")
		private boolean verbose;

		@Option(names = "--entity-query", description = "Entity query JSON. An omitted radius center follows the captured player each tick.")
		private String entityQueryJson;

		@Option(names = "--block-query", description = "Block region JSON with minX, minY, minZ, maxX, maxY, and maxZ.")
		private String blockQueryJson;

		private AgentDebugTraceStartCommand(CliContext context) {
			this.context = context;
		}

		@Override
		public Integer call() {
			List<String> normalizedInfos = normalizedInfos();
			if (windowTicks < 1 || windowTicks > 1_200) {
				throw new CliUsageException(COMMAND_PATH, "invalid_arguments", "window-ticks must be between 1 and 1200");
			}
			if (normalizedInfos.contains("frame") && windowTicks > 200) {
				throw new CliUsageException(COMMAND_PATH, "invalid_arguments", "window-ticks must not exceed 200 with frame tracing");
			}
			boolean hasEntityQuery = entityQueryJson != null && !entityQueryJson.isBlank();
			boolean hasBlockQuery = blockQueryJson != null && !blockQueryJson.isBlank();
			if (hasEntityQuery && !normalizedInfos.contains("entities")) {
				throw new CliUsageException(COMMAND_PATH, "invalid_arguments", "entity-query requires the entities trace info");
			}
			if (normalizedInfos.contains("blocks") != hasBlockQuery) {
				throw new CliUsageException(COMMAND_PATH, "invalid_arguments", "block-query is required only with the blocks trace info");
			}
			Map<String, Object> request = new LinkedHashMap<>();
			request.put("infos", normalizedInfos);
			request.put("windowTicks", windowTicks);
			request.put("once", once);
			if (hasEntityQuery) {
				request.put("entityQuery", parseJsonObject(entityQueryJson, COMMAND_PATH));
			}
			if (hasBlockQuery) {
				request.put("blockQuery", parseJsonObject(blockQueryJson, COMMAND_PATH));
			}
			Map<String, Object> startPayload = context.transport.post("/v1/agent/debug/trace/start", request);
			Path outputPath = output.toAbsolutePath().normalize();
			LinkedHashMap<String, Object> response = new LinkedHashMap<>(startPayload);
			response.put("outputPath", outputPath.toString());
			response.put("streaming", true);
			try {
				new ClientTickTraceJsonlStreamer(context.transport, OBJECT_MAPPER).stream(
					outputPath,
					startPayload,
					once,
					() -> context.printer.printSuccess(COMMAND_PATH, response)
				);
				return 0;
			}
			catch (java.io.IOException exception) {
				throw new UncheckedIOException(exception);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new BridgeUnavailableException("trace_stream_interrupted", "The client tick trace stream was interrupted");
			}
		}

		private List<String> normalizedInfos() {
			List<String> normalized = new ArrayList<>();
			for (String info : infos) {
				String value = info == null
					? ""
					: info.trim().toLowerCase(Locale.ROOT).replace('-', '_');
				if (!SUPPORTED_INFOS.contains(value)) {
					throw new CliUsageException(COMMAND_PATH, "invalid_arguments", "Unsupported trace info: " + info);
				}
				if (!normalized.contains(value)) {
					normalized.add(value);
				}
			}
			return List.copyOf(normalized);
		}
	}

	@Command(name = "stop", mixinStandardHelpOptions = true, description = "Stop a client tick trace. The stream writes its final records before it exits.")
	private static final class AgentDebugTraceStopCommand extends BaseCommand {
		@Option(names = "--trace-id", required = true, description = "Trace ID from trace start or status.")
		private String traceId;

		private AgentDebugTraceStopCommand(CliContext context) {
			super(context, "agent debug trace stop");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().post("/v1/agent/debug/trace/stop", Map.of("traceId", traceId));
		}
	}

	private abstract static class AgentDebugWorldSnapshotCommand extends BaseCommand {
		@Option(names = "--snapshot-id", required = true, description = "World snapshot ID from the last pause or step response.")
		private String snapshotId;

		private AgentDebugWorldSnapshotCommand(CliContext context, String commandPath) {
			super(context, commandPath);
		}

		final Map<String, Object> baseRequest(String operation) {
			Map<String, Object> request = new LinkedHashMap<>();
			request.put("snapshotId", snapshotId);
			request.put("operation", operation);
			return request;
		}
	}

	@Command(name = "metadata", mixinStandardHelpOptions = true, description = "Get metadata for a paused world snapshot.")
	private static final class AgentDebugWorldMetadataCommand extends AgentDebugWorldSnapshotCommand {
		private AgentDebugWorldMetadataCommand(CliContext context) {
			super(context, "agent debug world metadata");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().post("/v1/agent/debug/world/query", baseRequest("metadata"));
		}
	}

	@Command(name = "player-state", mixinStandardHelpOptions = true, description = "Get the full player state captured at a paused client tick.")
	private static final class AgentDebugWorldPlayerStateCommand extends AgentDebugWorldSnapshotCommand {
		private AgentDebugWorldPlayerStateCommand(CliContext context) {
			super(context, "agent debug world player-state");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().post("/v1/agent/debug/world/query", baseRequest("player_state"));
		}
	}

	@Command(name = "entities", mixinStandardHelpOptions = true, description = "Query loaded entities by region, radius, identity, name, type, and state.")
	private static final class AgentDebugWorldEntitiesCommand extends AgentDebugWorldSnapshotCommand {
		@Option(names = "--min-x", description = "Inclusive minimum block X for a region query.")
		private Integer minX;

		@Option(names = "--min-y", description = "Inclusive minimum block Y for a region query.")
		private Integer minY;

		@Option(names = "--min-z", description = "Inclusive minimum block Z for a region query.")
		private Integer minZ;

		@Option(names = "--max-x", description = "Inclusive maximum block X for a region query.")
		private Integer maxX;

		@Option(names = "--max-y", description = "Inclusive maximum block Y for a region query.")
		private Integer maxY;

		@Option(names = "--max-z", description = "Inclusive maximum block Z for a region query.")
		private Integer maxZ;

		@Option(names = "--center-x", description = "Radius center X. Omit all center coordinates to use the captured player position.")
		private Double centerX;

		@Option(names = "--center-y", description = "Radius center Y. Omit all center coordinates to use the captured player position.")
		private Double centerY;

		@Option(names = "--center-z", description = "Radius center Z. Omit all center coordinates to use the captured player position.")
		private Double centerZ;

		@Option(names = "--radius", description = "Three-dimensional radius from 0 to 4096 blocks.")
		private Double radius;

		@Option(names = "--entity-id", description = "Exact runtime entity ID.")
		private Integer entityId;

		@Option(names = "--uuid", description = "Exact entity UUID.")
		private String uuid;

		@Option(names = "--name", description = "Exact visible entity name. Matching ignores letter case.")
		private String name;

		@Option(names = {"--entity-type-id", "--type"}, split = ",", description = "Exact namespaced entity type ID. Repeat this option or use commas.")
		private List<String> entityTypeIds;

		@Option(names = "--alive", arity = "0..1", fallbackValue = "true", description = "Filter by entity alive state. Use --living-only to select living entities.")
		private Boolean alive;

		@Option(names = "--living-only", description = "Only return living entities.")
		private boolean livingOnly;

		@Option(names = "--player-only", description = "Only return player entities.")
		private boolean playerOnly;

		@Option(names = "--include-self", description = "Include the local player in results.")
		private boolean includeSelf;

		@Option(names = "--cursor", defaultValue = "0", description = "Zero-based cursor in the filtered entity result.")
		private long cursor;

		@Option(names = "--limit", defaultValue = "32", description = "Maximum entities to return, from 1 to 256.")
		private int limit;

		private AgentDebugWorldEntitiesCommand(CliContext context) {
			super(context, "agent debug world entities");
		}

		@Override
		Map<String, Object> runCommand() {
			validateSpatialSelector();
			if (cursor < 0L) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "cursor must not be negative");
			}
			if (limit < 1 || limit > 256) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "limit must be between 1 and 256");
			}
			if (radius != null && (!Double.isFinite(radius) || radius < 0.0D || radius > 4_096.0D)) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "radius must be between 0 and 4096");
			}
			Map<String, Object> request = baseRequest("entities");
			putIfNonNull(request, "minX", minX);
			putIfNonNull(request, "minY", minY);
			putIfNonNull(request, "minZ", minZ);
			putIfNonNull(request, "maxX", maxX);
			putIfNonNull(request, "maxY", maxY);
			putIfNonNull(request, "maxZ", maxZ);
			putIfNonNull(request, "centerX", centerX);
			putIfNonNull(request, "centerY", centerY);
			putIfNonNull(request, "centerZ", centerZ);
			putIfNonNull(request, "radius", radius);
			putIfNonNull(request, "entityId", entityId);
			putIfPresent(request, "uuid", uuid);
			putIfPresent(request, "name", name);
			if (entityTypeIds != null && !entityTypeIds.isEmpty()) {
				request.put("entityTypeIds", entityTypeIds);
			}
			putIfNonNull(request, "alive", alive);
			request.put("livingOnly", livingOnly);
			request.put("playerOnly", playerOnly);
			request.put("includeSelf", includeSelf);
			request.put("cursor", cursor);
			request.put("limit", limit);
			return transport().post("/v1/agent/debug/world/query", request);
		}

		private void validateSpatialSelector() {
			int regionCount = countNonNull(minX, minY, minZ, maxX, maxY, maxZ);
			if (regionCount != 0 && regionCount != 6) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "Provide all six region coordinates");
			}
			int centerCount = countNonNull(centerX, centerY, centerZ);
			if (centerCount != 0 && centerCount != 3) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "Provide all three radius center coordinates");
			}
			if (centerCount > 0 && radius == null) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "Provide --radius with center coordinates");
			}
			if (regionCount > 0 && radius != null) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "Use either region or radius, not both");
			}
		}
	}

	@Command(name = "get-block", mixinStandardHelpOptions = true, description = "Get one block from a paused world snapshot.")
	private static final class AgentDebugWorldGetBlockCommand extends AgentDebugWorldSnapshotCommand {
		@Option(names = "--x", required = true)
		private int x;

		@Option(names = "--y", required = true)
		private int y;

		@Option(names = "--z", required = true)
		private int z;

		private AgentDebugWorldGetBlockCommand(CliContext context) {
			super(context, "agent debug world get-block");
		}

		@Override
		Map<String, Object> runCommand() {
			Map<String, Object> request = baseRequest("get_block");
			request.put("x", x);
			request.put("y", y);
			request.put("z", z);
			return transport().post("/v1/agent/debug/world/query", request);
		}
	}

	private abstract static class AgentDebugWorldRegionCommand extends AgentDebugWorldSnapshotCommand {
		@Option(names = "--min-x", required = true)
		private int minX;

		@Option(names = "--min-y", required = true)
		private int minY;

		@Option(names = "--min-z", required = true)
		private int minZ;

		@Option(names = "--max-x", required = true)
		private int maxX;

		@Option(names = "--max-y", required = true)
		private int maxY;

		@Option(names = "--max-z", required = true)
		private int maxZ;

		@Option(names = "--cursor", defaultValue = "0", description = "Zero-based region cursor.")
		private long cursor;

		@Option(names = "--limit", defaultValue = "256", description = "Maximum cells to inspect, from 1 to 4096.")
		private int limit;

		private AgentDebugWorldRegionCommand(CliContext context, String commandPath) {
			super(context, commandPath);
		}

		final Map<String, Object> regionRequest(String operation) {
			if (cursor < 0L) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "cursor must not be negative");
			}
			if (limit < 1 || limit > 4_096) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "limit must be between 1 and 4096");
			}
			Map<String, Object> request = baseRequest(operation);
			request.put("minX", minX);
			request.put("minY", minY);
			request.put("minZ", minZ);
			request.put("maxX", maxX);
			request.put("maxY", maxY);
			request.put("maxZ", maxZ);
			request.put("cursor", cursor);
			request.put("limit", limit);
			return request;
		}
	}

	@Command(name = "scan-box", mixinStandardHelpOptions = true, description = "Scan a bounded page from an arbitrary world region.")
	private static final class AgentDebugWorldScanBoxCommand extends AgentDebugWorldRegionCommand {
		private AgentDebugWorldScanBoxCommand(CliContext context) {
			super(context, "agent debug world scan-box");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().post("/v1/agent/debug/world/query", regionRequest("scan_box"));
		}
	}

	@Command(name = "find-blocks", mixinStandardHelpOptions = true, description = "Find selected block IDs in an arbitrary world region.")
	private static final class AgentDebugWorldFindBlocksCommand extends AgentDebugWorldRegionCommand {
		@Option(names = "--block-id", required = true, split = ",", description = "Block ID. Repeat this option or use commas.")
		private List<String> blockIds;

		private AgentDebugWorldFindBlocksCommand(CliContext context) {
			super(context, "agent debug world find-blocks");
		}

		@Override
		Map<String, Object> runCommand() {
			Map<String, Object> request = regionRequest("find_blocks");
			request.put("blockIds", blockIds);
			return transport().post("/v1/agent/debug/world/query", request);
		}
	}

	@Command(name = "region-stats", mixinStandardHelpOptions = true, description = "Count block IDs in a bounded page from an arbitrary world region.")
	private static final class AgentDebugWorldRegionStatsCommand extends AgentDebugWorldRegionCommand {
		private AgentDebugWorldRegionStatsCommand(CliContext context) {
			super(context, "agent debug world region-stats");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().post("/v1/agent/debug/world/query", regionRequest("region_stats"));
		}
	}

	@Command(name = "context", mixinStandardHelpOptions = true, description = "Inspect planner context aggregation and compaction state.")
	private static final class AgentContextCommand extends BaseCommand {
		private AgentContextCommand(CliContext context) {
			super(context, "agent context");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentContext(transport().get("/v1/agent/context"), verbose());
		}
	}

	@Command(name = "recent", mixinStandardHelpOptions = true, description = "List recent agent events.")
	private static final class AgentEventsRecentCommand extends BaseCommand {
		@Option(names = "--since", description = "Only return events with seqNo greater than this value.")
		private Long since;

		private AgentEventsRecentCommand(CliContext context) {
			super(context, "agent events recent");
		}

		@Override
		Map<String, Object> runCommand() {
			String path = since == null ? "/v1/agent/events/recent" : "/v1/agent/events/recent?since=" + since;
			return PayloadViews.agentEvents(transport().get(path), verbose());
		}
	}

	@Command(name = "compact", mixinStandardHelpOptions = true, description = "Trigger a planner context compaction for debugging.")
	private static final class AgentCompactCommand extends BaseCommand {
		@Option(names = "--no-wait", description = "Return immediately after starting compaction.")
		private boolean noWait;

		@Option(names = "--timeout-seconds", description = "Wait timeout in seconds when compaction is synchronous.")
		private Integer timeoutSeconds;

		private AgentCompactCommand(CliContext context) {
			super(context, "agent compact");
		}

		@Override
		Map<String, Object> runCommand() {
			if (timeoutSeconds != null && timeoutSeconds < 1) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "timeout-seconds must be positive");
			}
			if (timeoutSeconds != null && timeoutSeconds > Integer.MAX_VALUE / 1000) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "timeout-seconds is too large");
			}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("wait", !noWait);
			if (timeoutSeconds != null) {
				body.put("timeoutMs", timeoutSeconds * 1000);
			}
			return PayloadViews.agentCompact(transport().post("/v1/agent/debug/compact", body), verbose());
		}
	}

	@Command(name = "event-policy", mixinStandardHelpOptions = true, description = "Inspect active event-policy rules and recent interventions.")
	private static final class AgentEventPolicyCommand extends BaseCommand {
		private AgentEventPolicyCommand(CliContext context) {
			super(context, "agent event-policy");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentEventPolicy(transport().get("/v1/agent/event-policy"), verbose());
		}
	}

	@Command(name = "show", mixinStandardHelpOptions = true, description = "Inspect active event-policy rules and recent interventions.")
	private static final class AgentEventPolicyShowCommand extends BaseCommand {
		private AgentEventPolicyShowCommand(CliContext context) {
			super(context, "agent event-policy show");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentEventPolicy(transport().get("/v1/agent/event-policy"), verbose());
		}
	}

	@Command(name = "clear", mixinStandardHelpOptions = true, description = "Clear all session-scoped event-policy rules.")
	private static final class AgentEventPolicyClearCommand extends BaseCommand {
		private AgentEventPolicyClearCommand(CliContext context) {
			super(context, "agent event-policy clear");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentEventPolicy(transport().post("/v1/agent/event-policy/clear", null), verbose());
		}
	}

	@Command(name = "status", mixinStandardHelpOptions = true, description = "Inspect scenario evaluation availability.")
	private static final class EvaluationStatusCommand extends BaseCommand {
		private EvaluationStatusCommand(CliContext context) {
			super(context, "evaluation status");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.evaluationStatus(transport().get("/v1/evaluation/status"), verbose());
		}
	}

	@Command(name = "scenarios", mixinStandardHelpOptions = true, description = "List available evaluation scenarios.")
	private static final class EvaluationScenariosCommand extends BaseCommand {
		private EvaluationScenariosCommand(CliContext context) {
			super(context, "evaluation scenarios");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.evaluationScenarios(transport().get("/v1/evaluation/scenarios"), verbose());
		}
	}

	@Command(name = "config", mixinStandardHelpOptions = true, description = "Inspect the scenario config associated with the current world.")
	private static final class EvaluationConfigCommand extends BaseCommand {
		private EvaluationConfigCommand(CliContext context) {
			super(context, "evaluation config");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.evaluationConfig(transport().get("/v1/evaluation/config"), verbose());
		}
	}

	@Command(name = "run", mixinStandardHelpOptions = true, description = "Start an evaluation scenario.")
	private static final class EvaluationRunCommand extends BaseCommand {
		@Option(names = "--scenario", required = true, description = "Scenario id from `airicraft evaluation scenarios`.")
		private String scenario;

		@Option(names = "--output-dir", description = "Directory where the evaluator addon should write the scenario flight record.")
		private String outputDir;

		private EvaluationRunCommand(CliContext context) {
			super(context, "evaluation run");
		}

		@Override
		Map<String, Object> runCommand() {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("scenario", scenario);
			putIfPresent(body, "outputDir", outputDir);
			return PayloadViews.evaluationRun(transport().post("/v1/evaluation/run", body), verbose());
		}
	}

	@Command(name = "results", mixinStandardHelpOptions = true, description = "Inspect evaluation scenario results.")
	private static final class EvaluationResultsCommand extends BaseCommand {
		private EvaluationResultsCommand(CliContext context) {
			super(context, "evaluation results");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.evaluationResults(transport().get("/v1/evaluation/results"), verbose());
		}
	}

	@Command(name = "evidence", mixinStandardHelpOptions = true, description = "Export evaluation evidence for external review.")
	private static final class EvaluationEvidenceCommand extends BaseCommand {
		private EvaluationEvidenceCommand(CliContext context) {
			super(context, "evaluation evidence");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.evaluationEvidence(transport().get("/v1/evaluation/evidence"), verbose());
		}
	}

	@Command(name = "list", mixinStandardHelpOptions = true, description = "List saved singleplayer worlds.")
	private static final class WorldsListCommand extends BaseCommand {
		private WorldsListCommand(CliContext context) {
			super(context, "worlds list");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.worldsList(transport().get("/v1/worlds"), verbose());
		}
	}

	@Command(name = "join", mixinStandardHelpOptions = true, description = "Join a saved singleplayer world.")
	private static final class WorldsJoinCommand extends BaseCommand {
		@Option(names = "--world-id", required = true, description = "World identifier from `airicraft worlds list`.")
		private String worldId;

		private WorldsJoinCommand(CliContext context) {
			super(context, "worlds join");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().post("/v1/worlds/join", Map.of("worldId", worldId));
		}
	}

	@Command(name = "list", mixinStandardHelpOptions = true, description = "List saved multiplayer servers.")
	private static final class ServersListCommand extends BaseCommand {
		private ServersListCommand(CliContext context) {
			super(context, "servers list");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.serversList(transport().get("/v1/servers"), verbose());
		}
	}

	@Command(name = "join", mixinStandardHelpOptions = true, description = "Join a saved multiplayer server.")
	private static final class ServersJoinCommand extends BaseCommand {
		@Option(names = "--server-id", required = true, description = "Server identifier from `airicraft servers list`.")
		private String serverId;

		private ServersJoinCommand(CliContext context) {
			super(context, "servers join");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().post("/v1/servers/join", Map.of("serverId", serverId));
		}
	}

	@Command(name = "focus", mixinStandardHelpOptions = true, description = "Describe the current player focus target.")
	private static final class PlayerFocusCommand extends BaseCommand {
		private PlayerFocusCommand(CliContext context) {
			super(context, "player focus");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().get("/v1/focus");
		}
	}

	@Command(name = "nearby-entities", mixinStandardHelpOptions = true, description = "List nearby loaded entities and their exact selectors.")
	private static final class PlayerNearbyEntitiesCommand extends BaseCommand {
		private PlayerNearbyEntitiesCommand(CliContext context) {
			super(context, "player nearby-entities");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().get("/v1/player/nearby-entities");
		}
	}

	@Command(name = "look-at", mixinStandardHelpOptions = true, description = "Rotate the player camera toward coordinates.")
	private static final class PlayerLookAtCommand extends BaseCommand {
		@Option(names = "--x", required = true)
		private double x;

		@Option(names = "--y", required = true)
		private double y;

		@Option(names = "--z", required = true)
		private double z;

		@Option(names = "--duration-ticks", description = "Spring response time in client ticks (approximate). Omit to use airicraft.yml cameraLerpDefaultTicks; 0 uses the default spring.")
		private Integer durationTicks;

		private PlayerLookAtCommand(CliContext context) {
			super(context, "player look-at");
		}

		@Override
		Map<String, Object> runCommand() {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("x", x);
			body.put("y", y);
			body.put("z", z);
			putIfNonNull(body, "durationTicks", durationTicks);
			return transport().post("/v1/player/look-at", body);
		}
	}

	private abstract static class PlayerEntitySelectorCommand extends BaseCommand {
		@Option(names = "--uuid", description = "Exact entity UUID.")
		private String uuid;

		@Option(names = "--name", description = "Visible entity name.")
		private String name;

		@Option(names = "--entity-type-id", description = "Exact namespaced entity type id, for example minecraft:sheep.")
		private String entityTypeId;

		private PlayerEntitySelectorCommand(CliContext context, String commandPath) {
			super(context, commandPath);
		}

		final String selectorUuid() {
			return uuid;
		}

		final String selectorName() {
			return name;
		}

		final String selectorEntityTypeId() {
			return entityTypeId;
		}

		final void requireSelector() {
			if ((uuid == null || uuid.isBlank())
				&& (name == null || name.isBlank())
				&& (entityTypeId == null || entityTypeId.isBlank())) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "Provide at least one of --uuid, --name, or --entity-type-id");
			}
		}
	}

	@Command(name = "attack-entity", mixinStandardHelpOptions = true, description = "Attack a nearby entity selected by uuid, name, or entity type.")
	private static final class PlayerAttackEntityCommand extends PlayerEntitySelectorCommand {
		@Option(names = "--mode", description = "Attack mode: kill or hit_once. Defaults to kill.")
		private String mode = "kill";

		private PlayerAttackEntityCommand(CliContext context) {
			super(context, "player attack-entity");
		}

		@Override
		Map<String, Object> runCommand() {
			requireSelector();
			return transport().post(
				"/v1/player/attack-entity",
				entitySelectorBody(selectorUuid(), selectorName(), selectorEntityTypeId(), null, mode)
			);
		}
	}

	@Command(name = "use-entity", mixinStandardHelpOptions = true, description = "Use current hand or an optional item on a nearby entity.")
	private static final class PlayerUseEntityCommand extends PlayerEntitySelectorCommand {
		@Option(names = "--item-id", description = "Optional exact namespaced item id to equip first.")
		private String itemId;

		private PlayerUseEntityCommand(CliContext context) {
			super(context, "player use-entity");
		}

		@Override
		Map<String, Object> runCommand() {
			requireSelector();
			return transport().post(
				"/v1/player/use-entity",
				entitySelectorBody(selectorUuid(), selectorName(), selectorEntityTypeId(), itemId, null)
			);
		}
	}

	@Command(name = "screenshot", mixinStandardHelpOptions = true, description = "Capture a first-person screenshot.")
	private static final class CameraScreenshotCommand implements Callable<Integer> {
		private final CliContext context;

		@Option(names = "--output", required = true, description = "Path to write the screenshot PNG.")
		private Path output;

		private CameraScreenshotCommand(CliContext context) {
			this.context = context;
		}

		@Override
		public Integer call() {
			CapturedImage capture = CapturedImage.screenshot(context.transport.post("/v1/camera/screenshot", null));
			Path outputPath = output.toAbsolutePath().normalize();
			try {
				Path parent = outputPath.getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				Files.write(outputPath, capture.bytes());
			}
			catch (java.io.IOException exception) {
				throw new UncheckedIOException(exception);
			}

			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("outputPath", outputPath.toString());
			payload.put("format", capture.format());
			payload.put("width", capture.width());
			payload.put("height", capture.height());
			payload.put("capturedAtMs", capture.capturedAtMs());
			context.printer.printSuccess("camera screenshot", payload);
			return 0;
		}
	}

	@Command(name = "describe", mixinStandardHelpOptions = true, description = "Describe the current first-person view.")
	private static final class VisionDescribeCommand implements Callable<Integer> {
		private final CliContext context;

		@Option(names = "--prompt", description = "Custom prompt for the vision model.")
		private String prompt;

		private VisionDescribeCommand(CliContext context) {
			this.context = context;
		}

		@Override
		public Integer call() {
			VisionDescriptionResult result = VisionDescriptionResult.fromBridgePayload(context.transport.post(
				"/v1/vision/describe",
				prompt == null || prompt.isBlank() ? null : Map.of("prompt", prompt)
			));
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("format", result.format());
			payload.put("capturedAtMs", result.capturedAtMs());
			payload.put("model", result.model());
			payload.put("description", result.description());
			context.printer.printSuccess("vision describe", payload);
			return 0;
		}
	}

	@Command(name = "status", mixinStandardHelpOptions = true, description = "Show map integration status.")
	private static final class MapStatusCommand extends BaseCommand {
		private MapStatusCommand(CliContext context) {
			super(context, "map status");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().get("/v1/map/status");
		}
	}

	@Command(name = "list", mixinStandardHelpOptions = true, description = "List map waypoints.")
	private static final class MapWaypointsListCommand extends BaseCommand {
		@Option(names = "--provider", description = "Map provider id.")
		private String provider;

		@Option(names = "--dimension", description = "Dimension id.")
		private String dimension;

		private MapWaypointsListCommand(CliContext context) {
			super(context, "map waypoints list");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().get(mapWaypointsPath(provider, dimension));
		}
	}

	@Command(name = "set", mixinStandardHelpOptions = true, description = "Create or update a map waypoint.")
	private static final class MapWaypointsSetCommand extends BaseCommand {
		@Option(names = "--provider", description = "Map provider id.")
		private String provider;

		@Option(names = "--waypoint-id", description = "Existing waypoint id to update.")
		private String waypointId;

		@Option(names = "--id", description = "Alias for --waypoint-id.")
		private String id;

		@Option(names = "--name", required = true)
		private String name;

		@Option(names = "--dimension", description = "Dimension id.")
		private String dimension;

		@Option(names = "--x", required = true)
		private Integer x;

		@Option(names = "--y", required = true)
		private Integer y;

		@Option(names = "--z", required = true)
		private Integer z;

		@Option(names = "--color", description = "Waypoint color as hex RGB.")
		private String color;

		private MapWaypointsSetCommand(CliContext context) {
			super(context, "map waypoints set");
		}

		@Override
		Map<String, Object> runCommand() {
			LinkedHashMap<String, Object> request = new LinkedHashMap<>();
			putIfPresent(request, "provider", provider);
			putIfPresent(request, "id", waypointId == null || waypointId.isBlank() ? id : waypointId);
			request.put("name", name);
			putIfPresent(request, "dimension", dimension);
			request.put("x", x);
			request.put("y", y);
			request.put("z", z);
			if (color != null && !color.isBlank()) {
				request.put("color", parseHexColor(commandPath(), color));
			}
			return transport().post("/v1/map/waypoints", request);
		}
	}

	@Command(name = "delete", mixinStandardHelpOptions = true, description = "Delete a map waypoint.")
	private static final class MapWaypointsDeleteCommand extends BaseCommand {
		@Option(names = "--waypoint-id", required = true)
		private String waypointId;

		private MapWaypointsDeleteCommand(CliContext context) {
			super(context, "map waypoints delete");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().delete("/v1/map/waypoints?id=" + encoded(waypointId), null);
		}
	}

	@Command(name = "image", mixinStandardHelpOptions = true, description = "Capture a map image.")
	private static final class MapImageCommand implements Callable<Integer> {
		private final CliContext context;

		@Option(names = "--provider", description = "Map provider id.")
		private String provider;

		@Option(names = "--kind", defaultValue = "worldmap", description = "Map image kind.")
		private String kind;

		@Option(names = "--dimension", description = "Dimension id.")
		private String dimension;

		@Option(names = "--radius-chunks", description = "Radius in chunks.")
		private Integer radiusChunks;

		@Option(names = "--zoom", description = "JourneyMap zoom level.")
		private Integer zoom;

		@Option(names = "--grid", description = "Include grid overlay.")
		private boolean grid;

		@Option(names = {"--origin-x", "--x"}, description = "Map center block X. Defaults to the player position.")
		private Integer originX;

		@Option(names = {"--origin-z", "--z"}, description = "Map center block Z. Defaults to the player position.")
		private Integer originZ;

		@Option(names = "--output", required = true, description = "Path to write the map PNG.")
		private Path output;

		private MapImageCommand(CliContext context) {
			this.context = context;
		}

		@Override
		public Integer call() {
			if ((originX == null) != (originZ == null)) {
				throw new CliUsageException("map image", "invalid_arguments", "origin-x and origin-z must be provided together");
			}
			LinkedHashMap<String, Object> request = new LinkedHashMap<>();
			putIfPresent(request, "provider", provider);
			putIfPresent(request, "kind", kind);
			putIfPresent(request, "dimension", dimension);
			if (radiusChunks != null) {
				request.put("radiusChunks", radiusChunks);
			}
			if (zoom != null) {
				request.put("zoom", zoom);
			}
			request.put("grid", grid);
			if (originX != null) {
				request.put("originX", originX);
				request.put("originZ", originZ);
			}
			CapturedImage capture = CapturedImage.mapImage(context.transport.post("/v1/map/image", request));
			Path outputPath = output.toAbsolutePath().normalize();
			writeCapture(outputPath, capture.bytes());

			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("outputPath", outputPath.toString());
			payload.put("format", capture.format());
			payload.put("width", capture.width());
			payload.put("height", capture.height());
			payload.put("capturedAtMs", capture.capturedAtMs());
			context.printer.printSuccess("map image", payload);
			return 0;
		}
	}

	@Command(name = "difficulty", mixinStandardHelpOptions = true, description = "Read or change the local singleplayer world's difficulty; respects locked and hardcore worlds.")
	private static final class WorldDifficultyCommand extends BaseCommand {
		@Option(names = "--set", description = "peaceful, easy, normal, or hard. Omit to inspect.")
		private String difficulty;
		private WorldDifficultyCommand(CliContext context) { super(context, "world difficulty"); }
		@Override Map<String, Object> runCommand() {
			if (difficulty == null) return transport().get("/v1/worlds/difficulty");
			if (!List.of("peaceful", "easy", "normal", "hard").contains(difficulty))
				throw new CliUsageException(commandPath(), "invalid_arguments", "Difficulty must be peaceful, easy, normal, or hard");
			return transport().post("/v1/worlds/difficulty", Map.of("difficulty", difficulty));
		}
	}

	@Command(name = "snapshot", mixinStandardHelpOptions = true, description = "Capture a snapshot of blocks around a position.")
	private static final class WorldSnapshotCommand extends BaseCommand {
		@Option(names = "--x")
		private Integer x;

		@Option(names = "--y")
		private Integer y;

		@Option(names = "--z")
		private Integer z;

		@Option(names = "--radius", description = "Snapshot radius from 0 to 4.")
		private Integer radius;

		private WorldSnapshotCommand(CliContext context) {
			super(context, "world snapshot");
		}

		@Override
		Map<String, Object> runCommand() {
			boolean anyCoordinate = x != null || y != null || z != null;
			boolean allCoordinates = x != null && y != null && z != null;
			if (anyCoordinate && !allCoordinates) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "x, y, and z must be provided together");
			}
			int requestedRadius = radius == null ? 1 : radius;
			if (requestedRadius < 0 || requestedRadius > 4) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "radius must be between 0 and 4");
			}
			String path = "/v1/world-snapshot?radius=" + requestedRadius;
			if (allCoordinates) {
				path += "&x=" + x + "&y=" + y + "&z=" + z;
			}
			return PayloadViews.worldSnapshot(
				transport().get(path),
				verbose()
			);
		}
	}

	@Command(name = "block", mixinStandardHelpOptions = true, description = "Create a block highlight.")
	private static final class HighlightBlockCommand extends BaseCommand {
		@Option(names = "--x", required = true)
		private int x;

		@Option(names = "--y", required = true)
		private int y;

		@Option(names = "--z", required = true)
		private int z;

		@Option(names = "--color")
		private String color;

		@Option(names = "--duration-seconds")
		private Integer durationSeconds;

		@Option(names = "--overlay-text")
		private String overlayText;

		private HighlightBlockCommand(CliContext context) {
			super(context, "highlights block");
		}

		@Override
		Map<String, Object> runCommand() {
			Map<String, Object> body = highlightBody(
				"block",
				validatedColor(color, commandPath()),
				validatedDurationMs(durationSeconds, commandPath()),
				overlayText
			);
			body.put("x", x);
			body.put("y", y);
			body.put("z", z);
			return transport().post("/v1/highlights", body);
		}
	}

	@Command(name = "region", mixinStandardHelpOptions = true, description = "Create a region highlight.")
	private static final class HighlightRegionCommand extends BaseCommand {
		@Option(names = "--x1", required = true)
		private int x1;

		@Option(names = "--y1", required = true)
		private int y1;

		@Option(names = "--z1", required = true)
		private int z1;

		@Option(names = "--x2", required = true)
		private int x2;

		@Option(names = "--y2", required = true)
		private int y2;

		@Option(names = "--z2", required = true)
		private int z2;

		@Option(names = "--color")
		private String color;

		@Option(names = "--duration-seconds")
		private Integer durationSeconds;

		@Option(names = "--overlay-text")
		private String overlayText;

		private HighlightRegionCommand(CliContext context) {
			super(context, "highlights region");
		}

		@Override
		Map<String, Object> runCommand() {
			Map<String, Object> body = highlightBody(
				"region",
				validatedColor(color, commandPath()),
				validatedDurationMs(durationSeconds, commandPath()),
				overlayText
			);
			body.put("x1", x1);
			body.put("y1", y1);
			body.put("z1", z1);
			body.put("x2", x2);
			body.put("y2", y2);
			body.put("z2", z2);
			return transport().post("/v1/highlights", body);
		}
	}

	@Command(name = "list", mixinStandardHelpOptions = true, description = "List active highlights.")
	private static final class HighlightsListCommand extends BaseCommand {
		private HighlightsListCommand(CliContext context) {
			super(context, "highlights list");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.highlightsList(transport().get("/v1/highlights"), verbose());
		}
	}

	@Command(name = "clear", mixinStandardHelpOptions = true, description = "Clear one highlight.")
	private static final class HighlightsClearCommand extends BaseCommand {
		@Option(names = "--highlight-id", required = true)
		private String highlightId;

		private HighlightsClearCommand(CliContext context) {
			super(context, "highlights clear");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().delete("/v1/highlights?id=" + encoded(highlightId), null);
		}
	}

	@Command(name = "clear-all", mixinStandardHelpOptions = true, description = "Clear all highlights.")
	private static final class HighlightsClearAllCommand extends BaseCommand {
		private HighlightsClearAllCommand(CliContext context) {
			super(context, "highlights clear-all");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().delete("/v1/highlights", null);
		}
	}

	@Command(name = "help", mixinStandardHelpOptions = true, description = "Show CLI help for a command path.")
	private static final class HelpCommand implements Callable<Integer> {
		private final CliContext context;

		@Parameters(arity = "0..*", description = "Command path to inspect, for example `highlights block`.")
		private List<String> commandPath = List.of();

		private HelpCommand(CliContext context) {
			this.context = context;
		}

		@Override
		public Integer call() {
			CommandLine target = context.rootCommandLine;
			List<String> consumed = new ArrayList<>();
			for (String token : commandPath) {
				CommandLine next = target.getSubcommands().get(token);
				if (next == null) {
					throw new CliUsageException(
						"help",
						"invalid_arguments",
						"Unknown command path: " + String.join(" ", append(consumed, token))
					);
				}
				target = next;
				consumed.add(token);
			}
			target.usage(context.printer.out);
			return 0;
		}
	}

	private static final class CliParameterExceptionHandler implements IParameterExceptionHandler {
		private final CliContext context;

		private CliParameterExceptionHandler(CliContext context) {
			this.context = context;
		}

		@Override
		public int handleParseException(ParameterException exception, String[] args) {
			String commandPath = commandPathFromArgs(rootCommandLine(exception.getCommandLine()), args);
			context.printer.printError(commandPath, "invalid_arguments", exception.getMessage());
			return 2;
		}
	}

	private static final class CliExecutionExceptionHandler implements IExecutionExceptionHandler {
		private final CliContext context;

		private CliExecutionExceptionHandler(CliContext context) {
			this.context = context;
		}

		@Override
		public int handleExecutionException(Exception exception, CommandLine commandLine, ParseResult parseResult) {
			String commandPath = parsedCommandPath(parseResult);
			Throwable cause = exception instanceof CommandLine.ExecutionException executionException && executionException.getCause() != null
				? executionException.getCause()
				: exception;

			if (cause instanceof CliUsageException usageException) {
				context.printer.printError(usageException.commandPath(), usageException.code(), usageException.getMessage());
				return 2;
			}
			if (cause instanceof BridgeUnavailableException bridgeUnavailableException) {
				context.printer.printError(commandPath, bridgeUnavailableException.code(), bridgeUnavailableException.getMessage());
				return transportExitCode(bridgeUnavailableException.code());
			}
			if (cause instanceof UncheckedIOException uncheckedIOException) {
				context.printer.printError(commandPath, "io_error", uncheckedIOException.getMessage());
				return 1;
			}

			context.printer.printError(commandPath, "internal_error", nonEmpty(cause.getMessage(), cause.getClass().getSimpleName()));
			return 1;
		}
	}

	private static final class CliUsageException extends RuntimeException {
		private final String commandPath;
		private final String code;

		private CliUsageException(String commandPath, String code, String message) {
			super(message);
			this.commandPath = commandPath;
			this.code = code;
		}

		private String commandPath() {
			return commandPath;
		}

		private String code() {
			return code;
		}
	}

	private static void putIfPresent(Map<String, Object> map, String key, String value) {
		if (value != null && !value.isBlank()) {
			map.put(key, value);
		}
	}

	private static void putIfNonNull(Map<String, Object> map, String key, Object value) {
		if (value != null) {
			map.put(key, value);
		}
	}

	private static Map<String, Object> statusPayload(MinecraftTransport transport) {
		try {
			return transport.get("/v1/status");
		}
		catch (BridgeUnavailableException exception) {
			return Map.of(
				"available", false,
				"bridgeAvailable", false,
				"worldLoaded", false,
				"sessionState", "minecraft_unavailable",
				"state", "minecraft_unavailable",
				"message", "Minecraft bridge is not active"
			);
		}
	}

	private static String actionGoalPath(String executionId, boolean list) {
		if (list) {
			return "/v1/agent/action-goals?list=true";
		}
		return executionId == null || executionId.isBlank()
			? "/v1/agent/action-goals"
			: "/v1/agent/action-goals?execution-id=" + encoded(executionId);
	}

	private static String mapWaypointsPath(String provider, String dimension) {
		StringBuilder path = new StringBuilder("/v1/map/waypoints");
		String separator = "?";
		if (provider != null && !provider.isBlank()) {
			path.append(separator).append("provider=").append(encoded(provider));
			separator = "&";
		}
		if (dimension != null && !dimension.isBlank()) {
			path.append(separator).append("dimension=").append(encoded(dimension));
		}
		return path.toString();
	}

	private static String encoded(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static Map<String, Object> entitySelectorBody(
		String uuid,
		String name,
		String entityTypeId,
		String itemId,
		String mode
	) {
		Map<String, Object> body = new LinkedHashMap<>();
		putIfPresent(body, "uuid", uuid);
		putIfPresent(body, "name", name);
		putIfPresent(body, "entityTypeId", entityTypeId);
		putIfPresent(body, "itemId", itemId);
		putIfPresent(body, "mode", mode);
		return body;
	}

	private static Map<String, Object> highlightBody(String kind, String color, Long durationMs, String overlayText) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("kind", kind);
		putIfPresent(body, "color", color);
		putIfNonNull(body, "durationMs", durationMs);
		putIfPresent(body, "overlayText", overlayText);
		return body;
	}

	private static int countNonNull(Object... values) {
		int count = 0;
		for (Object value : values) {
			if (value != null) {
				count++;
			}
		}
		return count;
	}

	private static int parseHexColor(String commandPath, String raw) {
		String value = raw.trim();
		if (value.startsWith("#")) {
			value = value.substring(1);
		}
		if (value.startsWith("0x") || value.startsWith("0X")) {
			value = value.substring(2);
		}
		if (value.length() != 6) {
			throw new CliUsageException(commandPath, "invalid_arguments", "Color must be a 6-digit RGB hex value");
		}
		try {
			return Integer.parseInt(value, 16);
		}
		catch (NumberFormatException exception) {
			throw new CliUsageException(commandPath, "invalid_arguments", "Color must be a 6-digit RGB hex value");
		}
	}

	private static void writeCapture(Path outputPath, byte[] bytes) {
		try {
			Path parent = outputPath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.write(outputPath, bytes);
		}
		catch (java.io.IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	private static Map<String, Object> prepareClientTickCapture(
		Map<String, Object> source,
		Path outputImage,
		String commandPath
	) {
		Map<String, Object> payload = new LinkedHashMap<>(source);
		Object encodedImage = payload.remove("imageBase64");
		if (encodedImage instanceof String imageBase64 && !imageBase64.isBlank()) {
			if (outputImage == null) {
				payload.put("imageOutputOmitted", true);
			}
			else {
				try {
					Path outputPath = outputImage.toAbsolutePath().normalize();
					writeCapture(outputPath, java.util.Base64.getDecoder().decode(imageBase64));
					payload.put("imageOutputPath", outputPath.toString());
				}
				catch (IllegalArgumentException exception) {
					throw new CliUsageException(commandPath, "bridge_io_error", "Bridge returned invalid image data");
				}
			}
		}
		return payload;
	}

	private static void requirePositivePauseEpoch(long pauseEpoch, String commandPath) {
		if (pauseEpoch < 1L) {
			throw new CliUsageException(commandPath, "invalid_arguments", "pause-epoch must be positive");
		}
	}

	private static Map<String, Object> parseJsonObject(String raw, String commandPath) {
		try {
			return OBJECT_MAPPER.readValue(raw == null || raw.isBlank() ? "{}" : raw, MAP_TYPE);
		}
		catch (java.io.IOException exception) {
			throw new CliUsageException(commandPath, "invalid_arguments", "arguments must be a JSON object");
		}
	}

	private static final class TextPrinter {
		private final PrintWriter out;

		private TextPrinter(PrintWriter out) {
			this.out = out;
		}

		private void printSuccess(String commandPath, Map<String, Object> payload) {
			out.println("status: ok");
			out.println("command: " + commandPath);
			renderMapBody(normalizeMap(payload));
			out.flush();
		}

		private void printError(String commandPath, String code, String message) {
			out.println("status: error");
			out.println("command: " + commandPath);
			out.println("error_code: " + code);
			out.println("message: " + message);
			out.flush();
		}

		private void renderMapBody(Map<String, Object> map) {
			List<Map.Entry<String, Object>> deferred = new ArrayList<>();
			for (Map.Entry<String, Object> entry : map.entrySet()) {
				Object value = entry.getValue();
				if (isScalar(value)) {
					out.println(entry.getKey() + ": " + scalarText(value));
				}
				else {
					deferred.add(entry);
				}
			}

			for (Map.Entry<String, Object> entry : deferred) {
				renderComplex(entry.getKey(), entry.getValue());
			}
		}

		private void renderComplex(String label, Object value) {
			if (value instanceof Map<?, ?> nested) {
				out.println();
				out.println("[" + label + "]");
				renderMapBody(normalizeMap(castMap(nested)));
				return;
			}
			if (value instanceof List<?> list) {
				String itemLabel = singularize(label);
				for (int index = 0; index < list.size(); index++) {
					Object item = list.get(index);
					out.println();
					out.println("[" + itemLabel + " " + (index + 1) + "]");
					if (item instanceof Map<?, ?> nested) {
						renderMapBody(normalizeMap(castMap(nested)));
					}
					else {
						out.println("value: " + scalarText(item));
					}
				}
			}
		}
	}

	private static final class PayloadViews {
		private PayloadViews() {
		}

		private static Map<String, Object> status(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "bridgeAvailable", "worldLoaded", "sessionState", "currentScreen", "canJoinWorldOrServer", "state", "message");
			if (payload.containsKey("debugDashboard")) {
				Map<String, Object> dashboard = map(payload.get("debugDashboard"));
				LinkedHashMap<String, Object> dashboardView = new LinkedHashMap<>();
				copy(dashboardView, dashboard, "enabled", "running", "port", "startedAtMs", "url", "error");
				view.put("debugDashboard", dashboardView);
			}
			if (payload.containsKey("automaticPlaytest")) {
				LinkedHashMap<String, Object> playtestView = new LinkedHashMap<>();
				copy(playtestView, map(payload.get("automaticPlaytest")), "enabled", "state", "outputDir", "error");
				view.put("automaticPlaytest", playtestView);
			}
			if (verbose) {
				copy(view, payload, "dimension", "player", "focus");
			}
			return view;
		}

		private static Map<String, Object> reload(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "reloaded", "agentStateReset", "sessionMode", "worldLoaded",
				"plannerVisionMode", "llmConfigured", "visionConfigured", "observabilityEnabled");
			if (verbose) {
				copy(view, payload, "config", "llm", "observability");
			}
			return view;
		}

		private static Map<String, Object> agentStatus(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "initialized", "tickCount", "codexDriverActive", "llmAvailable", "visionAvailable", "plannerVisionMode", "degraded");
			if (payload.containsKey("session")) {
				view.put("session", payload.get("session"));
			}
			Map<String, Object> eventPolicy = map(payload.get("eventPolicy"));
			copy(view, eventPolicy, "activeRuleCount", "recentInterventionCount", "lastMatchedRuleId", "lastMatchedEffect");
			if (payload.containsKey("taskExecution")) {
				view.put("taskExecution", payload.get("taskExecution"));
			}
			if (payload.containsKey("activeJob")) {
				view.put("activeJob", payload.get("activeJob"));
			}
			if (payload.containsKey("task")) {
				view.put("task", payload.get("task"));
			}
			if (payload.containsKey("reflex")) {
				view.put("reflex", payload.get("reflex"));
			}
			if (verbose && payload.containsKey("evaluation")) {
				view.put("evaluation", payload.get("evaluation"));
			}
			return view;
		}

		private static Map<String, Object> agentTools(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "codexDriverActive", "toolCount");
			List<Map<String, Object>> tools = maps(payload.get("tools"));
			List<Map<String, Object>> projected = new ArrayList<>();
			for (Map<String, Object> tool : tools) {
				Map<String, Object> function = map(tool.get("function"));
				LinkedHashMap<String, Object> item = new LinkedHashMap<>();
				copy(item, function, "name", "description");
				if (verbose) {
					copy(item, function, "parameters");
				}
				projected.add(item);
			}
			view.put("tools", projected);
			return view;
		}

		private static Map<String, Object> agentToolResult(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "codexDriverActive", "toolName", "result", "imageAttached", "imageOutputPath");
			if (verbose) {
				copy(view, payload, "imageMimeType", "imageDetail");
			}
			return view;
		}

		private static Map<String, Object> agentSession(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "lanPublished", "lanPort", "primaryInteractionPlayer", "session");
			List<Map<String, Object>> nearbyPlayers = maps(payload.get("nearbyPlayers"));
			view.put("nearbyPlayerCount", nearbyPlayers.size());
			if (verbose) {
				view.put("nearbyPlayers", nearbyPlayers);
			}
			return view;
		}

		private static Map<String, Object> agentGoals(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "activeGoal", "activeJob", "taskExecution");
			if (verbose) {
				copy(view, payload, "task", "missionExecution", "lastDialogueResponse");
			}
			return view;
		}

		private static Map<String, Object> agentTasks(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "cancelled", "resumed", "holdId");
			if (verbose) {
				copy(view, payload, "reflex", "task", "taskExecution", "missionExecution");
			}
			else {
				Map<String, Object> reflex = map(payload.get("reflex"));
				if (!reflex.isEmpty()) {
					LinkedHashMap<String, Object> compactReflex = new LinkedHashMap<>();
					copy(compactReflex, reflex, "state", "cause", "action", "safetyEpoch", "holdId");
					view.put("reflex", compactReflex);
				}
				Map<String, Object> taskExecution = map(payload.get("taskExecution"));
				if (!taskExecution.isEmpty()) {
					LinkedHashMap<String, Object> compactExecution = new LinkedHashMap<>();
					copy(compactExecution, taskExecution, "state", "taskId", "processName", "lastPathEvent");
					view.put("taskExecution", compactExecution);
				}
			}
			if (!verbose && payload.containsKey("task")) {
				Map<String, Object> task = map(payload.get("task"));
				LinkedHashMap<String, Object> compactTask = new LinkedHashMap<>();
				copy(compactTask, task, "state", "spec", "mission", "activeStepId", "activeStepKind");
				view.put("task", compactTask);
			}
			return view;
		}

		private static Map<String, Object> agentLedger(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "ledger");
			if (!verbose && payload.containsKey("ledger")) {
				Map<String, Object> ledger = map(payload.get("ledger"));
				LinkedHashMap<String, Object> compactLedger = new LinkedHashMap<>();
				copy(compactLedger, ledger, "missionId", "missionType", "goalText", "activeStepId", "replanReason");
				view.put("ledger", compactLedger);
			}
			return view;
		}

		private static Map<String, Object> agentEvidence(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "evidence");
			if (!verbose && payload.containsKey("evidence")) {
				Map<String, Object> evidence = map(payload.get("evidence"));
				LinkedHashMap<String, Object> compactEvidence = new LinkedHashMap<>();
				copy(compactEvidence, evidence, "dimension", "x", "y", "z", "inventoryCounts");
				view.put("evidence", compactEvidence);
			}
			return view;
		}

		private static Map<String, Object> agentStepExecution(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "stepExecution", "taskExecution");
			if (!verbose && payload.containsKey("stepExecution")) {
				Map<String, Object> stepExecution = map(payload.get("stepExecution"));
				LinkedHashMap<String, Object> compactStep = new LinkedHashMap<>();
				copy(compactStep, stepExecution, "stepId", "status", "failureReason", "updatedTick");
				view.put("stepExecution", compactStep);
			}
			return view;
		}

		private static Map<String, Object> agentActionInspect(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "worldLoaded", "sessionState", "primitiveCount", "domainProviderCount");
			view.put("primitives", filterItems(
				maps(payload.get("primitives")),
				verbose,
				List.of("id", "version", "summary", "foregroundActuation", "executorBinding"),
				List.of("cost", "cancellable", "defaultTimeoutTicks", "capabilityTags", "failureCodes", "guardFactTypes", "needFactTypes", "producedFactTypes", "consumedFactTypes", "params")
			));
			view.put("domainProviders", filterItems(
				maps(payload.get("domainProviders")),
				verbose,
				List.of("id", "summary", "producedGoal"),
				List.of("inputFacts")
			));
			return view;
		}

		private static Map<String, Object> agentActionGoal(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "admission", "foregroundExecutionId", "suspendedCount", "runnableCount", "residency", "executionId", "state", "executionPhase", "resolved", "accepted", "activePrimitive", "cursor", "stepAttempt", "replanCount", "watchCount", "pendingWatch", "activeTaskId", "failureCode", "message", "traceEventCount");
			copy(view, payload, "goal", "currentStep", "dispatch", "task", "taskExecution");
			Map<String, Object> route = map(payload.get("route"));
			if (!route.isEmpty()) {
				LinkedHashMap<String, Object> compactRoute = new LinkedHashMap<>();
				copy(compactRoute, route, "cost");
				compactRoute.put("stepCount", maps(route.get("steps")).size());
				if (verbose) {
					compactRoute.put("steps", maps(route.get("steps")));
				}
				view.put("route", compactRoute);
			}
			if (verbose) {
				copy(view, payload, "factSourceCounts", "trace", "recoveryHistory");
			}
			return view;
		}

		private static Map<String, Object> agentActionGoals(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "foregroundExecutionId", "executionCount", "nonterminalCount", "suspendedCount", "runnableCount", "watchCount");
			view.put("executions", filterItems(
				maps(payload.get("executions")),
				verbose,
				List.of("executionId", "state", "residency", "goal", "watchCount", "activeTaskId", "failureCode", "message", "updatedTick"),
				List.of("route", "currentStep", "trace", "recoveryHistory", "factSourceCounts")
			));
			if (verbose) {
				view.put("watches", maps(payload.get("watches")));
			}
			return view;
		}

		private static Map<String, Object> agentActionWatches(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "foregroundExecutionId", "executionCount", "nonterminalCount", "suspendedCount", "runnableCount", "watchCount");
			view.put("watches", filterItems(
				maps(payload.get("watches")),
				verbose,
				List.of("executionId", "watchId", "stepId", "progressKind", "consumedEligibleTicks", "timeoutTicks", "progressEligible", "pauseReason"),
				List.of("condition", "sourceFactIdentity", "anchor")
			));
			return view;
		}

		private static Map<String, Object> agentDialogue(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "lastChatTick", "lastChatText");
			Map<String, Object> dialogue = map(payload.get("dialogue"));
			Map<String, Object> conversationSources = map(payload.get("conversationSources"));
			copy(view, dialogue, "pendingReply", "pendingReplyReason", "degraded", "consecutiveFailureCount", "lastFailureType", "lastFailureTick");
			copy(view, conversationSources, "canonicalMessageCount", "projectedMessageCount", "canonicalUserTurnCount", "projectedUserTurnCount", "hiddenKinds");
			List<Map<String, Object>> recentTurns = maps(dialogue.get("recentTurns"));
			view.put("recentTurnCount", recentTurns.size());
			if (verbose) {
				copy(view, dialogue, "lastResponse");
				copy(view, payload, "conversation", "canonicalConversation", "projectedConversation", "chronicleConversation", "contextConversation", "conversationSources", "plannerJournal");
				view.put("recentTurns", recentTurns);
			}
			return view;
		}

		private static Map<String, Object> agentDebugState(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available");
			Map<String, Object> planner = map(payload.get("planner"));
			Map<String, Object> dialogueState = map(payload.get("dialogueState"));
			Map<String, Object> conversationSources = map(payload.get("conversationSources"));
			Map<String, Object> taskProgressProbe = map(payload.get("taskProgressProbe"));
			Map<String, Object> chatProbe = map(payload.get("chatProbe"));
			Map<String, Object> eventPipeline = map(payload.get("eventPipeline"));
			List<Map<String, Object>> plannerAttempts = maps(payload.get("plannerAttempts"));
			List<Map<String, Object>> timelineTail = maps(payload.get("timelineTail"));
			copy(view, planner, "configured", "plannerVisionMode", "inFlight", "plannerInFlight", "compactionInFlight", "toolInFlight", "activeGeneration", "currentPhase", "activeAttemptCount");
			copy(view, dialogueState, "pendingReply", "pendingReplyReason", "degraded", "consecutiveFailureCount", "lastFailureType", "lastFailureTick");
			copy(view, payload, "activeJob", "taskExecution");
			copy(view, conversationSources, "canonicalMessageCount", "projectedMessageCount", "canonicalUserTurnCount", "projectedUserTurnCount", "hiddenKinds");
			copy(view, taskProgressProbe, "active", "resourceKind", "baselineResourceCount", "currentResourceCount", "inventoryDelta", "targetQuantity", "collected", "remaining", "activeJobStatus", "blockedReason", "completionReason");
			copy(view, chatProbe, "lastAttemptTick", "lastAttemptSource", "lastAttemptReusedPriorResponse", "lastSendSucceeded", "lastEmissionTick", "lastEmissionSource");
			copy(view, eventPipeline, "rawLatestSeqNo", "plannerLatestSeqNo", "lastProcessedRawSeqNo", "lastRawEventSeqNo", "lastPlannerEventSeqNo", "lastEventType", "lastDecisionEffect", "lastTriggerType", "lastEmitSemantic", "lastEmitTrigger");
			view.put("plannerAttemptCount", plannerAttempts.size());
			view.put("timelineEntryCount", timelineTail.size());
			if (verbose) {
				copy(view, dialogueState, "lastResponse");
				copy(view, chatProbe, "lastAttemptText", "lastEmissionText");
				copy(view, payload, "task", "missionExecution");
				copy(view, conversationSources, "canonicalConversation", "projectedConversation", "chronicleConversation", "contextConversation");
				view.put("plannerAttempts", plannerAttempts);
				view.put("timelineTail", timelineTail);
			}
			return view;
		}

		private static Map<String, Object> agentDebugTimeline(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "oldestEntryId", "latestEntryId", "truncated");
			List<Map<String, Object>> entries = maps(payload.get("entries"));
			view.put("entryCount", entries.size());
			view.put("entries", verbose ? entries : filterItems(entries, false,
				List.of("entryId", "tick", "timestampMs", "domain", "action", "summary"),
				List.of("correlation", "payload")
			));
			return view;
		}

		private static Map<String, Object> clientTickDebugStatus(Map<String, Object> payload) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "phase", "paused", "debugSessionId", "pauseEpoch", "clientTickId", "snapshotId", "frameStatus", "serverPhase", "serverPaused", "serverTickId");
			return view;
		}

		private static Map<String, Object> clientTickDebugCapture(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "paused", "debugSessionId", "captureId", "snapshotId", "clientTickId", "pauseEpoch", "serverPhase", "serverPaused", "serverTickId", "imageOutputPath", "imageOutputOmitted");
			Map<String, Object> snapshot = map(payload.get("snapshot"));
			Map<String, Object> frame = map(payload.get("frame"));
			if (verbose) {
				view.put("snapshot", snapshot);
				view.put("frame", frame);
			}
			else {
				LinkedHashMap<String, Object> snapshotView = new LinkedHashMap<>();
				copy(snapshotView, snapshot, "capturedAtMs", "dimensionId", "worldTime", "timeOfDay", "player", "playerActions", "plannerGeneration", "plannerPhase");
				view.put("snapshot", snapshotView);
				LinkedHashMap<String, Object> frameView = new LinkedHashMap<>();
				copy(frameView, frame, "status", "format", "width", "height", "capturedAtMs", "errorCode", "message");
				view.put("frame", frameView);
			}
			return view;
		}

		private static Map<String, Object> agentDebugIdleTrigger(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "accepted", "triggerType", "speaker", "tick", "timestampMs", "sessionMode");
			if (verbose) {
				copy(view, payload, "task", "taskExecution", "lastDialogueResponse");
			}
			return view;
		}

		private static Map<String, Object> agentEvents(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "oldestSeqNo", "latestSeqNo", "truncated");
			List<Map<String, Object>> events = maps(payload.get("events"));
			view.put("eventCount", events.size());
			view.put("events", verbose ? events : filterItems(events, false,
				List.of("seqNo", "tick", "timestampMs", "type"),
				List.of("payload")
			));
			return view;
		}

		private static Map<String, Object> agentContext(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			view.put("available", payload.getOrDefault("available", false));
			Map<String, Object> planner = map(payload.get("planner"));
			Map<String, Object> context = map(planner.get("context"));
			Map<String, Object> eventPolicy = map(payload.get("eventPolicy"));
			Map<String, Object> conversationSources = map(payload.get("conversationSources"));
			List<Object> contextExcerpt = values(payload.get("contextExcerpt"));
			if (payload.containsKey("taskExecution")) {
				view.put("taskExecution", payload.get("taskExecution"));
			}
			if (payload.containsKey("activeJob")) {
				view.put("activeJob", payload.get("activeJob"));
			}
			copy(view, planner, "configured", "plannerVisionMode", "inFlight", "plannerInFlight", "compactionInFlight", "captureInFlight", "toolInFlight", "toolUsed");
			copy(view, planner, "coalescePending", "coalesceReadyAtMs", "coalesceWindowMs");
			copy(view, context, "compactionTriggerTokens", "compactionPending", "acceptedTurnCount",
				"pendingSemanticEventCount", "projectedPendingNoticeCount", "frozenPlannerMessageCount", "queuedTriggerCount",
				"lastObservedEventSeqNo", "lastAcceptedTimeContextAtMs", "pendingSemanticGap", "overflowFlushPending");
			copy(view, conversationSources, "canonicalMessageCount", "projectedMessageCount", "canonicalUserTurnCount", "projectedUserTurnCount", "hiddenKinds");
			copy(view, eventPolicy, "activeRuleCount", "recentInterventionCount", "lastMatchedRuleId", "lastMatchedEffect");
			view.put("contextExcerptLineCount", contextExcerpt.size());
			if (verbose) {
				copy(view, planner, "baseRequest", "lastCompactionResult");
				copy(view, context, "lastObservedUsage", "acceptedAmbientContext", "activeCheckpoint");
				copy(view, payload, "conversation", "canonicalConversation", "projectedConversation", "chronicleConversation", "contextConversation", "conversationSources", "plannerJournal");
				view.put("contextExcerpt", contextExcerpt);
			}
			return view;
		}

		private static Map<String, Object> agentEventPolicy(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "activeRuleCount", "recentInterventionCount");
			Map<String, Object> lastDecision = map(payload.get("lastDecision"));
			copy(view, lastDecision, "matchedRuleId", "effect", "reason", "bypassed");
			List<Map<String, Object>> activeRules = maps(payload.get("activeRules"));
			List<Map<String, Object>> recentInterventions = maps(payload.get("recentInterventions"));
			view.put("ruleCount", activeRules.size());
			view.put("interventionCount", recentInterventions.size());
			if (verbose) {
				view.put("activeRules", activeRules);
				view.put("recentInterventions", recentInterventions);
			}
			return view;
		}

		private static Map<String, Object> agentCompact(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "started", "completed", "timeoutMs");
			Map<String, Object> planner = map(payload.get("planner"));
			Map<String, Object> context = map(planner.get("context"));
			copy(view, planner, "configured", "inFlight", "plannerInFlight", "compactionInFlight", "toolInFlight");
			copy(view, context, "compactionPending", "acceptedTurnCount");
			if (verbose) {
				copy(view, planner, "lastCompactionResult");
				copy(view, context, "lastObservedUsage", "activeCheckpoint");
			}
			return view;
		}

		private static Map<String, Object> evaluationStatus(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			List<Object> capabilities = values(payload.get("capabilities"));
			Map<String, Object> report = map(payload.get("report"));
			copy(view, payload, "available", "sessionMode", "worldLoaded", "scenarioRoot");
			view.put("capabilityCount", capabilities.size());
			copyReportSummary(view, report);
			if (verbose) {
				view.put("capabilities", capabilities);
				view.put("report", report);
			}
			return view;
		}

		private static Map<String, Object> evaluationScenarios(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			List<Map<String, Object>> scenarios = maps(payload.get("scenarios"));
			copy(view, payload, "available", "scenarioRoot");
			view.put("scenarioCount", scenarios.size());
			view.put("scenarios", filterItems(
				scenarios,
				verbose,
				List.of("id", "name", "frozen", "promptConfigured", "checkCount"),
				List.of("minecraftVersion", "airicraftVersion", "worldArchive", "maxPlannerTurns", "maxElapsedTicks", "controlMode")
			));
			if (verbose && payload.containsKey("report")) {
				view.put("report", payload.get("report"));
			}
			return view;
		}

		private static Map<String, Object> evaluationConfig(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			Map<String, Object> scenario = map(payload.get("scenario"));
			copy(view, payload, "available", "scenarioId", "configPath", "worldArchivePath", "scenarioRoot");
			copy(view, scenario, "name", "frozen", "promptConfigured", "checkCount", "worldArchive");
			if (verbose) {
				view.put("scenario", scenario);
			}
			return view;
		}

		private static Map<String, Object> evaluationRun(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			Map<String, Object> report = map(payload.get("report"));
			copy(view, payload, "accepted", "scenario", "worldName", "worldPath");
			copyReportSummary(view, report);
			if (verbose) {
				copy(view, payload, "join", "report");
			}
			return view;
		}

		private static Map<String, Object> evaluationResults(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			Map<String, Object> report = map(payload.get("report"));
			copy(view, payload, "available");
			copyReportSummary(view, report);
			List<Map<String, Object>> checks = maps(report.get("checks"));
			view.put("checkCount", checks.size());
			if (verbose) {
				view.put("report", report);
			}
			return view;
		}

		private static Map<String, Object> evaluationEvidence(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			Map<String, Object> evidence = map(payload.get("evidence"));
			Map<String, Object> report = map(evidence.get("report"));
			copy(view, payload, "available");
			copyReportSummary(view, report);
			view.put("evidenceKeys", new ArrayList<>(evidence.keySet()));
			if (verbose) {
				view.put("evidence", evidence);
			}
			return view;
		}

		private static void copyReportSummary(Map<String, Object> view, Map<String, Object> report) {
			if (report.containsKey("status")) {
				view.put("reportStatus", report.get("status"));
			}
			if (report.containsKey("scenarioId")) {
				view.put("reportScenarioId", report.get("scenarioId"));
			}
			if (report.containsKey("message")) {
				view.put("reportMessage", report.get("message"));
			}
			if (report.containsKey("plannerTurns")) {
				view.put("plannerTurns", report.get("plannerTurns"));
			}
			if (report.containsKey("elapsedTicks")) {
				view.put("elapsedTicks", report.get("elapsedTicks"));
			}
			if (report.containsKey("evidenceReviewRequired")) {
				view.put("evidenceReviewRequired", report.get("evidenceReviewRequired"));
			}
		}

		private static Map<String, Object> worldsList(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "sessionState");
			List<Map<String, Object>> worlds = maps(payload.get("worlds"));
			view.put("worldCount", worlds.size());
			view.put("worlds", filterItems(worlds, verbose,
				List.of("worldId", "name", "displayName", "gameMode", "selectable", "lastPlayed"),
				List.of("immediatelyLoadable", "locked", "unavailable", "experimental", "details", "version")
			));
			return view;
		}

		private static Map<String, Object> serversList(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "sessionState");
			List<Map<String, Object>> servers = maps(payload.get("servers"));
			view.put("serverCount", servers.size());
			view.put("servers", filterItems(servers, verbose,
				List.of("serverId", "name", "address", "serverType", "index"),
				List.of("local", "realm", "resourcePackPolicy")
			));
			return view;
		}

		private static Map<String, Object> worldSnapshot(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "worldLoaded", "center", "radius");
			List<Map<String, Object>> blocks = maps(payload.get("blocks"));
			view.put("blockCount", blocks.size());
			view.put("blocks", verbose ? blocks : filterItems(blocks, false,
				List.of("pos", "id", "chunk"),
				List.of("state")
			));
			return view;
		}

		private static Map<String, Object> highlightsList(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			List<Map<String, Object>> highlights = maps(payload.get("highlights"));
			view.put("highlightCount", highlights.size());
			view.put("highlights", highlights);
			if (verbose && payload.containsKey("available")) {
				view.put("available", payload.get("available"));
			}
			return view;
		}

		private static String normalizeTaskValue(String value) {
			return value == null ? null : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
		}

		private static List<Map<String, Object>> filterItems(
			List<Map<String, Object>> items,
			boolean verbose,
			List<String> baseFields,
			List<String> verboseFields
		) {
			List<Map<String, Object>> filtered = new ArrayList<>(items.size());
			for (Map<String, Object> item : items) {
				LinkedHashMap<String, Object> current = new LinkedHashMap<>();
				copy(current, item, baseFields.toArray(String[]::new));
				if (verbose) {
					copy(current, item, verboseFields.toArray(String[]::new));
				}
				filtered.add(current);
			}
			return filtered;
		}

		private static void copy(Map<String, Object> target, Map<String, Object> source, String... keys) {
			for (String key : keys) {
				if (source.containsKey(key)) {
					target.put(key, source.get(key));
				}
			}
		}

		private static List<Map<String, Object>> maps(Object value) {
			if (!(value instanceof List<?> raw)) {
				return List.of();
			}
			List<Map<String, Object>> maps = new ArrayList<>(raw.size());
			for (Object item : raw) {
				if (item instanceof Map<?, ?> map) {
					maps.add(castMap(map));
				}
			}
			return maps;
		}

		private static List<Object> values(Object value) {
			if (value instanceof List<?> list) {
				return List.copyOf(list);
			}
			return List.of();
		}

		private static Map<String, Object> map(Object value) {
			if (value instanceof Map<?, ?> map) {
				return castMap(map);
			}
			return Map.of();
		}
	}

	private static Long validatedDurationMs(Integer durationSeconds, String commandPath) {
		if (durationSeconds == null) {
			return null;
		}
		if (durationSeconds < 1 || durationSeconds > 86_400) {
			throw new CliUsageException(commandPath, "invalid_arguments", "duration-seconds must be between 1 and 86400");
		}
		return durationSeconds * 1000L;
	}

	private static String validatedColor(String color, String commandPath) {
		if (color == null || color.isBlank()) {
			return null;
		}
		String normalized = color.startsWith("#") ? color.substring(1) : color;
		if (!(normalized.length() == 6 || normalized.length() == 8) || !normalized.matches("[0-9a-fA-F]+")) {
			throw new CliUsageException(commandPath, "invalid_arguments", "color must be a 6 or 8 digit hex value");
		}
		return color;
	}

	private static Map<String, Object> normalizeMap(Map<String, Object> source) {
		LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
		source.entrySet().stream()
			.sorted(entryComparator(source))
			.forEach(entry -> normalized.put(entry.getKey(), normalizeValue(entry.getValue())));
		return normalized;
	}

	private static Comparator<Map.Entry<String, Object>> entryComparator(Map<String, Object> source) {
		if (source instanceof LinkedHashMap<?, ?>) {
			return (left, right) -> 0;
		}
		return Comparator.comparing(Map.Entry::getKey);
	}

	private static Object normalizeValue(Object value) {
		if (value instanceof Map<?, ?> map) {
			return normalizeMap(castMap(map));
		}
		if (value instanceof List<?> list) {
			List<Object> normalized = new ArrayList<>(list.size());
			for (Object item : list) {
				normalized.add(normalizeValue(item));
			}
			return normalized;
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Map<?, ?> map) {
		Map<String, Object> converted = map instanceof LinkedHashMap<?, ?> ? new LinkedHashMap<>() : new TreeMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			converted.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return (Map<String, Object>) converted;
	}

	private static boolean isScalar(Object value) {
		return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
	}

	private static String scalarText(Object value) {
		if (value == null) {
			return "none";
		}
		return String.valueOf(value).replace("\n", "\\n");
	}

	private static String singularize(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		if ("matches".equals(lower)) {
			return value.substring(0, value.length() - 2);
		}
		if (lower.endsWith("ies") && value.length() > 3) {
			return value.substring(0, value.length() - 3) + "y";
		}
		if (lower.endsWith("s") && value.length() > 1) {
			return value.substring(0, value.length() - 1);
		}
		return value;
	}

	private static int transportExitCode(String code) {
		return switch (code) {
			case "minecraft_unavailable", "bridge_io_error", "bridge_interrupted" -> 3;
			default -> 4;
		};
	}

	private static String parsedCommandPath(ParseResult parseResult) {
		if (parseResult == null) {
			return "airicraft";
		}
		List<String> names = new ArrayList<>();
		for (CommandLine commandLine : parseResult.asCommandLineList()) {
			if (!"airicraft".equals(commandLine.getCommandName())) {
				names.add(commandLine.getCommandName());
			}
		}
		return names.isEmpty() ? "airicraft" : String.join(" ", names);
	}

	private static String commandPathFromArgs(CommandLine root, String[] args) {
		List<String> names = new ArrayList<>();
		CommandLine current = root;
		for (String arg : args) {
			if (arg.startsWith("-")) {
				break;
			}
			CommandLine next = current.getSubcommands().get(arg);
			if (next == null) {
				break;
			}
			names.add(arg);
			current = next;
		}
		return names.isEmpty() ? "airicraft" : String.join(" ", names);
	}

	private static CommandLine rootCommandLine(CommandLine commandLine) {
		CommandLine current = commandLine;
		while (current.getParent() != null) {
			current = current.getParent();
		}
		return current;
	}

	private static List<String> append(List<String> values, String value) {
		List<String> copy = new ArrayList<>(values);
		copy.add(value);
		return copy;
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
