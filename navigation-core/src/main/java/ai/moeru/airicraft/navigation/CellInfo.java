package ai.moeru.airicraft.navigation;

import java.util.Arrays;
import java.util.Objects;

/**
 * Movement classification of one block cell. The Minecraft adapter builds one instance per block
 * state and shares it; search never sees a block state.
 *
 * <p>Collision is summarized per footprint region. Each horizontal axis is split into the bands
 * [0, 0.2), [0.2, 0.8) and [0.8, 1], so a centred player body (0.6 wide) covers exactly the middle
 * band and a move to a neighbour sweeps the edge band on that side. Each region stores the vertical
 * extent of the collision boxes over it, in sixteenths of a block, from 0 up to 24 (fences and
 * walls are 1.5 blocks tall). Merging boxes per region is conservative: a gap between two boxes
 * over the same region counts as blocked.
 */
public final class CellInfo {
	public static final int REGIONS = 9;
	/** Region index is {@code zBand * 3 + xBand}; band 0 is north or west, band 2 south or east. */
	public static final int CENTER = 4;
	public static final int UNBREAKABLE = -1;
	/** The body must not enter the cell: fire, cobweb, berry bushes, powder snow, portals. */
	public static final int HAZARD_BODY = 1;
	/** Standing on the cell hurts: magma blocks, lit campfires. */
	public static final int HAZARD_FLOOR = 1 << 1;

	private static final int NONE_LO = Integer.MAX_VALUE;
	private static final int NONE_HI = Integer.MIN_VALUE;
	private static final double EPSILON = 1.0E-4;
	private static final double[] BAND_MIN = {0.0, 0.2, 0.8};
	private static final double[] BAND_MAX = {0.2, 0.8, 1.0};

	public static final CellInfo AIR = builder("air").replaceable().breakTicks(0).build();
	/** Outside loaded chunks or the world: never passable, never standable, never edited. */
	public static final CellInfo UNLOADED = builder("unloaded").unloaded().build();
	public static final CellInfo WATER = builder("water").fluid(Fluid.WATER, true).replaceable().build();
	public static final CellInfo LAVA = builder("lava").fluid(Fluid.LAVA, true).replaceable().build();

	public enum Fluid { NONE, WATER, LAVA }

	public enum Openable { NONE, DOOR, FENCE_GATE, TRAPDOOR }

	private final String label;
	private final int[] lo;
	private final int[] hi;
	private final boolean collision;
	private final boolean fullCube;
	private final boolean tall;
	private final Fluid fluid;
	private final boolean fluidSource;
	private final boolean climbable;
	private final Openable openable;
	private final CellInfo toggled;
	private final int hazards;
	private final double speedFactor;
	private final int breakTicks;
	private final boolean replaceable;
	private final boolean falling;
	private final boolean loaded;

	private CellInfo(Builder builder) {
		label = builder.label;
		lo = builder.lo.clone();
		hi = builder.hi.clone();
		boolean any = false;
		boolean full = true;
		boolean above = false;
		for (int region = 0; region < REGIONS; region++) {
			any |= lo[region] != NONE_LO;
			full &= lo[region] == 0 && hi[region] == 16;
			above |= lo[region] != NONE_LO && hi[region] > 16;
		}
		collision = any;
		fullCube = full;
		tall = above;
		fluid = builder.fluid;
		fluidSource = builder.fluidSource;
		climbable = builder.climbable;
		openable = builder.openable;
		toggled = builder.toggled;
		hazards = builder.hazards;
		speedFactor = builder.speedFactor;
		breakTicks = builder.breakTicks;
		replaceable = builder.replaceable;
		falling = builder.falling;
		loaded = builder.loaded;
	}

	public static Builder builder(String label) {
		return new Builder(label);
	}

	public static int region(int xBand, int zBand) {
		return zBand * 3 + xBand;
	}

	/** The edge band a body crosses when leaving a cell toward (dx, dz), or entering one from the opposite side. */
	public static int edgeRegion(int dx, int dz) {
		return region(1 + Integer.signum(dx), 1 + Integer.signum(dz));
	}

	public String label() { return label; }
	public boolean loaded() { return loaded; }
	public boolean hasCollision() { return collision; }
	public boolean fullCube() { return fullCube; }
	/** Collision reaches into the cell above, like fences and walls. */
	public boolean tall() { return tall; }
	public Fluid fluid() { return fluid; }
	public boolean fluidSource() { return fluidSource; }
	public boolean climbable() { return climbable; }
	public Openable openable() { return openable; }
	/** The geometry after one use of the door, gate or trapdoor; null when the cell cannot be toggled by hand. */
	public CellInfo toggled() { return toggled; }
	public int hazards() { return hazards; }
	public boolean hazard(int flag) { return (hazards & flag) != 0; }
	public double speedFactor() { return speedFactor; }
	public int breakTicks() { return breakTicks; }
	public boolean breakable() { return breakTicks >= 0; }
	public boolean replaceable() { return replaceable; }
	public boolean falling() { return falling; }

	public boolean hasCollision(int region) {
		return lo[region] != NONE_LO;
	}

	/** Top of the collision over a region in sixteenths, or -1 when the region is empty. */
	public int top(int region) {
		return lo[region] == NONE_LO ? -1 : hi[region];
	}

	public int bottom(int region) {
		return lo[region] == NONE_LO ? -1 : lo[region];
	}

	/** Whether the region has no collision between the given heights, in sixteenths of this cell. */
	public boolean freeIn(int region, double from, double to) {
		return lo[region] == NONE_LO || hi[region] <= from + EPSILON || lo[region] >= to - EPSILON;
	}

	/** {@link #freeIn(int, double, double)} for every region in a bit mask of region indices. */
	public boolean freeInMask(int regionMask, double from, double to) {
		for (int region = 0; region < REGIONS; region++) {
			if ((regionMask & (1 << region)) != 0 && !freeIn(region, from, to)) return false;
		}
		return true;
	}

	@Override
	public String toString() {
		return label;
	}

	public static final class Builder {
		private final String label;
		private final int[] lo = new int[REGIONS];
		private final int[] hi = new int[REGIONS];
		private Fluid fluid = Fluid.NONE;
		private boolean fluidSource;
		private boolean climbable;
		private Openable openable = Openable.NONE;
		private CellInfo toggled;
		private int hazards;
		private double speedFactor = 1.0;
		private int breakTicks = UNBREAKABLE;
		private boolean replaceable;
		private boolean falling;
		private boolean loaded = true;

		private Builder(String label) {
			this.label = Objects.requireNonNull(label, "label");
			Arrays.fill(lo, NONE_LO);
			Arrays.fill(hi, NONE_HI);
		}

		/** Adds a collision box in block units relative to the cell's minimum corner. */
		public Builder box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
			if (maxX - minX <= EPSILON || maxY - minY <= EPSILON || maxZ - minZ <= EPSILON) return this;
			int bottom = (int) Math.max(0, Math.floor(minY * 16 + EPSILON));
			int top = (int) Math.min(24, Math.ceil(maxY * 16 - EPSILON));
			for (int zBand = 0; zBand < 3; zBand++) {
				if (minZ >= BAND_MAX[zBand] - EPSILON || maxZ <= BAND_MIN[zBand] + EPSILON) continue;
				for (int xBand = 0; xBand < 3; xBand++) {
					if (minX >= BAND_MAX[xBand] - EPSILON || maxX <= BAND_MIN[xBand] + EPSILON) continue;
					int region = region(xBand, zBand);
					lo[region] = Math.min(lo[region], bottom);
					hi[region] = Math.max(hi[region], top);
				}
			}
			return this;
		}

		/** Adds a box in sixteenths, the unit of Minecraft's block shape definitions. */
		public Builder pixels(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
			return box(minX / 16, minY / 16, minZ / 16, maxX / 16, maxY / 16, maxZ / 16);
		}

		public Builder full() {
			return box(0, 0, 0, 1, 1, 1);
		}

		public Builder fluid(Fluid fluid, boolean source) {
			this.fluid = Objects.requireNonNull(fluid, "fluid");
			this.fluidSource = source;
			return this;
		}

		public Builder climbable() {
			climbable = true;
			return this;
		}

		public Builder openable(Openable openable, CellInfo toggled) {
			this.openable = Objects.requireNonNull(openable, "openable");
			this.toggled = toggled;
			return this;
		}

		public Builder hazards(int hazards) {
			this.hazards |= hazards;
			return this;
		}

		public Builder speedFactor(double speedFactor) {
			this.speedFactor = speedFactor;
			return this;
		}

		public Builder breakTicks(int breakTicks) {
			this.breakTicks = breakTicks;
			return this;
		}

		public Builder replaceable() {
			replaceable = true;
			return this;
		}

		public Builder falling() {
			falling = true;
			return this;
		}

		Builder unloaded() {
			loaded = false;
			return this;
		}

		public CellInfo build() {
			return new CellInfo(this);
		}
	}
}
