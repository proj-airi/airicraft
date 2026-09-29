package ai.moeru.airicraft.memory;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.BlockPos;
import java.util.*;

/** Server-thread observations, aggregated at the completed tick before asynchronous persistence. */
public final class InteractionLogbookRecorder {
	private static volatile java.util.function.BiConsumer<MinecraftServer,List<InteractionLogbook.Entry>> observer = (server, entries) -> { };
	public static void observe(java.util.function.BiConsumer<MinecraftServer,List<InteractionLogbook.Entry>> replacement) { observer = java.util.Objects.requireNonNull(replacement); }

	private static final Map<AbstractContainerMenu, Context> CONTEXTS = new WeakHashMap<>();
	private static final Map<MinecraftServer, LinkedHashMap<Key, InteractionLogbook.Entry>> PENDING = new IdentityHashMap<>();
	private record Context(BlockPos position, String type) {}
	private record Key(String actor, String dimension, BlockPos position, String entityUuid, String action, String itemId) {}
	public record Click(Map<String, Integer> containerBefore, String craftItem, int craftCountBefore) {}

	public static void opened(ServerPlayer player, BlockPos position, AbstractContainerMenu menu) {
		if (!(menu instanceof ChestMenu || menu instanceof AbstractFurnaceMenu
			|| menu instanceof CraftingMenu)) return;
		String type = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(position).getBlock()).toString();
		CONTEXTS.put(menu, new Context(position.immutable(), type));
		if (containerSize(menu) > 0) add(player, menu, "container_observed", "", 0, contents(menu));
	}

	public static Click beforeClick(ServerPlayer player, AbstractContainerMenu menu, int slotId) {
		observeEntityContainer(player, menu);
		String craftItem = slotId >= 0 && slotId < menu.slots.size() && menu.getSlot(slotId) instanceof ResultSlot
			? itemId(menu.getSlot(slotId).getItem()) : "";
		return new Click(contents(menu), craftItem, craftItem.isEmpty() ? 0 : carriedCount(player, menu, craftItem));
	}

	public static void afterClick(ServerPlayer player, AbstractContainerMenu menu, Click before) {
		Map<String, Integer> after = contents(menu);
		Set<String> items = new TreeSet<>(before.containerBefore().keySet());
		items.addAll(after.keySet());
		boolean changed = false;
		for (String item : items) {
			int delta = after.getOrDefault(item, 0) - before.containerBefore().getOrDefault(item, 0);
			if (delta == 0) continue;
			changed = true;
			// A signed change coalesces pickup/return clicks within this same server tick.
			add(player, menu, "container_delta", item, delta, Map.of());
		}
		if (changed) add(player, menu, "container_observed", "", 0, after);
		if (!before.craftItem().isEmpty()) {
			int gained = carriedCount(player, menu, before.craftItem()) - before.craftCountBefore();
			if (gained > 0) add(player, menu, "crafted", before.craftItem(), gained, Map.of());
		}
	}

	public static void dropped(ServerPlayer player, ItemStack stack) {
		if (!stack.isEmpty()) add(player, null, "dropped", itemId(stack), stack.getCount(), Map.of());
	}

	private static int carriedCount(ServerPlayer player, AbstractContainerMenu menu, String item) {
		int total = itemId(menu.getCarried()).equals(item) ? menu.getCarried().getCount() : 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (itemId(stack).equals(item)) total += stack.getCount();
		}
		return total;
	}

	private static int containerSize(AbstractContainerMenu menu) {
		if (menu instanceof ChestMenu chest) return chest.getRowCount() * 9;
		return menu instanceof AbstractFurnaceMenu ? 3 : 0;
	}

	private static Map<String, Integer> contents(AbstractContainerMenu menu) {
		Map<String, Integer> result = new TreeMap<>();
		for (int i = 0; i < containerSize(menu); i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (!stack.isEmpty()) result.merge(itemId(stack), stack.getCount(), Integer::sum);
		}
		return result;
	}

	private static String itemId(ItemStack stack) { return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }

	/** Only inspect the inventory the server has already opened for this player. */
	private static Entity containerEntity(AbstractContainerMenu menu) {
		return menu instanceof ChestMenu container && container.getContainer() instanceof Entity entity ? entity : null;
	}

	private static void observeEntityContainer(ServerPlayer player, AbstractContainerMenu menu) {
		Entity entity = containerEntity(menu);
		if (entity == null || CONTEXTS.containsKey(menu)) return;
		CONTEXTS.put(menu, new Context(entity.blockPosition(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()));
		add(player, menu, "container_observed", "", 0, contents(menu));
	}

	private static void add(ServerPlayer player, AbstractContainerMenu menu, String action, String item, int count, Map<String, Integer> contents) {
		Context context = menu == null ? null : CONTEXTS.get(menu);
		Entity entity = containerEntity(menu);
		BlockPos position = entity != null ? entity.blockPosition() : context == null ? player.blockPosition() : context.position();
		String entityUuid = entity == null ? "" : entity.getStringUUID();
		String dimension = player.level().dimension().location().toString();
		Key key = new Key(player.getStringUUID(), dimension, position, entityUuid, action, item);
		var pending = PENDING.computeIfAbsent(player.getServer(), ignored -> new LinkedHashMap<>());
		InteractionLogbook.Entry previous = pending.get(key);
		int amount = action.equals("container_observed") ? 0 : count + (previous == null ? 0 : previous.count());
		pending.put(key, new InteractionLogbook.Entry(System.currentTimeMillis(), player.level().getGameTime(),
			player.getStringUUID(), dimension, position.getX(), position.getY(), position.getZ(), action, item, amount,
			context == null ? "" : context.type(), contents, entityUuid));
	}

	public static void flushTick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers())
			observeEntityContainer(player, player.containerMenu);
		var entries = PENDING.remove(server);
		if (entries == null) return;
		List<InteractionLogbook.Entry> batch = new ArrayList<>();
		for (var entry : entries.values()) {
			if (entry.action().equals("container_delta")) {
				if (entry.count() == 0) continue;
				batch.add(new InteractionLogbook.Entry(entry.timestampMs(), entry.worldTick(), entry.actor(), entry.dimension(),
					entry.x(), entry.y(), entry.z(), entry.count() > 0 ? "container_put" : "container_take",
					entry.itemId(), Math.abs(entry.count()), entry.containerType(), Map.of(), entry.containerEntityUuid()));
			} else batch.add(entry);
		}
		InteractionLogbook.record(server.getWorldPath(LevelResource.ROOT), batch);
		observer.accept(server, List.copyOf(batch));
	}
}
