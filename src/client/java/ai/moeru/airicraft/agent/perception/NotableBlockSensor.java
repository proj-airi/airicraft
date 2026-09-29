package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

/**
 * Notable blocks with an exposed face in line of sight (spec section 5). The salience module's {@code interests}
 * name the block ids (or {@code #tags}) worth scanning; everything else is never looked at.
 */
public final class NotableBlockSensor implements Sensor {
	public static final String ID = "notable_blocks";

	private final NotableBlockScanner scanner = new NotableBlockScanner();
	private final Supplier<Set<String>> interests;
	/** Interests resolved to block objects and tags once per change, so the scan compares objects, not id strings. */
	private Set<String> resolvedFor = Set.of();
	private Set<net.minecraft.world.level.block.Block> interestingBlocks = Set.of();
	private java.util.List<TagKey<net.minecraft.world.level.block.Block>> interestingTags = java.util.List.of();

	public NotableBlockSensor(Supplier<Set<String>> interests) {
		this.interests = interests;
	}

	@Override public String id() {
		return ID;
	}

	@Override public EnumSet<LifecycleBoundary> boundaries() {
		return EnumSet.of(LifecycleBoundary.WORLD_LEFT, LifecycleBoundary.SHUTDOWN);
	}

	@Override public void sample(SensorContext context, PerceptSink sink) {
		var minecraft = context.client();
		if (!Scopes.ready(minecraft)) return;
		Set<String> wanted = interests.get();
		if (wanted.isEmpty()) return;
		resolve(wanted);
		var level = minecraft.level;
		var player = minecraft.player;
		Vec3 eye = player.getEyePosition();
		var pos = new BlockPos.MutableBlockPos();
		var budgets = context.budgets();
		NotableBlockScanner.Blocks blocks = new NotableBlockScanner.Blocks() {
			@Override public String blockId(int x, int y, int z) {
				BlockState state = level.getBlockState(pos.set(x, y, z));
				return state.isAir() ? null : interesting(state) ? BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString() : "";
			}

			@Override public boolean opaque(int x, int y, int z) {
				return level.getBlockState(pos.set(x, y, z)).canOcclude();
			}
		};
		NotableBlockScanner.Sight sight = (eyeX, eyeY, eyeZ, x, y, z, face) -> {
			var target = new BlockPos(x, y, z);
			// Aim just inside the face so the ray ends on this block, not its neighbour.
			var aim = new Vec3(x + .5 + face.dx * .49, y + .5 + face.dy * .49, z + .5 + face.dz * .49);
			BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.VISUAL,
				ClipContext.Fluid.NONE, player));
			return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
		};
		for (var candidate : scanner.scan(context.tick(), Scopes.of(minecraft), eye.x, eye.y, eye.z, budgets.radius(),
			budgets.positionsPerTick(), budgets.raycastsPerTick(), id -> !id.isEmpty(), blocks, sight)) {
			sink.candidate(candidate);
		}
	}

	private boolean interesting(BlockState state) {
		if (interestingBlocks.contains(state.getBlock())) return true;
		for (var tag : interestingTags) if (state.is(tag)) return true;
		return false;
	}

	private void resolve(Set<String> wanted) {
		if (wanted.equals(resolvedFor)) return;
		var blocks = new java.util.HashSet<net.minecraft.world.level.block.Block>();
		var tags = new java.util.ArrayList<TagKey<net.minecraft.world.level.block.Block>>();
		for (String entry : wanted) {
			ResourceLocation id = ResourceLocation.tryParse(entry.startsWith("#") ? entry.substring(1) : entry);
			if (id == null) continue;
			if (entry.startsWith("#")) tags.add(TagKey.create(Registries.BLOCK, id));
			else BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blocks::add);
		}
		interestingBlocks = Set.copyOf(blocks);
		interestingTags = java.util.List.copyOf(tags);
		resolvedFor = Set.copyOf(wanted);
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		scanner.clear();
	}
}
