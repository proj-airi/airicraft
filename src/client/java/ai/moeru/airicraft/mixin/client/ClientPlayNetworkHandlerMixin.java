package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(ClientPacketListener.class)
public class ClientPlayNetworkHandlerMixin {
	@Inject(method = "handleDamageEvent", at = @At("TAIL"))
	private void airicraft$onEntityDamage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread() || minecraft.player == null || minecraft.level == null) {
			return;
		}
		if (packet.entityId() != minecraft.player.getId()) {
			return;
		}
		AiricraftClient.runtimeController().onPlayerDamageObserved(packet.getSource(minecraft.level));
	}

	@WrapOperation(
		method = "handleSetHealth",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;hurtTo(F)V")
	)
	private void airicraft$reportHealthUpdate(LocalPlayer player, float health, Operation<Void> original) {
		LocalPlayer current = currentPlayer();
		float healthBefore = current == null ? 0.0f : current.getHealth();
		boolean healthInitializedBefore = current != null && current.flashOnSetHealth;
		original.call(player, health);
		if (current == null || currentPlayer() == null) {
			return;
		}
		AiricraftClient.runtimeController().onPlayerHealthUpdated(
			healthInitializedBefore,
			healthBefore,
			health
		);
	}

	@Inject(method = "handleRespawn", at = @At("TAIL"))
	private void airicraft$onPlayerRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
		AiricraftClient.runtimeController().onPlayerRespawned();
	}

	@Inject(
		method = "handleTakeItemEntity",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
			shift = At.Shift.AFTER
		)
	)
	private void airicraft$onItemPickupAnimation(
		ClientboundTakeItemEntityPacket packet,
		CallbackInfo ci,
		@Share("pickupEntityUuid") LocalRef<UUID> pickupEntityUuid,
		@Share("pickupPreStackCount") LocalIntRef pickupPreStackCount,
		@Share("pickupItemId") LocalRef<String> pickupItemId
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread() || minecraft.player == null || minecraft.level == null) {
			return;
		}
		if (!(minecraft.level.getEntity(packet.getItemId()) instanceof ItemEntity itemEntity)) {
			return;
		}

		ItemStack stack = itemEntity.getItem();
		if (stack.isEmpty()) {
			return;
		}
		pickupEntityUuid.set(itemEntity.getUUID());
		pickupPreStackCount.set(stack.getCount());
		pickupItemId.set(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
	}

	@Inject(method = "handleTakeItemEntity", at = @At("TAIL"))
	private void airicraft$reportItemPickupAnimation(
		ClientboundTakeItemEntityPacket packet,
		CallbackInfo ci,
		@Share("pickupEntityUuid") LocalRef<UUID> pickupEntityUuid,
		@Share("pickupPreStackCount") LocalIntRef pickupPreStackCount,
		@Share("pickupItemId") LocalRef<String> pickupItemId
	) {
		String itemId = pickupItemId.get();
		if (itemId == null) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread() || minecraft.player == null || minecraft.level == null) {
			return;
		}
		int preStackCount = pickupPreStackCount.get();
		ItemEntity itemEntity = minecraft.level.getEntity(packet.getItemId()) instanceof ItemEntity value ? value : null;
		int postStackCount = itemEntity == null ? 0 : itemEntity.getItem().getCount();
		int pickupDelta = itemEntity == null
			? Math.clamp(packet.getAmount(), 1, preStackCount)
			: Math.max(0, preStackCount - postStackCount);
		if (pickupDelta <= 0) {
			return;
		}
		Player collector = minecraft.level.getEntity(packet.getPlayerId()) instanceof Player player
			? player
			: null;
		AiricraftClient.runtimeController().onPlayerItemPickupObserved(
			packet.getItemId(),
			pickupEntityUuid.get(),
			itemId,
			pickupDelta,
			preStackCount,
			collector == null ? null : collector.getUUID(),
			UUID.randomUUID()
		);
		if (packet.getPlayerId() == minecraft.player.getId()) {
			AiricraftClient.runtimeController().onPlayerPickedUpItem(itemId, pickupDelta);
		}
	}

	@Inject(method = "handlePlayerInfoUpdate", at = @At("TAIL"))
	private void airicraft$onPlayerList(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo ci) {
		for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.newEntries()) {
			AiricraftClient.runtimeController().onPlayerJoinedGame(entry.profileId(), entry.profile().getName());
		}
	}

	@Inject(method = "handlePlayerInfoRemove", at = @At("TAIL"))
	private void airicraft$onPlayerRemove(ClientboundPlayerInfoRemovePacket packet, CallbackInfo ci) {
		for (java.util.UUID profileId : packet.profileIds()) {
			AiricraftClient.runtimeController().onPlayerLeftGame(profileId);
		}
	}

	@Unique
	private static LocalPlayer currentPlayer() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread() || minecraft.player == null || minecraft.level == null) {
			return null;
		}
		return minecraft.player;
	}
}
