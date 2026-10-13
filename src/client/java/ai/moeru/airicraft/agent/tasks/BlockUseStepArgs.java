package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;

import java.util.List;

public record BlockUseStepArgs(
	String itemId,
	List<Target> targets,
	int holdTicks
) {
	public static final int MAX_TARGETS = BlockBreakStepArgs.MAX_TARGETS;
	/** Longest repeat-use hold, in ticks (60 s), for blocks such as a Create hand crank that act only while clicked. */
	public static final int MAX_HOLD_TICKS = 1200;

	public BlockUseStepArgs(String itemId, List<Target> targets) {
		this(itemId, targets, 0);
	}

	public BlockUseStepArgs(String itemId, GoalPosition targetPosition, String facePreference, List<String> expectedSupportBlockIds, String expectedTargetMaterial, int holdTicks) {
		this(itemId, List.of(new Target(targetPosition, facePreference, expectedSupportBlockIds, expectedTargetMaterial)), holdTicks);
	}

	public BlockUseStepArgs(String itemId, GoalPosition targetPosition, String facePreference, List<String> expectedSupportBlockIds, String expectedTargetMaterial) {
		this(itemId, List.of(new Target(targetPosition, facePreference, expectedSupportBlockIds, expectedTargetMaterial)));
	}

	public BlockUseStepArgs {
		itemId = normalizeOptional(itemId);
		if (holdTicks < 0 || holdTicks > MAX_HOLD_TICKS) {
			throw new IllegalArgumentException("holdTicks must be 0.." + MAX_HOLD_TICKS);
		}
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

	public List<String> expectedSupportBlockIds() {
		return targets.getFirst().expectedSupportBlockIds();
	}

	public String expectedTargetMaterial() {
		return targets.getFirst().expectedTargetMaterial();
	}

	public record Target(
		GoalPosition targetPosition,
		String facePreference,
		List<String> expectedSupportBlockIds,
		String expectedTargetMaterial
	) {
		public Target {
			targetPosition = targetPosition == null
				? null
				: new GoalPosition(targetPosition.x(), targetPosition.y(), targetPosition.z(), true);
			if (targetPosition == null) {
				throw new IllegalArgumentException("targetPosition must not be null");
			}
			facePreference = normalizeOptional(facePreference);
			expectedSupportBlockIds = expectedSupportBlockIds == null
				? List.of()
				: expectedSupportBlockIds.stream()
					.map(BlockUseStepArgs::normalizeOptional)
					.filter(value -> value != null)
					.toList();
			expectedTargetMaterial = normalizeOptional(expectedTargetMaterial);
		}
	}

	private static String normalizeOptional(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
