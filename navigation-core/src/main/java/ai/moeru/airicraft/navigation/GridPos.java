package ai.moeru.airicraft.navigation;

/** A block cell. For a path node it is the cell holding the player's feet. */
public record GridPos(int x, int y, int z) {
	private static final long XZ_MASK = (1L << 26) - 1;
	private static final long Y_MASK = (1L << 12) - 1;

	public GridPos offset(int dx, int dy, int dz) {
		return new GridPos(x + dx, y + dy, z + dz);
	}

	public long key() {
		return key(x, y, z);
	}

	/** Packs a world cell into a long: 26 bits each for x and z, 12 bits for y. */
	public static long key(int x, int y, int z) {
		return ((x & XZ_MASK) << 38) | ((z & XZ_MASK) << 12) | (y & Y_MASK);
	}

	public int manhattan(GridPos other) {
		return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
	}

	@Override
	public String toString() {
		return x + "," + y + "," + z;
	}
}
