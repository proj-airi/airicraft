package ai.moeru.airicraft.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Input;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ClientTickPlayerSnapshotFactory {
	private ClientTickPlayerSnapshotFactory() {
	}

	static ClientTickPlayerSnapshot capture(Minecraft minecraft, LocalPlayer player) {
		var position = player.position();
		var velocity = player.getDeltaMovement();
		var bounds = player.getBoundingBox();
		var abilities = player.getAbilities();
		Input input = player.input == null || player.input.keyPresses == null
			? Input.EMPTY
			: player.input.keyPresses;
		return new ClientTickPlayerSnapshot(
			player.getId(),
			player.getStringUUID(),
			player.getName().getString(),
			BuiltInRegistries.ENTITY_TYPE.getKey(player.getType()).toString(),
			minecraft.gameMode == null ? null : minecraft.gameMode.getPlayerMode().getSerializedName(),
			new ClientTickPlayerSnapshot.PositionSnapshot(
				position.x,
				position.y,
				position.z,
				player.getBlockX(),
				player.getBlockY(),
				player.getBlockZ()
			),
			new ClientTickPlayerSnapshot.RotationSnapshot(
				player.getYRot(),
				player.getXRot(),
				player.getYHeadRot(),
				player.getVisualRotationYInDegrees()
			),
			new ClientTickPlayerSnapshot.VectorSnapshot(velocity.x, velocity.y, velocity.z),
			new ClientTickPlayerSnapshot.BoundsSnapshot(
				bounds.minX,
				bounds.minY,
				bounds.minZ,
				bounds.maxX,
				bounds.maxY,
				bounds.maxZ
			),
			new ClientTickPlayerSnapshot.MovementSnapshot(
				player.getPose().name().toLowerCase(Locale.ROOT),
				player.onGround(),
				player.horizontalCollision,
				player.verticalCollision,
				player.isSprinting(),
				player.isShiftKeyDown(),
				player.isSwimming(),
				player.isVisuallyCrawling(),
				player.isFallFlying(),
				player.onClimbable(),
				player.isInWater(),
				player.isUnderWater(),
				player.isInLava(),
				player.fallDistance,
				player.isUsingItem(),
				player.getTicksUsingItem(),
				player.getUseItemRemainingTicks()
			),
			new ClientTickPlayerSnapshot.VitalsSnapshot(
				player.isAlive(),
				player.isRemoved(),
				player.getHealth(),
				player.getMaxHealth(),
				player.getAbsorptionAmount(),
				player.getArmorValue(),
				player.getAirSupply(),
				player.getMaxAirSupply(),
				player.getRemainingFireTicks(),
				player.getTicksFrozen(),
				player.hurtTime,
				player.deathTime
			),
			new ClientTickPlayerSnapshot.HungerSnapshot(
				player.getFoodData().getFoodLevel(),
				player.getFoodData().getSaturationLevel()
			),
			new ClientTickPlayerSnapshot.ExperienceSnapshot(
				player.experienceLevel,
				player.totalExperience,
				player.experienceProgress
			),
			new ClientTickPlayerSnapshot.AbilitiesSnapshot(
				abilities.invulnerable,
				abilities.flying,
				abilities.mayfly,
				abilities.instabuild,
				abilities.mayBuild,
				abilities.getFlyingSpeed(),
				abilities.getWalkingSpeed()
			),
			new ClientTickPlayerSnapshot.InputSnapshot(
				input.forward(),
				input.backward(),
				input.left(),
				input.right(),
				input.jump(),
				input.shift(),
				input.sprint()
			),
			player.getInventory().getContainerSize(),
			player.getInventory().getSelectedSlot(),
			inventory(player),
			equipment(player),
			statusEffects(player.getActiveEffects()),
			attributes(player.getAttributes().getSyncableAttributes())
		);
	}

	static ClientTickPlayerSnapshot.ItemStackSnapshot itemStack(int slot, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		return new ClientTickPlayerSnapshot.ItemStackSnapshot(
			slot,
			BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
			stack.getHoverName().getString(),
			stack.getCount(),
			stack.getMaxStackSize(),
			stack.isDamageableItem(),
			stack.getDamageValue(),
			stack.getMaxDamage(),
			stack.hasFoil()
		);
	}

	static List<ClientTickPlayerSnapshot.StatusEffectSnapshot> statusEffects(
		Iterable<MobEffectInstance> effects
	) {
		List<ClientTickPlayerSnapshot.StatusEffectSnapshot> snapshots = new ArrayList<>();
		for (MobEffectInstance effect : effects) {
			snapshots.add(new ClientTickPlayerSnapshot.StatusEffectSnapshot(
				BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString(),
				effect.getAmplifier(),
				effect.getDuration(),
				effect.isAmbient(),
				effect.isVisible(),
				effect.showIcon()
			));
		}
		snapshots.sort(Comparator.comparing(ClientTickPlayerSnapshot.StatusEffectSnapshot::effectId));
		return List.copyOf(snapshots);
	}

	static List<ClientTickPlayerSnapshot.AttributeSnapshot> attributes(
		Iterable<AttributeInstance> attributes
	) {
		List<ClientTickPlayerSnapshot.AttributeSnapshot> snapshots = new ArrayList<>();
		for (AttributeInstance attribute : attributes) {
			snapshots.add(new ClientTickPlayerSnapshot.AttributeSnapshot(
				BuiltInRegistries.ATTRIBUTE.getKey(attribute.getAttribute().value()).toString(),
				attribute.getBaseValue(),
				attribute.getValue()
			));
		}
		snapshots.sort(Comparator.comparing(ClientTickPlayerSnapshot.AttributeSnapshot::attributeId));
		return List.copyOf(snapshots);
	}

	private static List<ClientTickPlayerSnapshot.ItemStackSnapshot> inventory(LocalPlayer player) {
		List<ClientTickPlayerSnapshot.ItemStackSnapshot> snapshots = new ArrayList<>();
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ClientTickPlayerSnapshot.ItemStackSnapshot snapshot = itemStack(slot, player.getInventory().getItem(slot));
			if (snapshot != null) {
				snapshots.add(snapshot);
			}
		}
		return List.copyOf(snapshots);
	}

	static Map<String, ClientTickPlayerSnapshot.ItemStackSnapshot> equipment(LivingEntity player) {
		Map<String, ClientTickPlayerSnapshot.ItemStackSnapshot> snapshots = new LinkedHashMap<>();
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ClientTickPlayerSnapshot.ItemStackSnapshot snapshot = itemStack(-1, player.getItemBySlot(slot));
			if (snapshot != null) {
				snapshots.put(slot.getName(), snapshot);
			}
		}
		return snapshots;
	}
}
