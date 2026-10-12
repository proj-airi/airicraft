package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.session.SessionMode;

public record PlannerAmbientContext(
	SessionMode sessionMode,
	String primaryInteractionPlayer,
	String activeGoalDescription,
	String activeMissionDescription,
	String missionEvidenceDescription
) {
	public static PlannerAmbientContext fromRequest(PlannerRequest request) {
		return new PlannerAmbientContext(
			request.sessionMode(),
			normalize(request.primaryInteractionPlayer()),
			describeGoal(request.activeGoal()),
			describeMission(request.activeTask(), request.missionExecution()),
			describeMissionEvidence(request.missionExecution())
		);
	}

	private static String normalize(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static String describeGoal(GoalSnapshot goal) {
		if (goal == null || goal.type() == null) {
			return null;
		}
		return switch (goal.type()) {
			case FOLLOW_PLAYER -> goal.targetPlayer() == null || goal.targetPlayer().isBlank()
				? "Follow the current player."
				: "Follow " + goal.targetPlayer() + ".";
			case NAVIGATE_TO -> goal.position() == null
				? "Navigate to the requested position."
				: "Navigate to "
					+ goal.position().x() + ", "
					+ goal.position().y() + ", "
					+ goal.position().z()
					+ (goal.position().exactY() ? " with exact Y." : ".");
			case MINE_BLOCKS -> goal.mineSpec() == null
				? "Mine the requested blocks."
				: "Mine "
					+ goal.mineSpec().quantity()
					+ " of "
					+ String.join(", ", goal.mineSpec().blockIds())
					+ ".";
		};
	}

	private static String describeMission(TaskSnapshot task, MissionExecutionSnapshot missionExecution) {
		if (task == null || task.mission() == null) {
			return null;
		}
		StringBuilder description = new StringBuilder()
			.append("Job state=").append(task.state()).append(" ")
			.append(task.mission().missionType().name())
			.append(": ")
			.append(task.mission().goalText() == null ? "no goal text" : task.mission().goalText());
		if (task.activeStepId() != null && task.activeStepKind() != null) {
			description.append(". Job step ").append(task.activeStepId()).append(" (").append(task.activeStepKind().name()).append(")");
		}
		if (missionExecution != null && missionExecution.lastStepResult() != null && missionExecution.lastStepResult().status() != null) {
			description.append(". Last step result ").append(missionExecution.lastStepResult().status().name());
		}
		if (task.progress() != null) {
			description.append(". Job progress: collected=")
				.append(task.progress().collected())
				.append(", remaining=")
				.append(task.progress().remaining());
		}
		description.append(".");
		return description.toString();
	}

	private static String describeMissionEvidence(MissionExecutionSnapshot missionExecution) {
		if (missionExecution == null) {
			return null;
		}
		StringBuilder description = new StringBuilder();
		if (missionExecution.ledger() != null) {
			description.append("Compatibility ledger snapshot: ").append(renderLedger(missionExecution.ledger()));
		}
		if (missionExecution.evidence() != null) {
			if (description.length() > 0) {
				description.append(' ');
			}
			description.append("World evidence snapshot: ").append(renderEvidence(missionExecution.evidence()));
		}
		if (missionExecution.lastStepResult() != null && missionExecution.lastStepResult().status() != null
			&& missionExecution.lastStepResult().status() != ai.moeru.airicraft.agent.tasks.StepExecutionStatus.IDLE) {
			if (description.length() > 0) {
				description.append(' ');
			}
			description.append("Last step result: ").append(renderStepResult(missionExecution.lastStepResult()));
		}
		String history = renderHistory(missionExecution.ledger());
		if (history != null) {
			if (description.length() > 0) {
				description.append(' ');
			}
			description.append(history);
		}
		return description.length() == 0 ? null : description.toString();
	}

	private static String renderLedger(ai.moeru.airicraft.agent.tasks.TaskLedger ledger) {
		StringBuilder builder = new StringBuilder("{");
		builder.append("missionId=").append(ledger.missionId());
		builder.append(", missionType=").append(ledger.missionType());
		builder.append(", activeStepId=").append(ledger.activeStepId());
		if (ledger.replanReason() != null && !ledger.replanReason().isBlank()) {
			builder.append(", replanReason=").append(ledger.replanReason());
		}
		if (ledger.plannerNotes() != null && !ledger.plannerNotes().isBlank()) {
			builder.append(", plannerNotes=").append(ledger.plannerNotes());
		}
		builder.append(", steps=[");
		for (int index = 0; index < ledger.steps().size(); index++) {
			if (index > 0) {
				builder.append(", ");
			}
			ai.moeru.airicraft.agent.tasks.LedgerStep step = ledger.steps().get(index);
			builder.append("{id=").append(step.id())
				.append(", kind=").append(step.kind())
				.append(", status=").append(step.status())
				.append(", dependsOn=").append(step.dependsOn())
				.append(", expectedEvidence=").append(step.expectedEvidence())
				.append(", retryBudget=").append(step.retryBudget());
			String argsSummary = renderStepArgs(step);
			if (argsSummary != null) {
				builder.append(", args=").append(argsSummary);
			}
			if (step.notes() != null && !step.notes().isBlank()) {
				builder.append(", notes=").append(step.notes());
			}
			builder.append('}');
		}
		builder.append("], completionCriteria=").append(ledger.completionCriteria()).append('}');
		return builder.toString();
	}

	private static String renderStepArgs(ai.moeru.airicraft.agent.tasks.LedgerStep step) {
		if (step.args() == null) {
			return null;
		}
		return switch (step.kind()) {
			case COLLECT_RESOURCE -> String.valueOf(step.args().collectResource());
			case CRAFT_RECIPE -> String.valueOf(step.args().craftRecipe());
			case ASK_USER -> String.valueOf(step.args().askUser());
			case FINISH -> String.valueOf(step.args().finish());
			case NAVIGATE_TO_POSITION -> String.valueOf(step.args().navigateToPosition());
			case NAVIGATE_TO_BLOCK_KIND -> String.valueOf(step.args().navigateToBlockKind());
			case MINE_BLOCKS -> String.valueOf(step.args().mineBlocks());
			case OPEN_CONTAINER -> String.valueOf(step.args().openContainer());
			case TRANSFER_ITEMS -> String.valueOf(step.args().transferItems());
			case PLACE_BLOCK -> String.valueOf(step.args().placeBlock());
			case USE_BLOCK -> "use_block";
			case BREAK_BLOCKS -> String.valueOf(step.args().blockBreak());
			case DROP_ITEMS -> String.valueOf(step.args().dropItems());
			case SMELT_ITEMS, COLLECT_SMELTED_ITEMS -> "smelting";
			case ATTACK_ENTITY, USE_ENTITY -> String.valueOf(step.args().entityInteraction());
		};
	}

	private static String renderEvidence(ai.moeru.airicraft.agent.tasks.WorldEvidence evidence) {
		var parts = new java.util.ArrayList<String>();
		parts.add((evidence.dimension() == null ? "unknown dimension" : PlannerStateText.item(evidence.dimension()))
			+ " at " + evidence.x() + "," + evidence.y() + "," + evidence.z());
		if (evidence.equippedItemId() != null) parts.add("holding " + PlannerStateText.item(evidence.equippedItemId()));
		// Zero counts for every tracked resource kind are noise; list only what is carried.
		String carried = counts(evidence.inventoryCounts().entrySet().stream()
			.filter(entry -> entry.getValue() != null && entry.getValue() != 0)
			.map(entry -> java.util.Map.entry(entry.getKey().name().toLowerCase(java.util.Locale.ROOT), entry.getValue())).toList());
		parts.add("tracked resources " + (carried.isEmpty() ? "none" : carried));
		if (!evidence.itemCounts().isEmpty()) parts.add("items " + counts(evidence.itemCounts().entrySet().stream().toList()));
		if (!evidence.nearbyBlocks().isEmpty()) parts.add("nearby blocks " + counts(limitNearbyBlocks(evidence.nearbyBlocks()).entrySet().stream().toList()));
		if (!evidence.availableCrafts().isEmpty()) parts.add(renderAvailableCrafts(evidence.availableCrafts()));
		return String.join("; ", parts) + ".";
	}

	private static String counts(java.util.List<? extends java.util.Map.Entry<String, Integer>> entries) {
		return entries.stream().map(entry -> entry.getValue() + " " + PlannerStateText.item(entry.getKey()))
			.collect(java.util.stream.Collectors.joining(", "));
	}

	private static String renderAvailableCrafts(java.util.List<ai.moeru.airicraft.agent.tasks.CraftingOpportunity> opportunities) {
		return opportunities.stream()
			.limit(12)
			.map(ai.moeru.airicraft.agent.tasks.CraftingOpportunity::compactDescription)
			.collect(java.util.stream.Collectors.joining("; ", "Available 2x2 crafts: ", ""));
	}

	private static java.util.Map<String, Integer> limitNearbyBlocks(java.util.Map<String, Integer> nearbyBlocks) {
		return nearbyBlocks.entrySet().stream()
			.sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(java.util.Map.Entry.comparingByKey()))
			.limit(12)
			.collect(java.util.stream.Collectors.toMap(
				java.util.Map.Entry::getKey,
				java.util.Map.Entry::getValue,
				(left, right) -> left,
				java.util.LinkedHashMap::new
			));
	}

	private static String renderStepResult(ai.moeru.airicraft.agent.tasks.StepExecutionResult result) {
		return "{stepId=" + result.stepId()
			+ ", status=" + result.status()
			+ ", failureReason=" + result.failureReason()
			+ ", evidenceDelta=" + result.evidenceDelta()
			+ ", terminalFacts=" + result.terminalFacts()
			+ "}";
	}

	private static String renderHistory(ai.moeru.airicraft.agent.tasks.TaskLedger ledger) {
		if (ledger == null) {
			return null;
		}
		java.util.List<String> completed = ledger.steps().stream()
			.filter(step -> step.status() == ai.moeru.airicraft.agent.tasks.LedgerStepStatus.COMPLETED)
			.map(ai.moeru.airicraft.agent.tasks.LedgerStep::id)
			.toList();
		java.util.List<String> failed = ledger.steps().stream()
			.filter(step -> step.status() == ai.moeru.airicraft.agent.tasks.LedgerStepStatus.FAILED)
			.map(ai.moeru.airicraft.agent.tasks.LedgerStep::id)
			.toList();
		java.util.List<String> cancelled = ledger.steps().stream()
			.filter(step -> step.status() == ai.moeru.airicraft.agent.tasks.LedgerStepStatus.CANCELLED)
			.map(ai.moeru.airicraft.agent.tasks.LedgerStep::id)
			.toList();
		return "Compatibility history summary: completedSteps=" + completed + ", failedSteps=" + failed + ", cancelledSteps=" + cancelled + ".";
	}
}
