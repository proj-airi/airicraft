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

	public record Prepared(NetherPortalFrame frame, List<BlueprintConstructionProgram.Cell> cells, String igniter, boolean light, String cornerItem, boolean siteChosen) { }

	private MinecraftNetherPortal() { }

	public static Prepared prepare(Minecraft client, JsonObject args) {
		var player = client.player;
		var level = client.level;
		if (player == null || level == null) throw new IllegalStateException("world_not_loaded");
		if (client.getSingleplayerServer() == null) throw new IllegalStateException("singleplayer_required");
		args = args.deepCopy();
		for (String key : List.of("x", "y", "z", "axis", "light")) if (args.has(key) && args.get(key).isJsonNull()) args.remove(key);
		boolean light = !args.has("light") || args.get("light").getAsBoolean();
		if (light && level.dimension() == Level.END) throw new IllegalStateException("portal_cannot_light_in_end");
		var terrain = terrain(level);
		boolean hasPosition = args.has("x") || args.has("y") || args.has("z");
		NetherPortalFrame frame;
		if (hasPosition) {
			if (!args.has("x") || !args.has("y") || !args.has("z") || !args.has("axis"))
				throw new IllegalArgumentException("x, y, z and axis are required together");
			frame = new NetherPortalFrame(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt(),
				NetherPortalFrame.Axis.parse(args.get("axis").getAsString()));
		} else {
			var feet = player.blockPosition();
			frame = NetherPortalFrame.findSite(new ConstructionEscape.Position(feet.getX(), feet.getY(), feet.getZ()), SITE_RADIUS, terrain)
				.orElseThrow(() -> new IllegalStateException("portal_site_not_found: no flat clear 4x5 spot with solid ground within "
					+ SITE_RADIUS + " blocks; move to open flat ground or pass x, y, z and axis"));
		}
		for (var slot : frame.slots()) {
			var pos = block(slot.position());
			if (!level.hasChunkAt(pos) || !pos.closerThan(player.blockPosition(), 64) || pos.getY() < level.getMinY() || pos.getY() > level.getMaxY())
				throw new IllegalStateException("portal_site_out_of_loaded_nearby_world");
		}
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
		String cornerItem = NetherPortalFrame.OBSIDIAN;
		if (cornersNeeded > 0) {
			cornerItem = CORNER_FILLERS.stream().filter(id -> counts.getOrDefault(id, 0) >= cornersNeeded).findFirst()
				.orElseGet(() -> obsidian >= obsidianNeeded + cornersNeeded ? NetherPortalFrame.OBSIDIAN : null);
			if (cornerItem == null)
				throw new IllegalStateException("portal_material_missing corners need " + cornersNeeded
					+ " of any full block (cobblestone, dirt, stone...) or extra obsidian; have obsidian=" + obsidian + " need=" + obsidianNeeded);
		}
		String igniter = IGNITERS.stream().filter(id -> counts.getOrDefault(id, 0) > 0).findFirst().orElse(null);
		if (light && igniter == null) throw new IllegalStateException("portal_igniter_missing: carry minecraft:flint_and_steel or minecraft:fire_charge, or pass light=false");
		return new Prepared(frame, frame.constructionCells(cornerItem, cornerStates), igniter, light, cornerItem, !hasPosition);
	}

	public static NetherPortalProgram.World world(Minecraft client, NetherPortalFrame frame) {
		var level = client.level;
		var owner = client.player;
		var terrain = terrain(level);
		var opening = new AABB(Math.min(frame.at(1, 1).x(), frame.at(2, 3).x()), frame.at(1, 1).y(), Math.min(frame.at(1, 1).z(), frame.at(2, 3).z()),
			Math.max(frame.at(1, 1).x(), frame.at(2, 3).x()) + 1, frame.at(2, 3).y() + 1, Math.max(frame.at(1, 1).z(), frame.at(2, 3).z()) + 1);
		return new NetherPortalProgram.World() {
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
			@Override public boolean playerInOpening() {
				requireWorld();
				return owner.getBoundingBox().intersects(opening);
			}
			@Override public void close() { }
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
