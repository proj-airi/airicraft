package ai.moeru.airicraft.agent.work;

import ai.moeru.airicraft.agent.actions.*;
import ai.moeru.airicraft.agent.job.*;
import ai.moeru.airicraft.agent.tasks.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WorkProjection {
	private WorkProjection() { }
	public static List<WorkSnapshot> project(ActiveJob job, TaskExecutionSnapshot primitive,
		List<ActionGraphExecutionView> graphs, List<SmeltingProcessSnapshot> processes, boolean held, String holdId, String interruptedJobId, String interruptedGraphId, long tick) {
		var result = new ArrayList<WorkSnapshot>();
		String parent = "";
		for (var view : graphs) {
			var graph = view.execution();
			var handle = WorkHandle.of(WorkHandle.Kind.GRAPH, graph.executionId());
			boolean foreground = view.residency() == ActionGraphResidency.FOREGROUND;
			WorkSnapshot.State state = switch (graph.state()) {
				case SUCCEEDED -> WorkSnapshot.State.SUCCEEDED;
				case FAILED -> WorkSnapshot.State.FAILED;
				case CANCELLED -> WorkSnapshot.State.CANCELLED;
				case READY, RESOLVING, REPLANNING, IDLE -> WorkSnapshot.State.QUEUED;
				case BLOCKED, WATCHING -> WorkSnapshot.State.WAITING;
				default -> WorkSnapshot.State.RUNNING;
			};
			if (held && graph.executionId().equals(interruptedGraphId) && !state.terminal()) state = WorkSnapshot.State.PAUSED;
			result.add(new WorkSnapshot(handle, "", state, "resource goal", graph.state().name(), foreground, view.updatedTick(),
				Map.of("goal", Objects.toString(graph.goal(), ""), "failureCode", graph.failureCode(), "message", graph.message(),
					"activeTaskId", graph.activeTaskId(), "holdId", Objects.toString(holdId, ""))));
			if (job != null && !graph.activeTaskId().isBlank()
				&& (graph.activeTaskId().startsWith(job.jobId()) || primitive != null && graph.activeTaskId().equals(primitive.taskId()))) parent = handle.id();
		}
		if (job != null && job.status() != ActiveJobStatus.IDLE) {
			WorkSnapshot.State state = switch (job.status()) {
				case QUEUED, IDLE -> WorkSnapshot.State.QUEUED;
				case RUNNING -> WorkSnapshot.State.RUNNING;
				case BLOCKED -> WorkSnapshot.State.WAITING;
				case COMPLETED -> WorkSnapshot.State.SUCCEEDED;
				case FAILED -> WorkSnapshot.State.FAILED;
				case CANCELLED -> WorkSnapshot.State.CANCELLED;
			};
			if (held && job.jobId().equals(interruptedJobId) && !state.terminal()) state = WorkSnapshot.State.PAUSED;
			var details = new java.util.LinkedHashMap<String, Object>(Map.of(
				"blockedReason", Objects.toString(job.blockedReason(), ""), "failure", Objects.toString(job.lastError(), ""),
				"holdId", Objects.toString(holdId, ""), "request", requestedWork(job), "message", primitiveMessage(job, primitive, state)));
			// Hunting reports itemized inventory gains; the resource-job counter does not measure pickups.
			if (job.type() != ActiveJobType.ATTACK_ENTITY) details.put("collected", job.collectedCount());
			result.add(new WorkSnapshot(WorkHandle.of(WorkHandle.Kind.JOB, job.jobId()), parent, state, job.type().name(),
				primitive == null || state.terminal() ? job.status().name() : primitive.state().name(), !state.terminal(), job.updatedTick(), details));
		}
		for (var process : processes) {
			result.add(new WorkSnapshot(WorkHandle.of(WorkHandle.Kind.SMELTING, process.processId()), "",
				WorkSnapshot.State.WAITING, "furnace: " + process.outputItemId(), process.outputReady() ? "CHECK_OUTPUT" : "COOKING", false, tick,
				Map.of("station", process.stationKey(), "outputItemId", Objects.toString(process.outputItemId(), ""), "outputKnown", process.outputItemId() != null, "expectedCount", process.expectedOutputCount(),
					"needsCollection", true, "readiness", "May be estimated; inspect slots and verify collection.")));
		}
		return List.copyOf(result);
	}

	private static String primitiveMessage(ActiveJob job, TaskExecutionSnapshot primitive, WorkSnapshot.State state) {
		if (primitive == null) return "";
		if (state.terminal() && (!Objects.equals(job.jobId(), primitive.taskId())
			|| !List.of(TaskExecutionState.COMPLETED, TaskExecutionState.FAILED, TaskExecutionState.CANCELLED).contains(primitive.state()))) return "";
		return Objects.toString(primitive.lastPathEvent(), "");
	}

	private static Object requestedWork(ActiveJob job) {
		Object request = switch (job.type()) {
			case FOLLOW_PLAYER, NAVIGATE_TO, MINE_BLOCKS, ENSURE_BLOCKS_IN_INVENTORY -> job.directGoal();
			case COLLECT_RESOURCE -> job.taskSpec();
			case CRAFT_RECIPE -> job.craftRecipe();
			case DROP_ITEMS -> job.dropItems();
			case SMELT_ITEMS -> job.smeltItems();
			case COLLECT_SMELTED_ITEMS -> job.collectSmeltedItems();
			case ATTACK_ENTITY, USE_ENTITY -> job.entityInteraction();
			case PLACE_BLOCK -> job.blockPlacement();
			case USE_BLOCK -> job.blockUse();
			case BREAK_BLOCKS -> job.blockBreak();
			case TEND_CROPS -> job.cropTending();
			case LURE_ENTITIES -> job.lureEntities();
			case FIGHT_ENDER_DRAGON -> Map.of("target", "minecraft:ender_dragon");
			case COLLECT_OBSIDIAN -> job.collectObsidian();
			case LOCATE_STRONGHOLD -> job.locateStronghold();
			case EXPLORE_FOR -> job.exploreFor();
			case COLLECT_BLAZE_RODS -> job.collectBlazeRods();
			case RETURN_TO_SURFACE -> job.returnToSurface();
			case ASK_USER -> job.askPrompt();
			case IDLE -> null;
		};
		return Objects.requireNonNullElse(request, Map.of("known", false));
	}
}
