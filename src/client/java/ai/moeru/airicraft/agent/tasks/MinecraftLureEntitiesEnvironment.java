package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class MinecraftLureEntitiesEnvironment implements LureEntitiesTaskExecutor.Environment {
	private final List<UUID> identities = new ArrayList<>();
	private final Map<Settings.Setting<Boolean>, Boolean> savedSettings = new LinkedHashMap<>();
	private LureEntitiesStepArgs args;
	private static MinecraftClient client() { return MinecraftClient.getInstance(); }

	@Override public String initialize(LureEntitiesStepArgs args) {
		this.args = args;
		identities.clear();
		var client = client();
		if (client.world == null || client.player == null) return "world_unavailable";
		for (int x : new int[]{args.x1(), args.x2()}) for (int y : new int[]{args.y1(), args.y2()}) for (int z : new int[]{args.z1(), args.z2()}) {
			var pos = new BlockPos(x,y,z);
			if (!client.world.isChunkLoaded(pos) || !client.world.isInBuildLimit(pos)) return "destination_unloaded_or_out_of_bounds";
			if (client.player.squaredDistanceTo(Vec3d.ofCenter(pos)) > 64 * 64) return "destination_too_far";
		}
		ItemStack lure = new ItemStack(Registries.ITEM.get(Identifier.of(args.itemId())));
		for (String token : args.uuids()) {
			List<Entity> matches = new ArrayList<>();
			for (Entity entity : client.world.getEntities()) if (entity.getUuidAsString().replace("-", "").startsWith(token)) matches.add(entity);
			if (matches.size() != 1) return "follower_missing_or_ambiguous uuid=" + token;
			Entity entity = matches.getFirst();
			if (!(entity instanceof AnimalEntity animal) || !animal.isAlive()) return "follower_not_live_animal uuid=" + token;
			if (client.player.squaredDistanceTo(entity) > 32 * 32) return "follower_too_far uuid=" + token;
			if (!animal.isBreedingItem(lure)) return "unsupported_lure_item uuid=" + token;
			if (identities.contains(entity.getUuid())) return "duplicate_follower";
			identities.add(entity.getUuid());
		}
		return null;
	}

	@Override public Vec3d position() { return client().player.getPos(); }

	@Override public List<LureEntitiesTaskExecutor.Follower> followers() {
		var world = client().world;
		if (world == null || client().player == null) return List.of();
		List<LureEntitiesTaskExecutor.Follower> result = new ArrayList<>();
		Box area = new Box(args.x1(),args.y1(),args.z1(), (double)args.x2()+1,(double)args.y2()+1,(double)args.z2()+1);
		for (Entity entity : world.getEntities()) if (identities.contains(entity.getUuid()) && entity.isAlive()) {
			Box body = entity.getBoundingBox();
			boolean inside = body.minX >= area.minX && body.maxX <= area.maxX && body.minY >= area.minY && body.maxY <= area.maxY
				&& body.minZ >= area.minZ && body.maxZ <= area.maxZ;
			result.add(new LureEntitiesTaskExecutor.Follower(entity.getUuidAsString(), entity.getPos(), inside, client().player.canSee(entity)));
		}
		return List.copyOf(result);
	}

	@Override public String holdItem() {
		var client = client();
		var player = client.player;
		if (player == null || client.interactionManager == null) return "world_unavailable";
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) return "inventory_busy";
		if (client.currentScreen != null) ScreenCloseSafety.clearScreen(client, "lure_entities");
		if (Registries.ITEM.getId(player.getMainHandStack().getItem()).toString().equals(args.itemId())) return null;
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			ItemStack stack = player.currentScreenHandler.getSlot(slot).getStack();
			if (stack.isEmpty() || !Registries.ITEM.getId(stack.getItem()).toString().equals(args.itemId())) continue;
			// Slot 0 belongs to Baritone's best-pick housekeeping. Keep the lure in another slot.
			if (slot > PlayerScreenHandler.HOTBAR_START) player.getInventory().setSelectedSlot(slot - PlayerScreenHandler.HOTBAR_START);
			else {
				client.interactionManager.clickSlot(player.currentScreenHandler.syncId, slot, 8, SlotActionType.SWAP, player);
				player.getInventory().setSelectedSlot(8);
			}
			return null;
		}
		return "lure_item_missing";
	}

	@Override public List<GoalPosition> leadPositions(List<LureEntitiesTaskExecutor.Follower> followers) {
		var world = client().world;
		List<GoalPosition> candidates = new ArrayList<>();
		for (BlockPos pos : BlockPos.iterate(new BlockPos(args.x1(),args.y1(),args.z1()), new BlockPos(args.x2(),args.y2(),args.z2()))) {
			if (!world.isChunkLoaded(pos) || !world.getFluidState(pos).isEmpty() || !world.getFluidState(pos.up()).isEmpty()) continue;
			if (!world.getBlockState(pos.down()).isSideSolidFullSquare(world, pos.down(), Direction.UP)) continue;
			Box body = new Box(pos.getX()+0.2,pos.getY(),pos.getZ()+0.2,pos.getX()+0.8,pos.getY()+1.8,pos.getZ()+0.8);
			if (world.getBlockCollisions(client().player, body).iterator().hasNext()) continue;
			candidates.add(new GoalPosition(pos.getX(),pos.getY(),pos.getZ(),true));
		}
		// Re-evaluate from the animals at the entrance, not only their original approach direction.
		candidates.sort(Comparator.comparingDouble((GoalPosition p) -> followers.stream().filter(f -> !f.inside())
			.mapToDouble(f -> new Vec3d(p.x()+0.5,p.y(),p.z()+0.5).squaredDistanceTo(f.position())).min().orElse(0)).reversed());
		return List.copyOf(candidates);
	}

	@Override public void beginTravel() {
		ai.moeru.airicraft.agent.navigation.NavigationPolicies.setWalkOnly(true);
		if (!savedSettings.isEmpty()) return;
		var settings = BaritoneAPI.getSettings();
		for (var setting : List.of(settings.allowBreak, settings.allowPlace, settings.allowSprint, settings.allowParkour,
			settings.allowInventory, settings.allowWaterBucketFall)) {
			savedSettings.put(setting, setting.value);
			setting.value = false;
		}
	}

	@Override public void release() {
		ai.moeru.airicraft.agent.navigation.NavigationPolicies.setWalkOnly(false);
		savedSettings.forEach((setting, value) -> setting.value = value);
		savedSettings.clear();
	}
}
