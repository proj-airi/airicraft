package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class CurrentWorldQueryService implements CurrentWorldQueryTool {
	static final int DEFAULT_HORIZONTAL_RADIUS = 8;
	static final int DEFAULT_VERTICAL_RADIUS = 4;
	static final int MAX_HORIZONTAL_RADIUS = 16;
	static final int MAX_VERTICAL_RADIUS = 8;
	static final int DEFAULT_MAX_RESULTS = 32;
	static final int MAX_RESULTS = 64;
	static final int MAX_DISTANCE_FROM_PLAYER = 64;
	static final int SEARCH_BLOCK_CAP = 20000;
	private static final double INTERACTION_RANGE_SQUARED = 20.25D;

	private final Supplier<Minecraft> clientSupplier;

	public CurrentWorldQueryService(Supplier<Minecraft> clientSupplier) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
	}

	@Override
	public CompletableFuture<String> inspectWorld(JsonObject arguments) {
		return inspectWorldDetailed(arguments).thenApply(WorldQueryResult::text);
	}

	public CompletableFuture<WorldQueryResult> inspectWorldDetailed(JsonObject arguments) {
		Minecraft minecraft = clientSupplier.get();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			return CompletableFuture.completedFuture(new WorldQueryResult("WORLD_UNAVAILABLE: world_not_loaded", List.of()));
		}
		try {
			return CompletableFuture.completedFuture(inspectWorld(minecraft, arguments == null ? new JsonObject() : arguments));
		}
		catch (WorldQueryException exception) {
			return CompletableFuture.completedFuture(new WorldQueryResult("TOOL_ERROR: inspect_world " + exception.getMessage(), List.of()));
		}
		catch (RuntimeException exception) {
			return CompletableFuture.completedFuture(new WorldQueryResult("TOOL_ERROR: inspect_world " + safeMessage(exception), List.of()));
		}
	}

	private static WorldQueryResult inspectWorld(Minecraft minecraft, JsonObject arguments) {
		String mode = stringArg(arguments, "mode").orElseThrow(() -> new WorldQueryException("mode is required"));
		QueryBounds bounds = QueryBounds.from(minecraft.player.blockPosition(), arguments);
		ensureWithinDistance(bounds, minecraft.player.blockPosition());
		ensureWithinBlockCap(bounds);
		WorldQueryResult result = switch (mode) {
			case "check_position", "check_interaction" -> LocalSpatialQuery.inspect(minecraft,mode,bounds.center(),arguments);
			case "inspect_area" -> inspectArea(minecraft.level, minecraft.player, bounds, arguments);
			case "find_blocks" -> findBlocks(minecraft.level, minecraft.player, bounds, arguments);
			case "find_placement_sites" -> findPlacementSites(minecraft.level, minecraft.player, bounds, arguments);
			default -> throw new WorldQueryException("unsupported_mode " + mode);
		};
		if (List.of("find_placement_sites", "check_position", "check_interaction").contains(mode)) return result;
		Level level = minecraft.level;
		Map<BlockPos, BlockPos> doors = new LinkedHashMap<>();
		for (BlockPos pos : result.observedPositions()) {
			if (!level.hasChunkAt(pos)) continue;
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof DoorBlock) doors.putIfAbsent(DoorPassageGeometry.lowerPos(pos, state), pos);
		}
		if (doors.isEmpty()) return result;
		StringBuilder text = new StringBuilder(result.text()).append("\nDoor passages (local collision heuristic; no route guarantee):");
		for (BlockPos door : doors.values().stream().limit(8).toList()) {
			DoorPassageGeometry.describe(level, door).ifPresent(description -> text.append('\n').append(description));
		}
		if (doors.size() > 8) text.append("\nAdditional door summaries omitted; narrow the query.");
		return new WorldQueryResult(text.toString(), result.observedPositions());
	}

	private static WorldQueryResult inspectArea(Level level, LocalPlayer player, QueryBounds bounds, JsonObject arguments) {
		ArrayList<BlockRecord> records = new ArrayList<>();
		int scanned = 0;
		for (BlockPos pos : bounds.positions()) {
			scanned++;
			if (!level.hasChunkAt(pos)) {
				records.add(BlockRecord.unloaded(pos, distance(player.blockPosition(), pos)));
				continue;
			}
			BlockState state = level.getBlockState(pos);
			records.add(BlockRecord.of(pos, state, distance(player.blockPosition(), pos)));
		}
		int maxResults = boundedInt(arguments, "maxResults", DEFAULT_MAX_RESULTS, 1, MAX_RESULTS);
		return areaResult(bounds, scanned, records, maxResults, stringArg(arguments, "detail").orElse("summary").equals("blocks"));
	}

	static WorldQueryResult areaResult(QueryBounds bounds, int scanned, List<BlockRecord> records, int maxResults) {
		return areaResult(bounds, scanned, records, maxResults, false);
	}

	static WorldQueryResult areaResult(QueryBounds bounds, int scanned, List<BlockRecord> records, int maxResults, boolean detailed) {
		List<BlockRecord> safeRecords = records == null ? List.of() : records;
		BlockPos center = bounds.center();
		List<BlockRecord> limited = safeRecords.stream()
			.sorted(Comparator.comparingInt((BlockRecord record) -> distance(center, record.pos()))
				.thenComparing(BlockRecord.ORDERING))
			.limit(maxResults)
			.toList();
		return new WorldQueryResult("Tool result for inspect_world: mode=inspect_area"
			+ " scope=" + bounds.scope()
			+ " bounds=" + bounds.compact()
			+ " scanned=" + scanned
			+ " matched=" + safeRecords.size()
			+ " returned=" + limited.size()
			+ " order=nearest_query_center queryCenter=" + compactPos(center) + " distanceOrigin=player"
			+ " truncated=" + (limited.size() < safeRecords.size())
			+ (detailed ? " blocks=" + formatRecords(limited, limited.size()) : summarizeArea(bounds, limited))
			+ (limited.size() < safeRecords.size()
				? "\nResult truncated. Use a smaller box or radius around the blocks you need; omitted positions have not been inspected."
				: ""), limited.stream().map(record -> record.pos().immutable()).toList());
	}

	/** Merge only fully observed, identical horizontal rectangles; never bridge gaps or states. */
	private static String summarizeArea(QueryBounds bounds, List<BlockRecord> records) {
		var cells = new java.util.TreeMap<BlockPos, BlockRecord>(Comparator.<BlockPos>comparingInt(BlockPos::getY)
			.thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX));
		for (var record : records) cells.put(record.pos(), record);
		var descriptions = new ArrayList<String>();
		boolean merged = false;
		while (!cells.isEmpty()) {
			var start = cells.firstEntry().getValue();
			BlockPos pos = start.pos();
			String material = start.materialDescription();
			int width = 1, depth = 1;
			while (sameMaterial(cells.get(pos.offset(width, 0, 0)), material)) width++;
			boolean nextRow = true;
			while (nextRow) {
				for (int x = 0; x < width; x++) if (!sameMaterial(cells.get(pos.offset(x, 0, depth)), material)) { nextRow = false; break; }
				if (nextRow) depth++;
			}
			int near = Integer.MAX_VALUE, far = Integer.MIN_VALUE;
			for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
				var cell = cells.remove(pos.offset(x, 0, z));
				near = Math.min(near, cell.distance()); far = Math.max(far, cell.distance());
			}
			merged |= width * depth >= 4;
			String shape = width * depth == 1 ? "At " + compactPos(pos) : width + "x" + depth + " horizontal patch at block Y=" + pos.getY()
				+ ", X=" + pos.getX() + ".." + (pos.getX() + width - 1) + ", Z=" + pos.getZ() + ".." + (pos.getZ() + depth - 1);
			descriptions.add(shape + ": " + start.semanticMaterial() + "; distance " + near + (near == far ? "" : ".." + far) + ".");
		}
		String summary = "\nObserved horizontal patches (X by Z; block Y, not feet Y). No clearance or route inferred."
			+ " Per-block distance detail omitted; request detail=blocks for exact records.\n" + String.join("\n", descriptions);
		String fallback = formatAreaRecords(bounds, records);
		return merged && summary.length() < fallback.length() ? summary : fallback;
	}

	private static boolean sameMaterial(BlockRecord record, String material) {
		return record != null && record.materialDescription().equals(material);
	}

	private static String formatAreaRecords(QueryBounds bounds, List<BlockRecord> records) {
		int width = bounds.max().getX() - bounds.min().getX() + 1;
		int height = bounds.max().getY() - bounds.min().getY() + 1;
		int depth = bounds.max().getZ() - bounds.min().getZ() + 1;
		if (!"box".equals(bounds.scope()) || width > 16 || depth > 16 || height > 8
			|| width * height * depth > 256 || records.size() < 4) {
			return " blocks=" + formatRecords(records, records.size());
		}
		List<String> legend = records.stream().map(BlockRecord::materialDescription).distinct().sorted().toList();
		Map<BlockPos, Integer> cells = new LinkedHashMap<>();
		for (BlockRecord record : records) cells.put(record.pos(), legend.indexOf(record.materialDescription()));
		StringBuilder text = new StringBuilder("\nformat=horizontal_layers; cells are legend numbers; ?=omitted, not observed."
			+ " Block Y labels occupied cells, not walking height. Columns increase X eastward; rows increase Z southward.\nlegend:");
		for (int index = 0; index < legend.size(); index++) text.append('\n').append(index).append('=').append(legend.get(index));
		text.append("\ncolumns X:");
		for (int x = bounds.min().getX(); x <= bounds.max().getX(); x++) text.append(' ').append(x);
		for (int y = bounds.min().getY(); y <= bounds.max().getY(); y++) {
			text.append("\nY=").append(y);
			for (int z = bounds.min().getZ(); z <= bounds.max().getZ(); z++) {
				text.append("\nZ=").append(z).append(':');
				for (int x = bounds.min().getX(); x <= bounds.max().getX(); x++) {
					Integer cell = cells.get(new BlockPos(x, y, z));
					text.append(' ').append(cell == null ? "?" : cell.toString());
				}
			}
		}
		return text.toString();
	}

	private static WorldQueryResult findBlocks(Level level, LocalPlayer player, QueryBounds bounds, JsonObject arguments) {
		List<String> blockIds = stringArrayArg(arguments, "blockIds");
		List<StateFilter> filters = stateFilters(arguments, "stateFilters");
		int maxResults = boundedInt(arguments, "maxResults", DEFAULT_MAX_RESULTS, 1, MAX_RESULTS);
		ArrayList<BlockRecord> matches = new ArrayList<>();
		int scanned = 0;
		for (BlockPos pos : bounds.positions()) {
			scanned++;
			if (scanned > SEARCH_BLOCK_CAP) {
				break;
			}
			if (!level.hasChunkAt(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (blockIds.contains(blockId(state)) && matchesFilters(state, filters)) {
				matches.add(BlockRecord.of(pos, state, distance(player.blockPosition(), pos)));
			}
		}
		matches.sort(BlockRecord.ORDERING);
		List<BlockRecord> limited = matches.stream().limit(maxResults).toList();
		return new WorldQueryResult("Tool result for inspect_world: mode=find_blocks"
			+ " scope=" + bounds.scope()
			+ " bounds=" + bounds.compact()
			+ " scanned=" + Math.min(scanned, SEARCH_BLOCK_CAP)
			+ " matched=" + matches.size()
			+ " returned=" + limited.size()
			+ " blocks=" + formatRecords(limited, maxResults), limited.stream().map(record -> record.pos().immutable()).toList());
	}

	private static WorldQueryResult findPlacementSites(Level level, LocalPlayer player, QueryBounds bounds, JsonObject arguments) {
		PlacementConstraints constraints = PlacementConstraints.from(arguments);
		int maxResults = boundedInt(arguments, "maxResults", DEFAULT_MAX_RESULTS, 1, MAX_RESULTS);
		ArrayList<PlacementSite> matches = new ArrayList<>();
		int scanned = 0;
		for (BlockPos pos : bounds.positions()) {
			scanned++;
			if (scanned > SEARCH_BLOCK_CAP) {
				break;
			}
			Optional<PlacementSite> site = placementSite(level, player, pos, constraints);
			site.ifPresent(matches::add);
		}
		matches.sort(PlacementSite.ORDERING);
		List<PlacementSite> limited = matches.stream().limit(maxResults).toList();
		return new WorldQueryResult("Tool result for inspect_world: mode=find_placement_sites"
			+ " scope=" + bounds.scope()
			+ " bounds=" + bounds.compact()
			+ " scanned=" + Math.min(scanned, SEARCH_BLOCK_CAP)
			+ " matched=" + matches.size()
			+ " returned=" + limited.size()
			+ " sites=" + (stringArg(arguments, "detail").orElse("summary").equals("blocks")
				? limited.stream().map(PlacementSite::compact).collect(Collectors.joining(", ", "[", "]")) : formatSites(limited)), observedPlacementPositions(limited));
	}

	private static Optional<PlacementSite> placementSite(
		Level level,
		LocalPlayer player,
		BlockPos targetPos,
		PlacementConstraints constraints
	) {
		if (!level.hasChunkAt(targetPos) || !level.hasChunkAt(targetPos.below())) {
			return Optional.empty();
		}
		BlockState target = level.getBlockState(targetPos);
		if (!constraints.targetMaterial().matches(target)) {
			return Optional.empty();
		}
		BlockPos supportPos = targetPos.below();
		BlockState support = level.getBlockState(supportPos);
		if (!constraints.supportBlockIds().isEmpty() && !constraints.supportBlockIds().contains(blockId(support))) {
			return Optional.empty();
		}
		if (!matchesFilters(support, constraints.supportStateFilters())) {
			return Optional.empty();
		}
		if (constraints.requireSolidTopSupport() && !support.isFaceSturdy(level, supportPos, Direction.UP)) {
			return Optional.empty();
		}
		if (constraints.requireAirAbove()) {
			BlockPos abovePos = targetPos.above();
			if (!level.hasChunkAt(abovePos)) {
				return Optional.empty();
			}
			BlockState above = level.getBlockState(abovePos);
			if (!above.isAir() && !above.canBeReplaced()) {
				return Optional.empty();
			}
		}
		Optional<BlockPos> standableAdjacent = standableAdjacentPosition(level, targetPos);
		if (constraints.requireStandableAdjacent() && standableAdjacent.isEmpty()) {
			return Optional.empty();
		}
		if (constraints.requireWithinInteractionRange() && !withinInteractionRange(player, targetPos)) {
			return Optional.empty();
		}
		BlockPos nearbyRequiredPos = null;
		if (!constraints.nearbyRequiredBlockIds().isEmpty()) {
			Optional<BlockPos> nearbyRequired = nearbyRequiredBlock(level, targetPos, constraints.nearbyRequiredBlockIds(), constraints.nearbyRequiredHorizontalRadius(), constraints.nearbyRequiredVerticalRadius());
			if (nearbyRequired.isEmpty()) {
				return Optional.empty();
			}
			nearbyRequiredPos = nearbyRequired.get();
		}
		return Optional.of(new PlacementSite(
			targetPos,
			blockId(target),
			properties(target),
			supportPos,
			blockId(support),
			properties(support),
			distance(player.blockPosition(), targetPos),
			standableAdjacent.orElse(null),
			nearbyRequiredPos,
			withinInteractionRange(player, targetPos)
		));
	}

	private static Optional<BlockPos> nearbyRequiredBlock(
		Level level,
		BlockPos origin,
		List<String> blockIds,
		int horizontalRadius,
		int verticalRadius
	) {
		for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
			for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
				for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
					BlockPos pos = origin.offset(dx, dy, dz);
					if (level.hasChunkAt(pos) && blockIds.contains(blockId(level.getBlockState(pos)))) {
						return Optional.of(pos.immutable());
					}
				}
			}
		}
		return Optional.empty();
	}

	private static List<BlockPos> observedPlacementPositions(List<PlacementSite> sites) {
		ArrayList<BlockPos> positions = new ArrayList<>();
		for (PlacementSite site : sites) {
			positions.add(site.targetPos().immutable());
			positions.add(site.supportPos().immutable());
			if (site.standableAdjacent() != null) {
				positions.add(site.standableAdjacent().immutable());
			}
			if (site.nearbyRequiredPos() != null) {
				positions.add(site.nearbyRequiredPos().immutable());
			}
		}
		return List.copyOf(positions);
	}

	private static Optional<BlockPos> standableAdjacentPosition(Level level, BlockPos targetPos) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos pos = targetPos.relative(direction);
			if (isStandable(level, pos)) {
				return Optional.of(pos);
			}
		}
		return Optional.empty();
	}

	private static boolean isStandable(Level level, BlockPos pos) {
		if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above()) || !level.hasChunkAt(pos.below())) {
			return false;
		}
		BlockState feet = level.getBlockState(pos);
		BlockState head = level.getBlockState(pos.above());
		BlockState floor = level.getBlockState(pos.below());
		return (feet.isAir() || feet.canBeReplaced())
			&& (head.isAir() || head.canBeReplaced())
			&& floor.isFaceSturdy(level, pos.below(), Direction.UP);
	}

	private static boolean withinInteractionRange(LocalPlayer player, BlockPos pos) {
		return player.distanceToSqr(Vec3.atCenterOf(pos)) <= INTERACTION_RANGE_SQUARED;
	}

	private static String formatRecords(List<BlockRecord> records, int maxResults) {
		if (records == null || records.isEmpty()) {
			return "none";
		}
		return records.stream()
			.limit(maxResults)
			.map(BlockRecord::compact)
			.collect(Collectors.joining(", ", "[", "]"));
	}

	static String formatSites(List<PlacementSite> sites) {
		if (sites.isEmpty()) return "No placement sites.";
		boolean allAir = sites.stream().allMatch(site -> site.targetBlockId().equals("minecraft:air") && site.targetProperties().isEmpty());
		boolean allBelow = sites.stream().allMatch(site -> site.supportPos().equals(site.targetPos().below()));
		boolean allReach = sites.stream().allMatch(PlacementSite::withinInteractionRange);
		boolean noRequired = sites.stream().allMatch(site -> site.nearbyRequiredPos() == null);
		var text = new StringBuilder("\nPlacement targets are block cells.");
		if (allAir) text.append(" All listed targets are air.");
		if (allBelow) text.append(" Each support is directly below its target.");
		if (allReach) text.append(" All are within interaction range by distance only.");
		text.append(" Standing cells are geometrically standable; routes and support-face visibility are not verified.");
		if (noRequired) text.append(" No nearby required-block match reported.");
		for (var site : sites) {
			text.append("\n- ").append(compactPos(site.targetPos())).append(": ");
			if (!allAir) text.append("target ").append(PlannerStateText.item(site.targetBlockId())).append(site.targetProperties().isEmpty() ? "" : site.targetProperties()).append("; ");
			text.append(PlannerStateText.item(site.supportBlockId())).append(" support");
			if (!allBelow) text.append(" at ").append(compactPos(site.supportPos()));
			if (!site.supportProperties().isEmpty()) text.append(' ').append(site.supportProperties());
			text.append("; distance ").append(site.distance());
			text.append(site.standableAdjacent() == null ? "; no adjacent standing position found" : "; stand at " + compactPos(site.standableAdjacent()));
			if (!allReach) text.append(site.withinInteractionRange() ? "; within reach" : "; out of reach");
			if (!noRequired) text.append("; nearby required-block match ").append(site.nearbyRequiredPos() == null ? "none" : compactPos(site.nearbyRequiredPos()));
		}
		return text.toString();
	}

	private static List<StateFilter> stateFilters(JsonObject arguments, String key) {
		if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return List.of();
		}
		ArrayList<StateFilter> filters = new ArrayList<>();
		JsonArray array = arguments.getAsJsonArray(key);
		for (JsonElement element : array) {
			String raw = element.getAsString();
			int separator = raw.indexOf('=');
			filters.add(new StateFilter(raw.substring(0, separator), raw.substring(separator + 1)));
		}
		return List.copyOf(filters);
	}

	private static boolean matchesFilters(BlockState state, List<StateFilter> filters) {
		for (StateFilter filter : filters) {
			if (!filter.value().equals(propertyValue(state, filter.name()))) {
				return false;
			}
		}
		return true;
	}

	private static String blockId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static Map<String, String> properties(BlockState state) {
		LinkedHashMap<String, String> properties = new LinkedHashMap<>();
		for (Property<?> property : state.getProperties()) {
			properties.put(property.getName(), propertyValue(state, property));
		}
		return java.util.Collections.unmodifiableMap(properties);
	}

	private static String propertyValue(BlockState state, String name) {
		for (Property<?> property : state.getProperties()) {
			if (property.getName().equals(name)) {
				return propertyValue(state, property);
			}
		}
		return null;
	}

	private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	private static int distance(BlockPos origin, BlockPos pos) {
		return Math.max(
			Math.max(Math.abs(pos.getX() - origin.getX()), Math.abs(pos.getY() - origin.getY())),
			Math.abs(pos.getZ() - origin.getZ())
		);
	}

	private static int boundedInt(JsonObject arguments, String key, int defaultValue, int min, int max) {
		if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return defaultValue;
		}
		int value = arguments.get(key).getAsInt();
		return Math.max(min, Math.min(value, max));
	}

	private static List<String> stringArrayArg(JsonObject arguments, String key) {
		if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return List.of();
		}
		ArrayList<String> values = new ArrayList<>();
		for (JsonElement element : arguments.getAsJsonArray(key)) {
			values.add(element.getAsString());
		}
		return List.copyOf(values);
	}

	private static Optional<String> stringArg(JsonObject arguments, String key) {
		if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return Optional.empty();
		}
		String value = arguments.get(key).getAsString();
		return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
	}

	private static boolean booleanArg(JsonObject arguments, String key, boolean defaultValue) {
		if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return defaultValue;
		}
		return arguments.get(key).getAsBoolean();
	}

	private static void ensureWithinDistance(QueryBounds bounds, BlockPos playerPos) {
		for (BlockPos corner : bounds.corners()) {
			if (distance(playerPos, corner) > MAX_DISTANCE_FROM_PLAYER) {
				throw new WorldQueryException("target_too_far maxDistance=" + MAX_DISTANCE_FROM_PLAYER);
			}
		}
	}

	private static void ensureWithinBlockCap(QueryBounds bounds) {
		long blockCount = bounds.blockCount();
		if (blockCount > SEARCH_BLOCK_CAP) {
			throw new WorldQueryException("query_too_large maxBlocks=" + SEARCH_BLOCK_CAP + " requestedBlocks=" + blockCount);
		}
	}

	private static String safeMessage(RuntimeException exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
	}

	record BlockRecord(BlockPos pos, String blockId, Map<String, String> properties, boolean loaded, boolean replaceable, boolean air, boolean fluid, int distance) {
		static final Comparator<BlockRecord> ORDERING = Comparator
			.comparingInt(BlockRecord::distance)
			.thenComparingInt(record -> record.pos().getX())
			.thenComparingInt(record -> record.pos().getY())
			.thenComparingInt(record -> record.pos().getZ());

		static BlockRecord unloaded(BlockPos pos, int distance) {
			return new BlockRecord(pos, "unloaded", Map.of(), false, false, false, false, distance);
		}

		static BlockRecord of(BlockPos pos, BlockState state, int distance) {
			return new BlockRecord(
				pos,
				CurrentWorldQueryService.blockId(state),
				CurrentWorldQueryService.properties(state),
				true,
				state.canBeReplaced(),
				state.isAir(),
				!state.getFluidState().isEmpty(),
				distance
			);
		}

		String compact() {
			return "{pos=" + compactPos(pos)
				+ ", " + materialDescription()
				+ ", distance=" + distance
				+ "}";
		}

		String semanticMaterial() {
			if (!loaded) return "unknown (unloaded)";
			return PlannerStateText.item(blockId) + (properties.isEmpty() ? "" : " " + new java.util.TreeMap<>(properties))
				+ (replaceable ? "; replaceable" : "") + (fluid ? "; contains fluid" : "");
		}

		String materialDescription() {
			return "id=" + PlannerStateText.item(blockId)
				+ (properties.isEmpty() ? "" : ", state=" + properties)
				+ ", loaded=" + loaded
				+ ", replaceable=" + replaceable
				+ ", air=" + air
				+ ", fluid=" + fluid;
		}
	}

	record PlacementSite(
		BlockPos targetPos,
		String targetBlockId,
		Map<String, String> targetProperties,
		BlockPos supportPos,
		String supportBlockId,
		Map<String, String> supportProperties,
		int distance,
		BlockPos standableAdjacent,
		BlockPos nearbyRequiredPos,
		boolean withinInteractionRange
	) {
		static final Comparator<PlacementSite> ORDERING = Comparator
			.comparingInt(PlacementSite::distance)
			.thenComparingInt(site -> site.targetPos().getX())
			.thenComparingInt(site -> site.targetPos().getY())
			.thenComparingInt(site -> site.targetPos().getZ());

		String compact() {
			return "{targetPos=" + compactPos(targetPos)
				+ ", targetBlockId=" + PlannerStateText.item(targetBlockId)
				+ (targetProperties.isEmpty() ? "" : ", targetState=" + targetProperties)
				+ ", supportPos=" + compactPos(supportPos)
				+ ", supportBlockId=" + PlannerStateText.item(supportBlockId)
				+ (supportProperties.isEmpty() ? "" : ", supportState=" + supportProperties)
				+ ", distance=" + distance
				+ ", standableAdjacent=" + (standableAdjacent == null ? "none" : compactPos(standableAdjacent))
				+ ", nearbyRequiredPos=" + (nearbyRequiredPos == null ? "none" : compactPos(nearbyRequiredPos))
				+ ", withinInteractionRange=" + withinInteractionRange
				+ "}";
		}
	}

	public record WorldQueryResult(String text, List<BlockPos> observedPositions) {
		public WorldQueryResult {
			text = text == null ? "" : text;
			observedPositions = observedPositions == null
				? List.of()
				: observedPositions.stream()
					.filter(Objects::nonNull)
					.map(BlockPos::immutable)
					.toList();
		}
	}

	private record StateFilter(String name, String value) {
	}

	private record PlacementConstraints(
		TargetMaterial targetMaterial,
		List<String> supportBlockIds,
		List<StateFilter> supportStateFilters,
		boolean requireSolidTopSupport,
		boolean requireAirAbove,
		boolean requireStandableAdjacent,
		boolean requireWithinInteractionRange,
		List<String> nearbyRequiredBlockIds,
		int nearbyRequiredHorizontalRadius,
		int nearbyRequiredVerticalRadius
	) {
		static PlacementConstraints from(JsonObject arguments) {
			return new PlacementConstraints(
				TargetMaterial.from(stringArg(arguments, "targetMaterial").orElse("air_or_replaceable")),
				stringArrayArg(arguments, "supportBlockIds"),
				stateFilters(arguments, "supportStateFilters"),
				booleanArg(arguments, "requireSolidTopSupport", false),
				booleanArg(arguments, "requireAirAbove", false),
				booleanArg(arguments, "requireStandableAdjacent", true),
				booleanArg(arguments, "requireWithinInteractionRange", false),
				stringArrayArg(arguments, "nearbyRequiredBlockIds"),
				boundedInt(arguments, "nearbyRequiredHorizontalRadius", 4, 0, MAX_HORIZONTAL_RADIUS),
				boundedInt(arguments, "nearbyRequiredVerticalRadius", 1, 0, MAX_VERTICAL_RADIUS)
			);
		}
	}

	private enum TargetMaterial {
		AIR,
		REPLACEABLE,
		AIR_OR_REPLACEABLE;

		static TargetMaterial from(String value) {
			return switch (value) {
				case "air" -> AIR;
				case "replaceable" -> REPLACEABLE;
				case "air_or_replaceable" -> AIR_OR_REPLACEABLE;
				default -> throw new WorldQueryException("unsupported_targetMaterial " + value);
			};
		}

		boolean matches(BlockState state) {
			return switch (this) {
				case AIR -> state.isAir();
				case REPLACEABLE -> !state.isAir() && state.canBeReplaced();
				case AIR_OR_REPLACEABLE -> state.isAir() || state.canBeReplaced();
			};
		}
	}

	record QueryBounds(String scope, BlockPos min, BlockPos max) {
		BlockPos center() {
			return new BlockPos(min.getX() + (max.getX() - min.getX()) / 2,
				min.getY() + (max.getY() - min.getY()) / 2,
				min.getZ() + (max.getZ() - min.getZ()) / 2);
		}

		static QueryBounds from(BlockPos playerPos, JsonObject arguments) {
			String scope = stringArg(arguments, "scope").orElseThrow(() -> new WorldQueryException("scope is required"));
			return switch (scope) {
				case "self" -> {
					int horizontalRadius = boundedInt(arguments, "horizontalRadius", DEFAULT_HORIZONTAL_RADIUS, 0, MAX_HORIZONTAL_RADIUS);
					int verticalRadius = boundedInt(arguments, "verticalRadius", DEFAULT_VERTICAL_RADIUS, 0, MAX_VERTICAL_RADIUS);
					yield centered(scope, playerPos, horizontalRadius, verticalRadius);
				}
				case "center" -> {
					BlockPos center = new BlockPos(intArg(arguments, "x"), intArg(arguments, "y"), intArg(arguments, "z"));
					int horizontalRadius = boundedInt(arguments, "horizontalRadius", DEFAULT_HORIZONTAL_RADIUS, 0, MAX_HORIZONTAL_RADIUS);
					int verticalRadius = boundedInt(arguments, "verticalRadius", DEFAULT_VERTICAL_RADIUS, 0, MAX_VERTICAL_RADIUS);
					yield centered(scope, center, horizontalRadius, verticalRadius);
				}
				case "box" -> box(
					new BlockPos(intArg(arguments, "x1"), intArg(arguments, "y1"), intArg(arguments, "z1")),
					new BlockPos(intArg(arguments, "x2"), intArg(arguments, "y2"), intArg(arguments, "z2"))
				);
				default -> throw new WorldQueryException("unsupported_scope " + scope);
			};
		}

		private static QueryBounds centered(String scope, BlockPos center, int horizontalRadius, int verticalRadius) {
			return new QueryBounds(
				scope,
				center.offset(-horizontalRadius, -verticalRadius, -horizontalRadius),
				center.offset(horizontalRadius, verticalRadius, horizontalRadius)
			);
		}

		private static QueryBounds box(BlockPos left, BlockPos right) {
			return new QueryBounds(
				"box",
				new BlockPos(Math.min(left.getX(), right.getX()), Math.min(left.getY(), right.getY()), Math.min(left.getZ(), right.getZ())),
				new BlockPos(Math.max(left.getX(), right.getX()), Math.max(left.getY(), right.getY()), Math.max(left.getZ(), right.getZ()))
			);
		}

		List<BlockPos> corners() {
			return List.of(
				new BlockPos(min.getX(), min.getY(), min.getZ()),
				new BlockPos(min.getX(), min.getY(), max.getZ()),
				new BlockPos(min.getX(), max.getY(), min.getZ()),
				new BlockPos(min.getX(), max.getY(), max.getZ()),
				new BlockPos(max.getX(), min.getY(), min.getZ()),
				new BlockPos(max.getX(), min.getY(), max.getZ()),
				new BlockPos(max.getX(), max.getY(), min.getZ()),
				new BlockPos(max.getX(), max.getY(), max.getZ())
			);
		}

		List<BlockPos> positions() {
			ArrayList<BlockPos> positions = new ArrayList<>();
			for (int x = min.getX(); x <= max.getX(); x++) {
				for (int y = min.getY(); y <= max.getY(); y++) {
					for (int z = min.getZ(); z <= max.getZ(); z++) {
						positions.add(new BlockPos(x, y, z));
					}
				}
			}
			return List.copyOf(positions);
		}

		long blockCount() {
			return (long) (max.getX() - min.getX() + 1)
				* (long) (max.getY() - min.getY() + 1)
				* (long) (max.getZ() - min.getZ() + 1);
		}

		String compact() {
			return compactPos(min) + ".." + compactPos(max);
		}
	}

	private static int intArg(JsonObject arguments, String key) {
		if (arguments == null || !arguments.has(key) || !arguments.get(key).isJsonPrimitive()) {
			throw new WorldQueryException(key + " is required");
		}
		return arguments.get(key).getAsInt();
	}

	private static String compactPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static final class WorldQueryException extends RuntimeException {
		private WorldQueryException(String message) {
			super(message);
		}
	}
}
