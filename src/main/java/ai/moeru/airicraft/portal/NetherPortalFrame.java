package ai.moeru.airicraft.portal;

import ai.moeru.airicraft.blueprint.BlueprintConstructionProgram;
import ai.moeru.airicraft.blueprint.ConstructionEscape;
import java.util.*;

/**
 * The smallest vanilla Nether portal: a 4 wide by 5 tall frame around a 2 by 3 opening.
 * {@code (x,y,z)} is the lower corner of the frame; the frame extends +1 along {@code axis}.
 * Only the 10 edge cells must be obsidian. The 4 corners may hold any block; they are built
 * so that the top bar always has a supported neighbour while it is placed.
 */
public record NetherPortalFrame(int x, int y, int z, Axis axis) {
	public static final int WIDTH = 4, HEIGHT = 5;
	public static final String OBSIDIAN = "minecraft:obsidian";
	public static final String PORTAL = "minecraft:nether_portal";

	public enum Axis {
		X, Z;
		public static Axis parse(String value) {
			return switch (value.toLowerCase(Locale.ROOT)) {
				case "x" -> X;
				case "z" -> Z;
				default -> throw new IllegalArgumentException("axis must be x or z");
			};
		}
		public String id() { return name().toLowerCase(Locale.ROOT); }
	}

	public enum Role { OBSIDIAN, CORNER, INTERIOR }

	public record Slot(ConstructionEscape.Position position, Role role) { }

	public NetherPortalFrame {
		Objects.requireNonNull(axis);
	}

	/** Position {@code along} blocks along the axis and {@code up} blocks above the lower corner. */
	public ConstructionEscape.Position at(int along, int up) {
		return axis == Axis.X ? new ConstructionEscape.Position(x + along, y + up, z) : new ConstructionEscape.Position(x, y + up, z + along);
	}

	public List<Slot> slots() {
		var slots = new ArrayList<Slot>();
		for (int up = 0; up < HEIGHT; up++)
			for (int along = 0; along < WIDTH; along++) {
				boolean side = along == 0 || along == WIDTH - 1, end = up == 0 || up == HEIGHT - 1;
				slots.add(new Slot(at(along, up), side && end ? Role.CORNER : side || end ? Role.OBSIDIAN : Role.INTERIOR));
			}
		return List.copyOf(slots);
	}

	public List<ConstructionEscape.Position> positions(Role role) {
		return slots().stream().filter(slot -> slot.role() == role).map(Slot::position).toList();
	}

	/** The interior cell that is lit: directly above the bottom bar, so flint and steel clicks the obsidian top face. */
	public ConstructionEscape.Position ignitionCell() { return at(1, 1); }

	/** Feet positions two blocks in front of and behind the opening, at the bottom bar's level. */
	public List<ConstructionEscape.Position> stanceCandidates() {
		var stances = new ArrayList<ConstructionEscape.Position>();
		for (int side : new int[]{2, -2}) for (int along : new int[]{1, 2}) {
			var cell = at(along, 0);
			stances.add(axis == Axis.X ? new ConstructionEscape.Position(cell.x(), cell.y(), cell.z() + side)
				: new ConstructionEscape.Position(cell.x() + side, cell.y(), cell.z()));
		}
		return List.copyOf(stances);
	}

	/**
	 * Feet positions from which a straight walk reaches the opening: one or two blocks in front
	 * of or behind an opening column, standing on the bottom bar's level or one below it.
	 */
	public List<ConstructionEscape.Position> entryStanceCandidates() {
		var stances = new ArrayList<ConstructionEscape.Position>();
		for (int side : new int[]{1, -1, 2, -2}) for (int up : new int[]{1, 0}) for (int along : new int[]{1, 2}) {
			var cell = at(along, up);
			stances.add(axis == Axis.X ? new ConstructionEscape.Position(cell.x(), cell.y(), cell.z() + side)
				: new ConstructionEscape.Position(cell.x() + side, cell.y(), cell.z()));
		}
		return List.copyOf(stances);
	}

	public List<ConstructionEscape.Position> entryStances(Terrain terrain) {
		return entryStanceCandidates().stream().filter(p -> standable(terrain, p)).toList();
	}

	static boolean standable(Terrain terrain, ConstructionEscape.Position p) {
		return terrain.clear(p) && terrain.clear(new ConstructionEscape.Position(p.x(), p.y() + 1, p.z()))
			&& terrain.solidGround(new ConstructionEscape.Position(p.x(), p.y() - 1, p.z()));
	}

	/**
	 * Construction cells for the blueprint construction program. Corners keep any solid block
	 * already there ({@code cornerStates} maps such positions to their current state) and use
	 * {@code cornerItem} elsewhere; the opening is planned air so anything inside is dug out.
	 */
	public List<BlueprintConstructionProgram.Cell> constructionCells(String cornerItem, Map<ConstructionEscape.Position, String> cornerStates) {
		var cells = new ArrayList<BlueprintConstructionProgram.Cell>();
		for (var slot : slots()) {
			var p = slot.position();
			cells.add(switch (slot.role()) {
				case OBSIDIAN -> new BlueprintConstructionProgram.Cell(p.x(), p.y(), p.z(), OBSIDIAN, OBSIDIAN, "nether_portal.frame");
				case CORNER -> {
					String existing = cornerStates.get(p);
					yield existing != null
						? new BlueprintConstructionProgram.Cell(p.x(), p.y(), p.z(), existing, existing.split("\\[")[0], "nether_portal.corner")
						: new BlueprintConstructionProgram.Cell(p.x(), p.y(), p.z(), cornerItem, cornerItem, "nether_portal.corner");
				}
				case INTERIOR -> new BlueprintConstructionProgram.Cell(p.x(), p.y(), p.z(), "minecraft:air", "minecraft:air", "nether_portal.opening");
			});
		}
		return List.copyOf(cells);
	}

	public Map<String, Object> describe() {
		var value = new LinkedHashMap<String, Object>();
		value.put("x", x);
		value.put("y", y);
		value.put("z", z);
		value.put("axis", axis.id());
		var min = at(1, 1);
		var max = at(2, 3);
		value.put("opening", List.of(List.of(min.x(), min.y(), min.z()), List.of(max.x(), max.y(), max.z())));
		return value;
	}

	/** Site probe for {@link #findSite}; all predicates read loaded world state. */
	public interface Terrain {
		/** Full, non-fluid block a player can stand on and the frame can rest on. */
		boolean solidGround(ConstructionEscape.Position position);
		/** Air or a replaceable plant, with no fluid. */
		boolean clear(ConstructionEscape.Position position);
	}

	/**
	 * Nearest site where the whole frame stands on solid ground in clear air, away from the
	 * player's own cells, with a standing position in front of the opening. Searches feet
	 * level first, then one and two blocks up and down.
	 */
	public static Optional<NetherPortalFrame> findSite(ConstructionEscape.Position feet, int radius, Terrain terrain) {
		// A site the body cannot walk to is no site: from a one-cell pit a playtest builder picked a trench two
		// blocks up and failed construction_no_feasible_target three times over 2000 ticks.
		Set<ConstructionEscape.Position> walkable = walkable(feet, radius + 4, terrain);
		for (int dy : new int[]{0, -1, 1, -2, 2})
			for (int ring = 2; ring <= radius; ring++) {
				NetherPortalFrame best = null;
				double bestDistance = Double.MAX_VALUE;
				for (int dx = -ring; dx <= ring; dx++)
					for (int dz = -ring; dz <= ring; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
						for (var axis : Axis.values()) {
							var frame = new NetherPortalFrame(feet.x() + dx, feet.y() + dy, feet.z() + dz, axis);
							if (!frame.siteFits(feet, terrain)) continue;
							if (frame.stanceCandidates().stream().noneMatch(walkable::contains)) continue;
							var c = frame.at(1, 0);
							double distance = Math.pow(c.x() + .5 - feet.x(), 2) + Math.pow(c.z() + .5 - feet.z(), 2);
							if (distance < bestDistance) { best = frame; bestDistance = distance; }
						}
					}
				if (best != null) return Optional.of(best);
			}
		return Optional.empty();
	}

	/**
	 * Standing cells reachable on foot from {@code feet} without edits, stepping at most one block up (with head
	 * room to jump) or down, within {@code radius} blocks horizontally.
	 */
	static Set<ConstructionEscape.Position> walkable(ConstructionEscape.Position feet, int radius, Terrain terrain) {
		Set<ConstructionEscape.Position> seen = new HashSet<>();
		ArrayDeque<ConstructionEscape.Position> queue = new ArrayDeque<>();
		seen.add(feet);
		queue.add(feet);
		int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		while (!queue.isEmpty() && seen.size() < 4096) {
			var at = queue.poll();
			for (int[] step : steps)
				for (int dy : new int[]{0, 1, -1}) {
					var next = new ConstructionEscape.Position(at.x() + step[0], at.y() + dy, at.z() + step[1]);
					if (Math.abs(next.x() - feet.x()) > radius || Math.abs(next.z() - feet.z()) > radius
						|| Math.abs(next.y() - feet.y()) > 4 || seen.contains(next) || !standable(terrain, next)) continue;
					// Jumping up needs the cell over the head clear; stepping down needs it over the lower cell's head.
					if (dy == 1 && !terrain.clear(new ConstructionEscape.Position(at.x(), at.y() + 2, at.z()))) continue;
					if (dy == -1 && !terrain.clear(new ConstructionEscape.Position(next.x(), next.y() + 2, next.z()))) continue;
					seen.add(next);
					queue.add(next);
				}
		}
		return seen;
	}

	boolean siteFits(ConstructionEscape.Position feet, Terrain terrain) {
		for (var slot : slots()) {
			var p = slot.position();
			if (!terrain.clear(p)) return false;
			if (p.x() == feet.x() && p.z() == feet.z() && (p.y() == feet.y() || p.y() == feet.y() + 1)) return false;
		}
		for (int along = 0; along < WIDTH; along++) {
			var base = at(along, 0);
			if (!terrain.solidGround(new ConstructionEscape.Position(base.x(), base.y() - 1, base.z()))) return false;
		}
		return lightingStance(terrain).isPresent();
	}

	public Optional<ConstructionEscape.Position> lightingStance(Terrain terrain) {
		return stanceCandidates().stream().filter(p -> standable(terrain, p)).findFirst();
	}
}
