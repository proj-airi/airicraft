package ai.moeru.airicraft.agent.actions;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minecraft shell that captures loaded loot-table resources and immutable registry metadata.
 */
public final class MinecraftBlockAcquisitionLoader {
	private MinecraftBlockAcquisitionLoader() {
	}

	public static BlockAcquisitionIndex load(ResourceManager resourceManager) {
		List<ToolCandidate> toolCandidates = toolCandidates();
		ArrayList<BlockLootTableSource> sources = new ArrayList<>();
		BuiltInRegistries.BLOCK.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(key -> key.location().toString())))
			.forEach(entry -> captureBlockSource(resourceManager, entry.getValue(), entry.getKey().location().toString(), toolCandidates)
				.ifPresent(sources::add));
		return BlockLootTableCompiler.compile(sources, itemTags());
	}

	private static Optional<BlockLootTableSource> captureBlockSource(
		ResourceManager resourceManager,
		Block block,
		String blockId,
		List<ToolCandidate> toolCandidates
	) {
		return block.getLootTable().flatMap(lootTableKey -> {
			ResourceLocation lootTableId = lootTableKey.location();
			ResourceLocation resourceId = ResourceLocation.fromNamespaceAndPath(
				lootTableId.getNamespace(),
				"loot_table/" + lootTableId.getPath() + ".json"
			);
			Optional<String> json = readResource(resourceManager, resourceId);
			if (json.isEmpty()) {
				return Optional.empty();
			}
			BlockState state = block.defaultBlockState();
			float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			int emptyHandBreakTicks = BlockMiningTime.baselineBreakTicks(
				hardness,
				1.0,
				!state.requiresCorrectToolForDrops()
			);
			LinkedHashMap<String, Integer> breakTicksByTool = new LinkedHashMap<>();
			for (ToolCandidate candidate : toolCandidates) {
				breakTicksByTool.put(candidate.itemId(), BlockMiningTime.baselineBreakTicks(
					hardness,
					candidate.stack().getDestroySpeed(state),
					!state.requiresCorrectToolForDrops() || candidate.stack().isCorrectToolForDrops(state)
				));
			}
			List<String> suitableTools = state.requiresCorrectToolForDrops()
				? toolCandidates.stream()
					.filter(candidate -> candidate.stack().isCorrectToolForDrops(state))
					.map(ToolCandidate::itemId)
					.toList()
				: List.of();
			return Optional.of(new BlockLootTableSource(
				blockId,
				lootTableId.toString(),
				json.get(),
				state.requiresCorrectToolForDrops(),
				suitableTools,
				emptyHandBreakTicks,
				breakTicksByTool
			));
		});
	}

	private static List<ToolCandidate> toolCandidates() {
		return BuiltInRegistries.ITEM.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(key -> key.location().toString())))
			.map(entry -> new ToolCandidate(entry.getKey().location().toString(), new ItemStack(entry.getValue())))
			.filter(candidate -> candidate.stack().get(DataComponents.TOOL) != null)
			.toList();
	}

	private static Map<String, List<String>> itemTags() {
		LinkedHashMap<String, List<String>> tags = new LinkedHashMap<>();
		BuiltInRegistries.ITEM.getTags()
			.sorted(Comparator.comparing(named -> named.key().location().toString()))
			.forEach(named -> tags.put(
				named.key().location().toString(),
				named.stream()
					.map(holder -> BuiltInRegistries.ITEM.getKey(holder.value()).toString())
					.distinct()
					.sorted()
					.toList()
			));
		return Collections.unmodifiableMap(tags);
	}

	private static Optional<String> readResource(ResourceManager resourceManager, ResourceLocation resourceId) {
		if (resourceManager != null) {
			Optional<Resource> loaded = resourceManager.getResource(resourceId);
			if (loaded.isPresent()) {
				try (InputStream input = loaded.get().open()) {
					return Optional.of(new String(input.readAllBytes(), StandardCharsets.UTF_8));
				}
				catch (IOException ignored) {
					return Optional.empty();
				}
			}
		}
		String classpathName = "data/" + resourceId.getNamespace() + "/" + resourceId.getPath();
		ClassLoader classLoader = MinecraftBlockAcquisitionLoader.class.getClassLoader();
		try (InputStream input = classLoader.getResourceAsStream(classpathName)) {
			return input == null
				? Optional.empty()
				: Optional.of(new String(input.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException ignored) {
			return Optional.empty();
		}
	}

	private record ToolCandidate(String itemId, ItemStack stack) {
	}
}
