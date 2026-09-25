package ai.moeru.airicraft.navigation;

/** Inclusive cell box, used for travel bounds, protected areas and loaded regions. */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	public Box {
		if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("empty box");
	}

	public boolean contains(int x, int y, int z) {
		return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
	}

	public boolean contains(GridPos pos) {
		return contains(pos.x(), pos.y(), pos.z());
	}

	/**
	 * Whether a move's conservative swept envelope stays inside: the box spanned by both feet cells,
	 * raised by the head cell, and by one more cell for moves that jump.
	 */
	public boolean permitsMovement(int x, int y, int z, int tx, int ty, int tz, boolean jumping) {
		return contains(Math.min(x, tx), Math.min(y, ty), Math.min(z, tz))
			&& contains(Math.max(x, tx), Math.max(y, ty) + (jumping ? 2 : 1), Math.max(z, tz));
	}
}
