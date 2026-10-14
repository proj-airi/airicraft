package ai.moeru.airicraft.agent.modded;

import ai.moeru.airicraft.modded.ItemGroups;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Wires {@link ItemGroups} to the live item registry and {@code config/airicraft/item-groups.json}.
 * The file maps a group key (for example {@code throwaway_blocks}) to extra item ids.
 */
public final class ClientItemGroups {
	private static final Logger LOGGER = Logger.getLogger(ClientItemGroups.class.getName());

	private ClientItemGroups() {
	}

	private static final java.util.concurrent.atomic.AtomicInteger TAG_RELOADS = new java.util.concurrent.atomic.AtomicInteger();
	private static final java.util.concurrent.atomic.AtomicBoolean LISTENING = new java.util.concurrent.atomic.AtomicBoolean();

	/** Installs the live tag source and the user's override file; safe to call again on reload. */
	public static void install() {
		if (LISTENING.compareAndSet(false, true)) {
			// Data-pack reloads and server tag syncs rebind tags without replacing the level.
			CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> TAG_RELOADS.incrementAndGet());
		}
		ItemGroups.install(new RegistryTags(), loadExtras(
			FabricLoader.getInstance().getConfigDir().resolve("airicraft").resolve("item-groups.json")));
	}

	static Map<String, List<String>> loadExtras(Path file) {
		if (!Files.isRegularFile(file)) {
			return Map.of();
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file));
			if (!root.isJsonObject()) {
				return Map.of();
			}
			Map<String, List<String>> extras = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
				if (entry.getValue().isJsonArray()) {
					List<String> ids = entry.getValue().getAsJsonArray().asList().stream()
						.filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString).toList();
					extras.put(entry.getKey(), ids);
				}
			}
			return extras;
		}
		catch (IOException | RuntimeException exception) {
			LOGGER.warning("Ignoring unreadable " + file + ": " + exception.getMessage());
			return Map.of();
		}
	}

	/** Item tags as synced to the client; the generation follows the loaded level and the tag binding. */
	private static final class RegistryTags implements ItemGroups.TagSource {
		@Override
		public Set<String> members(String tagId) {
			ResourceLocation location = ResourceLocation.tryParse(tagId);
			if (location == null) {
				return Set.of();
			}
			Set<String> ids = new LinkedHashSet<>();
			BuiltInRegistries.ITEM.get(TagKey.<Item>create(BuiltInRegistries.ITEM.key(), location))
				.ifPresent(tag -> tag.forEach(holder -> holder.unwrapKey()
					.ifPresent(key -> ids.add(key.location().toString()))));
			return ids;
		}

		@Override
		public Object generation() {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.level == null) {
				return "none";
			}
			return List.of(System.identityHashCode(minecraft.level), TAG_RELOADS.get());
		}
	}
}
