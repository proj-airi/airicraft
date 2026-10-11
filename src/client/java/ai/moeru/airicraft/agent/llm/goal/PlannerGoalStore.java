package ai.moeru.airicraft.agent.llm.goal;

import ai.moeru.airicraft.agent.work.ReadableIds;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Supplier;

/** World-owned intent. Observations and work outcomes are not planning notes. */
public final class PlannerGoalStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Gson CONTEXT_JSON = new GsonBuilder().disableHtmlEscaping().create();
	private final Supplier<Path> worldDirectory;
	private Path loadedWorld;
	private volatile Goal goal;
	private volatile String error;
	private List<Goal> previousGoals = List.of();
	public PlannerGoalStore(Supplier<Path> worldDirectory) { this.worldDirectory = worldDirectory; }

	public void refreshWorld() {
		Path world = worldDirectory.get();
		if (Objects.equals(world, loadedWorld)) return;
		loadedWorld = world; goal = null; error = null; previousGoals = List.of();
		if (world == null) return;
		try {
			if (!Files.exists(file())) return;
			Document doc = GSON.fromJson(Files.readString(file()), Document.class);
			if (doc == null || doc.version() < 1 || doc.version() > 2 || doc.goal() == null)
				throw new IllegalArgumentException("invalid planner goal document");
			goal = doc.goal();
			previousGoals = doc.previousGoals() == null ? List.of() : List.copyOf(doc.previousGoals());
			if (doc.version() == 1) {
				// Preserve the original and every status verbatim; migration never starts a goal.
				Files.copy(file(), file().resolveSibling("planner-goal.v1.backup.json"), StandardCopyOption.REPLACE_EXISTING);
				save(goal);
			}
		} catch (IOException | RuntimeException exception) { error = "planner_goal_load_failed: " + exception.getMessage(); }
	}
	public Goal snapshot() { return goal; }
	public boolean active() { return goal != null && goal.status() == Status.ACTIVE; }
	public boolean blocked() { return goal != null && goal.status() == Status.BLOCKED; }
	public boolean unresolved() { return active() || blocked(); }
	public boolean relevantToBlock(String eventType) {
		return blocked() && goal.blocker().reconsiderEvents().contains(eventType);
	}
	public String context() { return error != null ? error : goal == null ? "No planner goal." : CONTEXT_JSON.toJson(goal); }
	public Goal set(String objective) throws IOException { return set(objective, "", ""); }
	public Goal set(String objective, String constraints, String criteria) throws IOException {
		requireWorld();
		if (unresolved()) throw new IllegalArgumentException("goal_active: change or finish the current goal first");
		return replace(new Goal(nextGoalId(), objective, Status.ACTIVE, "", constraints, criteria, null, Map.of()));
	}
	public Goal change(String id, String objective, String reason) throws IOException {
		requireCurrent(id);
		return replace(new Goal(nextGoalId(), objective, Status.ACTIVE, checked(reason, "reason"), goal.constraints(), goal.completionCriteria(), null, goal.decisions()));
	}
	public Goal finish(String id, Status status, String outcome) throws IOException {
		requireCurrent(id);
		if (status != Status.SUCCEEDED && status != Status.GIVEN_UP) throw new IllegalArgumentException("outcome must be success or give_up");
		return save(copy(status, checked(outcome, "outcome"), null, goal.decisions()));
	}
	public Goal block(String id, String reason, String evidence, String change, List<String> events) throws IOException {
		requireCurrent(id);
		return save(copy(Status.BLOCKED, goal.outcome(), new Blocker(reason, evidence, change, events), goal.decisions()));
	}
	public Goal resume(String id, String reason) throws IOException {
		requireCurrent(id);
		if (!blocked()) throw new IllegalArgumentException("goal_not_blocked");
		return save(copy(Status.ACTIVE, checked(reason, "reason"), null, goal.decisions()));
	}
	public Goal decide(String id, String name, String decision, String reason) throws IOException {
		requireCurrent(id);
		name = checked(name, "name");
		if (name.length() > 64) throw new IllegalArgumentException("decision name exceeds 64 characters");
		var notes = new LinkedHashMap<>(goal.decisions());
		if (!notes.containsKey(name) && notes.size() >= 16) throw new IllegalArgumentException("decision_limit: replace an existing named decision");
		Decision old = notes.get(name);
		notes.put(name, new Decision(checked(decision, "decision"), checked(reason, "reason"), old == null ? "" : old.reason()));
		return save(copy(goal.status(), goal.outcome(), goal.blocker(), notes));
	}
	/** Short ids the planner can copy back; saved goals keep their ids, so numbering continues after them. */
	private String nextGoalId() {
		var taken = new ArrayList<String>();
		for (Goal previous : previousGoals) taken.add(previous.id());
		if (goal != null) taken.add(goal.id());
		return ReadableIds.nextAfter("goal", taken);
	}
	private Goal copy(Status status, String outcome, Blocker blocker, Map<String,Decision> decisions) {
		return new Goal(goal.id(), goal.objective(), status, outcome, goal.constraints(), goal.completionCriteria(), blocker, decisions);
	}
	private Goal replace(Goal replacement) throws IOException {
		var previous = previousGoals;
		if (goal != null) {
			var history = new ArrayList<>(previousGoals); history.add(goal);
			previousGoals = List.copyOf(history.subList(Math.max(0, history.size()-32), history.size()));
		}
		try { return save(replacement); } catch (IOException e) { previousGoals = previous; throw e; }
	}
	private void requireWorld() {
		refreshWorld();
		if (loadedWorld == null) throw new IllegalStateException("planner_goal_requires_local_world_save");
		if (error != null) throw new IllegalStateException(error);
	}
	private void requireCurrent(String id) {
		requireWorld();
		if (!unresolved() || !goal.id().equals(id)) throw new IllegalArgumentException("stale_or_inactive_goal: inspect current planner goal");
	}
	private Path file() { return loadedWorld.resolve("airicraft/planner-goal.json"); }
	private Goal save(Goal replacement) throws IOException {
		Path file = file(); Files.createDirectories(file.getParent());
		Path temporary = Files.createTempFile(file.getParent(), "planner-goal-", ".tmp");
		try {
			Files.writeString(temporary, GSON.toJson(new Document(2, replacement, previousGoals)) + "\n");
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			goal = replacement; return replacement;
		} finally { Files.deleteIfExists(temporary); }
	}
	public static String checked(String value, String field) {
		if (value == null || value.isBlank() || value.length() > 2048) throw new IllegalArgumentException(field + " must be 1..2048 characters");
		return value.trim();
	}
	private static String optional(String value) {
		if (value == null) return "";
		if (value.length() > 2048) throw new IllegalArgumentException("text exceeds 2048 characters");
		return value;
	}
	public enum Status { ACTIVE, BLOCKED, SUCCEEDED, GIVEN_UP }
	public record Blocker(String reason, String evidence, String requiredChange, List<String> reconsiderEvents) {
		public Blocker {
			reason = checked(reason, "reason"); evidence = checked(evidence, "evidence"); requiredChange = checked(requiredChange, "requiredChange");
			reconsiderEvents = List.copyOf(reconsiderEvents);
			if (reconsiderEvents.size() > 8) throw new IllegalArgumentException("at most 8 reconsideration event types");
			for (String event : reconsiderEvents) checked(event, "event type");
		}
	}
	public record Decision(String decision, String reason, String previousReason) { }
	public record Goal(String id, String objective, Status status, String outcome, String constraints,
		String completionCriteria, Blocker blocker, Map<String,Decision> decisions) {
		public Goal(String id, String objective, Status status, String outcome) { this(id, objective, status, outcome, "", "", null, Map.of()); }
		public Goal {
			id = checked(id, "id"); objective = checked(objective, "objective"); Objects.requireNonNull(status);
			outcome = optional(outcome); constraints = optional(constraints); completionCriteria = optional(completionCriteria);
			if ((status == Status.SUCCEEDED || status == Status.GIVEN_UP) && outcome.isBlank()) throw new IllegalArgumentException("invalid outcome");
			if (status == Status.BLOCKED && blocker == null) throw new IllegalArgumentException("blocked goal requires evidence");
			decisions = decisions == null ? Map.of() : Map.copyOf(decisions);
			if (decisions.size() > 16) throw new IllegalArgumentException("too many decisions");
		}
	}
	private record Document(int version, Goal goal, List<Goal> previousGoals) { }
}
