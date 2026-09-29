package ai.moeru.airicraft.agent.llm;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorldReadLedger {
	public static final int DEFAULT_FRESHNESS_TOOL_CALLS = 10;

	private final int freshnessToolCalls;
	private final Map<BlockPos, Long> observedAtToolCall = new LinkedHashMap<>();
	private long toolCallIndex;

	public WorldReadLedger() {
		this(DEFAULT_FRESHNESS_TOOL_CALLS);
	}

	WorldReadLedger(int freshnessToolCalls) {
		this.freshnessToolCalls = Math.max(0, freshnessToolCalls);
	}

	public long advanceToolCall() {
		toolCallIndex++;
		return toolCallIndex;
	}

	public long currentToolCallIndex() {
		return toolCallIndex;
	}

	public void recordObserved(List<BlockPos> positions) {
		if (positions == null || positions.isEmpty()) {
			return;
		}
		for (BlockPos position : positions) {
			if (position != null) {
				observedAtToolCall.put(position.immutable(), toolCallIndex);
			}
		}
	}

	public boolean isFresh(BlockPos position) {
		return freshnessRemaining(position) >= 0;
	}

	public int freshnessRemaining(BlockPos position) {
		Long observedAt = position == null ? null : observedAtToolCall.get(position);
		if (observedAt == null) {
			return -1;
		}
		long age = toolCallIndex - observedAt;
		if (age > freshnessToolCalls) {
			return -1;
		}
		return (int) Math.max(0L, freshnessToolCalls - age);
	}

	public void clear() {
		observedAtToolCall.clear();
		toolCallIndex = 0L;
	}
}
