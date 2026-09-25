package ai.moeru.airicraft.navigation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Weighted A* over the {@link Moves} catalog (see {@link SearchBudget#heuristicWeight()}). Returns a complete path, the best partial path when
 * a budget runs out or exploration reaches unloaded terrain, or the reason no path exists.
 */
public final class PathSearch {
	private static final double MIN_IMPROVEMENT = 0.01;
	private static final int CHECK_INTERVAL = 256;

	private PathSearch() {
	}

	public static SearchResult search(TerrainView terrain, MovementPolicy policy, GridPos start, Goal goal,
		SearchBudget budget, BooleanSupplier cancelled) {
		return new Run(new Moves(terrain, policy), start, goal, budget, cancelled).search();
	}

	private static final class Node {
		final int x;
		final int y;
		final int z;
		final double h;
		double g = Double.POSITIVE_INFINITY;
		double f;
		Node parent;
		int move = -1;
		int heapIndex = -1;
		boolean closed;
		/** Edits of the move that reached this node, seen as done when expanding it. */
		Moves.Overlay overlay;

		Node(int x, int y, int z, double h) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.h = h;
		}

		GridPos pos() {
			return new GridPos(x, y, z);
		}
	}

	private static final class Run {
		private final Moves moves;
		private final GridPos start;
		private final Goal goal;
		private final SearchBudget budget;
		private final BooleanSupplier cancelled;
		private final NodeMap nodes = new NodeMap();
		private final Heap open = new Heap();
		private final Moves.Result result = new Moves.Result();
		private final double weight;

		Run(Moves moves, GridPos start, Goal goal, SearchBudget budget, BooleanSupplier cancelled) {
			this.moves = moves;
			this.start = start;
			this.goal = goal;
			this.budget = budget;
			this.weight = budget.heuristicWeight();
			this.cancelled = cancelled == null ? () -> false : cancelled;
		}

		SearchResult search() {
			long started = System.nanoTime();
			Node origin = node(start.x(), start.y(), start.z());
			origin.g = 0;
			origin.f = origin.h * weight;
			open.add(origin);
			Node best = origin;
			int expanded = 0;
			while (!open.isEmpty()) {
				if (expanded % CHECK_INTERVAL == 0 && expanded > 0) {
					if (cancelled.getAsBoolean()) return new SearchResult.Cancelled(stats(expanded, started));
					if (System.nanoTime() - started > budget.timeoutNanos()) {
						return partial(best, SearchResult.Reason.TIME_BUDGET, expanded, started);
					}
				}
				if (expanded >= budget.maxExpanded()) return partial(best, SearchResult.Reason.NODE_BUDGET, expanded, started);
				Node current = open.poll();
				current.closed = true;
				expanded++;
				if (goal.isGoal(current.x, current.y, current.z)) {
					return new SearchResult.Found(path(current), stats(expanded, started));
				}
				if (current.h < best.h || current.h == best.h && current.g < best.g) best = current;
				moves.overlay(current.overlay);
				int kind = moves.probe(current.x, current.y, current.z);
				double base = moves.probeBase();
				if (kind == Moves.KIND_INVALID) {
					// The player may be mid-jump or knocked back; plan as if standing on the cell.
					if (current != origin) continue;
					kind = Moves.KIND_STAND;
					base = current.y;
				}
				for (int move = 0; move < Moves.COUNT; move++) {
					if (!moves.evaluate(move, current.x, current.y, current.z, kind, base, result)) continue;
					double g = current.g + result.cost;
					Node next = node(result.x, result.y, result.z);
					if (next.closed || g >= next.g - MIN_IMPROVEMENT) continue;
					next.g = g;
					next.f = g + next.h * weight;
					next.parent = current;
					next.move = move;
					next.overlay = result.overlay();
					if (next.heapIndex >= 0) open.decreased(next);
					else open.add(next);
				}
			}
			SearchResult.Reason reason = moves.touchedUnloaded() ? SearchResult.Reason.UNLOADED_FRONTIER : SearchResult.Reason.NO_ROUTE;
			return partial(best, reason, expanded, started);
		}

		private SearchResult partial(Node best, SearchResult.Reason reason, int expanded, long started) {
			SearchResult.Stats stats = stats(expanded, started);
			if (best.parent == null) return new SearchResult.Unreachable(reason, stats);
			Path path = path(best);
			GridPos end = path.end();
			double dx = end.x() - start.x(), dy = end.y() - start.y(), dz = end.z() - start.z();
			if (Math.sqrt(dx * dx + dy * dy + dz * dz) < budget.minPartialBlocks()) return new SearchResult.Unreachable(reason, stats);
			return new SearchResult.Partial(path, reason, stats);
		}

		private SearchResult.Stats stats(int expanded, long started) {
			return new SearchResult.Stats(expanded, System.nanoTime() - started, moves.touchedUnloaded());
		}

		private Path path(Node end) {
			List<Node> chain = new ArrayList<>();
			for (Node node = end; node.parent != null; node = node.parent) chain.add(node);
			List<Step> steps = new ArrayList<>(chain.size());
			double cost = 0;
			for (int i = chain.size() - 1; i >= 0; i--) {
				Node node = chain.get(i);
				moves.overlay(node.parent.overlay);
				Step step = moves.step(node.move, node.parent.pos());
				if (step == null || !step.to().equals(node.pos())) {
					throw new IllegalStateException("move " + node.move + " from " + node.parent.pos() + " did not replay");
				}
				steps.add(step);
				cost += step.cost();
			}
			return new Path(start, steps, cost);
		}

		private Node node(int x, int y, int z) {
			long key = GridPos.key(x, y, z);
			Node node = nodes.get(key);
			if (node == null) {
				node = new Node(x, y, z, goal.heuristic(x, y, z));
				nodes.put(key, node);
			}
			return node;
		}
	}

	/** Open-addressing map from packed cell keys to nodes. */
	private static final class NodeMap {
		private long[] keys = new long[1 << 12];
		private Node[] values = new Node[1 << 12];
		private int size;

		Node get(long key) {
			int mask = keys.length - 1;
			for (int index = mix(key) & mask; ; index = (index + 1) & mask) {
				Node value = values[index];
				if (value == null) return null;
				if (keys[index] == key) return value;
			}
		}

		void put(long key, Node value) {
			if ((size + 1) * 2 > keys.length) grow();
			insert(keys, values, key, value);
			size++;
		}

		private void grow() {
			long[] oldKeys = keys;
			Node[] oldValues = values;
			keys = new long[oldKeys.length * 2];
			values = new Node[oldValues.length * 2];
			for (int i = 0; i < oldKeys.length; i++) if (oldValues[i] != null) insert(keys, values, oldKeys[i], oldValues[i]);
		}

		private static void insert(long[] keys, Node[] values, long key, Node value) {
			int mask = keys.length - 1;
			int index = mix(key) & mask;
			while (values[index] != null) index = (index + 1) & mask;
			keys[index] = key;
			values[index] = value;
		}

		private static int mix(long key) {
			long hash = key * 0x9E3779B97F4A7C15L;
			return (int) (hash ^ (hash >>> 32));
		}
	}

	/** Binary min-heap on f with decrease-key; ties prefer the lower heuristic. */
	private static final class Heap {
		private Node[] items = new Node[1024];
		private int size;

		boolean isEmpty() {
			return size == 0;
		}

		void add(Node node) {
			if (size == items.length) items = Arrays.copyOf(items, size * 2);
			items[size] = node;
			node.heapIndex = size;
			size++;
			up(node.heapIndex);
		}

		Node poll() {
			Node top = items[0];
			size--;
			if (size > 0) {
				items[0] = items[size];
				items[0].heapIndex = 0;
				down(0);
			}
			items[size] = null;
			top.heapIndex = -1;
			return top;
		}

		void decreased(Node node) {
			up(node.heapIndex);
		}

		private void up(int index) {
			Node node = items[index];
			while (index > 0) {
				int parent = (index - 1) >>> 1;
				if (!less(node, items[parent])) break;
				items[index] = items[parent];
				items[index].heapIndex = index;
				index = parent;
			}
			items[index] = node;
			node.heapIndex = index;
		}

		private void down(int index) {
			Node node = items[index];
			while (true) {
				int child = index * 2 + 1;
				if (child >= size) break;
				if (child + 1 < size && less(items[child + 1], items[child])) child++;
				if (!less(items[child], node)) break;
				items[index] = items[child];
				items[index].heapIndex = index;
				index = child;
			}
			items[index] = node;
			node.heapIndex = index;
		}

		private static boolean less(Node left, Node right) {
			return left.f < right.f || left.f == right.f && left.h < right.h;
		}
	}
}
