package ai.moeru.airicraft.agent.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import java.util.List;
import java.util.Set;

/** Bounded client-visible facts; never reads server-only animal timers. */
public final class WorksiteObservation {
	private WorksiteObservation() {}
	public record Region(int x1, int y1, int z1, int x2, int y2, int z2, List<BlockPos> points, Set<String> sheepIds) {
		public Region {
			for (int coordinate : new int[]{x1,y1,z1,x2,y2,z2}) if (Math.abs((long)coordinate) >= 30_000_000)
				throw new IllegalArgumentException("worksite coordinate out of bounds");
			if (x2 < x1 || y2 < y1 || z2 < z1 || (long)x2 - x1 >= 32 || (long)y2 - y1 >= 16 || (long)z2 - z1 >= 32)
				throw new IllegalArgumentException("worksite bounds must fit 32 by 16 by 32");
			points = List.copyOf(points);
			sheepIds = Set.copyOf(sheepIds);
			if (sheepIds.size() > 32) throw new IllegalArgumentException("at most 32 tracked sheep");
			for (String id : sheepIds) java.util.UUID.fromString(id);
			if (points.size() > 8) throw new IllegalArgumentException("at most eight worksite points");
			for (BlockPos p : points) if (p.getX() < x1 || p.getX() > x2 || p.getY() <= y1 || p.getY() > y2 || p.getZ() < z1 || p.getZ() > z2)
				throw new IllegalArgumentException("worksite point and support must be inside region");
		}
	}
	public static Region parse(JsonObject args) {
		for (String key : args.keySet()) if (!Set.of("x1", "y1", "z1", "x2", "y2", "z2", "points", "sheepIds", "narration").contains(key))
			throw new IllegalArgumentException("unknown worksite argument: " + key);
		var points = new java.util.ArrayList<BlockPos>();
		if (args.has("points")) for (var value : args.getAsJsonArray("points")) {
			var point = value.getAsJsonObject();
			if (!Set.of("x", "y", "z").containsAll(point.keySet())) throw new IllegalArgumentException("unknown point argument");
			points.add(new BlockPos(integer(point, "x"), integer(point, "y"), integer(point, "z")));
		}
		var sheepIds = new java.util.HashSet<String>();
		if (args.has("sheepIds")) {
			var ids = args.getAsJsonArray("sheepIds");
			if (ids.size() > 32) throw new IllegalArgumentException("at most 32 tracked sheep");
			for (var id : ids) {
				if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("sheep UUID must be a string");
				sheepIds.add(id.getAsString());
			}
		}
		return new Region(integer(args,"x1"), integer(args,"y1"), integer(args,"z1"), integer(args,"x2"), integer(args,"y2"), integer(args,"z2"), points, sheepIds);
	}
	private static int integer(JsonObject args, String key) {
		var value = args.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be an integer");
		return value.getAsBigDecimal().intValueExact();
	}
	public static JsonObject inspect(Region r) {
		var c = MinecraftClient.getInstance();
		if (c.world == null || c.player == null) throw new IllegalStateException("world_unavailable");
		for (int x : new int[]{r.x1,r.x2}) for (int z : new int[]{r.z1,r.z2})
			if (c.player.squaredDistanceTo(x, r.y1, z) > 96 * 96) throw new IllegalArgumentException("worksite_too_far");
		JsonObject result = new JsonObject();
		result.addProperty("worldIdentity", Integer.toHexString(System.identityHashCode(c.world)));
		result.addProperty("dimension", c.world.getRegistryKey().getValue().toString());
		result.addProperty("worldTick", c.world.getTime());
		result.addProperty("paused", c.isPaused());
		result.addProperty("playerAlive", c.player.isAlive());
		result.addProperty("hookActive", c.player.fishHook != null);
		result.add("player", position(c.player.getX(), c.player.getY(), c.player.getZ()));
		var handler = c.player.currentScreenHandler;
		JsonObject screen = new JsonObject();
		screen.addProperty("kind", handler == c.player.playerScreenHandler ? "player_inventory"
			: handler instanceof GenericContainerScreenHandler ? "container" : "other");
		screen.addProperty("syncId", handler.syncId);
		screen.addProperty("cursorEmpty", handler.getCursorStack().isEmpty());
		result.add("screen", screen);
		JsonObject inventory = new JsonObject();
		int free = 0;
		for (int i = 0; i < 36; i++) {
			var stack = c.player.getInventory().getStack(i);
			if (stack.isEmpty()) { free++; continue; }
			String id = Registries.ITEM.getId(stack.getItem()).toString();
			inventory.addProperty(id, (inventory.has(id) ? inventory.get(id).getAsInt() : 0) + stack.getCount());
		}
		result.add("inventory", inventory); result.addProperty("freeSlots", free);
		JsonArray blocks = new JsonArray();
		boolean known = true, truncated = false;
		int[] leaves = new int[r.points.size()];
		for (BlockPos p : BlockPos.iterate(r.x1,r.y1,r.z1,r.x2,r.y2,r.z2)) {
			if (!c.world.isChunkLoaded(p)) { known = false; continue; }
			var state = c.world.getBlockState(p);
			if (state.isOf(Blocks.BIRCH_LEAVES)) for (int i = 0; i < r.points.size(); i++) {
				var root = r.points.get(i);
				if (Math.abs(p.getX()-root.getX()) <= 3 && Math.abs(p.getZ()-root.getZ()) <= 3 && p.getY() >= root.getY() && p.getY() <= root.getY()+10) leaves[i]++;
			}
			if (!(state.getBlock() instanceof CropBlock) && !state.isOf(Blocks.BIRCH_LOG) && !state.isOf(Blocks.BIRCH_SAPLING)
				&& !state.isOf(Blocks.COMPOSTER) && !(state.getBlock() instanceof FenceGateBlock)) continue;
			if (blocks.size() >= 256) { truncated = true; continue; }
			blocks.add(block(p, state));
		}
		JsonArray points = new JsonArray();
		for (int i = 0; i < r.points.size(); i++) {
			var p = r.points.get(i); var item = block(p, c.world.getBlockState(p));
			item.addProperty("support", Registries.BLOCK.getId(c.world.getBlockState(p.down()).getBlock()).toString());
			item.addProperty("leaves", leaves[i]); points.add(item);
		}
		var box = new Box(r.x1,r.y1,r.z1,r.x2+1.0,r.y2+1.0,r.z2+1.0);
		JsonArray sheep = new JsonArray();
		// A bounded surrounding margin lets a behavior recover known escapees.
		for (var entity : c.world.getEntitiesByClass(SheepEntity.class, c.player.getBoundingBox().expand(128),
			e -> e.isAlive() && (e.getBoundingBox().intersects(box.expand(16)) || r.sheepIds.contains(e.getUuidAsString())))) {
			if (sheep.size() >= 32) { truncated = true; break; }
			var item = position(entity.getX(),entity.getY(),entity.getZ());
			item.addProperty("uuid", entity.getUuidAsString()); item.addProperty("baby", entity.isBaby());
			item.addProperty("sheared", entity.isSheared()); item.addProperty("cooldownKnown", false); sheep.add(item);
		}
		JsonArray items = new JsonArray();
		for (var entity : c.world.getEntitiesByClass(ItemEntity.class, box, e -> e.isAlive())) {
			if (items.size() >= 64) { truncated = true; break; }
			var item = position(entity.getX(),entity.getY(),entity.getZ());
			item.addProperty("uuid", entity.getUuidAsString());
			item.addProperty("itemId", Registries.ITEM.getId(entity.getStack().getItem()).toString());
			item.addProperty("count", entity.getStack().getCount()); items.add(item);
		}
		result.add("blocks", blocks); result.add("points", points); result.add("sheep", sheep); result.add("items", items);
		result.addProperty("known", known && !truncated); result.addProperty("truncated", truncated); return result;
	}
	private static JsonObject position(double x, double y, double z) {
		var p = new JsonObject(); p.addProperty("x",x); p.addProperty("y",y); p.addProperty("z",z); return p;
	}
	private static JsonObject block(BlockPos p, BlockState state) {
		var result = position(p.getX(),p.getY(),p.getZ());
		result.addProperty("id", Registries.BLOCK.getId(state.getBlock()).toString());
		result.addProperty("replaceable", state.isReplaceable() || state.isAir());
		JsonObject properties = new JsonObject();
		state.getEntries().forEach((key,value) -> properties.addProperty(key.getName(), value.toString()));
		result.add("properties", properties); return result;
	}
}
