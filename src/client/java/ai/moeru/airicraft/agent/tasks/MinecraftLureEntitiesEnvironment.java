package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class MinecraftLureEntitiesEnvironment implements LureEntitiesTaskExecutor.Environment {
	private final List<UUID> identities = new ArrayList<>();
	private LureEntitiesStepArgs args;
	private static Minecraft client() { return Minecraft.getInstance(); }

	@Override public String initialize(LureEntitiesStepArgs args) {
		this.args = args;
		identities.clear();
		var minecraft = client();
		if (minecraft.level == null || minecraft.player == null) return "world_unavailable";
		for (int x : new int[]{args.x1(), args.x2()}) for (int y : new int[]{args.y1(), args.y2()}) for (int z : new int[]{args.z1(), args.z2()}) {
			var pos = new BlockPos(x,y,z);
			if (!minecraft.level.hasChunkAt(pos) || !minecraft.level.isInWorldBounds(pos)) return "destination_unloaded_or_out_of_bounds";
			if (minecraft.player.distanceToSqr(Vec3.atCenterOf(pos)) > 64 * 64) return "destination_too_far";
		}
		ItemStack lure = new ItemStack(BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(args.itemId())));
		for (String token : args.uuids()) {
			List<Entity> matches = new ArrayList<>();
			for (Entity entity : minecraft.level.entitiesForRendering()) if (entity.getStringUUID().replace("-", "").startsWith(token)) matches.add(entity);
			if (matches.size() != 1) return "follower_missing_or_ambiguous uuid=" + token;
			Entity entity = matches.getFirst();
			if (!(entity instanceof Animal animal) || !animal.isAlive()) return "follower_not_live_animal uuid=" + token;
			if (minecraft.player.distanceToSqr(entity) > 32 * 32) return "follower_too_far uuid=" + token;
			if (!animal.isFood(lure)) return "unsupported_lure_item uuid=" + token;
			if (identities.contains(entity.getUUID())) return "duplicate_follower";
			identities.add(entity.getUUID());
		}
		return null;
	}

	@Override public Vec3 position() { return client().player.position(); }

	@Override public List<LureEntitiesTaskExecutor.Follower> followers() {
		var level = client().level;
		if (level == null || client().player == null) return List.of();
		List<LureEntitiesTaskExecutor.Follower> result = new ArrayList<>();
		AABB area = new AABB(args.x1(),args.y1(),args.z1(), (double)args.x2()+1,(double)args.y2()+1,(double)args.z2()+1);
		for (Entity entity : level.entitiesForRendering()) if (identities.contains(entity.getUUID()) && entity.isAlive()) {
			AABB body = entity.getBoundingBox();
			boolean inside = body.minX >= area.minX && body.maxX <= area.maxX && body.minY >= area.minY && body.maxY <= area.maxY
				&& body.minZ >= area.minZ && body.maxZ <= area.maxZ;
			result.add(new LureEntitiesTaskExecutor.Follower(entity.getStringUUID(), entity.position(), inside, client().player.hasLineOfSight(entity)));
		}
		return List.copyOf(result);
	}

	@Override public String holdItem() {
		var minecraft = client();
		var player = minecraft.player;
		if (player == null || minecraft.gameMode == null) return "world_unavailable";
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) return "inventory_busy";
		if (minecraft.screen != null) ScreenCloseSafety.clearScreen(minecraft, "lure_entities");
		if (BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString().equals(args.itemId())) return null;
		for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_END; slot++) {
			ItemStack stack = player.containerMenu.getSlot(slot).getItem();
			if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(args.itemId())) continue;
			if (slot >= InventoryMenu.USE_ROW_SLOT_START) player.getInventory().setSelectedSlot(slot - InventoryMenu.USE_ROW_SLOT_START);
			else {
				minecraft.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, slot, 8, ClickType.SWAP, player);
				player.getInventory().setSelectedSlot(8);
			}
			return null;
		}
		return "lure_item_missing";
	}

	@Override public List<GoalPosition> leadPositions(List<LureEntitiesTaskExecutor.Follower> followers) {
		var level = client().level;
		List<GoalPosition> candidates = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(args.x1(),args.y1(),args.z1()), new BlockPos(args.x2(),args.y2(),args.z2()))) {
			if (!level.hasChunkAt(pos) || !level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) continue;
			if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) continue;
			AABB body = new AABB(pos.getX()+0.2,pos.getY(),pos.getZ()+0.2,pos.getX()+0.8,pos.getY()+1.8,pos.getZ()+0.8);
			if (level.getBlockCollisions(client().player, body).iterator().hasNext()) continue;
			candidates.add(new GoalPosition(pos.getX(),pos.getY(),pos.getZ(),true));
		}
		// Re-evaluate from the animals at the entrance, not only their original approach direction.
		candidates.sort(Comparator.comparingDouble((GoalPosition p) -> followers.stream().filter(f -> !f.inside())
			.mapToDouble(f -> new Vec3(p.x()+0.5,p.y(),p.z()+0.5).distanceToSqr(f.position())).min().orElse(0)).reversed());
		return List.copyOf(candidates);
	}
}
