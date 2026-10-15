package ai.moeru.airicraft.portal;

import ai.moeru.airicraft.blueprint.Blueprint;
import ai.moeru.airicraft.blueprint.BlueprintConstructionProgram;
import ai.moeru.airicraft.blueprint.ConstructionEscape;
import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Live adapter for {@link NetherPortalProgram}: picks the site and materials and reads the frame back. */
public final class MinecraftNetherPortal {
	/** Non-flammable full blocks a survival player usually carries; corners take one so obsidian goes where it matters. */
	static final List<String> CORNER_FILLERS = List.of("minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:stone",
		"minecraft:deepslate", "minecraft:netherrack", "minecraft:blackstone", "minecraft:andesite", "minecraft:diorite",
		"minecraft:granite", "minecraft:tuff", "minecraft:stone_bricks", "minecraft:sandstone", "minecraft:dirt");
	static final List<String> IGNITERS = List.of("minecraft:flint_and_steel", "minecraft:fire_charge");
	static final int SITE_RADIUS = 8;

	public record Prepared(NetherPortalFrame frame, List<BlueprintConstructionProgram.Cell> cells, String igniter, boolean light, boolean enter,
		String cornerItem, String site) { }

	private MinecraftNetherPortal() { }

	public static Prepared prepare(Minecraft client, JsonObject args) {
		var player = client.player;
		var level = client.level;
		if (player == null || level == null) throw new IllegalStateException("world_not_loaded");
		if (client.getSingleplayerServer() == null) throw new IllegalStateException("singleplayer_required");
		args = args.deepCopy();
		for (String key : List.of("x", "y", "z", "axis", "light", "enter")) if (args.has(key) && args.get(key).isJsonNull()) args.remove(key);
		boolean light = !args.has("light") || args.get("light").getAsBoolean();
		boolean enter = args.has("enter") && args.get("enter").getAsBoolean();
		if (enter && !light) throw new IllegalArgumentException("enter requires light");
		if (light && level.dimension() == Level.END) throw new IllegalStateException("portal_cannot_light_in_end");
		var terrain = terrain(level);
		boolean hasPosition = args.has("x") || args.has("y") || args.has("z");
		NetherPortalFrame frame;
		String site = "given";
		Optional<NetherPortalFrame> existing = hasPosition ? Optional.empty() : nearbyLitPortal(level, player.blockPosition());
		if (existing.isPresent()) {
			frame = existing.get();
			site = "existing_portal";
		} else if (hasPosition) {
			if (!args.has("x") || !args.has("y") || !args.has("z") || !args.has("axis"))
				throw new IllegalArgumentException("x, y, z and axis are required together");
			frame = new NetherPortalFrame(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt(),
				NetherPortalFrame.Axis.parse(args.get("axis").getAsString()));
		} else {
			var feet = player.blockPosition();
			site = "chosen";
			frame = NetherPortalFrame.findSite(new ConstructionEscape.Position(feet.getX(), feet.getY(), feet.getZ()), SITE_RADIUS, terrain)
				.orElseThrow(() -> new IllegalStateException("portal_site_not_found: no flat clear 4x5 spot with solid ground within "
					+ SITE_RADIUS + " blocks that the body can walk to; move to open flat ground or pass x, y, z and axis"));
		}
		for (var slot : frame.slots()) {
			var pos = block(slot.position());
			if (!level.hasChunkAt(pos) || !pos.closerThan(player.blockPosition(), 64) || pos.getY() < level.getMinY() || pos.getY() > level.getMaxY())
				throw new IllegalStateException("portal_site_out_of_loaded_nearby_world");
		}
		boolean lit = frame.positions(NetherPortalFrame.Role.INTERIOR).stream()
			.allMatch(p -> level.getBlockState(block(p)).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL));
		var cornerStates = new HashMap<ConstructionEscape.Position, String>();
		for (var corner : frame.positions(NetherPortalFrame.Role.CORNER)) {
			var pos = block(corner);
			var state = level.getBlockState(pos);
			if (!state.canBeReplaced() && level.getFluidState(pos).isEmpty() && state.isCollisionShapeFullBlock(level, pos))
				cornerStates.put(corner, Blueprint.stateText(state));
		}
		int obsidianNeeded = (int) frame.positions(NetherPortalFrame.Role.OBSIDIAN).stream()
			.filter(p -> !level.getBlockState(block(p)).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)).count();
		int cornersNeeded = frame.positions(NetherPortalFrame.Role.CORNER).size() - cornerStates.size();
		var counts = inventoryCounts(client);
		int obsidian = counts.getOrDefault(NetherPortalFrame.OBSIDIAN, 0);
		if (obsidian < obsidianNeeded)
			throw new IllegalStateException("portal_material_missing itemId=" + NetherPortalFrame.OBSIDIAN + " have=" + obsidian + " need=" + obsidianNeeded);
		// Each missing corner takes whichever filler is left, so two cobblestone and two dirt cover four corners.
		var used = new java.util.LinkedHashSet<String>();
		if (!lit) {
			var left = new HashMap<>(counts);
			left.put(NetherPortalFrame.OBSIDIAN, obsidian - obsidianNeeded);
			var fillers = new java.util.ArrayList<>(CORNER_FILLERS);
			fillers.add(NetherPortalFrame.OBSIDIAN);
			for (var corner : frame.positions(NetherPortalFrame.Role.CORNER)) {
				if (cornerStates.containsKey(corner)) continue;
				String filler = fillers.stream().filter(id -> left.getOrDefault(id, 0) > 0).findFirst().orElse(null);
				if (filler == null)
					throw new IllegalStateException("portal_material_missing corners need " + cornersNeeded
						+ " of any full block (cobblestone, dirt, stone...) or extra obsidian; have obsidian=" + obsidian + " need=" + obsidianNeeded);
				left.merge(filler, -1, Integer::sum);
				cornerStates.put(corner, filler);
				used.add(filler);
			}
		}
		String cornerItem = used.isEmpty() ? NetherPortalFrame.OBSIDIAN : String.join(",", used);
		String igniter = IGNITERS.stream().filter(id -> counts.getOrDefault(id, 0) > 0).findFirst().orElse(null);
		if (lit && igniter == null) igniter = IGNITERS.getFirst(); // Never used: a lit portal is not relit.
		if (light && igniter == null) throw new IllegalStateException("portal_igniter_missing: carry minecraft:flint_and_steel or minecraft:fire_charge, or pass light=false");
		if (lit) {
			// Keep whatever corners a lit portal already has; construction must not touch it.
			for (var corner : frame.positions(NetherPortalFrame.Role.CORNER))
				cornerStates.putIfAbsent(corner, Blueprint.stateText(level.getBlockState(block(corner))));
		}
		return new Prepared(frame, frame.constructionCells(NetherPortalFrame.OBSIDIAN, cornerStates), igniter, light, enter, cornerItem, site);
	}

	/**
	 * The nearest lit minimal portal (2 by 3 opening) within 16 blocks, so asking to build a portal
	 * next to one, or to enter one, reuses it instead of building a second frame.
	 */
	static Optional<NetherPortalFrame> nearbyLitPortal(ClientLevel level, BlockPos center) {
		NetherPortalFrame best = null;
		double bestDistance = Double.MAX_VALUE;
		for (var pos : BlockPos.betweenClosed(center.offset(-16, -8, -16), center.offset(16, 8, 16))) {
			if (!level.hasChunkAt(pos)) continue;
			var state = level.getBlockState(pos);
			if (!state.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) continue;
			var axis = state.getValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS) == net.minecraft.core.Direction.Axis.X
				? NetherPortalFrame.Axis.X : NetherPortalFrame.Axis.Z;
			// Only the lower-left portal block of an opening anchors a frame.
			var below = pos.below();
			var before = axis == NetherPortalFrame.Axis.X ? pos.west() : pos.north();
			if (level.getBlockState(below).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)
				|| level.getBlockState(before).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) continue;
			var frame = axis == NetherPortalFrame.Axis.X
				? new NetherPortalFrame(pos.getX() - 1, pos.getY() - 1, pos.getZ(), axis)
				: new NetherPortalFrame(pos.getX(), pos.getY() - 1, pos.getZ() - 1, axis);
			boolean minimal = frame.slots().stream().allMatch(slot -> {
				var blockState = level.getBlockState(block(slot.position()));
				return switch (slot.role()) {
					case INTERIOR -> blockState.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL);
					case OBSIDIAN -> blockState.is(net.minecraft.world.level.block.Blocks.OBSIDIAN);
					case CORNER -> true;
				};
			});
			if (!minimal) continue;
			double distance = pos.distSqr(center);
			if (distance < bestDistance) { best = frame; bestDistance = distance; }
		}
		return Optional.ofNullable(best);
	}

	static boolean touchingPortal(ClientLevel level, AABB box) {
		for (var pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ)))
			if (level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) return true;
		return false;
	}

	static AABB openingBox(NetherPortalFrame frame) {
		var low = frame.at(1, 1);
		var high = frame.at(2, 3);
		return new AABB(low.x(), low.y(), low.z(), high.x() + 1, high.y() + 1, high.z() + 1);
	}

	public static NetherPortalProgram.World world(Minecraft client, NetherPortalFrame frame) {
		var level = client.level;
		var owner = client.player;
		var terrain = terrain(level);
		var opening = openingBox(frame);
		// Portal blocks only collide with the thin slab in the middle of the opening.
		var low = frame.at(1, 1);
		var high = frame.at(2, 3);
		var portalSlab = frame.axis() == NetherPortalFrame.Axis.X
			? new AABB(low.x(), low.y(), low.z() + .375, high.x() + 1, high.y() + 1, low.z() + .625)
			: new AABB(low.x() + .375, low.y(), low.z(), low.x() + .625, high.y() + 1, high.z() + 1);
		var target = portalSlab.getCenter();
		var movement = new ai.moeru.airicraft.agent.control.MovementController("nether_portal_entry", ai.moeru.airicraft.control.Priority.FOREGROUND);
		var camera = new ai.moeru.airicraft.agent.control.CameraController();
		return new NetherPortalProgram.World() {
			private long walkTick;
			private boolean started, leaving;
			private int cooldownTicks;
			private NetherPortalFrame arrival;
			private final AABB openingBox = opening;
			private void requireWorld() {
				if (client.level != level || client.player != owner) throw new IllegalStateException("portal_world_changed");
			}
			@Override public String state(ConstructionEscape.Position position) {
				requireWorld();
				var pos = block(position);
				return level.hasChunkAt(pos) ? Blueprint.stateText(level.getBlockState(pos)) : "unknown_unloaded";
			}
			@Override public ConstructionEscape.Position lightingStance() {
				requireWorld();
				return frame.lightingStance(terrain).orElse(null);
			}
			@Override public ConstructionEscape.Position entryStance() {
				requireWorld();
				var feet = owner.position();
				return frame.entryStances(terrain).stream()
					.min(java.util.Comparator.comparingDouble(p -> feet.distanceToSqr(p.x() + .5, p.y(), p.z() + .5))).orElse(null);
			}
			@Override public boolean playerNear(ConstructionEscape.Position position) {
				var player = client.player;
				return player != null && player.position().distanceToSqr(position.x() + .5, position.y(), position.z() + .5) <= 2.25;
			}
			@Override public String playerPosition() {
				var player = client.player;
				return player == null ? "unknown" : String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", player.getX(), player.getY(), player.getZ());
			}
			@Override public boolean playerInOpening() {
				requireWorld();
				return owner.getBoundingBox().intersects(opening);
			}
			@Override public NetherPortalProgram.Entry enterStep() {
				// The level is swapped while the destination loads; keep waiting until a new one exists.
				if (client.level == null || client.player == null) return NetherPortalProgram.Entry.INSIDE;
				if (client.level != level || client.player != owner) return NetherPortalProgram.Entry.DIMENSION_CHANGED;
				if (!started) {
					started = true;
					// With nowhere to step out to, stay inside and let the portal fire.
					leaving = owner.getBoundingBox().intersects(openingBox) && entryStance() != null;
				}
				if (leaving) {
					// A portal only fires again after the player has left it.
					if (!owner.getBoundingBox().intersects(openingBox)) {
						leaving = false;
						// Touching the portal keeps refreshing the arrival cooldown; let it run out before stepping back in.
						cooldownTicks = 40;
						movement.stop(client);
					} else {
						var stance = entryStance();
						if (stance == null) throw new IllegalStateException("portal_entry_stance_not_found");
						camera.lookAt(client, new net.minecraft.world.phys.Vec3(stance.x() + .5, owner.getEyeY(), stance.z() + .5));
						camera.tick(client);
						movement.moveForward(client, false, false, walkTick++);
						return NetherPortalProgram.Entry.MOVING;
					}
				}
				if (cooldownTicks > 0) {
					cooldownTicks--;
					return NetherPortalProgram.Entry.MOVING;
				}
				if (owner.getBoundingBox().intersects(portalSlab)) {
					movement.stop(client);
					return NetherPortalProgram.Entry.INSIDE;
				}
				camera.lookAt(client, new net.minecraft.world.phys.Vec3(target.x, owner.getEyeY(), target.z));
				camera.tick(client);
				movement.moveForward(client, false, false, walkTick++);
				return NetherPortalProgram.Entry.MOVING;
			}
			@Override public boolean leaveArrivalStep() {
				var player = client.player;
				var current = client.level;
				if (player == null || current == null) return false;
				// Destination chunks can arrive a few ticks after the player. A portal that is not the
				// minimal frame is never recognised; it cannot be walked out of or reported.
				if (arrival == null) arrival = nearbyLitPortal(current, player.blockPosition()).orElse(null);
				// Out means no portal block touches the body, whatever portal it arrived in.
				if (!touchingPortal(current, player.getBoundingBox())) {
					movement.stop(client);
					return true;
				}
				if (arrival == null) return false;
				var feet = player.position();
				var stance = arrival.entryStances(terrain(current)).stream()
					.min(java.util.Comparator.comparingDouble(p -> feet.distanceToSqr(p.x() + .5, p.y(), p.z() + .5))).orElse(null);
				if (stance == null) return false; // Nowhere safe to step; stay put rather than walk blind.
				camera.lookAt(client, new net.minecraft.world.phys.Vec3(stance.x() + .5, player.getEyeY(), stance.z() + .5));
				camera.tick(client);
				movement.moveForward(client, false, false, walkTick++);
				return false;
			}
			@Override public java.util.Map<String, Object> arrivalPortal() {
				return arrival == null ? null : arrival.describe();
			}
			@Override public void stopMoving() {
				movement.stop(client);
				camera.clear();
			}
			@Override public String dimension() {
				var current = client.level;
				return current == null ? "" : current.dimension().location().toString();
			}
			@Override public void close() { stopMoving(); }
		};
	}

	static NetherPortalFrame.Terrain terrain(ClientLevel level) {
		return new NetherPortalFrame.Terrain() {
			@Override public boolean solidGround(ConstructionEscape.Position position) {
				var pos = block(position);
				return level.hasChunkAt(pos) && level.getFluidState(pos).isEmpty() && level.getBlockState(pos).isCollisionShapeFullBlock(level, pos);
			}
			@Override public boolean clear(ConstructionEscape.Position position) {
				var pos = block(position);
				return level.hasChunkAt(pos) && level.getFluidState(pos).isEmpty() && level.getBlockState(pos).canBeReplaced();
			}
		};
	}

	static Map<String, Integer> inventoryCounts(Minecraft client) {
		var counts = new HashMap<String, Integer>();
		var inventory = client.player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			var stack = inventory.getItem(slot);
			if (!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
		}
		return counts;
	}

	static BlockPos block(ConstructionEscape.Position p) { return new BlockPos(p.x(), p.y(), p.z()); }
}
