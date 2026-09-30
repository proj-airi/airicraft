package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.agent.memory.PlaceMemory;
import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;
import ai.moeru.airicraft.agent.spatial.TravelBounds;
import ai.moeru.airicraft.agent.spatial.WorldTravelPolicy;
import ai.moeru.airicraft.navigation.Avoidance;
import ai.moeru.airicraft.navigation.Box;
import ai.moeru.airicraft.navigation.MovementPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Builds the movement policy for one navigation request from the live world and inventory. */
public final class NavigationPolicies {
	/** Blocks navigation may place for bridges and pillars. */
	public static final Set<String> THROWAWAY_BLOCKS = Set.of("minecraft:cobblestone", "minecraft:cobbled_deepslate",
		"minecraft:dirt", "minecraft:netherrack", "minecraft:stone", "minecraft:andesite", "minecraft:diorite",
		"minecraft:granite", "minecraft:tuff", "minecraft:blackstone", "minecraft:end_stone", "minecraft:deepslate");
	private static final double MOB_AVOIDANCE_RADIUS = 16;
	private static final double MOB_AVOIDANCE_COEFFICIENT = 4.0;
	private static final double MOB_SCAN_RADIUS = 40;

	private NavigationPolicies() {
	}

	/**
	 * The policy for one request: the planner's {@link PathfindSettings} and the request's options,
	 * narrowed by the live world and inventory. Null when the travel policy forbids moving at all.
	 */
	public static MovementPolicy forPlayer(Minecraft minecraft, NavigationOptions options) {
		LocalPlayer player = minecraft.player;
		PathfindSettings.Values settings = PathfindSettings.current();
		MovementPolicy policy = MovementPolicy.defaults()
			.withBreaking(settings.allowBreak())
			.withPlacing(settings.allowPlace())
			.withMaxSafeFall(settings.maxFallHeight())
			.withWaterPenalty(options.waterPenalty() != null ? options.waterPenalty() : settings.waterCost())
			.withSprint(player.getFoodData().getFoodLevel() > 6)
			.withPlaceableBlocks(throwawayCount(player));
		WorldTravelPolicy.Limit limit = WorldTravelPolicy.limit(minecraft.level);
		if (limit.closed()) return null;
		if (limit.bounds() != null) policy = policy.withTravelBounds(box(limit.bounds()));
		List<PlaceMemory.PreservedArea> areas = WorldPlacePreservation.areas(minecraft.level);
		if (areas == null) {
			// Protection data is unavailable: no terrain edits anywhere.
			policy = policy.withBreaking(false).withPlaceableBlocks(0);
		}
		else if (!areas.isEmpty()) {
			policy = policy.withProtectedAreas(areas.stream()
				.map(area -> new Box(area.x1(), area.y1(), area.z1(), area.x2(), area.y2(), area.z2())).toList());
		}
		// Doors stay usable when walking only.
		if (options.walkOnly()) policy = policy.withBreaking(false).withPlaceableBlocks(0).withSprint(false);
		return policy.withAvoidances(settings.avoidMobs() ? mobAvoidances(minecraft) : List.of());
	}

	public static int throwawayCount(LocalPlayer player) {
		int count = 0;
		var inventory = player.getInventory();
		// Blocks that navigation may not move into the hotbar cannot be placed, so they do not count.
		int slots = PathfindSettings.current().allowInventoryToolSwap() ? 36 : 9;
		for (int slot = 0; slot < slots; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (isThrowaway(stack)) count += stack.getCount();
		}
		return count;
	}

	public static boolean isThrowaway(ItemStack stack) {
		return !stack.isEmpty() && THROWAWAY_BLOCKS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
	}

	private static List<Avoidance> mobAvoidances(Minecraft minecraft) {
		List<Avoidance> avoidances = new ArrayList<>();
		var box = minecraft.player.getBoundingBox().inflate(MOB_SCAN_RADIUS);
		for (LivingEntity entity : minecraft.level.getEntitiesOfClass(LivingEntity.class, box,
			entity -> entity.isAlive() && entity instanceof Enemy
				&& !(entity instanceof EnderMan) && !(entity instanceof ZombifiedPiglin))) {
			avoidances.add(new Avoidance(entity.getX(), entity.getY(), entity.getZ(), MOB_AVOIDANCE_RADIUS, MOB_AVOIDANCE_COEFFICIENT));
		}
		return avoidances;
	}

	private static Box box(TravelBounds bounds) {
		return new Box(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}
}
