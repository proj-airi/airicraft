package ai.moeru.airicraft.navigation;

import java.util.ArrayList;
import java.util.List;

import static ai.moeru.airicraft.navigation.CellInfo.CENTER;
import static ai.moeru.airicraft.navigation.CellInfo.HAZARD_BODY;
import static ai.moeru.airicraft.navigation.CellInfo.HAZARD_FLOOR;

/**
 * The move catalog over one terrain and policy. A node is a feet cell; {@link #probe} says what
 * holds the body there and at which height. {@link #evaluate} computes one move's destination and
 * cost, recording the edits it needs. Instances are single-threaded scratch state.
 *
 * <p>Clearance works on absolute heights: the body is {@value #HEIGHT} blocks tall from its feet
 * height, and a move sweeps the footprint regions (see {@link CellInfo}) it crosses. Collision
 * below the higher of the two feet heights is floor that the body steps or jumps onto.
 */
public final class Moves {
	public static final int COUNT = 18;
	public static final int KIND_INVALID = 0;
	public static final int KIND_STAND = 1;
	public static final int KIND_WATER = 2;
	public static final int KIND_CLIMB = 3;
	static final double HEIGHT = 1.8;
	/** Feet height difference walked without jumping: vanilla step height plus slab slack. */
	static final double STEP = 0.63;
	/** Highest rise a standing jump reaches. */
	static final double JUMP = 1.25;
	private static final double EPSILON = 1.0E-4;
	private static final int UP = 16;
	private static final int DOWN = 17;
	private static final int[] CARDINAL_DX = {0, 0, 1, -1};
	private static final int[] CARDINAL_DZ = {-1, 1, 0, 0};
	private static final int[] DIAGONAL_DX = {1, 1, -1, -1};
	private static final int[] DIAGONAL_DZ = {-1, 1, 1, -1};
	private static final double SQRT_2 = Math.sqrt(2);

	private final TerrainView terrain;
	private final MovementPolicy policy;
	private double probeBase;
	private boolean touchedUnloaded;
	private Overlay overlay;

	public Moves(TerrainView terrain, MovementPolicy policy) {
		this.terrain = terrain;
		this.policy = policy;
	}

	public MovementPolicy policy() {
		return policy;
	}

	public TerrainView terrain() {
		return terrain;
	}

	/** Whether any lookup so far hit an unloaded cell. */
	public boolean touchedUnloaded() {
		return touchedUnloaded;
	}

	/**
	 * Edits planned by the move that reached the node about to be expanded. Search sees them as done,
	 * so a tunnel or a bridge can continue from its own result. Null clears it.
	 */
	public void overlay(Overlay overlay) {
		this.overlay = overlay;
	}

	/** Feet height of the last {@link #probe}, or NaN when it was invalid. */
	public double probeBase() {
		return probeBase;
	}

	/** What holds a body with its feet in this cell: one of the {@code KIND_} constants. */
	public int probe(int x, int y, int z) {
		probeBase = Double.NaN;
		CellInfo feet = cell(x, y, z);
		CellInfo head = cell(x, y + 1, z);
		if (!feet.loaded() || !head.loaded()) return KIND_INVALID;
		if (deadly(feet) || deadly(head)) return KIND_INVALID;
		boolean feetOpen = !feet.hasCollision(CENTER);
		if (feet.fluid() == CellInfo.Fluid.WATER && feetOpen) {
			if (!sweepFree(x, z, 1 << CENTER, y, y + HEIGHT)) return KIND_INVALID;
			probeBase = y;
			return KIND_WATER;
		}
		double base = standBase(x, y, z, feet);
		if (!Double.isNaN(base)) {
			probeBase = base;
			return KIND_STAND;
		}
		if (feet.climbable() && feetOpen && sweepFree(x, z, 1 << CENTER, y, y + HEIGHT)) {
			probeBase = y;
			return KIND_CLIMB;
		}
		return KIND_INVALID;
	}

	/**
	 * Evaluates move {@code move} (0 to {@link #COUNT} - 1) from a probed node. Returns false when the
	 * move is impossible; otherwise {@code out} holds the destination, cost, type and edits.
	 */
	public boolean evaluate(int move, int x, int y, int z, int fromKind, double fromBase, Result out) {
		out.reset();
		if (fromKind == KIND_INVALID) return false;
		boolean possible;
		if (move < 4) possible = traverse(CARDINAL_DX[move], CARDINAL_DZ[move], x, y, z, fromKind, fromBase, out);
		else if (move < 8) possible = diagonal(DIAGONAL_DX[move - 4], DIAGONAL_DZ[move - 4], x, y, z, fromKind, fromBase, out);
		else if (move < 12) possible = ascend(CARDINAL_DX[move - 8], CARDINAL_DZ[move - 8], x, y, z, fromKind, fromBase, out);
		else if (move < 16) possible = descend(CARDINAL_DX[move - 12], CARDINAL_DZ[move - 12], x, y, z, fromBase, out);
		else if (move == UP) possible = up(x, y, z, fromKind, fromBase, out);
		else if (move == DOWN) possible = down(x, y, z, fromKind, out);
		else throw new IllegalArgumentException("unknown move " + move);
		if (!possible) {
			out.reset();
			return false;
		}
		out.cost *= policy.avoidanceFactor(out.x, out.y, out.z);
		return out.cost < Double.POSITIVE_INFINITY;
	}

	/**
	 * Re-evaluates a move from a cell and returns it as a step, or null when it is no longer possible.
	 * An unsupported start, such as mid-jump, is treated as standing.
	 */
	public Step step(int move, GridPos from) {
		int kind = probe(from.x(), from.y(), from.z());
		double base = probeBase;
		if (kind == KIND_INVALID) {
			kind = KIND_STAND;
			base = from.y();
		}
		Result result = new Result();
		if (!evaluate(move, from.x(), from.y(), from.z(), kind, base, result)) return null;
		return result.toStep(from);
	}

	/** Finds the cheapest move from {@code from} to {@code to}, or null when none reaches it. */
	public Step stepBetween(GridPos from, GridPos to) {
		Step best = null;
		for (int move = 0; move < COUNT; move++) {
			Step step = step(move, from);
			if (step != null && step.to().equals(to) && (best == null || step.cost() < best.cost())) best = step;
		}
		return best;
	}

	private boolean traverse(int dx, int dz, int x, int y, int z, int fromKind, double fromBase, Result out) {
		int tx = x + dx, tz = z + dz;
		if (!policy.permitsMovement(x, y, z, tx, y, tz, false)) return false;
		int sourceMask = 1 << CellInfo.edgeRegion(dx, dz);
		int destMask = (1 << CellInfo.edgeRegion(-dx, -dz)) | (1 << CENTER);
		int toKind = probe(tx, y, tz);
		double toBase = probeBase;
		if (toKind != KIND_INVALID) {
			if (Math.abs(toBase - fromBase) > STEP) return false;
			double top = Math.max(fromBase, toBase);
			// Stepping up raises the head above the space the source node already cleared.
			int mask = toBase > fromBase ? sourceMask | (1 << CENTER) : sourceMask;
			double edits = sweepPair(x, z, mask, tx, tz, destMask, top, top + HEIGHT, out);
			if (edits < 0) return false;
			out.set(MoveType.TRAVERSE, tx, y, tz, horizontalCost(fromKind, toKind, tx, y, tz, out.edited()) + edits);
			return true;
		}
		if (policy.allowBreak() && fromKind != KIND_CLIMB) {
			double base = floorBase(tx, y - 1, tz);
			if (!Double.isNaN(base) && Math.abs(base - fromBase) <= STEP) {
				double top = Math.max(fromBase, base);
				int mask = base > fromBase ? sourceMask | (1 << CENTER) : sourceMask;
				double edits = sweepPair(x, z, mask, tx, tz, destMask, top, top + HEIGHT, out);
				if (edits >= 0 && out.breakCount > 0) {
					out.set(MoveType.TRAVERSE, tx, y, tz, Costs.WALK + edits);
					return true;
				}
				out.clearEdits();
			}
		}
		if (policy.canPlace() && fromKind == KIND_STAND && fromBase == y) {
			CellInfo support = cell(x, y - 1, z);
			CellInfo target = cell(tx, y - 1, tz);
			CellInfo feet = cell(tx, y, tz);
			if (support.fullCube() && target.loaded() && target.replaceable() && target.fluid() != CellInfo.Fluid.LAVA
				&& feet.fluid() == CellInfo.Fluid.NONE && policy.permitsEdit(tx, y - 1, tz)
				&& sweepFree(x, z, sourceMask, y, y + HEIGHT) && sweepFree(tx, tz, destMask, y, y + HEIGHT)
				&& !deadly(cell(tx, y + 1, tz)) && !deadly(feet)) {
				out.place(tx, y - 1, tz, x, y - 1, z);
				// The motor places against our floor's side face from where it stands, then walks on.
				out.set(MoveType.BRIDGE, tx, y, tz, Costs.WALK + policy.placePenalty());
				return true;
			}
		}
		return false;
	}

	private boolean diagonal(int dx, int dz, int x, int y, int z, int fromKind, double fromBase, Result out) {
		int tx = x + dx, tz = z + dz;
		if (fromKind == KIND_CLIMB || !policy.permitsMovement(x, y, z, tx, y, tz, false)) return false;
		int toKind = probe(tx, y, tz);
		double toBase = probeBase;
		if (toKind == KIND_INVALID || toKind == KIND_CLIMB || (toKind == KIND_WATER) != (fromKind == KIND_WATER)) return false;
		if (Math.abs(toBase - fromBase) > STEP) return false;
		double from = Math.max(fromBase, toBase), to = from + HEIGHT;
		int sourceMask = bit(1 + dx, 1) | bit(1, 1 + dz) | bit(1 + dx, 1 + dz);
		int destMask = bit(1 - dx, 1) | bit(1, 1 - dz) | bit(1 - dx, 1 - dz);
		// Each side column is crossed near the corner it shares with both cells.
		int sideXMask = bit(1 - dx, 1 + dz) | bit(1, 1 + dz) | bit(1 - dx, 1) | (1 << CENTER);
		int sideZMask = bit(1 + dx, 1 - dz) | bit(1, 1 - dz) | bit(1 + dx, 1) | (1 << CENTER);
		if (!sweepFree(x, z, sourceMask, from, to) || !sweepFree(tx, tz, destMask, from, to)
			|| !sweepFree(tx, z, sideXMask, from, to) || !sweepFree(x, tz, sideZMask, from, to)) return false;
		out.set(MoveType.DIAGONAL, tx, y, tz, horizontalCost(fromKind, toKind, tx, y, tz, false) * SQRT_2);
		return true;
	}

	private boolean ascend(int dx, int dz, int x, int y, int z, int fromKind, double fromBase, Result out) {
		int tx = x + dx, ty = y + 1, tz = z + dz;
		if (!policy.permitsMovement(x, y, z, tx, ty, tz, true)) return false;
		int toKind = probe(tx, ty, tz);
		double toBase = probeBase;
		boolean tunnel = false;
		if (toKind == KIND_INVALID) {
			if (!policy.allowBreak()) return false;
			toBase = floorBase(tx, y, tz);
			if (Double.isNaN(toBase)) return false;
			tunnel = true;
		}
		else if (toKind != KIND_STAND) {
			return false;
		}
		double rise = toBase - fromBase;
		if (rise > JUMP + EPSILON || rise < -EPSILON) return false;
		int exit = CellInfo.edgeRegion(dx, dz), entry = CellInfo.edgeRegion(-dx, -dz);
		double top = toBase + HEIGHT;
		double headroom = sweep(x, z, 1 << CENTER, fromBase, top, out);
		if (headroom < 0) return false;
		double edits = sweepPair(x, z, 1 << exit, tx, tz, (1 << entry) | (1 << CENTER), toBase, top, out);
		if (edits < 0 || tunnel && out.breakCount == 0) return false;
		double move;
		if (fromKind == KIND_WATER) move = Costs.WALK_IN_WATER + Costs.JUMP_ONE_BLOCK;
		else if (rise > STEP) move = Math.max(Costs.JUMP_ONE_BLOCK, Costs.WALK) + policy.jumpPenalty();
		else move = Costs.WALK;
		out.set(MoveType.ASCEND, tx, ty, tz, move + headroom + edits);
		return true;
	}

	private boolean descend(int dx, int dz, int x, int y, int z, double fromBase, Result out) {
		int tx = x + dx, tz = z + dz;
		// With support at our level the column is a traverse, not a drop.
		if (probe(tx, y, tz) != KIND_INVALID) return false;
		int exit = CellInfo.edgeRegion(dx, dz), entry = CellInfo.edgeRegion(-dx, -dz);
		double entryCost = sweepPair(x, z, 1 << exit, tx, tz, (1 << entry) | (1 << CENTER), fromBase, fromBase + HEIGHT, out);
		if (entryCost < 0) return false;
		int limit = Math.max(policy.maxSafeFall(), policy.maxWaterFall()) + 1;
		for (int n = 1; n <= limit; n++) {
			int ly = y - n;
			int kind = probe(tx, ly, tz);
			if (kind != KIND_INVALID) {
				double height = fromBase - probeBase;
				boolean water = kind == KIND_WATER;
				if (height > (water ? policy.maxWaterFall() : policy.maxSafeFall()) + EPSILON) return false;
				if (!policy.permitsMovement(x, y, z, tx, ly, tz, false)) return false;
				double cost = Costs.WALK_OFF_EDGE + Costs.fallTicks(height) + Costs.CENTER_AFTER_FALL + entryCost
					+ (water ? policy.waterPenalty() : 0);
				out.set(n == 1 ? MoveType.DESCEND : MoveType.FALL, tx, ly, tz, cost);
				return true;
			}
			CellInfo cell = cell(tx, ly, tz);
			if (!cell.loaded()) return false;
			if (n == 1 && policy.allowBreak() && cell.hasCollision(CENTER)) {
				// Dig the step down: the cell below our feet level becomes the landing's feet cell.
				double base = floorBase(tx, ly - 1, tz);
				if (Double.isNaN(base) || !policy.permitsMovement(x, y, z, tx, ly, tz, false)) return false;
				double dig = sweep(tx, tz, 1 << CENTER, base, fromBase, out);
				if (dig < 0) return false;
				out.set(MoveType.DESCEND, tx, ly, tz,
					Costs.WALK_OFF_EDGE + Costs.fallTicks(fromBase - base) + Costs.CENTER_AFTER_FALL + entryCost + dig);
				return true;
			}
			if (!sweepFree(tx, tz, 1 << CENTER, ly, ly + 1)) return false;
		}
		return false;
	}

	private boolean up(int x, int y, int z, int fromKind, double fromBase, Result out) {
		int ty = y + 1;
		int toKind = probe(x, ty, z);
		if (fromKind == KIND_WATER) {
			if (toKind != KIND_WATER || !policy.permitsMovement(x, y, z, x, ty, z, false)) return false;
			out.set(MoveType.SWIM_UP, x, ty, z, Costs.WALK_IN_WATER + submerged(x, ty, z));
			return true;
		}
		if (toKind == KIND_CLIMB) {
			if (!policy.permitsMovement(x, y, z, x, ty, z, false)) return false;
			out.set(MoveType.CLIMB_UP, x, ty, z, Costs.LADDER_UP);
			return true;
		}
		if (fromKind != KIND_STAND || fromBase != y || !policy.canPlace()) return false;
		CellInfo feet = cell(x, y, z);
		CellInfo floor = cell(x, y - 1, z);
		if (!feet.replaceable() || feet.fluid() != CellInfo.Fluid.NONE || floor.top(CENTER) != 16
			|| !policy.permitsEdit(x, y, z) || !policy.permitsMovement(x, y, z, x, ty, z, true)) return false;
		// The body must rise a full block to stand on the new block.
		double headroom = sweep(x, z, 1 << CENTER, ty, ty + HEIGHT, out);
		if (headroom < 0) return false;
		out.place(x, y, z, x, y - 1, z);
		out.set(MoveType.PILLAR, x, ty, z, Costs.JUMP_ONE_BLOCK + policy.placePenalty() + policy.jumpPenalty() + headroom);
		return true;
	}

	private boolean down(int x, int y, int z, int fromKind, Result out) {
		int ty = y - 1;
		int toKind = probe(x, ty, z);
		if (!policy.permitsMovement(x, y, z, x, ty, z, false)) return false;
		if (fromKind == KIND_WATER && toKind == KIND_WATER) {
			out.set(MoveType.SWIM_DOWN, x, ty, z, Costs.WALK_IN_WATER + submerged(x, ty, z));
			return true;
		}
		if (fromKind == KIND_CLIMB && (toKind == KIND_CLIMB || toKind == KIND_STAND)) {
			out.set(MoveType.CLIMB_DOWN, x, ty, z, Costs.LADDER_DOWN);
			return true;
		}
		return false;
	}

	private double horizontalCost(int fromKind, int toKind, int tx, int ty, int tz, boolean edited) {
		if (fromKind == KIND_WATER || toKind == KIND_WATER) {
			return Costs.WALK_IN_WATER + (toKind == KIND_WATER ? policy.waterPenalty() + submerged(tx, ty, tz) : 0);
		}
		double cost = policy.allowSprint() && !edited && toKind == KIND_STAND ? Costs.SPRINT : Costs.WALK;
		CellInfo floor = cell(tx, ty - 1, tz);
		double factor = floor.hasCollision(CENTER) ? floor.speedFactor() : cell(tx, ty, tz).speedFactor();
		return factor > 0 && factor < 1 ? cost / factor : cost;
	}

	private double submerged(int x, int y, int z) {
		return cell(x, y + 1, z).fluid() == CellInfo.Fluid.WATER ? Costs.SUBMERGED : 0;
	}

	/** Feet height when standing on the cell's top surface, or NaN when it cannot be stood on. */
	private double floorBase(int x, int y, int z) {
		CellInfo floor = cell(x, y, z);
		int top = floor.top(CENTER);
		if (!floor.loaded() || top < 10 || top > 16 || floor.hazard(HAZARD_FLOOR)) return Double.NaN;
		return y + top / 16.0;
	}

	private double standBase(int x, int y, int z, CellInfo feet) {
		double base;
		if (feet.hasCollision(CENTER)) {
			int top = feet.top(CENTER);
			if (top > 9 || feet.hazard(HAZARD_FLOOR)) return Double.NaN;
			base = y + top / 16.0;
		}
		else {
			base = floorBase(x, y - 1, z);
			if (Double.isNaN(base)) return base;
		}
		return sweepFree(x, z, 1 << CENTER, base, base + HEIGHT) ? base : Double.NaN;
	}

	private double sweepPair(int x, int z, int mask, int tx, int tz, int destMask, double from, double to, Result out) {
		double source = sweep(x, z, mask, from, to, out);
		if (source < 0) return -1;
		double dest = sweep(tx, tz, destMask, from, to, out);
		return dest < 0 ? -1 : source + dest;
	}

	/**
	 * Clears the masked regions of a column between two absolute heights, scheduling door use or
	 * breaks where the policy allows. Returns the added cost, or -1 when the column is blocked.
	 */
	private double sweep(int x, int z, int mask, double from, double to, Result out) {
		double cost = 0;
		int first = (int) Math.floor(from + EPSILON), last = (int) Math.floor(to - EPSILON);
		if (tallBelow(x, first - 1, z, mask, from, to)) return -1;
		for (int cy = first; cy <= last; cy++) {
			CellInfo cell = cell(x, cy, z);
			if (!cell.loaded()) return -1;
			double lo = Math.max(0, (from - cy) * 16), hi = Math.min(16, (to - cy) * 16);
			boolean blocked = !cell.freeInMask(mask, lo, hi) || deadly(cell);
			if (!blocked) continue;
			if (!deadly(cell) && policy.allowDoors() && cell.toggled() != null && cell.toggled().freeInMask(mask, lo, hi)) {
				if (out.addDoor(x, cy, z)) cost += Costs.DOOR;
				continue;
			}
			if (!canBreak(x, cy, z, cell)) return -1;
			if (out.addBreak(x, cy, z)) cost += cell.breakTicks() + policy.breakPenalty();
		}
		return cost;
	}

	private boolean sweepFree(int x, int z, int mask, double from, double to) {
		int first = (int) Math.floor(from + EPSILON), last = (int) Math.floor(to - EPSILON);
		if (tallBelow(x, first - 1, z, mask, from, to)) return false;
		for (int cy = first; cy <= last; cy++) {
			CellInfo cell = cell(x, cy, z);
			if (!cell.loaded() || deadly(cell)) return false;
			double lo = Math.max(0, (from - cy) * 16), hi = Math.min(16, (to - cy) * 16);
			if (!cell.freeInMask(mask, lo, hi)) return false;
		}
		return true;
	}

	/** Fences and walls reach half a block into the cell above theirs. */
	private boolean tallBelow(int x, int y, int z, int mask, double from, double to) {
		CellInfo cell = cell(x, y, z);
		return cell.tall() && !cell.freeInMask(mask, (from - y) * 16, (to - y) * 16);
	}

	private boolean canBreak(int x, int y, int z, CellInfo cell) {
		if (!policy.allowBreak() || !cell.breakable() || cell.fluid() != CellInfo.Fluid.NONE || !policy.permitsEdit(x, y, z)) {
			return false;
		}
		// Falling blocks and fluids would pour into the opening.
		CellInfo above = cell(x, y + 1, z);
		if (!above.loaded() || above.falling() || above.fluid() != CellInfo.Fluid.NONE) return false;
		for (int side = 0; side < 4; side++) {
			CellInfo neighbour = cell(x + CARDINAL_DX[side], y, z + CARDINAL_DZ[side]);
			if (!neighbour.loaded() || neighbour.fluid() != CellInfo.Fluid.NONE) return false;
		}
		return true;
	}

	private static boolean deadly(CellInfo cell) {
		return cell.fluid() == CellInfo.Fluid.LAVA || cell.hazard(HAZARD_BODY);
	}

	private CellInfo cell(int x, int y, int z) {
		if (overlay != null) {
			CellInfo edited = overlay.cell(x, y, z);
			if (edited != null) return edited;
		}
		CellInfo cell = terrain.cell(x, y, z);
		if (!cell.loaded()) touchedUnloaded = true;
		return cell;
	}

	private static int bit(int xBand, int zBand) {
		return 1 << CellInfo.region(xBand, zBand);
	}

	/** Cells a planned move breaks or fills, as search should see them afterwards. */
	public static final class Overlay {
		/** A placed throwaway block. */
		public static final CellInfo PLACED = CellInfo.builder("placed").full().breakTicks(30).build();
		private final long[] keys;
		private final CellInfo[] cells;

		private Overlay(long[] keys, CellInfo[] cells) {
			this.keys = keys;
			this.cells = cells;
		}

		CellInfo cell(int x, int y, int z) {
			long key = GridPos.key(x, y, z);
			for (int i = 0; i < keys.length; i++) if (keys[i] == key) return cells[i];
			return null;
		}
	}

	/** Mutable move output, reused across evaluations. */
	public static final class Result {
		public int x;
		public int y;
		public int z;
		public double cost;
		public MoveType type;
		private int[] breaks = new int[12];
		private int breakCount;
		private int[] doors = new int[6];
		private int doorCount;
		private int[] place;

		void reset() {
			type = null;
			cost = Double.POSITIVE_INFINITY;
			clearEdits();
		}

		void clearEdits() {
			breakCount = 0;
			doorCount = 0;
			place = null;
		}

		boolean edited() {
			return breakCount > 0 || place != null;
		}

		/** The edits as an overlay for the destination node, or null when the move edits nothing. */
		public Overlay overlay() {
			if (!edited()) return null;
			int count = breakCount + (place == null ? 0 : 1);
			long[] keys = new long[count];
			CellInfo[] cells = new CellInfo[count];
			for (int i = 0; i < breakCount; i++) {
				keys[i] = GridPos.key(breaks[i * 3], breaks[i * 3 + 1], breaks[i * 3 + 2]);
				cells[i] = CellInfo.AIR;
			}
			if (place != null) {
				keys[breakCount] = GridPos.key(place[0], place[1], place[2]);
				cells[breakCount] = Overlay.PLACED;
			}
			return new Overlay(keys, cells);
		}

		void set(MoveType type, int x, int y, int z, double cost) {
			this.type = type;
			this.x = x;
			this.y = y;
			this.z = z;
			this.cost = cost;
		}

		void place(int x, int y, int z, int againstX, int againstY, int againstZ) {
			place = new int[]{x, y, z, againstX, againstY, againstZ};
		}

		boolean addBreak(int x, int y, int z) {
			if (contains(breaks, breakCount, x, y, z)) return false;
			if (breakCount * 3 == breaks.length) breaks = java.util.Arrays.copyOf(breaks, breaks.length * 2);
			breaks[breakCount * 3] = x;
			breaks[breakCount * 3 + 1] = y;
			breaks[breakCount * 3 + 2] = z;
			breakCount++;
			return true;
		}

		boolean addDoor(int x, int y, int z) {
			if (contains(doors, doorCount, x, y, z)) return false;
			if (doorCount * 3 == doors.length) doors = java.util.Arrays.copyOf(doors, doors.length * 2);
			doors[doorCount * 3] = x;
			doors[doorCount * 3 + 1] = y;
			doors[doorCount * 3 + 2] = z;
			doorCount++;
			return true;
		}

		private static boolean contains(int[] cells, int count, int x, int y, int z) {
			for (int i = 0; i < count; i++) {
				if (cells[i * 3] == x && cells[i * 3 + 1] == y && cells[i * 3 + 2] == z) return true;
			}
			return false;
		}

		Step toStep(GridPos from) {
			List<GridPos> breakList = new ArrayList<>(breakCount);
			for (int i = 0; i < breakCount; i++) breakList.add(new GridPos(breaks[i * 3], breaks[i * 3 + 1], breaks[i * 3 + 2]));
			// Top first, so a head-height block goes before the one under it.
			breakList.sort((left, right) -> Integer.compare(right.y(), left.y()));
			List<GridPos> doorList = new ArrayList<>(doorCount);
			for (int i = 0; i < doorCount; i++) doorList.add(new GridPos(doors[i * 3], doors[i * 3 + 1], doors[i * 3 + 2]));
			GridPos placeAt = place == null ? null : new GridPos(place[0], place[1], place[2]);
			GridPos against = place == null ? null : new GridPos(place[3], place[4], place[5]);
			return new Step(type, from, new GridPos(x, y, z), cost, breakList, placeAt, against, doorList);
		}
	}
}
