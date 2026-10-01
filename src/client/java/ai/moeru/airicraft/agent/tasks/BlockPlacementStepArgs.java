package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;

import java.util.List;

public record BlockPlacementStepArgs(
	String itemId,
	List<Target> targets,
	ai.moeru.airicraft.blueprint.ConstructionEscape.Bounds escapeBounds
) {
	public BlockPlacementStepArgs(String itemId,List<Target> targets) { this(itemId,targets,null); }

	public static final int MAX_TARGETS = BlockBreakStepArgs.MAX_TARGETS;

	public BlockPlacementStepArgs(String itemId, GoalPosition targetPosition, String facePreference, String requiredTargetMaterial) {
		this(itemId, List.of(new Target(targetPosition, facePreference, requiredTargetMaterial)));
	}

	public BlockPlacementStepArgs {
		itemId = normalizeRequired(itemId, "itemId");
		targets = targets == null
			? List.of()
			: targets.stream()
				.map(target -> {
					if (target == null) {
						throw new IllegalArgumentException("targets must not contain null");
					}
					return target;
				})
				.toList();
		if (targets.isEmpty()) {
			throw new IllegalArgumentException("targets must not be empty");
		}
		if (targets.size() > MAX_TARGETS) {
			throw new IllegalArgumentException("targets must contain at most " + MAX_TARGETS + " entries");
		}
	}

	public GoalPosition targetPosition() {
		return targets.getFirst().targetPosition();
	}

	public String facePreference() {
		return targets.getFirst().facePreference();
	}

	public String requiredTargetMaterial() {
		return targets.getFirst().requiredTargetMaterial();
	}

	public record Target(
		GoalPosition targetPosition,
		String facePreference,
		String requiredTargetMaterial,
		String expectedState
	) {
		public Target(GoalPosition position, String face, String material) {
			this(position, face, material, null);
		}
		public Target {
			targetPosition = targetPosition == null
				? null
				: new GoalPosition(targetPosition.x(), targetPosition.y(), targetPosition.z(), true);
			if (targetPosition == null) {
				throw new IllegalArgumentException("targetPosition must not be null");
			}
			facePreference = normalizeOptional(facePreference);
			requiredTargetMaterial = normalizeOptional(requiredTargetMaterial);
			expectedState = normalizeOptional(expectedState);
		}
	}

	private static String normalizeRequired(String value, String fieldName) {
		String normalized = normalizeOptional(value);
		if (normalized == null) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return normalized;
	}

	private static String normalizeOptional(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
