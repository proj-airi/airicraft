package ai.moeru.airicraft.navigation;

import java.util.HashMap;
import java.util.Map;

/**
 * A crude player simulation for follower tests: vanilla-like speeds, gravity, jumping, swimming,
 * ladder climbing, step-up and axis-separated collision against {@link CellInfo} regions. It also
 * applies the intent's block actions to the terrain.
 */
final class SimBody {
	private static final double HALF_WIDTH = 0.3;
	private static final double HEIGHT = 1.8;
	private static final double EPSILON = 1.0E-7;
	private static final double[] BAND = {0.0, 0.2, 0.8, 1.0};

	private final MapTerrain terrain;
	private final Map<CellInfo, CellInfo> toggles = new HashMap<>();
	double x;
	double y;
	double z;
	double velocityY;
	boolean onGround;
	boolean horizontalCollision;
	long tick;
	int placed;
	int broken;
	int used;
	double healthLost;
	private GridPos mining;
	private int miningTicks;
	private double fallStart = Double.NaN;

	SimBody(MapTerrain terrain, GridPos start) {
		this.terrain = terrain;
		this.x = start.x() + 0.5;
		this.y = start.y();
		this.z = start.z() + 0.5;
		settle();
		for (CellInfo cell : AsciiTerrain.LEGEND.values()) {
			if (cell.toggled() != null) {
				toggles.put(cell, cell.toggled());
				toggles.put(cell.toggled(), cell);
			}
		}
	}

	BodyState state() {
		return new BodyState(x, y, z, velocityY, onGround, inWater(), climbing(), horizontalCollision, tick);
	}

	void apply(MotorIntent intent) {
		tick++;
		act(intent.action());
		boolean water = inWater();
		boolean climbing = climbing();
		double speed = water ? 0.1 : intent.sneak() ? 0.065 : intent.sprint() ? 0.28 : 0.215;
		double dx = intent.moveX() * speed, dz = intent.moveZ() * speed;
		horizontalCollision = false;
		moveHorizontal(dx, 0);
		moveHorizontal(0, dz);

		if (water) {
			velocityY = intent.jump() ? 0.06 : intent.sneak() ? -0.08 : -0.02;
			fallStart = Double.NaN;
		}
		else if (climbing) {
			velocityY = intent.jump() || horizontalCollision ? 0.2 : intent.sneak() ? 0 : -0.15;
			fallStart = Double.NaN;
		}
		else {
			if (intent.jump() && onGround) velocityY = 0.42;
			else velocityY = (velocityY - 0.08) * 0.98;
		}
		if (!onGround && velocityY < 0 && Double.isNaN(fallStart)) fallStart = y;
		moveVertical(velocityY);
		if (onGround && !Double.isNaN(fallStart)) {
			double fall = fallStart - y;
			if (fall > 3.0 + EPSILON && !inWater()) healthLost += Math.ceil(fall - 3.0);
			fallStart = Double.NaN;
		}
		if (inWater()) fallStart = Double.NaN;
	}

	private void act(MotorIntent.Action action) {
		if (!(action instanceof MotorIntent.Break)) {
			mining = null;
			miningTicks = 0;
		}
		if (action == null) return;
		GridPos pos = action.pos();
		double reach = Math.sqrt(Math.pow(pos.x() + 0.5 - x, 2) + Math.pow(pos.y() + 0.5 - (y + 1.62), 2) + Math.pow(pos.z() + 0.5 - z, 2));
		if (reach > 5.0) throw new AssertionError("action out of reach: " + action + " from " + state());
		switch (action) {
			case MotorIntent.Break breaking -> {
				if (!pos.equals(mining)) {
					mining = pos;
					miningTicks = 0;
				}
				miningTicks++;
				CellInfo cell = terrain.cell(pos);
				if (!cell.breakable()) throw new AssertionError("breaking unbreakable " + cell + " at " + pos);
				if (miningTicks >= Math.max(1, cell.breakTicks())) {
					terrain.set(pos.x(), pos.y(), pos.z(), CellInfo.AIR);
					broken++;
					mining = null;
				}
			}
			case MotorIntent.Place place -> {
				CellInfo target = terrain.cell(place.pos());
				if (!target.replaceable()) return;
				if (intersectsBody(place.pos().x(), place.pos().y(), place.pos().z(), 0, 0, 16)) return;
				terrain.set(pos.x(), pos.y(), pos.z(), Moves.Overlay.PLACED);
				placed++;
			}
			case MotorIntent.Use use -> {
				CellInfo cell = terrain.cell(pos);
				if (cell.toggled() != null && !toggles.containsKey(cell)) {
					toggles.put(cell, cell.toggled());
					toggles.put(cell.toggled(), cell);
				}
				CellInfo toggled = toggles.get(cell);
				if (toggled == null) throw new AssertionError("using " + cell + " at " + pos);
				terrain.set(pos.x(), pos.y(), pos.z(), toggled);
				// Doors toggle both halves.
				for (int dy : new int[]{-1, 1}) {
					CellInfo other = terrain.cell(pos.x(), pos.y() + dy, pos.z());
					if (other == cell) terrain.set(pos.x(), pos.y() + dy, pos.z(), toggled);
				}
				used++;
			}
		}
	}

	private void moveHorizontal(double dx, double dz) {
		if (dx == 0 && dz == 0) return;
		if (!collides(x + dx, y, z + dz)) {
			x += dx;
			z += dz;
			return;
		}
		// Step up to 0.6 blocks when on the ground, like vanilla.
		if (onGround) {
			for (double rise = 0.0625; rise <= 0.6 + EPSILON; rise += 0.0625) {
				if (!collides(x + dx, y + rise, z + dz) && !collides(x, y + rise, z)) {
					x += dx;
					z += dz;
					y += rise;
					settle();
					return;
				}
			}
		}
		horizontalCollision = true;
	}

	private void moveVertical(double dy) {
		double step = dy / 8;
		onGround = false;
		for (int i = 0; i < 8; i++) {
			if (collides(x, y + step, z)) {
				if (dy < 0) onGround = true;
				velocityY = 0;
				// Close the gap to the surface.
				for (double fine = step / 8; Math.abs(fine) > 1e-4; fine /= 2) {
					if (!collides(x, y + fine, z)) y += fine;
				}
				return;
			}
			y += step;
		}
		if (dy <= 0 && collides(x, y - 0.001, z)) onGround = true;
	}

	private void settle() {
		for (int i = 0; i < 64 && !collides(x, y - 0.0625, z); i++) y -= 0.0625;
		if (collides(x, y - 0.001, z)) onGround = true;
	}

	private boolean collides(double px, double py, double pz) {
		double minX = px - HALF_WIDTH, maxX = px + HALF_WIDTH, minZ = pz - HALF_WIDTH, maxZ = pz + HALF_WIDTH;
		double minY = py, maxY = py + HEIGHT;
		for (int cx = (int) Math.floor(minX); cx <= (int) Math.floor(maxX - EPSILON); cx++) {
			for (int cz = (int) Math.floor(minZ); cz <= (int) Math.floor(maxZ - EPSILON); cz++) {
				for (int cy = (int) Math.floor(minY) - 1; cy <= (int) Math.floor(maxY - EPSILON); cy++) {
					CellInfo cell = terrain.cell(cx, cy, cz);
					if (!cell.loaded()) return true;
					for (int region = 0; region < CellInfo.REGIONS; region++) {
						if (!cell.hasCollision(region)) continue;
						int xb = region % 3, zb = region / 3;
						double bx0 = cx + BAND[xb], bx1 = cx + BAND[xb + 1], bz0 = cz + BAND[zb], bz1 = cz + BAND[zb + 1];
						double by0 = cy + cell.bottom(region) / 16.0, by1 = cy + cell.top(region) / 16.0;
						if (bx0 < maxX - EPSILON && bx1 > minX + EPSILON && bz0 < maxZ - EPSILON && bz1 > minZ + EPSILON
							&& by0 < maxY - EPSILON && by1 > minY + EPSILON) return true;
					}
				}
			}
		}
		return false;
	}

	private boolean intersectsBody(int cx, int cy, int cz, double lo, double loY, double hi) {
		return x + HALF_WIDTH > cx && x - HALF_WIDTH < cx + 1 && z + HALF_WIDTH > cz && z - HALF_WIDTH < cz + 1
			&& y + HEIGHT > cy + EPSILON && y < cy + 1 - EPSILON;
	}

	boolean inWater() {
		for (int cy = (int) Math.floor(y); cy <= (int) Math.floor(y + 0.8); cy++) {
			if (terrain.cell((int) Math.floor(x), cy, (int) Math.floor(z)).fluid() == CellInfo.Fluid.WATER) return true;
		}
		return false;
	}

	boolean climbing() {
		return terrain.cell((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)).climbable();
	}
}
