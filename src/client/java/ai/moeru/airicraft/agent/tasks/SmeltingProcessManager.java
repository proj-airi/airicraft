package ai.moeru.airicraft.agent.tasks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SmeltingProcessManager {
	private static final long CONFIRMATION_TTL_TICKS = 20L * 60L;
	private static final long ESTIMATED_READY_GRACE_TICKS = 40L;

	private final Map<SmeltingStationKey, TrackedProcess> processesByStation = new HashMap<>();
	private final Map<String, TrackedProcess> processesById = new HashMap<>();
	private final Map<String, Confirmation> confirmations = new HashMap<>();
	private final Map<String, SmeltingOption> optionsById = new HashMap<>();
	private final Map<String, SmeltingOption> processOptionsById = new HashMap<>();
	private final Map<String, SmeltingStationKey> confirmedCollectionStations = new HashMap<>();

	private final java.util.ArrayDeque<String> collectedProcesses = new java.util.ArrayDeque<>();
	public void markCollected(String processId) {
		if (cancel(processId)) {
			if (collectedProcesses.size() == 128) collectedProcesses.removeFirst();
			collectedProcesses.addLast(processId);
		}
	}
	public List<String> drainCollectedProcesses() {
		var result = List.copyOf(collectedProcesses);
		collectedProcesses.clear();
		return result;
	}

	public void registerOptions(List<SmeltingOption> options) {
		optionsById.clear();
		if (options == null) {
			return;
		}
		for (SmeltingOption option : options) {
			if (option != null) {
				optionsById.put(option.optionId(), option);
			}
		}
	}

	public List<SmeltingOption> registeredOptions() {
		return List.copyOf(optionsById.values());
	}

	public SmeltingOption registeredOption(String optionId) {
		if (optionId == null || optionId.isBlank()) {
			return null;
		}
		String normalizedOptionId = optionId.trim();
		SmeltingOption processOption = processOptionsById.get(normalizedOptionId);
		return processOption == null ? optionsById.get(normalizedOptionId) : processOption;
	}

	public SmeltingStationKey processStationKey(String processId) {
		if (processId == null || processId.isBlank()) {
			return null;
		}
		TrackedProcess process = processesById.get(processId.trim());
		return process == null ? null : process.stationKey();
	}

	public boolean processOutputReadyForCollection(String processId, SmeltingSlotSnapshot slots) {
		if (processId == null || processId.isBlank() || slots == null) {
			return false;
		}
		TrackedProcess process = processesById.get(processId.trim());
		if (process == null || slots.outputItemId() == null || slots.outputCount() <= 0) {
			return false;
		}
		if (process.expectedOutputItemId() != null && !Objects.equals(process.expectedOutputItemId(), slots.outputItemId())) {
			return false;
		}
		return slots.outputCount() >= Math.max(1, process.expectedOutputCount());
	}

	public String preferredCollectionProcessId() {
		return processesById.values().stream()
			.sorted(Comparator
				.comparing((TrackedProcess process) -> !process.outputReadyNotified())
				.thenComparingLong(TrackedProcess::estimatedReadyTick)
				.thenComparingLong(TrackedProcess::startedTick)
				.thenComparing(TrackedProcess::processId))
			.map(TrackedProcess::processId)
			.findFirst()
			.orElse(null);
	}

	public boolean hasTrackedProcesses() {
		return !processesById.isEmpty();
	}

	/** Preserve active furnace interactions and stations with no block position to reopen. */
	public boolean requiresOpenScreen(String dimensionId, int syncId, WorldTaskType activeTaskType) {
		return activeTaskType == WorldTaskType.SMELT_ITEMS
			|| activeTaskType == WorldTaskType.COLLECT_SMELTED_ITEMS
			|| processesByStation.containsKey(new SmeltingStationKey(dimensionId + "#open_screen", syncId, 0, 0));
	}

	public List<SmeltingProcessSnapshot> processSnapshots() {
		return processesById.values().stream()
			.sorted(Comparator.comparing(TrackedProcess::processId))
			.map(process -> new SmeltingProcessSnapshot(
				process.processId(),
				process.optionId(),
				process.stationKey(),
				process.expectedOutputItemId(),
				process.expectedOutputCount(),
				process.outputReadyNotified()
			))
			.toList();
	}

	public List<SmeltingStationKey> trackedStationKeys() {
		return List.copyOf(processesByStation.keySet());
	}

	public void updateProcessFingerprint(String optionId, SmeltingStationKey stationKey, SmeltingSlotSnapshot slots, long tick) {
		if (optionId == null || optionId.isBlank() || stationKey == null || slots == null) {
			return;
		}
		TrackedProcess process = processesByStation.get(stationKey);
		if (process == null || !Objects.equals(process.optionId(), optionId.trim())) {
			return;
		}
		long estimatedReadyTick = estimatedReadyTick(process, tick);
		TrackedProcess updated = new TrackedProcess(
			process.processId(),
			process.stationKey(),
			slots.fingerprint(),
			process.optionId(),
			process.expectedOutputItemId(),
			process.expectedOutputCount(),
			process.inputQuantity(),
			process.startedTick(),
			estimatedReadyTick,
			process.outputReadyNotified()
		);
		processesByStation.put(stationKey, updated);
		processesById.put(process.processId(), updated);
	}

	public boolean relocatePlacementProcess(String optionId, SmeltingStationKey oldKey, SmeltingStationKey newKey, double distance) {
		if (optionId == null || optionId.isBlank() || oldKey == null || newKey == null || Objects.equals(oldKey, newKey)) {
			return false;
		}
		SmeltingOption option = registeredOption(optionId);
		if (option == null || option.stationCandidate().source() != SmeltingStationSource.PLACE_FROM_INVENTORY) {
			return false;
		}
		SmeltingStationCandidate candidate = new SmeltingStationCandidate(
			SmeltingStationSource.PLACE_FROM_INVENTORY,
			SmeltingStationState.EMPTY,
			option.stationCandidate().kind(),
			newKey,
			distance,
			false
		);
		SmeltingStationObservation observation = new SmeltingStationObservation(
			newKey,
			option.stationObservation().kind(),
			new SmeltingSlotSnapshot(null, 0, null, 0, null, 0, 0, option.cookTimeTicks(), false),
			false,
			distance
		);
		SmeltingOption relocatedOption = new SmeltingOption(
			option.optionId(),
			option.inputItemId(),
			option.outputItemId(),
			option.outputCount(),
			option.maxInputQuantity(),
			option.cookTimeTicks(),
			candidate,
			observation
		);
		optionsById.put(option.optionId(), relocatedOption);
		processOptionsById.put(option.optionId(), relocatedOption);
		TrackedProcess process = processesByStation.remove(oldKey);
		if (process != null && Objects.equals(process.optionId(), option.optionId())) {
			TrackedProcess relocated = new TrackedProcess(
				process.processId(),
				newKey,
				observation.slots().fingerprint(),
				process.optionId(),
				process.expectedOutputItemId(),
				process.expectedOutputCount(),
				process.inputQuantity(),
				process.startedTick(),
				process.estimatedReadyTick(),
				process.outputReadyNotified()
			);
			processesByStation.put(newKey, relocated);
			processesById.put(process.processId(), relocated);
		}
		return true;
	}

	public SmeltingStationKey confirmationStationKey(String confirmationToken) {
		if (confirmationToken == null || confirmationToken.isBlank()) {
			return null;
		}
		Confirmation confirmation = confirmations.get(confirmationToken.trim());
		return confirmation == null ? null : confirmation.stationKey();
	}

	public SmeltingStationKey confirmedCollectionStationKey(String confirmationToken) {
		if (confirmationToken == null || confirmationToken.isBlank()) {
			return null;
		}
		return confirmedCollectionStations.get(confirmationToken.trim());
	}

	public SmeltingActionResult startRegisteredProcess(SmeltItemsStepArgs request, long tick) {
		Objects.requireNonNull(request, "request");
		SmeltingOption option = registeredOption(request.optionId());
		if (option == null) {
			return SmeltingActionResult.failed("option_not_found", "Unknown smelting optionId: " + request.optionId());
		}
		if (request.inputQuantity() > option.maxInputQuantity()) {
			return SmeltingActionResult.failed("insufficient_input", "Requested inputQuantity exceeds the registered option inventory count.");
		}
		return startProcess(request, option.stationObservation(), tick);
	}

	public SmeltingActionResult startProcess(SmeltItemsStepArgs request, SmeltingStationObservation observation, long tick) {
		Objects.requireNonNull(request, "request");
		SmeltingStationState state = classify(observation, tick);
		boolean unsafeStation = state == SmeltingStationState.OCCUPIED || state == SmeltingStationState.STALE;
		if (unsafeStation) {
			SmeltingActionResult confirmationFailure = consumeConfirmation(
				request.confirmationToken(),
				"smelt_items",
				request.optionId(),
				request.inputQuantity(),
				observation,
				tick
			);
			if (confirmationFailure != null) {
				return confirmationFailure;
			}
		}
		if (unsafeStation && request.confirmationToken() == null) {
			String token = createConfirmation("smelt_items", request.optionId(), request.inputQuantity(), observation, tick);
			return SmeltingActionResult.confirmationRequired(
				token,
				"confirmationRequired state=" + state.name() + " station=" + stationText(observation) + " slots=" + slotSummary(observation)
			);
		}
		String processId = "smelt-process-" + UUID.randomUUID();
		SmeltingOption option = registeredOption(request.optionId());
		int expectedOutputCount = option == null ? request.inputQuantity() : Math.max(1, option.outputCount()) * request.inputQuantity();
		if (option != null) {
			processOptionsById.put(option.optionId(), option);
		}
		TrackedProcess process = new TrackedProcess(
			processId,
			observation.key(),
			observation.slots().fingerprint(),
			request.optionId(),
			option == null ? null : option.outputItemId(),
			expectedOutputCount,
			request.inputQuantity(),
			tick,
			Long.MAX_VALUE,
			false
		);
		TrackedProcess replaced = processesByStation.put(observation.key(), process);
		if (replaced != null && !Objects.equals(replaced.processId(), process.processId())) {
			processesById.remove(replaced.processId());
			removeProcessOptionIfUnused(replaced.optionId());
		}
		processesById.put(processId, process);
		return SmeltingActionResult.accepted(
			processId,
			"accepted processId=" + processId + " station=" + stationText(observation)
		);
	}

	public SmeltingActionResult collectUntrackedOutput(CollectSmeltedItemsStepArgs request, SmeltingStationObservation observation, long tick) {
		Objects.requireNonNull(request, "request");
		if (request.processId() != null && processesById.containsKey(request.processId())) {
			return SmeltingActionResult.accepted(request.processId(), "accepted processId=" + request.processId());
		}
		SmeltingStationState state = classify(observation, tick);
		boolean unsafeStation = state == SmeltingStationState.OCCUPIED || state == SmeltingStationState.STALE;
		if (unsafeStation) {
			SmeltingActionResult confirmationFailure = consumeConfirmation(
				request.confirmationToken(),
				"collect_smelted_items",
				request.processId(),
				0,
				observation,
				tick
			);
			if (confirmationFailure != null) {
				return confirmationFailure;
			}
			if (request.confirmationToken() != null) {
				confirmedCollectionStations.put(request.confirmationToken(), observation.key());
				return SmeltingActionResult.accepted(null, "accepted untracked station=" + stationText(observation));
			}
		}
		if (unsafeStation && request.confirmationToken() == null) {
			String token = createConfirmation("collect_smelted_items", request.processId(), 0, observation, tick);
			return SmeltingActionResult.confirmationRequired(
				token,
				"confirmationRequired state=" + state.name() + " station=" + stationText(observation) + " slots=" + slotSummary(observation)
			);
		}
		return SmeltingActionResult.failed("nothing_to_collect", "No tracked or occupied smelting output is available.");
	}

	public SmeltingStationState classify(SmeltingStationObservation observation, long tick) {
		if (observation == null || observation.key() == null || observation.slots() == null) {
			return SmeltingStationState.STALE;
		}
		TrackedProcess process = processesByStation.get(observation.key());
		if (process != null) {
			if (!observation.slotsVisible()) {
				return SmeltingStationState.AIRICRAFT_OWNED;
			}
			if (Objects.equals(process.slotFingerprint(), observation.slots().fingerprint())) {
				return SmeltingStationState.AIRICRAFT_OWNED;
			}
			return SmeltingStationState.STALE;
		}
		return observation.slots().empty() ? SmeltingStationState.EMPTY : SmeltingStationState.OCCUPIED;
	}

	public List<SmeltingStationCandidate> rankCandidates(List<SmeltingStationCandidate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		ArrayList<SmeltingStationCandidate> ranked = new ArrayList<>(candidates);
		ranked.sort(Comparator
			.comparingInt((SmeltingStationCandidate candidate) -> sourceRank(candidate.source()))
			.thenComparingInt(candidate -> stateRank(candidate.state()))
			.thenComparingDouble(SmeltingStationCandidate::distance));
		return List.copyOf(ranked);
	}

	public List<SmeltingOutputReadyEvent> markReadyOutputs(List<SmeltingStationObservation> observations, long tick) {
		if (processesByStation.isEmpty()) {
			return List.of();
		}
		ArrayList<SmeltingOutputReadyEvent> events = new ArrayList<>();
		if (observations != null) {
			for (SmeltingStationObservation observation : observations) {
				SmeltingOutputReadyEvent event = markObservedReadyOutput(observation);
				if (event != null) {
					events.add(event);
				}
			}
		}
		for (TrackedProcess process : List.copyOf(processesById.values())) {
			SmeltingOutputReadyEvent event = markEstimatedReadyOutput(process, tick);
			if (event != null) {
				events.add(event);
			}
		}
		return List.copyOf(events);
	}

	public String inspectSummary() {
		if (processesById.isEmpty()) {
			return "Tool result for inspect_smelting: processes=0";
		}
		StringBuilder builder = new StringBuilder("Tool result for inspect_smelting: processes=").append(processesById.size());
		for (TrackedProcess process : processesById.values()) {
			builder.append("\nprocessId=")
				.append(process.processId())
				.append(" optionId=")
				.append(process.optionId())
				.append(" station=")
				.append(process.stationKey().compact())
				.append(" inputQuantity=")
				.append(process.inputQuantity());
		}
		builder.append("\nOutput is not auto-collected. After cooking, call collect_smelted_items with the processId; inspect_smelting only reports state.");
		return builder.toString();
	}

	public boolean cancel(String processId) {
		if (processId == null || processId.isBlank()) {
			return false;
		}
		TrackedProcess process = processesById.remove(processId.trim());
		if (process == null) {
			return false;
		}
		if (Objects.equals(processesByStation.get(process.stationKey()), process)) {
			processesByStation.remove(process.stationKey());
		}
		removeProcessOptionIfUnused(process.optionId());
		return true;
	}

	public int cancelProcessesForOption(String optionId) {
		String normalizedOptionId = optionId == null ? null : optionId.trim();
		if (normalizedOptionId == null || normalizedOptionId.isEmpty()) {
			return 0;
		}
		int cancelled = 0;
		for (TrackedProcess process : List.copyOf(processesById.values())) {
			if (Objects.equals(process.optionId(), normalizedOptionId) && cancel(process.processId())) {
				cancelled++;
			}
		}
		return cancelled;
	}

	private SmeltingOutputReadyEvent markObservedReadyOutput(SmeltingStationObservation observation) {
		if (observation == null || observation.key() == null || observation.slots() == null) {
			return null;
		}
		TrackedProcess process = processesByStation.get(observation.key());
		SmeltingSlotSnapshot slots = observation.slots();
		if (
			process == null
				|| process.outputReadyNotified()
				|| slots.outputItemId() == null
				|| slots.outputCount() <= 0
		) {
			return null;
		}
		SmeltingOption option = registeredOption(process.optionId());
		String expectedOutputItemId = process.expectedOutputItemId() == null && option != null
			? option.outputItemId()
			: process.expectedOutputItemId();
		if (expectedOutputItemId != null && !Objects.equals(expectedOutputItemId, slots.outputItemId())) {
			return null;
		}
		if (slots.outputCount() < Math.max(1, process.expectedOutputCount())) {
			return null;
		}
		// TODO: Harden against remote-server desync by requiring a fresh post-open slot observation
		// before treating visible output as authoritative.
		TrackedProcess updated = new TrackedProcess(
			process.processId(),
			process.stationKey(),
			slots.fingerprint(),
			process.optionId(),
			expectedOutputItemId,
			process.expectedOutputCount(),
			process.inputQuantity(),
			process.startedTick(),
			process.estimatedReadyTick(),
			true
		);
		processesByStation.put(process.stationKey(), updated);
		processesById.put(process.processId(), updated);
		return new SmeltingOutputReadyEvent(
			process.processId(),
			process.optionId(),
			process.stationKey(),
			slots.outputItemId(),
			slots.outputCount(),
			process.inputQuantity(),
			false
		);
	}

	private void removeProcessOptionIfUnused(String optionId) {
		if (optionId == null || optionId.isBlank()) {
			return;
		}
		for (TrackedProcess process : processesById.values()) {
			if (Objects.equals(process.optionId(), optionId)) {
				return;
			}
		}
		processOptionsById.remove(optionId);
	}

	private SmeltingOutputReadyEvent markEstimatedReadyOutput(TrackedProcess process, long tick) {
		if (
			process == null
				|| process.outputReadyNotified()
				|| process.estimatedReadyTick() == Long.MAX_VALUE
				|| tick < process.estimatedReadyTick()
				|| process.expectedOutputItemId() == null
		) {
			return null;
		}
		// TODO: Replace timer-only readiness with a server-backed signal when a safe route exists;
		// this is only a planner wakeup hint.
		TrackedProcess updated = new TrackedProcess(
			process.processId(),
			process.stationKey(),
			process.slotFingerprint(),
			process.optionId(),
			process.expectedOutputItemId(),
			process.expectedOutputCount(),
			process.inputQuantity(),
			process.startedTick(),
			process.estimatedReadyTick(),
			true
		);
		processesByStation.put(process.stationKey(), updated);
		processesById.put(process.processId(), updated);
		return new SmeltingOutputReadyEvent(
			process.processId(),
			process.optionId(),
			process.stationKey(),
			process.expectedOutputItemId(),
			process.expectedOutputCount(),
			process.inputQuantity(),
			true
		);
	}

	private long estimatedReadyTick(TrackedProcess process, long insertedTick) {
		SmeltingOption option = registeredOption(process.optionId());
		int cookTimeTicks = option == null ? 200 : Math.max(1, option.cookTimeTicks());
		// TODO: Account for server TPS, fuel burn gaps, partial cook progress, and chunk unloads
		// instead of assuming vanilla uninterrupted ticks.
		return insertedTick + (long) cookTimeTicks * Math.max(1, process.inputQuantity()) + ESTIMATED_READY_GRACE_TICKS;
	}

	private SmeltingActionResult consumeConfirmation(
		String token,
		String actionKind,
		String requestedId,
		int quantity,
		SmeltingStationObservation observation,
		long tick
	) {
		if (token == null) {
			return null;
		}
		Confirmation confirmation = confirmations.remove(token);
		if (confirmation == null
			|| tick > confirmation.expiresAtTick()
			|| !Objects.equals(confirmation.actionKind(), actionKind)
			|| !Objects.equals(confirmation.requestedId(), requestedId)
			|| confirmation.quantity() != quantity
			|| observation == null
			|| !Objects.equals(confirmation.stationKey(), observation.key())
			|| !Objects.equals(confirmation.slotFingerprint(), observation.slots().fingerprint())) {
			return SmeltingActionResult.failed("invalid_confirmation_token", "Confirmation token is invalid, expired, or no longer matches furnace contents.");
		}
		return null;
	}

	private String createConfirmation(String actionKind, String requestedId, int quantity, SmeltingStationObservation observation, long tick) {
		String token = "smelt-confirm-" + UUID.randomUUID();
		confirmations.put(token, new Confirmation(
			token,
			actionKind,
			requestedId,
			quantity,
			observation.key(),
			observation.slots().fingerprint(),
			tick + CONFIRMATION_TTL_TICKS
		));
		return token;
	}

	private static int sourceRank(SmeltingStationSource source) {
		return switch (source) {
			case OPEN_SCREEN -> 0;
			case NEARBY_EXISTING -> 1;
			case PLACE_FROM_INVENTORY -> 2;
		};
	}

	private static int stateRank(SmeltingStationState state) {
		return switch (state) {
			case EMPTY -> 0;
			case AIRICRAFT_OWNED -> 1;
			case OCCUPIED -> 2;
			case STALE -> 3;
		};
	}

	private static String stationText(SmeltingStationObservation observation) {
		return observation == null || observation.key() == null ? "unknown" : observation.key().compact();
	}

	private static String slotSummary(SmeltingStationObservation observation) {
		if (observation == null || observation.slots() == null) {
			return "unknown";
		}
		SmeltingSlotSnapshot slots = observation.slots();
		return "input=" + itemSummary(slots.inputItemId(), slots.inputCount())
			+ " fuel=" + itemSummary(slots.fuelItemId(), slots.fuelCount())
			+ " output=" + itemSummary(slots.outputItemId(), slots.outputCount());
	}

	private static String itemSummary(String itemId, int count) {
		return itemId == null || count <= 0 ? "empty" : itemId + "x" + count;
	}

	private record TrackedProcess(
		String processId,
		SmeltingStationKey stationKey,
		String slotFingerprint,
		String optionId,
		String expectedOutputItemId,
		int expectedOutputCount,
		int inputQuantity,
		long startedTick,
		long estimatedReadyTick,
		boolean outputReadyNotified
	) {
	}

	private record Confirmation(
		String token,
		String actionKind,
		String requestedId,
		int quantity,
		SmeltingStationKey stationKey,
		String slotFingerprint,
		long expiresAtTick
	) {
	}
}
