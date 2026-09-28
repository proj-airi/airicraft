package ai.moeru.airicraft.navigation;

/** How the body gets from one feet cell to the next. Tunnelling is any move with breaks. */
public enum MoveType {
	/** One cell sideways on the same level, walking or swimming, stepping over slabs. */
	TRAVERSE,
	/** One cell diagonally on the same level; never edits terrain. */
	DIAGONAL,
	/** One cell sideways and up with a jump. */
	ASCEND,
	/** One cell sideways and one down. */
	DESCEND,
	/** Walk off an edge and fall more than one block. */
	FALL,
	SWIM_UP,
	SWIM_DOWN,
	CLIMB_UP,
	CLIMB_DOWN,
	/** Jump and place a block underneath. */
	PILLAR,
	/** Place the missing floor ahead, then walk onto it. */
	BRIDGE;

	public boolean jumps() {
		return this == ASCEND || this == PILLAR;
	}

	public boolean vertical() {
		return this == SWIM_UP || this == SWIM_DOWN || this == CLIMB_UP || this == CLIMB_DOWN || this == PILLAR;
	}
}
