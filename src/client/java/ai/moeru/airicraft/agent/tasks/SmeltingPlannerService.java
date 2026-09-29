package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;

import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SmeltingPlannerService {
	private static final int STATION_SEARCH_RADIUS = 10;
	private static final int STATION_SEARCH_VERTICAL_RADIUS = 4;
	private static final int DEFAULT_COOK_TIME_TICKS = 200;
	private ServerRecipeDisplayCatalog.Snapshot cachedKnownCatalog;
	private List<SmeltingRecipeKnowledge> cachedKnownServerSmelts = List.of();

	public String checkSmeltables(Minecraft minecraft, SmeltingProcessManager manager, long tick) {
		Objects.requireNonNull(manager, "manager");
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (player == null || level == null) {
			manager.registerOptions(List.of());
			return "Tool result for check_smeltables: SMELTING_UNAVAILABLE world_not_loaded";
		}

		ServerRecipeDisplayCatalog.Snapshot recipeCatalog = ServerRecipeDisplayCatalog.current();
		List<SmeltableInput> inputs = smeltableInputs(player, level, recipeCatalog);
		List<SmeltingStationCandidate> candidates = manager.rankCandidates(stationCandidates(minecraft, manager, tick));
		List<SmeltingOption> options = buildOptions(inputs, candidates, observeStations(minecraft), manager, tick);
		FuelInventorySummary fuelSummary = fuelInventorySummary(player, level);
		manager.registerOptions(options);
		return renderCheckSmeltables(options, candidates, fuelSummary);
	}

	public List<SmeltingOption> availableSmeltingOptions(Minecraft minecraft, SmeltingProcessManager manager, long tick) {
		return inspectOpportunities(minecraft, manager, tick).availableSmelts();
	}

	public SmeltingOpportunitySnapshot inspectOpportunities(Minecraft minecraft, SmeltingProcessManager manager, long tick) {
		Objects.requireNonNull(manager, "manager");
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (player == null || level == null) {
			manager.registerOptions(List.of());
			return SmeltingOpportunitySnapshot.empty();
		}
		ServerRecipeDisplayCatalog.Snapshot recipeCatalog = ServerRecipeDisplayCatalog.current();
		List<SmeltableInput> inputs = smeltableInputs(player, level, recipeCatalog);
		List<SmeltingStationCandidate> candidates = manager.rankCandidates(stationCandidates(minecraft, manager, tick));
		List<SmeltingOption> options = buildOptions(inputs, candidates, observeStations(minecraft), manager, tick);
		manager.registerOptions(options);
		return new SmeltingOpportunitySnapshot(options, knownSmeltingRecipes(player, level, recipeCatalog));
	}

	public String inspectSmelting(Minecraft minecraft, SmeltingProcessManager manager, long tick) {
		Objects.requireNonNull(manager, "manager");
		StringBuilder builder = new StringBuilder(manager.inspectSummary());
		List<SmeltingStationObservation> observations = observeStations(minecraft);
		if (observations.isEmpty()) {
			return builder.append("\nnearbyFurnaces=0").toString();
		}
		builder.append("\nnearbyFurnaces=").append(observations.size());
		for (SmeltingStationObservation observation : observations) {
			SmeltingStationState state = manager.classify(observation, tick);
			builder.append("\nstation=")
				.append(observation.key().compact())
				.append(" kind=")
				.append(observation.kind().name())
				.append(" state=")
				.append(state.name())
				.append(" slots=")
				.append(slotSummary(observation));
			if (state == SmeltingStationState.OCCUPIED && observation.slots().outputCount() > 0) {
				builder.append(" untrackedReadyOutput=true");
			}
		}
		return builder.toString();
	}

	public List<SmeltingOutputReadyEvent> pollTrackedOutputReady(Minecraft minecraft, SmeltingProcessManager manager, long tick) {
		Objects.requireNonNull(manager, "manager");
		if (!manager.hasTrackedProcesses()) {
			return List.of();
		}
		ArrayList<SmeltingStationObservation> observations = new ArrayList<>();
		for (SmeltingStationKey key : manager.trackedStationKeys()) {
			observeTrackedStation(minecraft, key).ifPresent(observations::add);
		}
		return manager.markReadyOutputs(observations, tick);
	}

	public SmeltingActionResult startSmelting(Minecraft minecraft, SmeltingProcessManager manager, SmeltItemsStepArgs request, long tick) {
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(request, "request");
		SmeltingOption option = manager.registeredOption(request.optionId());
		if (option == null) {
			return SmeltingActionResult.failed("option_not_found", "Unknown smelting optionId: " + request.optionId());
		}
		if (request.inputQuantity() > option.maxInputQuantity()) {
			return SmeltingActionResult.failed("insufficient_input", "Requested inputQuantity exceeds available input items.");
		}
		SmeltingStationObservation latest = refreshObservation(minecraft, option.stationObservation())
			.orElse(option.stationObservation());
		return manager.startProcess(request, latest, tick);
	}

	public SmeltingActionResult collectSmelted(Minecraft minecraft, SmeltingProcessManager manager, CollectSmeltedItemsStepArgs request, long tick) {
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(request, "request");
		if (request.processId() == null && request.confirmationToken() == null) {
			String processId = manager.preferredCollectionProcessId();
			if (processId != null) {
				return SmeltingActionResult.accepted(processId, "accepted processId=" + processId + " trackedPending=true");
			}
		}
		SmeltingStationKey key = request.processId() == null
			? manager.confirmationStationKey(request.confirmationToken())
			: manager.processStationKey(request.processId());
		if (request.processId() != null && key != null) {
			return SmeltingActionResult.accepted(request.processId(), "accepted processId=" + request.processId());
		}
		if (key != null) {
			SmeltingStationObservation observation = refreshObservation(minecraft, key).orElse(null);
			if (observation == null) {
				return SmeltingActionResult.failed("station_unavailable", "Smelting station is no longer loaded.");
			}
			return manager.collectUntrackedOutput(request, observation, tick);
		}
		List<SmeltingStationObservation> observations = observeStations(minecraft).stream()
			.filter(observation -> observation.slots().outputCount() > 0)
			.sorted(Comparator.comparingDouble(SmeltingStationObservation::distance))
			.toList();
		if (observations.isEmpty()) {
			return SmeltingActionResult.failed("nothing_to_collect", "No nearby smelted output is visible.");
		}
		return manager.collectUntrackedOutput(request, observations.get(0), tick);
	}

	Optional<SmeltingStationObservation> refreshObservation(Minecraft minecraft, SmeltingStationObservation previous) {
		if (previous == null || previous.key() == null) {
			return Optional.empty();
		}
		return refreshObservation(minecraft, previous.key());
	}

	Optional<SmeltingStationObservation> refreshObservation(Minecraft minecraft, SmeltingStationKey key) {
		if (key == null) {
			return Optional.empty();
		}
		return observeStations(minecraft).stream()
			.filter(observation -> key.equals(observation.key()))
				.findFirst();
	}

	private Optional<SmeltingStationObservation> observeTrackedStation(Minecraft minecraft, SmeltingStationKey key) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (player == null || level == null || key == null) {
			return Optional.empty();
		}
		if (key.dimensionId() != null && key.dimensionId().contains("#open_screen")) {
			return observeStations(minecraft).stream()
				.filter(observation -> key.equals(observation.key()))
				.findFirst();
		}
		if (!Objects.equals(level.dimension().location().toString(), key.dimensionId())) {
			return Optional.empty();
		}
		BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
		if (!level.hasChunkAt(pos)) {
			return Optional.empty();
		}
		SmeltingStationKind kind = stationKind(level.getBlockState(pos)).orElse(null);
		if (kind == null) {
			return Optional.empty();
		}
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (!(blockEntity instanceof Container container)) {
			return Optional.empty();
		}
		return Optional.of(new SmeltingStationObservation(
			key,
			kind,
			slotSnapshot(container, level.getBlockState(pos)),
			false,
			player.distanceToSqr(Vec3.atCenterOf(pos)),
			false
		));
	}

	List<SmeltingStationObservation> observeStations(Minecraft minecraft) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (player == null || level == null) {
			return List.of();
		}
		ArrayList<SmeltingStationObservation> observations = new ArrayList<>();
		if (player.containerMenu instanceof AbstractFurnaceMenu furnaceMenu) {
			observations.add(openScreenObservation(player, level, furnaceMenu));
		}
		BlockPos origin = player.blockPosition();
		for (int dx = -STATION_SEARCH_RADIUS; dx <= STATION_SEARCH_RADIUS; dx++) {
			for (int dy = -STATION_SEARCH_VERTICAL_RADIUS; dy <= STATION_SEARCH_VERTICAL_RADIUS; dy++) {
				for (int dz = -STATION_SEARCH_RADIUS; dz <= STATION_SEARCH_RADIUS; dz++) {
					BlockPos pos = origin.offset(dx, dy, dz);
					if (!level.hasChunkAt(pos)) {
						continue;
					}
					SmeltingStationKind kind = stationKind(level.getBlockState(pos)).orElse(null);
					if (kind == null) {
						continue;
					}
					BlockEntity blockEntity = level.getBlockEntity(pos);
					if (!(blockEntity instanceof Container container)) {
						continue;
					}
					observations.add(new SmeltingStationObservation(
						stationKey(level, pos),
						kind,
						slotSnapshot(container, level.getBlockState(pos)),
						false,
						player.distanceToSqr(Vec3.atCenterOf(pos)),
						false
					));
				}
			}
		}
		return List.copyOf(observations);
	}

	private List<SmeltingStationCandidate> stationCandidates(Minecraft minecraft, SmeltingProcessManager manager, long tick) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (player == null || level == null) {
			return List.of();
		}
		ArrayList<SmeltingStationCandidate> candidates = new ArrayList<>();
		for (SmeltingStationObservation observation : observeStations(minecraft)) {
			SmeltingStationState state = manager.classify(observation, tick);
			candidates.add(new SmeltingStationCandidate(
				observation.openScreen() ? SmeltingStationSource.OPEN_SCREEN : SmeltingStationSource.NEARBY_EXISTING,
				state,
				observation.kind(),
				observation.key(),
				observation.distance(),
				state == SmeltingStationState.OCCUPIED || state == SmeltingStationState.STALE
			));
		}
		chooseFurnacePlacement(minecraft, player).ifPresent(pos -> candidates.add(new SmeltingStationCandidate(
			SmeltingStationSource.PLACE_FROM_INVENTORY,
			SmeltingStationState.EMPTY,
			SmeltingStationKind.FURNACE,
			stationKey(level, pos),
			player.distanceToSqr(Vec3.atCenterOf(pos)),
			false
		)));
		return List.copyOf(candidates);
	}

	private List<SmeltingOption> buildOptions(
		List<SmeltableInput> inputs,
		List<SmeltingStationCandidate> candidates,
		List<SmeltingStationObservation> observations,
		SmeltingProcessManager manager,
		long tick
	) {
		if (inputs.isEmpty() || candidates.isEmpty()) {
			return List.of();
		}
		Map<SmeltingStationKey, SmeltingStationObservation> observationsByKey = new LinkedHashMap<>();
		for (SmeltingStationObservation observation : observations) {
			observationsByKey.putIfAbsent(observation.key(), observation);
		}
		ArrayList<SmeltingOption> options = new ArrayList<>();
		int candidateIndex = 0;
		for (SmeltingStationCandidate candidate : candidates) {
			candidateIndex++;
			SmeltingStationObservation observation = observationsByKey.get(candidate.key());
			if (observation == null) {
				observation = new SmeltingStationObservation(
					candidate.key(),
					candidate.kind(),
					new SmeltingSlotSnapshot(null, 0, null, 0, null, 0, 0, DEFAULT_COOK_TIME_TICKS, false),
					false,
					candidate.distance()
				);
			}
			for (SmeltableInput input : inputs) {
				if (input.stationKind() != candidate.kind()) {
					continue;
				}
				String optionId = "smelt:" + optionSegment(input.inputItemId()) + "_to_" + optionSegment(input.outputItemId())
					+ ":" + sourceSegment(candidate.source()) + "-" + candidateIndex;
				options.add(new SmeltingOption(
					optionId,
					input.inputItemId(),
					input.outputItemId(),
					input.outputCount(),
					input.availableCount(),
					effectiveCookTimeTicks(input.cookTimeTicks(), candidate.kind()),
					candidate,
					observation
				));
			}
		}
		return List.copyOf(options);
	}

	private List<SmeltableInput> smeltableInputs(
		LocalPlayer player,
		ClientLevel level,
		ServerRecipeDisplayCatalog.Snapshot recipeCatalog
	) {
		Map<Item, Integer> inventoryCounts = inventoryCounts(player);
		if (inventoryCounts.isEmpty()) {
			return List.of();
		}
		Map<String, SmeltableInput> inputs = new LinkedHashMap<>();
		var context = SlotDisplayContext.fromLevel(level);
		for (RecipeDisplayEntry entry : recipeEntries(player, recipeCatalog)) {
			if (!(entry.display() instanceof FurnaceRecipeDisplay display)) {
				continue;
			}
			SmeltingStationKind stationKind = stationKind(display, context).orElse(null);
			ItemStack result = display.result().resolveForFirstStack(context);
			if (stationKind == null || result.isEmpty()) {
				continue;
			}
			for (ItemStack ingredient : display.ingredient().resolveForStacks(context)) {
				if (ingredient.isEmpty()) {
					continue;
				}
				int available = inventoryCounts.getOrDefault(ingredient.getItem(), 0);
				if (available <= 0) {
					continue;
				}
				String inputItemId = itemId(ingredient);
				String key = stationKind.name() + "|" + inputItemId + "|" + itemId(result);
				inputs.putIfAbsent(key, new SmeltableInput(
					inputItemId,
					itemId(result),
					result.getCount(),
					available,
					display.duration() <= 0 ? DEFAULT_COOK_TIME_TICKS : display.duration(),
					stationKind
				));
			}
		}
		return List.copyOf(inputs.values());
	}

	private List<SmeltingRecipeKnowledge> knownSmeltingRecipes(
		LocalPlayer player,
		ClientLevel level,
		ServerRecipeDisplayCatalog.Snapshot recipeCatalog
	) {
		List<SmeltingRecipeKnowledge> serverKnowledge;
		if (recipeCatalog == cachedKnownCatalog) {
			serverKnowledge = cachedKnownServerSmelts;
		}
		else {
			serverKnowledge = extractKnownSmeltingRecipes(recipeCatalog.entries(), level);
			cachedKnownCatalog = recipeCatalog;
			cachedKnownServerSmelts = serverKnowledge;
		}
		return mergeKnownSmelts(
			extractKnownSmeltingRecipes(recipeBookEntries(player), level),
			serverKnowledge
		);
	}

	@SafeVarargs
	static List<SmeltingRecipeKnowledge> mergeKnownSmelts(List<SmeltingRecipeKnowledge>... sources) {
		Map<String, SmeltingRecipeKnowledge> merged = new LinkedHashMap<>();
		if (sources == null) {
			return List.of();
		}
		for (List<SmeltingRecipeKnowledge> source : sources) {
			if (source == null) {
				continue;
			}
			for (SmeltingRecipeKnowledge recipe : source) {
				if (recipe != null) {
					merged.putIfAbsent(recipe.optionId(), recipe);
				}
			}
		}
		return List.copyOf(merged.values());
	}

	private static List<SmeltingRecipeKnowledge> extractKnownSmeltingRecipes(
		List<RecipeDisplayEntry> entries,
		ClientLevel level
	) {
		if (entries == null || entries.isEmpty() || level == null) {
			return List.of();
		}
		Map<String, SmeltingRecipeKnowledge> recipes = new LinkedHashMap<>();
		var context = SlotDisplayContext.fromLevel(level);
		for (RecipeDisplayEntry entry : entries) {
			if (!(entry.display() instanceof FurnaceRecipeDisplay display)
				|| stationKind(display, context).orElse(null) != SmeltingStationKind.FURNACE) {
				continue;
			}
			ItemStack result = display.result().resolveForFirstStack(context);
			if (result.isEmpty()) {
				continue;
			}
			for (ItemStack ingredient : display.ingredient().resolveForStacks(context)) {
				if (ingredient.isEmpty()) {
					continue;
				}
				String inputItemId = itemId(ingredient);
				String outputItemId = itemId(result);
				String optionId = "inferred:" + optionSegment(inputItemId) + "_to_" + optionSegment(outputItemId);
				recipes.putIfAbsent(optionId, new SmeltingRecipeKnowledge(
					optionId,
					inputItemId,
					outputItemId,
					result.getCount(),
					ingredient.getMaxStackSize(),
					display.duration() <= 0 ? DEFAULT_COOK_TIME_TICKS : display.duration(),
					"minecraft:furnace",
					1
				));
			}
		}
		return List.copyOf(recipes.values());
	}

	private static List<RecipeDisplayEntry> recipeEntries(
		LocalPlayer player,
		ServerRecipeDisplayCatalog.Snapshot recipeCatalog
	) {
		ArrayList<RecipeDisplayEntry> entries = new ArrayList<>(recipeBookEntries(player));
		if (recipeCatalog != null) {
			entries.addAll(recipeCatalog.entries());
		}
		return List.copyOf(entries);
	}

	private static List<RecipeDisplayEntry> recipeBookEntries(LocalPlayer player) {
		if (player == null) {
			return List.of();
		}
		ArrayList<RecipeDisplayEntry> entries = new ArrayList<>();
		for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
			entries.addAll(collection.getRecipes());
		}
		return List.copyOf(entries);
	}

	private static Optional<SmeltingStationKind> stationKind(FurnaceRecipeDisplay display, net.minecraft.util.context.ContextMap context) {
		for (ItemStack station : display.craftingStation().resolveForStacks(context)) {
			if (station.is(Items.FURNACE)) {
				return Optional.of(SmeltingStationKind.FURNACE);
			}
			if (station.is(Items.BLAST_FURNACE)) {
				return Optional.of(SmeltingStationKind.BLAST_FURNACE);
			}
			if (station.is(Items.SMOKER)) {
				return Optional.of(SmeltingStationKind.SMOKER);
			}
		}
		return Optional.empty();
	}

	private static SmeltingStationObservation openScreenObservation(LocalPlayer player, ClientLevel level, AbstractFurnaceMenu menu) {
		SmeltingStationKind kind = switch (menu) {
			case net.minecraft.world.inventory.BlastFurnaceMenu ignored -> SmeltingStationKind.BLAST_FURNACE;
			case net.minecraft.world.inventory.SmokerMenu ignored -> SmeltingStationKind.SMOKER;
			default -> SmeltingStationKind.FURNACE;
		};
		return new SmeltingStationObservation(
			new SmeltingStationKey(level.dimension().location() + "#open_screen", menu.containerId, 0, 0),
			kind,
			new SmeltingSlotSnapshot(
				itemId(menu.getSlot(0).getItem()),
				menu.getSlot(0).getItem().getCount(),
				itemId(menu.getSlot(1).getItem()),
				menu.getSlot(1).getItem().getCount(),
				itemId(menu.getSlot(2).getItem()),
				menu.getSlot(2).getItem().getCount(),
				0,
				DEFAULT_COOK_TIME_TICKS,
				menu.isLit()
			),
			true,
			0.0D
		);
	}

	private static SmeltingSlotSnapshot slotSnapshot(Container container, BlockState state) {
		ItemStack input = container.getItem(0);
		ItemStack fuel = container.getItem(1);
		ItemStack output = container.getItem(2);
		boolean burning = state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
		return new SmeltingSlotSnapshot(
			itemId(input),
			input.getCount(),
			itemId(fuel),
			fuel.getCount(),
			itemId(output),
			output.getCount(),
			0,
			DEFAULT_COOK_TIME_TICKS,
			burning
		);
	}

	private static Optional<SmeltingStationKind> stationKind(BlockState state) {
		if (state.is(Blocks.FURNACE)) {
			return Optional.of(SmeltingStationKind.FURNACE);
		}
		if (state.is(Blocks.BLAST_FURNACE)) {
			return Optional.of(SmeltingStationKind.BLAST_FURNACE);
		}
		if (state.is(Blocks.SMOKER)) {
			return Optional.of(SmeltingStationKind.SMOKER);
		}
		return Optional.empty();
	}

	private static Optional<BlockPos> chooseFurnacePlacement(Minecraft minecraft, LocalPlayer player) {
		if (!hasFurnaceItem(player.containerMenu)) {
			return Optional.empty();
		}
		BlockPos origin = player.blockPosition();
		for (BlockPos candidate : furnacePlacementCandidatePositions(origin)) {
			if (canPlaceAt(minecraft, candidate)) {
				return Optional.of(candidate.immutable());
			}
		}
		return Optional.empty();
	}

	static List<BlockPos> furnacePlacementCandidatePositions(BlockPos origin) {
		ArrayList<BlockPos> candidates = new ArrayList<>();
		for (int yOffset : List.of(0, -1, 1, 2, -2)) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				candidates.add(origin.relative(direction).offset(0, yOffset, 0));
			}
		}
		for (int yOffset : List.of(0, -1, 1, 2, -2)) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos candidate = origin.offset(dx, yOffset, dz);
					if (!candidate.equals(origin)) {
						candidates.add(candidate);
					}
				}
			}
		}
		return List.copyOf(candidates);
	}

	private static boolean hasFurnaceItem(AbstractContainerMenu menu) {
		if (!(menu instanceof InventoryMenu)) {
			return false;
		}
		for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_END; slot++) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (!stack.isEmpty() && stack.is(Items.FURNACE)) {
				return true;
			}
		}
		return false;
	}

	private static boolean canPlaceAt(Minecraft minecraft, BlockPos pos) {
		if (minecraft == null || minecraft.level == null || !minecraft.level.hasChunkAt(pos) || !minecraft.level.hasChunkAt(pos.below())) {
			return false;
		}
		if (WorldPlacePreservation.contains(minecraft.level, pos)) return false;
		BlockState target = minecraft.level.getBlockState(pos);
		BlockState support = minecraft.level.getBlockState(pos.below());
		return (target.isAir() || target.canBeReplaced())
			&& support.isFaceSturdy(minecraft.level, pos.below(), Direction.UP)
			&& minecraft.level.isUnobstructed(Blocks.FURNACE.defaultBlockState(), pos, CollisionContext.placementContext(minecraft.player));
	}

	private static Map<Item, Integer> inventoryCounts(LocalPlayer player) {
		LinkedHashMap<Item, Integer> counts = new LinkedHashMap<>();
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty()) {
				counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		return Map.copyOf(counts);
	}

	private static FuelInventorySummary fuelInventorySummary(LocalPlayer player, ClientLevel level) {
		LinkedHashMap<String, FuelItemSummary> fuels = new LinkedHashMap<>();
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.isEmpty() || !level.fuelValues().isFuel(stack)) {
				continue;
			}
			String itemId = itemId(stack);
			int fuelTicks = level.fuelValues().burnDuration(stack);
			FuelItemSummary previous = fuels.get(itemId);
			fuels.put(itemId, new FuelItemSummary(
				itemId,
				(previous == null ? 0 : previous.count()) + stack.getCount(),
				fuelTicks
			));
		}
		return new FuelInventorySummary(fuels);
	}

	private static SmeltingStationKey stationKey(ClientLevel level, BlockPos pos) {
		return new SmeltingStationKey(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
	}

	private static String renderCheckSmeltables(List<SmeltingOption> options, List<SmeltingStationCandidate> candidates, FuelInventorySummary fuelSummary) {
		StringBuilder builder = new StringBuilder("Tool result for check_smeltables: options=").append(options.size())
			.append(" fuelInventory=")
			.append(fuelSummary.format());
		if (options.isEmpty()) {
			return builder.append(" candidates=").append(candidates.size()).toString();
		}
		for (SmeltingOption option : options) {
			builder.append("\noptionId=")
				.append(option.optionId())
				.append(" input=")
				.append(option.inputItemId())
				.append(" maxInputQuantity=")
				.append(option.maxInputQuantity())
				.append(" output=")
				.append(option.outputItemId())
				.append("x")
				.append(option.outputCount())
				.append(" stationState=")
				.append(option.stationCandidate().state().name())
				.append(" stationSource=")
				.append(option.stationCandidate().source().name())
				.append(" autoFuelForMaxInput=")
				.append(fuelSummary.bestFuelFor(option.inputItemId(), option.maxInputQuantity(), option.cookTimeTicks()))
				.append(" confirmationRequired=")
				.append(option.stationCandidate().confirmationRequired());
		}
		return builder.toString();
	}

	static String slotSummary(SmeltingStationObservation observation) {
		if (observation == null || !observation.slotsVisible()) {
			boolean burning = observation != null && observation.slots() != null && observation.slots().burning();
			return "unavailable burning=" + burning;
		}
		return slotSummary(observation.slots());
	}

	private static String slotSummary(SmeltingSlotSnapshot slots) {
		return "input=" + itemSummary(slots.inputItemId(), slots.inputCount())
			+ " fuel=" + itemSummary(slots.fuelItemId(), slots.fuelCount())
			+ " output=" + itemSummary(slots.outputItemId(), slots.outputCount());
	}

	private static String itemSummary(String itemId, int count) {
		return itemId == null || count <= 0 ? "empty" : itemId + "x" + count;
	}

	private static String itemId(ItemStack stack) {
		return stack == null || stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static String optionSegment(String itemId) {
		return itemId == null ? "unknown" : itemId.toLowerCase(Locale.ROOT).replace(':', '_').replace('/', '_');
	}

	private static String sourceSegment(SmeltingStationSource source) {
		return switch (source) {
			case OPEN_SCREEN -> "open";
			case NEARBY_EXISTING -> "nearby";
			case PLACE_FROM_INVENTORY -> "carried_furnace";
		};
	}

	static int effectiveCookTimeTicks(int recipeCookTimeTicks, SmeltingStationKind stationKind) {
		int recipeTicks = recipeCookTimeTicks <= 0 ? DEFAULT_COOK_TIME_TICKS : recipeCookTimeTicks;
		int minimumTicks = switch (stationKind) {
			case BLAST_FURNACE, SMOKER -> DEFAULT_COOK_TIME_TICKS / 2;
			case FURNACE -> DEFAULT_COOK_TIME_TICKS;
		};
		return Math.max(recipeTicks, minimumTicks);
	}

	private record SmeltableInput(
		String inputItemId,
		String outputItemId,
		int outputCount,
		int availableCount,
		int cookTimeTicks,
		SmeltingStationKind stationKind
	) {
	}

	record FuelInventorySummary(Map<String, FuelItemSummary> fuels) {
		FuelInventorySummary {
			fuels = fuels == null ? Map.of() : Map.copyOf(fuels);
		}

		String format() {
			if (fuels.isEmpty()) {
				return "none";
			}
			return fuels.values().stream()
				.sorted(Comparator
					.comparingInt(FuelInventorySummary::defaultCookOperations)
					.reversed()
					.thenComparing(FuelItemSummary::itemId))
				.map(fuel -> fuel.itemId() + "x" + fuel.count() + "(cooks=" + defaultCookOperations(fuel) + ")")
				.toList()
				.toString();
		}

		String bestFuelFor(String inputItemId, int inputQuantity, int cookTimeTicks) {
			Map<String, Integer> counts = new LinkedHashMap<>();
			Map<String, Integer> ticks = new LinkedHashMap<>();
			fuels.values().forEach(fuel -> {
				counts.put(fuel.itemId(), fuel.count());
				ticks.put(fuel.itemId(), fuel.fuelTicksPerItem());
			});
			return SmeltingTaskExecutor.selectFuel(counts, ticks, inputItemId, inputQuantity, inputQuantity * cookTimeTicks)
				.map(fuel -> fuel.itemId() + "x" + fuel.quantity()).orElse("missing");
		}

		private static int defaultCookOperations(FuelItemSummary fuel) {
			return fuel.count() * fuel.fuelTicksPerItem() / DEFAULT_COOK_TIME_TICKS;
		}
	}

	record FuelItemSummary(String itemId, int count, int fuelTicksPerItem) {
	}
}
