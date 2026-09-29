package ai.moeru.airicraft.agent.perception;

/** Eight-way horizontal direction from the viewer to a target, in Minecraft terms (north is -z). */
final class Compass {
	private static final String[] NAMES = {"south", "southwest", "west", "northwest", "north", "northeast", "east", "southeast"};

	private Compass() {
	}

	static String direction(double dx, double dz) {
		if (Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9) return "here";
		// atan2 over (-dx, dz) puts south at 0 and turns clockwise, matching the yaw convention.
		double degrees = Math.toDegrees(Math.atan2(-dx, dz));
		int index = (int) Math.floorMod(Math.round(degrees / 45.0), 8);
		return NAMES[index];
	}

	static double round(double value) {
		return Math.round(value * 10.0) / 10.0;
	}
}
