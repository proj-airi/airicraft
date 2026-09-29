package ai.moeru.airicraft.agent.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.*;
import java.util.function.Function;

/** Connected air describes chambers; supported movement edges describe usable routes. */
public final class CaveRouteMap {
	public enum Cell { UNKNOWN, OPEN, SUPPORT, BLOCKED, WATER, HAZARD }
	public static final class Grid {
		private static final Cell[] VALUES = Cell.values();
		private final BlockPos min;
		private final int sx, sy, sz;
		private final byte[] cells;
		public Grid(BlockPos origin, int radius, int verticalRadius, Function<BlockPos, Cell> read) {
			min = origin.offset(-radius, -verticalRadius, -radius);
			sx = sz = radius * 2 + 1;
			sy = verticalRadius * 2 + 1;
			cells = new byte[sx * sy * sz];
			for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++)
				cells[(y * sz + z) * sx + x] = (byte) read.apply(min.offset(x, y, z)).ordinal();
		}
		public Cell cell(BlockPos p) {
			int x = p.getX() - min.getX(), y = p.getY() - min.getY(), z = p.getZ() - min.getZ();
			if (x < 0 || x >= sx || y < 0 || y >= sy || z < 0 || z >= sz) return Cell.UNKNOWN;
			return VALUES[cells[(y * sz + z) * sx + x]];
		}
		public int sampledCells() { return cells.length; }
	}
	public record Destination(BlockPos position, int localFloorArea, boolean frontier, double cost, int steps) {}
	public record Result(String status, int airCells, int floorCells, int reachableCells, boolean truncated,
		List<Destination> destinations, List<BlockPos> route) {}
	private record QueueNode(BlockPos position, double cost) {}
	private static final int MAX_AIR = 32768;
	private static final Comparator<BlockPos> ORDER = Comparator.<BlockPos>comparingInt(BlockPos::getX)
		.thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ);

	public static Result map(Grid grid, BlockPos origin, BlockPos target, double opennessWeight) {
		if (!standable(grid, origin)) return new Result("origin_not_standable", 0, 0, 0, false, List.of(), List.of());
		Set<BlockPos> air = new HashSet<>();
		ArrayDeque<BlockPos> pending = new ArrayDeque<>();
		air.add(origin); pending.add(origin);
		boolean truncated = false;
		while (!pending.isEmpty()) {
			BlockPos p = pending.removeFirst();
			for (Direction d : Direction.values()) {
				BlockPos q = p.relative(d);
				if (grid.cell(q) != Cell.OPEN || air.contains(q)) continue;
				if (air.size() == MAX_AIR) { truncated = true; continue; }
				air.add(q); pending.add(q);
			}
		}
		Set<BlockPos> floors = new HashSet<>();
		for (BlockPos p : air) if (air.contains(p.above()) && standable(grid, p)) floors.add(p);
		Map<BlockPos, List<BlockPos>> edges = new HashMap<>();
		for (BlockPos p : floors) edges.put(p, neighbors(grid, floors, p));
		Map<BlockPos, Integer> areas = new HashMap<>();
		Map<BlockPos, Double> costs = new HashMap<>();
		Map<BlockPos, BlockPos> parents = new HashMap<>();
		Map<BlockPos, Integer> steps = new HashMap<>();
		steps.put(origin, 0);
		PriorityQueue<QueueNode> queue = new PriorityQueue<>(Comparator.comparingDouble(QueueNode::cost)
			.thenComparing(QueueNode::position, ORDER));
		costs.put(origin, 0.0); queue.add(new QueueNode(origin, 0));
		while (!queue.isEmpty()) {
			QueueNode node = queue.remove();
			if (node.cost() > costs.get(node.position())) continue;
			for (BlockPos q : edges.getOrDefault(node.position(), List.of())) {
				int area = areas.computeIfAbsent(q, p -> localArea(edges, p));
				double distance = Math.sqrt(q.distSqr(node.position()));
				double next = node.cost() + distance * (1 + opennessWeight * (1 - Math.min(area, 25) / 25.0));
				if (next >= costs.getOrDefault(q, Double.POSITIVE_INFINITY)) continue;
				costs.put(q, next); parents.put(q, node.position()); steps.put(q, steps.get(node.position()) + 1); queue.add(new QueueNode(q, next));
			}
		}
		List<Destination> candidates = new ArrayList<>();
		for (BlockPos p : costs.keySet()) {
			if (p.distSqr(origin) < 36) continue;
			boolean frontier = false;
			for (Direction d : Direction.Plane.HORIZONTAL) if (grid.cell(p.relative(d)) == Cell.UNKNOWN) frontier = true;
			candidates.add(new Destination(p, areas.computeIfAbsent(p, q -> localArea(edges, q)), frontier,
				costs.get(p), steps.get(p)));
		}
		candidates.sort(Comparator.comparingDouble((Destination d) -> -(d.localFloorArea()
			+ (d.frontier() ? 30 : 0) + Math.sqrt(d.position().distSqr(origin)) * .4 - d.cost() * .25))
			.thenComparing(Destination::position, ORDER));
		List<Destination> spread = new ArrayList<>();
		for (Destination d : candidates) {
			if (spread.stream().anyMatch(other -> other.position().distSqr(d.position()) < 36)) continue;
			spread.add(d);
			if (spread.size() == 4) break;
		}
		BlockPos destination = target != null ? target : spread.isEmpty() ? null : spread.getFirst().position();
		if (destination == null || !costs.containsKey(destination))
			return new Result(target == null ? "no_frontier" : "target_not_reachable", air.size(), floors.size(), costs.size(), truncated, List.copyOf(spread), List.of());
		List<BlockPos> path = route(parents, origin, destination);
		return new Result(path.size() > 256 ? "route_too_long" : "mapped", air.size(), floors.size(), costs.size(), truncated,
			List.copyOf(spread), path.size() > 256 ? List.of() : path);
	}
	private static boolean standable(Grid g, BlockPos p) {
		return g.cell(p) == Cell.OPEN && g.cell(p.above()) == Cell.OPEN && g.cell(p.below()) == Cell.SUPPORT;
	}
	private static List<BlockPos> neighbors(Grid grid, Set<BlockPos> floors, BlockPos p) {
		List<BlockPos> result = new ArrayList<>();
		for (Direction d : Direction.Plane.HORIZONTAL) {
			// Ordinary exploration must be able to retrace a ledge without building footholds.
			for (int dy = 1; dy >= -1; dy--) {
				BlockPos q = p.relative(d).above(dy);
				if (!floors.contains(q)) continue;
				boolean clear = dy <= 0 || grid.cell(p.above(2)) == Cell.OPEN;
				for (int y = q.getY() + 2; y <= p.getY() + 1; y++)
					clear &= grid.cell(new BlockPos(q.getX(), y, q.getZ())) == Cell.OPEN;
				if (clear) result.add(q);
			}
			// One-cell gaps only, with jump headroom and no known hazard beneath the gap.
			BlockPos middle = p.relative(d), q = p.relative(d, 2);
			if (floors.contains(q) && !floors.contains(middle) && grid.cell(middle) == Cell.OPEN
				&& grid.cell(middle.above()) == Cell.OPEN && grid.cell(middle.above(2)) == Cell.OPEN
				&& grid.cell(p.above(2)) == Cell.OPEN && grid.cell(q.above(2)) == Cell.OPEN
				&& grid.cell(middle.below()) != Cell.HAZARD && grid.cell(middle.below()) != Cell.UNKNOWN) result.add(q);
		}
		return List.copyOf(result);
	}
	private static int localArea(Map<BlockPos, List<BlockPos>> edges, BlockPos origin) {
		Set<BlockPos> reached = new HashSet<>();
		reached.add(origin);
		Set<BlockPos> frontier = Set.of(origin);
		for (int depth = 0; depth < 3; depth++) {
			Set<BlockPos> next = new HashSet<>();
			for (BlockPos p : frontier) for (BlockPos q : edges.getOrDefault(p, List.of()))
				if (reached.add(q)) next.add(q);
			frontier = next;
		}
		return reached.size();
	}
	private static List<BlockPos> route(Map<BlockPos, BlockPos> parents, BlockPos origin, BlockPos target) {
		LinkedList<BlockPos> path = new LinkedList<>();
		for (BlockPos p = target; p != null; p = parents.get(p)) { path.addFirst(p); if (p.equals(origin)) break; }
		return List.copyOf(path);
	}
	/** Keep turns, descents and jumps; flat/uphill runs contain at most four movement edges. */
	public static List<BlockPos> waypoints(List<BlockPos> route) {
		if (route.size() < 2) return List.of();
		List<BlockPos> result = new ArrayList<>();
		int anchor = 0;
		for (int i = 1; i < route.size(); i++) {
			BlockPos p = route.get(i), previous = route.get(i - 1);
			boolean turn = i == route.size() - 1 || !p.subtract(previous).equals(route.get(i + 1).subtract(p));
			boolean gap = Math.abs(p.getX() - previous.getX()) + Math.abs(p.getZ() - previous.getZ()) > 1;
			if (turn || gap || p.getY() < previous.getY() || i - anchor >= 4) {
				result.add(p); anchor = i;
			}
		}
		return List.copyOf(result);
	}
}
