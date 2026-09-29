package ai.moeru.airicraft.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ClientTickPlayerActionsCapture {
	private String captureId;
	private boolean initialized;
	private Map<String, Boolean> previousActions = Map.of();
	private ClientTickPlayerActionsSnapshot.BreakProgress previousBreakProgress;

	ClientTickPlayerActionsSnapshot capture(Minecraft minecraft, String nextCaptureId) {
		if (!Objects.equals(captureId, nextCaptureId)) {
			captureId = nextCaptureId;
			initialized = false;
			previousActions = Map.of();
			previousBreakProgress = null;
		}

		Map<String, Boolean> currentActions = actions(minecraft);
		ClientTickPlayerActionEvents.EventBatch events = ClientTickPlayerActionEvents.take();
		Set<String> startedInteractions = events.startedActions();
		for (String action : startedInteractions) {
			currentActions.putIfAbsent(action, false);
		}
		ClientTickPlayerActionsSnapshot.BreakProgress breakProgress = events.breakProgress();
		if (breakProgress == null) {
			breakProgress = breakProgress(minecraft);
		}
		boolean breakStarted = initialized
			&& breakProgress != null
			&& (previousBreakProgress == null || !breakProgress.position().equals(previousBreakProgress.position()));
		if (breakProgress != null) {
			breakProgress = new ClientTickPlayerActionsSnapshot.BreakProgress(
				breakProgress.position(),
				breakProgress.progress(),
				breakProgress.stage(),
				breakStarted
			);
		}

		List<ClientTickPlayerActionsSnapshot.ActionState> actions = new ArrayList<>();
		for (Map.Entry<String, Boolean> entry : currentActions.entrySet()) {
			actions.add(new ClientTickPlayerActionsSnapshot.ActionState(
				entry.getKey(),
				entry.getValue(),
				startedInteractions.contains(entry.getKey())
					|| (entry.getKey().equals("attack") && breakStarted)
					|| (initialized && entry.getValue() && !previousActions.getOrDefault(entry.getKey(), false))
			));
		}

		initialized = true;
		previousActions = Map.copyOf(currentActions);
		previousBreakProgress = breakProgress;
		return new ClientTickPlayerActionsSnapshot(actions, breakProgress);
	}

	private static Map<String, Boolean> actions(Minecraft minecraft) {
		Map<String, Boolean> actions = new LinkedHashMap<>();
		if (minecraft == null || minecraft.options == null) {
			return actions;
		}
		add(actions, "attack", minecraft.options.keyAttack);
		add(actions, "use", minecraft.options.keyUse);
		add(actions, "pick_item", minecraft.options.keyPickItem);
		add(actions, "drop_item", minecraft.options.keyDrop);
		add(actions, "swap_hands", minecraft.options.keySwapOffhand);
		for (int index = 0; index < minecraft.options.keyHotbarSlots.length; index++) {
			add(actions, "hotbar_" + (index + 1), minecraft.options.keyHotbarSlots[index]);
		}
		return actions;
	}

	private static void add(Map<String, Boolean> actions, String action, KeyMapping keyMapping) {
		actions.put(action, keyMapping != null && keyMapping.isDown());
	}

	private static ClientTickPlayerActionsSnapshot.BreakProgress breakProgress(Minecraft minecraft) {
		if (minecraft == null || minecraft.gameMode == null) {
			return null;
		}
		MultiPlayerGameMode gameMode = minecraft.gameMode;
		BlockPos position = gameMode.destroyBlockPos;
		if (position == null) {
			return null;
		}
		float progress = gameMode.destroyProgress;
		if (!gameMode.isDestroying() && progress <= 0.0F) {
			return null;
		}
		int stage = Math.clamp((int) Math.floor(progress * 10.0F), 0, 9);
		return new ClientTickPlayerActionsSnapshot.BreakProgress(
			new ClientTickPlayerActionsSnapshot.Position(position.getX(), position.getY(), position.getZ()),
			progress,
			stage,
			false
		);
	}
}
