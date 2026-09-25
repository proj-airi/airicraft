package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.agent.memory.PlaceMemory;
import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;
import ai.moeru.airicraft.agent.spatial.TravelBounds;
import ai.moeru.airicraft.agent.spatial.WorldTravelPolicy;
import ai.moeru.airicraft.navigation.Avoidance;
import ai.moeru.airicraft.navigation.Box;
import ai.moeru.airicraft.navigation.MovementPolicy;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Builds the movement policy for one navigation request from the live world and inventory. */
public final class NavigationPolicies {
	/** Blocks navigation may place for bridges and pillars. */
	public static final Set<String> THROWAWAY_BLOCKS = Set.of("minecraft:cobblestone", "minecraft:cobbled_deepslate",
		"minecraft:dirt", "minecraft:netherrack", "minecraft:stone", "minecraft:andesite", "minecraft:diorite",
		"minecraft:granite", "minecraft:tuff", "minecraft:blackstone", "minecraft:end_stone", "minecraft:deepslate");
	// Matches the Baritone mob avoidance profile Airicraft used.
	private static final double MOB_AVOIDANCE_RADIUS = 16;
	private static final double MOB_AVOIDANCE_COEFFICIENT = 4.0;
	private static final double MOB_SCAN_RADIUS = 40;
	/**
	 * Set while an owner needs walking-only travel, such as leading animals. It mirrors the Baritone
	 * settings those owners turn off, until policies are passed per request.
	 */
	private static volatile boolean walkOnly;

	private NavigationPolicies() {
	}

	public static void setWalkOnly(boolean restricted) {
		walkOnly = restricted;
	}

	/** The policy for the current player, or null when the travel policy forbids moving at all. */
	public static MovementPolicy forPlayer(MinecraftClient client, double waterPenalty) {
		ClientPlayerEntity player = client.player;
		MovementPolicy policy = MovementPolicy.defaults()
			.withWaterPenalty(waterPenalty)
			.withSprint(player.getHungerManager().getFoodLevel() > 6)
			.withPlaceableBlocks(throwawayCount(player));
		WorldTravelPolicy.Limit limit = WorldTravelPolicy.limit(client.world);
		if (limit.closed()) return null;
		if (limit.bounds() != null) policy = policy.withTravelBounds(box(limit.bounds()));
		List<PlaceMemory.PreservedArea> areas = WorldPlacePreservation.areas(client.world);
		if (areas == null) {
			// Protection data is unavailable: no terrain edits anywhere.
			policy = policy.withBreaking(false).withPlaceableBlocks(0);
		}
		else if (!areas.isEmpty()) {
			policy = policy.withProtectedAreas(areas.stream()
				.map(area -> new Box(area.x1(), area.y1(), area.z1(), area.x2(), area.y2(), area.z2())).toList());
		}
		// Doors stay usable: the Baritone settings this mirrors never disabled them.
		if (walkOnly) policy = policy.withBreaking(false).withPlaceableBlocks(0).withSprint(false);
		return policy.withAvoidances(mobAvoidances(client));
	}

	public static int throwawayCount(ClientPlayerEntity player) {
		int count = 0;
		var inventory = player.getInventory();
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (isThrowaway(stack)) count += stack.getCount();
		}
		return count;
	}

	public static boolean isThrowaway(ItemStack stack) {
		return !stack.isEmpty() && THROWAWAY_BLOCKS.contains(Registries.ITEM.getId(stack.getItem()).toString());
	}

	private static List<Avoidance> mobAvoidances(MinecraftClient client) {
		List<Avoidance> avoidances = new ArrayList<>();
		var box = client.player.getBoundingBox().expand(MOB_SCAN_RADIUS);
		for (LivingEntity entity : client.world.getEntitiesByClass(LivingEntity.class, box,
			entity -> entity.isAlive() && entity instanceof Monster
				&& !(entity instanceof EndermanEntity) && !(entity instanceof ZombifiedPiglinEntity))) {
			avoidances.add(new Avoidance(entity.getX(), entity.getY(), entity.getZ(), MOB_AVOIDANCE_RADIUS, MOB_AVOIDANCE_COEFFICIENT));
		}
		return avoidances;
	}

	private static Box box(TravelBounds bounds) {
		return new Box(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}
}
