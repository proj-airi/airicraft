package ai.moeru.airicraft.agent.modded;

import ai.moeru.airicraft.modded.ItemGroups;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

	/** Installs the live tag source and the user's override file; safe to call again on reload. */
	public static void install() {
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

	/** Item tags as synced to the client; the generation follows the loaded level, which tags are bound to. */
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
			return minecraft == null || minecraft.level == null ? "none" : System.identityHashCode(minecraft.level);
		}
	}
}
