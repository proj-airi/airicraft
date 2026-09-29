package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.spatial.SurfaceTerrain;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class WorldFeatureSearchService implements WorldFeatureSearchTool {
	static final int DEFAULT_MAX_DISTANCE_BLOCKS = 192;
	static final int MAX_DISTANCE_BLOCKS = 256;
	static final int DEFAULT_LIMIT = 3;
	static final int MAX_LIMIT = 8;
	static final int DEFAULT_MIN_CONNECTED_WATER_SOURCES = 8;
	static final int DEFAULT_MIN_TREE_COUNT = 6;
	static final int MAX_CONNECTED_WATER_SCAN = 4096;
	private static final int WATER_SURFACE_SCAN_DEPTH = 5;
	private static final int FOREST_SURFACE_SCAN_DEPTH = 24;
	private static final int FOREST_CLUSTER_DISTANCE = 8;

	private final Supplier<Minecraft> clientSupplier;

	public WorldFeatureSearchService(Supplier<Minecraft> clientSupplier) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
	}

	@Override
	public CompletableFuture<WorldFeatureSearchResult> findFeaturesDetailed(JsonObject arguments) {
		Minecraft minecraft = clientSupplier.get();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			return CompletableFuture.completedFuture(new WorldFeatureSearchResult("WORLD_UNAVAILABLE: world_not_loaded", List.of()));
		}
		try {
			SearchRequest request = SearchRequest.from(arguments == null ? new JsonObject() : arguments);
			return CompletableFuture.completedFuture(search(new MinecraftWorldFeatureAccess(minecraft.level), minecraft.player.blockPosition(), request));
		}
		catch (WorldFeatureSearchException exception) {
			return CompletableFuture.completedFuture(new WorldFeatureSearchResult("TOOL_ERROR: find_world_features " + exception.getMessage(), List.of()));
		}
		catch (RuntimeException exception) {
			return CompletableFuture.completedFuture(new WorldFeatureSearchResult("TOOL_ERROR: find_world_features " + safeMessage(exception), List.of()));
		}
	}

	static WorldFeatureSearchResult search(WorldFeatureAccess access, BlockPos origin, JsonObject arguments) {
		return search(access, origin, SearchRequest.from(arguments == null ? new JsonObject() : arguments));
	}

	static WorldFeatureSearchResult search(WorldFeatureAccess access, BlockPos origin, SearchRequest request) {
		Objects.requireNonNull(access, "access");
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(request, "request");
		List<FeatureCandidate> candidates = switch (request.featureKind()) {
			case WATER_BODY -> findWaterBodies(access, origin, request);
			case FOREST -> findForests(access, origin, request);
		};
		List<FeatureCandidate> limited = candidates.stream()
			.sorted(FeatureCandidate.ORDERING)
			.limit(request.limit())
			.toList();
		return new WorldFeatureSearchResult(resultText(request, limited), observedPositions(limited));
	}

	private static List<FeatureCandidate> findWaterBodies(WorldFeatureAccess access, BlockPos origin, SearchRequest request) {
		ArrayList<FeatureCandidate> candidates = new ArrayList<>();
		HashSet<BlockPos> visited = new HashSet<>();
		access.forEachCandidatePosition(origin, request.maxDistanceBlocks(), request.direction(), FeatureKind.WATER_BODY, pos -> {
			BlockPos seed = pos.immutable();
			if (!request.accepts(origin, seed) || visited.contains(seed) || !access.sample(seed).plainWaterSource()) {
				return;
			}
			WaterComponent component = floodWaterComponent(access, origin, seed, request.maxDistanceBlocks(), visited);
			if (component.sources().size() < request.minConnectedWaterSources()) {
				return;
			}
			FeatureCandidate candidate = waterCandidate(component, access, origin, candidates.size() + 1, request.minConnectedWaterSources(), request.surfaceOnly());
			if (candidate == null) return;
			if (request.direction().isPresent() && request.direction().get() != directionFrom(origin, candidate.targetPos())) {
				return;
			}
			candidates.add(candidate);
		});
		return candidates;
	}

	private static WaterComponent floodWaterComponent(
		WorldFeatureAccess access,
		BlockPos origin,
		BlockPos seed,
		int maxDistanceBlocks,
		Set<BlockPos> visited
	) {
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		ArrayList<BlockPos> sources = new ArrayList<>();
		visited.add(seed);
		queue.add(seed);
		boolean capped = false;
		while (!queue.isEmpty()) {
			BlockPos pos = queue.removeFirst();
			sources.add(pos);
			if (sources.size() >= MAX_CONNECTED_WATER_SCAN) {
				capped = !queue.isEmpty();
				break;
			}
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.relative(direction).immutable();
				if (visited.contains(next) || distance(origin, next) > maxDistanceBlocks || !access.isLoaded(next)) {
					continue;
				}
				if (!access.sample(next).plainWaterSource()) {
					continue;
				}
				visited.add(next);
				queue.add(next);
			}
		}
		return new WaterComponent(List.copyOf(sources), capped);
	}

	private static FeatureCandidate waterCandidate(
		WaterComponent component,
		WorldFeatureAccess access,
		BlockPos origin,
		int index,
		int minConnectedWaterSources,
		boolean surfaceOnly
	) {
		List<BlockPos> eligible = component.sources().stream().filter(pos -> !surfaceOnly || access.isSurface(pos)).toList();
		if (eligible.isEmpty()) return null;
		BlockPos center = center(eligible);
		TargetSelection target = targetWithStand(access, eligible, origin, surfaceOnly);
		LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("connectedWaterSources", component.sources().size());
		evidence.put("requiredConnectedWaterSources", minConnectedWaterSources);
		evidence.put("capped", component.capped());
		if (surfaceOnly) evidence.put("surfaceWaterSources", eligible.size());
		double confidence = Math.min(1.0D, component.sources().size() / (double) (minConnectedWaterSources * 2));
		return new FeatureCandidate(
			"water_body-" + index,
			FeatureKind.WATER_BODY,
			center,
			target.targetPos(),
			target.standPos().orElse(null),
			distance(origin, target.targetPos()),
			directionFrom(origin, target.targetPos()),
			evidence,
			confidence
		);
	}

	private static List<FeatureCandidate> findForests(WorldFeatureAccess access, BlockPos origin, SearchRequest request) {
		HashMap<Column, BlockPos> stemsByColumn = new HashMap<>();
		access.forEachCandidatePosition(origin, request.maxDistanceBlocks(), request.direction(), FeatureKind.FOREST, pos -> {
			BlockPos immutable = pos.immutable();
			if (!request.accepts(origin, immutable) || !access.sample(immutable).log()
				|| (request.surfaceOnly() && !access.isSurface(immutable))) {
				return;
			}
			if (!hasNearbyLeaves(access, immutable)) {
				return;
			}
			Column column = new Column(immutable.getX(), immutable.getZ());
			stemsByColumn.merge(column, immutable, (left, right) -> distance(origin, left) <= distance(origin, right) ? left : right);
		});
		ArrayList<TreeStem> stems = stemsByColumn.entrySet().stream()
			.map(entry -> new TreeStem(entry.getKey(), entry.getValue()))
			.sorted(TreeStem.ORDERING)
			.collect(Collectors.toCollection(ArrayList::new));
		ArrayList<FeatureCandidate> candidates = new ArrayList<>();
		HashSet<Column> visited = new HashSet<>();
		for (TreeStem stem : stems) {
			if (!visited.add(stem.column())) {
				continue;
			}
			ArrayList<TreeStem> cluster = new ArrayList<>();
			ArrayDeque<TreeStem> queue = new ArrayDeque<>();
			cluster.add(stem);
			queue.add(stem);
			while (!queue.isEmpty()) {
				TreeStem current = queue.removeFirst();
				for (TreeStem other : stems) {
					if (visited.contains(other.column())) {
						continue;
					}
					if (columnDistance(current.column(), other.column()) > FOREST_CLUSTER_DISTANCE) {
						continue;
					}
					visited.add(other.column());
					cluster.add(other);
					queue.add(other);
				}
			}
			if (cluster.size() < request.minTreeCount()) {
				continue;
			}
			FeatureCandidate candidate = forestCandidate(cluster, access, origin, candidates.size() + 1, request.minTreeCount(), request.surfaceOnly());
			if (request.direction().isPresent() && request.direction().get() != candidate.direction()) {
				continue;
			}
			candidates.add(candidate);
		}
		return candidates;
	}

	private static FeatureCandidate forestCandidate(
		List<TreeStem> cluster,
		WorldFeatureAccess access,
		BlockPos origin,
		int index,
		int minTreeCount,
		boolean surfaceOnly
	) {
		List<BlockPos> stemPositions = cluster.stream().map(TreeStem::pos).toList();
		BlockPos center = center(stemPositions);
		TargetSelection target = targetWithStand(access, stemPositions, origin, surfaceOnly);
		LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("treeStems", cluster.size());
		evidence.put("requiredTreeStems", minTreeCount);
		evidence.put("leafBacked", true);
		double confidence = Math.min(1.0D, cluster.size() / (double) (minTreeCount * 2));
		return new FeatureCandidate(
			"forest-" + index,
			FeatureKind.FOREST,
			center,
			target.targetPos(),
			target.standPos().orElse(null),
			distance(origin, target.targetPos()),
			directionFrom(origin, target.targetPos()),
			evidence,
			confidence
		);
	}

	private static boolean hasNearbyLeaves(WorldFeatureAccess access, BlockPos logPos) {
		for (int dx = -4; dx <= 4; dx++) {
			for (int dy = -2; dy <= 8; dy++) {
				for (int dz = -4; dz <= 4; dz++) {
					BlockPos pos = logPos.offset(dx, dy, dz);
					if (access.isLoaded(pos) && access.sample(pos).leaves()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static TargetSelection targetWithStand(WorldFeatureAccess access, List<BlockPos> positions, BlockPos origin, boolean surfaceOnly) {
		List<BlockPos> sorted = positions.stream()
			.map(BlockPos::immutable)
			.sorted(Comparator.comparingInt(pos -> distance(origin, pos)))
			.toList();
		for (BlockPos pos : sorted) {
			Optional<BlockPos> standPos = nearestStandableAdjacent(access, pos, origin, surfaceOnly);
			if (standPos.isPresent()) {
				return new TargetSelection(pos, standPos);
			}
		}
		return new TargetSelection(sorted.getFirst(), Optional.empty());
	}

	private static Optional<BlockPos> nearestStandableAdjacent(WorldFeatureAccess access, BlockPos targetPos, BlockPos origin, boolean surfaceOnly) {
		ArrayList<BlockPos> candidates = new ArrayList<>();
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			for (int dy = -1; dy <= 2; dy++) {
				candidates.add(targetPos.relative(direction).offset(0, dy, 0).immutable());
			}
		}
		return candidates.stream()
			.filter(access::isStandable)
			.filter(pos -> !surfaceOnly || access.isSurface(pos))
			.min(Comparator.comparingInt(pos -> distance(origin, pos)));
	}

	private static List<BlockPos> observedPositions(List<FeatureCandidate> candidates) {
		ArrayList<BlockPos> positions = new ArrayList<>();
		for (FeatureCandidate candidate : candidates) {
			positions.add(candidate.centerPos());
			positions.add(candidate.targetPos());
			if (candidate.standPos() != null) {
				positions.add(candidate.standPos());
			}
		}
		return List.copyOf(positions);
	}

	private static String resultText(SearchRequest request, List<FeatureCandidate> candidates) {
		return "Tool result for find_world_features: featureKind=" + request.featureKind().wireValue()
			+ " surfaceOnly=" + request.surfaceOnly()
			+ " direction=" + request.direction().map(SearchDirection::wireValue).orElse("any")
			+ " maxDistanceBlocks=" + request.maxDistanceBlocks()
			+ " returned=" + candidates.size()
			+ " features=" + formatCandidates(candidates);
	}

	private static String formatCandidates(List<FeatureCandidate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return "none";
		}
		return candidates.stream()
			.map(FeatureCandidate::compact)
			.collect(Collectors.joining(", ", "[", "]"));
	}

	private static BlockPos center(List<BlockPos> positions) {
		long x = 0L;
		long y = 0L;
		long z = 0L;
		for (BlockPos pos : positions) {
			x += pos.getX();
			y += pos.getY();
			z += pos.getZ();
		}
		int count = Math.max(1, positions.size());
		return new BlockPos(Math.round(x / (float) count), Math.round(y / (float) count), Math.round(z / (float) count));
	}

	private static SearchDirection directionFrom(BlockPos origin, BlockPos pos) {
		int dx = pos.getX() - origin.getX();
		int dz = pos.getZ() - origin.getZ();
		if (dx == 0 && dz == 0) {
			return SearchDirection.NORTH;
		}
		double degrees = Math.toDegrees(Math.atan2(dx, -dz));
		if (degrees < 0.0D) {
			degrees += 360.0D;
		}
		int octant = (int) Math.round(degrees / 45.0D) % 8;
		return SearchDirection.values()[octant];
	}

	private static int distance(BlockPos origin, BlockPos pos) {
		return Math.max(
			Math.max(Math.abs(pos.getX() - origin.getX()), Math.abs(pos.getY() - origin.getY())),
			Math.abs(pos.getZ() - origin.getZ())
		);
	}

	private static int horizontalDistance(BlockPos origin, BlockPos pos) {
		return Math.max(Math.abs(pos.getX() - origin.getX()), Math.abs(pos.getZ() - origin.getZ()));
	}

	private static int columnDistance(Column left, Column right) {
		return Math.max(Math.abs(left.x() - right.x()), Math.abs(left.z() - right.z()));
	}

	private static int boundedInt(JsonObject arguments, String key, int defaultValue, int min, int max) {
		if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return defaultValue;
		}
		int value = arguments.get(key).getAsInt();
		return Math.max(min, Math.min(value, max));
	}

	private static Optional<String> stringArg(JsonObject arguments, String key) {
		if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return Optional.empty();
		}
		String value = arguments.get(key).getAsString();
		return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
	}

	private static String compactPos(BlockPos pos) {
		return pos == null ? "none" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static String safeMessage(RuntimeException exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
	}

	record SearchRequest(
		FeatureKind featureKind,
		Optional<SearchDirection> direction,
		int maxDistanceBlocks,
		int limit,
		int minConnectedWaterSources,
		int minTreeCount,
		boolean surfaceOnly
	) {
		static SearchRequest from(JsonObject arguments) {
			return new SearchRequest(
				FeatureKind.from(stringArg(arguments, "featureKind").orElseThrow(() -> new WorldFeatureSearchException("featureKind is required"))),
				stringArg(arguments, "direction").map(SearchDirection::from),
				boundedInt(arguments, "maxDistanceBlocks", DEFAULT_MAX_DISTANCE_BLOCKS, 1, MAX_DISTANCE_BLOCKS),
				boundedInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT),
				boundedInt(arguments, "minConnectedWaterSources", DEFAULT_MIN_CONNECTED_WATER_SOURCES, 1, MAX_CONNECTED_WATER_SCAN),
				boundedInt(arguments, "minTreeCount", DEFAULT_MIN_TREE_COUNT, 1, 128),
				arguments.has("surfaceOnly") && arguments.get("surfaceOnly").getAsBoolean()
			);
		}

		boolean accepts(BlockPos origin, BlockPos pos) {
			if (horizontalDistance(origin, pos) > maxDistanceBlocks) {
				return false;
			}
			return direction.isEmpty() || direction.get() == directionFrom(origin, pos);
		}
	}

	enum FeatureKind {
		WATER_BODY("water_body"),
		FOREST("forest");

		private final String wireValue;

		FeatureKind(String wireValue) {
			this.wireValue = wireValue;
		}

		String wireValue() {
			return wireValue;
		}

		static FeatureKind from(String value) {
			for (FeatureKind kind : values()) {
				if (kind.wireValue.equals(value)) {
					return kind;
				}
			}
			throw new WorldFeatureSearchException("unsupported_featureKind " + value);
		}
	}

	enum SearchDirection {
		NORTH("north"),
		NORTHEAST("northeast"),
		EAST("east"),
		SOUTHEAST("southeast"),
		SOUTH("south"),
		SOUTHWEST("southwest"),
		WEST("west"),
		NORTHWEST("northwest");

		private final String wireValue;

		SearchDirection(String wireValue) {
			this.wireValue = wireValue;
		}

		String wireValue() {
			return wireValue;
		}

		static SearchDirection from(String value) {
			for (SearchDirection direction : values()) {
				if (direction.wireValue.equals(value)) {
					return direction;
				}
			}
			throw new WorldFeatureSearchException("unsupported_direction " + value);
		}
	}

	interface WorldFeatureAccess {
		void forEachCandidatePosition(
			BlockPos origin,
			int maxDistanceBlocks,
			Optional<SearchDirection> direction,
			FeatureKind featureKind,
			Consumer<BlockPos> consumer
		);

		boolean isLoaded(BlockPos pos);

		SampledBlock sample(BlockPos pos);

		boolean isStandable(BlockPos pos);

		boolean isSurface(BlockPos pos);
	}

	record SampledBlock(boolean plainWaterSource, boolean log, boolean leaves) {
		static final SampledBlock EMPTY = new SampledBlock(false, false, false);
	}

	public record WorldFeatureSearchResult(String text, List<BlockPos> observedPositions) {
		public WorldFeatureSearchResult {
			text = text == null ? "" : text;
			observedPositions = observedPositions == null
				? List.of()
				: observedPositions.stream()
					.filter(Objects::nonNull)
					.map(BlockPos::immutable)
					.toList();
		}
	}

	private record WaterComponent(List<BlockPos> sources, boolean capped) {
	}

	private record TargetSelection(BlockPos targetPos, Optional<BlockPos> standPos) {
		TargetSelection {
			targetPos = targetPos.immutable();
			standPos = standPos == null ? Optional.empty() : standPos.map(BlockPos::immutable);
		}
	}

	private record FeatureCandidate(
		String id,
		FeatureKind kind,
		BlockPos centerPos,
		BlockPos targetPos,
		BlockPos standPos,
		int distanceBlocks,
		SearchDirection direction,
		Map<String, Object> evidence,
		double confidence
	) {
		static final Comparator<FeatureCandidate> ORDERING = Comparator
			.comparingInt(FeatureCandidate::distanceBlocks)
			.thenComparing(candidate -> candidate.kind().wireValue())
			.thenComparingInt(candidate -> candidate.targetPos().getX())
			.thenComparingInt(candidate -> candidate.targetPos().getY())
			.thenComparingInt(candidate -> candidate.targetPos().getZ());

		String compact() {
			return "{id=" + id
				+ ", kind=" + kind.wireValue()
				+ ", centerPos=" + compactPos(centerPos)
				+ ", targetPos=" + compactPos(targetPos)
				+ ", standPos=" + compactPos(standPos)
				+ ", distanceBlocks=" + distanceBlocks
				+ ", direction=" + direction.wireValue()
				+ ", evidence=" + evidence
				+ ", confidence=" + String.format(Locale.ROOT, "%.2f", confidence)
				+ "}";
		}
	}

	private record Column(int x, int z) {
	}

	private record TreeStem(Column column, BlockPos pos) {
		static final Comparator<TreeStem> ORDERING = Comparator
			.comparingInt((TreeStem stem) -> stem.pos().getX())
			.thenComparingInt(stem -> stem.pos().getY())
			.thenComparingInt(stem -> stem.pos().getZ());
	}

	private static final class MinecraftWorldFeatureAccess implements WorldFeatureAccess {
		private final Level level;

		private MinecraftWorldFeatureAccess(Level level) {
			this.level = Objects.requireNonNull(level, "world");
		}

		@Override
		public void forEachCandidatePosition(
			BlockPos origin,
			int maxDistanceBlocks,
			Optional<SearchDirection> direction,
			FeatureKind featureKind,
			Consumer<BlockPos> consumer
		) {
			int scanDepth = featureKind == FeatureKind.WATER_BODY ? WATER_SURFACE_SCAN_DEPTH : FOREST_SURFACE_SCAN_DEPTH;
			for (int dx = -maxDistanceBlocks; dx <= maxDistanceBlocks; dx++) {
				for (int dz = -maxDistanceBlocks; dz <= maxDistanceBlocks; dz++) {
					BlockPos columnPos = new BlockPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
					if (direction.isPresent() && direction.get() != directionFrom(origin, columnPos)) {
						continue;
					}
					if (!level.hasChunkAt(columnPos)) {
						continue;
					}
					int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, columnPos.getX(), columnPos.getZ());
					int minY = Math.max(level.getMinY(), topY - scanDepth);
					for (int y = topY; y >= minY; y--) {
						consumer.accept(new BlockPos(columnPos.getX(), y, columnPos.getZ()));
					}
				}
			}
		}

		@Override
		public boolean isLoaded(BlockPos pos) {
			return level.hasChunkAt(pos);
		}

		@Override
		public SampledBlock sample(BlockPos pos) {
			if (!level.hasChunkAt(pos)) {
				return SampledBlock.EMPTY;
			}
			BlockState state = level.getBlockState(pos);
			FluidState fluidState = state.getFluidState();
			boolean plainWaterSource = state.is(Blocks.WATER) && fluidState.is(FluidTags.WATER) && fluidState.isSource();
			return new SampledBlock(
				plainWaterSource,
				state.is(BlockTags.LOGS),
				state.is(BlockTags.LEAVES)
			);
		}

		@Override
		public boolean isSurface(BlockPos pos) {
			return level.hasChunkAt(pos) && pos.getY() >= SurfaceTerrain.groundY(level, pos);
		}

		@Override
		public boolean isStandable(BlockPos pos) {
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
	}

	private static final class WorldFeatureSearchException extends RuntimeException {
		private WorldFeatureSearchException(String message) {
			super(message);
		}
	}
}
