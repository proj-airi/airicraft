package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
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

import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mixin(ClientPacketListener.class)
public class ClientPlayNetworkHandlerMixin {
	@Unique
	private float airicraft$healthBeforeUpdate;

	@Unique
	private boolean airicraft$healthInitializedBeforeUpdate;
	@Unique
	private final Map<ClientboundTakeItemEntityPacket, UUID> airicraft$pickupObservationIds = new IdentityHashMap<>();
	@Unique
	private final Set<UUID> airicraft$reportedPickupObservationIds = new HashSet<>();
	@Unique
	private ClientboundTakeItemEntityPacket airicraft$pickupPacket;
	@Unique
	private UUID airicraft$pickupEntityUuid;
	@Unique
	private int airicraft$pickupPreStackCount;
	@Unique
	private String airicraft$pickupItemId;

	@Inject(method = "handleDamageEvent", at = @At("TAIL"))
	private void airicraft$onEntityDamage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || !minecraft.isSameThread() || minecraft.player == null || minecraft.level == null) {
			return;
		}
		if (packet.entityId() != minecraft.player.getId()) {
			return;
		}
		AiricraftClient.runtimeController().onPlayerDamageObserved(packet.getSource(minecraft.level));
	}

	@Inject(
		method = "handleSetHealth",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;hurtTo(F)V")
	)
	private void airicraft$captureHealthUpdate(ClientboundSetHealthPacket packet, CallbackInfo ci) {
		LocalPlayer player = currentPlayer();
		if (player == null) {
			return;
		}
		airicraft$healthBeforeUpdate = player.getHealth();
		airicraft$healthInitializedBeforeUpdate = player.flashOnSetHealth;
	}

	@Inject(
		method = "handleSetHealth",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;hurtTo(F)V",
			shift = At.Shift.AFTER
		)
	)
	private void airicraft$reportHealthUpdate(ClientboundSetHealthPacket packet, CallbackInfo ci) {
		if (currentPlayer() == null) {
			return;
		}
		AiricraftClient.runtimeController().onPlayerHealthUpdated(
			airicraft$healthInitializedBeforeUpdate,
			airicraft$healthBeforeUpdate,
			packet.getHealth()
		);
	}

	@Inject(method = "handleRespawn", at = @At("TAIL"))
	private void airicraft$onPlayerRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
		AiricraftClient.runtimeController().onPlayerRespawned();
	}

	@Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
	private void airicraft$onItemPickupAnimation(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
		airicraft$pickupPacket = packet;
		airicraft$pickupObservationIds.putIfAbsent(packet, UUID.randomUUID());
		airicraft$pickupEntityUuid = null;
		airicraft$pickupPreStackCount = -1;
		airicraft$pickupItemId = null;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || !minecraft.isSameThread() || minecraft.player == null || minecraft.level == null) {
			return;
		}
		if (!(minecraft.level.getEntity(packet.getItemId()) instanceof ItemEntity itemEntity)) {
			return;
		}

		ItemStack stack = itemEntity.getItem();
		if (stack == null || stack.isEmpty()) {
			return;
		}
		airicraft$pickupEntityUuid = itemEntity.getUUID();
		airicraft$pickupPreStackCount = stack.getCount();
		airicraft$pickupItemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	@Inject(method = "handleTakeItemEntity", at = @At("TAIL"))
	private void airicraft$reportItemPickupAnimation(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
		if (packet != airicraft$pickupPacket || airicraft$pickupPreStackCount < 0) {
			return;
		}
		UUID observationId = airicraft$pickupObservationIds.get(packet);
		if (observationId == null || !airicraft$reportedPickupObservationIds.add(observationId)) {
			return;
		}
		airicraft$pickupPacket = null;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || !minecraft.isSameThread() || minecraft.player == null || minecraft.level == null) {
			return;
		}
		ItemEntity itemEntity = minecraft.level.getEntity(packet.getItemId()) instanceof ItemEntity value ? value : null;
		int postStackCount = itemEntity == null ? 0 : itemEntity.getItem().getCount();
		int pickupDelta = itemEntity == null
			? Math.min(Math.max(1, packet.getAmount()), airicraft$pickupPreStackCount)
			: Math.max(0, airicraft$pickupPreStackCount - postStackCount);
		if (pickupDelta <= 0) {
			return;
		}
		String itemId = airicraft$pickupItemId;
		if (itemId == null) {
			return;
		}
		Player collector = minecraft.level.getEntity(packet.getPlayerId()) instanceof Player player
			? player
			: null;
		AiricraftClient.runtimeController().onPlayerItemPickupObserved(
			packet.getItemId(),
			airicraft$pickupEntityUuid,
			itemId,
			pickupDelta,
			airicraft$pickupPreStackCount,
			collector == null ? null : collector.getUUID(),
			observationId
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
		if (minecraft == null || !minecraft.isSameThread() || minecraft.player == null || minecraft.level == null) {
			return null;
		}
		return minecraft.player;
	}
}
