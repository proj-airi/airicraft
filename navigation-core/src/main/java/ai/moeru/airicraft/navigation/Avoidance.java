package ai.moeru.airicraft.navigation;

/** Multiplies the cost of moves that end within {@code radius} of a point, such as a hostile mob. */
public record Avoidance(double x, double y, double z, double radius, double coefficient) {
	public Avoidance {
		if (!(radius >= 0) || !(coefficient > 0)) throw new IllegalArgumentException("invalid avoidance");
	}

	public double factor(int cellX, int cellY, int cellZ) {
		double dx = cellX + 0.5 - x;
		double dy = cellY - y;
		double dz = cellZ + 0.5 - z;
		return dx * dx + dy * dy + dz * dz <= radius * radius ? coefficient : 1.0;
	}
}
