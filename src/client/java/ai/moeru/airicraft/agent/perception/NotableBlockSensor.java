package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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
	private Set<net.minecraft.block.Block> interestingBlocks = Set.of();
	private java.util.List<TagKey<net.minecraft.block.Block>> interestingTags = java.util.List.of();

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
		var client = context.client();
		if (!Scopes.ready(client)) return;
		Set<String> wanted = interests.get();
		if (wanted.isEmpty()) return;
		resolve(wanted);
		var world = client.world;
		var player = client.player;
		Vec3d eye = player.getEyePos();
		var pos = new BlockPos.Mutable();
		var budgets = context.budgets();
		NotableBlockScanner.Blocks blocks = new NotableBlockScanner.Blocks() {
			@Override public String blockId(int x, int y, int z) {
				BlockState state = world.getBlockState(pos.set(x, y, z));
				return state.isAir() ? null : interesting(state) ? Registries.BLOCK.getId(state.getBlock()).toString() : "";
			}

			@Override public boolean opaque(int x, int y, int z) {
				return world.getBlockState(pos.set(x, y, z)).isOpaque();
			}
		};
		NotableBlockScanner.Sight sight = (eyeX, eyeY, eyeZ, x, y, z, face) -> {
			var target = new BlockPos(x, y, z);
			// Aim just inside the face so the ray ends on this block, not its neighbour.
			var aim = new Vec3d(x + .5 + face.dx * .49, y + .5 + face.dy * .49, z + .5 + face.dz * .49);
			BlockHitResult hit = world.raycast(new RaycastContext(eye, aim, RaycastContext.ShapeType.VISUAL,
				RaycastContext.FluidHandling.NONE, player));
			return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
		};
		for (var candidate : scanner.scan(context.tick(), Scopes.of(client), eye.x, eye.y, eye.z, budgets.radius(),
			budgets.positionsPerTick(), budgets.raycastsPerTick(), id -> !id.isEmpty(), blocks, sight)) {
			sink.candidate(candidate);
		}
	}

	private boolean interesting(BlockState state) {
		if (interestingBlocks.contains(state.getBlock())) return true;
		for (var tag : interestingTags) if (state.isIn(tag)) return true;
		return false;
	}

	private void resolve(Set<String> wanted) {
		if (wanted.equals(resolvedFor)) return;
		var blocks = new java.util.HashSet<net.minecraft.block.Block>();
		var tags = new java.util.ArrayList<TagKey<net.minecraft.block.Block>>();
		for (String entry : wanted) {
			Identifier id = Identifier.tryParse(entry.startsWith("#") ? entry.substring(1) : entry);
			if (id == null) continue;
			if (entry.startsWith("#")) tags.add(TagKey.of(RegistryKeys.BLOCK, id));
			else Registries.BLOCK.getOptionalValue(id).ifPresent(blocks::add);
		}
		interestingBlocks = Set.copyOf(blocks);
		interestingTags = java.util.List.copyOf(tags);
		resolvedFor = Set.copyOf(wanted);
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		scanner.clear();
	}
}
