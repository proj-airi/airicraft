package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
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
	private final Map<String, TagKey<net.minecraft.block.Block>> tags = new HashMap<>();

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
		var world = client.world;
		var player = client.player;
		Vec3d eye = player.getEyePos();
		var pos = new BlockPos.Mutable();
		var budgets = context.budgets();
		NotableBlockScanner.Blocks blocks = new NotableBlockScanner.Blocks() {
			@Override public String blockId(int x, int y, int z) {
				BlockState state = world.getBlockState(pos.set(x, y, z));
				return state.isAir() ? null : interesting(state, wanted) ? Registries.BLOCK.getId(state.getBlock()).toString() : "";
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

	private boolean interesting(BlockState state, Set<String> wanted) {
		if (wanted.contains(Registries.BLOCK.getId(state.getBlock()).toString())) return true;
		for (String entry : wanted) {
			if (!entry.startsWith("#")) continue;
			var tag = tags.computeIfAbsent(entry, key -> {
				Identifier id = Identifier.tryParse(key.substring(1));
				return id == null ? null : TagKey.of(RegistryKeys.BLOCK, id);
			});
			if (tag != null && state.isIn(tag)) return true;
		}
		return false;
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		scanner.clear();
	}
}
